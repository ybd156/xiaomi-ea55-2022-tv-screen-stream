package com.droidlogic.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * 电视声音回采：Amlogic 的 LOOPBACK-A 设备 (/dev/snd/pcmC0D7c) 录的是播放混音。
 * 用系统自带的 tinycap 写 FIFO，本类读 FIFO 并广播给网页（裸 PCM，S16LE）。
 */
public class AudioStream {

    public static final int RATE = 48000;
    public static final int CH = 8;      // TDM-B 是 8 声道，必须按 8 声道抓
    public static final int OUT_CH = 2;  // 下混成 2 声道再发出去

    private static final List<Socket> clients = new ArrayList<>();
    private static volatile boolean running = false;
    private static Process proc;
    private static Thread pumpThread;
    private static volatile long bytesSent = 0;
    private static volatile String curSrc = "TDMOUT_B";
    private static volatile String lastError = "";

    public static synchronized void start(int card, int device) {
        if (running) return;
        running = true;
        final String fifo = "/data/local/tmp/audio.fifo";
        try {
            File f = new File(fifo);
            if (!f.exists()) {
                Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "mkfifo " + fifo});
                p.waitFor();
            }
        } catch (Throwable t) {
            lastError = "mkfifo: " + t;
            System.out.println(lastError);
        }
        try {
            proc = Runtime.getRuntime().exec(new String[]{
                    "tinycap", fifo,
                    "-D", String.valueOf(card),
                    "-d", String.valueOf(device),
                    "-c", String.valueOf(CH),
                    "-r", String.valueOf(RATE),
                    "-b", "16"
            });
            System.out.println("tinycap started, fifo=" + fifo);
        } catch (Throwable t) {
            lastError = "tinycap: " + t;
            System.out.println(lastError);
            running = false;
            return;
        }

        pumpThread = new Thread(() -> {
            byte[] buf = new byte[8192];
            byte[] out = new byte[8192];
            try (InputStream in = new FileInputStream(fifo)) {
                int n;
                while (running && (n = in.read(buf)) > 0) {
                    // 8ch -> 2ch：取 ch0/ch1
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
                    synchronized (clients) {
                        for (int i = clients.size() - 1; i >= 0; i--) {
                            Socket s = clients.get(i);
                            try {
                                OutputStream o = s.getOutputStream();
                                o.write(out, 0, off);
                                o.flush();
                            } catch (Throwable t) {
                                clients.remove(i);
                                try { s.close(); } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                lastError = "pump: " + t;
                System.out.println(lastError);
            }
            System.out.println("audio pump end");
        }, "audio-pump");
        pumpThread.setDaemon(true);
        pumpThread.start();
    }

    public static void addClient(Socket s) {
        synchronized (clients) {
            while (!clients.isEmpty()) {
                Socket old = clients.remove(0);
                try { old.close(); } catch (Throwable ignored) {}
                System.out.println("audio: dropped old client");
            }
            clients.add(s);
        }
        System.out.println("audio client connected, total=" + clients.size());
    }

    public static int clientCount() {
        synchronized (clients) { return clients.size(); }
    }

    public static long bytesSent() { return bytesSent; }
    public static String source() { return curSrc; }

    /** 切换回采来源：改 tinymix 路由 + 重启 tinycap */
    public static synchronized boolean setSource(String name) {
        String ctrl = null, val = null;
        if ("TDMOUT_A".equals(name) || "TDMOUT_B".equals(name) || "TDMOUT_C".equals(name)) {
            ctrl = "Loopback tdmin lb source"; val = name;
        } else if ("PDMIN".equals(name) || "SPDIFIN".equals(name) || "TDMIN_A".equals(name)
                || "TDMIN_B".equals(name) || "TDMIN_C".equals(name)) {
            ctrl = "Loopback datain source"; val = name;
        }
        if (ctrl == null) return false;
        try {
            Runtime.getRuntime().exec(new String[]{"tinymix", ctrl, val}).waitFor();
            System.out.println("audio source -> " + ctrl + " = " + val);
        } catch (Throwable t) {
            System.out.println("tinymix fail: " + t);
            return false;
        }
        curSrc = name;
        // 重启采集，让新路由生效
        stop();
        try { Thread.sleep(400); } catch (InterruptedException ignored) {}
        start(0, 7);
        return true;
    }

    public static synchronized void stop() {
        running = false;
        if (proc != null) { try { proc.destroy(); } catch (Throwable ignored) {} proc = null; }
    }
    public static String lastError() { return lastError; }
    public static boolean isRunning() { return running; }
}
