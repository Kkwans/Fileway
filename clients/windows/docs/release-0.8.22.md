# 栖卷 Windows 0.8.22

0.8.22 修正连接页输入框文字垂直偏上，并统一收藏/标签页。

## 本版完成

- **输入框文字垂直居中**：带 Header 的 TextBox 在 34px 最小高度下内容顶对齐，导致文字明显偏上。连接页四个字段改为“外置 12px 标签 + 36px 无 Header 输入框”，`FwTextBoxStyle`/`FwPasswordBoxStyle` 统一为 `MinHeight 36`、`Padding 12,7`；放大截图核对后文字在框内居中，与下拉同基线。
- 收藏/标签页：两组纯图标 CommandBar 换成 34px 图标按钮 + “更多”菜单；打开/编辑/移除改为图标+文字按钮；空状态卡片化；标题 22px。
- 移除收藏页对 `SelectionCommands` CommandBar 的依赖，代码中的启用/可见性逻辑保持原样。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误。
- 两次冒烟 `GATE=PASS`（pwsh 7）。
- 实机截图：连接页输入框放大核对 `screens/conn-text4/inputs-zoom.png`；收藏页 `screens/ui-collections2/collections-002014.png`。
- 产物：`.../20261029-ui0821/dist/Fileway-0.8.22-win-x64-unsigned-setup.exe`，校验值见同目录 json。

## 未完成

- 文件夹上传批次：端到端需真人点击验收；传输中心仍按文件展示。
- 拖到文件夹移动 / 拖出到资源管理器：合成鼠标无法触发 WinUI 拖拽，需真人验收。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
