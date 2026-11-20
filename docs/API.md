# HTTP 接口

服务默认监听 `:8899`。**没有鉴权、没有 TLS**，只适合在可信局域网内使用。
唯一的例外是**遥控端点**（`/key`、`/text`）——它们要求一个控制令牌，见下文「遥控」。

## 端点总览

| 路径 | 方法 | 内容类型 | 说明 |
|---|---|---|---|
| `/` | GET | `text/html` | 网页播放器 + 遥控面板 |
| `/raw` | GET | `application/octet-stream` | **裸 JPEG 连续流**（无边界头，前端自己找 `FFD8/FFD9`） |
| `/stream` | GET | `multipart/x-mixed-replace` | 标准 MJPEG，给 VLC / OBS 用 |
| `/snap` | GET | `image/jpeg` | 单帧快照 |
| `/stats` | GET | `application/json` | 实时状态 |
| `/set?w=&h=` | GET | `application/json` | 切换采集分辨率 |
| `/audio?src=` | GET | `application/json` | 切换声音来源 |
| `/pcm` | GET | `audio/L16;rate=48000;channels=2` | 裸 PCM 音频流（S16LE 交错） |
| `/wake` | GET | `application/json` | 唤醒电视 |
| `/sleep` | GET | `application/json` | 让电视息屏 |
| `/key?code=&t=` | GET | `application/json` | **遥控按键（需令牌）** |
| `/text?s=&t=` | GET | `application/json` | **输入文字（需令牌）** |

## `/stats`

```json
{
  "fps": 8.70,
  "kbps": 3052,
  "w": 960,
  "h": 540,
  "clients": 1,
  "frames": 1636,
  "audio": true,
  "aclients": 1,
  "abytes": 40173568,
  "asrc": "TDMOUT_B",
  "apeak": 2632,
  "aerr": "",
  "awake": true,
  "idle": false,
  "idleMs": 30000
}
```

| 字段 | 含义 |
|---|---|
| `fps` | **服务端**编码出的帧率（不是客户端收到的） |
| `kbps` | 视频码率（估算） |
| `w` / `h` | 当前采集输出尺寸 |
| `clients` | 正在连视频流的客户端数 |
| `frames` | 累计编码帧数（从服务启动算） |
| `audio` | 音频采集是否在跑 |
| `aclients` | 音频客户端数（**上限 1**） |
| `abytes` | 累计发出的音频字节 |
| `asrc` | 当前音源 |
| `apeak` | 最近约 1 秒的音频峰值（0..32767），**0 = 当前音源没有信号** |
| `aerr` | 音频链路最近一次错误原文（空字符串 = 正常） |
| `awake` | 电视是否醒着（`dumpsys power`，2 秒缓存） |
| `idle` | **采集是否因无人观看而暂停**（见下节） |
| `idleMs` | 空闲判定阈值（毫秒），默认 30000；`0` 表示该功能关闭 |

> `awake=false` 时画面一定是黑的（显示管线停了），但 `fps` 仍会跳动——那是黑帧。

### 空闲停采

`clients==0` 持续超过 `idleMs` 后，采集线程停止调用厂商接口（不再产帧），把 CPU 让给电视自己；
任一视频客户端接入后 **200 ms 内**自动恢复。

- 空闲时 `fps` 归 0、`frames` 冻结、`idle=true`，这是**正常状态**，不是采集坏了。
- 空闲时 `/snap` 仍然可用：会现场补抓一帧（`frames` 加 1），响应时间从毫秒级变成约 1 个抓帧周期（≤ 100 ms）。
- 音频不跟着停：`tinycap` 反复开关会让 loopback 退化到「能打开但不产数据」，只能重启电视恢复，风险大于它约 0.8% 的 CPU。
- 缩短阈值便于测试：启动参数加 `-Dtvss.idleMs=1500`。

## `/set`

```
GET /set?w=640&h=360
→ {"w":640,"h":360}
```

合法范围 `64..1920 × 36..1080`，超出会被忽略。

实现上会**重启采集管线**（旧线程按 `generation` 计数自动退出），约 0.3 秒黑屏。

## `/audio`

```
GET /audio?src=TDMOUT_B
→ {"ok":true,"src":"TDMOUT_B","run":true,"peak":2632,"err":""}
```

不带 `src` 时只查询状态，不切换：

```
GET /audio
→ {"ok":true,"src":"TDMOUT_B","run":true,"peak":2632,"err":""}
```

| 字段 | 含义 |
|---|---|
| `ok` | 切换是否**真的成功**（不只是"命令发出去了"） |
| `src` | 当前认定的音源（只有校验通过才会更新） |
| `run` | `tinycap` 采集是否在跑 |
| `peak` | 最近约 1 秒的采集峰值（0..32767）。**0 = 这个源当前没有信号** |
| `err` | 失败原因原文（`tinymix` 退出码 / 回读值 / `tinycap` 报错） |

| `src` 取值 | 底层控件 | 说明 |
|---|---|---|
| `TDMOUT_A` / `TDMOUT_B` / `TDMOUT_C` | `Loopback tdmin lb source` | 同一路混音的三个输出口，内容相同；网页只保留「电视声音」一项（`TDMOUT_B`），A/C 仍可通过 API 调 |
| `PDMIN` | `Loopback datain source` | 麦克风（需要电视有远场拾音） |
| `SPDIFIN` | `Loopback datain source` | SPDIF 输入 |
| `TDMIN_A/B/C` | `Loopback datain source` | 内部 TDM 输入 |

切换过程：`tinymix` 把**另一路输入先隔离到空闲源**（`TDMIN_C`）→ 再设目标路 → 两路都回读校验。
任何一步失败都会 `ok:false` + `err` 说明原因，`src` 不会更新。**不重启采集设备**（反复开关会让 loopback 退化）。

> **重要：loopback 是把两路输入相加的。** `Loopback tdmin lb source` 和 `Loopback datain source`
> 是两个独立控件，旧实现每次只改一路、另一路一直开着，所以听起来"换音源没作用"。
> 佐证：六个源抓到的字节数完全一样；选 `PDMIN`（麦克风）时居然能采到灌进 TDM-B 播放的测试音
> ——麦克风不可能采到，只能是两路在相加。现在切源会先把另一路指到空闲的 `TDMIN_C`。

> 2026-11-14 修复：旧实现丢弃 `tinymix` 的退出码、不回读、也不校验 `tinycap` 是否活着，
> 所以控件名不对、取值不合法、设备被占用这三种情况**全都回 `ok:true`**，
> 表现为"换音源没有作用"。现在这三种都会明确失败。

## `/wake` / `/sleep`

```
GET /wake  → {"awake":true}
GET /sleep → {"awake":false}
```

内部先 `dumpsys power` 判断状态再发键，避免反向操作：

- 唤醒：不在 `Awake` 才发 `input keyevent 26`
- 息屏：在 `Awake` 才发 `input keyevent 223`

## `/raw` vs `/stream`

| | `/raw` | `/stream` |
|---|---|---|
| 格式 | 裸 JPEG 首尾相接 | RFC 2046 multipart |
| 适用 | 自己写的 JS 解析 | VLC / mpv / OBS |
| 边界 | 自行找 `FF D8` … `FF D9` | `--frame` 分隔符 |

浏览器端用 `/raw` 是因为 `<img src>` 播放 MJPEG 长跑会卡死（见
[PITFALLS P8](PITFALLS.md#p8--浏览器-mjpegimg-src会莫名其妙卡住)）。

## 遥控（`/key`、`/text`）

**这两个端点要求令牌 `t`**，其余端点不要求。

```
GET /key?code=DPAD_UP&t=<token>
→ {"ok":true,"code":"DPAD_UP"}

GET /text?s=hello%20world&t=<token>
→ {"ok":true,"len":11}
```

| 情况 | 响应 |
|---|---|
| 令牌错 / 缺 | `403` + `{"ok":false,"err":"bad or missing token"}` |
| 未知按键名 | `400` + `{"ok":false,"err":"unknown code: XXX"}` |
| 文字为空 / 含非 ASCII | `400` + `{"ok":false,"err":"empty or unsupported text (ASCII only)"}` |

### 令牌从哪来

- **默认**：进程启动时随机生成，注入到网页里，网页自己带着用——正常用网页不需要关心它；
- **固定**：启动时加 `-Dtvss.token=你的令牌`（≥4 字符），方便脚本：

```bash
adb shell su -c 'CLASSPATH=/data/local/tmp/sc.dex app_process -Dtvss.token=abc123 \
  -Xmx96m /system/bin com.droidlogic.app.ScreenControlManager serve 1 960 540 8899 960 540 70'
curl "http://<tv-ip>:8899/key?code=HOME&t=abc123"
```

- **不要把它写进日志或仓库**（AGENTS.md §2.3）。需要时从页面源码里取：
  `curl -s http://<tv-ip>:8899/ | grep -o "TK='[a-f0-9]*'"`

### 允许的按键（白名单）

| 名称 | 键码 | 名称 | 键码 |
|---|---|---|---|
| `DPAD_UP` / `DOWN` / `LEFT` / `RIGHT` | 19 / 20 / 21 / 22 | `BACK` | 4 |
| `DPAD_CENTER` | 23 | `HOME` | 3 |
| `MENU` | 82 | `ENTER` | 66 |
| `VOLUME_UP` / `VOLUME_DOWN` | 24 / 25 | `MUTE` | 164 |
| `PLAY_PAUSE` | 85 | `NEXT` / `PREVIOUS` | 87 / 88 |
| `DEL` | 67 | `SETTINGS` | 176 |
| `TV_INPUT` | 178 | `NUM_0` … `NUM_9` | 7 … 16 |

**`POWER` 故意不在白名单里**：键码 26 是开关不是"唤醒"，盲发会把醒着的电视关掉
（AGENTS.md §2.4）。电源请走 `/wake` 和 `/sleep`，它们内部先查 `dumpsys power`。

### 安全边界（重要）

令牌能挡住的是**「你访问的恶意网页偷偷按电视」**：跨源页面读不到本页 HTML，也就拿不到令牌；
没有令牌时，`<img src="http://tv:8899/key?code=HOME">` 这种请求是拦不住的。

令牌**挡不住局域网里的其它主机**——它们可以直接 `curl /` 把令牌读出来。
真正的边界仍然是网络隔离：不要把这个端口映射到公网。

## CORS

**本服务不发送任何 CORS 响应头**，`/stats` 也不例外。浏览器里跨源 `fetch('/stats')`
会被同源策略挡住，只有同源页面（`http://<tv-ip>:8899/` 自带的那个网页）能读。

原因：服务本身**没有鉴权**。一旦放开通配 CORS，任意被访问过的网页都能读到电视状态
（分辨率、是否醒着、在线人数），可以拿来推断"家里现在有没有人在看电视"。
需要外部监控面板时的正确顺序是：**先加鉴权，再在反向代理上加限定 Origin 的 CORS**。

> 更正记录：本文档早期版本写的是"`/stats` 带 `Access-Control-Allow-Origin: *`"，
> 与代码不符（`send()` 从未输出该头）。2026-11-14 按实际实现更正为"不发任何 CORS 头"。

## 并发与丢帧

| 项 | 值 | 说明 |
|---|---|---|
| 视频客户端上限 | **8** | 超限时新连接收到 `HTTP/1.0 503 Service Unavailable` + 纯文本 `too many viewers (max 8)`，立即断开 |
| 每客户端积压上限 | 3 帧 / 512 KB | 队列满时丢**最旧**的帧，只影响该客户端自己 |
| 音频客户端 | **1** | 新连接会顶掉旧连接（未变） |
| 流式连接硬上限 | 30 分钟 | `/raw` `/stream` 到点自动断开；网页端靠 4 秒无帧自动重连兜住 |

慢客户端（暂停的标签页、拔网线的笔记本）**不会**拖慢采集、编码或其他观看端：
每个客户端独立排队、独立写线程，详见 [ADR 0003](decisions/0003-每客户端有界队列.md)。

## 客户端示例

```bash
# 抓一帧
curl -o frame.jpg http://192.168.250.11:8899/snap

# 看状态
curl http://192.168.250.11:8899/stats

# 切到低分辨率省带宽
curl "http://192.168.250.11:8899/set?w=480&h=270"

# 用 VLC 播放
vlc http://192.168.250.11:8899/stream
```
