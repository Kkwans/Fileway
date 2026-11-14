# 栖卷 Windows 0.8.63

0.8.63 处理主浏览页顶部搜索框：从原生方形下划线外观改为设计系统圆角输入框，并让高度与路径栏、返回/上级/刷新按钮对齐。

## 本版完成

- 新增 `FwSearchTextBoxStyle`，基于 WinUI `AutoSuggestBoxTextBoxStyle` 覆盖圆角 10、1px 描边、`ControlFillColorSecondaryBrush` 背景、`MinHeight 36` 与 14px 字号；保留模板自带的查询按钮与清除按钮，不用另造图标按钮。
- `FilewaySearchBoxStyle` 改为通过 `TextBoxStyle` 注入上述样式，并移除无效的 AutoSuggestBox 外框属性（原设置不会作用到内部 TextBox，导致搜索框仍是原生方形外观）。
- 搜索框高度 36→40，与 40px 路径栏及导航按钮同基线；宽度仍为 240。
- 实测输入过滤与 Enter 搜索：输入“01”后列表收敛为 1 项，Enter 进入搜索页并返回 13 项结果；搜索框清除按钮与查询图标均可见。
- 浅色 1440/1200、深色 1440 截图确认圆角、描边、图标与高度一致。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.63.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机截图：`screens/sizes/main-1440x900.png`、`main-1200x800.png`（浅色）、`screens/dark/main-1440x900.png`（深色）、`screens/search/search-focus.png`、`search-typed.png`、`search-results.png`。
- 产物：`.../20261031-ui0863/dist/Fileway-0.8.63-win-x64-unsigned-setup.exe`，SHA-256 `449e0786808e325eda18387953efe84d1eb5e94b489bd7c8b2c826adbbb80096`，与回执一致。

## 已知问题

- 深色 ToolTip 仍随系统浅色外观显示；0.8.54 已验证 Flyout 宿主原型无效并回退，需重新定位实际显示层。
- 高对比度主题未走查（需调整系统设置，待用户同意）。
- 文件批次的批次卡实拍仍需真人选择文件夹；拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
