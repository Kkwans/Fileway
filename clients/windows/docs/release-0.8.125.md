# 栖卷 Windows 0.8.125

0.8.125 统一收藏与标签列表的名称字重：由 14px 常规改为 14px SemiBold，与最近访问、搜索、文件任务、传输中心等列表的主名称一致；路径与“未分组”保持 12px 次级色，信息层级更清晰。

## 本版完成

- `ResourceCollectionsControl.xaml` 的 `CollectionTemplate` 名称文本增加 `FontWeight="SemiBold"`；收藏、标签资源、分组条目共用该模板，一次修改覆盖全页。
- 实拍：侧栏“管理”进入收藏页；名称行前后裁剪 `20261031-ui08125/screens/row-name-before.png`、`row-name-after.png`；整页 `screens/collections-from-sidebar.png`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.125.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui08125/dist/Fileway-0.8.125-win-x64-unsigned-setup.exe`，SHA-256 `41b129b38a58f92ed8b4e9b6350a19105d5e7dd4918cef8b960f16bdf3147904`，与回执一致。

## 已知问题

- 字重差异在小尺寸屏幕/低对比显示器上较细微，属列表统一性调整。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
