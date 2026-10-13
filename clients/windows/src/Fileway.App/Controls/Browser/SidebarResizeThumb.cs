using Microsoft.UI.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Markup;

namespace Fileway.App.Controls.Browser;

/// <summary>A native Thumb host whose cursor applies only to its sidebar resize target.</summary>
public sealed class SidebarResizeThumb : UserControl
{
    private InputSystemCursor? _resizeCursor;
    public event DragDeltaEventHandler? DragDelta;

    public SidebarResizeThumb()
    {
        var thumb = new Thumb
        {
            HorizontalAlignment = HorizontalAlignment.Stretch,
            VerticalAlignment = VerticalAlignment.Stretch,
            IsTabStop = false,
            Template = (ControlTemplate)XamlReader.Load(
                "<ControlTemplate xmlns=\"http://schemas.microsoft.com/winfx/2006/xaml/presentation\" TargetType=\"Thumb\"><Grid Background=\"Transparent\"/></ControlTemplate>"),
        };
        thumb.DragDelta += (_, args) => DragDelta?.Invoke(this, args);
        Content = thumb;
        Loaded += (_, _) =>
        {
            _resizeCursor ??= InputSystemCursor.Create(InputSystemCursorShape.SizeWestEast);
            ProtectedCursor = _resizeCursor;
        };
        Unloaded += (_, _) =>
        {
            ProtectedCursor = null;
            _resizeCursor?.Dispose();
            _resizeCursor = null;
        };
    }
}
