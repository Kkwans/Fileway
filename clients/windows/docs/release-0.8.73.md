# 栖卷 Windows 0.8.73

0.8.73 给压缩包查看器补上列表列头“名称 / 大小”，并完成 Markdown、图片、压缩包三类查看器在 1200×900 下的排版复核。

## 本版完成

- 压缩包条目列表新增 12px 次要色列头：左侧“名称”与文件/文件夹名对齐（左内边距 58 DIP），右侧“大小”与条目大小右对齐（右内边距 25 DIP）。
- 列头在空目录时自动隐藏；提示条 4 秒自动收起后，列头与列表仍保持对齐。
- 查看器复核：Markdown 渲染视图保持居中的阅读列；图片查看器错误态、翻页与缩放胶囊正常；压缩包头部按钮、面包屑与状态栏无溢出。
- 压缩包窗口行定义补齐为标题栏/工具条/面包屑/提示/列头/列表/状态栏，列头不会挤压列表可视高度。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.73.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机截图：`screens/archive/archive-viewer.png`（无列头）、`screens/archive/after2/archive-viewer.png`（列头对齐）、`screens/markdown-viewer.png`、`screens/image-viewer.png`。
- 产物：`.../20261031-ui0873/dist/Fileway-0.8.73-win-x64-unsigned-setup.exe`，SHA-256 `f74629634a51de6267d43c815dfb77168dafeb2e4b24a0af04bdafe612409a89`，与回执一致。

## 已知问题

- 压缩包列头按当前列表内边距硬编码 58/25 DIP；若后续改列表项内边距需同步调整。
- 菜单项与 Expander 头部的键盘焦点仍使用 WinUI 系统矩形焦点视觉（内部模板，暂列系统语义例外）。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 文件批次的批次卡实拍仍需真人选择文件夹；拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
