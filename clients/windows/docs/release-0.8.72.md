# 栖卷 Windows 0.8.72

0.8.72 优化文本查看器的 CSV 表格：列宽从固定 220 DIP 改为按查看器宽度自适应（140–220 DIP），小表不再横向溢出被裁掉最后一列。

## 本版完成

- `BuildTable` 按 `_tableScroll` 实际宽度计算列宽：`(viewport - 40) / 列数`，限制在 140–220 DIP；表头与数据行共用同一列宽。
- 4 列小表在 720 DIP 内容宽度下四列完整可见，不再需要横向滚动；列数很多时仍保持 140 DIP 下限并横向滚动。
- 顺带复核图片查看器：错误态卡片、左右翻页与底部缩放胶囊在 1200×900 下布局正常，本版未改动。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.72.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机截图：`screens/text-viewer.png`（修复前，备注列被裁）、`screens/after/text-viewer.png`（四列完整）、`screens/after/image-viewer.png`。
- 产物：`.../20261031-ui0872/dist/Fileway-0.8.72-win-x64-unsigned-setup.exe`，SHA-256 `96f0b3a6dbd794afb42ed2cc485bb86c1188443872318af2001aa758a79a67b7`，与回执一致。

## 已知问题

- CSV 列宽按打开时宽度计算，运行中调整查看器大小不会重新分配列宽。
- 菜单项与 Expander 头部的键盘焦点仍使用 WinUI 系统矩形焦点视觉（内部模板，暂列系统语义例外）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 文件批次的批次卡实拍仍需真人选择文件夹；拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
