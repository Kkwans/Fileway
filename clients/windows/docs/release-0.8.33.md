# 栖卷 Windows 0.8.33

0.8.33 把“文件任务”页的工具栏从原生 CommandBar 换成自研按钮，清掉最后一个 Segoe 字形。

## 本版完成

- 文件任务工具栏：刷新任务/刷新详情/核实记录/请求取消/重试改为 `FwTextIconButtonStyle` 的图标+文字按钮；分隔线改为 1px `FwStrokeBrush` 竖线。
- “归档记录 / 移出归档”从 CommandBar 溢出菜单移入自研“更多”按钮（`dots-three`）的 MenuFlyout；归档图标由 Segoe `E7B8` 改为 Lucide `clipboard`，不再混用字体图标。
- 历史模式下“更多”按钮随任务操作一起隐藏；刷新任务的文案仍按“刷新任务/刷新记录”切换。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.33.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机截图：`screens/nas-tasks.png`（新工具栏、更多菜单入口、任务列表渲染正常）。
- 产物：`.../20261030-ui0833/dist/Fileway-0.8.33-win-x64-unsigned-setup.exe`，SHA-256 `b6b51a17…cfc7d3535`，与回执一致。

## 未完成

- “更多”菜单的展开态未截图；其菜单外观沿用全局 MenuFlyout 亚克力样式。
- 高对比度主题未走查（需调整系统设置，待用户同意）。
- 批次卡仍无用真实文件夹上传产生的截图，需真人上传后查看上传页。
- 拖到文件夹移动 / 拖出到资源管理器：合成鼠标无法触发 WinUI 拖拽，需真人验收。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
