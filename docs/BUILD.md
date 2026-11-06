# 构建

## 1. 依赖

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17 | `javac` |
| Android SDK build-tools | 34.0.0 | `d8`（dex 转换） |
| Android platform | android-34 | `android.jar`（编译期 classpath） |
| NDK | 26.1.10909125 | **仅 native 工具需要** |

本项目**不需要 Gradle、不需要 Android Studio、不需要 aapt**。
产出就是一个 `classes.dex`，用 `app_process` 直接跑。

## 2. 编译 dex

```bash
bash tools/build.sh
# → build/classes.dex
```

脚本做的事：

```bash
javac -encoding UTF-8 -source 8 -target 8 \
      -cp $ANDROID_SDK/platforms/android-34/android.jar \
      -d build/classes src/com/droidlogic/app/*.java

d8 --min-api 28 \
   --lib $ANDROID_SDK/platforms/android-34/android.jar \
   --output build/ build/classes/com/droidlogic/app/*.class
```

### 可选环境变量

```bash
JDK=/path/to/jdk17 \
ANDROID_SDK=/path/to/android-sdk \
bash tools/build.sh
```

## 3. 部署

```bash
adb push build/classes.dex /data/local/tmp/sc.dex
adb push tools/run_srv.sh   /data/local/tmp/
adb push tools/stop_srv.sh  /data/local/tmp/
adb shell su -c 'chmod 755 /data/local/tmp/*.sh'
```

## 4. 运行

```bash
# 参数：宽 高 端口
adb shell su -c 'sh /data/local/tmp/run_srv.sh 960 540 8899'
```

或直接命令行：

```bash
adb shell su -c 'CLASSPATH=/data/local/tmp/sc.dex \
  app_process -Xmx96m /system/bin \
  com.droidlogic.app.ScreenControlManager serve 1 960 540 8899 960 540 70'
```

`serve` 的参数：`serve <srcType> <w> <h> <port> <outW> <outH> <jpegQ>`

| 模式 | 用法 |
|---|---|
| `serve` | HTTP 服务（本项目主力） |
| `cap` | 抓单帧到文件：`cap 1 1920 1080 /data/local/tmp/x.raw` |
| `bench` | 测采集帧率：`bench 1 960 540 6` |
| `multibench` | 并发采集测试 |
| `h264` | H.264 推流实验 |
| `rec` | 厂商录制接口（**有害，勿用**，见 [PITFALLS P3](PITFALLS.md#p3--startscreenrecord-会把电视踢出硬解)） |

## 5. Native 交叉编译（可选）

只有在需要写 C/C++ 探针时才用。**有三个连锁坑**（详见 [PITFALLS P5](PITFALLS.md#p5--交叉编译三个连锁坑)）：

1. NDK 的 `clang.exe` 段错误 → 用 `clang-cl.exe`
2. NDK 副本里的 `libc.so` 损坏 → 从电视 pull 一个当 stub
3. `clang-cl` 带 MSVC 默认库指令 → 编译加 `/Zl`

### 准备（只需一次）

```bash
adb shell su -c 'cp /system/lib/libc.so /data/local/tmp/libc_dev.so; chmod 644 /data/local/tmp/libc_dev.so'
adb pull /data/local/tmp/libc_dev.so build/devlibs/libc.so
```

### 编译

```bash
bash tools/native-build.sh path/to/x.c myprog
# → build/myprog
```

### 运行

```bash
adb push build/myprog /data/local/tmp/
adb shell su -c 'chmod 755 /data/local/tmp/myprog; cd /data/local/tmp; \
  LD_LIBRARY_PATH=/vendor/lib:/system/lib ./myprog'
```

**`LD_LIBRARY_PATH` 必须给**，否则 dlopen 厂商库会失败。

## 6. 常见错误

| 错误 | 原因 | 解法 |
|---|---|---|
| `error: cannot find symbol: IOException` | 缺 import | `import java.io.IOException;` |
| d8: `Illegal char <*>` | 通配符在 Windows 下没展开 | 显式列出 `.class` 文件 |
| javac: `编码 GBK 的不可映射字符` | 源码含中文 | 加 `-encoding UTF-8` |
| `dlopen: library not found` | 缺 `LD_LIBRARY_PATH` | 见上 |
| `NoClassDefFoundError` | `CLASSPATH` 没设 | `CLASSPATH=/data/local/tmp/sc.dex` |
| `linker input unused` | MSYS 把 `/O2` 当路径 | `MSYS_NO_PATHCONV=1` |
| d8: `Invalid output: \d\...\<乱码>` | **路径含中文**，d8 处理不了 | 本项目 `build.sh` 已自动改在 `/tmp` 下编译再把产物拷回 |
