# 适用范围：能用到哪些机器

## 一句话

**只适用于 Amlogic 芯片 + 已 root 的电视。** 而且只在**一台**机器上验证过。

## 强依赖厂商（换芯片即完全失效）

| 依赖 | 路径 | 作用 |
|---|---|---|
| 守护进程 | `/system/bin/screencontrol` | 没有它完全无法回采 |
| 服务库 | `/system/lib/libscreencontrolservice.so` | `ScreenManager` 实现 |
| 客户端库 | `/vendor/lib/libscreencontrolclient.so` | `ScreenControlClient` |
| JNI 封装 | `/vendor/lib/libscreencontrol_jni.so` | native 方法注册 |
| HIDL 服务 | `vendor.amlogic.hardware.screencontrol@1.0` | 跨进程接口 |
| 解码器节点 | `/sys/class/vdec/*` | 仅观测用（不影响回采） |
| 视频层节点 | `/sys/class/video/*` | 仅观测用 |
| 音频回采 | `/dev/snd/pcmC0D7c`（LOOPBACK-A） | 设备号可能因机型而异 |
| mixer 控件 | `Loopback tdmin lb source` 等 | 控件名可能因机型而异 |
| 硬件加速 | `/dev/vdin0`、`/dev/ge2d` | 备选路径，未使用 |

## 与厂商无关（可移植）

| 部分 | 说明 |
|---|---|
| `app_process` + dex 调用方式 | 任何 Android 都支持 |
| HTTP / MJPEG / H.264 服务端（纯 Java） | 平台无关 |
| 网页（HTML/JS/canvas 解 JPEG） | 完全通用 |
| `input keyevent`、`dumpsys power` | AOSP 标准 |
| `OMX.google.h264.encoder` | AOSP 自带软编，各机型都有 |
| `tinycap` / `tinymix` | AOSP tinyalsa 工具，但**设备号与控件名因厂商而异** |

## 未验证的部分

| 范围 | 状态 |
|---|---|
| 其他 Amlogic 机型 | ❓ `sourceType` 取值、`init` 参数语义、8 声道要求是否一致，**未知** |
| 非 Amlogic 电视（MediaTek / Realtek / Hisilicon / MStar） | ❌ 大概率完全不适用 |
| Android 10+ | ❓ `app_process` 与 HIDL 行为可能有变化 |
| 无 root 的机器 | ❌ 直接不可用 |
| 手机 / 平板 | ❌ 没有 `screencontrol`，不适用 |

## 为什么不用"通用"的 MediaProjection

`MediaProjection` 是 AOSP 官方截屏 API，理论全平台可用。但在这台电视上
`screencap` 拿到的是**全黑**：解码后的画面走独立硬件视频层，不经 `SurfaceFlinger`。

这个限制**很可能不止 Amlogic**——大多数电视 SoC 为了省电和画质都这么做。
但**没有在其他芯片上实测过，不做断言**。

## 如果有第二台机器，怎么判断能不能用

```bash
# 1. 有没有厂商回采服务
adb shell su -c 'ls /system/bin/screencontrol /vendor/lib/libscreencontrol*.so'
adb shell su -c 'lshal | grep -i screen'

# 2. 有没有硬件视频层（决定 screencap 是否可用）
adb shell 'ls /sys/class/video/ /sys/class/vdec/' 2>&1

# 3. 播放时 screencap 是不是黑的（最快的判定）
adb shell screencap -p /sdcard/t.png && adb pull /sdcard/t.png
# 若全黑而画面明显在播 → 需要厂商回采路线
```

## 要做成多平台需要什么

抽象出后端接口：

```
ScreenCaptureBackend
 ├─ AmlogicScreenControlBackend   ← 当前实现
 ├─ VendorXxxBackend              ← 每家厂商一个
 └─ MediaProjectionBackend        ← 兜底，但电视上多半黑屏
```

运行时探测用哪个（看 `screencontrol` 在不在、`/sys/class/vdec` 在不在）。

**目前没做**：没有第二台设备可验证，写了也无法证明能用。

参见 [ADR 0001](decisions/0001-回采方案仅适用Amlogic.md)。
