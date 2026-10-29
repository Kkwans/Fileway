---
version: alpha
name: 栖卷 Windows
description: NAS 文件浏览与预览桌面客户端的自研界面语言：安静的深色画布、毛玻璃浮层、自绘控件，不使用 Windows 原生控件外观。
colors:
  canvas: "#0B0C0F"
  surface: "#17191D"
  surface-raised: "#1F2228"
  surface-hover: "#2A2E36"
  hairline: "#1FFFFFFF"
  hairline-strong: "#33FFFFFF"
  ink: "#F4F6F8"
  muted: "#A8AEB8"
  faint: "#757C87"
  primary: "#0F6CBD"
  on-primary: "#FFFFFF"
  accent: "#69A8FF"
  danger: "#FF6B6B"
  glass: "#B3121519"
  glass-stroke: "#26FFFFFF"
  scrim: "#E60E1013"
typography:
  display-lg:
    fontFamily: "Segoe UI Variable Display, Microsoft YaHei UI"
    fontSize: 28px
    fontWeight: 600
    lineHeight: 36px
    letterSpacing: 0px
  heading-lg:
    fontFamily: "Segoe UI Variable Text, Microsoft YaHei UI"
    fontSize: 20px
    fontWeight: 600
    lineHeight: 28px
    letterSpacing: 0px
  title-md:
    fontFamily: "Segoe UI Variable Text, Microsoft YaHei UI"
    fontSize: 15px
    fontWeight: 600
    lineHeight: 22px
    letterSpacing: 0px
  body-md:
    fontFamily: "Segoe UI Variable Text, Microsoft YaHei UI"
    fontSize: 14px
    fontWeight: 400
    lineHeight: 22px
    letterSpacing: 0px
  body-sm:
    fontFamily: "Segoe UI Variable Text, Microsoft YaHei UI"
    fontSize: 13px
    fontWeight: 400
    lineHeight: 20px
    letterSpacing: 0px
  caption:
    fontFamily: "Segoe UI Variable Text, Microsoft YaHei UI"
    fontSize: 12px
    fontWeight: 400
    lineHeight: 18px
    letterSpacing: 0px
  code-md:
    fontFamily: "Cascadia Code, Consolas, Courier New"
    fontSize: 13px
    fontWeight: 400
    lineHeight: 21px
    letterSpacing: 0px
rounded:
  none: 0px
  xs: 6px
  sm: 8px
  md: 10px
  lg: 14px
  xl: 18px
  full: 9999px
spacing:
  xxs: 2px
  xs: 4px
  sm: 8px
  md: 12px
  lg: 16px
  xl: 20px
  xxl: 24px
  section: 32px
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    typography: "{typography.body-md}"
    rounded: "{rounded.md}"
    height: 36px
  button-primary-hover:
    backgroundColor: "#1A7AD4"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.md}"
  button-primary-disabled:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.md}"
  button-secondary:
    backgroundColor: "{colors.surface-raised}"
    textColor: "{colors.ink}"
    typography: "{typography.body-md}"
    rounded: "{rounded.md}"
    height: 36px
  icon-button:
    backgroundColor: transparent
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
    size: 36px
  action-tile:
    backgroundColor: transparent
    textColor: "{colors.ink}"
    typography: "{typography.caption}"
    rounded: "{rounded.md}"
    height: 62px
  viewer-pill:
    backgroundColor: "{colors.glass}"
    textColor: "{colors.ink}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.lg}"
  toolbar-bar:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body-sm}"
  content-dialog:
    backgroundColor: "{colors.surface-raised}"
    textColor: "{colors.ink}"
    typography: "{typography.body-md}"
    rounded: "{rounded.xl}"
    padding: "{spacing.xxl}"
  toast:
    backgroundColor: "{colors.glass}"
    textColor: "{colors.ink}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.md}"
    padding: "{spacing.sm}"
  list-row:
    backgroundColor: transparent
    textColor: "{colors.ink}"
    typography: "{typography.body-sm}"
    height: 40px
  detail-panel:
    backgroundColor: "{colors.surface-raised}"
    textColor: "{colors.ink}"
    typography: "{typography.body-md}"
    rounded: "{rounded.xl}"
    width: 420px
  breadcrumb-item:
    backgroundColor: transparent
    textColor: "{colors.muted}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.sm}"
    height: 28px
---

# 栖卷 Windows 设计契约

## Overview

栖卷是一个本地优先的 NAS 文件客户端。界面应当像一件安静的桌面工具：内容占主导，控件退到边缘；信息密度接近资源管理器，但视觉语言比系统控件更克制、更精致。

设计目标：

- **沉浸**：图片、视频、文档查看器以内容为画布，工具条悬浮、自动隐藏。
- **统一**：所有窗口、弹层、对话框、列表共享同一套颜色、圆角、间距与图标语言，不出现“这里是系统控件、那里是自研控件”的割裂。
- **自研**：按钮、对话框、下拉、弹层不使用 Windows 原生模板外观；通过自绘模板与毛玻璃层建立自己的识别度。
- **可读**：阅读类界面优先保证栏宽、行高与对比度，而不是堆满工具按钮。

## Colors

深色为默认体验，浅色主题保持相同语义层级。

- `canvas`：查看器的全幅画布；图片/PDF 查看器始终使用近黑，不随主题变化。
- `surface` / `surface-raised`：窗口与卡片；阅读器正文底色使用 `canvas`，工具栏使用 `surface`。
- `hairline`：1px 分隔线；需要更强边界时用 `hairline-strong`。
- `ink` / `muted` / `faint`：三级文字；不要用纯黑或纯白代替。
- `primary`：唯一的主操作色，只用于每个界面最重要的一个动作（如“打开”“开始解压”）。
- `accent`：链接、行内代码等强调信息，不与主按钮混用。
- `glass` / `glass-stroke`：浮动工具条、提示、详情面板的半透明层与其 1px 高光边。
- `scrim`：覆盖在图像上的深色玻璃条，保证白色文字在任意图片上都可读。

状态色只允许 `danger` 表达错误/破坏性操作；成功与警告通过图标与文案表达，不新增饱和色。

## Typography

- 字体：UI 文本 `Segoe UI Variable Text`，中文回退 `Microsoft YaHei UI`；标题可用 `Segoe UI Variable Display`。
- 等宽：`Cascadia Code`，用于源码、日志、代码块、路径。
- 层级：`display-lg` 仅用于空状态与查看器大标题；`heading-lg` 用于对话框标题与详情名称；`title-md` 用于窗口标题与卡片标题；正文 `body-md`；辅助 `body-sm`/`caption`。
- 正文行高固定为字号的 1.5–1.7 倍；Markdown 正文最大栏宽 780px，代码块行高 21px 且不折行。

## Layout

- 窗口内边距：常规 20px；查看器内容区可全幅 0 内边距。
- 工具栏高度 58px，底部状态栏 30px，列表行高 40px，图标按钮 36px，动作磁贴 62px。
- 阅读器：内容列最大 780px，左右至少 48px 留白；段落间距 14px，标题上间距 16–22px。
- 详情面板：宽 420px，高 480px，内边距 20/16；操作区固定两组（传输、管理），每组 4 列等宽。
- 查看器：顶部信息条 60px，底部浮动胶囊距底 22px；左右翻页按钮 44px 圆形，距边缘 14px。
- 任何工具条都不得改变内容尺寸；内容永远是画布，工具条是覆盖层。
- 连接页：未连接时隐藏导航侧栏，整窗为欢迎页。内容为一组最大宽 1120、水平居中的两栏：左侧品牌栏（应用标识、34px 标题、说明、三行带图标的能力说明），右侧 440px 表单卡片（18px 圆角、26px 内边距）。窗口宽度 < 1000 时隐藏品牌栏、卡片占满。操作区（保持登录、主按钮、取消/返回）紧贴表单卡片下方、与卡片同宽同列，随表单一起滚动与居中；**不得使用与卡片不同宽度的独立窗口底栏**（边缘不齐），也不得让主按钮滚出视野。下拉与输入框统一左对齐。连接页字段使用外置 12px 标签 + 36px 无 Header 输入框，输入样式统一 `MinHeight 36`、`Padding 12,7`（带 Header 且 34px 时文字会顶对齐偏上）；**不得对带 Header 的输入框设置固定 Height**（会把标题与输入框一起裁切）。

## Elevation & Depth

只有**浮动层**允许阴影与毛玻璃：

- 顶部/底部工具条：`scrim` 或 `glass` 背景 + 1px `glass-stroke` 边；不使用系统 Acrylic 的默认蓝灰。
- 浮动胶囊：`glass` + 16px 圆角 + 轻微投影（ThemeShadow，Z=24–32）。
- 对话框：18px 圆角 + `surface-raised`/毛玻璃 + 1px 描边；不允许直角或系统默认阴影。
- 静态内容区不使用阴影；用 1px hairline 表达层级。

## Shapes

- 圆角尺度：6（小标签）/8（输入与下拉）/10（按钮与磁贴）/14（面板与浮层）/18（对话框）/full（圆形翻页按钮）。
- 同类控件必须使用同一圆角：所有按钮 10，所有对话框 18，所有浮层 14，所有圆形按钮 full。
- 图标容器使用圆角方形（16px），不得用圆形裁切图标。

## Components

### Buttons

- `button-primary`：每个视图最多一个；位于操作区最右侧（桌面端阅读顺序）。高度 36，圆角 10，文字 14/600。
- `button-secondary`：取消、次要动作；与主按钮同高，描边 1px hairline。
- `icon-button` / `text-button`：工具条使用；悬停用 `surface-hover`，不使用系统高亮色。
- `action-tile`：详情面板与移动端式操作区；图标 22 + 文字 12，垂直居中，禁用时整体 35% 透明度。
- 禁止使用 AppBarButton、CommandBar 的默认外观作为主要操作入口。

### Dialogs & flyouts

- 统一 18px 圆角、`surface-raised` 或玻璃背景、1px 描边。
- 主按钮固定在右侧，取消在左；通过 `DialogOrdering` 附加属性全局保证，不在单个对话框里手写方向。
- 标题使用 `heading-lg`，正文 13–14px，按钮高度 36；不使用系统默认的“确定在左”。
- 下拉/菜单使用 `glass` + 8–10px 圆角 + 6px 内边距；禁止默认 MenuFlyout 直角外观。

### Lists & tables

- 行高 40，图标 20，名称 13.5，右侧元信息 12/muted。
- 表头使用 `surface-raised`，单元格 220px 起，允许横向滚动；不截断到无法阅读。
- 选中态使用 `surface-hover`，不使用系统蓝色高亮块。

### Viewers

- 顶部信息条：文件名 14/600 + 一行元信息 12/muted；2.6 秒无操作自动隐藏。
- 底部胶囊：图标/文字按钮 34–40 高，间距 2，分割线 1px `glass-stroke`。
- 图片/PDF：滚轮以光标为锚点缩放，双击切换适应/放大，拖拽平移，Esc 关闭。
- 文本：工具栏固定，不自动隐藏；源码与渲染切换不得丢失滚动位置以外的最小状态。

### Icons

- 只使用仓库内 Lucide 单色 PNG（`Assets/Lucide/light`），尺寸 16/18/20/22/30；颜色继承 `Foreground`。
- 不再混用 `SymbolIcon`/Segoe 字形；新增图标必须先补 Lucide 资源再使用。
- 同一个动作在全局使用同一个图标名（下载 `download-simple`、回收站 `trash`、更多 `dots-three`）。

## Motion

- 工具条显隐：170ms CubicEase Out；自动隐藏延迟 2600ms。
- 不使用弹跳、缩放超过 1.05 的动画；不阻塞输入的过渡。
- 高对比度或“减少动画”开启时，所有过渡降为 1ms 直接切换。

## Do's and Don'ts

- Do：优先复用 `Styles/Design.xaml` 的令牌与样式；新增控件先找现有样式。
- Do：把主操作放在右侧，并保持每个界面只有一个主按钮。
- Do：浮动层使用玻璃 + 描边 + 阴影三件套；内容层保持纯净。
- Don't：不要使用系统 Button/CommandBar/ContentDialog 的默认外观作为最终视觉。
- Don't：不要在同一界面混用两套图标体系或两套圆角。
- Don't：不要给静态内容加阴影、渐变或饱和色块。
- Don't：不要让工具条挤压内容布局；查看器内容必须全幅。

## Accessibility

- 正文对比度不低于 4.5:1，辅助文字不低于 3:1；玻璃层必须保证其上的文字在纯黑与纯白背景上都可读。
- 所有图标按钮必须有 `AutomationProperties.Name`；查看器提供键盘路径（方向键、0/1、Esc）。
- 语义状态不能只靠颜色：错误同时给出图标与文案。
- 高对比度下禁用玻璃与阴影，回退为系统色实底。

## Implementation Mapping

- 令牌与控件样式：`clients/windows/src/Fileway.App/Styles/Design.xaml`（`FwCanvasBrush`、`FwSurfaceBrush`、`FwGlassBrush`、`FwPrimaryButtonStyle`、`FwIconButtonStyle`、`FwActionTileStyle`、`FwPillStyle` 等）。
- 查看器外壳：`Controls/ViewerChrome.cs`（顶部条、底部胶囊、自动隐藏、加载/错误层）。
- 对话框排序：`Controls/DialogOrdering.cs`，由 ContentDialog 隐式样式自动启用。
- 工具栏选择器：排序/类型/视图统一使用 `DropDownButton` + `FilewaySelectorButtonStyle`（左对齐、12px 内边距），菜单项用 `LayoutChoiceStyle`；单选项选中后收起菜单。不要再让原生 `ComboBox` 承载工具栏选择器（其选中文本带原生内缩）。
- 空状态：居中 72px 圆角图标容器（`FwSurfaceBrush` + 1px `FwStrokeBrush`）、20px 标题、13px/20 行高说明；兜底“重新加载”使用 `FwSecondaryButtonStyle`，不得使用系统默认按钮。空列表同时隐藏列头。
- 传输中心：文件夹批次以批次卡片成组（32px 文件夹图标底、批次名、聚合进度、“x/y 已完成”、文件数与总大小），组内文件行左缩进 24px 且仍可点开单个任务详情；批次行只做汇总，不承载暂停/删除等任务操作。
- 共享控件样式（`LayoutChoiceStyle` 等）必须放在应用级 `Styles/Design.xaml`；页面级资源无法被独立 `UserControl` 解析，会在启动时抛 XAML 异常。
- 已知尚未迁移：部分弹层仍使用旧样式，按本契约逐步替换。
- PDF 查看器：工具栏使用底部浮动玻璃药丸（`FwPillStyle` + `FwIconButtonStyle` + `FwTextIconButtonStyle`，左/右方向键翻页，适应窗口按视口宽度计算缩放）。

## Known Gaps

- [x] 主浏览窗口工具栏/筛选/布局切换已按本契约重排（0.8.10，`screens/ui-browser/`）。
- [ ] PDF 查看器工具栏已迁移为玻璃药丸，待实机截图验收；沉浸外壳仍受原生堆破坏阻塞。
- [x] 弹层模糊结论（2026-10-29）：WinUI 应用内 `ContentDialog`/`MenuFlyout` 的 Acrylic 不会模糊内容，单纯降低不透明度只会牺牲对比度；已实测并回退。**决策**：在引入合成模糊层（Win2D/Composition）之前，弹层统一保持「主题色 Acrylic + 1px 高光边」，不再反复调整透明度。
- [x] 独立 Acrylic 窗口结论（2026-10-29）：三次原型均因定位/尺寸问题回退。复现要点：① `AppWindow.MoveAndResize` 使用物理像素，必须乘 `XamlRoot.RasterizationScale`；② 高度需为按钮行预留至少 72 DIP；③ owner 必须取 `App.MainWindow` 的顶层 HWND，`XamlRoot.ContentIslandEnvironment` 在部分路径下不可靠。若后续重启该方案，按此三点实现，不要复用详情面板的窗口。
- [x] 紧凑网格已实机复核（2026-10-29，`screens/ui-layouts/compact-grid.png`），容器状态正常；无界网格已复核（2026-10-29，`20261029-ui0815/screens/ui-grid/unbounded-grid-161844.png`），菜单选中态已改为半透明强调底。
- [ ] 拖到文件夹移动需真实鼠标验收；拖出到资源管理器未开始。
- [~] 文件夹“整组上传”（0.8.15 部分完成）：`UploadBatchInfo` 已随任务持久化并透传为 `X-Upload-Batch-*`/`X-Upload-Folder`，0.8.30 起传输中心按批次聚合展示；仍缺真人端到端验收（文件夹选择器无法自动化）。不能只改 UI 文案就算完成。
- [~] 主题走查：浅色已全量截图；深色已复核连接页、主界面（`20261029-ui0823/screens/ui-dark/`）、传输中心/回收站/存储工具/最近访问（`screens/ui-dark-panels/`）、搜索/收藏（`screens/ui-dark-search/`）与设置对话框（`20261029-ui0827/screens/ui-dark-dialogs/`）；仅高对比度主题仍待走查。
- [ ] 桌面拖放（拖入上传、拖到文件夹移动、拖出）尚未设计交互细节。
