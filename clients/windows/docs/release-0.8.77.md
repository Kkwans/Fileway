# 栖卷 Windows 0.8.77

0.8.77 把缓存与存储对话框接入自适应高度：900px 高窗口保持 0.8.75 的 360 DIP 内容高度，窗口更高时展示更多内容，不再固定在一档。

## 本版完成

- `StorageSettingsControl` 暴露 `SetMaxContentHeight`；MainPage 打开对话框时按 `DialogOrdering.ContentMaxHeight(this, 520)` 设置（窗口高度减 240 DIP，限制 240–520）。
- 900px 高窗口下内容约 360 DIP、上下仍留约 65–68px；1000px 高窗口下内容约 427 DIP，“自动继续未完成下载”“保存配置”等更多行直接可见。
- 滚动区始终保留可用滚动条，关闭/保存按钮固定底部。
- 顺带复核打开方式与关于弹窗：1200×900 下打开方式双栏 7 个分类完整、关于版本卡与按钮正常；本版未改这两个弹窗。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.77.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机截图：`screens/storage-900.png`、`screens/storage-1000.png`、`screens/open-with.png`、`screens/about.png`。
- 产物：`.../20261031-ui0877/dist/Fileway-0.8.77-win-x64-unsigned-setup.exe`，SHA-256 `994e924bb65930137dd0ad7c13910384589ce534ca95e0808bd5d567612ed426`，与回执一致。

## 已知问题

- 窗口高度不足时内容仍需内部滚动，滚动条为系统悬浮样式。
- 菜单项与 Expander 头部的键盘焦点仍使用 WinUI 系统矩形焦点视觉（内部模板，暂列系统语义例外）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 文件批次的批次卡实拍仍需真人选择文件夹；拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
