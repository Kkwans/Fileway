using Fileway.App.MediaSurfaces;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Tests.Playback;

public sealed class PlaybackPausePolicyTests
{
    [Theory]
    [InlineData(PlaybackStatus.Buffering, true, false, "Play", "开始播放")]
    [InlineData(PlaybackStatus.Buffering, false, true, "Pause", "暂停播放")]
    [InlineData(PlaybackStatus.Seeking, true, false, "Play", "开始播放")]
    [InlineData(PlaybackStatus.Seeking, false, true, "Pause", "暂停播放")]
    [InlineData(PlaybackStatus.Playing, true, false, "Play", "开始播放")]
    [InlineData(PlaybackStatus.Paused, false, true, "Pause", "暂停播放")]
    public void ConfirmedPauseControlsTheActionIndependentlyOfTransientStatus(PlaybackStatus status,
        bool paused, bool targetPaused, string icon, string name)
    {
        var snapshot = Snapshot(status) with { IsPaused = paused };

        var action = PlaybackPausePolicy.GetAction(snapshot);

        Assert.False(action.Restart);
        Assert.Equal(targetPaused, action.TargetPaused);
        Assert.Equal(icon, action.IconName);
        Assert.Equal(name, action.AutomationName);
        Assert.Equal(paused, snapshot.IsPaused);
    }

    [Theory]
    [InlineData(PlaybackStatus.Preparing, true)]
    [InlineData(PlaybackStatus.Buffering, true)]
    [InlineData(PlaybackStatus.Playing, true)]
    [InlineData(PlaybackStatus.Paused, false)]
    [InlineData(PlaybackStatus.Seeking, true)]
    [InlineData(PlaybackStatus.Stopped, true)]
    [InlineData(PlaybackStatus.Failed, true)]
    [InlineData(PlaybackStatus.Closing, true)]
    public void UnknownPausePreservesTheExistingStatusFallback(PlaybackStatus status, bool targetPaused)
    {
        var snapshot = Snapshot(status);

        var action = PlaybackPausePolicy.GetAction(snapshot);

        Assert.Null(snapshot.IsPaused);
        Assert.False(action.Restart);
        Assert.Equal(targetPaused, action.TargetPaused);
        Assert.Equal(targetPaused ? "Pause" : "Play", action.IconName);
        Assert.Equal(targetPaused ? "暂停播放" : "开始播放", action.AutomationName);
    }

    [Theory]
    [InlineData(null)]
    [InlineData(true)]
    [InlineData(false)]
    public void EndedAlwaysRestartsAndExplicitlyResumes(bool? paused)
    {
        var action = PlaybackPausePolicy.GetAction(Snapshot(PlaybackStatus.Ended) with { IsPaused = paused });

        Assert.True(action.Restart);
        Assert.False(action.TargetPaused);
        Assert.Equal("Play", action.IconName);
        Assert.Equal("重新播放", action.AutomationName);
    }

    [Fact]
    public void DataArrivalDoesNotChangeTheConfirmedManualPauseOrItsResumeAction()
    {
        var waiting = Snapshot(PlaybackStatus.Buffering) with { IsPaused = true };
        var ready = waiting with
        {
            Status = PlaybackStatus.Paused,
            BufferedUntil = TimeSpan.FromSeconds(45),
            BufferedRanges = [new(TimeSpan.Zero, TimeSpan.FromSeconds(45))],
        };

        Assert.Equal(PlaybackPausePolicy.GetAction(waiting), PlaybackPausePolicy.GetAction(ready));
        Assert.True(ready.IsPaused);
        Assert.False(PlaybackPausePolicy.GetAction(ready).TargetPaused);
    }

    [Fact]
    public void RepeatedEvaluationWaitsForEngineConfirmationRatherThanOptimisticallyToggling()
    {
        var waiting = Snapshot(PlaybackStatus.Buffering) with { IsPaused = false };

        Assert.True(PlaybackPausePolicy.GetAction(waiting).TargetPaused);
        Assert.True(PlaybackPausePolicy.GetAction(waiting).TargetPaused);
        var confirmed = waiting with { IsPaused = true };
        Assert.False(PlaybackPausePolicy.GetAction(confirmed).TargetPaused);
        Assert.Equal("开始播放", PlaybackPausePolicy.GetAction(confirmed).AutomationName);
    }

    private static PlaybackSnapshot Snapshot(PlaybackStatus status) => new(Guid.NewGuid(), 3, "fixture.mp4", status,
        TimeSpan.FromSeconds(12), TimeSpan.FromSeconds(12), TimeSpan.FromMinutes(1), true, [],
        new SubtitleCapabilities(true, true, true, true, true), new PlaybackDiagnostics());
}
