# 栖卷 Windows 0.8.79

0.8.79 用自研 `FwDisclosure` 替换任务详情与回收站待核实面板里的 WinUI Expander，补齐最后一块“系统矩形焦点框”的键盘焦点视觉。

## 本版完成

- 新增 `Controls/FwDisclosure`：`LayoutChoiceStyle` 开关头（自研双色焦点环、选中底色）+ 旋转箭头 + 内容区；`Header`/`SectionContent` 依赖属性。
- 任务详情的“任务编号”“记录信息”和回收站“需要核实的操作”全部改用 FwDisclosure；展开/收起、内容显示正常。
- 开关头绑定 `AutomationProperties.Name` 为标题文本，修正此前 Expander 头部读屏名称依赖内部模板的问题。
- 1600×1000 实拍：任务详情侧栏“任务编号”收起/展开（蓝色选中底 + 黑色圆角焦点环）正常；1200×900 下任务详情仍为上下堆叠布局，详情位于列表下方需滚动查看（既有布局，本版未改）。

## 构建与冒烟

- Release x64 + Publish：0 警告、0 错误（FileVersion 0.8.79.0）。
- 两次冒烟 `GATE=PASS`（pwsh 7，15 秒与 20 秒，独立数据目录，无 startup.log）。
- 实机截图：`screens/wide3/disclosure-scrolled.png`、`disclosure-focus.png`、`disclosure-expanded.png`。
- 产物：`.../20261031-ui0879/dist/Fileway-0.8.79-win-x64-unsigned-setup.exe`，SHA-256 `f81c460ff2188e931531f06083ce018b59779899ab482dcee407fe3356897d5d`，与回执一致。

## 已知问题

- FwDisclosure 展开后的长内容仍由详情面板自身滚动；1200 窄布局详情在列表下方，需要向下滚动。
- 菜单焦点仍为系统矩形（颜色已用应用双色），要圆角需整体替换 MenuFlyoutItem/SubItem 模板。
- 高对比度主题未系统走查（需调整系统设置，待用户同意）。
- 文件批次的批次卡实拍仍需真人选择文件夹；拖入/拖到文件夹/拖出仍需真人鼠标验证。
- PDF 沉浸式外壳的原生堆破坏未修复；真正内容级模糊需 Composition/Win2D。

本包为本地未签名候选，未合并 main、未打 tag、未发布公开 Release。
