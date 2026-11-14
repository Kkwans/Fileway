# 栖卷 Windows 0.8.149

0.8.149 高对比度连接页走查发现主按钮缺陷：`FwPrimaryButtonStyle` 在 HC 下使用系统 Accent/TextOnAccent，实测“连接并浏览”退化为纯文字、没有按钮底色或边框。本版新增主按钮主题资源，HC 使用系统窗口文本色填充 + 窗口色前景（白底黑字/黑底白字自动反转），常规主题仍为强调色 + 反白字。

## 本版完成

- 新增 `FwPrimaryButtonBackgroundBrush`/`FwPrimaryButtonHoverBrush`/`FwPrimaryButtonPressedBrush`/`FwPrimaryButtonForegroundBrush`：常规主题映射 Accent/TextOnAccent，HC 映射 `SystemColorWindowTextColorBrush`/`SystemColorWindowColorBrush`。
- `FwPrimaryButtonStyle` 及其 PointerOver/Pressed 状态改用新资源。
- HC 实拍 `20261101-ui08152/screens2/connection-hc.png`：连接页主按钮为白底黑字、可见按钮轮廓；修复前 `screens/connection-hc.png` 为纯文字。HC 从 127 恢复到 126。
- 常规主题的资源值与原 Accent/TextOnAccent 完全等价，未改视觉。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.149.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261101-ui08152/dist/Fileway-0.8.149-win-x64-unsigned-setup.exe`，SHA-256 `caa74d094493a9fb34288e179e4a43a737a0a8bcee1096f8f1f66de71ad08d24`，与回执一致。

## 已知问题

- HC 连接页“保持登录”复选框只显示勾号、无方框描边；本版未改 CheckBox 模板 HC 外观。
- 文本/图片/压缩包查看器仍未单独 HC 截图；PDF 查看器已通过。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
