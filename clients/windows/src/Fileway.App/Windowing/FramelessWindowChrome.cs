using System.ComponentModel;
using System.Runtime.InteropServices;
using Fileway.App.Controls;
using Fileway.App.MediaSurfaces;
using Microsoft.UI.Input;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Foundation;
using Windows.Graphics;

namespace Fileway.App.Windowing;

/// <summary>Native window behavior around an application-drawn title bar.</summary>
internal sealed class FramelessWindowChrome : IDisposable
{
    private const nuint SubclassId = 0x465743;
    private readonly Window _window;
    private readonly Microsoft.UI.Dispatching.DispatcherQueue _shutdownDispatcher;
    private readonly bool _recordShutdownStages;
    private readonly AppWindow _appWindow;
    private readonly WindowTitleBar _titleBar;
    private readonly nint _handle;
    private readonly SubclassProc _procedure;
    private InputNonClientPointerSource? _nonClientPointerSource;
    private (int Width, int Height, uint Dpi, bool Disabled)? _resizeRegionState;
    private string? _captionRegionsState;
    private bool _windowClosed;
    private bool _subclassed;
    private bool _maximizePressed;
    private bool _trackingNonClient;
    private bool _disposed;
    private bool _preparedForClose;

    internal FramelessWindowChrome(Window window, WindowTitleBar titleBar, bool recordShutdownStages = false)
    {
        ArgumentNullException.ThrowIfNull(window);
        ArgumentNullException.ThrowIfNull(titleBar);
        _window = window;
        _shutdownDispatcher = window.DispatcherQueue;
        _recordShutdownStages = recordShutdownStages;
        _appWindow = window.AppWindow;
        _titleBar = titleBar;
        _handle = WinRT.Interop.WindowNative.GetWindowHandle(window);
        _procedure = WindowProcedure;
        VerifyThread();
        try
        {
            if (!SetWindowSubclass(_handle, _procedure, SubclassId, 0))
                throw new InvalidOperationException("无法安装无边框窗口过程。");
            _subclassed = true;
            _titleBar.MinimizeRequested += TitleBar_MinimizeRequested;
            _titleBar.MaximizeRequested += TitleBar_MaximizeRequested;
            _titleBar.CloseRequested += TitleBar_CloseRequested;
            _titleBar.Loaded += TitleBar_Loaded;
            _titleBar.ActualThemeChanged += TitleBar_ActualThemeChanged;
            _titleBar.LayoutUpdated += TitleBar_LayoutUpdated;
            _appWindow.Changed += AppWindow_Changed;
            _window.Closed += Window_Closed;
            _nonClientPointerSource = InputNonClientPointerSource.GetForWindowId(_appWindow.Id);
            ApplyFrame();
            UpdateMaximized();
        }
        catch (Exception setupError)
        {
            try { Dispose(); }
            catch (Exception cleanupError)
            {
                throw new AggregateException("无边框窗口初始化和资源释放均失败。", setupError, cleanupError);
            }
            throw;
        }
    }

    internal event EventHandler? CloseRequested;

    private void ApplyFrame()
    {
        if (_disposed) return;
        _window.ExtendsContentIntoTitleBar = true;
        // Register caption input with the XAML island as well as the root HWND.
        _window.SetTitleBar(_titleBar.DragRegion);
        if (_appWindow.Presenter is OverlappedPresenter presenter)
        {
            presenter.SetBorderAndTitleBar(false, false);
            presenter.IsResizable = true;
            presenter.IsMinimizable = true;
            presenter.IsMaximizable = true;
        }
        // Preserve system menu, resize, minimize and maximize styles while
        // removing the caption. Drag/double-click/Alt+Space stay native.
        Marshal.SetLastPInvokeError(0);
        var style = GetWindowLongPtrW(_handle, -16);
        if (style == 0 && Marshal.GetLastPInvokeError() != 0) throw NativeFailure("GetWindowLongPtrW");
        Marshal.SetLastPInvokeError(0);
        var previous = SetWindowLongPtrW(_handle, -16, (nint)((style.ToInt64() & ~0x00c00000L) | 0x000f0000L));
        if (previous == 0 && Marshal.GetLastPInvokeError() != 0) throw NativeFailure("SetWindowLongPtrW");
        SetDwmAttribute(2, 2); // DWMNCRP_ENABLED: retain the native shadow.
        SetDwmAttribute(20, _titleBar.ActualTheme == ElementTheme.Dark ? 1 : 0);
        SetDwmAttribute(33, 2); // DWMWCP_ROUND; Windows suppresses rounding when maximized/snapped.
        SetDwmAttribute(34, unchecked((int)0xfffffffeu)); // DWMWA_COLOR_NONE: no native white stroke.
        var shadow = new Margins { Left = 1, Right = 1, Top = 1, Bottom = 1 };
        Marshal.ThrowExceptionForHR(DwmExtendFrameIntoClientArea(_handle, ref shadow));
        if (!SetWindowPos(_handle, 0, 0, 0, 0, 0, 0x0037)) // FRAMECHANGED; preserve bounds/Z order/activation.
            throw NativeFailure("SetWindowPos(SWP_FRAMECHANGED)");
        _resizeRegionState = null;
        UpdateResizeRegions();
    }

    private void TitleBar_MinimizeRequested(object? sender, EventArgs args)
    {
        if (!_disposed && _appWindow.Presenter is OverlappedPresenter presenter) presenter.Minimize();
    }

    private void TitleBar_MaximizeRequested(object? sender, EventArgs args) => ToggleMaximized();

    private void ToggleMaximized()
    {
        if (_disposed || _appWindow.Presenter is not OverlappedPresenter presenter) return;
        _window.Activate();
        if (IsZoomed(_handle)) presenter.Restore();
        else presenter.Maximize();
        UpdateMaximized();
    }

    private void TitleBar_CloseRequested(object? sender, EventArgs args)
    {
        // Use the normal system close path so AppWindow.Closing can cancel it
        // and finish the application's existing asynchronous shutdown.
        if (!_disposed && !PostMessageW(_handle, 0x0112, 0xf060, 0))
            throw NativeFailure("PostMessageW(SC_CLOSE)");
    }

    private void TitleBar_Loaded(object sender, RoutedEventArgs args) => RefreshWindowState();
    private void TitleBar_LayoutUpdated(object? sender, object args) => UpdateCaptionRegions();
    private void AppWindow_Changed(AppWindow sender, AppWindowChangedEventArgs args) => RefreshWindowState();
    private void TitleBar_ActualThemeChanged(FrameworkElement sender, object args)
    {
        if (!_disposed) SetDwmAttribute(20, _titleBar.ActualTheme == ElementTheme.Dark ? 1 : 0);
    }

    private void UpdateMaximized()
    {
        if (!_disposed) _titleBar.SetMaximized(IsZoomed(_handle));
    }

    private void RefreshWindowState()
    {
        UpdateMaximized();
        UpdateResizeRegions();
        UpdateCaptionRegions();
    }

    private void UpdateCaptionRegions()
    {
        if (_disposed || _preparedForClose || _windowClosed || _titleBar.InteractiveRegions.Count == 0 || _nonClientPointerSource is not { } source || _window.Content is not UIElement root) return;
        var rectangles = new List<RectInt32>();
        foreach (var element in _titleBar.InteractiveRegions)
        {
            if (!element.IsLoaded || element.Visibility != Visibility.Visible || element.XamlRoot is null || element.ActualWidth <= 0 || element.ActualHeight <= 0) continue;
            var bounds = element.TransformToVisual(root).TransformBounds(new Rect(0, 0, element.ActualWidth, element.ActualHeight));
            var scale = element.XamlRoot.RasterizationScale;
            rectangles.Add(new RectInt32((int)Math.Floor(bounds.X * scale), (int)Math.Floor(bounds.Y * scale),
                (int)Math.Ceiling(bounds.Width * scale), (int)Math.Ceiling(bounds.Height * scale)));
        }
        var state = string.Join(";", rectangles.Select(rect => $"{rect.X},{rect.Y},{rect.Width},{rect.Height}"));
        if (_captionRegionsState == state) return;
        if (rectangles.Count == 0) source.ClearRegionRects(NonClientRegionKind.Passthrough);
        else source.SetRegionRects(NonClientRegionKind.Passthrough, rectangles.ToArray());
        _captionRegionsState = state;
    }

    private void UpdateResizeRegions()
    {
        if (_disposed || _preparedForClose || _windowClosed || _nonClientPointerSource is not { } source
            || !GetClientRect(_handle, out var bounds)) return;
        var width = Math.Max(0, bounds.Right - bounds.Left);
        var height = Math.Max(0, bounds.Bottom - bounds.Top);
        var dpi = GetDpiForWindow(_handle);
        var disabled = IsZoomed(_handle) || IsIconic(_handle) || width == 0 || height == 0;
        var state = (width, height, dpi, disabled);
        if (_resizeRegionState == state) return;
        if (disabled) ClearResizeRegions(source);
        else
        {
            // These are physical client pixels. Caption remains owned by
            // Window.SetTitleBar; register only the four native sizing borders.
            var padding = GetSystemMetricsForDpi(92, dpi); // SM_CXPADDEDBORDER.
            var edgeX = Math.Clamp(GetSystemMetricsForDpi(32, dpi) + padding, 1, Math.Max(1, width / 2));
            var edgeY = Math.Clamp(GetSystemMetricsForDpi(33, dpi) + padding, 1, Math.Max(1, height / 2));
            source.SetRegionRects(NonClientRegionKind.TopBorder, [new RectInt32(0, 0, width, edgeY)]);
            source.SetRegionRects(NonClientRegionKind.BottomBorder, [new RectInt32(0, height - edgeY, width, edgeY)]);
            source.SetRegionRects(NonClientRegionKind.LeftBorder, [new RectInt32(0, 0, edgeX, height)]);
            source.SetRegionRects(NonClientRegionKind.RightBorder, [new RectInt32(width - edgeX, 0, edgeX, height)]);
        }
        _resizeRegionState = state;
    }

    private static void ClearResizeRegions(InputNonClientPointerSource source)
    {
        source.ClearRegionRects(NonClientRegionKind.TopBorder);
        source.ClearRegionRects(NonClientRegionKind.BottomBorder);
        source.ClearRegionRects(NonClientRegionKind.LeftBorder);
        source.ClearRegionRects(NonClientRegionKind.RightBorder);
    }

    private nint WindowProcedure(nint window, uint message, nuint wParam, nint lParam, nuint id, nuint reference)
    {
        if (_disposed) return DefSubclassProc(window, message, wParam, lParam);
        if (message == 0x0010 && CloseRequested is { } closeRequested)
        {
            // Only owners with an asynchronous shutdown handler intercept close.
            // Image windows retain the default native close path.
            closeRequested.Invoke(this, EventArgs.Empty);
            return 0;
        }
        if (message is 0x0083 or 0x0085) return 0; // Full-window client; no classic nonclient paint.
        if (message == 0x0086) return DefSubclassProc(window, message, wParam, -1);
        if (message == 0x0084) return HitTest(PointFromLParam(lParam));
        if (message == 0x00a0) // WM_NCMOUSEMOVE: retain native hover processing for the Win11 Snap menu.
        {
            if (wParam == 9)
            {
                SetMaximizeVisual(_maximizePressed ? "Pressed" : "PointerOver");
                if (!_trackingNonClient)
                {
                    var tracking = new MouseTracking { Size = (uint)Marshal.SizeOf<MouseTracking>(), Flags = 0x0012, Window = window };
                    _trackingNonClient = TrackMouseEvent(ref tracking);
                }
            }
            else if (!_maximizePressed) SetMaximizeVisual("Normal");
        }
        if ((message is 0x00a1 or 0x00a3) && wParam == 9) // Custom maximize button nonclient press/double-click.
        {
            _window.Activate();
            _maximizePressed = true;
            SetCapture(window);
            SetMaximizeVisual("Pressed");
            return 0;
        }
        if (_maximizePressed && message == 0x0200) // Captured WM_MOUSEMOVE uses client coordinates.
        {
            var point = PointFromLParam(lParam);
            if (ClientToScreen(window, ref point))
                SetMaximizeVisual(IsOverElement(point, _titleBar.MaximizeButtonElement) ? "Pressed" : "Normal");
            return 0;
        }
        if (_maximizePressed && (message is 0x0202 or 0x00a2))
        {
            var point = PointFromLParam(lParam);
            var validPoint = message == 0x00a2 || ClientToScreen(window, ref point);
            var invoke = validPoint && IsOverElement(point, _titleBar.MaximizeButtonElement);
            _maximizePressed = false;
            if (GetCapture() == window) ReleaseCapture();
            SetMaximizeVisual(invoke ? "PointerOver" : "Normal");
            if (invoke) _window.DispatcherQueue.TryEnqueue(ToggleMaximized);
            return 0;
        }
        if (message is 0x02a2 or 0x0215 or 0x001f) // NCMOUSELEAVE / CAPTURECHANGED / CANCELMODE.
        {
            _trackingNonClient = false;
            if (message != 0x02a2 || !_maximizePressed)
            {
                _maximizePressed = false;
                SetMaximizeVisual("Normal");
            }
            if (message == 0x001f && GetCapture() == window) ReleaseCapture();
        }
        if (message == 0x0082)
        {
            _windowClosed = true;
            _subclassed = false; // Native destruction releases the subclass chain.
            Dispose();
            return DefSubclassProc(window, message, wParam, lParam);
        }

        // WinUI receives its native DPI notification before we apply the OS
        // suggested physical rectangle, so XAML and the HWND use the same DPI.
        var suggested = message == 0x02e0 ? Marshal.PtrToStructure<NativeRect>(lParam) : default;
        var result = DefSubclassProc(window, message, wParam, lParam);
        if (message == 0x0024 && lParam != 0 && TryGetMonitor(out var monitor))
        {
            var limits = Marshal.PtrToStructure<MinMaxInfo>(lParam);
            limits.MaximumPosition = new NativePoint { X = monitor.Work.Left - monitor.Monitor.Left, Y = monitor.Work.Top - monitor.Monitor.Top };
            limits.MaximumSize = new NativePoint { X = monitor.Work.Right - monitor.Work.Left, Y = monitor.Work.Bottom - monitor.Work.Top };
            var minimumScale = GetDpiForWindow(window) / 96d;
            if (_titleBar.MinimumWindowWidth > 0) limits.MinimumTrackSize.X = (int)Math.Ceiling(_titleBar.MinimumWindowWidth * minimumScale);
            if (_titleBar.MinimumWindowHeight > 0) limits.MinimumTrackSize.Y = (int)Math.Ceiling(_titleBar.MinimumWindowHeight * minimumScale);
            Marshal.StructureToPtr(limits, lParam, false);
        }
        if (message == 0x02e0 && !_disposed)
        {
            if (IsZoomed(window) && TryGetMonitor(out var dpiMonitor)) suggested = dpiMonitor.Work;
            if (!IsIconic(window))
                SetWindowPos(window, 0, suggested.Left, suggested.Top, suggested.Right - suggested.Left,
                    suggested.Bottom - suggested.Top, 0x0014); // NOACTIVATE | NOZORDER.
        }
        if (message is 0x0005 or 0x02e0) _window.DispatcherQueue.TryEnqueue(RefreshWindowState);
        if (message == 0x031e) _window.DispatcherQueue.TryEnqueue(ApplyFrame); // DWM composition restart.
        return result;
    }

    private nint HitTest(NativePoint point)
    {
        if (!IsZoomed(_handle) && GetWindowRect(_handle, out var bounds))
        {
            var dpi = GetDpiForWindow(_handle);
            var edge = Math.Max(1, GetSystemMetricsForDpi(32, dpi) + GetSystemMetricsForDpi(92, dpi));
            var left = point.X >= bounds.Left && point.X < bounds.Left + edge;
            var right = point.X < bounds.Right && point.X >= bounds.Right - edge;
            var top = point.Y >= bounds.Top && point.Y < bounds.Top + edge;
            var bottom = point.Y < bounds.Bottom && point.Y >= bounds.Bottom - edge;
            if (top) return left ? 13 : right ? 14 : 12;
            if (bottom) return left ? 16 : right ? 17 : 15;
            if (left) return 10;
            if (right) return 11;
        }
        if (IsOverElement(point, _titleBar.MaximizeButtonElement)) return 9; // HTMAXBUTTON: official Snap menu contract.
        if (_titleBar.InteractiveRegions.Any(element => IsOverElement(point, element))) return 1;
        return IsOverElement(point, _titleBar.DragRegion) ? 2 : 1; // HTCAPTION / HTCLIENT.
    }

    private bool IsOverElement(NativePoint screenPoint, FrameworkElement element)
    {
        if (!element.IsLoaded || element.Visibility != Visibility.Visible || element.XamlRoot is null
            || _window.Content is not UIElement root || !ScreenToClient(_handle, ref screenPoint)) return false;
        var bounds = element.TransformToVisual(root).TransformBounds(new Rect(0, 0, element.ActualWidth, element.ActualHeight));
        var scale = element.XamlRoot.RasterizationScale;
        return bounds.Contains(new Point(screenPoint.X / scale, screenPoint.Y / scale));
    }

    private void SetMaximizeVisual(string state)
    {
        if (_titleBar.MaximizeButtonElement is Control control) VisualStateManager.GoToState(control, state, true);
    }

    private bool TryGetMonitor(out MonitorInfo information)
    {
        information = new MonitorInfo { Size = Marshal.SizeOf<MonitorInfo>() };
        return GetMonitorInfoW(MonitorFromWindow(_handle, 2), ref information);
    }

    private void SetDwmAttribute(uint attribute, int value) =>
        Marshal.ThrowExceptionForHR(DwmSetWindowAttribute(_handle, attribute, ref value, sizeof(int)));

    private static NativePoint PointFromLParam(nint value) => new()
    {
        X = unchecked((short)(value.ToInt64() & 0xffff)),
        Y = unchecked((short)((value.ToInt64() >> 16) & 0xffff)),
    };

    private void Window_Closed(object sender, WindowEventArgs args)
    {
        var uiThread = _shutdownDispatcher.HasThreadAccess;
        if (_recordShutdownStages) ShutdownDiagnostics.Record(ShutdownDiagnosticStage.ChromeClosedEntered, uiThread);
        _windowClosed = true;
        Dispose();
        if (_recordShutdownStages) ShutdownDiagnostics.Record(ShutdownDiagnosticStage.ChromeClosedReturned, uiThread);
    }
    private void VerifyThread()
    {
        if (!_window.DispatcherQueue.HasThreadAccess) throw new InvalidOperationException("窗口 chrome 必须在其 UI 线程上使用和释放。");
    }

    /// <summary>
    /// Removes native input ownership while the window is still alive. WinUI raises
    /// Closed from inside native destruction, so waiting until that callback to
    /// remove a subclass can leave a stale managed callback in the destroy path.
    /// The Closed subscription remains until the callback returns for diagnostics.
    /// </summary>
    internal void PrepareForClose()
    {
        VerifyThread();
        if (_disposed || _preparedForClose) return;
        var source = _nonClientPointerSource;
        _resizeRegionState = null;
        _captionRegionsState = null;
        if (source is not null)
        {
            ClearResizeRegions(source);
            source.ClearRegionRects(NonClientRegionKind.Passthrough);
        }
        // SetTitleBar installs a XAML non-client callback owned by the window.
        // Detach it before Close so native destruction does not call back into
        // a content tree that is already being torn down.
        _window.SetTitleBar(null);
        if (GetCapture() == _handle) ReleaseCapture();
        if (_subclassed)
        {
            if (!RemoveWindowSubclass(_handle, _procedure, SubclassId))
                throw new InvalidOperationException("无法在主窗口关闭前释放无边框窗口过程。");
            _subclassed = false;
        }
        _preparedForClose = true;
    }

    public void Dispose()
    {
        if (_disposed) return;
        VerifyThread();
        _disposed = true;
        _titleBar.MinimizeRequested -= TitleBar_MinimizeRequested;
        _titleBar.MaximizeRequested -= TitleBar_MaximizeRequested;
        _titleBar.CloseRequested -= TitleBar_CloseRequested;
        _titleBar.Loaded -= TitleBar_Loaded;
        _titleBar.ActualThemeChanged -= TitleBar_ActualThemeChanged;
        _titleBar.LayoutUpdated -= TitleBar_LayoutUpdated;
        _appWindow.Changed -= AppWindow_Changed;
        _window.Closed -= Window_Closed;
        CloseRequested = null;
        var source = _nonClientPointerSource;
        _nonClientPointerSource = null;
        _resizeRegionState = null;
        var removeFailed = false;
        try
        {
            // A closed window releases its input source itself. Explicit early
            // disposal removes only our borders, never WinUI's caption region.
            if (!_windowClosed && !_preparedForClose && source is not null)
            {
                ClearResizeRegions(source);
                if (_captionRegionsState is not null) source.ClearRegionRects(NonClientRegionKind.Passthrough);
            }
        }
        finally
        {
            if (GetCapture() == _handle) ReleaseCapture();
            if (_subclassed)
            {
                _subclassed = false;
                removeFailed = !RemoveWindowSubclass(_handle, _procedure, SubclassId);
            }
        }
        if (removeFailed) throw new InvalidOperationException("无法释放无边框窗口过程。");
    }

    private static Win32Exception NativeFailure(string operation) => new(Marshal.GetLastPInvokeError(), $"{operation} 失败。");
    private delegate nint SubclassProc(nint window, uint message, nuint wParam, nint lParam, nuint id, nuint reference);
    [StructLayout(LayoutKind.Sequential)] private struct NativePoint { internal int X, Y; }
    [StructLayout(LayoutKind.Sequential)] private struct NativeRect { internal int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] private struct Margins { internal int Left, Right, Top, Bottom; }
    [StructLayout(LayoutKind.Sequential)] private struct MinMaxInfo { internal NativePoint Reserved, MaximumSize, MaximumPosition, MinimumTrackSize, MaximumTrackSize; }
    [StructLayout(LayoutKind.Sequential)] private struct MonitorInfo { internal int Size; internal NativeRect Monitor, Work; internal uint Flags; }
    [StructLayout(LayoutKind.Sequential)] private struct MouseTracking { internal uint Size, Flags; internal nint Window; internal uint HoverTime; }

    [DllImport("comctl32.dll", ExactSpelling = true)] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWindowSubclass(nint window, SubclassProc procedure, nuint id, nuint reference);
    [DllImport("comctl32.dll", ExactSpelling = true)] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool RemoveWindowSubclass(nint window, SubclassProc procedure, nuint id);
    [DllImport("comctl32.dll", ExactSpelling = true)]
    private static extern nint DefSubclassProc(nint window, uint message, nuint wParam, nint lParam);
    [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)]
    private static extern nint GetWindowLongPtrW(nint window, int index);
    [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)]
    private static extern nint SetWindowLongPtrW(nint window, int index, nint value);
    [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWindowPos(nint window, nint after, int x, int y, int width, int height, uint flags);
    [DllImport("user32.dll", ExactSpelling = true)] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetWindowRect(nint window, out NativeRect rectangle);
    [DllImport("user32.dll", ExactSpelling = true)] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetClientRect(nint window, out NativeRect rectangle);
    [DllImport("user32.dll", ExactSpelling = true)] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool ScreenToClient(nint window, ref NativePoint point);
    [DllImport("user32.dll", ExactSpelling = true)] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool ClientToScreen(nint window, ref NativePoint point);
    [DllImport("user32.dll", ExactSpelling = true)] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsZoomed(nint window);
    [DllImport("user32.dll", ExactSpelling = true)] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsIconic(nint window);
    [DllImport("user32.dll", ExactSpelling = true)] private static extern uint GetDpiForWindow(nint window);
    [DllImport("user32.dll", ExactSpelling = true)] private static extern int GetSystemMetricsForDpi(int index, uint dpi);
    [DllImport("user32.dll", ExactSpelling = true)] private static extern nint MonitorFromWindow(nint window, uint flags);
    [DllImport("user32.dll", ExactSpelling = true)] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetMonitorInfoW(nint monitor, ref MonitorInfo info);
    [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool PostMessageW(nint window, uint message, nuint wParam, nint lParam);
    [DllImport("user32.dll", ExactSpelling = true)] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool TrackMouseEvent(ref MouseTracking tracking);
    [DllImport("user32.dll", ExactSpelling = true)] private static extern nint SetCapture(nint window);
    [DllImport("user32.dll", ExactSpelling = true)] private static extern nint GetCapture();
    [DllImport("user32.dll", ExactSpelling = true)] [return: MarshalAs(UnmanagedType.Bool)] private static extern bool ReleaseCapture();
    [DllImport("dwmapi.dll", ExactSpelling = true)] private static extern int DwmSetWindowAttribute(nint window, uint attribute, ref int value, int size);
    [DllImport("dwmapi.dll", ExactSpelling = true)] private static extern int DwmExtendFrameIntoClientArea(nint window, ref Margins margins);
}
