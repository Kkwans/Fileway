using System.Text.Json;
using Microsoft.UI.Xaml;

namespace Fileway.App;

internal readonly record struct WindowBounds(int X, int Y, int Width, int Height);

internal readonly record struct WindowState(WindowBounds? Bounds, bool Maximized);

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
        public bool Maximized { get; set; }
        public bool SidebarCollapsed { get; set; }
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

    internal static WindowState LoadWindowState()
    {
        var preferences = Read();
        return new WindowState(preferences.Window, preferences.Maximized);
    }

    internal static bool LoadSidebarCollapsed() => Read().SidebarCollapsed;

    internal static void SaveSidebarCollapsed(bool collapsed)
    {
        var preferences = Read();
        preferences.SidebarCollapsed = collapsed;
        Write(preferences);
    }

    internal static void SaveWindowState(WindowBounds? bounds, bool maximized)
    {
        var preferences = Read();
        if (bounds is { } saved) preferences.Window = saved;
        preferences.Maximized = maximized;
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
