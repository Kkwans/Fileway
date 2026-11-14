using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;
using Windows.UI;

namespace Fileway.App.Controls;

/// <summary>Resolves runtime colors against the owning window theme, rather than the OS default theme.</summary>
internal static class ThemePalette
{
    internal static Color Color(string key, ElementTheme theme) => Get<Color>(key, theme, highContrast: false);

    internal static double Number(string key, ElementTheme theme) => Get<double>(key, theme, highContrast: false);

    internal static SolidColorBrush Brush(string key, ElementTheme theme) =>
        Get<SolidColorBrush>(key, theme, new Windows.UI.ViewManagement.AccessibilitySettings().HighContrast);

    private static T Get<T>(string key, ElementTheme theme, bool highContrast)
    {
        var resources = Application.Current.Resources;
        string name = highContrast ? "HighContrast" :
            theme == ElementTheme.Light || theme == ElementTheme.Default && Application.Current.RequestedTheme == ApplicationTheme.Light
                ? "Light" : "Default";
        if (Find(resources, name, key) is T value) return value;
        throw new InvalidOperationException("Missing Fileway appearance token: " + key);
    }

    private static object? Find(ResourceDictionary resources, string theme, string key)
    {
        if (resources.ThemeDictionaries.TryGetValue(theme, out var item) && item is ResourceDictionary palette &&
            palette.TryGetValue(key, out var value)) return value;
        for (int index = resources.MergedDictionaries.Count - 1; index >= 0; index--)
            if (Find(resources.MergedDictionaries[index], theme, key) is { } found) return found;
        return null;
    }
}
