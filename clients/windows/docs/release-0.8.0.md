# 栖卷 Windows 0.8.0

账号与管理里程碑；本地未签名预览。按用户 2026-10-25 决定：本版发布后暂停功能开发，进入全面实机验收阶段。

## 本版新增（0.8.x）

- **账号密码修改**：应用菜单按服务器认证方式出现（仅 json 认证），对话框收集当前/新密码，经服务端用户 API 条件修改自身密码（当前密码必验、最小长度取自服务器能力）；
- **管理员用户管理**：存储工具第三标签（仅管理员可见），服务端用户列表/创建（权限勾选）/重置密码/管理员切换/密码锁定/删除，每次改动需输入管理员密码由服务器校验；
- **本地化基础设施**：中文为源语言的字典与语言切换（跟随系统/简体中文/English），未迁移界面诚实保持中文。

## 交付口径

0.7.0 之后实际增量即上述三项，版本号如实为 0.8.0。

历史版本（0.3.5—0.7.0）的完整功能清单见各 release-*.md 与 release-0.7.0.md；本版不重复罗列。播放器退出 C0000409 此前归因为外部环境钩子；2026-10-27 本机捕获纠正了该判断，并在 0.8.1 修复媒体 OpenGL 提供方选择，见 [当前分析](player-exit-cfg-analysis.md)。

## 启动崩溃修复（2026-10-25 晚复验，阻断发布）

`20261025-release080` 与 `20261025-release080-fix1` 目录的安装包启动后 8—15 秒静默退出，
为两段缺陷叠加；`20261025-release080-fix2` 已修复并重新打包：

1. **构造期托管异常（743eef84）**：0.7-a 在 `InitializeRemoteTools()`（MainPage 构造函数内）
   调用 `StorageAnalysisPane.Attach(_storageAnalysis ?? throw ...)`，而服务要到
   `SetServicesAsync` 才注入，构造期必为 null 并抛 `InvalidOperationException`（0xC0000271）。
   改为在 `UpdateRemoteToolsContext()` 内守卫式幂等附加。
2. **XAML 解析失败（224ad39a）**：`StorageAnalysisControl` 构造函数把 `duplicatesHost` 与
   `adminHost` 先加入 `analysisHost`、后又加入 `root`，抛 `XamlParseException: Element is
   already the child of another element`（HRESULT 0x802B000A），触发 `0xC000027B`
   stowed exception failfast。移除 `analysisHost` 中的两次重复挂载，三标签页显隐逻辑与
   root 布局保持一致。
3. **缺失图标资源（83dca1a7）**：侧栏存储工具引用不存在的 `database.png`，改用现有
   `file-code` 图标；此项不是本次崩溃根因，独立 database 美术资源留待验收后补充。

复验口径：Release 零警告零错误；publish 目录启动冒烟 15 秒与 20 秒均存活、
Responding=True、EnumWindows 可见主窗标题含“栖卷”，无 `diagnostics/startup.log`。
0.8.0 功能增量未变，版本号保持 0.8.0，以新交付目录与哈希区分。

本地未签名交付（仓库外）：

- `Fileway-windows-artifacts\20261025-release080-fix2\delivery\Fileway-0.8.0-win-x64-unsigned-setup.exe`
  （SHA-256 `6f80168ee48ff33912ea46a73b6b60cb1903d9dd44aeb539339ffdd9c25ca2eb`）
- `Fileway-0.8.0-win-x64-unsigned.zip`
  （SHA-256 `984a13ec1fdb3e4c8ca5c2cbad115f077c728660292592562dc8f6c352c0b3ef`）

## 按用户决定后置的范围（不在本版本）

- 网络与完整性全套：系统/PAC/手动代理、内嵌 tsnet、证书、外置播放器、休眠换网、
  HDR/多屏/DPI、手动更新/诊断（2026-10-25 决定后置）。
- 全量英文本地化（基础设施已就绪，按需迁移）；条件注册/验证码、管理员命令白名单；
  B/C 多风格主题（待视觉方向确认）；EPUB；归档包内搜索与条目临时打开。

## 跨端对齐（按用户 2026-10-25 指令复核）

对齐矩阵见 [cross-platform-alignment.md](cross-platform-alignment.md)（对照 Android
源码逐屏复核）。结论：**其余功能 Windows 均已实现或有等价超集**；仅剩三项可选
小项（文本编辑器编码/换行保持、查看器内文本搜索、独占文件创建）与两个可选增强
（音频独立队列、规则批量重命名核对），均不阻塞收敛，留实机验收后由用户决定。
按用户指示排除：归档搜索/成员预览、管理员规则与账号偏好、更新检查（Windows
安装包未公开发布，无检查对象）、网络全套、全量英文本地化、条件注册/命令白名单、
EPUB、B/C 主题。

## 发布后阶段规则（用户 2026-10-25 确认）

1. **0.8 对齐切片完成后暂停功能开发**，进入全面实机验收：0.3.5—0.8 各功能逐项
   人工验收（下载/续传/重启恢复、文件夹上传与拖入、ZIP 导出与包内浏览解压、播放器
   全套、文本编辑与条件冲突、存储分析、重复清理、管理员用户管理与规则、密码修改、
   安装包升级覆盖）。
2. 验收产生的优化完善项列成详细清单，经用户确认优先级后才继续执行（小版本精修）。
3. 未验收项不标通过；已知问题如实记录在案。
