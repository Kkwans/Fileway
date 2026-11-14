# 栖卷 Windows 0.8.129

0.8.129 继续清扫主浏览文件操作对话框中的浅色主题画刷捕获：新建/重命名/批量重命名/复制移动的说明与字段标签在深色下曾使用浅色次级灰，几乎不可读；下载另存为的预览卡也捕获了浅色主题的卡片底色与描边。本版统一改用 `FwCaptionTextStyle` 与新增 `FwPreviewCardStyle`，由 ThemeResource 跟随主题。

## 本版完成

- `MainPage.FileCommands.cs`：新建/重命名提示、批量重命名说明、“粘贴到”标签与复制/移动说明改用 `FwCaptionTextStyle`，不再捕获 `FwTextSecondaryBrush`。
- `MainPage.BatchDownloads.cs`：批量下载确认框的说明、条目路径、失败项等 9 处次级文字统一使用 `FwCaptionTextStyle`。
- `MainPage.Downloads.cs`：下载另存为的远端文件/大小/保存到字段标签、提示与校验文字改用 `FwCaptionTextStyle`；预览卡改用新增 `FwPreviewCardStyle`（`FwSurfaceBrush` + 1px `FwStrokeBrush` + 8 圆角 + 14/12 内边距）。
- `Styles/Design.xaml` 新增 `FwPreviewCardStyle`，与 `FwFormCardStyle` 同属主题卡片族。
- 深色实测（`20261101-ui08129/screens/rename-dark.png`、`batch-rename-dark.png`）：单权重命名提示与批量重命名说明主色均为 (168,174,184)，此前为浅色主题 (83,90,100)；日志 `Probe-RenameDark.log`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.129.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261101-ui08129/dist/Fileway-0.8.129-win-x64-unsigned-setup.exe`，SHA-256 `df730c8d6989cb690182a02ef51be3661c9de4ad16eda0d66d060a24ca4465b9`，与回执一致。

## 已知问题

- 批量下载确认框与下载另存为预览卡本版只完成代码与样式替换；批量下载对话框在探针中未取到（下载按钮在选择多个文件时未启用），下载另存为预览卡未单独截图。
- 查看器/预览区仍有同类代码捕获画刷（`ViewerChrome`、`ImageWindow`、`PdfWindow`），需逐窗口确认是否固定暗色外壳后再改，避免误改设计意图。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
