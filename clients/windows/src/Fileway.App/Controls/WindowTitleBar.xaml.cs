using Fileway.App.Branding;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Data;
using Microsoft.UI.Xaml.Media;
using Windows.Foundation;

namespace Fileway.App.Controls;

/// <summary>Fluent caption content; native window movement and Snap belong to its owner.</summary>
public sealed partial class WindowTitleBar : UserControl
{
    public static readonly DependencyProperty CaptionProperty = DependencyProperty.Register(
        nameof(Caption), typeof(string), typeof(WindowTitleBar),
        new PropertyMetadata(string.Empty, Caption_Changed));

    public WindowTitleBar()
    {
        InitializeComponent();
        Caption = AppBranding.DisplayName;
        SetButtonLabel(MinimizeButton, AppBranding.IsChinese ? "最小化" : "Minimize");
        SetButtonLabel(CloseButton, AppBranding.IsChinese ? "关闭" : "Close");
        SetMaximized(false);
    }

    public string Caption
    {
        get => (string)GetValue(CaptionProperty);
        set => SetValue(CaptionProperty, value ?? string.Empty);
    }

    public FrameworkElement DragRegion => DragSurface;
    public FrameworkElement MaximizeButtonElement => MaximizeButton;
    private IReadOnlyList<FrameworkElement> _browserMenuRegions = [];
    private FrameworkElement? _noticeRegion;
    public IReadOnlyList<FrameworkElement> InteractiveRegions { get; private set; } = [];
    public double MinimumWindowWidth { get; private set; }
    public double MinimumWindowHeight { get; private set; }

    /// <summary>Compact file caption for document and photo viewers.</summary>
    internal void SetViewerCaption()
    {
        Height = 44;
        MinimumWindowWidth = 480;
        MinimumWindowHeight = 340;
        BrandIcon.Visibility = Visibility.Collapsed;
        BrandIconColumn.Width = new GridLength(0);
        BrandSurface.ColumnSpacing = 0;
        BrandSurface.Padding = new Thickness(18, 0, 12, 0);
        CaptionText.FontSize = 13;
        foreach (var button in new[] { MinimizeButton, MaximizeButton, CloseButton })
        {
            button.Width = 36;
            button.Height = 32;
            button.MinWidth = 0;
            button.MinHeight = 0;
            button.Margin = new Thickness(0, 2, 4, 0);
            button.VerticalAlignment = VerticalAlignment.Center;
            button.CornerRadius = new CornerRadius(8);
            button.FocusVisualMargin = new Thickness(-2);
            if (button.Content is LucideIcon glyph) glyph.Width = glyph.Height = 16;
        }
        CloseButton.Margin = new Thickness(0, 2, 8, 0);
    }

    internal void SetImageDetails(string details)
    {
        CaptionDetails.Text = details;
        CaptionDetails.Visibility = string.IsNullOrEmpty(details) ? Visibility.Collapsed : Visibility.Visible;
    }

    /// <summary>Browser application commands occupy the caption; folder navigation stays in its page.</summary>
    public void SetBrowserMenu(UIElement content, IReadOnlyList<FrameworkElement> interactiveRegions)
    {
        Height = MinimizeButton.Height = MaximizeButton.Height = CloseButton.Height = 40;
        MinimumWindowWidth = 800; MinimumWindowHeight = 600;
        BrandColumn.Width = GridLength.Auto;
        BrandSurface.Visibility = Visibility.Collapsed;
        ToolTipService.SetToolTip(DragSurface, null);
        NavigationPresenter.Content = content;
        NavigationPresenter.Visibility = Visibility.Visible;
        _browserMenuRegions = InteractiveRegions = interactiveRegions;
    }

    internal void SetNoticeRegion(FrameworkElement? notice)
    {
        if (ReferenceEquals(_noticeRegion, notice)) return;
        _noticeRegion = notice;
        InteractiveRegions = notice is null ? _browserMenuRegions : [.. _browserMenuRegions, notice];
    }

    internal Rect GetNoticeSlot(FrameworkElement layer)
    {
        const double margin = 8;
        if (!IsLoaded || Visibility != Visibility.Visible)
            return new Rect(margin, margin, Math.Max(0, layer.ActualWidth - 2 * margin), 32);

        var left = _browserMenuRegions.Where(element => element.IsLoaded && element.Visibility == Visibility.Visible)
            .Select(element => Bounds(element).Right).DefaultIfEmpty(0).Max() + margin;
        var right = Bounds(MinimizeButton).Left - margin;
        return new Rect(left, 0, Math.Max(0, right - left), ActualHeight);

        Rect Bounds(FrameworkElement element) => element.TransformToVisual(layer).TransformBounds(
            new Rect(0, 0, element.ActualWidth, element.ActualHeight));
    }

    public event EventHandler? MinimizeRequested;
    public event EventHandler? MaximizeRequested;
    public event EventHandler? CloseRequested;

    internal void RequestClose() => CloseRequested?.Invoke(this, EventArgs.Empty);

    public void SetMaximized(bool maximized)
    {
        MaximizeIcon.IconName = maximized ? "copy" : "square";
        SetButtonLabel(MaximizeButton, maximized
            ? AppBranding.IsChinese ? "还原" : "Restore"
            : AppBranding.IsChinese ? "最大化" : "Maximize");
    }

    private static void Caption_Changed(DependencyObject sender, DependencyPropertyChangedEventArgs args)
    {
        var titleBar = (WindowTitleBar)sender;
        if (titleBar.CaptionText is null) return;
        var caption = args.NewValue as string ?? string.Empty;
        titleBar.CaptionText.Text = caption;
        ToolTipService.SetToolTip(titleBar.DragSurface, caption);
        AutomationProperties.SetName(titleBar.CaptionText, caption);
    }

    private static void SetButtonLabel(Button button, string label)
    {
        AutomationProperties.SetName(button, label);
        ToolTipService.SetToolTip(button, label);
    }

    private void CaptionButton_Loaded(object sender, RoutedEventArgs args)
    {
        var button = (Button)sender;
        if (button.Content is not Control icon) return;
        button.ApplyTemplate();
        if (FindCaptionPresenter(button, icon) is not { } presenter) return;
        // SDK visual states change this template presenter's Foreground, not
        // Button.Foreground. The XAML button binding remains a safe fallback.
        icon.SetBinding(Control.ForegroundProperty, new Binding
        {
            Source = presenter,
            Path = new PropertyPath(nameof(ContentPresenter.Foreground)),
            Mode = BindingMode.OneWay,
        });
    }

    private static ContentPresenter? FindCaptionPresenter(DependencyObject parent, Control icon)
    {
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(parent); index++)
        {
            var child = VisualTreeHelper.GetChild(parent, index);
            if (child is ContentPresenter presenter && ReferenceEquals(presenter.Content, icon)) return presenter;
            if (FindCaptionPresenter(child, icon) is { } nested) return nested;
        }
        return null;
    }

    private void Minimize_Click(object sender, RoutedEventArgs args) => MinimizeRequested?.Invoke(this, EventArgs.Empty);
    private void Maximize_Click(object sender, RoutedEventArgs args) => MaximizeRequested?.Invoke(this, EventArgs.Empty);
    private void Close_Click(object sender, RoutedEventArgs args) => RequestClose();
}
