# 栖卷 Windows 0.8.101

0.8.101 补齐窗口状态记忆：最大化时关闭或收起到托盘会在 `ui-preferences.json` 记录 `maximized=true` 并保留最近一次正常窗口尺寸；下次启动直接最大化，取消最大化回到原正常尺寸；取消最大化后关闭则记录 `maximized=false`。

## 本版完成

- `WindowState` 在窗口 bounds 之外增加 `maximized`：最大化关闭时只更新标记、不覆盖正常 bounds；正常状态关闭时同时写入 bounds 与 `maximized=false`。
- 启动恢复先按保存 bounds 定位窗口，再在 `OverlappedPresenter` 上调用 `Maximize()`；最大化失败时仍保留正常窗口。
- 实测：
  - prefs 为 `window=1500×1050@40,20, maximized=true` 启动 → 窗口最大化 `2560×1528@0,0`；取消最大化 → `1500×1050@40,20`；关闭 → `maximized:false`，bounds 不变。
  - 重启为正常 `1500×1050@40,20`；再最大化并关闭 → `maximized:true`，bounds 仍为 `1500×1050@40,20`。
  - 全流程 `theme:"Dark"` 保持。
- 证据：`20261031-ui08101/probe-max.log`、`data-max/ui-preferences.json`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.101.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui08101/dist/Fileway-0.8.101-win-x64-unsigned-setup.exe`，SHA-256 `c0198a2d65c4b70f97514c9ade57d09b6fbb7b2465b0930edf6132d10c799182`，与回执一致。

## 已知问题

- 菜单项键盘焦点形状仍为系统矩形（颜色已用应用双色）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
