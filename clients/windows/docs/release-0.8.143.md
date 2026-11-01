# 栖卷 Windows 0.8.143

0.8.143 补齐回收站的返回入口：该页有 `CloseRequested` 与 Escape 处理，但页头只有恢复/刷新图标，没有可见“返回文件”；在窄窗覆盖侧栏关闭时只能靠 Escape 或重新打开侧栏离开。现在页头右侧加入与其它远程页同款 `FwSecondaryButtonStyle` 返回按钮，并实测点击可回到工作区。

## 本版完成

- `TrashControl.xaml` 页头操作组末尾新增“返回文件”按钮，`Click="Close_Click"`，读屏名“离开回收站，返回文件”。
- UIA 回归：`BACK_BUTTON=True`，点击后 `恢复所选项目` 消失、工作区搜索框出现（`STORAGE_HEADING_AFTER=False`、`WORKSPACE_AFTER=True`）。
- 1500×1050 深色实拍 `20261101-ui08143/trash/trash.png`：恢复/刷新/返回三按钮靠右排列，与内容卡片同缘；空状态居中不变。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.143.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261101-ui08143/dist/Fileway-0.8.143-win-x64-unsigned-setup.exe`，SHA-256 `a633313ea0e24635ff1c804821cdff50ca2958ad713227e92ac495a359bb1406`，与回执一致。

## 已知问题

- 仅实测深色与 1500×1050；1200 覆盖侧栏下布局沿用自适应列，未单独截图。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
