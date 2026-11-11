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
        _chrome.SystemAppearanceChanged += SystemAppearanceChanged;
        AppTitleBar.ActualThemeChanged += AppearanceThemeChanged;
        ApplyAppearance();
    }
    private void AppearanceThemeChanged(FrameworkElement sender, object args) => ApplyAppearance();
    private static void SystemAppearanceChanged(object? sender, EventArgs args) => PopupMaterials.QueueRefresh();
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
        var theme = AppTitleBar.ActualTheme;
        var tone = PresentationPreferences.ShellTone;
        var neutral = ThemePalette.Color(tone switch
        {
            1 => "FwShellWarmColor", 2 => "FwShellCoolColor", 3 => "FwShellCustomBaseColor", _ => "FwShellNeutralColor",
        }, theme);
        if (tone == 3)
        {
            var tint = PresentationPreferences.ShellTint;
            var mix = ThemePalette.Number("FwShellCustomMix", theme);
            neutral = Windows.UI.Color.FromArgb(neutral.A,
                (byte)(neutral.R + tint.R * mix), (byte)(neutral.G + tint.G * mix), (byte)(neutral.B + tint.B * mix));
        }
        MainRoot.Background = material != 0 ? new SolidColorBrush(Microsoft.UI.Colors.Transparent) : contrast
            ? ThemePalette.Brush("FwCanvasBrush", theme) : new SolidColorBrush(neutral);
    }
    private void DisposeAppearance()
    {
        PresentationPreferences.AppearanceChanged -= ApplyAppearance;
        _chrome.SystemAppearanceChanged -= SystemAppearanceChanged;
        AppTitleBar.ActualThemeChanged -= AppearanceThemeChanged;
    }
}
