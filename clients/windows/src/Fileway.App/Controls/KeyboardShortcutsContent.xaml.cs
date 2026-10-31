using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App.Controls;

public sealed partial class KeyboardShortcutsContent : UserControl
{
    internal void SetMaxContentHeight(double value) => BodyScroll.MaxHeight = value;

    public KeyboardShortcutsContent()
    {
        InitializeComponent();
        AddGroup("浏览与导航",
            ("Alt + Left", "返回上一位置"),
            ("Alt + Up", "打开上一级目录"),
            ("Ctrl + L", "编辑原始路径"),
            ("F5", "刷新当前视图"),
            ("Ctrl + F", "搜索当前目录与子目录"),
            ("Ctrl + B", "展开或折叠导航侧栏"),
            ("Escape", "取消路径编辑，或关闭详情/临时侧栏"));
        AddGroup("文件与选择",
            ("Enter", "打开选中的文件或文件夹"),
            ("Ctrl + A", "全选当前文件"),
            ("F2", "重命名选中项目"),
            ("Delete", "将选中项目移入回收站"),
            ("Ctrl + C / X / V", "复制/剪切/粘贴服务器文件；文本输入保留系统行为"));
    }

    private void AddGroup(string title, params (string Key, string Action)[] items)
    {
        var group = new StackPanel { Spacing = 6 };
        group.Children.Add(new TextBlock
        {
            Text = title,
            FontSize = 13,
            FontWeight = FontWeights.SemiBold,
        });
        foreach (var (key, action) in items)
        {
            var row = new Grid { ColumnSpacing = 16 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(180) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var keycap = new Border
            {
                Style = (Style)Resources["FwKeycapStyle"],
                Child = new TextBlock
                {
                    Text = key,
                    FontSize = 12,
                    FontWeight = FontWeights.SemiBold,
                },
            };
            var description = new TextBlock
            {
                Text = action,
                FontSize = 13,
                TextWrapping = TextWrapping.Wrap,
                VerticalAlignment = VerticalAlignment.Center,
            };
            Grid.SetColumn(description, 1);
            row.Children.Add(keycap);
            row.Children.Add(description);
            group.Children.Add(row);
        }
        Groups.Children.Add(group);
    }
}
