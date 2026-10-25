# 栖卷 · Fileway Android

[简体中文](README.md) · [English](README_EN.md) · [项目首页](../../README.md)

原生 Android 文件与媒体客户端，连接自己的 Fileway 服务端。支持 Android **10+**、**arm64-v8a／x86_64**。

## 安装与更新

[下载 1.1.0 正式版 APK](https://github.com/Kkwans/Fileway/releases/download/android-1.1.0/fileway-android-1.1.0.apk) · [发布说明](docs/release-1.1.0.md) · [全部版本](https://github.com/Kkwans/Fileway/releases)

当前正式版为 **1.1.0**。覆盖安装保留原包名、签名和本机数据；不要通过卸载或清空数据解决普通升级问题。

## 界面预览

| 文件浏览 | 视频播放器 · 1.1 |
| --- | --- |
| <img src="../../docs/images/android-files.png" alt="文件浏览" width="300"> | <img src="../../docs/images/android-player.png" alt="视频播放器" width="300"> |

## 日常使用

- 连接自己的 NAS／Linux 或 Windows 服务端，支持直接连接与内置 Tailscale。
- 文件、最近、收藏和标签：单击查看详情、长按多选，可打开的对象双击打开。
- 图片、音视频与常用文档预览；本机缓存、收藏、标签和续播记录。
- 下载、上传、后台任务及操作历史集中管理；回收站与存储工具提供恢复和分析入口。
- 视频支持音轨选择、内嵌及外挂字幕、字体与偏移调整、自定义倍速、目录播放列表。

1.1 统一播放器玻璃反馈、封面氛围背景、图标动效和多选，并提供默认手动开启的系统画中画。

## 原生媒体与数据

播放采用 **Media3 + FFmpeg 音频 + libass 字幕**，网络与传输复用 [共享 Go 核心](../shared/core/README.md)。具体格式与 HDR 输出受设备、解码器和源文件影响。

- [字幕格式与边界](docs/subtitle-formats.md)
- [下载目录与恢复](docs/download-storage.md) · [上传](docs/uploads.md)
- [本机数据](docs/local-state.md) · [应用更新](docs/app-updates.md)
- [原生引擎验证](docs/native-engine-validation.md) · [历史版本记录](docs/release-history.md)

最近记录批量删除需服务端 `2026.10.25-v1` 或更新版本。账户、权限与服务器设置仍由各实例管理。

## 从源码构建

使用项目锁定的 JDK17、Android SDK37、NDK28.2、CMake3.22.1及 Go 工具链。先按 [FFmpeg 原生构建配方](docs/ffmpeg-audio-probe.md) 准备匹配的 AAR 与 `ffmpeg-probe.json`，再构建传输核心及应用：

```sh
cd clients/android
export ORG_GRADLE_PROJECT_filewayFfmpegManifest=/absolute/path/to/ffmpeg-probe.json
bash scripts/build-native.sh
./gradlew :app:assembleDebug
```

Windows 使用对应的 Gradle Wrapper 和已配置的原生工具链。不要把签名身份、账户凭据、数据库或私有媒体放入源码目录。

正式候选由明确源码提交构建，使用原签名并附带验证记录；代码构建、界面验收和硬件媒体能力分别记录。

## 图标与许可

图标来源统一为 Lordicon System Outline 和 Material Symbols Rounded。正式候选包含已取得使用许可的官方动画导出；公开源码构建保留同语义静态补充。详情见 [图标许可](licenses/lordicon.md) 与 [Material Symbols 来源](licenses/material-symbols.md)。

客户端采用 [GPL-3.0](LICENSE)。第三方许可随应用保留。

- [Media3／FFmpeg 源码包](https://github.com/Kkwans/Fileway/releases/download/android-preview-0.6.1-c3fe361/fileway-media3-ffmpeg-sources.tar.gz)
- [libass 源码包](https://github.com/Kkwans/Fileway/releases/download/android-preview-0.6.1-c3fe361/fileway-libass-0.5.1-sources.tar.gz)

Animated icons by [Lordicon.com](https://lordicon.com/)。
