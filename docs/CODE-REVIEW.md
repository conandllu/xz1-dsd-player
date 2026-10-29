# 代码审查记录（开源前）

审查范围：`app/src/main/java`（39 个 Kotlin 文件，约 11.7k 行）、`app/src/main/cpp`（约 5.7k 行）、
Manifest、Gradle、脚本与文档。目标：**开源源码发布前**清掉调试痕迹、修掉明显缺陷、把内部材料
挡在仓库外。

## A. 本次已处理

| # | 类别 | 问题 | 处理 |
|---|---|---|---|
| 1 | 调试信息 | `dec:` / `decp:` 两个一次性诊断探针（`FlacProbe`、`FlacDepthProbe`，约 400 行）仍随包发布 | **删除**；结论写进 `docs/DEV-NOTES.md`（合成头、帧对齐、flush 陷阱） |
| 2 | 调试信息 | `SELFTEST` / `dl:` 钩子任何构建都能用，可被任意 App 触发（读任意路径、走 WebDAV 下载） | 全部 `BuildConfig.DEBUG` 门控；release 构建里直接忽略并记一条 warn |
| 3 | 调试信息 | 周期日志（解码吞吐每 2 s、供数每 10 s、扫库逐目录最多 30 行）会刷 logcat | 周期日志与扫库明细都只在 debug 构建输出 |
| 4 | 安全 | `PlaybackService` 原本 `exported="true"` | 主 Manifest 改 `false`；只给 **debug 构建**加 `app/src/debug/AndroidManifest.xml` overlay 放开（测试脚本的 `am start-foreground-service` 照旧可用） |
| 5 | 安全 | `allowBackup="true"` 会把 WebDAV 密码 / Qobuz token 随系统备份外带 | 改 `false` |
| 6 | 隐私 | `strings.xml` 的 WebDAV 占位提示写死了私人 NAS 地址 | 换成示例地址 |
| 7 | 隐私 | `scripts/build_libusb.sh` 写死个人 NDK / libusb 路径 | 改为 `NDK=`/`LIBUSB=` 环境变量（缺失即报错退出） |
| 8 | 仓库卫生 | 内部材料（`dsd-player-debug.md` 441 KB 的逐轮真机日志、`ui-review*.md`、`push_report.py` 内含私有共享盘地址） | 加入 `.gitignore`，不进仓库 |
| 9 | 缺陷 | **列表形态的专辑行渲染成"横贯屏幕的窄封面"**：`GroupAdapter` 在非网格形态复用网格布局，只把高度改成 52dp 而宽度还是 `match_parent` | 新增 `item_album_list.xml`（52dp 方形封面 + 右侧标题/副标题），按 `grid` 选择布局；模拟器已复核（截图 `78_lib_list_mode.png`） |
| 10 | 质量 | 三处"参数未使用"告警（`openFloat(hdr)`、`detectPcmBytes(firstLen)`、`openStreamFor(cfg)`） | 删参数 |
| 11 | 质量 | 源文件没有许可标识 | 全部 Kotlin/C++ 文件加 `SPDX-License-Identifier: GPL-3.0-or-later`（第三方 `cpp/alac/` 除外） |
| 12 | 元数据 | `versionCode=1/versionName=0.1`，`buildConfig` 未开启 | 升到 `1.0` / `versionCode=2`，开启 `buildConfig`（供 `BuildConfig.DEBUG` 使用） |
| 13 | 文档 | README 混着逐轮迭代记录、引用内部 debug 文档 | 重写（功能/亮点/截图/构建/合规/许可），内部细节下沉到 `docs/DEV-NOTES.md` |

## B. 已知但不阻塞开源（记在 README「已知限制」）

| # | 类别 | 说明 | 建议 |
|---|---|---|---|
| B1 | 架构 | `PlaybackService` 约 900 行，承担队列/开流/USB 状态/MediaSession/调试钩子 | 后续按"队列控制 / 开流 / 会话"拆成 3 个类 |
| B2 | 错误处理 | 全仓 149 处 `runCatching {}`，部分只吞不记（关流/释放路径为主） | 释放路径可接受；涉及播放的补日志 |
| B3 | 安全（中） | WebDAV 密码、Qobuz token 明文存 SharedPreferences | 换 `EncryptedSharedPreferences`（需引入 `androidx.security`） |
| B4 | 安全（低） | `usesCleartextTraffic="true"`（内网 WebDAV 用 http） | 可改为 `networkSecurityConfig` 白名单，但用户自定义主机难做静态白名单，暂保持并写入 README |
| B5 | 平台策略 | `MANAGE_EXTERNAL_STORAGE` 属"所有文件访问"，Google Play 需特批 | 侧载自用/开源发布为主；上架需改 SAF 方案（DSD 会变成"先复制到缓存"） |
| B6 | 兼容 | `MediaSession` 的 `FLAG_HANDLES_MEDIA_BUTTONS`/`FLAG_HANDLES_TRANSPORT_CONTROLS` 已废弃 | 清理需回归锁屏/蓝牙按键，留给后续 |
| B7 | 兼容 | `ProgressDialog`（下载进度）已废弃 | 换 `AlertDialog` + 进度条布局 |
| B8 | 性能 | 24bit 精确解码常驻 6 MB 预读 + 1 MB 解码输出缓冲 | 长曲可选 3 MB；低内存设备可自适应 |
| B9 | 功能 | 本地 `.mp3` 不支持；DST 压缩 DFF 不支持；WebDAV 里的 DSD 只能下载后播 | 见 README「已知限制」 |
| B10 | 构建 | release 未开 R8/minify，也没有 release 签名配置 | 发布 APK 前自行配 `signingConfig`；minify 需先补 `proguard-rules.pro` 并回归 |
| B11 | 测试 | 无单元测试；只有主机侧 `scripts/test_*` 与设备侧 `SELFTEST` | 至少给 `uac2_parse` / `FlacFrames` / `CodecPcm` 补 JVM 单测（纯逻辑，好测） |

## C. 值得留下的设计（审查确认无问题）

- **位深不猜**：`CodecPcm`（读两个 key，兜底 16bit）+ 数据自检（比例 ≈1.00），把"2 倍速外星人声"
  这类问题变成一行日志；
- **USB 帧长唯一来源**：`channels × subslot`，24-in-32 固定 8 B，各解码路径都归一到它；
- **DSD 两条路**：Native 失败自动回退 DoP，DoP 静音帧保锁定；
- **环形缓冲语义分离**：暂停=保留、seek=丢弃（`seek_flush`）、EOF=drain；
- **流式三段缓冲**：HTTP Range 块（1 MB×2，LRU 24 MB）→ 解码预读（6 MB）→ USB 环（2 MB），
  每层都有对应日志（`探测/吞吐/供数`）。

## D. 验证

```bash
./gradlew assembleDsdDebug assembleCompanionDebug assembleDsdRelease lintDsdDebug   # 全绿
```

- 模拟器（API 34，1080×2340）：SELFTEST 本地 24bit FLAC `737280 B ≈ 1920 ms`、
  AAC 自检比例 1.02、16bit WAV 3000 ms；WebDAV 曲库/流播列表/文件夹/设置/横屏截图复核
- debug 构建 `adb … SELFTEST` 仍可用（overlay 生效）；release 构建服务不导出、无调试入口
