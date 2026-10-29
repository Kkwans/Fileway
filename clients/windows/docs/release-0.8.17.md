# 栖卷 Windows 0.8.17

0.8.17 收尾侧栏图标：最后两个 Segoe 字形图标换成统一的 Lucide 线条图标。

## 本版完成

- 回收站：`FontIcon E74D` → Lucide `trash`。
- 切换连接：`FontIcon E968` → Lucide `floppy-disk`（表示远端存储空间）。
- 侧栏六个入口现在全部使用同一套 Lucide 线条图标、16px、单色随前景着色。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误。
- 两次冒烟 `GATE=PASS`（pwsh 7）。
- 实机截图：`screens/ui-verify/main-after-login-162735.png`。
- 产物：`.../20261029-ui0816/dist/Fileway-0.8.17-win-x64-unsigned-setup.exe`，校验值见同目录 json。

## 未完成

- 文件夹上传批次：端到端需真人点击验收；传输中心仍按文件展示。
- 拖到文件夹移动 / 拖出到资源管理器：合成鼠标无法触发 WinUI 拖拽，需真人验收。
- PDF 沉浸式外壳的原生堆破坏未修复；真实内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
