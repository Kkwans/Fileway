# 栖卷 Windows 0.8.136

0.8.136 收敛批量下载确认框的信息密度：当所有条目保存到同一目录时，原先每个文件都重复两行完整本地路径（5 项约占 10 行），现在只在顶部显示一次“保存到”，条目行仅保留“名称 · 大小”。目录不一致（如保留相对结构的目录下载）时自动回退为逐条路径。

## 本版完成

- `MainPage.BatchDownloads`：预检后按 `DestinationDisplayPath` 的目录分组，同目录时在说明下方显示 12px“保存到”标签 + 13px 可选中路径，列表行仅显示名称与大小；不同目录保持逐条路径。
- 同步实拍“下载另存为”预览卡（0.8.129 遗留未验证项）：`20261101-ui08136/screens-after/saveas-preview.png`，字段卡与 0.8.135 的确认下载位置一致。
- 批量下载前后：`20261101-ui08136/screens/batch-download-dialog.png`（每条重复路径，约 10 行）→ `screens-after/batch-download-dialog.png`（单次保存路径 + 5 行条目，弹窗高度明显缩短）。日志 `rightmenu.log`、`rightmenu2.log`（`SAVEAS_DIALOG=True`、`PREVIEW_CARD=True`、`BATCH_DIALOG=True`）。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.136.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261101-ui08136/dist/Fileway-0.8.136-win-x64-unsigned-setup.exe`，SHA-256 `1401e2a9f7ed11b4bedfe19b049d284de5d3e672fa97c00e0c4f9d52b449f14d`，与回执一致。

## 已知问题

- 混合目录/相对结构下载未单独构造夹具；代码保留逐条路径回退。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
