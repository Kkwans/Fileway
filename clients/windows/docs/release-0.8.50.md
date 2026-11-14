# 栖卷 Windows 0.8.50

0.8.50 把主/次按钮的键盘焦点视觉改为自研双色圆角环，去掉对话框里的黑色系统方框。

## 本版完成

- `FwPrimaryButtonStyle` / `FwSecondaryButtonStyle`：`UseSystemFocusVisuals=False`，模板新增 FocusStates，Focused 时显示外层 2px `FwCanvasBrush` 光晕 + 内层 2px `FwTextPrimaryBrush` 圆角环（与列表项同一套语言）。
- 键盘 Tab 聚焦实测：删除下载任务确认框中“取消”按钮显示自研圆角焦点环，Escape 关闭后未删除任务；鼠标悬停主按钮仍是实心强调色。
- 图标/文字图标按钮仍保留系统焦点视觉（体积小、样式差异不明显），后续如需可统一。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.50.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机截图：`screens/dialog-tab-focus.png`（Tab 聚焦“取消”）、`screens/dialog-primary-focus.png`（再按 Tab 聚焦主按钮）；另回归连接页，确认主按钮/输入区布局未受全局按钮模板改动影响：`screens/connection.png`。
- 产物：`.../20261031-ui0850/dist/Fileway-0.8.50-win-x64-unsigned-setup.exe`，SHA-256 `71aec4a0…73c10baf`，与回执一致。

## 未完成

- 高对比度主题未走查（需调整系统设置，待用户同意）。
- 新提交文件夹批次的批次卡实拍仍需真人选择文件夹。
- 拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
