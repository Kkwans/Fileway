using System.Runtime.InteropServices;
using Fileway.App.Branding;
using Fileway.App.Controls.Playback;
using Fileway.Core.Contracts;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.Foundation;
using Windows.Graphics;
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
    private PlayerSettingsWindow? _settingsWindow;
    private readonly UISettings _policySettings = new();
    private readonly AccessibilitySettings _policyAccessibility = new();
    private bool _ownerFullscreen;
    private bool _disposed;
    private bool _activatedOnce;
    private bool _ownerVisible;
    private bool? _windowVisible;
    private bool? _materialHighContrast;
    private RectInt32? _windowBounds;

    internal PlayerOverlayWindow(IPlaybackSession session, nint owner)
    {
        _owner = owner;
        Title = AppBranding.DisplayName + (AppBranding.IsChinese ? " 播放控制" : " playback controls");
        _window = WinRT.Interop.WindowNative.GetWindowHandle(this);
        _procedure = WindowProcedure;
        try
        {
            _controls = new ScreenboxPlayerControls(session);
            RefreshBackdropState();
            EnableHostBackdrop();
            UpdateBackdropFactory();
            Content = _controls;
            SystemBackdrop = new WinUIEx.TransparentTintBackdrop();
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
            _controls.SetNativeSettingsHost(ShowNativeSettings, CloseNativeSettings);
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

    private void ShowNativeSettings(FrameworkElement content, FrameworkElement anchor, Style? style, Action onClosed)
    {
        if (_disposed) { onClosed(); return; }
        CloseNativeSettings();
        PlayerSettingsWindow? popup = null;
        try
        {
            popup = new PlayerSettingsWindow(_window, content, style, () =>
            {
                if (ReferenceEquals(_settingsWindow, popup)) _settingsWindow = null;
                onClosed();
            });
            _settingsWindow = popup;
            popup.ShowAt(anchor);
        }
        catch (Exception exception)
        {
            if (ReferenceEquals(_settingsWindow, popup)) _settingsWindow = null;
            popup?.Dispose();
            onClosed();
            _controls.ReportError("设置窗口未能打开，请关闭后重试。");
            System.Diagnostics.Trace.TraceError($"Playback settings host failed: {exception.GetType().Name}; HRESULT=0x{exception.HResult:X8}");
        }
    }

    private void CloseNativeSettings()
    {
        var popup = _settingsWindow;
        _settingsWindow = null;
        popup?.Dispose();
    }

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

    private void Controls_ActualThemeChanged(FrameworkElement sender, object args)
    {
        _materialHighContrast = null;
        RefreshBackdropState();
    }
    private void Controls_Loaded(object sender, RoutedEventArgs args)
    {
        _materialHighContrast = null;
        UpdateMaterialVisibility();
        DispatcherQueue.TryEnqueue(Microsoft.UI.Dispatching.DispatcherQueuePriority.Low, RecordIconDiagnostics);
    }

    private void Controls_MaterialBoundsChanged(object? sender, EventArgs args) => UpdateMaterialVisibility();

    private void UpdateMaterialVisibility()
    {
        if (_disposed) return;
        var visible = _controls.ControlsVisible && _ownerVisible;
        if (_windowVisible != visible)
        {
            // An empty visible owned HWND can retain activation and obstruct
            // foreground recovery. Hide it and return focus only within this group.
            if (!visible && NativePlayerOverlay.GetForegroundWindow() == _window)
                NativePlayerOverlay.SetForegroundWindow(_owner);
            _windowVisible = visible;
            // Keep the full transparent XAML surface: popup content can extend
            // above the footer and must never be clipped to a native toolbar region.
            NativeVideoWindow.ShowWindow(_window, visible ? 4 : 0);
        }
        if (visible)
        {
            var highContrast = _policyAccessibility.HighContrast;
            if (_materialHighContrast == highContrast) return;
            _materialHighContrast = highContrast;
            _controls.SetMaterialAvailable(true, highContrast);
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
        if (Microsoft.UI.Composition.SystemBackdrops.DesktopAcrylicController.IsSupported() && _policySettings.AdvancedEffectsEnabled && !_policyAccessibility.HighContrast)
            popupFactory = static () => new DesktopAcrylicBackdrop();
        _controls.SetFlyoutBackdropFactory(popupFactory);
        UpdateMaterialVisibility();
    }

    private void PolicySettings_Changed(UISettings sender, object args) => DispatcherQueue.TryEnqueue(UpdateBackdropFactory);

    private void EnableHostBackdrop()
    {
        if (_disposed) return;
        // Screenbox-style translucent edge gradients belong to WinUI. Do not
        // add a desktop-acrylic plate or extended glass behind the whole HUD.
        var noBorder = unchecked((int)0xfffffffeu);
        _ = NativeVideoWindow.DwmSetWindowAttribute(_window, 34, ref noBorder, sizeof(int));
        var dark = 1;
        _ = NativeVideoWindow.DwmSetWindowAttribute(_window, 20, ref dark, sizeof(int));
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
        if (!_disposed) UpdateMaterialVisibility();
    }

    internal void ApplySnapshot(PlaybackSnapshot snapshot)
    {
        if (!_disposed)
        {
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
            _controls.NotifyActivity();
        }
    }

    internal void ShowControls()
    {
        if (_disposed) return;
        _controls.ShowControls();
        if (!_ownerVisible) return;
        _activatedOnce = true;
        Activate();
        _windowVisible = true;
        UpdateMaterialVisibility();
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
        _ownerVisible = visible && !NativePlayerOverlay.IsIconic(_owner) && NativePlayerOverlay.IsWindowVisible(_owner);
        if (!_ownerVisible)
        {
            UpdateMaterialVisibility();
            return;
        }
        // All values are physical pixels from the owner's actual video HWND.
        // Preserve z-order while moving, especially when the owner is inactive.
        var windowBounds = new RectInt32(bounds.Left, bounds.Top, Math.Max(1, bounds.Right - bounds.Left), Math.Max(1, bounds.Bottom - bounds.Top));
        if (_windowBounds is not { } previous || !previous.Equals(windowBounds))
        {
            _windowBounds = windowBounds;
            if (!NativeVideoWindow.SetWindowPos(_window, 0, windowBounds.X, windowBounds.Y, windowBounds.Width, windowBounds.Height, 0x0014))
                _windowBounds = null;
        }
        if (!_activatedOnce && NativePlayerOverlay.GetForegroundWindow() == _owner)
        {
            // WinUI's first Activate completes the content lifetime. Only do
            // this while the owner is already foreground, then restore it.
            _activatedOnce = true;
            Activate();
            _windowVisible = true;
            NativePlayerOverlay.SetForegroundWindow(_owner);
        }
        UpdateMaterialVisibility();
    }

    internal void Hide()
    {
        if (_disposed) return;
        _ownerVisible = false;
        UpdateMaterialVisibility();
    }

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
        CloseNativeSettings();
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
    [DllImport("user32.dll", ExactSpelling = true)] internal static extern nint GetDC(nint window);
    [DllImport("user32.dll", ExactSpelling = true)] internal static extern int ReleaseDC(nint window, nint dc);
    [DllImport("user32.dll", ExactSpelling = true)] [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool GetClientRect(nint window, out Rect rectangle);
    [DllImport("user32.dll", ExactSpelling = true)] internal static extern int FillRect(nint dc, ref Rect rectangle, nint brush);
    [DllImport("gdi32.dll", ExactSpelling = true)] internal static extern nint GetStockObject(int index);
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
    [DllImport("user32.dll", ExactSpelling = true)] internal static extern nint GetAncestor(nint window, uint flags);
    [DllImport("user32.dll", ExactSpelling = true)] [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool SetForegroundWindow(nint window);
    [DllImport("user32.dll", ExactSpelling = true)] [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool IsZoomed(nint window);
}
