# 栖卷 Windows 0.8.163

0.8.162 补齐了压缩包浏览层；本版处理解压体验：目标目录只能使用默认值、没有解压进度、
结果只写进一条 banner。现在目标可编辑/可选择，解压有 6 DIP 进度轨道和逐条结果，
并且修掉了测试中暴露的**嵌套目录父目录未创建**缺陷。

## 现状取证

- 解压确认框只有三行说明，目标固定为 `<下载目录>\archive-extract\<包名>`，没有输入框和“浏览…”。
- `IArchiveBrowseSession.ExtractAsync` 不报告逐项进度；UI 只有一个“正在解压…”banner，
  完成后也只把汇总写进 banner。
- `FilewayClientServices.ArchiveBrowse.ExtractAsync` 只用 `Directory.CreateDirectory(target)`
  创建目标根目录，**没有创建条目自身的父目录**。选择「文档」文件夹解压时，
  `文档/说明.txt` 的父目录不存在，两个文件都以
  `Could not find a part of the path ...\extracted\文档\说明.txt` 失败。
  修复前实拍 `20261105-extract08163/light1/extract-results.png` 记录了 0 成功 · 2 失败。

## 改了什么

1. **目标目录可编辑**：确认框改为 12px 说明 + `FwTextBoxStyle` 输入框 + “浏览…”次按钮
   （`Microsoft.Windows.Storage.Pickers.FolderPicker`，WindowId 模式）；默认值仍是
   `<下载目录>\archive-extract\<包名>`，输入框可横向滚动，长路径不撑高。
2. **目标校验**：`Path.GetFullPath` 后要求绝对路径、合法、且不能是已存在的文件；
   不合法时在对话框内以 `FwDangerTextStyle` 红字提示，`PrimaryButtonClick` 里 `args.Cancel = true`，
   对话框不关闭；修正后可继续。
3. **逐条进度契约**：`ExtractAsync` 新增 `IProgress<ArchiveExtractItem>? progress = null`；
   实现每完成一项（成功/跳过/失败）回调一次，回调异常不会中断解压；1 GB 总预算仍按整个请求累计。
4. **进度与结果 UI**：列表与状态栏之间新增可折叠的 6 DIP 进度轨道 + `正在解压 3/10`；
   完成后展开“解压结果（N）”面板，逐条显示名称、已解压/已跳过/失败与失败原因，
   汇总同时写 banner 与状态栏；结果面板可“收起/展开”。
5. **嵌套目录修复**：写文件前 `Directory.CreateDirectory(Path.GetDirectoryName(destination)!)`，
   保证 `文档/说明.txt` 这类条目会创建父目录；同名文件仍走“已存在，不覆盖”。
6. **结果行对比度**：结果名称使用 13px `FwToolbarTextStyle`（主色），失败原因用 `FwDangerTextStyle`；
   结果为只读列表（`SelectionMode=None`），不参与主列表选择。

## 验证

- Release x64 构建 **0 警告 0 错误**（0.8.163.0），两次独立数据根启动冒烟均 `GATE=PASS`。
- `20261105-extract08163/Probe-Extract.ps1`：真实嵌套 zip，选择「文档」文件夹（2 个文件），
  浅色 `light4/report.txt` 与深色 `dark2/report.txt` **各 17 项 PASS、0 FAIL**：
  - 目标输入框与“浏览…”按钮存在；开始解压按钮存在。
  - 首次解压 `解压结果（2）`、汇总 `2 成功 · 0 跳过 · 0 失败`，磁盘上
    `extracted/文档/说明.txt`、`extracted/文档/长文.txt` 均存在。
  - 第二次解压同一目标：`0 成功 · 2 跳过 · 0 失败`，逐条显示“目标已存在，未覆盖。”。
  - 目标填成已存在的文件：对话框保持打开，红字错误可见；取消后正常关闭。
  - 修复前对照：`light1/extract-results.png`（0 成功 · 2 失败，路径不存在）；
    修复后 `light4/extract-results.png`、`dark2/extract-results.png`（2 成功）。
- **进度轨道说明**：2 个小文件在当前机器上完成过快，未取得解压中途的进度条实拍；
  UIA 已验证结果面板、汇总与逐条结果，进度回调路径已通过 2 个条目的回调执行。
  大批量/真人验收时补拍中途进度条，在此之前不把“进度条视觉”标为通过。
- 连接页 `Invoke` 偶发抖动重试后通过，属探针已知抖动，不计产品缺陷。

## 交付物（本地未签名候选，等待实机验收）

- `Fileway-windows-artifacts/20261105-extract08163/dist/Fileway-0.8.163-win-x64-unsigned-setup.exe`
  277,113,468 字节，SHA-256 `e86fc3f1ca65bc05a0d8bca812f25d5c5777b3a7e285a2f4b01b06970f6deb5e`
  （与同目录 `.json` 回执一致）。
- 压缩包 `...-unsigned.zip` 279,752,037 字节，
  SHA-256 `681b23ada39c1656607c8929bb7aa58e5b91a45806ce75828b2c02046d95b709`。

## 本版未做（按规划继续）

- 包内文本/图片预览、Enter/双击预览语义：0.8.164。
- 解压中途进度条实拍（大量文件/真人验收）；解压结果“打开目标文件夹”入口未加。
- 压缩包高对比度完整走查：与 0.8.168 回归收口一起做。
- 用户实机验收：重点看目标编辑/浏览、嵌套目录落盘、二次解压跳过、非法目标不关框。
