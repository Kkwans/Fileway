using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Imaging;

namespace Fileway.App.Controls;

/// <summary>Original Lucide artwork; no resource-dictionary registration at startup.</summary>
public sealed class LucideIcon : UserControl
{
    private readonly Image _image = new() { Stretch = Microsoft.UI.Xaml.Media.Stretch.Uniform };
    public static readonly DependencyProperty IconNameProperty = DependencyProperty.Register(nameof(IconName), typeof(string), typeof(LucideIcon), new PropertyMetadata("file", (owner, _) => ((LucideIcon)owner).Refresh()));
    public string IconName { get => (string)GetValue(IconNameProperty); set => SetValue(IconNameProperty, value); }
    public LucideIcon()
    {
        Content = _image; IsHitTestVisible = false; IsTabStop = false;
        Loaded += (_, _) => Refresh(); ActualThemeChanged += (_, _) => Refresh();
    }
    private void Refresh() => _image.Source = LucideActionIcon.SourceFor(IconName, ActualTheme);
    internal static LucideActionIcon Action(string name) => new() { IconName = name };
}

public sealed class LucideActionIcon : ImageIcon
{
    private static readonly Dictionary<string, BitmapImage> Sources = new(StringComparer.Ordinal);
    public static readonly DependencyProperty IconNameProperty = DependencyProperty.Register(nameof(IconName), typeof(string), typeof(LucideActionIcon), new PropertyMetadata("file", (owner, _) => ((LucideActionIcon)owner).Refresh()));
    public string IconName { get => (string)GetValue(IconNameProperty); set => SetValue(IconNameProperty, value); }
    public LucideActionIcon() { Loaded += (_, _) => Refresh(); ActualThemeChanged += (_, _) => Refresh(); }
    private void Refresh() => Source = SourceFor(IconName, ActualTheme);
    internal static BitmapImage SourceFor(string name, ElementTheme theme)
    {
        var dark = theme == ElementTheme.Dark;
        var accessibility = new Windows.UI.ViewManagement.AccessibilitySettings();
        if (accessibility.HighContrast)
        {
            var color = new Windows.UI.ViewManagement.UISettings().GetColorValue(Windows.UI.ViewManagement.UIColorType.Foreground);
            dark = color.R + color.G + color.B > 384;
        }
        var key = (dark ? "dark/" : "light/") + name;
        if (!Sources.TryGetValue(key, out var source)) Sources.Add(key, source = new BitmapImage(new Uri("ms-appx:///Assets/Lucide/" + key + ".png")));
        return source;
    }
}
