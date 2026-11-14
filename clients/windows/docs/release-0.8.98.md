# 栖卷 Windows 0.8.98

0.8.98 调整单文件上传确认框的信息层级：目标路径改为“上传到”12px 次级标签 + 13px 值，说明文字降为 12px 次级色；上传详情进度条补读屏名称，目标路径可选中复制。

## 本版完成

- “确认上传目标”原先用 14px 主色单行“上传到：/路径”，说明也是 12px 主色，层级接近；现改为 12px 次级“上传到”标签 + 13px 主色路径值，说明文字使用 `FwCaptionTextStyle` 的 12px 次级色，路径与说明不再竞争。
- 上传详情 `Destination` 增加 `IsTextSelectionEnabled=True`，与下载详情一致；上传进度条补 `AutomationProperties.Name="上传接收活动进度，完成还需服务器确认与保存"`。
- 证据：修复前 `20261031-ui0897/screens/upload-confirm/upload-confirm.png`（确认上传目标单行路径）；修复后 `20261031-ui0898/screens/upload-confirm/upload-confirm.png`（标签/值分行、说明次级）。
- 文件夹上传复核：WinRT 文件夹选择器可打开，并能通过“文件夹:”字段 + Enter 导航到测试目录；但内嵌 picker 的“选择文件夹”提交无法通过 UIA/鼠标可靠触发（点击后未返回文件夹、未出现确认框），整棵文件夹树提交与批次卡仍等待真人验收。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.98.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui0898/dist/Fileway-0.8.98-win-x64-unsigned-setup.exe`，SHA-256 `9c311bf8225dec3416eccf9c39533a909e35b9c835be9dd3d162f8a6ebf4daf9`，与回执一致。

## 已知问题

- 整棵文件夹树上传与批次卡未完成自动化验收，需要真人选择文件夹；不得用合成记录冒充。
- 菜单项键盘焦点形状仍为系统矩形（颜色已用应用双色）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 拖拽上传/移动与 PDF 沉浸外壳按原边界等待条件；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
