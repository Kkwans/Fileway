# 栖卷 Windows 0.8.20

0.8.20 统一“文件任务”页：分段选择器、Lucide 行图标、紧凑筛选栏。

## 本版完成

- 服务器任务/操作记录切换改为 `LayoutChoiceStyle` 分段选择器，选中态为半透明强调底。
- 任务行图标由 Segoe 字形换成 Lucide：复制 `copy`、移动 `scissors`、其他 `file`，颜色改为次级文本色，不再使用系统强调色。
- 页头补充说明文案；空状态改为圆角图标容器 + 20px 标题 + 13px 说明。
- 筛选栏限制状态下拉宽度（380）并整体左对齐，不再横跨整个窗口。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误。
- 两次冒烟 `GATE=PASS`（pwsh 7）。
- 实机截图：`screens/ui-panels2/nas-tasks-171418.png`。
- 产物：`.../20261029-ui0819/dist/Fileway-0.8.20-win-x64-unsigned-setup.exe`，校验值见同目录 json。

## 未完成

- 文件夹上传批次：端到端需真人点击验收；传输中心仍按文件展示。
- 拖到文件夹移动 / 拖出到资源管理器：合成鼠标无法触发 WinUI 拖拽，需真人验收。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
