# 栖卷 Windows 0.8.6

0.8.6 将主工具栏的新建/上传/下载替换为自绘按钮，完成主窗口工具栏与下拉的视觉统一。

## 本版完成

- 主工具栏从 CommandBar 主命令改为独立的自绘按钮组（36px、10px 圆角、统一悬停/禁用态），保留原有下拉与“更多”溢出菜单。
- 新建/上传的菜单（文件夹、空文件；选择文件、选择文件夹）保持不变；下载按钮沿用原有权限与选中判断。
- 多语言键改为同时写入 ToolTip 与 Automation Name，保持可访问性与自动化探针可用。
- 实测截图：`Fileway-windows-artifacts/20261025-regression080/screens/ui-browser/browser-restyle-114400.png`。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误；两次冒烟 `GATE=PASS`。
- 产物：`.../20261029-ui086/dist/Fileway-0.8.6-win-x64-unsigned-setup.exe`，校验值见同目录 json。

## 未完成

- 搜索框、面包屑输入与部分弹出菜单仍是系统外观。
- PDF 沉浸式外壳仍待修复崩溃后再接入。
- 桌面拖放未开始；深色/浅色/高对比度未逐屏走查。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
