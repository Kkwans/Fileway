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
internal sealed partial class PlayerWindow : Window, IPlaybackSurface, IPlaybackWindowControl
{
    private readonly IPlaybackSession _session;
    private readonly Fileway.Core.Contracts.IRemoteSubtitleSource? _remoteSubtitles;
    private readonly Fileway.Core.Contracts.IRemoteEpisodeSource? _remoteEpisodes;
    private readonly Fileway.Core.Contracts.ICompatibilityPlaybackSource? _compatibility;
    private Fileway.Core.Contracts.RemoteResourceRef? _compatibilityMedia;
    private string? _compatibilityTaskId;
    private SystemMediaController? _systemMedia;
    private bool _miniMode;
    private bool _miniWasTopmost;
    private IReadOnlyList<Fileway.Core.Contracts.RemoteMediaOption>? _episodeCache;
    private Fileway.Core.Contracts.RemoteResourceRef? _episodeCacheOrigin;
    private string? _advanceAttemptedWire;
    private readonly PlaybackResponsivenessDiagnostics? _responsiveness;
    private readonly PlaybackSnapshotDispatcher _snapshotDispatcher;
    private readonly Microsoft.UI.Dispatching.DispatcherQueueTimer _videoGestureTimer;
    private readonly long _videoGestureStarted = TimeProvider.System.GetTimestamp();
    private PlaybackVideoGesturePolicy? _videoGestures;
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
    private long? _bufferingSince;
    private readonly Microsoft.UI.Dispatching.DispatcherQueueTimer _bufferingTimer;
    private readonly Microsoft.UI.Dispatching.DispatcherQueueTimer _boundsTimer;
    private bool _showingState = true;
    private bool _closing;
    private bool _disposed;
    private bool _detached;
    private nint _child;
    private bool _subtitlePickerOpen;
    private long _subtitlePickerGeneration;
    private bool _initialAspectApplied;
    private bool _userAdjustedWindow;
    private bool _videoVisible;
    private RectInt32? _videoBounds;

    public PlayerWindow(IPlaybackSession session, IVideoSpriteService? sprites = null,
        Fileway.Core.Contracts.IRemoteSubtitleSource? remoteSubtitles = null,
        Fileway.Core.Contracts.ICompatibilityPlaybackSource? compatibility = null)
    {
        _session = session;
        _remoteSubtitles = remoteSubtitles;
        _remoteEpisodes = remoteSubtitles as Fileway.Core.Contracts.IRemoteEpisodeSource;
        _compatibility = compatibility;
        _bufferingTimer = DispatcherQueue.CreateTimer(); _bufferingTimer.IsRepeating = false; _bufferingTimer.Interval = TimeSpan.FromMilliseconds(500);
        _bufferingTimer.Tick += (_, _) => { if (!_closing && !_disposed && _hasFrame && _session.Snapshot.Status is PlaybackStatus.Seeking or PlaybackStatus.Buffering) _overlay?.ApplySnapshot(_session.Snapshot, transientLoading: true); };
        _boundsTimer = DispatcherQueue.CreateTimer(); _boundsTimer.IsRepeating = false; _boundsTimer.Interval = TimeSpan.FromMilliseconds(16);
        _boundsTimer.Tick += (_, _) => { if (!_disposed && _overlay is not null) ApplyBoundsFrame(); };
        var origin = session.Snapshot;
        // Settings and seek UIA work also defer at Low. Snapshot rendering must
        // yield to input without continually outranking those pending callbacks.
        _snapshotDispatcher = new PlaybackSnapshotDispatcher(origin.SessionId, origin.Generation,
            action => DispatcherQueue.TryEnqueue(Microsoft.UI.Dispatching.DispatcherQueuePriority.Low, () => action()), ApplySnapshot);
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
        _root.PointerPressed += VideoRoot_PointerPressed;
        _root.PointerReleased += VideoRoot_PointerReleased;
        _root.PointerCanceled += (_, _) => CancelVideoInput(PlaybackVideoInputSurface.Root);
        _root.PointerCaptureLost += (_, _) => CancelVideoInput(PlaybackVideoInputSurface.Root);
        Content = _root;
        _window = WinRT.Interop.WindowNative.GetWindowHandle(this);
        _systemMedia = SystemMediaController.TryCreate(_window,
            play: () => DispatcherQueue.TryEnqueue(() => { if (!_closing && !_disposed && !_detached) _ = RunCommandAsync(() => _session.SetPausedAsync(false, _watchCancellation.Token)); }),
            pause: () => DispatcherQueue.TryEnqueue(() => { if (!_closing && !_disposed && !_detached) _ = RunCommandAsync(() => _session.SetPausedAsync(true, _watchCancellation.Token)); }),
            next: () => DispatcherQueue.TryEnqueue(() => { if (!_closing && !_disposed && !_detached) _ = PlayEpisodeOffsetAsync(+1, announce: true); }),
            previous: () => DispatcherQueue.TryEnqueue(() => { if (!_closing && !_disposed && !_detached) _ = PlayEpisodeOffsetAsync(-1, announce: true); }),
            stop: () => DispatcherQueue.TryEnqueue(BeginClose));
        _videoGestureTimer = DispatcherQueue.CreateTimer();
        _videoGestureTimer.IsRepeating = false;
        _videoGestureTimer.Tick += VideoGestureTimer_Tick;
        _windowProcedure = WindowProcedure;
        NativeVideoWindow.SetWindowSubclass(_window, _windowProcedure, 1, 0);
        ApplyFramelessChrome();
        ApplyInitialWindowSize();
        _child = NativeVideoWindow.CreateWindowExW(0, "STATIC", "Fileway video", 0x50000004 | 0x04000000 | 0x02000000, 0, 0, 1, 1, _window, 0, 0, 0);
        if (_child == 0) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error(), "无法创建原生视频容器。");
        NativeVideoWindow.SetWindowSubclass(_child, _windowProcedure, 3, 0);
        _responsiveness = PlaybackResponsivenessDiagnostics.TryCreate(
            action => DispatcherQueue.TryEnqueue(Microsoft.UI.Dispatching.DispatcherQueuePriority.Normal, () => action()));
        try { _overlay = new PlayerOverlayWindow(session, _window, _responsiveness, sprites); }
        catch
        {
            _responsiveness?.Dispose();
            NativeVideoWindow.RemoveWindowSubclass(_child, _windowProcedure, 3);
            NativeVideoWindow.RemoveWindowSubclass(_window, _windowProcedure, 1);
            NativeVideoWindow.DestroyWindow(_child);
            _child = 0;
            Close();
            throw;
        }
        _overlay.CloseRequested += (_, _) => BeginClose();
        _overlay.ToggleFullscreenRequested += (_, _) => _ = RunCommandAsync(() => SetFullscreenAsync(!DesiredFullscreen, _watchCancellation.Token).AsTask());
        _overlay.ExitFullscreenRequested += (_, _) => _ = RunCommandAsync(() => SetFullscreenAsync(false, _watchCancellation.Token).AsTask());
        _overlay.AddSubtitleRequested += Overlay_AddSubtitleRequested;
        _overlay.RemoteSubtitleRequested += Overlay_RemoteSubtitleRequested;
        _overlay.EpisodeRequested += Overlay_EpisodeRequested;
        _overlay.SupportsRemoteSubtitles = remoteSubtitles is not null;
        _overlay.SupportsEpisodes = _remoteEpisodes is not null && session.Snapshot.Origin is not null;
        _overlay.SupportsCompatibilityPlayback = compatibility is not null && session.Snapshot.Origin is not null;
        _overlay.QualitySelected += Overlay_QualitySelected;
        _overlay.MiniWindowRequested += Overlay_MiniWindowRequested;
        _overlay.MinimizeRequested += (_, _) => MinimizeWindow();
        _overlay.MaximizeRequested += (_, _) => ToggleWindowMaximized();
        _overlay.VideoPointerPressed += point => HandleVideoGesture(true, point, PlaybackVideoInputSurface.Hud);
        _overlay.VideoPointerReleased += point => HandleVideoGesture(false, point, PlaybackVideoInputSurface.Hud);
        _overlay.VideoPointerCanceled += () => CancelVideoInput(PlaybackVideoInputSurface.Hud);
        _overlay.VideoKeyboardInput += () => CancelVideoGestures();
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
            if (args.WindowActivationState == WindowActivationState.Deactivated)
                DispatcherQueue.TryEnqueue(() =>
                {
                    var foreground = NativePlayerOverlay.GetForegroundWindow();
                    if (foreground != 0 && !_overlay.OwnsInputWindow(foreground)) CancelVideoGestures();
                });
            else
                DispatcherQueue.TryEnqueue(() => { UpdateBounds(); _overlay.SynchronizePointerPosition(); });
        };
        AppWindow.Closing += (_, args) => { if (_disposed) return; args.Cancel = true; BeginClose(); };
        Closed += (_, _) => {
            _bufferingTimer.Stop(); _boundsTimer.Stop(); CancelVideoGestures(); _responsiveness?.Dispose(); _snapshotDispatcher.Stop(); _systemMedia?.Dispose();
            if (_compatibility is { } service && _compatibilityMedia is { } media && _compatibilityTaskId is { } task)
                _ = service.CancelAsync(media, task, CancellationToken.None);
            if (!_disposed) _watchCancellation.Cancel();
        };
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
        var remembered = PresentationPreferences.RememberPlayerSize ? PresentationPreferences.PlayerSize : null;
        if (remembered is not null) _userAdjustedWindow = true;
        var requestedWidth = remembered?.Width ?? 1120; var requestedHeight = remembered?.Height ?? 630;
        var fit = Math.Min(1, Math.Min(availableWidth / (requestedWidth * scale), availableHeight / (requestedHeight * scale)));
        var width = Math.Max(1, (int)Math.Floor(requestedWidth * scale * fit));
        var height = Math.Max(1, (int)Math.Floor(requestedHeight * scale * fit));
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
        if (!_disposed && message == 0x0020 && ((long)lParam & 0xffff) == 1)
        {
            _ = NativePlayerOverlay.SetCursor(NativePlayerOverlay.LoadCursorW(0, (nint)32512));
            return 1;
        }
        if (window == _window && (message is 0x001a or 0x02e0)) CancelVideoGestures(resetPolicy: true);
        if ((window == _window || window == _child) && message == 0x001f) CancelVideoGestures();
        if (window == _child && message == 0x0215) CancelVideoInput(PlaybackVideoInputSurface.Native);
        if ((window == _window || window == _child) && message == 0x0008
            && wParam != 0 && !_overlay.OwnsInputWindow((nint)wParam)) CancelVideoGestures();
        if (window == _window && message == 0x0231) _userAdjustedWindow = true; // Native move/resize loop.
        if (window == _window && message == 0x0232) SaveWindowSize();
        if (!_disposed && window == _window && message == 0x0083) return 0; // WM_NCCALCSIZE: full-window client.
        if (!_disposed && window == _window && message == 0x0085) return 0;
        if (!_disposed && window == _window && message == 0x0086)
            return NativeVideoWindow.DefSubclassProc(window, message, wParam, (nint)(-1));
        if (!_disposed && window == _window && (message is 0x0006 or 0x001c))
            DispatcherQueue.TryEnqueue(() => _overlay?.RefreshBackdropState());
        if (!_disposed && window == _window && message == 0x0006 && ((uint)wParam & 0xffff) != 0)
        {
            // WM_ACTIVATE lParam is the previous active HWND. Returning focus from this
            // player's hidden HUD is internal bookkeeping, not new user activity.
            var internalActivation = lParam != 0 && NativePlayerOverlay.GetAncestor(lParam, 3) == _window;
            DispatcherQueue.TryEnqueue(() =>
            {
                if (internalActivation) _overlay?.SynchronizePointerPosition();
                else _overlay?.NotifyActivity();
            });
        }
        if (!_disposed && (window == _window || window == _child) && message == 0x0084)
        {
            var hit = HitTestFrame(NativePlayerOverlay.PointFromLParam(lParam));
            // Let the UI-thread parent receive resize/caption nonclient input,
            // rather than moving or sizing the video STATIC child itself.
            if (window == _window) return hit;
            if (hit != 1) return -1;
            // Own blank-video input explicitly instead of delegating hit testing
            // to STATIC. Interactive HUD controls live in their separate window.
            return 1;
        }
        if (!_disposed && window == _window && message == 0x00a0) _overlay?.NotifyPointerMoved();
        if (!_disposed && window == _window && (message is 0x00a1 or 0x0214 or 0x0216 or 0x0231 or 0x0232))
            _overlay?.NotifyActivity();
        // The mpv child is disabled in native-host mode, so this UI-thread
        // STATIC container receives the video's background input directly.
        if (!_disposed && !_detached && (window == _child || window == _window)
            && message == 0x0100)
        {
            var key = (VirtualKey)(uint)wParam;
            if (key is VirtualKey.Space or VirtualKey.Left or VirtualKey.Right or VirtualKey.F10 or VirtualKey.F11 or VirtualKey.Escape or VirtualKey.Q)
            {
                if (key == VirtualKey.F11) _responsiveness?.Record(PlaybackDiagnosticEvent.NativeFullscreenInput);
                if ((lParam.ToInt64() & (1L << 30)) == 0) HandleNativeKey(key);
                return 0;
            }
        }
        if (!_disposed && !_detached && (window == _child || window == _window) && message == 0x0200)
            _overlay?.NotifyPointerMoved();
        if (!_disposed && !_detached && window == _child && (message is 0x0201 or 0x0203))
        {
            _overlay?.NotifyActivity();
            NativeVideoWindow.SetFocus(_child);
            HandleVideoGesture(message, lParam);
            if (_videoGestures?.OwnsPress(PlaybackVideoInputSurface.Native) == true) NativeVideoWindow.SetCapture(_child);
            return 0;
        }
        if (!_disposed && !_detached && window == _child && message == 0x0202)
        {
            HandleVideoGesture(message, lParam);
            if (NativeVideoWindow.GetCapture() == _child) NativeVideoWindow.ReleaseCapture();
            return 0;
        }
        var result = NativeVideoWindow.DefSubclassProc(window, message, wParam, lParam);
        if (window == _window && (message is 0x02e0 or 0x007e)) FullscreenDisplayChanged();
        if (window == _window && (message is 0x0003 or 0x0005 or 0x02e0 or 0x0018))
            DispatcherQueue.TryEnqueue(UpdateBounds);
        if (message == 0x0024 && !_disposed && !FullscreenGeometry)
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

    private TimeSpan VideoGestureNow => TimeProvider.System.GetElapsedTime(_videoGestureStarted);

    private void HandleVideoGesture(uint message, nint lParam)
    {
        if (!_hasFrame || _showingState || _closing) { CancelVideoGestures(); return; }
        var point = NativePlayerOverlay.PointFromLParam(lParam);
        if (!NativeVideoWindow.ClientToScreen(_child, ref point)) { CancelVideoGestures(); return; }
        HandleVideoGesture(message != 0x0202, point);
    }

    private void HandleVideoGesture(bool pressed, NativeVideoWindow.NativePoint point, PlaybackVideoInputSurface surface = PlaybackVideoInputSurface.Native)
    {
        if (!_hasFrame || _showingState || _closing || _disposed || _detached) { CancelVideoGestures(); return; }
        if (_videoGestures is null)
        {
            var dpi = NativeVideoWindow.GetDpiForWindow(_child);
            if (dpi == 0) dpi = 96;
            _videoGestures = new PlaybackVideoGesturePolicy(
                TimeSpan.FromMilliseconds(Math.Max(1u, NativeVideoWindow.GetDoubleClickTime())),
                Math.Max(1, NativeVideoWindow.GetSystemMetricsForDpi(36, dpi)), // SM_CXDOUBLECLK.
                Math.Max(1, NativeVideoWindow.GetSystemMetricsForDpi(37, dpi)), // SM_CYDOUBLECLK.
                Math.Max(1, NativeVideoWindow.GetSystemMetricsForDpi(68, dpi)), // SM_CXDRAG.
                Math.Max(1, NativeVideoWindow.GetSystemMetricsForDpi(69, dpi))); // SM_CYDRAG.
        }
        var now = VideoGestureNow;
        var gesture = pressed ? _videoGestures.PointerPressed(now, point.X, point.Y, surface)
            : _videoGestures.PointerReleased(now, point.X, point.Y, surface);
        ApplyVideoGesture(gesture);
        ScheduleVideoGesture();
    }

    private bool TryVideoRootPointer(PointerRoutedEventArgs args, out NativeVideoWindow.NativePoint point)
    {
        point = default;
        if (args.Handled || args.Pointer.PointerDeviceType != Microsoft.UI.Input.PointerDeviceType.Mouse || _root.XamlRoot is null)
            return false;
        var position = args.GetCurrentPoint(_root).Position;
        var scale = _root.XamlRoot.RasterizationScale;
        point = new NativeVideoWindow.NativePoint { X = (int)Math.Round(position.X * scale), Y = (int)Math.Round(position.Y * scale) };
        return NativeVideoWindow.ClientToScreen(_window, ref point) && HitTestFrame(point) == 1;
    }

    private void VideoRoot_PointerPressed(object sender, PointerRoutedEventArgs args)
    {
        if (args.GetCurrentPoint(_root).Properties.PointerUpdateKind != Microsoft.UI.Input.PointerUpdateKind.LeftButtonPressed
            || !TryVideoRootPointer(args, out var point)) return;
        args.Handled = true;
        if (!_root.CapturePointer(args.Pointer)) return;
        _overlay.NotifyActivity();
        HandleVideoGesture(true, point, PlaybackVideoInputSurface.Root);
    }

    private void VideoRoot_PointerReleased(object sender, PointerRoutedEventArgs args)
    {
        if (args.GetCurrentPoint(_root).Properties.IsLeftButtonPressed
            || !TryVideoRootPointer(args, out var point)) return;
        args.Handled = true;
        HandleVideoGesture(false, point, PlaybackVideoInputSurface.Root);
        _root.ReleasePointerCapture(args.Pointer);
    }

    private void VideoGestureTimer_Tick(Microsoft.UI.Dispatching.DispatcherQueueTimer sender, object args)
    {
        if (_disposed || _detached || _closing || !_hasFrame || _showingState) { CancelVideoGestures(); return; }
        var foreground = NativePlayerOverlay.GetForegroundWindow();
        if (foreground == 0 || !_overlay.OwnsInputWindow(foreground)) { CancelVideoGestures(); return; }
        ApplyVideoGesture(_videoGestures?.Elapse(VideoGestureNow) ?? PlaybackVideoGesture.None);
        ScheduleVideoGesture();
    }

    private void ApplyVideoGesture(PlaybackVideoGesture gesture)
    {
        if (_disposed || _detached || _closing) return;
        if (gesture == PlaybackVideoGesture.TogglePause) _ = RunCommandAsync(TogglePausedAsync);
        else if (gesture == PlaybackVideoGesture.ToggleFullscreen)
            _ = RunCommandAsync(() => SetFullscreenAsync(!DesiredFullscreen, _watchCancellation.Token).AsTask());
    }

    private void ScheduleVideoGesture()
    {
        _videoGestureTimer.Stop();
        if (_videoGestures?.PendingDelay(VideoGestureNow) is not { } delay) return;
        _videoGestureTimer.Interval = delay > TimeSpan.Zero ? delay : TimeSpan.FromMilliseconds(1);
        _videoGestureTimer.Start();
    }

    private void CancelVideoGestures(bool resetPolicy = false)
    {
        _videoGestureTimer?.Stop();
        _videoGestures?.Cancel();
        if (NativeVideoWindow.GetCapture() == _child) NativeVideoWindow.ReleaseCapture();
        _root.ReleasePointerCaptures();
        _overlay?.ReleaseVideoPointer();
        if (resetPolicy) _videoGestures = null;
    }
    private void CancelVideoInput(PlaybackVideoInputSurface surface)
    {
        if (_videoGestures?.OwnsPress(surface) == true) CancelVideoGestures();
    }

    private nint HitTestFrame(NativeVideoWindow.NativePoint point)
    {
        if (FullscreenGeometry) return 1;
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
        NativeVideoWindow.ShowWindow(_window, 6); // Native minimize retains the active presenter.
    }

    private void ToggleWindowMaximized()
    {
        if (_disposed || _detached || FullscreenGeometry || AppWindow.Presenter is not OverlappedPresenter presenter) return;
        NativePlayerOverlay.SetForegroundWindow(_window);
        if (presenter.State == OverlappedPresenterState.Maximized) presenter.Restore();
        else presenter.Maximize();
        UpdateBounds();
    }

    private void HandleNativeKey(VirtualKey key)
    {
        CancelVideoGestures();
        _overlay.NotifyActivity();
        switch (key)
        {
            case VirtualKey.F10: _ = RunCommandAsync(() => ShowControlsAsync(_watchCancellation.Token).AsTask()); break;
            case VirtualKey.F11: _ = RunCommandAsync(() => SetFullscreenAsync(!DesiredFullscreen, _watchCancellation.Token).AsTask()); break;
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
        if (!_disposed && !_boundsTimer.IsRunning) _boundsTimer.Start();
    }
    private void ApplyBoundsFrame()
    {
        var diagnostics = _responsiveness;
        var started = diagnostics?.Timestamp ?? 0;
        try { UpdateBoundsCore(); }
        catch (Exception) when (_fullscreenApplyingFrame)
        {
            _fullscreenBoundsFailed = true;
        }
        finally { diagnostics?.RecordDuration(PlaybackDiagnosticEvent.BoundsDuration, started); }
    }

    private void UpdateBoundsCore()
    {
        if (_disposed || _child == 0) return;
        if (!NativePlayerOverlay.GetClientRect(_window, out var client) || client.Right <= client.Left || client.Bottom <= client.Top)
        {
            if (_fullscreenApplyingFrame) throw FullscreenFailure();
            return;
        }
        // The video grid fills the native client. Native physical geometry is authoritative;
        // queued XAML SizeChanged notifications cannot resize the child to an older frame.
        var videoBounds = new RectInt32(client.Left, client.Top, client.Right - client.Left, client.Bottom - client.Top);
        if (_videoBounds is not { } previous || !previous.Equals(videoBounds))
        {
            _videoBounds = videoBounds;
            if (!NativeVideoWindow.MoveWindow(_child, videoBounds.X, videoBounds.Y, videoBounds.Width, videoBounds.Height, true))
            {
                _videoBounds = null;
                if (_fullscreenApplyingFrame) throw FullscreenFailure();
            }
        }
        UpdateVideoVisibility();
        _overlay.SetWindowMaximized(NativePlayerOverlay.IsZoomed(_window));
        if (NativePlayerOverlay.GetWindowRect(_child, out var bounds))
        {
            if (_fullscreenApplyingFrame) _overlay.SyncMotionBounds(bounds, !_detached);
            else _overlay.SyncBounds(bounds, !_detached);
        }
        else if (_fullscreenApplyingFrame) throw FullscreenFailure();
    }

    private void UpdateVideoVisibility()
    {
        if (_child == 0) return;
        var visible = !_showingState && !_detached;
        if (_videoVisible == visible) return;
        _videoVisible = visible;
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
                _snapshotDispatcher.Publish(snapshot);
        }
        catch (OperationCanceledException) { }
        catch (Exception exception)
        {
            _snapshotDispatcher.Stop();
            DispatcherQueue.TryEnqueue(() =>
            {
                if (!_closing && !_disposed && !_detached && _snapshotDispatcher.Owns(_session.Snapshot))
                    ShowState("播放暂时不可用", MainPage.ErrorMessage(exception), loading: false);
            });
        }
    }

    private void ApplySnapshot(PlaybackSnapshot snapshot)
    {
        if (_responsiveness is not { } diagnostics) { ApplySnapshotCore(snapshot); return; }
        var started = diagnostics.Timestamp;
        try { ApplySnapshotCore(snapshot); }
        finally { diagnostics.RecordDuration(PlaybackDiagnosticEvent.SnapshotApplyDuration, started); }
    }

    private void ApplySnapshotCore(PlaybackSnapshot snapshot)
    {
        if (_disposed || _detached || !_snapshotDispatcher.Owns(snapshot) || !_snapshotDispatcher.Owns(_session.Snapshot)
            || (_closing && !PlaybackSnapshotDispatcher.IsLifecycleSnapshot(snapshot))) return;
        FitInitialVideoAspect(snapshot.DisplayAspectRatio);
        // A repeated progress snapshot must not stop/start the loading animation.
        var waiting = snapshot.Error is null && _hasFrame && snapshot.Status is PlaybackStatus.Seeking or PlaybackStatus.Buffering;
        if (waiting && _bufferingSince is null) { _bufferingSince = TimeProvider.System.GetTimestamp(); _bufferingTimer.Start(); }
        if (!waiting) { _bufferingSince = null; _bufferingTimer.Stop(); }
        var transientLoading = waiting && _bufferingSince is { } since && TimeProvider.System.GetElapsedTime(since) >= TimeSpan.FromMilliseconds(500);
        _overlay.ApplySnapshot(snapshot, transientLoading);
        // Stop retains any prior failure metadata. Closing must still happen.
        if (_systemMedia is { } systemMedia)
        {
            systemMedia.Update(snapshot.DisplayName, snapshot.Status switch
            {
                PlaybackStatus.Playing => global::Windows.Media.MediaPlaybackStatus.Playing,
                PlaybackStatus.Preparing or PlaybackStatus.Buffering or PlaybackStatus.Seeking =>
                    global::Windows.Media.MediaPlaybackStatus.Changing,
                PlaybackStatus.Paused => global::Windows.Media.MediaPlaybackStatus.Paused,
                PlaybackStatus.Ended => global::Windows.Media.MediaPlaybackStatus.Stopped,
                PlaybackStatus.Failed => global::Windows.Media.MediaPlaybackStatus.Stopped,
                _ => global::Windows.Media.MediaPlaybackStatus.Closed,
            });
        }
        if (snapshot.Status == PlaybackStatus.Stopped) { BeginClose(); return; }
        if (snapshot.Error is { } error) { ShowState("无法播放这个视频", error.Message, loading: false); return; }
        switch (snapshot.Status)
        {
            case PlaybackStatus.Preparing:
                if (!_hasFrame) ShowState("正在打开视频", "正在准备视频画面…", loading: true);
                break;
            case PlaybackStatus.Buffering:
                if (!_hasFrame) ShowState("正在缓冲", "画面准备好后会自动开始播放。", loading: true);
                else ShowTransientLoading();
                break;
            case PlaybackStatus.Seeking:
                ShowTransientLoading();
                break;
            case PlaybackStatus.Failed:
                ShowState("无法播放这个视频", "请关闭窗口，检查连接后重新打开文件。", loading: false);
                break;
            case PlaybackStatus.Ended:
                _hasFrame = true;
                // Auto-advance fires once per ended media; the guard resets when a switch changes the origin.
                _ = TryAdvanceEpisodeAsync();
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

    private void ShowTransientLoading()
    {
        // Retain the last rendered frame during seek/buffering. The separate HUD
        // only shows its loading indicator after the 500 ms presentation delay.
        CancelVideoGestures();
        _showingState = false;
        _state.Visibility = Visibility.Collapsed;
        _progress.IsIndeterminate = false;
        UpdateVideoVisibility();
    }

    private void FitInitialVideoAspect(double? displayAspectRatio)
    {
        if (_initialAspectApplied || displayAspectRatio is not { } aspect || !double.IsFinite(aspect) || aspect is < 0.1 or > 10) return;
        _initialAspectApplied = true;
        if (_userAdjustedWindow
            || FullscreenGeometry || NativePlayerOverlay.IsZoomed(_window)) return;
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

    public ValueTask SetFullscreenAsync(bool fullscreen, CancellationToken cancellationToken) =>
        SetNativeFullscreenAsync(fullscreen, cancellationToken);

    public ValueTask ShowControlsAsync(CancellationToken cancellationToken) => OnUiAsync(() =>
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (!_detached && !_disposed && !_closing) _overlay.ShowControls();
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
        var action = PlaybackPausePolicy.GetAction(_session.Snapshot);
        if (action.Restart) await _session.SeekAsync(TimeSpan.Zero, _watchCancellation.Token);
        await _session.SetPausedAsync(action.TargetPaused, _watchCancellation.Token);
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
        CancelVideoGestures();
        if (args.Key == VirtualKey.F10) { args.Handled = true; await RunCommandAsync(() => ShowControlsAsync(_watchCancellation.Token).AsTask()); }
        else if (args.Key == VirtualKey.Escape && FullscreenGeometry) { args.Handled = true; await RunCommandAsync(() => SetFullscreenAsync(false, _watchCancellation.Token).AsTask()); }
        else if (args.Key == VirtualKey.F11) { args.Handled = true; await RunCommandAsync(() => SetFullscreenAsync(!DesiredFullscreen, _watchCancellation.Token).AsTask()); }
        else if (args.Key == VirtualKey.Space) { args.Handled = true; await RunCommandAsync(TogglePausedAsync); }
        else if (args.Key == VirtualKey.Left) { args.Handled = true; await RunCommandAsync(() => SeekRelativeAsync(-10)); }
        else if (args.Key == VirtualKey.Right) { args.Handled = true; await RunCommandAsync(() => SeekRelativeAsync(10)); }
    }

    private async Task RunCommandAsync(Func<Task> command)
    {
        try { await command(); }
        catch (OperationCanceledException) { }
        catch (Exception exception) { if (!_closing && !_disposed && !_detached) ShowState("操作未完成", MainPage.ErrorMessage(exception), loading: false); }
    }

    private void Overlay_AddSubtitleRequested(object? sender, EventArgs args) => _ = AddLocalSubtitleAsync();

    private bool IsCurrentSubtitleRequest(long generation, PlaybackSnapshot origin) =>
        !_disposed && !_detached && !_closing
        && LocalSubtitlePolicy.IsCurrentRequest(generation, Interlocked.Read(ref _subtitlePickerGeneration),
            origin, _session.Snapshot, _watchCancellation.IsCancellationRequested);

    private void Overlay_RemoteSubtitleRequested(object? sender, EventArgs args) => _ = AddRemoteSubtitleAsync();

    private void Overlay_QualitySelected(object? sender, string quality) => _ = SwitchCompatibilityQualityAsync(quality);

    private void Overlay_MiniWindowRequested(object? sender, EventArgs args) => ToggleMiniWindow();

    /// <summary>
    /// Compact always-on-top window in the work-area corner; playback, HUD and system media
    /// keys keep working. Fullscreen is unaffected: it takes over and restores the mini state.
    /// </summary>
    private void ToggleMiniWindow()
    {
        if (_disposed || _detached || _closing || FullscreenGeometry) return;
        if (AppWindow.Presenter is not OverlappedPresenter presenter) return;
        _miniMode = !_miniMode;
        var work = DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Primary).WorkArea;
        if (_miniMode)
        {
            SaveWindowSize();
            _miniWasTopmost = presenter.IsAlwaysOnTop;
            presenter.IsAlwaysOnTop = true;
            presenter.IsResizable = false;
            AppWindow.MoveAndResize(new RectInt32(
                work.X + Math.Max(0, work.Width - 384 - 24), work.Y + Math.Max(0, work.Height - 216 - 24), 384, 216));
        }
        else
        {
            presenter.IsAlwaysOnTop = _miniWasTopmost;
            presenter.IsResizable = true;
            double scale = NativeVideoWindow.GetDpiForWindow(_window) / 96d;
            if (scale <= 0 || !double.IsFinite(scale)) scale = 1;
            var saved = PresentationPreferences.PlayerSize ?? (1120, 630);
            int width = Math.Clamp((int)(saved.Width * scale), 320, work.Width);
            int height = Math.Clamp((int)(saved.Height * scale), 180, work.Height);
            AppWindow.MoveAndResize(new RectInt32(
                work.X + Math.Max(0, (work.Width - width) / 2), work.Y + Math.Max(0, (work.Height - height) / 2), width, height));
        }
    }

    private async Task SwitchCompatibilityQualityAsync(string quality)
    {
        if (_compatibility is null || _disposed || _detached || _closing || _watchCancellation.IsCancellationRequested) return;
        var origin = _session.Snapshot.Origin;
        if (origin is null) return;
        if (_compatibilityTaskId is { } previous)
        {
            // A superseded task is canceled best effort; its lease dies with the session anyway.
            _ = _compatibility.CancelAsync(origin, previous, CancellationToken.None);
            _compatibilityTaskId = null;
        }
        if (quality == "source")
        {
            await RunCommandAsync(() => _session.PlayAsync(new RemotePlaybackInput(origin), _watchCancellation.Token));
            return;
        }
        try
        {
            var opened = await _compatibility.OpenAsync(origin, quality, _watchCancellation.Token);
            if (_disposed || _detached || _closing || _watchCancellation.IsCancellationRequested)
            { _ = _compatibility.CancelAsync(origin, opened.TaskId, CancellationToken.None); return; }
            _compatibilityTaskId = opened.TaskId;
            _compatibilityMedia = origin;
            await RunCommandAsync(() => _session.PlayAsync(new HlsPlaybackInput(opened.StreamUri, origin), _watchCancellation.Token));
            await OnUiAsync(() => { if (!_closing && !_disposed && !_detached) _overlay.ReportError("兼容画质：" + quality + "（转码在后台继续）"); });
        }
        catch (OperationCanceledException) { }
        catch (Exception)
        {
            await OnUiAsync(() => { if (!_closing && !_disposed && !_detached) _overlay.ReportError("兼容画质未能开始，可直接播放原画。"); });
        }
    }

    private void Overlay_EpisodeRequested(object? sender, EventArgs args) => _ = ShowEpisodePickerAsync();

    private async Task<IReadOnlyList<Fileway.Core.Contracts.RemoteMediaOption>?> EpisodesAsync()
    {
        if (_remoteEpisodes is null) return null;
        var origin = _session.Snapshot.Origin;
        if (origin is null) return null;
        if (_episodeCache is { } cache && _episodeCacheOrigin?.WirePath == origin.WirePath &&
            _episodeCacheOrigin.Account == origin.Account) return cache;
        var options = await _remoteEpisodes.ListEpisodesAsync(origin, _watchCancellation.Token);
        _episodeCache = options;
        _episodeCacheOrigin = origin;
        return options;
    }

    private async Task ShowEpisodePickerAsync()
    {
        if (_disposed || _detached || _closing || _subtitlePickerOpen || _watchCancellation.IsCancellationRequested) return;
        _subtitlePickerOpen = true;
        try
        {
            var options = await EpisodesAsync();
            if (_disposed || _detached || _closing || _watchCancellation.IsCancellationRequested || options is null) return;
            if (options.Count == 0)
            {
                await OnUiAsync(() => { if (!_closing && !_disposed && !_detached) _overlay.ReportError("同目录没有可切换的媒体文件。"); });
                return;
            }
            var panel = new StackPanel { Spacing = 8, MinWidth = 320, MaxWidth = 460 };
            panel.Children.Add(new TextBlock { Text = "选择要播放的媒体（与当前同目录）", TextWrapping = TextWrapping.Wrap, FontSize = 12, Opacity = 0.8 });
            var list = new ListView { SelectionMode = ListViewSelectionMode.Single, HorizontalContentAlignment = Microsoft.UI.Xaml.HorizontalAlignment.Stretch, MaxHeight = 320 };
            Fileway.Core.Contracts.RemoteMediaOption? initiallySelected = null;
            foreach (var option in options)
            {
                var row = new StackPanel { Spacing = 2, Tag = option };
                var title = new TextBlock { Text = option.Resource.DisplayName + (option.IsCurrent ? "  （正在播放）" : ""), TextWrapping = TextWrapping.Wrap, FontSize = 13 };
                if (option.IsCurrent) title.FontWeight = Microsoft.UI.Text.FontWeights.SemiBold;
                row.Children.Add(title);
                row.Children.Add(new TextBlock { Text = DownloadPresentationPolicy.FormatBytes(option.Size), FontSize = 11, Opacity = 0.6 });
                list.Items.Add(row);
                if (option.IsCurrent) initiallySelected = option;
            }
            if (initiallySelected is { } current) list.SelectedItem = list.Items.First(item => ((StackPanel)item).Tag is Fileway.Core.Contracts.RemoteMediaOption option && option.IsCurrent);
            if (list.SelectedItem is not null) list.ScrollIntoView(list.SelectedItem);
            panel.Children.Add(list);
            var dialog = new ContentDialog { Title = "选集", Content = panel, PrimaryButtonText = "播放", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Primary };
            var choice = await dialog.ShowAsync().AsTask(_watchCancellation.Token);
            if (choice != ContentDialogResult.Primary || list.SelectedItem is not StackPanel { Tag: Fileway.Core.Contracts.RemoteMediaOption selected } ||
                selected.IsCurrent || _disposed || _detached || _closing || _watchCancellation.IsCancellationRequested) return;
            await RunCommandAsync(() => _session.PlayAsync(new RemotePlaybackInput(selected.Resource), _watchCancellation.Token));
        }
        catch (OperationCanceledException) { }
        catch (Exception)
        {
            await OnUiAsync(() => { if (!_closing && !_disposed && !_detached) _overlay.ReportError("选集未能加载，请检查连接后重试。"); });
        }
        finally
        {
            if (DispatcherQueue.HasThreadAccess) _subtitlePickerOpen = false;
            else DispatcherQueue.TryEnqueue(() => _subtitlePickerOpen = false);
        }
    }

    /// <summary>Plays the next same-directory media once after the current one ends.</summary>
    private async Task TryAdvanceEpisodeAsync()
    {
        try
        {
            var origin = _session.Snapshot.Origin;
            if (origin is null || _advanceAttemptedWire == origin.WirePath) return;
            _advanceAttemptedWire = origin.WirePath;
            await PlayEpisodeOffsetAsync(+1, announce: true);
        }
        catch (Exception) { /* 自动连播失败不打断当前结束状态。 */ }
    }

    /// <summary>
    /// Plays a neighboring same-directory media by offset. Also refreshes the system media
    /// controller's next/previous availability from the fetched ordering.
    /// </summary>
    private async Task PlayEpisodeOffsetAsync(int offset, bool announce)
    {
        var origin = _session.Snapshot.Origin;
        if (origin is null || _disposed || _detached || _closing || _watchCancellation.IsCancellationRequested) return;
        var options = await EpisodesAsync();
        if (options is null || _disposed || _detached || _closing || _watchCancellation.IsCancellationRequested) return;
        int index = -1;
        for (int position = 0; position < options.Count; position++)
            if (options[position].Resource.WirePath == origin.WirePath) { index = position; break; }
        if (index < 0) return;
        if (_systemMedia is { } media)
        {
            media.NextEnabled = index + 1 < options.Count;
            media.PreviousEnabled = index - 1 >= 0;
        }
        int target = index + offset;
        if (target < 0 || target >= options.Count) return;
        var neighbor = options[target];
        await RunCommandAsync(() => _session.PlayAsync(new RemotePlaybackInput(neighbor.Resource), _watchCancellation.Token));
        if (announce)
            await OnUiAsync(() => { if (!_closing && !_disposed && !_detached) _overlay.ReportError((offset > 0 ? "已切换下一集：" : "已切换上一集：") + neighbor.Resource.DisplayName); });
    }

    private async Task AddRemoteSubtitleAsync()
    {
        if (_disposed || _detached || _closing || _remoteSubtitles is null || _subtitlePickerOpen ||
            _watchCancellation.IsCancellationRequested) return;
        var origin = _session.Snapshot.Origin;
        if (origin is null)
        {
            await OnUiAsync(() => { if (!_closing && !_disposed && !_detached) _overlay.ReportError("当前媒体不是服务器资源，无法检索同目录字幕。"); });
            return;
        }
        _subtitlePickerOpen = true;
        var cancellation = _watchCancellation.Token;
        try
        {
            var options = await _remoteSubtitles.ListAsync(origin, cancellation);
            if (_disposed || _detached || _closing || cancellation.IsCancellationRequested) return;
            if (options.Count == 0)
            {
                await OnUiAsync(() => { if (!_closing && !_disposed && !_detached) _overlay.ReportError("同目录没有可用的字幕文件。"); });
                return;
            }
            var panel = new StackPanel { Spacing = 8, MinWidth = 320, MaxWidth = 460 };
            panel.Children.Add(new TextBlock { Text = "选择要加载的外挂字幕（与媒体同目录）", TextWrapping = TextWrapping.Wrap, FontSize = 12, Opacity = 0.8 });
            var list = new ListView { SelectionMode = ListViewSelectionMode.Single, HorizontalContentAlignment = Microsoft.UI.Xaml.HorizontalAlignment.Stretch, MaxHeight = 320, IsItemClickEnabled = false };
            foreach (var option in options)
                list.Items.Add(new TextBlock { Text = option.Resource.DisplayName + "\n" + DownloadPresentationPolicy.FormatBytes(option.Size), TextWrapping = TextWrapping.Wrap, FontSize = 13, Tag = option });
            panel.Children.Add(list);
            var dialog = new ContentDialog { Title = "从服务器加载字幕", Content = panel, PrimaryButtonText = "加载字幕", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Primary, IsPrimaryButtonEnabled = false };
            list.SelectionChanged += (_, _) => dialog.IsPrimaryButtonEnabled = list.SelectedItem is not null;
            var choice = await dialog.ShowAsync().AsTask(cancellation);
            if (choice != ContentDialogResult.Primary || list.SelectedItem is not TextBlock { Tag: RemoteSubtitleOption selected } ||
                _disposed || _detached || _closing || cancellation.IsCancellationRequested) return;
            var loading = _session.AddSubtitleAsync(new RemotePlaybackInput(selected.Resource), cancellation);
            await loading;
        }
        catch (OperationCanceledException) { }
        catch (Exception)
        {
            await OnUiAsync(() => { if (!_closing && !_disposed && !_detached) _overlay.ReportError("远端字幕未能加载，请检查连接后重试。"); });
        }
        finally
        {
            if (DispatcherQueue.HasThreadAccess) _subtitlePickerOpen = false;
            else DispatcherQueue.TryEnqueue(() => _subtitlePickerOpen = false);
        }
    }

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
        SaveWindowSize(); _bufferingTimer.Stop(); _boundsTimer.Stop();
        CancelVideoGestures();
        _closing = true;
        _snapshotDispatcher.BeginClosing();
        Interlocked.Increment(ref _subtitlePickerGeneration);
        ShowState("正在关闭视频", "正在释放播放资源…", loading: true);
        _ = CloseSessionAsync();
    }
    private void SaveWindowSize()
    {
        if (_disposed || FullscreenGeometry || NativePlayerOverlay.IsZoomed(_window)) return;
        var dpi = NativeVideoWindow.GetDpiForWindow(_window);
        var scale = dpi == 0 ? 1 : dpi / 96d;
        try { PresentationPreferences.PlayerSize = ((int)Math.Round(AppWindow.Size.Width / scale), (int)Math.Round(AppWindow.Size.Height / scale)); }
        catch (Exception) { /* Window geometry is optional; never interfere with close. */ }
    }
    private async Task CloseSessionAsync()
    {
        try
        {
            try { await _session.StopAsync(CancellationToken.None); }
            catch (Exception) { /* Disposal still owns native renderer teardown. */ }
            await _session.DisposeAsync();
        }
        catch (Exception exception)
        {
            if (_disposed || _detached) return;
            _closing = false;
            ShowState("暂时无法关闭视频", MainPage.ErrorMessage(exception), loading: false);
        }
    }

    public ValueTask DetachAsync(CancellationToken cancellationToken) => OnUiAsync(() =>
    {
        if (_disposed) return;
        CancelVideoGestures();
        _responsiveness?.Dispose();
        _snapshotDispatcher.Stop();
        Interlocked.Increment(ref _subtitlePickerGeneration);
        _detached = true; _watchCancellation.Cancel(); _overlay.Hide(); UpdateVideoVisibility();
    });

    public ValueTask DisposeAsync() => OnUiAsync(() =>
    {
        if (_disposed) return;
        CancelVideoGestures();
        _videoGestureTimer.Tick -= VideoGestureTimer_Tick;
        _responsiveness?.Dispose();
        _snapshotDispatcher.Stop();
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
    [DllImport("user32.dll", ExactSpelling = true)] internal static extern nint SetCapture(nint window);
    [DllImport("user32.dll", ExactSpelling = true)] internal static extern nint GetCapture();
    [DllImport("user32.dll", ExactSpelling = true)] [return: MarshalAs(UnmanagedType.Bool)] internal static extern bool ReleaseCapture();
    [DllImport("user32.dll", ExactSpelling = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool ClientToScreen(nint window, ref NativePoint point);
    [DllImport("user32.dll", ExactSpelling = true)]
    internal static extern uint GetDoubleClickTime();
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
