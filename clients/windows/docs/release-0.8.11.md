# 栖卷 Windows 0.8.11

0.8.11 把网格的容器状态复用到详细/紧凑列表，列表选中态与网格统一。

## 本版完成

- 新增 `FwListViewItemStyle`：10px 圆角、1px 描边、悬停/按下/选中/焦点与禁用状态，与网格卡片共用同一套视觉。
- 详细列表选中行显示半透明强调底 + 左侧 3px 强调条，文字保持可读；行高 52。
- 实测截图（详细列表，选中 archive.zip）：`screens/ui-browser/browser-restyle-selected-124618.png`。
- 网格与列表的布局切换、选中、排序等既有行为未改变。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误；两次冒烟 `GATE=PASS`。
- 产物：`.../20261029-ui0811/dist/Fileway-0.8.11-win-x64-unsigned-setup.exe`，校验值见同目录 json。

## 未完成

- PDF 沉浸式外壳的原生堆破坏未修复；内置 PDF 使用稳定界面。
- 拖到文件夹移动需真实鼠标验收；拖出到资源管理器未开始。
- 紧凑网格/无界网格与深色主题下的列表选中态尚未逐一截图复核。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
