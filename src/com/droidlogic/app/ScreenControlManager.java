package com.droidlogic.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * 厂商 screencontrol 回采客户端。
 *
 * 5 个 native 方法名/签名来自 /vendor/lib/libscreencontrol_jni.so 的 RegisterNatives 字符串表：
 *   native_ConnectScreenControl ()V
 *   native_ScreenCap            (IIIIIIILjava/lang/String;)I
 *   native_ScreenRecord         (IIIIIILjava/lang/String;)I
 *   native_ScreenCapBuffer      (IIIIIII)[B      // 返回 RGBA_8888 原始帧
 *   native_ForceStop            ()V
 *
 * 用法：
 *   app_process ... com.droidlogic.app.ScreenControlManager cap   <srcType> <w> <h> <out>
 *   app_process ... com.droidlogic.app.ScreenControlManager bench <srcType> <w> <h> <n>
 *   app_process ... com.droidlogic.app.ScreenControlManager serve <srcType> <w> <h> <port> <outW> <outH>
 */
public class ScreenControlManager {

    static {
        System.load("/vendor/lib/libscreencontrol_jni.so");
    }

    public static native void native_ConnectScreenControl();
    public static native int native_ScreenCap(int left, int top, int right, int bottom,
                                              int width, int height, int sourceType, String fileName);
    public static native int native_ScreenRecord(int width, int height, int frameRate, int bitRate,
                                                 int limitTimeSec, int sourceType, String fileName);
    public static native byte[] native_ScreenCapBuffer(int left, int top, int right, int bottom,
                                                       int width, int height, int sourceType);
    public static native void native_ForceStop();

    // ---------------- 采集 ----------------

    /** 屏幕物理尺寸（裁剪矩形用），输出尺寸由 outW/outH 决定 */
    static int SCREEN_W = 1920;
    static int SCREEN_H = 1080;

    /**
     * 注意：前 4 个参数是「裁剪矩形」（屏幕坐标），后 2 个是「输出尺寸」。
     * 之前把裁剪矩形也传成输出尺寸，导致小分辨率时只截到左上角一块 —— 这是错的。
     */
    static byte[] grab(int srcType, int outW, int outH) {
        try {
            return native_ScreenCapBuffer(0, 0, SCREEN_W, SCREEN_H, outW, outH, srcType);
        } catch (Throwable t) {
            System.out.println("grab threw: " + t);
            return null;
        }
    }

    // ---------------- 模式 ----------------

    static void single(int srcType, int w, int h, String out) throws Exception {
        long t0 = System.currentTimeMillis();
        byte[] buf = grab(srcType, w, h);
        long ms = System.currentTimeMillis() - t0;
        if (buf == null) {
            System.out.println("RESULT null elapsed=" + ms + "ms");
            return;
        }
        System.out.println("RESULT len=" + buf.length + " elapsed=" + ms + "ms expectRGBA=" + (w * h * 4));
        if (buf.length > 0) {
            try (OutputStream fos = new java.io.FileOutputStream(out)) {
                fos.write(buf);
                System.out.println("wrote " + out);
            }
        }
    }

    static void bench(int srcType, int w, int h, int n) {
        long min = Long.MAX_VALUE, max = 0, sum = 0;
        int ok = 0;
        for (int i = 0; i < n; i++) {
            long t0 = System.currentTimeMillis();
            byte[] buf = grab(srcType, w, h);
            long ms = System.currentTimeMillis() - t0;
            if (buf != null && buf.length == w * h * 4) {
                ok++;
                sum += ms;
                min = Math.min(min, ms);
                max = Math.max(max, ms);
            } else {
                System.out.println("frame " + i + " failed len=" + (buf == null ? -1 : buf.length) + " ms=" + ms);
            }
        }
        if (ok > 0) {
            System.out.printf("BENCH %dx%d srcType=%d ok=%d/%d avg=%.1fms min=%dms max=%dms => %.1f fps%n",
                    w, h, srcType, ok, n, sum * 1.0 / ok, min, max, 1000.0 * ok / Math.max(1, sum));
        }
    }

    /** 多线程并发采集：看守护进程能否并行处理多个客户端 */
    static void multibench(final int srcType, final int w, final int h, int threads, final int frames) throws Exception {
        final int[] okCount = new int[threads];
        Thread[] ts = new Thread[threads];
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            ts[i] = new Thread(() -> {
                for (int f = 0; f < frames; f++) {
                    byte[] b = grab(srcType, w, h);
                    if (b != null && b.length == w * h * 4) okCount[idx]++;
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) t.join();
        long wall = System.currentTimeMillis() - t0;
        int total = 0;
        for (int c : okCount) total += c;
        System.out.printf("MULTIBENCH %dx%d threads=%d frames/thread=%d ok=%d wall=%dms => %.1f fps aggregate%n",
                w, h, threads, frames, total, wall, 1000.0 * total / Math.max(1, wall));
    }

    /** 厂商连续录制通道（不走逐帧重启），测实际帧率 */
    static void rec(int w, int h, int fps, int bitrate, int seconds, int srcType, String file) throws Exception {
        long t0 = System.currentTimeMillis();
        int rc = native_ScreenRecord(w, h, fps, bitrate, seconds, srcType, file);
        long ms = System.currentTimeMillis() - t0;
        java.io.File f = new java.io.File(file);
        System.out.printf("REC rc=%d elapsed=%dms file=%s size=%d bytes (%.2f MB/s)%n",
                rc, ms, file, f.length(), f.length() / 1048576.0 / Math.max(1, ms / 1000.0));
    }

    static int JPEGQ = 70;
    // 给网页 /stats 用的实时统计
    static volatile double STAT_FPS = 0;
    static volatile double STAT_KBPS = 0;
    static volatile int STAT_W = 0, STAT_H = 0, STAT_CLIENTS = 0;
    static volatile long STAT_FRAMES = 0;
    static final java.util.concurrent.atomic.AtomicInteger STREAM_CLIENTS =
            new java.util.concurrent.atomic.AtomicInteger();
    static int clientCount() { return STREAM_CLIENTS.get(); }

    // ---- 空闲停采：没人看的时候不要把电视的 CPU 一直占着 ----
    //
    // 旧实现只要进程活着就按采集接口能跑多快跑多快地 grab → 编码 → 广播，
    // 与有没有观看端无关。实测常驻 screencontrol 36% + app_process 33%（合计 0.69 核），
    // 而电视自己硬解 1080p 只用 0.16 核 —— 不看的时候这份开销纯属浪费。
    //
    // 注意两件事：
    //  1) 只暂停「抓帧」，不销毁采集线程。PITFALLS 里写过：起新管线前必须等旧采集线程
    //     真的从 native_ScreenCapture 退出来，否则厂商 screencontrol 会累积未释放的会话，
    //     最后返回空帧且不自愈。空闲态留在同一个线程里睡，天然不会撞上这个问题。
    //  2) 音频不跟着停。tinycap 反复开关会让 loopback 退化到「能打开但不产数据」，
    //     只能重启电视恢复，风险远大于它那 0.8% 的 CPU。
    /** 无人观看多久后暂停采集（毫秒）；-Dtvss.idleMs=0 可关闭该功能 */
    static final long IDLE_MS = Long.getLong("tvss.idleMs", 30000L);
    /** 最近一次「有观看端」的时刻 */
    static volatile long LAST_VIEWER_MS = System.currentTimeMillis();
    /** 采集是否已因空闲暂停 */
    static volatile boolean IDLE = false;
    /** 空闲期间 /snap 要求补一帧 */
    static volatile boolean SNAP_WANTED = false;

    /**
     * 采集后端开关：false = 厂商 screencontrol（默认，兼容旧行为）；
     * true = 直采 /dev/video12（serve-vdin 模式，实测 30 fps 恒定，但要求 screencontrol 不占用设备）。
     */
    static volatile boolean USE_VDIN = false;

    // ---- 视频客户端注册表：每端一个有界队列，慢端只丢自己的帧 ----

    /** 同时在线的视频流客户端上限（防止线程/内存被耗光） */
    static final int MAX_VIDEO_CLIENTS = 8;
    /** 每个客户端最多积压的帧数；超过就丢最旧的（直播场景旧帧没有价值） */
    static final int VIDEO_QUEUE_FRAMES = 3;
    /** 每个客户端的积压字节上限，与帧数上限先到先算 */
    static final long VIDEO_QUEUE_BYTES = 512 * 1024;

    static final java.util.List<ClientChannel> VIDEO_CHANNELS = new java.util.ArrayList<>();

    /** 注册视频客户端；已满返回 false（调用方负责回 503） */
    static boolean registerVideoClient(ClientChannel ch) {
        synchronized (VIDEO_CHANNELS) {
            for (int i = VIDEO_CHANNELS.size() - 1; i >= 0; i--) {
                if (VIDEO_CHANNELS.get(i).isClosed()) VIDEO_CHANNELS.remove(i);
            }
            if (VIDEO_CHANNELS.size() >= MAX_VIDEO_CLIENTS) return false;
            VIDEO_CHANNELS.add(ch);
            return true;
        }
    }

    /** 把最新一帧投递给所有客户端。offer() 不阻塞，慢端自己丢帧，不影响采集/编码 */
    static void broadcastJpeg(byte[] jpg) {
        ClientChannel[] snapshot;
        synchronized (VIDEO_CHANNELS) {
            if (VIDEO_CHANNELS.isEmpty()) return;
            snapshot = VIDEO_CHANNELS.toArray(new ClientChannel[0]);
        }
        for (ClientChannel ch : snapshot) ch.offer(jpg);
    }

    /**
     * 等客户端断开（读到 EOF）或超时。
     *
     * 为什么由处理线程读而不是让写线程自己发现：写线程只在有帧可写时才会撞到写异常，
     * 一旦编码停摆（息屏 / 采集失败），已断开的连接会一直占着客户端名额。
     * 本服务的流式端点客户端不会发任何数据，所以这里 read() 只可能等到 EOF 或超时。
     * socket 已设 soTimeout=5s，read 不会永久阻塞。
     */
    static void waitForPeerClose(InputStream in, ClientChannel ch, long maxMs) {
        long deadline = System.currentTimeMillis() + maxMs;
        byte[] sink = new byte[256];
        while (System.currentTimeMillis() < deadline && !ch.isClosed()) {
            try {
                if (in.read(sink) < 0) return;                  // 对端 FIN
            } catch (java.net.SocketTimeoutException te) {
                // 5 秒没有数据是正常情况，继续等
            } catch (Throwable t) {
                return;                                          // 连接已坏
            }
        }
    }

    static volatile byte[] latestJpeg;
    static volatile long latestSeq;
    static volatile int capFps10; // 最近一次统计的帧率 x10

    // 当前采集参数（网页可改）
    static volatile int CUR_W = 640, CUR_H = 360;
    static volatile int GEN = 0;

    static void serve(final int srcType, int w, int h, int port, int outW, int outH) throws Exception {
        CUR_W = w;
        CUR_H = h;
        if (USE_VDIN) {
            int rc = VdinCapture.open("/dev/video12", w, h);
            System.out.println("VDIN open(" + w + "x" + h + ") rc=" + rc);
            if (rc != 0) {
                System.out.println("VDIN 打不开（多半是 screencontrol 还占着 /dev/video12），进程退出");
                System.exit(3);
            }
            startVdinPipeline(w, h);
        } else {
            startPipeline(srcType, w, h);
        }
        startWatchdog();

        // 采 LOOPBACK-A（device 7, 8 声道）：这是原仓库的配置，实测音峰 ~100+；
        // 改采 TDM-B 采集端（device 1, 2ch）后音峰掉到 4，是错误的方向，已改回。
        try { AudioStream.start(0, 7); } catch (Throwable t) { System.out.println("audio start fail: " + t); }

        ServerSocket ss = new ServerSocket(port);
        System.out.println("LISTEN " + port);
        while (true) {
            final Socket s = ss.accept();
            Thread t = new Thread(() -> handle(s), "http");
            t.setDaemon(true);
            t.start();
        }
    }

    /** 起一套采集+编码线程；调用时旧的一代线程会自动退出 */
    /** 当前这一代的采集线程（用于起新管线前等它真正退出） */
    static volatile Thread CUR_CAP_THREAD;
    /** 当前采集参数（看门狗重启管线时要用） */
    static volatile int CUR_SRC_TYPE = 1;

    /**
     * 采集看门狗：帧数 30 秒不涨（且屏幕是亮的）就判定卡住，
     * 先打印采集线程的调用栈（用来定位到底卡在哪个函数），再自动重启管线。
     * 10 分钟内连续 3 次卡住就退出进程，让 Magisk 的看门狗脚本把服务整个重启。
     */
    static void startWatchdog() {
        Thread t = new Thread(() -> {
            long lastFrames = -1;
            long lastChange = System.currentTimeMillis();
            long lastRestart = 0;
            int restarts = 0;
            while (true) {
                try { Thread.sleep(10000); } catch (InterruptedException ignored) { }
                long f = STAT_FRAMES;
                long now = System.currentTimeMillis();
                if (IDLE || USE_VDIN) {
                    // 空闲停采时本来就不产帧；VDIN 模式下重启管线会走厂商接口（会失败），
                    // 所以两者都不做「帧数不涨」判定，避免误杀进程
                    // 否则每 30 秒白重启一次管线，还会累加到 10 分钟 3 次而误杀进程
                    lastFrames = f;
                    lastChange = now;
                    restarts = 0;
                    continue;
                }
                if (f != lastFrames) {
                    lastFrames = f;
                    lastChange = now;
                    continue;
                }
                if (now - lastChange < 30000) continue;
                if (!awakeCached()) continue;      // 息屏时本来就没帧，别误判
                System.out.println("看门狗：30 秒没有新帧，判定采集卡住");
                Thread cap = CUR_CAP_THREAD;
                if (cap != null) {
                    StackTraceElement[] st = cap.getStackTrace();
                    System.out.println("采集线程栈（" + st.length + " 帧）：");
                    for (int i = 0; i < st.length && i < 20; i++) {
                        System.out.println("  at " + st[i]);
                    }
                }
                if (now - lastRestart < 10 * 60 * 1000L) {
                    restarts++;
                } else {
                    restarts = 1;
                }
                lastRestart = now;
                if (restarts >= 3) {
                    System.out.println("看门狗：10 分钟内第 " + restarts
                            + " 次卡住，退出进程让脚本重启服务");
                    try { Thread.sleep(300); } catch (InterruptedException ignored) { }
                    System.exit(1);
                }
                if (restarts >= 2) {
                    // 第二次还卡，说明是厂商 screencontrol 守护进程自己僵住了
                    // （native_ScreenCapBuffer 永不返回）。杀掉它，系统会自动重新拉起，
                    // 实测这样能恢复画面，不用重启电视。
                    System.out.println("看门狗：重启 screencontrol 守护进程");
                    try {
                        Process p = Runtime.getRuntime().exec(new String[]{"killall", "screencontrol"});
                        p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Throwable ex) {
                        System.out.println("看门狗：killall screencontrol 失败: " + ex);
                    }
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
                }
                System.out.println("看门狗：重启采集管线（第 " + restarts + " 次）");
                startPipeline(CUR_SRC_TYPE, CUR_W, CUR_H);
                lastChange = System.currentTimeMillis();
                lastFrames = STAT_FRAMES;
            }
        }, "watchdog");
        t.setDaemon(true);
        t.start();
    }


    /**
     * 起一套采集+编码线程。
     *
     * **起新管线前必须等旧采集线程真的退出**：它可能正卡在厂商
     * `native_ScreenCapBuffer` 里，退不出来就无法检查 gen，于是厂商
     * `screencontrol` 那边会累积没释放的采集会话，最终返回空帧
     * （日志里的 `capture miss len=0`），而且守护进程不自愈，只能重启电视。
     * 加 synchronized 也防止两次 /set 并发进来各起一套。
     */
    static synchronized void startPipeline(final int srcType, final int w, final int h) {
        CUR_SRC_TYPE = srcType;
        final int gen = ++GEN;
        Thread old = CUR_CAP_THREAD;
        if (old != null && old.isAlive()) {
            try { old.join(1500); } catch (InterruptedException ignored) { }
            if (old.isAlive()) {
                System.out.println("警告：旧采集线程 1.5s 未退出，可能泄漏厂商采集会话");
            }
        }
        final int quality = JPEGQ;
        final Bitmap src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        final Object lock = new Object();
        final byte[][] slot = new byte[1][];
        final long[] produced = new long[1];
        STAT_W = w;
        STAT_H = h;

        Thread capThread = new Thread(() -> {
            int fails = 0;
            boolean wasIdle = false;
            while (gen == GEN) {
                // 空闲判断：有观看端就刷新时刻；没有且超时就只睡不抓
                if (clientCount() > 0) {
                    LAST_VIEWER_MS = System.currentTimeMillis();
                    if (wasIdle) {
                        wasIdle = false;
                        IDLE = false;
                        System.out.println("idle: 有观看端接入，恢复采集（gen=" + gen + "）");
                    }
                } else if (IDLE_MS > 0 && System.currentTimeMillis() - LAST_VIEWER_MS > IDLE_MS) {
                    if (!wasIdle) {
                        wasIdle = true;
                        IDLE = true;
                        STAT_FPS = 0;
                        STAT_KBPS = 0;
                        System.out.println("idle: 无人观看超过 " + (IDLE_MS / 1000)
                                + " 秒，暂停采集（gen=" + gen + "）");
                    }
                    // 空闲时仍要能出截图：借同一线程抓一帧交给编码线程，不并发调用厂商接口
                    if (SNAP_WANTED) {
                        SNAP_WANTED = false;
                        byte[] shot = grab(srcType, w, h);
                        if (shot != null && shot.length == w * h * 4) {
                            synchronized (lock) {
                                slot[0] = shot;
                                produced[0]++;
                                lock.notifyAll();
                            }
                        }
                    }
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    continue;
                }
                byte[] buf = grab(srcType, w, h);
                if (buf == null || buf.length != w * h * 4) {
                    fails++;
                    if (fails % 10 == 1) System.out.println("capture miss len=" + (buf == null ? -1 : buf.length));
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                    continue;
                }
                synchronized (lock) {
                    slot[0] = buf;
                    produced[0]++;
                    lock.notifyAll();
                }
            }
            IDLE = false;
            System.out.println("capture thread gen=" + gen + " exit");
        }, "cap");

        Thread encThread = new Thread(() -> {
            int okFrames = 0;
            long windowStart = System.currentTimeMillis();
            long lastProduced = 0;
            while (gen == GEN) {
                byte[] buf;
                synchronized (lock) {
                    while (produced[0] == lastProduced && gen == GEN) {
                        try { lock.wait(200); } catch (InterruptedException ignored) {}
                    }
                    if (gen != GEN) break;
                    lastProduced = produced[0];
                    buf = slot[0];
                    slot[0] = null;
                }
                if (buf == null) continue;
                try {
                    src.copyPixelsFromBuffer(ByteBuffer.wrap(buf));
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(256 * 1024);
                    src.compress(Bitmap.CompressFormat.JPEG, quality, bos);
                    byte[] jpg = bos.toByteArray();
                    latestJpeg = jpg;
                    latestSeq++;
                    broadcastJpeg(jpg);   // 只投递引用，不复制；各客户端自己排队
                    okFrames++;
                    STAT_FRAMES++;
                } catch (Throwable t) {
                    System.out.println("encode error: " + t);
                }
                long now = System.currentTimeMillis();
                if (now - windowStart >= 3000) {
                    double secs = (now - windowStart) / 1000.0;
                    double fps = okFrames / secs;
                    capFps10 = (int) Math.round(fps * 10);
                    STAT_FPS = fps;
                    STAT_KBPS = (latestJpeg == null ? 0 : latestJpeg.length) * 8.0 * fps / 1000.0;
                    STAT_CLIENTS = clientCount();
                    System.out.printf("STAT %dx%d fps=%.2f jpeg=%dKB%n",
                            w, h, fps, (latestJpeg == null ? 0 : latestJpeg.length / 1024));
                    okFrames = 0;
                    windowStart = now;
                }
            }
            // 本代 Bitmap 只被本线程使用，线程退出即释放。
            // 旧实现在每次 /set 后都泄漏 w*h*4 字节（1080p 约 8.3 MB，-Xmx96m 下十几次即可 OOM）
            src.recycle();
            System.out.println("encode thread gen=" + gen + " exit");
        }, "enc");

        capThread.setDaemon(true);
        encThread.setDaemon(true);
        CUR_CAP_THREAD = capThread;
        capThread.start();
        encThread.start();
        System.out.println("pipeline gen=" + gen + " " + w + "x" + h);
    }

    /**
     * VDIN 直采管线（serve-vdin 模式）。
     *
     * 与厂商路径的区别：常开流、单线程 grab→JPEG、**不做 RGBA 转换**（YuvImage 直接吃 NV21）。
     * 实测 480p/960p/720p/1080p 全部恒定 30 fps（厂商路径分别是 16.6 / 8.3 / 5.5 / 3.0）。
     * 前提：/dev/video12 必须空闲，即 screencontrol 不能占着它。
     */
    static void startVdinPipeline(final int w, final int h) {
        final int gen = ++GEN;
        STAT_W = w;
        STAT_H = h;
        Thread t = new Thread(() -> {
            byte[] nv21 = new byte[w * h * 3 / 2];
            boolean wasIdle = false;
            int okFrames = 0;
            long windowStart = System.currentTimeMillis();
            System.out.println("vdin pipeline gen=" + gen + " " + w + "x" + h);
            while (gen == GEN) {
                if (clientCount() > 0) {
                    LAST_VIEWER_MS = System.currentTimeMillis();
                    if (wasIdle) {
                        wasIdle = false;
                        IDLE = false;
                        System.out.println("idle: 恢复采集（VDIN）");
                    }
                } else if (IDLE_MS > 0 && System.currentTimeMillis() - LAST_VIEWER_MS > IDLE_MS) {
                    if (!wasIdle) {
                        wasIdle = true;
                        IDLE = true;
                        STAT_FPS = 0;
                        STAT_KBPS = 0;
                        System.out.println("idle: 无人观看，暂停采集（VDIN）");
                    }
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    continue;
                }
                int n = VdinCapture.grab(nv21);
                if (n <= 0) continue;          // -1 超时是常态，直接下一轮
                try {
                    android.graphics.YuvImage yi = new android.graphics.YuvImage(
                            nv21, android.graphics.ImageFormat.NV21, w, h, null);
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(256 * 1024);
                    yi.compressToJpeg(new android.graphics.Rect(0, 0, w, h), JPEGQ, bos);
                    byte[] jpg = bos.toByteArray();
                    latestJpeg = jpg;
                    latestSeq++;
                    broadcastJpeg(jpg);
                    STAT_FRAMES++;
                    okFrames++;
                } catch (Throwable e) {
                    System.out.println("vdin encode error: " + e);
                }
                long now = System.currentTimeMillis();
                if (now - windowStart >= 3000) {
                    double fps = okFrames * 1000.0 / (now - windowStart);
                    STAT_FPS = fps;
                    STAT_KBPS = (latestJpeg == null ? 0 : latestJpeg.length) * 8.0 * fps / 1000.0;
                    System.out.printf("VDINSTAT %dx%d fps=%.2f jpeg=%dKB%n",
                            w, h, fps, latestJpeg == null ? 0 : latestJpeg.length / 1024);
                    okFrames = 0;
                    windowStart = now;
                }
            }
            IDLE = false;
            System.out.println("vdin pipeline gen=" + gen + " exit");
        }, "vdin");
        t.setDaemon(true);
        CUR_CAP_THREAD = t;
        t.start();
    }


    // ---------------- 电源控制 ----------------

    static boolean isAwake() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "dumpsys power | grep mWakefulness="});
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            r.close();
            p.waitFor();
            return line != null && line.contains("Awake");
        } catch (Throwable t) {
            return true;
        }
    }

    static volatile boolean AWAKE_CACHE = true;
    static volatile long AWAKE_TS = 0;

    /** 带 2 秒缓存的醒睡状态（避免每次请求都 spawn 一个 dumpsys） */
    static boolean awakeCached() {
        long now = System.currentTimeMillis();
        if (now - AWAKE_TS > 2000) {
            AWAKE_CACHE = isAwake();
            AWAKE_TS = now;
        }
        return AWAKE_CACHE;
    }

    static void wakeUp() {
        try {
            if (!isAwake()) {
                Runtime.getRuntime().exec(new String[]{"sh", "-c", "input keyevent 26"}).waitFor();
                System.out.println("wake sent");
            }
        } catch (Throwable t) { System.out.println("wake fail: " + t); }
    }

    static void sleepNow() {
        try {
            if (isAwake()) {
                Runtime.getRuntime().exec(new String[]{"sh", "-c", "input keyevent 223"}).waitFor();
                System.out.println("sleep sent");
            }
        } catch (Throwable t) { System.out.println("sleep fail: " + t); }
    }

    static void switchResolution(int w, int h) {
        if (w < 64 || h < 36 || w > 1920 || h > 1080) return;
        CUR_W = w;
        CUR_H = h;
        if (USE_VDIN) {
            // VDIN 模式：必须重开设备换尺寸，**绝不能掉回厂商管线**
            // （screencontrol 已被停掉，走 startPipeline 会直接崩，实测进程当场死亡）
            ++GEN;
            Thread old = CUR_CAP_THREAD;
            if (old != null) {
                try { old.join(800); } catch (InterruptedException ignored) { }
            }
            VdinCapture.close();
            int rc = VdinCapture.open("/dev/video12", w, h);
            System.out.println("VDIN 重开 " + w + "x" + h + " rc=" + rc);
            if (rc == 0) startVdinPipeline(w, h);
            return;
        }
        startPipeline(1, w, h);
    }

    // ---------------- 遥控 ----------------

    /**
     * 控制端令牌：进程启动时生成，注入网页，控制类端点必须带上。
     *
     * 为什么需要：本服务没有鉴权，而 /key 能让任何能访问 8899 的东西操作电视。
     * 浏览器同源策略能挡住"你访问的恶意网页偷偷发按键"（它读不到本页，也就拿不到令牌）；
     * 但挡不住局域网里的其它主机——那要靠网络隔离，见 docs/API.md「安全边界」。
     */
    static final String TOKEN = initToken();

    /**
     * 令牌来源：优先 -Dtvss.token=xxx（便于脚本调用），否则随机生成。
     * **不要把它写进日志或仓库**（AGENTS.md §2.3）；需要时从页面里取，或用 -D 显式指定。
     */
    static String initToken() {
        String t = System.getProperty("tvss.token", "");
        if (t != null && t.length() >= 4) return t;
        byte[] b = new byte[6];
        try {
            java.security.SecureRandom.getInstance("SHA1PRNG").nextBytes(b);
        } catch (Throwable x) {
            new java.util.Random().nextBytes(b);
        }
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format("%02x", v));
        return sb.toString();
    }

    /** 按键白名单：只放遥控器上有的。**不放 POWER**（26 是开关，见 AGENTS.md §2.4） */
    static final java.util.Map<String, Integer> KEYS = new java.util.HashMap<>();
    static {
        KEYS.put("DPAD_UP", 19);       KEYS.put("DPAD_DOWN", 20);
        KEYS.put("DPAD_LEFT", 21);     KEYS.put("DPAD_RIGHT", 22);
        KEYS.put("DPAD_CENTER", 23);   KEYS.put("BACK", 4);
        KEYS.put("HOME", 3);           KEYS.put("MENU", 82);
        KEYS.put("VOLUME_UP", 24);     KEYS.put("VOLUME_DOWN", 25);
        KEYS.put("MUTE", 164);         KEYS.put("PLAY_PAUSE", 85);
        KEYS.put("NEXT", 87);          KEYS.put("PREVIOUS", 88);
        KEYS.put("ENTER", 66);         KEYS.put("DEL", 67);
        KEYS.put("SETTINGS", 176);     KEYS.put("TV_INPUT", 178);
        KEYS.put("NUM_0", 7);          KEYS.put("NUM_1", 8);
        KEYS.put("NUM_2", 9);          KEYS.put("NUM_3", 10);
        KEYS.put("NUM_4", 11);         KEYS.put("NUM_5", 12);
        KEYS.put("NUM_6", 13);         KEYS.put("NUM_7", 14);
        KEYS.put("NUM_8", 15);         KEYS.put("NUM_9", 16);
    }

    /** 取查询参数（不做 URL 解码；/key 的取值只允许 [A-Z_]） */
    static String param(String path, String name) {
        int q = path.indexOf('?');
        if (q < 0) return null;
        for (String kv : path.substring(q + 1).split("&")) {
            int e = kv.indexOf('=');
            if (e > 0 && kv.substring(0, e).equals(name)) return kv.substring(e + 1);
        }
        return null;
    }

    static boolean tokenOk(String t) { return TOKEN.equals(t); }

    // ---------------- 按键注入 ----------------
    //
    // 旧实现每次 exec("input keyevent N")。`/system/bin/input` 是个 shell 脚本，
    // 每次都要新起一个 ART 虚拟机：实测在这台电视上 **846 ms/次**（连 `input --help`
    // 纯启动都要 838 ms），连按还会各自 fork 一个 JVM 互相抢核。遥控因此迟一秒左右。
    //
    // 现在优先走 InputManager.injectInputEvent：一次 binder 调用，实测 1–3 ms，
    // Android keycode 语义与 `input keyevent` 完全一致（白名单不用改）。
    // 拿不到（隐藏 API 反射失败 / 没有 INJECT_EVENTS）就退回旧路径，但改成不等待
    // 进程退出——否则 /key 的 HTTP 响应还是会被拖到 800 ms 之后。
    static final java.util.concurrent.atomic.AtomicBoolean INJECT_READY =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    static final java.util.concurrent.atomic.AtomicLong INJECT_OK =
            new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong INJECT_FALLBACK =
            new java.util.concurrent.atomic.AtomicLong();
    static java.lang.reflect.Method M_INJECT;
    static Object INJECTOR;
    /** uninit / inputmanager / failed —— 给 /stats 显示当前走哪条路 */
    static volatile String INJECT_MODE = "uninit";

    /** 首次使用时初始化反射通道；失败则永久走兜底，只打印一次原因 */
    static synchronized boolean initInjector() {
        if (INJECT_READY.get()) return true;
        if ("failed".equals(INJECT_MODE)) return false;
        try {
            Class<?> im = Class.forName("android.hardware.input.InputManager");
            Object inst = im.getMethod("getInstance").invoke(null);
            M_INJECT = im.getMethod("injectInputEvent", android.view.InputEvent.class, int.class);
            INJECTOR = inst;
            INJECT_MODE = "inputmanager";
            INJECT_READY.set(true);
            System.out.println("injector: InputManager.injectInputEvent 可用");
            return true;
        } catch (Throwable t) {
            INJECT_MODE = "failed";
            System.out.println("injector: InputManager 不可用，退回 input 子进程 → " + t);
            return false;
        }
    }

    /** 用 InputManager 注入一次按下+抬起；返回 false 表示这条路径没成 */
    static boolean injectKeyFast(int code) {
        try {
            long now = android.os.SystemClock.uptimeMillis();
            android.view.KeyEvent down = new android.view.KeyEvent(
                    now, now, android.view.KeyEvent.ACTION_DOWN, code, 0);
            android.view.KeyEvent up = new android.view.KeyEvent(
                    now, now + 20, android.view.KeyEvent.ACTION_UP, code, 0);
            Object r1 = M_INJECT.invoke(INJECTOR, down, 0);   // 0 = MODE_ASYNC
            Object r2 = M_INJECT.invoke(INJECTOR, up, 0);
            return Boolean.TRUE.equals(r1) && Boolean.TRUE.equals(r2);
        } catch (Throwable t) {
            System.out.println("inject fast fail: " + t);
            return false;
        }
    }

    /** 发一个按键：优先 ~1 ms 的 binder 注入，失败退回 input 子进程（不阻塞响应） */
    static void sendKey(int code) {
        if (initInjector() && injectKeyFast(code)) {
            INJECT_OK.incrementAndGet();
            return;
        }
        INJECT_FALLBACK.incrementAndGet();
        try {
            // 不 waitFor：input 要 800+ ms，等它只会把 /key 的响应一起拖住
            Runtime.getRuntime().exec(new String[]{"input", "keyevent", String.valueOf(code)});
        } catch (Throwable t) {
            System.out.println("keyevent " + code + " fail: " + t);
        }
    }

    /** input text 要求空格写成 %s；其余特殊字符直接丢弃，避免被 input 自己解释 */
    static String forInputText(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 200; i++) {
            char c = s.charAt(i);
            if (c == ' ') sb.append("%s");
            else if (c >= 0x20 && c < 0x7f && c != '"' && c != '\\' && c != '%') sb.append(c);
        }
        return sb.toString();
    }

    static void sendText(String text) {
        String safe = forInputText(text);
        if (safe.isEmpty()) return;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"input", "text", safe});
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroy();
        } catch (Throwable t) {
            System.out.println("input text fail: " + t);
        }
    }

    // ---------------- 应用列表 / 启动应用 / 重启 ----------------
    //
    // 为什么不用 PackageManager：app_process 里想拿 Context 只能走
    // ActivityThread.systemMain()，实测在这台电视上会抛 InvocationTargetException
    // 并把整个进程带走（流直接断）。所以这里只用 `cmd package` 命令行拿组件，
    // 代价是拿不到应用中文名——下拉框里显示包名。
    /** 应用列表缓存（JSON），免得每次点开都重扫 */
    static volatile String APPS_JSON;
    static volatile long APPS_TS;
    static final long APPS_TTL_MS = 60 * 1000L;

    /** 扫一遍可启动组件：TV 应用走 LEANBACK_LAUNCHER，普通应用再补 LAUNCHER */
    static String appsJson() {
        long now = System.currentTimeMillis();
        String cached = APPS_JSON;
        if (cached != null && now - APPS_TS < APPS_TTL_MS) return cached;

        java.util.LinkedHashMap<String, String[]> uniq = new java.util.LinkedHashMap<>();
        String[] cats = {"android.intent.category.LEANBACK_LAUNCHER",
                         "android.intent.category.LAUNCHER"};
        for (String cat : cats) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c",
                        "cmd package query-activities --brief -a android.intent.action.MAIN -c "
                                + cat + " 2>/dev/null | grep '/'"});
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[1024];
                int n;
                while ((n = p.getInputStream().read(b)) > 0) bos.write(b, 0, n);
                p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
                for (String line : bos.toString("UTF-8").split("\\n")) {
                    String comp = line.trim();
                    int slash = comp.indexOf('/');
                    if (slash <= 0) continue;
                    String pkg = comp.substring(0, slash);
                    if (pkg.indexOf('.') < 0) continue;
                    // 同一包有多个入口时保留先出现的（LEANBACK 优先）
                    if (!uniq.containsKey(pkg)) uniq.put(pkg, new String[]{pkg, comp});
                }
            } catch (Throwable t) {
                System.out.println("apps: 枚举 " + cat + " 失败 → " + t);
            }
        }

        java.util.TreeMap<String, String[]> sorted = new java.util.TreeMap<>();
        for (String[] v : uniq.values()) sorted.put(v[0], v);
        java.util.List<String> items = new java.util.ArrayList<>();
        for (String[] v : sorted.values()) {
            items.add("{\"pkg\":\"" + jsonEscape(v[0]) + "\",\"label\":\"" + jsonEscape(v[0])
                    + "\",\"comp\":\"" + jsonEscape(v[1]) + "\"}");
        }
        String json = "{\"ok\":true,\"count\":" + items.size() + ",\"apps\":["
                + String.join(",", items) + "]}";
        APPS_JSON = json;
        APPS_TS = now;
        return json;
    }

    /**
     * 启动应用。`am` 每次也要起 JVM（约 850 ms），所以异步执行、立即返回，
     * 让画面自己体现结果。优先用页面传来的组件名，省掉一次解析。
     */
    static boolean launchApp(String pkg, String comp) {
        if (pkg == null || pkg.isEmpty()) return false;
        final String[] cmd = (comp != null && comp.indexOf('/') > 0)
                ? new String[]{"am", "start", "-n", comp}
                : new String[]{"am", "start", "-a", "android.intent.action.MAIN",
                        "-c", "android.intent.category.LEANBACK_LAUNCHER", "-p", pkg};
        try {
            new Thread(() -> {
                try {
                    Process p = Runtime.getRuntime().exec(cmd);
                    p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                    System.out.println("launch " + pkg + " via " + cmd[cmd.length - 1]);
                } catch (Throwable t) {
                    System.out.println("launch fail: " + t);
                }
            }, "launch").start();
            return true;
        } catch (Throwable t) {
            System.out.println("launch spawn fail: " + t);
            return false;
        }
    }

    /** 重启电视。先让 HTTP 响应发出去，再执行 reboot。 */
    static void rebootNow() {
        new Thread(() -> {
            try {
                Thread.sleep(800);
                System.out.println("reboot: 执行重启");
                Runtime.getRuntime().exec(new String[]{"reboot"});
            } catch (Throwable t) {
                System.out.println("reboot fail: " + t);
            }
        }, "reboot").start();
    }

    static final String PAGE_TEMPLATE =
        "<!doctype html><html lang=zh><head><meta charset=utf-8>"
      + "<meta name=viewport content='width=device-width,initial-scale=1,viewport-fit=cover'>"
      + "<title>电视画面</title>"
      + "<link rel=icon href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='7' fill='%231e1e26'/%3E%3Crect x='5' y='9' width='22' height='14' rx='2' fill='none' stroke='%232ecc71' stroke-width='2'/%3E%3Cpath d='M13.5 12.5l7 3.5-7 3.5z' fill='%232ecc71'/%3E%3C/svg%3E\">"
      + "<style>*{box-sizing:border-box;margin:0;padding:0}html,body{height:100%}"
      + "body{background:#0b0b0d;color:#d7d7dd;display:flex;flex-direction:column;"
      + "font:14px/1.5 -apple-system,system-ui,'PingFang SC','Microsoft YaHei',sans-serif;"
      + "-webkit-user-select:none;user-select:none;overflow:hidden}"
      + ".bar{display:flex;flex-wrap:wrap;align-items:center;gap:8px;padding:8px 12px;flex:0 0 auto;"
      + "background:#141419;border-bottom:1px solid #23232b}"
      + ".dot{width:9px;height:9px;border-radius:50%;background:#2ecc71;box-shadow:0 0 6px #2ecc71;flex:0 0 auto}"
      + ".dot.bad{background:#e74c3c;box-shadow:0 0 6px #e74c3c}.dot.idle{background:#8b8b93;box-shadow:none}"
      + ".stat{font-size:12px;color:#8b8b93;font-variant-numeric:tabular-nums;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}"
      + ".sp{flex:1}"
      + "button,select{background:#1e1e26;color:#d7d7dd;border:1px solid #2c2c36;border-radius:7px;padding:6px 10px;font-size:13px;cursor:pointer}"
      + ".wrap{flex:1 1 auto;min-height:0;display:flex;align-items:center;justify-content:center;padding:10px 12px;"
      + "--maxh:calc(100vh - 88px)}"
      + "@supports(height:100dvh){.wrap{--maxh:calc(100dvh - 88px)}}"
      + ".stage{position:relative;flex:0 0 auto;"
      + "width:min(var(--sw,1120px),100%,calc(var(--maxh)*16/9));"
      + "aspect-ratio:16/9;background:#000;border-radius:12px;overflow:hidden;"
      + "box-shadow:0 10px 40px #000b;border:1px solid #24242d}"
      + "#v{width:100%;height:100%;display:block;object-fit:contain;background:#000}"
      + "#v.hide{display:none}"
      + ".wrap:fullscreen{background:#000;padding:0}"
      + ".wrap:fullscreen .stage{width:100%;height:100%;max-width:none;aspect-ratio:auto;border:0;border-radius:0;box-shadow:none}"
      + ".wrap:fullscreen #pc{opacity:1}"
      + "#ov{position:absolute;right:10px;top:10px;display:none;font:12px/1.5 ui-monospace,SFMono-Regular,Consolas,monospace;"
      + "color:#c8c8d4;background:#000b;border:1px solid #2c2c36;border-radius:8px;padding:6px 9px;"
      + "pointer-events:none;backdrop-filter:blur(4px)}"
      + "#ov.on{display:block}"
      + "#pc{position:absolute;left:0;right:0;bottom:0;display:flex;flex-wrap:wrap;align-items:center;gap:6px;"
      + "padding:26px 12px 10px;background:linear-gradient(transparent,#000c 45%);"
      + "opacity:0;transition:opacity .18s}"
      + ".stage:hover #pc,.stage.touch #pc,.stage.pz #pc{opacity:1}"
      + ".lb{font-size:12px;color:#7a7a86;white-space:nowrap}"
      + "#pc button,#pc .seg button{padding:5px 11px;font-size:12.5px;border-radius:8px;"
      + "background:#1c1c24cc;border:1px solid #33333f;color:#dcdce4;cursor:pointer;backdrop-filter:blur(4px)}"
      + "#pc button:hover{border-color:#4a4a5a;background:#25252fee}"
      + "#pc .seg{display:flex;gap:3px;padding:3px;background:#1c1c24cc;border:1px solid #33333f;border-radius:9px}"
      + "#pc .seg button{border:0;background:transparent;padding:3px 9px;border-radius:6px}"
      + "#pc .seg button.on{background:#2ecc71;color:#0b0b0d;font-weight:600}"
      + "#pc .sp2{flex:1}"
      + ".pz::after{content:'已暂停';position:absolute;inset:0;display:flex;align-items:center;justify-content:center;"
      + "font-size:15px;color:#9a9aa3;background:#000a;pointer-events:none}"
      + "#off{position:absolute;inset:0;display:none;flex-direction:column;align-items:center;justify-content:center;gap:14px;background:#0b0b0d}"
      + "#off .t{font-size:16px;color:#9a9aa3}#off .s{font-size:12px;color:#63636b}"
      + "#off button{padding:10px 22px;font-size:15px;border-color:#3a3a46}"
      + "@media(max-width:680px){.stat{display:none}}"
      + "@media(hover:none){#pc{opacity:1}}"
      + "@media(min-width:1100px){body.rcopen .wrap{padding-right:238px}}"
      + "#rc{position:fixed;right:10px;top:56px;display:none;grid-template-columns:repeat(3,60px);gap:6px;"
      + "background:#141419f2;border:1px solid #2c2c36;border-radius:12px;padding:10px;z-index:9;box-shadow:0 6px 24px #000a;"
      + "max-height:calc(100vh - 20px);overflow:auto}"
      + "#rc.on{display:grid}"
      + "#rc .numpad{grid-column:span 3;display:grid;grid-template-columns:repeat(5,1fr);gap:6px}"
      + "#rc .pwr{grid-column:span 3;display:grid;grid-template-columns:1fr 1fr;gap:6px}"
      + "#rc button{padding:11px 0;font-size:15px;border-radius:9px;border:1px solid #2c2c36;background:#1e1e26;color:#d7d7dd;cursor:pointer}"
      + "#rc button:active{background:#2ecc71;color:#0b0b0d}"
      + "#rc .gap{visibility:hidden}"
      + "#rc .wide{grid-column:span 3;display:flex;gap:6px}"
      + "#rc .wide button{flex:0 0 64px}"
      + "#rc input{flex:1;min-width:0;background:#1e1e26;color:#d7d7dd;border:1px solid #2c2c36;border-radius:7px;padding:8px;font-size:13px}"
      + "#rc .wide select{flex:1;min-width:0;background:#1e1e26;color:#d7d7dd;border:1px solid #2c2c36;border-radius:7px;padding:8px;font-size:13px}"
      + "#rc .pwr button.reboot{border-color:#7a2f2f;color:#ff9a9a}"
      + "</style></head><body>"
      + "<div class=bar><span id=dot class='dot idle'></span><span class=stat id=s1>连接中…</span><span class=sp></span>"
      + "<button id=ab onclick=audio()>关闭声音</button>"
      + "<select id=asrc onchange=setaudio()>"
      + "<option value='TDMOUT_B' selected>电视声音</option>"
      + "<option value='PDMIN'>麦克风</option>"
      + "<option value='SPDIFIN'>SPDIF 输入</option>"
      + "</select>"
      + "<span class=lb>画质</span><select id=res onchange=setres()>"
      + "<option value='320,180'>320×180</option>"      + "<option value='480,270' selected>480×270</option>"
      + "<option value='640,360'>640×360</option>"
      + "<option value='960,540'>960×540</option>"
      + "<option value='1280,720'>1280×720</option>"
      + "<option value='1920,1080'>1920×1080</option>"
      + "</select>"
      + "<button onclick=toggleRC()>遥控</button></div>"
      + "<div id=rc class=on>"
      + "<div class=pwr><button onclick=\"pw('/wake')\">唤醒</button><button onclick=\"pw('/sleep')\">息屏</button></div>"
      + "<span class=gap></span><button onclick=\"rc('DPAD_UP')\">▲</button><span class=gap></span>"
      + "<button onclick=\"rc('DPAD_LEFT')\">◀</button><button onclick=\"rc('DPAD_CENTER')\">OK</button><button onclick=\"rc('DPAD_RIGHT')\">▶</button>"
      + "<span class=gap></span><button onclick=\"rc('DPAD_DOWN')\">▼</button><span class=gap></span>"
      + "<button onclick=\"rc('BACK')\">返回</button><button onclick=\"rc('HOME')\">主页</button><button onclick=\"rc('MENU')\">菜单</button>"
      + "<button onclick=\"rc('VOLUME_DOWN')\">音量−</button><button onclick=\"rc('MUTE')\">静音</button><button onclick=\"rc('VOLUME_UP')\">音量+</button>"
      + "<div class=numpad>"
      + "<button onclick=\"rc('NUM_1')\">1</button><button onclick=\"rc('NUM_2')\">2</button><button onclick=\"rc('NUM_3')\">3</button><button onclick=\"rc('NUM_4')\">4</button><button onclick=\"rc('NUM_5')\">5</button>"
      + "<button onclick=\"rc('NUM_6')\">6</button><button onclick=\"rc('NUM_7')\">7</button><button onclick=\"rc('NUM_8')\">8</button><button onclick=\"rc('NUM_9')\">9</button><button onclick=\"rc('NUM_0')\">0</button>"
      + "</div>"
      + "<div class=wide><input id=txt placeholder=\"输入文字…\" onkeydown=\"if(event.key==='Enter')rcText()\">"
      + "<button onclick=\"rcText()\">发送</button></div>"
      + "<div class=wide><select id=apps onchange=launchApp()><option value=''>启动应用…</option></select>"
      + "<button onclick=loadApps()>刷新</button></div>"
      + "<div class=wide><button class=reboot style=\"flex:1\" onclick=doReboot()>重启电视</button></div>"
      + "</div>"
      + "<div class=wrap id=wrap><div class=stage id=stage>"
      + "<canvas id=v></canvas>"
      + "<div id=ov></div>"
      + "<div id=off><div class=t>电视已息屏</div><div class=s>屏幕关闭时无法采集画面（回采的是显示管线）</div>"
      + "<button onclick=\"pw('/wake')\">唤醒电视</button></div>"
      + "<div id=pc>"
      + "<button id=pb onclick=togglePause()>⏸ 暂停</button>"
      + "<button onclick=shot()>截图</button>"
      + "<button onclick=fs()>⛶ 全屏</button>"
      + "<button onclick=toggleOv()>统计</button>"
      + "<span class=sp2></span>"
      + "<span class=lb>窗口</span>"
      + "<span class=seg id=seg>"
      + "<button data-sw='720px' onclick=\"setsize('720px',this)\">小</button>"
      + "<button data-sw='960px' onclick=\"setsize('960px',this)\">中</button>"
      + "<button data-sw='1280px' onclick=\"setsize('1280px',this)\">大</button>"
      + "<button data-sw='100%' onclick=\"setsize('100%',this)\">自适应</button>"
      + "</span></div></div></div>"
      + "<script>"
      + "const TK='__TK__';"
      + "function rc(c){fetch('/key?code='+c+'&t='+TK).then(r=>r.json()).then(j=>{if(!j.ok){AB.textContent='遥控失败';AB.title=j.err||'';}}).catch(e=>{AB.textContent='遥控失败';AB.title=String(e);});}"
      + "function rcText(){const i=document.getElementById('txt'),v=i.value;if(!v)return;"
      + "fetch('/text?s='+encodeURIComponent(v)+'&t='+TK).then(r=>r.json()).then(j=>{"
      + "if(!j.ok){AB.textContent='输入失败';AB.title=j.err||'';}else{i.value='';}}).catch(e=>{AB.textContent='输入失败';AB.title=String(e);});}"
      + "const V=document.getElementById('v'),CTX=V.getContext('2d'),D=document.getElementById('dot'),S=document.getElementById('s1'),AB=document.getElementById('ab'),OFF=document.getElementById('off');"
      + "let sleeping=false,audioOn=false,ac=null,t0=0,aborter=null,vctl=null,lastFrame=0,fc=0,fps=0,fbuf=new Uint8Array(0),paused=false;"
      + "const OV=document.getElementById('ov'),ST=document.getElementById('stage'),PB=document.getElementById('pb');"
      + "function findSeq(a,code,from){for(let i=from;i<a.length-1;i++){if(a[i]===((code>>8)&255)&&a[i+1]===(code&255))return i;}return -1;}"
      + "function drawFrame(bytes){const b=new Blob([bytes],{type:'image/jpeg'});"
      + " createImageBitmap(b).then(bm=>{if(V.width!==bm.width){V.width=bm.width;V.height=bm.height;}CTX.drawImage(bm,0,0);bm.close();}).catch(()=>{});}"
      + "function openVideo(){"
      + " try{if(vctl)vctl.abort();}catch(e){}"
      + " vctl=new AbortController();fbuf=new Uint8Array(0);lastFrame=Date.now();"
      + " fetch('/raw',{cache:'no-store',signal:vctl.signal}).then(r=>{"
      + "  if(!r.body||!r.body.getReader)return;"
      + "  const rd=r.body.getReader();"
      + "  (function pump(){rd.read().then(({done,value})=>{"
      + "   if(done)return;"
      + "   const d=new Uint8Array(fbuf.length+value.length);d.set(fbuf);d.set(value,fbuf.length);fbuf=d;"
      + "   let guard=0;"
      + "   while(guard++<20){"
      + "    const s=findSeq(fbuf,0xFFD8,0);"
      + "    if(s<0){if(fbuf.length>1)fbuf=fbuf.slice(fbuf.length-1);break;}"
      + "    const e=findSeq(fbuf,0xFFD9,s+2);"
      + "    if(e<0){if(s>0)fbuf=fbuf.slice(s);break;}"
      + "    const jpg=fbuf.slice(s,e+2);fbuf=fbuf.slice(e+2);"
      + "    drawFrame(jpg);lastFrame=Date.now();fc++;"
      + "   }"
      + "   pump();}).catch(()=>{});})();"
      + " }).catch(()=>{});"
      + "}"
      + "setInterval(function(){fps=fc;fc=0;if(!sleeping&&!paused&&Date.now()-lastFrame>4000){openVideo();}},1000);"
      + "function tick(){fetch('/stats',{cache:'no-store'}).then(r=>r.json()).then(j=>{"
      + " const awake=(j.awake!==false);"
      + " if(!awake){"
      + "  if(!sleeping){sleeping=true;OFF.style.display='flex';D.className='dot idle';V.style.display='none';}"
      + "  S.textContent='已息屏';"
      + " }else{"
      + "  if(sleeping){sleeping=false;OFF.style.display='none';V.style.display='block';openVideo();}"
      + "  S.textContent=fps+' fps · '+(j.kbps/1000).toFixed(2)+' Mbps · '+j.w+'×'+j.h+' · 看 '+j.clients+(j.audio?(' · 音峰 '+j.apeak):(' · 音频关'+(j.aerr?('（'+j.aerr.slice(0,18)+'…）'):'')));"
      + "  S.title=j.aerr||'';"
      + "  OV.innerHTML='<b style=\\'color:#2ecc71\\'>'+fps+'</b> fps<br>'+(j.kbps/1000).toFixed(2)+' Mbps<br>'+j.w+'×'+j.h+'<br>看 '+j.clients+' 端'+(j.audio?(' · 音峰 '+j.apeak):'');"
      + "  const rv=j.w+','+j.h,rs=document.getElementById('res');"
      + "  if(rs.value!==rv){for(let i=0;i<rs.options.length;i++){if(rs.options[i].value===rv){rs.selectedIndex=i;break;}}}"
      + "  D.className=(fps>0)?'dot':'dot bad';"
      + " }"
      + "}).catch(()=>{S.textContent='连不上服务';D.className='dot bad';});}"
      + "setInterval(tick,2000);tick();"
      + "function fs(){const el=document.getElementById('wrap');"
      + "if(document.fullscreenElement){document.exitFullscreen();}else if(el.requestFullscreen){el.requestFullscreen();}}"
      + "function shot(){"
      + " fetch('/snap',{cache:'no-store'}).then(r=>r.blob()).then(b=>{"
      + "  const u=URL.createObjectURL(b),a=document.createElement('a');"
      + "  const d=new Date(),p=n=>String(n).padStart(2,'0');"
      + "  a.href=u;a.download='tv-'+d.getFullYear()+p(d.getMonth()+1)+p(d.getDate())+'-'+p(d.getHours())+p(d.getMinutes())+p(d.getSeconds())+'.jpg';"
      + "  document.body.appendChild(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(u),3000);"
      + " }).catch(()=>{S.textContent='截图失败';});"
      + "}"
      + "function togglePause(){paused=!paused;"
      + " if(paused){try{if(vctl){vctl.abort();vctl=null;}}catch(e){}}else{openVideo();}"
      + " PB.textContent=paused?'▶ 继续':'⏸ 暂停';ST.classList.toggle('pz',paused);}"
      + "function toggleOv(){OV.classList.toggle('on');if(OV.classList.contains('on'))tick();}"
      + "function setsize(v,btn){ST.style.setProperty('--sw',v);"
      + " [].forEach.call(document.getElementById('seg').children,function(b){b.classList.toggle('on',b===btn);});"
      + " try{localStorage.setItem('tvss_sw',v);}catch(e){}"
      + " ST.classList.add('touch');clearTimeout(window.__szt);"
      + " window.__szt=setTimeout(function(){ST.classList.remove('touch');},2500);"
      + "}"
      + "function markSize(){const v=ST.style.getPropertyValue('--sw')||'min(100%,1120px)';"
      + " [].forEach.call(document.getElementById('seg').children,function(b){"
      + "  b.classList.toggle('on',(b.dataset.sw||'')===v);});}"
      + "(function(){let v='960px';try{v=localStorage.getItem('tvss_sw')||'960px';}catch(e){}"
      + " try{const q=new URLSearchParams(location.search).get('sw');if(q)v=q;}catch(e){}"
      + " ST.style.setProperty('--sw',v);markSize();"
      + " ST.classList.add('touch');setTimeout(function(){ST.classList.remove('touch');},3500);"
      + " if(window.innerWidth<900){document.getElementById('rc').classList.remove('on');}syncRC();})();"
      + "function toggleRC(){const r=document.getElementById('rc');r.classList.toggle('on');"
      + " document.body.classList.toggle('rcopen',r.classList.contains('on'));}"
      + "function syncRC(){document.body.classList.toggle('rcopen',document.getElementById('rc').classList.contains('on'));}"
      + "function loadApps(){fetch('/apps?t='+TK).then(r=>r.json()).then(j=>{if(!j.ok)return;"
      + "const s=document.getElementById('apps');while(s.options.length>1)s.remove(1);"
      + "j.apps.forEach(a=>{const o=document.createElement('option');o.value=a.pkg;o.textContent=a.label;o.dataset.comp=a.comp||'';s.appendChild(o);});"
      + "}).catch(()=>{});}"
      + "function launchApp(){const s=document.getElementById('apps');if(!s.value)return;"
      + "const p=s.value;const c=(s.options[s.selectedIndex].dataset.comp)||'';s.selectedIndex=0;"
      + "fetch('/app?t='+TK+'&pkg='+encodeURIComponent(p)+'&comp='+encodeURIComponent(c)).then(r=>r.json()).then(j=>{if(!j.ok)AB.textContent='启动失败';}).catch(()=>{});}"
      + "function doReboot(){if(!confirm('确定重启电视？约 90 秒后恢复，期间画面会中断'))return;"
      + "fetch('/reboot?t='+TK).then(r=>r.json()).then(()=>{document.getElementById('s1').textContent='正在重启，约 90 秒后恢复…';}).catch(()=>{});}"
      + "function setres(){const p=document.getElementById('res').value.split(',');"
      + "fetch('/set?w='+p[0]+'&h='+p[1]).then(r=>r.json()).then(()=>{openVideo();}).catch(()=>{});}"
      + "function pw(u){fetch(u).then(r=>r.json()).then(()=>{setTimeout(tick,800);setTimeout(()=>{if(!sleeping)openVideo();},1800);}).catch(()=>{});}"
      + "function stopAudio(){audioOn=false;try{if(aborter){aborter.abort();aborter=null;}}catch(e){}try{if(ac)ac.close();}catch(e){}ac=null;AB.textContent='开启声音';}"
      + "function audio(){if(audioOn){stopAudio();}else{startAudio();}}"
      + "function startAudio(){"
      + " if(audioOn)return;"
      + " try{ac=new (window.AudioContext||window.webkitAudioContext)();}catch(e){AB.textContent='不支持';return;}"
      + " audioOn=true;AB.textContent='关闭声音';"
      + " try{if(ac.state==='suspended')ac.resume();}catch(e){}"
      + " openPcm();"
      + "}"
      + "function openPcm(){"
      + " try{if(aborter)aborter.abort();}catch(e){}"
      + " aborter=new AbortController();"
      + " fetch('/pcm',{cache:'no-store',signal:aborter.signal}).then(r=>{"
      + "  if(!r.body||!r.body.getReader){AB.textContent='不支持流式音频';return;}"
      + "  const rd=r.body.getReader();let carry=new Uint8Array(0);"
      + "  (function pump(){rd.read().then(({done,value})=>{"
      + "   if(done)return;"
      + "   const d=new Uint8Array(carry.length+value.length);d.set(carry);d.set(value,carry.length);"
      + "   const u=d.length-(d.length%4);carry=d.slice(u);"
      + "   if(u>0&&ac){const i16=new Int16Array(d.buffer,0,u/2),n=u/4;"
      + "    const b=ac.createBuffer(2,n,48000),L=b.getChannelData(0),R=b.getChannelData(1);"
      + "    const G=8;for(let i=0;i<n;i++){L[i]=Math.max(-1,Math.min(1,i16[i*2]*G/32768));R[i]=Math.max(-1,Math.min(1,i16[i*2+1]*G/32768));}"
      + "    const s=ac.createBufferSource();s.buffer=b;s.connect(ac.destination);"
      + "    const now=ac.currentTime;if(t0<now+0.06)t0=now+0.06;s.start(t0);t0+=b.duration;}"
      + "   pump();}).catch(()=>{});})();"
      + " }).catch(()=>{AB.textContent='音频失败';});"
      + "}"
      + "function setaudio(){const s=document.getElementById('asrc').value;"
      + "fetch('/audio?src='+s).then(r=>r.json()).then(j=>{"
      + "if(!j.ok){AB.textContent='切源失败';AB.title=j.err||'未知错误';console.warn('set audio failed',j);"
      + "document.getElementById('asrc').value=j.src;return;}"
      + "AB.textContent=audioOn?'关闭声音':'开启声音';AB.title=j.src+' · peak='+j.peak;"
      + "document.getElementById('asrc').value=j.src;"
      + "setTimeout(()=>{if(audioOn)openPcm();},800);"
      + "}).catch(e=>{AB.textContent='切源失败';AB.title=String(e);});}"
      + "openVideo();"
      + "startAudio();"
      + "loadApps();"
      + "document.addEventListener('pointerdown',function(){try{if(ac&&ac.state==='suspended')ac.resume();}catch(e){}},{once:true});"
      + "</script></body></html>";

    /** 每次请求把令牌注入页面（模板本身不变） */
    static String page() {
        return PAGE_TEMPLATE.replace("__TK__", TOKEN);
    }

    static final String CRLF = new String(new char[]{13, 10});

    /** 最小 JSON 字符串转义（lastError 里可能有引号、路径、换行） */
    static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }

    static void send(OutputStream out, String ctype, byte[] body, boolean noStore) throws IOException {
        send(out, 200, ctype, body, noStore);
    }

    static void send(OutputStream out, int status, String ctype, byte[] body, boolean noStore)
            throws IOException {
        String reason = (status == 200) ? "OK"
                : (status == 400) ? "Bad Request"
                : (status == 403) ? "Forbidden"
                : (status == 503) ? "Service Unavailable" : "Error";
        String head = "HTTP/1.0 " + status + " " + reason + CRLF + "Content-Type: " + ctype + CRLF
                + (noStore ? ("Cache-Control: no-store" + CRLF) : "")
                + "Content-Length: " + body.length + CRLF + CRLF;
        out.write(head.getBytes("ISO-8859-1"));
        out.write(body);
        out.flush();
    }

    static void handle(Socket s) {
        boolean wasStream = false;
        boolean keepOpen = false;
        try {
            s.setSoTimeout(5000);
            // 关掉 Nagle：响应头/体是两次 write，带 Nagle 时会撞上对端延迟 ACK，
            // 每个小响应白等 ~40 ms（实测 /stats 48 ms，而 TCP 握手只要 16 ms）。
            // 推流侧每帧也是一次 write，关掉同样能少等每帧尾部那个 ACK。
            try { s.setTcpNoDelay(true); } catch (Throwable ignored) { }
            InputStream in = s.getInputStream();
            byte[] reqBuf = new byte[2048];
            int n = in.read(reqBuf);
            if (n <= 0) { s.close(); return; }
            String firstLine = new String(reqBuf, 0, n, "ISO-8859-1").split(CRLF)[0];
            String path = "/";
            String[] parts = firstLine.split(" ");
            if (parts.length > 1) path = parts[1];
            OutputStream out = s.getOutputStream();

            if (path.startsWith("/stream")) {
                ClientChannel.FrameFormat multipart = (o, f) -> {
                    o.write(("--frame" + CRLF + "Content-Type: image/jpeg" + CRLF
                            + "Content-Length: " + f.length + CRLF + CRLF).getBytes("ISO-8859-1"));
                    o.write(f);
                    o.write(CRLF.getBytes("ISO-8859-1"));
                };
                ClientChannel ch = new ClientChannel(s, out, multipart, "mjpeg",
                        VIDEO_QUEUE_FRAMES, VIDEO_QUEUE_BYTES);
                if (!registerVideoClient(ch)) {
                    send(out, 503, "text/plain; charset=utf-8",
                            ("too many viewers (max " + MAX_VIDEO_CLIENTS + ")\n").getBytes("UTF-8"), true);
                    ch.close();
                } else {
                    wasStream = true;
                    STREAM_CLIENTS.incrementAndGet();
                    try {
                        out.write(("HTTP/1.0 200 OK" + CRLF
                                + "Cache-Control: no-store" + CRLF
                                + "Connection: close" + CRLF
                                + "Content-Type: multipart/x-mixed-replace; boundary=frame" + CRLF + CRLF)
                                .getBytes("ISO-8859-1"));
                        out.flush();
                        ch.start();                       // 必须在响应头之后起写线程
                        waitForPeerClose(in, ch, 30 * 60 * 1000L);   // 与原实现一致的 30 分钟上限
                    } finally {
                        ch.close();                       // 任何异常路径都要释放名额
                    }
                }
            } else if (path.startsWith("/raw")) {
                ClientChannel ch = new ClientChannel(s, out, ClientChannel.RAW, "raw",
                        VIDEO_QUEUE_FRAMES, VIDEO_QUEUE_BYTES);
                if (!registerVideoClient(ch)) {
                    send(out, 503, "text/plain; charset=utf-8",
                            ("too many viewers (max " + MAX_VIDEO_CLIENTS + ")\n").getBytes("UTF-8"), true);
                    ch.close();
                } else {
                    wasStream = true;
                    STREAM_CLIENTS.incrementAndGet();
                    try {
                        out.write(("HTTP/1.0 200 OK" + CRLF
                                + "Cache-Control: no-store" + CRLF
                                + "Connection: close" + CRLF
                                + "Content-Type: application/octet-stream" + CRLF + CRLF)
                                .getBytes("ISO-8859-1"));
                        out.flush();
                        ch.start();
                        waitForPeerClose(in, ch, 30 * 60 * 1000L);
                    } finally {
                        ch.close();
                    }
                }
            } else if (path.startsWith("/snap")) {
                byte[] jpg = latestJpeg;
                if (jpg == null || IDLE) {
                    // 空闲停采后 latestJpeg 可能是几分钟前的旧帧；这里让采集线程补抓一帧
                    long before = latestSeq;
                    SNAP_WANTED = true;
                    long deadline = System.currentTimeMillis() + 2000;
                    while (latestSeq == before && System.currentTimeMillis() < deadline) {
                        try { Thread.sleep(50); } catch (InterruptedException ignored) { }
                    }
                    jpg = latestJpeg;
                }
                if (jpg == null) {
                    send(out, "text/plain", "no frame yet".getBytes("UTF-8"), true);
                } else {
                    send(out, "image/jpeg", jpg, true);
                }
            } else if (path.startsWith("/set")) {
                int nw = CUR_W, nh = CUR_H;
                int q = path.indexOf('?');
                if (q > 0) {
                    for (String kv : path.substring(q + 1).split("&")) {
                        String[] p2 = kv.split("=");
                        if (p2.length == 2) {
                            if (p2[0].equals("w")) nw = Integer.parseInt(p2[1]);
                            if (p2[0].equals("h")) nh = Integer.parseInt(p2[1]);
                        }
                    }
                }
                switchResolution(nw, nh);
                Thread.sleep(300);
                send(out, "application/json",
                        ("{\"w\":" + CUR_W + ",\"h\":" + CUR_H + "}").getBytes("UTF-8"), true);
            } else if (path.startsWith("/pcm")) {
                String head = "HTTP/1.0 200 OK" + CRLF
                        + "Content-Type: audio/L16;rate=48000;channels=2" + CRLF
                        + "Cache-Control: no-store" + CRLF
                        + "Connection: close" + CRLF + CRLF;
                out.write(head.getBytes("ISO-8859-1"));
                out.flush();
                AudioStream.addClient(s, out);
                keepOpen = true;
            } else if (path.startsWith("/wake")) {
                wakeUp();
                Thread.sleep(300);
                send(out, "application/json", ("{\"awake\":" + isAwake() + "}").getBytes("UTF-8"), true);
            } else if (path.startsWith("/sleep")) {
                sleepNow();
                Thread.sleep(300);
                send(out, "application/json", ("{\"awake\":" + isAwake() + "}").getBytes("UTF-8"), true);
            } else if (path.startsWith("/audio")) {
                String be = param(path, "backend");
                String src = param(path, "src");
                boolean ok;
                if (be != null) {
                    // 切换采集后端：tinycap（ALSA loopback）或 submix（AudioRecord REMOTE_SUBMIX）
                    if (!"tinycap".equals(be) && !"submix".equals(be)) {
                        send(out, 400, "application/json",
                                "{\"ok\":false,\"err\":\"backend 只能是 tinycap 或 submix\"}"
                                        .getBytes("UTF-8"), true);
                        return;
                    }
                    AudioStream.stop();
                    AudioStream.setBackend(be);
                    ok = AudioStream.start(0, 7);
                } else if (src == null) {
                    ok = true;                       // 不带参数 = 只查询当前状态
                } else {
                    ok = AudioStream.setSource(src);
                }
                send(out, "application/json",
                        ("{\"ok\":" + ok
                                + ",\"src\":\"" + jsonEscape(AudioStream.source()) + "\""
                                + ",\"backend\":\"" + jsonEscape(AudioStream.backend()) + "\""
                                + ",\"run\":" + AudioStream.isRunning()
                                + ",\"peak\":" + AudioStream.peak()
                                + ",\"err\":\"" + jsonEscape(AudioStream.lastError()) + "\"}")
                                .getBytes("UTF-8"), true);
            } else if (path.startsWith("/key")) {
                if (!tokenOk(param(path, "t"))) {
                    send(out, 403, "application/json",
                            "{\"ok\":false,\"err\":\"bad or missing token\"}".getBytes("UTF-8"), true);
                } else {
                    String code = param(path, "code");
                    Integer kc = (code == null) ? null : KEYS.get(code);
                    if (kc == null) {
                        send(out, 400, "application/json",
                                ("{\"ok\":false,\"err\":\"unknown code: "
                                        + jsonEscape(String.valueOf(code)) + "\"}")
                                        .getBytes("UTF-8"), true);
                    } else {
                        sendKey(kc);
                        send(out, "application/json",
                                ("{\"ok\":true,\"code\":\"" + code + "\"}").getBytes("UTF-8"), true);
                    }
                }
            } else if (path.startsWith("/text")) {
                if (!tokenOk(param(path, "t"))) {
                    send(out, 403, "application/json",
                            "{\"ok\":false,\"err\":\"bad or missing token\"}".getBytes("UTF-8"), true);
                } else {
                    String raw = param(path, "s");
                    String dec = "";
                    try {
                        if (raw != null) dec = java.net.URLDecoder.decode(raw, "UTF-8");
                    } catch (Throwable ignored) {
                    }
                    String safe = forInputText(dec);
                    if (safe.isEmpty()) {
                        send(out, 400, "application/json",
                                "{\"ok\":false,\"err\":\"empty or unsupported text (ASCII only)\"}"
                                        .getBytes("UTF-8"), true);
                    } else {
                        sendText(dec);
                        send(out, "application/json",
                                ("{\"ok\":true,\"len\":" + safe.length() + "}").getBytes("UTF-8"), true);
                    }
                }
            } else if (path.startsWith("/apps")) {
                if (!tokenOk(param(path, "t"))) {
                    send(out, 403, "application/json",
                            "{\"ok\":false,\"err\":\"bad or missing token\"}".getBytes("UTF-8"), true);
                } else {
                    send(out, "application/json", appsJson().getBytes("UTF-8"), true);
                }
            } else if (path.startsWith("/app")) {
                if (!tokenOk(param(path, "t"))) {
                    send(out, 403, "application/json",
                            "{\"ok\":false,\"err\":\"bad or missing token\"}".getBytes("UTF-8"), true);
                } else {
                    String pkg = param(path, "pkg");
                    // param() 不解码，组件名里的 %2F 要自己还原
                    String comp = param(path, "comp");
                    try {
                        if (comp != null) comp = java.net.URLDecoder.decode(comp, "UTF-8");
                    } catch (Throwable ignored) { }
                    boolean ok = launchApp(pkg, comp);
                    send(out, "application/json",
                            ("{\"ok\":" + ok + ",\"pkg\":\"" + jsonEscape(String.valueOf(pkg)) + "\"}")
                                    .getBytes("UTF-8"), true);
                }
            } else if (path.startsWith("/reboot")) {
                if (!tokenOk(param(path, "t"))) {
                    send(out, 403, "application/json",
                            "{\"ok\":false,\"err\":\"bad or missing token\"}".getBytes("UTF-8"), true);
                } else {
                    send(out, "application/json",
                            "{\"ok\":true,\"reboot\":true,\"note\":\"约 90 秒后恢复\"}".getBytes("UTF-8"), true);
                    rebootNow();
                }
            } else if (path.startsWith("/stats")) {
                String json = "{\"fps\":" + String.format(java.util.Locale.US, "%.2f", STAT_FPS)
                        + ",\"kbps\":" + String.format(java.util.Locale.US, "%.0f", STAT_KBPS)
                        + ",\"w\":" + STAT_W + ",\"h\":" + STAT_H
                        + ",\"clients\":" + clientCount()
                        + ",\"frames\":" + STAT_FRAMES
                        + ",\"audio\":" + AudioStream.isRunning()
                        + ",\"aclients\":" + AudioStream.clientCount()
                        + ",\"abytes\":" + AudioStream.bytesSent()
                        + ",\"asrc\":\"" + jsonEscape(AudioStream.source()) + "\""
                        + ",\"apeak\":" + AudioStream.peak()
                        + ",\"aerr\":\"" + jsonEscape(AudioStream.lastError()) + "\""
                        + ",\"aback\":\"" + jsonEscape(AudioStream.backend()) + "\""
                        + ",\"awake\":" + awakeCached()
                        + ",\"idle\":" + IDLE
                        + ",\"idleMs\":" + IDLE_MS
                        + ",\"inject\":\"" + INJECT_MODE + "\""
                        + ",\"injectOk\":" + INJECT_OK.get()
                        + ",\"injectFallback\":" + INJECT_FALLBACK.get() + "}";
                send(out, "application/json", json.getBytes("UTF-8"), true);
            } else {
                send(out, "text/html; charset=utf-8", page().getBytes("UTF-8"), false);
            }
            if (!keepOpen) s.close();
        } catch (Throwable t) {
            try { s.close(); } catch (Throwable ignored) {}
        } finally {
            if (wasStream) STREAM_CLIENTS.decrementAndGet();
        }
    }

    // ---------------- 入口 ----------------

    public static void main(String[] args) throws Exception {
        // 进程被 SIGTERM 杀掉时，先优雅停掉音频采集再退出。
        // 否则 tinycap 的 ALSA 句柄来不及释放，loopback 设备会卡在"能打开但不产数据"，
        // 只能重启电视恢复 —— 这正是"每次部署/重启服务都把音频搞坏"的原因。
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    AudioStream.stop();
                    System.out.println("shutdown: audio stopped cleanly");
                } catch (Throwable t) {
                    System.out.println("shutdown: audio stop failed: " + t);
                }
            }, "audio-shutdown"));
        } catch (Throwable ignored) {
        }
        String mode = args.length > 0 ? args[0] : "cap";
        int srcType = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        int w = args.length > 2 ? Integer.parseInt(args[2]) : 1920;
        int h = args.length > 3 ? Integer.parseInt(args[3]) : 1080;
        // 可选：用 -Dscreen=1920x1080 覆盖裁剪矩形（默认按 1080p 屏幕）
        String scr = System.getProperty("screen", "1920x1080");
        String[] sxy = scr.split("x");
        if (sxy.length == 2) {
            SCREEN_W = Integer.parseInt(sxy[0]);
            SCREEN_H = Integer.parseInt(sxy[1]);
        }
        System.out.println("screen=" + SCREEN_W + "x" + SCREEN_H + " out=" + w + "x" + h);

        try {
            native_ConnectScreenControl();
            System.out.println("connected");
        } catch (Throwable t) {
            System.out.println("connect failed: " + t);
        }

        try {
            if ("bench".equals(mode)) {
                bench(srcType, w, h, args.length > 4 ? Integer.parseInt(args[4]) : 10);
            } else if ("multibench".equals(mode)) {
                multibench(srcType, w, h,
                        args.length > 4 ? Integer.parseInt(args[4]) : 2,
                        args.length > 5 ? Integer.parseInt(args[5]) : 6);
            } else if ("rec".equals(mode)) {
                rec(w, h,
                        args.length > 4 ? Integer.parseInt(args[4]) : 25,
                        args.length > 5 ? Integer.parseInt(args[5]) : 1000000,
                        args.length > 6 ? Integer.parseInt(args[6]) : 10,
                        srcType,
                        args.length > 7 ? args[7] : "/data/local/tmp/rec.ts");
            } else if ("h264".equals(mode)) {
                int port = args.length > 4 ? Integer.parseInt(args[4]) : 8899;
                int bitrate = args.length > 5 ? Integer.parseInt(args[5]) : 600000;
                int fpsArg = args.length > 6 ? Integer.parseInt(args[6]) : 15;
                new H264Stream(w, h, fpsArg, bitrate, port).start();
            } else if ("serve".equals(mode) || "serve-vdin".equals(mode)) {
                USE_VDIN = "serve-vdin".equals(mode);
                int port = args.length > 4 ? Integer.parseInt(args[4]) : 8899;
                int outW = args.length > 5 ? Integer.parseInt(args[5]) : 960;
                int outH = args.length > 6 ? Integer.parseInt(args[6]) : 540;
                if (args.length > 7) JPEGQ = Integer.parseInt(args[7]);
                serve(srcType, w, h, port, outW, outH);
            } else {
                single(srcType, w, h, args.length > 4 ? args[4] : "/data/local/tmp/cap.raw");
            }
        } finally {
            try { native_ForceStop(); } catch (Throwable ignored) {}
        }
    }
}
