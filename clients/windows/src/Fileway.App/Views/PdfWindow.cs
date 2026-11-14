using Fileway.App.Branding;
using Fileway.App.Controls;
using Fileway.App.Windowing;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Data.Pdf;
using Windows.Storage.Streams;
using Windows.System;

namespace Fileway.App.Views;

/// <summary>Read-only PDF preview. The visible page changes only after its bitmap is decoded.</summary>
public sealed class PdfWindow : Window, IAsyncDisposable
{
    public const long MaximumPdfBytes = 64L * 1024 * 1024;
    public const uint MaximumPages = 500;
    private const double MaximumRenderEdge = 4096;
    private const double MaximumLayoutEdge = 2400;

    private enum ViewZoom { FitWidth, ActualSize, Manual }

    private readonly IFileBytesService _bytes;
    private readonly RemoteResourceRef _resource;
    private readonly WindowTitleBar _titleBar = new();
    private readonly FramelessWindowChrome _chrome;
    private readonly CancellationTokenSource _lifetime = new();
    private readonly Image _page = new()
    {
        Stretch = Stretch.Uniform, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Top,
    };
    private readonly ScrollViewer _scroll = new()
    {
        ZoomMode = ZoomMode.Enabled, MinZoomFactor = 0.1f, MaxZoomFactor = 8,
        HorizontalScrollBarVisibility = ScrollBarVisibility.Auto, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
        IsTabStop = true,
        FocusVisualPrimaryThickness = new Thickness(1), FocusVisualSecondaryThickness = new Thickness(0),
    };
    private readonly InfoBar _notice = new() { IsOpen = false, IsClosable = false, Visibility = Visibility.Collapsed };
    private readonly Button _retry = new() { Content = "重试", Style = SurfaceGeometry.Style("FwSecondaryButtonStyle") };
    private readonly ProgressRing _progress = new()
    {
        Width = 32, Height = 32, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
        Visibility = Visibility.Collapsed,
    };
    private readonly TextBlock _pageLabel = new()
    {
        MinWidth = 100, TextAlignment = TextAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
        Style = SurfaceGeometry.Style("FwToolbarTextStyle"),
    };
    private readonly Button _previous;
    private readonly Button _next;
    private readonly ToggleButton _fit;
    private readonly ToggleButton _actual;
    private readonly HashSet<uint> _touchContacts = [];
    private PdfDocument? _document;
    private MemoryStream? _sourceBytes;
    private IRandomAccessStream? _sourceStream;
    private Task _work = Task.CompletedTask;
    private Task? _shutdown;
    private uint? _visibleIndex;
    private uint? _failedIndex;
    private ViewZoom _zoom = ViewZoom.FitWidth;
    private double _layoutScale = 1;
    private double _manualScale = 1;
    private float? _touchStartZoom;
    private bool _started;
    private bool _closed;
    private bool _rendering;
    private bool _updatingZoom;
    private bool _zoomQueued;
    private bool _resetScrollPending;

    public PdfWindow(IFileBytesService bytes, RemoteResourceRef resource)
    {
        _bytes = bytes;
        _resource = resource;
        Title = _titleBar.Caption = resource.DisplayName + " · " + AppBranding.DisplayName;
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"));
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1000, 760));

        _previous = NavigationButton("PdfPrevious", "上一页", false, () => Step(-1));
        _next = NavigationButton("PdfNext", "下一页", true, () => Step(1));
        _fit = ZoomButton("PdfFitWidth", "适宽", "缩放至适应窗口宽度", ViewZoom.FitWidth);
        _actual = ZoomButton("PdfActualSize", "100%", "原始大小", ViewZoom.ActualSize);
        AutomationProperties.SetAutomationId(_scroll, "PdfViewport");
        AutomationProperties.SetName(_scroll, "PDF 阅读区");
        AutomationProperties.SetAutomationId(_page, "PdfPageImage");
        AutomationProperties.SetName(_page, "PDF 页面");
        AutomationProperties.SetAutomationId(_pageLabel, "PdfPageLabel");
        AutomationProperties.SetAutomationId(_notice, "PdfNotice");
        AutomationProperties.SetAutomationId(_retry, "PdfRetryRender");
        _retry.Click += (_, _) => { if (_failedIndex is { } index) StartRender(index); };
        _notice.ActionButton = _retry;
        FloatingNotice.Attach(_notice, automatic: false);

        var viewport = new Grid { Margin = new Thickness(12, 8, 12, 0) };
        _scroll.Content = _page;
        viewport.Children.Add(_scroll);
        viewport.Children.Add(_progress);

        var root = new Grid { Style = SurfaceGeometry.Style("FwViewerCanvasStyle"), IsTabStop = true };
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition());
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.Children.Add(_titleBar);
        Grid.SetRow(_notice, 1); root.Children.Add(_notice);
        Grid.SetRow(viewport, 2); root.Children.Add(viewport);
        var toolbar = BuildToolbar();
        Grid.SetRow(toolbar, 3); root.Children.Add(toolbar);

        Content = root;
        _chrome = new FramelessWindowChrome(this, _titleBar);
        root.PreviewKeyDown += OnPreviewKeyDown;
        root.Loaded += async (_, _) =>
        {
            if (_started || _closed) return;
            _started = true;
            FocusViewport();
            _work = LoadAsync();
            await _work;
            if (_closed) return;
            DispatcherQueue.TryEnqueue(Microsoft.UI.Dispatching.DispatcherQueuePriority.Low, () =>
            {
                if (_closed || root.XamlRoot is not { } xamlRoot) return;
                var focused = FocusManager.GetFocusedElement(xamlRoot);
                if (focused is null || ReferenceEquals(focused, root) || focused is FrameworkElement { Name: "MinimizeButton" })
                    FocusViewport();
            });
        };
        _scroll.SizeChanged += (_, _) => QueueZoom();
        _page.SizeChanged += (_, _) => QueueZoom();
        _scroll.ViewChanged += (_, _) =>
        {
            if (_touchStartZoom is { } start && Math.Abs(start - _scroll.ZoomFactor) > 0.0005)
            {
                _manualScale = _scroll.ZoomFactor * _layoutScale;
                SetZoomMode(ViewZoom.Manual);
            }
        };
        _scroll.DirectManipulationStarted += (_, _) =>
        {
            if (_touchContacts.Count > 0) _touchStartZoom = _scroll.ZoomFactor;
        };
        _scroll.DirectManipulationCompleted += (_, _) => _touchStartZoom = null;
        _scroll.AddHandler(UIElement.PointerPressedEvent, new PointerEventHandler(OnPointerPressed), true);
        _scroll.AddHandler(UIElement.PointerReleasedEvent, new PointerEventHandler(OnPointerReleased), true);
        _scroll.AddHandler(UIElement.PointerCanceledEvent, new PointerEventHandler(OnPointerReleased), true);
        _page.PointerWheelChanged += OnWheel;
        _scroll.AddHandler(UIElement.PointerWheelChangedEvent, new PointerEventHandler(OnWheel), true);
        Closed += OnClosed;
        UpdateCommands();
    }

    private void OnClosed(object sender, WindowEventArgs args) => _ = BeginShutdown();

    internal void CloseIfOpen()
    {
        if (!_closed) Close();
    }

    public ValueTask DisposeAsync() => new(BeginShutdown());

    private Task BeginShutdown()
    {
        if (_shutdown is not null) return _shutdown;
        _closed = true;
        _lifetime.Cancel();
        FloatingNotice.Detach(_notice);
        _chrome.Dispose();
        return _shutdown = ReleaseDocumentAsync();
    }

    private async Task ReleaseDocumentAsync()
    {
        // WinRT can continue using the input during an outstanding native render.
        try { await _work; }
        finally
        {
            _document = null;
            try { _sourceStream?.Dispose(); }
            finally
            {
                _sourceStream = null;
                _sourceBytes?.Dispose();
                _sourceBytes = null;
                _lifetime.Dispose();
            }
        }
    }

    public void SetTheme(ElementTheme theme)
    {
        if (!_closed && Content is FrameworkElement root) root.RequestedTheme = theme;
    }

    private Border BuildToolbar()
    {
        var bar = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4, VerticalAlignment = VerticalAlignment.Center };
        bar.Children.Add(_previous);
        bar.Children.Add(_pageLabel);
        bar.Children.Add(_next);
        bar.Children.Add(new Border
        {
            Width = 1, Height = 18, Margin = new Thickness(4, 0, 4, 0),
            VerticalAlignment = VerticalAlignment.Center, Style = SurfaceGeometry.Style("FwDividerStrongStyle"),
        });
        bar.Children.Add(_fit);
        bar.Children.Add(_actual);
        var pill = new Border
        {
            Style = SurfaceGeometry.Style("FwPillStyle"), Padding = new Thickness(4),
            HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 6, 0, 8), Child = bar,
        };
        SurfaceStyles.SetUsePopupMaterial(pill, true);
        return pill;
    }

    private static Button NavigationButton(string id, string label, bool next, Action click)
    {
        var glyph = new LucideIcon
        {
            IconName = "arrow-left", Width = 18, Height = 18,
            Style = SurfaceGeometry.Style("FwPrimaryIconStyle"),
        };
        if (next) glyph.RenderTransform = new RotateTransform { Angle = 180, CenterX = 9, CenterY = 9 };
        var button = new Button
        {
            Style = SurfaceGeometry.Style("FwIconButtonStyle"), Width = 36, Height = 36, Content = glyph,
        };
        AutomationProperties.SetAutomationId(button, id);
        AutomationProperties.SetName(button, label);
        ToolTipService.SetToolTip(button, label);
        button.Click += (_, _) => click();
        return button;
    }

    private ToggleButton ZoomButton(string id, string text, string label, ViewZoom mode)
    {
        var button = new ToggleButton { Content = text, MinWidth = 52, Style = SurfaceGeometry.Style("FwSegmentedChoiceStyle") };
        AutomationProperties.SetAutomationId(button, id);
        AutomationProperties.SetName(button, label);
        ToolTipService.SetToolTip(button, label);
        button.Checked += (_, _) => { if (!_updatingZoom) SelectZoom(mode); };
        button.Unchecked += (_, _) => { if (!_updatingZoom) SetZoomMode(_zoom); };
        return button;
    }

    private void SelectZoom(ViewZoom mode)
    {
        if (_closed || _visibleIndex is null) return;
        SetZoomMode(mode);
        QueueZoom();
    }

    private void SetZoomMode(ViewZoom mode)
    {
        _zoom = mode;
        _updatingZoom = true;
        try
        {
            _fit.IsChecked = mode == ViewZoom.FitWidth;
            _actual.IsChecked = mode == ViewZoom.ActualSize;
        }
        finally { _updatingZoom = false; }
    }

    private void QueueZoom(bool resetScroll = false)
    {
        _resetScrollPending |= resetScroll;
        if (_zoomQueued || _closed || _page.Source is null || _zoom == ViewZoom.Manual && !_resetScrollPending) return;
        _zoomQueued = DispatcherQueue.TryEnqueue(Microsoft.UI.Dispatching.DispatcherQueuePriority.Low, () =>
        {
            _zoomQueued = false;
            if (_closed || _page.Source is null || _scroll.ActualWidth <= 0) return;
            var zoom = _zoom switch
            {
                ViewZoom.FitWidth => _scroll.ActualWidth / _page.Width,
                ViewZoom.ActualSize => 1 / _layoutScale,
                _ => _manualScale / _layoutScale,
            };
            zoom = Math.Clamp(zoom, _scroll.MinZoomFactor, _scroll.MaxZoomFactor);
            var ratio = zoom / _scroll.ZoomFactor;
            var x = _resetScrollPending || _zoom == ViewZoom.FitWidth ? 0 : _scroll.HorizontalOffset * ratio;
            var y = _resetScrollPending ? 0 : _scroll.VerticalOffset * ratio;
            _resetScrollPending = false;
            _scroll.ChangeView(x, y, (float)zoom, disableAnimation: true);
        });
    }

    private void OnPointerPressed(object sender, PointerRoutedEventArgs args)
    {
        if (args.Pointer.PointerDeviceType == Microsoft.UI.Input.PointerDeviceType.Touch)
            _touchContacts.Add(args.Pointer.PointerId);
    }

    private void OnPointerReleased(object sender, PointerRoutedEventArgs args) =>
        _touchContacts.Remove(args.Pointer.PointerId);

    private void OnWheel(object sender, PointerRoutedEventArgs args)
    {
        if (args.Handled || _page.Source is null || (args.KeyModifiers & VirtualKeyModifiers.Control) == 0) return;
        var pointer = args.GetCurrentPoint(_scroll);
        var delta = pointer.Properties.MouseWheelDelta;
        if (delta == 0) return;
        var old = _scroll.ZoomFactor;
        var zoom = Math.Clamp(old * (delta > 0 ? 1.12f : 1 / 1.12f), _scroll.MinZoomFactor, _scroll.MaxZoomFactor);
        _manualScale = zoom * _layoutScale;
        SetZoomMode(ViewZoom.Manual);
        var ratio = zoom / old;
        var point = pointer.Position;
        _scroll.ChangeView(
            Math.Max(0, (_scroll.HorizontalOffset + point.X) * ratio - point.X),
            Math.Max(0, (_scroll.VerticalOffset + point.Y) * ratio - point.Y),
            zoom, disableAnimation: true);
        args.Handled = true;
    }

    private void OnPreviewKeyDown(object sender, KeyRoutedEventArgs args)
    {
        if (args.Key is VirtualKey.Left or VirtualKey.Right)
            for (var element = args.OriginalSource as DependencyObject; element is not null;
                 element = VisualTreeHelper.GetParent(element))
                if (element is ButtonBase) return;

        switch (args.Key)
        {
            case VirtualKey.Escape: Close(); break;
            case VirtualKey.Left: Step(-1); break;
            case VirtualKey.Right: Step(1); break;
            case VirtualKey.Number0: SelectZoom(ViewZoom.FitWidth); break;
            case VirtualKey.Number1: SelectZoom(ViewZoom.ActualSize); break;
            default: return;
        }
        args.Handled = true;
    }

    private void Step(int delta)
    {
        if (_visibleIndex is not { } current || _document is not { } document) return;
        var target = (long)current + delta;
        if (target >= 0 && target < document.PageCount) StartRender((uint)target);
    }

    private void StartRender(uint index)
    {
        if (_closed || _rendering || _document is not { } document || index >= document.PageCount || index == _visibleIndex) return;
        FocusViewport();
        _work = RenderAsync(document, index);
    }

    private void FocusViewport()
    {
        if (!_closed && !_scroll.Focus(FocusState.Programmatic) && Content is FrameworkElement root)
            root.Focus(FocusState.Programmatic);
    }

    private async Task LoadAsync()
    {
        MemoryStream? bytes = null;
        IRandomAccessStream? stream = null;
        SetProgress(true);
        try
        {
            var content = await _bytes.OpenAsync(_resource, MaximumPdfBytes, _lifetime.Token);
            if (_closed) return;
            bytes = new MemoryStream(content.Bytes, writable: false);
            stream = bytes.AsRandomAccessStream();
            var document = await PdfDocument.LoadFromStreamAsync(stream);
            if (_closed) return;
            if (document.IsPasswordProtected)
            {
                ShowNotice("此 PDF 受密码保护，暂不支持在预览中打开。", InfoBarSeverity.Informational);
                return;
            }
            if (document.PageCount is 0)
            {
                ShowNotice("此 PDF 没有可显示的页面。", InfoBarSeverity.Informational);
                return;
            }
            if (document.PageCount > MaximumPages)
            {
                ShowNotice($"此 PDF 有 {document.PageCount} 页，超过 {MaximumPages} 页的预览上限，请下载后查看。", InfoBarSeverity.Informational);
                return;
            }

            _sourceBytes = bytes;
            _sourceStream = stream;
            _document = document;
            bytes = null;
            stream = null;
            await RenderAsync(document, 0);
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (!_closed)
                ShowNotice(error is FilewayException fileway ? fileway.Error.Message : "PDF 未能打开，文件可能损坏或格式不受支持。", InfoBarSeverity.Error);
        }
        finally
        {
            stream?.Dispose();
            bytes?.Dispose();
            if (!_closed) SetProgress(false);
        }
    }

    private async Task RenderAsync(PdfDocument document, uint index)
    {
        _rendering = true;
        _failedIndex = null;
        HideNotice();
        SetProgress(true);
        UpdateCommands();
        try
        {
            using var page = document.GetPage(index);
            var size = page.Size;
            if (!double.IsFinite(size.Width) || !double.IsFinite(size.Height) || size.Width <= 0 || size.Height <= 0)
                throw new InvalidDataException("Invalid PDF page dimensions.");
            var longEdge = Math.Max(size.Width, size.Height);
            var rasterScale = Math.Min(2, MaximumRenderEdge / longEdge);
            var options = new PdfPageRenderOptions
            {
                DestinationWidth = (uint)Math.Max(1, Math.Round(size.Width * rasterScale)),
                DestinationHeight = (uint)Math.Max(1, Math.Round(size.Height * rasterScale)),
            };
            using var output = new InMemoryRandomAccessStream();
            await page.RenderToStreamAsync(output, options);
            if (_closed) return;
            output.Seek(0);
            var bitmap = new BitmapImage();
            await bitmap.SetSourceAsync(output);
            if (_closed) return;
            if (bitmap.PixelWidth <= 0 || bitmap.PixelHeight <= 0)
                throw new InvalidDataException("The PDF page did not produce a bitmap.");

            _layoutScale = Math.Min(1, MaximumLayoutEdge / longEdge);
            _page.Width = size.Width * _layoutScale;
            _page.Height = size.Height * _layoutScale;
            _page.Source = bitmap;
            _visibleIndex = index;
            _pageLabel.Text = $"第 {index + 1} / {document.PageCount} 页";
            AutomationProperties.SetHelpText(_page, _pageLabel.Text);
            QueueZoom(resetScroll: true);
        }
        catch (Exception)
        {
            if (_closed) return;
            _failedIndex = index;
            ShowNotice($"第 {index + 1} 页未能显示，请重试。", InfoBarSeverity.Error);
        }
        finally
        {
            _rendering = false;
            if (!_closed)
            {
                SetProgress(false);
                UpdateCommands();
            }
        }
    }

    private void ShowNotice(string message, InfoBarSeverity severity)
    {
        _notice.Severity = severity;
        _notice.Message = message;
        _retry.Visibility = _failedIndex is null ? Visibility.Collapsed : Visibility.Visible;
        _notice.Visibility = Visibility.Visible;
        _notice.IsOpen = true;
    }

    private void HideNotice()
    {
        _notice.IsOpen = false;
        _notice.Visibility = Visibility.Collapsed;
    }

    private void SetProgress(bool active)
    {
        _progress.IsActive = active;
        _progress.Visibility = active ? Visibility.Visible : Visibility.Collapsed;
    }

    private void UpdateCommands()
    {
        var ready = !_rendering && _visibleIndex.HasValue;
        _previous.IsEnabled = ready && _visibleIndex > 0;
        _next.IsEnabled = ready && _document is { } document && _visibleIndex + 1 < document.PageCount;
        _fit.IsEnabled = _actual.IsEnabled = ready;
        _retry.IsEnabled = !_rendering && _failedIndex.HasValue;
        SetZoomMode(_zoom);
    }
}
