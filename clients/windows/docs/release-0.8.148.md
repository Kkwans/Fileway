# 栖卷 Windows 0.8.148

0.8.148 继续高对比度走查：修复选中网格/列表项在 HC 下“青色高亮 + 白色文件图标/次级文字”对比不足的问题。选中填充在 HC 改为透明，左侧 3px 标记改用系统窗口文本色；文本与图标保持白字黑底。选择器（分段/选项）仍使用系统高亮填充，避免选中态丢失。

## 本版完成

- 新增 `FwSelectionBarBrush`（常规主题别名 `AccentFillColorDefaultBrush`，HC 为 `SystemColorWindowTextColorBrush`）与 `FwChoiceSelectionBrush`（常规 `FwSelectionBrush`，HC 为 `SystemColorHighlightColorBrush`）。
- HC 下 `FwSelectionBrush` 改为 Transparent；列表/网格 `SelectionBar` 使用 `FwSelectionBarBrush`；`LayoutChoiceStyle.SelectionFill` 使用 `FwChoiceSelectionBrush`。
- HC 实拍 `20261101-ui08148/hc/browser-hc-selected.png`、`recent-hc-selected.png`：选中卡片/行为黑底、白色 3px 标记，名称/路径/时间/图标全部黑底白字；对比修复前 `20261101-ui08147/hc4/browser-hc-selected.png`（青底白图标）。
- 常规深色复核 `20261101-ui08148/normal/browser-hc-selected.png`：仍为深蓝选中底 + 蓝条，无回归。HC Flags 已恢复 126。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.148.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261101-ui08148/dist/Fileway-0.8.148-win-x64-unsigned-setup.exe`，SHA-256 `058a334e0e3b71bf36ccceb9b651ccdabdae4a7e10251eaac862dd6a06bc95b4`，与回执一致。

## 已知问题

- HC 已补拍传输详情、文件任务详情与回收站空状态：`20261101-ui08149/screens2/transfer-hc-selected.png`、`tasks-hc-selected.png`、`trash-hc.png`，边框/选中标记/文字对比正常；PDF 查看器 HC 已实拍通过（`20261101-ui08151/screens/pdf-hc-window.png`），文本/图片/压缩包查看器未单独 HC 走查。
- MenuBar 系统白色边框保留系统外观。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
