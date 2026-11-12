namespace Fileway.App;

internal static class FileTypePresentation
{
    internal static readonly HashSet<string> ImageExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".jpg", ".jpeg", ".png", ".bmp", ".gif", ".webp", ".tif", ".tiff", ".heic", ".avif",
    };

    internal static readonly HashSet<string> VideoExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".mp4", ".mkv", ".mov", ".webm", ".avi", ".m4v", ".ts", ".m2ts", ".wmv",
    };

    internal static bool IsImage(string extension, string? mediaType) =>
        mediaType?.StartsWith("image/", StringComparison.OrdinalIgnoreCase) == true || ImageExtensions.Contains(extension);

    internal static bool IsVideo(string extension, string? mediaType) =>
        mediaType?.StartsWith("video/", StringComparison.OrdinalIgnoreCase) == true || VideoExtensions.Contains(extension);

    internal static bool HasAudioMediaType(string? mediaType) =>
        mediaType?.StartsWith("audio/", StringComparison.OrdinalIgnoreCase) == true;

    internal static string ResolveIconName(string displayName, bool isDirectory, string? mediaType = null)
    {
        if (isDirectory) return "folder";

        var extension = Path.GetExtension(displayName);
        if (IsImage(extension, mediaType)) return "image";
        if (IsVideo(extension, mediaType)) return "film-strip";
        if (HasAudioMediaType(mediaType)) return "headphones";

        return extension.ToLowerInvariant() switch
        {
            ".srt" or ".ass" or ".txt" or ".md" => "article",
            ".cs" or ".py" or ".go" or ".json" => "file-code",
            _ => "file",
        };
    }
}
