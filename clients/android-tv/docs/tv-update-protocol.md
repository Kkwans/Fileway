# TV 在线更新契约（实施中）

TV 使用当前栖卷服务的认证会话检查更新、请求准备和接收 APK；TV 不需要访问 GitHub。此文描述开发中的 v1 契约，不代表服务已部署或更新已可用。旧服务缺少能力字段时，TV 显示不支持并保留其他功能。

## 发布清单

每个支持在线更新的 TV Release 包含原签名 APK、UTF-8 `tv-update-v1.json` 和原始二进制 `tv-update-v1.sig`。清单字段必须严格校验：

```json
{
  "schema": 1,
  "product": "android-tv",
  "applicationId": "io.github.kkwans.fileway.tv",
  "tag": "android-tv-0.0.0",
  "channel": "stable",
  "versionName": "0.0.0",
  "versionCode": 1,
  "minSdk": 28,
  "abis": ["armeabi-v7a", "arm64-v8a"],
  "apkName": "Fileway-TV-0.0.0.apk",
  "size": 1,
  "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
  "signerSha256": "ad35201bcbbcba94754447da9f6e7f369c2a4264bfd5e179194a3cd0b0a0c4a9",
  "sourceRevision": "0000000000000000000000000000000000000000",
  "notes": ""
}
```

示例仅说明结构，不是可安装版本。`channel` 为 `stable` 或 `preview`，与 GitHub prerelease 状态一致；实际 APK `versionName`、`versionCode` 与频道分别校验。清单和说明有尺寸上限；标签和 APK 名称须和同一公开 Release 的附件完全匹配。Go 服务端只接受固定仓库的真实上传附件，不接收客户端 URL、路径或摘要。

签名算法为 `SHA256withRSA`，输入为 ASCII `Fileway-TV-Update/v1`、一个 NUL 字节、完整清单原始字节。发布工具使用现有 TV APK 发布身份，服务端验原 TV 公共证书及其 SHA-256，TV 以当前已安装的原签名证书验签。安装前 TV 再校验 APK 文件 SHA-256、长度、包名、实际版本、minSdk、ABI 和签名集合。当前版本不引入新的发布私钥，也不自动迁移签名身份。历史 Release 未提供此清单时不得伪称可在线更新；首个支持更新的版本需要先用原签 APK 手动安装。

## 同源 API v1

`GET /api/client-capabilities` 返回可选整数 `tvUpdateProxyVersion: 1`。下列接口均需当前账号或允许的 TV 设备会话授权；只能访问自己创建的 offer、下载状态与内容。所有 ID 为短期随机不透明句柄，不包含路径、URL、凭据或用户资料。

- `GET /api/app-updates/tv?versionCode=<当前整数>&api=<API整数>&abi=<ABI>`：可重复的 `abi` 参数按设备优先顺序提供，返回 `{ "offers": [{ "offerId": "...", "manifestBase64": "...", "signatureBase64": "..." }], "incomplete": false }`。内容是标准 Base64 原始清单与签名字节；每频道至多一个可安装较新候选。`incomplete` 为可选布尔值；存在无效候选时为 `true`，TV 可展示其他已验证候选，但不得将空列表误报为已是最新。检查不会下载 APK。
- `POST /api/app-updates/tv/downloads`：JSON `{ "offerId": "..." }`，由用户明确选择之后调用。返回 `{ "downloadId": "...", "state": "preparing" | "ready" | "failed", "receivedBytes": 0, "totalBytes": 1 }`；按内容身份去重且校验当前账号、设备与 offer。
- `GET /api/app-updates/tv/downloads/{id}`：同格式返回真实服务器准备状态，失败附稳定 `code` 和面向用户的安全 `message`。未知总量不伪造百分比。
- `GET` / `HEAD /api/app-updates/tv/downloads/{id}/content`：仅对完整且摘要匹配的 APK 提供流式内容；包含 `Content-Length`、以摘要为身份的强 ETag，支持单范围、`If-Range`、`206` 和 `416`。TV 通过现有会话绑定的 broker lease 访问，JWT 不出现在媒体 URL。

服务器对固定官方仓库的上游、重定向、响应大小、并发、缓存和过期均施加边界；服务端下载/TV 传输分别报告真实字节。APK 使用每进程最高 128 MiB 的块缓存，下载中、已完成和仍被读取的数据共同计入预算；服务重启后重新获取。这样 Linux 根目录为 `/`、Windows 虚拟计算机根以及共享目录中的符号链接都不会将更新缓存作为普通文件暴露。GitHub 返回附件 digest 时必须与已签清单一致；缺少可选 digest 时仍须以签名清单与完整文件 SHA-256 校验。失效、撤权或换账号/服务会使旧句柄失效。错误不得携带服务器文件路径、凭据或上游签名 URL。

TV 只有在用户选择后才请求服务端下载，后台传输与返回页面共用同一状态；完成后经过校验再提示用户安装。Android 系统的未知来源授权和安装确认必须由用户完成，失败、取消或结果未知均保留恢复入口。不可仅凭启动安装界面就报告已安装。

## 发布打包

原签 release APK 和源提交通过项目门禁后，使用仓库内的打包脚本在受控签名环境生成两个更新附件：

```sh
python3 clients/android-tv/scripts/package-tv-update.py \
  --apk /absolute/release/Fileway-TV-X.Y.Z.apk \
  --tag android-tv-X.Y.Z --channel stable --source-revision <40位源码SHA> \
  --notes-file /absolute/release/notes.txt \
  --aapt /absolute/android-sdk/build-tools/36.0.0/aapt \
  --apksigner-jar /absolute/android-sdk/build-tools/36.0.0/lib/apksigner.jar \
  --java /absolute/jdk/bin/java --signing-properties /absolute/private/tv-signing.properties \
  --output-dir /absolute/existing/release-output
```

Windows 使用相应的 `aapt.exe`、`java.exe` 和绝对路径。`--channel preview` 用于预览 Release；tag 与 APK 版本名仍是 `android-tv-X.Y.Z` / `X.Y.Z`，频道来自 Release 标记。输出目录必须已存在且不含同名清单，脚本不覆盖原产物、不创建密钥、不上传或发布。APK、`tv-update-v1.json` 与 `tv-update-v1.sig` 应同属同一个 Release，先上传齐全并完成校验，再公开；缺失或不匹配的候选不能作为在线更新。签名配置及证书私钥始终位于仓库外。
