# 栖卷 Windows 0.8.99

0.8.99 连接欢迎页隐藏无效的侧栏切换按钮，并完成 1000×700 DIP 小窗口的浏览器、传输中心与连接页排版复核：路径栏、搜索框、四列网格与传输行均无溢出。

## 本版完成

- `SetConnectionView` 同步 `PaneButton.Visibility`：未连接时隐藏侧栏切换按钮，连接后恢复；欢迎页标题栏现在从“文件”菜单开始，不再出现一个无侧栏可折叠的无效图标。`Pane_Click` 对连接视图已有早退保护，不会因 Ctrl+B 改变侧栏状态。
- 1000×700 DIP（1500×1050 物理）实拍复核：主浏览路径栏/搜索框/筛选与四列网格完整；传输中心行在窄宽度下路径正确省略、状态与操作按钮不重叠；连接页品牌栏与 440 DIP 表单卡并排完整，未触发单栏断点。
- 证据：修复前 `20261031-ui0898/screens/narrow/narrow-connection.png`（左上角仍有侧栏按钮）；修复后 `20261031-ui0899/screens/narrow/narrow-connection.png`（按钮消失）；连接后 `20261031-ui0899/screens/narrow-connected/narrow-browser.png`、`narrow-sidebar.png`（按钮恢复并可折叠为图标栏）；窄窗口浏览器/传输/连接页对照见 `20261031-ui0898/screens/narrow/`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.99.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui0899/dist/Fileway-0.8.99-win-x64-unsigned-setup.exe`，SHA-256 `9f492439c8463a0e06a7e547d9aa7cdb2dfec70bedcb99da638344f5066b88a8`，与回执一致。

## 已知问题

- 菜单项键盘焦点形状仍为系统矩形（颜色已用应用双色）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复，真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
