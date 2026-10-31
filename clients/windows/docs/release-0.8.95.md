# 栖卷 Windows 0.8.95

0.8.95 统一传输中心行内进度条的视觉：此前等待中的任务只有约 2px 的低对比度轨道，完成的任务则是一条 5px 强调色长线，看起来像文件名下划线；现在批次头与任务行都使用 6 DIP 圆角轨道，未开始时也能看到明确的空进度，完成时强调色进度嵌在轨道内。

## 本版完成

- 批次头与文件任务行的 `ProgressBar` 外包 6 DIP、3 DIP 圆角的 `FwStrokeBrush` 轨道；进度控件背景设为透明、指示条 4 DIP 并居中，不再直接压一条强调色细线在文件名下方。
- 等待状态（0%）显示完整 6 DIP 空轨道；完成状态显示强调色进度条，轨道上下各留约 1 DIP，完成/等待两种状态形状一致。
- 像素复核：等待轨道浅色 `#EBEBEB`、物理高度 9px（=6 DIP）；完成行在强调色上下仍可见轨道，浅/深色均实拍。
- 证据：修复前 `20261031-ui0894/screens/before-0895-light/transfer-downloads.png`、`before-0895-dark/transfer-downloads.png`；修复后 `20261031-ui0895/screens/after-light/transfer-downloads.png`、`after-dark/transfer-downloads.png`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.95.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui0895/dist/Fileway-0.8.95-win-x64-unsigned-setup.exe`，SHA-256 `e6cf82ec95b74234dae5862e7f3ada584e6538fc18b030941d710b192c1e12ea`，与回执一致。

## 已知问题

- 本版只验证确定性进度；不确定进度的动画未单独截图。
- 菜单项键盘焦点形状仍为系统矩形（颜色已用应用双色）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 文件批次的批次卡实拍仍需真人选择文件夹；拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
