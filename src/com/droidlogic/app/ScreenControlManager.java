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
        startPipeline(srcType, w, h);

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
    static void startPipeline(final int srcType, final int w, final int h) {
        final int gen = ++GEN;
        final int quality = JPEGQ;
        final Bitmap src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        final Object lock = new Object();
        final byte[][] slot = new byte[1][];
        final long[] produced = new long[1];
        STAT_W = w;
        STAT_H = h;

        Thread capThread = new Thread(() -> {
            int fails = 0;
            while (gen == GEN) {
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
        capThread.start();
        encThread.start();
        System.out.println("pipeline gen=" + gen + " " + w + "x" + h);
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
        startPipeline(1, w, h);
    }

    static final String PAGE =
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
      + ".stage{position:relative;flex:1 1 auto;min-height:0;background:#000;display:flex;align-items:center;justify-content:center;overflow:hidden}"
      + "#v{max-width:100%;max-height:100%;display:block}"
      + "#off{position:absolute;inset:0;display:none;flex-direction:column;align-items:center;justify-content:center;gap:14px;background:#0b0b0d}"
      + "#off .t{font-size:16px;color:#9a9aa3}#off .s{font-size:12px;color:#63636b}"
      + "#off button{padding:10px 22px;font-size:15px;border-color:#3a3a46}"
      + "@media(max-width:680px){.stat{display:none}}"
      + "</style></head><body>"
      + "<div class=bar><span id=dot class='dot idle'></span><span class=stat id=s1>连接中…</span><span class=sp></span>"
      + "<button id=ab onclick=audio()>关闭声音</button>"
      + "<select id=asrc onchange=setaudio()>"
      + "<option value='TDMOUT_B' selected>电视声音 B</option>"
      + "<option value='TDMOUT_A'>电视声音 A</option>"
      + "<option value='TDMOUT_C'>电视声音 C</option>"
      + "<option value='PDMIN'>麦克风</option>"
      + "<option value='SPDIFIN'>SPDIF 输入</option>"
      + "</select>"
      + "<button onclick=pw('/wake')>唤醒</button><button onclick=pw('/sleep')>息屏</button>"
      + "<select id=res onchange=setres()>"
      + "<option value='320,180'>320×180</option>"
      + "<option value='480,270'>480×270</option>"
      + "<option value='640,360'>640×360</option>"
      + "<option value='960,540' selected>960×540</option>"
      + "<option value='1280,720'>1280×720</option>"
      + "<option value='1920,1080'>1920×1080</option>"
      + "</select>"
      + "<button onclick=fs()>全屏</button><button onclick=shot()>截图</button></div>"
      + "<div class=stage><canvas id=v></canvas>"
      + "<div id=off><div class=t>电视已息屏</div><div class=s>屏幕关闭时无法采集画面（回采的是显示管线）</div>"
      + "<button onclick=pw('/wake')>唤醒电视</button></div></div>"
      + "<script>"
      + "const V=document.getElementById('v'),CTX=V.getContext('2d'),D=document.getElementById('dot'),S=document.getElementById('s1'),AB=document.getElementById('ab'),OFF=document.getElementById('off');"
      + "let sleeping=false,audioOn=false,ac=null,t0=0,aborter=null,vctl=null,lastFrame=0,fc=0,fps=0,fbuf=new Uint8Array(0);"
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
      + "setInterval(function(){fps=fc;fc=0;if(!sleeping&&Date.now()-lastFrame>4000){openVideo();}},1000);"
      + "function tick(){fetch('/stats',{cache:'no-store'}).then(r=>r.json()).then(j=>{"
      + " const awake=(j.awake!==false);"
      + " if(!awake){"
      + "  if(!sleeping){sleeping=true;OFF.style.display='flex';D.className='dot idle';V.style.display='none';}"
      + "  S.textContent='已息屏';"
      + " }else{"
      + "  if(sleeping){sleeping=false;OFF.style.display='none';V.style.display='block';openVideo();}"
      + "  S.textContent=fps+' fps · '+(j.kbps/1000).toFixed(2)+' Mbps · '+j.w+'×'+j.h+' · 看 '+j.clients;"
      + "  D.className=(fps>0)?'dot':'dot bad';"
      + " }"
      + "}).catch(()=>{S.textContent='连不上服务';D.className='dot bad';});}"
      + "setInterval(tick,2000);tick();"
      + "function fs(){const el=document.querySelector('.stage');"
      + "if(document.fullscreenElement){document.exitFullscreen();}else if(el.requestFullscreen){el.requestFullscreen();}}"
      + "function shot(){window.open('/snap','_blank');}"
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
      + "fetch('/audio?src='+s).then(r=>r.json()).then(()=>{setTimeout(()=>{if(audioOn)openPcm();},800);}).catch(()=>{});}"
      + "document.addEventListener('dblclick',fs);"
      + "openVideo();"
      + "startAudio();"
      + "document.addEventListener('pointerdown',function(){try{if(ac&&ac.state==='suspended')ac.resume();}catch(e){}},{once:true});"
      + "</script></body></html>";

    static final String CRLF = new String(new char[]{13, 10});

    static void send(OutputStream out, String ctype, byte[] body, boolean noStore) throws IOException {
        send(out, 200, ctype, body, noStore);
    }

    static void send(OutputStream out, int status, String ctype, byte[] body, boolean noStore)
            throws IOException {
        String reason = (status == 200) ? "OK"
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
                String src = null;
                int q = path.indexOf("?src=");
                if (q >= 0) src = path.substring(q + 5);
                boolean ok = (src != null) && AudioStream.setSource(src);
                send(out, "application/json",
                        ("{\"ok\":" + ok + ",\"src\":\"" + AudioStream.source() + "\"}")
                                .getBytes("UTF-8"), true);
            } else if (path.startsWith("/stats")) {
                String json = "{\"fps\":" + String.format(java.util.Locale.US, "%.2f", STAT_FPS)
                        + ",\"kbps\":" + String.format(java.util.Locale.US, "%.0f", STAT_KBPS)
                        + ",\"w\":" + STAT_W + ",\"h\":" + STAT_H
                        + ",\"clients\":" + STAT_CLIENTS
                        + ",\"frames\":" + STAT_FRAMES
                        + ",\"audio\":" + AudioStream.isRunning()
                        + ",\"aclients\":" + AudioStream.clientCount()
                        + ",\"abytes\":" + AudioStream.bytesSent()
                        + ",\"asrc\":\"" + AudioStream.source() + "\""
                        + ",\"awake\":" + awakeCached() + "}";
                send(out, "application/json", json.getBytes("UTF-8"), true);
            } else {
                send(out, "text/html; charset=utf-8", PAGE.getBytes("UTF-8"), false);
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
            } else if ("serve".equals(mode)) {
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
