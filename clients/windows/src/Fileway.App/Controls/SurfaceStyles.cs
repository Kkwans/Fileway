using System.Runtime.CompilerServices;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;

namespace Fileway.App.Controls;

public static class SurfaceStyles
{
    public static readonly DependencyProperty UsePopupMaterialProperty = DependencyProperty.RegisterAttached(
        "UsePopupMaterial", typeof(bool), typeof(SurfaceStyles), new PropertyMetadata(false, OnUsePopupMaterialChanged));

    private static readonly ConditionalWeakTable<FrameworkElement, Surface> Surfaces = new();
    private static readonly ConditionalWeakTable<Popup, Surface> Selectors = new();

    public static bool GetUsePopupMaterial(DependencyObject element) => (bool)element.GetValue(UsePopupMaterialProperty);
    public static void SetUsePopupMaterial(DependencyObject element, bool value) => element.SetValue(UsePopupMaterialProperty, value);

    private static void OnUsePopupMaterialChanged(DependencyObject element, DependencyPropertyChangedEventArgs args)
    {
        if (element is not FrameworkElement owner) return;
        if (args.NewValue is true) Surfaces.GetValue(owner, static target => new Surface(target));
        else if (Surfaces.TryGetValue(owner, out var previous) && Surfaces.Remove(owner)) previous.Dispose();
    }

    internal static void Soften(Control control, string prefix, ElementTheme? theme = null)
    {
        var contrast = new Windows.UI.ViewManagement.AccessibilitySettings().HighContrast;
        var actual = theme ?? control.ActualTheme;
        var fill = contrast ? null : ThemePalette.Brush("FwSoftChoiceFillBrush", actual);
        var text = contrast ? null : ThemePalette.Brush("FwSoftChoiceTextBrush", actual);
        var selection = prefix == "ToggleButton" ? "Checked" : "Selected";
        foreach (var suffix in new[] { "", "PointerOver", "Pressed", "Unfocused" })
        {
            var background = prefix + "Background" + selection + suffix;
            var foreground = prefix + "Foreground" + selection + suffix;
            if (contrast)
            {
                control.Resources.Remove(background);
                control.Resources.Remove(foreground);
            }
            else
            {
                control.Resources[background] = fill;
                control.Resources[foreground] = text;
            }
        }
    }

    internal static void AttachSelectorPopup(Control owner)
    {
        owner.ApplyTemplate();
        var popup = FindDescendant<Popup>(owner, owner is AutoSuggestBox ? "SuggestionsPopup" : "Popup");
        if (popup?.Child is Border border) Selectors.GetValue(popup, target => new Surface(border, target, owner));
    }

    internal static void Apply(FlyoutBase flyout)
    {
        var isMenu = flyout is MenuFlyout;
        var style = new Style(isMenu ? typeof(MenuFlyoutPresenter) : typeof(FlyoutPresenter));
        var defaultKey = isMenu ? "DefaultMenuFlyoutPresenterStyle" : "DefaultFlyoutPresenterStyle";
        if (Application.Current.Resources.TryGetValue(defaultKey, out var baseStyle) && baseStyle is Style basis) style.BasedOn = basis;
        style.Setters.Add(new Setter(UsePopupMaterialProperty, true));
        style.Setters.Add(new Setter(Control.PaddingProperty, new Thickness(6)));
        style.Setters.Add(new Setter(Control.CornerRadiusProperty, new CornerRadius(14)));
        style.Setters.Add(new Setter(FrameworkElement.MinWidthProperty, 0d));
        style.Setters.Add(new Setter(Control.BorderThicknessProperty, new Thickness(1)));
        if (flyout is MenuFlyout menu) menu.MenuFlyoutPresenterStyle = style;
        else if (flyout is Flyout panel) panel.FlyoutPresenterStyle = style;
    }

    internal static void Attach(FlyoutBase flyout) => Apply(flyout);

    private static T? FindDescendant<T>(DependencyObject root, string name) where T : FrameworkElement
    {
        if (root is T candidate && candidate.Name == name) return candidate;
        var count = VisualTreeHelper.GetChildrenCount(root);
        for (var index = 0; index < count; index++)
            if (FindDescendant<T>(VisualTreeHelper.GetChild(root, index), name) is { } found) return found;
        return null;
    }

    private static Popup? FindOwningPopup(FrameworkElement element)
    {
        if (element.XamlRoot is not { } root) return null;
        var ancestors = new HashSet<DependencyObject>();
        for (DependencyObject? current = element; current is not null; current = VisualTreeHelper.GetParent(current))
            ancestors.Add(current);
        return VisualTreeHelper.GetOpenPopupsForXamlRoot(root)
            .FirstOrDefault(popup => popup.Child is { } child && ancestors.Contains(child));
    }

    private sealed class Surface : IDisposable
    {
        private readonly FrameworkElement _element;
        private readonly FrameworkElement _themeOwner;
        private readonly Popup? _selector;
        private Popup? _hostPopup;
        private SystemBackdrop? _backdrop;
        private PopupMaterial? _applied;
        private bool _active;

        internal Surface(FrameworkElement element, Popup? selector = null, FrameworkElement? themeOwner = null)
        {
            _element = element;
            _themeOwner = themeOwner ?? element;
            _selector = selector;
            if (selector is not null)
            {
                selector.Opened += PopupOpened;
                selector.Closed += PopupClosed;
                element.Unloaded += Unloaded;
                if (selector.IsOpen) Activate();
            }
            else
            {
                element.Loaded += Loaded;
                element.Unloaded += Unloaded;
                if (element is ContentDialog dialog)
                {
                    dialog.Opened += DialogOpened;
                    dialog.Closed += DialogClosed;
                }
                if (element.IsLoaded) Activate();
            }
        }

        private void Loaded(object sender, RoutedEventArgs args) => Activate();
        private void Unloaded(object sender, RoutedEventArgs args) => Deactivate();
        private void PopupOpened(object? sender, object args) => Activate();
        private void PopupClosed(object? sender, object args) => Deactivate();
        private void DialogOpened(ContentDialog sender, ContentDialogOpenedEventArgs args) => Activate();
        private void DialogClosed(ContentDialog sender, ContentDialogClosedEventArgs args) => Deactivate();

        private void Activate()
        {
            if (!_active)
            {
                _active = true;
                PopupMaterials.Changed += Refresh;
                _themeOwner.ActualThemeChanged += ThemeChanged;
            }
            Refresh();
            if (_element is FlyoutPresenter or ToolTip && _hostPopup is null)
                _element.DispatcherQueue.TryEnqueue(() => { if (_active) Refresh(); });
        }

        private void ThemeChanged(FrameworkElement sender, object args) => Refresh();

        private void NativeConnectionFailed() =>
            _element.DispatcherQueue.TryEnqueue(() => { if (_active) Refresh(); });

        private void Refresh()
        {
            var theme = _element is ToolTip && _element.XamlRoot?.Content is FrameworkElement root
                ? root.ActualTheme : _themeOwner.ActualTheme;
            Brush brush;
            if (_selector is not null || _element is FlyoutPresenter or MenuFlyoutPresenter or ToolTip)
            {
                var popup = _selector ?? (_element is MenuFlyoutPresenter ? null : FindOwningPopup(_element));
                if (_applied != PopupMaterials.Effective || !ReferenceEquals(popup, _hostPopup))
                {
                    DisconnectBackdrop();
                    _applied = PopupMaterials.Effective;
                    _hostPopup = popup;
                    _backdrop = popup is not null || _element is MenuFlyoutPresenter ? PopupMaterials.NativeBackdrop() : null;
                    if (_backdrop is PopupMaterials.SoftPopupBackdrop soft) soft.ConnectionFailed += NativeConnectionFailed;
                    if (_element is MenuFlyoutPresenter menu) menu.SystemBackdrop = _backdrop;
                    else if (popup is not null) popup.SystemBackdrop = _backdrop;
                }
                brush = _backdrop is null or PopupMaterials.SoftPopupBackdrop { Failed: true } ? ThemePalette.Brush("FwSurfaceRaisedBrush", theme)
                    : ThemePalette.Brush("FwPopupPresenterBrush", theme);
            }
            else
            {
                brush = PopupMaterials.InWindowBrush(theme);
            }

            var stroke = ThemePalette.Brush("FwStrokeStrongBrush", theme);
            if (_element is Control control)
            {
                control.Background = brush;
                control.BorderBrush = stroke;
                if (control is ToolTip tooltip) tooltip.Foreground = ThemePalette.Brush("FwTextPrimaryBrush", theme);
                if (control is ContentDialog dialog && FindDescendant<Grid>(dialog, "CommandSpace") is { } commands)
                    commands.Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent);
            }
            else if (_element is Border border)
            {
                border.Background = brush;
                border.BorderBrush = stroke;
            }
        }

        private void DisconnectBackdrop()
        {
            if (_backdrop is PopupMaterials.SoftPopupBackdrop soft) soft.ConnectionFailed -= NativeConnectionFailed;
            if (_hostPopup is not null && ReferenceEquals(_hostPopup.SystemBackdrop, _backdrop)) _hostPopup.SystemBackdrop = null;
            if (_element is MenuFlyoutPresenter menu) menu.SystemBackdrop = null;
            _hostPopup = null;
            _backdrop = null;
            _applied = null;
        }

        private void Deactivate()
        {
            if (!_active) return;
            _active = false;
            PopupMaterials.Changed -= Refresh;
            _themeOwner.ActualThemeChanged -= ThemeChanged;
            DisconnectBackdrop();
        }

        public void Dispose()
        {
            Deactivate();
            _element.Loaded -= Loaded;
            _element.Unloaded -= Unloaded;
            if (_element is ContentDialog dialog)
            {
                dialog.Opened -= DialogOpened;
                dialog.Closed -= DialogClosed;
            }
        }
    }
}
