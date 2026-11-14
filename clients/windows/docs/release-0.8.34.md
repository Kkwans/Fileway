# 栖卷 Windows 0.8.34

0.8.34 清掉两处原生 CommandBar：文件浏览工具栏的溢出菜单与缓存设置对话框的操作区。

## 本版完成

- 文件浏览工具栏：原生 CommandBar 溢出菜单改为自绘“更多”（`dots-three`）按钮 + MenuFlyout，下载记录/上传记录/详细信息/收藏/标记/上次操作结果全部迁入；菜单项代码由 `Label` 改为 `Text`，启用状态逻辑不变。
- 缓存与存储对话框：刷新 / 清理未使用缓存改为 `FwTextIconButtonStyle` 的图标+文字按钮，移除对话框内原生 CommandBar。
- 两处仍保留原有命令与禁用状态语义；不再出现系统 AppBarButton/CommandBar 外观。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.34.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机截图：`screens/browser-more.png`（自绘更多按钮）、`screens/storage-dialog.png`（对话框内图标+文字操作）。
- 产物：`.../20261030-ui0834/dist/Fileway-0.8.34-win-x64-unsigned-setup.exe`，SHA-256 `cc22d1e0…1237f536`，与回执一致。

## 未完成

- “更多”菜单展开态未截图，外观沿用全局 MenuFlyout 亚克力样式。
- 高对比度主题未走查（需调整系统设置，待用户同意）。
- 批次卡仍无用真实文件夹上传产生的截图，需真人上传后查看上传页。
- 拖到文件夹移动 / 拖出到资源管理器：合成鼠标无法触发 WinUI 拖拽，需真人验收。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
