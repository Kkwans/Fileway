using Fileway.App.Controls.Playback;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Tests.Playback;

public sealed class ScreenboxBufferedTimelineLayoutTests
{
    [Fact]
    public void DisjointRangesKeepEveryUnbufferedGap()
    {
        var widths = Layout(0, 100, [Range(10, 20), Range(40, 60)]);
        Assert.Equal(new double[] { 10, 10, 20, 20, 40 }, widths);
    }

    [Fact]
    public void UnsortedOverlappingAndTouchingRangesBecomeOneFill()
    {
        var widths = Layout(0, 100, [Range(30, 50), Range(10, 25), Range(20, 30), Range(10, 25)]);
        Assert.Equal(new double[] { 10, 40, 50 }, widths);
    }

    [Fact]
    public void TinyPositiveGapsAreNotRoundedIntoContiguousBuffering()
    {
        var widths = Layout(0, 10, [Range(1, 2), Range(2.0000001, 3)]);
        Assert.Equal(5, widths.Count);
        Assert.True(widths[2] > 0);
        Assert.Equal(0.0000001, widths[2], precision: 12);
    }

    [Fact]
    public void ChapterIntersectionsClipToTheirOwnBoundaries()
    {
        BufferedTimeRange[] ranges = [Range(-10, 15), Range(25, 45), Range(70, 120)];
        Assert.Equal(new double[] { 0, 15, 10, 5, 0 }, Layout(0, 30, ranges));
        Assert.Equal(new double[] { 0, 15, 25, 20, 0 }, Layout(30, 90, ranges));
        Assert.Equal(new double[] { 0, 10, 0 }, Layout(90, 100, ranges));
    }

    [Fact]
    public void EmptyInvalidAndOutOfChapterRangesLeaveOnlyTrack()
    {
        Assert.Equal(new double[] { 10 }, Layout(20, 30, []));
        Assert.Equal(new double[] { 10 }, Layout(20, 30,
            [Range(2, 2), Range(9, 3), Range(-10, -1), Range(0, 20), Range(30, 40)]));
        Assert.Equal(new double[] { 0 }, Layout(0, 0, [Range(0, 10)]));
    }

    [Fact]
    public void MovingEndpointsRetainStableGapFillSlotsAtChapterBoundaries()
    {
        Assert.Equal(new double[] { 1, 8, 1 }, Layout(0, 10, [Range(1, 9)]));
        Assert.Equal(new double[] { 0, 9, 1 }, Layout(0, 10, [Range(0, 9)]));
        Assert.Equal(new double[] { 0, 10, 0 }, Layout(0, 10, [Range(-1, 11)]));
    }

    [Fact]
    public void ReusedDestinationsDoNotKeepStaleRangesAfterShrinkOrClear()
    {
        var merged = new List<(double Start, double End)>();
        var widths = new List<double>();
        Apply([Range(1, 2), Range(4, 5), Range(7, 8)]);
        Assert.Equal(new double[] { 1, 1, 2, 1, 2, 1, 2 }, widths);
        Apply([Range(0, 10)]);
        Assert.Equal(new double[] { 0, 10, 0 }, widths);
        Apply([]);
        Assert.Empty(merged);
        Assert.Equal(new double[] { 10 }, widths);
        Apply([Range(3, 4), Range(6, 7)]);
        Assert.Equal(new double[] { 3, 1, 2, 1, 3 }, widths);

        void Apply(BufferedTimeRange[] ranges)
        {
            ScreenboxBufferedTimelineLayout.MergeRanges(ranges, merged);
            ScreenboxBufferedTimelineLayout.FillSegmentWidths(0, 10, merged, widths);
        }
    }

    [Fact]
    public void GlobalUnionMatchesOriginalPerChapterClippingAcrossChangingSnapshots()
    {
        var random = new Random(6870);
        var merged = new List<(double Start, double End)>();
        var widths = new List<double>();
        for (var snapshot = 0; snapshot < 500; snapshot++)
        {
            var ranges = Enumerable.Range(0, random.Next(0, 20))
                .Select(_ => Range(random.Next(-20, 121), random.Next(-20, 121))).ToArray();
            ScreenboxBufferedTimelineLayout.MergeRanges(ranges, merged);
            foreach (var (start, end) in new (double, double)[] { (0, 10), (10, 30), (30, 60), (60, 100) })
            {
                ScreenboxBufferedTimelineLayout.FillSegmentWidths(start, end, merged, widths);
                Assert.True(widths.Count % 2 == 1);
                Assert.All(widths, width => Assert.True(width >= 0));
                Assert.Equal(end - start, widths.Sum(), precision: 10);
                var actual = new List<(double Start, double End)>();
                var cursor = start;
                for (var index = 0; index < widths.Count; index++)
                {
                    if (index % 2 == 1) actual.Add((cursor, cursor + widths[index]));
                    cursor += widths[index];
                }
                Assert.Equal(OriginalIntersections(start, end, ranges), actual);
            }
        }
    }

    private static List<double> Layout(double start, double end, BufferedTimeRange[] ranges)
    {
        var merged = new List<(double Start, double End)>();
        var widths = new List<double>();
        ScreenboxBufferedTimelineLayout.MergeRanges(ranges, merged);
        ScreenboxBufferedTimelineLayout.FillSegmentWidths(start, end, merged, widths);
        return widths;
    }

    private static List<(double Start, double End)> OriginalIntersections(double start, double end, BufferedTimeRange[] ranges)
    {
        var result = new List<(double Start, double End)>();
        foreach (var range in ranges.Select(range => (Start: Math.Max(start, range.Start.TotalSeconds), End: Math.Min(end, range.End.TotalSeconds)))
            .Where(range => range.End > range.Start).OrderBy(range => range.Start))
        {
            if (result.Count > 0 && range.Start <= result[^1].End)
                result[^1] = (result[^1].Start, Math.Max(result[^1].End, range.End));
            else result.Add(range);
        }
        return result;
    }

    private static BufferedTimeRange Range(double start, double end) => new(TimeSpan.FromSeconds(start), TimeSpan.FromSeconds(end));
}
