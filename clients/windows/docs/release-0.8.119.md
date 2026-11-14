# 栖卷 Windows 0.8.119

0.8.119 收敛两处状态文案：主浏览底部在未选择时不再显示“已选择 0 个”，只有实际选中后才拼接选中数量；收藏与标签页自动刷新成功不再弹出“已刷新收藏与标签。”提示，手动刷新仍保留成功回执，未确认写入的警告始终显示。

## 本版完成

- 主浏览状态栏：有内容且未选择时显示“12 个项目 · 双击或按 Enter 打开”；选中后显示“12 个项目 · 已选择 1 个 · 双击或按 Enter 打开”。空目录保持“0 个项目”。
- 收藏与标签刷新：`RefreshAsync(notify)` 默认静默；打开页面、连接后刷新、数据变更刷新不再弹成功提示；手动“刷新”按钮传 `notify: true`，仍显示“已刷新收藏与标签。”。`_unknownWrite` 的警告与失败提示不受影响。
- 证据：`20261031-ui08119/screens/status/browser-none.png`（无已选择 0）、`browser-selected.png`（已选择 1）；`screens/collections-toast/collections-page.png`（打开无提示）、`collections-refreshed.png`（手动刷新有提示）。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.119.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 产物：`.../20261031-ui08119/dist/Fileway-0.8.119-win-x64-unsigned-setup.exe`，SHA-256 `f9c0b9c69492a371328591e5cd3bf6f5a134c0c95d95366ea3d210d500f7e876`，与回执一致。

## 已知问题

- 收藏页自动刷新与手动刷新的成功提示已区分；未确认写入仍按警告每次显示。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 整棵文件夹树上传/批次卡与拖拽仍待真人验收；PDF 沉浸式外壳的原生堆破坏未修复。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
