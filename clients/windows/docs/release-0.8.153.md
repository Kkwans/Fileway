# 栖卷 Windows 0.8.153

0.8.152 的「本地服务端」页在用户真机验收后暴露 7 个问题，本版逐条闭环：先如实回显本机已有的服务端、
去掉「共享文件夹」这个错误的默认、部署端口默认 8888、部署前先检查、日志不再每秒闪一次、一键打开 Web 端、
一键免密连接本地服务端。同时随包的 `fileway-server.exe` 第一次真正内嵌了 Web 前端。

## 用户反馈 → 本版结果

1. **“获取不到之前部署的服务端吗？之前明明部署了 8888 端口”** → 新增 `ServerDiscovery`：
   `GetExtendedTcpTable` 枚举本机 LISTEN 端口 → 按进程名（`fileway-server` / `filebrowser` / `fileway`）
   或托管端口筛候选 → 再用 `/health` 的 `{"status":"OK"}` 与 `/` 的 `text/html` 双重确认，才写进
   「检测到的服务端」卡。实测把用户本机那个早就在跑的 `filebrowser.exe`（PID 41764、端口 8888）
   连端口、PID、进程名一起回显，并给出「打开 Web 端 / 连接」，同时明确标注“不是本客户端启动的实例，
   客户端不会停止或修改它”。
2. **“共享文件夹是什么意思？部署默认挂载所有硬盘和文件夹”** → 默认改为挂载整台电脑（服务端虚拟根
   `computer`）。「共享文件夹」从默认可见区消失：部署卡只剩端口 + 管理员用户名 + 管理员密码，
   限定单个文件夹收进「高级：限定共享范围」开关，未勾选时路径行根本不渲染。本机构建卡的字段改名
   「共享范围」，值显示「整台电脑（所有盘符）」。
3. **“部署端口改成默认 8888 而不是 18099”** → `LocalServerLayout.DefaultPort = 8888`，且默认值会基于
   检测结果避开已被占用的端口；实测 8888 被占用时表单预填 8889。用户手动改过端口后不再被刷新覆盖。
4. **“需要先检查之前是否已经部署了服务端”** → 两道检查：打开页面即检测并回显（未部署时标题为
   “检测到本机已有服务端，无需重复部署”）；`DeployAndStartAsync` 在建库**之前**探测目标端口，已有服务端
   应答就直接拒绝并提示换端口或直接连接那个实例，不再留下半个数据库。已部署过的目录（本机
   `%LOCALAPPDATA%\Fileway\Server\local-server.json`）不会被重复部署。
5. **“日志每一秒左右就会闪一次”** → 根因是两处整表重设：跟随定时器每 2 秒 `ItemsSource = rows`，
   加上看门狗每 5 秒**无条件**发一次状态事件又触发一轮重渲染。改为 ①`ObservableCollection` + 首尾公共段
   diff，内容没变时零 UI 变更；②服务侧状态事件按签名去重，签名加入检测到的端口集合；③启动/停止后主动
   失效检测缓存，避免卡片显示过期状态。实测同一份日志静置 6 秒，列表行的 RuntimeId 完全不变。
6. **“新增一键打开 web 端”** → 状态卡与检测卡每行都有「打开 Web 端」，先用 `GET /` 确认返回 200 且
   content-type 是 HTML 才 `Launcher.LaunchUriAsync`，失败给出可操作提示而不是静默。实测默认浏览器出现
   「登录 - File Browser」窗口。
7. **“一键连接服务端，不需要输入用户名和密码”** → 主按钮「连接到本地服务端」经 `GetSignInAsync` 取回
   端点 + DPAPI 解密的管理员凭据，回填连接页后直接发起连接；对外来实例只填地址（客户端没有它的密码）
   并如实说明。实测免密进入工作区，根目录列出 C、D 两个盘。

## 随包服务端内嵌 Web 前端

`eng/Build-GoServer.ps1` 现在先 `pnpm install --frozen-lockfile` → `vite build` → `embed-web.mjs`，再
`go build`；缺 `server/backend/frontend/dist/index.html` 时构建直接失败，不允许产出没有网页的服务端。
回执新增 `webAssets`（2859 个文件 / 54,794,932 字节），`fileway-server.exe` 涨到 82,231,296 字节。

## 两个静默失效的根因

- `GetExtendedTcpTable` 只把 `ERROR_INSUFFICIENT_BUFFER` 作为**返回值**，不设置线程 last error；
  原代码读 `Marshal.GetLastWin32Error()` 拿到 0，于是直接放弃枚举。改为判断返回值。
- `MIB_TCP_STATE` 里 1 是 Closed、**2 才是 Listen**；原常量 `ListenState = 1` 会把所有监听端口滤空。
  改为 2 并在代码里留下注释。

两个缺陷叠加时的表现就是“检测不到任何服务端”，而异常又被上层吞掉，只能靠脱离 App 的独立复现脚本
（`Probe-TcpTable.ps1` / `Probe-TcpDump.ps1`）才定位到。教训：探测类代码不能只写不验。

## 验证（Release x64，0 警告 0 错误）

- UIA 功能回归 **24 项全通过**（`20261102-server08153/verify5`、`verify6/report.txt`）。关键项：检测卡回显
  8888 + PID + 进程名、端口预填 8889、路径行默认不渲染且勾选后出现、部署后 8889 运行中、共享范围显示
  「整台电脑（所有盘符）」、日志静置 6 秒行不重建、浏览器打开服务端网页、免密连接后工作区列出 C/D 盘、
  停止后外来 8888 依然存活且仍被回显。
- 两次启动冒烟 15 秒 / 20 秒均 `GATE=PASS`（`data/smoke-c`、`data/smoke-d`：Responding=True、可见主窗标题
  含“栖卷”、无 `startup.log`）。
- **未取得**：实拍截图与数值几何复核。验证期间 Windows 会话再次锁定，`CopyFromScreen` 只拍到锁屏画面，
  且锁定时多个元素的 `BoundingRectangle` 为空。这两类证据被明确记为 INCONCLUSIVE 而不是“通过”，
  解锁后重跑 `Probe-Shots3.ps1`（浅色 / 深色）与 `Probe-Geometry2.ps1`。

## 安装包（本地未签名候选）

- `Fileway-windows-artifacts/20261102-server08153/dist/Fileway-0.8.153-win-x64-unsigned-setup.exe`
  277,113,653 字节，sha256 `55b43e2cfaa36acf6ce435c149a6e036676f1954f72d562207d0dfc358b61f39`
- 同目录 `Fileway-0.8.153-win-x64-unsigned.zip` 279,739,438 字节，
  sha256 `f6d9e4fea1cc02efcd56cc60172e1fcb8e691d985047c7c740eac59c55eb6ccf`
- 随包 `fileway-server.exe` sha256 `1e4a112facc6e0a999535278ac4b4ffffb6dbdf7128c65de0c7894697a92732e`，
  与 `server-build.json` 回执逐位一致（已用 `sha256sum` 复核）；回执 `serverVersion` 为 0.8.153，
  `buildCommit` 为 `3a451ddc`，服务端源码树 `workingTreeDirty = false`。
- NSIS 3.12（核对过归档哈希），未签名，`installed = false`。

## 已知边界

- 检测只覆盖进程名为 `fileway-server` / `filebrowser` / `fileway` 的监听进程，或恰好等于托管端口的进程；
  其他命名的 Fileway 派生服务端不会被认领（避免对全部端口发探测请求）。
- 旧部署文档缺少 `mountsWholeComputer` 字段时按新默认（整台电脑）处理：用户在 0.8.152 里那份 18099 部署
  下次启动会挂载整台电脑，而不是原来那个空的 `files` 目录。schema 仍为 1，避免旧文档被判成“未部署”后
  撞上“数据库已存在但不是本客户端创建”的死路。
- 深色与高对比度实拍仍缺；开机自启仍是当前用户 Run 项（普通权限），故障自动重启仍依赖客户端在运行。
- 安装包为本机未签名候选，不是 GitHub Release、不是商店版本。
