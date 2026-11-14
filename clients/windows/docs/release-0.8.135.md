# 栖卷 Windows 0.8.135

0.8.135 重排“确认下载位置”弹窗的信息层级：原先“大小：/保存到：”与路径混在 13px 正文里，长路径换行后难以扫读。现在改为 12px 次级字段标签 + 13px 值的预览卡（复用 `FwPreviewCardStyle`），说明文字降为 12px 次级，和“下载另存为”保持同一套表单语言。

## 本版完成

- `MainPage.Downloads` 单文件下载确认框：名称下方新增 `FwPreviewCardStyle` 卡片，内含“大小/保存到”两组字段；路径可选中、自动换行；说明使用 `FwCaptionTextStyle`。
- 深色实拍 `20261101-ui08135/screens/direct-download.png`：卡片描边/底色与主题一致，标签 12px 次级、值 13px 主色，路径在卡内换行。日志 `download.log`（`DIALOG=True`，已打开“确认下载位置”）。
- 副产品：`FwPreviewCardStyle` 首次在真实下载流程中完成实拍验证（0.8.129 只做了代码替换）。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.135.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261101-ui08135/dist/Fileway-0.8.135-win-x64-unsigned-setup.exe`，SHA-256 `9d6e6efe2e0deecde38592e7a63da18ca1069a5c16de16ab9d68085c997d6c9f`，与回执一致。

## 已知问题

- “下载另存为”预览卡仍未在完成“核对保存位置”后实拍（本次确认框已复验同一卡片样式）。
- 批量下载确认框仍未单独截图。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
