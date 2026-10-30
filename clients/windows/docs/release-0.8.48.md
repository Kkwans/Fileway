# 栖卷 Windows 0.8.48

0.8.48 修复一个全局对话框问题：`DefaultButton=Close` 会让“取消”也被强调色填充，导致两个蓝色主按钮并列。

## 本版完成

- 根因：WinUI ContentDialog 会给“默认按钮”套用强调色；此前大量确认框使用 `DefaultButton=Close`，于是“取消”和主操作同时变蓝。
- 全局把 21 处 `DefaultButton = ContentDialogButton.Close` 改为 `None`：Enter 不再自动触发取消或主操作，破坏性确认更安全；主操作保持唯一蓝色。
- `DialogOrdering` 在对话框打开时统一把主按钮重置为 `FwPrimaryButtonStyle`、关闭/次要按钮重置为 `FwSecondaryButtonStyle`，并保留主按钮右置的幂等换列。
- 实测（真实下载记录）：“删除下载任务？”现在“取消”为白底描边、“删除任务”为唯一蓝色按钮；点击取消不删除任何任务，测试账号状态未变。截图：`screens/delete-download-dialog-2.png`（修复后）、`screens/delete-download-dialog.png`（修复前双蓝对照）。
- 0.8.47 的恢复确认框同样保持“取消”次级、“恢复”唯一主按钮。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.48.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui0848/dist/Fileway-0.8.48-win-x64-unsigned-setup.exe`，SHA-256 `dbd769b4…22ac3995`，与回执一致。

## 未完成

- 主按钮获得键盘焦点时会显示系统黑色焦点环，尚未改为自研双色焦点环。
- 高对比度主题未走查（需调整系统设置，待用户同意）。
- 新提交文件夹批次的批次卡实拍仍需真人选择文件夹。
- 拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
