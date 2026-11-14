# 栖卷 Windows 0.8.29

0.8.29 清掉 XAML 中剩余的原生按钮样式的漏网之鱼（播放器控件除外）。

## 本版完成

- 文件任务页：返回文件、查看关联操作记录、查看新重试任务、加载更多统一 `FwSecondaryButtonStyle`；提示条内“查看操作记录”使用 `FwTextIconButtonStyle`。
- 主界面右下角提示条的“核实结果 / 查看上传 / 查看下载”使用 `FwTextIconButtonStyle`。
- 缓存与存储对话框两处“选择…”使用 `FwSecondaryButtonStyle`。
- 侧栏遮罩按钮与播放器内部按钮保持原样（前者为无视觉的点击遮罩，后者为独立暗色播放器语言）。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误。
- 两次冒烟 `GATE=PASS`（pwsh 7）。
- 实机截图：`screens/settings/storage-settings-011928.png`、`screens/panels/nas-tasks-012022.png`。
- 产物：`.../20261029-ui0828/dist/Fileway-0.8.29-win-x64-unsigned-setup.exe`，校验值见同目录 json。

## 未完成

- 高对比度主题未走查（需调整系统设置，待用户同意）。
- 文件夹上传批次：选择器无法自动化，端到端需真人点击验收；传输中心仍按文件展示。
- 拖到文件夹移动 / 拖出到资源管理器：合成鼠标无法触发 WinUI 拖拽，需真人验收。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
