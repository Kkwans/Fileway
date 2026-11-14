// Buffer geometry extracted from Fileway's adapted Screenbox 6870eb0
// ChapterProgressBar implementation (GPL-3.0).
using Fileway.Core.Contracts;

namespace Fileway.App.Controls.Playback;

/// <summary>Time-based buffer geometry, independent of XAML and display size.</summary>
internal static class ScreenboxBufferedTimelineLayout
{
    internal static void MergeRanges(IReadOnlyList<BufferedTimeRange> ranges, List<(double Start, double End)> destination)
    {
        destination.Clear();
        foreach (var range in ranges)
        {
            var start = range.Start.TotalSeconds;
            var end = range.End.TotalSeconds;
            if (end > start) destination.Add((start, end));
        }
        destination.Sort(static (left, right) => left.Start.CompareTo(right.Start));
        var count = 0;
        for (var index = 0; index < destination.Count; index++)
        {
            var range = destination[index];
            if (count > 0 && range.Start <= destination[count - 1].End)
                destination[count - 1] = (destination[count - 1].Start, Math.Max(destination[count - 1].End, range.End));
            else destination[count++] = range;
        }
        if (count < destination.Count) destination.RemoveRange(count, destination.Count - count);
    }

    // Alternating gap/fill widths, followed by a final gap. Keeping zero-width
    // gaps gives the renderer stable slots as buffer endpoints reach boundaries.
    internal static void FillSegmentWidths(double start, double end, IReadOnlyList<(double Start, double End)> mergedRanges,
        List<double> destination)
    {
        destination.Clear();
        var cursor = start;
        foreach (var range in mergedRanges)
        {
            if (range.End <= start) continue;
            if (range.Start >= end) break;
            var intersectionStart = Math.Max(start, range.Start);
            var intersectionEnd = Math.Min(end, range.End);
            if (intersectionEnd <= intersectionStart) continue;
            destination.Add(intersectionStart - cursor);
            destination.Add(intersectionEnd - intersectionStart);
            cursor = intersectionEnd;
        }
        destination.Add(Math.Max(0, end - cursor));
    }
}
