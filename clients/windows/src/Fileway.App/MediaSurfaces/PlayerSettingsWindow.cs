using System.ComponentModel;
using Fileway.App.Branding;
using Fileway.App.Controls.Playback;
using Fileway.App.Windowing;
using System.Runtime.InteropServices;
using Microsoft.UI.Composition;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Composition.SystemBackdrops;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.Foundation;
using Windows.System;
using Windows.UI.ViewManagement;

namespace Fileway.App.MediaSurfaces;

/// <summary>An owned, interactive settings or details surface.</summary>
internal sealed class PlayerSettingsWindow : Window, IDisposable
{
    private const double MinimumMenuViewport = 160;
    private readonly Grid _root = new() { RequestedTheme = ElementTheme.Dark };
    private readonly SolidColorBrush _transparentBackground = new(Microsoft.UI.Colors.Transparent);
    private readonly FrameworkElement _presenter;
    private readonly FrameworkElement? _playerMenuContent;
    private readonly SettingsAcrylicBackdrop _backdrop;
    private readonly NativeVideoWindow.SubclassProc _procedure;
    private readonly KeyEventHandler _keyHandler;
    private readonly nint _window;
    private readonly NonClientInputGate? _inputGate;
    private readonly PlaybackResponsivenessDiagnostics? _responsiveness;
    private Action? _onClosed;
    private Control? _returnFocusTarget;
    private (FrameworkElement TopBar, FrameworkElement SeekBar)? _pictureBounds;
    private bool _preferBelow;
    private bool _trackLayoutQueued;
    private bool _subclassed;
    private bool _shown;
    private bool _closing;
    private bool _closed;
    private bool _released;
    private bool _moveArmed;
    private Point _moveStart;
    private NativeVideoWindow.NativePoint _moveStartScreen;
    private NativePlayerOverlay.Rect _moveStartRect;
    private Microsoft.UI.Dispatching.DispatcherQueueTimer? _moveWatch;
    private readonly bool _lightDismiss;
    private readonly bool _movable;
    private readonly nint _owner;

    internal PlayerSettingsWindow(nint owner, FrameworkElement content, Style? presenterStyle, Action onClosed,
        PlaybackResponsivenessDiagnostics? responsiveness = null, ElementTheme theme = ElementTheme.Dark,
        bool wrapContent = true, bool lightDismiss = true, bool movable = false)
    {
        _responsiveness = responsiveness;
        _owner = owner; _lightDismiss = lightDismiss; _movable = movable;
        _root.RequestedTheme = theme;
        _backdrop = new SettingsAcrylicBackdrop(theme, SetAcrylicAvailable);
        ArgumentNullException.ThrowIfNull(content);
        ArgumentNullException.ThrowIfNull(onClosed);
        _onClosed = onClosed;
        _procedure = WindowProcedure;
        _keyHandler = Root_KeyDown;
        _window = WinRT.Interop.WindowNative.GetWindowHandle(this);
        _inputGate = _movable ? new NonClientInputGate(_window) : null;
        _presenter = !wrapContent || content is FlyoutPresenter or MenuFlyoutPresenter ? content : new FlyoutPresenter { Content = content };
        try
        {
            if (owner == 0 || !IsWindow(owner)) throw new ArgumentException("需要有效的播放器所属窗口。", nameof(owner));
            Title = AppBranding.DisplayName + (AppBranding.IsChinese ? " 播放设置" : " playback settings");
            if (presenterStyle is not null) _presenter.Style = presenterStyle;
            if (_presenter is Control control)
            {
                control.Background = _transparentBackground;
                control.BorderThickness = new Thickness(0);
            }
            if (_presenter is FlyoutPresenter && content is ScreenboxTrackPicker or ScreenboxSpeedControl)
            {
                _playerMenuContent = content;
                // Give the menu's own scrollable region a finite viewport.
                ScrollViewer.SetHorizontalScrollMode(_presenter, ScrollMode.Disabled);
                ScrollViewer.SetHorizontalScrollBarVisibility(_presenter, ScrollBarVisibility.Disabled);
                ScrollViewer.SetVerticalScrollMode(_presenter, ScrollMode.Disabled);
                ScrollViewer.SetVerticalScrollBarVisibility(_presenter, ScrollBarVisibility.Disabled);
            }
            SetAcrylicAvailable(false);
            _root.Children.Add(_presenter);
            Content = _root;
            if (AppWindow.Presenter is OverlappedPresenter presenter)
            {
                presenter.SetBorderAndTitleBar(false, false);
                presenter.IsResizable = false;
                presenter.IsMinimizable = false;
                presenter.IsMaximizable = false;
            }
            SetWindowLong(-8, owner); // GWLP_HWNDPARENT: owned, never TopMost.
            var extended = GetWindowLong(-20).ToInt64();
            SetWindowLong(-20, (nint)((extended | 0x80L) & ~(0x40000L | 0x08000000L | 0x20L | 0x08L)));
            if (NativeVideoWindow.GetWindow(_window, 4) != owner)
                throw new InvalidOperationException("无法绑定播放器设置窗口。");
            if (!NativeVideoWindow.SetWindowSubclass(_window, _procedure, 1, 0))
                throw new InvalidOperationException("无法安装播放器设置窗口过程。");
            _subclassed = true;
            SetDwmAttribute(20, theme == ElementTheme.Dark ? 1 : 0);
            SetDwmAttribute(33, 2); // DWMWCP_ROUND; DWM retains its native shadow.
            SetDwmAttribute(34, unchecked((int)0xfffffffeu)); // DWMWA_COLOR_NONE.
            UpdatePopupMaterial();
            _root.AddHandler(UIElement.KeyDownEvent, _keyHandler, true);
            if (_playerMenuContent is not null) _root.PreviewKeyDown += PlayerMenu_PreviewKeyDown;
            _root.AddHandler(UIElement.PointerPressedEvent, new PointerEventHandler(Root_PointerPressed), true);
            _root.AddHandler(UIElement.PointerReleasedEvent, new PointerEventHandler(Root_PointerReleased), true);
            _root.PointerCaptureLost += Root_PointerCaptureLost;
            Activated += Window_Activated;
            Closed += Window_Closed;
            Controls.PopupMaterials.Changed += UpdatePopupMaterial;
        }
        catch (Exception creationError)
        {
            _onClosed = null;
            try { Release(); base.Close(); }
            catch (Exception cleanupError)
            {
                throw new AggregateException("设置窗口初始化和资源释放均失败。", creationError, cleanupError);
            }
            throw;
        }
    }

    internal nint Hwnd => _closed ? 0 : _window;

    private void UpdatePopupMaterial()
    {
        if (_closed || _closing || _released) return;
        var material = Controls.PopupMaterials.Effective;
        if (material != PopupMaterial.Solid && DesktopAcrylicController.IsSupported())
        {
            try
            {
                _backdrop.SetKind(material == PopupMaterial.SoftBlur ? DesktopAcrylicKind.Thin : DesktopAcrylicKind.Base);
                if (!_backdrop.IsAttached)
                {
                    // Keep a readable surface even when composition connects on a later layout pass.
                    SetAcrylicAvailable(false);
                    SystemBackdrop = null;
                    SystemBackdrop = _backdrop;
                }
                return;
            }
            catch (Exception) { /* An optional backdrop must not disable this window. */ }
        }

        SetAcrylicAvailable(false);
        if (SystemBackdrop is not null)
        {
            try { SystemBackdrop = null; }
            catch (Exception) { /* The opaque root already covers any detached material. */ }
        }
    }

    private void SetAcrylicAvailable(bool available)
    {
        if (_closed || _closing || _released) return;
        if (available)
        {
            _root.Background = _transparentBackground;
            return;
        }

        var theme = _root.RequestedTheme == ElementTheme.Default ? _root.ActualTheme : _root.RequestedTheme;
        var fallback = new AccessibilitySettings().HighContrast
            ? new UISettings().GetColorValue(UIColorType.Background)
            : Controls.ThemePalette.Color("FwOwnedPanelFallbackColor", theme);
        _root.Background = new SolidColorBrush(fallback);
    }

    internal void ShowCentered()
    {
        ObjectDisposedException.ThrowIf(_closed || _released, this); VerifyThread();
        if (!NativePlayerOverlay.GetWindowRect(_owner, out var owner)) throw NativeFailure("GetWindowRect");
        var scale = NativeVideoWindow.GetDpiForWindow(_owner) / 96d;
        var work = Microsoft.UI.Windowing.DisplayArea.GetFromWindowId(Microsoft.UI.Win32Interop.GetWindowIdFromWindow(_owner), DisplayAreaFallback.Primary).WorkArea;
        var width = Math.Min(560, work.Width / scale - 48); var height = Math.Min(680, work.Height / scale - 48);
        _root.MaxWidth = _presenter.MaxWidth = width; _root.MaxHeight = _presenter.MaxHeight = height;
        _root.Measure(new Size(width, height));
        var w = (int)Math.Ceiling(_root.DesiredSize.Width * scale); var h = (int)Math.Ceiling(_root.DesiredSize.Height * scale);
        AppWindow.MoveAndResize(new Windows.Graphics.RectInt32(Math.Clamp(owner.Left + (owner.Right-owner.Left-w)/2, work.X, work.X+work.Width-w), Math.Clamp(owner.Top+(owner.Bottom-owner.Top-h)/2,work.Y,work.Y+work.Height-h),w,h));
        _shown = true; Activate(); _root.UpdateLayout(); FocusFirstControl(_root, FocusState.Programmatic);
    }

    private void Root_PointerPressed(object sender, PointerRoutedEventArgs args)
    {
        _moveArmed = false;
        if (!_movable || !args.GetCurrentPoint(_root).Properties.IsLeftButtonPressed || args.GetCurrentPoint(_root).Position.Y > 56) return;
        for (DependencyObject? current = args.OriginalSource as DependencyObject; current is not null && current != _root; current = VisualTreeHelper.GetParent(current))
            if (current is Microsoft.UI.Xaml.Controls.Primitives.ButtonBase or TextBox or AutoSuggestBox or ScrollViewer) return;
        if (!GetCursorPos(out _moveStartScreen)) return;
        if (!NativePlayerOverlay.GetWindowRect(_window, out _moveStartRect)) return;
        _moveStart = args.GetCurrentPoint(_root).Position;
        _moveArmed = true;
        _moveWatch ??= DispatcherQueue.CreateTimer();
        _moveWatch.Stop();
        _moveWatch.Interval = TimeSpan.FromMilliseconds(16);
        _moveWatch.IsRepeating = true;
        _moveWatch.Tick -= MoveWatch_Tick;
        _moveWatch.Tick += MoveWatch_Tick;
        _moveWatch.Start();
    }

    private void MoveWatch_Tick(object? sender, object args)
    {
        if (!_moveArmed) { _moveWatch?.Stop(); return; }
        if ((GetAsyncKeyState(0x01) & 0x8000) == 0) { _moveArmed = false; _moveWatch?.Stop(); _ = NativePlayerOverlay.SetCursor(NativePlayerOverlay.LoadCursorW(0, (nint)32512)); return; }
        if (!GetCursorPos(out var current)) return;
        var dx = current.X - _moveStartScreen.X;
        var dy = current.Y - _moveStartScreen.Y;
        if (Math.Abs(dx) < 4 && Math.Abs(dy) < 4) return;
        _ = NativePlayerOverlay.SetCursor(NativePlayerOverlay.LoadCursorW(0, (nint)32644));
        NativeVideoWindow.SetWindowPos(_window, 0, _moveStartRect.Left + dx, _moveStartRect.Top + dy, 0, 0, 0x0015);
    }

    private void Root_PointerReleased(object sender, PointerRoutedEventArgs args)
    {
        if (_moveArmed) _ = NativePlayerOverlay.SetCursor(NativePlayerOverlay.LoadCursorW(0, (nint)32512));
        _moveArmed = false;
        _moveWatch?.Stop();
    }

    private void Root_PointerCaptureLost(object sender, PointerRoutedEventArgs args)
    {
        _moveArmed = false;
        _moveWatch?.Stop();
    }

    internal void ShowAt(FrameworkElement anchor, bool preferBelow = false,
        (FrameworkElement TopBar, FrameworkElement SeekBar)? pictureBounds = null)
    {
        ArgumentNullException.ThrowIfNull(anchor);
        ObjectDisposedException.ThrowIf(_closed || _released, this);
        VerifyThread();
        if (_shown) { Activate(); return; }
        try
        {
            _preferBelow = preferBelow;
            if (_playerMenuContent is not null) _pictureBounds = pictureBounds;
            var placement = GetAnchorPlacement(anchor);
            // Size before the first show, then finish XAML initialization without
            // activation. Re-measure once using the target window's actual DPI.
            PlaceAndMeasure(placement, placement.AnchorScale);
            _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsShowBegin);
            AppWindow.Show(false);
            _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsShowEnd);
            _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsLayoutBegin, 1);
            _root.UpdateLayout();
            _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsLayoutEnd, 1);
            PlaceAndMeasure(placement, _root.XamlRoot?.RasterizationScale ?? placement.AnchorScale);
            _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsLayoutBegin, 2);
            _root.UpdateLayout();
            _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsLayoutEnd, 2);
            _shown = true;
            if (_playerMenuContent is not null) _returnFocusTarget = anchor as Control;
            _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsActivateBegin);
            Activate(); // This surface was explicitly opened for interaction.
            var focusState = anchor is Control { FocusState: FocusState.Keyboard }
                ? FocusState.Keyboard : FocusState.Programmatic;
            var focused = _playerMenuContent switch
            {
                ScreenboxTrackPicker tracks => tracks.TryFocusInitialControl(focusState),
                ScreenboxSpeedControl speed => speed.TryFocusInitialControl(focusState),
                _ => false,
            };
            if (!focused) FocusFirstControl(_root, focusState);
            if (_playerMenuContent is ScreenboxTrackPicker trackPicker)
                trackPicker.TrackLayoutChanged += Tracks_LayoutChanged;
            _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsActivateEnd);
        }
        catch
        {
            Close();
            throw;
        }
    }

    private readonly record struct AnchorPlacement(NativePlayerOverlay.Rect Work, int Left, int Top, int Right,
        int Bottom, double AnchorScale, int ClientTop, int? PictureTop);

    private AnchorPlacement GetAnchorPlacement(FrameworkElement anchor)
    {
        var xamlRoot = anchor.XamlRoot ?? throw new InvalidOperationException("设置按钮尚未连接到窗口。");
        var anchorWindow = Microsoft.UI.Win32Interop.GetWindowFromWindowId(xamlRoot.ContentIslandEnvironment.AppWindowId);
        var origin = new NativeVideoWindow.NativePoint();
        if (!ClientToScreen(anchorWindow, ref origin)) throw NativeFailure("ClientToScreen");
        var logical = anchor.TransformToVisual(xamlRoot.Content).TransformPoint(new Point(0, 0));
        var scale = xamlRoot.RasterizationScale;
        var right = checked(origin.X + (int)Math.Ceiling((logical.X + anchor.ActualWidth) * scale));
        var left = checked(origin.X + (int)Math.Floor(logical.X * scale));
        var top = checked(origin.Y + (int)Math.Floor(logical.Y * scale));
        var bottom = checked(origin.Y + (int)Math.Ceiling((logical.Y + anchor.ActualHeight) * scale));
        var monitor = MonitorFromPoint(new NativeVideoWindow.NativePoint { X = right, Y = top }, 2);
        var information = new NativeVideoWindow.MonitorInfo { Size = Marshal.SizeOf<NativeVideoWindow.MonitorInfo>() };
        if (monitor == 0 || !NativeVideoWindow.GetMonitorInfoW(monitor, ref information)) throw NativeFailure("GetMonitorInfoW");
        int? pictureTop = null;
        if (_pictureBounds is { } picture)
        {
            var seekTop = picture.SeekBar.TransformToVisual(xamlRoot.Content).TransformPoint(new Point(0, 0)).Y;
            var titleBottom = picture.TopBar.TransformToVisual(xamlRoot.Content).TransformPoint(new Point(0, picture.TopBar.ActualHeight)).Y;
            top = Math.Min(top, checked(origin.Y + (int)Math.Floor(seekTop * scale)));
            pictureTop = checked(origin.Y + (int)Math.Ceiling(titleBottom * scale));
        }
        return new AnchorPlacement(information.Work, left, top, right, bottom, scale, origin.Y, pictureTop);
    }

    private void PlaceAndMeasure(AnchorPlacement anchor, double scale)
    {
        _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsMeasureBegin);
        var work = anchor.Work;
        var gap = Math.Max(1, (int)Math.Ceiling(8 * scale));
        var availableWidth = Math.Max(1, work.Right - work.Left - gap * 2);
        var availableHeight = Math.Max(1, work.Bottom - work.Top - gap * 2);
        var (width, height) = MeasurePanel(scale, availableWidth, availableHeight);
        var roomAbove = anchor.Top - work.Top - gap * 2;
        var roomBelow = work.Bottom - anchor.Bottom - gap * 2;
        var roomInPicture = anchor.PictureTop is { } pictureTop ? anchor.Top - Math.Max(work.Top, pictureTop) - gap * 2 : 0;
        var roomInPlayer = anchor.PictureTop is not null ? anchor.Top - Math.Max(work.Top, anchor.ClientTop) - gap * 2 : 0;
        var minimumViewport = MinimumMenuViewport * scale;
        var roomInside = roomInPicture >= minimumViewport ? roomInPicture : roomInPlayer;
        var below = height <= roomBelow && (_preferBelow || height > roomAbove);
        var largerSide = Math.Max(roomAbove, roomBelow);
        if (roomInside >= minimumViewport)
        {
            below = false;
            if (height > roomInside) (width, height) = MeasurePanel(scale, availableWidth, roomInside);
        }
        else if (_playerMenuContent is not null && height > largerSide && largerSide >= minimumViewport)
        {
            below = roomBelow > roomAbove || (roomBelow == roomAbove && _preferBelow);
            (width, height) = MeasurePanel(scale, availableWidth, largerSide);
        }
        var left = Math.Clamp(_preferBelow ? anchor.Left : anchor.Right - width, work.Left + gap, work.Right - gap - width);
        var top = Math.Clamp(below ? anchor.Bottom + gap : anchor.Top - gap - height, work.Top + gap, work.Bottom - gap - height);
        if ((!NativePlayerOverlay.GetWindowRect(_window, out var current) ||
            current.Left != left || current.Top != top || current.Right != left + width || current.Bottom != top + height) &&
            !NativeVideoWindow.SetWindowPos(_window, 0, left, top, width, height, 0x0214)) // NOACTIVATE | NOZORDER | NOOWNERZORDER.
            throw NativeFailure("SetWindowPos");
        _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsMeasureEnd);
    }

    private (int Width, int Height) MeasurePanel(double scale, int availableWidth, int availableHeight)
    {
        var maxWidth = Math.Min(double.IsFinite(_presenter.Width) ? _presenter.Width : 420, availableWidth / scale);
        var maxHeight = availableHeight / scale;
        _root.MaxWidth = _presenter.MaxWidth = maxWidth;
        _root.MaxHeight = _presenter.MaxHeight = maxHeight;
        _presenter.MinWidth = Math.Min(_presenter.MinWidth, maxWidth);
        _presenter.MinHeight = Math.Min(_presenter.MinHeight, maxHeight);
        if (_playerMenuContent is { } content && _presenter is FlyoutPresenter presenter)
        {
            var padding = presenter.Padding;
            content.MaxWidth = Math.Max(1, maxWidth - padding.Left - padding.Right);
            content.MaxHeight = Math.Max(1, maxHeight - padding.Top - padding.Bottom);
        }
        if (_presenter is Control control) control.ApplyTemplate();
        _root.Measure(new Size(maxWidth, maxHeight));
        var width = Math.Clamp((int)Math.Ceiling(_root.DesiredSize.Width * scale), 1, availableWidth);
        var height = Math.Clamp((int)Math.Ceiling(_root.DesiredSize.Height * scale), 1, availableHeight);
        return (width, height);
    }

    private void Tracks_LayoutChanged(object? sender, EventArgs args)
    {
        if (_trackLayoutQueued || _closed || _closing) return;
        _trackLayoutQueued = DispatcherQueue.TryEnqueue(Microsoft.UI.Dispatching.DispatcherQueuePriority.Low, RefreshTrackLayout);
    }

    private void RefreshTrackLayout()
    {
        _trackLayoutQueued = false;
        if (_closed || _closing || _returnFocusTarget is not { IsLoaded: true, Visibility: Visibility.Visible } anchor)
            return;
        try
        {
            var placement = GetAnchorPlacement(anchor);
            _root.InvalidateMeasure();
            PlaceAndMeasure(placement, _root.XamlRoot?.RasterizationScale ?? placement.AnchorScale);
        }
        catch (Win32Exception error)
        {
            System.Diagnostics.Trace.TraceWarning($"Playback track menu resize failed: {error.NativeErrorCode}");
        }
    }

    private void Window_Activated(object sender, WindowActivatedEventArgs args)
    {
        _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsActivation, (int)args.WindowActivationState);
        if (_lightDismiss && _shown && args.WindowActivationState == WindowActivationState.Deactivated)
            DispatcherQueue.TryEnqueue(Microsoft.UI.Dispatching.DispatcherQueuePriority.Low, DismissIfOutside);
    }

    private void DismissIfOutside()
    {
        if (!_shown || _closing || _closed) return;
        var current = NativePlayerOverlay.GetForegroundWindow();
        if (current == 0) return; // Foreground can be empty during an activation transition.
        for (var depth = 0; current != 0 && depth < 32; depth++)
        {
            var root = NativePlayerOverlay.GetAncestor(current, 2); // GA_ROOT.
            if (current == _window || root == _window) return;
            current = NativeVideoWindow.GetWindow(root == 0 ? current : root, 4); // GW_OWNER: keep IME/ComboBox popups alive.
        }
        Close();
    }

    private void Root_KeyDown(object sender, KeyRoutedEventArgs args)
    {
        if (args.Handled || args.Key != VirtualKey.Escape) return;
        args.Handled = true;
        Close();
    }

    private void PlayerMenu_PreviewKeyDown(object sender, KeyRoutedEventArgs args)
    {
        if (args.Key != VirtualKey.Escape) return;
        args.Handled = true;
        CloseWithAnchorFocus();
    }

    private void CloseWithAnchorFocus()
    {
        var target = _returnFocusTarget;
        var restoreFocus = target is not null && NativePlayerOverlay.GetForegroundWindow() == _window;
        if (restoreFocus) NativePlayerOverlay.SetForegroundWindow(_owner);
        Close();
        if (restoreFocus && NativePlayerOverlay.GetForegroundWindow() == _owner &&
            target is { IsLoaded: true, IsEnabled: true, Visibility: Visibility.Visible })
            target.Focus(FocusState.Keyboard);
    }

    private static bool FocusFirstControl(DependencyObject parent, FocusState focusState)
    {
        if (parent is Control { IsEnabled: true, IsTabStop: true, Visibility: Visibility.Visible } control
            && control is not UserControl && control.Focus(focusState)) return true;
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(parent); index++)
            if (FocusFirstControl(VisualTreeHelper.GetChild(parent, index), focusState)) return true;
        return false;
    }

    private nint WindowProcedure(nint window, uint message, nuint wParam, nint lParam, nuint id, nuint reference)
    {
        if (message is 0x0083 or 0x0085) return 0; // Exact client bounds; no classic nonclient frame.
        if (message == 0x0084 && _movable && _shown)
        {
            // Native caption hit-testing moves the borderless surface through the
            // system drag loop; this is more reliable than polling the pointer.
            var lp = lParam.ToInt64();
            var screenX = unchecked((short)(lp & 0xFFFF));
            var screenY = unchecked((short)((lp >> 16) & 0xFFFF));
            if (NativePlayerOverlay.GetWindowRect(window, out var frame))
            {
                var scale = NativeVideoWindow.GetDpiForWindow(window) / 96d;
                var header = (int)Math.Ceiling(56 * scale);
                var reserved = (int)Math.Ceiling(56 * scale);
                var clientX = screenX - frame.Left;
                var clientY = screenY - frame.Top;
                if (clientX >= 0 && clientY >= 0 && clientY < header && clientX < frame.Right - frame.Left - reserved)
                    return 1; // HTCAPTION
            }
        }
        if (message == 0x0086) return NativeVideoWindow.DefSubclassProc(window, message, wParam, -1);
        if (message == 0x0010 && !_closing)
        {
            DispatcherQueue.TryEnqueue(CloseWithAnchorFocus);
            return 0;
        }
        if (message == 0x0082)
        {
            _subclassed = false;
            _closed = true;
            DispatcherQueue.TryEnqueue(Release);
        }
        if (_inputGate is not null && _inputGate.TryHandle(message, wParam, lParam) is nint gated) return gated;
        return NativeVideoWindow.DefSubclassProc(window, message, wParam, lParam);
    }

    private void Window_Closed(object sender, WindowEventArgs args)
    {
        _closed = true;
        Release();
    }

    public new void Close()
    {
        if (_closed || _closing) return;
        VerifyThread();
        _closing = true;
        try
        {
            SystemBackdrop = null;
            _backdrop.Dispose();
            base.Close();
            _closed = true;
            Release();
        }
        finally { if (!_closed) _closing = false; }
    }

    public void Dispose() => Close();

    private void Release()
    {
        if (_released) return;
        _released = true;
        Controls.PopupMaterials.Changed -= UpdatePopupMaterial;
        _root.RemoveHandler(UIElement.KeyDownEvent, _keyHandler);
        if (_playerMenuContent is not null) _root.PreviewKeyDown -= PlayerMenu_PreviewKeyDown;
        if (_playerMenuContent is ScreenboxTrackPicker trackPicker)
            trackPicker.TrackLayoutChanged -= Tracks_LayoutChanged;
        _returnFocusTarget = null;
        _pictureBounds = null;
        _root.RemoveHandler(UIElement.PointerPressedEvent, new PointerEventHandler(Root_PointerPressed));
        _root.RemoveHandler(UIElement.PointerReleasedEvent, new PointerEventHandler(Root_PointerReleased));
        _root.PointerCaptureLost -= Root_PointerCaptureLost;
        if (_moveWatch is not null) { _moveWatch.Stop(); _moveWatch.Tick -= MoveWatch_Tick; _moveWatch = null; }
        Activated -= Window_Activated;
        Closed -= Window_Closed;
        if (_subclassed)
        {
            _subclassed = false;
            NativeVideoWindow.RemoveWindowSubclass(_window, _procedure, 1);
        }
        try { _backdrop.Dispose(); }
        finally
        {
            _root.Children.Clear();
            if (_presenter is FlyoutPresenter presenter) presenter.Content = null;
            var callback = _onClosed;
            _onClosed = null;
            callback?.Invoke();
        }
    }

    private void VerifyThread()
    {
        if (!DispatcherQueue.HasThreadAccess) throw new InvalidOperationException("设置窗口必须在其 UI 线程上使用和释放。");
    }

    private nint GetWindowLong(int index)
    {
        Marshal.SetLastPInvokeError(0);
        var value = GetWindowLongPtrW(_window, index);
        if (value == 0 && Marshal.GetLastPInvokeError() != 0) throw NativeFailure("GetWindowLongPtrW");
        return value;
    }

    private void SetWindowLong(int index, nint value)
    {
        Marshal.SetLastPInvokeError(0);
        var previous = NativePlayerOverlay.SetWindowLongPtrW(_window, index, value);
        if (previous == 0 && Marshal.GetLastPInvokeError() != 0) throw NativeFailure("SetWindowLongPtrW");
    }

    private void SetDwmAttribute(uint attribute, int value) =>
        Marshal.ThrowExceptionForHR(NativeVideoWindow.DwmSetWindowAttribute(_window, attribute, ref value, sizeof(int)));

    private static Win32Exception NativeFailure(string operation) => new(Marshal.GetLastPInvokeError(), $"{operation} 失败。");

    internal sealed class SettingsAcrylicBackdrop(ElementTheme theme, Action<bool> setAvailable) : SystemBackdrop, IDisposable
    {
        private DesktopAcrylicController? _controller;
        private ICompositionSupportsSystemBackdrop? _target;
        private SystemBackdropConfiguration? _configuration;
        private DesktopAcrylicKind _kind = DesktopAcrylicKind.Thin;
        private bool _disposed;
        internal bool IsAttached { get; private set; }

        internal void SetKind(DesktopAcrylicKind kind)
        {
            if (_kind == kind) return;
            _kind = kind;
            if (_controller is not { } controller) return;
            controller.Kind = kind;
            ApplyPalette();
        }

        protected override void OnTargetConnected(ICompositionSupportsSystemBackdrop connectedTarget, XamlRoot xamlRoot)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            if (_target is not null) throw new InvalidOperationException("设置窗口的 Acrylic 目标不能共享。");
            base.OnTargetConnected(connectedTarget, xamlRoot);
            _target = connectedTarget;
            try
            {
                Microsoft.UI.Dispatching.DispatcherQueue.GetForCurrentThread().EnsureSystemDispatcherQueue();
                _configuration = new SystemBackdropConfiguration { Theme = SystemBackdropTheme.Dark, IsInputActive = true };
                CopySystemPolicy(connectedTarget, xamlRoot);
                _controller = new DesktopAcrylicController { Kind = _kind };
                _controller.SetSystemBackdropConfiguration(_configuration);
                ApplyPalette();
                // Keep the SDK's luminosity/noise recipe and system fallback for the selected kind.
                if (!_controller.AddSystemBackdropTarget(connectedTarget))
                    throw new InvalidOperationException("无法连接设置窗口的 Acrylic 背景。");
                IsAttached = true;
            }
            catch (Exception)
            {
                try { ReleaseController(); }
                catch (Exception) { /* The root remains opaque if composition is unavailable. */ }
            }
            setAvailable(IsAttached);
        }

        protected override void OnDefaultSystemBackdropConfigurationChanged(ICompositionSupportsSystemBackdrop target, XamlRoot xamlRoot)
        {
            if (_target is not null && _configuration is not null) CopySystemPolicy(target, xamlRoot);
        }

        private void CopySystemPolicy(ICompositionSupportsSystemBackdrop target, XamlRoot xamlRoot)
        {
            if (_configuration is null) return;
            var policy = GetDefaultSystemBackdropConfiguration(target, xamlRoot);
            _configuration.IsHighContrast = policy.IsHighContrast;
            _configuration.HighContrastBackgroundColor = policy.HighContrastBackgroundColor;
            _configuration.Theme = theme switch { ElementTheme.Dark => SystemBackdropTheme.Dark, ElementTheme.Light => SystemBackdropTheme.Light, _ => policy.Theme };
            _configuration.IsInputActive = true;
            ApplyPalette();
        }

        private void ApplyPalette()
        {
            if (_controller is null || _configuration is null) return;
            var theme = _configuration.Theme == SystemBackdropTheme.Dark ? ElementTheme.Dark : ElementTheme.Light;
            var color = Controls.ThemePalette.Color("FwOwnedPanelFallbackColor", theme);
            _controller.TintColor = color;
            _controller.TintOpacity = 0.15f;
            _controller.FallbackColor = _configuration.IsHighContrast && _configuration.HighContrastBackgroundColor is { } contrast ? contrast : color;
        }

        protected override void OnTargetDisconnected(ICompositionSupportsSystemBackdrop disconnectedTarget) => Disconnect();

        private void Disconnect()
        {
            var target = _target;
            _target = null;
            try { ReleaseController(); }
            finally
            {
                if (target is not null) base.OnTargetDisconnected(target);
                setAvailable(false);
            }
        }

        private void ReleaseController()
        {
            var controller = _controller;
            _controller = null;
            _configuration = null;
            IsAttached = false;
            try { controller?.RemoveAllSystemBackdropTargets(); }
            finally { controller?.Dispose(); }
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            Disconnect();
        }
    }

    [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool ClientToScreen(nint window, ref NativeVideoWindow.NativePoint point);
    [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetCursorPos(out NativeVideoWindow.NativePoint point);
    [DllImport("user32.dll", ExactSpelling = true)]
    private static extern short GetAsyncKeyState(int key);
    [DllImport("user32.dll", ExactSpelling = true)]
    private static extern nint MonitorFromPoint(NativeVideoWindow.NativePoint point, uint flags);
    [DllImport("user32.dll", ExactSpelling = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsWindow(nint window);
    [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)]
    private static extern nint GetWindowLongPtrW(nint window, int index);
}
