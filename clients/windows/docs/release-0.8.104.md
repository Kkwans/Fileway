# 栖卷 Windows 0.8.104

0.8.104 修正搜索结果右侧列头：该列实际显示文件大小（文件夹显示“文件夹”）+ 修改时间，原“类型 · 修改时间”与内容不符，改为“大小/类型 · 修改时间”。

## 本版完成

- `SearchResultsControl` 右侧列头由“类型 · 修改时间”改为“大小/类型 · 修改时间”。`SearchResultRow.Summary` 对文件是 `大小 · 修改时间`，对文件夹是 `文件夹 · 修改时间`，新列头同时覆盖两种情况。
- 1000×700 下以真实搜索 `png`（含子目录、4 项结果）复核：查询行、含子目录选择器、打开/重新搜索按钮与结果行均无溢出。
- 证据：修复前 `20261031-ui08103/screens/narrow-search/search.png`；修复后 `20261031-ui08104/screens/search-light/search.png`、`screens/search-dark/search.png`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.104.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui08104/dist/Fileway-0.8.104-win-x64-unsigned-setup.exe`，SHA-256 `c418977fe3195df12291895e2ad24e99feb7cc7192fc4264d058157c403cdc70`，与回执一致。

## 已知问题

- 菜单项键盘焦点形状仍为系统矩形（颜色已用应用双色）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
