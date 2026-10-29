# API28 音频扩展

TV 使用共享 NativePlayer / Media3，系统解码器优先，FFmpeg 补充系统不支持的音频。
扩展不会替换视频解码器，不依靠关闭 HDR 或烧录字幕取得声音。

## 构建输入

与手机 API29 原生产物分别构建，不能把手机 ARM64 音频库直接当作 TV 双 ARM 库。
固定输入见 `clients/shared/android/native/media3-ffmpeg-tv.lock.json`：NDK28.2、
Media3 1.11.1、FFmpeg9.0.2、Android API28。构建覆盖 ARM32/ARM64 与测试模拟器
ABI，使用静态 FFmpeg、16KiB ELF 对齐和独立 JNI 符号。

已有 Linux x64 构建机上执行：

```sh
python3 ../shared/android/build-tv-audio.py --work-root "$TV_AUDIO_WORK" --ndk "$ANDROID_NDK_HOME"
./gradlew :tv-audio:assembleRelease :tv-audio:lintRelease -PfilewayTvFfmpegWorkDir="$TV_AUDIO_WORK"
python3 ../shared/android/package-tv-audio.py --work-root "$TV_AUDIO_WORK" --ndk "$ANDROID_NDK_HOME" \
  --aar ../shared/android/audio-codecs/build/outputs/aar/tv-audio-release.aar --output-dir "$TV_AUDIO_OUTPUT"
./gradlew :app:assembleDebug -PfilewayTvFfmpegManifest="$TV_AUDIO_OUTPUT/tv-audio.json"
```

工作与输出变量必须是已分配的绝对目录。使用与 Android 构建匹配的主机和项目
锁定工具链；`ANDROID_HOME`、`ANDROID_SDK_ROOT` 同时存在时必须指向同一 SDK。
根目录 Android TV workflow 也生成经过检查的 `tv-audio-<源码SHA>` artifact，
可将其中 AAR、manifest 与源码包一起交给 Windows 等其他构建机消费。

应用验证 manifest 对应的锁、构建与打包配方、AAR 校验值及 API/Media3 版本。
打包验证 ARM32/ARM64/模拟器的 ELF 类别、机器类型、16KiB 对齐、JNI 导出和 ABI 闭包。
发布时保留 manifest、对应源码包和第三方许可，不能仅分发一个来源不明的 `.so`。

## 明确的构建模式

正常 debug 候选也必须包含已验证的音频扩展；release 另外要求明确源码 SHA。
仅检查布局或系统解码器时，可显式使用
`-PfilewayTvAllowSystemAudioOnly=true` 生成不带扩展的 debug 诊断包。
该开关不放宽 release 门禁，不能代表完整音频支持。

设置的「设备与播放信息」分别报告未包含、原生加载失败或已加载 FFmpeg 的实际状态。
包含依赖与运行加载成功是两个不同事实，具体片源还需观察实际音频解码器及播放错误。

## 受影响验证

`TvAudioExtensionTest` 使用已经核验的自有多音轨样本，经 TV 实际 NativePlayer
执行 TrueHD/AAC/FLAC/Opus 选择、暂停中 seek/倍速调整与恢复，检查 JNI、实际解码器、
播放时钟和输出缓冲计数。TrueHD 必须走 FFmpeg。该检查不读取用户密码、不清账号、
不写服务器媒体；临时样本在结束后删除。

输出缓冲被音频 sink 接收不证明扬声器有声、环绕声直通或 Q1/HDR 现场效果。
实机听觉和输出设备验证仍需单独记录，不能用构建、库加载或模拟器结果替代。
