# 栖卷 Windows 0.8.106

0.8.106 修复“打开方式”对话框在 1000×700 下被裁切的问题：说明缩短为一行、分类间距由 12 收紧为 8、滚动区高度接入 `DialogOrdering.ContentMaxHeight`；七个分类（Markdown / 文本与代码 / 图片 / 视频 / 音频 / PDF / 压缩包）现在完整显示，不再把“视频”一行切在控件中间。

## 本版完成

- 说明由两行长句改为“系统默认应用会先下载到默认目录，再交给 Windows 打开。”，保留关键行为说明，去掉示例。
- 左右分类列 `StackPanel.Spacing` 12→8；ScrollViewer 的最大高度从固定 520 改为按窗口高度自适应（预留 240 DIP），内容溢出时仍可滚动。
- 1000×700 实拍：七个分类完整、保存/取消按钮完整可见；对话框高度由约 730px 缩短到约 703px。
- 证据：修复前 `20261031-ui08105/screens/tool-dialogs/open-with.png`（视频行被裁）；修复后 `20261031-ui08106/screens/open-with2/open-with.png`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.106.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui08106/dist/Fileway-0.8.106-win-x64-unsigned-setup.exe`，SHA-256 `815b3aa500a1eeacfb3e37e37f1a8d2917437d30b8cdfefdb340da8a4ed0a89e`，与回执一致。

## 已知问题

- 菜单项键盘焦点形状仍为系统矩形（颜色已用应用双色）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
