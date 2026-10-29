# 栖卷 Windows 0.8.36

0.8.36 把搜索与最近访问列表也纳入自研列表样式，并修掉选中行上重叠的系统方形焦点框。

## 本版完成

- 搜索结果、最近访问列表改用 `FwListViewItemStyle`：行卡片、悬停/按下底、半透明强调选中底 + 左侧 3px 标记，与文件任务、传输中心一致。
- `FwListViewItemStyle` 关闭 `UseSystemFocusVisuals`，只保留自研圆角焦点环，避免键盘选中时出现系统方形黑框与自研选中底叠加。
- 搜索结果行在选中时可通过 UIA 验证：强调底、左侧标记、圆角焦点环均正常，未出现系统蓝色高亮块。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.36.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机截图：`screens/search-selected.png`（真实搜索 44 条结果并选中首行）、`screens/recent.png`（最近访问 5 条记录）。
- 深色补验见 `20261030-ui0835/screens/dark-nas.png`、`dark-storage.png`（文件任务卡片/选中态、缓存对话框）；深色 ToolTip 未取得截图。
- 产物：`.../20261030-ui0836/dist/Fileway-0.8.36-win-x64-unsigned-setup.exe`，SHA-256 `d1cc3fef…c82f61`，与回执一致。

## 未完成

- 最近访问列表的选中态未单独截图（与搜索结果共用同一 `FwListViewItemStyle`）。
- 高对比度主题未走查（需调整系统设置，待用户同意）。
- 批次卡仍无用真实文件夹上传产生的截图，需真人上传后查看上传页。
- 拖到文件夹移动 / 拖出到资源管理器：合成鼠标无法触发 WinUI 拖拽，需真人验收。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
