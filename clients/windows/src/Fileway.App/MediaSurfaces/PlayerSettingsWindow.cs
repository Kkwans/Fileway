using System.ComponentModel;
using Fileway.App.Branding;
using System.Runtime.InteropServices;
using Microsoft.UI.Composition;
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

/// <summary>An owned, interactive settings surface with a whole-window acrylic target.</summary>
internal sealed class PlayerSettingsWindow : Window, IDisposable
{
    private readonly Grid _root = new() { RequestedTheme = ElementTheme.Dark };
    private readonly FrameworkElement _presenter;
    private readonly SettingsAcrylicBackdrop _backdrop = new();
    private readonly NativeVideoWindow.SubclassProc _procedure;
    private readonly KeyEventHandler _keyHandler;
    private readonly nint _window;
    private readonly PlaybackResponsivenessDiagnostics? _responsiveness;
    private Action? _onClosed;
    private bool _subclassed;
    private bool _shown;
    private bool _closing;
    private bool _closed;
    private bool _released;

    internal PlayerSettingsWindow(nint owner, FrameworkElement content, Style? presenterStyle, Action onClosed,
        PlaybackResponsivenessDiagnostics? responsiveness = null)
    {
        _responsiveness = responsiveness;
        ArgumentNullException.ThrowIfNull(content);
        ArgumentNullException.ThrowIfNull(onClosed);
        _onClosed = onClosed;
        _procedure = WindowProcedure;
        _keyHandler = Root_KeyDown;
        _window = WinRT.Interop.WindowNative.GetWindowHandle(this);
        _presenter = content is FlyoutPresenter or MenuFlyoutPresenter ? content : new FlyoutPresenter { Content = content };
        try
        {
            if (owner == 0 || !IsWindow(owner)) throw new ArgumentException("需要有效的播放器所属窗口。", nameof(owner));
            Title = AppBranding.DisplayName + (AppBranding.IsChinese ? " 播放设置" : " playback settings");
            if (presenterStyle is not null) _presenter.Style = presenterStyle;
            if (_presenter is Control control)
            {
                control.Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent);
                control.BorderThickness = new Thickness(0);
            }
            _root.Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent);
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
            SetDwmAttribute(20, 1); // Dark nonclient appearance.
            SetDwmAttribute(33, 2); // DWMWCP_ROUND; DWM retains its native shadow.
            SetDwmAttribute(34, unchecked((int)0xfffffffeu)); // DWMWA_COLOR_NONE.
            if (DesktopAcrylicController.IsSupported()) SystemBackdrop = _backdrop;
            else
            {
                // Material availability must never disable playback settings.
                var fallback = new AccessibilitySettings().HighContrast
                    ? new UISettings().GetColorValue(UIColorType.Background)
                    : Windows.UI.Color.FromArgb(255, 44, 44, 44);
                _root.Background = new SolidColorBrush(fallback);
            }
            _root.AddHandler(UIElement.KeyDownEvent, _keyHandler, true);
            Activated += Window_Activated;
            Closed += Window_Closed;
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

    internal void ShowAt(FrameworkElement anchor)
    {
        ArgumentNullException.ThrowIfNull(anchor);
        ObjectDisposedException.ThrowIf(_closed || _released, this);
        VerifyThread();
        if (_shown) { Activate(); return; }
        var xamlRoot = anchor.XamlRoot ?? throw new InvalidOperationException("设置按钮尚未连接到窗口。");
        var anchorWindow = Microsoft.UI.Win32Interop.GetWindowFromWindowId(xamlRoot.ContentIslandEnvironment.AppWindowId);
        var origin = new NativeVideoWindow.NativePoint();
        if (!ClientToScreen(anchorWindow, ref origin)) throw NativeFailure("ClientToScreen");
        var logical = anchor.TransformToVisual(xamlRoot.Content).TransformPoint(new Point(0, 0));
        var anchorScale = xamlRoot.RasterizationScale;
        var anchorRight = checked(origin.X + (int)Math.Ceiling((logical.X + anchor.ActualWidth) * anchorScale));
        var anchorTop = checked(origin.Y + (int)Math.Floor(logical.Y * anchorScale));
        var anchorBottom = checked(origin.Y + (int)Math.Ceiling((logical.Y + anchor.ActualHeight) * anchorScale));
        var monitor = MonitorFromPoint(new NativeVideoWindow.NativePoint { X = anchorRight, Y = anchorTop }, 2);
        var information = new NativeVideoWindow.MonitorInfo { Size = Marshal.SizeOf<NativeVideoWindow.MonitorInfo>() };
        if (monitor == 0 || !NativeVideoWindow.GetMonitorInfoW(monitor, ref information)) throw NativeFailure("GetMonitorInfoW");

        try
        {
            // Size before the first show, then finish XAML initialization without
            // activation. Re-measure once using the target window's actual DPI.
            PlaceAndMeasure(information.Work, anchorRight, anchorTop, anchorBottom, anchorScale);
            _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsShowBegin);
            AppWindow.Show(false);
            _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsShowEnd);
            _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsLayoutBegin, 1);
            _root.UpdateLayout();
            _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsLayoutEnd, 1);
            PlaceAndMeasure(information.Work, anchorRight, anchorTop, anchorBottom,
                _root.XamlRoot?.RasterizationScale ?? anchorScale);
            _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsLayoutBegin, 2);
            _root.UpdateLayout();
            _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsLayoutEnd, 2);
            _shown = true;
            _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsActivateBegin);
            Activate(); // This surface was explicitly opened for interaction.
            FocusFirstControl(_root, anchor is Control { FocusState: FocusState.Keyboard }
                ? FocusState.Keyboard : FocusState.Programmatic);
            _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsActivateEnd);
        }
        catch
        {
            Close();
            throw;
        }
    }

    private void PlaceAndMeasure(NativePlayerOverlay.Rect work, int anchorRight, int anchorTop, int anchorBottom, double scale)
    {
        _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsMeasureBegin);
        var gap = Math.Max(1, (int)Math.Ceiling(8 * scale));
        var availableWidth = Math.Max(1, work.Right - work.Left - gap * 2);
        var availableHeight = Math.Max(1, work.Bottom - work.Top - gap * 2);
        var maxWidth = Math.Min(420, availableWidth / scale);
        var maxHeight = availableHeight / scale;
        _root.MaxWidth = _presenter.MaxWidth = maxWidth;
        _root.MaxHeight = _presenter.MaxHeight = maxHeight;
        _presenter.MinWidth = Math.Min(_presenter.MinWidth, maxWidth);
        _presenter.MinHeight = Math.Min(_presenter.MinHeight, maxHeight);
        if (_presenter is Control control) control.ApplyTemplate();
        _root.Measure(new Size(maxWidth, maxHeight));
        var width = Math.Clamp((int)Math.Ceiling(_root.DesiredSize.Width * scale), 1, availableWidth);
        var height = Math.Clamp((int)Math.Ceiling(_root.DesiredSize.Height * scale), 1, availableHeight);
        var left = Math.Clamp(anchorRight - width, work.Left + gap, work.Right - gap - width);
        var top = anchorTop - gap - height;
        if (top < work.Top + gap && anchorBottom + gap + height <= work.Bottom - gap) top = anchorBottom + gap;
        top = Math.Clamp(top, work.Top + gap, work.Bottom - gap - height);
        if (!NativeVideoWindow.SetWindowPos(_window, 0, left, top, width, height, 0x0214)) // NOACTIVATE | NOZORDER | NOOWNERZORDER.
            throw NativeFailure("SetWindowPos");
        _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsMeasureEnd);
    }

    private void Window_Activated(object sender, WindowActivatedEventArgs args)
    {
        _responsiveness?.Record(PlaybackDiagnosticEvent.SettingsActivation, (int)args.WindowActivationState);
        if (_shown && args.WindowActivationState == WindowActivationState.Deactivated)
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
        if (message == 0x0086) return NativeVideoWindow.DefSubclassProc(window, message, wParam, -1);
        if (message == 0x0010 && !_closing)
        {
            DispatcherQueue.TryEnqueue(Close);
            return 0;
        }
        if (message == 0x0082)
        {
            _subclassed = false;
            _closed = true;
            DispatcherQueue.TryEnqueue(Release);
        }
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
        _root.RemoveHandler(UIElement.KeyDownEvent, _keyHandler);
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

    private sealed class SettingsAcrylicBackdrop : SystemBackdrop, IDisposable
    {
        private DesktopAcrylicController? _controller;
        private ICompositionSupportsSystemBackdrop? _target;
        private SystemBackdropConfiguration? _configuration;
        private bool _disposed;

        protected override void OnTargetConnected(ICompositionSupportsSystemBackdrop connectedTarget, XamlRoot xamlRoot)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            if (_target is not null) throw new InvalidOperationException("设置窗口的 Acrylic 目标不能共享。");
            base.OnTargetConnected(connectedTarget, xamlRoot);
            _target = connectedTarget;
            try
            {
                _configuration = new SystemBackdropConfiguration { Theme = SystemBackdropTheme.Dark, IsInputActive = true };
                CopySystemPolicy(connectedTarget, xamlRoot);
                _controller = new DesktopAcrylicController { Kind = DesktopAcrylicKind.Thin };
                _controller.SetSystemBackdropConfiguration(_configuration);
                _controller.TintColor = Windows.UI.Color.FromArgb(255, 44, 44, 44);
                _controller.TintOpacity = 0.15f;
                _controller.FallbackColor = Windows.UI.Color.FromArgb(255, 44, 44, 44);
                // Keep the SDK's Thin luminosity/noise recipe and system fallback.
                if (!_controller.AddSystemBackdropTarget(connectedTarget))
                    throw new InvalidOperationException("无法连接设置窗口的 Acrylic 背景。");
            }
            catch { Disconnect(); throw; }
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
            _configuration.Theme = SystemBackdropTheme.Dark;
            _configuration.IsInputActive = true;
        }

        protected override void OnTargetDisconnected(ICompositionSupportsSystemBackdrop disconnectedTarget) => Disconnect();

        private void Disconnect()
        {
            var controller = _controller;
            var target = _target;
            _controller = null;
            _target = null;
            _configuration = null;
            try { controller?.RemoveAllSystemBackdropTargets(); }
            finally
            {
                try { controller?.Dispose(); }
                finally { if (target is not null) base.OnTargetDisconnected(target); }
            }
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
    [DllImport("user32.dll", ExactSpelling = true)]
    private static extern nint MonitorFromPoint(NativeVideoWindow.NativePoint point, uint flags);
    [DllImport("user32.dll", ExactSpelling = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsWindow(nint window);
    [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)]
    private static extern nint GetWindowLongPtrW(nint window, int index);
}
