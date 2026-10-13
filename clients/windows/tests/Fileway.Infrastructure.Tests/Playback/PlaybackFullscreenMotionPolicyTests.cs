using Fileway.App.MediaSurfaces;

namespace Fileway.Infrastructure.Tests.Playback;

public sealed class PlaybackFullscreenMotionPolicyTests
{
    private static readonly FullscreenPixelRect Windowed = new(320, 180, 1280, 720);
    private static readonly FullscreenPixelRect Fullscreen = new(0, 0, 1920, 1080);

    [Fact]
    public void InitialFrameIsExactAndCommittedWithoutCreatingMotion()
    {
        var motion = new PlaybackFullscreenMotionPolicy(Windowed);

        Assert.Equal(Windowed, motion.Current.Bounds);
        Assert.Equal(Windowed, motion.Current.TargetBounds);
        Assert.False(motion.Current.DesiredFullscreen);
        Assert.False(motion.Current.CommittedFullscreen);
        Assert.False(motion.Current.IsAnimating);
        Assert.Equal(default, motion.Current.Velocity);
        Assert.Equal(TimeSpan.FromMilliseconds(400), motion.ResponseDuration);
    }

    [Fact]
    public void NormalMonitorTransitionHasLiveIntermediateGeometryAndCommitsOnlyAtItsEndpoint()
    {
        var motion = new PlaybackFullscreenMotionPolicy(Windowed);
        var started = motion.Retarget(Fullscreen, true, TimeSpan.Zero);
        var middle = Advance(motion, started.Generation, 150);

        Assert.True(started.IsAnimating);
        Assert.True(middle.IsAnimating);
        Assert.True(middle.DesiredFullscreen);
        Assert.False(middle.CommittedFullscreen);
        Assert.NotEqual(Windowed, middle.Bounds);
        Assert.NotEqual(Fullscreen, middle.Bounds);
        Assert.InRange(middle.Bounds.Width, Windowed.Width + 1, Fullscreen.Width - 1);
        var settled = Advance(motion, started.Generation, 450);
        Assert.False(settled.IsAnimating);
        Assert.True(settled.CommittedFullscreen);
        Assert.Equal(Fullscreen, settled.Bounds);
        Assert.Equal(new FullscreenMotionVector(0, 0, 1920, 1080), settled.Geometry);
        Assert.Equal(default, settled.Velocity);
    }

    [Fact]
    public void MotionAcceleratesFromRestAndDeceleratesBeforeSettling()
    {
        var motion = new PlaybackFullscreenMotionPolicy(Windowed);
        var started = motion.Retarget(Fullscreen, true, TimeSpan.Zero);
        var early = Advance(motion, started.Generation, 10);
        var moving = Advance(motion, started.Generation, 60);
        var late = Advance(motion, started.Generation, 280);

        Assert.Equal(default, started.Velocity);
        Assert.True(early.Velocity.Width > 0);
        Assert.True(moving.Velocity.Width > early.Velocity.Width);
        Assert.InRange(late.Velocity.Width, 0, moving.Velocity.Width);
        Assert.True(late.IsAnimating);
        Assert.Equal(default, Advance(motion, started.Generation, 1000).Velocity);
    }

    [Fact]
    public void ExitKeepsCommittedFullscreenUntilTheWindowedEndpointIsActuallyReached()
    {
        var motion = new PlaybackFullscreenMotionPolicy(Fullscreen, committedFullscreen: true);
        var exiting = motion.Retarget(Windowed, false, TimeSpan.Zero);
        var intermediate = Advance(motion, exiting.Generation, 100);

        Assert.False(intermediate.DesiredFullscreen);
        Assert.True(intermediate.CommittedFullscreen);
        Assert.True(intermediate.IsAnimating);
        var settled = Advance(motion, exiting.Generation, 1000);
        Assert.Equal(Windowed, settled.Bounds);
        Assert.False(settled.CommittedFullscreen);
        Assert.False(settled.IsAnimating);
    }

    [Fact]
    public void ReversingMidFlightPreservesPositionAndVelocityInsteadOfRestarting()
    {
        var motion = new PlaybackFullscreenMotionPolicy(Windowed);
        var entering = motion.Retarget(Fullscreen, true, TimeSpan.Zero);
        var before = Advance(motion, entering.Generation, 120);

        var reversing = motion.Retarget(Windowed, false, Milliseconds(120));

        AssertVector(before.Geometry, reversing.Geometry);
        AssertVector(before.Velocity, reversing.Velocity);
        Assert.Equal(before.Bounds, reversing.Bounds);
        Assert.NotEqual(entering.Generation, reversing.Generation);
        Assert.False(reversing.DesiredFullscreen);
        Assert.False(reversing.CommittedFullscreen);
        Assert.False(motion.TryAdvance(entering.Generation, Milliseconds(500), out var retired));
        Assert.Equal(reversing, retired);
        var settled = Advance(motion, reversing.Generation, 1200);
        Assert.Equal(Windowed, settled.Bounds);
        Assert.False(settled.IsAnimating);
        Assert.False(settled.CommittedFullscreen);
    }

    [Fact]
    public void LatestMonitorTargetWinsWithoutLosingInheritedMotion()
    {
        var motion = new PlaybackFullscreenMotionPolicy(Windowed);
        var first = motion.Retarget(Fullscreen, true, TimeSpan.Zero);
        var before = Advance(motion, first.Generation, 100);
        var otherMonitor = new FullscreenPixelRect(-2560, -400, 2560, 1440);

        var redirected = motion.Retarget(otherMonitor, true, Milliseconds(100));

        AssertVector(before.Geometry, redirected.Geometry);
        AssertVector(before.Velocity, redirected.Velocity);
        Assert.Equal(otherMonitor, redirected.TargetBounds);
        Assert.False(motion.TryAdvance(first.Generation, TimeSpan.MaxValue, out _));
        var settled = Advance(motion, redirected.Generation, 1600);
        Assert.Equal(otherMonitor, settled.Bounds);
        Assert.True(settled.CommittedFullscreen);
        AssertNativeSafe(settled);
    }

    [Fact]
    public void RepeatedTargetDoesNotRestartItsClockOrInvalidateItsGeneration()
    {
        var reference = new PlaybackFullscreenMotionPolicy(Windowed);
        var repeated = new PlaybackFullscreenMotionPolicy(Windowed);
        var baseline = reference.Retarget(Fullscreen, true, TimeSpan.Zero);
        var current = repeated.Retarget(Fullscreen, true, TimeSpan.Zero);

        foreach (int time in new[] { 40, 80, 120, 180, 250, 320, 450 })
        {
            var duplicate = repeated.Retarget(Fullscreen, true, Milliseconds(time));
            var expected = Advance(reference, baseline.Generation, time);
            Assert.Equal(current.Generation, duplicate.Generation);
            Assert.Equal(expected.Bounds, duplicate.Bounds);
            AssertVector(expected.Geometry, duplicate.Geometry);
            AssertVector(expected.Velocity, duplicate.Velocity);
            Assert.Equal(expected.IsAnimating, duplicate.IsAnimating);
        }
    }

    [Fact]
    public void OutOfOrderClockValuesCannotRewindMotionOrChangeItsVelocity()
    {
        var motion = new PlaybackFullscreenMotionPolicy(Windowed);
        var started = motion.Retarget(Fullscreen, true, TimeSpan.FromSeconds(10));
        var current = Advance(motion, started.Generation, 10120);

        Assert.True(motion.TryAdvance(started.Generation, TimeSpan.MinValue, out var old));
        Assert.Equal(current, old);
        Assert.True(motion.TryAdvance(started.Generation, TimeSpan.FromSeconds(10), out old));
        Assert.Equal(current, old);
        var reversed = motion.Retarget(Windowed, false, TimeSpan.Zero);
        AssertVector(current.Geometry, reversed.Geometry);
        AssertVector(current.Velocity, reversed.Velocity);
        Assert.Equal(Windowed, Advance(motion, reversed.Generation, 12000).Bounds);
    }

    [Fact]
    public void ExtremelyLongFrameGapSettlesExactlyWithoutOverflowOrCatchUpFrames()
    {
        var motion = new PlaybackFullscreenMotionPolicy(Windowed);
        var started = motion.Retarget(Fullscreen, true, TimeSpan.Zero);

        Assert.True(motion.TryAdvance(started.Generation, TimeSpan.MaxValue, out var settled));

        Assert.Equal(Fullscreen, settled.Bounds);
        Assert.False(settled.IsAnimating);
        Assert.Equal(default, settled.Velocity);
        AssertNativeSafe(settled);
    }

    [Fact]
    public void ReducedMotionImmediatelyCommitsLatestTargetAndRejectsQueuedFrames()
    {
        var motion = new PlaybackFullscreenMotionPolicy(Windowed);
        var started = motion.Retarget(Fullscreen, true, TimeSpan.Zero);
        _ = Advance(motion, started.Generation, 100);

        var reduced = motion.SetAnimationsEnabled(false, Milliseconds(100));

        Assert.Equal(Fullscreen, reduced.Bounds);
        Assert.True(reduced.CommittedFullscreen);
        Assert.False(reduced.IsAnimating);
        Assert.Equal(default, reduced.Velocity);
        Assert.False(motion.TryAdvance(started.Generation, TimeSpan.MaxValue, out var late));
        Assert.Equal(reduced, late);
        var exited = motion.Retarget(Windowed, false, Milliseconds(120));
        Assert.Equal(Windowed, exited.Bounds);
        Assert.False(exited.IsAnimating);
        Assert.False(exited.CommittedFullscreen);
        _ = motion.SetAnimationsEnabled(true, Milliseconds(140));
        Assert.True(motion.Retarget(Fullscreen, true, Milliseconds(150)).IsAnimating);
    }

    [Fact]
    public void CancelCommitsLatestIntentAndNoRetiredFrameCanRestoreAnOlderTarget()
    {
        var motion = new PlaybackFullscreenMotionPolicy(Windowed);
        var entering = motion.Retarget(Fullscreen, true, TimeSpan.Zero);
        _ = Advance(motion, entering.Generation, 80);
        var exiting = motion.Retarget(Windowed, false, Milliseconds(80));

        var canceled = motion.Cancel(Milliseconds(100));

        Assert.Equal(Windowed, canceled.Bounds);
        Assert.False(canceled.DesiredFullscreen);
        Assert.False(canceled.CommittedFullscreen);
        Assert.False(canceled.IsAnimating);
        Assert.False(motion.TryAdvance(entering.Generation, TimeSpan.MaxValue, out _));
        Assert.False(motion.TryAdvance(exiting.Generation, TimeSpan.MaxValue, out _));
        Assert.Equal(canceled, motion.Current);
    }

    [Fact]
    public void ClosingFreezesLastSampleAndCannotMoveOrCommitAClosingWindowLater()
    {
        var motion = new PlaybackFullscreenMotionPolicy(Windowed);
        var entering = motion.Retarget(Fullscreen, true, TimeSpan.Zero);
        var lastVisible = Advance(motion, entering.Generation, 100);

        var closed = motion.Close();

        Assert.Equal(lastVisible.Bounds, closed.Bounds);
        AssertVector(lastVisible.Geometry, closed.Geometry);
        Assert.False(closed.CommittedFullscreen);
        Assert.True(closed.IsClosed);
        Assert.False(closed.IsAnimating);
        Assert.Equal(default, closed.Velocity);
        Assert.False(motion.TryAdvance(entering.Generation, TimeSpan.MaxValue, out var late));
        Assert.Equal(closed, late);
        Assert.Equal(closed, motion.Retarget(Windowed, false, TimeSpan.MaxValue));
        Assert.Equal(closed, motion.SetAnimationsEnabled(false, TimeSpan.MaxValue));
        Assert.Equal(closed, motion.Cancel(TimeSpan.MaxValue));
        Assert.Equal(closed, motion.Close());
    }

    [Theory]
    [InlineData(-3840, -2160, 3840, 2160)]
    [InlineData(int.MinValue, int.MinValue, int.MaxValue, int.MaxValue)]
    [InlineData(int.MaxValue - 1, int.MaxValue - 1, 1, 1)]
    [InlineData(-1, -1, 1, 1)]
    public void NegativeAndExtremeNativeRectanglesRemainFiniteAndRepresentable(int x, int y, int width, int height)
    {
        var initial = new FullscreenPixelRect(x, y, width, height);
        var target = initial == Windowed ? Fullscreen : Windowed;
        var motion = new PlaybackFullscreenMotionPolicy(initial);
        var started = motion.Retarget(target, true, TimeSpan.Zero);

        for (int time = 0; time < 2000; time += 17) AssertNativeSafe(Advance(motion, started.Generation, time));

        Assert.Equal(target, Advance(motion, started.Generation, 3000).Bounds);
    }

    [Fact]
    public void FasterAndSlowerResponsesAreTunableWithoutChangingTheirExactEndpoint()
    {
        var fast = new PlaybackFullscreenMotionPolicy(Windowed, responseDuration: Milliseconds(300));
        var slow = new PlaybackFullscreenMotionPolicy(Windowed, responseDuration: Milliseconds(450));
        var first = fast.Retarget(Fullscreen, true, TimeSpan.Zero);
        var second = slow.Retarget(Fullscreen, true, TimeSpan.Zero);

        Assert.True(Advance(fast, first.Generation, 150).Geometry.Width > Advance(slow, second.Generation, 150).Geometry.Width);
        Assert.Equal(Fullscreen, Advance(fast, first.Generation, 1000).Bounds);
        Assert.Equal(Fullscreen, Advance(slow, second.Generation, 1000).Bounds);
    }

    [Fact]
    public void SlowRedirectionInheritsFastShrinkingVelocityWithoutCreatingZeroSizeOrNonfiniteGeometry()
    {
        var huge = new FullscreenPixelRect(int.MinValue, int.MinValue, int.MaxValue, int.MaxValue);
        var tiny = new FullscreenPixelRect(int.MaxValue - 1, int.MaxValue - 1, 1, 1);
        var motion = new PlaybackFullscreenMotionPolicy(huge, responseDuration: Milliseconds(100));
        for (int index = 0; index < 200; ++index)
        {
            var before = motion.Current;
            var redirected = motion.Retarget(index % 2 == 0 ? tiny : huge, index % 2 == 0,
                Milliseconds(index * 3), index % 2 == 0 ? Milliseconds(100) : TimeSpan.FromSeconds(2));
            AssertNativeSafe(redirected);
            Assert.True(redirected.Generation >= before.Generation);
            AssertNativeSafe(Advance(motion, redirected.Generation, index * 3 + 2));
        }
        Assert.True(motion.TryAdvance(motion.Current.Generation, TimeSpan.MaxValue, out var settled));
        Assert.Equal(huge, settled.Bounds);
        Assert.False(settled.IsAnimating);
    }

    [Fact]
    public void SameOnePixelGeometryCanCommitLogicalFullscreenWithoutArtificialMovement()
    {
        var tiny = new FullscreenPixelRect(-1, -1, 1, 1);
        var motion = new PlaybackFullscreenMotionPolicy(tiny);

        var changed = motion.Retarget(tiny, true, TimeSpan.Zero);

        Assert.Equal(tiny, changed.Bounds);
        Assert.True(changed.DesiredFullscreen);
        Assert.True(changed.CommittedFullscreen);
        Assert.False(changed.IsAnimating);
        Assert.Equal(default, changed.Velocity);
    }

    [Theory]
    [InlineData(0, 0, 0, 1)]
    [InlineData(0, 0, 1, -1)]
    [InlineData(int.MaxValue, 0, 1, 1)]
    [InlineData(0, int.MaxValue, 1, 1)]
    public void InvalidNativeRectanglesAreRejectedBeforeChangingIntent(int x, int y, int width, int height)
    {
        var motion = new PlaybackFullscreenMotionPolicy(Windowed);
        var original = motion.Current;

        Assert.Throws<ArgumentOutOfRangeException>(() => motion.Retarget(new(x, y, width, height), true, TimeSpan.Zero));

        Assert.Equal(original, motion.Current);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(99)]
    [InlineData(2001)]
    public void InvalidResponseDurationIsRejectedWithoutPartiallyApplyingANewTarget(int milliseconds)
    {
        var motion = new PlaybackFullscreenMotionPolicy(Windowed);
        var original = motion.Current;

        Assert.Throws<ArgumentOutOfRangeException>(() => motion.Retarget(Fullscreen, true, TimeSpan.Zero, Milliseconds(milliseconds)));

        Assert.Equal(original, motion.Current);
    }

    private static TimeSpan Milliseconds(int value) => TimeSpan.FromMilliseconds(value);
    private static FullscreenMotionFrame Advance(PlaybackFullscreenMotionPolicy motion, long generation, int milliseconds)
    {
        Assert.True(motion.TryAdvance(generation, Milliseconds(milliseconds), out var frame));
        return frame;
    }
    private static void AssertVector(FullscreenMotionVector expected, FullscreenMotionVector actual)
    {
        Assert.InRange(Math.Abs(expected.X - actual.X), 0, 0.00001);
        Assert.InRange(Math.Abs(expected.Y - actual.Y), 0, 0.00001);
        Assert.InRange(Math.Abs(expected.Width - actual.Width), 0, 0.00001);
        Assert.InRange(Math.Abs(expected.Height - actual.Height), 0, 0.00001);
    }
    private static void AssertNativeSafe(FullscreenMotionFrame frame)
    {
        Assert.True(frame.Bounds.Width >= 1 && frame.Bounds.Height >= 1);
        Assert.True((long)frame.Bounds.X + frame.Bounds.Width <= int.MaxValue);
        Assert.True((long)frame.Bounds.Y + frame.Bounds.Height <= int.MaxValue);
        foreach (double value in new[] { frame.Geometry.X, frame.Geometry.Y, frame.Geometry.Width, frame.Geometry.Height,
            frame.Velocity.X, frame.Velocity.Y, frame.Velocity.Width, frame.Velocity.Height }) Assert.True(double.IsFinite(value));
        Assert.True(frame.Geometry.Width > 0 && frame.Geometry.Height > 0);
    }
}
