using Fileway.App.MediaSurfaces;

namespace Fileway.Infrastructure.Tests.Playback;

public sealed class PlaybackVideoGesturePolicyTests
{
    [Fact]
    public void SingleClickWaitsForSystemIntervalThenTogglesOnlyOnce()
    {
        var policy = Create();
        Assert.Equal(PlaybackVideoGesture.None, policy.PointerPressed(Ms(0), 100, 100));
        Assert.Equal(PlaybackVideoGesture.None, policy.PointerReleased(Ms(60), 100, 100));
        Assert.Equal(Ms(440), policy.PendingDelay(Ms(60)));
        Assert.Equal(PlaybackVideoGesture.None, policy.Elapse(Ms(499)));
        Assert.Equal(PlaybackVideoGesture.TogglePause, policy.Elapse(Ms(500)));
        Assert.Equal(PlaybackVideoGesture.None, policy.Elapse(Ms(1000)));
        Assert.Null(policy.PendingDelay(Ms(1000)));
    }

    [Fact]
    public void DoubleClickCancelsBothPauseActionsBeforeSecondRelease()
    {
        var policy = Create();
        policy.PointerPressed(Ms(0), 100, 100);
        policy.PointerReleased(Ms(60), 100, 100);
        Assert.Equal(PlaybackVideoGesture.ToggleFullscreen, policy.PointerPressed(Ms(200), 101, 99));
        Assert.Null(policy.PendingDelay(Ms(200)));
        Assert.Equal(PlaybackVideoGesture.None, policy.PointerReleased(Ms(250), 101, 99));
        Assert.Equal(PlaybackVideoGesture.None, policy.Elapse(Ms(1000)));
    }

    [Fact]
    public void DoubleClickWindowStartsAtFirstPressRatherThanFirstRelease()
    {
        var policy = Create();
        policy.PointerPressed(Ms(0), 100, 100);
        policy.PointerReleased(Ms(400), 100, 100);
        Assert.Equal(Ms(100), policy.PendingDelay(Ms(400)));
        Assert.Equal(PlaybackVideoGesture.ToggleFullscreen, policy.PointerPressed(Ms(450), 100, 100));
        // A long second hold cannot turn its release into a late single click.
        Assert.Equal(PlaybackVideoGesture.None, policy.PointerReleased(Ms(1200), 100, 100));
        Assert.Equal(PlaybackVideoGesture.None, policy.Elapse(Ms(1500)));
    }

    [Fact]
    public void DistantSecondClickCommitsFirstAndSchedulesAnIndependentSingleClick()
    {
        var policy = Create();
        policy.PointerPressed(Ms(0), 100, 100);
        policy.PointerReleased(Ms(60), 100, 100);
        Assert.Equal(PlaybackVideoGesture.TogglePause, policy.PointerPressed(Ms(200), 103, 100));
        Assert.Equal(PlaybackVideoGesture.None, policy.PointerReleased(Ms(250), 103, 100));
        Assert.Equal(PlaybackVideoGesture.None, policy.Elapse(Ms(500)));
        Assert.Equal(PlaybackVideoGesture.TogglePause, policy.Elapse(Ms(700)));
    }

    [Fact]
    public void DelayedUiTimerDoesNotMistakeAnExpiredSecondPressForDoubleClick()
    {
        var policy = Create();
        policy.PointerPressed(Ms(0), 100, 100);
        policy.PointerReleased(Ms(60), 100, 100);
        Assert.Equal(PlaybackVideoGesture.TogglePause, policy.PointerPressed(Ms(501), 100, 100));
        Assert.Equal(PlaybackVideoGesture.None, policy.PointerReleased(Ms(550), 100, 100));
        Assert.Equal(PlaybackVideoGesture.None, policy.Elapse(Ms(1000)));
        Assert.Equal(PlaybackVideoGesture.TogglePause, policy.Elapse(Ms(1001)));
    }

    [Fact]
    public void ReleaseOutsideSystemDragRectangleDoesNotActivatePlayback()
    {
        var policy = Create();
        policy.PointerPressed(Ms(0), 100, 100);
        Assert.Equal(PlaybackVideoGesture.None, policy.PointerReleased(Ms(60), 100, 103));
        Assert.Null(policy.PendingDelay(Ms(60)));
        Assert.Equal(PlaybackVideoGesture.None, policy.Elapse(Ms(1000)));
    }

    [Fact]
    public void LongSinglePressDoesNotAddAnotherDoubleClickDelayAfterRelease()
    {
        var policy = Create();
        policy.PointerPressed(Ms(0), 100, 100);
        Assert.Equal(PlaybackVideoGesture.TogglePause, policy.PointerReleased(Ms(600), 100, 100));
        Assert.Null(policy.PendingDelay(Ms(600)));
        Assert.Equal(PlaybackVideoGesture.None, policy.Elapse(Ms(1000)));
    }

    [Fact]
    public void CancelDropsPendingClickAndUnmatchedRelease()
    {
        var policy = Create();
        policy.PointerPressed(Ms(0), 100, 100);
        policy.PointerReleased(Ms(60), 100, 100);
        policy.Cancel();
        Assert.Equal(PlaybackVideoGesture.None, policy.Elapse(Ms(1000)));
        Assert.Equal(PlaybackVideoGesture.None, policy.PointerReleased(Ms(1100), 100, 100));
        policy.PointerPressed(Ms(1200), 100, 100);
        policy.Cancel();
        Assert.Equal(PlaybackVideoGesture.None, policy.PointerReleased(Ms(1250), 100, 100));
        Assert.Null(policy.PendingDelay(Ms(2000)));
    }

    [Fact]
    public void ThirdClickAfterDoubleClickIsAnIndependentSingleClick()
    {
        var policy = Create();
        policy.PointerPressed(Ms(0), 100, 100);
        policy.PointerReleased(Ms(60), 100, 100);
        Assert.Equal(PlaybackVideoGesture.ToggleFullscreen, policy.PointerPressed(Ms(200), 100, 100));
        policy.PointerReleased(Ms(250), 100, 100);
        Assert.Equal(PlaybackVideoGesture.None, policy.PointerPressed(Ms(300), 100, 100));
        Assert.Equal(PlaybackVideoGesture.None, policy.PointerReleased(Ms(350), 100, 100));
        Assert.Equal(PlaybackVideoGesture.TogglePause, policy.Elapse(Ms(800)));
    }

    [Fact]
    public void PhysicalPixelRectanglesSupportNegativeScreenCoordinatesWithoutOverflow()
    {
        var policy = Create();
        policy.PointerPressed(Ms(0), -100, -100);
        policy.PointerReleased(Ms(60), -100, -100);
        Assert.Equal(PlaybackVideoGesture.ToggleFullscreen, policy.PointerPressed(Ms(200), -102, -98));
        policy.PointerReleased(Ms(250), -102, -98);
        policy.PointerPressed(Ms(300), int.MinValue, int.MinValue);
        Assert.Equal(PlaybackVideoGesture.None, policy.PointerReleased(Ms(350), int.MaxValue, int.MaxValue));
        Assert.Null(policy.PendingDelay(Ms(350)));
    }

    private static PlaybackVideoGesturePolicy Create() => new(Ms(500), 4, 4, 4, 4);
    private static TimeSpan Ms(int value) => TimeSpan.FromMilliseconds(value);
}
