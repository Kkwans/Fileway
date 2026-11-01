# 栖卷 Windows 0.8.133

0.8.133 收敛侧栏重复 ToolTip：展开侧栏时导航标签始终可见，却仍会在悬停约 1 秒后弹出同名浮层，遮挡顶部菜单或相邻导航行（浅色实拍中遮住“文件”菜单）。现在只有折叠成 72px 图标栏、标签隐藏时才启用 ToolTip，并固定放到图标右侧。

## 本版完成

- `MainPage.BrowserShell.UpdateSidebarLayout`：我的文件/最近访问/传输中心/文件任务/存储工具/回收站六个导航按钮按 `rail` 状态动态设置 `ToolTipService.ToolTip`；展开或覆盖式侧栏时置空。
- 折叠态 ToolTip 使用 `PlacementMode.Right`，避免提示压住图标栏左侧边缘；展开态恢复默认放置。
- 深色实拍：展开态 `20261101-ui08133/screens-tips2/expanded-final.png` 无重复浮层；折叠态 `rail-final.png` 图标右侧显示“存储工具”提示。验证日志 `tips.log`、`tips2.log`（`EXPANDED_TIPS`/`RAIL_TIPS` 计数随文本副本变化，截图为准）。
- 浅色审计证据：修复前 `20261101-ui08133/audit-main-light/browser-dark.png` 中 ToolTip 遮住菜单栏；同时复核最近访问/文件任务/存储工具/回收站，四列网格 UIA 等宽（262–263px），未发现其他排版回归。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.133.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261101-ui08133/dist/Fileway-0.8.133-win-x64-unsigned-setup.exe`，SHA-256 `c7b9778a3c3dd434b4f85354e77db38e63bac6e549b7db6fe85c0f4ef28bcac8`，与回执一致。

## 已知问题

- 连接按钮（底部“切换连接”）仍由 `MainPage.AppMenus` 始终设置 ToolTip，展开态悬停仍可能出现同名提示；本版未改，后续统一。
- 批量重命名/批量下载对话框的 Windows 11 滚动条仍自动隐藏；`ScrollBarVisibility.Visible` 实测无效，已回退，不宣称修复。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
