# 栖卷 Windows 0.8.19

0.8.19 统一“最近访问”和“存储工具”的界面语言。

## 本版完成

- 最近访问：返回/打开按钮统一 `FwSecondaryButtonStyle`，刷新统一图标按钮；空状态改为圆角图标容器 + 20px 标题；服务器路径按 UTF-8 解码展示，与显示路径相同时不再重复显示。
- 存储工具：原生单选列表换成横向分段选择器（`LayoutChoiceStyle`，半透明选中）；标题补充说明；分析范围输入框使用 `FwTextBoxStyle` 并限制 480–720 宽；开始分析改用主按钮样式；说明文字使用次级文本令牌。
- 三个页签的切换逻辑保持不变（分析 / 重复文件 / 用户管理），管理员页签仍按权限显示。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误。
- 两次冒烟 `GATE=PASS`（pwsh 7）。
- 实机截图：`screens/ui-panels2/recent-access-170225.png`、`storage-tools-170232.png`。
- 产物：`.../20261029-ui0818/dist/Fileway-0.8.19-win-x64-unsigned-setup.exe`，校验值见同目录 json。

## 未完成

- 文件夹上传批次：端到端需真人点击验收；传输中心仍按文件展示。
- 拖到文件夹移动 / 拖出到资源管理器：合成鼠标无法触发 WinUI 拖拽，需真人验收。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
