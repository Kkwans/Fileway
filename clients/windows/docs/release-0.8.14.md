# 栖卷 Windows 0.8.14

0.8.14 重做目录空状态：图标容器、标题与说明统一到设计系统，不再出现裸文字和原生按钮。

## 本版完成

- 空目录状态改为居中卡片式排版：72px 圆角图标容器（`FwSurfaceBrush` + `FwStrokeBrush`）、20px 标题、13px/20 行高说明，全部使用设计令牌。
- “重新加载”按钮改用 `FwSecondaryButtonStyle`（加载失败的兜底状态），不再使用系统默认按钮。
- 空状态文案仍按场景区分：未连接、空目录、筛选无结果、加载失败。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误。
- 两次冒烟 `GATE=PASS`（pwsh 7）。
- 实机截图：`screens/ui-empty/empty-folder-155605.png`（07 空目录）。
- 产物：`.../20261029-ui0813/dist/Fileway-0.8.14-win-x64-unsigned-setup.exe`，校验值见同目录 json。

## 未完成

- 文件夹整组上传：协议层 `UploadLeaseRequest.Batch` 仍未发送，需补持久化与透传。
- 拖到文件夹移动 / 拖出到资源管理器：合成鼠标无法触发 WinUI 拖拽，需真人验收。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D；无界网格待复核。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
