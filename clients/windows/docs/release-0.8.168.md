# 栖卷 Windows 0.8.168

0.8.167 的 P1-3 只做了验证、没有布局缺陷，因此没有发无代码版本。本版是回归收口：
修正压缩包列头用透明度降对比度的高对比度风险，并把 P0/P1 各页面的验证证据收敛成一张回归矩阵。

## 改了什么

1. `ArchiveWindow.HeaderText` 不再用 `Opacity=0.65`，改用 `FwCaptionTextStyle`：
   常规主题是 12px 次级色，高对比度主题通过 `FwTextSecondaryBrush` 映射到系统窗口文本色，
   不会把系统文本色再压到 65%。行内容与列头几何不变。
2. 版本号 `0.8.168` / `FileVersion 0.8.168.0`。

## 验证

- Release x64 构建 **0 警告 0 错误**，两次独立数据根启动冒烟均 `GATE=PASS`。
- 压缩包回归 `20261105-regress08168/Probe-Archive.ps1`：浅色 `light1`、深色 `dark1`
  **各 27 项 PASS、0 FAIL**：
  - 四列列头存在；名称左缘、大小右缘、类型左缘、修改时间右缘与行内容 **delta=0**。
  - 根目录 7 条/69.41 KB、子目录 2 条/16.37 KB 的面包屑摘要正确；状态栏与选中数正确。
  - 空目录标题/说明、隐藏列头、无标题栏按钮持焦均通过。
  - 实拍：`light1/archive-root.png`、`archive-subdir.png`、`archive-empty.png`；`dark1/` 同名一套。
- 高低对比度实拍**本版未取得**：切换 Windows 系统高对比度是系统级可见设置，未在未获确认时改动；
  本版只做了静态审计（`FwSelectionStrokeBrush`/`FwItemBorderThickness`/`FwCaptionTextStyle`
  在 HighContrast 字典中均映射系统色），HC 实拍继续列为待真机验收。
- 打包时发现 publish 目录混入手动启动服务端生成的 `filebrowser.db`；已移出到 artifacts
  （未删除、未打包），随后打包成功。

## 回归矩阵（证据索引）

| 页面/能力 | 版本 | 证据 | 结论 |
| --- | --- | --- | --- |
| 主浏览选中态（网格/详细/紧凑） | 0.8.161 | `20261104-select08161/light3`、`dark3`，各 20 PASS | 浅/深通过；无蓝条、单层轮廓 |
| 压缩包浏览（四列/摘要/空目录） | 0.8.162、0.8.168 | `archive08162/light5`、`dark3` 各 27 PASS；`regress08168/light1`、`dark1` 各 27 PASS | 浅/深通过，列对齐 delta=0 |
| 压缩包解压（目标/进度/逐条结果） | 0.8.163 | `extract08163/light4`、`dark2`，各 17 PASS | 浅/深通过；进度条中途实拍待补 |
| 压缩包预览（文本/图片） | 0.8.164 | `preview08164/light2`、`dark1`，各 22 PASS | 文本通过；图片画布待真机 |
| 收藏与标签页 | 0.8.165 | `collections08165/light4`、`dark1`，各 28 PASS | 浅/深通过；分组菜单截图受弹层裁切 |
| 文件任务/操作记录 | 0.8.166 | `tasks08166/light4`、`dark2`，各 18 PASS | 浅/深通过；列头 delta=0 |
| 传输中心/下载详情 | P1-3 验证 | `transfers08167/transfer-seeded-dark` | 深色通过；下载目录已隔离到 artifacts |
| 回收站详情 | P1-3 验证 | `transfers08167/trash-final` | 深色通过；字段断言通过 |
| 高对比度全页 | 未完成 | 静态审计 | **待真机/系统 HC 验收** |
| 文件夹整批上传/桌面拖放/PDF 沉浸外壳 | 未完成 | — | 需真人或原生修复，未标通过 |

## 交付物（本地未签名候选，等待实机验收）

- `Fileway-windows-artifacts/20261105-regress08168/dist/Fileway-0.8.168-win-x64-unsigned-setup.exe`
  277,109,105 字节，SHA-256 `934c7c4b7458b8b9f4ae72677ad95b85bd0ae1952c0d907759f08f071e31c87b`
  （与同目录 `.json` 回执一致）。
- 压缩包 `...-unsigned.zip` 279,756,205 字节，
  SHA-256 `96b859c0d7a3962d993b1324574546d61316ca43d8b03f329a6dc345621b76f8`。

## 剩余

- 系统高对比度实拍（需用户同意切换系统设置）与文件夹整批上传/拖放真人验收。
- 进度条中途实拍、图片画布实拍、PDF 原生堆破坏修复不在本版范围。
- 夹具目录 `回归-文本(2..6).txt` 副本与用户 `D:\Kkwans\Downloads\回归-样张.png` 仍待用户确认清理。
