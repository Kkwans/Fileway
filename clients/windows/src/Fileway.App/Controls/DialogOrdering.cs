using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace Fileway.App.Controls;

/// <summary>
/// Places the primary action on the right edge for every ContentDialog while keeping the
/// SDK template (and its automation names/patterns) intact. Applied from the implicit style.
/// </summary>
public static class DialogOrdering
{
    public static readonly DependencyProperty EnableProperty = DependencyProperty.RegisterAttached(
        "Enable", typeof(bool), typeof(DialogOrdering), new PropertyMetadata(false, OnEnableChanged));

    public static void SetEnable(DependencyObject element, bool value) => element.SetValue(EnableProperty, value);
    public static bool GetEnable(DependencyObject element) => (bool)element.GetValue(EnableProperty);

    private static void OnEnableChanged(DependencyObject element, DependencyPropertyChangedEventArgs args)
    {
        if (element is not ContentDialog dialog || args.NewValue is not true) return;
        dialog.Opened += (_, _) => Reorder(dialog);
    }

    private static void Reorder(ContentDialog dialog)
    {
        var primary = Find(dialog, "PrimaryButton");
        var secondary = Find(dialog, "SecondaryButton");
        var close = Find(dialog, "CloseButton");
        if (primary is null || close is null) return;
        if (primary.Parent is not Panel container || close.Parent != container) return;
        if (container is Grid grid && grid.ColumnDefinitions.Count >= 3)
        {
            var primaryColumn = Grid.GetColumn(primary);
            var closeColumn = Grid.GetColumn(close);
            if (primaryColumn == closeColumn) return;
            Grid.SetColumn(primary, closeColumn);
            Grid.SetColumn(close, primaryColumn);
            return;
        }
        // Fallback for stack-based templates: move the primary button to the container end.
        var index = container.Children.IndexOf(primary);
        if (index >= 0 && index != container.Children.Count - 1)
        {
            container.Children.RemoveAt(index);
            container.Children.Add(primary);
        }
    }

    private static FrameworkElement? Find(DependencyObject root, string name)
    {
        if (root is FrameworkElement { Name: var elementName } element && elementName == name) return element;
        var count = VisualTreeHelper.GetChildrenCount(root);
        for (var index = 0; index < count; index++)
        {
            var child = VisualTreeHelper.GetChild(root, index);
            if (Find(child, name) is { } match) return match;
        }
        return null;
    }
}
