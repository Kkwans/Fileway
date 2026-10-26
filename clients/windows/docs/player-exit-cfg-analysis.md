# 播放器退出 CFG 故障与 OpenGL 提供方修复

更新日期：2026-10-27。新的本机捕获纠正了此前“仅属于外部钩子”的判断：固定媒体闭包中的
Mesa `opengl32.dll` 会加载 `libgallium_wgl.dll`，后者具有实际被派发的 ANSI 钩子。
显式选择 Windows System32 OpenGL 后，无调试器的播放后退出已通过。
交互与断言细节见 [player-smoke.md](player-smoke.md)，
生命周期背景见 [media-lifecycle.md](media-lifecycle.md) 的“原生退出调查边界”一节。

## 证据来源

2026-10-24 之前的 bounded 导出证据（`events.jsonl` / `report.json`，仓库外工件目录）：

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
  ANSI 并不能证明它属于外部软件；此前未覆盖媒体 DLL 的归属排除不成立。
- `shutdown-cfg-helper-argument` 分类 6：候选地址在捕获时位于 `MEM_FREE` 区域，
  不在任何已加载或已卸载的已知模块范围内。
- `shutdown-cfg-pre-close-module` 映射 0：关闭前快照中，该地址也不在任何已知模块范围内。
- `shutdown-cfg-saved-arguments`：`targetMatches=1`（确认派发目标即该已释放地址），
  `messageKind=0`（被钩消息**不是** WM_CLOSE/WM_DESTROY/WM_NCDESTROY），
  `windowRole=0`（消息目标窗口**不是**主窗口/播放器/HUD）。
- 关机面包屑：阶段 1–8、10–13 完成、阶段 9（主窗口 close 调用返回）永远缺失——
  崩溃在所有应用层 `Closed` 回调返回之后的原生销毁消息流中。

## 2026-10-27 本机归属与对照

- 原始运行包播放 H264、关闭播放器后退出，仍为 `0xC0000409` / fast-fail 10。
  当前 dump 的调用链为 WinUI InputStateManager 消息窗口销毁 → `DispatchHookA` → CFG；
  服务释放阶段 1–8 在约 153ms 内完成，主窗口 Close 未返回。旧结论不能替代该次证据。
- 关闭前的有界调试捕获读取 `DispatchHookA` 第四参数 `r9`，实际目标包含
  `libgallium_wgl.dll + 0x98ffa0`；模块来自交付包的 `media` 目录。
  同场也观察到 RTSS 钩子，但不能把模块共存当作它导致此次故障的证据。
- 固定锁证明 `libmpv-2.dll` 导入 `opengl32.dll`，媒体目录内的 Mesa 代理再导入
  `libgallium_wgl.dll`。此前只审查应用/WinUI DLL，没有排除这条媒体依赖链。
- 保持应用代码和其他 DLL 不变，只在隔离诊断副本中移走 Mesa 代理，系统 OpenGL 被加载，
  Gallium 未加载，H264 播放后无调试器退出为 0。随后产品加载策略修复在完整原始闭包仍在的
  条件下得到相同结果。调试器捕获本身没有复现崩溃，不能作为退出验收。

证据位于外部 `20261026-phaseb-081/logs`：`batch7-media-exit-r1.log`、
`batch7-exit-dump.log`、`batch7-live-hook.log`、`batch7-media-systemgl-r1.log`、
`batch7-media-fixed-r1.log`。dump 和调试日志不进入 Git。

## 修复与边界

`MpvNative` 先以绝对 System32 路径加载 Windows `opengl32.dll`，再按既有受限搜索路径加载
固定 mpv。依赖会按已加载模块列表解析到系统提供方，符合
[Windows DLL 搜索规则](https://learn.microsoft.com/en-us/windows/win32/dlls/dynamic-link-library-search-order)。
播放器仍使用既定 D3D11 输出；vendor、固定锁、DLL 哈希与完整运行库输入保持不变。

构造失败释放已取得的两个引用；正常销毁先释放 mpv，再释放本次取得的系统 OpenGL 引用。
不关闭 CFG，不更改全局 DLL 搜索路径，不保留已卸载驱动或跳过资源释放，不修改 RTSS 配置。

这确认并修复本机媒体依赖选择问题，不追溯宣称旧 CI 每一次故障都已完成同进程地址归属，
也不关闭曾单独出现、尚无完整堆栈的 progressive 堆写坏观察。
最终候选仍须按实际提交和环境验证无调试器的播放、应用/Host 退出，不能复用调试器退出码。
