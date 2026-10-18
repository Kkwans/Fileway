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
    }
    private void DisposeAppearance()
    {
        PresentationPreferences.AppearanceChanged -= ApplyAppearance;
        AppTitleBar.ActualThemeChanged -= AppearanceThemeChanged;
    }
}
