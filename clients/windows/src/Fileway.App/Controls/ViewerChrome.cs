using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Fileway.App.Branding;

namespace Fileway.App.Controls;

/// <summary>Image viewer canvas with separate space for navigation and zoom controls.</summary>
internal sealed class ViewerChrome : Grid
{
    private readonly Grid _content = new() { Margin = new Thickness(8) };
    private readonly ColumnDefinition _previousColumn = new() { Width = GridLength.Auto };
    private readonly ColumnDefinition _nextColumn = new() { Width = GridLength.Auto };
    private readonly StackPanel _zoomActions = new()
    {
        Orientation = Orientation.Horizontal, Spacing = 4, VerticalAlignment = VerticalAlignment.Center,
    };
    private readonly TextBlock _metadata = new()
    {
        Style = SurfaceGeometry.Style("FwCaptionTextStyle"), VerticalAlignment = VerticalAlignment.Center,
        TextTrimming = TextTrimming.CharacterEllipsis, TextWrapping = TextWrapping.NoWrap,
    };
    private readonly Button _previous = NavigationButton("上一张", false);
    private readonly Button _next = NavigationButton("下一张", true);
    private readonly LucideIcon _fullscreenIcon = new() { Width = 18, Height = 18 };
    private readonly Button _fullscreen = new()
    {
        Style = SurfaceGeometry.Style("FwIconButtonStyle"), Width = 36, Height = 36,
        HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Center,
    };
    private readonly InfoBar _fullscreenNotice = new()
    {
        Severity = InfoBarSeverity.Warning, HorizontalAlignment = HorizontalAlignment.Center,
        VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(12),
    };
    private readonly ProgressRing _progress = new() { Width = 32, Height = 32 };
    private readonly TextBlock _progressText = new()
    {
        Style = SurfaceGeometry.Style("FwCaptionTextStyle"), TextAlignment = TextAlignment.Center,
        TextWrapping = TextWrapping.Wrap,
    };
    private readonly StackPanel _loading;
    private readonly Border _error;
    private readonly TextBlock _errorTitle = new()
    {
        Style = SurfaceGeometry.Style("FwBodyTextStyle"), TextAlignment = TextAlignment.Center,
    };
    private readonly TextBlock _errorMessage = new()
    {
        Style = SurfaceGeometry.Style("FwCaptionTextStyle"), TextWrapping = TextWrapping.Wrap,
        TextAlignment = TextAlignment.Center,
    };

    internal Grid ContentHost => _content;
    internal StackPanel ZoomActions => _zoomActions;
    internal event EventHandler? RetryRequested;
    internal event EventHandler? FullscreenRequested;

    internal ViewerChrome()
    {
        Style = SurfaceGeometry.Style("FwViewerChromeStyle");
        RowDefinitions.Add(new RowDefinition());
        RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

        var stage = new Grid();
        stage.ColumnDefinitions.Add(_previousColumn);
        stage.ColumnDefinitions.Add(new ColumnDefinition());
        stage.ColumnDefinitions.Add(_nextColumn);
        SetColumn(_content, 1);
        SetColumn(_next, 2);
        stage.Children.Add(_content);
        stage.Children.Add(_previous);
        stage.Children.Add(_next);
        Children.Add(stage);
        AutomationProperties.SetAutomationId(_previous, "ImagePrevious");
        AutomationProperties.SetAutomationId(_next, "ImageNext");
        FloatingNotice.Attach(_fullscreenNotice, automatic: false);
        SetColumn(_fullscreenNotice, 1);
        Canvas.SetZIndex(_fullscreenNotice, 2);
        stage.Children.Add(_fullscreenNotice);

        var footer = new Grid { MinHeight = 56, Padding = new Thickness(16, 4, 16, 4), ColumnSpacing = 12 };
        footer.ColumnDefinitions.Add(new ColumnDefinition());
        footer.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        footer.ColumnDefinitions.Add(new ColumnDefinition());
        var controls = new Border
        {
            Style = SurfaceGeometry.Style("FwPillStyle"), VerticalAlignment = VerticalAlignment.Center,
            Padding = new Thickness(4), Child = _zoomActions,
        };
        SurfaceStyles.SetUsePopupMaterial(controls, true);
        SetColumn(controls, 1);
        SetColumn(_fullscreen, 2);
        _fullscreen.Content = _fullscreenIcon;
        _fullscreen.Click += (_, _) => FullscreenRequested?.Invoke(this, EventArgs.Empty);
        AutomationProperties.SetAutomationId(_fullscreen, "ImageFullscreenButton");
        SetFullscreen(false);
        footer.Children.Add(_metadata);
        footer.Children.Add(controls);
        footer.Children.Add(_fullscreen);
        SetRow(footer, 1);
        Children.Add(footer);
        AutomationProperties.SetAutomationId(_metadata, "ImageMetadata");
        AutomationProperties.SetAutomationId(_zoomActions, "ImageZoomBar");

        _loading = new StackPanel
        {
            Spacing = 12, Margin = new Thickness(12), HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center, Visibility = Visibility.Collapsed,
        };
        _loading.Children.Add(_progress);
        _loading.Children.Add(_progressText);
        Canvas.SetZIndex(_loading, 1);
        _content.Children.Add(_loading);

        var retry = new Button
        {
            Content = "重试", Style = SurfaceGeometry.Style("FwSecondaryButtonStyle"),
            HorizontalAlignment = HorizontalAlignment.Center,
        };
        retry.Click += (_, _) => RetryRequested?.Invoke(this, EventArgs.Empty);
        var errorBody = new StackPanel { Spacing = 10 };
        errorBody.Children.Add(new LucideIcon
        {
            IconName = "alert-triangle", Width = 24, Height = 24, HorizontalAlignment = HorizontalAlignment.Center,
            Style = SurfaceGeometry.Style("FwDangerIconStyle"),
        });
        errorBody.Children.Add(_errorTitle);
        errorBody.Children.Add(_errorMessage);
        errorBody.Children.Add(retry);
        _error = new Border
        {
            Style = SurfaceGeometry.Style("FwErrorCardStyle"), MaxWidth = 420, Margin = new Thickness(12),
            HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
            Visibility = Visibility.Collapsed, Child = errorBody,
        };
        SurfaceStyles.SetUsePopupMaterial(_error, true);
        Canvas.SetZIndex(_error, 1);
        _content.Children.Add(_error);
    }

    internal void SetMetadata(string metadata)
    {
        _metadata.Text = metadata;
        ToolTipService.SetToolTip(_metadata, metadata);
    }

    internal void SetFullscreen(bool fullscreen)
    {
        _fullscreenIcon.IconName = fullscreen ? "minimize" : "maximize";
        var label = AppBranding.IsChinese
            ? fullscreen ? "退出全屏 (F11)" : "全屏 (F11)"
            : fullscreen ? "Exit fullscreen (F11)" : "Fullscreen (F11)";
        AutomationProperties.SetName(_fullscreen, label);
        ToolTipService.SetToolTip(_fullscreen, label);
    }

    internal void ShowFullscreenFailure()
    {
        _fullscreenNotice.Title = AppBranding.IsChinese ? "全屏切换失败，请重试。" : "Couldn't switch fullscreen mode. Try again.";
        FloatingNotice.Show(_fullscreenNotice, _fullscreenNotice.Title, repeat: true, compact: true);
    }

    internal void ClearFullscreenFailure() => _fullscreenNotice.IsOpen = false;

    internal void DetachNotice() => FloatingNotice.Detach(_fullscreenNotice);

    internal void SetNavigation(Action? previous, Action? next)
    {
        _previousColumn.Width = new GridLength(previous is null ? 0 : 56);
        _nextColumn.Width = new GridLength(next is null ? 0 : 56);
        _previous.Tag = previous;
        _next.Tag = next;
        _previous.Visibility = previous is null ? Visibility.Collapsed : Visibility.Visible;
        _next.Visibility = next is null ? Visibility.Collapsed : Visibility.Visible;
    }

    internal void SetLoading(bool loading, string message = "")
    {
        _loading.Visibility = loading ? Visibility.Visible : Visibility.Collapsed;
        _progress.IsActive = loading;
        _progressText.Text = message;
        if (loading) _error.Visibility = Visibility.Collapsed;
    }

    internal void ShowError(string title, string message)
    {
        SetLoading(false);
        _errorTitle.Text = title;
        _errorMessage.Text = message;
        _error.Visibility = Visibility.Visible;
    }

    private static Button NavigationButton(string label, bool next)
    {
        var glyph = new LucideIcon { IconName = "arrow-left", Width = 20, Height = 20 };
        if (next) glyph.RenderTransform = new RotateTransform { Angle = 180, CenterX = 10, CenterY = 10 };
        var button = new Button
        {
            Style = SurfaceGeometry.Style("FwNavButtonStyle"), HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center, Content = glyph, Visibility = Visibility.Collapsed,
        };
        ToolTipService.SetToolTip(button, label);
        AutomationProperties.SetName(button, label);
        button.Click += (_, _) => { if (button.Tag is Action action) action(); };
        return button;
    }
}
