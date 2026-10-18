using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;

namespace Fileway.App.Controls;

internal static class SurfaceStyles
{
    internal static void Soften(Control control, string prefix, ElementTheme? theme = null)
    {
        if (new Windows.UI.ViewManagement.AccessibilitySettings().HighContrast) return;
        var dark = (theme ?? control.ActualTheme) == ElementTheme.Dark;
        var fill = new SolidColorBrush(dark ? Windows.UI.Color.FromArgb(255,61,68,79) : Windows.UI.Color.FromArgb(255,229,233,239));
        var text = new SolidColorBrush(dark ? Windows.UI.Color.FromArgb(255,241,244,248) : Windows.UI.Color.FromArgb(255,35,43,53));
        foreach (var suffix in new[] { "", "PointerOver", "Pressed", "Unfocused" })
        {
            control.Resources[prefix + "Background" + (prefix == "ToggleButton" ? "Checked" : "Selected") + suffix] = fill;
            control.Resources[prefix + "Foreground" + (prefix == "ToggleButton" ? "Checked" : "Selected") + suffix] = text;
        }
    }
    internal static Brush PopupBrush(ElementTheme theme)
    {
        var tint = theme == ElementTheme.Dark ? Windows.UI.Color.FromArgb(255, 36, 39, 44) : Windows.UI.Color.FromArgb(255, 249, 250, 252);
        if (new Windows.UI.ViewManagement.AccessibilitySettings().HighContrast)
            return new SolidColorBrush(new Windows.UI.ViewManagement.UISettings().GetColorValue(Windows.UI.ViewManagement.UIColorType.Background));
        return new AcrylicBrush { TintColor = tint, TintOpacity = 0.82, TintLuminosityOpacity = 0.85, FallbackColor = tint };
    }
    internal static void Apply(FlyoutBase flyout, ElementTheme theme)
    {
        var style = new Style(flyout is MenuFlyout ? typeof(MenuFlyoutPresenter) : typeof(FlyoutPresenter));
        style.Setters.Add(new Setter(Control.BackgroundProperty, PopupBrush(theme)));
        style.Setters.Add(new Setter(Control.PaddingProperty, new Thickness(6)));
        style.Setters.Add(new Setter(Control.CornerRadiusProperty, new CornerRadius(10)));
        style.Setters.Add(new Setter(FrameworkElement.MinWidthProperty, 0d));
        style.Setters.Add(new Setter(Control.BorderThicknessProperty, new Thickness(0)));
        if (flyout is MenuFlyout menu) menu.MenuFlyoutPresenterStyle = style;
        else if (flyout is Flyout panel) panel.FlyoutPresenterStyle = style;
    }
}
