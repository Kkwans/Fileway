# Android visual direction

## 1.1 候选：播放器与操作一致性

本节为当前约定，覆盖下方早期版本的固定播放器背景及图标快照。

- 非全屏视频画面之外使用封面取色的低饱和暗色氛围；缓慢流动只在前台播放且系统允许动画时运行。暂停或后台静止，全屏保持画面主导。原生 SurfaceView 与解码链不用于装饰采样。
- 亮度、音量、横滑、进度条、长按倍速和十秒跳转共用窗口局部实时玻璃反馈。反馈窗口边界匹配内容，先设置不抢焦点/不接收触摸再显示，保留拖动所有权；低版本与系统关闭模糊时降级为清晰的同色材质。
- 非全屏提供紧凑的视频信息，真实未知字段占位；主页面不展示冗余文件位置卡片。所在目录入口置于按需打开的播放信息中，跳转须真正结束当前播放并显示文件页。
- 系统画中画默认手动，自动进入是本机可选设置，默认关闭。使用同一播放会话及原生视口；返回保留进度、字幕、倍速与显示模式。
- UI 图标来源限 Lordicon System Outline 与 Material Symbols Rounded。普通控件24dp视觉槽位、至少48dp触区，普通页主蓝/语义色，播放器使用白灰语义色。动效只响应明确交互或状态变化，播放一次；静止帧必须可辨认。更多固定为四方块。
- 同一工具栏、菜单动作组、列表操作组只用一套图标来源，不能逐图标自动替换而混搭。当前免费许可下，五项底部导航整组用 Lordicon System Outline；普通操作组整组用 Material Symbols Rounded。新增／搜索／多选／更多保持同一24dp视觉槽位。Lordicon Plus 属于 PRO，未取得许可时不接入。
- 多选的进入/退出始终位于同一个顶部槽位；保留搜索、清空、更多等原有入口。下方为计数、全选、反选与批量动作，不再提供另一个退出叉。清空仍使用原有范围及确认语义。零项仍可保持多选状态。
- 存储报告使用返回标题栏、摘要、范围及紧凑排名；目录排名按同组最大值归一，不能把重叠目录当成互斥分区。截断、跳过、不完整身份和清理风险必须可见。
- README 以中文为默认并配套英文；真实截图注明其版本，下载链接保留已公开正式版。1.1候选不自动发布。

## 1.0 当前交互与详情规范

以下规范覆盖文档中早期预览版本的手势与几何快照：

- 普通卡片、控件、菜单及面板使用12dp圆角，操作触区至少48dp。
- 浏览列表单击详情、长按多选；可打开的文件和目录双击打开。多选时单击切换选中，双击不打开；回收站及仅含操作记录的条目无双击打开。
- 最近播放、最近访问各自提供标题栏右侧的多选、清空入口；批量删除只处理对应记录，不删除媒体文件。清空需要确认。
- 详情先展示完整框架，未知字段占位；数据在原位置回填，未知类型时不启用文件写入动作。保持首次布局的视口高度，内容可内部滚动；窗口、字体或系统边距变化时重新适配。
- 详情底栏使用等宽图标操作按钮，单按钮占满整行；保留右上角关闭，不再重复设置底部关闭按钮。
- 传输文件图标与右侧更多操作图标均24dp，置于原有48dp槽位内，卡片保持紧凑间距。


User requirement: modern, beautiful, refined UI is a release gate, not optional polish.

## Subject and signature

A personal file and film library. Let actual covers and filenames lead. The signature is a disciplined media row: a large proportional cover, readable two-line title, quiet technical metadata, and a precise resume marker. File navigation remains fast and calm.

Do not use generic dashboard statistics, oversized gradients, repeated card containers, fake media, or development terminology in product flows. Native Material components are primitives, not the finished visual design.

## Current library and connection tokens

| Role | Light | Dark |
| --- | --- | --- |
| Canvas | #F5F5F7 | #202023 |
| Surface | #FFFFFF | #141416 |
| Primary text | #202023 | #F3F3F6 |
| Secondary text | #63636D | #B5B5BE |
| Accent | #1767E8 | #69A8FF |
| Divider | #E9E9ED | #34343A |

Native sans family with deliberate roles: 20sp page title, 22sp connection introduction, 16sp medium file title/body, 13sp metadata; tabular numerals for times and sizes. Respect system font scale. Normal text contrast ≥4.5:1.

Spacing: 4/8/12/16/24/32dp. Connection page gutters and section padding 16dp; media page gutters 12–16dp. Corners: 10dp on category/form cards and 8dp on their inner controls. Outline icons form a consistent family; controls ≥48dp.

## Connection alignment and system navigation

The connection page owns one 16dp gutter. Address/account fields, connection-mode
controls and the Tailscale section share that same left/right boundary. Network
content must not add a card with another 16dp inset. The status row, explanation,
full-width network action and errors belong to the form's existing grid. Cancel
and disconnect stay directly reachable; confirmed account logout is a secondary
menu action. IPs and health details expand only when requested.

Use the approved bright blue identity, matching the layered storage icon. Primary
server login stays in the bottom action area; network setup uses a tonal action.
Check unconfigured, connecting, login-required and connected states in both
palettes. Assert actual heading/button geometry, then inspect the rendered page;
a passing action test alone is not visual acceptance.

Compose draws the page/bottom surface through the system gesture area, with safe
insets protecting touch targets. Disable the Activity's extra navigation contrast
scrim. Preserve the native system gesture handle and correct light/dark icon mode.
The owner's input method owns its own window/navigation styling; verify that state
separately instead of calling a transient IME inset or an absent keyboard screenshot
full keyboard acceptance. Switching connection modes clears editing focus.

## Layout

Unbounded grid now follows the user's Xiaohongshu waterfall reference: two independent columns in compact windows, adaptive columns on larger windows, natural image height from the loaded preview's intrinsic ratio, and complete left-aligned filenames with no line clamp or ellipsis. Existing 10dp cards remain. Thumbnail Fit preserves the entire image; this mode omits size/time rows and keeps long-press details. The earlier fixed-slot/two-line prototype did not meet this requirement and is superseded. Account/source-scoped layout persistence retains the stable UNBOUNDED value.

File metadata comes from the existing NAS `modified` field. Regular rows keep size and local modification time on one quiet metadata line; cover/detail cards provide a time line, while compact cards retain their density. Shared selectable details use 12dp corners, the normal page title, secondary field labels and primary-color values for complete name, root-based path, source type, known size and modification time. Missing dates remain explicitly unavailable; directories have no fabricated aggregate size. Search reuses the same details and retains its open/close actions.

Files: one compact toolbar with layout selection, refresh and right-side search icon → directory breadcrumb when below root → content. Server/account details and switching belong in settings. Search context shows only the root-based directory path. Folders share the selected grid/detail card geometry with media, using the pinned folder vector on a quiet accent surface; they do not request fake previews. Grid cell size responds to width, retains readable titles and never stretches previews. Grid caption slots include actual CJK/Latin font metrics so folder and file card bottoms align.

Recent: real resume entries, no fake hero. Server editor: a focused sheet/form with field examples, a visible connection mode and one primary action. Settings: grouped rows with clear current values.

Player: black video stage, unambiguous source title, restrained floating controls. Subtitles keep their own visible area; timelines and track sheets have space to breathe. Buffers/failures use inline feedback, not stacked toast overlays.

Motion: 160–220ms focus/navigation transitions; respects system animation scale. No entrance spectacle. Light/dark, empty/error/loading/buffering and large-font layouts receive equal scrutiny.

Connection implementation: one concise introduction, separate rounded server/account cards, complete address first, optional profile name and compact accessible network choices. The scrollable form is bounded to 560dp on larger screens; primary connect and secondary save actions stay in a bottom surface above the keyboard. Four fields fit the ordinary-scale portrait viewport on the owned API35 device. The same library pink/neutral palette applies to chips and embedded-network actions; no residual blue selection style. Tailscale presents one prominent official-login action when its URL exists, plus cancellation and sensitive-link copying. Official Material outline vectors are pinned and attributed under `licenses/`.

## Visual gate

Inspect actual rendered Android screenshots for each main page, both themes, compact/landscape/≥600dp at default font scale. The user explicitly excluded further 130%/200% checks; historical enlarged-font results below remain historical evidence, not a requirement to repeat them. Capture controls visible/hidden, long filenames, all errors and track sheets. Fix clipping, weak hierarchy, density and inconsistent spacing before release. Emulator images validate UI only; hardware decoding/HDR needs devices.

## Player revision: Bilibili / Artplayer direction

The user rejected the prototype's large default buttons, thick blue footer and unused portrait space. Keep Compose as the native rendering/interaction toolkit; Material's default component styling is not a product design requirement. Official Compose custom-design-system guidance supports independent colors, typography and components. Traditional Views can be embedded when a proven component fits; switching rendering frameworks alone does not improve hierarchy or spacing.

Primary design reference: https://artplayer.org/document/ and the MIT-licensed Artplayer source pinned in `licenses/artplayer.md`. Reuse its actual play/pause/volume/fullscreen/check/close vectors, compact control composition, thin progress and accent/check selection. Reuse Compose's tested slider/dialog/selectable interaction and accessibility instead of introducing a custom gesture/keyboard framework. Artplayer itself is a JavaScript/HTML5 player, not a Kotlin UI library; do not replace native libVLC to import its DOM.

BiliPai is a third-party Compose video app, not official Bilibili source. Its player UI contains application-specific account, danmaku, video-quality and Media3 integration. It is a reference, not a drop-in independent libVLC controller. General component libraries such as Miuix are also not complete video-control systems; no blanket UI-framework migration is needed for this slice. No claim is made about current proprietary Bilibili/YouTube/iQiyi/Tencent implementation details.

Portrait: proportional video at the top, compact transport, then complete filename, quiet server identity, real playback metadata and track rows. Landscape: large native viewport, gradient title, thin timeline and a single compact transport/options row. The native subtitle viewport reserves the transport area so PGS cannot sit under controls; viewport size stays stable on idle HUD hide.

Player palette: canvas #141416, panel #202023, white controls, secondary #B5B5BE and one pink accent #FF80A6. Use 22dp icons inside 48dp targets, a 3dp timeline with a 10dp thumb inside a 48dp interaction region. Selection uses a subtle accent fill and right-side check, without oversized radio controls or a Material drag handle. Portrait menus emerge from the bottom; landscape menus occupy a bounded right panel. Preserve real state, IDs, duration, accessible slider semantics and reduced interaction hiding under TalkBack.

This is an implemented direction, not visual acceptance. Actual screenshots, rotation, control visibility, native picture, track interaction, large-font clipping and foreground recovery must be checked before release.

## Search page

Current search controls: the first row keeps back, an outlined 8dp input with the pinned official search vector, and a 48dp submit icon. Current directory, recursive directory and all-files tabs have equal widths and centered labels. All-files search uses the existing authenticated root recursive request, while the original entry directory is retained for scope changes. Result locations show root-based paths. Account history and shared file-view modes remain pending; the empty page is not populated with fabricated suggestions or local history presented as service persistence.

Search follows the player's restrained pink direction with a white light canvas and charcoal dark canvas. It inherits the app theme rather than independently forcing system color mode. Accent: #C63262 / #FF80A6; secondary text: #63636D / #B5B5BE. Measured text-on-canvas contrast: 5.21/7.79 for accent and 5.94/9.04 for secondary text. Native sans uses the existing 16sp body/file title and 13sp metadata roles. The result title remains two lines with full name/location in a scrollable long-press detail dialog.

Use a compact BasicTextField/IME search action, 48dp clear/back targets, flat underlined scope controls and a quiet result row. Header context/status/details belong to the scrolling results area so landscape and 200% fonts remain usable. Result status reflects actual emitted rows and termination; no invented popular keywords, covers or traversal percentage. Reuse the pinned Material folder vector and existing Artplayer close icon, with original licenses; Compose input/selection/dialog/lazy-list primitives supply native editing, focus and accessibility actions. File-page search/up/refresh actions wrap at narrow/large-font widths.

Owned API35 screenshots and actual control tests cover 100% light, 130% light and 200% dark, portrait/landscape and long-file-name detail scrolling. These are fixture visuals, not user aesthetic approval, all light/dark/font combinations, a ≥600dp device, physical TalkBack or the whole app's visual acceptance.

Video layout correction: libVLC 3.7.6 `VideoHelper.updateVideoSurfaces` otherwise considers Activity orientation and swaps a wide embedded viewport in a portrait activity. `MediaPlayer.setUseOrientationFromBounds(true)` is used so aspect fitting follows the real native viewport. The owned 16:9 fixture test requires both decoded PixelCopy content and video Surface width fitting that viewport.

Fullscreen correction: landscape no longer reserves the portrait transport strip. Native video/subtitle surfaces use the full stage height; controls overlay the picture and hide without resizing it. Aspect fitting retains natural letterboxing. The actual owned landscape fixture must occupy at least 95% of window height. Subtitle/control overlap, large fonts, insets and physical-device coverage remain part of final player acceptance.

Next preview priorities from the user's installed first-preview feedback: compact file header/navigation, aligned dense rows, switchable responsive grid and authenticated real image/video thumbnails. Those features are not completed by the fullscreen fix and must be validated independently before the next UI preview release. Formal hardware/media/network gates remain mandatory for the final release.

File browser implementation: compact server/path/action rows replace stacked large headings; a 64dp-minimum list and adaptive 152dp-minimum grid share two-line names and long-press selectable details. Grid title slots use two scaled text line heights so metadata aligns regardless of wrapping. View-mode buttons stay paired during wrapping. White/charcoal surfaces and pink accents match the search/player direction. Current artwork is explicitly a type placeholder; authenticated thumbnails and durable view preferences are subsequent slices. Actual phone/API35 coverage includes normal list occupancy >=60% of screen height, grid/details/navigation/search regression, 130% light and 200% dark. File landscape, >=600dp, complete theme/font combinations and physical TalkBack remain open.


## Recent playback cards

Recent playback reuses FileEntry's detail-card composition and the existing authenticated NAS thumbnail/Coil capability path. Canvas, 10dp cards, 8dp spacing, 12dp content gutters, two-line titles and 16sp file names match the library. The header uses the normal 20sp title role. Long press reuses the same selectable full-name/path dialog as files; no unknown file size is fabricated.

Each card shows its stored source position, known full duration and real synchronization status. Unknown duration is labelled pending confirmation and has no progress bar. A changed file labels the stored time as an old record and has no bar presented as current progress. Opening still uses the existing identity/progress verification; no new playback API or implicit conversion is added. Empty history offers actual file navigation. Account-scoped history and preview capabilities remain bound to the current session.

The thin progress bar retains Material 3's existing implementation and semantics, with its [documented color, gap and stop-indicator parameters](https://developer.android.com/reference/kotlin/androidx/compose/material3/LinearProgressIndicator.composable) set to the library's pink/neutral continuous styling. This does not create another canvas-based progress component.

TX5Pro debug build/test-APK/lint gates passed; the local unit task has no sources. Nine installed API35 tests passed in 95.142s (recent cards, three native preview gates, browser, three search interactions and theme recreation). After the progress styling correction, the affected recent-card gate passed again in 17.770s. Actual Room history drives these cards; generated owned PNG bytes pass authenticated JNI/HTTP into Coil and visible compositor pixels. Light/dark cards, details and account-isolated empty state were captured at default font scale. The real open action reaches the existing playback identity request and cancellation path. These are owned-fixture/emulator checks, not actual NAS media-thumbnail, physical TalkBack, hardware/HDR or signed-release acceptance. No enlarged-font checks were performed. Public 0.2.1-preview is unchanged; rollback source for this UI slice is `b7c876f`.

- 2026-10-25 导航反馈：底部五项只保留图标的一次动效和蓝色选中状态，关闭该组件范围内的 Material ripple／灰色按压底色；保留点击触区、选中语义及系统无障碍焦点。
