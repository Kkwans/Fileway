# 栖卷 Windows 0.8.66

0.8.66 解决长期存在的“深色主题下 ToolTip 仍是浅色”问题。根因是 WinUI 把 ToolTip 放在独立 popup 中，`ThemeResource` 按系统主题解析，不随应用根元素的 `RequestedTheme` 变化；此前强制 `RequestedTheme`、替换隐式样式、自定义模板与 Flyout 宿主均无效。

## 本版完成

- ToolTip 的背景、描边、文字改为引用应用自有的可变 `SolidColorBrush`（`FwToolTipBackgroundBrush` / `FwToolTipBorderBrush` / `FwToolTipForegroundBrush`），通过 `StaticResource` 注入，完全绕过 popup 的主题资源解析。
- `MainWindow.Appearance` 在应用主题变化时（启动、应用内切换、标题栏主题变化）同步更新这三种画刷颜色；浅色为白底深字，深色为深底浅字，与既有 8px 圆角、1px 描边、10×6 内边距、12px 字号保持一致。
- 高对比度模式下改用系统背景色/前景色，避免退化为固定浅色或深色。
- 实测三种路径：浅色启动（浅色 ToolTip）、深色启动（深色 ToolTip）、应用内“视图→界面主题→深色”切换后立即聚焦刷新按钮（深色 ToolTip 即时生效）。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.66.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机截图：`screens/light/tooltip.png`、`screens/dark/tooltip.png`、`screens/switch/switched-tooltip.png`。
- 产物：`.../20261031-ui0866/dist/Fileway-0.8.66-win-x64-unsigned-setup.exe`，SHA-256 `73f78caa4c61ef94362b8f445d78f9debda96aa19791548b1ccaaeac4b9a84c1`，与回执一致。

## 已知问题

- Expander 仍使用系统默认焦点视觉（其模板由 WinUI 提供，未整体替换）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）；ToolTip 已做系统色兜底。
- 文件批次的批次卡实拍仍需真人选择文件夹；拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
