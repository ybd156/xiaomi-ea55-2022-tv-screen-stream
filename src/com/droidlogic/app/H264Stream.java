package com.droidlogic.app;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.ParcelFileDescriptor;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * H.264 推流：RGBA 采集帧 -> NV12 -> MediaCodec(OMX.google.h264.encoder) -> MPEG-TS -> HTTP
 *
 * 目的：把带宽从 MJPEG 的约 4.8 Mbps 降到 0.5-0.8 Mbps。
 * 编码器仍是软件编码（这台电视没有硬件编码器），CPU 与 JPEG 相当。
 * 播放：VLC / mpv / ffplay 打开 http://<ip>:8899/ts
 */
public class H264Stream {

    private static final int TIMEOUT_US = 10000;

    /** 每个 /ts 客户端最多积压的块数 / 字节数（块大小随编码输出变化） */
    private static final int QUEUE_FRAMES = 32;
    private static final long QUEUE_BYTES = 512 * 1024;

    private final int w, h, fps, bitrate, port;
    private final List<ClientChannel> clients = new ArrayList<>();
    private volatile boolean running = true;
    private volatile byte[] csd0;      // SPS/PPS（Annex-B）

    public H264Stream(int w, int h, int fps, int bitrate, int port) {
        this.w = w;
        this.h = h;
        this.fps = fps;
        this.bitrate = bitrate;
        this.port = port;
    }

    /** RGBA_8888 -> NV12 (YUV420SemiPlanar) */
    static byte[] rgbaToNv12(byte[] rgba, int w, int h) {
        int frames = w * h;
        byte[] nv12 = new byte[frames * 3 / 2];
        int yIdx = 0, uvIdx = frames;
        for (int j = 0; j < h; j++) {
            int row = j * w * 4;
            for (int i = 0; i < w; i++) {
                int p = row + i * 4;
                int r = rgba[p] & 0xff;
                int g = rgba[p + 1] & 0xff;
                int b = rgba[p + 2] & 0xff;
                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                nv12[yIdx++] = (byte) (y < 16 ? 16 : (y > 235 ? 235 : y));
            }
        }
        for (int j = 0; j < h; j += 2) {
            int row0 = j * w * 4;
            int row1 = (j + 1 < h ? j + 1 : j) * w * 4;
            for (int i = 0; i < w; i += 2) {
                int p0 = row0 + i * 4;
                int p1 = row1 + i * 4;
                int r = ((rgba[p0] & 0xff) + (rgba[p0 + 4] & 0xff) + (rgba[p1] & 0xff) + (rgba[p1 + 4] & 0xff)) >> 2;
                int g = ((rgba[p0 + 1] & 0xff) + (rgba[p0 + 5] & 0xff) + (rgba[p1 + 1] & 0xff) + (rgba[p1 + 5] & 0xff)) >> 2;
                int b = ((rgba[p0 + 2] & 0xff) + (rgba[p0 + 6] & 0xff) + (rgba[p1 + 2] & 0xff) + (rgba[p1 + 6] & 0xff)) >> 2;
                int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                nv12[uvIdx++] = (byte) (u < 0 ? 0 : (u > 255 ? 255 : u));
                nv12[uvIdx++] = (byte) (v < 0 ? 0 : (v > 255 ? 255 : v));
            }
        }
        return nv12;
    }

    public void start() throws Exception {
        MediaCodec codec = MediaCodec.createEncoderByType("video/avc");
        MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", w, h);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();

        // 编码输出 -> 管道 -> 广播线程
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor.AutoCloseOutputStream pipeOut =
                new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
        final TsMuxer muxer = new TsMuxer(pipeOut);

        Thread pump = new Thread(() -> {
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    // 拷贝一份：buf 在下一次 read 时会被覆盖
                    byte[] chunk = java.util.Arrays.copyOf(buf, n);
                    ClientChannel[] snapshot;
                    synchronized (clients) {
                        if (clients.isEmpty()) continue;
                        snapshot = clients.toArray(new ClientChannel[0]);
                    }
                    // 不持锁写 socket：慢端只丢自己的数据，不阻塞编码和推流
                    for (ClientChannel ch : snapshot) ch.offer(chunk);
                }
            } catch (Throwable t) {
                System.out.println("pump end: " + t);
            }
        }, "ts-pump");
        pump.setDaemon(true);
        pump.start();

        startHttp();
        muxer.writePsi();

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long frames = 0, bytes = 0;
        long statStart = System.currentTimeMillis();
        long ptsUs = 0;
        long frameDurUs = 1000000L / fps;
        long lastSyncMs = 0;
        long auCount = 0;

        while (running) {
            byte[] rgba = ScreenControlManager.grab(1, w, h);
            if (rgba == null || rgba.length != w * h * 4) {
                Thread.sleep(20);
                continue;
            }
            byte[] nv12 = rgbaToNv12(rgba, w, h);

            int inIdx = codec.dequeueInputBuffer(TIMEOUT_US);
            if (inIdx >= 0) {
                ByteBuffer in = codec.getInputBuffer(inIdx);
                in.clear();
                in.put(nv12);
                codec.queueInputBuffer(inIdx, 0, nv12.length, ptsUs, 0);
                ptsUs += frameDurUs;
                frames++;
            }

            // 每秒请求一次 IDR，保证中途接入的播放器能解码
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastSyncMs >= 1000) {
                lastSyncMs = nowMs;
                try {
                    android.os.Bundle b = new android.os.Bundle();
                    b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                    codec.setParameters(b);
                } catch (Throwable t) {
                    System.out.println("requestSync failed: " + t);
                }
            }

            int outIdx;
            while ((outIdx = codec.dequeueOutputBuffer(info, 0)) >= 0) {
                if (info.size > 0) {
                    if (csd0 == null) {
                        try {
                            MediaFormat of = codec.getOutputFormat();
                            // SPS 和 PPS 可能分别放在 csd-0 / csd-1，必须都带上
                            java.io.ByteArrayOutputStream cs = new java.io.ByteArrayOutputStream();
                            for (String key : new String[]{"csd-0", "csd-1"}) {
                                ByteBuffer b = of.containsKey(key) ? of.getByteBuffer(key) : null;
                                if (b != null) {
                                    byte[] tmp = new byte[b.remaining()];
                                    b.get(tmp);
                                    cs.write(tmp);
                                    System.out.println(key + " " + tmp.length + " bytes");
                                }
                            }
                            if (cs.size() > 0) csd0 = cs.toByteArray();
                        } catch (Throwable t) {
                            System.out.println("csd read failed: " + t);
                        }
                    }
                    if (csd0 == null) {
                        codec.releaseOutputBuffer(outIdx, false);
                        continue;
                    }
                    ByteBuffer out = codec.getOutputBuffer(outIdx);
                    out.position(info.offset);
                    out.limit(info.offset + info.size);
                    byte[] au = new byte[info.size];
                    out.get(au);
                    boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    // 没有 IDR 时也要周期性带 SPS/PPS，否则中途接入解不了
                    // 每一帧都带上 SPS/PPS：中途接入的播放器立刻就能解（19 字节/帧，可忽略）
                    boolean injectCsd = csd0 != null;
                    auCount++;
                    byte[] payload = au;
                    if (injectCsd) {
                        payload = new byte[csd0.length + au.length];
                        System.arraycopy(csd0, 0, payload, 0, csd0.length);
                        System.arraycopy(au, 0, payload, csd0.length, au.length);
                    }
                    try {
                        muxer.writeVideo(payload, info.presentationTimeUs, key);
                        bytes += payload.length;
                    } catch (Throwable t) {
                        System.out.println("mux failed: " + t);
                    }
                }
                codec.releaseOutputBuffer(outIdx, false);
            }

            long now = System.currentTimeMillis();
            if (now - statStart >= 10000) {
                System.out.printf("H264STAT fps=%.1f kbps=%.0f clients=%d%n",
                        frames * 1000.0 / (now - statStart),
                        bytes * 8 / 1000.0 / ((now - statStart) / 1000.0),
                        clientCount());
                frames = 0;
                bytes = 0;
                statStart = now;
            }
        }
    }

    private int clientCount() {
        synchronized (clients) {
            int n = 0;
            for (ClientChannel c : clients) if (!c.isClosed()) n++;
            return n;
        }
    }

    private void startHttp() {
        Thread t = new Thread(() -> {
            try {
                ServerSocket ss = new ServerSocket(port);
                System.out.println("H264 LISTEN " + port);
                while (running) {
                    Socket s = ss.accept();
                    Thread th = new Thread(() -> handle(s), "http");
                    th.setDaemon(true);
                    th.start();
                }
            } catch (Throwable e) {
                System.out.println("http: " + e);
            }
        }, "http");
        t.setDaemon(true);
        t.start();
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(5000);
            byte[] reqBuf = new byte[2048];
            int n = s.getInputStream().read(reqBuf);
            if (n <= 0) { s.close(); return; }
            String path = "/";
            String[] parts = new String(reqBuf, 0, n, "ISO-8859-1").split("\r\n")[0].split(" ");
            if (parts.length > 1) path = parts[1];
            OutputStream out = s.getOutputStream();

            if (path.startsWith("/ts")) {
                out.write(("HTTP/1.0 200 OK\r\n"
                        + "Content-Type: video/mp2t\r\n"
                        + "Cache-Control: no-store\r\n"
                        + "Connection: close\r\n\r\n").getBytes("ISO-8859-1"));
                out.flush();
                ClientChannel ch = new ClientChannel(s, out, ClientChannel.RAW, "ts",
                        QUEUE_FRAMES, QUEUE_BYTES);
                synchronized (clients) {
                    for (int i = clients.size() - 1; i >= 0; i--) {
                        if (clients.get(i).isClosed()) clients.remove(i);
                    }
                    clients.add(ch);
                }
                ch.start();          // 必须在响应头之后起写线程
                System.out.println("client connected, total=" + clientCount());
            } else {
                String ip = getLocalIp();
                String html = "<html><head><meta charset=\"utf-8\"><title>TV H.264</title></head>"
                        + "<body style=\"background:#111;color:#ddd;font-family:sans-serif;padding:16px\">"
                        + "<h3>电视 H.264 流</h3>"
                        + "<p>用 VLC / mpv / ffplay 打开：</p>"
                        + "<pre>http://" + ip + ":" + port + "/ts</pre>"
                        + "<p>ffplay：<code>ffplay -fflags nobuffer -flags low_delay http://" + ip + ":" + port + "/ts</code></p>"
                        + "</body></html>";
                byte[] body = html.getBytes("UTF-8");
                out.write(("HTTP/1.0 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                        + body.length + "\r\n\r\n").getBytes("ISO-8859-1"));
                out.write(body);
                out.flush();
                s.close();
            }
        } catch (Throwable t) {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    private String getLocalIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs = java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Throwable ignored) {}
        return "192.168.250.11";
    }
}
