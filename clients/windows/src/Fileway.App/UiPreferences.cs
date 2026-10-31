using System.Text.Json;
using Microsoft.UI.Xaml;

namespace Fileway.App;

internal readonly record struct WindowBounds(int X, int Y, int Width, int Height);

internal static class UiPreferences
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
    };
    private static string? _path;

    private sealed class Preferences
    {
        public string? Theme { get; set; }
        public WindowBounds? Window { get; set; }
    }

    internal static void Configure(string dataDirectory) =>
        _path = Path.Combine(dataDirectory, "ui-preferences.json");

    internal static ElementTheme LoadTheme()
    {
        var theme = Read().Theme;
        return Enum.TryParse<ElementTheme>(theme, out var parsed) ? parsed : ElementTheme.Default;
    }

    internal static void SaveTheme(ElementTheme theme)
    {
        var preferences = Read();
        preferences.Theme = theme.ToString();
        Write(preferences);
    }

    internal static WindowBounds? LoadWindowBounds() => Read().Window;

    internal static void SaveWindowBounds(WindowBounds bounds)
    {
        var preferences = Read();
        preferences.Window = bounds;
        Write(preferences);
    }

    private static Preferences Read()
    {
        try
        {
            if (_path is null || !File.Exists(_path)) return new Preferences();
            return JsonSerializer.Deserialize<Preferences>(File.ReadAllText(_path), JsonOptions) ?? new Preferences();
        }
        catch (Exception) { return new Preferences(); }
    }

    private static void Write(Preferences preferences)
    {
        try
        {
            if (_path is null) return;
            File.WriteAllText(_path, JsonSerializer.Serialize(preferences, JsonOptions));
        }
        catch (Exception) { }
    }
}
