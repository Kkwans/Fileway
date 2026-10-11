using System.Text.Json;
using Fileway.Core.Contracts;

namespace Fileway.Playback;

/// <summary>Only native seekable intervals count as buffered media time.</summary>
internal static class PlaybackCacheState
{
    internal static IReadOnlyList<BufferedTimeRange>? ParseRanges(JsonElement? value, TimeSpan? duration)
    {
        if (value is not { ValueKind: JsonValueKind.Object } state ||
            !state.TryGetProperty("seekable-ranges", out var ranges) || ranges.ValueKind != JsonValueKind.Array)
            return null;

        var parsed = new List<BufferedTimeRange>();
        foreach (var range in ranges.EnumerateArray())
        {
            if (range.ValueKind != JsonValueKind.Object || !range.TryGetProperty("start", out var start) ||
                !range.TryGetProperty("end", out var end) || start.ValueKind != JsonValueKind.Number ||
                end.ValueKind != JsonValueKind.Number || !start.TryGetDouble(out var from) ||
                !end.TryGetDouble(out var to) || !double.IsFinite(from) || !double.IsFinite(to) || to < from)
                continue;

            from = Math.Max(0, from);
            if (duration is { } limit) to = Math.Min(to, limit.TotalSeconds);
            if (to < from || from >= TimeSpan.MaxValue.TotalSeconds || to >= TimeSpan.MaxValue.TotalSeconds)
                continue;
            parsed.Add(new BufferedTimeRange(TimeSpan.FromSeconds(from), TimeSpan.FromSeconds(to)));
        }

        parsed.Sort((left, right) => left.Start.CompareTo(right.Start));
        var merged = new List<BufferedTimeRange>();
        foreach (var range in parsed)
        {
            if (merged.Count > 0 && range.Start <= merged[^1].End)
                merged[^1] = new BufferedTimeRange(merged[^1].Start, range.End > merged[^1].End ? range.End : merged[^1].End);
            else merged.Add(range);
        }
        return merged.AsReadOnly();
    }

    internal static TimeSpan? ReaderEnd(JsonElement? value, IReadOnlyList<BufferedTimeRange>? ranges)
    {
        if (ranges is null || value is not { ValueKind: JsonValueKind.Object } state ||
            !state.TryGetProperty("reader-pts", out var reader) || reader.ValueKind != JsonValueKind.Number ||
            !reader.TryGetDouble(out var seconds) || !double.IsFinite(seconds) || seconds < 0 ||
            seconds >= TimeSpan.MaxValue.TotalSeconds) return null;

        var position = TimeSpan.FromSeconds(seconds);
        foreach (var range in ranges)
            if (range.Start <= position && position <= range.End) return range.End;
        return null;
    }
}
