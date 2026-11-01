# 栖卷 Windows 0.8.118

0.8.118 给“交互与外观”的两个滑块补上实时数值：提示自动消失、播放控件自动隐藏在标题右侧以 12px 次级色显示“4 秒 / 2 秒”，拖动时即时更新。滑块不再使用自带 Header，避免 150% DPI 下的标题与轨道重叠风险；深色主题下的收藏选中态与操作启用同步复核。

## 本版完成

- 外观对话框：`SliderField` 统一生成“12px 次级标签 + 右侧当前秒数 + Slider”结构；两个滑块显示实时值，读屏名称保留“提示自动消失/播放控件自动隐藏”。
- 深色复核：收藏页选中行为半透明蓝底 + 亮色 3px 强调条，打开/编辑/移除 UIA 均为 enabled=True；顶部禁用收藏/标签按钮与启用操作区分清晰。
- 证据：修复前深色外观 `20261031-ui08118/screens/dark2/cache-dark.png`（无秒数）；修复后 `screens/appearance-after/appearance-dark.png`（“4 秒 / 2 秒”）；深色收藏选中 `screens/dark2/collections-selected.png`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.118.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui08118/dist/Fileway-0.8.118-win-x64-unsigned-setup.exe`，SHA-256 `5a7729f4ad7df7fd4d374bc9a5d839673dfe826c3af8b81eedf19bf4af2f747f`，与回执一致。

## 已知问题

- 深色缓存设置滚动条本轮误打开了外观对话框，未单独复核；浅色下已见滚动条。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
