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
    private static SolidColorBrush PopupBorder(ElementTheme theme)
    {
        if (new Windows.UI.ViewManagement.AccessibilitySettings().HighContrast)
            return new SolidColorBrush(new Windows.UI.ViewManagement.UISettings().GetColorValue(Windows.UI.ViewManagement.UIColorType.Foreground));
        return new SolidColorBrush(theme == ElementTheme.Dark
            ? Windows.UI.Color.FromArgb(0x33, 255, 255, 255)
            : Windows.UI.Color.FromArgb(0x29, 0, 0, 0));
    }
    internal static void Apply(FlyoutBase flyout, ElementTheme theme)
    {
        var isMenu = flyout is MenuFlyout;
        var style = new Style(isMenu ? typeof(MenuFlyoutPresenter) : typeof(FlyoutPresenter));
        var defaultKey = isMenu ? "DefaultMenuFlyoutPresenterStyle" : "DefaultFlyoutPresenterStyle";
        if (Application.Current.Resources.TryGetValue(defaultKey, out var baseStyle) && baseStyle is Style basis) style.BasedOn = basis;
        style.Setters.Add(new Setter(Control.BackgroundProperty, PopupBrush(theme)));
        style.Setters.Add(new Setter(Control.PaddingProperty, new Thickness(6)));
        style.Setters.Add(new Setter(Control.CornerRadiusProperty, new CornerRadius(14)));
        style.Setters.Add(new Setter(FrameworkElement.MinWidthProperty, 0d));
        style.Setters.Add(new Setter(Control.BorderBrushProperty, PopupBorder(theme)));
        style.Setters.Add(new Setter(Control.BorderThicknessProperty, new Thickness(1)));
        if (flyout is MenuFlyout menu) menu.MenuFlyoutPresenterStyle = style;
        else if (flyout is Flyout panel) panel.FlyoutPresenterStyle = style;
    }
    internal static void Attach(FlyoutBase flyout)
    {
        flyout.Opening += (_, _) =>
        {
            var theme = flyout.Target?.ActualTheme ?? ElementTheme.Light;
            Apply(flyout, theme);
        };
        if (flyout is Flyout panel)
        {
            panel.Opened += (_, _) =>
            {
                var theme = panel.Target?.ActualTheme ?? ElementTheme.Light;
                StylePresenter(panel, theme);
            };
        }
    }
    private static void StylePresenter(Flyout panel, ElementTheme theme)
    {
        var root = panel.Target?.XamlRoot;
        if (root is null) { return; }
        foreach (var popup in VisualTreeHelper.GetOpenPopupsForXamlRoot(root))
        {
            var presenter = FindPresenter(popup.Child);
            if (presenter is null) continue;
            if (!ReferenceEquals(presenter.Content, panel.Content)) continue;
            presenter.CornerRadius = new CornerRadius(14);
            presenter.BorderBrush = PopupBorder(theme);
            presenter.BorderThickness = new Thickness(1);
            presenter.Background = PopupBrush(theme);
            presenter.Padding = new Thickness(6);
            return;
        }
    }
    private static FlyoutPresenter? FindPresenter(DependencyObject? node)
    {
        if (node is FlyoutPresenter presenter) return presenter;
        if (node is null) return null;
        int count = VisualTreeHelper.GetChildrenCount(node);
        for (int index = 0; index < count; index++)
        {
            var found = FindPresenter(VisualTreeHelper.GetChild(node, index));
            if (found is not null) return found;
        }
        return null;
    }
}
