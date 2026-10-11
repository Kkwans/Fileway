using System.Globalization;
using Fileway.Core.Contracts;

namespace Fileway.App.MediaSurfaces;

/// <summary>Local picker admission rules; subtitle bytes are never loaded by the UI.</summary>
internal static class LocalSubtitlePolicy
{
    internal const long MaximumFileBytes = 500_000_000;
    internal static IReadOnlyList<string> Extensions { get; } = Array.AsReadOnly<string>(
        [".srt", ".ass", ".ssa", ".vtt", ".sup", ".ttml", ".dfxp", ".smi", ".sami"]);

    internal static string? GetRejection(string path, long size)
    {
        if (!Path.IsPathFullyQualified(path)
            || !Extensions.Contains(Path.GetExtension(path), StringComparer.OrdinalIgnoreCase))
            return "请选择可直接读取的本地字幕文件。";
        return size > MaximumFileBytes
            ? string.Create(CultureInfo.InvariantCulture,
                $"所选字幕超过 {MaximumFileBytes / 1_000_000} MB（{MaximumFileBytes:N0} 字节）上限。")
            : null;
    }

    internal static bool IsCurrentRequest(long requestGeneration, long activeGeneration,
        PlaybackSnapshot origin, PlaybackSnapshot current, bool cancellationRequested) =>
        !cancellationRequested && requestGeneration == activeGeneration
        && current.SessionId == origin.SessionId && current.Generation == origin.Generation
        && current.Status is not (PlaybackStatus.Closing or PlaybackStatus.Stopped);
}
