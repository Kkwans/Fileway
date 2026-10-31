# 栖卷 Windows 0.8.64

0.8.64 补齐侧栏导航按钮与文件详情动作磁贴的自研焦点环，并修复动作磁贴没有横向拉伸、只占图标宽度的排版问题。

## 本版完成

- `FwNavigationButtonStyle`：侧栏“我的文件/最近访问/传输中心/文件任务/存储工具/回收站/切换连接”等按钮改用自研双色圆角焦点环，关闭系统方形焦点视觉。
- `FwActionTileStyle`：详情面板“下载/复制/剪切/移动到/重命名/收藏/标签/移入回收站”动作磁贴同样接入双色焦点环；同时补 `HorizontalAlignment=Stretch`，修复磁贴只占内容宽度（约 24 DIP）导致图标看起来散落、悬停/焦点区过窄的问题。
- 浅色与深色实拍：侧栏焦点环、详情面板下载磁贴焦点环均正常；磁贴现在填满四列栅格，图标与文字居中一致。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.64.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机截图：`screens/focus-sidebar.png`、`screens/focus-details-download.png`（浅色）、`screens/dark/focus-sidebar.png`、`screens/dark/focus-details-download.png`（深色）。详情面板通过真实选择 `01 文本/data.csv` 后打开。
- 产物：`.../20261031-ui0864/dist/Fileway-0.8.64-win-x64-unsigned-setup.exe`，SHA-256 `ff8fa11dec189da1ec072169a39cc2ba60f029c439b3691c9db3e4e5c28cded0`，与回执一致。

## 已知问题

- 深色 ToolTip 仍随系统浅色外观显示；0.8.54 已验证 Flyout 宿主原型无效并回退，需重新定位实际显示层。
- 高对比度主题未走查（需调整系统设置，待用户同意）。
- 文件批次的批次卡实拍仍需真人选择文件夹；拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
