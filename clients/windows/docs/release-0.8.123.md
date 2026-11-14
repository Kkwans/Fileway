# 栖卷 Windows 0.8.123

0.8.123 给侧栏“收藏夹”分组补上“管理”入口：12px 次级文字按钮，带应用焦点环与“管理收藏与标签”读屏名称，点击直接打开收藏与标签页，不再只能从“工具”菜单进入。

## 本版完成

- `SidebarCollectionsControl` 新增 `ManageRequested` 事件；`Section("收藏夹", …, manage: true)` 在标题右侧渲染 24 DIP 高的“管理”按钮（`FwTextIconButtonStyle` 模板、次级文字色、8px 横向内边距、右 8px 边距）。
- `MainPage` 订阅 `ManageRequested` 并复用 `Collections_Click`，打开管理视图并刷新。
- 实拍：侧栏入口 `20261031-ui08123/screens/browser-none.png`；点击后 `screens/collections-from-sidebar.png` 显示“收藏与标签”页（下拉筛选、收藏行、打开/编辑/移除）。
- 修复过程中修正 `Thickness(8,0)` 参数错误，构建现已 0 错误。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.123.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui08123/dist/Fileway-0.8.123-win-x64-unsigned-setup.exe`，SHA-256 `17f04da846dc1c9b515fa2a650f9e4b75fe934d4eee8b163d39b751a9fd46aa2`，与回执一致。

## 已知问题

- “管理”入口仅放在“收藏夹”分组；标签分组沿用同一页面，未重复放置按钮。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
