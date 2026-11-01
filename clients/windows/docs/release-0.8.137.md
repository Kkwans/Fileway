# 栖卷 Windows 0.8.137

0.8.137 统一卡片圆角：`FwPreviewCardStyle`（下载确认/另存为预览卡）原为 8px，而 `FwFormCardStyle` 与按钮/磁贴为 10px，违反 DESIGN.md“同类控件必须使用同一圆角”。本版把预览卡改为 10px，并复拍确认卡。

## 本版完成

- `Styles/Design.xaml` `FwPreviewCardStyle.CornerRadius` 8 → 10。
- 深色实拍 `20261101-ui08137/screens-download/direct-download.png`：确认下载卡圆角与 10px 按钮一致，字段卡仍为 14px 内边距、1px 描边。日志 `download.log`（`DIALOG=True`）。
- 同时复核 1200×900 覆盖式侧栏下的传输中心与收藏页；截图中的标题遮挡确认是合成鼠标停留在侧栏按钮上产生的 ToolTip，不是布局缺陷，未改代码。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.137.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261101-ui08137/dist/Fileway-0.8.137-win-x64-unsigned-setup.exe`，SHA-256 `3b6aba5fa54367978eaa943318028c43246dfbe778132527b32067b5d1f8000d`，与回执一致。

## 已知问题

- 传输中心/收藏页在 1200×900 覆盖侧栏下的截图被 ToolTip 遮挡标题，需在无悬停状态下补拍后才能给出完整视觉结论。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
