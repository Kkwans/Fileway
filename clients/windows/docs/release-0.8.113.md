# 栖卷 Windows 0.8.113

0.8.113 收敛等待/冲突传输行的指标噪声：等待接收、等待网络或同名冲突的下载不再显示“0 KB/s · 剩余 — · 已用 131:…”这类误导信息，只保留阶段与路径；同时把文件详情的“打开”和文件任务的“更多任务操作”统一到 36 DIP。深色主题下的选择器焦点环、网格与详情浮窗已实拍复核。

## 本版完成

- 传输中心：`RefreshClock` 只为正在下载、已暂停、已完成、失败或取消的任务生成速率/剩余/耗时指标；等待网络、等待登录、同名冲突等状态折叠指标行。旧数据中一条等待中的记录不再显示 131 小时“已用”。
- 文件详情：`DetailsOpenButton` 34→36 DIP，与 `FwPrimaryButtonStyle` 及其他对话框主按钮一致。
- 文件任务：`更多任务操作` 34→36，与工具栏其他 36 DIP 图标按钮对齐。
- 深色复核：主浏览四列网格、布局选择器双色焦点环（白内环 + 画布色光晕）、文件详情浮窗深色样式均正常；详情“打开”按钮在深色下为系统强调底 + 黑色前景，对比度正常。
- 证据：修复前 `20261031-ui08111/screens/final/transfer.png`、`20261031-ui08113/screens/details/window-1.png`；修复后 `20261031-ui08113/screens/transfer-after/transfer.png`、`screens/details-after/window-1.png`；深色 `screens/browser-dark.png`、`selector-focus-dark.png`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.113.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui08113/dist/Fileway-0.8.113-win-x64-unsigned-setup.exe`，SHA-256 `ed4fadcc546a566870ee69858da4e857aa81b300afb0a8445cc61b98a1f66c45`，与回执一致。

## 已知问题

- 等待/冲突行仍保留空进度轨道，用于表达“尚未接收”；不再附加速率与耗时。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
