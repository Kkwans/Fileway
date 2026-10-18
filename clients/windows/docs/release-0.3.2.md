# 栖卷 Windows 0.3.2 热修复

修复 0.3.1 双击启动后无窗口的回归，保留其功能与视觉方向。

- 移除 Application 构造期间读取 Resources 的图标注册代码：实际异常栈为
  `Application.get_Resources → App..ctor`，返回 `E_UNEXPECTED`。
- 常驻操作图标使用编译到所属页面的 `PathIcon.Data`，动态菜单各自创建原始路径的 Geometry；
  不再把从其他 XAML 对象取得的 Geometry 动态插入资源字典，该路径实际返回 `0x800F1000`。
  同时移除启动期间的跨字典图标别名；Phosphor 原始路径及 Duotone 文件图标保持。
- 下拉样式使用控件默认主题模板叠加应用 Setter，移除对未公开导出的
  `DefaultDropDownButtonStyle` / `DefaultComboBoxStyle` 资源键的依赖；实际启动日志
  已定位 `Cannot find a Resource with the Name/Key DefaultDropDownButtonStyle`。
- 安装完成页提供“完成”和位于其右侧的“打开”按钮；打开准确的本次安装版本。
  保留实际 DPI 缩放后的按钮位置与字体，不用取消或窗口关闭动作代替打开。

使用新的版本目录安装，不覆盖或删除已有 0.3.1 文件及用户数据。
本次只做启动故障定位、交付必要构建与打包，不运行测试套件。修复后的本机程序已实际创建
标题为“栖卷”的主窗口，响应正常；完整 UI/播放体验和安装完成页交互仍由用户验收。
既有播放器退出 CFG 风险、跨重启续传和完整边下边播仍未关闭。
