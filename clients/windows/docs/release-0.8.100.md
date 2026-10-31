# 栖卷 Windows 0.8.100

0.8.100 记住主窗口大小与位置：正常（非最大化）窗口在关闭或收起到托盘时写入 `ui-preferences.json`，下次启动恢复并限制在当前显示器工作区；原有主题偏好保持兼容。

## 本版完成

- `UiPreferences` 改为“主题 + 窗口 bounds”的同一对象，使用 camelCase、大小写不敏感读写，兼容旧文件 `{"theme":"Dark"}`；保存窗口不再覆盖主题，保存主题也不再丢失窗口。
- 启动时读取分屏/窗口位置：宽高 ≥ 800×600 才采用；按保存点所在显示器 `WorkArea` 裁剪，越界坐标钳制回可见区；读取或恢复异常回退默认 1120×760 居中。
- 保存时机：`Chrome_CloseRequested`（包括关闭到托盘）与 `MainWindow_Closed`；仅在 `OverlappedPresenterState.Restored` 时保存，最大化状态不会覆盖正常尺寸。
- 实测：空数据根调整为 1500×1050@40,20 → 关闭到托盘 → 重启恢复为 1500×1050@40,20，prefs 为 `{"theme":null,"window":{"x":40,"y":20,"width":1500,"height":1050}}`；深色数据根保存后 `theme` 仍为 `Dark`，恢复尺寸一致且界面保持深色；越界坐标 50000,50000 启动后被钳制到 1060,478，尺寸保持 1500×1050。
- 证据：`20261031-ui08100/screens/bounds-theme2/restored.png`、`screens/bounds3/`、`screens/bounds-theme3/`、`screens/offscreen/`；`probe-bounds*.log`、`probe-offscreen.log`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.100.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui08100/dist/Fileway-0.8.100-win-x64-unsigned-setup.exe`，SHA-256 `a3edb05ca53394eca2a6bc6abee91ecca021bb14e343b40f60037710bf2a46be`，与回执一致。

## 已知问题

- 最大化状态未持久化：最大化关闭时保留上一次正常窗口尺寸，不会在下次启动还原最大化。
- 菜单项键盘焦点形状仍为系统矩形（颜色已用应用双色）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
