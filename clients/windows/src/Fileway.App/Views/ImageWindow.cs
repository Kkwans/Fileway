using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.App.Branding;
using Fileway.App.Controls;
using Fileway.App.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Graphics.Imaging;

namespace Fileway.App.Views;

/// <summary>A stream-owned native image viewer. Encoded input and decoded pixels are bounded independently.</summary>
public sealed class ImageWindow : Window, IAsyncDisposable
{
    private const long EncodedByteBudget = 96L * 1024 * 1024;
    private const double PixelBudget = 16_000_000;
    private readonly IImageContentService _service;
    private readonly IRecentAccessService? _recentAccess;
    private readonly WindowTitleBar _titleBar = new();
    private readonly FramelessWindowChrome _chrome;
    private RemoteResourceRef _resource;
    private readonly IReadOnlyList<RemoteResourceRef> _resources;
    private readonly CancellationTokenSource _lifetime = new();
    private readonly Image _image = new() { Stretch = Stretch.Uniform };
    private readonly ScrollViewer _scroll = new() { ZoomMode = ZoomMode.Enabled, MinZoomFactor = 0.1f, MaxZoomFactor = 8, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
    private readonly InfoBar _notice = new() { IsOpen = true, IsClosable = false, Message = "正在加载图片…" };
    private readonly ProgressRing _progress = new() { IsActive = true, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
    private IImageContent? _content;
    private bool _closed;
    private bool _started;
    private Task? _loadTask;
    private CancellationTokenSource? _imageCancellation;
    private long _generation;
    private int _index;

    public ImageWindow(IImageContentService service, RemoteResourceRef resource, IReadOnlyList<RemoteResourceRef>? resources = null,
        IRecentAccessService? recentAccess = null)
    {
        _recentAccess = recentAccess;
        _service = service; _resource = resource; Title = _titleBar.Caption = resource.DisplayName + " · " + AppBranding.DisplayName;
        _resources = resources is { Count: > 0 } ? resources : [resource];
        _index = _resources.ToList().FindIndex(item => item == resource);
        if (_index < 0) _index = 0;
        SystemBackdrop = new MicaBackdrop();
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"));
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1000, 760));
        var root = new Grid { Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent), Padding = new Thickness(20), RowSpacing = 12 };
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); root.RowDefinitions.Add(new RowDefinition());
        var commands = new CommandBar { DefaultLabelPosition = CommandBarDefaultLabelPosition.Right, Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent) };
        var fit = new AppBarButton { Label = "适应窗口", Icon = new SymbolIcon(Symbol.FourBars) };
        var actual = new AppBarButton { Label = "100%", Icon = new SymbolIcon(Symbol.Zoom) };
        var zoomOut = new AppBarButton { Label = "缩小", Icon = new SymbolIcon(Symbol.ZoomOut) };
        var zoomIn = new AppBarButton { Label = "放大", Icon = new SymbolIcon(Symbol.ZoomIn) };
        var previous = new AppBarButton { Label = "上一张", Icon = new SymbolIcon(Symbol.Back), IsEnabled = _resources.Count > 1 };
        var next = new AppBarButton { Label = "下一张", Icon = new SymbolIcon(Symbol.Forward), IsEnabled = _resources.Count > 1 };
        previous.Click += async (_, _) => await NavigateAsync((_index + _resources.Count - 1) % _resources.Count);
        next.Click += async (_, _) => await NavigateAsync((_index + 1) % _resources.Count);
        fit.Click += (_, _) => Fit(); actual.Click += (_, _) => _scroll.ChangeView(0, 0, 1);
        zoomOut.Click += (_, _) => _scroll.ChangeView(null, null, Math.Max(0.1f, _scroll.ZoomFactor / 1.25f));
        zoomIn.Click += (_, _) => _scroll.ChangeView(null, null, Math.Min(8, _scroll.ZoomFactor * 1.25f));
        commands.PrimaryCommands.Add(previous); commands.PrimaryCommands.Add(next); commands.PrimaryCommands.Add(fit); commands.PrimaryCommands.Add(actual); commands.PrimaryCommands.Add(zoomOut); commands.PrimaryCommands.Add(zoomIn);
        root.Children.Add(commands); Grid.SetRow(_notice, 1); root.Children.Add(_notice);
        var surface = new Grid(); Grid.SetRow(surface, 2); root.Children.Add(surface);
        _scroll.Content = _image; surface.Children.Add(_scroll); surface.Children.Add(_progress);
        var windowRoot = new Grid();
        windowRoot.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        windowRoot.RowDefinitions.Add(new RowDefinition());
        windowRoot.Children.Add(_titleBar);
        Grid.SetRow(root, 1); windowRoot.Children.Add(root);
        Content = windowRoot;
        _chrome = new FramelessWindowChrome(this, _titleBar);
        root.Loaded += async (_, _) => { if (_started) return; _started = true; await NavigateAsync(_index); };
        Closed += OnClosed;
    }

    public void SetTheme(ElementTheme theme)
    {
        if (!_closed && Content is FrameworkElement root) root.RequestedTheme = theme;
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
        _image.Source = null; _progress.IsActive = true; _progress.Visibility = Visibility.Visible;
        _notice.Severity = InfoBarSeverity.Informational; _notice.Message = "正在加载图片…";
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
            _image.Source = source; _image.Width = bitmap.PixelWidth; _image.Height = bitmap.PixelHeight;
            _notice.Message = $"{resource.DisplayName} · {bitmap.PixelWidth} × {bitmap.PixelHeight} · Ctrl + 滚轮可缩放";
            _notice.Severity = InfoBarSeverity.Informational;
            Fit();
            if (_recentAccess is not null)
                _ = new RecentOpenRecording(_recentAccess, resource, generation).ConfirmOpenedAsync(generation, token);
        }
        catch (OperationCanceledException) { if (!_closed && generation == _generation) _notice.Message = "图片加载已取消。"; }
        catch (Exception exception) { if (!_closed && generation == _generation) { _notice.Message = MainPage.ErrorMessage(exception); _notice.Severity = InfoBarSeverity.Error; } }
        finally { if (generation == _generation) { _progress.IsActive = false; _progress.Visibility = Visibility.Collapsed; } }
    }

    private void Fit()
    {
        if (_image.Width <= 0 || _scroll.ViewportWidth <= 0 || _scroll.ViewportHeight <= 0) return;
        _scroll.ChangeView(0, 0, (float)Math.Clamp(Math.Min(_scroll.ViewportWidth / _image.Width, _scroll.ViewportHeight / _image.Height), 0.1, 8));
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
