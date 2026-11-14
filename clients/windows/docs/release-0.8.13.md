# 栖卷 Windows 0.8.13

0.8.13 统一主工具栏选择器：排序不再使用原生 ComboBox，并修掉连接成功后残留的进度提示。

## 本版完成

- 排序控件由 `ChoiceBox`（原生 ComboBox）替换为自绘 `DropDownButton` + 弹出菜单，与“类型筛选”“视图”同款 `FilewaySelectorButtonStyle`；选中后菜单自动收起，按钮显示“名称 ↑ / 大小 ↓”等当前状态。
- 三个工具栏选择器统一左对齐（12px 内边距），与路径栏、搜索框左侧基线一致；菜单项使用统一 `LayoutChoiceStyle` 单选列表。
- 修复连接成功后右下角残留“正在连接服务器…”浮动提示：登录提示会被浮动通知宿主托管，连接成功时显式关闭。
- 排序偏好仍按原逻辑持久化；切换排序立即重排列表。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误。
- 两次冒烟 `GATE=PASS`（pwsh 7）。
- 实机截图：排序菜单展开 `screens/ui-sort/sort-size-desc-154153.png`；选择“大小 ↓”后菜单收起、列表重排 `screens/ui-sort/sort-size-desc-154516.png`；连接后主界面无残留提示 `screens/ui-verify3/main-after-login-153848.png`。
- 产物：`.../20261029-ui0813/dist/Fileway-0.8.13-win-x64-unsigned-setup.exe`，校验值见同目录 json。

## 未完成

- 文件夹整组上传：协议层 `UploadLeaseRequest.Batch` 仍未发送，需补持久化与透传。
- 拖到文件夹移动 / 拖出到资源管理器：合成鼠标无法触发 WinUI 拖拽，需真人验收。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D；无界网格待复核。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
