# 栖卷 Windows 0.8.18

0.8.18 把传输中心与回收站拉回同一套设计语言。

## 本版完成

- 传输中心：下载/上传标签改为半透明强调底（不再系统实心蓝）；“返回文件”“加载更多”“重试”统一 `FwSecondaryButtonStyle`，刷新按钮统一 `FwIconButtonStyle`；空状态改为圆角图标容器 + 20px 标题 + 13px 说明。
- 回收站：标题增加说明文案；恢复/刷新按钮统一图标按钮样式；空状态卡片化，并在为空时隐藏“名称/大小/删除时间”列头。
- `LayoutChoiceStyle` 移入应用级 `Design.xaml`：该样式此前定义在 `MainPage.xaml` 页面资源中，`UserControl` 解析不到会导致启动崩溃。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误。
- 两次冒烟 `GATE=PASS`（pwsh 7）。
- 实机截图：`screens/ui-panels4/transfer-center-164516.png`、`trash-164523.png`；最近访问/存储工具截图同目录。
- 产物：`.../20261029-ui0817/dist/Fileway-0.8.18-win-x64-unsigned-setup.exe`，校验值见同目录 json。

## 未完成

- 文件夹上传批次：端到端需真人点击验收；传输中心仍按文件展示。
- 拖到文件夹移动 / 拖出到资源管理器：合成鼠标无法触发 WinUI 拖拽，需真人验收。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
