using Fileway.App.MediaSurfaces;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Tests.Playback;

public sealed class LocalSubtitlePolicyTests
{
    [Theory]
    [InlineData(16_777_217L)]
    [InlineData(499_999_999L)]
    [InlineData(500_000_000L)]
    public void LocalSubtitlesUpToDecimal500MbAreAllowed(long size) =>
        Assert.Null(LocalSubtitlePolicy.GetRejection(@"C:\media\captions.srt", size));

    [Fact]
    public void OneByteAbove500MbIsRejectedWithAnAccurateLimit()
    {
        var rejection = LocalSubtitlePolicy.GetRejection(@"C:\media\captions.srt", 500_000_001);
        Assert.Equal("所选字幕超过 500 MB（500,000,000 字节）上限。", rejection);
    }

    [Theory]
    [InlineData(".srt")]
    [InlineData(".ass")]
    [InlineData(".ssa")]
    [InlineData(".vtt")]
    [InlineData(".sup")]
    [InlineData(".ttml")]
    [InlineData(".dfxp")]
    [InlineData(".smi")]
    [InlineData(".sami")]
    [InlineData(".SRT")]
    public void ExistingSubtitleFormatsRemainSupported(string extension) =>
        Assert.Null(LocalSubtitlePolicy.GetRejection(@"C:\media\captions" + extension, 500_000_000));

    [Theory]
    [InlineData(@"C:\media\captions.txt")]
    [InlineData(@"media\captions.srt")]
    [InlineData("https://example.test/captions.srt")]
    public void UnsupportedOrRelativePathsAreRejectedBeforeReadingMetadata(string path) =>
        Assert.Equal("请选择可直接读取的本地字幕文件。", LocalSubtitlePolicy.GetRejection(path, 0));

    [Theory]
    [InlineData(@"\\server\share\captions.srt")]
    [InlineData(@"\\?\C:\media\captions.srt")]
    public void PickerAdmissionPreservesExistingFullyQualifiedPathRules(string path) =>
        Assert.Null(LocalSubtitlePolicy.GetRejection(path, 500_000_000));

    [Fact]
    public void CurrentRequestRemainsValidWhenPlaybackProgressChanges()
    {
        var origin = Snapshot();
        var current = origin with { Position = TimeSpan.FromSeconds(12), Status = PlaybackStatus.Paused };
        Assert.True(LocalSubtitlePolicy.IsCurrentRequest(7, 7, origin, current, false));
    }

    [Fact]
    public void LatePickerCallbackCannotAttachAfterCancellation()
    {
        var origin = Snapshot();
        using var cancellation = new CancellationTokenSource();
        cancellation.Cancel();
        Assert.False(LocalSubtitlePolicy.IsCurrentRequest(7, 7, origin, origin, cancellation.IsCancellationRequested));
    }

    [Fact]
    public void LatePickerCallbackCannotAttachToAReplacedRequestOrPlayback()
    {
        var origin = Snapshot();
        Assert.False(LocalSubtitlePolicy.IsCurrentRequest(7, 8, origin, origin, false));
        Assert.False(LocalSubtitlePolicy.IsCurrentRequest(7, 7, origin, origin with { Generation = 4 }, false));
        Assert.False(LocalSubtitlePolicy.IsCurrentRequest(7, 7, origin, origin with { SessionId = Guid.NewGuid() }, false));
    }

    [Theory]
    [InlineData(PlaybackStatus.Closing)]
    [InlineData(PlaybackStatus.Stopped)]
    public void LatePickerCallbackCannotAttachAfterPlaybackCloses(PlaybackStatus status)
    {
        var origin = Snapshot();
        Assert.False(LocalSubtitlePolicy.IsCurrentRequest(7, 7, origin, origin with { Status = status }, false));
    }

    private static PlaybackSnapshot Snapshot() => new(Guid.NewGuid(), 3, "fixture.mp4", PlaybackStatus.Playing,
        TimeSpan.Zero, TimeSpan.Zero, TimeSpan.FromMinutes(1), true, [],
        new SubtitleCapabilities(true, true, true, true, true), new PlaybackDiagnostics());
}
