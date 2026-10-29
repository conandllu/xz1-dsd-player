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
- 环形缓冲 2 MB（`native/usb_stream.cpp: kRingBytes`）。欠载计数口径：一个 URB 少 ≥2 帧才算一次。
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
