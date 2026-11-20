/*
 * vdin_stream —— V4L2 常开流采集压测（NV21）
 *
 * 用法：
 *   vdin_stream /dev/video12 480 270 5      # 采 5 秒，打印帧率
 *
 * 流程：open(O_RDWR|O_NONBLOCK) → S_FMT(NV21) → REQBUFS(4,MMAP)
 *      → QUERYBUF+mmap → QBUF → STREAMON → select+DQBUF 循环 → QBUF 归还 → STREAMOFF
 *
 * 注意：会真占住设备。用完务必 STREAMOFF + close。
 * 出事后回退：/data/local/tmp/restore-service.sh
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <errno.h>
#include <unistd.h>
#include <time.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/select.h>
#include <linux/videodev2.h>

#define NBUF 4

static double now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

int main(int argc, char **argv) {
    if (argc < 5) { printf("用法: %s <dev> <w> <h> <seconds>\n", argv[0]); return 2; }
    const char *dev = argv[1];
    int W = atoi(argv[2]), H = atoi(argv[3]);
    int secs = atoi(argv[4]);

    printf("== open %s ==\n", dev);
    int fd = open(dev, O_RDWR | O_NONBLOCK);
    if (fd < 0) { printf("open 失败: %s (errno=%d)\n", strerror(errno), errno); return 2; }

    /* 可选第 5 个参数：port type（十进制或 0x 十六进制）。
     * 厂商在 S_FMT 之前调 VIDIOC_S_OUTPUT（Amlogic 扩展，_IOWR('V',0x27,4)）
     * 告诉 VDIN 采哪一路；不调的话流能开但永远没有帧。 */
    if (argc >= 6) {
        unsigned int port = (unsigned int)strtoul(argv[5], NULL, 0);
        unsigned int v = port;
        int rc = ioctl(fd, 0xC0045627, &v);
        printf("S_OUTPUT(port=0x%x) → rc=%d errno=%d\n", port, rc, rc < 0 ? errno : 0);
    }

    struct v4l2_format fmt;
    memset(&fmt, 0, sizeof(fmt));
    fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    fmt.fmt.pix.width = W;
    fmt.fmt.pix.height = H;
    fmt.fmt.pix.pixelformat = V4L2_PIX_FMT_NV21;
    fmt.fmt.pix.field = V4L2_FIELD_ANY;
    if (ioctl(fd, VIDIOC_S_FMT, &fmt) < 0) {
        printf("S_FMT 失败: %s (errno=%d)\n", strerror(errno), errno);
        close(fd); return 3;
    }
    printf("S_FMT ok → %ux%u fourcc=0x%08x stride=%u size=%u\n",
           fmt.fmt.pix.width, fmt.fmt.pix.height, fmt.fmt.pix.pixelformat,
           fmt.fmt.pix.bytesperline, fmt.fmt.pix.sizeimage);

    struct v4l2_requestbuffers req;
    memset(&req, 0, sizeof(req));
    req.count = NBUF; req.type = V4L2_BUF_TYPE_VIDEO_CAPTURE; req.memory = V4L2_MEMORY_MMAP;
    if (ioctl(fd, VIDIOC_REQBUFS, &req) < 0) {
        printf("REQBUFS 失败: %s\n", strerror(errno)); close(fd); return 3;
    }
    printf("REQBUFS ok count=%u\n", req.count);

    void *bufs[NBUF]; size_t lens[NBUF]; int n = 0;
    for (unsigned i = 0; i < req.count && i < NBUF; i++) {
        struct v4l2_buffer b; memset(&b, 0, sizeof(b));
        b.type = V4L2_BUF_TYPE_VIDEO_CAPTURE; b.memory = V4L2_MEMORY_MMAP; b.index = i;
        if (ioctl(fd, VIDIOC_QUERYBUF, &b) < 0) { printf("QUERYBUF %u 失败: %s\n", i, strerror(errno)); break; }
        bufs[i] = mmap(NULL, b.length, PROT_READ | PROT_WRITE, MAP_SHARED, fd, b.m.offset);
        if (bufs[i] == MAP_FAILED) { printf("mmap %u 失败\n", i); break; }
        lens[i] = b.length;
        ioctl(fd, VIDIOC_QBUF, &b);
        n++;
    }
    printf("mmap 了 %d 个 buffer（每个 %zu 字节）\n", n, n ? lens[0] : 0);
    if (n == 0) { close(fd); return 3; }

    int type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (ioctl(fd, VIDIOC_STREAMON, &type) < 0) {
        printf("STREAMON 失败: %s (errno=%d)\n", strerror(errno), errno); close(fd); return 3;
    }
    printf("STREAMON ok，开始采 %d 秒…\n", secs);

    double t0 = now_ms(), tEnd = t0 + secs * 1000.0;
    long frames = 0, bytes = 0, timeouts = 0, errors = 0;
    double firstMs = -1, lastMs = t0;
    double minGap = 1e9, maxGap = 0, sumGap = 0; long gapN = 0;

    while (now_ms() < tEnd) {
        fd_set fds; FD_ZERO(&fds); FD_SET(fd, &fds);
        struct timeval tv; tv.tv_sec = 0; tv.tv_usec = 500000;
        int r = select(fd + 1, &fds, NULL, NULL, &tv);
        if (r < 0) { if (errno == EINTR) continue; errors++; break; }
        if (r == 0) { timeouts++; continue; }

        struct v4l2_buffer b; memset(&b, 0, sizeof(b));
        b.type = V4L2_BUF_TYPE_VIDEO_CAPTURE; b.memory = V4L2_MEMORY_MMAP;
        if (ioctl(fd, VIDIOC_DQBUF, &b) < 0) {
            if (errno == EAGAIN) continue;
            printf("DQBUF 失败: %s (errno=%d)\n", strerror(errno), errno); errors++; break;
        }
        double t = now_ms();
        if (firstMs < 0) firstMs = t;
        else { double g = t - lastMs; if (g < minGap) minGap = g; if (g > maxGap) maxGap = g; sumGap += g; gapN++; }
        lastMs = t;
        frames++; bytes += b.bytesused;

        if (frames == 1) {
            unsigned char *p = (unsigned char *)bufs[b.index];
            unsigned y0 = p[0];
            int nz = 0; for (int i = 0; i < 1024; i++) if (p[i]) nz++;
            printf("首帧: bytesused=%u Y[0]=%u 前 1KB 非零字节=%d\n", b.bytesused, y0, nz);
        }
        ioctl(fd, VIDIOC_QBUF, &b);
    }

    double elapsed = (now_ms() - t0) / 1000.0;
    printf("\n== 结果 ==\n");
    printf("帧数=%ld  时长=%.2fs  帧率=%.2f fps\n", frames, elapsed, frames / elapsed);
    printf("超时=%ld  错误=%ld\n", timeouts, errors);
    if (gapN > 0) printf("帧间隔: min=%.1f avg=%.1f max=%.1f ms\n", minGap, sumGap / gapN, maxGap);
    printf("平均每帧 %ld 字节（NV21 %dx%d 理论 %d）\n", frames ? bytes / frames : 0, W, H, W * H * 3 / 2);

    ioctl(fd, VIDIOC_STREAMOFF, &type);
    for (int i = 0; i < n; i++) munmap(bufs[i], lens[i]);
    close(fd);
    printf("已 STREAMOFF 并关闭\n");
    return 0;
}
