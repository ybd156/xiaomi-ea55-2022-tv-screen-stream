/*
 * libvdin.so —— V4L2 直采（NV21）的 JNI 封装
 *
 * 对应 Java: com.droidlogic.app.VdinCapture
 *   open(String path, int w, int h) -> int   (0 成功)
 *   grab(byte[] nv21)               -> int   (写入字节数；-1 超时；-2 出错)
 *   close()                         -> void
 *
 * 设备：/dev/video12（vdin0）。必须独占，用之前要停掉 screencontrol。
 * S_OUTPUT 的值 0x1100a006 是从厂商 screencontrol 的 ioctl 录制里取到的，
 * 换成别的值驱动也返回 0，但一帧都不出。
 */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/select.h>
#include <linux/videodev2.h>

#define PORT_TYPE 0x1100a006u
#define NBUF 4

static int g_fd = -1;
static int g_nbuf = 0;
static void *g_buf[NBUF];
static size_t g_len[NBUF];
static int g_w = 0, g_h = 0;
static int g_streaming = 0;

static void teardown(void) {
    if (g_fd >= 0 && g_streaming) {
        int t = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        ioctl(g_fd, VIDIOC_STREAMOFF, &t);
        g_streaming = 0;
    }
    for (int i = 0; i < g_nbuf; i++) {
        if (g_buf[i] && g_buf[i] != MAP_FAILED) munmap(g_buf[i], g_len[i]);
        g_buf[i] = NULL;
    }
    g_nbuf = 0;
    if (g_fd >= 0) { close(g_fd); g_fd = -1; }
}

JNIEXPORT jint JNICALL
Java_com_droidlogic_app_VdinCapture_open(JNIEnv *env, jclass cls, jstring jpath, jint w, jint h) {
    teardown();
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return -1;
    int fd = open(path, O_RDWR | O_NONBLOCK);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (fd < 0) return -2;

    unsigned int port = PORT_TYPE;
    if (ioctl(fd, 0xC0045627, &port) < 0) { close(fd); return -3; }   /* S_OUTPUT */

    struct v4l2_format fmt;
    memset(&fmt, 0, sizeof(fmt));
    fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    fmt.fmt.pix.width = (unsigned)w;
    fmt.fmt.pix.height = (unsigned)h;
    fmt.fmt.pix.pixelformat = V4L2_PIX_FMT_NV21;
    fmt.fmt.pix.field = V4L2_FIELD_ANY;
    if (ioctl(fd, VIDIOC_S_FMT, &fmt) < 0) { close(fd); return -4; }
    if ((int)fmt.fmt.pix.pixelformat != V4L2_PIX_FMT_NV21) { close(fd); return -5; }

    struct v4l2_requestbuffers req;
    memset(&req, 0, sizeof(req));
    req.count = NBUF;
    req.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    req.memory = V4L2_MEMORY_MMAP;
    if (ioctl(fd, VIDIOC_REQBUFS, &req) < 0) { close(fd); return -6; }

    g_fd = fd; g_nbuf = 0; g_w = w; g_h = h;
    for (unsigned i = 0; i < req.count && i < NBUF; i++) {
        struct v4l2_buffer b;
        memset(&b, 0, sizeof(b));
        b.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        b.memory = V4L2_MEMORY_MMAP;
        b.index = i;
        if (ioctl(fd, VIDIOC_QUERYBUF, &b) < 0) { teardown(); return -7; }
        g_buf[i] = mmap(NULL, b.length, PROT_READ | PROT_WRITE, MAP_SHARED, fd, b.m.offset);
        if (g_buf[i] == MAP_FAILED) { teardown(); return -8; }
        g_len[i] = b.length;
        if (ioctl(fd, VIDIOC_QBUF, &b) < 0) { teardown(); return -9; }
        g_nbuf++;
    }
    int type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (ioctl(fd, VIDIOC_STREAMON, &type) < 0) { teardown(); return -10; }
    g_streaming = 1;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_droidlogic_app_VdinCapture_grab(JNIEnv *env, jclass cls, jbyteArray jout) {
    if (g_fd < 0 || !g_streaming) return -2;
    int need = (*env)->GetArrayLength(env, jout);

    fd_set fds; FD_ZERO(&fds); FD_SET(g_fd, &fds);
    struct timeval tv; tv.tv_sec = 0; tv.tv_usec = 300000;
    int r = select(g_fd + 1, &fds, NULL, NULL, &tv);
    if (r == 0) return -1;                 /* 超时，调用方继续下一轮 */
    if (r < 0) return (errno == EINTR) ? -1 : -2;

    struct v4l2_buffer b;
    memset(&b, 0, sizeof(b));
    b.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    b.memory = V4L2_MEMORY_MMAP;
    if (ioctl(g_fd, VIDIOC_DQBUF, &b) < 0) return (errno == EAGAIN) ? -1 : -2;

    int n = (int)b.bytesused;
    if (n > need) n = need;
    if (n > 0 && b.index < (unsigned)g_nbuf) {
        (*env)->SetByteArrayRegion(env, jout, 0, n, (const jbyte *)g_buf[b.index]);
    }
    ioctl(g_fd, VIDIOC_QBUF, &b);          /* 立刻归还，保持流水 */
    return n;
}

JNIEXPORT void JNICALL
Java_com_droidlogic_app_VdinCapture_close(JNIEnv *env, jclass cls) {
    teardown();
}
