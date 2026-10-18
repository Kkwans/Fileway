using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Data;
using Microsoft.UI.Xaml.Markup;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;

namespace Fileway.App.Controls;

/// <summary>Unmodified Phosphor Duotone paths, with inherited theme foreground.</summary>
public sealed class PhosphorIcon : UserControl
{
    public static readonly DependencyProperty IconNameProperty = DependencyProperty.Register(nameof(IconName), typeof(string), typeof(PhosphorIcon), new PropertyMetadata("file", (owner, _) => ((PhosphorIcon)owner).Draw()));
    public string IconName { get => (string)GetValue(IconNameProperty); set => SetValue(IconNameProperty, value); }
    public PhosphorIcon() { IsHitTestVisible = false; IsTabStop = false; Draw(); }
    private void Draw()
    {
        var canvas = new Canvas { Width = 256, Height = 256 };
        if (!PhosphorIconData.Paths.TryGetValue(IconName ?? "file", out var paths)) paths = PhosphorIconData.Paths["file"];
        foreach (var source in paths)
        {
            var path = new Microsoft.UI.Xaml.Shapes.Path { Data = (Geometry)XamlBindingHelper.ConvertValue(typeof(Geometry), source.Data), Opacity = source.Opacity };
            path.SetBinding(Shape.FillProperty, new Binding { Source = this, Path = new PropertyPath(nameof(Foreground)) });
            canvas.Children.Add(path);
        }
        Content = new Viewbox { Child = canvas, Stretch = Stretch.Uniform };
    }
    internal static PathIcon Action(string name) => new() { Data = (Geometry)Application.Current.Resources["Phosphor-" + name] };
    internal static void RegisterResources(ResourceDictionary resources)
    {
        foreach (var entry in PhosphorIconData.Paths)
        {
            resources["Phosphor-" + entry.Key] = ((Microsoft.UI.Xaml.Shapes.Path)resources["Phosphor-source-" + entry.Key]).Data;
        }
    }
}
