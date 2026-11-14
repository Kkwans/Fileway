using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Playback;

namespace Fileway.Infrastructure.Tests.Services;

public sealed class RecentOpenRecordingTests
{
    private static readonly RemoteResourceRef Resource = new(
        new AccountKey(Guid.NewGuid(), Guid.NewGuid(), "user"), "/%D6%D0+%25.jpg", "image", "/image");
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Fact]
    public async Task DuplicateSuccessPreservesExactResourceAndMakesOneAttempt()
    {
        var service = new Service();
        var recording = new RecentOpenRecording(service, Resource, 3);
        var first = recording.ConfirmOpenedAsync(3, Token);
        Assert.Same(first, recording.ConfirmOpenedAsync(3, Token));
        await first;
        Assert.Equal(1, service.Calls);
        Assert.Same(Resource, service.Resource);
    }

    [Fact]
    public async Task WrongGenerationDoesNotConsumeCurrentSuccess()
    {
        var service = new Service();
        var recording = new RecentOpenRecording(service, Resource, 3);
        await recording.ConfirmOpenedAsync(2, Token);
        Assert.Equal(0, service.Calls);
        await recording.ConfirmOpenedAsync(3, Token);
        Assert.Equal(1, service.Calls);
    }

    [Fact]
    public async Task CanceledBeforeSchedulingDoesNotSubmitOrReplay()
    {
        var service = new Service();
        using var cancellation = new CancellationTokenSource();
        cancellation.Cancel();
        var recording = new RecentOpenRecording(service, Resource, 3);
        await recording.ConfirmOpenedAsync(3, cancellation.Token);
        await recording.ConfirmOpenedAsync(3, Token);
        Assert.Equal(0, service.Calls);
    }

    [Theory]
    [InlineData(RecentAccessRecordOutcome.Confirmed)]
    [InlineData(RecentAccessRecordOutcome.Rejected)]
    [InlineData(RecentAccessRecordOutcome.OutcomeUnknown)]
    [InlineData(RecentAccessRecordOutcome.NotSubmitted)]
    public async Task EveryOutcomeConsumesAttempt(RecentAccessRecordOutcome outcome)
    {
        var service = new Service { Handler = _ => Task.FromResult(new RecentAccessRecordResult(outcome)) };
        var recording = new RecentOpenRecording(service, Resource, 3);
        await recording.ConfirmOpenedAsync(3, Token);
        await recording.ConfirmOpenedAsync(3, Token);
        Assert.Equal(1, service.Calls);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ProviderFailureNeverEscapesOrRetries(bool asynchronous)
    {
        var service = new Service { Handler = _ => asynchronous
            ? Task.FromException<RecentAccessRecordResult>(new InvalidOperationException("private source detail"))
            : throw new InvalidOperationException("private source detail") };
        var recording = new RecentOpenRecording(service, Resource, 3);
        await recording.ConfirmOpenedAsync(3, Token);
        await recording.ConfirmOpenedAsync(3, Token);
        Assert.Equal(1, service.Calls);
    }

    [Fact]
    public async Task BlockingProviderIsOffCallerThreadAndCancellationBoundsItsWait()
    {
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var reply = new TaskCompletionSource<RecentAccessRecordResult>(TaskCreationOptions.RunContinuationsAsynchronously);
        var service = new Service { Handler = _ => { entered.TrySetResult(); return reply.Task; } };
        using var cancellation = new CancellationTokenSource();
        var recording = new RecentOpenRecording(service, Resource, 3);
        var completion = recording.ConfirmOpenedAsync(3, cancellation.Token);
        await entered.Task.WaitAsync(Token);
        Assert.False(completion.IsCompleted);
        await cancellation.CancelAsync();
        await completion.WaitAsync(TimeSpan.FromSeconds(2), Token);
        reply.TrySetException(new InvalidOperationException("late private fault"));
        await recording.ConfirmOpenedAsync(3, Token);
        Assert.Equal(1, service.Calls);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task PlaybackNeedsBothAcceptanceAndConfirmedReadiness(bool readyFirst)
    {
        var service = new Service();
        var id = Guid.NewGuid();
        using var recording = new RecentPlaybackOpenRecording(service, Resource, id, 3, Token, Token);
        if (readyFirst) recording.Observe(Snapshot(id)); else recording.Accepted();
        Assert.Equal(0, service.Calls);
        if (readyFirst) recording.Accepted(); else recording.Observe(Snapshot(id));
        await recording.Completion;
        recording.Observe(Snapshot(id));
        recording.Accepted();
        await recording.Completion;
        Assert.Equal(1, service.Calls);
    }

    [Theory]
    [InlineData("wrong-session")]
    [InlineData("wrong-generation")]
    [InlineData("unconfirmed")]
    [InlineData("preparing")]
    [InlineData("failed")]
    [InlineData("closed")]
    [InlineData("canceled")]
    public async Task PlaybackRejectsUnconfirmedStaleOrTerminalOpen(string scenario)
    {
        var service = new Service();
        var id = Guid.NewGuid();
        using var opening = new CancellationTokenSource();
        using var recording = new RecentPlaybackOpenRecording(service, Resource, id, 3, opening.Token, Token);
        var snapshot = Snapshot(id);
        snapshot = scenario switch
        {
            "wrong-session" => snapshot with { SessionId = Guid.NewGuid() },
            "wrong-generation" => snapshot with { Generation = 4 },
            "unconfirmed" => snapshot with { ConfirmedPosition = null },
            "preparing" => snapshot with { Status = PlaybackStatus.Preparing },
            "failed" => snapshot with { Status = PlaybackStatus.Failed },
            "closed" => snapshot with { Status = PlaybackStatus.Closing },
            _ => snapshot,
        };
        if (scenario == "canceled") opening.Cancel();
        recording.Observe(snapshot);
        recording.Accepted();
        await recording.Completion;
        Assert.Equal(0, service.Calls);
        if (scenario is "failed" or "closed")
        {
            recording.Observe(Snapshot(id));
            await recording.Completion;
            Assert.Equal(0, service.Calls);
        }
    }

    [Theory]
    [InlineData(PlaybackStatus.Playing)]
    [InlineData(PlaybackStatus.Paused)]
    [InlineData(PlaybackStatus.Buffering)]
    public async Task ConfirmedReadyMediaRecordsEvenWhenPausedOrBuffering(PlaybackStatus status)
    {
        var service = new Service();
        var id = Guid.NewGuid();
        using var recording = new RecentPlaybackOpenRecording(service, Resource, id, 3, Token, Token);
        recording.Accepted();
        recording.Observe(Snapshot(id) with { Status = status });
        await recording.Completion;
        Assert.Equal(1, service.Calls);
    }

    [Fact]
    public async Task DisposedPlaybackDoesNotRecordLateReadiness()
    {
        var service = new Service();
        var id = Guid.NewGuid();
        var recording = new RecentPlaybackOpenRecording(service, Resource, id, 3, Token, Token);
        recording.Accepted();
        recording.Dispose();
        recording.Observe(Snapshot(id));
        recording.Accepted();
        recording.Dispose();
        await recording.Completion;
        Assert.Equal(0, service.Calls);
    }

    [Fact]
    public async Task UnresponsiveProviderHasBoundedWaitWithoutRetry()
    {
        var reply = new TaskCompletionSource<RecentAccessRecordResult>(TaskCreationOptions.RunContinuationsAsynchronously);
        var service = new Service { Handler = _ => reply.Task };
        var recording = new RecentOpenRecording(service, Resource, 3);
        try
        {
            await recording.ConfirmOpenedAsync(3, Token).WaitAsync(TimeSpan.FromSeconds(8), Token);
            await recording.ConfirmOpenedAsync(3, Token);
            Assert.Equal(1, service.Calls);
        }
        finally { reply.TrySetResult(new RecentAccessRecordResult(RecentAccessRecordOutcome.OutcomeUnknown)); }
    }

    private static PlaybackSnapshot Snapshot(Guid id) => new(id, 3, "video", PlaybackStatus.Playing,
        TimeSpan.Zero, TimeSpan.Zero, null, false, [], new SubtitleCapabilities(true, true, true, true, true), new PlaybackDiagnostics());

    private sealed class Service : IRecentAccessService
    {
        internal int Calls;
        internal RemoteResourceRef? Resource;
        internal Func<CancellationToken, Task<RecentAccessRecordResult>> Handler = _ =>
            Task.FromResult(new RecentAccessRecordResult(RecentAccessRecordOutcome.Confirmed));
        public Task<RecentAccessRecordResult> RecordOpenedAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
        {
            Interlocked.Increment(ref Calls);
            Resource = resource;
            return Handler(cancellationToken);
        }
        public Task<IReadOnlyList<RecentAccessEntry>> ListRecentAsync(AccountKey account, CancellationToken cancellationToken)
            => throw new NotSupportedException();
    }
}
