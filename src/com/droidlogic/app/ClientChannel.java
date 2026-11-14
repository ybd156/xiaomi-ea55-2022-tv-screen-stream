package com.droidlogic.app;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayDeque;

/**
 * 单客户端有界发送队列 + 独立写线程。
 *
 * 解决的问题：旧实现（main 分支）在采集/编码线程里直接 {@code out.write()}，
 * 一个不读数据的客户端会让 write 永久阻塞，进而拖死整个服务：
 *   - 视频端：该连接的处理线程永久卡在 write 上，30 分钟 deadline 永远等不到；
 *   - 音频端：写操作在 {@code synchronized (clients)} 锁内，一个卡住的客户端
 *     会让音频泵和 addClient() 一起锁死，所有观看者都没声音。
 *
 * 本类保证：
 *   1. {@link #offer(byte[])} 永不阻塞，只在极短的锁内做入队/丢弃；
 *   2. 队列满时丢**最旧**的帧（直播场景旧帧没有价值），只影响该客户端自己；
 *   3. 真正的写操作在独立线程里做，慢客户端只慢自己；
 *   4. {@link #close()} 关闭 socket，使阻塞中的 write 抛异常退出；
 *   5. 写线程超过 {@link #STALL_MS} 没有任何成功写入，就在下一次 offer() 时被判定僵死并关闭。
 *
 * 线程安全：offer()/close() 可被任意线程调用；offer() 只在内部锁内操作队列。
 */
public final class ClientChannel {

    /** 超过这个时间没有一次成功写入，视为僵死连接（由 offer() 触发清理） */
    public static final long STALL_MS = 30 * 1000L;

    /** 每帧如何写入流：/raw 直接写字节，/stream 需要 multipart 边界头 */
    public interface FrameFormat {
        void write(OutputStream out, byte[] frame) throws IOException;
    }

    /** 裸字节流（裸 JPEG / 裸 PCM）：帧与帧首尾相接 */
    public static final FrameFormat RAW = (out, frame) -> out.write(frame);

    private final Socket socket;
    private final OutputStream out;
    private final FrameFormat fmt;
    private final String tag;
    private final int maxFrames;
    private final long maxBytes;

    private final ArrayDeque<byte[]> queue = new ArrayDeque<byte[]>();
    private long queuedBytes;
    private boolean closed;
    private long droppedFrames;
    private long sentFrames;
    private volatile long lastWriteTs = System.currentTimeMillis();
    private Thread writer;

    public ClientChannel(Socket socket, OutputStream out, FrameFormat fmt,
                         String tag, int maxFrames, long maxBytes) {
        this.socket = socket;
        this.out = out;
        this.fmt = fmt;
        this.tag = tag;
        this.maxFrames = Math.max(1, maxFrames);
        this.maxBytes = Math.max(1024L, maxBytes);
    }

    /**
     * 起写线程。**必须在 HTTP 响应头写完之后调用**，
     * 否则写线程可能抢在响应头之前把帧发出去。
     */
    public void start() {
        synchronized (queue) {
            if (closed || writer != null) return;
            writer = new Thread(this::pump, "send-" + tag);
            writer.setDaemon(true);
            writer.start();
        }
    }

    /** 生产者调用：入队一帧。永不阻塞、永不抛异常。 */
    public void offer(byte[] frame) {
        if (frame == null || frame.length == 0) return;
        boolean stall = false;
        synchronized (queue) {
            if (closed) return;
            if (System.currentTimeMillis() - lastWriteTs > STALL_MS) {
                stall = true;                       // 写线程僵死，出锁后再关，避免持锁 close
            } else {
                queue.addLast(frame);
                queuedBytes += frame.length;
                while ((queue.size() > maxFrames || queuedBytes > maxBytes) && !queue.isEmpty()) {
                    byte[] old = queue.pollFirst();
                    if (old == null) break;
                    queuedBytes -= old.length;
                    droppedFrames++;
                }
                queue.notifyAll();
            }
        }
        if (stall) close();
    }

    public void close() {
        synchronized (queue) {
            if (closed) return;
            closed = true;
            queue.clear();
            queuedBytes = 0;
            queue.notifyAll();
        }
        try { socket.close(); } catch (Throwable ignored) { }
    }

    public boolean isClosed() {
        synchronized (queue) { return closed; }
    }

    public int queued() {
        synchronized (queue) { return queue.size(); }
    }

    public long dropped() {
        synchronized (queue) { return droppedFrames; }
    }

    public long sent() {
        synchronized (queue) { return sentFrames; }
    }

    private void pump() {
        try {
            while (true) {
                byte[] frame;
                synchronized (queue) {
                    while (!closed && queue.isEmpty()) {
                        try { queue.wait(200); } catch (InterruptedException ignored) { }
                    }
                    if (closed) return;
                    frame = queue.pollFirst();
                    if (frame != null) queuedBytes -= frame.length;
                }
                if (frame == null) continue;
                fmt.write(out, frame);
                out.flush();
                lastWriteTs = System.currentTimeMillis();
                synchronized (queue) { sentFrames++; }
            }
        } catch (Throwable t) {
            // 客户端断开 / socket 被 close() / 写失败：都走正常收尾
        } finally {
            close();
            System.out.println("send-" + tag + " end: sent=" + sent() + " dropped=" + dropped());
        }
    }
}
