using System.Text.Json;
using System.Text.Json.Serialization;
using Fileway.Core.Contracts;

namespace Fileway.App;

/// <summary>Small local contract for per-category open preferences; never stores credentials.</summary>
internal static class OpenWithPreferencesStore
{
    private static readonly JsonSerializerOptions Options = new()
    {
        WriteIndented = true,
        Converters = { new JsonStringEnumConverter() },
    };

    private static string FilePath => Path.Combine(App.DataDirectory(), "open-with.json");

    internal static OpenWithPreferences Load()
    {
        try
        {
            if (!File.Exists(FilePath)) return OpenWithPreferences.Default;
            return JsonSerializer.Deserialize<OpenWithPreferences>(File.ReadAllText(FilePath), Options) ?? OpenWithPreferences.Default;
        }
        catch (Exception) { return OpenWithPreferences.Default; }
    }

    internal static void Save(OpenWithPreferences preferences)
    {
        var path = FilePath;
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temporary = path + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(preferences, Options));
        File.Move(temporary, path, overwrite: true);
    }
}
