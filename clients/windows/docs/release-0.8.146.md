# 栖卷 Windows 0.8.146

0.8.146 收口长期遗留的“菜单焦点是系统矩形”：实测发现无需整体替换 WinUI 菜单模板，只要在 `MenuFlyoutItem`/`ToggleMenuFlyoutItem`/`MenuFlyoutSubItem`/`RadioMenuFlyoutItem` 的样式上设置 `CornerRadius=10`，系统高可见焦点框就会跟随菜单项的圆角绘制。本版加入该 setter，并用键盘焦点实拍确认。

## 本版完成

- `Styles/Design.xaml` 四个菜单项样式新增 `CornerRadius=10`，保留原有应用双色焦点画刷与 `FocusVisualMargin=2`。
- 深色实拍 `20261101-ui08146/screens/menu-focus.png`：键盘下移到“关闭窗口后保留在托盘”，焦点框为与应用一致的圆角描边，不再是系统直角矩形；UIA 复核该菜单项 `HasKeyboardFocus=True`。
- 未替换模板，菜单项的内容、勾选、快捷键、禁用态与弹出行为保持不变。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.146.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261101-ui08146/dist/Fileway-0.8.146-win-x64-unsigned-setup.exe`，SHA-256 `f3390084b0bee53c11eb8ac3a48a5acb74d6219a2fa68223076b4d04a0e5d55d`，与回执一致。

## 已知问题

- 深色键盘回归补齐：主菜单普通/切换项、`界面主题` 子菜单、`浅色/深色` 单选项焦点框均为圆角（`20261101-ui08147/screens/submenu-focus.png`、`submenu-open.png`）。
- 菜单弹出背景仍按系统 Acrylic 方案；高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
