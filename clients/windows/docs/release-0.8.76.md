# 栖卷 Windows 0.8.76

0.8.76 给高内容弹窗加入自适应高度：`DialogOrdering.ContentMaxHeight` 按窗口高度扣除标题/按钮/边距预留，把“交互与外观”“键盘快捷键”的内容限制在 240–520/440 DIP，1200×900 下不再顶边裁切。

## 本版完成

- 新增 `DialogOrdering.ContentMaxHeight(anchor, preferred)`：以窗口实际高度减去 240 DIP 预留，限制在 240 DIP 与首选高度之间。
- 「交互与外观」内容 MaxHeight 由固定 520 改为自适应；900px 高窗口下约 360 DIP，对话框上下留出约 80px 边距，设置项在内部滚动。
- 「键盘快捷键」内容由固定 440 改为自适应；900px 高窗口下先显示浏览与导航组及部分文件与选择组，其余滚动查看。
- 关闭按钮仍固定在对话框底部；0.8.61 的初始聚焦关闭按钮行为不变。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.76.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机截图：`screens/presentation.png`、`screens/shortcuts.png`（固定高度，顶边被裁）、`screens/after2/presentation.png`、`screens/after2/shortcuts.png`（自适应高度）。
- 产物：`.../20261031-ui0876/dist/Fileway-0.8.76-win-x64-unsigned-setup.exe`，SHA-256 `ed963cf077010e05199cfb7ed7f4888df8585b0390b149b8ecdfd77368a3bb41`，与回执一致。

## 已知问题

- 打开方式/关于弹窗本轮自动化菜单点击未成功打开，未作为本版证据；其上次验收为 0.8.60/0.8.59。
- 菜单项与 Expander 头部的键盘焦点仍使用 WinUI 系统矩形焦点视觉（内部模板，暂列系统语义例外）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 文件批次的批次卡实拍仍需真人选择文件夹；拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
