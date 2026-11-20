/*
 * vdin_stream_mp —— V4L2 多平面（MPLANE）常开流采集压测，NV21
 *
 * 用法：vdin_stream_mp /dev/video12 480 270 5
 *
 * 与 vdin_stream 的区别：走 V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE，
 * 用 v4l2_pix_format_mplane / v4l2_buffer.m.planes。
 * driver 的 caps 里有 0x200000(CAPTURE_MPLANE) 且单平面 REQBUFS 返回 ENOTTY 时用这个。
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
#define T_MP V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE

static double now_ms(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

int main(int argc, char **argv) {
    if (argc < 5) { printf("用法: %s <dev> <w> <h> <seconds>\n", argv[0]); return 2; }
    const char *dev = argv[1];
    int W = atoi(argv[2]), H = atoi(argv[3]), secs = atoi(argv[4]);

    printf("== open %s (MPLANE) ==\n", dev);
    int fd = open(dev, O_RDWR | O_NONBLOCK);
    if (fd < 0) { printf("open 失败: %s (errno=%d)\n", strerror(errno), errno); return 2; }

    struct v4l2_format fmt; memset(&fmt, 0, sizeof(fmt));
    fmt.type = T_MP;
    fmt.fmt.pix_mp.width = W;
    fmt.fmt.pix_mp.height = H;
    fmt.fmt.pix_mp.pixelformat = V4L2_PIX_FMT_NV21;
    fmt.fmt.pix_mp.field = V4L2_FIELD_ANY;
    fmt.fmt.pix_mp.num_planes = 1;
    fmt.fmt.pix_mp.plane_fmt[0].bytesperline = (W + 31) & ~31;
    fmt.fmt.pix_mp.plane_fmt[0].sizeimage = ((W + 31) & ~31) * H * 3 / 2;
    if (ioctl(fd, VIDIOC_S_FMT, &fmt) < 0) {
        printf("S_FMT(MPLANE) 失败: %s (errno=%d)\n", strerror(errno), errno);
        close(fd); return 3;
    }
    printf("S_FMT ok → %ux%u fourcc=0x%08x planes=%u size=%u\n",
           fmt.fmt.pix_mp.width, fmt.fmt.pix_mp.height, fmt.fmt.pix_mp.pixelformat,
           fmt.fmt.pix_mp.num_planes, fmt.fmt.pix_mp.plane_fmt[0].sizeimage);

    struct v4l2_requestbuffers req; memset(&req, 0, sizeof(req));
    req.count = NBUF; req.type = T_MP; req.memory = V4L2_MEMORY_MMAP;
    if (ioctl(fd, VIDIOC_REQBUFS, &req) < 0) {
        printf("REQBUFS(MPLANE) 失败: %s (errno=%d)\n", strerror(errno), errno);
        close(fd); return 3;
    }
    printf("REQBUFS ok count=%u\n", req.count);

    void *bufs[NBUF] = {0}; size_t lens[NBUF] = {0}; int n = 0;
    struct v4l2_plane planes[NBUF][1];
    for (unsigned i = 0; i < req.count && i < NBUF; i++) {
        struct v4l2_buffer b; memset(&b, 0, sizeof(b));
        memset(&planes[i][0], 0, sizeof(planes[i][0]));
        b.type = T_MP; b.memory = V4L2_MEMORY_MMAP; b.index = i;
        b.length = 1; b.m.planes = &planes[i][0];
        if (ioctl(fd, VIDIOC_QUERYBUF, &b) < 0) { printf("QUERYBUF %u 失败: %s\n", i, strerror(errno)); break; }
        size_t len = planes[i][0].length;
        off_t off = planes[i][0].m.mem_offset;
        bufs[i] = mmap(NULL, len, PROT_READ | PROT_WRITE, MAP_SHARED, fd, off);
        if (bufs[i] == MAP_FAILED) { printf("mmap %u 失败: %s\n", i, strerror(errno)); break; }
        lens[i] = len;
        if (ioctl(fd, VIDIOC_QBUF, &b) < 0) { printf("QBUF %u 失败: %s\n", i, strerror(errno)); break; }
        n++;
    }
    printf("mmap 了 %d 个 buffer（每个 %zu 字节）\n", n, n ? lens[0] : 0);
    if (n == 0) { close(fd); return 3; }

    int type = T_MP;
    if (ioctl(fd, VIDIOC_STREAMON, &type) < 0) {
        printf("STREAMON 失败: %s (errno=%d)\n", strerror(errno), errno); close(fd); return 3;
    }
    printf("STREAMON ok，采 %d 秒…\n", secs);

    double t0 = now_ms(), tEnd = t0 + secs * 1000.0;
    long frames = 0, bytes = 0, timeouts = 0, errors = 0;
    double lastMs = t0, minGap = 1e9, maxGap = 0, sumGap = 0; long gapN = 0;

    while (now_ms() < tEnd) {
        fd_set fds; FD_ZERO(&fds); FD_SET(fd, &fds);
        struct timeval tv; tv.tv_sec = 0; tv.tv_usec = 500000;
        int r = select(fd + 1, &fds, NULL, NULL, &tv);
        if (r < 0) { if (errno == EINTR) continue; errors++; break; }
        if (r == 0) { timeouts++; continue; }

        struct v4l2_buffer b; memset(&b, 0, sizeof(b));
        struct v4l2_plane pl; memset(&pl, 0, sizeof(pl));
        b.type = T_MP; b.memory = V4L2_MEMORY_MMAP; b.length = 1; b.m.planes = &pl;
        if (ioctl(fd, VIDIOC_DQBUF, &b) < 0) {
            if (errno == EAGAIN) continue;
            printf("DQBUF 失败: %s (errno=%d)\n", strerror(errno), errno); errors++; break;
        }
        double t = now_ms();
        if (frames > 0) { double g = t - lastMs; if (g < minGap) minGap = g; if (g > maxGap) maxGap = g; sumGap += g; gapN++; }
        lastMs = t;
        frames++; bytes += pl.bytesused;

        if (frames == 1) {
            unsigned char *p = (unsigned char *)bufs[b.index];
            int nz = 0; for (int i2 = 0; i2 < 1024; i2++) if (p[i2]) nz++;
            printf("首帧: bytesused=%u Y[0]=%u 前 1KB 非零=%d\n", pl.bytesused, p[0], nz);
        }
        ioctl(fd, VIDIOC_QBUF, &b);
    }

    double elapsed = (now_ms() - t0) / 1000.0;
    printf("\n== 结果 ==\n帧数=%ld 时长=%.2fs 帧率=%.2f fps\n", frames, elapsed, frames / elapsed);
    printf("超时=%ld 错误=%ld\n", timeouts, errors);
    if (gapN > 0) printf("帧间隔: min=%.1f avg=%.1f max=%.1f ms\n", minGap, sumGap / gapN, maxGap);
    printf("平均每帧 %ld 字节（NV21 %dx%d 理论 %d）\n", frames ? bytes / frames : 0, W, H, W * H * 3 / 2);

    ioctl(fd, VIDIOC_STREAMOFF, &type);
    for (int i = 0; i < n; i++) munmap(bufs[i], lens[i]);
    close(fd);
    printf("已 STREAMOFF 并关闭\n");
    return 0;
}
