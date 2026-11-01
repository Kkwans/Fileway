# 栖卷 Windows 0.8.151

0.8.151 继续高对比度复选框收口：未选中状态原为 1px 白色描边，在 HC 下弱于选中态。新增 `FwCheckBoxBorderThickness` 主题资源（常规 1px、HC 2px），未选中方框更明确；选中仍为 0.8.150 的白底黑勾。

## 本版完成

- `FwCheckBoxBorderThickness`：Default/Light 为 1，HighContrast 为 2；CheckBox `Box` 的 BorderThickness 改用该资源。
- HC 实拍：选中态 `20261101-ui08153/screens/connection-hc.png`（白底黑勾），取消勾选后 `screens-unchecked/connection-hc.png`（2px 方框可见）。HC 从 127 恢复到 126。
- 常规主题仍为 1px，无视觉变化。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.151.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261101-ui08153/dist/Fileway-0.8.151-win-x64-unsigned-setup.exe`，SHA-256 `6b588efd1c83a00c8c6b5fbde8e99151f54979b9ddb9e958ce67ae83bf4488de`，与回执一致。

## 已知问题

- HC 未选中框为白色 2px 描边，仍弱于选中白底；属可接受差异。
- 文本/图片/压缩包查看器仍未单独 HC 截图；PDF 查看器已通过。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
