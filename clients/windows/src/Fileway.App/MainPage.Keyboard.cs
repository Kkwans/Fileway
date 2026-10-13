using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Windows.System;

namespace Fileway.App;

public sealed partial class MainPage
{
    private bool _keyboardOpenPending;

    // ListView/GridView item class handling can consume Enter before bubbling
    // KeyDown. Intercept only a plain Enter on the active file item in preview.
    private async void FileList_PreviewKeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Handled || e.Key != VirtualKey.Enter || !ReferenceEquals(sender, ActiveFiles) ||
            IsTextInput(e.OriginalSource as DependencyObject)) return;
        foreach (var modifier in new[] { VirtualKey.Control, VirtualKey.Menu, VirtualKey.Shift })
            if (Microsoft.UI.Input.InputKeyboardSource.GetKeyStateForCurrentThread(modifier)
                .HasFlag(Windows.UI.Core.CoreVirtualKeyStates.Down)) return;
        var source = e.OriginalSource as DependencyObject;
        while (source is not null && !ReferenceEquals(source, sender) && source is not SelectorItem)
        {
            if (source is ButtonBase) return;
            source = Microsoft.UI.Xaml.Media.VisualTreeHelper.GetParent(source);
        }
        if (source is not SelectorItem { Content: FileRow row } || sender is not ListViewBase view) return;
        e.Handled = true;
        if (_keyboardOpenPending || e.KeyStatus.WasKeyDown) return;
        _keyboardOpenPending = true;
        try
        {
            view.SelectedItem = row;
            await OpenSelectedAsync();
        }
        finally { _keyboardOpenPending = false; }
    }
}
