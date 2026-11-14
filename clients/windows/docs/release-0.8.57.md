# 栖卷 Windows 0.8.57

0.8.57 把分段选择器/选项按钮（`LayoutChoiceStyle`）的系统焦点框改为自研双色圆角环，补齐最后一类常用交互控件的键盘焦点语言。

## 本版完成

- `LayoutChoiceStyle`：`UseSystemFocusVisuals=False`，新增 FocusStates；Focused 时显示外层 2px `FwCanvasBrush` 光晕 + 内层 2px 前景色圆角环。
- 适用范围：文件任务/操作记录分段标签、排序/类型/视图菜单选项、其它使用 `LayoutChoiceStyle` 的选项按钮。
- 浅色与深色键盘焦点已实拍；选中背景、悬停和禁用状态保持不变。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.57.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机截图：`screens/task-tab-focus.png`、`screens/task-tab-focus-dark.png`。
- 产物：`.../20261031-ui0857/dist/Fileway-0.8.57-win-x64-unsigned-setup.exe`，SHA-256 `d1e89f8b…8f94`，与回执一致。

## 已知问题

- 深色 ToolTip 仍未解决；0.8.54 的 Flyout 宿主原型已验证无效并回退，后续需要重新定位实际显示层。
- 高对比度主题未走查（需调整系统设置，待用户同意）。
- 文件夹批次的批次卡实拍仍需真人选择文件夹。
- 拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
