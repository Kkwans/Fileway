using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.App.Branding;
using Fileway.App.Controls;
using Fileway.App.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Foundation;
using Windows.Graphics.Imaging;
using Windows.System;

namespace Fileway.App.Views;

/// <summary>Immersive stream-owned image viewer: fit/actual zoom, pointer-anchored wheel zoom, drag pan.</summary>
public sealed class ImageWindow : Window, IAsyncDisposable
{
    private const long EncodedByteBudget = 96L * 1024 * 1024;
    private const double PixelBudget = 16_000_000;
    private const float MinimumZoom = 0.1f;
    private const float MaximumZoom = 8f;
    // Keep long previews inside the ScrollViewer's legal zoom range.
    private const double LongEdgeLayoutLimit = 2400;

    private enum ZoomModeSelection { Fit, Actual, Manual }
    private readonly IImageContentService _service;
    private readonly IRecentAccessService? _recentAccess;
    private readonly WindowTitleBar _titleBar = new();
    private readonly FramelessWindowChrome _chrome;
    private readonly ViewerChrome _viewer = new();
    private readonly ScrollViewer _scroll = new()
    {
        ZoomMode = ZoomMode.Enabled, MinZoomFactor = MinimumZoom, MaxZoomFactor = MaximumZoom,
        HorizontalScrollBarVisibility = ScrollBarVisibility.Auto, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
        HorizontalScrollMode = ScrollMode.Enabled, VerticalScrollMode = ScrollMode.Enabled,
        IsTabStop = true,
        FocusVisualPrimaryThickness = new Thickness(1), FocusVisualSecondaryThickness = new Thickness(0),
    };
    private readonly Grid _canvas = new() { HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
    private readonly Image _image = new() { Stretch = Stretch.Uniform };
    private readonly TextBlock _zoomLabel = new() { Style = (Style)Application.Current.Resources["FwScrimBodyTextStyle"], MinWidth = 44, TextAlignment = TextAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
    private readonly ToggleButton _fitButton = new();
    private readonly ToggleButton _actualButton = new();
    private readonly Grid _root = new();
    private XamlRoot? _xamlRoot;
    private ZoomModeSelection _zoomMode;
    private double _rasterizationScale = 1;
    private double _layoutScale = 1;
    private bool _zoomRefreshPending;
    private bool _updatingZoomMode;
    private readonly HashSet<uint> _touchContacts = [];
    private float? _gestureStartZoom;
    private RemoteResourceRef _resource;
    private readonly IReadOnlyList<RemoteResourceRef> _resources;
    private readonly CancellationTokenSource _lifetime = new();
    private IImageContent? _content;
    private bool _closed;
    private bool _started;
    private Task? _loadTask;
    private CancellationTokenSource? _imageCancellation;
    private long _generation;
    private int _index;
    private bool _dragging;
    private Point _dragStart;
    private double _dragHorizontal;
    private double _dragVertical;

    public ImageWindow(IImageContentService service, RemoteResourceRef resource, IReadOnlyList<RemoteResourceRef>? resources = null,
        IRecentAccessService? recentAccess = null)
    {
        _recentAccess = recentAccess;
        _service = service; _resource = resource; Title = _titleBar.Caption = resource.DisplayName + " · " + AppBranding.DisplayName;
        _resources = resources is { Count: > 0 } ? resources : [resource];
        _index = _resources.ToList().FindIndex(item => item == resource);
        if (_index < 0) _index = 0;
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"));
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1120, 800));

        _scroll.Content = _canvas;
        _canvas.Children.Add(_image);
        _viewer.ContentHost.Children.Add(_scroll);
        AutomationProperties.SetAutomationId(_scroll, "ImageViewport");
        AutomationProperties.SetName(_scroll, "图片画布");
        AutomationProperties.SetAutomationId(_image, "ImageView");
        AutomationProperties.SetName(_image, "图片");

        BuildChrome();

        // The canvas is the app canvas: a photo is judged against the surface the user chose,
        // so a light theme must not be forced into a black mat.
        var windowRoot = _root;
        windowRoot.Style = (Style)Application.Current.Resources["FwViewerCanvasStyle"];
        windowRoot.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        windowRoot.RowDefinitions.Add(new RowDefinition());
        windowRoot.Children.Add(_titleBar);
        Grid.SetRow(_viewer, 1);
        windowRoot.Children.Add(_viewer);
        Content = windowRoot;
        _chrome = new FramelessWindowChrome(this, _titleBar);
        windowRoot.PreviewKeyDown += OnKeyDown;
        _viewer.FullscreenRequested += (_, _) => ToggleFullscreen();
        _viewer.RetryRequested += async (_, _) => await NavigateAsync(_index);
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
            _scroll.Focus(FocusState.Programmatic);
            await NavigateAsync(_index);
        };
        _scroll.ViewChanged += OnViewChanged;
        _scroll.SizeChanged += (_, _) => QueueZoomRefresh();
        _image.SizeChanged += (_, _) => QueueZoomRefresh();
        _scroll.DirectManipulationStarted += (_, _) =>
        {
            if (_touchContacts.Count > 0) _gestureStartZoom = _scroll.ZoomFactor;
        };
        _scroll.DirectManipulationCompleted += (_, _) => _gestureStartZoom = null;
        _scroll.DoubleTapped += OnDoubleTapped;
        _scroll.PointerWheelChanged += OnWheel;
        _scroll.AddHandler(UIElement.PointerPressedEvent, new PointerEventHandler(OnPointerPressed), true);
        _scroll.PointerMoved += OnPointerMoved;
        _scroll.AddHandler(UIElement.PointerReleasedEvent, new PointerEventHandler(OnPointerReleased), true);
        _scroll.AddHandler(UIElement.PointerCanceledEvent, new PointerEventHandler(OnPointerReleased), true);
        _scroll.PointerCaptureLost += (_, _) => _dragging = false;
        Closed += OnClosed;
    }

    private void BuildChrome()
    {
        _viewer.SetNavigation(
            _resources.Count > 1 ? () => _ = NavigateAsync((_index + _resources.Count - 1) % _resources.Count) : null,
            _resources.Count > 1 ? () => _ = NavigateAsync((_index + 1) % _resources.Count) : null);
        _viewer.ZoomActions.Children.Add(ZoomButton("minus", "缩小", () => ZoomBy(0.8f)));
        _viewer.ZoomActions.Children.Add(_zoomLabel);
        _viewer.ZoomActions.Children.Add(ZoomButton("plus", "放大", () => ZoomBy(1.25f)));
        _viewer.ZoomActions.Children.Add(new Border
        {
            Width = 1, Height = 18, Margin = new Thickness(4, 0, 4, 0),
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
            Width = 36, Height = 36, Style = SurfaceGeometry.Style("FwIconButtonStyle"),
            Content = new LucideIcon { IconName = icon, Width = 16, Height = 16 },
        };
        AutomationProperties.SetName(button, label);
        ToolTipService.SetToolTip(button, label);
        button.Click += (_, _) => click();
        return button;
    }

    private void ConfigurePreset(ToggleButton button, string text, string label, Action click)
    {
        button.Style = SurfaceGeometry.Style("FwSegmentedChoiceStyle");
        button.Content = text;
        button.MinWidth = 48;
        AutomationProperties.SetName(button, label);
        ToolTipService.SetToolTip(button, label);
        button.Checked += (_, _) => { if (!_updatingZoomMode) click(); };
        button.Unchecked += (_, _) => { if (!_updatingZoomMode) SetZoomMode(_zoomMode); };
        _viewer.ZoomActions.Children.Add(button);
    }

    private void SetZoomControlsEnabled(bool enabled)
    {
        foreach (var control in _viewer.ZoomActions.Children.OfType<Control>()) control.IsEnabled = enabled;
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
        if (_image.Source is null || _closed) return;
        var point = args.GetCurrentPoint(_scroll);
        var delta = point.Properties.MouseWheelDelta;
        if (delta == 0) return;
        var factor = delta > 0 ? 1.12f : 1f / 1.12f;
        ZoomTo(_scroll.ZoomFactor * factor, point.Position);
        args.Handled = true;
    }

    private void ZoomBy(float factor) => ZoomTo(_scroll.ZoomFactor * factor, null);

    private void ZoomTo(float target, Point? anchor)
    {
        if (_image.Source is null) return;
        target = Math.Clamp(target, MinimumZoom, MaximumZoom);
        var old = _scroll.ZoomFactor;
        if (Math.Abs(target - old) < 0.0005) return;
        var viewportWidth = _scroll.ViewportWidth;
        var viewportHeight = _scroll.ViewportHeight;
        if (viewportWidth <= 0 || viewportHeight <= 0) return;
        var point = anchor ?? new Point(viewportWidth / 2, viewportHeight / 2);
        var horizontal = AnchorOffset(point.X, viewportWidth, _image.Width, _scroll.HorizontalOffset, old, target);
        var vertical = AnchorOffset(point.Y, viewportHeight, _image.Height, _scroll.VerticalOffset, old, target);
        SetZoomMode(ZoomModeSelection.Manual);
        _scroll.ChangeView(horizontal, vertical, target, disableAnimation: true);
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
        var viewportWidth = _scroll.ActualWidth;
        var viewportHeight = _scroll.ActualHeight;
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
        if (!point.Properties.IsLeftButtonPressed || !CanPan()) return;
        _dragging = true;
        _dragStart = point.Position;
        _dragHorizontal = _scroll.HorizontalOffset;
        _dragVertical = _scroll.VerticalOffset;
        _scroll.CapturePointer(args.Pointer);
        args.Handled = true;
    }

    private void OnPointerMoved(object sender, PointerRoutedEventArgs args)
    {
        if (!_dragging) return;
        var position = args.GetCurrentPoint(_scroll).Position;
        _scroll.ChangeView(
            _dragHorizontal - (position.X - _dragStart.X),
            _dragVertical - (position.Y - _dragStart.Y),
            null, disableAnimation: true);
        args.Handled = true;
    }

    private void OnPointerReleased(object sender, PointerRoutedEventArgs args)
    {
        if (_touchContacts.Remove(args.Pointer.PointerId)) return;
        if (!_dragging) return;
        _dragging = false;
        _scroll.ReleasePointerCapture(args.Pointer);
    }

    private bool CanPan() => _image.Source is not null &&
        (_image.Width * _scroll.ZoomFactor > _scroll.ViewportWidth + 1 ||
         _image.Height * _scroll.ZoomFactor > _scroll.ViewportHeight + 1);

    private void ToggleFullscreen()
    {
        if (_closed || _root.XamlRoot is not { } root) return;
        var focused = FocusManager.GetFocusedElement(root) as Control;
        var focusState = focused?.FocusState ?? FocusState.Programmatic;
        if (_dragging)
        {
            _dragging = false;
            _scroll.ReleasePointerCaptures();
        }
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
                _scroll.Focus(FocusState.Programmatic);
        }
    }

    private void OnKeyDown(object sender, KeyRoutedEventArgs args)
    {
        if (args.Key is VirtualKey.Left or VirtualKey.Right or VirtualKey.Up or VirtualKey.Down)
            for (var element = args.OriginalSource as DependencyObject; element is not null && element != _root;
                 element = VisualTreeHelper.GetParent(element))
                if (element is ButtonBase) return;

        switch (args.Key)
        {
            case VirtualKey.Left: if (_resources.Count > 1) _ = NavigateAsync((_index + _resources.Count - 1) % _resources.Count); break;
            case VirtualKey.Right: if (_resources.Count > 1) _ = NavigateAsync((_index + 1) % _resources.Count); break;
            case VirtualKey.Up: ZoomBy(1.25f); break;
            case VirtualKey.Down: ZoomBy(0.8f); break;
            case VirtualKey.Number0: Fit(); break;
            case VirtualKey.Number1: ActualSize(); break;
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

    private async Task NavigateAsync(int index)
    {
        var generation = ++_generation;
        _index = index;
        _imageCancellation?.Cancel();
        if (_loadTask is not null) await _loadTask;
        if (_closed || generation != _generation) return;
        var previousContent = _content; _content = null;
        if (previousContent is not null) await previousContent.DisposeAsync();
        if (_closed || generation != _generation) return;
        _imageCancellation?.Dispose();
        _imageCancellation = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
        _resource = _resources[index]; Title = _titleBar.Caption = _resource.DisplayName + " · " + AppBranding.DisplayName;
        _image.Source = null;
        _touchContacts.Clear();
        _gestureStartZoom = null;
        SetZoomMode(ZoomModeSelection.Fit);
        SetZoomControlsEnabled(false);
        _viewer.SetMetadata(_resources.Count > 1 ? $"{_index + 1}/{_resources.Count}" : "");
        _viewer.SetLoading(true, $"正在加载 {_resource.DisplayName}…");
        _zoomLabel.Text = "—";
        _loadTask = LoadAsync(generation, _imageCancellation.Token);
        await _loadTask;
    }

    private async Task LoadAsync(long generation, CancellationToken token)
    {
        var resource = _resource;
        try
        {
            var content = await _service.OpenAsync(resource, token);
            if (_closed || generation != _generation) { await content.DisposeAsync(); return; }
            _content = content;
            using var bitmap = await Task.Run(async () =>
            {
                if (content.Length > EncodedByteBudget) throw new FilewayException(new ErrorInfo(ErrorCode.ResponseTooLarge, "图片文件超过本次预览的 96 MB 上限。"));
                using var encoded = new MemoryStream();
                var buffer = new byte[64 * 1024];
                int read;
                while ((read = await content.Content.ReadAsync(buffer.AsMemory(), token)) != 0)
                {
                    if (encoded.Length + read > EncodedByteBudget) throw new FilewayException(new ErrorInfo(ErrorCode.ResponseTooLarge, "图片文件超过本次预览的 96 MB 上限。"));
                    await encoded.WriteAsync(buffer.AsMemory(0, read), token);
                }
                encoded.Position = 0;
                using var random = encoded.AsRandomAccessStream();
                var decoder = await BitmapDecoder.CreateAsync(random).AsTask(token);
                var scale = Math.Min(1, Math.Min(Math.Sqrt(PixelBudget / ((double)decoder.PixelWidth * decoder.PixelHeight)), 8192d / Math.Max(decoder.PixelWidth, decoder.PixelHeight)));
                var transform = new BitmapTransform { ScaledWidth = Math.Max(1, (uint)(decoder.PixelWidth * scale)), ScaledHeight = Math.Max(1, (uint)(decoder.PixelHeight * scale)), InterpolationMode = BitmapInterpolationMode.Fant };
                return await decoder.GetSoftwareBitmapAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied, transform, ExifOrientationMode.RespectExifOrientation, ColorManagementMode.ColorManageToSRgb).AsTask(token);
            }, token);
            token.ThrowIfCancellationRequested();
            var source = new SoftwareBitmapSource();
            await source.SetBitmapAsync(bitmap);
            if (_closed || generation != _generation || token.IsCancellationRequested) return;
            _layoutScale = Math.Min(1, LongEdgeLayoutLimit / Math.Max(bitmap.PixelWidth, bitmap.PixelHeight));
            _image.Width = bitmap.PixelWidth * _layoutScale;
            _image.Height = bitmap.PixelHeight * _layoutScale;
            _image.Source = source;
            _viewer.SetLoading(false);
            SetZoomControlsEnabled(true);
            _viewer.SetMetadata(
                $"{(_resources.Count > 1 ? $"{_index + 1}/{_resources.Count} · " : "")}{bitmap.PixelWidth}×{bitmap.PixelHeight}" +
                (content.Length > 0 ? $" · {DownloadPresentationPolicy.FormatBytes(content.Length)}" : ""));
            QueueZoomRefresh();
            if (_recentAccess is not null)
                _ = new RecentOpenRecording(_recentAccess, resource, generation).ConfirmOpenedAsync(generation, token);
        }
        catch (OperationCanceledException) { }
        catch (Exception exception)
        {
            if (_closed || generation != _generation) return;
            _viewer.SetLoading(false);
            // A decode failure is about this file, not the connection; blaming the server sends the user away.
            var detail = exception is FilewayException or System.Threading.Tasks.TaskCanceledException
                ? MainPage.ErrorMessage(exception)
                : "文件内容无法解码，可能已损坏，或不是受支持的图片格式。";
            _viewer.ShowError("图片打不开", detail);
        }
    }

    private async void OnClosed(object sender, WindowEventArgs args)
    {
        _chrome.Dispose();
        await DisposeAsync();
    }

    public async ValueTask DisposeAsync()
    {
        if (_closed) return;
        _closed = true; ++_generation; _lifetime.Cancel(); _imageCancellation?.Cancel(); _image.Source = null;
        _viewer.DetachNotice();
        if (_xamlRoot is not null) _xamlRoot.Changed -= OnXamlRootChanged;
        if (_loadTask is not null) await _loadTask;
        var content = _content; _content = null;
        if (content is not null) await content.DisposeAsync();
        _imageCancellation?.Dispose(); _lifetime.Dispose();
    }
}
