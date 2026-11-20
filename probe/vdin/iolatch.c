/*
 * libiolatch.so —— 给 screencontrol 挂的 ioctl 记录器（LD_PRELOAD）
 *
 * 目的：把厂商"能出画面"的真实 ioctl 序列录下来，包括我们猜不到的参数。
 *
 * 用法：
 *   LD_PRELOAD=/data/local/tmp/libiolatch.so /system/bin/screencontrol &
 *
 * 日志：/data/local/tmp/iolatch.log（用 syscall 直接写，避免递归进 libc 的 open）
 *
 * 安全：只透传 + 记录，不修改任何参数；出问题直接不带 LD_PRELOAD 重启即可。
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdarg.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/syscall.h>
#include <linux/videodev2.h>

#ifndef SYS_openat
#define SYS_openat 56
#endif
#ifndef SYS_write
#define SYS_write 4
#endif

static int g_logfd = -1;

static void lg(const char *fmt, ...) {
    if (g_logfd < 0) {
        g_logfd = (int)syscall(SYS_openat, AT_FDCWD, "/data/local/tmp/iolatch.log",
                               O_WRONLY | O_CREAT | O_APPEND, 0644);
        if (g_logfd < 0) return;
    }
    char buf[768];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n > 0) syscall(SYS_write, g_logfd, buf, (size_t)n);
}

static const char *reqname(unsigned long r) {
    switch (r) {
        case 0x80685600UL: return "QUERYCAP";
        case 0x80685604UL: return "G_FMT";
        case 0xc0d05605UL: return "S_FMT";
        case 0xc0145608UL: return "REQBUFS";
        case 0xc0445609UL: return "QUERYBUF";
        case 0xc044560aUL: return "QBUF";
        case 0xc044560bUL: return "DQBUF";
        case 0xc0045627UL: return "S_OUTPUT";
        case 0x00005612UL: return "STREAMON";
        case 0x00005613UL: return "STREAMOFF";
        default: return "?";
    }
}

static void dump(unsigned long req, void *arg) {
    if (!arg) return;
    switch (req) {
        case 0x80685600UL: {
            struct v4l2_capability *c = (struct v4l2_capability *)arg;
            lg("      cap driver=%s card=%s caps=0x%08x\n", c->driver, c->card, c->capabilities);
            break;
        }
        case 0xc0d05605UL: {
            struct v4l2_format *f = (struct v4l2_format *)arg;
            lg("      fmt type=%u %ux%u fourcc=0x%08x (%c%c%c%c) stride=%u size=%u field=%u\n",
               f->type, f->fmt.pix.width, f->fmt.pix.height, f->fmt.pix.pixelformat,
               (char)(f->fmt.pix.pixelformat & 0xff), (char)((f->fmt.pix.pixelformat >> 8) & 0xff),
               (char)((f->fmt.pix.pixelformat >> 16) & 0xff), (char)((f->fmt.pix.pixelformat >> 24) & 0xff),
               f->fmt.pix.bytesperline, f->fmt.pix.sizeimage, f->fmt.pix.field);
            break;
        }
        case 0xc0145608UL: {
            struct v4l2_requestbuffers *r = (struct v4l2_requestbuffers *)arg;
            lg("      reqbufs count=%u type=%u memory=%u\n", r->count, r->type, r->memory);
            break;
        }
        case 0xc0045627UL: {
            lg("      s_output value=0x%x (%u)\n", *(unsigned int *)arg, *(unsigned int *)arg);
            break;
        }
        case 0xc0445609UL:
        case 0xc044560aUL:
        case 0xc044560bUL: {
            struct v4l2_buffer *b = (struct v4l2_buffer *)arg;
            lg("      buf index=%u type=%u bytesused=%u len=%u flags=0x%x\n",
               b->index, b->type, b->bytesused, b->length, b->flags);
            break;
        }
        default: break;
    }
}

int ioctl(int fd, int request, ...) {
    void *arg = NULL;
    va_list ap;
    va_start(ap, request);
    arg = va_arg(ap, void *);
    va_end(ap);

    unsigned long req = (unsigned long)(unsigned int)request;
    const char *n = reqname(req);
    lg("ioctl fd=%d req=0x%08lx [%s]\n", fd, req, n);
    dump(req, arg);

    int r = (int)syscall(SYS_ioctl, fd, req, arg);
    if (r < 0) lg("      -> -1 errno=%d\n", errno);
    else lg("      -> %d\n", r);
    return r;
}
