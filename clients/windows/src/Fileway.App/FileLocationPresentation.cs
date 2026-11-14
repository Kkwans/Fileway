namespace Fileway.App;

internal static class FileLocationPresentation
{
    internal static string SearchDirectory(string relativeDisplayPath, string displayName) =>
        ParentDirectory(relativeDisplayPath, displayName, rooted: false, directDirectory: "起始目录");

    internal static string RecentDirectory(string displayPath, string displayName) =>
        ParentDirectory(displayPath, displayName, rooted: true, directDirectory: "根目录");

    private static string ParentDirectory(string displayPath, string displayName, bool rooted, string directDirectory)
    {
        if (displayPath.StartsWith('/') != rooted || displayPath.Contains('\0')) return displayPath;

        int separator = displayPath.LastIndexOf('/');
        var leaf = displayPath.AsSpan(separator + 1);
        if (leaf.IsEmpty || !leaf.Equals(displayName.AsSpan(), StringComparison.Ordinal)) return displayPath;

        var segments = displayPath.AsSpan(rooted ? 1 : 0);
        foreach (Range range in segments.Split('/'))
        {
            var segment = segments[range];
            if (segment.IsEmpty || segment is "." or "..") return displayPath;
        }

        return separator <= 0 ? directDirectory : displayPath[..separator];
    }
}
