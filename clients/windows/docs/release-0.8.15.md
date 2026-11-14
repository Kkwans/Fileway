# 栖卷 Windows 0.8.15

0.8.15 让文件夹上传带上批次元数据：服务器可以按一次文件夹上传跟踪，而不是只看 N 个孤立文件。

## 本版完成

- 新增 `UploadBatchInfo`（批次 ID、名称、文件数、总字节、文件夹标记），随 `UploadSubmission` / `TransferPlan` 持久化；复用现有 `record_json`，不改 SQLite schema，旧任务解码为无批次。
- 文件夹上传提交时写入真实批次信息（文件夹名、枚举文件数、总字节）；单文件上传保持 `Batch = null`。
- 上传运行器把批次映射为协议头 `X-Upload-Batch-ID/Name/Items/Bytes` 与 `X-Upload-Folder: true`；暂停/恢复/续传沿用持久化的批次信息。
- 批次字段进入持久化前做长度与取值范围校验。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误。
- 两次冒烟 `GATE=PASS`（pwsh 7）。
- 产物：`.../20261029-ui0814/dist/Fileway-0.8.15-win-x64-unsigned-setup.exe`，校验值见同目录 json。

## 验证边界（如实）

- 文件夹上传的端到端验收未完成：系统文件夹选择器无法用现有 UIA/键盘自动化可靠驱动（winapp 与 SendKeys 均未进入确认步骤），需要真人点击一次文件夹上传后核对服务器侧批次与传输结果。
- 传输中心仍按文件逐条展示，批次聚合展示尚未做。

## 未完成

- 拖到文件夹移动 / 拖出到资源管理器：合成鼠标无法触发 WinUI 拖拽，需真人验收。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D；无界网格待复核。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
