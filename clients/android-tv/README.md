# 栖卷 TV

独立 Android TV / 横屏投影仪客户端。最低 Android 9（API28），发布 ABI 为
`armeabi-v7a` 与 `arm64-v8a`，与手机应用使用不同包名和私有数据。

## 构建

使用手机端相同的锁定 JDK17、SDK37、NDK28.2、CMake3.22.1 和 Gradle wrapper。
在已有 x64 Android 构建机上运行，工具与缓存显式配置到所属开发目录：

```sh
python3 ../shared/android/build-native.py --ndk "$ANDROID_NDK_HOME" --api 28 --mode direct --abis armeabi-v7a,arm64-v8a
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

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

## 验证边界

构建、模拟器交互和物理设备播放分别记录。API28 安装不证明硬件解码、HDR、
声音或长时间稳定性。哈趣 Q1 Max 没有 ADB 时使用侧载候选及设备信息页，
缺失的系统级指标必须保留未验证状态。
