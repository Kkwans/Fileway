using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;

namespace Fileway.App.Controls;

/// <summary>
/// Shared immersive viewer shell: a full-bleed content host with an auto-hiding acrylic
/// caption, a floating pill toolbar and centered loading/error surfaces. Content owns the
/// canvas; chrome never reflows it.
/// </summary>
internal sealed class ViewerChrome : Grid
{
    private readonly Grid _content = new();
    private readonly Border _topBar;
    private readonly Border _bottomPill;
    private readonly Grid _bottomHost;
    private readonly StackPanel _topActions = new() { Orientation = Orientation.Horizontal, Spacing = 2, VerticalAlignment = VerticalAlignment.Center };
    private readonly StackPanel _bottomActions = new() { Orientation = Orientation.Horizontal, Spacing = 2, VerticalAlignment = VerticalAlignment.Center };
    private readonly TextBlock _title = new() { Style = (Style)Application.Current.Resources["FwScrimTitleTextStyle"] };
    private readonly TextBlock _subtitle = new() { Style = (Style)Application.Current.Resources["FwScrimMutedTextStyle"] };
    private readonly Button _previous;
    private readonly Button _next;
    private readonly ProgressRing _progress = new() { IsActive = true, Width = 38, Height = 38 };
    private readonly TextBlock _progressText = new() { Style = (Style)Application.Current.Resources["FwScrimMutedTextStyle"], TextAlignment = TextAlignment.Center };
    private readonly StackPanel _loadingHost;
    private readonly Border _errorCard;
    private readonly TextBlock _errorTitle = new() { FontSize = 16, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap, Foreground = (Brush)Application.Current.Resources["FwTextPrimaryBrush"] };
    private readonly TextBlock _errorMessage;
    private readonly Button _retry;
    private readonly Border _toast;
    private readonly TextBlock _toastText = new() { Style = (Style)Application.Current.Resources["FwScrimBodyTextStyle"] };
    private readonly DispatcherQueueTimer _idle;
    private bool _chromeVisible = true;
    private bool _pointerOverChrome;

    internal Grid ContentHost => _content;
    internal StackPanel TopActions => _topActions;
    internal StackPanel BottomActions => _bottomActions;
    internal Border BottomPill => _bottomPill;

    internal bool AutoHideChrome { get; set; } = true;

    internal ViewerChrome()
    {
        Background = (Brush)Application.Current.Resources["FwCanvasBrush"];
        _content.Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent);
        Children.Add(_content);

        var titleStack = new StackPanel { Spacing = 1, VerticalAlignment = VerticalAlignment.Center };
        titleStack.Children.Add(_title);
        titleStack.Children.Add(_subtitle);
        var topGrid = new Grid { Padding = new Thickness(18, 0, 10, 0), Height = 60 };
        topGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        topGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        topGrid.Children.Add(titleStack);
        Grid.SetColumn(_topActions, 1);
        topGrid.Children.Add(_topActions);
        _topBar = new Border { VerticalAlignment = VerticalAlignment.Top, Style = (Style)Application.Current.Resources["FwChromeBarStyle"], Child = topGrid };
        _topBar.PointerEntered += (_, _) => _pointerOverChrome = true;
        _topBar.PointerExited += (_, _) => _pointerOverChrome = false;
        Children.Add(_topBar);

        _previous = NavButton("arrow-left", "上一张", false);
        _next = NavButton("arrow-left", "下一张", true);
        _previous.HorizontalAlignment = HorizontalAlignment.Left;
        _next.HorizontalAlignment = HorizontalAlignment.Right;
        _previous.Margin = new Thickness(14, 0, 0, 0);
        _next.Margin = new Thickness(0, 0, 14, 0);
        _previous.Visibility = Visibility.Collapsed;
        _next.Visibility = Visibility.Collapsed;
        _previous.Click += NavClick;
        _next.Click += NavClick;
        Children.Add(_previous);
        Children.Add(_next);

        _bottomPill = new Border
        {
            Style = (Style)Application.Current.Resources["FwPillStyle"],
            HorizontalAlignment = HorizontalAlignment.Center,
            Child = _bottomActions,
        };
        _bottomHost = new Grid { VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, 0, 22) };
        _bottomHost.Children.Add(_bottomPill);
        _bottomHost.PointerEntered += (_, _) => _pointerOverChrome = true;
        _bottomHost.PointerExited += (_, _) => _pointerOverChrome = false;
        Children.Add(_bottomHost);

        _toast = new Border
        {
            Style = (Style)Application.Current.Resources["FwToastStyle"],
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(0, 0, 0, 78),
            VerticalAlignment = VerticalAlignment.Bottom,
            Visibility = Visibility.Collapsed,
            Child = _toastText,
        };
        Children.Add(_toast);

        _loadingHost = new StackPanel { Spacing = 12, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Visibility = Visibility.Collapsed };
        _loadingHost.Children.Add(_progress);
        _loadingHost.Children.Add(_progressText);
        Children.Add(_loadingHost);

        _errorMessage = new TextBlock { FontSize = 13, TextWrapping = TextWrapping.Wrap, Foreground = (Brush)Application.Current.Resources["FwTextSecondaryBrush"] };
        var errorBody = new StackPanel { Spacing = 10 };
        errorBody.Children.Add(_errorTitle);
        errorBody.Children.Add(_errorMessage);
        _retry = new Button { Content = "重试", Style = (Style)Application.Current.Resources["FwSecondaryButtonStyle"], HorizontalAlignment = HorizontalAlignment.Left };
        _retry.Click += (_, _) => RetryRequested?.Invoke(this, EventArgs.Empty);
        errorBody.Children.Add(_retry);
        _errorCard = new Border
        {
            Style = (Style)Application.Current.Resources["FwErrorCardStyle"],
            MaxWidth = 420,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
            Visibility = Visibility.Collapsed,
            Child = errorBody,
        };
        Children.Add(_errorCard);

        _idle = DispatcherQueue.CreateTimer();
        _idle.IsRepeating = false;
        _idle.Interval = TimeSpan.FromMilliseconds(2600);
        _idle.Tick += (_, _) => { if (AutoHideChrome && !_pointerOverChrome) SetChromeVisible(false, animate: true); };
        PointerMoved += (_, _) => { if (AutoHideChrome) { if (!_chromeVisible) SetChromeVisible(true, animate: true); RestartIdle(); } };
    }

    internal event EventHandler? RetryRequested;

    internal void SetTitle(string title, string subtitle = "")
    {
        _title.Text = title;
        ToolTipService.SetToolTip(_title, title);
        SetSubtitle(subtitle);
    }

    internal void SetSubtitle(string subtitle)
    {
        _subtitle.Text = subtitle;
        _subtitle.Visibility = string.IsNullOrEmpty(subtitle) ? Visibility.Collapsed : Visibility.Visible;
    }

    internal void SetNavigation(Action? previous, Action? next)
    {
        _previous.Tag = previous;
        _next.Tag = next;
        _previous.Visibility = previous is null ? Visibility.Collapsed : Visibility.Visible;
        _next.Visibility = next is null ? Visibility.Collapsed : Visibility.Visible;
    }

    private void NavClick(object sender, RoutedEventArgs args)
    {
        if (sender is FrameworkElement { Tag: Action action }) action();
    }

    internal void SetChromeVisible(bool visible, bool animate)
    {
        if (_chromeVisible == visible) { if (visible) RestartIdle(); return; }
        _chromeVisible = visible;
        Fade(_topBar, visible, animate);
        Fade(_bottomHost, visible, animate);
        if (_previous.Tag is not null) Fade(_previous, visible, animate);
        if (_next.Tag is not null) Fade(_next, visible, animate);
        if (visible) RestartIdle(); else _idle.Stop();
    }

    internal void ToggleChrome()
    {
        SetChromeVisible(!_chromeVisible, animate: true);
        if (_chromeVisible) RestartIdle();
    }

    internal void RestartIdle()
    {
        if (!AutoHideChrome) return;
        _idle.Stop();
        _idle.Start();
    }

    internal void SetLoading(bool loading, string message = "")
    {
        _loadingHost.Visibility = loading ? Visibility.Visible : Visibility.Collapsed;
        _progress.IsActive = loading;
        _progressText.Text = message;
        _progressText.Visibility = string.IsNullOrEmpty(message) ? Visibility.Collapsed : Visibility.Visible;
        if (loading)
        {
            _errorCard.Visibility = Visibility.Collapsed;
            Canvas.SetZIndex(_loadingHost, 20);
        }
    }

    internal void ShowError(string title, string message)
    {
        _errorTitle.Text = title;
        _errorMessage.Text = message;
        _errorCard.Visibility = Visibility.Visible;
        _loadingHost.Visibility = Visibility.Collapsed;
        _progress.IsActive = false;
        Canvas.SetZIndex(_errorCard, 30);
        SetChromeVisible(true, animate: false);
    }

    internal void ClearError() => _errorCard.Visibility = Visibility.Collapsed;

    internal void ShowToast(string message, int seconds = 3)
    {
        _toastText.Text = message;
        _toast.Visibility = Visibility.Visible;
        Canvas.SetZIndex(_toast, 25);
        var timer = DispatcherQueue.CreateTimer();
        timer.IsRepeating = false;
        timer.Interval = TimeSpan.FromSeconds(seconds);
        timer.Tick += (_, _) => { timer.Stop(); _toast.Visibility = Visibility.Collapsed; };
        timer.Start();
    }

    internal static Button IconButton(string icon, string tooltip, Action click, bool scrim = true, double size = 36)
    {
        var button = new Button
        {
            Style = (Style)Application.Current.Resources[scrim ? "FwScrimIconButtonStyle" : "FwIconButtonStyle"],
            Width = size,
            Height = size,
            Content = new LucideIcon { IconName = icon, Width = 18, Height = 18 },
        };
        ToolTipService.SetToolTip(button, tooltip);
        AutomationProperties.SetName(button, tooltip);
        button.Click += (_, _) => click();
        return button;
    }

    internal static Button LabelButton(string text, string tooltip, Action click)
    {
        var button = new Button
        {
            Style = (Style)Application.Current.Resources["FwTextIconButtonStyle"],
            Foreground = (Brush)Application.Current.Resources["FwScrimTextBrush"],
            Content = new TextBlock { Text = text, FontSize = 13, Foreground = (Brush)Application.Current.Resources["FwScrimTextBrush"] },
        };
        ToolTipService.SetToolTip(button, tooltip);
        AutomationProperties.SetName(button, tooltip);
        button.Click += (_, _) => click();
        return button;
    }

    private static Button NavButton(string icon, string tooltip, bool mirror)
    {
        var glyph = new LucideIcon { IconName = icon, Width = 20, Height = 20 };
        if (mirror) glyph.RenderTransform = new RotateTransform { Angle = 180, CenterX = 10, CenterY = 10 };
        var button = new Button
        {
            Style = (Style)Application.Current.Resources["FwNavButtonStyle"],
            VerticalAlignment = VerticalAlignment.Center,
            Content = glyph,
        };
        ToolTipService.SetToolTip(button, tooltip);
        AutomationProperties.SetName(button, tooltip);
        return button;
    }

    private static void Fade(UIElement element, bool visible, bool animate)
    {
        if (visible) element.Visibility = Visibility.Visible;
        var animation = new DoubleAnimation
        {
            To = visible ? 1 : 0,
            Duration = new Duration(TimeSpan.FromMilliseconds(animate ? 170 : 1)),
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut },
        };
        Storyboard.SetTarget(animation, element);
        Storyboard.SetTargetProperty(animation, "Opacity");
        var storyboard = new Storyboard();
        storyboard.Children.Add(animation);
        storyboard.Completed += (_, _) => { if (!visible) element.Visibility = Visibility.Collapsed; };
        storyboard.Begin();
    }
}
