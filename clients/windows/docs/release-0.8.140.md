# 栖卷 Windows 0.8.140

0.8.140 修复传输详情状态卡在“下载已完成”时仍显示满格蓝色进度线的问题：该线在完成态没有信息量，读起来更像分隔线，也与 0.8.124 起列表/批次完成态隐藏轨道的行为不一致。现在完成态隐藏整条 6px 轨道，只保留已接收/磁盘确认与说明文字；未完成、失败、取消仍显示进度。

## 本版完成

- `MainPage.Downloads` 下载详情的进度轨道命名 `ProgressTrack`，`RenderDownloadDetails` 在 `DownloadStatus.Completed` 时 `Visibility.Collapsed`。
- 1200×900 深色实拍 `20261101-ui08140/screens-after/transfer-clean.png`（完成态状态卡无蓝线）与修复前 `screens/transfer-clean.png`（满格蓝线）对照；日志 `audit.log`、`audit2.log`。
- 传输中心页面本身在 1200×900 覆盖侧栏下不再被 ToolTip 遮挡，页面标题/筛选/记录列表完整。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.140.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261101-ui08140/dist/Fileway-0.8.140-win-x64-unsigned-setup.exe`，SHA-256 `8862130a3a2c93836931f6ffa34b4592a908c8cbe6a6513f256e2a1d66f327b7`，与回执一致。

## 已知问题

- 未完成/失败/取消状态的进度轨道未在本版单独截图。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
