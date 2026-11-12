using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Data;

namespace Fileway.App.Controls;

internal static class SurfaceGeometry
{
    internal const double RowPadding = 10;
    internal const double RowIconWidth = 28;
    internal const double RowGap = 12;
    internal const double EmptyIconSize = 72;
    internal static Style Style(string name) => (Style)Application.Current.Resources[name];

    internal static DependencyProperty Text<T>(string name, Action<T, string> set) where T : DependencyObject =>
        DependencyProperty.Register(name, typeof(string), typeof(T),
            new PropertyMetadata(string.Empty, (owner, args) => set((T)owner, (string?)args.NewValue ?? "")));

    internal static void SetText(TextBlock block, string text)
    {
        block.Text = text;
        block.Visibility = text.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
    }
}

public sealed class FwPageHeader : UserControl
{
    private readonly TextBlock _title = new() { Style = SurfaceGeometry.Style("FwPageTitleStyle"), TextTrimming = TextTrimming.CharacterEllipsis };
    private readonly TextBlock _subtitle = new()
    {
        Style = SurfaceGeometry.Style("FwCaptionTextStyle"), TextWrapping = TextWrapping.Wrap,
        MaxLines = 2, TextTrimming = TextTrimming.CharacterEllipsis, Visibility = Visibility.Collapsed,
    };
    private readonly ContentPresenter _actions = new() { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Right };

    public static readonly DependencyProperty TitleProperty = SurfaceGeometry.Text<FwPageHeader>(nameof(Title), static (header, text) => SurfaceGeometry.SetText(header._title, text));
    public static readonly DependencyProperty SubtitleProperty = SurfaceGeometry.Text<FwPageHeader>(nameof(Subtitle), static (header, text) => SurfaceGeometry.SetText(header._subtitle, text));
    public static readonly DependencyProperty ActionsProperty = DependencyProperty.Register(nameof(Actions), typeof(UIElement), typeof(FwPageHeader),
        new PropertyMetadata(null, static (owner, args) => ((FwPageHeader)owner)._actions.Content = args.NewValue));

    public string Title { get => (string)GetValue(TitleProperty); set => SetValue(TitleProperty, value); }
    public string Subtitle { get => (string)GetValue(SubtitleProperty); set => SetValue(SubtitleProperty, value); }
    public UIElement? Actions { get => (UIElement?)GetValue(ActionsProperty); set => SetValue(ActionsProperty, value); }

    public FwPageHeader()
    {
        var labels = new StackPanel { Spacing = 4, VerticalAlignment = VerticalAlignment.Center };
        labels.Children.Add(_title);
        labels.Children.Add(_subtitle);
        var root = new Grid { ColumnSpacing = 12, MinHeight = 48 };
        root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        root.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        root.Children.Add(labels);
        Grid.SetColumn(_actions, 1);
        root.Children.Add(_actions);
        AutomationProperties.SetAutomationId(_actions, "PageHeaderActions");
        Content = root;
    }
}

public sealed class FwEmptyState : UserControl
{
    private readonly LucideIcon _icon = new() { Width = 32, Height = 32, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
    private readonly TextBlock _title = new() { FontSize = 20, FontWeight = FontWeights.SemiBold, TextAlignment = TextAlignment.Center, TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _description = new()
    {
        Style = SurfaceGeometry.Style("FwCaptionTextStyle"), TextAlignment = TextAlignment.Center, TextWrapping = TextWrapping.Wrap,
        FontSize = 13, LineHeight = 20, Visibility = Visibility.Collapsed,
    };
    private readonly ContentPresenter _action = new() { HorizontalAlignment = HorizontalAlignment.Center, Visibility = Visibility.Collapsed };

    public static readonly DependencyProperty TitleProperty = SurfaceGeometry.Text<FwEmptyState>(nameof(Title), static (state, text) => SurfaceGeometry.SetText(state._title, text));
    public static readonly DependencyProperty DescriptionProperty = SurfaceGeometry.Text<FwEmptyState>(nameof(Description), static (state, text) => SurfaceGeometry.SetText(state._description, text));
    public static readonly DependencyProperty IconNameProperty = DependencyProperty.Register(nameof(IconName), typeof(string), typeof(FwEmptyState),
        new PropertyMetadata("folder", static (owner, args) => ((FwEmptyState)owner)._icon.IconName = (string)args.NewValue));
    public static readonly DependencyProperty ActionProperty = DependencyProperty.Register(nameof(Action), typeof(UIElement), typeof(FwEmptyState),
        new PropertyMetadata(null, static (owner, args) =>
        {
            var host = ((FwEmptyState)owner)._action;
            host.Content = args.NewValue;
            if (args.NewValue is UIElement child)
                host.SetBinding(VisibilityProperty, new Binding { Source = child, Path = new PropertyPath(nameof(Visibility)) });
            else
                host.Visibility = Visibility.Collapsed;
        }));

    public string Title { get => (string)GetValue(TitleProperty); set => SetValue(TitleProperty, value); }
    public string Description { get => (string)GetValue(DescriptionProperty); set => SetValue(DescriptionProperty, value); }
    public string IconName { get => (string)GetValue(IconNameProperty); set => SetValue(IconNameProperty, value); }
    public UIElement? Action { get => (UIElement?)GetValue(ActionProperty); set => SetValue(ActionProperty, value); }

    public FwEmptyState()
    {
        MaxWidth = 400;
        Margin = new Thickness(12);
        HorizontalAlignment = HorizontalAlignment.Center;
        VerticalAlignment = VerticalAlignment.Center;
        _icon.IconName = "folder";
        _icon.SetBinding(ForegroundProperty, new Binding { Source = _description, Path = new PropertyPath(nameof(TextBlock.Foreground)) });
        var tile = new EmptyStateTile
        {
            Width = SurfaceGeometry.EmptyIconSize, Height = SurfaceGeometry.EmptyIconSize, HorizontalAlignment = HorizontalAlignment.Center,
            Content = new Border { Style = SurfaceGeometry.Style("FwEmptyIconTileStyle"), Child = _icon },
        };
        AutomationProperties.SetAutomationId(tile, "EmptyStateIconTile");
        AutomationProperties.SetAccessibilityView(tile, AccessibilityView.Raw);
        AutomationProperties.SetAccessibilityView(_icon, AccessibilityView.Raw);
        var copy = new StackPanel { Spacing = 4 };
        copy.Children.Add(_title);
        copy.Children.Add(_description);
        var content = new StackPanel { Spacing = 12 };
        content.Children.Add(tile);
        content.Children.Add(copy);
        content.Children.Add(_action);
        Content = content;
    }

    private sealed class EmptyStateTile : UserControl
    {
        protected override AutomationPeer OnCreateAutomationPeer() => new FrameworkElementAutomationPeer(this);
    }
}

public sealed class FwResourceRow : UserControl
{
    private readonly LucideIcon _icon = new() { Width = 22, Height = 22, VerticalAlignment = VerticalAlignment.Center };
    private readonly TextBlock _title = new() { FontSize = 14, FontWeight = FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis };
    private readonly TextBlock _detail = new()
    {
        Style = SurfaceGeometry.Style("FwCaptionTextStyle"), TextWrapping = TextWrapping.NoWrap,
        TextTrimming = TextTrimming.CharacterEllipsis, Visibility = Visibility.Collapsed,
    };
    private readonly TextBlock _hint = new() { Style = SurfaceGeometry.Style("FwCaptionTextStyle"), TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
    private readonly TextBlock _summary = new()
    {
        Style = SurfaceGeometry.Style("FwCaptionTextStyle"), MaxWidth = 220, VerticalAlignment = VerticalAlignment.Center,
        TextAlignment = TextAlignment.Right, TextWrapping = TextWrapping.NoWrap,
        TextTrimming = TextTrimming.CharacterEllipsis, Visibility = Visibility.Collapsed,
    };

    public static readonly DependencyProperty TitleProperty = SurfaceGeometry.Text<FwResourceRow>(nameof(Title), static (row, text) => SetRowText(row._title, text));
    public static readonly DependencyProperty DetailProperty = SurfaceGeometry.Text<FwResourceRow>(nameof(Detail), static (row, text) =>
    {
        SurfaceGeometry.SetText(row._detail, text);
        row.UpdateDetailToolTip();
    });
    public static readonly DependencyProperty DetailToolTipProperty = SurfaceGeometry.Text<FwResourceRow>(nameof(DetailToolTip), static (row, _) => row.UpdateDetailToolTip());
    public static readonly DependencyProperty HintProperty = SurfaceGeometry.Text<FwResourceRow>(nameof(Hint), static (row, text) => SetRowText(row._hint, text));
    public static readonly DependencyProperty SummaryProperty = SurfaceGeometry.Text<FwResourceRow>(nameof(Summary), static (row, text) => SetRowText(row._summary, text));
    public static readonly DependencyProperty IconNameProperty = DependencyProperty.Register(nameof(IconName), typeof(string), typeof(FwResourceRow),
        new PropertyMetadata("file", static (owner, args) => ((FwResourceRow)owner)._icon.IconName = (string)args.NewValue));

    public string Title { get => (string)GetValue(TitleProperty); set => SetValue(TitleProperty, value); }
    public string Detail { get => (string)GetValue(DetailProperty); set => SetValue(DetailProperty, value); }
    public string DetailToolTip { get => (string)GetValue(DetailToolTipProperty); set => SetValue(DetailToolTipProperty, value); }
    public string Hint { get => (string)GetValue(HintProperty); set => SetValue(HintProperty, value); }
    public string Summary { get => (string)GetValue(SummaryProperty); set => SetValue(SummaryProperty, value); }
    public string IconName { get => (string)GetValue(IconNameProperty); set => SetValue(IconNameProperty, value); }

    public FwResourceRow()
    {
        _icon.SetBinding(ForegroundProperty, new Binding { Source = _detail, Path = new PropertyPath(nameof(TextBlock.Foreground)) });
        var labels = new StackPanel { Spacing = 3, VerticalAlignment = VerticalAlignment.Center };
        labels.Children.Add(_title);
        labels.Children.Add(_detail);
        labels.Children.Add(_hint);
        var root = new Grid { Padding = new Thickness(SurfaceGeometry.RowPadding, 8, SurfaceGeometry.RowPadding, 8), ColumnSpacing = SurfaceGeometry.RowGap };
        root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(SurfaceGeometry.RowIconWidth) });
        root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        root.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        root.Children.Add(_icon);
        Grid.SetColumn(labels, 1);
        root.Children.Add(labels);
        Grid.SetColumn(_summary, 2);
        root.Children.Add(_summary);
        Content = root;
    }

    private void UpdateDetailToolTip()
    {
        string value = DetailToolTip;
        if (string.IsNullOrEmpty(value)) value = _detail.Text;
        ToolTipService.SetToolTip(_detail, value.Length == 0 ? null : value);
    }

    private static void SetRowText(TextBlock text, string value)
    {
        SurfaceGeometry.SetText(text, value);
        ToolTipService.SetToolTip(text, value.Length == 0 ? null : value);
    }
}
