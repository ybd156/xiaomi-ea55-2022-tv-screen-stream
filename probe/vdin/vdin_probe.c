/*
 * vdin_probe —— 只读探测 Amlogic VDIN 采集通道
 *
 * 目的：验证「绕开厂商 screencontrol API、直接读 /dev/vdin0 拿帧」这条路是否可行。
 * 默认**只做查询**（open + QUERYCAP + ENUM_FMT + G_FMT），不开流、不申请缓冲，
 * 因此即使 screencontrol 正占着设备也不会干扰它在跑的采集。
 *
 * 加 --stream N 才会真的 REQBUFS/STREAMON/DQBUF N 帧 —— 这一步有风险：
 * 可能和 screencontrol 抢设备，或让显示管线进入异常状态。
 * 出事后的恢复：killall screencontrol，然后跑 /data/local/tmp/restore-service.sh
 *
 * 编译（见 tools/native-build.sh）：
 *   bash tools/native-build.sh probe/vdin/vdin_probe.c vdin_probe
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <errno.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <linux/videodev2.h>

static const char *fourcc_str(unsigned int f, char *buf) {
    buf[0] = (char)(f & 0xff);
    buf[1] = (char)((f >> 8) & 0xff);
    buf[2] = (char)((f >> 16) & 0xff);
    buf[3] = (char)((f >> 24) & 0xff);
    buf[4] = 0;
    return buf;
}

static void query(int fd, const char *path) {
    struct v4l2_capability cap;
    memset(&cap, 0, sizeof(cap));
    if (ioctl(fd, VIDIOC_QUERYCAP, &cap) < 0) {
        printf("QUERYCAP 失败: %s (errno=%d)\n", strerror(errno), errno);
    } else {
        printf("driver=%s card=%s bus=%s version=%u.%u.%u caps=0x%08x\n",
               cap.driver, cap.card, cap.bus_info,
               (cap.version >> 16) & 0xff, (cap.version >> 8) & 0xff, cap.version & 0xff,
               cap.capabilities);
        printf("  video_capture=%d streaming=%d\n",
               (cap.capabilities & V4L2_CAP_VIDEO_CAPTURE) ? 1 : 0,
               (cap.capabilities & V4L2_CAP_STREAMING) ? 1 : 0);
    }

    printf("--- 支持的像素格式 ---\n");
    for (int i = 0; i < 24; i++) {
        struct v4l2_fmtdesc d;
        memset(&d, 0, sizeof(d));
        d.index = i;
        d.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        if (ioctl(fd, VIDIOC_ENUM_FMT, &d) < 0) break;
        char f[5];
        printf("  [%d] %s  %s  flags=0x%x\n", i, fourcc_str(d.pixelformat, f), d.description, d.flags);
    }

    printf("--- 当前格式 ---\n");
    struct v4l2_format fmt;
    memset(&fmt, 0, sizeof(fmt));
    fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (ioctl(fd, VIDIOC_G_FMT, &fmt) < 0) {
        printf("  G_FMT 失败: %s\n", strerror(errno));
    } else {
        char f[5];
        printf("  %ux%u pixfmt=%s sizeimage=%u bytesperline=%u field=%u\n",
               fmt.fmt.pix.width, fmt.fmt.pix.height,
               fourcc_str(fmt.fmt.pix.pixelformat, f),
               fmt.fmt.pix.sizeimage, fmt.fmt.pix.bytesperline, fmt.fmt.pix.field);
    }
}

/* 开流试验：只拿来判断「能不能真的拿到帧」，不做长时间采集 */
static int stream_test(int fd, int want) {
    struct v4l2_requestbuffers req;
    memset(&req, 0, sizeof(req));
    req.count = 3;
    req.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    req.memory = V4L2_MEMORY_MMAP;
    if (ioctl(fd, VIDIOC_REQBUFS, &req) < 0) {
        printf("REQBUFS 失败: %s (errno=%d)\n", strerror(errno), errno);
        return -1;
    }
    printf("REQBUFS ok, count=%u\n", req.count);

    void *bufs[8] = {0};
    size_t lens[8] = {0};
    for (unsigned i = 0; i < req.count && i < 8; i++) {
        struct v4l2_buffer b;
        memset(&b, 0, sizeof(b));
        b.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        b.memory = V4L2_MEMORY_MMAP;
        b.index = i;
        if (ioctl(fd, VIDIOC_QUERYBUF, &b) < 0) { printf("QUERYBUF %u 失败\n", i); return -1; }
        bufs[i] = mmap(NULL, b.length, PROT_READ | PROT_WRITE, MAP_SHARED, fd, b.m.offset);
        if (bufs[i] == MAP_FAILED) { printf("mmap %u 失败\n", i); return -1; }
        lens[i] = b.length;
        ioctl(fd, VIDIOC_QBUF, &b);
    }

    int type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (ioctl(fd, VIDIOC_STREAMON, &type) < 0) {
        printf("STREAMON 失败: %s (errno=%d)\n", strerror(errno), errno);
        return -1;
    }
    printf("STREAMON ok —— 开始 DQBUF\n");

    int got = 0;
    for (int k = 0; k < want; k++) {
        struct v4l2_buffer b;
        memset(&b, 0, sizeof(b));
        b.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        b.memory = V4L2_MEMORY_MMAP;
        if (ioctl(fd, VIDIOC_DQBUF, &b) < 0) {
            printf("DQBUF #%d 失败: %s (errno=%d)\n", k, strerror(errno), errno);
            break;
        }
        if (got == 0 && b.bytesused > 0) {
            unsigned char *p = (unsigned char *)bufs[b.index];
            printf("首帧: index=%u bytesused=%u 前 16 字节=%02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x\n",
                   b.index, b.bytesused, p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7],
                   p[8], p[9], p[10], p[11], p[12], p[13], p[14], p[15]);
        }
        got++;
        ioctl(fd, VIDIOC_QBUF, &b);
    }
    printf("收帧 %d / %d\n", got, want);
    ioctl(fd, VIDIOC_STREAMOFF, &type);
    printf("STREAMOFF done\n");
    return got;
}

int main(int argc, char **argv) {
    const char *path = "/dev/vdin0";
    int want = 0;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--stream") && i + 1 < argc) want = atoi(argv[++i]);
        else if (!strncmp(argv[i], "/dev/", 5)) path = argv[i];
    }

    printf("== 打开 %s ==\n", path);
    int fd = open(path, O_RDWR | O_NONBLOCK);
    if (fd < 0) {
        printf("open 失败: %s (errno=%d)\n", strerror(errno), errno);
        return 2;
    }
    printf("open ok, fd=%d\n", fd);

    query(fd, path);
    if (want > 0) {
        printf("== 开流试验 %d 帧 ==\n", want);
        stream_test(fd, want);
    } else {
        printf("(只查询，未开流；加 --stream N 才试抓帧)\n");
    }
    close(fd);
    return 0;
}
