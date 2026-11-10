using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;
using Fileway.App.Controls;

namespace Fileway.App;

public sealed partial class MainWindow
{
    private int _material = -1;
    private void InitializeAppearance()
    {
        PresentationPreferences.AppearanceChanged += ApplyAppearance;
        AppTitleBar.ActualThemeChanged += AppearanceThemeChanged;
        ApplyAppearance();
    }
    private void AppearanceThemeChanged(FrameworkElement sender, object args) => ApplyAppearance();
    private void ApplyAppearance()
    {
        if (MainRoot.RequestedTheme != AppTitleBar.ActualTheme) MainRoot.RequestedTheme = AppTitleBar.ActualTheme;
        var material = PresentationPreferences.ShellMaterial;
        var contrast = new Windows.UI.ViewManagement.AccessibilitySettings().HighContrast;
        if (contrast) material = 0;
        if (_material != material)
        {
            SystemBackdrop = material switch
            {
                1 => new DesktopAcrylicBackdrop(),
                2 => new MicaBackdrop { Kind = Microsoft.UI.Composition.SystemBackdrops.MicaKind.BaseAlt },
                _ => null,
            };
            _material = material;
        }
        var dark = AppTitleBar.ActualTheme == ElementTheme.Dark;
        var tone = PresentationPreferences.ShellTone;
        var light = tone switch { 1 => Windows.UI.Color.FromArgb(255,238,234,229), 2 => Windows.UI.Color.FromArgb(255,230,235,240), _ => Windows.UI.Color.FromArgb(255,232,232,232) };
        var dim = tone switch { 1 => Windows.UI.Color.FromArgb(255,36,34,32), 2 => Windows.UI.Color.FromArgb(255,31,37,45), _ => Windows.UI.Color.FromArgb(255,32,33,36) };
        if (tone == 3)
        {
            var tint = PresentationPreferences.ShellTint;
            light = Windows.UI.Color.FromArgb(255,(byte)(225+tint.R*0.1),(byte)(225+tint.G*0.1),(byte)(225+tint.B*0.1));
            dim = Windows.UI.Color.FromArgb(255,(byte)(24+tint.R*0.08),(byte)(24+tint.G*0.08),(byte)(24+tint.B*0.08));
        }
        MainRoot.Background = material == 0 ? new SolidColorBrush(contrast
            ? new Windows.UI.ViewManagement.UISettings().GetColorValue(Windows.UI.ViewManagement.UIColorType.Background)
            : dark ? dim : light) : new SolidColorBrush(Microsoft.UI.Colors.Transparent);
        ApplyToolTipPalette(dark, contrast);
    }
    private static void ApplyToolTipPalette(bool dark, bool contrast)
    {
        var background = dark ? Windows.UI.Color.FromArgb(0xF2, 0x1B, 0x1E, 0x23) : Windows.UI.Color.FromArgb(0xF2, 0xFB, 0xFC, 0xFD);
        var border = dark ? Windows.UI.Color.FromArgb(0x33, 0xFF, 0xFF, 0xFF) : Windows.UI.Color.FromArgb(0x29, 0x00, 0x00, 0x00);
        var foreground = dark ? Windows.UI.Color.FromArgb(0xFF, 0xF4, 0xF6, 0xF8) : Windows.UI.Color.FromArgb(0xFF, 0x1B, 0x1F, 0x24);
        if (contrast)
        {
            var settings = new Windows.UI.ViewManagement.UISettings();
            background = settings.GetColorValue(Windows.UI.ViewManagement.UIColorType.Background);
            foreground = settings.GetColorValue(Windows.UI.ViewManagement.UIColorType.Foreground);
            border = foreground;
        }
        if (FindResource(Application.Current.Resources, "FwToolTipBackgroundBrush") is FwBlurBrush blur) blur.Tint = background;
        else SetBrushColor("FwToolTipBackgroundBrush", background);
        SetBrushColor("FwToolTipBorderBrush", border);
        SetBrushColor("FwToolTipForegroundBrush", foreground);
    }
    private static void SetBrushColor(string key, Windows.UI.Color color)
    {
        if (FindResource(Application.Current.Resources, key) is SolidColorBrush brush) brush.Color = color;
    }
    private static object? FindResource(ResourceDictionary resources, string key)
    {
        if (resources.TryGetValue(key, out var value)) return value;
        foreach (var merged in resources.MergedDictionaries)
            if (FindResource(merged, key) is { } found) return found;
        return null;
    }
    private void DisposeAppearance()
    {
        PresentationPreferences.AppearanceChanged -= ApplyAppearance;
        AppTitleBar.ActualThemeChanged -= AppearanceThemeChanged;
    }
}
