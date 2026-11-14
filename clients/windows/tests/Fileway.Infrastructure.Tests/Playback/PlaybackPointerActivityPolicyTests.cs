using Fileway.App.MediaSurfaces;

namespace Fileway.Infrastructure.Tests.Playback;

public sealed class PlaybackPointerActivityPolicyTests
{
    [Fact]
    public void UnseededRoutingMessageInitializesWithoutClaimingUserMovement()
    {
        var policy = new PlaybackPointerActivityPolicy();
        Assert.False(policy.ObserveMouseMove(400, 300));
        Assert.False(policy.ObserveMouseMove(400, 300));
        Assert.True(policy.ObserveMouseMove(401, 300));
    }

    [Fact]
    public void NativeAndXamlSameScreenPointShareOneActivityObservation()
    {
        var policy = new PlaybackPointerActivityPolicy();
        policy.SynchronizePosition(400, 300);
        Assert.True(policy.ObserveMouseMove(401, 300)); // Native video input.
        Assert.False(policy.ObserveMouseMove(401, 300)); // Same point retargeted to XAML.
        Assert.False(policy.ObserveMouseMove(401, 300)); // Same point retargeted to nonclient.
        Assert.True(policy.ObserveMouseMove(401, 301));
        Assert.False(policy.ObserveMouseMove(401, 301));
    }

    [Theory]
    [InlineData(0, 0, 1, 0)]
    [InlineData(-1920, -1080, -1921, -1080)]
    [InlineData(-1, -1, -1, 0)]
    [InlineData(int.MinValue, int.MaxValue, int.MaxValue, int.MinValue)]
    public void PhysicalCoordinatesSupportSinglePixelChangesAndNegativeMonitorPositions(int x, int y, int nextX, int nextY)
    {
        var policy = new PlaybackPointerActivityPolicy();
        policy.InitializePosition(x, y);
        Assert.False(policy.ObserveMouseMove(x, y));
        Assert.True(policy.ObserveMouseMove(nextX, nextY));
        Assert.False(policy.ObserveMouseMove(nextX, nextY));
    }

    [Fact]
    public void IdleHideAndRepeatedSyntheticMovesRemainHiddenUntilActualMovement()
    {
        var hud = new IdleHud();
        hud.ExplicitInput(Ms(0), 400, 300);
        hud.Advance(Ms(4000));
        Assert.False(hud.Visible);
        for (int cycle = 1; cycle <= 20; cycle++)
        {
            var now = Ms(4000 * cycle + 10);
            hud.OwnedWindowSynchronization(400, 300);
            Assert.False(hud.MouseMoved(now, 400, 300));
            hud.Advance(now);
            Assert.False(hud.Visible);
            Assert.Equal(Ms(0), hud.LastActivity);
        }

        Assert.True(hud.MouseMoved(Ms(80_020), 401, 300));
        Assert.True(hud.Visible);
        Assert.Equal(Ms(80_020), hud.LastActivity);
        Assert.False(hud.MouseMoved(Ms(80_030), 401, 300));
        Assert.Equal(Ms(80_020), hud.LastActivity);
        hud.Advance(Ms(84_019));
        Assert.True(hud.Visible);
        hud.Advance(Ms(84_020));
        Assert.False(hud.Visible); // Real movement does not disable normal automatic hiding.
    }

    [Fact]
    public void OwnedActivationSynchronizationCannotConsumeAnUnprocessedRealMove()
    {
        var hud = new IdleHud();
        hud.ExplicitInput(Ms(0), 400, 300);
        hud.Advance(Ms(4000));
        // Cursor movement is already observable, but its message is still queued when focus returns.
        hud.OwnedWindowSynchronization(401, 300);
        Assert.False(hud.Visible);
        Assert.True(hud.MouseMoved(Ms(4010), 401, 300));
        Assert.True(hud.Visible);
    }

    [Fact]
    public void ExplicitInputAtAnUnchangedPositionStillWakesAndRestartsIdle()
    {
        var hud = new IdleHud();
        hud.ExplicitInput(Ms(0), 400, 300);
        hud.Advance(Ms(4000));
        hud.ExplicitInput(Ms(4050), 400, 300); // Keys, buttons and contact input do not use mouse deduplication.
        Assert.True(hud.Visible);
        Assert.Equal(Ms(4050), hud.LastActivity);
        Assert.False(hud.MouseMoved(Ms(4060), 400, 300));
        Assert.Equal(Ms(4050), hud.LastActivity);
        hud.Advance(Ms(8050));
        Assert.False(hud.Visible);
    }

    [Fact]
    public void GenuineActivationOrExplicitInputSeedsItsCurrentCursorWithoutASecondIdleReset()
    {
        var hud = new IdleHud();
        hud.ExplicitInput(Ms(0), 400, 300);
        hud.Advance(Ms(4000));
        hud.ExplicitInput(Ms(10_000), -1200, 500);
        Assert.False(hud.MouseMoved(Ms(10_010), -1200, 500));
        Assert.Equal(Ms(10_000), hud.LastActivity);
        hud.Advance(Ms(14_000));
        hud.OwnedWindowSynchronization(-1200, 500);
        Assert.False(hud.MouseMoved(Ms(14_010), -1200, 500));
        Assert.False(hud.Visible);
    }

    private static TimeSpan Ms(int milliseconds) => TimeSpan.FromMilliseconds(milliseconds);

    // A deterministic idle consumer of the production activity decision; no window/input or clock sleeps.
    private sealed class IdleHud
    {
        private readonly PlaybackPointerActivityPolicy _policy = new();
        internal bool Visible { get; private set; }
        internal TimeSpan LastActivity { get; private set; }
        internal void Advance(TimeSpan now) { if (now - LastActivity >= TimeSpan.FromSeconds(4)) Visible = false; }
        internal void OwnedWindowSynchronization(int x, int y) => _policy.InitializePosition(x, y);
        internal void ExplicitInput(TimeSpan now, int x, int y)
        { _policy.SynchronizePosition(x, y); LastActivity = now; Visible = true; }
        internal bool MouseMoved(TimeSpan now, int x, int y)
        {
            if (!_policy.ObserveMouseMove(x, y)) return false;
            ExplicitInput(now, x, y);
            return true;
        }
    }
}
