using System.Security;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Persistence;

internal static class StorageSettingsValidation
{
    internal const string ManagedCacheDirectoryName = "fileway-cache-v1";

    internal static ClientStorageSettings Normalize(ClientStorageSettings settings)
    {
        if (settings is null || settings.MaximumCacheBytes is < 0 or > ClientStorageSettings.MaximumSupportedCacheBytes)
            throw Invalid();
        try
        {
            string cache = DirectoryPath(settings.CacheDirectory);
            string download = DirectoryPath(settings.DownloadDirectory);
            string managed = Path.Combine(cache, ManagedCacheDirectoryName);
            // A shared parent is safe; downloads must never be placed inside the managed pool.
            if (SameDirectory(download, managed) || download.StartsWith(managed + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
                throw Invalid();
            return settings with { CacheDirectory = cache, DownloadDirectory = download };
        }
        catch (Exception error) when (error is ArgumentException or IOException or UnauthorizedAccessException or SecurityException or NotSupportedException)
        { throw Invalid(); }
    }

    internal static bool SameDirectory(string left, string right) => StringComparer.OrdinalIgnoreCase.Equals(left, right);

    internal static string DirectoryPath(string value)
    {
        if (string.IsNullOrWhiteSpace(value) || !Path.IsPathFullyQualified(value) || value.Any(char.IsControl)) throw Invalid();
        // Device syntax and Win32 trailing-dot/space aliases would defeat a lexical ownership boundary.
        if (value.StartsWith(@"\\?\", StringComparison.Ordinal) || value.StartsWith(@"\\.\", StringComparison.Ordinal)) throw Invalid();
        string full = Path.GetFullPath(value);
        string relative = full[(Path.GetPathRoot(full)?.Length ?? 0)..];
        foreach (string part in relative.Split([Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar], StringSplitOptions.RemoveEmptyEntries))
            if (part.EndsWith('.') || part.EndsWith(' ') || part.IndexOfAny(['<', '>', ':', '"', '|', '?', '*']) >= 0) throw Invalid();
        return Path.TrimEndingDirectorySeparator(full);
    }

    internal static FilewayException Invalid() => new(new ErrorInfo(ErrorCode.InvalidRequest, "请输入有效的独立本地目录，缓存上限须在 0 到 1 TiB 之间。"));
}
