using Fileway.App.MediaSurfaces;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Input;
using Windows.System;

namespace Fileway.App.Controls;

/// <summary>Retains ComboBox selection/binding semantics, with one native acrylic choice surface.</summary>
public sealed class ChoiceBox : ComboBox
{
    private PlayerSettingsWindow? _popup;
    public ChoiceBox()
    {
        DefaultStyleKey = typeof(ComboBox);
        Style = (Style)Application.Current.Resources["FilewayChoiceBoxStyle"];
        HorizontalContentAlignment = HorizontalAlignment.Center;
        MinHeight = 36; CornerRadius = new CornerRadius(8); FontSize = 14;
        DropDownOpened += (_, _) => { IsDropDownOpen = false; OpenChoices(); };
        Unloaded += (_, _) => { _popup?.Dispose(); _popup = null; };
    }
    protected override void OnPointerPressed(PointerRoutedEventArgs e)
    {
        if (!IsEditable && IsEnabled && (e.GetCurrentPoint(this).Properties.IsLeftButtonPressed || e.Pointer.PointerDeviceType == Microsoft.UI.Input.PointerDeviceType.Touch))
        {
            e.Handled = true; Focus(FocusState.Pointer);
            if (_popup is not null) { _popup.Dispose(); _popup = null; } else OpenChoices();
            return;
        }
        base.OnPointerPressed(e);
    }
    protected override void OnKeyDown(KeyRoutedEventArgs e)
    {
        if (!IsEditable && e.Key is VirtualKey.F4 or VirtualKey.Space or VirtualKey.Enter)
        { e.Handled = true; OpenChoices(); return; }
        base.OnKeyDown(e);
    }
    private void OpenChoices()
    {
        if (_popup is not null || XamlRoot is null || !IsEnabled || Items.Count == 0) return;
        var values = Items.Select(item => item is ComboBoxItem choice ? choice.Content : item).ToArray();
        var list = new ListView { ItemsSource = values, ItemTemplate = ItemTemplate, DisplayMemberPath = DisplayMemberPath,
            SelectionMode = ListViewSelectionMode.Single, IsItemClickEnabled = true, SelectedIndex = SelectedIndex,
            HorizontalContentAlignment = HorizontalAlignment.Stretch, MaxHeight = 360,
            Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent), Padding = new Thickness(4) };
        ScrollViewer.SetHorizontalScrollBarVisibility(list, ScrollBarVisibility.Disabled);
        var panel = new Border { Width = Math.Clamp(Math.Max(ActualWidth, 184), 184, 320), Padding = new Thickness(4), Child = list };
        if (ItemTemplate is null && string.IsNullOrEmpty(DisplayMemberPath)) list.ItemTemplate = (DataTemplate)Application.Current.Resources["FilewayChoiceLabelTemplate"];
        if (!new Windows.UI.ViewManagement.AccessibilitySettings().HighContrast)
        {
            var dark = ActualTheme == ElementTheme.Dark;
            var selected = new SolidColorBrush(dark ? Windows.UI.Color.FromArgb(255, 53, 67, 86) : Windows.UI.Color.FromArgb(255, 231, 237, 245));
            list.Resources["ListViewItemBackgroundSelected"] = selected;
            list.Resources["ListViewItemBackgroundSelectedPointerOver"] = selected;
            list.Resources["ListViewItemBackgroundSelectedPressed"] = selected;
        }
        var owner = Microsoft.UI.Win32Interop.GetWindowFromWindowId(XamlRoot.ContentIslandEnvironment.AppWindowId);
        var popup = new PlayerSettingsWindow(owner, panel, null, () => _popup = null, theme: ActualTheme, wrapContent: false);
        popup.Title = Header?.ToString() ?? "选择选项";
        _popup = popup;
        list.ItemClick += (_, args) =>
        {
            var index = Array.IndexOf(values, args.ClickedItem);
            if (index >= 0) SelectedIndex = index;
            popup.Dispose(); Focus(FocusState.Programmatic);
        };
        list.KeyDown += (_, args) =>
        {
            if (args.Key is not (VirtualKey.Enter or VirtualKey.Escape)) return;
            args.Handled = true;
            if (args.Key == VirtualKey.Enter && list.SelectedIndex >= 0) SelectedIndex = list.SelectedIndex;
            popup.Dispose(); Focus(FocusState.Programmatic);
        };
        try { popup.ShowAt(this, preferBelow: true); }
        catch { popup.Dispose(); throw; }
    }
}
