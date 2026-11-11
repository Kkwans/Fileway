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
        var actual = theme ?? control.ActualTheme;
        var fill = ThemePalette.Brush("FwSoftChoiceFillBrush", actual);
        var text = ThemePalette.Brush("FwSoftChoiceTextBrush", actual);
        foreach (var suffix in new[] { "", "PointerOver", "Pressed", "Unfocused" })
        {
            control.Resources[prefix + "Background" + (prefix == "ToggleButton" ? "Checked" : "Selected") + suffix] = fill;
            control.Resources[prefix + "Foreground" + (prefix == "ToggleButton" ? "Checked" : "Selected") + suffix] = text;
        }
    }
    internal static Brush PopupBrush(ElementTheme theme)
    {
        if (new Windows.UI.ViewManagement.AccessibilitySettings().HighContrast)
            return ThemePalette.Brush("FwSurfaceBrush", theme);
        return new FwBlurBrush { Tint = ThemePalette.Color("FwPopupBlurColor", theme) };
    }
    internal static SolidColorBrush TranslucentTint(ElementTheme theme) => ThemePalette.Brush("FwPopupPresenterBrush", theme);
    internal static void AttachPopupBackdrop(DependencyObject root)
    {
        if (!Microsoft.UI.Composition.SystemBackdrops.DesktopAcrylicController.IsSupported()) return;
        if (new Windows.UI.ViewManagement.AccessibilitySettings().HighContrast) return;
        var queue = new System.Collections.Generic.Queue<DependencyObject>();
        queue.Enqueue(root);
        while (queue.Count > 0)
        {
            var node = queue.Dequeue();
            if (node is Microsoft.UI.Xaml.Controls.Primitives.Popup popup)
            {
                popup.SystemBackdrop ??= new Microsoft.UI.Xaml.Media.DesktopAcrylicBackdrop();
                continue;
            }
            var count = VisualTreeHelper.GetChildrenCount(node);
            for (var i = 0; i < count; i++) queue.Enqueue(VisualTreeHelper.GetChild(node, i));
        }
    }
    private static SolidColorBrush PopupBorder(ElementTheme theme) => ThemePalette.Brush("FwStrokeStrongBrush", theme);
    internal static void Apply(FlyoutBase flyout, ElementTheme theme)
    {
        var isMenu = flyout is MenuFlyout;
        var style = new Style(isMenu ? typeof(MenuFlyoutPresenter) : typeof(FlyoutPresenter));
        var defaultKey = isMenu ? "DefaultMenuFlyoutPresenterStyle" : "DefaultFlyoutPresenterStyle";
        if (Application.Current.Resources.TryGetValue(defaultKey, out var baseStyle) && baseStyle is Style basis) style.BasedOn = basis;
        style.Setters.Add(new Setter(Control.BackgroundProperty, TranslucentTint(theme)));
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
            if (Microsoft.UI.Composition.SystemBackdrops.DesktopAcrylicController.IsSupported() && !new Windows.UI.ViewManagement.AccessibilitySettings().HighContrast)
                flyout.SystemBackdrop ??= new Microsoft.UI.Xaml.Media.DesktopAcrylicBackdrop();
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
