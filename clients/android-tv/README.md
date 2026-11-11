# 栖卷 TV

独立 Android TV / 横屏投影仪客户端。最低 Android 9（API28），发布 ABI 为
`armeabi-v7a` 与 `arm64-v8a`，与手机应用使用不同包名和私有数据。

[下载 TV 0.3.0](https://github.com/Kkwans/Fileway/releases/tag/android-tv-0.3.0) · [配套手机端 1.2.2](https://github.com/Kkwans/Fileway/releases/tag/android-1.2.2)

0.3.0 使用原签名 release/R8 APK，保留 API28 FFmpeg 音频扩展，交付了首批浏览、
设置和配对反馈调整。完整 UI/UX 重构及媒体问题仍在推进；Q1 声音/HDR、复杂字幕、
长播放与实际验证范围以发布包附带的兼容说明为准。

## 构建

主线包含后续功能修复和 UI/UX 重构；debug 构建带 `-dev` 后缀，不等于公开包。
当前公开下载为上面的 0.3.0。字幕实现契约见[字幕选路与恢复](docs/subtitles.md)。
视频预缓存与退出清理契约见[共享媒体核心](../shared/android/media-core/README.md)。

使用手机端相同的锁定 JDK17、SDK37、NDK28.2、CMake3.22.1 和 Gradle wrapper。
在已有 x64 Android 构建机上运行，工具与缓存显式配置到所属开发目录：

```sh
python3 ../shared/android/build-native.py --ndk "$ANDROID_NDK_HOME" --api 28 --mode direct --abis armeabi-v7a,arm64-v8a
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest -PfilewayTvFfmpegManifest=/absolute/path/to/tv-audio.json
```

音频扩展的构建与检查见[API28 音频扩展](docs/audio-extension.md)。普通候选需要
经过验证的 API28 音频输入；系统解码器布局诊断包须显式传入
`-PfilewayTvAllowSystemAudioOnly=true`，release 不接受该豁免。

模拟器可通过 `-PfilewayTvAbis=x86_64` 配置，并构建匹配 ABI 的原生核心。
直连构建会检查 Go 依赖闭包，拒绝包含 Tailscale 的产物。无 JWT 的撤销式本地
媒体租约、原始 wirePath、账号隔离和服务端权限规则与手机端一致。

签名通过 `-PfilewayTvSigningFile=/absolute/private/tv-signing.properties` 注入。
该私有文件包含 `storeFile`（绝对路径）、`storePassword`、`keyAlias`、`keyPassword`，
必须位于仓库之外。TV 使用独立且长期保留的签名身份，不能复用或替换手机签名。
构建候选时传入 `-PfilewaySourceRevision=<40字符已推送SHA>`。

安装预览可以使用独立签名的 debug APK，必须明确标记其可调试、未压缩的构建属性。
这种预览不包含 FFmpeg 音频扩展时，音频支持取决于系统解码器；它不代表完整发布门槛
通过。正式 release 构建要求核验 API28 音频扩展或从已锁定源码构建它。

手机和 TV 的矢量图标统一维护在 `clients/shared/android/design-foundation`。
手机资源别名保留原调用名称，TV 只调整尺寸与遥控焦点反馈。

## 手机遥控

TV 0.2.0 与手机端 1.2.2 引入局域网配对和独立设备登录。
在现有手机应用侧栏打开「连接 TV」，扫码配对后可发送手机当前账号授权、
操作导航/输入和媒体控制；TV 不依赖手机持续在线。
配对与撤销入口位于 TV 设置。首次未配对时也可从登录页连接手机。
操作及安全边界见[手机遥控说明](../android/docs/tv-control.md)。

快照中的 `fileDetailsKey: 1` 表示 `app.key` 支持 `key: "details"`，用于查看当前
焦点文件的详情；手机仅在该能力存在时为确认键启用长按。`detailsHoldMillis` 是
物理 TV 遥控器的长按阈值（500–2000ms）。`recentAccessPage: 1` 表示支持导航到
`RECENT_ACCESS`；该列表与手机一致排除视频，且不展示无法验证原始路径的记录。
旧 TV 没有这些能力声明时，手机保留原有确认键行为。

## 验证边界

构建、模拟器交互和物理设备播放分别记录。API28 安装不证明硬件解码、HDR、
声音或长时间稳定性。哈趣 Q1 Max 没有 ADB 时使用侧载候选及设备信息页，
缺失的系统级指标必须保留未验证状态。

## 播放器界面与源码复用

视频仍由共享 Media3 核心、稳定 SurfaceView 和独立字幕层播放。TV HUD 沿用手机
横屏播放器的时间／进度／时间、播放控制和媒体选项结构；进度条绘制共用
`design-foundation/MediaProgressTrack.kt`，各端保留自己的遥控、触摸与焦点输入。
TV 媒体菜单使用一个窗口承载轨道、外观、同步、字体、倍速和队列层级，使用静态
半透明表面，不调用手机端的跨窗口实时模糊。

播放控制的横向分组框架适配自 [JetStream VideoPlayerMainFrame](https://github.com/android/tv-samples/blob/15ac8df7df91a07450751a5fe8c6fd17a6a4297f/JetStreamCompose/jetstream/src/main/java/com/google/jetstream/presentation/screens/videoPlayer/components/VideoPlayerMainFrame.kt)，
Copyright 2024 Google LLC，Apache License 2.0；时间轴位于操作行上方，与手机端一致。电视播放状态、输入和字幕仍使用栖卷现有核心。

`TvPlayerChoiceList` 的选择列表／焦点结构适配自 [BV RadioMenuList](https://github.com/aaa1115910/bv/blob/0e454c06e954b00df7a0a5a6873c9f18a7f9d0f5/player/tv/src/main/kotlin/dev/aaa1115910/bv/player/tv/controller/playermenu/component/RadioMenuList.kt)，
Copyright (c) 2022 aaa1115910，MIT；许可随 APK 保留在 `licenses/bv-MIT.txt`。
Fileway 的数据绑定、行布局、资源标识、字幕策略和播放内核独立维护，不包含 BV
的 B站服务接口、账号或品牌素材。

TV 界面使用随包提供的 Noto Sans SC 静态 Regular／Medium 字体，固定字形避免
设备主题替换与可变字体轴设置差异。它不替换片源字幕字体，也不改变手机应用的
系统字体选择。来源为 [Noto CJK Sans 2.004](https://github.com/notofonts/noto-cjk/tree/Sans2.004/Sans/SubsetOTF/SC)，文件未经修改：

| 资源 | 上游 Git blob | SHA-256 |
|---|---|---|
| NotoSansSC-Medium.otf | `fafdfebf3103db4dc81d33ebd55b6786e08c9ebe` | `7633f5a016d4dd95e685a69633d818aabc4644c4b08e26bd35b1b30c45ed5dda` |
| NotoSansSC-Regular.otf | `fc0fda9394367c16c1af7555a2ae4d5e2e6a6f02` | `faa6c9df652116dde789d351359f3d7e5d2285a2b2a1f04a2d7244df706d5ea9` |

SIL OFL 1.1 原文及版权声明随 APK 保留在 `licenses/noto-sans-sc-OFL.txt`。字体选型不等于实际投影清晰度已验收，仍须在目标设备确认。
