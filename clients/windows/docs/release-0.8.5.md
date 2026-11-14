# 栖卷 Windows 0.8.5

0.8.5 按 DESIGN.md 统一主浏览窗口的基础控件，修复侧栏回归。

## 本版完成

- 主窗口按钮、下拉与网格容器统一到设计令牌：
  - 工具栏与导航按钮改用自绘 `FwIconButtonStyle`（圆角 10、悬停/按下统一），不再使用系统 SubtleButton 模板。
  - 排序/类型筛选/布局三个下拉统一 10px 圆角、34px 高度与主题描边。
  - 文件网格卡片增加 12px 圆角、10px 内边距与 8px 行距，名称 13.5px。
- 新增 `FwNavigationButtonStyle`：侧栏导航左对齐、拉伸宽度、36px 高、10px 圆角；修复继承图标按钮宽度导致侧栏文字被裁成单字的问题。
- 实测截图：`Fileway-windows-artifacts/20261025-regression080/screens/ui-browser/browser-restyle-113605.png`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误。
- 启动冒烟两次：`GATE=PASS`。
- 产物：`.../20261029-ui085/dist/Fileway-0.8.5-win-x64-unsigned-setup.exe`，校验值见同目录 json。

## 未完成

- PDF 沉浸式外壳仍因 WinUI 崩溃（`CoreMessagingXP 0xc000027b`）未合入；内置 PDF 使用稳定旧界面。
- 主窗口的 AppBarButton（新建/上传/下载/更多）与搜索框仍为系统外观，尚未替换。
- 桌面拖放、深色/浅色/高对比度逐屏走查未完成。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
