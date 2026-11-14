package com.droidlogic.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * 电视声音回采：直接从 TDM-B 的采集端 (pcmC0D1c) 取播放混音。
 * （LOOPBACK-A / pcmC0D7c 只能读到静音，且一个 period 就停，已弃用。）
 * 用系统自带的 tinycap 写 FIFO，本类读 FIFO 并广播给网页（裸 PCM，S16LE）。
 */
public class AudioStream {

    public static final int RATE = 48000;
    public static final int CH = 8;      // LOOPBACK-A 是 8 声道，必须按 8 声道抓
    public static final int OUT_CH = 2;  // 下混成 2 声道再发出去

    /** 每个音频客户端最多积压的块数 / 字节数（一块约 2 KB，约 94 块/秒） */
    private static final int QUEUE_FRAMES = 32;
    private static final long QUEUE_BYTES = 64 * 1024;

    private static final List<ClientChannel> clients = new ArrayList<>();
    private static volatile boolean running = false;
    private static Process proc;
    private static Thread pumpThread;
    private static volatile long bytesSent = 0;
    private static volatile String curSrc = "TDMOUT_B";
    private static volatile String lastError = "";
    /** 最近约 1 秒的采集峰值（0..32767）：用来判断"这个音源到底有没有信号" */
    private static volatile int lastPeak = 0;
    private static volatile long peakWindowStart = 0;
    /** 是否是我们主动 stop()：用于区分"正常切源"和"采集意外中断" */
    private static volatile boolean stopping = false;

    /**
     * 启动音频采集。返回 false 表示没起来，原因见 {@link #lastError()}。
     *
     * 旧实现不校验 tinycap 是否真的活着：设备被占用（EBUSY）或参数不支持时
     * tinycap 会立刻退出，但代码以为成功，running 停在 true，
     * 之后任何 start() 都被开头的 `if (running) return;` 挡掉 —— 音频就再也起不来了。
     */
    public static synchronized boolean start(int card, int device) {
        if (running) return true;
        running = true;
        lastError = "";
        final String fifo = "/data/local/tmp/audio.fifo";
        // 每次启动都重建 FIFO：进程内反复 start/stop 时，旧的 FIFO 会残留读写端状态，
        // 累积几次后新 tinycap 和新泵就对不上（表现为 audio=true 但 abytes 不涨）。
        // run_srv.sh 启动时那句 `rm -f audio.fifo` 正是同一个道理。
        try {
            new File(fifo).delete();
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "mkfifo " + fifo});
            p.waitFor();
        } catch (Throwable t) {
            lastError = "mkfifo: " + t;
            System.out.println(lastError);
        }
        // 判定标准是「泵还活着」，不是「tinycap 进程还在」：
        // tinycap 会先阻塞在打开 FIFO 上（此时进程是活的），等泵把 FIFO 打开之后
        // 它才去开 PCM 设备，撞 EBUSY 才退出。所以必须等泵起来之后再判一次。
        for (int attempt = 1; attempt <= 3; attempt++) {
            running = true;
            stopping = false;
            // 开设备前确认上一次的句柄真的放掉了（否则会 EBUSY，甚至把 DMA 搞卡）
            waitDeviceFree(1500);
            try {
                proc = launchTinycap(fifo, card, device);
            } catch (Throwable t) {
                lastError = "tinycap 启动异常: " + t;
                System.out.println(lastError);
                running = false;
                lastPeak = 0;
                return false;
            }
            try { Thread.sleep(250); } catch (InterruptedException ignored) { }
            if (!proc.isAlive()) {
                lastError = "tinycap 第 " + attempt + " 次启动后立即退出: " + drainQuietly(proc);
                System.out.println(lastError);
                proc = null;
                running = false;
                if (attempt < 3) { try { Thread.sleep(300); } catch (InterruptedException ignored) { } }
                continue;
            }
            startPump(fifo);
            try { Thread.sleep(700); } catch (InterruptedException ignored) { }
            if (running && proc != null && proc.isAlive()) {
                System.out.println("tinycap started (attempt " + attempt + "), fifo=" + fifo);
                return true;
            }
            String why = (proc != null) ? drainQuietly(proc) : "(进程已退出)";
            lastError = "tinycap 第 " + attempt + " 次启动后随即退出（泵收到 EOF）: " + why;
            System.out.println(lastError);
            stop();
            if (attempt < 3) { try { Thread.sleep(300); } catch (InterruptedException ignored) { } }
        }
        running = false;
        lastPeak = 0;
        return false;
    }

    /** 起采集泵线程：读 FIFO → 2ch 直通（CH==2 时就是原样）→ 投递给各客户端 */
    static void startPump(final String fifo) {

        pumpThread = new Thread(() -> {
            byte[] buf = new byte[8192];
            byte[] out = new byte[8192];
            try (InputStream in = new FileInputStream(fifo)) {
                int n;
                while (running && (n = in.read(buf)) > 0) {
                    // CH==2 时就是原样拷贝（ch0/ch1）；CH==8 时下混取前两声道
                    int frames = n / (CH * 2);
                    int off = 0;
                    for (int i = 0; i < frames; i++) {
                        int base = i * CH * 2;
                        out[off++] = buf[base];
                        out[off++] = buf[base + 1];
                        out[off++] = buf[base + 2];
                        out[off++] = buf[base + 3];
                    }
                    if (off == 0) continue;
                    bytesSent += off;
                    // 峰值：回答"切到这个音源到底有没有信号"（旧实现没有任何可观测性）
                    int peak = 0;
                    for (int i = 0; i + 1 < off; i += 2) {
                        int v = (short) (((out[i + 1] & 0xff) << 8) | (out[i] & 0xff));
                        int a = v < 0 ? -v : v;
                        if (a > peak) peak = a;
                    }
                    long nowMs = System.currentTimeMillis();
                    if (nowMs - peakWindowStart >= 1000) {
                        lastPeak = peak;
                        peakWindowStart = nowMs;
                    } else if (peak > lastPeak) {
                        lastPeak = peak;
                    }
                    // 拷贝一份：out 缓冲在下一次 read 时会被覆盖
                    byte[] frame = java.util.Arrays.copyOf(out, off);
                    ClientChannel[] snapshot;
                    synchronized (clients) {
                        if (clients.isEmpty()) continue;
                        snapshot = clients.toArray(new ClientChannel[0]);
                    }
                    // 不再持锁写 socket：慢端只丢自己的音频，不阻塞泵线程和其他客户端
                    for (ClientChannel ch : snapshot) ch.offer(frame);
                }
            } catch (Throwable t) {
                lastError = "pump: " + t;
                System.out.println(lastError);
            } finally {
                // 泵线程退出（tinycap 死了 / FIFO 断了）必须把状态复位，
                // 否则 start() 开头的 `if (running) return;` 会让音频永远起不来
                running = false;
                lastPeak = 0;
                if (!stopping) {
                    // 不是我们主动停的 → 采集是中途断掉的，必须留个原因给 /stats 的 aerr
                    lastError = "采集中断（tinycap 已退出或 FIFO 断流）";
                    System.out.println(lastError);
                }
            }
            System.out.println("audio pump end");
        }, "audio-pump");
        pumpThread.setDaemon(true);
        pumpThread.start();
    }

    /**
     * 注册音频客户端。
     *
     * 策略不变：**只保留最新一个**（见 docs/API.md「aclients 上限 1」）。
     * 变的是发送方式：旧实现在泵线程里持有 clients 锁逐个同步写 socket，
     * 一个不读数据的客户端会锁死整个音频泵（所有人一起没声音）；
     * 现在每端一个有界队列 + 独立写线程，慢端只丢自己的音频。
     *
     * 必须在 HTTP 响应头写完之后调用，否则写线程可能抢在响应头之前发数据。
     */
    public static void addClient(Socket s, OutputStream out) {
        ClientChannel ch = new ClientChannel(s, out, ClientChannel.RAW, "pcm",
                QUEUE_FRAMES, QUEUE_BYTES);
        List<ClientChannel> old = new ArrayList<>();
        synchronized (clients) {
            old.addAll(clients);
            clients.clear();
            clients.add(ch);
        }
        for (ClientChannel c : old) c.close();
        if (!old.isEmpty()) System.out.println("audio: dropped old client");
        ch.start();
        System.out.println("audio client connected, total=" + clientCount());
    }

    public static int clientCount() {
        synchronized (clients) {
            int n = 0;
            for (ClientChannel c : clients) if (!c.isClosed()) n++;
            return n;
        }
    }

    public static long bytesSent() { return bytesSent; }
    public static String source() { return curSrc; }

    /** 枚举里没有 off，用空闲的内部 TDM 输入当"关掉这一路"的占位源 */
    static final String IDLE_SRC = "TDMIN_C";

    /**
     * 切换回采来源。
     *
     * **loopback 是把两路输入相加的**：`Loopback tdmin lb source` 和
     * `Loopback datain source` 是两个独立控件，旧实现每次只改一路，另一路一直开着，
     * 所以听起来"换音源没作用"（改了一路，另一路的声音还在）。
     * 实测佐证：六个源抓到的字节数完全一样；选 PDMIN(麦克风) 时居然能采到
     * 灌进 TDM-B 播放的测试音——麦克风不可能采到，只能是两路在相加。
     *
     * 现在改成：先把另一路指到空闲源（隔离），再设目标路，两路都回读校验。
     */
    public static synchronized boolean setSource(String name) {
        String ctrl = null, val = null, otherCtrl = null;
        if ("TDMOUT_A".equals(name) || "TDMOUT_B".equals(name) || "TDMOUT_C".equals(name)) {
            ctrl = "Loopback tdmin lb source"; val = name;
            otherCtrl = "Loopback datain source";
        } else if ("PDMIN".equals(name) || "SPDIFIN".equals(name) || "TDMIN_A".equals(name)
                || "TDMIN_B".equals(name) || "TDMIN_C".equals(name)) {
            ctrl = "Loopback datain source"; val = name;
            otherCtrl = "Loopback tdmin lb source";
        }
        if (ctrl == null) {
            lastError = "未知音源: " + name;
            return false;
        }

        // 1. 先把另一路隔离到空闲源（除非目标本身就是那个空闲源）
        if (otherCtrl != null && !IDLE_SRC.equals(name)) {
            String ro = execCapture("tinymix", otherCtrl, IDLE_SRC);
            if (!ro.startsWith("exit=0")) {
                lastError = "tinymix \"" + otherCtrl + "\" \"" + IDLE_SRC + "\" 失败 → " + ro;
                System.out.println(lastError);
                return false;
            }
            System.out.println("audio source -> " + otherCtrl + " = " + IDLE_SRC + "（隔离另一路）");
        }

        // 2. 设目标路，并检查退出码
        String r1 = execCapture("tinymix", ctrl, val);
        if (!r1.startsWith("exit=0")) {
            lastError = "tinymix \"" + ctrl + "\" \"" + val + "\" 失败 → " + r1;
            System.out.println(lastError);
            return false;
        }
        // 3. 回读两路，确认都到位
        String r2 = execCapture("tinymix", ctrl);
        if (r2.indexOf(val) < 0) {
            lastError = "回读不一致，目标路没变 → " + r2;
            System.out.println(lastError);
            return false;
        }
        if (otherCtrl != null && !IDLE_SRC.equals(name)) {
            String r3 = execCapture("tinymix", otherCtrl);
            if (r3.indexOf(IDLE_SRC) < 0) {
                lastError = "回读不一致，隔离路没变 → " + r3;
                System.out.println(lastError);
                return false;
            }
        }
        System.out.println("audio source -> " + ctrl + " = " + val);

        // 只改 mixer，不重启 tinycap：反复开关 ALSA 设备会让 loopback 退化到
        // "能打开但不产数据"，只能重启电视恢复。
        curSrc = name;
        return true;
    }

    public static synchronized void stop() {
        stopping = true;
        running = false;
        Process p = proc;
        proc = null;
        if (p != null) {
            // 先 SIGTERM 让它自己退出。**不要一上来就 SIGKILL**：
            // 强杀会让 ALSA 来不及释放设备，loopback 的 DMA 会卡住（TODDR time out），
            // 之后任何采集都只能读到预填的一个 buffer，只能重启电视才能恢复。
            try { p.destroy(); } catch (Throwable ignored) { }
            try {
                if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    System.out.println("tinycap 3 秒未退出，强制结束");
                    p.destroyForcibly();
                    p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (Throwable ignored) { }
        }
        Thread t = pumpThread;
        pumpThread = null;
        if (t != null) {
            try { t.join(500); } catch (InterruptedException ignored) { }
        }
        // 等设备真的变成 closed 再返回，避免下一次 start() 撞 EBUSY 或把 DMA 搞卡
        if (!waitDeviceFree(2000)) {
            System.out.println("警告：/dev/snd/pcmC0D7c 仍被占用");
        }
        lastPeak = 0;
    }

    /** 等 loopback 设备真正释放（hw_params 变成 closed），最多 ms 毫秒 */
    static boolean waitDeviceFree(long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            if (isDeviceClosed()) return true;
            try { Thread.sleep(100); } catch (InterruptedException ignored) { }
        }
        return isDeviceClosed();
    }

    static boolean isDeviceClosed() {
        try (InputStream in = new FileInputStream("/proc/asound/card0/pcm7c/sub0/hw_params")) {
            byte[] b = new byte[256];
            int n = in.read(b);
            if (n <= 0) return true;
            String s = new String(b, 0, n, "UTF-8").trim();
            return s.isEmpty() || s.equals("closed");
        } catch (Throwable t) {
            return true;      // 读不到就当已释放，不阻塞启动
        }
    }

    /** 跑一条命令并收集输出，返回 "exit=<code>|<stdout+stderr>"；超时 3 秒 */
    static String execCapture(String... cmd) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            long deadline = System.currentTimeMillis() + 3000;
            boolean exited = false;
            while (!exited) {
                drainTo(in, bos);
                if (!p.isAlive()) {
                    drainTo(in, bos);           // 进程退出后管道里可能还有残留
                    exited = true;
                } else if (System.currentTimeMillis() > deadline) {
                    p.destroy();
                    exited = true;
                } else {
                    Thread.sleep(10);
                }
            }
            int code;
            if (p.isAlive()) {
                code = -2;                      // 超时
            } else {
                code = p.waitFor();
            }
            return "exit=" + code + "|" + bos.toString("UTF-8").trim();
        } catch (Throwable t) {
            return "exit=-1|" + t;
        }
    }

    /** 把 InputStream 里当前可读的字节全部倒进 bos（不阻塞） */
    static void drainTo(InputStream in, java.io.ByteArrayOutputStream bos) {
        try {
            byte[] b = new byte[256];
            while (in.available() > 0) {
                int n = in.read(b, 0, Math.min(in.available(), b.length));
                if (n <= 0) break;
                bos.write(b, 0, n);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 起一个 tinycap 进程（把 device 1 / pcmC0D1c 的 2 声道数据写进 FIFO） */
    static Process launchTinycap(String fifo, int card, int device) throws java.io.IOException {
        return Runtime.getRuntime().exec(new String[]{
                "tinycap", fifo,
                "-D", String.valueOf(card),
                "-d", String.valueOf(device),
                "-c", String.valueOf(CH),
                "-r", String.valueOf(RATE),
                "-b", "16"
        });
    }

    /** 读掉一个已退出进程的残余输出（用于把 tinycap 的错误原文带进 lastError） */
    static String drainQuietly(Process p) {
        try {
            if (p == null) return "";
            InputStream in = p.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[256];
            while (in.available() > 0) {
                int n = in.read(b, 0, Math.min(in.available(), b.length));
                if (n <= 0) break;
                bos.write(b, 0, n);
            }
            String s = bos.toString("UTF-8").trim();
            return s.isEmpty() ? "(无输出)" : s;
        } catch (Throwable t) {
            return "(读取失败: " + t + ")";
        }
    }

    public static String lastError() { return lastError; }
    public static boolean isRunning() { return running; }
    /** 最近约 1 秒的采集峰值，0 表示这个音源当前没有信号 */
    public static int peak() { return lastPeak; }
}
