# 栖卷 Windows 0.8.78

0.8.78 让菜单键盘焦点使用应用的双色焦点色：MenuFlyoutItem、ToggleMenuFlyoutItem、MenuFlyoutSubItem、RadioMenuFlyoutItem 的 `FocusVisualPrimaryBrush/SecondaryBrush` 改为文字色/画布色，并内缩 2 DIP。

## 本版完成

- 菜单项保留 WinUI 模板与键盘/无障碍语义，只覆盖焦点视觉颜色与外边距；浅色下形成深色内环 + 浅色外环，深色下为浅色环 + 深色内线，与自研双色焦点环语言一致。
- 覆盖普通菜单项、开关菜单项、子菜单与单选菜单项；悬停/按下/禁用状态不受影响。
- 实拍浅色/深色视图菜单的键盘焦点；快捷键标签（F5）仍正常显示。
- 局限：焦点框形状仍由系统绘制为矩形（不是自研圆角环）；Expander 头部仍使用默认系统焦点框。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.78.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机截图：`20261031-ui0866/screens/dark/view-menu.png`（改前）、`20261031-ui0878/screens/dark/menu-focus.png`、`screens/light/menu-focus.png`（改后）。
- 产物：`.../20261031-ui0878/dist/Fileway-0.8.78-win-x64-unsigned-setup.exe`，SHA-256 `79273a69e8c41458ad3994591991efb4888ef0cc5c7a3f111f4b0c6cee4513ba`，与回执一致。

## 已知问题

- 菜单焦点框仍为系统矩形；要圆角需整体替换 MenuFlyoutItem/SubItem 模板，后续单独评估。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 文件批次的批次卡实拍仍需真人选择文件夹；拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
