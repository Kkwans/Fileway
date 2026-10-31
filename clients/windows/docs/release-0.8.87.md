# 栖卷 Windows 0.8.87

0.8.87 统一弹层外观：工具栏排序/类型/布局 Flyout、文件右键菜单、更多菜单、任务/回收站/收藏菜单与播放器菜单全部改为 14px 圆角 + 1px 应用描边，并跟随应用主题（应用深色而系统浅色时不再出现浅色弹层）。

## 本版完成

- 工具区三处 DropDownButton Flyout 改为 14px 圆角、1px 描边、应用 Acrylic 背景；此前是系统默认 8px 且不跟随应用主题。
- 文件右键菜单与“更多文件操作”菜单同步 14px + 描边；右键菜单原为 10px 无描边。
- 任务“更多任务操作”、回收站右键菜单、收藏/标签两处“更多”与列表右键菜单接入同一弹层样式。
- 播放器 Flyout/MenuFlyout 样式由 8px 改为 14px（深色播放器语境保持深色）。
- 修复实现细节：WinUI 不应用应用级 `FlyoutPresenter` 隐式样式，且在 `Opening` 设置 presenter style 会被模板创建时序覆盖。现由 `SurfaceStyles.Attach` 在 `Opened` 后通过 `XamlRoot` 的打开弹层列表定位同一内容的 `FlyoutPresenter`，直接设置圆角/描边/背景；`MenuFlyout` 仍用 `MenuFlyoutPresenterStyle`。
- 浅色 1800×1100 实拍：`screens/final-light/`；深色（应用主题深色、系统浅色）实拍：`screens/final-dark/`；修复前对照：`screens/flyout-sort.png`、`screens/context-menu.png`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.87.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui0887/dist/Fileway-0.8.87-win-x64-unsigned-setup.exe`，SHA-256 `c89847ff0d5dcba4fae9a1ebb6d352ed43f0255fd009b98d157305a0690a1750`，与回执一致。

## 已知问题

- `MenuBar` 的文件/编辑/视图/工具/帮助下拉仍使用系统菜单外观（DESIGN 明确保留系统语义）。
- 播放器菜单的 14px 改动为样式层修改，未单独截图其打开态。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 文件批次的批次卡实拍仍需真人选择文件夹；拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
