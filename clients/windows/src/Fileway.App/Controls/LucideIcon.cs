using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Data;

namespace Fileway.App.Controls;

/// <summary>Original Lucide artwork tinted by the native foreground, including control states.</summary>
public sealed class LucideIcon : UserControl
{
    private readonly BitmapIcon _image = new() { ShowAsMonochrome = true };
    public static readonly DependencyProperty IconNameProperty = DependencyProperty.Register(nameof(IconName), typeof(string), typeof(LucideIcon), new PropertyMetadata("file", (owner, _) => ((LucideIcon)owner).Refresh()));
    public string IconName { get => (string)GetValue(IconNameProperty); set => SetValue(IconNameProperty, value); }
    public LucideIcon()
    {
        Content = _image; IsHitTestVisible = false; IsTabStop = false;
        _image.SetBinding(IconElement.ForegroundProperty, new Binding { Source = this, Path = new PropertyPath(nameof(Foreground)) });
        Refresh();
    }
    private void Refresh() => _image.UriSource = LucideActionIcon.SourceFor(IconName);
    internal static LucideActionIcon Action(string name) => new() { IconName = name };
}

public sealed class LucideActionIcon : BitmapIcon
{
    private static readonly Dictionary<string, Uri> Sources = new(StringComparer.Ordinal);
    public static readonly DependencyProperty IconNameProperty = DependencyProperty.Register(nameof(IconName), typeof(string), typeof(LucideActionIcon), new PropertyMetadata("file", (owner, _) => ((LucideActionIcon)owner).Refresh()));
    public string IconName { get => (string)GetValue(IconNameProperty); set => SetValue(IconNameProperty, value); }
    public LucideActionIcon() { ShowAsMonochrome = true; Refresh(); }
    private void Refresh() => UriSource = SourceFor(IconName);
    internal static Uri SourceFor(string name)
    {
        // Only the existing artwork's alpha is used. Native Foreground resources handle
        // light/dark, high contrast, selection and disabled states without global subscriptions.
        if (!Sources.TryGetValue(name, out var source)) Sources.Add(name, source = new Uri("ms-appx:///Assets/Lucide/light/" + name + ".png"));
        return source;
    }
}
