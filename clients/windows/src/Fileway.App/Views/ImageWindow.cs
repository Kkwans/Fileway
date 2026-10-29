using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.App.Branding;
using Fileway.App.Controls;
using Fileway.App.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
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
    };
    private readonly Grid _canvas = new() { HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
    private readonly Image _image = new() { Stretch = Stretch.Uniform };
    private readonly TextBlock _zoomLabel = new() { FontSize = 13, MinWidth = 44, TextAlignment = TextAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Foreground = (Brush)Application.Current.Resources["FwScrimTextBrush"] };
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
        // Photography surfaces stay dark in both themes; only the window chrome follows the app theme.
        _viewer.Background = new SolidColorBrush(Windows.UI.Color.FromArgb(255, 11, 12, 15));
        AutomationProperties.SetAutomationId(_scroll, "ImageViewport");
        AutomationProperties.SetName(_scroll, "图片画布");
        AutomationProperties.SetAutomationId(_image, "ImageView");
        AutomationProperties.SetName(_image, "图片");

        BuildChrome();

        var windowRoot = new Grid
        {
            RequestedTheme = ElementTheme.Dark,
            Background = new SolidColorBrush(Windows.UI.Color.FromArgb(255, 11, 12, 15)),
        };
        windowRoot.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        windowRoot.RowDefinitions.Add(new RowDefinition());
        windowRoot.Children.Add(_titleBar);
        Grid.SetRow(_viewer, 1);
        windowRoot.Children.Add(_viewer);
        Content = windowRoot;
        _chrome = new FramelessWindowChrome(this, _titleBar);
        windowRoot.KeyDown += OnKeyDown;
        windowRoot.IsTabStop = true;
        _viewer.RetryRequested += async (_, _) => await NavigateAsync(_index);
        windowRoot.Loaded += async (_, _) =>
        {
            if (_started) return;
            _started = true;
            windowRoot.Focus(FocusState.Programmatic);
            await NavigateAsync(_index);
        };
        _scroll.ViewChanged += (_, _) => UpdateZoomLabel();
        _scroll.DoubleTapped += OnDoubleTapped;
        _scroll.Tapped += OnTapped;
        _scroll.PointerWheelChanged += OnWheel;
        _scroll.PointerPressed += OnPointerPressed;
        _scroll.PointerMoved += OnPointerMoved;
        _scroll.PointerReleased += OnPointerReleased;
        _scroll.PointerCaptureLost += (_, _) => _dragging = false;
        Closed += OnClosed;
    }

    private void BuildChrome()
    {
        _viewer.SetNavigation(
            _resources.Count > 1 ? () => _ = NavigateAsync((_index + _resources.Count - 1) % _resources.Count) : null,
            _resources.Count > 1 ? () => _ = NavigateAsync((_index + 1) % _resources.Count) : null);
        _viewer.BottomActions.Children.Add(ViewerChrome.LabelButton("−", "缩小", () => ZoomBy(0.8f)));
        _viewer.BottomActions.Children.Add(_zoomLabel);
        _viewer.BottomActions.Children.Add(ViewerChrome.LabelButton("+", "放大", () => ZoomBy(1.25f)));
        _viewer.BottomActions.Children.Add(Separator());
        _viewer.BottomActions.Children.Add(ViewerChrome.LabelButton("适应", "适应窗口 (0)", Fit));
        _viewer.BottomActions.Children.Add(ViewerChrome.LabelButton("1:1", "实际大小 (1)", ActualSize));
        AutomationProperties.SetAutomationId(_zoomLabel, "ViewerZoomLabel");
    }

    private static Border Separator() => new()
    {
        Width = 1, Height = 18, Margin = new Thickness(4, 0, 4, 0),
        Background = (Brush)Application.Current.Resources["FwStrokeStrongBrush"],
        VerticalAlignment = VerticalAlignment.Center,
    };

    private void UpdateZoomLabel()
    {
        if (_image.Width <= 0) { _zoomLabel.Text = "—"; return; }
        _zoomLabel.Text = $"{(int)Math.Round(_scroll.ZoomFactor * 100)}%";
    }

    private void OnWheel(object sender, PointerRoutedEventArgs args)
    {
        if (_image.Width <= 0 || _closed) return;
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
        if (_image.Width <= 0) return;
        target = Math.Clamp(target, MinimumZoom, MaximumZoom);
        var old = _scroll.ZoomFactor;
        if (Math.Abs(target - old) < 0.0005) return;
        var viewportWidth = _scroll.ViewportWidth;
        var viewportHeight = _scroll.ViewportHeight;
        if (viewportWidth <= 0 || viewportHeight <= 0) return;
        var point = anchor ?? new Point(viewportWidth / 2, viewportHeight / 2);
        var contentX = (_scroll.HorizontalOffset + point.X) / old;
        var contentY = (_scroll.VerticalOffset + point.Y) / old;
        _scroll.ChangeView(contentX * target - point.X, contentY * target - point.Y, target);
    }

    private void Fit()
    {
        if (_image.Width <= 0 || _scroll.ViewportWidth <= 0 || _scroll.ViewportHeight <= 0) return;
        var zoom = (float)Math.Clamp(Math.Min(
            (_scroll.ViewportWidth - 32) / _image.Width,
            (_scroll.ViewportHeight - 32) / _image.Height), MinimumZoom, 1d);
        CenterAt(zoom);
    }

    private void ActualSize() => CenterAt(1f);

    private void CenterAt(float zoom)
    {
        var width = _image.Width * zoom;
        var height = _image.Height * zoom;
        var horizontal = Math.Max(0, (width - _scroll.ViewportWidth) / 2);
        var vertical = Math.Max(0, (height - _scroll.ViewportHeight) / 2);
        _scroll.ChangeView(horizontal, vertical, zoom);
    }

    private void OnDoubleTapped(object sender, DoubleTappedRoutedEventArgs args)
    {
        if (_image.Width <= 0) return;
        args.Handled = true;
        if (_scroll.ZoomFactor > 1.02f) Fit();
        else
        {
            var point = args.GetPosition(_scroll);
            ZoomTo(2f, point);
        }
    }

    private void OnTapped(object sender, TappedRoutedEventArgs args)
    {
        if (_dragging) return;
        _viewer.ToggleChrome();
    }

    private void OnPointerPressed(object sender, PointerRoutedEventArgs args)
    {
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
        if (!_dragging) return;
        _dragging = false;
        _scroll.ReleasePointerCapture(args.Pointer);
    }

    private bool CanPan() =>
        _image.Width * _scroll.ZoomFactor > _scroll.ViewportWidth + 1 ||
        _image.Height * _scroll.ZoomFactor > _scroll.ViewportHeight + 1;

    private void OnKeyDown(object sender, KeyRoutedEventArgs args)
    {
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
            case VirtualKey.Escape: Close(); break;
            default: return;
        }
        args.Handled = true;
    }

    public void SetTheme(ElementTheme theme)
    {
        // Image surfaces are intentionally theme-independent (always dark).
        if (!_closed) _viewer.RequestedTheme = ElementTheme.Dark;
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
        _viewer.SetTitle(_resource.DisplayName, _resources.Count > 1 ? $"{_index + 1} / {_resources.Count}" : "");
        _viewer.ClearError();
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
            _image.Source = source;
            _image.Width = bitmap.PixelWidth;
            _image.Height = bitmap.PixelHeight;
            _viewer.SetLoading(false);
            _viewer.SetTitle(_resource.DisplayName,
                $"{(_resources.Count > 1 ? $"{_index + 1} / {_resources.Count} · " : "")}{bitmap.PixelWidth} × {bitmap.PixelHeight}" +
                (content.Length > 0 ? $" · {DownloadPresentationPolicy.FormatBytes(content.Length)}" : ""));
            DispatcherQueue.TryEnqueue(() => { Fit(); UpdateZoomLabel(); });
            _viewer.RestartIdle();
            if (_recentAccess is not null)
                _ = new RecentOpenRecording(_recentAccess, resource, generation).ConfirmOpenedAsync(generation, token);
        }
        catch (OperationCanceledException) { }
        catch (Exception exception)
        {
            if (_closed || generation != _generation) return;
            _viewer.SetLoading(false);
            _viewer.ShowError("图片打不开", MainPage.ErrorMessage(exception));
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
        if (_loadTask is not null) await _loadTask;
        var content = _content; _content = null;
        if (content is not null) await content.DisposeAsync();
        _imageCancellation?.Dispose(); _lifetime.Dispose();
    }
}
