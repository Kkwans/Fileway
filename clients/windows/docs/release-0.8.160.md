# 栖卷 Windows 0.8.160

0.8.159 把初始焦点从标题栏按钮手里抢了回来，但四个查看器的键盘能力并不齐：
只有图片窗口接了按键，文本、压缩包、PDF 窗口按 Esc 关不掉，文本阅读器方向键不滚动，
压缩包列表方向键不选条目。本版补这条键盘路径。

## 现状取证（代码级）

- `ImageWindow`：`windowRoot.KeyDown` 已处理方向键/0/1/`+`/`-`/Esc（0.8.156 起）。
- `PdfWindow`：只有 `KeyboardAccelerator` 的左右翻页，**没有 Esc**。
- `TextWindow`：`FocusContent()` 之后没有任何按键处理——Esc 无效、PageUp/PageDown 无效。
- `ArchiveWindow`：`_list.Focus()` 两次尝试均返回 false（0.8.159 记录），也没有 KeyDown 接线。

## 改了什么

1. **三个窗口都有 Esc 关窗**：`TextWindow`、`ArchiveWindow`、`PdfWindow` 在窗口根上接
   `PreviewKeyDown`，Esc → `Close()`。文本窗口在**编辑态直接放行**按键，避免 Esc 绕过
   “放弃未保存修改？”确认框把改动丢掉。
2. **压缩包列表键盘导航**：焦点留在根容器（列表在本窗口里拿不到焦点），由根容器把按键翻译成
   条目选择——`Down/Up` 进入或移动选中（无选中时 Down 从首行、Up 从末行进入，与资源管理器一致）、
   `Home/End` 跳首末行、`Enter` 对当前行执行与双击相同的动作（目录进入，文件走“解压选中”确认框）、
   `Ctrl+A` 全选（走 `KeyboardAccelerator`）。选中行变化仍由既有 `SelectionChanged → UpdateCommands`
   驱动“解压选中”可用性。
3. **确认框优先于关窗**：解压确认框打开期间 Esc 只关对话框，不关窗口——为此给 `ExtractAsync`
   加了 `_confirming` 标记，`Escape` 分支在它为非空时不触发 `Close()`。
4. **文本阅读器键盘滚动**：`Up/Down` 走一行（24 DIP）、`PageUp/PageDown/Space` 走约九成视口、
   `Home/End` 跳首末。目标滚动面按当前可见模式取：Markdown 用 `_renderScroll`、CSV 用 `_tableScroll`、
   纯文本用只读编辑器内部的滚动面（模板实例化后从视觉树里取第一个 `ScrollViewer`）。
   仍然不把焦点交给只读 TextBox：它一聚焦就会在圆角文档面板底部画出 3px 强调色下划线（0.8.159 实测）。

## 验证

- Release x64 构建 **0 警告 0 错误**（0.8.160.0），两次启动冒烟 `GATE=PASS`（15s / 20s，独立数据根，无 `startup.log`）。
- 新增 `20261103-keys08160/Probe-Keys.ps1`：21 项断言，全部以**真实按键 + 真实结果**判定——
  PageDown/Home/End 前后各截一帧做像素差（要求滚动可见变化、回到顶部时差值 <0.5%），
  压缩包用 `ArchiveExtractButton.IsEnabled` 的 False→True 证明方向键真的选中了条目，
  `Enter` 后用 UIA 找“解压选中条目”确认框，再验证一次 Esc 只关框、第二次 Esc 才关窗，
  图片窗口 Esc 作为回归项一并跑。
- **键盘与实拍未完成**：本轮验证时段 Windows 会话再次锁定（`GetForegroundWindow()==0`、`LogonUI` 在跑、
  两帧逐字节相同），锁定下既送不进按键也打不开查看器窗口，探针已改为**检测到锁定就以
  INCONCLUSIVE 退出（exit 2）并留下说明**，不再把“窗口不存在”误判成断言通过。
  `20261103-keys08160/light1/report.txt` 就是锁定期间的那一份，只作为“探针会如实记账”的证据，
  **不能作为功能结论**。
- 解锁后需跑 `Probe-Keys.ps1 -Theme Light|Dark` 取 21 项断言与实拍，并特别确认纯文本模式
  能否拿到只读编辑器内部的滚动面（拿不到时方向键无效果，但不影响 Esc 与压缩包列表）。

## 交付物

**暂不打包**：键盘路径是这版的全部内容，未实测就不出包。
