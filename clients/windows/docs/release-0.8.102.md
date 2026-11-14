# 栖卷 Windows 0.8.102

0.8.102 记住导航侧栏的展开/图标栏状态：在宽窗口切换侧栏后写入 `ui-preferences.json` 的 `sidebarCollapsed`，重启保持；窄窗口的覆盖式侧栏不改变该偏好。

## 本版完成

- `UiPreferences` 增加 `sidebarCollapsed`，与 `theme`、`window`、`maximized` 共用同一个组合对象，保存任一项不覆盖其他项。
- `MainPage.BrowserShell` 初始化时读取该偏好；非覆盖模式下点击侧栏切换即保存；覆盖式（窄窗口）开合不写入。
- 实测：深色 `1500×1050@40,20` 数据根折叠侧栏 → prefs `sidebarCollapsed:true`；重启为 1500×1050 深色、图标栏状态保持；点击展开 → `sidebarCollapsed:false`，主题与窗口尺寸不变。
- 证据：`20261031-ui08102/screens/sidebar/collapsed-relaunch.png`、`expanded.png`、`probe-sidebar.log`、`data-side/ui-preferences.json`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.102.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui08102/dist/Fileway-0.8.102-win-x64-unsigned-setup.exe`，SHA-256 `1b6d0dffd00e5cc967742c342611ceb2ea24dadbdd0d9b010beae22cd7deca67`，与回执一致。

## 已知问题

- 菜单项键盘焦点形状仍为系统矩形（颜色已用应用双色）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
