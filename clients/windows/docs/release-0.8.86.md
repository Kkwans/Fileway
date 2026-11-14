# 栖卷 Windows 0.8.86

0.8.86 修正播放器 HUD 在最亮画面上的对比度：顶部标题/副标题与底部控件原先落在渐变接近透明的区域，白色文字直接压在彩条/高亮视频上。本版加强顶/底 scrim，并用同一彩条全屏帧前后对照。

## 本版完成

- 播放器顶部渐变由 `#99→#33(50%)→透明` 改为 `#CC→#66(55%)→透明`；底部由 `透明→#99` 改为 `透明→#73(45%)→#CC`。顶部窗口按钮、标题、副标题和底部进度/时间/音量/画质控件均落在有效暗化区域内。
- 用测试服务器真实短片 `h264-aac.mp4`（6 秒彩条）打开内置播放器，出现即暂停，分别截取 1680×945 普通窗口与 2560×1600 全屏；修复前后均保留：`screens/player-fullscreen.png`（前）与 `screens/after/player-fullscreen.png`（后）。
- 普通窗口黑底 HUD 复核无回归：`screens/after/player-normal.png`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.86.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实拍：`20261031-ui0886/screens/`（修复前全屏/普通）、`screens/after/`（修复后全屏/普通）。
- 产物：`.../20261031-ui0886/dist/Fileway-0.8.86-win-x64-unsigned-setup.exe`，SHA-256 `40a268e74f96765cf9f05a1bb53ed759da1169aef839c91d58c27fe7c51b12d7`，与回执一致。

## 已知问题

- 短片播放结束会自动关闭播放器（`PlaybackStatus.Stopped → BeginClose`），自动化截图需在播放期间暂停；不是本版引入。
- 菜单焦点仍为系统矩形（颜色已用应用双色），要圆角需整体替换 MenuFlyoutItem/SubItem 模板。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 文件批次的批次卡实拍仍需真人选择文件夹；拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
