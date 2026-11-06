# 目标设备配置

> 所有数据来自 2026-11-06 在真机上执行的 `getprop` / `cat /proc` / `tinymix` 等只读命令。
> 这是本项目**唯一验证过**的设备。

## 1. 身份

| 项 | 值 |
|---|---|
| 商品名 | 小米电视（具体型号请以机身标签为准） |
| 型号 | `MiTV-ASTP0` |
| 品牌 | Xiaomi |
| device / board | `mulan` |
| 硬件平台 | `amlogic`（`ro.hardware=amlogic`） |
| SoC 设备树 compatible | `amlogic, t5_t963_ak301` |
| 固件版本 | `23.2.15.1886` |
| 内核 | 4.9.113 |

## 2. 系统

| 项 | 值 |
|---|---|
| Android 版本 | 9 |
| API Level | **28** |
| ABI | **armeabi-v7a, armeabi**（仅 32 位） |
| CPU | 4 核，最高 1.8 GHz |
| 内存 | **1,537,828 kB ≈ 1.5 GB** |
| 屏幕分辨率 | 1920×1080 |
| 面板刷新率 | 50 / 60 Hz（随内容切换） |
| 显示密度 | 320 dpi |

## 3. Root

| 项 | 值 |
|---|---|
| 方案 | **Magisk** |
| 验证 | `su -c id` → `uid=0(root) ... context=u:r:magisk:s0` |
| 用途 | 加载厂商私有库、直接用 `app_process`、改音频路由 |

## 4. 编解码器

### 4.1 硬件解码（可用）

```
OMX.amlogic.avc.decoder.awesome              video/avc   ← 实际在用
OMX.amlogic.avc.decoder.awesome.secure       video/avc
OMX.amlogic.avc.decoder.awesome.hdcp.secure  video/avc
OMX.amlogic.hevc.decoder.awesome             video/hevc
OMX.amlogic.mpeg2.decoder.awesome            video/mpeg2
OMX.amlogic.mpeg4.decoder.awesome            video/mp4v-es
OMX.amlogic.h263.decoder.awesome             video/3gpp
```

实测能力：**4K H.264 60fps 只能跑到约 32 fps**（`fps_info`: input 60 / output 32 / drop 27）。
1080p / 720p 满帧无压力。

### 4.2 编码器（**全部是软件**）

```
OMX.google.h264.encoder      video/avc        ← 唯一可用的 H.264 编码器
OMX.google.h263.encoder      video/3gpp
OMX.google.mpeg4.encoder     video/mp4v-es
OMX.google.vp8.encoder       video/x-vnd.on2.vp8
```

**这台机器没有任何硬件视频编码器**，连硬件 JPEG 编码器都没有（`grep image/` 为空）。

原因：设备树里 `amvenc_avc` / `jpegenc` 都是 **disabled**，且缺少 `reg` 和 `clocks`。
厂商自己的 `screencontrol` 也会打印：

```
ScreenManager Open /dev/amvenc_avc failed, use software encoder instead!
```

软件编码实测：`OMX.google.h264.encoder` 480p ≈ 26–27 fps；x264 ultrafast 480p ≈ 24–44 fps。

> 这个模块（`vcodec-dec`）是**解码器**，可用。名字相近但别混淆。

## 5. 显示与视频层

| 路径 | 说明 |
|---|---|
| `/sys/class/vdec/` | 解码器统计（`vdec_status`、`core`、`profile_idc`、`dump_fps`） |
| `/sys/class/video/` | 视频层属性（`axis`、`angle`、`mirror`、`frame_width/height`、`fps_info`） |
| `/dev/vdin0`、`/dev/vdin1` | 视频输入采集（VDIN），权限 666 |
| `/dev/video11`、`/dev/video12` | VDIN 暴露的 V4L2 节点，权限 666 |
| `/dev/ge2d` | 2D 加速（缩放/转色），权限 660 system |

`/sys/class/vdec/vdec_status` 在播放时形如：

```
vdec channel 0 statistics:
  device name : ammvdec_h264-00
  frame width : 1280 / 1920 / 3840
 frame height : 720 / 1080 / 2160
   frame rate : 25 / 60 fps
   frame count : 12117
   drop count : 0
 hw err count : 0
```

> `bit rate: 0 kbps`、`frame data: 0 KB` 是驱动未填的字段，**不代表没数据**，看 `frame count` 增量。

## 6. 厂商屏幕回采服务

| 组件 | 路径 |
|---|---|
| 守护进程 | `/system/bin/screencontrol`（root 常驻，init 拉起） |
| 服务库 | `/system/lib/libscreencontrolservice.so` |
| 客户端库 | `/vendor/lib/libscreencontrolclient.so` |
| JNI 封装 | `/vendor/lib/libscreencontrol_jni.so` |
| HAL 库 | `/vendor/lib/vendor.amlogic.hardware.screencontrol@1.0.so` |
| init 脚本 | `/system/etc/init/screencontrol.rc` |
| HIDL 服务 | `vendor.amlogic.hardware.screencontrol@1.0::IScreenControl/default` |

JNI 注册的 Java 类名是 `com.droidlogic.app.ScreenControlManager`（系统里**没有**这个 class 的 dex，
在原厂 `TvService` 的 oat 里，取不出来），所以我们自己实现同名类。

方法签名（从 `RegisterNatives` 字符串表还原）：

| native 方法 | 签名 |
|---|---|
| `native_ConnectScreenControl` | `()V` |
| `native_ScreenCap` | `(IIIIIIILjava/lang/String;)I` |
| `native_ScreenRecord` | `(IIIIIILjava/lang/String;)I` |
| `native_ScreenCapBuffer` | `(IIIIIII)[B` ← **本项目用的就是它** |
| `native_ForceStop` | `()V` |

## 7. 音频

### 7.1 声卡

```
card 0: AMLAUGESOUND  (AML-AUGESOUND)
```

| PCM 设备 | 名称 | 方向 |
|---|---|---|
| `pcmC0D0p` | TDM-A dummy | playback + capture |
| `pcmC0D1p` | TDM-B `tl1-acodec-hifi` | playback + capture |
| `pcmC0D2p` | TDM-C dummy | playback + capture |
| `pcmC0D3c` | PDM dummy | capture |
| `pcmC0D4p` | SPDIF-A | playback + capture |
| `pcmC0D5p` | SPDIF-B | playback |
| `pcmC0D6p` | EXTN (TV) | playback + capture |
| **`pcmC0D7c`** | **LOOPBACK-A** | **capture** ← 本项目用这个 |

### 7.2 回采路径

```
播放混音 → TDMOUT_B → LOOPBACK-A (/dev/snd/pcmC0D7c) → tinycap → 我们
```

**必须按 8 声道采集**（`-c 8`）。按 2 声道会 `start error`，只出 4096 帧就停。
实测 ch0–ch3 有信号，ch4–ch7 静音。

**这是「音量之后」的抽头**：电视音量 0 / 静音时，采到的只有底噪（peak≈63）。
要远程听到声音，电视本身必须开着音量。

### 7.3 可切换的 mixer 控件

| 控件 | 可选值 | 默认 |
|---|---|---|
| `Loopback tdmin lb source` | TDMOUT_A / **TDMOUT_B** / TDMOUT_C / TDMIN_A/B/C … | `TDMOUT_B` |
| `Loopback datain source` | TDMIN_A/B/C / SPDIFIN / **PDMIN** | `PDMIN` |

`TDMOUT_B` 是电视扬声器那一路（唯一确认有信号的）。

工具：`/system/bin/tinycap`、`/system/bin/tinymix`、`/system/bin/tinypcminfo`、`/system/bin/tinyplay`。

## 8. 存储与其他

| 项 | 值 |
|---|---|
| `/data/local/tmp` | 可写，存放 `sc.dex`、脚本、FIFO |
| 注意 | `*.yuv`、`*.gz` 大文件会被某机制几秒内删除，测试用中性文件名 |

## 9. 怎么复现这份配置

```bash
ADB="adb"
$ADB connect <tv-ip>:5555

# 身份 / 系统
$ADB shell 'getprop ro.product.model; getprop ro.board.platform; getprop ro.build.version.sdk'
$ADB shell 'getprop ro.product.cpu.abilist; uname -r'
$ADB shell 'grep -c ^processor /proc/cpuinfo; grep MemTotal /proc/meminfo'

# 编解码器
$ADB shell 'su -c "grep -h \"<MediaCodec name\" /vendor/etc/media_codecs*.xml"'

# 厂商服务
$ADB shell 'su -c "ls -l /system/bin/screencontrol /vendor/lib/libscreencontrol*.so"'
$ADB shell 'su -c "lshal | grep -i screen"'

# 音频
$ADB shell 'su -c "cat /proc/asound/pcm; tinymix 2>/dev/null | grep -i loopback"'
```
