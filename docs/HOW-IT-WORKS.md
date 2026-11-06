# 原理

## 1. 为什么普通截屏抓不到电视画面

大多数电视 SoC 为了省电和画质，把解码后的画面送到**独立的硬件视频层**，
由显示控制器直接合成输出，**不经过 `SurfaceFlinger`**。

```
解码器 ──► 硬件视频层 ──► 显示控制器 ──► HDMI
              ▲
              │  （不经过这里）
        SurfaceFlinger ──► UI 层
```

所以任何基于 `SurfaceFlinger` 的截屏都只能拿到 UI 层，视频区域是**黑的**：

| 方案 | 结果 |
|---|---|
| `adb shell screencap` | ❌ 全黑（1920×1080 PNG 只有 10595 字节，纯黑） |
| `MediaProjection` | ❌ 全黑 |
| `scrcpy` | ❌ 全黑 |

**这不是 bug**——同一时刻 `/sys/class/vdec/vdec_status` 的 `frame count` 正以 25 fps 增长。

## 2. 厂商的解法：AMD screen source

厂商自己的投屏（Miracast / DLNA）也要抓屏幕，它们走的是 **VDIN 硬件回采**：

```
显示管线输出 ──► VDIN (视频输入) ──► AML screen source ──► 用户态
```

`screencontrol` 守护进程封装了这条路，并对外提供 HIDL 接口 + JNI 封装。

```
我们的进程 (root)
  │
  ├─ System.load("/vendor/lib/libscreencontrol_jni.so")
  │     └─ JNI_OnLoad → RegisterNatives
  │          把 5 个 native 方法绑定到 com.droidlogic.app.ScreenControlManager
  │
  └─ native_ScreenCapBuffer(0,0,1920,1080, 960,540, 1)
        └─ libscreencontrolclient.so
              └─ HIDL: vendor.amlogic.hardware.screencontrol@1.0
                    └─ 内部 new 一个 ScreenManager
                          ├─ init(w,h,fps,datatype,&clientId,surface)
                          ├─ start(clientId)   ← 启动 AML_SCREEN_SOURCE
                          ├─ readBuffer(...)   ← 从 VDIN 读一帧
                          └─ stop / reset / uninit
```

### 一个关键发现：`ScreenManager` 跑在**调用方进程**里

反汇编 + 日志都证实：`libscreencontrolservice.so` 被 dlopen 后 new 出来的 `ScreenManager`，
其日志以**我们的 pid** 打印（`9327 ScreenManager: [start 393] start AML_SCREEN_SOURCE`）。

也就是说它直接打开设备节点，守护进程 `screencontrol` 并不参与。好处是：**我们崩了不连累守护进程**。

### 但是：帧泵不在这个库里

`readBuffer` 的早退分支反汇编：

```asm
7786: beq 0x781c       ; if ([this+0x28] == 0) ...
7834: movs r5, #1      ; ...直接 return 1（无帧），且不打日志
```

标志位要靠**采集线程**置位，而采集线程的代码在：

```
/system/bin/screencontrol 引用：
  android::ScreenCatch::setVideoCrop(int,int,int,int)
  android::ScreenCatch::ScreenCatch(unsigned,unsigned,unsigned,unsigned)
  libstagefright_screencatch.so        ← 28 KB，帧泵在这里
```

`libscreencontrolservice.so` 里**没有** `threadFuncForScreenManager` / `ScreenCatch`。

**所以自己实现持久会话是走不通的**：能启动 source，但没人往队列里填帧，
`readBuffer` 永远返回 1。详见 [ADR 0002](decisions/0002-放弃持久会话方案.md)。

## 3. 为什么用 `JNI_OnLoad` 而不是直接 dlopen

系统里**没有** `com.droidlogic.app.ScreenControlManager` 的 class 文件
（它的 dex 在原厂 `TvService` 的 oat 里，取不出来）。

所以做法是：**自己写一个同名同签名的类**，用 `System.load("/vendor/lib/libscreencontrol_jni.so")`
触发 `JNI_OnLoad`，让 `RegisterNatives` 把 native 方法绑到我们的类上。

方法签名是从 `.so` 的 `RegisterNatives` 字符串表还原的：

```java
native_ConnectScreenControl ()V
native_ScreenCap            (IIIIIIILjava/lang/String;)I
native_ScreenRecord         (IIIIIILjava/lang/String;)I
native_ScreenCapBuffer      (IIIIIII)[B
native_ForceStop            ()V
```

## 4. 整套数据流

```
┌─────────────────────────────────────────────────┐
│ 电视                                              │
│                                                   │
│  采集线程 ──grab()──► RGBA 帧 ──► slot[0]         │
│                                       │           │
│  编码线程 ◄───────────────────────────┘           │
│      └─ Bitmap.compress(JPEG) ──► latestJpeg      │
│                                       │           │
│  HTTP 线程 ◄──────────────────────────┘           │
│      ├─ /raw    裸 JPEG 连发                      │
│      ├─ /stream MJPEG（multipart）                │
│      ├─ /snap   单帧                              │
│      └─ /stats  JSON 状态                         │
│                                                   │
│  tinycap ──► FIFO ──► 下混 2ch ──► /pcm           │
└─────────────────────────────────────────────────┘
                    │
                    ▼ HTTP :8899
            ┌───────────────┐
            │  浏览器 canvas │
            └───────────────┘
```

**采集与编码分两条线程**：采集在等源端出帧时（约 30–33 ms），编码可以并行。
这一改动让 960×540 从 6.8 fps 提到 9.4 fps。

## 5. 帧率天花板在哪

单帧耗时拆解（960×540）：

| 阶段 | 耗时 |
|---|---|
| 请求 → `start AML_SCREEN_SOURCE` | ~1 ms |
| **等源端出一个非空帧** | **~30–33 ms**（固定，源约 30 fps） |
| 拷贝 + 停止/复位 | ~70 ms（与像素量近似线性） |

拷贝带宽稳定在 **约 30 MB/s**，这是 `hidl_memory → Java byte[]` 路径的固有速度。

> 已实测：改成 native 客户端（绕开 Java 的 `byte[]` 拷贝）**并不会更快**
> —— 640×360 原生 69.6–74.9 ms，Java 71.7 ms，基本一致。
> 瓶颈在守护进程内部，不在客户端。

**所以分辨率是唯一有效的杠杆。**

## 6. 声音为什么要按 8 声道

```
播放混音 → TDMOUT_B → LOOPBACK-A (/dev/snd/pcmC0D7c) → tinycap
```

LOOPBACK-A 是 TDM 通道，硬件上是 8 声道。按 2 声道打开会直接 `start error`。
采回来取 ch0/ch1 下混成 2 声道即可。

**这是「音量之后」的抽头**——电视静音时采到的是静音。
