using Fileway.App.Branding;
using Fileway.App.Controls;
using Fileway.App.Windowing;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Data.Pdf;
using Windows.Storage.Streams;

namespace Fileway.App.Views;

/// <summary>
/// Read-only remote PDF viewer using the built-in Windows.Data.Pdf engine: pages render on demand
/// at 2× scale with prev/next navigation. Encrypted or oversized files are refused with explicit
/// errors; no page thumbnails, editing or text extraction is implied.
/// </summary>
public sealed class PdfWindow : Window, IAsyncDisposable
{
    public const long MaximumPdfBytes = 64L * 1024 * 1024;
    public const uint MaximumPages = 500;
    private const int RenderScalePercent = 200;

    private readonly IFileBytesService _bytes;
    private readonly WindowTitleBar _titleBar = new();
    private readonly FramelessWindowChrome _chrome;
    private readonly RemoteResourceRef _resource;
    private readonly CancellationTokenSource _lifetime = new();
    private readonly Image _page = new() { Stretch = Stretch.Uniform, HorizontalAlignment = HorizontalAlignment.Center };
    private readonly ScrollViewer _scroll = new()
    {
        ZoomMode = ZoomMode.Enabled, MinZoomFactor = 0.2f, MaxZoomFactor = 5,
        HorizontalScrollBarVisibility = ScrollBarVisibility.Auto, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
    };
    private readonly InfoBar _notice = new() { IsOpen = true, IsClosable = false, Message = "正在打开 PDF…" };
    private readonly ProgressRing _progress = new() { IsActive = true, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
    private readonly TextBlock _pageLabel = new() { FontSize = 13, VerticalAlignment = VerticalAlignment.Center, Opacity = 0.8 };
    private PdfDocument? _document;
    private uint _index;
    private bool _closed;
    private bool _started;
    private bool _rendering;

    public PdfWindow(IFileBytesService bytes, RemoteResourceRef resource)
    {
        _bytes = bytes;
        _resource = resource;
        Title = _titleBar.Caption = resource.DisplayName + " · " + AppBranding.DisplayName;
        SystemBackdrop = new MicaBackdrop();
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"));
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1000, 760));

        var commands = new CommandBar { DefaultLabelPosition = CommandBarDefaultLabelPosition.Right, Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent) };
        var previous = new AppBarButton { Label = "上一页", Icon = new SymbolIcon(Symbol.Back) };
        var next = new AppBarButton { Label = "下一页", Icon = new SymbolIcon(Symbol.Forward) };
        var fit = new AppBarButton { Label = "适应窗口", Icon = new SymbolIcon(Symbol.FourBars) };
        var actual = new AppBarButton { Label = "100%", Icon = new SymbolIcon(Symbol.Zoom) };
        previous.Click += (_, _) => _ = NavigateAsync(_index > 0 ? _index - 1 : _index);
        next.Click += (_, _) => _ = NavigateAsync(_document is { } document && _index + 1 < document.PageCount ? _index + 1 : _index);
        fit.Click += (_, _) => _scroll.ChangeView(0, 0, 1);
        actual.Click += (_, _) => _scroll.ChangeView(0, 0, 1);
        commands.PrimaryCommands.Add(previous);
        commands.PrimaryCommands.Add(next);
        commands.PrimaryCommands.Add(new AppBarButton { Label = "页码" });
        commands.PrimaryCommands.Add(fit);
        commands.PrimaryCommands.Add(actual);
        // Replace the inert 页码 button with the live label in the same slot.
        commands.PrimaryCommands[^2] = new AppBarButton { IsEnabled = false, Content = _pageLabel };

        var root = new Grid { Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent), Padding = new Thickness(20), RowSpacing = 12 };
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition());
        root.Children.Add(commands);
        Grid.SetRow(_notice, 1);
        root.Children.Add(_notice);
        var surface = new Grid();
        Grid.SetRow(surface, 2);
        _scroll.Content = _page;
        surface.Children.Add(_scroll);
        surface.Children.Add(_progress);
        root.Children.Add(surface);

        var windowRoot = new Grid();
        windowRoot.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        windowRoot.RowDefinitions.Add(new RowDefinition());
        windowRoot.Children.Add(_titleBar);
        Grid.SetRow(root, 1);
        windowRoot.Children.Add(root);
        Content = windowRoot;
        _chrome = new FramelessWindowChrome(this, _titleBar);
        root.Loaded += async (_, _) => { if (_started) return; _started = true; await LoadAsync(); };
        Closed += (_, _) =>
        {
            _closed = true;
            _lifetime.Cancel();
            _chrome.Dispose();
            _lifetime.Dispose();
        };
    }

    public ValueTask DisposeAsync()
    {
        if (!_closed) { _closed = true; _lifetime.Cancel(); _chrome.Dispose(); _lifetime.Dispose(); }
        return ValueTask.CompletedTask;
    }

    public void SetTheme(ElementTheme theme)
    {
        if (!_closed && Content is FrameworkElement root) root.RequestedTheme = theme;
    }

    private async Task LoadAsync()
    {
        try
        {
            var content = await _bytes.OpenAsync(_resource, MaximumPdfBytes, _lifetime.Token);
            if (_closed) return;
            using var stream = new MemoryStream(content.Bytes) { Position = 0 };
            var random = stream.AsRandomAccessStream();
            _document = await PdfDocument.LoadFromStreamAsync(random);
            if (_closed) return;
            if (_document.IsPasswordProtected)
            {
                _notice.Severity = InfoBarSeverity.Informational;
                _notice.Message = "此 PDF 受密码保护，暂不支持在预览中打开。";
                _progress.IsActive = false;
                return;
            }
            if (_document.PageCount is 0)
            {
                _notice.Severity = InfoBarSeverity.Informational;
                _notice.Message = "此 PDF 没有可显示的页面。";
                _progress.IsActive = false;
                return;
            }
            if (_document.PageCount > MaximumPages)
            {
                _notice.Severity = InfoBarSeverity.Informational;
                _notice.Message = $"此 PDF 有 {_document.PageCount} 页，超过 {MaximumPages} 页的预览上限，请下载后查看。";
                _progress.IsActive = false;
                return;
            }
            _notice.IsOpen = false;
            await RenderAsync(0);
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (_closed) return;
            _progress.IsActive = false;
            _notice.Severity = InfoBarSeverity.Error;
            _notice.Message = error is FilewayException fileway ? fileway.Error.Message : "PDF 未能打开，文件可能损坏或格式不受支持。";
        }
    }

    private async Task NavigateAsync(uint index)
    {
        if (_document is null || _rendering || index == _index && _page.Source is not null) return;
        await RenderAsync(index);
    }

    private async Task RenderAsync(uint index)
    {
        if (_document is not { } document || _closed || _rendering) return;
        _rendering = true;
        _index = index;
        _pageLabel.Text = $"第 {index + 1} / {document.PageCount} 页";
        _progress.IsActive = true;
        _progress.Visibility = Visibility.Visible;
        try
        {
            using var page = document.GetPage(index);
            var size = page.Size;
            var options = new PdfPageRenderOptions
            {
                DestinationWidth = (uint)Math.Clamp((int)(size.Width * RenderScalePercent / 100.0), 64, 4096),
            };
            using var output = new InMemoryRandomAccessStream();
            await page.RenderToStreamAsync(output, options);
            var bitmap = new BitmapImage();
            await bitmap.SetSourceAsync(output);
            if (_closed || _index != index) return;
            _page.Source = bitmap;
            _scroll.ChangeView(0, 0, 1, disableAnimation: true);
        }
        catch (OperationCanceledException) { }
        catch (Exception)
        {
            if (!_closed) _pageLabel.Text = $"第 {index + 1} 页渲染失败";
        }
        finally
        {
            _rendering = false;
            if (!_closed) { _progress.IsActive = false; _progress.Visibility = Visibility.Collapsed; }
        }
    }
}
