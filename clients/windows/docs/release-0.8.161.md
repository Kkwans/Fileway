# 栖卷 Windows 0.8.161

0.8.160 的实拍暴露了选中态的多重标记：行/卡片同时出现卡片描边、选中底色、
左侧 3px 蓝色短竖条，以及键盘焦点时的双色焦点环。用户明确说「为什么文件的选中态有两层外框」
和「左边会有一个蓝色的小短竖条，也很丑」。本版按方案 A 一次收口：**任何时刻最多一条轮廓线**。

## 现状取证（代码级，三个独立来源）

1. `FwListViewItemStyle` 与 `FwGridViewItemStyle` 的模板里都有 `SelectionBar`（3px、高 28、
   `Margin=6,0,0,0`），在 `Selected/PointerOverSelected/PressedSelected` 三个状态显示。
2. `FwGridViewItemStyle` **缺少** `UseSystemFocusVisuals=False`；`FwListViewItemStyle` 有。
   网格因此可能同时绘制系统焦点矩形与自研焦点环。
3. `ArchiveWindow._list`（`Views/ArchiveWindow.cs:36`）**没有** `ItemContainerStyle`，用的是
   WinUI 默认 `ListViewItem`。`20261103-keys08160/light6/archive-selected.png` 里贴窗口最左缘的
   蓝色短条，是默认模板的选中指示器，不是 `Design.xaml` 的 `SelectionBar`。

只改其中一处都会留下半套，所以本版同时处理三处。

## 改了什么

1. **删除强调条**：两个模板的 `SelectionBar` 与 `FwSelectionBarBrush` 资源移除。
2. **单层选中（方案 A）**：
   - 新增 `FwSelectionStrokeBrush`（深色 `#8069A8FF`、浅色 `#802563EB`、高对比度系统文本色）。
   - 新增 `FwItemBorderThickness`（常规 1、高对比度 2）。
   - 选中态：底色 `FwSelectionBrush` + 卡片描边改 `FwSelectionStrokeBrush`。
   - `Focused`：隐藏卡片描边，只保留现有双色圆角焦点环；`Unfocused/PointerFocused` 恢复描边。
   - `FwGridViewItemStyle` 补 `UseSystemFocusVisuals=False`。
   - `FwListViewItemStyle` 补上设计契约要求但此前缺失的 1px `CardBorder`。
3. **压缩包列表收口**：新增 `FwArchiveListItemStyle`（基于 `FwListViewItemStyle`，
   `Padding=0`、`Margin=0,0,0,2`、`MinHeight=40`），`ArchiveWindow` 套用；
   列头左内边距 58→44，与新的行内容盒对齐。
4. **契约更新**：`DESIGN.md` 的 Lists & tables 规则改为「半透明底 + 强调色描边；不得使用左侧
   3px 强调条；键盘聚焦时隐藏卡片描边；同一项最多一条轮廓线」，并记录网格必须关闭系统焦点视觉。
5. 版本号 `0.8.161` / `FileVersion 0.8.161.0`。

## 验证

- Release x64 构建 **0 警告 0 错误**（`0.8.161.0`），两次独立数据根启动冒烟均 `GATE=PASS`
  （15s，`responding=True`、标题含栖卷、无 `startup.log`）。
- `20261104-select08161/Probe-Selection.ps1`：真实服务端 + 真实夹具，浅色 `light3/report.txt`
  与深色 `dark3/report.txt` **各 20 项 PASS、0 FAIL**：
  - 详细网格、详细列表、紧凑列表：鼠标选中与键盘选中均产生选中项；
    键盘项 `HasKeyboardFocus=True`。
  - 每个选中项的「原 3px 条区域」高饱和蓝色像素计数 **0**（浅/深相同），
    证明短竖条已删除，而不是被底色掩盖。
  - 压缩包窗口：条目可选中，窗口最左缘 3px 区域的默认蓝条像素计数 **0**；
    默认 WinUI 选中指示器不再出现。
  - 实拍：`light3/grid-mouse.png`、`grid-keyboard.png`、`list-mouse.png`、`list-keyboard.png`、
    `compact-mouse.png`、`compact-keyboard.png`、`archive-selected.png`；`dark3/` 同名一套。
  - 视觉判读：鼠标选中 = 蓝底 + 蓝边，无焦点环；键盘选中 = 蓝底 + 焦点环，
    卡片描边已隐藏；浅色焦点环为深色内环 + 浅色光晕，深色为白色内环 + 深色光晕。
- 探针日志里仍会出现一次「用本地服务端地址打开连接页」的 `Invoke` 抖动
  （服务端页定时刷新导致），脚本重试后连接成功、`fixture list reachable` 为 PASS；
  这是探针已知抖动，不计产品缺陷。
- 键盘选中断言不强制停在初始行：`Down/Up` 会移动选择，探针断言的是
  「至少一行被选中且 `HasKeyboardFocus=True`」，与真实键盘行为一致。

## 交付物（本地未签名候选，等待实机验收）

- `Fileway-windows-artifacts/20261104-select08161/dist/Fileway-0.8.161-win-x64-unsigned-setup.exe`
  277,119,731 字节，SHA-256 `bdacb4a6575309947ea66446e66211f607445505effbba9b2161386912f7b37a`
  （与同目录 `.json` 回执一致）。
- 压缩包 `...-unsigned.zip` 279,745,652 字节，
  SHA-256 `2843b240d10640a19f9ce243284ded82f864f6e4ab80d8db9ef0db7902d0b0a5`。

## 本版未做（按规划继续）

- 压缩包窗口的面包屑摘要、名称/大小/类型/修改时间列、空目录图标卡：0.8.162。
- 解压目标可编辑、进度条、逐条结果：0.8.163。
- 压缩包内文本/图片预览：0.8.164。
- `ScreenboxTrackPicker` 仍是播放器深色专用列表，使用系统默认选中态，单独审计，不与文件选中态混改。
- 用户实机验收：重点看浅色/深色下鼠标点击与键盘导航时是否只剩一条轮廓线，
  以及压缩包窗口最左缘是否还有蓝色短条。
