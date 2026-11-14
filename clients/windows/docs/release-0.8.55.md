# 栖卷 Windows 0.8.55

0.8.55 清理主浏览器筛选栏最后一层原生 `CommandBar` 容器，并统一最近访问页“打开/刷新”两个按钮的 36px 基线。

## 本版完成

- 主浏览器筛选栏由 `CommandBar` 改为无原生外观的 `ContentControl` + `StackPanel`；保留原有 `BrowserCommands.IsEnabled` 语义，排序/类型/视图/更多四个控件的间距与位置不变。
- 最近访问页刷新按钮由 34px 调整为 36px，与同排“打开”按钮上下边缘对齐。
- 主浏览页、最近访问页实拍确认布局未受容器替换影响。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.55.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机截图：`screens/main-light.png`（筛选栏）、`screens/recent-light.png`（打开/刷新基线）。
- 产物：`.../20261031-ui0855b/dist/Fileway-0.8.55-win-x64-unsigned-setup.exe`，SHA-256 `3576d52c…c1c2b`，与回执一致。

## 已知问题

- 深色 ToolTip 仍未解决；0.8.54 的 Flyout 宿主原型已验证无效并回退，后续需要重新定位实际显示层。
- 高对比度主题未走查（需调整系统设置，待用户同意）。
- 文件夹批次的批次卡实拍仍需真人选择文件夹。
- 拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
