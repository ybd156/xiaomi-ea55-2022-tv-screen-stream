# HTTP 接口

服务默认监听 `:8899`。**没有鉴权、没有 TLS**，只适合在可信局域网内使用。

## 端点总览

| 路径 | 方法 | 内容类型 | 说明 |
|---|---|---|---|
| `/` | GET | `text/html` | 网页播放器 |
| `/raw` | GET | `application/octet-stream` | **裸 JPEG 连续流**（无边界头，前端自己找 `FFD8/FFD9`） |
| `/stream` | GET | `multipart/x-mixed-replace` | 标准 MJPEG，给 VLC / OBS 用 |
| `/snap` | GET | `image/jpeg` | 单帧快照 |
| `/stats` | GET | `application/json` | 实时状态 |
| `/set?w=&h=` | GET | `application/json` | 切换采集分辨率 |
| `/audio?src=` | GET | `application/json` | 切换声音来源 |
| `/pcm` | GET | `audio/L16;rate=48000;channels=2` | 裸 PCM 音频流（S16LE 交错） |
| `/wake` | GET | `application/json` | 唤醒电视 |
| `/sleep` | GET | `application/json` | 让电视息屏 |

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
  "awake": true
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
| `awake` | 电视是否醒着（`dumpsys power`，2 秒缓存） |

> `awake=false` 时画面一定是黑的（显示管线停了），但 `fps` 仍会跳动——那是黑帧。

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
→ {"ok":true,"src":"TDMOUT_B"}
```

| `src` 取值 | 底层控件 | 说明 |
|---|---|---|
| `TDMOUT_A` / `TDMOUT_B` / `TDMOUT_C` | `Loopback tdmin lb source` | `TDMOUT_B` = 电视扬声器（唯一确认有信号） |
| `PDMIN` | `Loopback datain source` | 麦克风（需要电视有远场拾音） |
| `SPDIFIN` | `Loopback datain source` | SPDIF 输入 |
| `TDMIN_A/B/C` | `Loopback datain source` | 内部 TDM 输入 |

切换过程：`tinymix` 改路由 → 杀掉 `tinycap` → 重启 `tinycap`（约 0.4 秒断音）。

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

## CORS

`/stats` 带 `Access-Control-Allow-Origin: *`，方便做外部监控面板。
其余端点不带。

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
