# 工程笔记（踩过的坑，按主题）

这份笔记只写"为什么这么写"。所有结论都来自真机（Sony XZ1 + XD05 Bal）与模拟器实测，
踩错时的症状写在各条后面，方便后来人对照日志定位。

---

## 1. USB 音频（UAC2，用户态等时泵）

| 结论 | 踩错的症状 |
|---|---|
| 全速 USB 每微帧 8000 帧，每微帧字节数必须用**整数累加**交替（44.1k×8B → 44,44,…,45） | 直接整除截断 → 44.1k 实际低 0.227%，DAC 跑调/嗒嗒 |
| 每帧字节数 = 通道数 × subslot（32bit=8 / 24bit=6 / 16bit=4），不能硬编码 8 | 24bit alt 上整帧错位 → 静音 |
| `FORMAT_TYPE` 描述符要按 `bLength` 分支（≥8 与 ==6 字段位置不同），解析不到 subslot 时兜底 **4**（不是 1） | 帧长算成 2B，数据只有 1/4 → 全静音 |
| DSD native 判定看 AS_GENERAL 的 `bmFormats` bit31，不能看 `bFormatType` | 把 DSD alt 当 PCM → 静音 |
| 解绑内核驱动要先 Java `claimInterface(itf, true)`（USBDEVFS_DISCONNECT_CLAIM），再 native claim | 只有 `detach_kernel_driver` 时部分 ROM 拿不到接口 |
| `close_stream()` 后任何 native 调用都必须先 `ensure_open(fd)` 重 wrap | 用悬空句柄 → 崩溃/静音 |
| 停播必须 `setInterface(alt 0)` + `releaseInterface`，且是幂等的（stop/失败/onDestroy/onTaskRemoved 全覆盖） | 不播放也一直"哒哒哒" |
| `libusb_set_option(nullptr, …)` 会隐式建默认 context 并枚举设备（avc denied 来源），要用 `libusb_init_context` | 权限报错 + 性能抖动 |

## 2. DoP（DSD over PCM）

- 32-bit 子槽里 marker 必须在 **bit31..24**，16bit DSD 载荷在 bit23..8，最低字节 0：
  `word = (marker << 24) | (dsd_b0 << 16) | (dsd_b1 << 8)`；marker 逐帧交替 `0x05 / 0xFA`。
- marker 放错位（例如 bit23..16）→ DAC 不认 DoP，按载波率当 PCM 播（听感：带音调的刺耳噪）。
- 暂停/欠载时必须继续送**合法 DoP 静音帧**（`00 69 69`），送全 0 会让 DAC 丢 DoP 锁定。
- DSD native / DoP 的载荷是位流，**不能乘增益**（PCM 的淡入淡出要按样本插值，per-URB 常增益会有台阶）。

## 3. 环形缓冲 / 供数（PCM）

- 暂停 = **保留**缓冲（停取数 + 淡出）；seek = 必须丢弃（`seek_flush`，读指针追平写指针），
  否则会先把旧位置的数据播完（44.1k 约 2.9 s）。
- 进度必须用"**已送给 DAC**"的字节（`pos − ringUsed`，DoP 再除 2），不能用"已读出"的字节，
  否则预填 1MB 缓冲会让切歌从 ~3.5 s 开始。
- 环形缓冲 **4 MB**（`native/usb_stream.cpp: kRingBytes`，96k/24bit ≈5.2 s）。演进：1 MB 时 96k
  有 ~270 次/s 欠载 → 2 MB 降到 ~10 次/s（供数周期性突发）→ 4 MB 加预填。
- **起播要"先灌饱再解除静音"**：native 泵在静音态不消耗环形缓冲，所以起播时先让生产者灌到
  约 0.8 s 的水位再 `set_mute(false)`；否则"生产≈消耗"时水位会一直贴在 0 附近，几十毫秒的抖动
  就是一次欠载（真机 96k/24bit 的"偶发滋啦"）。
- 欠载计数口径：一个 URB 少 ≥2 帧（≥16 B）才算一次；native 在 DEBUG 里每秒最多打一条
  `欠载：URB 需 N B，只取到 M B（差 K B），环里剩 R B`，用来分辨"环见底"还是别的原因。
- **泵要"静音启动、预填后淡入"**：`start()` 里 gain/target 置 0、`g_silent=true`；静音分支不读
  环形缓冲也不计欠载，于是"有流但还没数据"的窗口里不会产生起播欠载（以前泵一起来就抢数据，
  第一包必然空 → 起播"咔"一声）。上层 `prefillRingThenUnmute()` 灌到约 0.5 s 音频量再
  `set_mute(false)`；暂停恢复走 `resumeOutput()` 同样会解除静音。
- 预填完成后要 `nativeResetStats()` 清零：预填期的欠载发生在淡入之前（听不到），
  不清零日志里会永远挂一个恒定数字，容易误判。起播/稳态欠载在 native 里分开计数
  （`startup_underruns` → 供数日志的 `起播欠载=`）。
- **起播延迟的真凶是"首块太大"**：数据源冷启动时若一次要 2 MB，465 KB/s 的公网 WebDAV 光等这一块
  就要 4.3 s（表现：切歌/起播 4 秒才出声）。现在冷启动只取 1/4 块（256 KB）并在访问到块尾时补全；
  解码侧单次读源改为 `max(128 KB, 帧长×2)`。
- **后台线程停止不要用 `interrupt()`**：预读线程里 `Thread.sleep` 收到中断会抛
  `InterruptedException`，线程没捕获就是 `FATAL EXCEPTION: http-prefetch` → 进程被杀 →
  "点播放没反应"（真机起播 8 次挂 3 次）。用标志位 + 短 sleep（15 ms）优雅退出，并给整个循环
  包一层兜底 catch。
- **起播第一跳只发一次请求**：`Range: 0-(块/4-1)` 同时拿 `Content-Range` 的总长与首块数据，
  别再用 `Range: 0-0` 只探长度、再单独取首块（公网白白多一个 RTT）。服务器忽略 Range 回 200 时
  必须**有界读取**，不能把整个文件读进内存。
- **mediaPlayback 前台服务的通知必须挂 MediaSession**（`MediaStyle` + token），否则系统认为
  "FGS 没绑定会话"（日志 `setFgsIfNoSessionIsLinkedToNotification`），暂停/停止后会回收进程，
  表现就是"停止→立刻再播没反应、intent 丢掉一半"。停止后还保留 60 s 宽限再 `stopSelf()`。
- **网络必须在独立线程预读**（`HttpMediaDataSource` 的 `http-prefetch` 线程，窗口 12 MB）：
  解码线程同步读源时，网络一抖就会退化成"碎读 + 重试"（真机实测 5 秒 557 次、每次 ~9 KB），
  4 MB 环被抽干 → 泵欠载 → "滋啦"。有了预读窗口，解码线程基本都命中缓存。
- 数据源短读（部分数据）不要当失败立即返回：要带退避重试几次并保留已取到的部分，
  否则外层 1 ms 后重试会变成高频碎读。
- **标准 FLAC 流不要用 MediaExtractor 探测**：只要文件头是 `fLaC`，位深/声道/采样率/总样本
  都能从 STREAMINFO 直接读（`FlacFrameReader`）。用 extractor 探测会让平台 FLACDecoder 对着
  不完整的探测数据报一串 `no streaminfo metadata block`，而且会白解一遍（起播更慢、更费电）。
  现在：`fLaC` → 自研切帧（任何位深都走 float 输出）；其他容器（m4a/aac 等）才走 extractor。
- 供数日志：`Producer` 每 10 s 一行 `供数：…×实时 + underrun 增量 + ring 最低水位`（debug 构建）。

## 4. 解码与位深（最容易翻车的部分）

- USB 端帧长固定 `2ch × 4B = 8B`（24-in-32）。**位深只认实际值，绝不靠猜**：
  `pcm-encoding` → `android._config-pcm-encoding` → 都无效按 MediaCodec 规范默认 **16bit**。
  历史事故：用 `n % (ch*4) == 0` 猜成 32bit，m4a/AAC 直接 **2 倍速 + 音调升八度**（"外星人声"）。
- codec2 的位深可能只在 AMessage 层，Java `getOutputFormat()` 里两个 key 都没有 → 必须有 16bit 兜底。
- 自检日志（每源一次）：`解码自检：X B / Y ms → 比例 1.02（应 ≈1.00）`；比例 ≈2.0 就是 2 倍速复现。
- **平台 FLAC extractor 会把 24bit 素材降成 16bit**（`pcmBytes=2 extractor报=24bit`）。要真 24bit
  必须自己切帧喂解码器并**显式要求 `KEY_PCM_ENCODING = ENCODING_PCM_FLOAT`**。
- 喂平台 FLAC 解码器的三条硬约束：
  1. 先喂 **42 字节合成头**（`fLaC` + STREAMINFO 元数据块，last 置位），只喂帧数据会一个字节都不吐；
  2. 输入要**帧对齐**（任意 256 KB 块只吐 42% 样本），把多帧拼成超大缓冲会
     `IllegalStateException: buffer is inaccessible`；
  3. **seek 不能用 `flush()`**：flush 后解码器会把已解码帧再吐一遍（读到整曲两倍的量），
     必须 `release() + create()` 重建。
- 24bit 精确解码的 seek：样本比例估偏移 → 在 ±4 帧界内按帧头 CRC8 找合法帧（不必扫全文件）。

## 5. 流式（HTTP / WebDAV / Qobuz）

- 必须给 `MediaExtractor` 一个实现 `MediaDataSource` 的 Range 随机读（现在 1 MB 块、一次补 2 块、
  LRU 24 MB）；否则 seek 直接失败。
- 真机 96k/24bit 的供数只有 0.64× 时，根因有两个：**帧扫占一半 CPU** + **缓冲太小**。
  现在的做法是：独立解码线程 + 6 MB 预读 + 帧对齐喂入 + 2 MB 顺序块读。
- `HttpURLConnection` 在 Android 只允许少数方法，`PROPFIND` 必须用 okhttp；缺 INTERNET 权限的症状是
  socket EPERM（不是 SecurityException）；targetSdk≥28 起内网 http 需要 `usesCleartextTraffic`。
- PROPFIND 多状态响应的首条通常是"自身"，要按规范化 href 过滤；文件名优先 `displayname`。
- Qobuz：私有 API `https://www.qobuz.com/api.json/0.2/`，头 `X-App-Id`/`X-User-Auth-Token`；
  取流签名 `md5("trackgetFileUrlformat_id{fmt}intentstreamtrack_id{id}{ts}{secret}")`（**秒级** ts）。
  app_id/secret 在运行时从 web 播放器 bundle 里提取并只存本机（不写进仓库）。

## 6. Android 平台行为

- 动态注册非纯系统广播的 receiver，Android 14 上必须带 `RECEIVER_NOT_EXPORTED`。
- 向 Service 发控制命令要用**显式组件** Intent（`setComponent`），`setPackage` 对无 intent-filter 的
  service 会静默 not found。
- MediaSession 三件套（`isActive` + `setPlaybackState` + Callback）缺一不可，否则锁屏不显示。
- `.dsf/.dff` 不属于媒体 MIME，MediaStore 不收录；分区存储下没有 `MANAGE_EXTERNAL_STORAGE`
  就既不在曲库也打不开（判据：`run-as <pkg> ls /sdcard/...` 被拒）。
- 装包后**第一次**点播放时 `usb.connected` 可能还是 false（枚举/授权未完成）→ 等 2.5 s 再判，
  不要直接抛异常（用户会以为"点了没反应"）。

## 7. 界面/交互

- 播放页封面必须用 `min(可用宽, 剩余高)` 的正方形边长，宽高相等，否则"看起来上下被裁"。
- 切歌瞬间要先把 position/duration 清零再渲染，否则迷你进度条会按上一首的进度画满格。
- 迷你进度条宽度不能设 0（ConstraintLayout 里 0dp = match_constraint）→ 用 1px + INVISIBLE。
- 胶囊/圆角容器做"贴边选中"时，选中项外侧圆角半径 = 容器圆角，内侧用小值；首/中/末三段要分别画。
- 网格封面要靠 **URL/尺寸做 tag 门控**再异步加载，否则复用 View 会串图。
- 无中文标签的曲库用目录名「艺术家 - 专辑」+ 文件名「01 - 曲名」兜底，且只在标签为空时生效。

## 8. 调试方式（不需 DAC）

```bash
# 本地文件 / http 直链 / webdav:<路径> 都能验：解码、位深、时长、seek
adb shell am start-foreground-service -n com.dsd.player/.playback.PlaybackService \
  -a com.dsd.player.SELFTEST --es path /sdcard/Music/x.flac --ei secs 12
adb logcat -s PlaybackService FlacSource FlacFloat HttpFlac StreamByteSource Mp4ByteSource
```

`SELFTEST` 与 `dl:` 钩子只在 **debug 构建**可用（release 构建里直接忽略）。

模拟器没有 DAC 也能看关键日志：`精确解码：请求 FLOAT 输出`、`解码输出 encoding=4(float)`、
`吞吐：…×实时`、`解码自检：… 比例 ≈1.00`。

## 9. 小屏 / 新系统适配（720p、16 KB 页）

- **16 KB 页对齐**：Android 15+ 出现 16 KB 内核设备，未对齐的 `.so` 会加载失败（当前 4 KB 设备只弹
  警告："此应用不符合 16 KB 对齐要求"）。NDK r27 及以下必须在链接阶段加
  `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`（`app/src/main/cpp/CMakeLists.txt`），
  NDK r28+ 默认就是。
  自查两条：`llvm-readelf -l libdsdplayer.so | grep LOAD` → Align 必须 `0x4000`；
  `zipalign -c -P 16 -v 4 app.apk` 必须 "Verification succesful"。
- **播放页封面算大小别用兄弟控件的当前 height**：封面一变大就会把兄弟挤扁，用"当前 height"求和
  会偏小 → 封面越算越大，最后把 DAC 卡片挤到可视区外（720p 上 `dacCaps` 只剩 9px）。
  正确做法：对每个兄弟控件 `measure(AT_MOST(width), UNSPECIFIED)` 取**自然高度**再加 margin。
- **ScrollView/NestedScrollView 里直接子项别用 `match_parent` 高度**：会被夹到视口高度，
  `wrap_content` 才让内容自然增长并可滚动（`page_player.xml` 曾因此挤压最底部卡片）。
- **目录名切分要跳过括号内的分隔符**：`[Hi-Res]Test Album` 里的 `-` 不是"艺术家 - 专辑"的分隔符，
  否则会切成 artist=`[Hi` / album=`Res]Test Album`（显示成 `Res]Test Album [Hi`）。
- 验证小屏不用真机：`adb shell wm size 720x1280 && adb shell wm density 320` 复现 720p 几何，
  用 `uiautomator dump` 读控件 bounds 判断有没有被裁（比肉眼靠谱）。

## 10. USB 异步反馈与曲末淡出（第一百二十五轮）

- **async DAC 必须读 feedback 端点**：XD05 Bal 这类异步 DAC 在流接口上有一个 IN 端点
  （`bmAttributes` bit5..4 = 01 = feedback，16.16 定点，单位"样本/微帧"）。主机按标称帧率
  恒定发送时，DAC 晶振与主机标称差几十 ppm，漂到 FIFO 边界就漏/溢一次 —— **主机侧零日志**，
  听感是偶发「嗞」，且采样率越高越频繁。现在：解析 `ep_fb`/`bRefresh`，开中断 IN 传输周期性读回，
  按 `fps = fb × 8000 / 65536` 调整每微帧帧数（复用原来的整数 delta-sigma 累加器），
  并 clamp 在标称 ±2% 内；反馈不可用时自动回落到标称帧率。日志：`feedback=… 帧/µframe（偏差 … ppm）`。
- **曲末要提前淡出**：等泵把环形缓冲吃空才淡出，切歌处必然出现一条 `欠载：…环里剩 0 B`。
  现在 EOF 后先等水位降到约 50 ms 再 `set_mute(true)` 淡出，然后才排空/切歌 —— 切歌处不再有欠载行。
- **HTTP 加固**：读超时 4 s（丢包下 TCP 重传 1–3 s 能容忍，4 s 没数据就是真停顿）；
  重试时**每次缩小 Range 范围**（大请求卡住后重试同一个大请求只会再卡一次）。
  服务器不支持 Range（回 200 + 整文件）时必须**流式跳过 + 有界读取**，绝不能 `body.bytes()`。
- **曲末淡出提前量要 ≥ 200 ms**：50 ms 时真机切歌仍出现 `欠载：URB 需 352 B，只取到 240 B`
  —— 那正好是泵最后两个 URB 的量，而"判断环低 → 调 native 静音"自身有几十毫秒延迟
  （日志里环见底到"开始淡出"隔 61 ms），于是泵先饿死。改 200 ms（8 MB 环的 2.5%）+ 5 ms 轮询。
- 环形缓冲 8 MB（96k/24bit ≈10.4 s）：链路有 10% 丢包 + 秒级停顿时，4 MB 会被瞬间抽干。

## 11. 反馈端点的提交方式（第一百二十七轮，真机踩到的 EIO 真因）

- **反馈端点在描述符里是 isochronous，就必须用 iso transfer 提交**。用
  `libusb_fill_interrupt_transfer` 打到 iso 端点，usbfs 直接回 `LIBUSB_ERROR_IO`（EIO），
  看起来像"设备不支持反馈"，实际是提交类型错了。判据：端点描述符 `bmAttributes & 0x03 == 0x01`。
  现在按属性选类型，失败时 iso / interrupt 轮流各试（有些固件两者都吃）。
- **`libusb_alloc_transfer(n)` 的 n 必须预留够 iso 包数**：`libusb_fill_iso_transfer` 只写
  `iso_packet_desc[]` 不复分配，按 0 分配再填 1 个包就是越界写。反馈端点固定按 1 包分配。
- **绝不能在任何 libusb 回调里 `sleep` 重试**：回调跑在事件线程上，睡 300 ms 会让音频 URB 全部超时
  → 满屏欠载 + 「嗞」。重试要改成"置 pending 标志，由**音频 URB 完成回调**按 100 ms 节奏驱动"，
  并且失败日志要限流（前 3 次 + 每 10 次一条），否则一次重试风暴能刷几百行。
- **反馈值有两种定点解释，要按合理性选**：UAC2 是 16.16「样本/微帧」（`raw/65536*8000` 帧/秒）；
  部分固件沿用 UAC1 的 10.14「样本/帧」（`raw/16384*1000` 帧/秒）。两种都算，取落在标称 ±10% 内
  的那个，都不在内就**退回标称帧率并打 WARN**，绝不把坏反馈灌进帧长计算。
- **反馈 URB 要"多包成串"提交，单包 URB 拿不到数据**：UAC2 的 `bInterval` 语义是
  「每 2^(bInterval-1) 微帧一个反馈包」。按单包 URB 提交等于每微帧重提交一次，
  真机上设备**一个字节都不回**（第一百二十八轮：端点已开、6 分钟无任何反馈值、也无错误）。
  现在一次装 `kFbPackets = 8` 个包（`alloc_transfer(8)`，每包 `wMaxPacketSize` 字节），
  节奏与设备一致，重提交次数也降到 1/8。
- **反馈值长度要按实际字节数认，不能写死 4**：UAC2 是 4 字节 16.16；UAC1 遗留固件是
  **3 字节 10.14**（`raw/16384*1000` 帧/秒）。两种解释都算、取落在标称 ±10% 内的那个。
  另外 `libusb` 个别版本只填 `iso_packet_desc[].actual_length` 而不汇总到 `actual_length`，
  两个都要看（多包 URB 里数据可能在任何一包）。
- **必须能看见"反馈到底参没参与调速"**：每 30 s 一条汇总
  `反馈汇总 30s：提交 N / 完成 M（0字节 Z，有值 L），最近 raw=0x… ，采用 X 帧/秒，
  实发 %f 帧（标称 %f，±ppm），环 …KB`。判据：`有值` 在涨、`采用` 帧/秒偏离标称、
  实发帧数的 ppm 跟着反馈走；三者都不动说明反馈没生效。
- **`NO_DEVICE` 不是"反馈不可用"**：拔线/重开流窗口内提交会回 `LIBUSB_ERROR_NO_DEVICE`，
  这不该消耗重试预算（以前会累计 2 次就放弃）。现在单独标记 `g_fb_no_device`，立刻停试，
  等下次 `open_stream` 重建（每次开流都清零计数器）。
：`AS altN 端点: data=0x.. fb=0x..(attr/maxpkt/interval/refresh) 数据EP.synchAddr=0x..`。
  反馈端点有三种来源（usage=01、数据 EP 的 `bSynchAddress`、无描述符只能猜），没有这行日志时，
  只能看到"反馈 0x81 提交失败"而无法判断是端点选错还是提交方式错。
