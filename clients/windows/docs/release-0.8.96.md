# 栖卷 Windows 0.8.96

0.8.96 重排“缓存与存储”对话框的“当前生效”摘要：由一段混排长路径的 12px 文本改为 12px 次级标签 + 13px 值的四行表单；区块标题提升到 15px，滚动区与底部按钮之间补 1px 分隔线，滚动高度由 520 提升到 560 DIP，避免测试窗口下帮助文字被截断。

## 本版完成

- “当前生效”改为四行标签/值：缓存目录、容量、下载目录、自动继续；路径在值列内换行、可选中复制，标签固定 72 DIP 左列。原先的“缓存：… 容量：… 下载：…”混排段落不再使用。
- 对话框三个区块标题“当前生效 / 保存配置 / 缓存占用”统一 15px SemiBold，与 14px 字段标题、13px 值形成层级。
- 滚动区内容补 16 DIP 底部留白；底部按钮行外包 1px `FwStrokeBrush` 上分隔线与 12 DIP 内边距，滚动边界不再像内容被按钮遮住。
- `SetMaxContentHeight` 首选高度 520→560 DIP，`DialogOrdering.ContentMaxHeight` 仍按窗口高度−240 自适应；在当前 1800×1250 物理窗口下默认下载目录的帮助文字完整可见，无半行裁切。
- 证据：修复前 `20261031-ui0895/screens/settings-audit/storage-settings.png`、`settings-audit-dark/storage-settings.png`；修复后 `20261031-ui0896/screens/final2-light/storage-settings.png`、`final2-dark/storage-settings.png`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.96.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui0896/dist/Fileway-0.8.96-win-x64-unsigned-setup.exe`，SHA-256 `60418fa00f798717ab0ea9b8f8a88e45d8f16d5c03da33112a8935abc3e57499`，与回执一致。

## 已知问题

- 更小高度窗口下对话框仍需滚动查看“缓存占用”；未增加常驻滚动条或滚动提示。
- 菜单项键盘焦点形状仍为系统矩形（颜色已用应用双色）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 文件批次的批次卡实拍仍需真人选择文件夹；拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
