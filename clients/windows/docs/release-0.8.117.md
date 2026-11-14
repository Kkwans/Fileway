# 栖卷 Windows 0.8.117

0.8.117 修正收藏与标签页的分组操作状态：当前筛选为“全部收藏”或标签模式时，“更多分组操作”按钮禁用，不再点开一个只有禁用项的分组菜单；选中收藏行后“打开/编辑/移除”正常启用，选中态保持蓝色底 + 3px 强调条。另完成宽窗口文件任务 340 DIP 详情栏实拍复核，未发现新的排版缺口。

## 本版完成

- 收藏与标签：`GroupMoreButton.IsEnabled` 跟随 `EditGroupButton/RemoveGroupButton` 的可用条件（`CanWrite && !TagMode && SelectedGroup is not null`）；无具体分组时按钮不可用。
- 选中态复核：选择“01 文本”收藏后行背景为半透明强调底 + 左侧 3px 强调条，“打开/编辑/移除”由灰变黑可用；顶部“更多收藏操作”保持可用（新建分组/标签）。
- 宽窗口复核：2560×1528 最大化下文件任务使用 340 DIP 右侧详情栏，列表与详情分隔线、标题、状态、操作按钮均完整，详情内容在窄栏内正常换行。
- 证据：收藏页 UIA `更多分组操作 enabled=True → False`；截图 `20261031-ui08117/screens/collections-selected.png`（修复前按钮可用）、`screens/after/collections-selected.png`（修复后）；宽任务详情 `screens/tasks-wide/tasks-detail.png`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.117.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui08117/dist/Fileway-0.8.117-win-x64-unsigned-setup.exe`，SHA-256 `944538cf30a3d51c76d4d32dd16c0916eed749990318240aa883641bdc0ee949`，与回执一致。

## 已知问题

- 收藏页“更多收藏操作”仍始终可用（新建分组/标签与选择无关）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
