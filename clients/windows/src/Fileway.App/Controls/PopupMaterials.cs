using Microsoft.UI.Composition;
using Microsoft.UI.Composition.SystemBackdrops;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;
using Windows.UI.ViewManagement;
using System.Runtime.InteropServices;

namespace Fileway.App.Controls;

internal static class PopupMaterials
{
    private static DispatcherQueue? _dispatcher;
    private static UISettings? _settings;
    private static AccessibilitySettings? _accessibility;
    private static PopupMaterial _selection;
    private static bool _highContrast;

    internal static PopupMaterial Effective { get; private set; } = PopupMaterial.Solid;
    internal static event Action? Changed;

    internal static void Initialize(DispatcherQueue dispatcher, PopupMaterial selection)
    {
        _dispatcher = dispatcher;
        _settings = new UISettings();
        _accessibility = new AccessibilitySettings();
        _settings.AdvancedEffectsEnabledChanged += SettingsChanged;
        SetSelection(selection);
    }

    internal static void SetSelection(PopupMaterial selection)
    {
        _selection = selection;
        Refresh();
    }

    internal static void QueueRefresh() => _dispatcher?.TryEnqueue(Refresh);

    private static void SettingsChanged(UISettings sender, object args) => QueueRefresh();

    private static void Refresh()
    {
        if (_settings is null || _accessibility is null) return;
        var highContrast = _accessibility.HighContrast;
        var energySaver = GetSystemPowerStatus(out var power) && power.SystemStatusFlag == 1;
        var material = _settings.AdvancedEffectsEnabled && !highContrast && !energySaver ? _selection : PopupMaterial.Solid;
        if (Effective == material && _highContrast == highContrast) return;
        Effective = material;
        _highContrast = highContrast;
        Changed?.Invoke();
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SystemPowerStatus
    {
        internal byte AcLineStatus, BatteryFlag, BatteryLifePercent, SystemStatusFlag;
        internal uint BatteryLifeTime, BatteryFullLifeTime;
    }

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetSystemPowerStatus(out SystemPowerStatus status);

    internal static void Shutdown()
    {
        if (_settings is not null) _settings.AdvancedEffectsEnabledChanged -= SettingsChanged;
        _settings = null;
        _accessibility = null;
        _dispatcher = null;
    }

    internal static SystemBackdrop? NativeBackdrop()
    {
        if (Effective == PopupMaterial.Solid || !DesktopAcrylicController.IsSupported()) return null;
        return Effective == PopupMaterial.SoftBlur ? new SoftPopupBackdrop() : new DesktopAcrylicBackdrop();
    }

    internal sealed class SoftPopupBackdrop : SystemBackdrop
    {
        private DesktopAcrylicController? _controller;
        internal bool Failed { get; private set; }
        internal event Action? ConnectionFailed;

        protected override void OnTargetConnected(ICompositionSupportsSystemBackdrop target, XamlRoot root)
        {
            base.OnTargetConnected(target, root);
            DesktopAcrylicController? controller = null;
            try
            {
                DispatcherQueue.GetForCurrentThread().EnsureSystemDispatcherQueue();
                controller = new DesktopAcrylicController { Kind = DesktopAcrylicKind.Thin };
                controller.SetSystemBackdropConfiguration(GetDefaultSystemBackdropConfiguration(target, root));
                if (!controller.AddSystemBackdropTarget(target)) return;
                _controller = controller;
                controller = null;
            }
            catch (COMException) { }
            finally
            {
                controller?.Dispose();
                if (_controller is null)
                {
                    Failed = true;
                    ConnectionFailed?.Invoke();
                }
            }
        }

        protected override void OnDefaultSystemBackdropConfigurationChanged(ICompositionSupportsSystemBackdrop target, XamlRoot root)
        {
            base.OnDefaultSystemBackdropConfigurationChanged(target, root);
            _controller?.SetSystemBackdropConfiguration(GetDefaultSystemBackdropConfiguration(target, root));
        }

        protected override void OnTargetDisconnected(ICompositionSupportsSystemBackdrop target)
        {
            var controller = _controller;
            _controller = null;
            try { controller?.RemoveSystemBackdropTarget(target); }
            finally
            {
                controller?.Dispose();
                base.OnTargetDisconnected(target);
            }
        }
    }

    internal static Brush InWindowBrush(ElementTheme theme)
    {
        var surface = ThemePalette.Brush("FwSurfaceRaisedBrush", theme);
        return Effective switch
        {
            PopupMaterial.Acrylic => new AcrylicBrush
            {
                TintColor = surface.Color, TintOpacity = 0.75, TintLuminosityOpacity = 0.88,
                FallbackColor = surface.Color,
            },
            PopupMaterial.SoftBlur => new FwBlurBrush { Tint = surface.Color },
            _ => surface,
        };
    }
}
