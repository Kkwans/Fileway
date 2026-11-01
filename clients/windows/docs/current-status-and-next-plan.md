# 栖卷 Windows：当前能力与后续产品计划

更新日期：2026-11-01。当前交付版本：**0.8.148，本地未签名预览**（UI/UX 重构系列 0.8.12–0.8.148，
逐版说明见 `release-0.8.12.md`–`release-0.8.148.md`，设计契约见 [DESIGN.md](DESIGN.md)）；
最新安装包在 `Fileway-windows-artifacts/20261101-ui08148/dist`，等待用户实机验收。
本文件维护当前产品事实与后续范围；历史逐版细节见各 `release-*.md`，整体路线见
[product-roadmap.md](product-roadmap.md)。历史文档中“下一版”“尚未接入”等时态若冲突，以本文件与最新实现为准。

## 交付口径

- “已实现”表示存在接入产品的代码，不自动表示真实 Windows 体验已验收。
- 0.8.0 修复包（`20261025-release080-fix2`）已完成 Release/x64 构建、发布与安装包生成，
  构建 0 警告、0 错误；修复提交为 `743eef84`（构造期服务缺失）、`224ad39a`
  （存储工具重复挂载）、`83dca1a7`（缺失图标替换）。
- 启动冒烟：publish 目录 15 秒与 20 秒均存活、Responding=True，EnumWindows 可见主窗
  标题含“栖卷”，无 startup.log。该观察不证明批量下载、播放器退出、存储工具交互、
  视觉效果、安装升级或完整旅程通过。
- 0.8.0 启动崩溃为两段缺陷叠加：构造期 `StorageAnalysisPane.Attach(... ?? throw)` 抛
  `InvalidOperationException`（0xC0000271），以及 `StorageAnalysisControl` 把
  `duplicatesHost`/`adminHost` 同时挂到 `analysisHost` 与 `root` 导致
  `XamlParseException`（0x802B000A）→ `0xC000027B` failfast。此前 0.8.0 与 0.8.0-fix1
  安装包均不可用，勿交付。
- 按用户当前要求，本批没有编写或运行测试套件，没有执行私人 NAS 媒体或原生交互验收；用户人工测试。
- 这不是 GitHub Release、Store 上架或稳定版声明。播放器退出 CFG 故障已定责为外部钩子（P-01），身份指认与最终退出证据仍开放。
- Web/Android 功能尚未全部覆盖。1.0 目标为两端已有且未被用户明确排除的功能并集，
  平台机制按 Windows 适配；**分享/公共分享、手动文件校验和明确排除**。

## 0.3.4 相比 0.3.3

| 项目 | 0.3.4 的变化 | 明确边界 |
| --- | --- | --- |
| 批量下载 | 多选普通文件，预检来源、默认本地路径、名称碰撞；集中展示清单/数量/总大小/失败项，确认后提交 | 每次最多 100 文件；目录跳过，不递归；每个草稿最多提交一次 |
| 下载队列 | 当前进程 FIFO，最多 100 个未排空注册项，包含运行、等待和取消中；一个实际下载 writer | 不是多线程分段下载，不是重启恢复；共享执行预算可能使队首等待 |
| 排队项取消 | 未开始项在自己的任务锁与数据库事务下完成本地取消，不等待前一个大文件 | 不取远端资源、不创建 partial、不占数据 writer；不取消其他任务 |
| 生命周期 | 退出/账号切换纳入队列、取消操作与读取者的真实排空 | 不把取消请求接受当作已清理；未知结果不重放 |
| 批量确认体验 | 确认前选择/账号/目录变化作废旧预览；确认后可继续浏览；预检期间已有任务仍可暂停/取消 | 不允许旧回调跨账号继续派发 |
| 收藏分组 | 原生 Expander 分组头改为紧凑布局，名称与计数分开 | 保留键盘、展开语义与高对比资源；最终视觉待人工确认 |
| 图标状态 | Lucide 通过原生单色 Foreground 跟随主题、禁用与高对比 | 保留已选图标体系，不再另造图标；实际明暗/高对比效果待确认 |

## 当前功能矩阵

下列“已有”均需结合其边界理解；不能根据存在接口、测试夹具或旧构建推断完整功能。

| 功能域 | 当前已实现 | 未完成或验收边界 | 主要源码入口，均相对 `clients/windows/` |
| --- | --- | --- | --- |
| 连接与登录 | 账号密码/免认证、连接档案、新建与编辑、保存并浏览、DPAPI 保存会话、保持登录启动恢复、显式退出/遗忘；移除 HTTP 必勾选框 | 认证过期与网络故障仍须正确区分；系统/PAC、内嵌组网和完整证书管理未收敛；不保证任意部署 SSO | `src/Fileway.App/MainPage*.cs`；`src/Fileway.Infrastructure/Credentials/`、`Persistence/SqliteProfileStore.cs` |
| 文件浏览 | 面包屑在内容导航行、返回/上级/刷新、目录过滤、服务端搜索、排序、五种布局、网格居中、缩略图、类型多选、按账号目录保存部分浏览偏好 | 大目录分页/极限规模、列宽及类型多选跨重启持久化仍需核对；当前目录过滤不等于完整远端搜索 | `src/Fileway.App/MainPage*.cs`、`Controls/Browser/`、`SearchResultsController.cs` |
| 收藏/分组/标签 | 侧栏直接快跳、可折叠分组、真实颜色点和数量、关联资源与管理入口、绑定/解绑 | 现有页面有实现，整体视觉及大批量使用仍待人工验收；不退回“只有管理入口”的设计 | `Controls/Browser/SidebarCollectionsControl.cs`、`docs/resource-collections.md` |
| 最近访问 | 成功打开图片/视频后有界后台记录；当前账号列表、刷新、打开前重核资源 | 非完整最近播放队列，不把打开失败或本地媒体记入远端历史 | `src/Fileway.Infrastructure/Services/`、`src/Fileway.App/` 的 Recent 相关文件 |
| 文件写操作 | 新建目录/零字节文件、重命名/批量预览、复制/移动；Ctrl+C/X/V 与私有剪贴板标记、冲突确认 | 未知写入保留记录核实，不能无条件重试；文本框仍使用普通文字剪贴板 | `MainPage.FileCommands.cs`、`src/Fileway.Infrastructure/Services/FilewayClientServices.Files.cs` |
| 搜索 | Windows 原生搜索结果页调用现有 NAS API，当前目录/含子目录、流式结果、取消、结束原因与可信 wire 身份核对 | 不另建服务器网页；旧服务端缺能力时降级或明确不支持 | `Controls/Search/`、`SearchResultsController.cs`、`Services/FilewayClientServices.Search.cs` |
| 文件任务 | Windows 页面展示 NAS 执行的复制/移动等任务，筛选、分页、详情、取消/重试/归档、未知结果核实 | 默认当前账号；管理员跨用户任务与完整结果体验后续补齐；202 表示提交接受 | `src/Fileway.App/` 的 Task 相关控制器/控件、`Services/` |
| 回收站 | 当前账号列表、紧凑名称/时间、详情、恢复冲突确认、单项永久删除、持久未知操作核实 | 全部清空、统计、撤销窗口、批量策略尚未形成完整交付；旧文档“单项永久删除未接入”已过时 | `Controls/Trash/TrashControl.xaml.cs`、`TrashListController.cs`、`Services/FilewayClientServices.Trash.cs` |
| 图片 | 真正解码、Enter/双击打开、前后张、缩放，原图缓存、成功记录最近访问；0.4 音频：常见音频格式经同一播放器打开（HUD 控制/进度/倍速/选集连播复用），下载任务音频可播放 | 旋转、更多格式与完整图像工具后续完善；旧原生通过记录不覆盖当前所有 UI 变化 | `src/Fileway.App/MediaSurfaces/`、`Services/` 图片入口 |
| 视频基础 | 0.4 选集与自动连播：同目录选集菜单（当前项高亮、选定后同窗口换源）、播放结束自动播下一集（仅远端→远端、每集一次、失败不打断）；libmpv、Screenbox 风格界面、暂停/seek、音量/静音、章节、音轨/内挂字幕、倍速、续播、真实 FullScreen Presenter、单击播放暂停/双击全屏、窗口拖动缩放 | 代码接入不等于所有硬件/容器/窗口模式通过；退出 CFG 故障已定责外部钩子（P-01），最终退出证据仍开放；不改成 WebView 主界面 | `MediaSurfaces/PlayerWindow*.cs`、`Controls/Playback/ScreenboxPlayerControls*`、`src/Fileway.Playback/` |
| 播放反馈 | 0.4 小窗模式：更多菜单一键进入右下角置顶 384×216 小窗（播放/HUD/媒体键持续工作），再次点击或经全屏往返恢复原尺寸；| 0.4 兼容画质（HLS/转码）：播放器更多菜单新增"兼容画质"（原画直连/2160p/1080p/720p/480p），切换经服务端 /media/hls 任务在 streamable 后以租约子路径流式换源， superseded 任务自动取消、退出关闭收尾；0.4 SMTC 系统媒体键：硬件播放/暂停/停止键与 Windows 媒体浮层控制播放器（含上一集/下一集，按同目录排序），标题与播放状态实时反映到系统浮层，不触碰系统音量；| 0.4 播放器音量 0–200%：100% 即系统混音器电平，100–200% 为引擎侧软件增幅（softvol-max，独立于 Windows 系统音量，超过 100% 数值用主题强调色显示并提示可能削波）；seek 保留待确认目标，避免圆点回跳；加载超过 500ms 再显示，保留旧帧；引擎有真实缓冲百分比才展示；默认 5 秒隐藏控件、时长可调 | 真实大文件跳转、缩放、全屏和错误帧体验由用户验收，不宣称已全通过 | 同上及 `docs/ui-motion.md` |
| 字幕 | 本地外挂文件上限 500 MB；系统字体列表、字体/字号/缩放/位置/延迟即时调节；黑边坐标策略；ASS 作者样式与覆盖分开；位图字幕限制提示；0.4 远端同目录外挂字幕：播放器“从服务器加载字幕”列出同目录候选（同名优先、≤50），选定后经认证租约流式 sub-add | 检索为同目录一层不递归；任意 PGS 画布/ASS 显式坐标不保证可完整移入黑边；真实字幕旅程待用户验收 | `MediaSurfaces/LocalSubtitlePolicy.cs`、播放器控件与 mpv 属性适配 |
| 雪碧图/分辨率 | 服务端媒体雪碧图状态与悬停 tile 预览已接；工具栏常用清晰度名，精确尺寸在媒体信息 | 兼容画质切换已接入（真实 HLS 任务，不生成假画质列表）；转码进度与音轨/内挂字幕流选择待后续切片 | `ScreenboxPlayerControls.Sprites.cs`、`Services/` 的 VideoSprites 实现 |
| 缓存/预加载 | 视频磁盘缓存与预读、真实引擎时间区间和上游速率；缩略图/原图共池；默认总额 5 GB、自定义缓存/下载目录、清理 | 缓存不是完整离线下载；目录变更不静默迁移旧数据；活跃缓存与账号隔离仍需后续候选集中验收 | `Services/LocalStorageSettingsService*.cs`、`src/Fileway.Playback/PlaybackCacheState.cs`、共享 `transport/cache.go` |
| 上传 | 普通单文件 TUS、持久任务、确认偏移、显式上传队列、暂停/取消/手动恢复、切账号/退出排空；0.3.6 文件夹上传批次（本地有界枚举≤1000、跳过 reparse、逐级创建远端目录、冲突即停不覆盖、BatchId 批次身份）与拖入上传（文件区拖放高亮/标题、松散文件合并确认、文件夹复用树流程） | 重启自动恢复完整旅程未完成；不得用已发送字节冒充远端接受 | `MainPage.Uploads.cs`、`Services/UploadApplicationService.Uploads.cs`、`docs/upload-execution.md` |
| 下载 | 普通文件从头下载、另存为本地名、实际路径确认、不覆盖目标、句柄归属、flush/checkpoint、内部 SHA-256、发布与未知结果核实；0.3.4 多文件队列；0.3.5 版本绑定 fresh 与 Range 续传/重启恢复、挂起保留 partial、人工/自动恢复偏好（schema 7，默认手动）；0.3.6-a 目录下载批次：有界递归枚举（≤1000 文件/≤5000 队列）、Windows 非法名/保留名映射与去重、确认后创建目标子目录树、BatchId+Ordinal 批次身份复用同一 FIFO 队列 | 仅固定本地卷；UNC/映射盘/reparse 安全能力未支持；旧服务端（无 conditionalDownloadV1）保持 fresh-only 不续传；续传执行期间不提供该 partial 的边下边播准入；另存为不修改远端名 | `MainPage.Downloads.cs`、`MainPage.BatchDownloads.cs`、`Transfers/FileDownloadRunner*.cs`、`WindowsDownloadFileAccess.cs` |
| 传输中心 | 下载在前、上传在后，当前账号分页记录，实时速度/ETA/耗时、暂停继续/取消/删除；删除完成本地文件需额外勾选，默认保留 | 删除不能操作 NAS 源文件或陌生同名文件；历史记录不自动启动；目录/归档/重启恢复未完成 | `src/Fileway.App/Controls/` 传输控件、`Services/UploadApplicationService.Download*.cs` |
| 边下边播 | 活动视频下载读取同一持有文件的连续前缀；缺失部分可用认证 Range；取消/撤权唤醒并排空；播放器单独显示下载字节/写盘证据 | 非完整任意容器/索引支持；非已完成历史任务重开；Y4M `vo=null` 旧证据不代表可见 UI 或 MP4/MKV 全通过 | `Services/UploadApplicationService.DownloadPlayback.cs`、`Transfers/FileDownloadRunner.Readers.cs`、`Native/MpvDownloadStreamBridge.cs` |
| 主界面/材质 | 无原生顶栏边框；全局菜单、可折叠可调宽侧栏、主内容不透明；中性雾灰默认、实时桌面 Acrylic/壁纸 Mica 可选；暗色/高对比回退 | 统一下拉、焦点、窄窗、字体裁切和所有页面仍需用户确认；不因 API 启用材质即宣称美观 | `Windowing/FramelessWindowChrome.cs`、`Controls/ChoiceBox.cs`、样式资源 |
| 详情/提示 | 独立居中可拖动详情表面、图标操作；各页面可关闭自动消失浮层，默认 5 秒 | 特定模态/非模态焦点、缩放/滚动/材质实际观感需人工反馈，避免两层重复表面 | `MediaSurfaces/` 详情窗口、`Controls/FloatingNotice.cs` |
| 托盘与安装 | 运行显示托盘、默认关前台留后台、可设彻底退出；托盘退出；安装器请求正常退出，自选路径/快捷方式、DPI 清晰、中英名称、完成右侧“打开” | 升级中断/迁移回退、异常孤儿 Host/未正常退出和干净机器全矩阵未关闭；未签名 | `Windowing/ApplicationTray.cs`、`installer/`、`eng/Package-Preview.ps1` |

## 架构与数据不变约束

| 层 | 职责 | 不可越界的事项 |
| --- | --- | --- |
| Fileway.App | WinUI 3 页面、交互、窗口和视图状态 | 不持有服务密码、不拼服务器业务 URL、不把过期选择当作操作来源 |
| Fileway.Core | 类型化契约、领域状态与集成抽象 | 公共契约只有一位集成负责人；增量兼容，不把 UI 状态写成真实业务结果 |
| Fileway.Infrastructure | Host IPC、SQLite、DPAPI、文件能力、服务和传输编排 | 账号/session/activation/task/generation/revision fence；写入未知只核实，不自动重放 |
| Fileway.Playback | libmpv 绑定、播放事件、缓存/下载观察、原生 stream callback | callback 在实际 native destroy 返回后释放；不按路径重开可被替换的 partial |
| Go Host/共享核心 | 私有 IPC、认证续期、严格路由、单资源租约/传输 | 父进程监管只是子进程生命周期管理，不是内容审查；不绕过 TLS/权限或回退到未授权路由 |
| 服务端 | 既有 API、权限、任务、TUS、媒体/搜索与数据 | Windows 可调用已有能力；改共享 API 必须 additive、唯一负责人和 Android 兼容门槛 |

当前技术基线：.NET SDK 10.0.401、Windows App SDK 2.5.1、WinUIEx 2.9.3、
Windows SDK BuildTools 10.0.26100.9169、Go 1.26.6、libmpv 媒体运行包 0.41.0-8、NSIS 3.12。
包与 NuGet/Go 锁文件、media runtime-lock 共同固定依赖；不要因本机有更新就全局升级。
SQLite 为当前用户单机嵌入式持久化，不引入 MySQL 服务，不导入 Android Room 数据库。

## 开放问题与优先级

| 编号 | 优先级 | 问题/缺口 | 下一步与完成定义 |
| --- | --- | --- | --- |
| P-01 | P0 | 播放器退出 C0000409 / fast-fail 10 已定责：外部安装的 ANSI Windows 钩子过程经 `USER32!DispatchHookA` 派发时指向已释放内存，非 Fileway callback/窗口/native 生命周期缺陷（证据链见 `docs/player-exit-cfg-analysis.md`） | 待重新授权后用有界实时捕获指认外部钩子所有者；在无陈旧钩子环境取得未附加调试器的应用与 Host 双零退出证据后才可关闭；CFG 与安全机制保持 |
| P-02 | P1 | 0.3.3/0.3.4 下拉、收藏/标签、详情、回收站和队列视觉及交互尚未完成用户验收 | 整理用户一轮反馈、按共同组件修复；维持已认可播放器及清晰 Fluent，不再逐页发明样式 |
| P-03 | 已实现待验收 | 0.3.5 已接入下载 Range 续传与重启恢复：pinned sourceVersion、确认偏移、partial 身份、200 不追加、版本变更拒绝、退出挂起保留、人工/自动恢复偏好（schema 7） | 真实 NAS 大文件续传、崩溃/退出重启旅程与自动恢复偏好由用户人工验收；旧服务端保持 fresh-only |
| P-04 | 已实现待验收 | 0.3.7 已接入历史完成下载只读重开：终态证据（Completed+发布身份+哈希一致）与 128 位最终文件身份核实后经只读句柄授予播放器读取；无活动执行时自动路由重开，账号/权限围栏与排空保持，不加写/删权限 | 真实 NAS 历史重开旅程由用户人工验收；边下边播完整验收（P-05）继续 |
| P-05 | 代码齐备待验收 | 边下边播链路完整：fresh/续传执行均提供已写前缀读取与认证远端 Range 补齐，历史重开走只读最终文件；0.3.5 起续传期间前缀可读 | 普通/尾置 MP4、MKV、大跨度跳转与多容器真实旅程由用户人工验收；保存进度与读取进度隔离保持 |
| P-06 | P1 | 目录上传下载、批次子项、非法名称映射和归档下载未交付 | 有界枚举、预览/冲突确认、逐项结果；动态归档不可假装 Range 恢复，失败不重做已确认项 |
| P-07 | P1 | Web/Android 对齐矩阵仍有媒体、网络、文档、存储和设置缺口 | 开工时比较最新两端相关入口；不得根据旧快照宣称已全部对齐 |
| P-08 | 发布门槛 | 真实 HDR/多屏/DPI、安装迁移/中断、许可证完整性及 Android 回归未全部关闭 | 未来候选同 SHA 集中完成所需门槛；本地预览不替代稳定/公开分发资格 |

UI 质量不是后期才处理：每个功能切片同时完成对齐、可读性、反馈、键鼠、空态/错误态；
多风格主题可以后移，基本美感与一致性不能后移。普通提示必须自动消失且可关闭；
连续调节即时生效；长耗时动作不阻塞 UI；不伪造缓冲百分比、任务完成或可播放时间。

## 后续版本与产品结果

下表是目标顺序，不承诺日历工期，也不允许为赶版本跳过数据/权限边界。
若用户报告 P0 或启动/日常阻断，先出 0.3.4 的热修复增量，功能顺序相应调整。

| 版本 | 产品结果 | 主要依赖/交付边界 |
| --- | --- | --- |
| 0.3.4 | 当前普通文件批量预检与 FIFO、排队取消、紧凑分组和图标状态 | 已构建本地预览；当前用户人工测试，保留全部开放问题 |
| 0.3.5 | 可靠下载 Range 恢复/重启恢复，明确人工恢复与自动恢复偏好；优先处理已确认阻断缺陷 | 代码与构建已完成并出本地未签名预览（见 `release-0.3.5.md`）；源版本与原生文件身份已按契约实现，旧端缺能力保持安全 fresh；真实 NAS 旅程由用户人工验收 |
| 0.3.6 | 文件夹/批次传输、拖入上传、逐项冲突/名称映射、ZIP/TAR 等服务端支持的归档下载 | 全部接入：目录下载批次、文件夹上传批次、拖入上传、服务端 ZIP 归档导出（多选/目录右键，一次性流式、无续传无假校验、复用 fresh partial/发布与账号排空）；附带修复 host lease 白名单漏 sourceVersion 的真缺陷；归档旅程待用户人工验收 |
| 0.3.7（已交付待验收） | 完整边下边播与已完成历史下载重开、下载/播放连续生命周期 | 已有 Range 模块继续复用；普通/尾置 MP4、MKV、大跨度跳转与原生退出分别有证据，不扩大“前缀验证”结论 |
| 0.4 | 完整影音：选集/播放队列、远端外挂字幕、HLS 画质/转码、音频、图片扩展、小窗/媒体键与偏好 | 对标 Web ArtPlayer 与 Android 用户任务；来源/缓冲/保存进度分别展示；不生成假的画质列表 |
| 0.6 | 文档与归档：文本/代码/Markdown 阅读编辑、PDF/EPUB/CSV、包内浏览/打开/解压、本地打开 | 0.6-a 已接入：文本/代码只读查看器（40+ 扩展与 text/*，4 MB 解码预算，BOM/严格 UTF-8/GB18030 回退标注，自动换行/复制全文），0.6-b Markdown 原生渲染：自研有界子集（标题/段落/行内强调代码链接/列表/代码块/引用/分隔线，表格与未知语法退化为预格式文本，图片折叠为 alt 说明，仅 http(s) 链接可点），渲染/纯文本一键切换、渲染异常自动回退纯文本；0.6-c CSV/TSV 表格视图：RFC 4180 有界解析（≤500 行/≤60 列，引号转义与跨行字段支持，截断如实标注），固定列宽 ListView 虚拟化呈现；0.6-d 条件文本写入：编辑/保存/放弃与脏关闭确认；保存经服务端 conditional PUT（同锁下二次核实+同盘暂存发布），冲突 409 明确拒绝且不覆盖，保存后刷新观察支持连续编辑；非 UTF-8 源保存前明示将重写为 UTF-8；host request 通道 additive 支持 bodyBase64 原始字节；0.6-e 本地打开：已完成且发布校验一致的任务经只读身份核实（身份/长度/祖先）后交系统默认应用打开，不加写/删权限、不触碰 NAS 原文件；0.6-f ZIP 包内浏览与选择性解压：≤64 MB 的 .zip 经缓存租约全量缓冲后 ZipArchive 只读浏览（≤5000 条目，超限诚实拒绝），多选解压到下载目录 archive-extract 子目录——zip-slip 路径防御、逐条目与 1 GB 总量预算、已存在目标跳过不覆盖、逐条结果列明；tar 系列保持下载路径；0.6-g PDF 查看（补充：其文件路由在此批补齐落地，此前提交仅含窗口与服务）：≤64 MB 的 .pdf 经字节服务读入后用系统内置 Windows.Data.Pdf 引擎按需渲染（2× 缩放、上下页导航、密码保护/超 500 页诚实拒绝，渲染失败逐页提示，不做文字提取/编辑承诺）；EPUB 留 0.9 评估；不把 Web 应用嵌入替代原生产品 |
| 0.7 | 存储工具：容量/范围分析、重复扫描、保留副本与清理结果 | 0.7-a 容量分析（消费服务端任务，Windows 只呈现服务器数字）；0.7-b 重复文件：同面板双标签，服务端扫描→分组渲染（保留建议/可回收字节）→逐组单选保留副本→确认后 cleanup 提交，全部走回收站可恢复、逐文件结果列明，只动所选组内未保留副本，绝不直接删除，需要明确用户动作；不含分享/公共分享 |
| 0.8 | 设置/账号/管理 | 已收敛：0.8-a 账号密码修改（条件 PUT，仅 json 认证服务器显示入口）；0.8-b 管理员用户管理（存储工具第三标签，管理员密码确认，列表/创建/重置密码/权限/锁定/删除）；0.8-c 本地化基础设施（中文为源语言，语言切换跟随系统/简体中文/English，未迁移界面诚实保持中文）。按用户 2026-10-25 决定：全量英文本地化、条件注册/验证码、管理员命令白名单**后置（当前不需要）**；B/C 多风格主题须先经用户确认视觉方向，未确认前不动工 |
| 0.9 | 收敛与发布：冻结跨端对齐清单、清零 P0/P1、性能/安全/迁移/安装/可访问性/许可证收敛。按用户 2026-10-25 决定：**网络与完整性全套（系统/PAC/手动代理、内嵌 tsnet、证书、外置播放器、休眠换网、HDR/多屏/DPI、手动更新/诊断）后置**——9.0 发布后按实际需要评估 | 同一最终 SHA 的必要集中验证；9.0 发布后进入全面实机验收与小版本精修 |
| 1.0 | 两端未排除功能并集完成，日常体验可靠、可维护 | 用户最终验收；不自动授权 GitHub/Store/付费签名/生产部署 |

## 0.8.12–0.8.24 UI 迭代摘要

- **连接页**：整窗欢迎页（品牌栏 + 440px 表单卡片），外置标签 + 36px 输入框（文字垂直居中），
  Enter 提交，主按钮右置，保存档案自动选中；深色主题已复核。
- **统一设计语言**：工具栏选择器自绘并左对齐；菜单选中态改为半透明强调底；空状态统一
  （72px 图标容器 + 20px 标题 + 13px 说明）；传输中心/回收站/最近访问/存储工具/文件任务/搜索/收藏
  全部套用同一套控件与图标（侧栏与任务行已全量 Lucide）。
- **查看器**：PDF 工具栏改为底部玻璃药丸并打开即适应宽度；图片/文本/Markdown/压缩包维持沉浸式外壳。
- **对话框**：主按钮右置在菜单打开的对话框上通过延迟重试生效；缓存设置对话框操作栏内置。
- **文件夹上传**：提交端与持久化已带批次元数据（`X-Upload-Batch-*`/`X-Upload-Folder`）；
  0.8.30 起传输中心上传页按批次成组（批次汇总卡 + 缩进文件行），真人端到端待验收。
- **0.8.25–0.8.50**：连接页单主操作、操作区紧贴卡片（小窗口主按钮可见）、对话框主按钮右置幂等、收藏空状态统一、原生按钮清零、传输中心批次聚合、ToolTip 纳入自研毛玻璃浮层、侧栏图标语义去重、文件任务/文件浏览/缓存设置去 CommandBar 与 Segoe 字形、全部可选列表（文件任务/搜索/最近访问/收藏/传输中心）统一自研选中态、文件网格图标降为次级色；文件夹整批上传改为整批结束后刷新一次、复选框自研模板、展开面板与滚动条接入设计令牌、界面主题持久化、列表卡片 1px 描边、双色键盘焦点环、详情浮窗原生拖动与拖动手柄、收藏行尾部分隔符修复、回收站详情浮窗与恢复确认框统一、全部 ContentDialog 双蓝按钮修复、主按钮悬停对比度修复、主/次按钮自研焦点环；
  深色已覆盖主界面/连接页/全部主要面板与设置对话框；2026-10-30 用应用内主题切换补验文件任务卡片与选中态、缓存与存储对话框（`20261030-ui0835/screens/dark-nas.png`、`dark-storage.png`）；2026-10-30 用历史真实记录复核传输中心上传列表（4 条、选中态与卡片描边正常；旧批次无 `Batch` 元数据按单文件回退）与下载列表（4 条、暂停/删除按钮正常）；同日复核最小窗口 1200×900 下侧栏覆盖态（`20261030-ui0839/screens/size-1000x700.png`、`sidebar-overlay.png`）。
- **0.8.51**：图标/文字图标按钮补齐自研双色焦点环；内层环跟随 `Foreground`，在视频遮罩等深色表面仍可读；浅色与深色键盘焦点已实拍（`20261031-ui0851/screens/`）。
- **0.8.52**：最近访问、搜索、文件任务与操作记录统一“左图标/主信息、右时间/摘要”的列表层级；最近访问操作收拢到工具区右侧；浅色与深色截图见 `20261031-ui0852/screens/`。
- **0.8.53**：文件任务/操作记录补列头（任务/创建时间、操作/时间），空状态自动隐藏；截图见 `20261031-ui0853-final3/screens/tasks-light.png`。
- **0.8.54**：最近访问与搜索结果补列头（名称/访问时间、名称/类型·修改时间），空状态隐藏；截图见 `20261031-ui0854-final/screens/recent-light.png`、`search-light.png`。
- **0.8.55**：主浏览器筛选栏去掉原生 `CommandBar` 容器，最近访问“刷新”与“打开”统一 36px 基线；截图见 `20261031-ui0855b/screens/main-light.png`、`recent-light.png`。
- **0.8.56**：文件网格单元宽度扣除 4px 外边距，修复 1180 宽下最后一列贴边/溢出；截图见 `20261031-ui0856/screens/main-1180x760.png`、`main-1000x700.png`。
- **0.8.57**：`LayoutChoiceStyle` 分段/选项按钮补齐自研双色焦点环；浅/深色截图见 `20261031-ui0857/screens/task-tab-focus.png`、`task-tab-focus-dark.png`。
- **0.8.58**：键盘快捷键弹窗改为分组键帽布局，12 条快捷键一次可见；截图见 `20261031-ui0858c/screens/keyboard-shortcuts.png`。
- **0.8.59**：关于弹窗改为品牌/版本卡/资源按钮布局，HyperlinkButton 改为自研次级按钮；截图见 `20261031-ui0859b/screens/about.png`。
- **0.8.60**：打开方式对话框改为双栏，7 个类别一次可见；截图见 `20261031-ui0860/screens/open-with.png`。
- **0.8.61**：交互与外观分组 + 设置弹窗初始聚焦关闭按钮，移除首屏系统焦点框/数值浮层；截图见 `20261031-ui0861b/screens/presentation.png`、`open-with.png`。
- **0.8.62**：`FwPasswordBoxStyle` 设为 PasswordBox 隐式样式，修改密码与用户管理密码字段统一圆角外观；修改密码弹窗字段改为横向拉伸并与说明左对齐；截图见 `20261031-ui0862d/screens/password.png`。
- **0.8.63**：主浏览页搜索框改用 `FwSearchTextBoxStyle`（基于 `AutoSuggestBoxTextBoxStyle`，保留查询/清除按钮），圆角与 40px 高度对齐路径栏；输入过滤与 Enter 搜索实测通过；截图见 `20261031-ui0863/screens/sizes/`、`screens/dark/`、`screens/search/`。
- **0.8.64**：侧栏导航按钮与详情动作磁贴接入自研双色焦点环；动作磁贴补 `HorizontalAlignment=Stretch`，修复只占内容宽度导致图标散落；浅/深色截图见 `20261031-ui0864/screens/`、`screens/dark/`。
- **0.8.65**：CheckBox 关闭系统焦点视觉，改用自研双色圆角焦点环；连接页“保持登录”浅/深色实拍见 `20261031-ui0865/screens/`、`screens/dark/`。
- **0.8.66**：ToolTip 改用应用自有可变画刷（绕过 popup 的 ThemeResource 系统主题解析），浅色/深色/应用内切换三条路径实拍，深色 ToolTip 问题解决；截图见 `20261031-ui0866/screens/`。
- **0.8.67**：最小窗口排版复核；返回/上级/刷新改为 40×40，与路径栏、搜索框同高；截图见 `20261031-ui0867/screens/min1200/`、`screens/browser/`。
- **0.8.68**：编辑/视图/工具菜单补真实快捷键标签（Ctrl+C/X/V、F2、Delete、Ctrl+A、F5、Ctrl+F）；并复核 1200×900 搜索页与详情浮窗；截图见 `20261031-ui0868/screens/menus/`。
- **0.8.69**：回收站空状态补说明、头部图标 36px 对齐；文件任务空状态多余空格修正；截图见 `20261031-ui0869/screens/`。
- **0.8.70**：传输中心上传记录折叠空指标行，修复字节与远端路径之间约 75px 断层；真实历史上传/下载记录 1200×900 复核；截图见 `20261031-ui0870/screens/after/`。
- **0.8.71**：收藏与标签页 8 个头部/分组图标按钮、传输中心刷新按钮 34→36；首次 1200×900 实拍收藏页；截图见 `20261031-ui0871/screens/`。
- **0.8.72**：CSV 表格列宽按查看器宽度自适应（140–220 DIP），小表四列完整显示；图片查看器 1200×900 复核；截图见 `20261031-ui0872/screens/after/`。
- **0.8.73**：压缩包查看器补“名称 / 大小”列头并对齐条目；Markdown/图片/压缩包查看器 1200×900 复核；截图见 `20261031-ui0873/screens/`。
- **0.8.74**：文本查看器补空文件状态（图标/标题/说明），编辑时自动隐藏；媒体播放器 1200×900 复核（顶/底栏渐变与控件正常）；截图见 `20261031-ui0874/screens/`。
- **0.8.75**：缓存与存储对话框滚动区 480→360 DIP，修复 1200×900 下顶边被裁；截图见 `20261031-ui0875/screens/`。
- **0.8.76**：`DialogOrdering.ContentMaxHeight` 自适应弹窗内容高度；交互与外观、键盘快捷键在 900px 高窗口下不再裁切；截图见 `20261031-ui0876/screens/after2/`。
- **0.8.77**：缓存与存储对话框接入自适应高度（900px 高约 360 DIP，1000px 高约 427 DIP），并复核打开方式/关于；截图见 `20261031-ui0877/screens/`。
- **0.8.78**：菜单键盘焦点改用应用双色并内缩 2 DIP（形状仍系统矩形）；浅/深色实拍见 `20261031-ui0878/screens/`。
- **0.8.79**：新增 FwDisclosure 替换任务详情/回收站的 Expander，开关头用自研焦点环并绑定读屏名称；1600×1000 实拍见 `20261031-ui0879/screens/wide3/`。
- **0.8.80**：缓存与存储对话框改为“当前生效/保存配置”在前、“缓存占用/清理”在后，900px 高窗口可直接编辑缓存根目录；截图见 `20261031-ui0880/screens/`。
- **0.8.81**：文件任务窄布局改为详情占满内容区 + “返回列表”，UIA 验证列表隐藏/恢复；修改密码弹窗 1200×900 复核无裁切；截图见 `20261031-ui0881/screens/after6/`。
- **0.8.82**：连接页单栏模式去掉遗留 64px 列间距，表单卡片在 1200/1440 下真正居中；截图见 `20261031-ui0882/screens/after/`。
- **0.8.83**：文件网格按可用宽度分配列数并铺满（详细/紧凑/无界/紧凑列表），修复 1200 DIP 窗口右端约 100px 尾隙；筛选栏右侧动作组与搜索框右缘对齐；文件任务筛选收敛为 220/160 DIP；搜索页操作按钮右置；详细列表右列改为右对齐；连接表单补读屏名称。UIA 实测卡片左右外边距各 4 DIP，1200/1500/1800/2400 实拍见 `20261031-ui0883/screens/after/` 与 `screens/layouts/`。
- **0.8.84**：回收站主列表与待核实列表接入 `FwListViewItemStyle`（卡片、半透明选中 + 3px 条、自研焦点环），列头 66/18 与行内容盒对齐，“大小/删除时间”右对齐；主浏览详细列表列头左内边距 56→62；侧栏标签空提示断为两行。真实流程把测试账号“01 文本”移入回收站截图后恢复，回收站为空、目录回归；截图见 `20261031-ui0884/screens/`。
- **0.8.85**：批量下载确认框改为“名称 · 大小”13px 主色 + 目标路径 12px 次级，按“将添加/未添加”分组，滚动高度接入 `DialogOrdering.ContentMaxHeight`；打开确认框时收起“正在核对下载目标”浮条。真实选中 4 张图片触发并取消，1800×1250 与 1200×900 均无裁切；截图见 `20261031-ui0885/screens/after/`。
- **0.8.86**：播放器 HUD 顶部渐变加强为 `#CC→#66(55%)→透明`、底部改为 `透明→#73(45%)→#CC`；用真实 6 秒彩条短片 `h264-aac.mp4` 暂停后截取普通/全屏，修复前后全屏帧见 `20261031-ui0886/screens/player-fullscreen.png`、`screens/after/player-fullscreen.png`。
- **0.8.87**：弹层统一 14px 圆角 + 1px 应用描边，覆盖工具栏排序/类型/布局 Flyout、右键/更多菜单、任务/回收站/收藏菜单与播放器菜单；`SurfaceStyles.Attach` 在 `Opened` 后定位 presenter 设定属性，解决 Flyout 忽略隐式样式与 Opening 时序问题。浅/深色实拍见 `20261031-ui0887/screens/final-light/`、`final-dark/`。
- **0.8.88**：`DialogOrdering` 改为在 `CommandSpace` 内解析动作按钮并处理包裹元素，修复内容 InfoBar 同名 `CloseButton` 导致的主按钮左置；“下载另存为”文件名输入框接入 `FwTextBoxStyle`，远端/本地/大小/保存到改为标签+值层级并放入预览卡，滚动高度自适应。修复前后实拍见 `20261031-ui0888/screens/before/`、`screens/final2/`。
- **0.8.89**：新建文件夹/空文件、单个重命名、批量重命名与“移动到/复制到”输入框统一 `FwTextBoxStyle`；说明降为 12px 次级；批量重命名补规则提示与自适应滚动；主按钮文案改为“创建/重命名”。实拍见 `20261031-ui0889/screens/before/`、`screens/after/`。
- **0.8.90**：回收站详情浮窗改为 56px 图标磁贴 + 名称/类型 + 12px 标签/13px 值表单（大小/删除时间两列），恢复/永久删除按钮 34→36；真实移入/详情/恢复流程复核，回收站为空。前后实拍见 `20261031-ui0890/screens/trash-before/`、`screens/trash-after/`。
- **0.8.91**：传输中心下载/上传详情内的操作按钮接入 `FwSecondaryButtonStyle`/`FwPrimaryButtonStyle`（36 DIP、圆角），状态卡与取消确认卡改用 `FwSurfaceBrush` + 1px `FwStrokeBrush`；`XamlReader.Load` 字符串内的 `StaticResource` 不生效，改为构造函数按资源键赋值。真实下载记录打开详情截图，前后见 `20261031-ui0891/screens/before/`、`screens/after2/`；上传详情因夹具无上传记录未截图。
- **0.8.92**：修复 `LayoutChoiceStyle` 分段选择器切换后旧项残留蓝底的问题。选中色原写在 `Root.Background` 的 Checked 动画里，`Unchecked` 不恢复且 `PointerOver` 会覆盖同一属性；现改为独立 `SelectionFill` 层并由新增 `FwBoolToVisibilityConverter` 绑定 `IsChecked`。存储工具、传输中心、文件任务三处实拍，UIA `ToggleState` 与视觉一致；前后证据见 `20261031-ui0891/screens/audit-0892/`、`20261031-ui0892/screens/final-tabs2/`。
- **0.8.93**：存储工具三个子页的说明与表单收进 `FwFormCardStyle` 卡片（最大 720 DIP、16 内边距、10 圆角），说明 13px 次级、路径输入框卡内填充，结果与用户列表保留在卡外；浅色/深色实拍 `20261031-ui0893/screens/final2-light/`、`final2-dark/`。实现中修正了构造函数取 `Application.Current.Resources` 主题画刷导致深色白卡的问题，改为样式内 `ThemeResource`；同时补验 0.8.92 分段选择器深色正常。
- **0.8.94**：主浏览网格卡片的名称在 40 DIP 预留区内垂直居中（UIA 名称矩形 60→30px 高、y=393→408，元数据 y 不变），单行名称与图标/元数据留白更均衡；浅/深色实拍 `20261031-ui0894/screens/after-light/`、`after-dark/`，修复前 `20261031-ui0893/screens/before-0894-light/`、`before-0894-dark/`。
- **0.8.95**：传输中心批次头与任务行的进度条改为 6 DIP 圆角轨道（`FwStrokeBrush`）+ 4 DIP 强调色指示条；等待状态显示完整空轨道，完成状态指示条嵌在轨道内，不再像文件名下划线。浅/深色实拍 `20261031-ui0895/screens/after-light/`、`after-dark/`，修复前 `20261031-ui0894/screens/before-0895-light/`、`before-0895-dark/`；像素复核等待轨道为 `#EBEBEB`、9px 物理高（6 DIP）。
- **0.8.96**：缓存与存储对话框“当前生效”改为 12px 标签 + 13px 值的四行摘要，区块标题 15px SemiBold；滚动区与按钮间补 1px 分隔线和底部留白，首选内容高度 520→560 DIP（仍按窗口高度−240 自适应），默认下载目录帮助文字不再半行裁切。浅/深色实拍 `20261031-ui0896/screens/final2-light/`、`final2-dark/`，修复前 `20261031-ui0895/screens/settings-audit/`、`settings-audit-dark/`。
- **0.8.97**：上传文件/文件夹选择器由 `Microsoft.Windows.Storage.Pickers` 切换到 WinRT `Windows.Storage.Pickers` + `InitializeWithWindow`，修复本机 `COMException`（接口已为另一线程整理）导致上传无法选择文件；真实上传 29 B/37 B 文件成功并由服务器确认，文件夹选择器可打开。选择器失败改为专用提示，不再误报“检查服务器连接”；上传/下载详情进度条接入 6 DIP 轨道。证据 `20261031-ui0897/screens/upload/`、`screens/folder-picker/`、`screens/download-details/`。
- **0.8.98**：单文件上传确认框改为“上传到”12px 次级标签 + 13px 路径值，说明文字使用 12px 次级色；上传详情目标路径可选中复制，进度条补读屏名称。浅色实拍前后见 `20261031-ui0897/screens/upload-confirm/`、`20261031-ui0898/screens/upload-confirm/`。文件夹选择器可打开并导航，但内嵌 picker 的提交无法通过 UIA 可靠驱动，整棵文件夹树上传仍待真人验收。
- **0.8.99**：连接欢迎页隐藏无效侧栏切换按钮（`SetConnectionView` 同步 `PaneButton.Visibility`），连接后恢复；完成 1000×700 DIP 的浏览器/传输中心/连接页排版复核，未发现溢出。证据 `20261031-ui0898/screens/narrow/`、`20261031-ui0899/screens/narrow/`、`narrow-connected/`。
- **0.8.100**：主窗口记住大小与位置（`ui-preferences.json` 的 `window` 字段），启动时按显示器工作区钳制，关闭/收起到托盘时保存；仅保存非最大化状态，主题偏好兼容保留。实测 1500×1050@40,20 关闭重启后原样恢复，深色主题保留，越界坐标 50000,50000 被钳制到 1060,478。证据 `20261031-ui08100/screens/bounds-theme2/`、`bounds3/`、`bounds-theme3/`、`offscreen/`。
- **0.8.101**：窗口状态增加 `maximized`：最大化关闭只更新标记、保留正常 bounds，重启直接最大化，取消最大化回到原尺寸；实测最大化 2560×1528 → 取消为 1500×1050@40,20，重启/再最大化/关闭的 prefs 与主题均正确。证据 `20261031-ui08101/probe-max.log`。
- **0.8.102**：导航侧栏展开/图标栏状态写入 `sidebarCollapsed`，重启保持，覆盖式窄窗口不改变偏好；实测深色 1500×1050 折叠→重启仍为图标栏，展开后 prefs 回到 false，theme/window 不变。证据 `20261031-ui08102/screens/sidebar/`、`probe-sidebar.log`。
- **0.8.103**：关于弹窗的两个链接按钮改为同一行（32 DIP、间距 8），弹窗高度缩短约 40 DIP；1000×700 浅/深色实拍 `20261031-ui08103/screens/about-light/`、`about-dark/`，修复前 `20261031-ui0899/screens/help-dialogs/about.png`。
- **0.8.104**：搜索结果右侧列头由“类型 · 修改时间”改为“大小/类型 · 修改时间”，与文件显示大小、文件夹显示“文件夹”的行内容一致；1000×700 真实 `png` 搜索（4 项、含子目录）浅/深色实拍 `20261031-ui08104/screens/search-light/`、`search-dark/`。
- **0.8.105**：回收站空状态说明改为“删除的文件和文件夹会先出现在这里，可在彻底删除前恢复”，与页头“项目…恢复或彻底删除”一致；同批 1000×700 复核收藏夹视图与键盘快捷键弹窗。证据 `20261031-ui08105/screens/light/trash.png`、`dark/trash.png`、`20261031-ui08104/screens/more-narrow/`。
- **0.8.106**：“打开方式”对话框说明缩短为一行、分类间距 12→8、滚动区接入 `ContentMaxHeight`，1000×700 下七个分类完整显示，不再裁切“视频”行。证据 `20261031-ui08105/screens/tool-dialogs/open-with.png`、`20261031-ui08106/screens/open-with2/open-with.png`。
- **0.8.107**：“交互与外观”改为两栏（提示与播放 / 界面背景），面板 320→480 DIP、ColorPicker 268→228；1000×700 下全部设置完整可见。证据 `20261031-ui08106/screens/appearance/appearance.png`、`20261031-ui08107/screens/light/`、`dark/`。
- **0.8.108**：上传/下载详情的取消确认出现时隐藏命令区“暂停/继续”主按钮，保持唯一蓝色“确认取消”，返回或状态变化后恢复；真实 512 MB 上传 active → 取消确认 → 服务器确认取消。证据 `20261031-ui08107/screens/cancel-upload/upload-cancel-confirm.png`、`20261031-ui08108/screens/cancel/`。
- **0.8.109**：上传/下载详情打开时隐藏右下重复浮层，关闭后恢复；浮层改为“文件名 / 指标”两行，数值与单位不拆行；取消确认时“取消上传…”收起而不是置灰。真实 512 MB 上传 active → 关闭详情 → 浮层恢复 → 重开 → 取消确认 → 服务器确认取消。证据 `20261031-ui08109/screens/`。
- **0.8.110**：文件网格列宽扣除垂直滚动条占位，修复末列整列换行；1600×1000 由 3 列恢复 4 列、最大化 7→8 列。最近访问把错误提示移入操作组并折叠空行，去掉约 12 DIP 双倍间距；搜索条件与输入一致时隐藏重复行，编辑后仍显示旧条件。证据 `20261031-ui08110/screens/before/`、`grid-after/`、`after/`。
- **0.8.111**：任务详情的满格蓝色进度长线改为 6 DIP 轨道并在完成度 100% 时隐藏；传输中心已完成记录隐藏进度条与无效暂停按钮，批次头同步；文件任务去掉空提示行双倍间距，未选中任务时“更多任务操作”禁用。真实 7 条服务器任务与 4 条历史下载实拍，证据 `20261031-ui08111/screens/tasks.png`、`tasks-detail.png`、`transfer.png`、`screens/final/`。
- **0.8.112**：排序/类型/布局选择器改用自研 DropDownButton 模板（双色圆角焦点环 + Lucide 箭头，去掉系统焦点矩形）；修复 `ControlFillColorQuaternaryBrush` 缺失导致的启动 XamlParseException（按下态改用 `SubtleFillColorTertiaryBrush`，次按钮同步）。复核紧凑网格 6 列、无界网格 5 列、详细列表、紧凑列表 2 列在 1600×1000 均铺满。证据 `20261031-ui08112/screens/`、`screens/after/`。
- **0.8.113**：等待/冲突传输行折叠速率/剩余/耗时指标，旧记录不再显示“已用 131 小时”；文件详情“打开”与文件任务“更多任务操作”统一 36 DIP。深色选择器焦点环、网格、详情浮窗实拍复核。证据 `20261031-ui08113/screens/`、`screens/transfer-after/`、`screens/details-after/`。
- **0.8.114**：空文件夹与空收藏标签的底部状态不再显示“双击或按 Enter 打开”，0 项时只显示“0 个项目”；真实空目录实拍 `20261031-ui08114/screens/empty-folder.png` → `screens/after/empty-folder.png`。收藏页菜单自动化仍在枚举弹层时阻塞，文案改动未截图。
- **0.8.115**：缓存与存储对话框的容量 `NumberBox` 标题在 150% DPI 下与输入框重叠并出现裁切残影，改为 12px 外置标签；缓存根目录/默认下载目录同步外置标签，三个字段层级一致。前后裁剪 `20261031-ui08115/screens/cache-capacity-crop.png` → `screens/after/cache-capacity-crop.png`，完整弹窗 `screens/final/collections.png`。
- **0.8.116**：收藏与标签页副标题改为页面功能描述；收藏行由通用文件图标改为收藏图标（标签资源行仍为文件）；缓存与存储滚动区改为常驻滚动条，提示下方还有说明与缓存占用。收藏页前后实拍 `20261031-ui08116/screens/menu-click/collections-page.png` → `screens/collections-after/collections-page.png`，滚动条 `screens/cache-after/`。
- **0.8.117**：收藏页“全部收藏/标签模式”下“更多分组操作”禁用，选中收藏行后打开/编辑/移除启用；选中态为蓝色底 + 3px 强调条。宽窗口 2560×1528 文件任务 340 DIP 详情栏复核无缺口。证据 `20261031-ui08117/screens/collections-selected.png`、`screens/after/collections-selected.png`、`screens/tasks-wide/tasks-detail.png`。
- **0.8.118**：“交互与外观”两个滑块右侧显示实时秒数（4 秒/2 秒），标题 12px 次级色；不再使用 Slider Header。深色收藏选中态与操作启用复核。前后 `20261031-ui08118/screens/dark2/cache-dark.png` → `screens/appearance-after/appearance-dark.png`。
- **0.8.119**：主浏览未选择时不再显示“已选择 0 个”，选中后才拼接数量；收藏页自动刷新不再弹成功提示，手动刷新仍显示。证据 `20261031-ui08119/screens/status/`、`screens/collections-toast/`。
- **0.8.120**：搜索页未开始搜索时隐藏禁用“打开”按钮，底部只显示“尚未开始搜索”（不再带“0 项结果”）。证据 `20261031-ui08120/screens/search-initial.png` → `screens/after/search-initial.png`。
- **0.8.121**：搜索页表单/动作/提示合并为可折叠内容组，未查询时动作行与提示隐藏，空状态上移；提交后动作行恢复。证据 `20261031-ui08121/screens/search-initial.png`、`screens/pages-after/search.png`。
- **0.8.122**：缓存设置“当前生效”的长路径改为单行省略 + Tooltip，摘要高度减少约 40 DIP，首屏容量说明完整显示；深色实拍。证据 `20261031-ui08121/screens/cache-dark/collections.png` → `20261031-ui08122/screens/cache-dark-after/collections.png`。
- **0.8.123**：侧栏“收藏夹”分组新增“管理”入口，点击直达收藏与标签页；不再只能经“工具”菜单进入。证据 `20261031-ui08123/screens/browser-none.png`、`collections-from-sidebar.png`。
- **0.8.124**：传输中心下载/上传空状态按页签给文案；进度轨道只用于传输中/暂停/失败/取消，等待与冲突行不再显示空轨道。证据 `20261031-ui08124/screens/transfer-after/transfer.png`、`transfer-upload.png`。
- **0.8.125**：收藏与标签列表名称改为 14px SemiBold，与最近访问/搜索/任务列表一致；路径与“未分组”保持 12px 次级。证据 `20261031-ui08125/screens/row-name-before.png` → `row-name-after.png`、`collections-from-sidebar.png`。
- **0.8.126**：传输中心空状态图标按下载/上传切换（download-simple/upload-simple）；文件任务空状态图标按任务/操作记录切换（file-code/clipboard）。证据 `20261031-ui08126/screens/transfer-after/transfer-upload.png`、`screens/operations-after/operations.png`。
- **0.8.127**：深色下侧栏“管理”按钮因构造时捕获浅色次级画刷几乎不可见，改用 `FwCaptionTextStyle` 的 TextBlock 内容，深浅色正确显示次级灰。证据 `20261031-ui08127/screens/browser-none.png`（修复前几乎不可见）→ `screens/dark-final/browser-none.png`（可读）。
- **0.8.128**：深色“交互与外观”滑块标签实测从浅色次级灰 (83,90,100) 修正为深色次级灰 (168,174,184)；回收站详情弹层的类型/字段标签与 56×56 图标底板改走 `FwCaptionTextStyle` 与新增 `FwIconTileStyle`。证据 `20261031-ui08128/screens/appearance-dark.png`、`Probe-AppearanceDark.log`。
- **0.8.129**：主浏览文件操作对话框（新建/重命名/批量重命名/复制移动）的提示与字段标签改用 `FwCaptionTextStyle`；批量下载确认框 9 处次级文字、下载另存为标签同步；下载预览卡改用新增 `FwPreviewCardStyle`。深色重命名与批量重命名提示实测 (168,174,184)。证据 `20261101-ui08129/screens/rename-dark.png`、`batch-rename-dark.png`、`Probe-RenameDark.log`。
- **0.8.130**：PDF 查看器深色下不再停留浅色内容壳：页面卡、页码、工具栏图标/文字/分隔线改用 `FwDocumentSurfaceStyle`、`FwBodyTextStyle`、`FwPrimaryIconStyle`、`FwDividerStyle`。修复前白色内容卡 + 深色文字，修复后深色壳 + 浅色页码/工具栏。证据 `20261101-ui08130/screens-before/before-window.png` → `screens-after/after-window.png`。
- **0.8.131**：图片查看器深色错误卡标题“图片打不开”原先完全不可见（浅色主色画刷 + 暗色卡片），说明也低对比；改用 `FwBodyTextStyle`/`FwCaptionTextStyle` 后标题白色、说明次级灰。前后 `20261101-ui08131/screens-before/before-window.png` → `screens-after/after-window.png`。
- **0.8.132**：PDF 工具栏页码/文字误用正文样式的 22px 行高，13px 文字行高被撑到 33px；新增 `FwToolbarTextStyle`（13px/18px 行高/主题主色），实测回到 27px、基线 y=794。证据 `20261101-ui08132/screens/toolbar-window.png`。
- **0.8.133**：展开侧栏时导航项仍弹同名 ToolTip，遮挡菜单/相邻导航；改为仅在 72px 图标栏启用，并放右侧。浅色审计同时复核主浏览/搜索/最近/任务/存储/回收站，四列网格 UIA 等宽 262–263px。证据 `20261101-ui08133/screens-tips2/expanded-final.png`、`rail-final.png`、`audit-main-light/`。
- **0.8.134**：底部“切换连接”按钮同步侧栏 ToolTip 规则：展开无浮层、折叠态右置并跟随“连接服务器/切换连接”文案。实拍 `20261101-ui08134/screens/expanded-connection.png`、`rail-connection.png`。
- **0.8.135**：单文件“确认下载位置”改为 `FwPreviewCardStyle` 字段卡（大小/保存到 12px 标签 + 13px 值，路径可选中换行），说明降为 12px 次级，与“下载另存为”一致。深色实拍 `20261101-ui08135/screens/direct-download.png`。
- **0.8.136**：批量下载确认框同目录时只在顶部显示一次“保存到”，条目行仅“名称 · 大小”，弹窗高度显著缩短；目录不一致时回退逐条路径。同步实拍“下载另存为”预览卡。前后 `20261101-ui08136/screens/batch-download-dialog.png` → `screens-after/batch-download-dialog.png`。
- **0.8.137**：`FwPreviewCardStyle` 圆角 8→10，与 `FwFormCardStyle`/按钮统一；下载确认卡深色复拍 `20261101-ui08137/screens-download/direct-download.png`。
- **0.8.138**：收藏与标签页头部操作图标间距 4→8，与最近访问/任务/传输/存储页面一致；1200×900 深色复核 `20261101-ui08138/screens/collections.png`。
- **0.8.139**：收藏页返回由 36px 箭头改为与其它页面一致的“返回文件”次按钮，导航与图标操作分组更清晰；1200×900 深色复核 `20261101-ui08139/screens/collections.png`。
- **0.8.140**：传输详情“下载已完成”状态卡不再显示满格蓝色进度线，完成态隐藏 6px 轨道；未完成/失败/取消保留。前后 `20261101-ui08140/screens/transfer-clean.png` → `screens-after/transfer-clean.png`。
- **0.8.141**：上传详情完成态同步折叠满格进度轨道；用真实 29 B 上传记录实拍 `20261101-ui08141/screens/upload-detail.png`。
- **0.8.142**：存储工具页补“返回文件”次按钮并接回 `ReturnFromRemoteTools`，与搜索/任务/回收站/传输一致；深色实拍 `20261101-ui08142/screens/storage.png`。
- **0.8.143**：回收站页头补“返回文件”，`Close_Click` 返回工作区；UIA 验证 `BACK_BUTTON=True`、返回后工作区恢复。实拍 `20261101-ui08143/trash/trash.png`。
- **0.8.144**：统一各页“返回文件”读屏名，文件任务去掉了多余空格；无可见布局变化。
- **0.8.145**：回收站页头顺序调整为“返回文件｜恢复｜刷新”，与收藏页“返回在图标组最左”一致；UIA 返回导航通过，实拍 `20261101-ui08145/trash/trash.png`。
- **0.8.146**：菜单项焦点由系统直角改为 10px 圆角——仅给四个菜单项样式加 `CornerRadius=10`，系统焦点框即跟随；键盘实拍 `20261101-ui08146/screens/menu-focus.png`，未替换模板。 子菜单与单选项键盘焦点也已实测为圆角：`20261101-ui08147/screens/submenu-focus.png`、`submenu-open.png`。
- **0.8.147**：高对比度走查（用户授权）发现侧栏当前页选中态不可见；新增 `FwNavSelected*` 主题资源与 `SelectionBorder` 模板，HC 为透明底 + 系统文本色 1px 圆角描边，常规主题保持填充。证据 `20261101-ui08147/hc3/`、`normal/recent.png`；HC Flags 已恢复 126。
- **0.8.148**：HC 选中列表/网格改为透明底 + 白色 3px 标记，文本与图标保持黑底白字；选择器保留系统高亮。证据 `20261101-ui08148/hc/browser-hc-selected.png`、`recent-hc-selected.png`、`normal/browser-hc-selected.png`；HC 已恢复 126。 传输详情/文件任务/回收站空状态已补拍 `20261101-ui08149/screens2/`。 PDF 查看器 HC 已补拍 `20261101-ui08151/screens/pdf-hc-window.png`。
- **仍需用户**：实机验收 0.8.148；整棵文件夹树上传与批次卡、拖拽验收；是否允许临时改系统设置做高对比度走查。

## 下一开发批次的建议顺序

**当前阶段为 UI/UX 持续迭代**：0.8.148 已交付，等待用户实机反馈；
每次改动仍遵循“构建 0 警告 + 两次冒烟 + 截图/真实操作证据”。

1. 先读取本文件、当前代码与用户最新反馈，核实远端最新提交，保护用户修改。
2. 先关闭启动/退出/队列取消等阻断问题；没有新证据不要推翻已认可 UI 或重复实现现有功能。
3. 验收阶段只做用户确认的精修项；未确认项不擅自实现。
4. 每个切片精确 diff、按授权构建/打包、中文 Conventional Commit 推送 Windows 分支；
   当前用户保留人工测试，不新增/运行测试。后续若用户改变验证要求，再集中安排，不自动启动旧全套门禁。
5. 持续更新本文件、产品路线和版本说明；临时执行收据、会话交接、启动提示词放仓库外。
6. UI 下一步：0.8.148 继续全局焦点覆盖与弹层余项；菜单焦点已用应用双色但仍是系统矩形，若要圆角需评估整体替换 MenuFlyoutItem/SubItem 模板的成本。
   之后再继续全局键盘遍历与高对比度走查；整棵文件夹树上传/拖拽与 PDF 沉浸外壳按原边界等待条件。

## 原计划与当前要求的关系

R1 的 WinUI 3 / Go Host / libmpv / SQLite-DPAPI、账号与路径语义、未知写入、安全及 G1—G5
完成门槛保留。版本号增加、构建通过或功能入口出现都不能自动关闭原目标。
用户后续明确调整覆盖旧执行方式：当前不使用子代理、禁止 Luna；将来重新获准时仅 GPT-6.1 Sol
low/medium/high，默认 medium；人工测试优先、当前不写/跑测试；分享/公共分享与手动校验和排除。
主代理模型切换必须依据实际运行能力，不能仅凭提示词声明已经切换。
