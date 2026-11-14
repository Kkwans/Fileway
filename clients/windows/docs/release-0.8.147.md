# 栖卷 Windows 0.8.147

0.8.147 完成高对比度 UI 走查（已获用户授权，临时开启后恢复系统设置）。发现并修复一个真实缺陷：侧栏“当前页面”在常规主题下依赖 `SubtleFillColorSecondaryBrush` 填充，高对比度下该填充不可见，导致无法判断当前所在页面。本版为选中导航增加主题化背景/描边资源：常规主题保持填充，高对比度改用透明底 + 系统文本色 1px 圆角描边，避免高亮底与白字/白图标的对比问题。

## 本版完成

- 新增 `FwNavSelectedBackgroundBrush` / `FwNavSelectedBorderBrush` / `FwNavSelectedBorderThickness` 三套主题资源；`SidebarSelectedNavigationButtonStyle` 使用它们。
- `FwNavigationButtonStyle` 模板新增 `SelectionBorder`（TemplateBinding BorderBrush/BorderThickness，默认 0，不改变普通导航）。
- 高对比度走查证据：`20261101-ui08147/hc3/browser-hc-expanded.png`（我的文件选中为白色圆角描边）、`recent-hc.png`、`menu-hc.png`、`appearance-hc.png`；对比修复前 `hc2/browser-hc-expanded.png`（无选中态）。系统高对比度已从 Flags 127 恢复到 126。
- 常规深色复核 `20261101-ui08147/normal/recent.png`：选中导航使用 `FwSurfaceHoverBrush` 填充，未出现过重边框。
- 高对比度下菜单焦点、弹层表单、网格文字与按钮描边均可用；MenuBar 白色边框保留系统外观（DESIGN.md 例外条款）。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.147.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261101-ui08147/dist/Fileway-0.8.147-win-x64-unsigned-setup.exe`，SHA-256 `a85512d1663f8c0d6c71f13be5779da698b9859de69cdb59067479a719d306ec`，与回执一致。

## 已知问题

- 高对比度只覆盖主浏览、最近访问、菜单与外观弹窗；传输/任务/回收站/查看器未逐一 HC 截图。
- 系统 MenuBar 项在 HC 下带系统白色边框，保留系统外观。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
