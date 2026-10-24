# 单文件 fresh 下载与 0.3.5 Range 续传

`FileDownloadRunner` 连接下载源、schema 6/7 持久任务与 Windows 本地文件能力。
明确确认的单文件默认从头读取，`Fail` 不覆盖；服务端声明 `conditionalDownloadV1` 时，
下载从准备阶段起就绑定 opaque `sourceVersion`（快照写入 checkpoint.Source），这正是后续
断点续传的身份前提。旧服务端无该能力时保持原 fresh-only 语义，续传入口对其不可用。

0.3.1 增加当前进程活动下载的暂停/继续。0.3.5 增加跨进程/跨重启的 Range 断点续传：
进程内暂停仍保留原 HTTP 响应与句柄（不是续传）；挂起、崩溃或可重试失败后，
由持久证据（pinned sourceVersion + 已确认 flush 偏移 + 已持有的 partial 文件身份）授权
`ResumeAsync` 重新进入同一 FIFO 单 writer 队列。列表与本地核实本身不会发起下载或续传。

## Range 续传契约（0.3.5）

- 续传准入是持久证据而非 UI 状态：`Source.SourceVersion` 非空、`Files` 含未发布
  partial 身份、`ConfirmedOffset` 已确认、无 Uncertainty；相位为 Paused/Running(陈旧 run)/
  Reconciling(本执行器自己的恢复 claim)/WaitingNetwork/WaitingAuthentication/Failed。
  恢复执行先在本任务 OS 锁下把意图翻回 Run，再以 recovery claim 围栏陈旧 run。
- 重新观察源：版本、长度或修改时间与快照不一致即拒绝续传（`SourceChanged`），
  删除 partial 收束任务；版本变更绝不续传。校验和经
  `CalculateConditionalDownloadChecksumAsync` 绑定同一版本（前后观察与 pinned 版本一致）。
- partial 以 `OpenOwnedAsync` 重开并核对 128 位身份；长于 `ConfirmedOffset` 的未确认
  尾部先截断（同句柄 `FileEndOfInformation`），短于确认偏移视为证据损坏，删除并失败。
- Range 请求要求精确 `206` 与 `Content-Range: bytes offset-(total-1)/total` 且响应
  `X-Fileway-Source-Version` 与 pinned 版本一致；对 Range 请求返回 `200` 一律拒绝
  （不追加、不盲目重开），`412/416` 按源变化处理。
- 版本化接收每 `CheckpointInterval`（默认 30 秒）flush 并持久化确认偏移，崩溃最多
  损失一个间隔的已接收字节；完成校验仍从持有句柄重读全文件 SHA-256，前缀不需要
  可序列化的哈希状态。
- 失败语义：版本化任务的取消以外失败保留 partial 并落入可恢复相位
  （认证过期→WaitingAuthentication、网络/超时→WaitingNetwork、其余→Failed 并保留错误）；
  显式用户取消与源变化仍删除 partial。发布不确定路径不变（先核实）。
- 退出排空优先"挂起"：对符合条件的版本化执行持久化 Pause 意图、等待有界的
  flush 确认后停止，保留 partial 与确认偏移（相位 Paused、释放 run）；不符合条件的
  执行保持原取消清理语义。发布中（Publishing）的任务不挂起，仍走核实路径。
- 传输中心的继续按钮按证据路由：进程内暂停（Running+Pause 意图）走原暂停控制，
  其余可恢复任务走 `ResumeAsync`。"连接后自动继续未完成的下载"是存储设置中的
  显式偏好（schema 7，默认关闭），开启后每次连接把有持久证据的中断任务批量重新
  排队（`ResumeEligibleAsync`，仍受队列上限约束）。

已恢复执行的 partial 不提供边下边播读取准入（`OpenOwnedAsync` 不授予 reader admission）；
边下边播与历史完成下载重开保持各自独立边界。

0.3.4 的应用层接受当前进程内最多 100 个已确认 fresh 任务，按 FIFO 单 writer 执行。
批量确认前只预检，不创建或开始下载；确认后目录浏览不取消已确认的清单，但账号、会话、权限及退出仍使其失效。
未开始项的取消使用任务 OS 锁及原子本地取消事务，不等待其他大文件；不能因此宣称跨重启恢复或目录批次已实现。

## 当前进程暂停与删除（0.3.1）

`IActiveDownloadPauseControl` 和可选 `IDownloadControlService` 保持原执行器唯一所有权。
暂停先 CAS 保存 Pause 意图，执行器 flush 原持有句柄并保存确认偏移后才发出暂停确认；
保留 run、HTTP 响应与同一文件句柄，不能使用会释放 run 的通用 Paused checkpoint。
继续恢复 Run 意图并等待执行器确认，避免快速暂停/继续覆盖前一轮信号。
取消会唤醒暂停等待，仍遵循原发布及取消语义。暂停不重新 GET、不重开 partial；
长暂停导致上游连接断开时保持安全失败。进程内暂停不是 Range 续传；退出时的挂起与
崩溃后的重启恢复见上方 0.3.5 契约。

传输中心先展示下载，使用接收字节增量计算实时速度与预计剩余时间，完成时间和耗时来自持久记录。
删除运行任务先取消并等待原执行及播放 reader 排空；仅允许按 revision 删除无 run、无未知结果的终态记录。
已完成任务可额外选择删除本地文件，默认不选；通过原持有身份验证流程核对卷、文件和祖先目录后删除，
不删除 NAS 原文件、不删除身份变化的同名文件。延迟进度事件不能把已移除任务重新加入列表。

## 本地保存与发布

1. 取得现有每任务 OS 执行锁，再领取 SQLite run/generation；数据库的三项总执行预算
   同时包含上传与下载。本执行器仅允许一个活跃任务。
2. 固定已存在的本地目录与祖先，以相对目录句柄、随机名称和 `FILE_CREATE` 创建
   同目录 partial。碰撞只换随机名称，绝不打开或截断碰撞项。
3. 检查普通文件、单硬链接、最终路径、卷与 128 位文件身份；保持独占读写/DELETE 句柄。
   当前仅接受固定本地卷。UNC、映射网络盘、重解析点及不能证明归属的目标失败关闭。
4. 回环租约只允许完整 GET 200；不带 Range，不跟随重定向，不使用代理、Cookie 或自动解压。
   检查响应长度及真实 EOF，拒绝短读、多读和意外 Content-Range。超时针对无进展，
   不把大文件总时长限制为三十秒。
5. 写入完成后 flush，再从同一持有句柄重读 SHA-256。收到的字节只是活动进度；
   ConfirmedOffset 只有 flush 成功后才能前进。满进度仍不能表示校验或保存完成。
6. 核对服务器当前 SHA-256 与源观察，先持久化 Publishing/LocalPublish，再进行一次
   handle-based、同目录、不替换目标的 rename。身份和摘要证据齐全后保存 Completed。

没有复制替代原子 rename 的退路，没有覆盖既有目标的退路。flush 与 rename 不构成
对任意磁盘、过滤驱动或断电情况的普遍持久性承诺。shared/core 与 server 已加入 additive
conditionalDownloadV1 契约基础：能力清单声明该能力，资源元数据可携带 opaque sourceVersion，
raw 读取返回相同观察值，请求携带不匹配的 X-Fileway-Source-Version 时返回 412，Go broker
透传该请求/响应头；raw 也接受受控的 sourceVersion 查询参数，bridge 的 lease 命令已能将
可选 SourceVersion 安全编码到该参数。Windows 播放读取现在通过独立的可选接口消费该能力：
每次读取先重新观察版本，再用版本绑定的临时 lease 和 Range 响应头核对；旧服务器或不支持
该接口的 provider 继续使用原 fresh-only 路径。该切片只保护边下边播的读取一致性，不改变
下载 runner、partial checkpoint、续传或启动自动恢复语义，因此仍不能宣称断点续传已经启用。

契约提交 `fcb251d3` 已通过 Unified server/Web 的 lint、Web、Linux server 和 Windows server
门槛，以及 Go transport 回归；这证明新增头部和能力字段在跨平台构建中可用，不代表客户端已
获得续传语义。Windows 播放消费切片提交 `fdb8727f`，随后由 `a162994c` 修复响应头遍历编译
问题、`ba212789` 修复测试引用；Windows native run `39086956881` 在同一候选 SHA 上通过
Release/x64 编译和 Windows infrastructure 测试。该证据仍不替代真实 NAS 播放器画面和任意
容器的原生验收。

专用 progressive decoder run `39087415443`（候选提交 `201bd281`）已通过固定 Y4M 前置头的真实
libmpv 解码、前缀耗尽等待、追加后恢复、暂停期间追加、取消和销毁生命周期。该 harness 使用
`vo=null`，只证明固定夹具的解码与 reader 生命周期，不证明可见画面、通用 MP4/MKV、任意跳转
或原生播放器窗口退出稳定性。

## 取消与不明结果

取消先持久化意图并排空请求和文件 I/O。只能通过已确认属于任务、尚未发布的原生句柄
删除 partial；不以持久化路径调用任意删除。Dispose 释放资源，不推导删除权限。
rename 已发生时保留最终文件与完成事实，迟到取消不能删除已发布文件。

创建目标或获取源租约迟到时仍完成已取得能力的释放。清理 partial 失败必须保留已验证的
归属证据并进入本地结果未知；归属持久化失败则保留受保护执行记录，不能误确认取消。
迟到源租约释放失败不会遮蔽原取消或绕过本地清理；先完成文件状态的持久化收束，再向
调用方报告脱敏的释放错误。文件状态保存失败优先报告，不能重试 GET 或任意删除路径。

发布回复或记录写入不明时，显式本地核实取得原 OS 锁，重新证明 partial/final 的身份、
长度与已存摘要。已发布则收束完成，确认未发布则按原取消/停止意图清理自身 partial。
身份不明则保留 OutcomeUnknown。核实不 GET、不续传、不重复 rename、不删除陌生文件。

## 应用与界面

单文件菜单支持“下载另存为”：只编辑本地文件名，仍使用已生效的默认下载目录。
先通过 `PrepareAsAsync` 取得实际目的路径及大小的只读预览，再明确确认开始下载；
原远端账号、会话与 wire 路径不变，不修改服务器名称，也不覆盖同名本地文件。
无效 Windows 叶名（路径分隔符、ADS、保留设备名、非法 Unicode、尾部空格或句点等）
在准备阶段拒绝。编辑、关闭或切换连接使旧预览失效，正常默认下载入口保持原行为。

现有 `UploadApplicationService` 同时实现 `IDownloadService`，仍是唯一账号生命周期边界。
准备阶段读取已生效的 `Active.DownloadDirectory`，生成有界、不可变、固定任务 ID 的确认草稿。
提交校验原草稿、账号、会话、激活身份与生效目录，先持久创建再明确启动。
仅有 Download 权限也可使用，不借用 Create 权限作为下载门槛。

账号切换、注销、权限失效和退出先 fence，排空上传与下载，再退役原会话。
租约未知结果的退役通知只使应用失效，不在执行器调用栈反向等待自身。
迟到选择、确认、进度或完成事件不能跨到新账号或覆盖新状态。

原生入口沿用浏览器命令栏、单文件菜单和主题对话框。确认实际保存路径；分别显示接收活动、
磁盘确认及校验/保存阶段。关闭进度对话框不取消任务，退出应用则排空任务。
取消需要明确确认；不明结果提供本地核实。活动下载提供真实进程内暂停/继续，不能当作重启续传。

## 验证边界

纯执行、应用编排与展示策略测试不替代 Windows 原生文件语义或真实服务器/UI 旅程。
原生测试覆盖碰撞、路径/身份保护、同目录发布、取消和句柄释放；必须在 Windows 执行。
共享上传、任务存储及会话失效回归仍是交付门槛。完整传输页面、批次、续传和离线索引
独立推进，不能因为单文件入口接通而标为完成。
