# 栖卷 Windows 0.8.97

0.8.97 修复单文件与文件夹上传的文件选择器不可用：Windows App SDK `Microsoft.Windows.Storage.Pickers` 在本机调用时抛 `COMException`（接口已为另一线程整理），上传随即失败并误报“请检查服务器连接”。本版改用 WinRT `Windows.Storage.Pickers` + `InitializeWithWindow`（由 `WindowId` 取 HWND），文件选择器可正常打开，真实上传完成并由服务器确认；同时把上传/下载详情的进度条接入 0.8.95 的 6 DIP 轨道。

## 本版完成

- 单文件上传与文件夹上传的 picker 均切换到 `Windows.Storage.Pickers.FileOpenPicker` / `FolderPicker`，通过 `WinRT.Interop.InitializeWithWindow.Initialize(picker, Win32Interop.GetWindowFromWindowId(_uploadOwner))` 绑定主窗口；移除不支持的 `Title` 属性，保留提交按钮文案与起始位置。
- 选择器异常单独捕获并提示“无法打开文件选择器/文件夹选择器 + 重试或拖入上传”；不再把 picker 失败归因为服务器连接，提交阶段仍保留原有错误映射。
- 真实流程复核：通过选择器上传 29 B 与 37 B 两个测试文本，确认框出现、上传完成、`文件上传` 详情自动打开，显示“服务器已确认 37 B / 37 B / 服务器已确认文件发布完成”；文件出现在目录中。
- 文件夹选择器已确认可打开（`FOLDER_PICKER_OPEN`）；未完成整棵文件夹树的提交与批次卡验收，仍待真人选择。
- 上传/下载详情状态卡的 `ProgressBar` 外包 6 DIP、3 DIP 圆角 `FwStrokeBrush` 轨道，指示条 4 DIP 居中，与传输中心行内进度一致；上传详情像素复核轨道 `#EBEBEB` 上下可见。
- 证据：根因诊断（非交付构建）`20261031-ui0897-diag/probe-diag.log` 记录 `COMException: 应用程序调用一个已为另一线程整理的接口`；真实上传详情 `20261031-ui0897/screens/upload/upload-details.png`；文件夹选择器 `screens/folder-picker/folder-picker.png`；下载详情 `screens/download-details/download-details.png`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.97.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui0897/dist/Fileway-0.8.97-win-x64-unsigned-setup.exe`，SHA-256 `9dc59723994cfd857382d026ef70722b26bce4004636c747eac4cdd7934e1151`，与回执一致。

## 已知问题

- 文件夹选择器只验证了打开，整棵文件夹树上传与批次卡仍待真人验收。
- 本机自动化触发的 picker 异常已由 WinRT picker 规避；其他 Windows 版本/系统策略下的选择器行为未走查。
- 菜单项键盘焦点形状仍为系统矩形（颜色已用应用双色）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
