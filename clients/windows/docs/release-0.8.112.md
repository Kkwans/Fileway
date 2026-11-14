# 栖卷 Windows 0.8.112

0.8.112 把主浏览工具栏的排序、类型与布局下拉接入自研控件模板：去掉系统焦点矩形，改为应用双色圆角焦点环与 Lucide 下拉箭头；同时修复一个缺失主题资源导致的启动崩溃，并复核其余四种文件布局的列宽。四种布局在 1600×1000 下均填满宽度、无末列空白。

## 本版完成

- 选择器模板：`FilewaySelectorButtonStyle` 从“只设属性、沿用系统模板”改为完整 `DropDownButton` 模板（主题背景/1px 描边/10 DIP 圆角、自研 FocusStates、Lucide `caret-down` 箭头），不再出现系统黑色焦点矩形；排序、类型筛选、布局三个选择器共用。
- 启动崩溃修复：新模板首次应用时抛出 `XamlParseException: Cannot find a Resource with the Name/Key ControlFillColorQuaternaryBrush`——该键不在应用主题资源中。按下态改用 `SubtleFillColorTertiaryBrush`，并同步替换 `FwSecondaryButtonStyle` 的同类引用，避免次按钮按下时再触发。
- 布局复核：1600×1000 下紧凑网格 6 列、无界网格 5 列、详细列表右列对齐、紧凑列表 2 列，四种布局均铺满内容宽度；切换后选择器保持键盘焦点并显示自研焦点环。
- 证据：修复前 `/screens/{compact-grid,windows-icons,details-list,compact-list}.png`（系统焦点矩形）与 `/screens/before-selector-crop.png`；修复后 `/screens/after/` 与 `/screens/after-selector-crop.png`；启动崩溃日志 `20261031-ui08109/data/diagnostics/startup.log`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.112.0）。
- 模板改动后先跑 10 秒启动检查 `GATE=PASS`，随后两次正式冒烟 `GATE=PASS`（15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui08112/dist/Fileway-0.8.112-win-x64-unsigned-setup.exe`，SHA-256 `7173d398c9405dd7751948a2f237c495b48a0354370885c370b497301cb8312c`，与回执一致。

## 已知问题

- 选择器获得键盘焦点时显示自研双色环（黑内环 + 画布色光晕）；鼠标点击后焦点通常仍在按钮上，若不需要常驻焦点环需调整焦点转移策略。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
