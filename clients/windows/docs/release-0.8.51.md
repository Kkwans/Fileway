# 栖卷 Windows 0.8.51

0.8.51 把图标按钮与文字图标按钮的键盘焦点视觉统一为自研双色圆角环，补上 0.8.50 之后最后一类仍使用系统黑色焦点框的按钮。

## 本版完成

- `FwIconButtonStyle` / `FwTextIconButtonStyle`：`UseSystemFocusVisuals=False`，模板新增 FocusStates；Focused 时显示外层 2px `FwCanvasBrush` 光晕 + 内层 2px 前景色圆角环，与主/次按钮、列表项使用同一套焦点语言。
- 内层环使用 `{TemplateBinding Foreground}`，普通图标按钮跟随主文字色，`FwScrimIconButtonStyle` 在视频遮罩上跟随遮罩文字色，避免深色表面上焦点环不可见。
- 键盘焦点实测（浅色/深色各一组）：文件浏览工具栏“更多文件操作”与文件任务页“刷新任务”均显示圆角焦点环；鼠标悬停时焦点环不出现。
- 设计契约同步：`DESIGN.md` 删除“图标/文字图标按钮暂保留系统焦点视觉”，改为统一自研焦点环。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.51.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机截图：`screens/icon-button-focus-light-crop.png`、`screens/text-icon-button-focus-light-crop.png`、`screens/icon-button-focus-dark-crop.png`、`screens/text-icon-button-focus-dark-crop.png`；完整窗口图同目录。
- 产物：`.../20261031-ui0851/dist/Fileway-0.8.51-win-x64-unsigned-setup.exe`，SHA-256 `35e436b0…aa43f59`，与回执一致。

## 已知问题

- 0.8.51 焦点测试发现：深色主题下 ToolTip 弹层仍显示浅色资源（主窗口、菜单、对话框的深色正常）。已列入 0.8.52 修复，不计入本版通过范围。
- 高对比度主题未走查（需调整系统设置，待用户同意）。
- 文件夹批次的批次卡实拍仍需真人选择文件夹。
- 拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
