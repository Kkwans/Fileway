using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Dispatching;

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

    /// <summary>Keeps tall dialog content inside the window with room for title and action buttons.</summary>
    public static double ContentMaxHeight(FrameworkElement anchor, double preferred, double reserved = 240, double minimum = 240)
    {
        var height = anchor.ActualHeight > 0 ? anchor.ActualHeight : anchor.XamlRoot?.Size.Height ?? 0;
        return height <= 0 ? preferred : Math.Clamp(height - reserved, minimum, preferred);
    }

    internal static Grid FieldRow(string label, Control input, double labelWidth = 112)
    {
        var row = new Grid { ColumnSpacing = 8 };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(labelWidth) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var caption = new TextBlock
        {
            Text = label, Style = SurfaceGeometry.Style("FwCaptionTextStyle"),
            VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis,
        };
        ToolTipService.SetToolTip(caption, label);
        if (string.IsNullOrEmpty(AutomationProperties.GetName(input))) AutomationProperties.SetName(input, label);
        input.HorizontalAlignment = HorizontalAlignment.Stretch;
        row.Children.Add(caption);
        Grid.SetColumn(input, 1);
        row.Children.Add(input);
        return row;
    }

    private static void OnEnableChanged(DependencyObject element, DependencyPropertyChangedEventArgs args)
    {
        if (element is not ContentDialog dialog) return;
        if (args.NewValue is true) dialog.Opened += DialogOpened;
        else dialog.Opened -= DialogOpened;
    }

    private static void DialogOpened(ContentDialog dialog, ContentDialogOpenedEventArgs args)
    {
        if (Reorder(dialog)) return;
        // Dialogs opened from a menu can raise Opened before the button panel is realized.
        var timer = dialog.DispatcherQueue.CreateTimer();
        timer.Interval = TimeSpan.FromMilliseconds(140);
        timer.IsRepeating = false;
        timer.Tick += Retry;
        dialog.Closed += Closed;
        timer.Start();
        dialog.DispatcherQueue.TryEnqueue(() =>
        {
            if (timer.IsRunning && Reorder(dialog)) Finish();
        });

        void Retry(DispatcherQueueTimer sender, object args)
        {
            Finish();
            Reorder(dialog);
        }

        void Closed(ContentDialog sender, ContentDialogClosedEventArgs args) => Finish();

        void Finish()
        {
            timer.Stop();
            timer.Tick -= Retry;
            dialog.Closed -= Closed;
        }
    }

    internal static bool Reorder(ContentDialog dialog)
    {
        var primary = Find(dialog, "PrimaryButton");
        var commandSpace = primary is null ? null : FindAncestor(primary, "CommandSpace");
        var secondary = commandSpace is not null ? Find(commandSpace, "SecondaryButton") : Find(dialog, "SecondaryButton");
        var close = commandSpace is not null ? Find(commandSpace, "CloseButton") : Find(dialog, "CloseButton");
        if (primary is Button primaryButton && close is Button closeButton)
            ApplyButtonStyles(primaryButton, secondary as Button, closeButton);
        var hasPrimaryAndClose = !string.IsNullOrWhiteSpace(dialog.PrimaryButtonText) &&
            !string.IsNullOrWhiteSpace(dialog.CloseButtonText);
        if (commandSpace is Grid { ColumnDefinitions.Count: 5 } nativeGrid &&
            primary is not null && secondary is not null && close is not null &&
            ReferenceEquals(VisualTreeHelper.GetParent(primary), nativeGrid) &&
            ReferenceEquals(VisualTreeHelper.GetParent(secondary), nativeGrid) &&
            ReferenceEquals(VisualTreeHelper.GetParent(close), nativeGrid))
        {
            // Let the SDK keep assigning columns as actions appear and disappear.
            // Mirror placement while keeping each button's reading direction.
            primary.FlowDirection = secondary.FlowDirection = close.FlowDirection = dialog.FlowDirection;
            nativeGrid.FlowDirection = hasPrimaryAndClose ? FlowDirection.RightToLeft : dialog.FlowDirection;
            return true;
        }
        // Secondary + Close already share the SDK's two-button columns.
        if (!hasPrimaryAndClose) return true;
        if (primary is null || close is null) return false;
        var ancestors = new HashSet<DependencyObject>();
        for (DependencyObject? node = VisualTreeHelper.GetParent(primary); node is not null; node = VisualTreeHelper.GetParent(node))
            ancestors.Add(node);
        Panel? container = null;
        for (DependencyObject? node = VisualTreeHelper.GetParent(close); node is not null; node = VisualTreeHelper.GetParent(node))
            if (node is Panel panel && ancestors.Contains(node)) { container = panel; break; }
        if (container is null) return false;
        var primaryItem = DirectChild(container, primary);
        var closeItem = DirectChild(container, close);
        if (primaryItem is null || closeItem is null || ReferenceEquals(primaryItem, closeItem)) return false;
        if (primaryItem is not FrameworkElement primaryElement || closeItem is not FrameworkElement closeElement) return false;
        if (container is Grid grid && grid.ColumnDefinitions.Count >= 3)
        {
            var primaryColumn = Grid.GetColumn(primaryElement);
            var closeColumn = Grid.GetColumn(closeElement);
            if (primaryColumn == closeColumn) return false;
            if (primaryColumn > closeColumn) return true;
            Grid.SetColumn(primaryElement, closeColumn);
            Grid.SetColumn(closeElement, primaryColumn);
            return true;
        }
        var index = container.Children.IndexOf(primaryItem);
        var closeIndex = container.Children.IndexOf(closeItem);
        if (index < 0 || closeIndex < 0) return false;
        if (index > closeIndex) return true;
        container.Children.RemoveAt(index);
        container.Children.Add(primaryItem);
        return true;
    }
    private static UIElement? DirectChild(Panel container, DependencyObject node)
    {
        DependencyObject? current = node;
        while (current is not null)
        {
            var parent = VisualTreeHelper.GetParent(current);
            if (ReferenceEquals(parent, container)) return current as UIElement;
            current = parent;
        }
        return null;
    }

    private static void ApplyButtonStyles(Button primary, Button? secondary, Button close)
    {
        // The SDK template accents whichever button is the dialog default, so a
        // DefaultButton=Close dialog otherwise shows two filled primary actions.
        var resources = Application.Current.Resources;
        if (resources["FwPrimaryButtonStyle"] is Style primaryStyle) primary.Style = primaryStyle;
        if (resources["FwSecondaryButtonStyle"] is Style secondaryStyle)
        {
            close.Style = secondaryStyle;
            if (secondary is not null) secondary.Style = secondaryStyle;
        }
    }

    internal static void FocusCloseButton(ContentDialog dialog)
    {
        dialog.Opened += (_, _) => dialog.DispatcherQueue.TryEnqueue(Microsoft.UI.Dispatching.DispatcherQueuePriority.Low, () =>
        {
            if (Find(dialog, "CloseButton") is Button close) close.Focus(FocusState.Keyboard);
        });
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

    private static FrameworkElement? FindAncestor(DependencyObject node, string name)
    {
        for (DependencyObject? current = node; current is not null; current = VisualTreeHelper.GetParent(current))
            if (current is FrameworkElement { Name: var elementName } element && elementName == name) return element;
        return null;
    }
}
