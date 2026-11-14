# 栖卷 Windows 0.8.49

0.8.49 修正主按钮悬停/按下态：不再用半透明选中色替换实心强调色，白字对比度保持正常。

## 本版完成

- `FwPrimaryButtonStyle` 的 PointerOver / Pressed 从 `FwSelectionBrush`（半透明）改为 `AccentFillColorSecondaryBrush` / `AccentFillColorTertiaryBrush`，悬停仍是实心蓝、按下更深，白字不失去可读性。
- 实测：文件列表“移入回收站”确认框，浅色与深色下鼠标悬停主按钮均保持实心强调色、文字可读（`screens/primary-hover.png`、`screens/primary-hover-dark.png`）；随后点击取消，未移动任何文件，测试账号状态未变。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.49.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui0849/dist/Fileway-0.8.49-win-x64-unsigned-setup.exe`，SHA-256 `43d46885…ba09ef85d`，与回执一致。

## 未完成

- 主按钮键盘焦点仍显示系统黑色焦点环。
- 高对比度主题未走查（需调整系统设置，待用户同意）。
- 新提交文件夹批次的批次卡实拍仍需真人选择文件夹。
- 拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
