# Android 应用更新

“检查更新”位于设置页和未连接服务器的页面，不依赖 NAS／Windows 登录。
客户端通过公开 GitHub Releases API 同时检查正式版与预览版，不使用服务器凭据。
每个渠道保留最新的兼容版本，按数字版本号排序；同版本优先正式版。
两个渠道均有更新时显示两个选项，默认选中较新版本，说明处标注正式版／预览版。
低于当前版本的选项不会作为升级提供。

## 发布契约

- 仓库固定为 `Kkwans/fileway`，草稿与非 Android release 不参与更新。
- Android tag：`android-preview-X.Y.Z-<7至40位源码SHA>`；正式版可用
  `android-X.Y.Z-<SHA>`。两位版本号 `X.Y` 同样可识别。
- 以 release 的 `prerelease` 字段标记渠道，不根据标题猜测。
- 通用安装包：`fileway-android-X.Y.Z-preview.apk` 或
  `fileway-android-X.Y.Z.apk`；可追加 `-universal`、`-arm64-v8a`、`-x86_64`
  后缀。优先选择设备支持的 ABI，其次通用包。
- 资产必须已上传完成，来自该 release 的官方 HTTPS 下载 URL，大小不超过512MiB。
- 正式发布的 APK 必须沿用原 application ID 和签名，versionCode 严格递增。
  versionName 与 tag 数字版本一致。没有显式修订号的预览版可使用纯数字版本，
  也可带 `-preview` 后缀；带 `-rN` 的预览 tag 要求 APK 的预览修订号一致。
- 不能仅提高 versionCode 而沿用相同渠道的数字版本号；修复版应提高补丁号。
  同数字版本的预览转正式版也必须提高 versionCode。
- release body 是 App 中展示的更新说明，宜使用可直接阅读的段落和列表。

## 下载与安装

复用 Android DownloadManager 处理用户主动下载，显示实际接收字节和百分比，
离开页面后下载继续。专用偏好记录所选版本和任务 ID；再次启动应用恢复原任务，
不重复创建下载。网络暂不可用时显示等待，失败可重新下载或重新检查版本。
取消只删除本模块记录的任务和应用专属 `updates` 目录中的对应安装包。

完整接收后，核对长度、实际包名、递增 versionCode、versionName、当前签名证书、
最低系统版本及原生 ABI。安装器启动前再次核对。签名更换、降级、重复安装、
不完整文件和版本不一致均拒绝。最终密码学验证由 Android PackageInstaller 执行。

仅通过不可导出的 FileProvider 暂时授予该 APK 只读访问，不使用 `file://` 安装 URI，
不暴露完整存储目录。安装遵循系统“允许此来源安装”和覆盖安装确认；
不卸载、不清数据、不替换证书、不自动降级。系统下载通知只展示进行中的下载，
安装入口留在 App 中，避免跳过 App 的校验。

## 验证边界

版本／渠道／ABI／地址筛选有单元测试；真实包解析、身份与证书拒绝以及
FileProvider 授权有设备测试。公开 GitHub 下载／恢复测试属于显式外网验收，
不混入离线 fixture 套件。最终发布时还须通过更新入口实测新版本覆盖安装及数据保持，
当前版本检查通过不代表这个完整升级闭环已通过。
