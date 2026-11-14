# 栖卷 Windows 0.8.164

0.8.163 补齐了解压；本版处理压缩包内预览：双击或按 Enter 文件时，文本类复用只读文本查看器、
图片类复用图片查看器，字节直接来自已打开的会话，不重新下载压缩包。目录仍进入，不支持的类型
给出明确提示。

## 现状取证

- 0.8.163 及以前，`Enter`/双击文件只走「解压选中」确认框，无法先看内容。
- `IArchiveBrowseSession` 只有列表与解压，没有按条目读取字节的受控入口。
- `ArchiveWindow.Activate` 对文件直接调用 `ExtractAsync(all:false)`；没有类型分派与预览窗口。

## 改了什么

1. **契约**：新增 `ArchiveEntryContent(Index, FullName, Bytes)` 与
   `ReadEntryAsync(index, maximumBytes, cancellationToken)`；目录、越界索引、超过上限的条目明确拒绝。
2. **实现**：`ZipBrowseSession.ReadEntryAsync` 从已缓冲的 zip 条目读取，文本调用方上限 4 MB、
   图片 32 MB；每次读取独立 `MemoryStream`，不触碰网络，不写最近访问。
3. **文本预览**：复用 `TextWindow`，内存 `ITextContentService` 适配器返回 `TextContent` 且
   `Edit = null`，编辑按钮自动禁用；编码沿用 `FilewayClientServices.DecodeText`
   （BOM/严格 UTF-8/GB18030 回退）。
4. **图片预览**：复用 `ImageWindow`，内存 `IImageContentService` 返回 `IImageContent`；
   不传 `IRecentAccessService`，不写最近访问；窗口关闭时由既有逻辑释放流。
5. **交互**：`Activate` 改为目录进入、文件预览；`Enter` 与双击同一语义；解压仍走
   「解压选中/全部解压」按钮；不支持类型提示「该类型暂不支持预览，可解压后打开。」。
6. **资源**：`FileRow.ImageExtensions` 改为内部共享，文本扩展集沿用 `FileRow.TextExtensions`；
   预览窗口初始主题跟随压缩包窗口。

## 验证

- Release x64 构建 **0 警告 0 错误**（0.8.164.0），两次独立数据根启动冒烟均 `GATE=PASS`。
- `20261105-preview08164/Probe-Preview.ps1`：真实嵌套 zip，浅色 `light2/report.txt` 与
  深色 `dark1/report.txt` **各 22 项 PASS、0 FAIL**：
  - 文本：`Enter` 打开 `说明.txt` 窗口，`EditTextButton` 存在且 **IsEnabled=False**，
    `TextEditor` 内容非空，Esc 关闭后窗口消失。
  - 图片：`Enter` 打开 `样张.png` 窗口，UIA 命中 `ImageView`，Esc 关闭后窗口消失。
  - 不支持类型：`内层压缩包.zip` 按 Enter 后出现
    「该类型暂不支持预览，可解压后打开。」，不打开预览窗口。
  - 文本实拍 `light2/preview-text.png`、`dark1/preview-text.png` 干净可读；
    不可预览提示实拍 `preview-unsupported.png` 正常。
- **图片实拍限制**：`preview-image.png` 的屏幕抓取仍拍到后面的主窗服务端页，
  原因是 WinUI 3 合成窗口被遮挡时 GDI `CopyFromScreen` 无法抓取图片画布。
  UIA 已确认图片窗口与 `ImageView` 存在、Esc 可关闭；**图片画布像素证据以待真机验收为准**，
  不把该截图当通过证据。
- 连接页 `Invoke` 偶发抖动重试后通过，属探针已知抖动，不计产品缺陷。

## 交付物（本地未签名候选，等待实机验收）

- `Fileway-windows-artifacts/20261105-preview08164/dist/Fileway-0.8.164-win-x64-unsigned-setup.exe`
  277,110,510 字节，SHA-256 `61b995d74c6e928f609f8162f6df3978aada5284868bd657243efdf2569c46b2`
  （与同目录 `.json` 回执一致）。
- 压缩包 `...-unsigned.zip` 279,755,571 字节，
  SHA-256 `2f614358ba4b1b4853136970b2c6d8e7ff8253e3df658f0dcae0885f8ed1e947`。

## 本版未做（按规划继续）

- 文本 4 MB/图片 32 MB 上限的超限提示未做真实超限夹具；代码路径已校验，超限场景待补。
- 视频/PDF/音频包内预览未做，统一提示不支持。
- 图片画布、深色/高对比度包内预览待真机验收；压缩包 HC 完整走查与 0.8.168 一起做。
- 下一步回到 P1：0.8.165 收藏与标签排版。
