# 播放器退出 C0000409 / CFG fast-fail 10 根因分析

更新日期：2026-10-24。本文记录 P-01 的证据链与当前结论；证据来自既有 CI 导出诊断，
未新开任何测试或本机运行。交互与断言细节见 [player-smoke.md](player-smoke.md)，
生命周期背景见 [media-lifecycle.md](media-lifecycle.md) 的“原生退出调查边界”一节。

## 证据来源

全部为既有 bounded 导出证据（`events.jsonl` / `report.json`，仓库外工件目录）：

| Run | 场景 | 应用退出码 | 结果 |
| --- | --- | --- | --- |
| `39087690339`、`39088124286`、`39088634175`、`39091009837` | 完整播放旅程（含设置三开、暂停/恢复、双全屏、播放面关闭） | `-1073740791`（`0xC0000409`） | 交互全部通过，主窗口关闭阶段崩溃 |
| `39088126099/A-minimal` | 播放后正常关播放器再关主窗 | 同上 | 同一签名 |
| `39088126099/B-open-player` | 保留播放器直接关主窗 | 同上 | 同一签名，触发点更早 |

另有 2026-10-18 本机调查（`20261018-hook-provenance`）：cdb 从进程创建附加，
`bu user32!SetWindowsHookExA/W`、`bu user32!UnhookWindowsHookEx` 全程未触发，
该次本机运行也未复现 CFG 故障。

## 崩溃点与调用链

导出的 `shutdown-native-frame` / `shutdown-dispatch-frame` / `shutdown-dispatch-instruction`
证据共同确立（以 `39091009837` 为准，其余 run 偏移一致）：

```
win32u!NtUserDestroyWindow（B 场景为 Microsoft.UI.Input 发起的另一 win32u 调用）
  → ntdll!KiUserCallbackDispatcherContinue
  → USER32+0x47E73（A/完整旅程）或 USER32+0x4DB66（B 场景）＝内核回调处理器（__fnHk* 族）
  → USER32+0x365AE 所在函数（0x364B0..0x368CA，末尾 call DispatchHookA）
  → USER32!DispatchHookA（RVA 0x366E0 起，帧 3，symbolId 5）
  → DispatchHookA+0x83 处 CFG 保护间接调用：目标＝已释放的钩子过程
  → ntdll!RtlpHandleInvalidUserCallTarget → RtlFailFast2 → 0xC0000409 fast-fail 10
```

关键事实：

- 故障调用是 `DispatchHookA`（**ANSI** Windows 钩子派发器）对钩子过程的受保护间接调用。
  本应用及其全部依赖均为 Unicode；ANSI 钩子过程是外部遗留软件的典型特征。
- `shutdown-cfg-helper-argument` 分类 6：候选地址在捕获时位于 `MEM_FREE` 区域，
  不在任何已加载或已卸载的已知模块范围内。
- `shutdown-cfg-pre-close-module` 映射 0：关闭前快照中，该地址也不在任何已知模块范围内。
- `shutdown-cfg-saved-arguments`：`targetMatches=1`（确认派发目标即该已释放地址），
  `messageKind=0`（被钩消息**不是** WM_CLOSE/WM_DESTROY/WM_NCDESTROY），
  `windowRole=0`（消息目标窗口**不是**主窗口/播放器/HUD）。
- 关机面包屑：阶段 1–8、10–13 完成、阶段 9（主窗口 close 调用返回）永远缺失——
  崩溃在所有应用层 `Closed` 回调返回之后的原生销毁消息流中。

## 结论：失效目标不是 Fileway 拥有的回调

- 被调用的是一个**外部安装的 ANSI Windows 钩子过程**（CALLWNDPROC 族内核回调），
  其内存在应用开始关闭消息流之前已被释放。销毁窗口必然产生大量 SendMessage 流量，
  任何窗口销毁都会派发到该全局钩子，与具体窗口、具体消息无关——这与
  `messageKind=0` / `windowRole=0`（三种不同关闭路径全部命中同一派发点）完全一致。
- 进程内自有来源已被排除：
  1. 仓库源码无任何 `SetWindowsHookEx`/`SetWinEventHook` 调用；
  2. 本机 2026-10-18 全程断点证明本进程从未通过 user32 安装/卸载钩子；
  3. 逐一检查交付二进制的导入表：`Fileway.App.exe`、`WinUIEx.dll`、
     `Microsoft.UI.Input.dll`、`Microsoft.UI.Xaml.dll`、`Microsoft.UI.Windowing.Core.dll`、
     `Microsoft.WindowsAppRuntime.dll`、`Microsoft.Internal.FrameworkUdk.dll`、
     `Microsoft.InteractiveExperiences.Projection.dll` 均无钩子 API 引用。
- B 场景栈中的 `Microsoft.UI.Input` 帧是发起窗口调用（从而触发消息派发）的调用方，
  不是钩子所有者；其模块身份已由 `shutdown-dispatch-module`（`shutdown-dispatch-input-candidate` matches=1）
  指纹单独核实，仅为环境相关性记录。
- 结论：这是**第三方/环境级故障模式**——陈旧的外部全局钩子指向已释放内存，
  任何 Win32 程序在相同环境关闭窗口都会同样 fast-fail。它不是 Fileway 的
  callback/窗口/native 生命周期缺陷；应用侧不存在可以“修复”的归属对象。

## 边界与未决项

- 尚未指认外部钩子的具体所有者。静态证据只能证明“不属于本进程的全部交付二进制”。
  需要一次实时有界捕获（在既有 shutdown-debugger 附加会话中，于关闭前对
  `USER32!DispatchHookA` 设断点，仅记录钩子过程参数所在模块的 basename 与偏移类别）
  才能命名所有者。该步骤属于 harness/测试改动，当前用户规则下不编写、不运行，
  待重新授权后作为独立小切片实施。
- 本机（用户日常环境）从未复现该崩溃，与“CI 运行器上的陈旧全局钩子”解释一致；
  用户人工测试中未报告退出崩溃。这不关闭问题：定位所有者并拿到无钩子环境的
  退出 0 证据之前，P-01 保持开放。
- 保持不变：不关闭 CFG、不降低安全机制、不吞异常、不通过长驻 DLL 或保留加载掩盖故障；
  附加调试器的运行永远不能作为正常退出验证（`player-smoke.md` 既有规则）。
- 2026-10-18 本机调查还记录过一次独立现象：progressive 播放期间
  `RtlpBreakPointHeap` 报 "Free Heap block modified after it was freed"
  （堆释放块被改写，无栈记录）。它与 CI CFG 故障的内存形态不同（堆内块 vs 整段
  未映射 VA），当时未捕获调用栈，作为独立开放观察保留，不并入 P-01 结论。

## 对计划的影响

- P-01 从“修 Fileway callback/窗口/native 生命周期”改判为“外部钩子故障的身份指认 +
  无钩子环境退出验证”。不阻塞 0.3.5 及后续功能批次。
- 完整关闭证据的最终定义保持：无调试器附加、应用与 Host 双零退出；且需要解释
  本分析的证据链（同 SHA、同环境）。在此之前任何版本的退出稳定性声明都不得引用
  附加调试器的运行。
