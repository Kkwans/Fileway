# 栖卷 Windows 0.8.44

0.8.44 把键盘焦点环改为双色描边，减轻纯黑/纯白硬框对选中蓝底的压迫感。

## 本版完成

- `FwListViewItemStyle` 焦点环拆为两层：外层 2px `FwCanvasBrush` 光晕 + 内层 2px `FwTextPrimaryBrush` 环，选中状态下同时显示；高对比度自动使用系统窗口色/窗口文本色。
- 键盘焦点仍清晰可见，但与半透明强调底之间多了一层页面色间隔，弱化硬框感；鼠标选中不显示焦点环。
- 搜索结果选中行实拍确认双层环、3px 左侧标记与卡片描边互不遮挡。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.44.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机截图：`screens/focus-ring.png`（44 条搜索结果，键盘焦点选中行）、`screens/dark-search-focus.png`（深色下同一选中态与卡片描边）。
- 产物：`.../20261031-ui0844/dist/Fileway-0.8.44-win-x64-unsigned-setup.exe`，SHA-256 `ec3de60e…65eaa5c`，与回执一致。

## 未完成

- 高对比度主题未走查（需调整系统设置，待用户同意）。
- 批次卡仍无用真实文件夹上传产生的截图，需真人上传后查看上传页。
- 拖到文件夹移动 / 拖出到资源管理器：合成鼠标无法触发 WinUI 拖拽，需真人验收。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
