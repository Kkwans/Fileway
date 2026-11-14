# 栖卷 Windows 0.8.116

0.8.116 收敛收藏与标签页：页面副标题改为“管理当前账号的收藏、分组与标签；可从文件列表添加。”，收藏行不再使用通用文件图标，改为与页面收藏语义一致的 Lucide 收藏图标；缓存与存储对话框的滚动区改为常驻纵向滚动条，提示还有更多设置。

## 本版完成

- 收藏与标签页：副标题从“从文件列表选择一个项目，可在这里收藏或设置标签。”改为页面功能描述；无当前资源时不再像操作提示。选中资源时仍显示“选中资源：路径”。
- 收藏行图标：`CollectionRow.IconName` 对收藏返回 `heart`（界面显示为收藏星形），标签资源行仍为 `file`；不再把文件夹收藏画成文件。
- 缓存与存储：`BodyScroll.VerticalScrollBarVisibility` 由 Auto 改为 Visible，首个视口右侧出现滚动条轨道，提示“默认 5 GB…”等说明与缓存占用在下方。
- 证据：收藏页修复前 `20261031-ui08116/screens/menu-click/collections-page.png`，修复后 `screens/collections-after/collections-page.png`；缓存滚动条 `screens/cache-after/collections.png`、`screens/cache-after/scroll-strip.png`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.116.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui08116/dist/Fileway-0.8.116-win-x64-unsigned-setup.exe`，SHA-256 `9e26ee7cb621febed01b7058acae43b24eb98e878570582cd25cd97803745a65`，与回执一致。

## 已知问题

- 收藏引用本身不携带文件/文件夹类型，收藏行统一用收藏图标；标签资源行仍为通用文件图标。
- 首个视口底部说明仍需滚动查看，滚动条已常驻提示。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
