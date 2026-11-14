# 栖卷 Windows 0.8.122

0.8.122 压缩缓存与存储对话框的“当前生效”摘要：缓存目录与下载目录由两行换行改为单行省略 + Tooltip，摘要高度减少约 40 DIP，容量说明在首屏完整显示，深色主题下同样生效。缓存 / 下载目录的可编辑字段仍提供完整路径。

## 本版完成

- `StorageSettingsControl.AddActiveRow`：值文本 `MaxLines=1` + `TextTrimming=CharacterEllipsis` + 完整路径 Tooltip；容量/自动继续等短值不受影响。
- 深色首屏：缓存目录、下载目录各占一行（y=287/357），容量说明两行完整显示（y=713–761），不再只露出 4px；编辑区“缓存根目录 / 磁盘缓存总容量”完整。
- 证据：修复前 `20261031-ui08121/screens/cache-dark/collections.png`（路径两行、说明裁切）；修复后 `20261031-ui08122/screens/cache-dark-after/collections.png`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.122.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui08122/dist/Fileway-0.8.122-win-x64-unsigned-setup.exe`，SHA-256 `77ac65bd322af938941e058608c10e9270ac4639b3452f634abfb5cec17874ab`，与回执一致。

## 已知问题

- Windows 11 的滚动条仍按系统自动隐藏策略显示，首屏未常驻可见滚动条；本次通过压缩摘要降低裁切影响。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
