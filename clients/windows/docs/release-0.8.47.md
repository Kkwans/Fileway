# 栖卷 Windows 0.8.47

0.8.47 统一回收站详情浮窗与恢复确认框，修掉“取消/恢复”两个蓝色主按钮并列的问题。

## 本版完成

- 回收站详情浮窗：标题区新增三点拖动手柄与拖动提示；关闭、恢复、永久删除改为 `FwIconButtonStyle`，补 `AutomationProperties.Name`（关闭回收站详情 / 恢复项目 / 永久删除项目）。
- 恢复确认框：非破坏性确认的默认操作改为“恢复”，并显式指定 `FwPrimaryButtonStyle` / `FwSecondaryButtonStyle`；现在“取消”为白底描边次级按钮、“恢复”为唯一蓝色主按钮（此前两个按钮都是强调色实心）。
- 真实流程验证：测试账号将“01 文本”移入回收站 → 双击打开详情浮窗截图 → 恢复并确认回收站清空（测试账号已恢复干净）。截图：`screens/trash-details.png`、`screens/restore-dialog.png`、`screens/trash-restored2.png`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.47.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui0847/dist/Fileway-0.8.47-win-x64-unsigned-setup.exe`，SHA-256 `bd2ed1f9…8dc5ed29`，与回执一致。

## 未完成

- 高对比度主题未走查（需调整系统设置，待用户同意）。
- 新提交文件夹批次的批次卡实拍仍需真人选择文件夹。
- 拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
