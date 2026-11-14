# 栖卷 Windows 0.8.115

0.8.115 修复缓存与存储对话框的字段标题问题：`NumberBox` 自带 Header 在 150% DPI 下与输入框顶边重叠，“磁盘缓存总容量（GB）”出现被裁切的残影；改为 12px 次级外置标签。缓存根目录、默认下载目录同步改为外置标签，三个可编辑字段的标题层级一致。

## 本版完成

- 磁盘缓存容量：移除 `NumberBox.Header`，使用 `<TextBlock FontSize="12" 次级色>` + 输入框；标题与输入框不再重叠，实测裁剪图干净。
- 缓存根目录 / 默认下载目录：移除 `TextBox.Header`，改为同一套 12px 次级标签 + 36 DIP 输入框 + “选择…”次按钮；三个字段的标签字号、颜色与间距一致。
- 真实弹窗：工具 → 缓存与存储…，1600×1000、150% DPI 实拍；修复前 `20261031-ui08115/screens/collections.png`、`screens/cache-capacity-crop.png`，修复后 `screens/after/cache-capacity-crop.png`、`screens/final/collections.png`。
- 弹窗仍为可滚动布局；首个视口显示“当前生效”摘要与两个编辑字段，说明与缓存占用需向下滚动。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.115.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui08115/dist/Fileway-0.8.115-win-x64-unsigned-setup.exe`，SHA-256 `493344f1f9df28f5968e3dfeb85fad7371a24f880b32dafb70351e7e96040dc3`，与回执一致。

## 已知问题

- 首个视口底部“默认 5 GB…”说明只露出约 4px，需要滚动查看；未改滚动区高度。
- 收藏页“管理收藏与标签…”的 UIA 弹层仍无法可靠自动化，收藏页文案与空状态未在本版截图。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
