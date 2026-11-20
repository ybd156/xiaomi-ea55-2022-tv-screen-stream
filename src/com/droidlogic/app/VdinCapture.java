package com.droidlogic.app;

/**
 * V4L2 直采（NV21）的 JNI 封装。
 *
 * <p>对应 {@code /data/local/tmp/libvdin.so}，走 /dev/video12（vdin0）。
 * **必须先停掉 screencontrol**，否则 open 返回 EBUSY。
 *
 * <p>历史背景：厂商 screencontrol 每帧 start→等首帧→读回→stop，实测只有 16.6 fps@480p、
 * 8.3 fps@960p；直接走 V4L2 常开流可以稳定 30 fps，且与分辨率无关。
 * 关键参数 {@code S_OUTPUT=0x1100a006} 来自对厂商进程的 ioctl 录制（见 probe/vdin/ABI.md）。
 */
public final class VdinCapture {

    private VdinCapture() {}

    static {
        System.load("/data/local/tmp/libvdin.so");
    }

    /** 打开并开流。fps<=0 表示用驱动默认（30）。0=成功，负数为错误码（-2 打不开、-3 S_OUTPUT、-4/-5 S_FMT、-6 REQBUFS、-7~-9 缓冲、-10 STREAMON、-11 S_PARM）。 */
    public static native int open(String path, int w, int h, int fps);

    /** 取一帧到 nv21（长度需 ≥ w*h*3/2）。返回写入字节数；-1 本轮超时（正常）；-2 出错。 */
    public static native int grab(byte[] nv21);

    /** 停止流并释放。 */
    public static native void close();
}
