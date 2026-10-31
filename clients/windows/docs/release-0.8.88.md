# 栖卷 Windows 0.8.88

0.8.88 修正“下载另存为”对话框：主按钮回到右侧、文件名输入框改用应用自研样式，并重做远端/本地文件/核对预览的信息层级。同时修复一个影响所有带 InfoBar 对话框的按钮排序缺陷。

## 本版完成

- `DialogOrdering` 根因修复：内容里的InfoBar 自带名为 `CloseButton` 的子元素，旧逻辑按全树查找会抓到它而不是动作区按钮；现在先定位 `CommandSpace` 动作面板，只在其内部解析 Primary/Secondary/Close，并对可能被包裹的按钮取共同容器后交换列/顺序，另加 140ms 延迟重试。下载另存为的“核对保存位置/开始下载”现固定在主按钮右侧。
- “下载另存为”文件名输入框接入 `FwTextBoxStyle`（圆角 10、应用焦点视觉），不再使用系统默认方角输入框。
- 信息层级：远端文件改为 12px 次级标签 + 14px SemiBold 文件名；说明与校验提示统一 12px 次级色；核对后的本地文件名/大小/保存到改为 13px 值 + 12px 次级标签，并放入 `FwSurfaceBrush` + `FwStrokeBrush` 的预览卡片；说明与路径可读性提升。
- 滚动高度由固定 400 改为 `DialogOrdering.ContentMaxHeight(this, 400)`；1200×900 下内容、预览卡与按钮完整显示。
- 实拍：修复前 `screens/before/save-as-initial-1800x1250.png`、`save-as-preview-1800x1250.png`、`save-as-preview-1200x900.png`；修复后 `screens/final2/` 同尺寸。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.88.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui0888/dist/Fileway-0.8.88-win-x64-unsigned-setup.exe`，SHA-256 `632e96bb70612b7001d6218bf074f00a620ec54a3f551a2a9ad87c2169e41125`，与回执一致。

## 已知问题

- 菜单项键盘焦点形状仍为系统矩形（颜色已用应用双色）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 文件批次的批次卡实拍仍需真人选择文件夹；拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
