# 栖卷 Windows 0.8.65

0.8.65 把自研复选框接入统一键盘焦点视觉：勾选框与文字外包一层双色圆角环，不再出现系统方形焦点框。

## 本版完成

- CheckBox 样式关闭 `UseSystemFocusVisuals`，模板新增 `FocusStates` 与双色焦点环（画布色光晕 + 文本色内环，6px 圆角）；焦点环画在控件外侧，避免压住 20px 勾选框和文字。
- 覆盖连接页“保持登录”与用户权限、对话框等全部 CheckBox；勾选/半选视觉保持不变。
- 浅色与深色实拍：连接页“保持登录”聚焦状态均正常。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.65.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机截图：`screens/focus-checkbox.png`、`screens/focus-checkbox-crop.png`（浅色）、`screens/dark/focus-checkbox.png`、`screens/dark/focus-checkbox-crop.png`（深色）。
- 产物：`.../20261031-ui0865/dist/Fileway-0.8.65-win-x64-unsigned-setup.exe`，SHA-256 `c84a2e9f94f7f1fe4ca2a0ddc936d06cbdc418ed63fa05d53bf4dba58e67e148`，与回执一致。

## 已知问题

- Expander 仍使用系统默认焦点视觉（其模板由 WinUI 提供，未整体替换），待后续按需处理。
- 深色 ToolTip 仍随系统浅色外观显示；0.8.54 已验证 Flyout 宿主原型无效并回退，需重新定位实际显示层。
- 高对比度主题未走查（需调整系统设置，待用户同意）。
- 文件批次的批次卡实拍仍需真人选择文件夹；拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
