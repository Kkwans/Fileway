using System.Runtime.InteropServices;
using Fileway.App.Branding;
using Fileway.Core.Contracts;
using Fileway.Playback.Contracts;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.Foundation;
using Windows.Graphics;
using Windows.System;
using Microsoft.Windows.Storage.Pickers;

namespace Fileway.App.MediaSurfaces;

/// <summary>Native video owner and lifecycle; a separate owned WinUI window hosts the HUD.</summary>
internal sealed class PlayerWindow : Window, IPlaybackSurface, IPlaybackWindowControl
{
    private readonly IPlaybackSession _session;
    private readonly CancellationTokenSource _watchCancellation = new();
    private readonly Grid _root = new() { Background = new SolidColorBrush(Microsoft.UI.Colors.Black), RequestedTheme = ElementTheme.Dark };
    private readonly Grid _video = new();
    private readonly StackPanel _state = new() { Spacing = 16, MaxWidth = 440, Margin = new Thickness(40), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
    private readonly TextBlock _stateTitle = new() { FontSize = 24, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _stateDescription = new() { FontSize = 14, TextWrapping = TextWrapping.Wrap, Foreground = new SolidColorBrush(Windows.UI.Color.FromArgb(255, 184, 184, 184)) };
    private readonly ProgressBar _progress = new() { IsIndeterminate = true, Width = 160, HorizontalAlignment = HorizontalAlignment.Left };
    private readonly Button _closeState = new() { Content = "关闭窗口", HorizontalAlignment = HorizontalAlignment.Left };
    private readonly NativeVideoWindow.SubclassProc _windowProcedure;
    private readonly nint _window;
    private readonly PlayerOverlayWindow _overlay;
    private OverlappedPresenter? _windowedPresenter;
    private RectInt32 _windowedBounds;
    private bool _wasMaximized;
    private bool _hasFrame;
    private bool _showingState = true;
    private bool _closing;
    private bool _disposed;
    private bool _detached;
    private nint _child;
    private bool _subtitlePickerOpen;
    private long _subtitlePickerGeneration;
    private bool _initialAspectApplied;
    private bool _userAdjustedWindow;

    public PlayerWindow(IPlaybackSession session)
    {
        _session = session;
        Title = session.Snapshot.DisplayName + " · " + AppBranding.DisplayName;
        _windowedPresenter = AppWindow.Presenter as OverlappedPresenter;
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"));
        _root.Children.Add(_video);
        _state.Children.Add(new FontIcon { Glyph = "\uE714", FontSize = 32, HorizontalAlignment = HorizontalAlignment.Left });
        _state.Children.Add(_stateTitle);
        _state.Children.Add(_stateDescription);
        _state.Children.Add(_progress);
        _state.Children.Add(_closeState);
        _root.Children.Add(_state);
        _closeState.Click += (_, _) => BeginClose();
        _root.KeyDown += Root_KeyDown;
        Content = _root;
        _window = WinRT.Interop.WindowNative.GetWindowHandle(this);
        _windowProcedure = WindowProcedure;
        NativeVideoWindow.SetWindowSubclass(_window, _windowProcedure, 1, 0);
        ApplyFramelessChrome();
        ApplyInitialWindowSize();
        _child = NativeVideoWindow.CreateWindowExW(0, "STATIC", "Fileway video", 0x50000004 | 0x04000000 | 0x02000000, 0, 0, 1, 1, _window, 0, 0, 0);
        if (_child == 0) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error(), "无法创建原生视频容器。");
        NativeVideoWindow.SetWindowSubclass(_child, _windowProcedure, 3, 0);
        try { _overlay = new PlayerOverlayWindow(session, _window); }
        catch
        {
            NativeVideoWindow.RemoveWindowSubclass(_child, _windowProcedure, 3);
            NativeVideoWindow.RemoveWindowSubclass(_window, _windowProcedure, 1);
            NativeVideoWindow.DestroyWindow(_child);
            _child = 0;
            Close();
            throw;
        }
        _overlay.CloseRequested += (_, _) => BeginClose();
        _overlay.ToggleFullscreenRequested += (_, _) => _ = RunCommandAsync(() => SetFullscreenAsync(AppWindow.Presenter.Kind != AppWindowPresenterKind.FullScreen, _watchCancellation.Token).AsTask());
        _overlay.ExitFullscreenRequested += (_, _) => _ = RunCommandAsync(() => SetFullscreenAsync(false, _watchCancellation.Token).AsTask());
        _overlay.AddSubtitleRequested += Overlay_AddSubtitleRequested;
        _overlay.MinimizeRequested += (_, _) => MinimizeWindow();
        _overlay.MaximizeRequested += (_, _) => ToggleWindowMaximized();
        NativeVideoWindow.ShowWindow(_child, 0);
        ShowState("正在打开视频", "正在准备视频画面…", loading: true);
        _video.SizeChanged += (_, _) => UpdateBounds();
        _root.Loaded += (_, _) =>
        {
            if (_root.XamlRoot is not null) _root.XamlRoot.Changed += Root_Changed;
            UpdateBounds();
        };
        AppWindow.Changed += (_, _) => UpdateBounds();
        Activated += (_, args) =>
        {
            DispatcherQueue.TryEnqueue(_overlay.RefreshBackdropState);
            if (args.WindowActivationState != WindowActivationState.Deactivated)
                DispatcherQueue.TryEnqueue(() => { UpdateBounds(); _overlay.NotifyActivity(); });
        };
        AppWindow.Closing += (_, args) => { if (_disposed) return; args.Cancel = true; BeginClose(); };
        Closed += (_, _) => { if (!_disposed) _watchCancellation.Cancel(); };
        _ = WatchAsync();
    }

    public nint ParentHwnd => _child;
    public event EventHandler<bool>? FullscreenChanged;

    private void ApplyInitialWindowSize()
    {
        // Only construction chooses a size. Later resize/DPI changes preserve the user's window.
        var dpi = NativeVideoWindow.GetDpiForWindow(_window);
        var scale = dpi == 0 ? 1 : dpi / 96d;
        var work = DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Primary).WorkArea;
        var frameWidth = Math.Max(0, AppWindow.Size.Width - AppWindow.ClientSize.Width);
        var frameHeight = Math.Max(0, AppWindow.Size.Height - AppWindow.ClientSize.Height);
        var margin = (int)Math.Round(24 * scale);
        var availableWidth = Math.Max(1, work.Width - margin * 2 - frameWidth);
        var availableHeight = Math.Max(1, work.Height - margin * 2 - frameHeight);
        var fit = Math.Min(1, Math.Min(availableWidth / (1120 * scale), availableHeight / (630 * scale)));
        var width = Math.Max(1, (int)Math.Floor(1120 * scale * fit));
        var height = Math.Max(1, (int)Math.Floor(630 * scale * fit));
        var outerWidth = Math.Min(work.Width, width + frameWidth);
        var outerHeight = Math.Min(work.Height, height + frameHeight);
        AppWindow.MoveAndResize(new RectInt32(work.X + (work.Width - outerWidth) / 2, work.Y + (work.Height - outerHeight) / 2, outerWidth, outerHeight));
    }

    private void ApplyFramelessChrome()
    {
        ExtendsContentIntoTitleBar = true;
        if (AppWindow.Presenter is OverlappedPresenter presenter)
        {
            presenter.SetBorderAndTitleBar(false, false);
            presenter.IsResizable = true;
            presenter.IsMinimizable = true;
            presenter.IsMaximizable = true;
        }
        // Keep native resize/system/minimize/maximize capabilities for Snap
        // and keyboard commands, but remove the standard caption entirely.
        var style = NativePlayerOverlay.GetWindowLongPtrW(_window, -16).ToInt64();
        NativePlayerOverlay.SetWindowLongPtrW(_window, -16, (nint)((style & ~0x00c00000L) | 0x000f0000L));
        var dark = 1;
        var noBorderColor = unchecked((int)0xfffffffeu); // DWMWA_COLOR_NONE.
        var renderNonClient = 2; // DWMNCRP_ENABLED: retain the system window shadow.
        var roundCorners = 2; // DWMWCP_ROUND.
        _ = NativeVideoWindow.DwmSetWindowAttribute(_window, 2, ref renderNonClient, sizeof(int));
        _ = NativeVideoWindow.DwmSetWindowAttribute(_window, 20, ref dark, sizeof(int));
        _ = NativeVideoWindow.DwmSetWindowAttribute(_window, 33, ref roundCorners, sizeof(int));
        _ = NativeVideoWindow.DwmSetWindowAttribute(_window, 34, ref noBorderColor, sizeof(int));
        var shadowFrame = new NativeVideoWindow.FrameMargins { Left = 1, Right = 1, Top = 1, Bottom = 1 };
        _ = NativeVideoWindow.DwmExtendFrameIntoClientArea(_window, ref shadowFrame);
        NativeVideoWindow.SetWindowPos(_window, 0, 0, 0, 0, 0, 0x0037); // FRAMECHANGED, no activation/move/size/z-order.
    }

    private nint WindowProcedure(nint window, uint message, nuint wParam, nint lParam, nuint subclassId, nuint reference)
    {
        if (window == _window && message == 0x0231) _userAdjustedWindow = true; // Native move/resize loop.
        if (!_disposed && window == _window && message == 0x0083) return 0; // WM_NCCALCSIZE: full-window client.
        if (!_disposed && window == _window && message == 0x0085) return 0;
        if (!_disposed && window == _window && message == 0x0086)
            return NativeVideoWindow.DefSubclassProc(window, message, wParam, (nint)(-1));
        if (!_disposed && window == _window && (message is 0x0006 or 0x001c))
            DispatcherQueue.TryEnqueue(() => _overlay?.RefreshBackdropState());
        if (!_disposed && (window == _window || window == _child) && message == 0x0084)
        {
            var hit = HitTestFrame(NativePlayerOverlay.PointFromLParam(lParam));
            // Let the UI-thread parent receive resize/caption nonclient input,
            // rather than moving or sizing the video STATIC child itself.
            if (window == _window) return hit;
            if (hit != 1) return -1;
        }
        if (!_disposed && window == _window && (message is 0x00a0 or 0x00a1 or 0x0214 or 0x0216 or 0x0231 or 0x0232))
            _overlay?.NotifyActivity();
        // The mpv child is disabled in native-host mode, so this UI-thread
        // STATIC container receives the video's background input directly.
        if (!_disposed && !_detached && (window == _child || window == _window)
            && message == 0x0100)
        {
            var key = (VirtualKey)(uint)wParam;
            if (key is VirtualKey.Space or VirtualKey.Left or VirtualKey.Right or VirtualKey.F10 or VirtualKey.F11 or VirtualKey.Escape or VirtualKey.Q)
            {
                if ((lParam.ToInt64() & (1L << 30)) == 0) HandleNativeKey(key);
                return 0;
            }
        }
        if (!_disposed && !_detached && window == _child && (message is 0x0200 or 0x0201))
        {
            _overlay?.NotifyActivity();
            if (message == 0x0201) NativeVideoWindow.SetFocus(_child);
        }
        if (!_disposed && !_detached && _hasFrame && window == _child && message == 0x0202)
            _ = RunCommandAsync(TogglePausedAsync);
        var result = NativeVideoWindow.DefSubclassProc(window, message, wParam, lParam);
        if (window == _window && (message is 0x0003 or 0x0005 or 0x02e0 or 0x0018))
            DispatcherQueue.TryEnqueue(UpdateBounds);
        if (message == 0x0024 && !_disposed && AppWindow.Presenter.Kind != AppWindowPresenterKind.FullScreen)
        {
            var limits = Marshal.PtrToStructure<NativeVideoWindow.MinMaxInfo>(lParam);
            var scale = NativeVideoWindow.GetDpiForWindow(window) / 96d;
            limits.MinimumTrackSize = new NativeVideoWindow.NativePoint { X = (int)Math.Round(480 * scale), Y = (int)Math.Round(270 * scale) };
            var monitor = NativeVideoWindow.MonitorFromWindow(_window, 2);
            var info = new NativeVideoWindow.MonitorInfo { Size = Marshal.SizeOf<NativeVideoWindow.MonitorInfo>() };
            if (NativeVideoWindow.GetMonitorInfoW(monitor, ref info))
            {
                limits.MaximumPosition = new NativeVideoWindow.NativePoint { X = info.Work.Left - info.Monitor.Left, Y = info.Work.Top - info.Monitor.Top };
                limits.MaximumSize = new NativeVideoWindow.NativePoint { X = info.Work.Right - info.Work.Left, Y = info.Work.Bottom - info.Work.Top };
            }
            Marshal.StructureToPtr(limits, lParam, false);
        }
        return result;
    }

    private nint HitTestFrame(NativeVideoWindow.NativePoint point)
    {
        if (AppWindow.Presenter.Kind == AppWindowPresenterKind.FullScreen) return 1;
        if (!NativePlayerOverlay.IsZoomed(_window) && NativePlayerOverlay.GetWindowRect(_window, out var bounds))
        {
            var dpi = NativeVideoWindow.GetDpiForWindow(_window);
            var edge = Math.Max(1, NativeVideoWindow.GetSystemMetricsForDpi(32, dpi) + NativeVideoWindow.GetSystemMetricsForDpi(92, dpi));
            var left = point.X < bounds.Left + edge;
            var right = point.X >= bounds.Right - edge;
            var top = point.Y < bounds.Top + edge;
            var bottom = point.Y >= bounds.Bottom - edge;
            if (top) return left ? 13 : right ? 14 : 12;
            if (bottom) return left ? 16 : right ? 17 : 15;
            if (left) return 10;
            if (right) return 11;
        }
        return _overlay?.IsTitleDragPoint(point) == true ? 2 : 1; // HTCAPTION / HTCLIENT.
    }

    private void MinimizeWindow()
    {
        if (_disposed || _detached) return;
        if (AppWindow.Presenter is OverlappedPresenter presenter) presenter.Minimize();
    }

    private void ToggleWindowMaximized()
    {
        if (_disposed || _detached || AppWindow.Presenter is not OverlappedPresenter presenter) return;
        NativePlayerOverlay.SetForegroundWindow(_window);
        if (presenter.State == OverlappedPresenterState.Maximized) presenter.Restore();
        else presenter.Maximize();
        UpdateBounds();
    }

    private void HandleNativeKey(VirtualKey key)
    {
        _overlay.NotifyActivity();
        switch (key)
        {
            case VirtualKey.F10: _ = RunCommandAsync(() => ShowControlsAsync(_watchCancellation.Token).AsTask()); break;
            case VirtualKey.F11: _ = RunCommandAsync(() => SetFullscreenAsync(AppWindow.Presenter.Kind != AppWindowPresenterKind.FullScreen, _watchCancellation.Token).AsTask()); break;
            case VirtualKey.Escape: _ = RunCommandAsync(() => SetFullscreenAsync(false, _watchCancellation.Token).AsTask()); break;
            case VirtualKey.Space when _hasFrame: _ = RunCommandAsync(TogglePausedAsync); break;
            case VirtualKey.Left when _session.Snapshot.Seekable: _ = RunCommandAsync(() => SeekRelativeAsync(-10)); break;
            case VirtualKey.Right when _session.Snapshot.Seekable: _ = RunCommandAsync(() => SeekRelativeAsync(10)); break;
            case VirtualKey.Q: BeginClose(); break;
        }
    }

    private void Root_Changed(XamlRoot sender, XamlRootChangedEventArgs args) => UpdateBounds();
    private void UpdateBounds()
    {
        if (_child == 0 || _root.XamlRoot is null) return;
        var point = _video.TransformToVisual(_root).TransformPoint(new Point(0, 0));
        var scale = _root.XamlRoot.RasterizationScale;
        NativeVideoWindow.MoveWindow(_child, (int)Math.Round(point.X * scale), (int)Math.Round(point.Y * scale), Math.Max(1, (int)Math.Round(_video.ActualWidth * scale)), Math.Max(1, (int)Math.Round(_video.ActualHeight * scale)), true);
        UpdateVideoVisibility();
        _overlay.SetWindowMaximized(NativePlayerOverlay.IsZoomed(_window));
        if (NativePlayerOverlay.GetWindowRect(_child, out var bounds)) _overlay.SyncBounds(bounds, !_detached);
    }

    private void UpdateVideoVisibility()
    {
        if (_child == 0) return;
        var visible = !_showingState && !_detached;
        NativeVideoWindow.ShowWindow(_child, visible ? 8 : 0);
        if (visible) NativeVideoWindow.SetWindowPos(_child, 0, 0, 0, 0, 0, 0x0013);
    }

    private void FocusVideo()
    {
        if (_child == 0 || _showingState || _detached) return;
        _overlay.NotifyActivity();
    }

    private async Task WatchAsync()
    {
        try
        {
            await foreach (var snapshot in _session.WatchAsync(_watchCancellation.Token).ConfigureAwait(false))
                DispatcherQueue.TryEnqueue(() => ApplySnapshot(snapshot));
        }
        catch (OperationCanceledException) { }
        catch (Exception exception) { DispatcherQueue.TryEnqueue(() => { if (!_detached) ShowState("播放暂时不可用", MainPage.ErrorMessage(exception), loading: false); }); }
    }

    private void ApplySnapshot(PlaybackSnapshot snapshot)
    {
        if (_detached) return;
        FitInitialVideoAspect(snapshot.DisplayAspectRatio);
        _overlay.ApplySnapshot(snapshot);
        if (snapshot.Error is { } error) { ShowState("无法播放这个视频", error.Message, loading: false); return; }
        switch (snapshot.Status)
        {
            case PlaybackStatus.Preparing:
                if (!_hasFrame) ShowState("正在打开视频", "正在准备视频画面…", loading: true);
                break;
            case PlaybackStatus.Buffering:
                if (!_hasFrame) ShowState("正在缓冲", "画面准备好后会自动开始播放。", loading: true);
                break;
            case PlaybackStatus.Failed:
                ShowState("无法播放这个视频", "请关闭窗口，检查连接后重新打开文件。", loading: false);
                break;
            case PlaybackStatus.Stopped:
                BeginClose();
                break;
            case PlaybackStatus.Closing:
                ShowState("正在关闭视频", "正在释放播放资源…", loading: true);
                break;
            default:
                _hasFrame = true;
                _showingState = false;
                _state.Visibility = Visibility.Collapsed;
                _progress.IsIndeterminate = false;
                UpdateVideoVisibility();
                break;
        }
    }

    private void FitInitialVideoAspect(double? displayAspectRatio)
    {
        if (_initialAspectApplied || displayAspectRatio is not { } aspect || !double.IsFinite(aspect) || aspect is < 0.1 or > 10) return;
        _initialAspectApplied = true;
        if (_userAdjustedWindow
            || AppWindow.Presenter.Kind == AppWindowPresenterKind.FullScreen || NativePlayerOverlay.IsZoomed(_window)) return;
        var scale = NativeVideoWindow.GetDpiForWindow(_window) / 96d;
        var work = DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Primary).WorkArea;
        var margin = (int)Math.Round(24 * scale);
        var frameWidth = Math.Max(0, AppWindow.Size.Width - AppWindow.ClientSize.Width);
        var frameHeight = Math.Max(0, AppWindow.Size.Height - AppWindow.ClientSize.Height);
        var width = AppWindow.ClientSize.Width;
        var height = Math.Max((int)Math.Round(270 * scale), (int)Math.Round(width / aspect));
        var maxHeight = Math.Max(1, work.Height - 2 * margin - frameHeight);
        if (height > maxHeight)
        {
            height = maxHeight;
            width = Math.Max((int)Math.Round(480 * scale), (int)Math.Round(height * aspect));
        }
        width = Math.Min(width, Math.Max(1, work.Width - 2 * margin - frameWidth));
        var outerWidth = width + frameWidth;
        var outerHeight = height + frameHeight;
        var x = AppWindow.Position.X + (AppWindow.Size.Width - outerWidth) / 2;
        var y = AppWindow.Position.Y + (AppWindow.Size.Height - outerHeight) / 2;
        AppWindow.MoveAndResize(new RectInt32(Math.Clamp(x, work.X, work.X + Math.Max(0, work.Width - outerWidth)),
            Math.Clamp(y, work.Y, work.Y + Math.Max(0, work.Height - outerHeight)), outerWidth, outerHeight));
        UpdateBounds();
    }

    private void ShowState(string title, string description, bool loading)
    {
        _showingState = true;
        _stateTitle.Text = title;
        _stateDescription.Text = description;
        _state.Visibility = Visibility.Visible;
        _progress.Visibility = loading ? Visibility.Visible : Visibility.Collapsed;
        _progress.IsIndeterminate = loading;
        _closeState.Visibility = loading ? Visibility.Collapsed : Visibility.Visible;
        UpdateVideoVisibility();
        if (!loading && _root.XamlRoot is not null) _closeState.Focus(FocusState.Programmatic);
    }

    public ValueTask SetFullscreenAsync(bool fullscreen, CancellationToken cancellationToken) => OnUiAsync(() =>
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (_detached || _disposed) return;
        var isFullscreen = AppWindow.Presenter.Kind == AppWindowPresenterKind.FullScreen;
        if (fullscreen == isFullscreen) return;
        if (fullscreen)
        {
            _windowedPresenter = AppWindow.Presenter as OverlappedPresenter;
            _wasMaximized = _windowedPresenter?.State == OverlappedPresenterState.Maximized;
            _windowedBounds = new RectInt32(AppWindow.Position.X, AppWindow.Position.Y, AppWindow.Size.Width, AppWindow.Size.Height);
            AppWindow.SetPresenter(AppWindowPresenterKind.FullScreen);
        }
        else
        {
            AppWindow.SetPresenter(_windowedPresenter ?? OverlappedPresenter.Create());
            if (_wasMaximized && AppWindow.Presenter is OverlappedPresenter presenter) presenter.Maximize();
            else AppWindow.MoveAndResize(_windowedBounds);
            ApplyFramelessChrome();
        }
        var actualFullscreen = AppWindow.Presenter.Kind == AppWindowPresenterKind.FullScreen;
        _overlay.SetFullscreen(actualFullscreen);
        if (actualFullscreen != isFullscreen) FullscreenChanged?.Invoke(this, actualFullscreen);
        DispatcherQueue.TryEnqueue(() => { UpdateBounds(); FocusVideo(); });
    });

    public ValueTask ShowControlsAsync(CancellationToken cancellationToken) => OnUiAsync(() =>
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (!_detached && !_disposed) _overlay.ShowControls();
    });

    private void AddTracks(MenuFlyout menu, PlaybackSnapshot snapshot, MediaTrackKind kind, string label)
    {
        var tracks = snapshot.Tracks.Where(track => track.Kind == kind).ToArray();
        var group = new MenuFlyoutSubItem { Text = label, IsEnabled = tracks.Length > 0 };
        if (kind == MediaTrackKind.Subtitle) AddCommand(group.Items, "关闭字幕", () => _session.SelectTrackAsync(kind, null, _watchCancellation.Token));
        foreach (var track in tracks)
        {
            var name = !string.IsNullOrWhiteSpace(track.Title) ? track.Title : !string.IsNullOrWhiteSpace(track.Language) ? track.Language : $"{label} {track.Id}";
            if (!string.IsNullOrWhiteSpace(track.Codec)) name += " · " + track.Codec;
            var item = new ToggleMenuFlyoutItem { Text = name, IsChecked = track.Selected };
            item.Click += async (_, _) => await RunCommandAsync(() => _session.SelectTrackAsync(kind, track.Id, _watchCancellation.Token));
            group.Items.Add(item);
        }
        menu.Items.Add(group);
    }

    private void AddCommand(IList<MenuFlyoutItemBase> items, string text, Func<Task> command, bool enabled = true)
    {
        var item = new MenuFlyoutItem { Text = text, IsEnabled = enabled };
        item.Click += async (_, _) => await RunCommandAsync(command);
        items.Add(item);
    }

    private async Task TogglePausedAsync()
    {
        var snapshot = _session.Snapshot;
        if (snapshot.Status == PlaybackStatus.Ended) await _session.SeekAsync(TimeSpan.Zero, _watchCancellation.Token);
        await _session.SetPausedAsync(snapshot.Status != PlaybackStatus.Paused && snapshot.Status != PlaybackStatus.Ended, _watchCancellation.Token);
    }

    private Task SeekRelativeAsync(double seconds)
    {
        var snapshot = _session.Snapshot;
        var position = Math.Max(0, (snapshot.ConfirmedPosition ?? snapshot.Position).TotalSeconds + seconds);
        if (snapshot.Duration is { } duration) position = Math.Min(duration.TotalSeconds, position);
        return _session.SeekAsync(TimeSpan.FromSeconds(position), _watchCancellation.Token);
    }

    private async void Root_KeyDown(object sender, KeyRoutedEventArgs args)
    {
        if (_detached || _disposed) return;
        if (args.Key == VirtualKey.F10) { args.Handled = true; await RunCommandAsync(() => ShowControlsAsync(_watchCancellation.Token).AsTask()); }
        else if (args.Key == VirtualKey.Escape && AppWindow.Presenter.Kind == AppWindowPresenterKind.FullScreen) { args.Handled = true; await RunCommandAsync(() => SetFullscreenAsync(false, _watchCancellation.Token).AsTask()); }
        else if (args.Key == VirtualKey.F11) { args.Handled = true; await RunCommandAsync(() => SetFullscreenAsync(AppWindow.Presenter.Kind != AppWindowPresenterKind.FullScreen, _watchCancellation.Token).AsTask()); }
        else if (args.Key == VirtualKey.Space) { args.Handled = true; await RunCommandAsync(TogglePausedAsync); }
        else if (args.Key == VirtualKey.Left) { args.Handled = true; await RunCommandAsync(() => SeekRelativeAsync(-10)); }
        else if (args.Key == VirtualKey.Right) { args.Handled = true; await RunCommandAsync(() => SeekRelativeAsync(10)); }
    }

    private async Task RunCommandAsync(Func<Task> command)
    {
        try { await command(); }
        catch (OperationCanceledException) { }
        catch (Exception exception) { if (!_detached) ShowState("操作未完成", MainPage.ErrorMessage(exception), loading: false); }
    }

    private void Overlay_AddSubtitleRequested(object? sender, EventArgs args) => _ = AddLocalSubtitleAsync();

    private bool IsCurrentSubtitleRequest(long generation, PlaybackSnapshot origin) =>
        !_disposed && !_detached && !_closing
        && LocalSubtitlePolicy.IsCurrentRequest(generation, Interlocked.Read(ref _subtitlePickerGeneration),
            origin, _session.Snapshot, _watchCancellation.IsCancellationRequested);

    private async Task AddLocalSubtitleAsync()
    {
        if (_disposed || _detached || _closing || _subtitlePickerOpen || _watchCancellation.IsCancellationRequested) return;
        _subtitlePickerOpen = true;
        var generation = Interlocked.Increment(ref _subtitlePickerGeneration);
        var origin = _session.Snapshot;
        var cancellation = _watchCancellation.Token;
        try
        {
            var picker = new FileOpenPicker(AppWindow.Id)
            {
                Title = "添加本地字幕",
                CommitButtonText = "添加字幕",
                SuggestedStartLocation = PickerLocationId.VideosLibrary,
                ViewMode = PickerViewMode.List,
            };
            foreach (var extension in LocalSubtitlePolicy.Extensions) picker.FileTypeFilter.Add(extension);
            var file = await picker.PickSingleFileAsync().AsTask(cancellation);
            if (file is null || !IsCurrentSubtitleRequest(generation, origin)) return;

            // Validate the path and format before filesystem I/O. The picker
            // returns a path; only its metadata is inspected, never its bytes.
            var rejection = LocalSubtitlePolicy.GetRejection(file.Path, 0);
            (string Name, long Length) properties = ("", 0);
            if (rejection is null)
            {
                properties = await Task.Run(() =>
                {
                    cancellation.ThrowIfCancellationRequested();
                    var localFile = new FileInfo(file.Path);
                    return (localFile.Name, localFile.Length);
                }, cancellation);
                rejection = LocalSubtitlePolicy.GetRejection(file.Path, properties.Length);
            }
            if (!IsCurrentSubtitleRequest(generation, origin)) return;
            if (rejection is not null)
            {
                await OnUiAsync(() => { if (IsCurrentSubtitleRequest(generation, origin)) _overlay.ReportError(rejection); });
                return;
            }

            Task? loading = null;
            // Recheck on the owner UI thread immediately before dispatch. A
            // completed picker may otherwise outlive closing or a new session.
            await OnUiAsync(() =>
            {
                if (IsCurrentSubtitleRequest(generation, origin))
                    loading = _session.AddSubtitleAsync(new LocalPlaybackInput(file.Path, properties.Name), cancellation);
            });
            if (loading is not null) await loading;
        }
        catch (OperationCanceledException) { }
        catch (Exception)
        {
            if (IsCurrentSubtitleRequest(generation, origin))
                await OnUiAsync(() =>
                {
                    if (IsCurrentSubtitleRequest(generation, origin))
                        _overlay.ReportError("字幕未能加载，请检查文件格式后重试。");
                });
        }
        finally
        {
            if (DispatcherQueue.HasThreadAccess) _subtitlePickerOpen = false;
            else DispatcherQueue.TryEnqueue(() => _subtitlePickerOpen = false);
        }
    }

    private void BeginClose()
    {
        if (_closing || _disposed) return;
        _closing = true;
        Interlocked.Increment(ref _subtitlePickerGeneration);
        _ = CloseSessionAsync();
    }
    private async Task CloseSessionAsync()
    {
        try
        {
            try { await _session.StopAsync(CancellationToken.None); }
            catch (Exception) { /* Disposal still owns native renderer teardown. */ }
            await _session.DisposeAsync();
        }
        catch (Exception exception) { _closing = false; ShowState("暂时无法关闭视频", MainPage.ErrorMessage(exception), loading: false); }
    }

    public ValueTask DetachAsync(CancellationToken cancellationToken) => OnUiAsync(() =>
    {
        if (_disposed) return;
        Interlocked.Increment(ref _subtitlePickerGeneration);
        _detached = true; _watchCancellation.Cancel(); _overlay.Hide(); UpdateVideoVisibility();
    });

    public ValueTask DisposeAsync() => OnUiAsync(() =>
    {
        if (_disposed) return;
        Interlocked.Increment(ref _subtitlePickerGeneration);
        _disposed = true; _detached = true; _watchCancellation.Cancel();
        _overlay.AddSubtitleRequested -= Overlay_AddSubtitleRequested;
        _overlay.Dispose();
        if (_root.XamlRoot is not null) _root.XamlRoot.Changed -= Root_Changed;
        NativeVideoWindow.RemoveWindowSubclass(_window, _windowProcedure, 1);
        if (_child != 0) { NativeVideoWindow.RemoveWindowSubclass(_child, _windowProcedure, 3); NativeVideoWindow.DestroyWindow(_child); _child = 0; }
        _watchCancellation.Dispose();
        Close();
    });

    private ValueTask OnUiAsync(Action action)
    {
        if (DispatcherQueue.HasThreadAccess) { action(); return ValueTask.CompletedTask; }
        var completion = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!DispatcherQueue.TryEnqueue(() => { try { action(); completion.TrySetResult(); } catch (Exception exception) { completion.TrySetException(exception); } })) completion.TrySetException(new InvalidOperationException("播放窗口调度器已关闭。"));
        return new ValueTask(completion.Task);
    }
}

internal static class NativeVideoWindow
{
    [StructLayout(LayoutKind.Sequential)] internal struct FrameMargins { internal int Left, Right, Top, Bottom; }
    [DllImport("dwmapi.dll", ExactSpelling = true)] internal static extern int DwmExtendFrameIntoClientArea(nint window, ref FrameMargins margins);
    internal delegate nint SubclassProc(nint window, uint message, nuint wParam, nint lParam, nuint subclassId, nuint reference);
    [StructLayout(LayoutKind.Sequential)]
    internal struct NativePoint { internal int X; internal int Y; }
    [StructLayout(LayoutKind.Sequential)]
    internal struct MinMaxInfo { internal NativePoint Reserved; internal NativePoint MaximumSize; internal NativePoint MaximumPosition; internal NativePoint MinimumTrackSize; internal NativePoint MaximumTrackSize; }
    [StructLayout(LayoutKind.Sequential)]
    internal struct MonitorInfo { internal int Size; internal NativePlayerOverlay.Rect Monitor; internal NativePlayerOverlay.Rect Work; internal uint Flags; }

    [DllImport("user32.dll", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)]
    internal static extern nint CreateWindowExW(uint exStyle, string className, string windowName, uint style, int x, int y, int width, int height, nint parent, nint menu, nint instance, nint parameter);
    [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool MoveWindow(nint window, int x, int y, int width, int height, [MarshalAs(UnmanagedType.Bool)] bool repaint);
    [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool SetWindowPos(nint window, nint insertAfter, int x, int y, int width, int height, uint flags);
    [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool ShowWindow(nint window, int command);
    [DllImport("user32.dll", ExactSpelling = true)]
    internal static extern nint GetWindow(nint window, uint command);
    [DllImport("user32.dll", ExactSpelling = true)]
    internal static extern nint SetFocus(nint window);
    [DllImport("user32.dll", ExactSpelling = true)]
    internal static extern uint GetDpiForWindow(nint window);
    [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool DestroyWindow(nint window);
    [DllImport("dwmapi.dll", ExactSpelling = true)]
    internal static extern int DwmSetWindowAttribute(nint window, uint attribute, ref int value, int size);
    [DllImport("comctl32.dll", ExactSpelling = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool SetWindowSubclass(nint window, SubclassProc procedure, nuint id, nuint reference);
    [DllImport("comctl32.dll", ExactSpelling = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool RemoveWindowSubclass(nint window, SubclassProc procedure, nuint id);
    [DllImport("comctl32.dll", ExactSpelling = true)]
    internal static extern nint DefSubclassProc(nint window, uint message, nuint wParam, nint lParam);
    [DllImport("user32.dll", ExactSpelling = true)] internal static extern nint MonitorFromWindow(nint window, uint flags);
    [DllImport("user32.dll", ExactSpelling = true)] [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool GetMonitorInfoW(nint monitor, ref MonitorInfo info);
    [DllImport("user32.dll", ExactSpelling = true)] internal static extern int GetSystemMetricsForDpi(int index, uint dpi);
}
