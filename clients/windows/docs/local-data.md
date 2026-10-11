# Windows 本地连接数据

生产客户端通过 `SqliteProfileStore` 与 `DpapiCredentialVault` 保存连接。未传入存储的
`FilewayClientServices(host)` 仅使用进程内存，供隔离调用者和测试使用。
界面只接收 `SavedConnection` 和 `ConnectionSession`，不读取令牌。

## 数据根与并发

默认根为 `%LOCALAPPDATA%\Fileway\Preview`。开发及测试显式使用独立
`FILEWAY_WINDOWS_DATA_ROOT`，不能共享用户真实数据。程序目录、下载目录与此根分离。
应用持有 `.client.lock` 的独占文件句柄；同一数据根只允许一个应用进程，关闭时释放。
SQLite 工作在后台执行并串行访问，锁等待有界；连接启用 WAL、外键和 FULL 同步。

## Schema 2

`client.db` 用 SQLite `user_version` 管理版本。`saved_connections` 以 profile UUID 为主键，
保存 source revision、名称、服务器地址、网络/代理策略、认证方式、用户名、服务端 userId、
保持登录开关和最后连接时间；不保存密码或 JWT。

`pending_logouts` 以 `(profileId, sourceRevision, serverUserId)` 为复合主键。
注销意图事务先关闭对应账号的保持登录标记，再收回会话和媒体租约，删除加密凭据后完成清理。
启动、恢复及读取档案前处理未完成意图。旧账号的清理不能删除同 profile 的新 revision 或新账号。

`client_storage_settings` 保存本机缓存根目录、独立的默认下载目录与总缓存数据上限，
默认 5,000,000,000 字节；不保存媒体或凭据。目录变更保存为待生效配置，下次启动使用，
不会搬移或删除旧目录的数据。播放缓存的分块和配额由 Go 核心统一管理，见
[缓存与预加载](cache-storage.md)。

首次仅在空数据库创建 schema。升级 schema 1 前使用 SQLite Backup API 制作包含已提交 WAL
内容的一致备份，随后事务添加设置表并设置版本 2；备份名为 `client.db.schema1-<随机标识>.backup`。
备份失败不开始迁移。未知更高版本或无版本但已有数据表的数据库拒绝写入，不自动重建。
不能复制运行中的 WAL 文件冒充备份，也不能只换旧程序做不兼容降级。

## 凭据与恢复

仅勾选保持登录时保存会话令牌；密码始终只用于当次登录。凭据使用 DPAPI CurrentUser 加密，
熵和文件名绑定完整 AccountKey；同目录临时写、落盘后原子替换，明文缓冲区用后清零。
恢复必须向服务器核验同一 userId、当前账号元数据和能力后才激活，不能采用 JWT 的旧权限作为界面权限。
续期更新同账号元数据；Go 核心拒绝把不同 userId 的续期 JWT 发布给 API 或媒体请求。

Disconnect 保留档案和已选择保存的会话；Logout 清会话但保留档案；Forget 同时移除该档案。
改变服务器或安全路由必须创建新 source revision。不能把旧凭据重新绑定到新地址。
网络、权限、认证、本地存储和凭据失败分别反馈安全错误，不自动保存密码或重放写操作。

已确认的远端写入不因续期或本地保存失败变成失败。安全事件携带 AccountKey 和 SessionId，
旧会话的迟到通知不能关闭新会话。会话撤销失败时终止自有核心进程；兜底仍失败则保留清理意图，
不得宣称已完整注销。真实传输恢复和后续实体迁移在各自版本继续实现。

注销先退休会话并删除凭据，再清理当前缓存池内该账号的数据。缓存盘不可用时保留注销意图，
显式注销/遗忘报告清理未完成；其他账号仍可连接，后续待办继续处理。同一账号的待办完成前
禁止写回凭据，避免失败重连复活已注销登录。旧缓存根目录的登记与跨目录清理仍是后续工作。
