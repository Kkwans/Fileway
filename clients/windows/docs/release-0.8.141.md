# 栖卷 Windows 0.8.141

0.8.141 把 0.8.140 的完成态规则同步到上传详情：`UploadDetailsView` 与下载详情同一结构，完成态同样会显示一条满格蓝色进度线。现在 `UploadStatus.Completed` 时折叠 6px 轨道，只保留服务器确认量与说明；未完成/暂停/失败等状态继续显示进度。

## 本版完成

- `MainPage.Uploads` 上传详情进度轨道命名 `ProgressTrack`，`RenderUploadDetails` 在 `UploadStatus.Completed` 时 `Visibility.Collapsed`。
- 真实上传记录验证：用 0.8.97 已通过的真人上传探针上传 `回归-上传详情.txt`（29 B，服务器确认 29/29 B），再打开传输中心上传页选中该记录；实拍 `20261101-ui08141/screens/upload-detail.png` 中完成状态卡无蓝线，仅“上传已完成 / 服务器已确认 29 B / 29 B”。日志 `seed.log`（`UPLOAD_STARTED`）、`upload2.log`（`UPLOAD_ROWS=1`、`UPLOAD_DETAIL=True`）。
- 上传页本身 `upload-page.png` 同批复核，单条记录无溢出。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.141.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261101-ui08141/dist/Fileway-0.8.141-win-x64-unsigned-setup.exe`，SHA-256 `d569dffc822da64f28df9520f7557f10d4d833868347bbb4798d0a26dfafe403`，与回执一致。

## 已知问题

- 仅实测完成态；上传中/暂停/失败的上传详情进度轨道未单独截图。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
