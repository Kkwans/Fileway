# 栖卷 Windows 0.8.134

0.8.134 把底部“切换连接”按钮纳入 0.8.133 的侧栏 ToolTip 规则：展开/覆盖式侧栏时标签可见，不再弹同名提示；折叠成 72px 图标栏时保留提示，并放到图标右侧。同时修复会话变化后提示文案（连接服务器/切换连接）与折叠状态不同步的问题。

## 本版完成

- `MainPage.AppMenus`：不再无条件设置连接按钮 ToolTip；按 `!overlay && collapsed` 决定是否设置，文案跟随 `SidebarConnectionActionLabel.Text`，折叠态放置右侧。
- `MainPage.BrowserShell.UpdateSidebarLayout`：连接按钮加入动态 ToolTip 列表，折叠/展开切换时同步。
- 深色实拍：展开态 `20261101-ui08134/screens/expanded-connection.png` 仅悬停底色、无浮层；折叠态 `rail-connection.png` 图标右侧显示“切换连接”。日志 `tips.log`（两态计数均为 1，截图为准）。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.134.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261101-ui08134/dist/Fileway-0.8.134-win-x64-unsigned-setup.exe`，SHA-256 `c29bed425ee0ffd9494ce693e000639f1b041c2544dbdec41e5bb9a49ae4a1b0`，与回执一致。

## 已知问题

- 仅实测深色；浅色由同一状态逻辑保证。
- 侧栏“管理收藏与标签”按钮的提示仍始终启用（标签可见），但文本“管理”有歧义，保留提示可接受。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
