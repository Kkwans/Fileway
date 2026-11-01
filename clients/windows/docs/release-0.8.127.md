# 栖卷 Windows 0.8.127

0.8.127 修复深色下侧栏“管理”按钮几乎不可见的问题：按钮在构造时通过 `Application.Current.Resources` 捕获了浅色主题的次级画刷，深色下仍是深灰色。改为使用 `TextBlock` + `FwCaptionTextStyle`（ThemeResource 次级色）作为按钮内容，深浅色都能正确解析。

## 本版完成

- `SidebarCollectionsControl.Section` 的“管理”按钮不再在初始化器中设置捕获的 `Foreground`；内容改为 `TextBlock { Text="管理", Style=FwCaptionTextStyle }`，由样式的 `ThemeResource FwTextSecondaryBrush` 跟随主题。
- 深色实拍：`20261031-ui08127/screens/dark-final/browser-none.png`，侧栏“管理”为可读的次级灰；此前 `screens/browser-none.png` 中几乎与背景同色。浅色下仍为次级灰。
- 尝试 `SetResourceReference` 时 WinUI 3 无该方法（构建 CS1061），已改回样式方案并重新构建成功。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.127.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui08127/dist/Fileway-0.8.127-win-x64-unsigned-setup.exe`，SHA-256 `3d46043a63cd1746607849ef20609216c129da69351de26fcac5e25f7da7c870`，与回执一致。

## 已知问题

- 代码创建的控件如需主题色，必须使用基于 ThemeResource 的样式；不能直接捕获 `Application.Current.Resources` 画刷（本类问题再次出现）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
