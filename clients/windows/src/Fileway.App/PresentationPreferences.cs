using System.Runtime.InteropServices;
using System.Text.Json;
using Fileway.Core.Contracts;
using Microsoft.Win32;

namespace Fileway.App;

internal static class PresentationPreferences
{
    private const string Key = @"HKEY_CURRENT_USER\Software\Fileway\Preview\Presentation";
    internal static bool RememberPlayerSize { get => Registry.GetValue(Key, "RememberPlayerSize", 0) is int value && value == 1; set => Registry.SetValue(Key, "RememberPlayerSize", value ? 1 : 0); }
    internal static (int Width, int Height)? PlayerSize
    {
        get => Registry.GetValue(Key, "PlayerWidth", 0) is int w && Registry.GetValue(Key, "PlayerHeight", 0) is int h && w is >= 480 and <= 7680 && h is >= 270 and <= 4320 ? (w, h) : null;
        set { if (value is { } size) { Registry.SetValue(Key, "PlayerWidth", size.Width); Registry.SetValue(Key, "PlayerHeight", size.Height); } }
    }
    internal static int ControlsHideSeconds { get => ReadSeconds("ControlsHideSeconds"); set => Registry.SetValue(Key, "ControlsHideSeconds", Math.Clamp(value, 2, 30)); }
    internal static int ToastSeconds { get => ReadSeconds("ToastSeconds"); set => Registry.SetValue(Key, "ToastSeconds", Math.Clamp(value, 2, 30)); }
    private static int ReadSeconds(string name)
    {
        try { return Registry.GetValue(Key, name, 5) is int value ? Math.Clamp(value, 2, 30) : 5; }
        catch (Exception) { return 5; }
    }
    internal static SubtitlePreferences Subtitles
    {
        get
        {
            try
            {
                var value = Registry.GetValue(Key, "Subtitles", null) as string;
                var prefs = value is { Length: < 4096 } ? JsonSerializer.Deserialize<SubtitlePreferences>(value) : null;
                return prefs is not null && double.IsFinite(prefs.FontSize) && prefs.FontSize is >= 8 and <= 120 &&
                    double.IsFinite(prefs.Scale) && prefs.Scale is >= 0.1 and <= 10 &&
                    double.IsFinite(prefs.VerticalPositionPercent) && prefs.VerticalPositionPercent is >= 0 and <= 150
                    ? prefs : new();
            }
            catch (Exception) { return new(); }
        }
        set => Registry.SetValue(Key, "Subtitles", JsonSerializer.Serialize(value));
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
