# 栖卷 Windows 0.8.139

0.8.139 统一收藏与标签页的返回入口：该页原先使用 36px 返回箭头图标，与最近访问、文件任务、传输中心、存储工具的“返回文件”次按钮不一致，且箭头混在刷新/收藏/标签等页面操作里。现在改为同一 `FwSecondaryButtonStyle` 文字按钮，导航动作与页面操作分组更清晰。

## 本版完成

- `ResourceCollectionsControl.xaml`：删除 `arrow-left` 图标按钮，改为 `Content="返回文件"` 的 `FwSecondaryButtonStyle`；读屏名“离开收藏与标签，返回文件”。
- 1200×900 深色实拍 `20261101-ui08139/screens/collections.png`：返回按钮与右侧图标组间距 8，按钮高度 36、圆角 10，与其它页面一致；无溢出，筛选行与标题不变。
- 选中态 `collections-selected.png` 同批复核；日志 `audit.log`（`COLLECTION_ROWS=1`）。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.139.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261101-ui08139/dist/Fileway-0.8.139-win-x64-unsigned-setup.exe`，SHA-256 `1850f4fd5eda19ff868136074663d5ddb08dca28c635f2f58d48ecee87f88379`，与回执一致。

## 已知问题

- 仅实测 1200×900 深色；更宽窗口下返回按钮与图标组仍共用 Grid 自适应列，未单独截图。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
