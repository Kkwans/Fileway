# 栖卷 Windows 0.8.132

0.8.132 修正 0.8.130 引入的 PDF 工具栏排版回归：页码与“适应窗口/100%”套用 `FwBodyTextStyle` 时继承了 22px 行高，13px 文字行高被撑大到 33 物理像素（修复前 26）。新增紧凑 `FwToolbarTextStyle`（13px、18px 行高、主题主色、垂直居中），颜色仍随主题解析。

## 本版完成

- `Styles/Design.xaml` 新增 `FwToolbarTextStyle`。
- `PdfWindow` 页码与工具栏文字改用该样式，去掉 `FontSize=13` 局部覆盖与 `FwBodyTextStyle`。
- 深色实测（`20261101-ui08132/screens/toolbar-window.png`）：页码/按钮文字高 27 物理像素、基线 y=794，与 0.8.129 的 26/794 一致；修复前 0.8.130 为 33/791。文字仍为浅色、图标正常。日志 `Probe-PdfDark.log`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.132.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261101-ui08132/dist/Fileway-0.8.132-win-x64-unsigned-setup.exe`，SHA-256 `077ae6da8188729b8f001ec6fd89275cdafc3e626dae50e0475f637582774370`，与回执一致。

## 已知问题

- 仅实测深色 PDF 工具栏；浅色路径由同一 ThemeResource 保证。
- 正文 `FwBodyTextStyle` 自带 22px 行高，紧凑工具栏/列表不要直接复用它，需要局部行高或专用样式。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
