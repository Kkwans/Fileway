<div align="center">

# 栖卷 · Fileway

**把自己的文件、照片和影音，放在随时可用的私人空间。**

连接自己的服务器，在浏览器或 Android 手机上管理文件、欣赏媒体、整理资料。

[Android 1.0.0 正式版](https://github.com/Kkwans/Fileway/releases/tag/android-1.0.0-966bf38) · [下载 APK](https://github.com/Kkwans/Fileway/releases/download/android-1.0.0-966bf38/fileway-android-1.0.0.apk) · [部署服务端](#快速开始) · [English](README_EN.md)

</div>

## 看看栖卷

| Web · 文件浏览 | Web · 媒体预览 |
| --- | --- |
| ![Web 文件浏览](docs/images/web-files.png) | ![Web 媒体预览](docs/images/web-media.png) |

| Android · 文件浏览 | Android · 视频播放 |
| --- | --- |
| <img src="docs/images/android-files.png" alt="Android 文件浏览" width="300"> | <img src="docs/images/android-player.png" alt="Android 视频播放" width="300"> |

Android 截图展示 **1.1.0 候选界面**；当前可公开下载的正式版为 **1.0.0**。截图仅使用演示内容。

## 可以做什么

- **浏览与整理**：文件与目录管理、搜索、收藏、标签和最近记录，让常用内容更容易找到。
- **查看与播放**：图片浏览、音视频播放与常用文档预览，减少在不同工具之间切换。
- **原生 Android 播放**：Media3、FFmpeg 音频与 libass 字幕，支持音轨、字幕、倍速、续播和本机媒体缓存。
- **传输与任务**：集中处理下载、上传、后台任务及操作历史，查看实际进度、时间和结果。
- **空间与恢复**：回收站、存储分析和重复文件报告，帮助了解空间去向并谨慎清理。
- **自己的服务器**：Linux／NAS 与 Windows 服务端共用 Web 界面及 API，账户权限和访问范围由自己的实例管理。

Android 1.1 候选正在完善系统画中画、播放器反馈、图标动效与统一多选体验，尚未作为正式版公开发布。

## 快速开始

### 1. 部署服务端

选择适合自己的部署方式，按指南设置文件目录、持久化位置与账户：

- [Linux／NAS 容器部署](deploy/linux/README.md)
- [Windows 服务端部署](deploy/windows/README.md)

服务端已包含 Web 界面。部署完成后，用浏览器打开实例地址即可访问。

### 2. 连接 Android

[下载 Android 正式版 APK](https://github.com/Kkwans/Fileway/releases/download/android-1.0.0-966bf38/fileway-android-1.0.0.apk)，安装后填写自己的服务器地址并登录。

- Android **10 及以上**；安装包包含 **arm64-v8a、x86_64**。
- 覆盖安装沿用原包名和签名，保留账户、任务记录及本机设置；无需卸载旧版。
- 可直接连接服务器，或使用应用内置的 Tailscale 连接方式。
- 部分新操作需要配套服务器版本；最近记录批量删除需 `2026.10.25-v1` 或更新版本。

## 文档与开发

| 文档 | 内容 |
| --- | --- |
| [Android 客户端](clients/android/README.md) | 安装、原生媒体、构建与本机数据 |
| [服务端开发](server/README.md) | Go 后端、Web 开发和构建 |
| [项目架构](docs/server-architecture.md) | 平台边界、目录职责和共享 API |
| [共享客户端核心](clients/shared/core/README.md) | Android 使用的 Go 网络与传输模块 |
| [发布记录](https://github.com/Kkwans/Fileway/releases) | 已公开版本、安装包和校验文件 |

问题反馈请附版本、平台、复现步骤与必要日志；请勿提交密码、令牌、真实账户资料或私有媒体。

## 许可与致谢

各组件保留自己的许可证：服务端使用 [Apache-2.0](server/LICENSE)，Android 客户端使用 [GPL-3.0](clients/android/LICENSE)。第三方组件的许可证及来源随各组件保留，本仓库未统一更换其许可。

感谢 [File Browser](https://github.com/filebrowser/filebrowser)、AndroidX Media3、FFmpeg、libass 及其他开源项目。

Animated icons by [Lordicon.com](https://lordicon.com/)。静态补充图标来自 [Material Symbols](https://github.com/google/material-design-icons)。
