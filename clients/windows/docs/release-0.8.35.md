# 栖卷 Windows 0.8.35

0.8.35 让文件任务页的列表选中态回归自研样式，不再出现系统蓝色高亮块。

## 本版完成

- 文件任务三个列表（服务器任务、操作记录、逐项确认）统一使用 `FwListViewItemStyle`：
  - 行容器为 `FwSurfaceBrush` 卡片，选中态为半透明 `FwSelectionBrush` + 左侧 3px 强调色标记；
  - 指针悬停/按下使用 `SubtleFillColorSecondary/Tertiary`，与传输中心等面板一致；
  - 逐项确认列表为只读展示，仍保留同一卡片外观。
- 行内边距统一 10、行间距 6，任务行不再贴在纯背景上。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.35.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机截图：`screens/nas-selected.png`（选中行显示半透明强调底与左侧标记，非系统蓝块）。
- 产物：`.../20261030-ui0835/dist/Fileway-0.8.35-win-x64-unsigned-setup.exe`，SHA-256 `683fa4cf…044f834b`，与回执一致。

## 未完成

- 高对比度主题未走查（需调整系统设置，待用户同意）。
- 批次卡仍无用真实文件夹上传产生的截图，需真人上传后查看上传页。
- 拖到文件夹移动 / 拖出到资源管理器：合成鼠标无法触发 WinUI 拖拽，需真人验收。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
