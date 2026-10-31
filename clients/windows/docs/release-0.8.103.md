# 栖卷 Windows 0.8.103

0.8.103 将关于弹窗的“项目主页与源代码 / 许可证”两个链接按钮由纵向堆叠改为同一行，缩短弹窗高度；1000×700 浅色与深色均实拍。

## 本版完成

- `AboutContent` 的两个次级按钮改为水平 `StackPanel`（间距 8、各 32 DIP 高），不再各占一行；内容宽度 380–460 DIP 内并排完整，不换行。
- 关于弹窗总高度缩短约 40 DIP，版本卡、说明与关闭按钮的层级不变。
- 证据：修复前 `20261031-ui0899/screens/help-dialogs/about.png`（纵向两个按钮）；修复后 `20261031-ui08103/screens/about-light/about.png`、`screens/about-dark/about.png`（同一行，浅/深色）。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.103.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui08103/dist/Fileway-0.8.103-win-x64-unsigned-setup.exe`，SHA-256 `1d7b5a3ccba1b87c923fdc591c48414cca15ab489caf649d87b89295d5f37993`，与回执一致。

## 已知问题

- 菜单项键盘焦点形状仍为系统矩形（颜色已用应用双色）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
