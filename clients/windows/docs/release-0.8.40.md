# 栖卷 Windows 0.8.40

0.8.40 用自研模板替换系统复选框外观，连接页与筛选区的勾选控件进入同一套控件语言。

## 本版完成

- 新增全局隐式 `CheckBox` 样式：
  - 20px 圆角（6px）方框，未选中为 1px `FwStrokeStrongBrush` 描边；
  - 选中时填充 `AccentFillColorDefaultBrush`、隐藏描边并显示自绘白色勾线；三态显示短横线；
  - 悬停/按下使用 `SubtleFillColorSecondary/Tertiary` 叠加层，禁用整体 40% 透明度；
  - 保留键盘焦点视觉与 Space 切换行为，文本沿用主文字色与 14px 字号。
- 通过连接页“保持登录”实拍验证选中与取消两种状态；浅色下勾线、圆角与对齐正常。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.40.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机截图：`screens/checkbox-checked.png`、`screens/checkbox-unchecked.png`、`screens/nas-filters.png`（文件任务筛选区“只看归档”未选中态，确认全局样式无回归）、`screens/checkbox-dark-checked.png`、`screens/checkbox-dark-unchecked.png`（深色主题）。
- 产物：`.../20261030-ui0840/dist/Fileway-0.8.40-win-x64-unsigned-setup.exe`，SHA-256 `4fd5cc11…c776ccd`，与回执一致。

## 未完成

- 三态与禁用外观未单独截图（当前界面未使用三态；模板包含对应状态）。
- 高对比度主题未走查（需调整系统设置，待用户同意）。
- 批次卡仍无用真实文件夹上传产生的截图，需真人上传后查看上传页。
- 拖到文件夹移动 / 拖出到资源管理器：合成鼠标无法触发 WinUI 拖拽，需真人验收。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
