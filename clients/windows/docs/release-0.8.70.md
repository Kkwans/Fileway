# 栖卷 Windows 0.8.70

0.8.70 修复传输中心上传记录的空白行：已完成的上传没有速度/剩余时间指标，但详情模板仍为指标行预留一整行，导致“服务器已确认字节”和远端路径之间出现约 75px 的断层。

## 本版完成

- `TransferCenterRow` 新增 `MetricsVisibility`，指标为空时折叠指标行；下载记录有速度/耗时指标时仍正常显示。
- 列表项模板的指标 TextBlock 绑定可见性；指标变化时同时通知 `Metrics` 与 `MetricsVisibility`。
- `AccessibleName` 改为过滤空片段后拼接，避免读屏文本出现连续逗号。
- 1200×900 下用真实历史上传/下载记录复核：上传行字节、路径恢复为紧凑相邻，下载行指标与路径关系不变。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.70.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机截图：`screens/uploads.png`（修复前）、`screens/after/uploads.png`（修复后）、`screens/after/downloads.png`；数据来自真实历史记录 `20261025-regression080/data/m02-nokeep`。
- 产物：`.../20261031-ui0870/dist/Fileway-0.8.70-win-x64-unsigned-setup.exe`，SHA-256 `4bd3b3b6677696161556c60c28d8ebec029c27b06cc06a165edf9d9b6758292f`，与回执一致。

## 已知问题

- 菜单项与 Expander 头部的键盘焦点仍使用 WinUI 系统矩形焦点视觉（内部模板，暂列系统语义例外）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 文件批次的批次卡实拍仍需真人选择文件夹；拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
