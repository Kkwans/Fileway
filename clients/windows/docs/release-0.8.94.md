# 栖卷 Windows 0.8.94

0.8.94 调整主浏览网格卡片的文件名垂直对齐：名称在 40 DIP 的两行预留区内居中，收紧名称与类型/日期之间的空白；双行名称布局不变。浅色与深色均实拍并用 UIA 几何复核。

## 本版完成

- `BrowserGridTemplate` 的名称由固定 `MinHeight=40` 的 `TextBlock` 改为 40 DIP `Grid` 内垂直居中的 `TextBlock`；单行名称不再贴顶，图标→名称与名称→元数据的留白更均衡。
- UIA 实测：名称文本矩形由 `y=393 h=60` 变为 `y=408 h=30`，仍在 393–453 的预留区内垂直居中；“文件夹”与日期仍在 `y=465`、`y=492`，卡片高度与行距未变。
- 覆盖详细网格/紧凑网格等共用该模板的布局；双行名称仍占满 40 DIP 预留区，不与元数据重叠。
- 证据：修复前 `20261031-ui0893/screens/before-0894-light/browser-root.png`、`before-0894-dark/browser-root.png`；修复后 `20261031-ui0894/screens/after-light/browser-root.png`、`after-dark/browser-root.png`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.94.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui0894/dist/Fileway-0.8.94-win-x64-unsigned-setup.exe`，SHA-256 `90037b6a4f4cb230bfc4a1e3999357f5cd67e88b28f8f7e63836f80cd33e945f`，与回执一致。

## 已知问题

- 菜单项键盘焦点形状仍为系统矩形（颜色已用应用双色）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 文件批次的批次卡实拍仍需真人选择文件夹；拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
