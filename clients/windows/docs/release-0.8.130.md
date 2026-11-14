# 栖卷 Windows 0.8.130

0.8.130 修复 PDF 查看器在深色主题下停留浅色内容壳的问题：页面容器、页码、工具栏图标/文字与分隔线在窗口构造时捕获了应用级浅色画刷，`SetTheme(Dark)` 只切换了请求主题，已捕获的画刷不会更新。本版改用 ThemeResource 样式（`FwDocumentSurfaceStyle`、`FwPrimaryIconStyle`、`FwDividerStyle`、`FwBodyTextStyle`），深色下与主窗口一致。

## 本版完成

- `PdfWindow` 页面卡片改用新增 `FwDocumentSurfaceStyle`（`FwCanvasBrush` + 1px `FwStrokeBrush` + 12 圆角），深色下为深色底、浅色描边。
- 页码 `_pageLabel`、工具栏文字改用 `FwBodyTextStyle`（13px 局部覆盖），不再捕获 `FwTextPrimaryBrush`；工具栏图标改用新增 `FwPrimaryIconStyle`；分隔线改用 `FwDividerStyle`。
- `Styles/Design.xaml` 新增 `FwPrimaryIconStyle`（LucideIcon）、`FwDividerStyle`（Border）、`FwDocumentSurfaceStyle`（Border）。
- 深色实测：修复前 `20261101-ui08130/screens-before/before-window.png` 为白色内容卡 + 深色文字；修复后 `screens-after/after-window.png` 为深色内容壳、浅色页码与工具栏，PDF 页面本身保持白色。日志 `Probe-PdfDark-before.log`、`Probe-PdfDark-after.log`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.130.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261101-ui08130/dist/Fileway-0.8.130-win-x64-unsigned-setup.exe`，SHA-256 `54f9a7580f9a3dbc7961589fea0aea000fc28484cd817ee8fd060e2a015e5751`，与回执一致。

## 已知问题

- 本版只实测深色；浅色路径由同一 ThemeResource 保证，未单独截图。
- 图片查看器固定暗色外壳，`FwScrimTextBrush` 为固定白色，未在本版调整；`ViewerChrome` 其余捕获画刷待逐窗口确认设计意图。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
