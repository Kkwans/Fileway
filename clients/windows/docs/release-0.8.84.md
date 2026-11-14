# 栖卷 Windows 0.8.84

0.8.84 把回收站列表接入自研列表容器，补齐列头/右列对齐，并顺带修正主浏览详细列表列头与侧栏空提示的排版。文件详情浮窗本版做了 420×480 实拍复核，无布局改动。

## 本版完成

- 回收站主列表与“需要核实的操作”列表接入 `FwListViewItemStyle`：行卡片 `FwSurfaceBrush` + 1px 描边，选中使用半透明 `FwSelectionBrush` + 左侧 3px 强调条，焦点为自研双色圆角环；替换系统整行蓝底与方形系统焦点框。
- 回收站列头改为 `66,8,18,8`：与行内容盒对齐（UIA 实测列头“名称”与行名左缘差 1px）；“大小”“删除时间”列头与值均右对齐，删除时间列头与日期右缘实测同为 1741px。
- 主浏览详细列表列头左内边距 56→62，与行首名称对齐；前后截图对照列头移动 9px。
- 侧栏标签空提示主动断为“尚无标签。/ 选中资源后可直接标记。”两行，消除第二行只剩“源。”的孤字。
- 真实回收站流程验证：将测试账号的“01 文本”移入回收站后截图选中态，再恢复；回收站回到空状态、目录回归（FOLDER_BACK=True），未使用合成记录。
- 文件详情浮窗实拍复核（420×480 DIP）：位置/大小/修改时间与两组动作磁贴完整，无裁切。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.84.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实拍：`20261031-ui0884/screens/trash-selected.png`、`trash-empty-after.png`、`browser-after-restore.png`、`screens/layouts/browser-details.png`、`details-popup.png`。
- 产物：`.../20261031-ui0884/dist/Fileway-0.8.84-win-x64-unsigned-setup.exe`，SHA-256 `340b10029312cd4d1bd2df67170d47ca0412a295ee367ff944deadb6b5deb866`，与回执一致。

## 已知问题

- 菜单焦点仍为系统矩形（颜色已用应用双色），要圆角需整体替换 MenuFlyoutItem/SubItem 模板。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 文件批次的批次卡实拍仍需真人选择文件夹；拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
