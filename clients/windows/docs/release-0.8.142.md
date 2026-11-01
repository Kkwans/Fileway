# 栖卷 Windows 0.8.142

0.8.142 补齐存储工具页的返回入口：搜索、文件任务、回收站、传输中心、最近访问均有“返回文件”，存储工具页此前只能经侧栏或 Ctrl+B 离开，在覆盖式侧栏关闭（<960 DIP）时尤其难发现。现在页头右侧提供同款 `FwSecondaryButtonStyle` 返回按钮，并接回 `ReturnFromRemoteTools`。

## 本版完成

- `StorageAnalysisControl` 页头由 StackPanel 改为两列 Grid：标题/副标题 + “返回文件”次按钮（36 DIP、圆角 10），新增内部 `CloseRequested` 事件；读屏名“离开存储工具，返回文件”。
- `MainPage.RemoteTools.StorageTools_Click` 订阅 `CloseRequested` → `ReturnFromRemoteTools()`，与搜索/任务/回收站一致。
- 1500×1050 深色实拍 `20261101-ui08142/screens/storage.png`：返回按钮与标题基线对齐，右侧与内容卡片同缘；日志 `audit.log`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.142.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261101-ui08142/dist/Fileway-0.8.142-win-x64-unsigned-setup.exe`，SHA-256 `db3e6f652cc21d8e305fb232ecd708248284b34e28a215ab6be3d73f8e96987f`，与回执一致。

## 已知问题

- 未单独点击返回按钮做导航回归；按钮事件与其它远程页同路径（`ReturnFromRemoteTools`），已在代码层对齐。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
