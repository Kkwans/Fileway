using Fileway.App.Controls.Collections;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Data;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;

namespace Fileway.App.Controls.Browser;

/// <summary>Native quick navigation; display hints never establish resource identity or type.</summary>
public sealed class SidebarCollectionsControl : UserControl
{
    private readonly StackPanel _favorites = new() { Spacing = 4 };
    private readonly StackPanel _tags = new() { Spacing = 4 };
    private readonly Dictionary<string, bool> _expandedGroups = new(StringComparer.Ordinal);
    private AccountKey? _account;
    private long _generation;
    public event EventHandler<ResourceFavorite>? FavoriteRequested;
    public event EventHandler<ResourceTag>? TagRequested;

    public SidebarCollectionsControl()
    {
        var panel = new StackPanel { Spacing = 24 };
        panel.Children.Add(Section("收藏夹", _favorites));
        panel.Children.Add(Section("标签", _tags));
        Content = panel;
        SetSnapshot(null);
    }

    public void SetSnapshot(ResourceCollectionsSnapshot? snapshot)
    {
        var generation = ++_generation;
        if (_account != snapshot?.Account) { _expandedGroups.Clear(); _account = snapshot?.Account; }
        _favorites.Children.Clear(); _tags.Children.Clear();
        if (snapshot is null) { _favorites.Children.Add(Empty("连接后显示收藏")); _tags.Children.Add(Empty("连接后显示标签")); return; }
        foreach (var favorite in snapshot.Favorites.Where(item => string.IsNullOrEmpty(item.GroupId)).OrderBy(item => item.Order)) _favorites.Children.Add(FavoriteButton(favorite));
        foreach (var group in snapshot.FavoriteGroups.OrderBy(item => item.Order))
        {
            var children = new StackPanel { Spacing = 4 };
            var favorites = snapshot.Favorites.Where(item => item.GroupId == group.Id).OrderBy(item => item.Order).ToArray();
            foreach (var favorite in favorites) children.Children.Add(FavoriteButton(favorite));
            if (favorites.Length == 0) children.Children.Add(Empty("此分组暂无收藏"));
            var expander = new Expander { Header = $"{group.Name} · {favorites.Length}", Content = children, IsExpanded = _expandedGroups.GetValueOrDefault(group.Id), HorizontalAlignment = HorizontalAlignment.Stretch, HorizontalContentAlignment = HorizontalAlignment.Stretch, Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent), BorderThickness = new Thickness(0), CornerRadius = new CornerRadius(4), Padding = new Thickness(4, 0, 4, 0) };
            AutomationProperties.SetName(expander, $"收藏分组 {group.Name}，{favorites.Length} 项");
            expander.Expanding += (_, _) => { if (generation == _generation) _expandedGroups[group.Id] = true; };
            expander.Collapsed += (_, _) => { if (generation == _generation) _expandedGroups[group.Id] = false; };
            _favorites.Children.Add(expander);
        }
        var missing = snapshot.Favorites.Where(item => !string.IsNullOrEmpty(item.GroupId) && snapshot.FavoriteGroups.All(group => group.Id != item.GroupId)).OrderBy(item => item.Order).ToArray();
        if (missing.Length > 0)
        {
            _favorites.Children.Add(Empty("分组不可用"));
            foreach (var favorite in missing) _favorites.Children.Add(FavoriteButton(favorite));
        }
        if (snapshot.Favorites.Count == 0 && snapshot.FavoriteGroups.Count == 0) _favorites.Children.Add(Empty("尚无收藏。选中文件即可添加。"));
        foreach (var tag in snapshot.Tags)
        {
            var content = new Grid { ColumnSpacing = 8 };
            content.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            content.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            content.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var dot = new Ellipse { Width = 10, Height = 10, Fill = new ColorChoice("", tag.Color).Brush, StrokeThickness = 1, IsHitTestVisible = false };
            dot.SetBinding(Shape.StrokeProperty, new Binding { Source = this, Path = new PropertyPath(nameof(Foreground)) });
            AutomationProperties.SetAccessibilityView(dot, AccessibilityView.Raw);
            content.Children.Add(dot);
            var name = new TextBlock { Text = tag.Name, TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center }; Grid.SetColumn(name, 1); content.Children.Add(name);
            var count = new TextBlock { Text = tag.Resources.Count.ToString(System.Globalization.CultureInfo.CurrentCulture), FontSize = 12, VerticalAlignment = VerticalAlignment.Center }; Grid.SetColumn(count, 2); content.Children.Add(count);
            var button = NavigationButton(content); AutomationProperties.SetName(button, $"标签 {tag.Name}，{tag.Resources.Count} 个关联");
            button.Click += (_, _) => { if (generation == _generation && _account == tag.Account) TagRequested?.Invoke(this, tag); };
            _tags.Children.Add(button);
        }
        if (snapshot.Tags.Count == 0) _tags.Children.Add(Empty("尚无标签。可直接标记选中资源。"));
    }

    private Button FavoriteButton(ResourceFavorite favorite)
    {
        var generation = _generation;
        var content = new Grid { ColumnSpacing = 8 };
        content.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); content.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var hint = FileRow.NameTypeHint(favorite.Name);
        content.Children.Add(new FontIcon { Glyph = hint.Glyph, FontSize = 16, VerticalAlignment = VerticalAlignment.Center });
        var text = new StackPanel { Spacing = 2 };
        text.Children.Add(new TextBlock { Text = favorite.Name, TextTrimming = TextTrimming.CharacterEllipsis });
        var state = favorite.Reference.Resource is null ? "历史路径身份无法确认" : hint.Kind;
        if (favorite.Reference.Resource is null)
            text.Children.Add(new TextBlock { Text = state, FontSize = 12, TextTrimming = TextTrimming.CharacterEllipsis });
        Grid.SetColumn(text, 1); content.Children.Add(text);
        var button = NavigationButton(content); button.IsEnabled = favorite.Reference.Resource is not null;
        AutomationProperties.SetName(button, $"收藏 {favorite.Name}，{state}");
        ToolTipService.SetToolTip(button, favorite.Reference.Resource is null ? "原始资源身份无法确认，请在管理中处理此历史收藏。" : favorite.Reference.DisplayPath);
        button.Click += (_, _) => { if (generation == _generation && _account == favorite.Account) FavoriteRequested?.Invoke(this, favorite); };
        return button;
    }
    private static StackPanel Section(string title, UIElement content)
    {
        var section = new StackPanel { Spacing = 4 };
        section.Children.Add(new TextBlock { Text = title, FontSize = 12, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, Margin = new Thickness(12, 0, 12, 4) });
        section.Children.Add(content);
        return section;
    }
    private static Button NavigationButton(UIElement content) => new() { Content = content, HorizontalAlignment = HorizontalAlignment.Stretch, HorizontalContentAlignment = HorizontalAlignment.Stretch, Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent), BorderThickness = new Thickness(0), CornerRadius = new CornerRadius(4), FontSize = 14, MinHeight = 36, Padding = new Thickness(12, 8, 12, 8) };
    private static TextBlock Empty(string text) => new() { Text = text, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(12, 4, 12, 4), FontSize = 12 };
}
