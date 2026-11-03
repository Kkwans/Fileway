# 栖卷 Windows 0.8.159

0.8.158 的四张实拍里都有一个多余的东西：**标题栏最小化按钮带着键盘焦点框**。
查下来不是拍照伪影，而是真实的初始焦点分配错误。

## 缺陷取证

先按 UIA 逐元素枚举焦点（`20261103-viewers08158/focus-base`、`focus2`）：

| 窗口 | 修复前 |
| --- | --- |
| 文本查看器 | `MinimizeButton hasFocus=True` |
| 压缩包查看器 | `MinimizeButton hasFocus=True` |
| 图片查看器 | 无（构造里已显式 `windowRoot.Focus(Programmatic)`） |

后果不是难看，而是**按 Enter / Space 会直接最小化窗口**：用户刚打开一个文件，
键盘第一次落下就把窗口收起来。图片查看器因为早就显式设过初始焦点而幸免。

## 修了什么

1. `TextWindow`、`ArchiveWindow`、`PdfWindow` 在内容加载完成后显式接管初始焦点
   （根容器 `IsTabStop=true` + `Focus(Programmatic)`，并在 `DispatcherQueue.TryEnqueue`
   里补一次，覆盖布局完成前焦点拿不住的情况）。
2. **文本阅读器不聚焦只读 TextBox**：实测聚焦编辑器会让 TextBox 模板画出强调色下划线，
   在圆角文档面板底部裁成一条 3px 蓝线（`final-light/text-viewer.png` 检出 y=706–708 两条
   强调色扫描线，`final2-light` 为 0 条），比焦点框更像故障。因此焦点交给根容器；
   用户点进正文时仍会自然获得编辑器焦点与全选/复制能力。
3. 压缩包窗口同样先由根容器持焦。`_list.Focus()` 立即调用与布局后重试都返回 false，
   所以**方向键进出条目仍未生效**——这不是本版引入的回归（该窗口此前没有任何键盘接线），
   作为遗留项记入 DESIGN.md，不谎报为已可用。

## 验证

- **焦点断言**：`Probe-Viewers.ps1` 新增「窗口打开后焦点不在任何标题栏按钮上」，
  浅色 `final2-light/report.txt` 与深色 `final2-dark/report.txt` **各 13 项全 PASS、0 FAIL**
  （含文本窗口打开、`TextEditor` ValuePattern 读回正文、`EditTextButton` 存在、
  压缩包列表存在、图片窗口打开、三张截图落盘）。
- **像素复核**：强调色下划线扫描 修复前 2 行 → 修复后 0 行；深色的文本与压缩包窗口均为 0 行。
- **实拍**：`final2-light/text-viewer.png`、`final2-light/archive-viewer.png`、
  `final2-dark/text-viewer.png`、`final2-dark/archive-viewer.png`，标题栏干净无焦点框，
  浅/深配色与 0.8.158 一致。
- Release x64 构建 0 警告 0 错误；两次启动冒烟 `GATE=PASS`（15s / 20s，独立数据根，无 `startup.log`）。
- 探针自身也修了一处抖动：服务端页定时刷新会让「用本地服务端地址打开连接页」的 `Invoke`
  偶发抛「无法识别的错误」，现在最多重试三次；此前两次深色跑失败就是被它挡住的。

## 交付物（本地未签名候选，等待实机验收）

- 见本文末尾追加的安装包哈希。
