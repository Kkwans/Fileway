# 栖卷 Windows 0.8.92

0.8.92 修复分段选择器切换后残留选中底色：存储工具、传输中心、文件任务共用的 `LayoutChoiceStyle` 把选中色与悬停色写在同一属性上，旧选项在取消选中后仍保留蓝底，悬停也会盖住选中色。本版将选中态改为独立填充层，仅由 `IsChecked` 控制显隐。

## 本版完成

- 根因：模板在 `Checked` 视觉状态中把 `Root.Background` 设为 `FwSelectionBrush`，`Unchecked` 没有恢复；切换后 UIA `ToggleState` 已是 Off，但旧项仍是蓝底。
- `LayoutChoiceStyle` 模板新增 `SelectionFill` 层（`FwSelectionBrush` + 同圆角），`Visibility` 通过新增的 `FwBoolToVisibilityConverter` 绑定 `IsChecked`；`CheckStates` 不再改写背景，悬停只影响底层背景，不再覆盖选中色。
- 覆盖存储工具的容量分析/重复文件/用户管理、传输中心的下载/上传、文件任务的服务器任务/操作记录与核实；同一模板也用于排序/类型/视图菜单和 `FwDisclosure` 开关头。
- 实拍对照：0.8.91 切换后旧标签仍为蓝底（`20261031-ui0891/screens/audit-0892/用户管理.png`、`重复文件.png`）；0.8.92 仅当前项有填充（`20261031-ui0892/screens/final-tabs2/storage-duplicates.png`、`storage-admin.png`、`transfer-uploads.png`、`tasks-history.png`）。
- UIA 复核：存储工具 0/1/2 与文件任务 5/6 的 `ToggleState` 始终单选 On/Off，视觉与状态一致。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.92.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui0892/dist/Fileway-0.8.92-win-x64-unsigned-setup.exe`，SHA-256 `e7fa4ca773dc6228dcd7fe781daa37270e0c4da0b2d0b421884ca49a184a2ff1`，与回执一致。

## 已知问题

- 菜单项键盘焦点形状仍为系统矩形（颜色已用应用双色）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 文件批次的批次卡实拍仍需真人选择文件夹；拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。
- 本版分段选择器实拍为浅色主题；深色下 `FwSelectionBrush` 走深色令牌，逻辑相同，未单独截图。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
