# Fileway 客户端网络核心

这是 Go 客户端模块，不是文件服务器，也没有另一套 Web UI。

| 目录 | 职责 |
| --- | --- |
| `transport` | HTTP 传输、请求取消、播放数据/缓存边界 |
| `tailnet` | 内嵌 Tailscale 节点及网络生命周期 |
| `bridge` | 原生客户端可调用的桥接接口 |
| `mobile` | Android Go 原生库入口 |

Android 的 [`build-native.sh`](../../android/scripts/build-native.sh) 从这里
生成 `generated/<ABI>/libnfbcore.so`，Gradle 使用同一路径打包。`generated` 是
构建产物，不提交 Git。Windows 客户端尚未实现，计划复用不等于已完成。

## 已核验的复用边界

`transport`、`bridge`、`tailnet` 的受控单元测试已在 Windows 原生环境运行通过，
CI 继续分别检查 Linux 和 Windows。这支持复用 Go 网络/控制逻辑，不代表 Windows
客户端、DLL/.NET 绑定或真实 Tailscale 登录已经完成验收。

`tailnet/platform_android.go` 只处理 Android 网络接口和日志目录；其他平台使用
Tailscale 原生的系统接口。Android JNI/C++ 在 `clients/android`，不能原样用作
Windows 绑定。`mobile` 导出 C 控制接口，但 Windows DLL 构建、调用约定、UI、
播放器与系统密钥保护仍需要各自适配和端到端验证。

旧模块路径暂时保留兼容性，产品名为栖卷 · Fileway。此组件使用 GPL-3.0，见
[LICENSE](LICENSE)。使用 `go.mod` 指定的版本，在此目录执行：

```sh
go test ./transport ./bridge
go vet ./transport ./bridge
```

## 原生上传数据通道

`upload_lease` 是添加的控制命令：指定固定session、path/wirePath和upload选项
（resources/tus、size、transferId、overwrite、resume与允许的批次metadata）。
返回无token的随机loopback URL；上传字节通过HTTP body发送，不进入JSON/JNI。
普通播放/预览lease继续只允许读取。写入只使用原session的HTTP client，不能跟随
其他来源或目标的Location；原始路径只解码一次，旧编码wire字节保持不变。

resources只允许POST；tus保留原HEAD/POST/PATCH/DELETE接口和offset语义。
客户端必须先检查权限、源身份与冲突；核心不会自动重放写入。撤销/关闭session
取消实际上游及等待中的输入流。`upload_stats`区分sentBytes与acceptedOffset：
前者是Go送入网络传输的字节，后者只来自已确认的成功响应，不是接收端速度证明。
Upload-Length/Offset/Content-Type、固定长度与64MiB块上限检查位于写入通道。

当前服务端TUS仍有临时片段过期和完成后HEAD语义限制。核心兼容不等于App上传
队列、持久续传或真机/NAS验收已完成；后端行为不得通过伪造成功/版本头掩盖。

## TV / direct-only builds and HLS assets

`-tags=fileway_direct` builds the same broker and control protocol without the
embedded network dependency. Embedded requests are rejected explicitly. Default
builds retain the existing Tailscale implementation. The Android native builder
in `clients/shared/android/build-native.py` isolates generated output by API,
network mode and ABI and checks that direct builds contain no Tailscale imports.

`hls_lease` accepts an immutable session and a 64-character playback task ID in
`path`. It returns a token-free loopback manifest URL. All playlist and segment
requests remain inside that task and original account; relative/same-task absolute
links are normalized to the loopback lease. Cross-origin links, other jobs,
queries and traversal are rejected. Revoking the manifest lease revokes every
child resource. Existing single-resource leases and HTTP Range behavior remain.

Run both protocol variants when changing this boundary:

```sh
go test ./bridge ./transport
go test -tags fileway_direct ./bridge ./transport
```
