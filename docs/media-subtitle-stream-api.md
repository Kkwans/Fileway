# 渐进源字幕接口

`GET /api/client-capabilities` 的可选 `sourcePGSStreaming: 1` 表示服务端支持
以下版本。旧客户端可以忽略；旧的 `format=source` 完整文件、Range 和 VTT 默认
行为保持不变。这是源字幕传输协议，不表示客户端已实现增量索引或完成设备验收。

## 请求与鉴权

在现有源字幕请求上增加 `stream=1`：

```text
GET /api/subtitle{wirePath}?streamIndex=N&format=source&identity=SOURCE_ID&stream=1
```

PGS/SUP 由 `sourcePGSStreaming: 1` 声明；ASS/SSA 的 ASS 输出由独立可选
`sourceASSStreaming: 1` 声明。未声明时客户端保留完整格式路径，不能从 PGS 能力
推断 ASS 能力。其他格式返回 400。沿用原账号、原媒体权限、opaque wirePath 和
源身份校验；初始身份不符返回 409。不能在媒体 URL 放 JWT。原生客户端通过现有
可撤销 broker asset lease 访问，broker 必须立即转发响应头和小帧，避免心跳被
本地 HTTP 缓冲吞掉。新协议不提供 Range、HTTP 文件缓存或静态 Content-Length。

## 响应

Content-Type 为 `application/x-fileway-media-asset+ndjson; version=1`，
Cache-Control 为 `private, no-store`。每行一个 JSON 对象，按顺序发送：

| type | 字段与含义 |
|---|---|
| `start` | `identity` 必须等于请求源身份；`format` 为 `sup` 或 `ass`，须与请求轨道匹配。 |
| `data` | `data` 为至多 32 KiB 原始字幕字节的 base64。按帧拼接，不是独立字幕事件。 |
| `pending` | 每秒最多一个等待心跳，覆盖排队及字幕事件间隔；不表示百分比。 |
| `complete` | `bytes` 为原始总字节数；`sha256` 为完整源字幕（SUP 或 ASS）的小写十六进制 SHA-256。 |
| `error` | `code` 为 `SOURCE_ASSET_FAILED` 或 `SOURCE_CHANGED`，没有服务器路径和 FFmpeg 日志。 |

`start` 之后的失败通过 `error` 结束；不能再以 HTTP 状态推断成功。客户端必须
同时验证 `complete`、字节数和摘要；连接关闭、解析失败或缺少最终帧均不是完整成功。
若实现渐进渲染，只能发布完整 SUP display set，保留原片时间，不得将不完整段
当作有效字幕；错误、身份变化或过期结果必须失效并释放该次未完成导入。

客户端不应把 SUP 当作 ASS 或普通文字。PGS 的字形与颜色来自位图；字体选择
不能改变位图字形。HDR/SDR 视频路径仍共享源片字幕时间，不能再次叠加源偏移。

## 工作与资源生命周期

流式读者和完整文件读者共享原媒体资产缓存、提取 lane、single-flight 和容量
预留。提取开始后即可读取已写入的字节；只有完整成功且同步落盘后才原子发布缓存。
完成前不提供 ETag。开始和发送完成帧前复核源身份与访问条件。

一个读者取消不取消其他读者；最后一个读者关闭会取消未完成提取。临时输出受
既有大小、磁盘预算和任务时限约束，失败输出不发布。流式读者 pin 其最终路径直到
关闭，避免文件发布后又被缓存淘汰。临时文件打开/读取/关闭和发布重命名使用同一
缓存锁，避免 Windows 活动临时句柄阻止重命名；不持锁等待网络消费者。

ASS 数据保留原始脚本、样式和源时间，不能把网络块边界当成行或事件边界。
客户端只能按完整 UTF-8 行／事件组增量解析，覆盖范围不足时不得保持旧画面，
最终仍须验证完整长度和摘要。ASS 与旧完整 ASS 接口共用同一个资产生产者和缓存。
协议不保证 ASS 事件按起点排序：已观察到的时间不能证明后续不会出现更早事件。
若客户端提前展示已收到的内容，应保持加载状态；检测到乱序时撤销临时预览，
并在完整资产校验通过后恢复。内嵌字体段须完整送入 libass，不能按网络块拆分。

源字体附件接口仍使用完整资源、原媒体身份和权限校验，不支持本流协议。
单字体输出上限为 32 MiB，与原生 MKV 字体读取一致；缓存仍按源身份、账号和
附件索引隔离，并保留原有总容量、并发去重、失败清理和 Range 行为。
