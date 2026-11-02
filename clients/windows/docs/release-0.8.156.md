# 栖卷 Windows 0.8.156

用户点名图片查看窗口：「UI 样式差，排版布局也差，而且为什么默认浅色主题，背景却是黑色的？」
这条批评同样成立，而且根因不是某一处颜色写错，是**主题取值机制**用错了 —— 本版把它连同查看器外壳的三处
排版问题一起收掉。

## 为什么浅色主题会得到黑色画布

应用切主题时只设 `Shell.RequestedTheme`，从不设 `Application.Current.RequestedTheme`（这是 WinUI 3
多窗口应用的正确做法）。而代码里大量 `Application.Current.Resources["FwCanvasBrush"]` 这种写法取的是
**应用级资源字典**，它跟随的是系统主题，不是窗口主题。系统浅色 + 应用深色 → 拿到浅色画刷；
系统深色 + 应用浅色 → 拿到深色画刷。图片查看器还额外在构造里硬编码了 `#0B0C0F` 画布并把根
`RequestedTheme` 钉成 Dark，所以无论用户选什么主题它都是黑的。

WinUI 3 没有 `SetResourceReference`，运行时创建的控件无法直接绑定 ThemeResource。正确做法是把画刷包进
**带 ThemeResource setter 的 Style**，再给控件赋 `Style` —— 样式 setter 里的 ThemeResource 会在元素
实际主题确定时重新解析。

## 修了什么

1. **画布跟随主题**：新增 `FwViewerCanvasStyle` / `FwViewerChromeStyle`（setter 用
   `{ThemeResource FwCanvasBrush}`），`ViewerChrome` 与 `ImageWindow` 根网格改赋 Style；删掉
   `ImageWindow` 里硬编码的 `#0B0C0F` 与 `RequestedTheme = Dark`。`SetTheme` 现在把主题同时下发给
   根网格、标题栏与查看器外壳，窗口内所有表面各自解析 ThemeResource。
2. **浅色主题下 chrome 文字不可读**：`FwScrimTextBrush` / `FwScrimMutedBrush` 原先只在深色字典里有定义
   （白色），浅色主题回落到白色 → 白底白字。浅色字典补上对应深色值（`#FF1B1F24` / `#FF5C6470`），
   缩放倍率标签与胶囊文字改用 `FwScrimBodyTextStyle`，分隔线改用 `FwDividerStrongStyle`。
3. **顶部信息条与标题栏重复同一个文件名**：标题栏已经显示「回归-样张.png · 栖卷」，信息条再显示一遍是
   纯浪费。信息条改为单行元信息 `2 / 2 · 1290 × 1023 · 77.34 KB`。
4. **加载/错误时 chrome 自动隐藏**：空闲计时器会在 2.6s 后把顶部条和胶囊淡出 —— 错误卡还挂在屏幕中央，
   工具条却消失了。新增 `_holdsChrome`，加载中与错误态下禁止自动隐藏，`ClearError()` 才释放。
5. **没有图片却显示缩放工具**：加载/错误态下胶囊显示「− — + | 适应 1:1」，倍率是个「—」占位，四个按钮
   全是死的。新增 `ViewerChrome.ShowsZoomBar`，未解码成功前整条胶囊隐藏。
6. **错误卡排版**：改为 56 DIP 图标容器 + `alert-triangle`（danger 色）+ 15px SemiBold 标题 + 说明 +
   重试按钮，全部居中；此前是左对齐文字块配一个「重试」，视觉上像半成品。
7. **错误文案甩锅服务端**：任何解码异常都显示「操作未能完成。请检查服务器连接后重试。」，而图片打不开
   最常见的原因是文件本身损坏。现在只有 `FilewayException` / `TaskCanceledException`（真的是服务端或
   网络问题）沿用原文案，其余显示「文件内容无法解码，可能已损坏，或不是受支持的图片格式。」

## 验证

- **实机 UIA + 截图**：`20261102-server08156/Probe-ImageViewer2.ps1` 在隔离数据根下部署一个只共享夹具目录
  的服务端 → 一键免密连接 → 分别打开一张正常 PNG 与一张 8 字节的坏 PNG。10 项断言 × 浅色/深色两轮
  **全部 PASS**（`light9/report.txt`、`dark2/report.txt`）：端口预清、部署运行、夹具可达、窗口打开、
  像素尺寸已解析、错误态出现、两张截图落盘。
- **实拍 4 张**：`light9/image-viewer.png`、`light9/image-error.png`、`dark2/image-viewer.png`、
  `dark2/image-error.png`。浅色主题下画布、顶部条、胶囊、错误卡全部为浅色且文字为深色可读；深色主题
  对应反色。错误态下顶部条/翻页键仍在、缩放胶囊已隐藏。
- Release x64 构建 0 警告 0 错误（`build-08156b.log`，`已成功生成`）。
- 探针本身踩到的坑记下来复用：夹具 PNG 是一张「服务端页截图」的截图，肉眼极易误判成残影/未渲染；
  查看器窗口必须与主窗**不重叠**摆放再截图，并先移动指针唤醒自动隐藏的 chrome，否则拍到的是隐藏后的画面。

## 顺带记录

- `DESIGN.md` 的 `canvas` 与 `scrim` 两条规则已改写：查看器画布随主题切换，视频播放器与文本/压缩包
  阅读面是**刻意**固定的深色表面。后两者目前仍是硬编码深色（`TextWindow.ApplyTheme` 里有一整段显式
  颜色），如果下一轮主题走查认为它们也该跟随，需要单独一刀处理，不能顺手改。
- 加载态与错误态共用同一条隐藏胶囊的代码路径，本轮只拍到了错误态的实拍。
- 下一刀：播放器（以及所有无边框窗口）的**边缘误触调整大小** —— 隐形 sizing 边框带约 8–16 物理像素，
  单击即进入系统 sizing 模态循环，用户点一下边缘想暂停视频结果把窗口拽歪。应改为「按住并拖动超过
  SM_CXDRAG 才进入调整大小」。
