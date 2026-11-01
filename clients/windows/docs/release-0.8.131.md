# 栖卷 Windows 0.8.131

0.8.131 修复图片查看器错误卡在固定暗色外壳下不可读的问题：`ViewerChrome` 的错误标题与说明在构造时捕获了浅色主题的 `FwTextPrimaryBrush`/`FwTextSecondaryBrush`，深色下标题与卡片背景几乎同色（实测被背景色过滤，完全不可见）。本版改用 `FwBodyTextStyle` 与 `FwCaptionTextStyle`，由 ThemeResource 解析。

## 本版完成

- `ViewerChrome` 错误标题改用 `FwBodyTextStyle`（16px SemiBold 局部覆盖），错误说明改用 `FwCaptionTextStyle`（13px 局部覆盖），删除构造期画刷捕获。
- 深色实测：`broken.png` 打开失败时，修复前标题“图片打不开”不可见（`screens-before/before-window.png`），修复后为白色标题 + 次级灰说明（`screens-after/after-window.png`）；标题区域主色像素 255,255,255（修复前无任何非背景像素）。日志 `Probe-ImageError-before.log`、`Probe-ImageError-after.log`。
- 图片查看器仍保持固定暗色摄影外壳；页码/缩放标签使用固定白色 `FwScrimTextBrush`，不受本次改动影响。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.131.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261101-ui08131/dist/Fileway-0.8.131-win-x64-unsigned-setup.exe`，SHA-256 `a5d0e8567408e11f19e06c429c5a3ec1d90c39a9466e4f8a6d9946a1bdd14a89`，与回执一致。

## 已知问题

- 仅实测深色错误态；浅色图片查看器仍为固定暗色外壳，未单独截图。
- `ViewerChrome` 背景画刷仍由 `ImageWindow` 显式覆盖为固定深色；如后续复用到跟随主题的查看器，需要一并主题化。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
