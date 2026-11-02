# 栖卷 Windows 0.8.157

用户反馈：「视频播放窗口调整大小的操作体验很差……是点击触发调整大小模式，非常逆天反人类的操作，
应该改成长按」。这条批评在 0.8.156 上被实测复现，而且比描述的更严重。

## 先复现，再动手

同一套断言跑在 0.8.156 的播放器上（`20261103-resize08157/player-base`）：

- 在窗口左边缘（隐形 sizing 边框带上）**按下再抬起、完全不移动** → 尺寸不变，看起来没事；
- 但紧接着**不按键**只移动鼠标 → 窗口宽度从 1100 被拉到 720。

也就是说单击之后窗口进入了系统 sizing 模态循环，而循环永远收不到配对的按键抬起，
于是窗口"粘"在光标上，必须再点一次才能甩掉 —— 正是用户描述的现象。后续所有拖动都被这次
残留的循环吃掉，所以"拖动调整大小"本身反而不可用。

根因有两层：

1. 无边框窗口的 sizing 边框是**隐形**的，而且盖在画面/HUD 之上，用户根本不知道那里可以拖；
2. 播放器的 HUD 是独立窗口。按下边缘时由 HUD 的 XAML 输入合成一条 `WM_NCLBUTTONDOWN` 用
   `PostMessage` 发给主窗口，主窗口的模态 sizing 循环因此拿不到真实按键抬起（抬起被 HUD/island
   一侧消化），循环不会自然结束。

## 修了什么

1. **文档类无边框窗口（图片 / 文本 / 压缩包 / PDF）新增 `Windowing/EdgeResizeGate.cs`**：
   拦下边缘上的 `WM_NCLBUTTONDOWN`，先自己捕获鼠标；只有指针真的拖动超过 `SM_CXDRAG`
   （按窗口 DPI 取）才把这条按下**原样重放**给原窗口过程，让系统照常进入 sizing 循环；
   没超过阈值就当作普通点击吞掉。标题栏（`HTCAPTION`）与最大化按钮（`wParam==9`）不受影响。
   接线点只有一行：`FramelessWindowChrome.WindowProcedure` 在 `WM_NCHITTEST` 之后问一次门控。
2. **播放器不能用系统模态循环**（抬起消息收不到，这是结构问题），改为 HUD 自己按拖动增量
   `SetWindowPos` 主窗口：按下边缘记录命中方向与起始窗口矩形，16ms 定时器跟踪
   `GetPhysicalCursorPos` 与 `GetAsyncKeyState(VK_LBUTTON)`，抬起/取消时结束。
   用定时器而不是 XAML `PointerMoved`，是因为窗口一边变大一边指针就移出了本窗口，
   而尺寸变化会让 island 丢了指针捕获 —— 事件流不可靠。
   拖动期间补发 `WM_ENTERSIZEMOVE` / `WM_EXITSIZEMOVE`，让既有的"用户调整过窗口"标记与
   尺寸持久化逻辑照常工作；最小尺寸用 `SM_CXMINTRACK` / `SM_CYMINTRACK`。
3. 单击边缘现在**什么都不做**（既不改变尺寸，也不残留捕获），符合"要调整就得按住拖"的预期。

## 验证

- **图片窗口** `20261103-resize08157/Probe-ResizeGate.ps1` → `run4/report.txt`，10 项断言全 PASS：
  单击左边缘尺寸不变（1300/1100 → 1300/1100）、释放后无残留鼠标捕获、单击后移动鼠标不改变尺寸、
  单击右下角尺寸不变、拖动左边缘 140px → 宽度 +126、松手后调整立即结束、拖动下边缘 120px → 高度 +108、
  窗口仍可响应、标题栏拖动仍然移动窗口（top 60 → 150，宽度不变）。
- **播放器** `Probe-ResizePlayer.ps1` → `playerB/report.txt`，11 项断言全 PASS：
  单击左边缘 / 右下角均无副作用、无残留捕获、单击后移动鼠标不改变尺寸（这条在 0.8.156 上是 FAIL）、
  拖动左边缘 150px → 宽度 +150、松手后不再跟手、拖动下边缘 130px → 高度 +130、窗口仍响应。
  夹具是真实短片 `h264-aac.mp4`（复制为 `回归-短片.mp4`），实拍
  `playerB/player-after-resize.png`（1250×890）显示视频画面与 HUD（顶部标题、底部控制条、进度条）
  都按新尺寸正确重排。
- Release x64 构建 0 警告 0 错误；两次启动冒烟 `GATE=PASS`（15s / 20s，独立数据根，无 startup.log）。

## 踩坑记录（避免下一轮重犯）

- `SM_CXMINTRACK` / `SM_CYMINTRACK` 是 **34 / 35**，不是 16 / 17。用错成 16/17 时拿到的是全屏
  宽高（150% 缩放下 2560×1494），结果"拖 150px 直接把窗口撑满整屏"。
- 自己 `PostMessage` 合成的 `WM_SYSCOMMAND(SC_SIZE|HTxxx)` 会落进**移动**模式而不是调整大小；
  要启动系统 sizing 循环，正确做法是把原始 `WM_NCLBUTTONDOWN` 用 `DefSubclassProc` 原样重放。
- 无边框窗口的边缘输入由 XAML island 转发：主窗口的子类过程**收不到** `WM_MOUSEMOVE`，
  所以在主窗口上 `SetCapture` 等事件的门控只对"输入本来就落在该窗口"的情况有效（文档类窗口成立，
  播放器不成立，因为播放器上面还压着 HUD 窗口）。

## 交付物（本地未签名候选，等待实机验收）

- `Fileway-windows-artifacts/20261103-resize08157/dist/Fileway-0.8.157-win-x64-unsigned-setup.exe`
  277,117,421 字节，SHA-256 `fe5a203ebf7df655239c09ce76b4f26ce263ceacfe8cde231030f230b3717584`
  （与同目录 `.json` 回执一致）；压缩包 `...-unsigned.zip` 279,743,982 字节。
- NSIS v3.12；未签名、未安装、未发布公开 Release。
