# 踩过的坑

> 每条都是真实踩过、有日志或数据支撑的。**动手前先看完，能省几小时。**

---

## P1 · `sourceType = 2` 会让 `screencontrol` 守护进程崩溃

**症状**

控制台直接 `Aborted`，`pid` 变化（守护进程被 init 重启）。

**日志**

```
E ScreenManager: [start 382] For now ,we don't capture osd only by AML_SCREEN_HARDWARE_MODULE_ID module!
E ScreenCatch: ScreenControlService start fail
F libc    : Fatal signal 6 (SIGABRT), code -6 (SI_TKILL) in tid 4998 (HwBinder:4733_2)
F DEBUG   :     #02 pc 00006ef9  libscreencontrolservice.so (ScreenManager::KeyedVector::valueFor)
F DEBUG   :     #03 pc 000075b7  libscreencontrolservice.so (ScreenManager::stop(int)+82)
```

**原因**：`stop()` 里对没初始化的 map 取值。

**对策**：**只用 `sourceType = 1`**。0 是读超时，2 会崩。

---

## P2 · NDK 的 `clang.exe` 一启动就段错误

**症状**

```bash
$ clang.exe --version
Segmentation fault        # exit 139
```

`clang.exe`、`clang-format.exe` 都崩，但 `llvm-nm.exe`、`llvm-readobj.exe`、`ld.lld.exe` 正常。

**对策**：用同目录的 **`clang-cl.exe`**（同一 LLVM 后端，只是驱动不同，能正常工作）：

```bash
clang-cl.exe --target=armv7a-linux-androideabi28 /O2 /Zl -c x.c -o x.o
```

---

## P3 · `startScreenRecord` 会把电视踢出硬解

**症状**：磁盘上报 `rc=0` 但**不产出文件**；同时播放器的硬件解码器被销毁，回退软解。

**日志**

```
03:03:59.446  makeComponentInstance(OMX.google.h264.decoder)      ← 播放器改用软解
03:03:59.680  AmlogicVideoDecoderAwesome ... mOmxInstanceID 33    ← 硬解器创建后 5ms 销毁
03:03:59.696  makeComponentInstance(OMX.google.h264.encoder)
```

**后果**

| 指标 | 之前 | 之后 |
|---|---|---|
| `/sys/class/vdec/vdec_status` | `ammvdec_h264-00` 正常 | `No vdec.` |
| `media.codec` CPU | 13% | **90.3%** |
| 画面 | 正常 | 仍能播，但烧 CPU |

**恢复**

```bash
adb shell am force-stop com.litv.tv
adb shell am start -n com.litv.tv/.launcher   # 或直接重新选台
```

**对策**：**永远不要调用 `native_ScreenRecord`**，只用 `native_ScreenCapBuffer`。

---

## P4 · 裁剪矩形 ≠ 输出尺寸

`startScreenCapBuffer` 的 7 个参数：

```java
startScreenCapBuffer(left, top, right, bottom,   // ← 屏幕坐标的裁剪矩形
                     width, height,               // ← 输出尺寸（硬件缩放）
                     sourceType)
```

**错误写法**：`(0, 0, w, h, w, h, 1)` —— 一旦输出小于 1080p，就变成「截屏幕左上角一块再放大」。

**识别方法**：同一张静态测试卡，正确参数下 `mean_luma ≈ 45`，错误参数下 `mean_luma ≈ 89`（画面被放大，亮度统计完全不同）。

**正确写法**

```java
startScreenCapBuffer(0, 0, 1920, 1080, outW, outH, 1);
```

缩放走硬件，**不额外花钱**（同样的输出尺寸，耗时不变）。

---

## P5 · 交叉编译：三个连锁坑

| 坑 | 症状 | 解法 |
|---|---|---|
| sysroot 里的 `libc.so` 损坏 | `ld.lld: unknown directive: �` | 从电视 `adb pull /system/lib/libc.so` 当链接 stub |
| `clang-cl` 带 MSVC 默认库指令 | `ld.lld: unable to find library libcmt / oldnames` | 编译加 **`/Zl`** |
| MSYS 把 `/O2`、`/Zl` 当路径改写 | `'linker' input unused` | 加 `MSYS_NO_PATHCONV=1` |
| `ld.lld` 不认 `/d/...` | `cannot open /d/...` | 链接参数用 `D:\...` Windows 路径 |

完整脚本见 [../tools/native-build.sh](../tools/native-build.sh)。

---

## P6 · 反汇编必须指定 Thumb

ARM32 的库大多是 Thumb 指令集。直接 `llvm-objdump -d` 解出来全是垃圾：

```bash
# ❌ 乱码
llvm-objdump -d lib.so

# ✅ 正确
llvm-objdump -d --triple=thumbv7-linux-androideabi lib.so
```

---

## P7 · `pkill -f ScreenControlManager` 会杀掉自己

`su -c "pkill -f ScreenControlManager"` 的命令行本身就包含这个字符串，会匹配到自己的 `sh -c`。

**对策**：按 PID 杀，或用 `tools/stop_srv.sh`（里面用 `ps` + `grep` 精确匹配类名）。

更糟的情况：用 `ps -A | grep app_process` 过滤会把**别的** app_process 也杀了
（本项目曾误杀过一个无关的 dex 服务）。**只按完整类名匹配。**

---

## P8 · 浏览器 MJPEG（`<img src>`）会莫名其妙卡住

**症状**：服务端 `/stats` 里 fps 一直正常，但浏览器画面停住不动，必须刷新才行。

**原因**：Chrome 用 `<img>` 播放 `multipart/x-mixed-replace` 时，长连接跑久了渲染进程会停止消费。
再叠加同域 6 个并发连接上限（被多次重连的 `/pcm` 音频流占满），就彻底卡死。

**对策**

1. 页面改用 `<canvas>` + `fetch` 流式解析 JPEG（找 `FFD8`/`FFD9` 边界），不依赖 `<img>`。
2. 加**看门狗**：4 秒没新帧就自动重连。
3. 音频流限制**只保留 1 个客户端**，新连接进来先踢掉旧的。
4. 切换音源时复用同一个 `AudioContext`，**不要重建**（重建会超出用户手势窗口，被浏览器置为 suspended → 静音）。

---

## P9 · 音频必须按 8 声道采集

```bash
# ❌ 报错，只出 4096 帧
tinycap a.wav -D 0 -d 7 -c 2 -r 48000 -b 16
# start errorCapturing sample: 2 ch, 48000 hz, 16 bit

# ✅ 正常
tinycap a.wav -D 0 -d 7 -c 8 -r 48000 -b 16
# Captured 98304 frames
```

下混取 ch0/ch1 即可。

---

## P10 · `keyevent 26` 是开关，不是唤醒

```bash
input keyevent 26    # POWER —— 醒着会睡着，睡着会醒
input keyevent 224   # WAKEUP —— 在这台机器上无效（实测按了没反应）
input keyevent 223   # SLEEP  —— 有效
```

**对策**：改电源状态前先查：

```bash
dumpsys power | grep mWakefulness=
```

代码里见 `ScreenControlManager.isAwake()` / `wakeUp()` / `sleepNow()`。

---

## P11 · 电视息屏后画面全黑，但服务一切正常

息屏 = 显示时序发生器停了，VDIN 采不到任何东西。

**迷惑点**：`/stats` 里 `fps` 照常在跳（仍在采，只是采到全黑帧），页面会误以为在播。

**对策**：`/stats` 返回 `awake` 字段（`dumpsys power` 查询，2 秒缓存），前端据此显示遮罩。

---

## P12 · 强制常亮会抢走遥控器

`svc power stayon true` + `stay_on_while_plugged_in=7` 会让遥控器电源键失效。

用户明确要求「按息屏就该息屏」，所以**不要默认开启常亮**，改成显式的 `/wake` `/sleep` 按钮。

---

## P13 · d8 无法处理含中文的路径

**症状**

```
com.android.tools.r8.utils.b: Invalid output: \d\...\С�׵���uild
Output must be a .zip or .jar archive or an existing directory
```

**原因**：d8 是 Java 程序，Windows 下用平台默认编码解释路径，中文目录名会变成乱码。

**对策**：在纯 ASCII 路径下编译，产物再拷回。

`tools/build.sh` 已经这么做了：

```bash
STAGE="${TMPDIR:-/tmp}/tvss-build-$$"
cp src/*.java "$STAGE/java/com/droidlogic/app/"
javac ... -d "$STAGE/classes" ...
d8 --output "$STAGE/" ...
cp "$STAGE/classes.dex" build/classes.dex
```

顺带的好处：仓库可以放在任意目录（含中文）而不影响构建。

---

## P14 · Magisk service.d 里 PATH 很精简

**症状**

```
/data/adb/service.d/tv-screen-stream.sh: line 49: app_process: not found
```

脚本手动跑没问题，开机就找不到 `app_process`。

**原因**：Magisk 执行 `service.d` 时环境变量很精简，`PATH` 不含 `/system/bin`。

**对策**

```sh
export PATH=/system/bin:/system/xbin:/vendor/bin:/sbin:$PATH
# 或者直接用绝对路径
CLASSPATH=$DEX /system/bin/app_process ...
```

---

## P15 · `ps -o ARGS` 单列输出会被截断

**症状**：`ps -A -o PID,ARGS | grep 类名` 能匹配，但 `ps -A -o ARGS | grep 类名` 匹配不到，
导致守护脚本以为服务没起来，反复拉起 → **多个实例**。

**对策**：别用 `ps` 做存活判断，改用 **pidfile + /proc**：

```sh
running() {
    [ -f "$PIDFILE" ] || return 1
    p=$(cat "$PIDFILE" 2>/dev/null); [ -n "$p" ] || return 1
    [ -d "/proc/$p" ] || return 1
    tr ' ' ' ' < "/proc/$p/cmdline" | grep -q 'com.droidlogic.app.ScreenControlManager' || return 1
    return 0
}
```

---

## P16 · 改完正在运行的 shell 脚本会把它搞坏

**症状**：脚本报 `syntax error: unmatched 'if'` 或行为错乱。

**原因**：`sh` 是边读边执行的，覆盖正在运行的文件会让它读到半截内容。

**对策**：改脚本前先 `kill` 掉旧进程，改完再启动。别直接覆盖正在跑的脚本。

---

## P17 · Windows 上写的脚本带 CRLF，Android 跑不了

**症状**

```
/data/adb/service.d/xxx.sh[13]: : not found
syntax error: unmatched 'if'
```

**原因**：Python `io.open(p,'w')` 在 Windows 默认把 `
` 写成 `
`，`` 被当成 token 的一部分。

**对策**：写脚本时显式 `newline=''`，或写完做一次转换：

```python
s = io.open(p, encoding='utf-8', newline='').read().replace('
', '
')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
```
