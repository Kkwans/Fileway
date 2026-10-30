# 栖卷 Windows 0.8.31

0.8.31 把系统提示气泡（ToolTip）纳入自研弹层语言，消除最后一类原生观感的浮层。

## 本版完成

- `Design.xaml` 新增全局隐式 `ToolTip` 样式：亚克力 `FwPanelBrush` 背景、1px `FwStrokeStrongBrush` 描边、8px 圆角、10×6 内边距、12px 字号、最大宽度 360，文字使用 `FwTextPrimaryBrush`。
- 与菜单/Flyout（App.xaml 亚克力覆盖）和 ContentDialog（`FwPanelBrush` + 描边）统一为同一套浮层语言；高对比度主题沿用系统窗口色与文本色资源。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.31.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机截图：`screens/tooltip.png`（传输中心刷新按钮上的“刷新记录”提示，圆角、描边、留白符合设计）。
- 产物：`.../20261030-ui0831/dist/Fileway-0.8.31-win-x64-unsigned-setup.exe`，SHA-256 `342af096…0d4322a7`，与回执一致。

## 补验（2026-10-30）

- 以持久化深色偏好直接启动，悬停传输中心刷新按钮取得深色 ToolTip 截图 `20261031-ui0850/screens/tooltip-dark.png`：深色亚克力底、浅色文字与描边正常，此前“深色 ToolTip 未截图”的缺口已关闭。

## 未完成

- 高对比度主题未走查（需调整系统设置，待用户同意）；ToolTip 在高对比度下的表现未单独截图。
- 批次卡仍无用真实文件夹上传产生的截图，需真人上传后查看上传页。
- 拖到文件夹移动 / 拖出到资源管理器：合成鼠标无法触发 WinUI 拖拽，需真人验收。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
