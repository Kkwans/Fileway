# 栖卷 Windows 0.8.126

0.8.126 让空状态图标与当前页签一致：传输中心下载空状态用下载箭头、上传空状态用上传箭头；文件任务空状态用文件任务图标，操作记录空状态用剪贴板图标。此前两类空状态分别固定为刷新箭头与文件图标，与内容不对应。

## 本版完成

- 传输中心 `EmptyIcon`：`ChangeDirection` 时按 `Download/Upload` 切换 `download-simple / upload-simple`；空状态标题与说明沿用 0.8.124 的页签文案。
- 文件任务 `EmptyIcon`：`Render` 时按 `_historyMode` 切换 `file-code / clipboard`，与“服务器任务 / 操作记录与核实”页签一致。
- 实拍：上传空状态 `20261031-ui08126/screens/transfer-after/transfer-upload.png`（上传箭头 + “暂无上传记录”）；操作记录空状态 `screens/operations-after/operations.png`（剪贴板 + “暂无任务操作记录”）。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.126.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui08126/dist/Fileway-0.8.126-win-x64-unsigned-setup.exe`，SHA-256 `d2903f7788e68a79bc8d5c5f6e869836105fc08ef48fa96fee9255e9c6541c69`，与回执一致。

## 已知问题

- 下载空状态图标沿用同一映射，未单独截图（下载页有历史记录）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
