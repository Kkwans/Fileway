# 栖卷 Windows 0.8.138

0.8.138 统一收藏与标签页头部图标间距：该页操作组原为 4 DIP，其余页面头部（最近访问、文件任务、传输中心、存储工具）均为 8 DIP，造成同类页面节奏不一致。本版改为 8 DIP，并在 1200×900 覆盖侧栏下复核布局无溢出。

## 本版完成

- `ResourceCollectionsControl.xaml` 头部操作组 `Spacing` 4 → 8。
- 1200×900 深色实拍 `20261101-ui08138/screens/collections.png`：返回/刷新/收藏/标签/更多按钮间距与其它页面一致，标题与筛选行不重叠；选中态 `collections-selected.png`。日志 `audit.log`（`COLLECTION_ROWS=1`）。
- 同批复核传输中心覆盖侧栏页面；截图中的标题遮挡仍是合成光标停留产生的 ToolTip 伪影，未改代码。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.138.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261101-ui08138/dist/Fileway-0.8.138-win-x64-unsigned-setup.exe`，SHA-256 `37fc11a1188219d1fc18f892871a9accc0ade48895d758a397c887aee3128962`，与回执一致。

## 已知问题

- 仅实测 1200×900 深色；更宽窗口的收藏页头部布局沿用同一 Grid，未单独截图。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
