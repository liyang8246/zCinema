# Z Cinema

一个 **Fabric 1.21.1** 的联机放映 Mod：不依赖机械动力（Create），
支持 **MP4 直链与解析接口链接**，让服务器里的小伙伴一起看视频。

灵感来自 [Create Cinema](https://github.com/Abuous/Createcinema)，
但播放模型被完全重写为「**客户端各自直连锁播 + 服务端只掌握时间轴**」。

---

## 屏幕长什么样

**没有隐藏方块**：用原版**黑色混凝土**贴一块平整的墙（任意大小），
然后**手持木棍 + 潜行 + 右键**其中任意一块 —— mod 会识别这块相连的平整区域，
在注册的那一刻把整面墙替换成「屏幕方块」：外观、硬度、声音、掉落、Pick-block
都和黑色混凝土完全一致，闲置时看上去就是一面黑墙（播放时自适应视频比例，黑边留白）。

屏幕尺寸**只在注册时测量一次**，之后不会实时重测。

| 操作 | 手势 |
| --- | --- |
| 创建屏幕 | 木棍 + 潜行 + 右键 黑色混凝土（整面墙会变成屏幕方块） |
| 打开控制面板 | 木棍 + 潜行 + 右键 屏幕方块，整面墙任意一块 |
| 撤下屏幕 | 面板里的「撤下屏幕」按钮（唯一的撤下方式，整面墙变回黑色混凝土） |
| 播放/暂停/拖动进度 | 面板内操作，全员同步 |

所有屏幕手势都**必须有木棍且潜行**；不拿木棍时右键屏幕和普通黑色混凝土完全一样。
撤下没有世界手势，避免误触。

---

## 它是怎么工作的

客户端时钟、缓冲、纠偏全部照 Create Cinema 的做法移植，只是把「投影仪」换成了混凝土墙上的屏幕方块：

```
        ┌──────────────────────── 服务端（权威时间轴） ────────────────────────┐
        │  CinemaScreenBlockEntity: url / playing / frozen / position / anchor  │
        │  另外聚合所有观众的「播放健康度」，决定要不要冻结时间轴               │
        └───────────────┬───────────────────────────────────────────┬─────────┘
                        │ S2CState（全量快照，变更即发 + 每秒心跳）    │ ▲
                        ▼                                           │
        ┌───────────────┴──────────┐                     ┌───────────┴─────────┐
        │ 客户端 A（本地 FFmpeg）   │  ── C2S 控制/健康度 ──▶│ 客户端 B（本地 FFmpeg）│
        │ 各自直链接流 + 解码 + 声音 │                     │ 各自直链接流 + 解码 + 声音 │
        └──────────────────────────┘                     └─────────────────────┘
```

- **服务端从不碰视频数据**，只维护一条时间轴：
  `实际进度 = positionMs + (播放中 && 未冻结 ? now - anchorMs : 0)`
- 每个客户端用同样的公式按最近一次快照**外推**自己的本地时钟，
  各自用 FFmpeg 从同一个 URL 拉流、解码、播放（画面 + 声音都是本地的）。
- 客户端本地时间轴与共享时钟之间有一层平滑偏移：**每秒只向锚点靠拢 5%**，
  偏差小于 0.15 秒完全不动，只有真的差远了（> 3 秒，可配）才把流定位过去——
  不会跳变，也不会「纠偏→缓冲空→再纠偏」自反馈。

### 同步保证

| 事件 | 行为 |
| --- | --- |
| 暂停 / 播放 | 服务端冻结/继续时钟，全员跟随 |
| 拖动进度条 / 快进快退 | seek 即时间轴改写，本地流直接定位（不重开连接） |
| 本地卡顿（缓冲耗尽 250ms） | 本地回到「缓冲中」，声音一起停，恢复后重新对齐 |
| 视频源真的挂了 | 每秒上报健康度，**多名观众连续 3 秒都报告不可达**才冻结全员时钟；恢复 2 秒后自动继续 |
| 别的客户端卡顿 | 只有它自己被冻住一下，其他人几乎无感 |
| 正常播放 | 客户端领先解码 ~1.5 秒，画面严格按共享时钟取帧（超前 35ms） |
| 新玩家加入 | 进 chunk 的瞬间就会收到当前快照，不会“从 0 开始” |

> 同步**不依赖各机器的系统时钟**：服务端广播的是“发送那一刻的进度”，客户端只使用
> 自己收到之后的本地流逝时间外推，机器之间的时钟差不会进入公式（系统时间没同步也不影响）。
> 客户端的单向网络延迟会用当前 ping 的一半自动补偿，向服务端看到的时间轴对齐。

---

## 使用方法

1. 安装 [Fabric Loader](https://fabricmc.net/)（0.16+）与 [Fabric API](https://modrinth.com/mod/fabric-api)（1.21.1）。
2. 把 `zcinema-<版本>.jar` 丢进 `mods/`（服务端和客户端都装，Fabric API 也是两端都要）。
3. 用**黑色混凝土**搭一块平整的墙（比如 9x5），**手持木棍 + 潜行 + 右键**墙上任意一块
   —— 整面墙会变成外观相同的「屏幕方块」。
4. 对屏幕**木棍 + 潜行 + 右键**打开控制面板 → 粘贴视频链接 →「载入」→「播放」。
   撤下屏幕用面板里的「撤下屏幕」按钮（整面墙变回黑色混凝土）。

> 视频源可以是**直接的 mp4 链接**，也可以是**解析接口地址**（例如
> `http://ckapi.sevenbrothers.cn/bili/api?id=BV1uFaS6uEqz` 这类 B 站解析链接）：
> 客户端会先把它解析成真正的 CDN 直链，再交给 FFmpeg 解码。
> 签名直链过期后会自动重新解析。支持 HTTP Range 的服务器体验最好；
> 不支持 Range 也能播，只是拖动进度会慢一点。画面与声音各走一条 FFmpeg 连接，
> 声音打开失败会自动重试。

## 配置

文件位于 `config/zcinema-common.json` 与 `config/zcinema-client.json`：

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `syncIntervalTicks` | 20 | 全量状态广播间隔 |
| `globalStallPause` | true | 多名观众都报告视频源不可达时，是否冻结全员 |
| `bufferSeconds` | 1.5 | 本地解码领先共享时钟多少 |
| `hardResyncSeconds` | 3.0 | 偏差超过该值时直接把流定位过去 |
| `audioDelayMs` | 100 | 声音相对画面延迟多少毫秒（补偿显示管线，觉得声音快就调大） |
| `maxFrameWidth/Height` | 1920/1080 | 解码缩放上限 |
| `audioDistance` | 48 | 声音传播距离 |

> 服务端配置在开服时读取，改完重启服务器生效；客户端配置在启动时读取。

## 排查日志

每台机器（服务端和每个客户端）都会写一份独立的诊断日志：

```
logs/zcinema.log
```

行格式：

```
绝对毫秒时间戳  本地时间(HH:mm:ss.SSS)  端(C/S)  [类别]  线程  消息
1791031126606 20:38:46.606 C [lifecycle] render === Z Cinema ... side=C ...
```

- **绝对时间戳**是为了把两台机器的日志按时间对齐——排查“多客户端不同步”时，把几份文件放在一起，同一时刻各自的状态一目了然。
- **类别**：`lifecycle` `config` `gesture` `server` `net` `session` `clock` `seek` `decode` `buffer` `audio` `health` `state` `resolver` `range` `ui`。
- 播放中的每个屏幕每秒会写一行 `[state]`，包含最关键的一组数字：

```
[state] screen=3,-58,-1 shared=334.582s media=334.551s itemStart=+0.031s drift=-0.031s
        videoErr=-0.042s audioErr=+0.118s frame=334.540s buf=41/1.37s ready=true rebuf=false
        seeking=false resyncing=false playing=true frozen=false clockMoving=true failed=false
        ended=false rangeOk=true
```

| 字段 | 含义 | 异常表现 |
| --- | --- | --- |
| `shared` | 服务端权威时钟（本地外推） | 各机器应一致 |
| `media` | 本机正在播放的媒体时间轴 | 与 `shared` 差太多说明本机定位没跟上 |
| `videoErr` | 最近上屏画面的时间 − `media` | 明显负值＝画面落后（卡帧、追帧中） |
| `audioErr` | 声音估算位置 − `media` | 绝对值大＝音画不同步 |
| `buf/ready/rebuf` | 帧队列长度/秒数、是否可播、是否在缓冲 | `rebuf=true` 表示正在等数据 |
| `video/drop` | 每秒解码出的帧数/被跳过的（过时）帧数 | `drop` 持续大于 0 说明本机解码跟不上，画面会落后 |
| `seeking` | 是否正在定位 | 长时间 true 说明源定位慢或卡住 |
| `resyncing` | 是否正在“对齐共享时钟”的重定位 | 前跳/回跳后应短暂为 true，随后归位 |

其它值得注意的事件行：`[seek] hard resync`（服务端时间轴跳变）、`[seek] applied/landed`（定位发起/落地耗时）、`[audio] reopen`（声音重开原因和漂移量）、`[clock] snapshot delta=`（服务端快照与本地外推的差值）、`[health] FROZEN/recovered`（全员冻结判定及依据数字）。

反馈问题时请提供：**每台机器**的 `logs/zcinema.log` + `logs/latest.log`（日志超过 8MB 会自动轮转为 `zcinema.log.1`）。

> 日志里会包含完整的视频链接（直链通常带签名参数），公开发布前注意删除。

## 构建

```bash
./gradlew build
```

产物在 `build/libs/`。`bundled_platforms` 决定打进 jar 的 FFmpeg 原生库，
当前是 `windows-x86_64`（只有客户端需要解码）；想要全平台包：

```bash
./gradlew build -Pbundled_platforms=windows-x86_64,linux-x86_64,linux-arm64,macosx-x86_64,macosx-arm64
```

开发运行用 `./gradlew runClient` / `./gradlew runServer`，运行目录是 `run-fabric/`。

Java 21 与 Gradle 9.2 由 wrapper 自动处理，Fabric Loom 会下载 Minecraft 与映射。

## 第三方依赖

- [JavaCV / JavaCPP / FFmpeg](https://github.com/bytedeco/javacv)（Apache-2.0）：本地解码 MP4 的画面与声音
- [Fabric Loader / Fabric API](https://fabricmc.net/)：平台本体
- Minecraft：平台本体

## 许可

MIT（沿用 Create Cinema 的思路，代码为独立实现）。
