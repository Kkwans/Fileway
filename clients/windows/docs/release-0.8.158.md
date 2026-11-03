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

## 验证状态

- Release x64 构建 0 警告 0 错误；两次启动冒烟 `GATE=PASS`（15s / 20s，独立数据根，无 `startup.log`）。
- **浅/深两轮实机断言各 8 项全通过**（`20261103-viewers08158/light3/report.txt`、`dark2/report.txt`）：
  限定共享范围部署在跑 → 夹具可达 → 文本窗口打开 → `TextEditor` 的 ValuePattern 读回正文首行
  「栖卷文本查看器回归样张…」→ `EditTextButton` 存在 → 截图落盘 → 压缩包窗口打开 →
  列表含「回归-文本.txt」。
- **实拍 4 张**：`light3/text-viewer.png`、`light3/archive-viewer.png`、
  `dark2/text-viewer.png`、`dark2/archive-viewer.png`。浅色：浅灰画布 + 白色圆角正文面板 + 深色文字，
  标题深色、副标题「UTF-8 · 338 B · 可编辑」次级灰、工具条「换行/复制/编辑」深色、状态栏「纯文本」；
  压缩包页面包屑条、提示条、列头「名称/大小」、行图标与状态栏全部为浅色分层，无白底白字。
  深色为对应反色，无黑底黑字。
- 探针踩到的两个假失败已修正并记录：正文内容不在 UIA `Name` 里（走 `ValuePattern`）；
  工具条按钮设了 `AutomationProperties.Name`（=提示语），所以按「源码/渲染」找 Name 找不到——
  这是本项目反复出现的 UIA 命名遮蔽坑，断言应打在 `AutomationId` 上。
- 中途一次深色跑在「用本地服务端地址打开连接页」上 `Invoke` 抛「无法识别的错误」，重跑即通过，
  属探针时序抖动，未计入产品缺陷。
- 遗留观察（未在本版处理）：四张实拍里窗口标题栏的**最小化按钮都带着键盘焦点框**，说明查看器窗口
  初始焦点落在标题栏按钮而不是内容上——按 Enter/Space 会直接最小化，下一版应把初始焦点交给内容。

## 交付物（本地未签名候选，等待实机验收）

- `Fileway-windows-artifacts/20261103-viewers08158/dist/Fileway-0.8.158-win-x64-unsigned-setup.exe`
  277,117,432 字节，SHA-256 `87f3d3da96281ba7fcbc5b35fb16bf312346cac20dd29ad0fd67b09e09123e85`
  （与同目录 `.json` 回执一致）；压缩包 `...-unsigned.zip` 279,743,353 字节，
  SHA-256 `673858b8cda3f73bfc2938d88307f133a4b862979f3d0f9f372089423040822c`。
- NSIS v3.12；未签名、未安装、未发布公开 Release。
