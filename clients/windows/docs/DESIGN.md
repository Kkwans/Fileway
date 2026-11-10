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

- `canvas`：查看器的全幅画布，随应用主题切换（浅色主题下为浅色画布）；图片、PDF、文本、压缩包查看器全部跟随主题，只有视频播放器保留固定深色沉浸面。
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
- 窗口最小 1200×900 物理像素；内容宽度 < 960 DIP 时导航侧栏改为覆盖式（默认收起，标题栏菜单键打开，点遮罩或 Esc 关闭），搜索框宽度同时降为 160。
- 阅读器：内容列最大 780px，左右至少 48px 留白；段落间距 14px，标题上间距 16–22px。
- 详情面板：宽 420px，高 480px，内边距 20/16；操作区固定两组（传输、管理），每组 4 列等宽。
- 可移动详情浮窗：标题区（顶部 56 DIP，右侧预留 56 DIP 给关闭按钮）通过原生 `WM_NCHITTEST → HTCAPTION` 拖动，必须显示三点拖动手柄与“按住标题区域拖动移动窗口”提示；不得只依赖轮询鼠标按键。
- 无边框窗口的**隐形 sizing 边框与标题拖拽区**不得让单击进入系统移动/缩放循环：统一由 `Windowing/NonClientInputGate` 拦下 `WM_NCLBUTTONDOWN`（hit 2 与 10–17），按住并拖动超过 DPI 化 `SM_CXDRAG/SM_CYDRAG` 后才原样重放原始 press 启动原生循环（保留 Snap/resize）；16ms `WM_TIMER` 兜底检测拖动，抬起/失焦/取消模式/关窗/销毁必须清理 capture 与 timer。标题双击最大化交回原生；自定义最大化按钮 hit9 不接管。主窗/四查看器经 `FramelessWindowChrome` 接入，播放器主窗与可移动设置/详情浮窗直接接入（0.9.0 M02，替换 0.8.157 只门控 resize 的 EdgeResizeGate）。播放器 HUD 仍由自身按拖动增量 `SetWindowPos` 主窗口并补发 `WM_ENTERSIZEMOVE/WM_EXITSIZEMOVE`。最小尺寸用 `SM_CXMINTRACK/SM_CYMINTRACK`（34/35）。
- 查看器：顶部信息条 60px，底部浮动胶囊距底 22px；左右翻页按钮 44px 圆形，距边缘 14px。
- 查看器外壳三条硬规则（0.8.156）：① **不重复**——信息条不得再写标题栏已有的文件名，只放元信息（序号 / 像素尺寸 / 体积）。② **不空转**——没有解码出内容（加载中、错误态）时缩放胶囊整条隐藏，不得留「—」占位与一排无效按钮。③ **不躲**——加载与错误期间禁止 chrome 自动隐藏，用户要能看到工具条与重试入口。查看器画布/chrome 一律走带 `{ThemeResource}` setter 的 Style（`FwViewerCanvasStyle`/`FwViewerChromeStyle`/scrim 系列）：应用只设 `Shell.RequestedTheme`，运行时用 `Application.Current.Resources[...]` 直接取画刷拿到的是**系统主题**，WinUI 3 又没有 `SetResourceReference`。
- 任何工具条都不得改变内容尺寸；内容永远是画布，工具条是覆盖层。
- 本地服务端页：状态卡、本机构建、启动与账号、日志四块共用 20/16 页边距与 `FwFormCardStyle`；卡内动作按钮的右边缘必须等于卡片内容右边缘（窗口右 − 20 页边距 − 16 卡内边距），**不得用负边距“贴齐”卡片**——0.8.152 实测 `选择…`/`重新生成`/`显示密码` 用 `Margin="0,22,-16,0"` 会凸出内容边界 16 DIP。窗口 < 900 DIP 时「启动与账号」整卡换行占满两列，而不是把两张卡挤窄。日志/路径/端点使用 `FwCodeTextStyle`（等宽 13/21）与 `FwCodeCaptionTextStyle`（12/18）；状态只以图标 + 文案表达，错误才用 `danger`。
- 本地服务端页的三条内容规则（0.8.153，用户实机反馈）：① **先回显，再动作**——本机任何在监听的服务端（含不是本客户端启动的旧 `filebrowser.exe`）都必须出现在「检测到的服务端」卡里，带端口、PID、进程名与「打开 Web 端 / 连接」，不能假设"只有本客户端起的才算存在"（端口预填规则见下一条）。② **默认即全量**——共享范围默认是「整台电脑（所有盘符）」，限定单个文件夹是「高级」折叠区里的一个开关，未勾选时不得出现路径输入行；字段名统一叫「共享范围」，不再叫「共享文件夹」。③ **定时刷新不得整表重设**——日志列表、检测卡这类会轮询刷新的区域，内容没变时必须零 UI 变更（增量 diff + 按签名去重的状态事件）；直接重设 `ItemsSource` 就是用户看到的"每秒闪一次"。
- 「标签 | 值 | 操作」事实网格的五条硬规则（0.8.155，用户实机指出“最基本的对齐都没做好”）：① 标签和值**都必须**显式 `VerticalAlignment="Center"`——值不设就是顶对齐，只要同一行里有 36 DIP 按钮把行高撑起来，标签与值就会错开 12–14px（本机构建的日志目录行、启动与账号的用户名/密码行都中过）。② 同类行的值必须共享同一右边缘：**不要**在部分行上写 `Grid.ColumnSpan` 跨过操作列，那样有按钮的行反而比没按钮的行短一截、省略号位置不一致。③ 操作列宽度按该卡最多按钮数**固定**（如 `76` = 36+4+36），并让按钮 `HorizontalAlignment="Right"`，否则一行一个按钮、一行两个按钮时右边缘不齐。④ 上下堆叠的两张卡标签列宽必须相同（本机构建与启动与账号都取 84），否则两卡的值左边缘不齐。⑤ 空闲的 `ProgressRing` 必须 `Visibility="Collapsed"`——只把 `IsActive` 设 false 仍保留 20 DIP 占位并在头部留下淡色残影。
- 一台机器只允许一个服务端（0.8.154）：端口默认固定 8888 可改，但**不再自动跳端口**——换端口不能让第二个服务端合法存在，预填 8889 等于误导。约束要在**点之前**就讲清楚：检测到外来服务端时「部署并启动」直接置灰、部署卡说明改成单服务端规则；已部署但停止且外来实例在跑时，主按钮置灰并把**原因写进按钮本身**（文案「已有其他服务端」，读屏名“本机已有其他服务端在运行，无法启动第二个”）。被禁用的控件必须自己说明为什么，不能只变灰。同时「连接 / 打开 Web 端」对那个已有服务端保持可用——限制的目的是不重复起服务，不是把用户挡在门外。
- 本地服务端部署后可编辑（0.9.0 M03/M04）：部署卡双态（未部署=部署并启动；已部署=部署设置/保存并重启），端口默认 8888 可改；绑定地址只允许 127.0.0.1 或 0.0.0.0，LAN 开关必须带共享范围与防火墙入站风险说明，不自动改防火墙；共享范围/管理员账号/可选新密码同卡编辑，密码留空保留当前 secret。重配置走 `ReconfigureAsync`：停止 owned 进程→离线 `users update`→原子写文档/secret→同步启动项→启动→健康探测；失败必须恢复原运行态，未知结果不盲目重放。凭据卡显示 Valid/Stale/Unknown（`CheckCredentialAsync` 登录核对），LAN 地址由 `GetLanEndpointsAsync` 回显，UI 不拼服务器 URL。
- 主窗口顶层视图互斥（0.9.0 M01）：Workspace/ConnectionPanel/RecentPane/SearchPane/NasTasksPane/TrashPane/TransfersPanel/StorageToolsHost/CollectionsPanel/LocalServerPane 同一时刻只能有一个可见，唯一入口是 `MainPage.ShowPane(MainPane)`；导航、返回、连接请求、菜单快捷键不得手工拼装 Visibility。离开 pane 时由状态机统一 SuspendView/RetireStorageAnalysis；菜单/侧栏/标题/缩略图/选择态从状态派生。新增顶层页面必须加入 MainPane 与 ShowPane，并注册菜单可见性监听。
- 连接页：未连接时隐藏导航侧栏，整窗为欢迎页。内容为一组最大宽 1120、水平居中的两栏：左侧品牌栏（应用标识、34px 标题、说明、三行带图标的能力说明），右侧 440px 表单卡片（18px 圆角、26px 内边距）。窗口宽度 < 1000 时隐藏品牌栏、卡片占满；单栏模式必须把两栏网格的 `ColumnSpacing` 归零，否则卡片会相对窗口中心右偏（遗留列间距）。操作区（保持登录、主按钮、取消/返回）紧贴表单卡片下方、与卡片同宽同列，随表单一起滚动与居中；**不得使用与卡片不同宽度的独立窗口底栏**（边缘不齐），也不得让主按钮滚出视野。下拉与输入框统一左对齐。连接页字段使用外置 12px 标签 + 36px 无 Header 输入框，输入样式统一 `MinHeight 36`、`Padding 12,7`（带 Header 且 34px 时文字会顶对齐偏上）；**不得对带 Header 的输入框设置固定 Height**（会把标题与输入框一起裁切）。未连接时同时隐藏侧栏切换按钮（`PaneButton.Visibility` 随 `SetConnectionView` 切换），连接后恢复；1000×700 DIP 下连接页双栏、主浏览四列网格与传输中心均实拍无溢出（0.8.99）。

## Elevation & Depth

只有**浮动层**允许阴影与毛玻璃：

- 播放器顶部/底部工具条：有界 native region + SDK DesktopAcrylic Thin（Tint #101012、TintOpacity 0.72）恢复用户确认的高级模糊（0.9.0 M05）；其余查看器工具条 `scrim`/`glass` + 1px `glass-stroke` 边；不使用系统 Acrylic 的默认蓝灰。
- 浮动胶囊：`glass` + 16px 圆角 + 轻微投影（ThemeShadow，Z=24–32）。
- 对话框：18px 圆角 + `surface-raised`/毛玻璃 + 1px 描边；不允许直角或系统默认阴影。
- 静态内容区不使用阴影；用 1px hairline 表达层级。

## Shapes

- 圆角尺度：6（小标签）/8（输入与下拉）/10（按钮与磁贴）/14（面板与浮层）/18（对话框）/full（圆形翻页按钮）。
- 同类控件必须使用同一圆角：所有按钮 10，所有对话框 18，所有浮层 14，所有圆形按钮 full。工具栏 Flyout、右键/更多菜单、任务/回收站/收藏菜单与播放器菜单统一 14 + 1px 应用描边；WinUI 不应用应用级 `FlyoutPresenter` 隐式样式，`Opening` 设置 presenter style 也会被模板时序覆盖，必须用 `SurfaceStyles.Attach` 在 `Opened` 后从 `XamlRoot` 的打开弹层中定位同内容的 `FlyoutPresenter` 直接设置属性；`MenuBar` 菜单保留系统外观。
- 图标容器使用圆角方形（16px），不得用圆形裁切图标。
- 文件网格与列表中的类型图标使用 `FwTextSecondaryBrush`，不得用主文字色绘制大尺寸图标。
- 网格列宽按可用宽度分配（含紧凑列表的两列布局）：以偏好卡片宽为下限计算列数，再令 `ItemsWrapGrid.ItemWidth = 可用宽度 / 列数`。`ItemWidth` 是包含 `GridViewItem` 4px 左右外边距的槽位，不得再扣减 8；否则每列少 8 DIP，右端会累积明显尾隙（0.8.83 实测 1200 DIP 窗口尾隙约 100px）。计算前必须为垂直滚动条预留 10 DIP：滚动条出现后视口变窄，恰好整除的末列会被整体挤到下一行，右侧留下整列空白（0.8.110 实测 1600×1000 由 3 列恢复 4 列、最大化 7→8 列）。
- 页面内可关闭的 `InfoBar` 不得单独占用一个 Grid 行：空行仍会计入父级 `RowSpacing`，形成双倍间距。错误提示应放在操作行下方的容器里，无错误时把容器整体折叠（0.8.110 最近访问、0.8.111 文件任务）。文件任务工具栏中依赖选中项的“刷新详情/请求取消/重试/更多任务操作”必须同步禁用，“更多任务操作”不得在无选中时仍呈现为可用（0.8.111）。
- 搜索结果页的“搜索条件”行只在输入框内容与已提交条件不一致时显示；两者一致时不再重复查询词，编辑查询后仍显示结果对应的旧条件（0.8.110）。未提交查询前隐藏“打开”按钮，底部只显示“尚未开始搜索”，不得出现孤立的禁用“打开”或“· 0 项结果”（0.8.120）。搜索表单、动作行和状态提示必须放在同一个可折叠内容组内；未查询时动作行与提示整体 Collapsed，不得为固定 Grid 行保留 `RowSpacing`（0.8.121）。
- 网格卡片名称：占 40 DIP 两行预留区，单行名称必须在该区内垂直居中；不得把 `MinHeight=40` 直接放在 `TextBlock` 上让单行文字贴顶，否则名称下方会出现约 20 DIP 的空洞再接下一条元数据（0.8.94 用外层 `Grid MinHeight=40` + 内层 `VerticalAlignment=Center` 修复，UIA 名称矩形 60→30px 高）。
- 主浏览页筛选栏右侧的图标动作组与搜索框、状态栏使用同一右边界，不得额外内缩；左端与筛选按钮同左边界。
- 详细列表右列“大小 / 修改时间”（含列头）必须在 130 DIP 列内右对齐，与最近访问/搜索/文件任务的时间列一致。
- 主/次按钮、图标/文字图标按钮与分段/选项按钮统一使用自研双色圆角焦点环（`UseSystemFocusVisuals=False`，外层 `FwCanvasBrush` 2px + 内层 `FwTextPrimaryBrush`/前景色 2px）；图标/文字图标按钮的内层环取 `Foreground`，以适配视频遮罩等深色表面。
- 主按钮悬停/按下必须使用 `AccentFillColorSecondaryBrush`/`AccentFillColorTertiaryBrush` 保持实心强调色，禁止用半透明 `FwSelectionBrush` 替换背景（白字会失去对比度）。
- 复选框使用自研模板：20px 方框、6px 圆角、选中填充强调色并显示自绘勾线；不得直接使用系统 CheckBox 外观。
- 展开面板统一 `FwSurfaceBrush` + 1px `FwStrokeBrush` + 10px 圆角；滚动条使用 App.xaml 的 `ScrollBarThumbFill`/`ScrollBarTrackFill` 令牌（透明轨道、分级半透明滑块），高对比度走系统色。
- 主题偏好持久化在数据根的 `ui-preferences.json`（仅 `theme`），不写注册表或全局 AppData；文件缺失/损坏时回退“跟随系统”。
- 明确保留系统外观的控件：窗口菜单栏（`MenuBar`/`MenuBarItem`）与 `ProgressRing` 保留系统模板、键盘与无障碍语义，不再自绘；`InfoBar` 保留系统严重度图标与语义配色，浮层形态由 `FloatingNotice` 统一圆角、底色与超时行为。除此之外的按钮、选择器、弹层、列表、复选框、展开面板与滚动条均使用自研样式。

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
- 选中态使用 `surface-hover`，不使用系统蓝色高亮块；可选择的列表一律以 `FwListViewItemStyle` 为基底（含文件任务、搜索、最近访问、传输中心、收藏/标签、压缩包条目），行卡片使用 `FwSurfaceBrush` + `FwItemBorderThickness`（常规 1px、高对比度 2px）`FwStrokeBrush` 描边（浅色下白卡不再与页面同色）；选中时底色 `FwSelectionBrush`、卡片描边改 `FwSelectionStrokeBrush`，**不得再使用左侧 3px 强调条**（0.8.161 删除）。焦点只保留自研双色圆角焦点环（外层 `FwCanvasBrush` 2px 光晕 + 内层 `FwTextPrimaryBrush` 2px 环），并关闭系统焦点视觉；键盘聚焦时隐藏卡片描边，避免「选中描边 + 焦点环」两条轮廓线同时出现（0.8.161）。同一项任何时刻最多一条轮廓线，不得同时出现卡片描边、系统焦点矩形、自研焦点环与强调条中的两种以上。
- 文本型列表（最近访问/搜索/文件任务/操作记录）统一扫描路径：左 22px 类型图标，中间名称/路径/附加说明，右侧时间或“大小/类型 · 修改时间”摘要右对齐；时间使用 tabular numerals。列头必须与行摘要一致：搜索结果的右列是文件大小（文件夹显示“文件夹”）+ 修改时间，列头因此为“大小/类型 · 修改时间”（0.8.104）。页面级操作收拢到工具区右侧，不在列表上方留下孤立按钮。
- 文本列表列头使用 12px 次级文字，左列与首列主信息同基线，右侧时间列右对齐；空状态隐藏列头。列头的左/右内边距必须按行内容盒实测：回收站 66/18、主浏览详细列表 62，不得沿用目测值或清单里未落地的理论值。
- 压缩包浏览（0.8.162）：列头与行共用同一套列宽（名称 `*`、大小 96、类型 96、修改时间 150，间距 12）；行内容盒为左 4 + 图标 28 + 间距 12，列头左内边距因此是 44。列头右内边距必须等于行右内边距 8 + **实际滚动条宽度**（从内部 `ScrollViewer` 的 `ActualWidth - ViewportWidth` 取，不得写死），否则有滚动条时类型/时间列会漂移。空目录隐藏列头并显示 72px 图标卡 + 标题 + 说明；面包屑右侧显示当前目录 `N 个条目 · 文件合计大小`，窗口宽度 < 760 DIP 时隐藏摘要；状态栏只在选中数 > 0 时追加 `已选择 N 个`，空目录只显示 `0 个条目`。目录没有显式 zip 条目时修改时间显示 `—`，不得用子文件时间冒充。列头文字必须使用 `FwCaptionTextStyle`（高对比度下映射系统文本色），不得用 `Opacity` 降低对比度（0.8.168）。
- 压缩包解压（0.8.163）：目标目录必须可编辑并可“浏览…”选择（默认 `<下载目录>\archive-extract\<包名>`）；确认前校验绝对路径、合法字符且不能是已存在文件，非法时对话框内红字提示并**不关闭**。嵌套条目写盘前必须创建父目录；同名文件跳过且不覆盖。解压中显示 6 DIP 进度轨道与 `正在解压 N/M`，完成后显示逐条结果（已解压/已跳过/失败原因）与汇总；结果列表只读，不得参与主列表选择。进度条的中途实拍必须在大量文件或真机验收时补齐，不能只用最终汇总代替。
- 收藏与标签页（0.8.165）：分组过滤的“分组不可用”只在确实存在指向已删除分组的收藏时出现，健康账号不得常驻该诊断项；标签模式下账号没有标签时只显示顶部“暂无标签”一个空状态，必须隐藏下方 `TagResourcesArea` 的“选择标签”区，有标签但未选中时才显示“选择标签”。选中具体分组后“更多分组操作”才启用；选中标签/收藏行后“编辑/移除”可用，打开仅对可导航资源可用。

### Viewers

- 顶部信息条：文件名 14/600 + 一行元信息 12/muted；2.6 秒无操作自动隐藏。
- 底部胶囊：图标/文字按钮 34–40 高，间距 2，分割线 1px `glass-stroke`。
- 图片/PDF：滚轮以光标为锚点缩放，双击切换适应/放大，拖拽平移，Esc 关闭。
- 文本：工具栏固定，不自动隐藏；源码与渲染切换不得丢失滚动位置以外的最小状态。
- **每个查看器窗口都必须能用 Esc 关窗**（图片 0.8.131 起、文本/压缩包/PDF 自 0.8.160）。
  窗口根接 `PreviewKeyDown`，不要把这类全局键挂在某个内容控件上：0.8.159 之后初始焦点在根容器，
  挂在内容控件上的处理器收不到键。文本窗口在**编辑态必须放行按键**，否则 Esc 会绕过
  “放弃未保存修改？”确认框直接丢掉改动。确认框打开期间 Esc 只关框不关窗（压缩包吃这条，
  用 `_confirming` 标记挡住关窗分支）。
- **键盘导航不依赖列表拿到焦点**：独立窗口里 `ListView.Focus()` 在刚激活时常返回 false，
  因此压缩包列表由根容器把 `Down/Up/Home/End` 翻译成 `SelectedIndex`（无选中时 Down 从首行、
  Up 从末行进入，与资源管理器一致），`Enter` 执行与双击相同的动作（目录进入、文件预览），
  `Ctrl+A` 全选走 `KeyboardAccelerator`（`Modifiers` 是 `VirtualKeyModifiers` 标志，不是集合）。
  解压不再由 Enter 触发，统一走「解压选中/全部解压」按钮（0.8.164）。
- **压缩包预览**（0.8.164）：双击/Enter 文件时，文本类复用 `TextWindow`（只读，`Edit=null` 使编辑按钮禁用），
  图片类复用 `ImageWindow`；字节从当前压缩包会话读取（文本 4 MB、图片 32 MB 上限），不得重新下载压缩包，
  不得写入最近访问。目录仍进入；不支持的类型提示「该类型暂不支持预览，可解压后打开。」，
  不得假装支持或自动解压。预览窗口初始主题跟随压缩包窗口。
- **文本阅读器按键滚动**：`Up/Down` 一行（24 DIP）、`PageUp/PageDown/Space` 约九成视口、
  `Home/End` 首末，目标取当前可见面（Markdown/CSV 用各自的 `ScrollViewer`，纯文本取只读编辑器
  内部的滚动面）。不要为了拿到原生滚动而聚焦只读 TextBox——它会画出 3px 强调色下划线（0.8.159）。

### Icons

- 只使用仓库内 Lucide 单色 PNG（`Assets/Lucide/light`），尺寸 16/18/20/22/30；颜色继承 `Foreground`。
- 不再混用 `SymbolIcon`/Segoe 字形；新增图标必须先补 Lucide 资源再使用。
- 同一个动作在全局使用同一个图标名（下载 `download-simple`、回收站 `trash`、更多 `dots-three`）；不同侧栏入口不得复用同一图标（文件任务 `file-code`、存储工具 `gear`、本地服务端 `server`、连接 `floppy-disk`）。0.8.152 起资源共 41 个（新增 `server`/`activity`/`terminal`/`alert-triangle`/`hard-drive`/`power`），由 `eng/Import-LucideIcons.cjs` 从固定 commit 复现，旧 35 个逐字节不变。

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
- 所有图标按钮必须有 `AutomationProperties.Name`；每个查看器窗口都提供键盘路径：Esc 关窗，
  图片另有方向键翻页/缩放与 `0`/`1`，PDF 另有左右翻页，文本另有滚动键，压缩包另有选择与确认键（0.8.160）。
- 语义状态不能只靠颜色：错误同时给出图标与文案。
- 高对比度下禁用玻璃与阴影，回退为系统色实底。

## Implementation Mapping

- 令牌与控件样式：`clients/windows/src/Fileway.App/Styles/Design.xaml`（`FwCanvasBrush`、`FwSurfaceBrush`、`FwGlassBrush`、`FwPrimaryButtonStyle`、`FwIconButtonStyle`、`FwActionTileStyle`、`FwPillStyle` 等）。
- 查看器外壳：`Controls/ViewerChrome.cs`（顶部条、底部胶囊、自动隐藏、加载/错误层）。
- 播放器 HUD 材质为有界 region acrylic（0.9.0 M05 恢复 e3610896 方案，用户确认 HUD 与原生模糊菜单两者都要）；渐变 scrim 仅作为不支持 acrylic/HC/透明效果关闭时的回退；全屏时标题、时间与控件必须落在有效暗化区域内，白字不得直接压在彩条/高亮画面上。
- 对话框排序：`Controls/DialogOrdering.cs`，由 ContentDialog 隐式样式自动启用。动作按钮可能被包裹，且对话框内容（如 InfoBar）也可能含同名 `CloseButton`；必须先在 `CommandSpace` 动作面板内解析 Primary/Secondary/Close，再交换共同容器中的直接子元素或 Grid 列，不得按整棵视觉树按名字查找。高内容对话框的滚动区统一 `DialogOrdering.ContentMaxHeight(anchor, preferred)`，不得写死 400/480；批量下载确认框与下载另存为的文件项使用“名称 · 大小”13px 主色 + 目标路径 12px 次级，并按“将添加/未添加”分组，文件项与失败项不得使用同一视觉层级；对话框打开期间收起“正在核对下载目标”浮条。
- 文件写入类代码对话框（新建文件夹/空文件、单个重命名、批量重命名、移动到/复制到）：输入框统一 `FwTextBoxStyle`，说明/校验统一 12px 次级色；批量重命名给出命名规则提示，列表与确认摘要用 `DialogOrdering.ContentMaxHeight` 自适应；主按钮文案按动作使用“创建/重命名/预览/提交任务”，不得统一成“确定”。
- 工具栏选择器：排序/类型/视图统一使用 `DropDownButton` + `FilewaySelectorButtonStyle`（左对齐、12px 内边距），菜单项用 `LayoutChoiceStyle`；单选项选中后收起菜单。不要再让原生 `ComboBox` 承载工具栏选择器（其选中文本带原生内缩）。选择器必须使用自研完整模板：36 DIP 高、1px `FwStrokeBrush`、10 DIP 圆角、`UseSystemFocusVisuals=False` + 双色圆角焦点环、Lucide `caret-down` 12 DIP 箭头，不得沿用系统模板的黑色焦点矩形。按下态不得引用 `ControlFillColorQuaternaryBrush`（应用主题没有该键，会抛启动 `XamlParseException`），统一用 `SubtleFillColorTertiaryBrush`（0.8.112）。
- 分段选择器（`LayoutChoiceStyle`）：选中态必须画在独立的 `SelectionFill` 层，并通过 `FwBoolToVisibilityConverter` 绑定 `IsChecked` 控制显隐；不得在 `Checked` 视觉状态里改写 `Root.Background`。同一属性同时承载选中与悬停会出现两个缺陷：取消选中后旧项残留选中底色（UIA `ToggleState` 已为 Off 仍显示蓝底），以及悬停时选中色被盖住（0.8.92 实拍复核）。
- 侧栏空提示使用完整短句；文案换行后不得只剩 1–2 个字（标签空提示主动断为“尚无标签。/ 选中资源后可直接标记。”两行）。
- 空状态：居中 72px 圆角图标容器（`FwSurfaceBrush` + 1px `FwStrokeBrush`）、20px 标题、13px/20 行高说明；兜底“重新加载”使用 `FwSecondaryButtonStyle`，不得使用系统默认按钮。空列表同时隐藏列头。所有页面空状态必须有标题 + 说明两层，文案不得出现“暂无 文件任务”这类多余空格。回收站空状态必须覆盖文件和文件夹（“删除的文件和文件夹会先出现在这里，可在彻底删除前恢复”），不得只写“删除的文件”（0.8.105）。空文件夹/空收藏标签的底部状态栏只显示“0 个项目”，不得继续提示“已选择 0 个 · 双击或按 Enter 打开”（0.8.114）。有内容但未选择时也不显示“已选择 0 个”，只在选中数量大于 0 时拼接“ · 已选择 N 个”（0.8.119）。
- 页面头部操作：标题右侧的图标按钮统一 36×36（回收站恢复/刷新、最近访问刷新、传输中心刷新、收藏与标签的返回/刷新/收藏/标签/更多等），不得使用 34 或其他零散尺寸；标题 22px、说明 12px、标题块间距 4。文件详情浮窗的“打开”主按钮与文件任务的“更多任务操作”也统一 36 DIP（0.8.113）。
- 文件任务窄布局（内容宽度 < 880 DIP）：选中任务/操作记录后隐藏列表，详情占满内容区，标题行右侧显示“返回列表”；宽布局保留右侧 340 DIP 详情栏且隐藏返回按钮。
- 文件任务与操作记录共用列头时，两种行必须使用同一几何：24px 图标列 + 行内 `Padding=8` + 列间距 12，列头标题 `Margin=36` 才成立；操作记录行不得省略图标列去对齐有图标列的列头（0.8.166）。操作类型图标按复制/移动/取消/重试/取消归档映射，未知用 `clipboard`。
- 回收站等可移动详情浮窗同样使用三点手柄 + 原生 caption 拖动；关闭/恢复/删除使用自研图标按钮，不得使用系统默认按钮。回收站详情需与文件详情一致：56px 图标磁贴 + 17px 名称 + 12px 类型，信息区用 12px 次级标签 + 13px 值，大小/删除时间两列排布，动作按钮 36×36。
- 非破坏性确认框默认操作使用 `DefaultButton=Primary`；禁止用 `DefaultButton=Close`（WinUI 会给默认按钮套强调色，造成双蓝按钮），需要安全默认时用 `None`。`DialogOrdering` 在打开时统一把主按钮归一为 `FwPrimaryButtonStyle`、关闭/次要按钮归一为 `FwSecondaryButtonStyle`，确保“取消”为白底描边、主操作唯一蓝色。 上传/下载详情内的取消确认出现时，命令区的“暂停/继续”主按钮必须暂时隐藏或置空，确保“确认取消”是唯一蓝色主操作；返回或任务状态变化后恢复（0.8.108）。详情对话框打开时，同一任务的右下浮层必须隐藏，关闭后恢复并重置去重键；取消确认出现时“取消上传…/取消下载…”按钮直接收起，不得只置灰留在确认卡上方（0.8.109）。
- 传输任务详情（文件下载/文件上传）：操作按钮必须与应用一致——次按钮用 `FwSecondaryButtonStyle`（36 DIP、圆角），取消确认中的主操作用 `FwPrimaryButtonStyle`；状态卡与取消确认卡用 `FwSurfaceBrush` + 1px `FwStrokeBrush` + 10 DIP 圆角，不得沿用系统默认按钮与描边。经 `XamlReader.Load` 构造的 XAML 字符串内写 `Style="{StaticResource ...}"` 不可靠，必须在视图构造函数中用 `Application.Current.Resources` 按资源键赋样式（0.8.91 实拍验证）。传输浮层消息按两行排版：第一行文件名，第二行阶段指标（如“服务器已确认 50 MB / 512 MB”），不得把数值与单位拆到两行；浮层内容变但阶段未变时不得因去重而丢失（0.8.109）。
- 收藏/标签：行状态仅在确有附加信息时拼接“ · ”，不得出现“未分组 ·”这类尾部分隔符。收藏页副标题描述页面功能（“管理当前账号的收藏、分组与标签；可从文件列表添加。”），不得把“未选资源”的操作提示当作页面说明；侧栏“收藏夹”分组必须在标题右侧提供 12px 次级“管理”入口（24 DIP 高、应用焦点环、读屏名“管理收藏与标签”），点击打开收藏与标签页，不得只依赖“工具”菜单（0.8.123）。代码创建的控件需要主题色时，必须使用带 ThemeResource 的 Style（如 `FwCaptionTextStyle`）作为内容样式，不得在构造时捕获 `Application.Current.Resources` 画刷；后者在深色下会保留浅色实例，导致文字几乎不可见（0.8.127 实测）。同一规则在 0.8.128 复验：交互与外观滑块标签由浅色 (83,90,100) 回到深色次级灰 (168,174,184)；回收站详情弹层改用 `FwCaptionTextStyle` 与 `FwIconTileStyle`（56×56、16px 圆角、`FwSurfaceHoverBrush` 底 + 1px `FwStrokeBrush`）。收藏行使用收藏图标（`heart`），标签资源行使用通用文件图标，不得把文件夹收藏画成文件（0.8.116）。收藏/标签列表的主名称使用 14px SemiBold，路径与分组状态 12px 次级色，与最近访问/搜索/任务列表一致（0.8.125）。分组“更多操作”按钮只在选中具体分组且可写时可用；筛选为“全部收藏”或标签模式时禁用，不得打开只有禁用项的分组菜单（0.8.117）。收藏页自动刷新（打开页面、连接后、数据变化）不得弹出成功提示，手动点击“刷新”才显示“已刷新收藏与标签。”；未确认写入的警告始终显示（0.8.119）。
- 传输中心：文件夹批次以批次卡片成组（32px 文件夹图标底、批次名、聚合进度、“x/y 已完成”、文件数与总大小），组内文件行左缩进 24px 且仍可点开单个任务详情；批次行只做汇总，不承载暂停/删除等任务操作。行内指标（速度/剩余/耗时）为空时必须折叠该行，不得为上传等无指标任务预留空白行。进度条必须放在 6 DIP 圆角中性轨道内（`FwStrokeBrush`），进度控件背景透明、指示条 4 DIP 居中；等待状态要显示完整空轨道，不得让 0% 只剩 1–2px 低对比度细线、100% 却变成文件名下方的强调色长线（0.8.95 修复）。文件上传/下载详情状态卡中的进度条同样使用该轨道（0.8.97）。传输中心已完成记录隐藏进度条，批次头全部完成后隐藏总进度；不可暂停/继续的任务隐藏暂停按钮，只保留删除（0.8.111）。进度轨道只在真正有进度时显示：下载为传输中/暂停/失败/取消，上传为上传中/暂停/失败/取消；等待网络、等待登录、同名冲突、校验/保存中不显示空或满格轨道（0.8.124）。空状态按页签写明“暂无下载记录/暂无上传记录”并给出对应来源说明（0.8.124）；空状态图标必须跟随方向/页签：下载 `download-simple`、上传 `upload-simple`，文件任务 `file-code`、操作记录 `clipboard`（0.8.126）。等待网络、等待登录、同名冲突等未传输状态的记录不得显示“0 KB/s · 剩余 — · 已用 …”指标行，只保留阶段与路径；指标仅用于正在下载、已暂停、已完成、失败或取消的任务（0.8.113）。文件任务详情进度条也必须在 6 DIP 轨道内，完成度 100% 的已完成任务隐藏进度条，不得以横贯详情区的蓝色长线代替完成状态（0.8.111）。
- 文件与文件夹选择器：未打包 WinUI 3 使用 WinRT `Windows.Storage.Pickers.FileOpenPicker`/`FolderPicker`，并通过 `WinRT.Interop.InitializeWithWindow.Initialize(picker, Microsoft.UI.Win32Interop.GetWindowFromWindowId(windowId))` 绑定主窗口；Windows App SDK 的 `Microsoft.Windows.Storage.Pickers` 版本在本机实测抛 `COMException`（接口已为另一线程整理），不得用于上传选择器。选择器异常必须显示专用提示（如“无法打开文件选择器”），不得归类为服务器连接失败（0.8.97）。
- 上传确认框：目标路径使用 12px 次级“上传到”标签 + 13px 值，说明文字使用 12px 次级色；不得用 14px 主色单行“上传到：路径”与同色说明竞争层级（0.8.98）。上传详情的目标路径可选中复制，进度条需有读屏名称。
- CSV 表格视图：列宽按查看器内容宽度除以列数自适应，限制 140–220 DIP；表头与数据行共用列宽。小表不得因固定 220 DIP 列宽横向溢出而裁掉末列，宽表仍允许横向滚动。
- 压缩包查看器：列表上方必须有 12px 次要色“名称 / 大小”列头，名称左内边距 58 DIP、大小右内边距 25 DIP，与条目列对齐；空目录隐藏列头。
- 文本查看器空文件：内容长度为 0 且非编辑时显示居中空状态（72px 圆角图标容器 + 20px 标题 + 13px/20 说明），不得只显示一块空白编辑框；进入编辑后隐藏，保存或取消后按内容重新判断。
- 设置类对话框：内容滚动区高度必须给对话框上下留出边距（缓存与存储使用 `DialogOrdering.ContentMaxHeight(this, 560)`，仍按窗口高度−240 自适应），不得让对话框贴住窗口边缘或被裁切；关闭/保存按钮固定底部。高内容弹窗用同一助手按窗口高度自适应（预留 240 DIP，下限 240 DIP），不要写死超过窗口可用高度的值。可编辑设置必须排在只读诊断之前，保证 900px 高窗口打开即可修改缓存根目录/容量/下载目录。可编辑字段使用 12px 次级外置标签 + 输入框，不得使用 `NumberBox.Header`/`TextBox.Header`：WinUI 自带 Header 在 150% DPI 下会与输入框顶边重叠，出现裁切残影（0.8.115）。内容超出首屏时滚动区必须保持常驻纵向滚动条（`VerticalScrollBarVisibility=Visible`），不得出现半行文字被裁切且没有任何滚动提示（0.8.116）。“当前生效”摘要必须使用 12px 次级标签 + 13px 值的标签/值行（缓存目录、容量、下载目录、自动继续），不得用一段混排长路径的文本；长路径必须单行省略并提供完整路径 Tooltip，不得换行成两行挤占编辑区（0.8.122）；区块标题 15px SemiBold；滚动内容底部留 16 DIP，按钮行上方加 1px `FwStrokeBrush` 分隔线，避免滚动边界像内容被按钮遮住（0.8.96）。
- 存储工具/设置类页面表单：说明 + 输入 + 主操作使用 `FwFormCardStyle`（`FwSurfaceBrush` + 1px `FwStrokeBrush` + 10px 圆角 + 16px 内边距）卡片，卡片最大 720 DIP、左侧对齐；说明文字 13px 次级色，路径输入框在卡内横向填充，分析结果与用户列表保留在卡外全宽。主题画刷不得在构造函数里用 `Application.Current.Resources[...]` 取值（会拿到浅色实例，深色下出现白卡、白字输入框不可见）；必须通过 XAML 样式中的 `ThemeResource` 声明（0.8.93 实测浅/深色）。代码构建的说明/字段标签同样适用；0.8.128 的滑块标签与回收站详情字段不得捕获 `FwTextSecondaryBrush`。
- 弹层：菜单/Flyout 使用亚克力背景（App.xaml 的 `FlyoutBackground`/`MenuFlyoutPresenterBackground`），ContentDialog 使用 `FwPanelBrush` + 1px `FwStrokeStrongBrush`；ToolTip 圆角 8、内边距 10×6、12px 字号、最大宽度 360，与菜单保持同一套浮层语言。ToolTip 的三色使用应用自有可变画刷（`FwToolTipBackgroundBrush`/`FwToolTipBorderBrush`/`FwToolTipForegroundBrush`，`StaticResource` 注入，运行时随应用主题更新），因为 ToolTip 的 popup 会按系统主题解析 `ThemeResource`，不能直接用它取 `FwPanelBrush`。
- 工具栏不保留原生 `CommandBar` 容器、溢出菜单或 `AppBarButton`：主浏览筛选栏使用 `ContentControl` + `StackPanel`，更多操作一律使用自绘图标按钮 + `MenuFlyout`，与文件任务、文件浏览、缓存设置保持一致。
- 共享控件样式（`LayoutChoiceStyle` 等）必须放在应用级 `Styles/Design.xaml`；页面级资源无法被独立 `UserControl` 解析，会在启动时抛 XAML 异常。
- 密码输入框不得呈现 WinUI 原生下划线外观：`FwPasswordBoxStyle` 作为 PasswordBox 隐式样式全局生效（10px 圆角、1px 描边、`MinHeight 36`、`Padding 12,7`）。对话框内的密码字段横向拉伸，与说明文字同宽左对齐，不设置固定宽度。
- 主浏览页搜索框：`AutoSuggestBox` 通过 `TextBoxStyle` 注入 `FwSearchTextBoxStyle`（基于 `AutoSuggestBoxTextBoxStyle`，10px 圆角、1px 描边、14px 字号），保留模板自带查询与清除按钮；高度 40，与路径栏、返回/上级/刷新按钮同基线。直接给 `AutoSuggestBox` 设置边框/圆角不会作用到内部 TextBox，禁止用这种方式“修样式”。
- 关于弹窗：品牌行 + 版本卡；两个链接按钮（项目主页与源代码 / 许可证）放在同一行（32 DIP 高、间距 8），不得纵向堆叠占两行（0.8.103）。
- 交互与外观对话框：480 DIP 两栏（左“提示与播放”、右“界面背景”，间距 24），说明文字置于栏下；ColorPicker 宽 228 DIP，仅在“自定义取色”时显示。1000×700 下全部设置必须完整可见（0.8.107）。两个时间滑块使用 12px 次级标签 + 右侧实时“N 秒”值 + 无 Header 的 Slider，拖动时数值即时更新；不得只给标题与轨道而不显示当前值（0.8.118）。
- 打开方式对话框：说明保持一行（系统默认应用会先下载到默认目录），分类两列间距 8，滚动区高度使用 `DialogOrdering.ContentMaxHeight`；1000×700 下七个分类必须完整显示，不得把最后一行切在控件中间（0.8.106）。
- 窗口状态：主窗口正常状态的大小/位置与 `maximized` 标记写入数据根 `ui-preferences.json`（camelCase，大小写不敏感，兼容旧 `theme` 文件），启动时按保存点所在显示器 `WorkArea` 钳制，越界坐标必须回到可见区；最大化关闭只更新标记、不覆盖正常 bounds，启动恢复 bounds 后再最大化（0.8.100/0.8.101）。首次启动默认 1120×760 居中。导航侧栏的展开/图标栏状态保存为 `sidebarCollapsed`，重启保持，覆盖式窄窗口开合不写入（0.8.102）。
- 主浏览顶部工具行：返回/上级/刷新使用 `BrowserToolbarButtonStyle`（40×40、10px 圆角），与 40px 路径栏、搜索框同高；路径栏内部的编辑按钮保持 32px 内嵌尺寸，不要为了对齐把它放大到超出 32px 列。
- 自研双色焦点环覆盖主/次/图标/文字图标/分段选项/侧栏导航按钮/详情动作磁贴与 CheckBox；这些控件一律 `UseSystemFocusVisuals=False`，由模板 `FocusStates` 显示光晕 + 内环。动作磁贴必须 `HorizontalAlignment=Stretch` 填满所在栅格列，不能按内容宽度收缩；CheckBox 焦点环画在内容外侧，避免压住 20px 勾选框与文字。菜单项键盘焦点使用应用双色（`FocusVisualPrimaryBrush`=文字色、`FocusVisualSecondaryBrush`=画布色，内缩 2 DIP），但形状仍由 WinUI 内部模板绘制为矩形。任务详情与回收站的展开面板使用 `FwDisclosure`（`LayoutChoiceStyle` 开关头 + 旋转箭头 + 自研焦点环），不再使用 WinUI Expander；开关头必须把标题绑定到 `AutomationProperties.Name`。
- 菜单快捷键：只对真实存在的快捷键设置 `KeyboardAcceleratorTextOverride`（复制 Ctrl+C、剪切 Ctrl+X、粘贴 Ctrl+V、重命名 F2、移入回收站 Delete、全选 Ctrl+A、刷新 F5、搜索 Ctrl+F），不得虚构未接入的组合键；标签由系统菜单模板右对齐渲染。
- 已知尚未迁移：部分弹层仍使用旧样式，按本契约逐步替换。
- PDF 查看器：工具栏使用底部浮动玻璃药丸（`FwPillStyle` + `FwIconButtonStyle` + `FwTextIconButtonStyle`，左/右方向键翻页，适应窗口按视口宽度计算缩放）。

## Known Gaps

- [x] 主浏览窗口工具栏/筛选/布局切换已按本契约重排（0.8.10，`screens/ui-browser/`）。
- [x] 文本列表排版已收敛（0.8.52）：最近访问/搜索/文件任务/操作记录统一左图标、中主信息、右时间/摘要；浅色与深色截图见 `20261031-ui0852/screens/`。
- [x] 网格铺满与工具区右边界已收敛（0.8.83）：五种布局按可用宽度分配列宽；1200/1500/1800/2400 实拍与 UIA 边界见 `20261031-ui0883/screens/after/`、`screens/layouts/`；卡片左右外边距各 4 DIP。
- [x] 回收站列表与列头已收敛（0.8.84）：真实移入/恢复流程截图见 `20261031-ui0884/screens/trash-selected.png`、`trash-empty-after.png`；列头与行内容盒左缘差 1px、日期右缘同为 1741px；主浏览详细列表列头同步左移 9px 对齐。
- [x] 批量下载确认框已收敛（0.8.85）：`20261031-ui0885/screens/after/` 的 1800×1250 与 1200×900 实拍显示主/次信息分层、分组与按钮完整；`正在核对下载目标` 浮条不再盖在模态框后。
- [x] 播放器 HUD 对比度已收敛（0.8.86）：真实彩条短片全屏前后帧见 `20261031-ui0886/screens/player-fullscreen.png`、`screens/after/player-fullscreen.png`；普通窗口 HUD 无回归。
- [x] 弹层圆角与主题已收敛（0.8.87）：浅色/深色工具区 Flyout 与右键菜单见 `20261031-ui0887/screens/final-light/`、`final-dark/`；修复前对照为同目录 `flyout-sort.png`、`context-menu.png`。
- [x] 下载另存为已收敛（0.8.88）：主按钮右置、输入框自研样式与预览卡层级修复前后见 `20261031-ui0888/screens/before/`、`screens/final2/`；`DialogOrdering` 已改为在 `CommandSpace` 内解析动作按钮。
- [x] 文件写入类对话框已收敛（0.8.89）：新建/重命名（`20261031-ui0889/screens/before/`、`after/`）、批量重命名与“移动到”同目录实拍；输入框/说明/主按钮文案统一。
- [x] 回收站详情浮窗已收敛（0.8.90）：真实移入/详情/恢复流程；前后实拍 `20261031-ui0890/screens/trash-before/`、`trash-after/`，浮窗高度 588→528 物理像素，信息层级与文件详情一致。
- [x] 传输任务详情已收敛（0.8.91）：真实下载记录打开“文件下载”详情，操作按钮改为应用次按钮、确认取消为主按钮，状态卡/确认卡改用应用表面与描边；前后实拍 `20261031-ui0891/screens/before/`、`screens/after2/`。上传详情同构修复，因夹具无真实上传记录未单独截图。
- [x] 分段选择器选中态已收敛（0.8.92）：`LayoutChoiceStyle` 改为独立 `SelectionFill` + `IsChecked` 绑定，修复切换后旧项残留蓝底与悬停盖住选中色；存储工具/传输中心/文件任务实拍与 UIA `ToggleState` 复核见 `20261031-ui0892/screens/final-tabs2/`，修复前对照见 `20261031-ui0891/screens/audit-0892/`。
- [x] 存储工具表单已卡片化（0.8.93）：三个子页说明与表单收进 `FwFormCardStyle` 卡片，说明 13px 次级、最大 720 DIP；结果/用户列表保持全宽。浅/深色实拍 `20261031-ui0893/screens/final2-light/`、`final2-dark/`；同批补验 0.8.92 分段选择器深色正常。
- [x] 网格卡片名称垂直对齐已收敛（0.8.94）：名称在 40 DIP 两行预留区内居中，单行名称不再贴顶；UIA 名称矩形 60→30px 高、y=393→408，元数据位置不变。浅/深色实拍 `20261031-ui0894/screens/after-light/`、`after-dark/`，修复前 `20261031-ui0893/screens/before-0894-light/`、`before-0894-dark/`。
- [x] 传输行进度条已收敛（0.8.95）：6 DIP 圆角中性轨道 + 4 DIP 指示条，等待状态显示空轨道、完成状态指示条嵌在轨道内；浅/深色实拍 `20261031-ui0895/screens/after-light/`、`after-dark/`，修复前 `20261031-ui0894/screens/before-0895-light/`、`before-0895-dark/`。不确定进度的动画未单独截图。
- [x] 缓存与存储对话框已收敛（0.8.96）：“当前生效”改为标签/值四行，区块标题 15px，按钮行补分隔线，首选内容高度 560 DIP；默认下载目录帮助文字不再半行裁切。浅/深色实拍 `20261031-ui0896/screens/final2-light/`、`final2-dark/`，修复前 `20261031-ui0895/screens/settings-audit/`、`settings-audit-dark/`。更小高度窗口仍需滚动查看“缓存占用”，未加常驻滚动条。
- [x] 上传选择器与详情进度已收敛（0.8.97）：WinRT picker + `InitializeWithWindow` 修复 `COMException` 导致的选择器不可用；真实 29 B/37 B 上传完成并由服务器确认，文件夹选择器可打开；上传/下载详情进度条接入 6 DIP 轨道。证据 `20261031-ui0897/screens/upload/upload-details.png`、`screens/folder-picker/folder-picker.png`、`screens/download-details/download-details.png`；根因日志见非交付构建 `20261031-ui0897-diag/probe-diag.log`。整棵文件夹树提交仍待真人验收。
- [x] 上传确认框层级已收敛（0.8.98）：目标路径改为 12px 次级标签 + 13px 值，说明 12px 次级；上传详情目标可选中、进度条有读屏名。前后实拍 `20261031-ui0897/screens/upload-confirm/`、`20261031-ui0898/screens/upload-confirm/`。文件夹选择器可打开并导航到测试目录，但内嵌 picker 的“选择文件夹”提交无法通过 UIA/鼠标可靠触发，整棵文件夹树上传与批次卡仍待真人验收。
- [x] 小窗口布局已复核（0.8.99）：1000×700 DIP 下主浏览路径栏/搜索/四列网格、传输中心行、连接页双栏均无溢出；连接页隐藏无效侧栏切换按钮，连接后恢复并可折叠为图标栏。证据 `20261031-ui0898/screens/narrow/`、`20261031-ui0899/screens/narrow/`、`narrow-connected/`。
- [x] 主窗口位置已保持（0.8.100）：正常窗口大小/位置写入 `ui-preferences.json`，重启恢复并按显示器 WorkArea 钳制；实测 1500×1050@40,20 恢复一致，深色主题保留，越界坐标钳制到 1060,478。证据 `20261031-ui08100/screens/bounds-theme2/`、`bounds3/`、`bounds-theme3/`、`offscreen/`。
- [x] 最大化状态已保持（0.8.101）：最大化关闭只写 `maximized=true` 并保留正常 bounds，重启直接最大化，取消最大化回到 1500×1050@40,20；再最大化关闭后 prefs 与主题保持正确。证据 `20261031-ui08101/probe-max.log`。
- [x] 侧栏状态已保持（0.8.102）：宽窗口折叠为图标栏后写入 `sidebarCollapsed`，重启仍为图标栏；展开后回写 false，主题与窗口尺寸不变。证据 `20261031-ui08102/screens/sidebar/`、`probe-sidebar.log`。
- [x] 关于弹窗链接行已收敛（0.8.103）：项目主页与许可证按钮由纵向改为同一行，弹窗高度缩短约 40 DIP；1000×700 浅/深色实拍 `20261031-ui08103/screens/about-light/`、`about-dark/`，修复前 `20261031-ui0899/screens/help-dialogs/about.png`。
- [x] 搜索结果列头已收敛（0.8.104）：右列由“类型 · 修改时间”改为“大小/类型 · 修改时间”，与文件大小/文件夹行摘要一致；1000×700 真实 `png` 搜索浅/深色实拍 `20261031-ui08104/screens/search-light/`、`search-dark/`。
- [x] 回收站空状态文案已收敛（0.8.105）：改为“删除的文件和文件夹…彻底删除前恢复”，与页头一致；1000×700 浅/深色实拍 `20261031-ui08105/screens/light/trash.png`、`dark/trash.png`。
- [x] 打开方式对话框已收敛（0.8.106）：说明缩短为一行、分类间距 12→8、滚动区高度自适应，1000×700 下七个分类完整可见；修复前后见 `20261031-ui08105/screens/tool-dialogs/open-with.png`、`20261031-ui08106/screens/open-with2/open-with.png`。
- [x] 交互与外观对话框已收敛（0.8.107）：由单栏 320 DIP 改为 480 DIP 两栏，ColorPicker 268→228，1000×700 下两个滑块、开关、两个背景选择器与说明完整可见；前后实拍 `20261031-ui08106/screens/appearance/appearance.png`、`20261031-ui08107/screens/light/appearance.png`、`dark/appearance.png`。
- [x] 取消确认双主按钮已修复（0.8.108）：确认框可见时隐藏命令区暂停/继续主按钮，只保留唯一蓝色“确认取消”；真实 512 MB 上传的 active/确认/已取消三态实拍 `20261031-ui08108/screens/cancel/`，修复前 `20261031-ui08107/screens/cancel-upload/upload-cancel-confirm.png`。下载详情同步。
- [x] 详情浮层与取消排版已收敛（0.8.109）：详情打开时隐藏右下重复浮层，关闭后恢复；浮层改为“文件名 / 指标”两行，单位不拆行；取消确认时“取消上传…”收起。真实 512 MB 上传的 active / 关闭后浮层 / 重开 / 取消确认 / 已取消实拍 `20261031-ui08109/screens/`，UIA 复核详情打开时“查看上传”不可见、确认态“取消上传…”不在按钮树中。
- [x] 网格末列与页面空白已收敛（0.8.110）：网格列宽预留滚动条占位，1600×1000 由 3 列恢复 4 列、最大化 7→8 列；最近访问去掉空错误行的双倍间距；搜索条件同源时隐藏重复行。前后实拍 `20261031-ui08110/screens/before/`、`screens/grid-after/`、`screens/after/`，UIA 行首列数 3→4、7→8。
- [x] 文件任务与传输完成态已收敛（0.8.111）：任务详情满格蓝色长线改为 6 DIP 轨道并在 100% 时隐藏；传输中心已完成记录隐藏进度条与暂停按钮，批次头同步；任务页去掉空提示行双倍间距，“更多任务操作”无选中时禁用。真实 7 条服务器任务与 4 条历史下载实拍 `20261031-ui08111/screens/` 与 `screens/final/`。
- [x] 工具栏选择器与其余布局已收敛（0.8.112）：排序/类型/布局选择器改为自研 DropDownButton 模板（双色焦点环 + Lucide 箭头），修复 `ControlFillColorQuaternaryBrush` 缺失导致的启动崩溃；紧凑网格 6 列、无界网格 5 列、详细列表、紧凑列表 2 列在 1600×1000 均铺满。前后实拍 `20261031-ui08112/screens/`、`screens/after/`，焦点环对比 `before-selector-crop.png`、`after-selector-crop.png`。
- [x] 等待传输指标与深色详情已收敛（0.8.113）：等待/冲突行折叠 0 KB/s 指标，详情“打开”与任务“更多任务操作”统一 36 DIP；深色网格、选择器焦点环、详情浮窗实拍 `20261031-ui08113/screens/browser-dark.png`、`selector-focus-dark.png`、`screens/details-after/`，前后传输对照 `20261031-ui08111/screens/final/transfer.png` → `20261031-ui08113/screens/transfer-after/transfer.png`。
- [x] 空目录状态栏已收敛（0.8.114）：0 个项目只显示计数，不再提示双击/Enter；真实空目录 `20261031-ui08114/screens/empty-folder.png` → `screens/after/empty-folder.png`。收藏页同分支已改，菜单自动化未取到截图。
- [x] 缓存设置字段标签已收敛（0.8.115）：容量输入框不再用 `NumberBox.Header`（150% DPI 与输入框重叠、出现裁切残影），缓存根目录/默认下载目录同步改为 12px 外置标签；前后裁剪 `20261031-ui08115/screens/cache-capacity-crop.png` → `screens/after/cache-capacity-crop.png`，完整弹窗 `screens/final/collections.png`。
- [x] 收藏页说明与行图标已收敛（0.8.116）：副标题改为页面功能描述，收藏行使用收藏图标；缓存设置滚动区常驻滚动条。前后实拍 `20261031-ui08116/screens/menu-click/collections-page.png` → `screens/collections-after/collections-page.png`，滚动条 `screens/cache-after/scroll-strip.png`。
- [x] 收藏分组操作状态已收敛（0.8.117）：无具体分组时“更多分组操作”禁用；选中收藏行后打开/编辑/移除启用，选中态为蓝底 + 3px 强调条。UIA 复核 `更多分组操作 enabled=True → False`，截图 `20261031-ui08117/screens/after/collections-selected.png`；宽窗口任务详情 `screens/tasks-wide/tasks-detail.png`。
- [x] 外观滑块数值已补齐（0.8.118）：两个时间滑块右侧显示实时“N 秒”，标签 12px 次级色；深色外观实拍 `20261031-ui08118/screens/appearance-after/appearance-dark.png`，修复前 `screens/dark2/cache-dark.png`（无秒数）。
- [x] 状态文案已收敛（0.8.119）：主浏览未选择时省略“已选择 0 个”；收藏页自动刷新不再弹成功提示，手动刷新保留。证据 `20261031-ui08119/screens/status/browser-none.png`、`browser-selected.png`、`screens/collections-toast/`。
- [x] 搜索初始空状态已收敛（0.8.120）：未查询时隐藏禁用“打开”，底部只显示“尚未开始搜索”。前后实拍 `20261031-ui08120/screens/search-initial.png` → `screens/after/search-initial.png`。
- [x] 搜索动作行间距已收敛（0.8.121）：表单/动作/提示合并为可折叠组，未查询时无固定空行；初始空状态上移约 20 物理像素，提交后动作行恢复。证据 `20261031-ui08121/screens/search-initial.png`、`screens/pages-after/search.png`。
- [x] 缓存设置摘要已压缩（0.8.122）：长路径单行省略 + Tooltip，首屏容量说明完整显示；深色前后 `20261031-ui08121/screens/cache-dark/collections.png` → `20261031-ui08122/screens/cache-dark-after/collections.png`。
- [x] 侧栏收藏管理入口已补齐（0.8.123）：收藏夹标题右侧“管理”按钮直达收藏与标签页；实拍 `20261031-ui08123/screens/browser-none.png`、`collections-from-sidebar.png`。
- [x] 传输空状态与进度轨道已收敛（0.8.124）：下载/上传空状态分页签文案；等待/冲突/校验状态不再显示空轨道。实拍 `20261031-ui08124/screens/transfer-after/transfer.png`、`transfer-upload.png`。
- [x] 收藏列表字重已统一（0.8.125）：名称 14px SemiBold，路径/分组 12px 次级；前后裁剪 `20261031-ui08125/screens/row-name-before.png` → `row-name-after.png`。
- [x] 空状态图标已按上下文收敛（0.8.126）：传输下载/上传、文件任务/操作记录各用对应图标；实拍 `20261031-ui08126/screens/transfer-after/transfer-upload.png`、`screens/operations-after/operations.png`。
- [x] 深色侧栏管理入口已修复（0.8.127）：改用 `FwCaptionTextStyle` 的 TextBlock 内容，避免构造期捕获浅色画刷；修复前 `20261031-ui08127/screens/browser-none.png`（几乎不可见）→ `screens/dark-final/browser-none.png`（可读次级灰）。
- [x] 深色外观滑块标签已修复（0.8.128）：标签与秒数改用 `FwCaptionTextStyle`，实测主色 (168,174,184)，此前为浅色 (83,90,100)；回收站详情同步改用样式并新增 `FwIconTileStyle`。证据 `20261031-ui08128/screens/appearance-dark.png`、`Probe-AppearanceDark.log`。
- [x] 深色文件操作对话框已修复（0.8.129）：新建/重命名/批量重命名/复制移动提示与下载另存为标签改用 `FwCaptionTextStyle`；下载预览卡改用 `FwPreviewCardStyle`（`FwSurfaceBrush` + 1px `FwStrokeBrush` + 8 圆角）。深色重命名/批量重命名提示实测 (168,174,184)。证据 `20261101-ui08129/screens/rename-dark.png`、`batch-rename-dark.png`。
- [x] PDF 查看器深色壳已修复（0.8.130）：页面卡、页码、工具栏图标/文字/分隔线不再捕获浅色画刷，改用 `FwDocumentSurfaceStyle`、`FwBodyTextStyle`、`FwPrimaryIconStyle`、`FwDividerStyle`。修复前白卡深字 → 修复后深壳浅字；证据 `20261101-ui08130/screens-before/before-window.png` → `screens-after/after-window.png`。
- [x] 图片查看器深色错误卡已修复（0.8.131）：`ViewerChrome` 错误标题/说明改用 `FwBodyTextStyle`/`FwCaptionTextStyle`，修复前标题与暗色卡片同色不可见；证据 `20261101-ui08131/screens-before/before-window.png` → `screens-after/after-window.png`。
- [x] PDF 工具栏行高已收敛（0.8.132）：正文样式自带 22px 行高，紧凑工具栏应使用 `FwToolbarTextStyle`（13px/18px）。证据 `20261101-ui08132/screens/toolbar-window.png`，文字高 27px、基线 y=794。
- [x] 侧栏 ToolTip 已收敛（0.8.133）：展开/覆盖式侧栏标签可见时不显示同名 ToolTip，仅 72px 图标栏启用并右侧放置。证据 `20261101-ui08133/screens-tips2/expanded-final.png`、`rail-final.png`。
- [x] 连接按钮 ToolTip 已同步（0.8.134）：底部“切换连接”跟随侧栏规则，展开无浮层、折叠右置且文案随会话更新；证据 `20261101-ui08134/screens/expanded-connection.png`、`rail-connection.png`。
- [x] 下载确认字段卡已统一（0.8.135）：单文件“确认下载位置”使用 `FwPreviewCardStyle` + 12px 标签/13px 值，路径可选中换行；证据 `20261101-ui08135/screens/direct-download.png`。
- [x] 批量下载不再重复目录（0.8.136）：同目录时顶部单次“保存到”，条目行仅名称与大小；不同目录回退逐条路径。证据 `20261101-ui08136/screens-after/batch-download-dialog.png`。
- [x] 预览卡圆角已统一（0.8.137）：`FwPreviewCardStyle` 8→10，与表单卡/按钮同属 10px 圆角；证据 `20261101-ui08137/screens-download/direct-download.png`。
- [x] 收藏页头部操作间距已统一（0.8.138）：操作组 4→8 DIP，与其它页面头部一致；证据 `20261101-ui08138/screens/collections.png`。
- [x] 收藏页返回入口已统一（0.8.139）：由箭头图标改为“返回文件”`FwSecondaryButtonStyle`，与最近访问/任务/传输/存储一致；证据 `20261101-ui08139/screens/collections.png`。
- [x] 完成态进度线已隐藏（0.8.140）：传输详情“下载已完成”不再显示满格蓝线，完成态折叠 6px 轨道；证据 `20261101-ui08140/screens-after/transfer-clean.png`。
- [x] 上传详情同步完成态规则（0.8.141）：`UploadStatus.Completed` 折叠进度轨道；真实 29 B 上传记录实拍 `20261101-ui08141/screens/upload-detail.png`。
- [x] 存储工具返回入口已补齐（0.8.142）：页头右侧“返回文件”次按钮，`CloseRequested` 接 `ReturnFromRemoteTools`；证据 `20261101-ui08142/screens/storage.png`。
- [x] 回收站返回入口已补齐（0.8.143）：页头操作组末尾“返回文件”，点击回工作区；UIA 验证返回后工作区恢复，证据 `20261101-ui08143/trash/trash.png`。
- [x] 菜单焦点圆角已收口（0.8.146）：四个菜单项样式加 `CornerRadius=10` 后系统焦点框随圆角绘制，无需替换模板；键盘实拍 `20261101-ui08146/screens/menu-focus.png`。 子菜单/单选项同批复核：`20261101-ui08147/screens/submenu-focus.png`、`submenu-open.png`。
- [x] 高对比度侧栏选中态已修复（0.8.147）：`FwNavSelected*` 主题资源 + `SelectionBorder`；HC 透明底 + 系统文本色 1px 圆角描边，常规主题填充。证据 `20261101-ui08147/hc3/browser-hc-expanded.png`、`normal/recent.png`；HC 已恢复。
- [x] 高对比度选中项已修复（0.8.148）：HC 下列表/网格选中填充透明、3px 标记用窗口文本色，文字/图标保持高对比；选择器用 `FwChoiceSelectionBrush` 保留系统高亮。证据 `20261101-ui08148/hc/browser-hc-selected.png`、`recent-hc-selected.png`；常规复核 `normal/browser-hc-selected.png`。 传输详情/文件任务/回收站空状态 HC 已补拍 `20261101-ui08149/screens2/`。 PDF 查看器 HC 已补拍 `20261101-ui08151/screens/pdf-hc-window.png`。
- [x] 高对比度主按钮已修复（0.8.149）：`FwPrimaryButton*` 资源在 HC 映射 WindowText/WindowColor，白底黑字；常规仍 Accent/TextOnAccent。证据 `20261101-ui08152/screens2/connection-hc.png`。
- [x] 高对比度复选框已修复（0.8.150）：`FwCheckBox*` 资源在 HC 映射 WindowText/WindowColor，选中为白底黑勾、20px 方框；证据 `20261101-ui08152/screens3/connection-hc.png`。
- [x] HC 复选框描边已加强（0.8.151）：`FwCheckBoxBorderThickness` 常规 1、HC 2；证据 `20261101-ui08153/screens-unchecked/connection-hc.png`。
- [x] 图片查看器主题已修复（0.8.156）：画布/chrome 不再钉死深色，改走 `{ThemeResource}` Style；浅色字典补 scrim 文字深色值。同时收掉顶部信息条与标题栏重复文件名、加载/错误期 chrome 误自动隐藏、无图时仍显示缩放胶囊、错误卡左对齐无图标四类排版问题。证据 `20261102-server08156/light9`、`dark2`（各 10 项 UIA 断言 + 4 张实拍）。
- [x] 文本与压缩包查看器已跟随主题（0.8.158）：撤销「刻意固定深色阅读面」的旧决策，与 0.8.156 对图片查看器的批评同源。`TextWindow.ApplyTheme`/`ArchiveWindow.ApplyTheme` 不再钉 `ElementTheme.Dark`，改赋 `FwViewerCanvasStyle`/`FwDocumentPanelStyle`/`FwDocumentNoticeStyle`/`FwCaptionTextStyle`，Markdown 代码块与空文件状态同步；正文/标题去掉显式 Foreground 由主题继承。同轮按该根因全 App 扫出并修掉 6 处构造期捕获主题画刷（图片查看器分隔线、CSV 表头、存储工具两张卡、Markdown 引用块边框与列表符号、错误图标）。证据 `20261103-viewers08158/light3`、`dark2`（各 8 项 UIA 断言 + 2 张实拍，浅/深均无同色文字）。
- [x] 查看器窗口初始焦点已交给内容（0.8.159）：修复前 UIA 实测 `TextWindow`/`ArchiveWindow` 打开后 `MinimizeButton hasFocus=True`，按 Enter/Space 直接把窗口最小化。现在三个文档查看器在加载完成后显式持焦于根容器（并在布局后补一次）。规则：**只读阅读面不要把焦点给 TextBox**——编辑器模板会画出强调色下划线，在圆角面板底部裁成一条 3px 蓝线，比焦点框更像故障（像素复核 2 行→0 行）；用户点进正文时仍会自然获得编辑器焦点与全选/复制。证据 `20261103-viewers08158/final2-light`、`final2-dark`（各 13 项断言全通过 + 4 张实拍）。
- [x] 压缩包查看器键盘导航与查看器 Esc 关窗已补齐（0.8.159 遗留 → 0.8.160 实测通过）：`_list.Focus()` 在刚激活的独立窗口里两次都返回 false，所以改由窗口根把 `Down/Up/Home/End/Enter/Ctrl+A` 翻译成条目选择与操作，同时给文本/压缩包/PDF 补上 Esc 关窗与文本按键滚动。浅色 `20261104-keys08160/light6`、深色 `dark2` **各 21 项真实按键断言全 PASS**（PageDown 像素差 10.79%、Home 回到首帧 0%、`ArchiveExtractButton` False→True 证明选中、两步 Esc 分别关框与关窗）+ 6 张实拍，安装包 `20261104-keys08160/dist`。
- [x] 选中态已收敛（0.8.161）：删除两个模板的左侧 3px `SelectionBar`，选中改为半透明底 + `FwSelectionStrokeBrush` 描边；`FwGridViewItemStyle` 补 `UseSystemFocusVisuals=False`；键盘聚焦时隐藏卡片描边、只留双色焦点环；压缩包列表改套 `FwArchiveListItemStyle`，不再出现 WinUI 默认蓝条。证据 `20261104-select08161/light3`、`dark3`（各 20 项 PASS，原条区高饱和像素 0）与各布局实拍。
- [x] 压缩包信息架构已收敛（0.8.162）：四列名称/大小/类型/修改时间；列头与行共用列宽，右内边距按实际滚动条宽度动态计算；面包屑摘要、空目录图标卡、状态栏选中数、导航后焦点交回内容。证据 `20261105-archive08162/light5`、`dark3`（各 27 项 PASS，四列 UIA delta=0）与根目录/子目录/空目录实拍。
- [x] 压缩包解压体验已补齐（0.8.163）：目标可编辑/可浏览、非法目标不关框；嵌套条目写盘前创建父目录；进度契约 + 6 DIP 轨道 + 逐条结果（成功/跳过/失败原因）；二次解压全跳过。证据 `20261105-extract08163/light4`、`dark2`（各 17 项 PASS）与修复前后对照；进度条中途实拍仍待大量文件/真人验收，未标通过。
- [x] 压缩包内预览已补齐（0.8.164）：双击/Enter 文件按类型复用 `TextWindow`/`ImageWindow`，文本只读、图片从会话字节解码；目录仍进入，不支持类型提示；Enter 不再触发解压。证据 `20261105-preview08164/light2`、`dark1`（各 22 项 PASS，文本窗口只读与内容断言、图片窗口与 `ImageView`、Esc 关闭、不可预览提示）。图片窗口的 GDI 屏幕抓取受 WinUI 合成/遮挡影响，不把 `preview-image.png` 当像素证据，图片画布待真机验收。
- [x] 收藏与标签页层级已收敛（0.8.165）：健康账号不再常驻“分组不可用”；标签模式无标签时只保留一个“暂无标签”空状态；真实创建/重命名/删除分组菜单与创建标签流程验证。证据 `20261105-collections08165/light4`、`dark1`（各 28 项 PASS）与三张实拍；分组菜单为全屏抓取，标签行黑框为程序化焦点伪影。
- [x] 文件任务/操作记录列对齐已收敛（0.8.166）：操作记录行补 24px 图标列与 `Padding=8`，与任务行共用列头几何。证据 `20261105-tasks08166/light4`、`dark2`（各 18 项 PASS，任务/操作列头左缘 delta=0、时间右缘 delta=1）与宽/历史/窄实拍；浅色历史图左上侧栏 ToolTip 为探针持焦伪影。
- [x] 回归收口与压缩包列头 HC 风险已处理（0.8.168）：列头由 `Opacity=0.65` 改为 `FwCaptionTextStyle`，HC 映射系统文本色；浅/深 archive 回归各 27 项 PASS、列对齐 delta=0。系统高对比度实拍仍未取得（需用户同意切换系统设置），不标 HC 通过。
- [ ] 自研双色焦点环在 UIA 自动化下必现（`Focused` 状态），鼠标点击不出现（`PointerFocused` 是空状态）：截图验收必须意识到这点，否则会把测试伪影当成产品缺陷；若连程序化聚焦也不想显示，需要按 `FocusState` 区分。
- [ ] PDF 查看器工具栏已迁移为玻璃药丸，待实机截图验收；沉浸外壳仍受原生堆破坏阻塞。
- [x] 弹层模糊决策（0.9.0 M05 覆盖 2026-10-29 回退结论）：播放器 HUD 恢复有界 region + SDK Thin acrylic，菜单/弹层用原生 DesktopAcrylicBackdrop（用户确认两者都要）；应用内 ContentDialog/ToolTip/toast 的内容级模糊仍待 M10/M11 合成模糊层统一交付，在此之前保持主题 Acrylic + 1px 高光边，不再反复调透明度。
- [x] 独立 Acrylic 窗口结论（2026-10-29）：三次原型均因定位/尺寸问题回退。复现要点：① `AppWindow.MoveAndResize` 使用物理像素，必须乘 `XamlRoot.RasterizationScale`；② 高度需为按钮行预留至少 72 DIP；③ owner 必须取 `App.MainWindow` 的顶层 HWND，`XamlRoot.ContentIslandEnvironment` 在部分路径下不可靠。若后续重启该方案，按此三点实现，不要复用详情面板的窗口。可移动详情浮窗的拖动已在 0.8.46 改为原生 caption 命中（浅/深色均实测 MOVED=True）。
- [x] 紧凑网格已实机复核（2026-10-29，`screens/ui-layouts/compact-grid.png`），容器状态正常；无界网格已复核（2026-10-29，`20261029-ui0815/screens/ui-grid/unbounded-grid-161844.png`），菜单选中态已改为半透明强调底。
- [ ] 拖到文件夹移动需真实鼠标验收；拖出到资源管理器未开始。
- [~] 文件夹“整组上传”（0.8.15 部分完成）：`UploadBatchInfo` 已随任务持久化并透传为 `X-Upload-Batch-*`/`X-Upload-Folder`，0.8.30 起传输中心按批次聚合展示；旧记录若仅有 `batch_id` 而 `record_json.Plan.Batch` 为空，按单文件显示（兼容回退）。仍缺真人端到端验收：2026-10-30 再次尝试打开“选择文件夹…”后枚举 UIA，探测脚本在模态选择器上阻塞超时，确认无法自动化。不能只改 UI 文案就算完成。
- [~] 主题走查：浅色已全量截图；深色已复核连接页、主界面（`20261029-ui0823/screens/ui-dark/`）、传输中心/回收站/存储工具/最近访问（`screens/ui-dark-panels/`）、搜索/收藏（`screens/ui-dark-search/`）与设置对话框（`20261029-ui0827/screens/ui-dark-dialogs/`）；2026-10-30 用应用内“视图→界面主题→深色”补验本批新增组件：文件任务卡片与选中态（`20261030-ui0835/screens/dark-nas.png`）、缓存与存储对话框（`20261030-ui0835/screens/dark-storage.png`）。深色 ToolTip 已于 0.8.50 补拍（`20261031-ui0850/screens/tooltip-dark.png`）；同日补验自研复选框深色选中/未选中（`20261030-ui0840/screens/checkbox-dark-checked.png`、`checkbox-dark-unchecked.png`）；0.8.42 主题持久化后补验深色搜索结果列表与滚动条（`20261031-ui0842/screens/theme-restart.png`、`dark-search.png`）、传输中心/收藏/最近访问（`dark-transfer.png`、`dark-collections.png`、`dark-recent.png`）；0.8.49 复核深色下对话框主按钮悬停与次按钮样式（`20261031-ui0849/screens/primary-hover-dark.png`）；0.8.44 复核深色下卡片描边与双色焦点环（`20261031-ui0844/screens/dark-search-focus.png`）及真实下载记录的传输中心（`dark-download-rows.png`）；0.8.51 补验图标/文字图标按钮的浅色与深色键盘焦点环（`20261031-ui0851/screens/icon-button-focus-light-crop.png`、`text-icon-button-focus-light-crop.png`、`icon-button-focus-dark-crop.png`、`text-icon-button-focus-dark-crop.png`），同时发现深色 ToolTip 弹层仍取浅色资源，列入 0.8.52；高对比度主题仍待走查。
- [ ] 桌面拖放（拖入上传、拖到文件夹移动、拖出）尚未设计交互细节。
- [x] 深色 ToolTip 主题（0.8.66 解决）：WinUI ToolTip 在独立 popup 上按系统主题解析 `ThemeResource`；强制 `RequestedTheme`、替换隐式样式、自定义模板与 Flyout 宿主均无效。0.8.66 改用应用自有的可变 `SolidColorBrush`（`StaticResource` 注入，`MainWindow.Appearance` 随应用主题更新颜色，高对比度用系统色），浅色、深色与应用内切换三条路径均实拍通过（`20261031-ui0866/screens/`）。
