# AGENTS.md — 接手契约（给 AI 编码助手）

> 本文件是**机器的入口**。人类请先看 [README.md](README.md)。
> 本文件的目标：让一个没有上下文的新 AI 在**不重复踩坑、不虚报进度**的前提下继续工作。

## 0. 一分钟事实

- 目标设备：**小米电视 EA55 2022 款**（型号代码 `MiTV-ASTP0`），Amlogic T5，Android 9 / API 28 / armeabi-v7a，**已 root（Magisk）**。
- 干什么：把电视的画面和声音推到浏览器，`http://<tv-ip>:8899/`。
- 核心机制：`app_process` 跑 dex → `System.load("/vendor/lib/libscreencontrol_jni.so")` → 调用厂商 `startScreenCapBuffer` 拿 RGBA 帧。
- 为什么不用 screencap：视频走独立硬件层，`SurfaceFlinger` 截图全黑。
- 帧率上限：**约 18 fps**（厂商接口固定开销，不是编码瓶颈）。

详细参数见 [docs/DEVICE.md](docs/DEVICE.md)，结构化数据见 [ai/facts.json](ai/facts.json)。

## 1. 状态分级（必须如实标注）

| 级别 | 含义 |
|---|---|
| 已提出 | 只有描述 |
| 已实现 | 代码存在 |
| 已编译 | 构建通过 |
| 已自测 | 本机跑过 |
| **已真机验证** | 在目标电视上验证过，附证据 |

**禁止把「已实现」写成「已可用」。禁止把「编译通过」写成「功能正常」。**

### 当前各项的真实状态

| 功能 | 状态 | 证据 |
|---|---|---|
| 画面回采 + 网页播放 | ✅ 已真机验证 | 2026-11-06，960×540 / 8.7 fps |
| 分辨率运行时切换 | ✅ 已真机验证 | 320/480/640/960/1280/1920 全部测过 |
| 声音回采（扬声器） | ✅ 已真机验证 | `peak=2632 rms=376`（音量 3 时） |
| 声音来源切换 | ⚠️ 部分真机验证 | 2026-11-14 实测（电视醒着、音量 1）：`TDMOUT_A` / `TDMOUT_C` **采集起不来**（tinycap 连续 3 次 `Captured 0 frames`）；`TDMOUT_B` / `PDMIN` / `SPDIFIN` 能采但 `apeak=0`，未测到内容。历史上**只有 `TDMOUT_B` 被测到过真实信号**（peak=2632） |
| 多客户端并发（有界队列 + 独立写线程） | ⚠️ 已编译 + 已自测（本机）+ 上限已真机验证 | 慢端只丢自己（本机 300/300 帧全收）；真机 8 端占满后第 9 个回 `503`。**慢端隔离未真机验证**，见 [ADR 0003](docs/decisions/0003-每客户端有界队列.md) |
| 远程唤醒 / 息屏 | ✅ 已真机验证 | `/wake` `/sleep` 双向测过 |
| 遥控（按键 / 文字注入） | ✅ 已真机验证 | 2026-11-14 用户实测按键有效；`/key` `/text` 要求令牌，`POWER` 不在白名单，见 [ADR 0004](docs/decisions/0004-控制端点需要令牌.md) |
| H.264 推流 | ⚠️ 已编译 + 已解码验证 | 软解 82 帧通过，**但未在浏览器里播放过** |
| 多平台适配 | ❌ 未开始 | — |
| 开机自启 + 崩溃自愈 | ✅ 已真机验证 | 2026-11-07 重启 3 次验证，单实例、20 秒内自愈 |
| 鉴权 / TLS | ❌ 未实现 | — |

## 2. 硬约束（不可违反）

### 2.1 平台
- 目标 ABI：**armeabi-v7a**，API 28。禁止用 API 29+ 才有的接口而不做降级。
- 必须能跑在 32 位进程里，注意内存（整机 1.5 GB）。

### 2.2 采集
- **只能用 `sourceType = 1`**。见 [docs/PITFALLS.md](docs/PITFALLS.md) P1。
- `startScreenCapBuffer(left, top, right, bottom, width, height, srcType)`：
  **前 4 个参数是屏幕坐标的裁剪矩形，后 2 个才是输出尺寸**。搞错会变成"截左上角再放大"。
- **禁止调用 `startScreenRecord`**。它会把播放器的硬件解码器踢掉（详见 P3）。

### 2.3 安全
- 不把 token、配对密钥、证书私钥写进仓库或日志。
- 不对电视执行恢复出厂、清数据、改分区等不可逆操作。
- `adb forward --remove-all` 会清掉其他项目的转发，**禁止执行**。

### 2.4 设备状态
- 改电源状态前先 `dumpsys power` 查，**不要盲发 `keyevent 26`**（那是开关，会把醒的发睡）。

## 3. 环境

| 项 | 路径 / 值 |
|---|---|
| adb | `/d/Android/sdk/platform-tools/adb.exe`（注意：旧路径 `LIPTV/...` 已失效） |
| JDK | `/d/Android/jdk17` |
| Android SDK | `/d/Android/sdk`（build-tools 34.0.0、platforms android-34） |
| NDK | `LIPTV/vendor/android/sdk/ndk/26.1.10909125`（Windows） |
| 电视 | `192.168.250.11:5555` |

### 构建命令

```bash
bash tools/build.sh          # → build/classes.dex
```

### native 交叉编译（有坑，见 P5）

```bash
bash tools/native-build.sh probe.c out
```

## 4. 已知的坑（先读再动手）

全部在 [docs/PITFALLS.md](docs/PITFALLS.md)。最重要的三条：

- **P1** `sourceType=2` 会让 `screencontrol` 守护进程 SIGABRT。
- **P2** NDK 的 `clang.exe` 一启动就段错误，要用 `clang-cl.exe`。
- **P3** `startScreenRecord` 会把电视踢出硬解（软解 CPU 90%）。

## 5. 不要做的事

- ❌ 不要假设这套东西能跑在别的电视上（见 [docs/UNIVERSALITY.md](docs/UNIVERSALITY.md)）。
- ❌ 不要为了「更流畅」去写 native 客户端 —— 已实测无效，瓶颈在守护进程内部。
- ❌ 不要试图自己实现 `ScreenManager` 持久会话 —— 已试到底，帧泵不在我们能碰的库里。
- ❌ 不要在未验证时声称「支持硬解」「零延迟」。

## 6. 改完代码后

1. 跑 `bash tools/build.sh` 确认编译通过。
2. 推上去重启服务，用 `/stats` 和 `/snap` 验证。
3. 更新 [docs/MEASUREMENTS.md](docs/MEASUREMENTS.md)（如果改了性能相关路径）。
4. 更新本文件第 1 节的状态表。
5. 有架构取舍就加一条 [ADR](docs/decisions/)。
