using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.App.Branding;
using Fileway.App.Controls;
using Fileway.App.Controls.Browser;
using Fileway.App.Windowing;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Foundation;
using Windows.System;
using Windows.UI.ViewManagement;

namespace Fileway.App.Views;

/// <summary>Immersive stream-owned image viewer: fit/actual zoom, pointer-anchored wheel zoom, drag pan.</summary>
public sealed class ImageWindow : Window, IAsyncDisposable
{
    private const float MinimumZoom = 0.1f;
    private const float MaximumZoom = 8f;
    // At the minimum zoom a long image must still fit the shortest supported window.
    private const double LongEdgeLayoutLimit = 1536;

    private enum ZoomModeSelection { Fit, Actual, Manual }
    private sealed record ZoomRequest(float Factor, double Horizontal, double Vertical);
    private sealed class PhotoCanvas : UserControl
    {
        protected override AutomationPeer OnCreateAutomationPeer() => new FrameworkElementAutomationPeer(this);
    }
    private readonly IImageContentService _service;
    private readonly IRecentAccessService? _recentAccess;
    private readonly WindowTitleBar _titleBar = new();
    private readonly FramelessWindowChrome _chrome;
    private readonly ViewerChrome _viewer = new();
    private readonly ScrollViewer _scroll = new()
    {
        ZoomMode = ZoomMode.Enabled, MinZoomFactor = MinimumZoom, MaxZoomFactor = MaximumZoom,
        HorizontalScrollBarVisibility = ScrollBarVisibility.Hidden, VerticalScrollBarVisibility = ScrollBarVisibility.Hidden,
        HorizontalScrollMode = ScrollMode.Enabled, VerticalScrollMode = ScrollMode.Enabled,
        IsTabStop = false,
    };
    private readonly PhotoCanvas _canvas = new()
    {
        Style = SurfaceGeometry.Style("FwPhotoViewportStyle"),
        HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
        HorizontalContentAlignment = HorizontalAlignment.Stretch, VerticalContentAlignment = VerticalAlignment.Stretch,
        IsTabStop = true, UseSystemFocusVisuals = true,
        FocusVisualPrimaryThickness = new Thickness(1), FocusVisualSecondaryThickness = new Thickness(0),
    };
    private ScrollContentPresenter? _viewportInput;
    private readonly Image _image = new() { Stretch = Stretch.Uniform };
    private readonly Image _ambientBack = CreateAmbientLayer();
    private readonly Image _ambientFront = CreateAmbientLayer();
    private readonly TextBlock _zoomLabel = new() { Style = (Style)Application.Current.Resources["FwScrimBodyTextStyle"], MinWidth = 44, TextAlignment = TextAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
    private readonly ToggleButton _fitButton = new();
    private readonly ToggleButton _actualButton = new();
    private readonly Grid _root = new();
    private readonly UISettings _systemSettings = new();
    private readonly AccessibilitySettings _accessibility = new();
    private ImageAmbientSample? _displayedAmbient;
    private long _ambientRevision;
    private XamlRoot? _xamlRoot;
    private ZoomModeSelection _zoomMode;
    private double _rasterizationScale = 1;
    private double _layoutScale = 1;
    private bool _zoomRefreshPending;
    private bool _updatingZoomMode;
    private ZoomRequest? _zoomRequest;
    private readonly HashSet<uint> _touchContacts = [];
    private float? _gestureStartZoom;
    private readonly IReadOnlyList<RemoteResourceRef> _resources;
    private readonly CancellationTokenSource _lifetime = new();
    private bool _closed;
    private bool _started;
    private Task? _loadTask;
    private CancellationTokenSource? _imageCancellation;
    private CancellationTokenSource? _prefetchCancellation;
    private Task<ImagePreviewFrame?>? _prefetchTask;
    private int _prefetchIndex = -1;
    private Storyboard? _imageTransition;
    private long _generation;
    private int _requestedIndex;
    private int _navigationDirection = 1;
    private uint? _dragPointer;
    private Point _dragStart;
    private double _dragHorizontal;
    private double _dragVertical;

    public ImageWindow(IImageContentService service, RemoteResourceRef resource, IReadOnlyList<RemoteResourceRef>? resources = null,
        IRecentAccessService? recentAccess = null)
    {
        _recentAccess = recentAccess;
        _service = service;
        _titleBar.SetViewerCaption();
        UpdateCaption(resource);
        var position = resources?.ToList().FindIndex(item => item == resource) ?? -1;
        _resources = position >= 0 ? resources! : [resource];
        _requestedIndex = Math.Max(0, position);
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"));

        _scroll.Content = _canvas;
        _canvas.Content = _image;
        _viewer.ContentHost.Children.Add(_scroll);
        AutomationProperties.SetAutomationId(_scroll, "ImageViewport");
        AutomationProperties.SetName(_scroll, "图片画布");
        AutomationProperties.SetAutomationId(_canvas, "ImageCanvasInput");
        AutomationProperties.SetName(_canvas, "图片");
        AutomationProperties.SetAutomationId(_image, "ImageView");
        AutomationProperties.SetName(_image, "图片");

        BuildChrome();
        Func<RemoteResourceRef, CancellationToken, Task<ImagePlaylist.Thumbnail?>>? thumbnailLoader =
            _service is IThumbnailContentService ? LoadPlaylistThumbnailAsync : null;
        _viewer.SetPlaylist(_resources, thumbnailLoader);
        _viewer.ImageSelected += (_, index) => _ = NavigateAsync(index);

        // The canvas is the app canvas: a photo is judged against the surface the user chose,
        // so a light theme must not be forced into a black mat.
        var windowRoot = _root;
        windowRoot.Style = (Style)Application.Current.Resources["FwViewerCanvasStyle"];
        windowRoot.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        windowRoot.RowDefinitions.Add(new RowDefinition());
        Grid.SetRowSpan(_ambientBack, 2);
        Grid.SetRowSpan(_ambientFront, 2);
        windowRoot.Children.Add(_ambientBack);
        windowRoot.Children.Add(_ambientFront);
        windowRoot.Children.Add(_titleBar);
        Grid.SetRow(_viewer, 1);
        windowRoot.Children.Add(_viewer);
        Content = windowRoot;
        _chrome = new FramelessWindowChrome(this, _titleBar);
        SetInitialBounds();
        windowRoot.PreviewKeyDown += OnKeyDown;
        windowRoot.ActualThemeChanged += (_, _) => _ = RefreshAmbientThemeAsync();
        _viewer.FullscreenRequested += (_, _) => ToggleFullscreen();
        _viewer.RetryRequested += async (_, _) =>
        {
            FocusCanvas();
            await NavigateAsync(_requestedIndex);
        };
        windowRoot.Loaded += async (_, _) =>
        {
            if (_started) return;
            _started = true;
            _xamlRoot = windowRoot.XamlRoot;
            if (_xamlRoot is not null)
            {
                _rasterizationScale = _xamlRoot.RasterizationScale;
                _xamlRoot.Changed += OnXamlRootChanged;
            }
            ConnectViewportInput();
            FocusCanvas();
            await NavigateAsync(_requestedIndex);
        };
        _scroll.ViewChanged += OnViewChanged;
        _scroll.SizeChanged += (_, _) => QueueZoomRefresh();
        _image.SizeChanged += (_, _) => QueueZoomRefresh();
        _scroll.DirectManipulationStarted += (_, _) =>
        {
            _zoomRequest = null;
            if (_touchContacts.Count > 0) _gestureStartZoom = _scroll.ZoomFactor;
        };
        _scroll.DirectManipulationCompleted += (_, _) => _gestureStartZoom = null;
        _scroll.DoubleTapped += OnDoubleTapped;
        Closed += OnClosed;
    }

    private static Image CreateAmbientLayer()
    {
        var layer = new Image { Stretch = Stretch.UniformToFill, IsHitTestVisible = false };
        AutomationProperties.SetAccessibilityView(layer, AccessibilityView.Raw);
        return layer;
    }

    private async Task<ImagePlaylist.Thumbnail?> LoadPlaylistThumbnailAsync(RemoteResourceRef resource, CancellationToken token)
    {
        if (_service is not IThumbnailContentService thumbnails) return null;
        using var bitmap = await Task.Run(() => BrowserThumbnailController.DecodeAsync(thumbnails, resource, token), token);
        token.ThrowIfCancellationRequested();
        var source = new SoftwareBitmapSource();
        await source.SetBitmapAsync(bitmap);
        token.ThrowIfCancellationRequested();
        return new ImagePlaylist.Thumbnail(source, (double)bitmap.PixelWidth / bitmap.PixelHeight);
    }

    private void UpdateCaption(RemoteResourceRef resource)
    {
        Title = resource.DisplayName + " · " + AppBranding.DisplayName;
        _titleBar.Caption = resource.DisplayName;
    }

    private void SetImageDetails(int index, string details)
    {
        var position = _resources.Count > 1 ? $"{index + 1}/{_resources.Count}" : "";
        if (details.Length > 0 && position.Length > 0) details = $"{position} · {details}";
        else if (details.Length == 0) details = position;
        _titleBar.SetImageDetails(details);
        AutomationProperties.SetHelpText(_image, details);
    }

    private UIElement CanvasInput => _viewportInput ?? (UIElement)_scroll;

    private void ConnectViewportInput()
    {
        _scroll.ApplyTemplate();
        if (FindViewport(_scroll) is { } viewport)
        {
            _viewportInput = viewport;
            // Receive input over the full canvas before the native ScrollViewer handles it.
            viewport.Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent);
        }
        var surface = CanvasInput;
        surface.PointerWheelChanged += OnWheel;
        surface.AddHandler(UIElement.PointerPressedEvent, new PointerEventHandler(OnPointerPressed), true);
        surface.AddHandler(UIElement.PointerMovedEvent, new PointerEventHandler(OnPointerMoved), true);
        surface.AddHandler(UIElement.PointerReleasedEvent, new PointerEventHandler(OnPointerReleased), true);
        surface.AddHandler(UIElement.PointerCanceledEvent, new PointerEventHandler(OnPointerReleased), true);
        surface.PointerCaptureLost += OnPointerCaptureLost;
    }

    private static ScrollContentPresenter? FindViewport(DependencyObject parent)
    {
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(parent); index++)
        {
            var child = VisualTreeHelper.GetChild(parent, index);
            if (child is ScrollContentPresenter viewport) return viewport;
            if (FindViewport(child) is { } descendant) return descendant;
        }
        return null;
    }

    private void FocusCanvas()
    {
        _canvas.Focus(FocusState.Programmatic);
    }

    private void SetInitialBounds()
    {
        var area = DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Primary).WorkArea;
        var scale = _chrome.RasterizationScale;
        var margin = (int)Math.Round(24 * scale);
        var width = Math.Min((int)Math.Round(960 * scale), Math.Max(1, area.Width - 2 * margin));
        var height = Math.Min((int)Math.Round(680 * scale), Math.Max(1, area.Height - 2 * margin));
        AppWindow.MoveAndResize(new Windows.Graphics.RectInt32(
            area.X + (area.Width - width) / 2, area.Y + (area.Height - height) / 2, width, height));
    }

    private void BuildChrome()
    {
        _viewer.SetNavigation(
            _resources.Count > 1 ? () => StepImage(-1) : null,
            _resources.Count > 1 ? () => StepImage(1) : null);
        _viewer.ZoomActions.Children.Add(ZoomButton("minus", "缩小", () => ZoomBy(0.8f)));
        _viewer.ZoomActions.Children.Add(_zoomLabel);
        _viewer.ZoomActions.Children.Add(ZoomButton("plus", "放大", () => ZoomBy(1.25f)));
        _viewer.ZoomActions.Children.Add(new Border
        {
            Width = 1, Height = 16, Margin = new Thickness(2, 0, 2, 0),
            Style = SurfaceGeometry.Style("FwDividerStrongStyle"), VerticalAlignment = VerticalAlignment.Center,
        });
        ConfigurePreset(_fitButton, "适应", "适应窗口 (0)", Fit);
        ConfigurePreset(_actualButton, "1:1", "实际大小 (1)", ActualSize);
        AutomationProperties.SetAutomationId(_zoomLabel, "ViewerZoomLabel");
        SetZoomControlsEnabled(false);
        SetZoomMode(ZoomModeSelection.Fit);
    }

    private static Button ZoomButton(string icon, string label, Action click)
    {
        var button = new Button
        {
            Width = 34, Height = 34, Padding = new Thickness(0), Style = SurfaceGeometry.Style("FwTextIconButtonStyle"),
            Content = new LucideIcon { IconName = icon, Width = 16, Height = 16 },
        };
        AutomationProperties.SetName(button, label);
        ToolTipService.SetToolTip(button, label);
        button.Click += (_, _) => click();
        return button;
    }

    private void ConfigurePreset(ToggleButton button, string text, string label, Action click)
    {
        button.Style = SurfaceGeometry.Style("FwViewerToggleStyle");
        button.Content = text;
        button.MinWidth = 36;
        AutomationProperties.SetName(button, label);
        ToolTipService.SetToolTip(button, label);
        button.Checked += (_, _) => { if (!_updatingZoomMode) click(); };
        button.Unchecked += (_, _) => { if (!_updatingZoomMode) SetZoomMode(_zoomMode); };
        _viewer.ZoomActions.Children.Add(button);
    }

    private void SetZoomControlsEnabled(bool enabled)
    {
        foreach (var control in _viewer.ZoomActions.Children.OfType<Control>()) control.IsEnabled = enabled;
        _viewer.SetImageReady(enabled);
    }

    private void SetZoomMode(ZoomModeSelection mode)
    {
        _zoomMode = mode;
        _updatingZoomMode = true;
        try
        {
            _fitButton.IsChecked = mode == ZoomModeSelection.Fit;
            _actualButton.IsChecked = mode == ZoomModeSelection.Actual;
        }
        finally { _updatingZoomMode = false; }
    }

    private float ActualZoom => (float)(1 / (_layoutScale * _rasterizationScale));

    private void OnViewChanged(object? sender, ScrollViewerViewChangedEventArgs args)
    {
        if (!args.IsIntermediate && _zoomRequest is { } request && Math.Abs(request.Factor - _scroll.ZoomFactor) < 0.0005)
            _zoomRequest = null;
        if (_gestureStartZoom is { } start && Math.Abs(_scroll.ZoomFactor - start) >= 0.0005)
            SetZoomMode(ZoomModeSelection.Manual);
        UpdateZoomLabel();
    }

    private void UpdateZoomLabel()
    {
        if (_image.Source is null) { _zoomLabel.Text = "—"; return; }
        _zoomLabel.Text = $"{(int)Math.Round(_scroll.ZoomFactor / ActualZoom * 100)}%";
    }

    private void OnXamlRootChanged(XamlRoot sender, XamlRootChangedEventArgs args)
    {
        if (_rasterizationScale == sender.RasterizationScale) return;
        _rasterizationScale = sender.RasterizationScale;
        QueueZoomRefresh();
        UpdateZoomLabel();
    }

    private void QueueZoomRefresh()
    {
        if (_zoomRefreshPending || _closed || _image.Source is null || _zoomMode == ZoomModeSelection.Manual) return;
        _zoomRefreshPending = DispatcherQueue.TryEnqueue(Microsoft.UI.Dispatching.DispatcherQueuePriority.Low, () =>
        {
            _zoomRefreshPending = false;
            ApplyPreset();
        });
    }

    private void OnWheel(object sender, PointerRoutedEventArgs args)
    {
        if (args.Handled) return;
        args.Handled = true;
        if (_image.Source is null || _closed) return;
        var point = args.GetCurrentPoint(_scroll);
        var delta = point.Properties.MouseWheelDelta;
        if (delta == 0 || point.Properties.IsHorizontalMouseWheel) return;
        var factor = (float)Math.Pow(1.12, delta / 120d);
        ZoomTo(InteractionZoom * factor, point.Position);
    }

    private float InteractionZoom => _zoomRequest?.Factor ?? _scroll.ZoomFactor;
    private void ZoomBy(float factor) => ZoomTo(InteractionZoom * factor, null);

    private void ZoomTo(float target, Point? anchor)
    {
        if (_image.Source is null) return;
        target = Math.Clamp(target, MinimumZoom, MaximumZoom);
        var old = InteractionZoom;
        if (Math.Abs(target - old) < 0.0005) return;
        var viewportWidth = _scroll.ViewportWidth;
        var viewportHeight = _scroll.ViewportHeight;
        if (viewportWidth <= 0 || viewportHeight <= 0) return;
        var point = anchor ?? new Point(viewportWidth / 2, viewportHeight / 2);
        var horizontal = AnchorOffset(point.X, viewportWidth, _image.Width, _zoomRequest?.Horizontal ?? _scroll.HorizontalOffset, old, target);
        var vertical = AnchorOffset(point.Y, viewportHeight, _image.Height, _zoomRequest?.Vertical ?? _scroll.VerticalOffset, old, target);
        SetZoomMode(ZoomModeSelection.Manual);
        _zoomRequest = new(target, horizontal, vertical);
        if (!_scroll.ChangeView(horizontal, vertical, target, disableAnimation: true)) _zoomRequest = null;
    }

    private static double AnchorOffset(double pointer, double viewport, double image, double scroll, float old, float target)
    {
        var oldInset = Math.Max(0, (viewport - image * old) / 2);
        var newInset = Math.Max(0, (viewport - image * target) / 2);
        var imagePoint = Math.Clamp((scroll + pointer - oldInset) / old, 0, image);
        return Math.Clamp(imagePoint * target + newInset - pointer, 0, Math.Max(0, image * target - viewport));
    }

    private void Fit()
    {
        SetZoomMode(ZoomModeSelection.Fit);
        ApplyPreset();
    }

    private void ActualSize()
    {
        SetZoomMode(ZoomModeSelection.Actual);
        ApplyPreset();
    }

    private void ApplyPreset()
    {
        if (_closed || _image.Source is null || _zoomMode == ZoomModeSelection.Manual) return;
        _zoomRequest = null;
        var viewportWidth = _scroll.ViewportWidth > 0 ? _scroll.ViewportWidth : _scroll.ActualWidth;
        var viewportHeight = _scroll.ViewportHeight > 0 ? _scroll.ViewportHeight : _scroll.ActualHeight;
        if (viewportWidth <= 0 || viewportHeight <= 0) return;
        var zoom = _zoomMode == ZoomModeSelection.Actual ? ActualZoom : Math.Min(ActualZoom,
            (float)Math.Min(viewportWidth / _image.Width, viewportHeight / _image.Height));
        zoom = Math.Clamp(zoom, MinimumZoom, MaximumZoom);
        var horizontal = Math.Max(0, (_image.Width * zoom - viewportWidth) / 2);
        var vertical = Math.Max(0, (_image.Height * zoom - viewportHeight) / 2);
        _scroll.ChangeView(horizontal, vertical, zoom, disableAnimation: true);
        UpdateZoomLabel();
    }

    private void OnDoubleTapped(object sender, DoubleTappedRoutedEventArgs args)
    {
        if (_image.Source is null) return;
        args.Handled = true;
        if (_zoomMode != ZoomModeSelection.Fit) Fit();
        else ZoomTo(_scroll.ZoomFactor < ActualZoom * 0.98f ? ActualZoom : 2 * ActualZoom, args.GetPosition(_scroll));
    }

    private void OnPointerPressed(object sender, PointerRoutedEventArgs args)
    {
        if (args.Pointer.PointerDeviceType == Microsoft.UI.Input.PointerDeviceType.Touch)
        {
            _touchContacts.Add(args.Pointer.PointerId);
            return;
        }
        if (args.Handled) return;
        var point = args.GetCurrentPoint(_scroll);
        if (!point.Properties.IsLeftButtonPressed || _dragPointer is not null || !CanPan() ||
            !CanvasInput.CapturePointer(args.Pointer)) return;
        _zoomRequest = null;
        _dragPointer = args.Pointer.PointerId;
        _dragStart = point.Position;
        _dragHorizontal = _scroll.HorizontalOffset;
        _dragVertical = _scroll.VerticalOffset;
        args.Handled = true;
    }

    private void OnPointerMoved(object sender, PointerRoutedEventArgs args)
    {
        if (_dragPointer != args.Pointer.PointerId) return;
        var position = args.GetCurrentPoint(_scroll).Position;
        _scroll.ChangeView(
            Math.Clamp(_dragHorizontal - (position.X - _dragStart.X), 0, _scroll.ScrollableWidth),
            Math.Clamp(_dragVertical - (position.Y - _dragStart.Y), 0, _scroll.ScrollableHeight),
            null, disableAnimation: true);
        args.Handled = true;
    }

    private void OnPointerReleased(object sender, PointerRoutedEventArgs args)
    {
        if (_touchContacts.Remove(args.Pointer.PointerId)) return;
        if (_dragPointer != args.Pointer.PointerId) return;
        _dragPointer = null;
        CanvasInput.ReleasePointerCapture(args.Pointer);
    }

    private void OnPointerCaptureLost(object sender, PointerRoutedEventArgs args)
    {
        if (_dragPointer == args.Pointer.PointerId) _dragPointer = null;
    }

    private bool CanPan() => _image.Source is not null && (_scroll.ScrollableWidth > 1 || _scroll.ScrollableHeight > 1);

    private void EndPan()
    {
        if (_dragPointer is null) return;
        _dragPointer = null;
        CanvasInput.ReleasePointerCaptures();
    }

    private void ToggleFullscreen()
    {
        if (_closed || _root.XamlRoot is not { } root) return;
        var focused = FocusManager.GetFocusedElement(root) as Control;
        var focusState = focused?.FocusState ?? FocusState.Programmatic;
        EndPan();
        _viewer.ClearFullscreenFailure();
        try { _chrome.ToggleFullscreen(); }
        catch (Exception exception)
        {
            System.Diagnostics.Debug.WriteLine(exception);
            _viewer.ShowFullscreenFailure();
        }
        finally
        {
            _viewer.SetFullscreen(_chrome.IsFullscreen);
            if (focused is null || !focused.Focus(focusState == FocusState.Unfocused ? FocusState.Programmatic : focusState))
                FocusCanvas();
        }
    }

    private void OnKeyDown(object sender, KeyRoutedEventArgs args)
    {
        if ((args.KeyStatus.IsMenuKeyDown ||
            Microsoft.UI.Input.InputKeyboardSource.GetKeyStateForCurrentThread(VirtualKey.Control)
                .HasFlag(Windows.UI.Core.CoreVirtualKeyStates.Down))) return;
        if (args.Key is VirtualKey.Left or VirtualKey.Right or VirtualKey.Up or VirtualKey.Down)
            for (var element = args.OriginalSource as DependencyObject; element is not null && element != _root;
                 element = VisualTreeHelper.GetParent(element))
                if (element is ImagePlaylist || element is ButtonBase button && button.FocusState != FocusState.Pointer) return;

        switch (args.Key)
        {
            case VirtualKey.Left: StepImage(-1); break;
            case VirtualKey.Right: StepImage(1); break;
            case VirtualKey.Up: ZoomBy(1.25f); break;
            case VirtualKey.Down: ZoomBy(0.8f); break;
            case VirtualKey.Number0: Fit(); break;
            case VirtualKey.NumberPad0: Fit(); break;
            case VirtualKey.Number1: ActualSize(); break;
            case VirtualKey.NumberPad1: ActualSize(); break;
            case VirtualKey.Add:
            case (VirtualKey)187: ZoomBy(1.25f); break;
            case VirtualKey.Subtract:
            case (VirtualKey)189: ZoomBy(0.8f); break;
            case VirtualKey.F11:
                if (!args.KeyStatus.WasKeyDown) ToggleFullscreen();
                break;
            case VirtualKey.Escape:
                if (!args.KeyStatus.WasKeyDown)
                {
                    if (_viewer.DismissPlaylistSize()) break;
                    if (_chrome.IsFullscreen) ToggleFullscreen();
                    else Close();
                }
                break;
            default: return;
        }
        args.Handled = true;
    }

    public void SetTheme(ElementTheme theme)
    {
        if (_closed) return;
        // Every surface below resolves its own ThemeResource once the root theme moves.
        _root.RequestedTheme = theme;
        _titleBar.RequestedTheme = theme;
        _viewer.RequestedTheme = theme;
    }

    private void StepImage(int direction)
    {
        if (_resources.Count < 2 || _closed) return;
        _navigationDirection = direction;
        _ = NavigateAsync((_requestedIndex + direction + _resources.Count) % _resources.Count);
    }

    private async Task NavigateAsync(int index)
    {
        if (_closed) return;
        var generation = ++_generation;
        _requestedIndex = index;
        EndPan();
        StopImageTransition();
        _imageCancellation?.Cancel();
        if (_loadTask is not null) await _loadTask;
        if (_closed || generation != _generation) return;
        _imageCancellation?.Dispose();
        _imageCancellation = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
        var resource = _resources[index];
        _viewer.SetLoading(true, $"正在加载 {resource.DisplayName}…");
        _loadTask = LoadAsync(index, resource, generation, _imageCancellation.Token);
        await _loadTask;
    }

    private async Task LoadAsync(int index, RemoteResourceRef resource, long generation, CancellationToken token)
    {
        ImagePreviewFrame? cached = null;
        ImagePreviewFrame? frame = null;
        try
        {
            cached = await TakePrefetchAsync(index, token);
            frame = await Task.Run(() => ImagePreviewDecoder.DecodeAsync(_service, resource, cached, prefetch: false, token), token)
                ?? throw new InvalidOperationException("The image decoder did not return a frame.");
            token.ThrowIfCancellationRequested();
            var bitmap = frame.Bitmap;
            var source = new SoftwareBitmapSource();
            await source.SetBitmapAsync(bitmap);
            var ambientTheme = _root.ActualTheme;
            var ambient = await BuildAmbientAsync(frame.Ambient, ambientTheme);
            if (_closed || generation != _generation || token.IsCancellationRequested) return;
            var animate = _image.Source is not null;
            EndPan();
            _touchContacts.Clear();
            _gestureStartZoom = null;
            _zoomRequest = null;
            _layoutScale = Math.Min(1, LongEdgeLayoutLimit / Math.Max(bitmap.PixelWidth, bitmap.PixelHeight));
            _image.Width = bitmap.PixelWidth * _layoutScale;
            _image.Height = bitmap.PixelHeight * _layoutScale;
            _image.Source = source;
            _displayedAmbient = frame.Ambient;
            ++_ambientRevision;
            _ambientBack.Source = _ambientFront.Source;
            _ambientFront.Source = ambient;
            UpdateCaption(resource);
            _viewer.SetLoading(false);
            _viewer.SelectPlaylistImage(index);
            SetZoomControlsEnabled(true);
            SetZoomMode(ZoomModeSelection.Fit);
            SetImageDetails(index, $"{bitmap.PixelWidth}×{bitmap.PixelHeight}" +
                (frame.EncodedLength > 0 ? $" · {DownloadPresentationPolicy.FormatBytes(frame.EncodedLength)}" : ""));
            ApplyPreset();
            QueueZoomRefresh();
            if (animate) AnimateImageTransition();
            if (ambientTheme != _root.ActualTheme) _ = RefreshAmbientThemeAsync();
            if (_recentAccess is not null)
                _ = new RecentOpenRecording(_recentAccess, resource, generation).ConfirmOpenedAsync(generation, token);
            StartPrefetch(index);
        }
        catch (OperationCanceledException) { }
        catch (Exception exception)
        {
            if (_closed || generation != _generation || token.IsCancellationRequested) return;
            EndPan();
            _image.Source = null;
            _displayedAmbient = null;
            ++_ambientRevision;
            _ambientBack.Source = _ambientFront.Source = null;
            UpdateCaption(resource);
            _viewer.SelectPlaylistImage(index);
            SetZoomMode(ZoomModeSelection.Fit);
            SetZoomControlsEnabled(false);
            SetImageDetails(index, "");
            UpdateZoomLabel();
            // A decode failure is about this file, not the connection; blaming the server sends the user away.
            var detail = exception is FilewayException or System.Threading.Tasks.TaskCanceledException
                ? MainPage.ErrorMessage(exception)
                : "文件内容无法解码，可能已损坏，或不是受支持的图片格式。";
            _viewer.ShowError("图片打不开", detail);
        }
        finally
        {
            if (!ReferenceEquals(frame, cached)) cached?.Dispose();
            frame?.Dispose();
        }
    }

    private async Task<BitmapImage?> BuildAmbientAsync(ImageAmbientSample? sample, ElementTheme theme)
    {
        if (sample is null || _accessibility.HighContrast) return null;
        try { return await sample.CreateBackdropAsync(theme == ElementTheme.Dark); }
        catch (Exception error) when (error is System.Runtime.InteropServices.COMException or ArgumentException)
        {
            return null;
        }
    }

    private async Task RefreshAmbientThemeAsync()
    {
        if (_closed || _displayedAmbient is not { } sample) return;
        var revision = ++_ambientRevision;
        var background = await BuildAmbientAsync(sample, _root.ActualTheme);
        if (_closed || revision != _ambientRevision) return;
        _ambientBack.Source = null;
        _ambientFront.Source = background;
    }

    private void StartPrefetch(int index)
    {
        if (_closed || _resources.Count < 2) return;
        _prefetchIndex = (index + _navigationDirection + _resources.Count) % _resources.Count;
        var resource = _resources[_prefetchIndex];
        _prefetchCancellation = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
        var token = _prefetchCancellation.Token;
        _prefetchTask = Task.Run(async () =>
        {
            try { return await ImagePreviewDecoder.DecodeAsync(_service, resource, null, prefetch: true, token); }
            catch (Exception error)
            {
                System.Diagnostics.Debug.WriteLine($"Image prefetch ended: {error.GetType().Name}");
                return null;
            }
        });
    }

    private async Task<ImagePreviewFrame?> TakePrefetchAsync(int index, CancellationToken token)
    {
        var task = _prefetchTask;
        var cancellation = _prefetchCancellation;
        if (task is null || cancellation is null) return null;
        var matches = index == _prefetchIndex;
        _prefetchTask = null;
        _prefetchCancellation = null;
        _prefetchIndex = -1;
        using (cancellation)
        {
            if (!matches) cancellation.Cancel();
            using var registration = token.Register(cancellation.Cancel);
            var frame = await task;
            if (matches && !token.IsCancellationRequested) return frame;
            frame?.Dispose();
            return null;
        }
    }

    private void AnimateImageTransition()
    {
        try { if (!_systemSettings.AnimationsEnabled) return; }
        catch (System.Runtime.InteropServices.COMException) { return; }
        _imageTransition = new Storyboard();
        var fade = new DoubleAnimation
        {
            From = 0.84, To = 1, Duration = TimeSpan.FromMilliseconds(140),
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut },
        };
        Storyboard.SetTarget(fade, _image);
        Storyboard.SetTargetProperty(fade, "Opacity");
        _imageTransition.Children.Add(fade);
        if (_ambientFront.Source is not null && _ambientBack.Source is not null)
        {
            var atmosphere = new DoubleAnimation { From = 0, To = 1, Duration = TimeSpan.FromMilliseconds(190) };
            Storyboard.SetTarget(atmosphere, _ambientFront);
            Storyboard.SetTargetProperty(atmosphere, "Opacity");
            _imageTransition.Children.Add(atmosphere);
        }
        _imageTransition.Begin();
    }

    private void StopImageTransition()
    {
        _imageTransition?.Stop();
        _imageTransition = null;
        _image.Opacity = 1;
        _ambientFront.Opacity = 1;
    }

    private async void OnClosed(object sender, WindowEventArgs args)
    {
        _chrome.Dispose();
        await DisposeAsync();
    }

    public async ValueTask DisposeAsync()
    {
        if (_closed) return;
        _closed = true; ++_generation; _lifetime.Cancel(); _imageCancellation?.Cancel();
        EndPan();
        StopImageTransition();
        _image.Source = null;
        _ambientBack.Source = _ambientFront.Source = null;
        _viewer.Dispose();
        if (_xamlRoot is not null) _xamlRoot.Changed -= OnXamlRootChanged;
        if (_loadTask is not null) await _loadTask;
        await TakePrefetchAsync(-1, CancellationToken.None);
        _imageCancellation?.Dispose(); _lifetime.Dispose();
    }
}
