# 栖卷 Windows 0.8.3

0.8.3 是 UI 规范落地的第二个候选：把所有对话框的主按钮统一到右侧，并首次建立可执行的设计契约。功能行为与 0.8.2 一致。

## 本版完成

- 新增全局对话框排序：`Controls/DialogOrdering.cs` 通过附加属性在对话框打开时交换主按钮与取消按钮的列位置，所有 ContentDialog 自动变为「取消在左、主操作在右」，无需逐个修改（覆盖新建、重命名、删除、复制移动、文本保存、解压等 41 处弹窗）。
- 压缩包确认框恢复标准 Title 语义，排序交由全局机制处理，避免逐窗手写方向。
- 新增设计契约 `clients/windows/docs/DESIGN.md`：颜色、字体、圆角、间距、组件令牌、毛玻璃浮层、图标规范、动效与无障碍约束；后续 0.8.x 迭代以此为验收依据。
- 全局 ContentDialog 维持 18px 圆角、自绘主/次按钮与主题面板。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误。
- 启动冒烟两次（15s / 20s）：`RESULT=ALIVE`、`responding=True`、`title=True`、`GATE=PASS`。
- 产物：
  - `Fileway-windows-artifacts/20261029-ui083/dist/Fileway-0.8.3-win-x64-unsigned-setup.exe`
  - `.../Fileway-0.8.3-win-x64-unsigned.zip`
  - 校验值见同目录 `...json`。
- 对话框排序实测截图：`.../screens/ui-archive/archive-extract-dialog-110821.png`（取消在左、开始解压在右）。

## 未完成（进入 0.8.4）

- PDF 不再内置阅读：改为下载到本地后调用 Windows 默认 PDF 查看器。
- 设置页支持按文件类型配置默认打开方式（Markdown/图片/视频/文本/PDF/压缩包等），默认内置，可切换为系统应用（如 Typora 打开 Markdown）。
- 主浏览窗口工具栏与网格按 DESIGN.md 重排。
- 桌面拖放（拖入上传 / 拖到文件夹移动 / 拖出）。
- 深色/浅色/高对比度逐屏走查。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
