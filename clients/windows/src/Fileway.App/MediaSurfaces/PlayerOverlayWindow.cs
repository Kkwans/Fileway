using System.Runtime.InteropServices;
using Fileway.App.Controls.Playback;
using Fileway.Core.Contracts;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.Foundation;
using Windows.System;
using Windows.UI.ViewManagement;

namespace Fileway.App.MediaSurfaces;

/// <summary>A complete owned WinUI window, separate from the native video HWND.</summary>
internal sealed class PlayerOverlayWindow : Window, IDisposable
{
    private readonly ScreenboxPlayerControls _controls;
    private readonly NativeVideoWindow.SubclassProc _procedure;
    private readonly nint _window;
    private readonly nint _owner;
    private readonly PlayerBackdropState _backdropActivity = new();
    private readonly UISettings _policySettings = new();
    private readonly AccessibilitySettings _policyAccessibility = new();
    private bool _ownerFullscreen;
    private bool _disposed;
    private bool _activatedOnce;
    private bool _ownerVisible;
    private (int Width, int Height, int Top, int Bottom)? _materialRegion;

    internal PlayerOverlayWindow(IPlaybackSession session, nint owner)
    {
        _owner = owner;
        Title = "Fileway 播放控制";
        _window = WinRT.Interop.WindowNative.GetWindowHandle(this);
        _procedure = WindowProcedure;
        try
        {
            var initialRegion = NativePlayerOverlay.CreateRectRgn(0, 0, 0, 0);
            if (initialRegion == 0) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
            if (NativePlayerOverlay.SetWindowRgn(_window, initialRegion, true) == 0)
            {
                NativePlayerOverlay.DeleteObject(initialRegion);
                throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
            }
            _controls = new ScreenboxPlayerControls(session);
            RefreshBackdropState();
            EnableHostBackdrop();
            UpdateBackdropFactory();
            Content = _controls;
            // Assign acrylic only after the bounded native region exists.
            EnableHostBackdrop();
            if (AppWindow.Presenter is OverlappedPresenter presenter)
            {
                presenter.SetBorderAndTitleBar(false, false);
                presenter.IsResizable = false;
                presenter.IsMinimizable = false;
                presenter.IsMaximizable = false;
            }
            // Ownership, not TopMost: another application's foreground window
            // remains above this whole video/HUD group.
            NativePlayerOverlay.SetWindowLongPtrW(_window, -8, owner);
            var extended = NativePlayerOverlay.GetWindowLongPtrW(_window, -20).ToInt64();
            NativePlayerOverlay.SetWindowLongPtrW(_window, -20, (nint)((extended | 0x80) & ~0x40000L));
            if (NativeVideoWindow.GetWindow(_window, 4) != owner)
                throw new InvalidOperationException("无法绑定播放器控制窗口。");
            NativeVideoWindow.SetWindowSubclass(_window, _procedure, 2, 0);
            EnableHostBackdrop();
            _controls.CloseRequested += Controls_CloseRequested;
            _controls.ToggleFullscreenRequested += Controls_ToggleFullscreenRequested;
            _controls.AddSubtitleRequested += Controls_AddSubtitleRequested;
            _controls.MinimizeRequested += Controls_MinimizeRequested;
            _controls.MaximizeRequested += Controls_MaximizeRequested;
            _controls.KeyDown += Controls_KeyDown;
            _controls.ActualThemeChanged += Controls_ActualThemeChanged;
            _controls.Loaded += Controls_Loaded;
            _controls.MaterialBoundsChanged += Controls_MaterialBoundsChanged;
            _policySettings.AdvancedEffectsEnabledChanged += PolicySettings_Changed;
            Activated += (_, _) => DispatcherQueue.TryEnqueue(() => { EnableHostBackdrop(); RefreshBackdropState(); });
            AppWindow.Closing += (_, args) =>
            {
                if (_disposed) return;
                args.Cancel = true;
                CloseRequested?.Invoke(this, EventArgs.Empty);
            };
        }
        catch
        {
            _controls?.Dispose();
            Close();
            throw;
        }
    }

    internal event EventHandler? CloseRequested;
    internal event EventHandler? ToggleFullscreenRequested;
    internal event EventHandler? ExitFullscreenRequested;
    internal event EventHandler? AddSubtitleRequested;
    internal event EventHandler? MinimizeRequested;
    internal event EventHandler? MaximizeRequested;

    private void Controls_CloseRequested(object? sender, EventArgs args) => CloseRequested?.Invoke(this, EventArgs.Empty);
    private void Controls_ToggleFullscreenRequested(object? sender, EventArgs args) => ToggleFullscreenRequested?.Invoke(this, EventArgs.Empty);
    private void Controls_AddSubtitleRequested(object? sender, EventArgs args)
    {
        if (!_disposed) AddSubtitleRequested?.Invoke(this, EventArgs.Empty);
    }
    private void Controls_MinimizeRequested(object? sender, EventArgs args) => MinimizeRequested?.Invoke(this, EventArgs.Empty);
    private void Controls_MaximizeRequested(object? sender, EventArgs args) => MaximizeRequested?.Invoke(this, EventArgs.Empty);

    internal void SetWindowMaximized(bool maximized)
    {
        if (!_disposed) _controls.SetWindowMaximized(maximized);
    }

    internal bool IsTitleDragPoint(NativeVideoWindow.NativePoint screenPoint)
    {
        if (_disposed || _controls.XamlRoot is null) return false;
        NativePlayerOverlay.ScreenToClient(_window, ref screenPoint);
        var scale = _controls.XamlRoot.RasterizationScale;
        return _controls.GetTitleBarDragBounds().Contains(new Point(screenPoint.X / scale, screenPoint.Y / scale));
    }

    internal void ReportError(string message)
    {
        if (!_disposed) _controls.ReportError(message);
    }

    private void Controls_ActualThemeChanged(FrameworkElement sender, object args) => RefreshBackdropState();
    private void Controls_Loaded(object sender, RoutedEventArgs args)
    {
        UpdateMaterialVisibility();
        DispatcherQueue.TryEnqueue(Microsoft.UI.Dispatching.DispatcherQueuePriority.Low, RecordIconDiagnostics);
    }

    private void Controls_MaterialBoundsChanged(object? sender, EventArgs args) => UpdateMaterialVisibility();

    private void UpdateMaterialVisibility()
    {
        if (_disposed) return;
        if (!_controls.ControlsVisible || !_ownerVisible)
        {
            // An empty visible owned HWND can retain activation and obstruct
            // foreground recovery. Hide it and return focus only within this group.
            if (NativePlayerOverlay.GetForegroundWindow() == _window)
                NativePlayerOverlay.SetForegroundWindow(_owner);
            NativeVideoWindow.ShowWindow(_window, 0);
            return;
        }
        if (UpdateMaterialRegion()) NativeVideoWindow.ShowWindow(_window, 4);
    }

    private bool UpdateMaterialRegion()
    {
        if (_disposed || _controls.XamlRoot is null || !NativePlayerOverlay.GetClientRect(_window, out var bounds)) return false;
        var scale = _controls.XamlRoot.RasterizationScale;
        var (top, bottom) = _controls.GetMaterialInsets();
        var width = Math.Max(0, bounds.Right - bounds.Left);
        var height = Math.Max(0, bounds.Bottom - bounds.Top);
        var topPixels = Math.Clamp((int)Math.Ceiling(top * scale), 0, height);
        var bottomPixels = Math.Clamp((int)Math.Ceiling(bottom * scale), 0, height);
        var regionKey = (width, height, topPixels, bottomPixels);
        if (_materialRegion == regionKey) return true;
        var region = NativePlayerOverlay.CreateRectRgn(0, 0, width, topPixels);
        var footer = NativePlayerOverlay.CreateRectRgn(0, height - bottomPixels, width, height);
        if (region == 0 || footer == 0)
        {
            if (region != 0) NativePlayerOverlay.DeleteObject(region);
            if (footer != 0) NativePlayerOverlay.DeleteObject(footer);
            NativeVideoWindow.ShowWindow(_window, 0); // Fail closed: no full-movie material.
            return false;
        }
        try
        {
            if (NativePlayerOverlay.CombineRgn(region, region, footer, 2) == 0
                || NativePlayerOverlay.SetWindowRgn(_window, region, true) == 0)
            {
                NativeVideoWindow.ShowWindow(_window, 0);
                return false;
            }
            region = 0; // A successful SetWindowRgn transfers ownership to USER32.
            _materialRegion = regionKey;
            SystemBackdrop ??= new NativePlayerAcrylicBackdrop(_backdropActivity);
            return true;
        }
        finally
        {
            NativePlayerOverlay.DeleteObject(footer);
            if (region != 0) NativePlayerOverlay.DeleteObject(region);
        }
    }

    private void RecordIconDiagnostics()
    {
        if (_disposed) return;
        try
        {
            var root = Environment.GetEnvironmentVariable("FILEWAY_WINDOWS_DATA_ROOT");
            if (string.IsNullOrEmpty(root)) return;
            var errors = new List<string>();
            void Collect(DependencyObject element)
            {
                if (element is ScreenboxVectorIcon { LoadDiagnostic: { } diagnostic }) errors.Add(diagnostic);
                for (var index = 0; index < VisualTreeHelper.GetChildrenCount(element); index++)
                    Collect(VisualTreeHelper.GetChild(element, index));
            }
            Collect(_controls);
            var directory = Path.Combine(root, "diagnostics");
            Directory.CreateDirectory(directory);
            File.WriteAllText(Path.Combine(directory, "player-icons.json"), System.Text.Json.JsonSerializer.Serialize(errors));
        }
        catch (Exception) { }
    }

    private void UpdateBackdropFactory()
    {
        if (_disposed) return;
        Func<SystemBackdrop>? popupFactory = null;
        if (NativePlayerAcrylicBackdrop.IsSupported && _policySettings.AdvancedEffectsEnabled && !_policyAccessibility.HighContrast)
            popupFactory = static () => new DesktopAcrylicBackdrop();
        _controls.SetFlyoutBackdropFactory(popupFactory);
    }

    private void PolicySettings_Changed(UISettings sender, object args) => DispatcherQueue.TryEnqueue(UpdateBackdropFactory);

    private void EnableHostBackdrop()
    {
        if (_disposed) return;
        // Official DWMWA_USE_HOSTBACKDROPBRUSH (17), Windows 11 22000+.
        // Only the owned HUD HWND is changed; never the video/HDR HWND.
        var enabled = 1;
        _backdropActivity.SetHostBackdropResult(NativeVideoWindow.DwmSetWindowAttribute(_window, 17, ref enabled, sizeof(int)));
        var noBorder = unchecked((int)0xfffffffeu);
        _ = NativeVideoWindow.DwmSetWindowAttribute(_window, 34, ref noBorder, sizeof(int));
        var dark = 1;
        _ = NativeVideoWindow.DwmSetWindowAttribute(_window, 20, ref dark, sizeof(int));
        var margins = new NativePlayerOverlay.Margins { Left = -1, Right = -1, Top = -1, Bottom = -1 };
        _ = NativePlayerOverlay.DwmExtendFrameIntoClientArea(_window, ref margins);
        var dc = NativePlayerOverlay.GetDC(_window);
        if (dc != 0)
        {
            try { ClearBackground(dc); }
            finally { _ = NativePlayerOverlay.ReleaseDC(_window, dc); }
        }
    }

    private bool ClearBackground(nint dc)
    {
        if (!NativePlayerOverlay.GetClientRect(_window, out var rectangle)) return false;
        return NativePlayerOverlay.FillRect(dc, ref rectangle, NativePlayerOverlay.GetStockObject(4)) != 0;
    }

    internal void RefreshBackdropState()
    {
        if (_disposed) return;
        var current = NativePlayerOverlay.GetForegroundWindow();
        var active = false;
        for (var depth = 0; current != 0 && depth < 16; depth++)
        {
            var root = NativePlayerOverlay.GetAncestor(current, 2); // GA_ROOT.
            if (current == _window || current == _owner || root == _window || root == _owner)
            {
                active = true;
                break;
            }
            current = NativeVideoWindow.GetWindow(root == 0 ? current : root, 4); // GW_OWNER.
        }
        _backdropActivity.Update(active, _controls.ActualTheme);
    }

    internal void ApplySnapshot(PlaybackSnapshot snapshot)
    {
        if (!_disposed)
        {
            RefreshBackdropState();
            _controls.ApplySnapshot(snapshot);
        }
    }

    internal void SetFullscreen(bool fullscreen)
    {
        _ownerFullscreen = fullscreen;
        if (!_disposed) _controls.SetFullscreen(fullscreen);
    }

    internal void NotifyActivity()
    {
        if (!_disposed)
        {
            RefreshBackdropState();
            _controls.NotifyActivity();
        }
    }

    internal void ShowControls()
    {
        if (_disposed) return;
        _controls.ShowControls();
        _activatedOnce = true;
        Activate();
        FocusFirstControl(_controls);
    }

    private static bool FocusFirstControl(DependencyObject parent)
    {
        if (parent is Control { IsEnabled: true, IsTabStop: true, Visibility: Visibility.Visible } control
            && control is not UserControl && control.Focus(FocusState.Keyboard)) return true;
        for (int i = 0; i < VisualTreeHelper.GetChildrenCount(parent); i++)
            if (FocusFirstControl(VisualTreeHelper.GetChild(parent, i))) return true;
        return false;
    }

    internal void SyncBounds(NativePlayerOverlay.Rect bounds, bool visible)
    {
        if (_disposed) return;
        RefreshBackdropState();
        _ownerVisible = visible && !NativePlayerOverlay.IsIconic(_owner) && NativePlayerOverlay.IsWindowVisible(_owner);
        if (!_ownerVisible)
        {
            NativeVideoWindow.ShowWindow(_window, 0);
            return;
        }
        // All values are physical pixels from the owner's actual video HWND.
        // Preserve z-order while moving, especially when the owner is inactive.
        NativeVideoWindow.SetWindowPos(_window, 0, bounds.Left, bounds.Top,
            Math.Max(1, bounds.Right - bounds.Left), Math.Max(1, bounds.Bottom - bounds.Top), 0x0014);
        if (!_activatedOnce && NativePlayerOverlay.GetForegroundWindow() == _owner)
        {
            // WinUI's first Activate completes the content lifetime. Only do
            // this while the owner is already foreground, then restore it.
            _activatedOnce = true;
            Activate();
            NativePlayerOverlay.SetForegroundWindow(_owner);
        }
        UpdateMaterialVisibility();
    }

    internal void Hide() { if (!_disposed) { _ownerVisible = false; NativeVideoWindow.ShowWindow(_window, 0); } }

    private nint WindowProcedure(nint window, uint message, nuint wParam, nint lParam, nuint id, nuint reference)
    {
        if (!_disposed && message == 0x0083) return 0; // Entire owned window is client content.
        if (!_disposed && message == 0x0085) return 0; // No classic nonclient border paint.
        if (!_disposed && message == 0x0086)
            return NativeVideoWindow.DefSubclassProc(window, message, wParam, (nint)(-1));
        if (!_disposed && message == 0x0014 && ClearBackground((nint)wParam)) return 1;
        if (!_disposed && (message is 0x0006 or 0x001c)) DispatcherQueue.TryEnqueue(RefreshBackdropState);
        // AccessibilitySettings.HighContrastChanged has no usable event source
        // in this unpackaged desktop runtime (0x80070490). Native setting/theme
        // broadcasts still refresh the material and its high-contrast fallback.
        if (!_disposed && (message is 0x0015 or 0x001a or 0x031a))
            DispatcherQueue.TryEnqueue(UpdateBackdropFactory);
        if (message == 0x0084 && !_disposed && _controls.XamlRoot is not null)
        {
            var point = new NativeVideoWindow.NativePoint
            {
                X = unchecked((short)(lParam.ToInt64() & 0xffff)),
                Y = unchecked((short)((lParam.ToInt64() >> 16) & 0xffff)),
            };
            if (!_ownerFullscreen && !NativePlayerOverlay.IsZoomed(_owner)
                && NativePlayerOverlay.GetWindowRect(_owner, out var bounds))
            {
                var dpi = NativeVideoWindow.GetDpiForWindow(_owner);
                var edge = Math.Max(1, NativeVideoWindow.GetSystemMetricsForDpi(32, dpi) + NativeVideoWindow.GetSystemMetricsForDpi(92, dpi));
                if (point.X < bounds.Left + edge || point.X >= bounds.Right - edge
                    || point.Y < bounds.Top + edge || point.Y >= bounds.Bottom - edge)
                    return -1; // Resize always belongs to the video owner, even over a HUD button.
            }
            NativePlayerOverlay.ScreenToClient(window, ref point);
            var scale = _controls.XamlRoot.RasterizationScale;
            var position = new Point(point.X / scale, point.Y / scale);
            foreach (var element in VisualTreeHelper.FindElementsInHostCoordinates(position, _controls))
            {
                if (element is Control { IsEnabled: true, IsHitTestVisible: true, Visibility: Visibility.Visible }
                    && (element is ButtonBase or RangeBase or TextBox or PasswordBox or ComboBox or SelectorItem or ToggleSwitch or ScrollViewer))
                    return 1; // HTCLIENT: native controls retain keyboard/IME.
            }
            return -1; // HTTRANSPARENT: same UI-thread video container receives input.
        }
        var result = NativeVideoWindow.DefSubclassProc(window, message, wParam, lParam);
        if (!_disposed && message == 0x031e) EnableHostBackdrop(); // WM_DWMCOMPOSITIONCHANGED.
        return result;
    }

    private void Controls_KeyDown(object sender, KeyRoutedEventArgs args)
    {
        if (_disposed || args.Handled) return;
        for (DependencyObject? source = args.OriginalSource as DependencyObject; source is not null; source = VisualTreeHelper.GetParent(source))
            if (source is TextBox or PasswordBox or NumberBox or MenuFlyoutPresenter) return;
        if (args.Key == VirtualKey.F11) { args.Handled = true; ToggleFullscreenRequested?.Invoke(this, EventArgs.Empty); }
        else if (args.Key == VirtualKey.F10) { args.Handled = true; ShowControls(); }
        else if (args.Key == VirtualKey.Escape) { args.Handled = true; ExitFullscreenRequested?.Invoke(this, EventArgs.Empty); }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        NativeVideoWindow.ShowWindow(_window, 0);
        NativeVideoWindow.RemoveWindowSubclass(_window, _procedure, 2);
        _controls.CloseRequested -= Controls_CloseRequested;
        _controls.ToggleFullscreenRequested -= Controls_ToggleFullscreenRequested;
        _controls.AddSubtitleRequested -= Controls_AddSubtitleRequested;
        _controls.MinimizeRequested -= Controls_MinimizeRequested;
        _controls.MaximizeRequested -= Controls_MaximizeRequested;
        _controls.KeyDown -= Controls_KeyDown;
        _controls.ActualThemeChanged -= Controls_ActualThemeChanged;
        _controls.Loaded -= Controls_Loaded;
        _controls.MaterialBoundsChanged -= Controls_MaterialBoundsChanged;
        _policySettings.AdvancedEffectsEnabledChanged -= PolicySettings_Changed;
        _controls.Dispose();
        Close();
    }
}

internal static class NativePlayerOverlay
{
    [StructLayout(LayoutKind.Sequential)] internal struct Margins { internal int Left, Right, Top, Bottom; }
    [DllImport("dwmapi.dll", ExactSpelling = true)] internal static extern int DwmExtendFrameIntoClientArea(nint window, ref Margins margins);
    [DllImport("user32.dll", ExactSpelling = true)] internal static extern nint GetDC(nint window);
    [DllImport("user32.dll", ExactSpelling = true)] internal static extern int ReleaseDC(nint window, nint dc);
    [DllImport("user32.dll", ExactSpelling = true)] [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool GetClientRect(nint window, out Rect rectangle);
    [DllImport("user32.dll", ExactSpelling = true)] internal static extern int FillRect(nint dc, ref Rect rectangle, nint brush);
    [DllImport("gdi32.dll", ExactSpelling = true)] internal static extern nint GetStockObject(int index);
    [DllImport("gdi32.dll", ExactSpelling = true, SetLastError = true)] internal static extern nint CreateRectRgn(int left, int top, int right, int bottom);
    [DllImport("gdi32.dll", ExactSpelling = true)] internal static extern int CombineRgn(nint destination, nint first, nint second, int mode);
    [DllImport("gdi32.dll", ExactSpelling = true)] [return: MarshalAs(UnmanagedType.Bool)] internal static extern bool DeleteObject(nint value);
    [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)] internal static extern int SetWindowRgn(nint window, nint region, [MarshalAs(UnmanagedType.Bool)] bool redraw);
    internal static NativeVideoWindow.NativePoint PointFromLParam(nint value) => new()
    {
        X = unchecked((short)(value.ToInt64() & 0xffff)),
        Y = unchecked((short)((value.ToInt64() >> 16) & 0xffff)),
    };
    [StructLayout(LayoutKind.Sequential)] internal struct Rect { internal int Left, Top, Right, Bottom; }
    [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)]
    internal static extern nint SetWindowLongPtrW(nint window, int index, nint value);
    [DllImport("user32.dll", ExactSpelling = true)]
    internal static extern nint GetWindowLongPtrW(nint window, int index);
    [DllImport("user32.dll", ExactSpelling = true)] [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool GetWindowRect(nint window, out Rect rect);
    [DllImport("user32.dll", ExactSpelling = true)] [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool ScreenToClient(nint window, ref NativeVideoWindow.NativePoint point);
    [DllImport("user32.dll", ExactSpelling = true)] [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool IsIconic(nint window);
    [DllImport("user32.dll", ExactSpelling = true)] [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool IsWindowVisible(nint window);
    [DllImport("user32.dll", ExactSpelling = true)] internal static extern nint GetForegroundWindow();
    [DllImport("user32.dll", ExactSpelling = true)] [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool SetForegroundWindow(nint window);
    [DllImport("user32.dll", ExactSpelling = true)] internal static extern nint GetAncestor(nint window, uint flags);
    [DllImport("user32.dll", ExactSpelling = true)] [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool IsZoomed(nint window);
}
