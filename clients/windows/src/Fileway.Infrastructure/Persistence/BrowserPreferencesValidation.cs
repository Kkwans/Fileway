using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Persistence;

internal static class BrowserPreferencesValidation
{
    internal static string DirectoryIdentity(AccountKey account, string directoryIdentity)
    {
        ProfileStoreValidation.Validate(account);
        if (string.IsNullOrWhiteSpace(account.ServerUserId) || account.ServerUserId.Any(char.IsControl) ||
            string.IsNullOrEmpty(directoryIdentity) || directoryIdentity.Length % 2 != 0 ||
            directoryIdentity.Any(character => !char.IsAsciiHexDigit(character))) throw Unavailable();
        // This is already-decoded opaque path identity, not a local path or URL.
        // Hex casing is the only normalization performed by persistence.
        return directoryIdentity.ToUpperInvariant();
    }

    internal static void Validate(BrowserPreferences preferences)
    {
        if (preferences is null || !Enum.IsDefined(preferences.ViewMode) || !Enum.IsDefined(preferences.CompactGridSize) ||
            !Enum.IsDefined(preferences.WindowsIconSize) || !Enum.IsDefined(preferences.TypeFilter) ||
            !Enum.IsDefined(preferences.SortBy)) throw Unavailable();
    }

    internal static FilewayException Unavailable() => new(new ErrorInfo(
        ErrorCode.LocalStorageUnavailable, "无法访问本地浏览偏好，请稍后重试。"));
}
