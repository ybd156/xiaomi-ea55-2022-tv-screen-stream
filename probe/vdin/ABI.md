# Amlogic VDIN 采集 ABI（逆向还原 · 2026-11-21）

> 来源：`/system/lib/hw/screen_source.amlogic.so`（37,244 B，ARM32，符号未 strip）
> 工具：IG5 / Ghidra 12.1.4，sha256 `449d76fa…f1fc06`
> 状态：**部分还原**。已确证的部分附函数地址；未确证的一律标「未确认」。

## 1. 调用链

```
libscreencontrol_jni.so      (JNI: native_ScreenCapBuffer)
  → libscreencontrolclient.so (binder client)
  → /system/bin/screencontrol (HIDL service, vendor.amlogic.hardware.screencontrol@1.0)
  → libscreencontrolservice.so (service 逻辑)
  → /system/lib/hw/screen_source.amlogic.so   ← 真正操作驱动的一层
  → /dev/video12 (vdin0)  ← V4L2
```

注意：`libscreencontrolservice.so` 与 `vendor.amlogic.hardware.screencontrol@1.0.so`
里都**没有** `ioctl`/`vdin`/`/dev/` 字样，驱动访问全部集中在 `screen_source.amlogic.so`。

## 2. 设备节点

| 节点 | 对应 | 实测 |
|---|---|---|
| `/dev/video12` | vdin0 | screencontrol 独占（`/proc/7688/fd/20`），`open` 返回 **EBUSY** |
| `/dev/video11` | vdin1 | `open` 返回 **ENOMEM**（device-tree 有 `vdin1_cma_reserved`，未配置 CMA） |
| `/dev/vdin0` | 旧式字符设备 | 可 open，但所有 V4L2 ioctl 返回 **ENOTTY**——**不是** V4L2 接口，此前误判 |

**结论：自建通道只能接管 `/dev/video12`，即必须先停掉 screencontrol。**

## 3. 打开与默认参数（`init(int)` @ 0x133f0）

```c
fd = open(path, 0x802);         // O_RDWR | O_NONBLOCK
ctx = calloc(1, 0x1f0);         // 496 字节上下文
ctx->buf_count   = 3;           // this+0x54
ctx->pixelformat = 0x3132564e;  // "NV21"  ← 关键
ctx->field_x     = 0x11;        // this+0x60，语义未确认（可能是 port_type / field）
ctx->this+0x40 = 0; ctx->this+0x58 = 0;
```

**NV21 = 1.5 字节/像素**，而厂商 API 交给 Java 的是 RGBA（4 字节/像素）。
也就是说 NV21→RGBA 的转换发生在厂商栈内部，属于那 40ms 固定开销的一部分。

## 4. ioctl 序列（`start_v4l2_device()` @ 0x13624）

| 顺序 | 常量 | 解码 | 参数 |
|---|---|---|---|
| 1 | `0x80685600` | `_IOR('V',0,104)` = **VIDIOC_QUERYCAP** | 104 字节 cap 结构 |
| 2 | `0xc0145608` | `_IOWR('V',8,20)` = **VIDIOC_REQBUFS** | count=`this+0x28`(默认 3)，type=1，memory=1(MMAP) |
| 3 | `0xc0445609` | `_IOWR('V',9,68)` = **VIDIOC_QUERYBUF** | index=i，type=1，memory=1 |
| 4 | `mmap(0, buf.length, 3, 1, fd, buf.offset)` | — | PROT_READ\|PROT_WRITE，MAP_SHARED |

第 4 步之后（QBUF / STREAMON / 首帧等待）在函数尾部，本次响应被截断，**未确认**。

## 5. 格式协商（`set_format(int w, int h, int x)` @ 0x13dfc）

- 宽度按 **32 对齐**：`w_aligned = (w + 0x1f) & 0xffffffe0`，写入 `ctx+0x1d8`（stride）
- 某个模式标志（`ctx+0x1ec == 1`）下走 **64 对齐 + 双 slice** 逻辑：
  `slice = w / 64`，偏移 `this+0x4c = w % 64`
- 具体 S_FMT 的 ioctl 号与 `struct v4l2_format` 布局：**未确认**

## 6. 对外可用的能力（供自己实现时对照）

`vdin_screen_source` 导出的方法（均为 `android::vdin_screen_source::`）：

```
init(int)                        start() / stop() / pause()
set_format(int,int,int)          get_format()
set_crop(int,int,int,int)        set_amlvideo2_crop(int x4) / get_amlvideo2_crop()
set_port_type(uint32)            get_port_type()
set_frame_rate(int)              set_screen_mode(int) / set_mode(int)
set_rotation(int)                set_data_callback(cb, void*) / set_state_callback(cb)
aquire_buffer(aml_screen_buffer_info*)   release_buffer(long*)
start_v4l2_device() / stop_v4l2_device()
init_native_window() / set_preview_window(ANativeWindow*)
workThread()                     get_current_sourcesize(int*, int*)   microdimming(...)
```

`aml_screen_buffer_info` 结构体布局：**未确认**（在 `aquire_buffer` 的参数里，未展开）。

## 7. 自建通道需要补的功课

1. `set_fmt` 的 ioctl 号 + `struct v4l2_format` 逐字段值（width/height/pixelformat/bytesperline/field）
2. `VIDIOC_S_OUTPUT`（Amlogic 扩展，用于选输入源）的号与参数
3. `workThread` 里的 DQBUF→回调→QBUF 循环，以及超时/丢帧处理
4. `aml_screen_buffer_info` 的字段顺序
5. 与播放器解码器（VDEC）共享 VDIN 的行为——P3 记录过 `startScreenRecord` 会踢掉硬解

## 8. 预期收益（若做成）

| 指标 | 现状（厂商 API） | NV21 直采（估算） |
|---|---|---|
| 每帧回读 | RGBA 4 B/px | NV21 1.5 B/px（-62%） |
| 960×540 单帧拷贝 | ~60 ms | ~23 ms |
| 960×540 帧率 | 8.3 fps | 理论 ~15–17 fps（未验证） |
| 会话模型 | 每帧 start/stop（固定 ~40 ms） | 常开流（固定开销消失） |

**这两个收益叠加才有可能突破 20 fps 天花板**，但均属估算，未做任何真机验证。

---

## 9. 真机实测记录（2026-11-21 04:00）

自写探针 `probe/vdin/vdin_stream.c`（单平面）与 `vdin_stream_mp.c`（多平面），
用 NDK r26b + `clang-cl --target=armv7a-linux-androideabi28` + `ld.lld` 编译。

### 9.1 能通的部分（实跑）

| 步骤 | 结果 |
|---|---|
| `open("/dev/video12", O_RDWR\|O_NONBLOCK)` | ✅ 成功（**前提：先停掉自己的 8899 服务**，让 screencontrol 空闲释放设备；否则 EBUSY） |
| `VIDIOC_S_FMT`（NV21, 480×270） | ✅ `stride=720 size=194400`（= 480×270×1.5，符合 NV21） |
| `VIDIOC_REQBUFS`（4, MMAP） | ✅ count=4 |
| `QUERYBUF` + `mmap` | ✅ 4 个 buffer，每个 195,840 字节 |
| `VIDIOC_STREAMON` | ✅ 成功 |
| `VIDIOC_S_OUTPUT`（`0xC0045627`，4 字节 u32） | ✅ 传 0x11 / 1 / 3 都返回 rc=0 |

**注意**：多平面（MPLANE）在这台上不成立——`S_FMT(MPLANE)` 直接 EINVAL，单平面才是对的。

### 9.2 卡住的部分

**STREAMON 之后 3–4 秒零帧**（`select` 全部超时），屏幕已唤醒、`awake=true` 时也一样。
试过 port type = 0x11 / 1 / 3，都被接受但都不出帧。

结论：**VDIN 没有被路由到合成器输出**。缺的大概率是 `set_mode` / `set_screen_mode` 那一层
（它决定 VDIN 采哪条通路），或者 `/sys/class/vdin/vdin0` 的某个使能位。
`set_format` 里那个 `ctx+0x1ec` 模式标志（由 `set_port_type` 用 `(p>>16)&0x1ffff` 写入）
也还没摸清取值。

### 9.3 结论

- 「能自己开流」已验证：V4L2 这一层完全打通，参数被驱动接受。
- 「能拿到画面」未验证：还差路由/使能那一步。
- 收益（NV21 + 常开流）目前仍是**估算**，没有任何实测支撑。

### 9.4 补充试验（同日 04:10）

- port type 再试 `0x10011 / 0x10001 / 0x20011 / 0x40011 / 0x1f`：**全部 rc=0，全部零帧**。
  说明 `S_OUTPUT` 的返回值不能作为"设置成功"的判据，或者真正的门不在这个 ioctl 上。
- 反编译 `start()` @0x13954 显示：它只做 `start_v4l2_device()` → 可选 `init_native_window()`（`this+0x78 & 1`）
  → 可选创建 WorkThread（`this+0x78 & 3`）→ 调状态回调。**没有额外的路由动作**，
  且 WorkThread 只是取帧循环，不是使能开关。⇒ 缺的那一步在 `set_mode` / `set_screen_mode` 或内核侧。
- 设备上**没有 strace**（`which strace` 为空），所以无法直接抓厂商的 ioctl 序列。

### 9.5 下一步（唯一高效的办法）

不要继续盲猜参数，改成**给厂商进程挂一个 ioctl 记录器**：

1. 用 NDK 编一个 `libiolatch.so`，`LD_PRELOAD` 进 `screencontrol`，拦截 `open`/`ioctl`，
   把 `(fd, request, arg)` 与几个 V4L2 结构体的前若干字节打到 log。
2. `stop screencontrol` → 手动带 `LD_PRELOAD` 起一次 → 用现有 8899 客户端触发一次抓帧 →
   收集完整 ioctl 序列。
3. 比对序列里我们缺的那几条（大概率就是 `set_mode` 对应的 ioctl 及其参数）。
4. 恢复：`start screencontrol` + `restore-service.sh`。

这比读内核驱动快得多，也能直接拿到真实参数值（port type、S_FMT 全字段、额外 ioctl）。
代价：又要停一次 screencontrol（约 40 秒），且需要认真做回退。

---

## 10. 突破：真实序列与关键值（2026-11-21 04:20）

用 `libiolatch.so`（LD_PRELOAD 拦 ioctl）挂在厂商 `screencontrol` 上，录到 10,081 行真实调用。
`S_OUTPUT` 共出现 283 次（每帧会话一次）。

### 10.1 厂商的真实序列

```
S_OUTPUT      0xc0045627   value = 0x1100a006      ← 关键！
custom        0xc0cc5616   (204 字节)              ← 我们没调过
S_FMT         0xc0cc5605   (204 字节结构)          ← 注意不是标准 208
custom        0x4014563c   (20 字节)               ← 我们没调过
QUERYCAP      0x80685600
REQBUFS       0xc0145608   count=4 type=1 memory=1
QUERYBUF      0xc0445609   ×4
QBUF          0xc044560f   ×4                      ← 标准里 15 就是 QBUF
STREAMON      0x40045612
DQBUF         0xc0445611   (EAGAIN 轮询 → 成功)
QBUF          0xc044560f   ← 循环
```

### 10.2 唯一必需的那个值

**`S_OUTPUT = 0x1100a006`**。之前的 0x11 / 1 / 3 / 0x10011 … 全部被驱动接受（rc=0）
但一帧都不出；换成 0x1100a006 立刻出帧。

按 `set_port_type` 的位域：`(p>>16)&0x1ffff = 0x1100`（模式位），低 16 位 `0xa006` 是端口。

### 10.3 实测帧率（自建 V4L2 直采，NV21，不含编码）

| 分辨率 | 自建通道 | 厂商 API | 倍数 |
|---|---|---|---|
| 480×270 | **29.93 fps** | 16.6 fps | 1.8× |
| 960×540 | **30.27 fps** | 8.3 fps | 3.6× |
| 1280×720 | **30.17 fps** | 5.5 fps | 5.5× |
| 1920×1080 | **30.28 fps** | 3.0 fps | 10× |

帧间隔 avg 33.4 ms、min 16.0、max 43.8 —— **帧率与分辨率解耦**，恒定 30 fps。
首帧 `bytesused` 精确等于 `W×H×1.5`（NV21），前 1 KB 全非零，是真画面。
（1080p 那次 Y[0]=0、前 1 KB 全零，可能是该分辨率下画面本身是黑的，帧率仍 30。）

### 10.4 还没做的

- 这只是**采集层**帧率：没有 JPEG 编码、没有网络。端到端还要加上编码（NV21→JPEG 可用
  `android.graphics.YuvImage`，无需转 RGBA）。
- 需要接管 `/dev/video12`（停 screencontrol）才能用，与现有 MJPEG 服务互斥。
- 两个私有 ioctl（`0xc0cc5616`、`0x4014563c`）未解析；目前不用它们也能出帧。
- 1080p 黑帧待确认。

回退：`/data/local/tmp/restore-service.sh`，实测可用（本次实验后 3 次恢复全部成功，17.9 fps）。
