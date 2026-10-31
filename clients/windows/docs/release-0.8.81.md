# 栖卷 Windows 0.8.81

0.8.81 调整文件任务的窄窗口布局：1200×900 下选中任务后不再把页面上下劈成两半，而是隐藏任务列表、详情占满内容区，并提供“返回列表”。

## 本版完成

- 窄布局（内容宽度 < 880 DIP）选中任务/操作记录时：`ListArea` 收起，`DetailsPane` 占满 Body 行，详情标题右侧显示“返回列表”按钮。
- “返回列表”清空当前选中并恢复任务列表；宽窗口仍保留右侧 340 DIP 详情栏，不显示返回按钮。
- 详情内容继续使用内部 ScrollViewer；“任务编号/记录信息”仍为 FwDisclosure。
- 顺带复核修改密码弹窗：1200×900 下三个密码框横向拉伸、说明与字段左对齐、取消/修改按钮固定底部，无裁切。

## 验证

- UIA 状态验证窄布局切换：打开详情后 `TaskRecords` IsOffscreen=True、返回按钮可见；Invoke 返回后 `TaskRecords` IsOffscreen=False。
- 截图：`screens/after6/task-detail-full.png`、`task-list-returned.png`。自动化时侧栏遮罩保持打开，截图左侧被遮罩覆盖，但 UIA 状态与返回恢复均通过。
- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.81.0）；两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，无 startup.log）。
- 产物：`.../20261031-ui0881/dist/Fileway-0.8.81-win-x64-unsigned-setup.exe`，SHA-256 `92e84e30d41f4874cdb0dbf37b1f9a111162be7cbeef9ddb8f17a541a6d73d88`，与回执一致。

## 已知问题

- 窄布局使用“列表/详情”二选一，不再同时显示；如需对照列表可返回后重新选择。
- 菜单焦点仍为系统矩形（颜色已用应用双色），要圆角需整体替换 MenuFlyoutItem/SubItem 模板。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 文件批次的批次卡实拍仍需真人选择文件夹；拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
