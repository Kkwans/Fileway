# 栖卷 Windows 0.8.93

0.8.93 重排存储工具三个子页的说明与表单：容量分析、重复文件、用户管理统一收进应用卡片，说明文字限宽并降为 13px 次级色，输入框与主操作在卡内对齐；浅色与深色均已实拍。

## 本版完成

- 新增应用级 `FwFormCardStyle`（`FwSurfaceBrush` + 1px `FwStrokeBrush` + 10px 圆角 + 16px 内边距）；卡片最大 720 DIP、左侧对齐，主浏览内容区不再让说明文字横跨整窗（此前单行约 950 DIP）。
- 容量分析/重复文件：说明 + 路径输入框 + 主按钮进入同一卡片，输入框在卡内横向填充；分析结果、进度与提示仍保留在卡外，不压缩结果区宽度。
- 用户管理：说明与五个操作按钮进入卡片，用户列表保留在卡外；按钮组仍可横向滚动。
- 修复实现过程中发现的深色回退：初版在构造函数通过 `Application.Current.Resources` 取主题画刷，深色下得到浅色表面（白卡 + 白字输入框不可见）；改为 XAML 样式的 `ThemeResource` 后浅/深色均正常。该教训已写入 `DESIGN.md`。
- 同时补验 0.8.92 分段选择器深色：存储工具、传输中心、文件任务选中填充均正确。
- 证据：修复前 `20261031-ui0892/screens/final-tabs2/storage-duplicates.png`；修复后浅色 `20261031-ui0893/screens/final2-light/storage-duplicates.png`、`storage-admin.png`，深色 `screens/final2-dark/storage-duplicates.png`、`storage-admin.png`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.93.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui0893/dist/Fileway-0.8.93-win-x64-unsigned-setup.exe`，SHA-256 `b1e27de69b4cb213e16856b1fd683e29595c3c3f0d77e34ee8d5e938b0b83a89`，与回执一致。

## 已知问题

- 菜单项键盘焦点形状仍为系统矩形（颜色已用应用双色）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 文件批次的批次卡实拍仍需真人选择文件夹；拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
