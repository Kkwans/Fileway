# 栖卷 Windows 0.8.150

0.8.150 高对比度连接页收口：复选框选中态原用 `AccentFillColorDefaultBrush` 填充 + 反白勾，HC 下强调填充不可见，只剩一个白色勾号、缺少 20px 方框边界。新增复选框主题资源，HC 选中为白底 + 黑色勾，未选中仍为白色描边；常规主题保持强调色底 + 白勾。

## 本版完成

- 新增 `FwCheckBoxCheckedBrush`/`FwCheckBoxGlyphBrush`：常规映射 Accent/TextOnAccent，HC 映射 `SystemColorWindowTextColorBrush`/`SystemColorWindowColorBrush`。
- CheckBox 模板 Checked/Indeterminate 背景与 Check/Indeterminate 勾选图形改用新资源。
- HC 实拍 `20261101-ui08152/screens3/connection-hc.png`：保持登录为白底黑勾、20px 圆角方框；此前 `screens2/connection-hc.png` 只见勾号。HC 从 127 恢复到 126。
- 主按钮继续使用 0.8.149 的 HC 白底黑字。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.150.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261101-ui08152/dist/Fileway-0.8.150-win-x64-unsigned-setup.exe`，SHA-256 `0db3241f9f8c1405d9cee6323fdc179262173700a5d0cf8b003976b98de5ac1d`，与回执一致。

## 已知问题

- HC 未选中复选框为 1px 白色描边，弱于选中态；后续可评估加粗。
- 文本/图片/压缩包查看器仍未单独 HC 截图；PDF 查看器已通过。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
