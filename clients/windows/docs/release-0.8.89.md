# 栖卷 Windows 0.8.89

0.8.89 统一文件写入类对话框：新建文件夹/空文件、单个重命名、批量重命名与“移动到/复制到”的目标路径输入框全部接入应用自研样式，说明文字降为 12px 次级色，批量重命名补命名规则提示与自适应滚动高度。

## 本版完成

- `AskNameAsync`（新建文件夹/新建空文件/重命名）：输入框改用 `FwTextBoxStyle`，命名规则说明改为 12px 次级色；主按钮文案按场景改为“创建”或“重命名”，不再统一为“确定”。
- 批量重命名：每个输入框接入 `FwTextBoxStyle`；顶部新增 12px 次级色规则提示“可直接修改原名称；命名规则与单个重命名一致，提交前会先预览冲突。”；列表与确认摘要的滚动高度由固定 360 改为 `DialogOrdering.ContentMaxHeight(this, 360)`，摘要字号 13。
- “移动到/复制到”：目标原始路径输入框接入 `FwTextBoxStyle`；说明改为 12px 次级色；剪贴板粘贴目标改为“粘贴到”标签 + 13px 路径值。
- 实拍：新建/重命名/批量重命名修复前后见 `20261031-ui0889/screens/before/`、`screens/after/`；“移动到”对话框见 `screens/after/move-dialog-after.png`。所有对话框主按钮保持右置（`DialogOrdering` 已在上版修复 InfoBar 同名按钮问题）。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.89.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui0889/dist/Fileway-0.8.89-win-x64-unsigned-setup.exe`，SHA-256 `c81d99244c0c600891ff1a03eeee87ea1e09ddf7327c502d4da7bb833426f688`，与回执一致。

## 已知问题

- 菜单项键盘焦点形状仍为系统矩形（颜色已用应用双色）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 文件批次的批次卡实拍仍需真人选择文件夹；拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
