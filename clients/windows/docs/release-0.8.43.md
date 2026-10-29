# 栖卷 Windows 0.8.43

0.8.43 给所有自研列表卡片补上 1px 描边，浅色主题下未选中行不再与页面同色而“消失”。

## 本版完成

- `FwListViewItemStyle` 模板增加 1px `FwStrokeBrush` 圆角描边（不参与命中测试），文件任务、搜索、最近访问、传输中心、收藏/标签列表统一生效。
- 浅色下卡片轮廓可辨；深色下描边保持低对比，不抢选中态；高对比度沿用系统窗口文本色。
- 选中态仍是半透明强调底 + 左侧 3px 标记，描边与焦点环互不遮挡。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.43.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机截图：`screens/nas-cards-light.png`、`screens/nas-cards-dark.png`（文件任务列表卡片轮廓）、`screens/search-cards.png`（44 条搜索结果行描边）。
- 产物：`.../20261031-ui0843/dist/Fileway-0.8.43-win-x64-unsigned-setup.exe`，SHA-256 `3631b511…5f42e1e`，与回执一致。

## 未完成

- 高对比度主题未走查（需调整系统设置，待用户同意）。
- 批次卡仍无用真实文件夹上传产生的截图，需真人上传后查看上传页。
- 拖到文件夹移动 / 拖出到资源管理器：合成鼠标无法触发 WinUI 拖拽，需真人验收。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
