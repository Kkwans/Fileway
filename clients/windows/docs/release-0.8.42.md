# 栖卷 Windows 0.8.42

0.8.42 修复界面主题选择在重启后丢失的问题。

## 本版完成

- 新增数据根内的 `ui-preferences.json`（仅含 `theme`），由 `UiPreferences` 读写；不写注册表、不写全局 AppData，测试数据根与真实数据根互不影响。
- 启动时在创建主窗口前读取偏好，`MainPage` 以保存的主题初始化，`Loaded` 后应用；通过“视图→界面主题”切换后立即保存。
- 偏好文件缺失或解析失败时回退“跟随系统”，不影响启动。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.42.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机验证：同一数据根内切到“深色”后写入 `{"theme":"Dark"}`，关闭并重启，主界面保持深色；截图 `screens/theme-set-dark.png`、`screens/theme-restart.png`；重启后在深色下完成 44 条搜索结果与滚动条复核（`screens/dark-search.png`）。
- 产物：`.../20261031-ui0842/dist/Fileway-0.8.42-win-x64-unsigned-setup.exe`，SHA-256 `15c7683a…52a3b093`，与回执一致。

## 未完成

- 高对比度主题未走查（需调整系统设置，待用户同意）。
- 批次卡仍无用真实文件夹上传产生的截图，需真人上传后查看上传页。
- 拖到文件夹移动 / 拖出到资源管理器：合成鼠标无法触发 WinUI 拖拽，需真人验收。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
