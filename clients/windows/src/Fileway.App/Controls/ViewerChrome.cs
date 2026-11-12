using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;
using Fileway.App.Branding;
using Fileway.Core.Contracts;

namespace Fileway.App.Controls;

/// <summary>Image canvas between the viewing controls and thumbnail strip.</summary>
internal sealed class ViewerChrome : Grid, IDisposable
{
    private const double CanvasInset = 10;
    private const double NavigationClearance = 54;

    private readonly Grid _stage = new();
    private readonly Grid _content = new() { Margin = new Thickness(CanvasInset, 0, CanvasInset, 0) };
    private readonly StackPanel _zoomActions = new()
    {
        Orientation = Orientation.Horizontal, Spacing = 4, VerticalAlignment = VerticalAlignment.Center,
        Visibility = Visibility.Collapsed,
    };
    private readonly Border _actionDivider = ToolbarDivider(new Thickness(4, 0, 4, 0));
    private readonly Button _previous = NavigationButton("上一张", false);
    private readonly Button _next = NavigationButton("下一张", true);
    private readonly LucideIcon _fullscreenIcon = new() { Width = 18, Height = 18 };
    private readonly Button _fullscreen = new()
    {
        Style = SurfaceGeometry.Style("FwTextIconButtonStyle"), Width = 34, Height = 34, Padding = new Thickness(0),
        VerticalAlignment = VerticalAlignment.Center,
    };
    private readonly ImagePlaylist _playlist = new()
    {
        HorizontalAlignment = HorizontalAlignment.Center,
        Margin = new Thickness(16, 0, 16, 10),
        Visibility = Visibility.Collapsed,
    };
    private readonly ToggleButton _playlistToggle = new()
    {
        Style = SurfaceGeometry.Style("FwViewerToggleStyle"), Width = 34,
        Padding = new Thickness(0), Visibility = Visibility.Collapsed,
    };
    private readonly Button _playlistSize = new()
    {
        Style = SurfaceGeometry.Style("FwTextIconButtonStyle"), Width = 34, Height = 34,
        Padding = new Thickness(0), Visibility = Visibility.Collapsed,
        Content = new LucideIcon { IconName = "gear", Width = 17, Height = 17 },
    };
    private int _preferredThumbnailHeight = PresentationPreferences.ImageThumbnailHeight;
    private int _savedThumbnailHeight;
    private Flyout? _playlistSizeFlyout;
    private bool _playlistSizeOpen;
    private bool _playlistAvailable;
    private bool? _playlistPreference;
    private bool _updatingPlaylistToggle;
    private bool _isFullscreen;
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
    internal event EventHandler<int>? ImageSelected;

    internal ViewerChrome()
    {
        Style = SurfaceGeometry.Style("FwViewerChromeStyle");
        Background = null;
        _savedThumbnailHeight = _preferredThumbnailHeight;

        RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        Children.Add(BuildToolbar());

        _stage.Margin = new Thickness(0, 4, 0, 4);
        SetRow(_stage, 1);
        _stage.Children.Add(_content);
        _stage.Children.Add(_previous);
        _stage.Children.Add(_next);
        Children.Add(_stage);
        AutomationProperties.SetAutomationId(_previous, "ImagePrevious");
        AutomationProperties.SetAutomationId(_next, "ImageNext");
        FloatingNotice.Attach(_fullscreenNotice, automatic: false);
        Canvas.SetZIndex(_fullscreenNotice, 2);
        _stage.Children.Add(_fullscreenNotice);

        SetRow(_playlist, 2);
        Children.Add(_playlist);
        _playlist.ImageSelected += (_, index) => ImageSelected?.Invoke(this, index);
        SizeChanged += (_, _) => UpdatePlaylistLayout();

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

    internal void SetImageReady(bool ready)
    {
        var visibility = ready ? Visibility.Visible : Visibility.Collapsed;
        _zoomActions.Visibility = visibility;
        _actionDivider.Visibility = visibility;
    }

    internal void SetPlaylist(IReadOnlyList<RemoteResourceRef> resources,
        Func<RemoteResourceRef, CancellationToken, Task<ImageSource?>>? loadThumbnail)
    {
        _playlistAvailable = resources.Count > 1;
        _playlistToggle.Visibility = _playlistAvailable ? Visibility.Visible : Visibility.Collapsed;
        _playlistSize.Visibility = _playlistToggle.Visibility;
        if (_playlistAvailable)
        {
            _playlist.SetItems(resources, loadThumbnail);
        }
        UpdatePlaylistLayout();
    }

    internal void SelectPlaylistImage(int index) => _playlist.SetCurrent(index);

    internal bool DismissPlaylistSize()
    {
        if (!_playlistSizeOpen || _playlistSizeFlyout is not { } flyout) return false;
        flyout.Hide();
        return true;
    }

    private void UpdatePlaylistLayout()
    {
        var visible = _playlistAvailable && (_playlistPreference ?? (!_isFullscreen && ActualWidth >= 560));
        _playlist.Visibility = visible ? Visibility.Visible : Visibility.Collapsed;
        var heightLimit = ActualHeight > 0
            ? Math.Max(PresentationPreferences.MinimumImageThumbnailHeight, (int)(ActualHeight * 0.19))
            : PresentationPreferences.MaximumImageThumbnailHeight;
        _playlist.SetThumbnailHeight(Math.Min(_preferredThumbnailHeight, heightLimit));
        _updatingPlaylistToggle = true;
        _playlistToggle.IsChecked = visible;
        _updatingPlaylistToggle = false;
    }

    internal void SetFullscreen(bool fullscreen)
    {
        _isFullscreen = fullscreen;
        _fullscreenIcon.IconName = fullscreen ? "minimize" : "maximize";
        var label = AppBranding.IsChinese
            ? fullscreen ? "退出全屏 (F11)" : "全屏 (F11)"
            : fullscreen ? "Exit fullscreen (F11)" : "Fullscreen (F11)";
        AutomationProperties.SetName(_fullscreen, label);
        ToolTipService.SetToolTip(_fullscreen, label);
        UpdatePlaylistLayout();
    }

    internal void ShowFullscreenFailure()
    {
        _fullscreenNotice.Title = AppBranding.IsChinese ? "全屏切换失败，请重试。" : "Couldn't switch fullscreen mode. Try again.";
        FloatingNotice.Show(_fullscreenNotice, _fullscreenNotice.Title, repeat: true, compact: true);
    }

    internal void ClearFullscreenFailure() => _fullscreenNotice.IsOpen = false;

    public void Dispose()
    {
        SaveThumbnailHeight();
        FloatingNotice.Detach(_fullscreenNotice);
        _playlist.Dispose();
    }

    internal void SetNavigation(Action? previous, Action? next)
    {
        var inset = previous is null && next is null ? CanvasInset : NavigationClearance;
        _content.Margin = new Thickness(inset, 0, inset, 0);
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

    private Border BuildToolbar()
    {
        _fullscreen.Content = _fullscreenIcon;
        _fullscreen.Click += (_, _) => FullscreenRequested?.Invoke(this, EventArgs.Empty);
        AutomationProperties.SetAutomationId(_fullscreen, "ImageFullscreenButton");
        AutomationProperties.SetAutomationId(_zoomActions, "ImageZoomBar");
        var playlistLabel = AppBranding.IsChinese ? "图片列表" : "Image list";
        _playlistToggle.Content = new LucideIcon { IconName = "image", Width = 17, Height = 17 };
        AutomationProperties.SetName(_playlistToggle, playlistLabel);
        ToolTipService.SetToolTip(_playlistToggle, playlistLabel);
        AutomationProperties.SetAutomationId(_playlistToggle, "ImagePlaylistToggle");
        _playlistToggle.Checked += (_, _) => OnPlaylistToggleChanged();
        _playlistToggle.Unchecked += (_, _) => OnPlaylistToggleChanged();
        ConfigurePlaylistSizing();
        SetFullscreen(false);

        var actions = new StackPanel
        {
            Orientation = Orientation.Horizontal, Spacing = 2, VerticalAlignment = VerticalAlignment.Center,
        };
        actions.Children.Add(_zoomActions);
        actions.Children.Add(_actionDivider);
        actions.Children.Add(_playlistToggle);
        actions.Children.Add(_playlistSize);
        actions.Children.Add(_fullscreen);

        var toolbar = new Border
        {
            Style = SurfaceGeometry.Style("FwPillStyle"),
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(12, 4, 12, 4), Padding = new Thickness(5, 2, 5, 2),
            MinHeight = 40,
            CornerRadius = new CornerRadius(12), Child = actions,
        };
        AutomationProperties.SetAutomationId(toolbar, "ImageViewerToolbar");
        SurfaceStyles.SetUsePopupMaterial(toolbar, true);
        return toolbar;
    }

    private void ConfigurePlaylistSizing()
    {
        var label = AppBranding.IsChinese ? "缩略图大小" : "Thumbnail size";
        var slider = new Slider
        {
            Header = label, Width = 218, StepFrequency = 2, IsThumbToolTipEnabled = false,
            Minimum = PresentationPreferences.MinimumImageThumbnailHeight,
            Maximum = PresentationPreferences.MaximumImageThumbnailHeight,
            Value = _preferredThumbnailHeight,
        };
        AutomationProperties.SetAutomationId(_playlistSize, "ImagePlaylistSizeButton");
        AutomationProperties.SetName(_playlistSize, label);
        AutomationProperties.SetAutomationId(slider, "ImagePlaylistSizeSlider");
        AutomationProperties.SetName(slider, label);
        ToolTipService.SetToolTip(_playlistSize, label);
        var flyout = new Flyout { Content = slider, Placement = FlyoutPlacementMode.Bottom };
        flyout.Opening += (_, _) =>
        {
            _playlistSizeOpen = true;
            _playlistPreference = true;
            UpdatePlaylistLayout();
        };
        flyout.Closed += (_, _) =>
        {
            _playlistSizeOpen = false;
            SaveThumbnailHeight();
        };
        slider.ValueChanged += (_, args) =>
        {
            _preferredThumbnailHeight = (int)Math.Round(args.NewValue);
            UpdatePlaylistLayout();
        };
        _playlistSize.Flyout = _playlistSizeFlyout = flyout;
    }

    private void SaveThumbnailHeight()
    {
        if (_savedThumbnailHeight == _preferredThumbnailHeight) return;
        try
        {
            PresentationPreferences.ImageThumbnailHeight = _preferredThumbnailHeight;
            _savedThumbnailHeight = _preferredThumbnailHeight;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or System.Security.SecurityException)
        {
            System.Diagnostics.Debug.WriteLine(error.GetType().Name);
        }
    }

    private void OnPlaylistToggleChanged()
    {
        if (_updatingPlaylistToggle) return;
        _playlistPreference = _playlistToggle.IsChecked == true;
        UpdatePlaylistLayout();
    }

    private static Border ToolbarDivider(Thickness margin) => new()
    {
        Style = SurfaceGeometry.Style("FwDividerStrongStyle"), Width = 1, Height = 18,
        Margin = margin, VerticalAlignment = VerticalAlignment.Center, Visibility = Visibility.Collapsed,
    };

    private static Button NavigationButton(string label, bool next)
    {
        var glyph = new LucideIcon { IconName = "arrow-left", Width = 17, Height = 17 };
        if (next) glyph.RenderTransform = new RotateTransform { Angle = 180, CenterX = 8.5, CenterY = 8.5 };
        var button = new Button
        {
            Style = SurfaceGeometry.Style("FwNavButtonStyle"), Width = 38, Height = 38, CornerRadius = new CornerRadius(19),
            Margin = new Thickness(CanvasInset, 0, CanvasInset, 0),
            HorizontalAlignment = next ? HorizontalAlignment.Right : HorizontalAlignment.Left,
            VerticalAlignment = VerticalAlignment.Center, Content = glyph, Visibility = Visibility.Collapsed,
        };
        SurfaceStyles.SetUsePopupMaterial(button, true);
        ToolTipService.SetToolTip(button, label);
        AutomationProperties.SetName(button, label);
        button.Click += (_, _) => { if (button.Tag is Action action) action(); };
        return button;
    }
}
