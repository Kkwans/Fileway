# 栖卷 Windows 0.8.32

0.8.32 修正侧栏图标语义：存储工具不再与文件任务共用同一个图标。

## 本版完成

- 侧栏“存储工具”由 `file-code` 改为 `gear`，与“文件任务”的 `file-code` 区分开；图标仍为既有 Lucide 资源，不新增依赖。
- DESIGN.md 图标规则补充：不同侧栏入口不得复用同一图标名。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.32.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机截图：`screens/sidebar-gear.png`（侧栏“文件任务”为文件图标、“存储工具”为齿轮图标）。
- 产物：`.../20261030-ui0832/dist/Fileway-0.8.32-win-x64-unsigned-setup.exe`，SHA-256 `603e4bc9…b0350c591`，与回执一致。

## 未完成

- 高对比度主题未走查（需调整系统设置，待用户同意）。
- 批次卡仍无用真实文件夹上传产生的截图，需真人上传后查看上传页。
- 拖到文件夹移动 / 拖出到资源管理器：合成鼠标无法触发 WinUI 拖拽，需真人验收。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
