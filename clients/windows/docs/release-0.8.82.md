# 栖卷 Windows 0.8.82

0.8.82 修正连接页单栏模式下的水平居中：品牌栏隐藏后，Grid 仍保留 64px 列间距，导致表单卡片相对窗口中心右偏约 40+px。

## 本版完成

- `ConnectionPanel_SizeChanged` 在品牌栏隐藏（窗口宽度 < 1000 DIP）时把 `ConnectionFormGrid.ColumnSpacing` 设为 0，品牌栏可见时恢复 64。
- 1200×900 与 1440×900 实拍复核：表单卡片中心与窗口中心误差约 2 DIP；卡片宽度、字段对齐、连接按钮保持不变。
- 品牌栏显示阈值与之前一致（≥1000 DIP 显示两栏）。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.82.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机截图：`20261031-ui0882/screens/connection-1200x900.png`（旧偏右）、`screens/after/connection-1200x900.png`、`connection-1440x900.png`（居中原图）。
- 产物：`.../20261031-ui0882/dist/Fileway-0.8.82-win-x64-unsigned-setup.exe`，SHA-256 `25ba364a0b5d6ba7c4a38f7fd055efab831318f55b27c083e43c2614c39a16b3`，与回执一致。

## 已知问题

- 菜单焦点仍为系统矩形（颜色已用应用双色），要圆角需整体替换 MenuFlyoutItem/SubItem 模板。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 文件批次的批次卡实拍仍需真人选择文件夹；拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
