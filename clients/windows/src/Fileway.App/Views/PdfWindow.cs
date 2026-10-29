using Fileway.App.Branding;
using Fileway.App.Controls;
using Fileway.App.Windowing;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Data.Pdf;
using Windows.Storage.Streams;
using Windows.System;

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
    private Button? _previousButton;
    private Button? _nextButton;
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

        _pageLabel.MinWidth = 96;
        _pageLabel.TextAlignment = TextAlignment.Center;
        _pageLabel.Foreground = (Brush)Application.Current.Resources["FwTextPrimaryBrush"];
        _page.Margin = new Thickness(24);

        var root = new Grid { Padding = new Thickness(20, 12, 20, 20), RowSpacing = 12 };
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition());
        root.Children.Add(_notice);

        var surfaceGrid = new Grid();
        _scroll.Content = _page;
        surfaceGrid.Children.Add(_scroll);
        surfaceGrid.Children.Add(_progress);
        surfaceGrid.Children.Add(BuildToolbar());
        var surface = new Border
        {
            CornerRadius = new CornerRadius(12),
            Background = (Brush)Application.Current.Resources["FwCanvasBrush"],
            BorderBrush = (Brush)Application.Current.Resources["FwStrokeBrush"],
            BorderThickness = new Thickness(1),
            Child = surfaceGrid,
        };
        Grid.SetRow(surface, 1);
        root.Children.Add(surface);

        var windowRoot = new Grid();
        windowRoot.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        windowRoot.RowDefinitions.Add(new RowDefinition());
        windowRoot.Children.Add(_titleBar);
        Grid.SetRow(root, 1);
        windowRoot.Children.Add(root);
        AddNavigationAccelerator(windowRoot, VirtualKey.Left, -1);
        AddNavigationAccelerator(windowRoot, VirtualKey.Right, +1);
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

    private Border BuildToolbar()
    {
        var previous = _previousButton = ToolbarIcon("arrow-left", "上一页", false, () => _ = NavigateAsync(_index > 0 ? _index - 1 : _index));
        var next = _nextButton = ToolbarIcon("arrow-left", "下一页", true,
            () => _ = NavigateAsync(_document is { } document && _index + 1 < document.PageCount ? _index + 1 : _index));
        previous.IsEnabled = next.IsEnabled = false;
        var bar = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 2, VerticalAlignment = VerticalAlignment.Center };
        bar.Children.Add(previous);
        bar.Children.Add(_pageLabel);
        bar.Children.Add(next);
        bar.Children.Add(new Border
        {
            Width = 1, Height = 18, Margin = new Thickness(6, 0, 6, 0),
            Background = (Brush)Application.Current.Resources["FwStrokeBrush"],
        });
        bar.Children.Add(ToolbarText("适应窗口", "缩放至适应窗口宽度", FitWidth));
        bar.Children.Add(ToolbarText("100%", "原始大小", () => _scroll.ChangeView(null, null, 1)));
        var pill = new Border
        {
            Style = (Style)Application.Current.Resources["FwPillStyle"],
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Bottom,
            Margin = new Thickness(0, 0, 0, 16),
            Child = bar,
        };
        Canvas.SetZIndex(pill, 10);
        return pill;
    }

    private void FitWidth()
    {
        if (_page.Source is not BitmapImage { PixelWidth: > 0 } bitmap)
        {
            _scroll.ChangeView(null, null, 1);
            return;
        }
        var width = Math.Max(0, _scroll.ViewportWidth - _page.Margin.Left - _page.Margin.Right);
        var zoom = width > 0 ? width / bitmap.PixelWidth : 1;
        _scroll.ChangeView(null, null, (float)Math.Clamp(zoom, _scroll.MinZoomFactor, _scroll.MaxZoomFactor), true);
    }

    private void AddNavigationAccelerator(UIElement host, VirtualKey key, int delta)
    {
        var accelerator = new KeyboardAccelerator { Key = key };
        accelerator.Invoked += (sender, args) =>
        {
            args.Handled = true;
            if (_document is not { } document) return;
            var target = (long)_index + delta;
            if (target < 0 || target >= document.PageCount) return;
            _ = NavigateAsync((uint)target);
        };
        host.KeyboardAccelerators.Add(accelerator);
    }

    private static Button ToolbarIcon(string icon, string tooltip, bool mirror, Action click)
    {
        var glyph = new LucideIcon
        {
            IconName = icon, Width = 18, Height = 18,
            Foreground = (Brush)Application.Current.Resources["FwTextPrimaryBrush"],
        };
        if (mirror) glyph.RenderTransform = new RotateTransform { Angle = 180, CenterX = 9, CenterY = 9 };
        var button = new Button
        {
            Style = (Style)Application.Current.Resources["FwIconButtonStyle"],
            Width = 34, Height = 34, Content = glyph,
        };
        ToolTipService.SetToolTip(button, tooltip);
        AutomationProperties.SetName(button, tooltip);
        button.Click += (_, _) => click();
        return button;
    }

    private static Button ToolbarText(string text, string tooltip, Action click)
    {
        var button = new Button
        {
            Style = (Style)Application.Current.Resources["FwTextIconButtonStyle"],
            Foreground = (Brush)Application.Current.Resources["FwTextPrimaryBrush"],
            Content = new TextBlock { Text = text, FontSize = 13 },
        };
        ToolTipService.SetToolTip(button, tooltip);
        AutomationProperties.SetName(button, tooltip);
        button.Click += (_, _) => click();
        return button;
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
            // Fit after the first page has been laid out so the viewport width is real.
            DispatcherQueue.TryEnqueue(() => { if (!_closed) FitWidth(); });
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
        if (_previousButton is not null) _previousButton.IsEnabled = index > 0;
        if (_nextButton is not null) _nextButton.IsEnabled = index + 1 < document.PageCount;
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
