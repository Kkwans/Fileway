using System.Globalization;
using System.Runtime.InteropServices;
using Windows.System.UserProfile;

namespace Fileway.App.Branding;

/// <summary>Product naming only; installation identities and data paths stay stable.</summary>
internal static class AppBranding
{
    internal static string DisplayName { get; } = ResolveDisplayName();
    internal static bool IsChinese => DisplayName == "栖卷";

    private static string ResolveDisplayName()
    {
        // The installer records its explicit language choice beside this version's EXE.
        // Portable packages use the current user's Windows preferences instead.
        try
        {
            var choice = new FileInfo(Path.Combine(AppContext.BaseDirectory, "product-language.txt"));
            if (choice.Exists && choice.Length <= 32)
            {
                var language = File.ReadAllText(choice.FullName).Trim();
                if (language.Equals("zh-CN", StringComparison.OrdinalIgnoreCase)) return "栖卷";
                if (language.Equals("en", StringComparison.OrdinalIgnoreCase)) return "Fileway";
            }
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException) { }

        try
        {
            var uiLanguage = GetUserDefaultUILanguage();
            var region = GlobalizationPreferences.HomeGeographicRegion;
            if ((uiLanguage & 0x3ff) == 0x04 || region.Equals("CN", StringComparison.OrdinalIgnoreCase)) return "栖卷";
            if (uiLanguage != 0 || !string.IsNullOrWhiteSpace(region)) return "Fileway";
        }
        catch (Exception exception) when (exception is COMException or DllNotFoundException or EntryPointNotFoundException)
        {
            if (CultureInfo.CurrentUICulture.TwoLetterISOLanguageName.Equals("zh", StringComparison.OrdinalIgnoreCase)) return "栖卷";
            if (!CultureInfo.CurrentUICulture.Equals(CultureInfo.InvariantCulture)) return "Fileway";
        }
        return "栖卷";
    }

    [DllImport("kernel32.dll", ExactSpelling = true)]
    private static extern ushort GetUserDefaultUILanguage();
}
