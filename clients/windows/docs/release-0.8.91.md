# 栖卷 Windows 0.8.91

0.8.91 统一传输中心任务详情弹窗的按钮与卡片：下载/上传详情内的操作按钮不再使用 WinUI 默认外观，改为应用的 36 DIP 圆角按钮；状态卡与取消确认卡改用应用表面/描边。

## 本版完成

- 文件下载详情：`边下边播`、`打开文件`、`重新下载（新任务）`、`取消下载…`、`返回` 统一 `FwSecondaryButtonStyle`（36 DIP、圆角、12px 内边距）；`确认取消` 使用 `FwPrimaryButtonStyle`。此前 XAML 字符串内的 `StaticResource` 未实际生效，改为 `DownloadDetailsView` 构造函数从 `Application.Current.Resources` 取样式赋值。
- 下载/上传详情的状态卡与取消确认卡：由系统默认描边改为 `FwSurfaceBrush` + 1px `FwStrokeBrush` + 10px 圆角。
- `UploadDetailsView` 同构修复：`取消上传…`、`继续上传` 用次按钮，`确认取消` 用主按钮；构造函数赋值与下载详情一致。
- 真实流程复核：将真实下载记录复制到专用数据根，打开传输中心 → 点击第一条下载任务 → 截取“文件下载”弹窗；按钮高度、圆角与全局次按钮一致，状态卡描边随主题。修复前后实拍见 `20261031-ui0891/screens/before/download-details.png`、`screens/after2/download-details.png`。
- 上传详情未单独截图：该专用数据根的上传标签为 0 条记录，且禁止伪造记录；本版只做与下载详情同构的代码修复，待有真实上传记录时补拍。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.91.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui0891/dist/Fileway-0.8.91-win-x64-unsigned-setup.exe`，SHA-256 `08eab76d745ea05bdb93f85c92efe99d0015f78f0c780b3a38f61cd591998d5a`，与回执一致。

## 已知问题

- 菜单项键盘焦点形状仍为系统矩形（颜色已用应用双色）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 文件批次的批次卡实拍仍需真人选择文件夹；拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
