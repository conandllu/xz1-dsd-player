# 第三方组件与许可

本项目整体以 **GNU General Public License v3.0**（见 `LICENSE`）发布。之所以选 GPL-3.0 而不是
MIT/Apache-2.0：应用**静态链接**了 LGPL-2.1 的 libusb，GPL-3.0 与之兼容且义务清晰；如果你希望
换更宽松的许可，需要先把 libusb 改成动态链接并自行评估。

| 组件 | 版本/来源 | 许可 | 用途 | 说明 |
|---|---|---|---|---|
| libusb | 1.0.28（静态库，`app/src/main/cpp/libs/<abi>/libusb-1.0.a`，由 `scripts/build_libusb.sh` 生成） | LGPL-2.1-or-later | 用户态 USB 等时传输（UAC2 独占输出） | 静态链接 ⇒ 整个应用按 GPL-3.0 发布；libusb 源码与其许可见 <https://github.com/libusb/libusb> |
| Apple ALACDecoder | `app/src/main/cpp/alac/`（Apache-2.0，见该目录 `APPLE_LICENSE.txt`） | Apache-2.0 | 平台无 `audio/alac` 解码器时解 ALAC/m4a | 仅用于用户自己拥有的无 DRM ALAC 文件 |
| AndroidX / Material Components | Google Maven | Apache-2.0 | UI 基础设施 | |
| Coil | 2.6.0 | Apache-2.0 | 封面加载（内存/磁盘缓存、降采样） | |
| OkHttp / Okio | 由 Coil 引入 | Apache-2.0 | WebDAV（PROPFIND 等自定义方法）与 Range 流式读取 | |
| Kotlin / kotlinx.coroutines | JetBrains | Apache-2.0 | 语言与协程 | |
| Noto Sans/Serif CJK（仅文档截图排版） | Google | OFL-1.1 | 生成示例封面 | 不随 APK 分发 |

## 不包含的东西

- **不含任何 Apple Music / FairPlay 相关内容**：Apple Music 无损流是 FairPlay 加密的，第三方无法取得
  PCM；本项目只能播放用户自己拥有的本地 ALAC 文件（iTunes 购买的 iTunes Plus 或自己抓轨）。
- **不含任何 Qobuz 的 app_id / app_secret / 账号凭据**：Qobuz 支持是运行时从 Qobuz 自己的 web 播放器
  公开页面提取参数、由用户登录自己的账号后使用，凭据只存在本机 SharedPreferences 里。
- **不内置、不收集任何用户数据**：没有统计、没有崩溃上报、没有账号体系。

## 商标

Sony / Walkman、XD05（iFi）、Apple / iTunes / Apple Music、Qobuz 等名称与商标归各自所有者。
本项目与这些公司无任何隶属或背书关系。
