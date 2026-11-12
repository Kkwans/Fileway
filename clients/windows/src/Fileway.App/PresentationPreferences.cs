using System.Runtime.InteropServices;
using System.Text.Json;
using Fileway.Core.Contracts;
using Microsoft.Win32;

namespace Fileway.App;

internal static class PresentationPreferences
{
    private const string Key = @"HKEY_CURRENT_USER\Software\Fileway\Preview\Presentation";
    private static readonly string? IsolatedDataRoot = Environment.GetEnvironmentVariable("FILEWAY_WINDOWS_DATA_ROOT");
    private static readonly object IsolatedValuesGate = new();
    private static Dictionary<string, JsonElement>? _isolatedValues;

    internal static event Action? AppearanceChanged;
    internal static int ShellMaterial { get => ReadChoice("ShellMaterial"); set { WriteValue("ShellMaterial", Math.Clamp(value, 0, 2)); AppearanceChanged?.Invoke(); } }
    internal static int ShellTone { get => ReadChoice("ShellTone", 3); set { WriteValue("ShellTone", Math.Clamp(value, 0, 3)); AppearanceChanged?.Invoke(); } }
    internal static Windows.UI.Color ShellTint
    {
        get
        {
            try
            {
                if (ReadValue<int?>("ShellTint", null) is int rgb)
                    return Windows.UI.Color.FromArgb(255, (byte)(rgb >> 16), (byte)(rgb >> 8), (byte)rgb);
            }
            catch (Exception) { }
            return Controls.ThemePalette.Color("FwShellCustomDefaultColor", Microsoft.UI.Xaml.ElementTheme.Default);
        }
        set { WriteValue("ShellTint", value.R << 16 | value.G << 8 | value.B); AppearanceChanged?.Invoke(); }
    }
    private static int ReadChoice(string name, int maximum = 2)
    { try { return Math.Clamp(ReadValue(name, 0), 0, maximum); } catch (Exception) { return 0; } }
    internal static bool RememberPlayerSize { get => ReadValue("RememberPlayerSize", 0) == 1; set => WriteValue("RememberPlayerSize", value ? 1 : 0); }
    internal static (int Width, int Height)? PlayerSize
    {
        get
        {
            lock (IsolatedValuesGate)
            {
                var width = ReadValue("PlayerWidth", 0);
                var height = ReadValue("PlayerHeight", 0);
                return width is >= 480 and <= 7680 && height is >= 270 and <= 4320 ? (width, height) : null;
            }
        }
        set
        {
            if (value is not { } size) return;
            if (IsolatedDataRoot is null)
            {
                Registry.SetValue(Key, "PlayerWidth", size.Width);
                Registry.SetValue(Key, "PlayerHeight", size.Height);
                return;
            }

            WriteIsolatedValues(values =>
            {
                values["PlayerWidth"] = JsonSerializer.SerializeToElement(size.Width);
                values["PlayerHeight"] = JsonSerializer.SerializeToElement(size.Height);
            });
        }
    }
    internal static int ControlsHideSeconds { get => ReadSeconds("ControlsHideSeconds"); set => WriteValue("ControlsHideSeconds", Math.Clamp(value, 2, 30)); }
    internal static int ToastSeconds { get => ReadSeconds("ToastSeconds"); set => WriteValue("ToastSeconds", Math.Clamp(value, 2, 30)); }
    internal const int MinimumImageThumbnailHeight = 56;
    internal const int DefaultImageThumbnailHeight = 82;
    internal const int MaximumImageThumbnailHeight = 120;
    internal static int ImageThumbnailHeight
    {
        get
        {
            try { return Math.Clamp(ReadValue(nameof(ImageThumbnailHeight), DefaultImageThumbnailHeight), MinimumImageThumbnailHeight, MaximumImageThumbnailHeight); }
            catch (Exception) { return DefaultImageThumbnailHeight; }
        }
        set => WriteValue(nameof(ImageThumbnailHeight), Math.Clamp(value, MinimumImageThumbnailHeight, MaximumImageThumbnailHeight));
    }
    private static int ReadSeconds(string name)
    {
        try { return Math.Clamp(ReadValue(name, 5), 2, 30); }
        catch (Exception) { return 5; }
    }
    internal static SubtitlePreferences Subtitles
    {
        get
        {
            try
            {
                var value = ReadValue<string?>("Subtitles", null);
                var prefs = value is { Length: < 4096 } ? JsonSerializer.Deserialize<SubtitlePreferences>(value) : null;
                return prefs is not null && double.IsFinite(prefs.FontSize) && prefs.FontSize is >= 8 and <= 120 &&
                    double.IsFinite(prefs.Scale) && prefs.Scale is >= 0.1 and <= 10 &&
                    double.IsFinite(prefs.VerticalPositionPercent) && prefs.VerticalPositionPercent is >= 0 and <= 150
                    ? prefs : new();
            }
            catch (Exception) { return new(); }
        }
        set => WriteValue("Subtitles", JsonSerializer.Serialize(value));
    }

    private static string IsolatedPath => Path.Combine(Path.GetFullPath(IsolatedDataRoot!), "presentation-preferences.json");

    private static T ReadValue<T>(string name, T fallback)
    {
        if (IsolatedDataRoot is null)
            return Registry.GetValue(Key, name, fallback) is T registered ? registered : fallback;

        lock (IsolatedValuesGate)
        {
            if (!LoadIsolatedValues().TryGetValue(name, out var value)) return fallback;
            try { return value.Deserialize<T>() is { } parsed ? parsed : fallback; }
            catch (JsonException) { return fallback; }
        }
    }

    private static void WriteValue<T>(string name, T value) where T : notnull
    {
        if (IsolatedDataRoot is null) Registry.SetValue(Key, name, value);
        else WriteIsolatedValues(values => values[name] = JsonSerializer.SerializeToElement(value));
    }

    private static Dictionary<string, JsonElement> LoadIsolatedValues()
    {
        if (_isolatedValues is not null) return _isolatedValues;
        try
        {
            using var file = File.OpenRead(IsolatedPath);
            return _isolatedValues = JsonSerializer.Deserialize<Dictionary<string, JsonElement>>(file) ?? new();
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException or ArgumentException)
        {
            return _isolatedValues = new();
        }
    }

    private static void WriteIsolatedValues(Action<Dictionary<string, JsonElement>> update)
    {
        lock (IsolatedValuesGate)
        {
            var values = new Dictionary<string, JsonElement>(LoadIsolatedValues());
            update(values);
            var path = IsolatedPath;
            var temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            try
            {
                File.WriteAllText(temporary, JsonSerializer.Serialize(values));
                File.Move(temporary, path, overwrite: true);
                _isolatedValues = values;
            }
            finally
            {
                try { File.Delete(temporary); }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
            }
        }
    }
}

internal static class InstalledFonts
{
    private static readonly Lazy<Task<IReadOnlyList<string>>> Families = new(() => Task.Run(Enumerate));
    internal static Task<IReadOnlyList<string>> GetAsync() => Families.Value;
    private static IReadOnlyList<string> Enumerate()
    {
        var result = new SortedSet<string>(StringComparer.CurrentCultureIgnoreCase);
        var dc = GetDC(0);
        if (dc == 0) return Array.Empty<string>();
        try
        {
            var font = new LogFont { CharSet = 1, FaceName = "" };
            FontCallback callback = (data, _, _, _) =>
            {
                var name = Marshal.PtrToStructure<LogFont>(data).FaceName;
                if (!string.IsNullOrWhiteSpace(name) && !name.StartsWith('@')) result.Add(name);
                return 1;
            };
            _ = EnumFontFamiliesExW(dc, ref font, callback, 0, 0);
            GC.KeepAlive(callback);
            return result.ToArray();
        }
        finally { _ = ReleaseDC(0, dc); }
    }
    [StructLayout(LayoutKind.Sequential, CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
    private struct LogFont
    {
        internal int Height, Width, Escapement, Orientation, Weight;
        internal byte Italic, Underline, StrikeOut, CharSet, OutPrecision, ClipPrecision, Quality, PitchAndFamily;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] internal string FaceName;
    }
    private delegate int FontCallback(nint font, nint metric, uint type, nint state);
    [DllImport("gdi32.dll", CharSet = CharSet.Unicode, ExactSpelling = true)] private static extern int EnumFontFamiliesExW(nint dc, ref LogFont font, FontCallback callback, nint state, uint flags);
    [DllImport("user32.dll")] private static extern nint GetDC(nint window);
    [DllImport("user32.dll")] private static extern int ReleaseDC(nint window, nint dc);
}
