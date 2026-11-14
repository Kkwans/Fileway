# 栖卷 Windows 0.8.128

0.8.128 继续清扫“代码创建控件捕获浅色主题画刷”的同类缺陷：深色下“交互与外观”滑块标签实测为 RGB (83,90,100)（浅色主题次级灰 `#5C6470`），应为深色次级灰 (168,174,184)。本版改用 `FwCaptionTextStyle` 由 ThemeResource 解析，并同步修正回收站详情弹层的类型标签与字段标签、图标底板。

## 本版完成

- `MainPage.Presentation.cs` 的 `SliderField`：标签与“N 秒”数值都改用 `FwCaptionTextStyle`，不再捕获 `FwTextSecondaryBrush`。
- `Controls/Trash/TrashControl.xaml.cs`：详情弹层图标底板改用新增的 `FwIconTileStyle`；类型标签与 `Field` 标签改用 `FwCaptionTextStyle`，删除捕获的 `secondary` 画刷。
- `Styles/Design.xaml` 新增 `FwIconTileStyle`（Border，`FwSurfaceHoverBrush` 背景、`FwStrokeBrush` 1px 边框），供详情弹层 56×56 圆角图标底板复用。
- 深色实测（`20261031-ui08128/screens/appearance-dark.png`）：`提示自动消失` 与 `播放控件自动隐藏` 标签的主色像素为 (168,174,184)，与深色次级色一致；对比 0.8.127 前的 (83,90,100)。
- 截图与测量日志：`20261031-ui08128/Probe-AppearanceDark.log`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.128.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui08128/dist/Fileway-0.8.128-win-x64-unsigned-setup.exe`，SHA-256 `8bd2dac1da4babe93aa3574ad59b7546bf0fecfa85e45c1cd66c231aaaa437d3`，与回执一致；ZIP SHA-256 `73f12e1075982b155d23d51401e330d0ad03c9f9fa20b483d13dc163bb16083d`。

## 已知问题

- 同类的代码捕获画刷仍分布在部分查看器/预览区（`ViewerChrome`、批量下载确认、下载预览卡等），后续版本继续清扫。
- 回收站详情在深色下的实机截图未单独采集；本次只验证了同一 ThemeResource 机制与 `FwIconTileStyle` 的资源存在性。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
