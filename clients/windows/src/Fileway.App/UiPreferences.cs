using System.Text.Json;
using Microsoft.UI.Xaml;

namespace Fileway.App;

internal static class UiPreferences
{
    private static string? _path;

    internal static void Configure(string dataDirectory) =>
        _path = Path.Combine(dataDirectory, "ui-preferences.json");

    internal static ElementTheme LoadTheme()
    {
        try
        {
            if (_path is null || !File.Exists(_path)) return ElementTheme.Default;
            using var document = JsonDocument.Parse(File.ReadAllText(_path));
            return document.RootElement.TryGetProperty("theme", out var value)
                && Enum.TryParse<ElementTheme>(value.GetString(), out var theme) ? theme : ElementTheme.Default;
        }
        catch (Exception) { return ElementTheme.Default; }
    }

    internal static void SaveTheme(ElementTheme theme)
    {
        try
        {
            if (_path is null) return;
            File.WriteAllText(_path, JsonSerializer.Serialize(new { theme = theme.ToString() }));
        }
        catch (Exception) { }
    }
}
