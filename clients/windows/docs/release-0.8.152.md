# 栖卷 Windows 0.8.152

0.8.152 新增「本地服务端」页面：客户端自带一份 `fileway-server.exe`，可以在本机一键部署、启动、停止，配置登录自启与故障自动重启，并查看按错误/普通分级的服务端日志。这是客户端第一次拥有服务端的生命周期，因此本版把工作重心放在“不误伤别人的进程”和“状态必须说真话”上。

## 用户能看到的

- 侧栏底部新增「本地服务端」入口（Lucide `server`，与存储工具 `gear`、文件任务 `file-code` 不重复）；未连接时连接页底部也有「本机还没有可连接的服务？部署本地服务端」入口。
- 页面三态自洽：未部署 → 部署表单（端口 / 共享文件夹 / 管理员账号，密码随机生成可重生成、可显示）；已部署 → 状态卡 + 本机构建 + 启动与账号 + 服务端日志。
- 状态卡覆盖 8 种真实状态：运行中 / 已停止 / 正在启动 / 正在停止 / 异常退出 / 端口被其他进程占用 / 服务端组件缺失 / 尚未部署；错误同时给图标与文案，不只靠颜色。
- 主按钮随状态变化且永远只有一个、永远在最右：运行中是「连接到本地服务端」，停止后是「启动服务」，失败后是「重试启动」。
- 运行中可直接把本机地址与用户名回填到连接页（地址由基础设施层给出，页面不自造 URL）。
- 日志区：全部 / 仅错误分段、关键字筛选、自动跟随、`打开日志文件夹`；运行日志与诊断日志分通道标注。

## 实现要点（含一次实测纠错）

- 打包：新增 `eng/Build-GoServer.ps1`，`CGO_ENABLED=0` 构建 `server/backend`，注入 `version.Version=Fileway-0.8.152`/`CommitSHA`，产出 `server-build.json`（版本、提交、go 版本、sha256），与 `host-build.json` 同规格；csproj 以 `Content` 随包发布并在 `ValidateFilewayGoBinaries` 目标里强制存在，`Package-Preview.ps1` 的必需清单加入两份文件。运行时按回执校验哈希，文件变化才重新哈希。
- 引导：实测确认 `--password` 走 quickSetup 时按“已哈希”入库、不能传明文，因此改为 `config init` + `users add --perm.admin=true --scope . --locale zh --viewMode mosaic`（`--perm.admin true` 的空格写法会被 cobra 当成第三个位置参数，必须用 `=`）。数据库已存在但不是本客户端建的 → 直接拒绝而不是接管；`users add` 失败会删掉本次刚建的库，避免留下无法重试的半成品。
- 进程：`CreateProcessW` + `CREATE_NEW_CONSOLE` + `SW_HIDE`，stdout/stderr 以可继承句柄追加到日志文件（不用管道，客户端退出不影响服务端）；不加 kill-on-close Job，服务端按设计要能活过客户端。
- 停机（本版最重要的纠错）：先后试出 CTRL_C 广播会连带客户端自己、`SetConsoleCtrlHandler` 回调链是**控制台共享**的（父进程先返回 true 会把事件吞掉，导致服务端永远收不到），最终改为 **CTRL_BREAK + 指定子进程 PID**——Go 把 CTRL_BREAK 映射为 SIGINT，服务端本就捕获。实测 `local-server.json` 记录 `停止事件在 1ms 内投递，进程在 252ms 内退出`，`server.log` 出现 `Got signal: interrupt` → `Graceful shutdown complete.`，退出码 0。
- 收尾保护：一旦监听已停但进程仍在收尾，判为 `Draining` 不再强杀；由用户点停止的进程永远显示「已停止」，不会被误报成「异常退出」。
- 开机自启：本机非管理员下 `schtasks /XML` 与 COM `Register-ScheduledTask` 均返回“拒绝访问”，因此改用当前用户 `HKCU\...\CurrentVersion\Run`（实测写入/回读/删除均正常），开关状态以“注册表里的命令与本部署参数完全一致”为准，参数变了会在下次启动时同步。故障自动重启由客户端看门狗承担（5 秒轮询、退避 2/4/8/16/32→60 秒、连续 5 次失败自动暂停并提示），页面文案如实写明“关闭客户端后不再重拉”。
- 日志分级：Go 侧日志无等级前缀，故客户端按关键字归类（error/failed/panic/bind:/timeout… 为错误，warning/deprecat… 为警告），并在 UI 明示这是关键字归类而非服务端契约；诊断通道单独标注，避免把 `No config file used` 之类启动信息误判成错误。
- 凭据：管理员密码只以当前 Windows 用户 DPAPI 加密存在数据根下，页面需主动点“显示”才可见；文档、日志、状态文件均不含凭据。

## 设计系统增量

- 新增 `FwCodeTextStyle`（Cascadia Code 13/21）与 `FwCodeCaptionTextStyle`（12/18），落实 DESIGN.md 的 `code-md`：日志、路径、端点一律等宽。
- 新增 6 个 Lucide 资源：`server`/`activity`/`terminal`/`alert-triangle`/`hard-drive`/`power`（`eng/Import-LucideIcons.cjs` 固定同一 commit 重新导入，35 个旧图标逐字节复现未变，说明导入链路可复现）。
- 窄窗口（<900 DIP）「启动与账号」卡整体换行到「本机构建」下方并占满两列，不把两张卡挤窄。
- 几何自检修掉一处真实缺陷：部署卡里 `选择…`/`重新生成`/`显示密码` 三个按钮原先用 `-16` 负边距“贴齐”卡片，实测凸出内容边界 16 DIP；改为正常边距后 UIA 右边缘 1474 与卡片内容右边缘一致。

## 验证与证据

- Release x64 + Publish：**0 警告、0 错误**（FileVersion 0.8.152.0）。
- 两次启动冒烟 `GATE=PASS`（pwsh 7，15 秒窗口存活，独立数据目录，无 `startup.log`）。
- 端到端 UIA 功能回归 **31 项全通过、0 失败**：`20261101-servermgmt01/probe/verify11/report.txt`。覆盖部署→健康检查→看门狗重启→开机项写入/删除→密码显示→错误筛选→连接回填→优雅停止→再从停止启动→端口被外来进程占用时拒绝操作并如实提示→外来进程离开后恢复。
- 几何回归：`20261101-servermgmt01/geom3/geometry.txt`（1500×1000 与 1180×900，页边距/基线/右边界/窄栏换行/无溢出）。
- 隔离测试：全程使用 `FILEWAY_WINDOWS_DATA_ROOT` 与 `FILEWAY_LOCAL_SERVER_DATA_ROOT` 专用目录，未读写真实客户端数据根 `Fileway\Preview`；一次探针漏设服务端数据根变量，误在 `%LOCALAPPDATA%\Fileway\Server` 建了一份测试部署，已确认创建于本次探针时间窗并整体删除，注册表启动项为空。
- 产物：`.../20261102-server08152/dist/Fileway-0.8.152-win-x64-unsigned-setup.exe`（250,177,242 字节，含服务端后比 0.8.151 大约 +36 MB），SHA-256 `1a8fc2a4d4ae44b36b1bdb675b6a349f35d0ecb021ea3c85bbd7e3a5674339d1`，与回执一致。

## 已知问题

- **本版没有实拍截图**：验证期间 Windows 会话反复锁定，`CopyFromScreen` 只能拍到锁屏界面，于是改用 UIA 边界矩形做像素级几何核对。视觉观感（字体渲染、卡片留白、日志行高）仍需真机确认。
- 高对比度下的新页面未走查（同样受锁屏影响），日志等宽字与状态卡在 HC 的对比度待补拍。
- 随包服务端不含 Web 前端产物（`server/frontend/dist` 只有占位文件），因此本机部署后浏览器打开地址是 404；桌面客户端走 API 不受影响。
- 故障自动重启依赖客户端在运行（默认“关闭窗口后保留在托盘”场景下成立）；纯开机后台、客户端不打开时的崩溃重启没有实现，因为普通用户无法注册计划任务。
- 端口/共享文件夹不提供“已部署后改配置”，要换端口需停止后重新部署；日志按关键字归类不是服务端等级契约。
- 整棵文件夹树上传、桌面拖拽、PDF 沉浸式外壳三项历史待办仍未动。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
