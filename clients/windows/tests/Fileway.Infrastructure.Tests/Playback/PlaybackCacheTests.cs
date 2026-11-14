using System.Diagnostics;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Playback;

namespace Fileway.Infrastructure.Tests.Playback;

public sealed class PlaybackCacheTests
{
    [Fact]
    public void SeekBufferRetainsItsStartInsteadOfClaimingEarlierMedia()
    {
        using var document = JsonDocument.Parse("""{"reader-pts":625,"seekable-ranges":[{"start":600,"end":660}]}""");
        var ranges = PlaybackCacheState.ParseRanges(document.RootElement, TimeSpan.FromSeconds(900));
        Assert.Equal(new BufferedTimeRange(TimeSpan.FromSeconds(600), TimeSpan.FromSeconds(660)), Assert.Single(ranges!));
        Assert.Equal(TimeSpan.FromSeconds(660), PlaybackCacheState.ReaderEnd(document.RootElement, ranges));
    }

    [Fact]
    public void DisconnectedBuffersStaySeparateAndInvalidIntervalsDoNotExpandCoverage()
    {
        using var document = JsonDocument.Parse("""
            {"seekable-ranges":[{"start":600,"end":660},{"start":150,"end":220},
                {"start":100,"end":200},{"start":250,"end":240},{"start":"bad","end":500},
                {"start":700,"end":1e99}]}
            """);
        var ranges = PlaybackCacheState.ParseRanges(document.RootElement, TimeSpan.FromSeconds(620));
        Assert.Equal(new[]
        {
            new BufferedTimeRange(TimeSpan.FromSeconds(100), TimeSpan.FromSeconds(220)),
            new BufferedTimeRange(TimeSpan.FromSeconds(600), TimeSpan.FromSeconds(620)),
        }, ranges);
    }

    [Fact]
    public void UnknownCacheStateDiffersFromAnObservedEmptyBuffer()
    {
        Assert.Null(PlaybackCacheState.ParseRanges(null, null));
        using var document = JsonDocument.Parse("""{"seekable-ranges":[]}""");
        Assert.Empty(PlaybackCacheState.ParseRanges(document.RootElement, null)!);
    }

    [Fact]
    public void RateUsesMonotonicByteDeltasAndStopsShowingOldSpeedWhenIdle()
    {
        var rate = new UpstreamRateWindow();
        long second = Stopwatch.Frequency;
        Assert.Null(rate.Observe(1_000_000, second));
        Assert.Equal(2_000_000d, rate.Observe(3_000_000, second * 2));
        Assert.Equal(0d, rate.Observe(3_000_000, second * 3));
    }

    [Fact]
    public void ChangedCountersAndNonmonotonicSamplesDiscardOldBaselines()
    {
        var rate = new UpstreamRateWindow();
        long second = Stopwatch.Frequency;
        rate.Observe(10_000, second);
        Assert.Null(rate.Observe(10, second * 2));
        Assert.Equal(10d, rate.Observe(20, second * 3));
        Assert.Null(rate.Observe(30, second * 3));
        Assert.Equal(10d, rate.Observe(40, second * 4));
        rate.Reset();
        Assert.Null(rate.Observe(50, second * 5));
    }
}
