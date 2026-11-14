# 栖卷 Windows 0.8.110

0.8.110 修复文件网格末列被滚动条挤掉的问题：列宽计算现在扣除垂直滚动条占位，最后一列不再整列换行，1600 物理像素窗口由 3 列恢复为 4 列、最大化由 7 列恢复为 8 列。另收紧最近访问的空错误行间距，并在搜索条件与输入框一致时隐藏重复的“搜索条件”行。

## 本版完成

- 文件网格：`UpdateGridMetrics` 在计算列数与 `ItemWidth` 前扣除 10 DIP 滚动条占位；此前 `available / preferred` 恰好等于可用列数，滚动条出现后被挤掉一列，右侧留下整列空白。详细网格 1600×1000 实拍 3→4 列，最大化 7→8 列，默认 1680×1140 保持 4 列，末列右缘与工具栏右缘对齐。
- 最近访问：错误提示由独立 Grid 行移入操作行下方的 `RecentErrorPanel`，无错误时整体折叠，不再因空行仍计入 `RowSpacing` 而多出约 12 DIP；列头随之上移，操作行与列头间距与任务页一致。
- 搜索：`ConfirmedQueryText` 仅在 `_lastQuery` 与输入框内容不一致时显示；输入与结果同源时不再重复“搜索条件：txt”，编辑查询后仍显示结果对应的旧条件（实拍 `search-edited.png`）。
- 证据：修复前 `20261031-ui08110/screens/before/browser-grid.png`（3 列 + 右侧空列）、`recent.png`、`search.png`；修复后 `screens/grid-after/`、`screens/after/`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.110.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui08110/dist/Fileway-0.8.110-win-x64-unsigned-setup.exe`，SHA-256 `9f0fcf31cd9f1e2d526197eb8a0b2433be80b51ab740a9b3005ff12d8f4c1671`，与回执一致。

## 已知问题

- 网格列宽按“可用宽度均分”计算，整数除法会带来 1–2 DIP 的列宽差异。
- 菜单项键盘焦点形状仍为系统矩形（颜色已用应用双色）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
