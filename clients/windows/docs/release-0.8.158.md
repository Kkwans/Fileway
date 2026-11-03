# 栖卷 Windows 0.8.158

0.8.156 修图片查看器时确认了根因：应用只设 `Shell.RequestedTheme`，代码里
`Application.Current.Resources["...Brush"]` 取到的是**系统主题**字典，所以运行时创建的窗口
必须走带 `{ThemeResource}` setter 的 Style 才能跟随主题。同一套逻辑当时只改了图片与 PDF，
文本与压缩包查看器仍写着「刻意固定的深色阅读面」——但用户在 0.8.156 那条反馈里的原话是
**「为什么默认浅色主题，背景却是黑色的？」**，这条对文本/压缩包同样成立，本版把它们统一。

## 改了什么

1. **`TextWindow.ApplyTheme` / `ArchiveWindow.ApplyTheme` 不再钉死 `ElementTheme.Dark`**，
   改为接收并应用真实主题；`SetTheme(theme)` 直接把主题透传下去（此前两个窗口都写成
   `ApplyTheme(ElementTheme.Dark)`，等于把 `MainPage` 传进来的主题丢掉）。
2. 删掉两文件里共 41 处显式 `Brush(r,g,b)` 赋值，改赋设计令牌样式：
   - 根与窗口容器 → `FwViewerCanvasStyle`（`FwCanvasBrush`）
   - 工具条 / 面包屑 / 状态条 / 正文面板 → 新增 `FwDocumentPanelStyle`（`FwSurfaceBrush` + `FwStrokeBrush`）
   - 提示条 → 新增 `FwDocumentNoticeStyle`（`FwSurfaceRaisedBrush`）
   - 摘要与状态文字 → `FwCaptionTextStyle`；标题与正文去掉显式前景色，由主题继承
3. **Markdown 渲染面同步**：代码块容器改用 `FwDocumentPanelStyle`，代码文字继承主题前景色；
   行内代码的蓝色（105,168,255）在浅底与深底都可读，保留。
4. **空文件状态**（0.8.74 加的那块）此前也是硬编码深色：图标底板改用 `FwIconTileStyle`，
   说明文字改用 `FwCaptionTextStyle`。
5. 两个窗口的 `_theme` 初值由 `Dark` 改为 `Default`（继承），避免首帧按错误的主题解析资源。
6. **同类问题全扫**：按「构造期捕获主题画刷」这一根因在全 App 静态扫描，又找到 6 处并一并修掉
   （它们的值在浅/深两套字典里确实不同，不是无害的）：
   - `ImageWindow.Separator()` 捕获 `FwStrokeStrongBrush`（深 `#33FFFFFF` / 浅 `#29000000`）
     → 改用 `FwDividerStrongStyle`；
   - `TextWindow` CSV 表头与 `StorageAnalysisControl` 两处结果卡捕获系统
     `LayerFillColorDefaultBrush` → 改用新增 `FwSubtlePanelStyle`（`FwSurfaceRaisedBrush`
     深 `#1F2228` / 浅 `#FFFFFF`）；
   - `MarkdownBlockRenderer` 列表符号捕获 `TextFillColorSecondaryBrush`（Run 无法赋样式，
     改为继承整行前景色）、引用块左边框捕获 `ControlStrokeColorDefaultBrush`
     → 改用新增 `FwAccentBarStyle`；
   - `ViewerChrome` 错误图标捕获 `FwDangerBrush`（深 `#FFFF6B6B` / 浅 `#FFC42B1C`）
     → 新增 `FwDangerIconStyle`（`TargetType="ctl:LucideIcon"`）。
   复扫结果：`Application.Current.Resources[...]` 已不再被赋给任何 Foreground/Background/BorderBrush，
   也没有把画刷存进字段的写法。
7. `DESIGN.md` 的 `canvas` 规则改为
   「图片/PDF/文本/压缩包查看器跟随主题，只有视频播放器固定深色」。

## 验证状态（如实记录）

- Release x64 构建 0 警告 0 错误；两次启动冒烟 `GATE=PASS`（15s / 20s，独立数据根，无 `startup.log`）。
- **视觉证据未取得**：本轮验证时段 Windows 会话处于锁定状态（`GetForegroundWindow()==0`、
  `LogonUI` 在跑、连续两帧桌面截图逐字节相同）。锁定下 `SendKeys` 抛错、合成鼠标点击无法打开
  文档窗口，`20261103-viewers08158/light1/report.txt` 因此只记录了
  `SESSION LOCKED: screenshots withheld` 与三条 INCONCLUSIVE，**没有**把环境限制写成产品通过。
- 已核对的静态事实：`_root`/`_windowRoot`/`_toolbar`/`_banner`/`_textFrame` 构造时都没有本地
  `Background` 赋值，因此样式赋值一定生效；四个引用的样式键（`FwViewerCanvasStyle`、
  `FwDocumentPanelStyle`、`FwDocumentNoticeStyle`、`FwCaptionTextStyle`）与 `FwIconTileStyle`
  均存在于应用级 `Design.xaml`。
- **解锁后必须补做**（探针已就绪，含锁定检测与「详情面板→打开」的免输入路径）：
  `Probe-Viewers.ps1 -Theme Light` 与 `-Theme Dark`，各 4 项断言（文本窗口打开、正文已加载、
  工具条存在、压缩包列表存在）+ 两张实拍，确认浅色下没有白字白底、深色下没有黑底黑字，
  然后才打包 0.8.158 安装包并记录哈希。本版**暂不打包**。
