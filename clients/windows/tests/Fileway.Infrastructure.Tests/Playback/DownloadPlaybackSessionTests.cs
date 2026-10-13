using System.Collections.Concurrent;
using System.Threading.Channels;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Playback;
using Fileway.Playback.Contracts;

namespace Fileway.Infrastructure.Tests.Playback;

public sealed class DownloadPlaybackSessionTests
{
    private static readonly TimeSpan Deadline = TimeSpan.FromSeconds(10);
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Fact]
    public async Task MissingCapabilityFailsBeforeAnyNativeOrRemoteAcquisition()
    {
        var fixture = new Fixture();
        var remote = new RejectRemoteLeases();
        await using var service = new MpvPlaybackService(remote, new RejectSurface(), MissingRuntime());
        var error = await Assert.ThrowsAsync<FilewayException>(() => service.OpenAsync(fixture.Request, Token));
        Assert.Equal(ErrorCode.CapabilityMissing, error.Error.Code);
        Assert.Equal("当前下载不支持播放读取。", error.Error.Message);
        Assert.Equal(0, remote.Calls);
    }

    [Fact]
    public async Task NativeLoadFailureReleasesAdmittedReaderAndSubscriptionUsingOriginalProgressIdentity()
    {
        var fixture = new Fixture();
        var remote = new RejectRemoteLeases();
        var progress = new ProgressStore();
        await using var service = new MpvPlaybackService(remote, new RejectSurface(), MissingRuntime(), progress,
            downloads: fixture.Provider);
        var error = await Assert.ThrowsAsync<FilewayException>(() => service.OpenAsync(fixture.Request, Token));
        Assert.Equal(ErrorCode.CapabilityMissing, error.Error.Code);
        Assert.Equal(1, fixture.Reader.DisposeCount);
        Assert.Equal(0, fixture.Provider.Subscribers);
        Assert.Equal(fixture.Source, progress.Resource);
        Assert.Equal(0, remote.Calls);
        Assert.Equal(0, fixture.Provider.WriterMutations);
    }

    [Fact]
    public async Task CanceledLateAdmissionStillOwnsAndReleasesReturnedReader()
    {
        var fixture = new Fixture();
        var entered = Signal();
        var release = Signal();
        fixture.Provider.Acquire = async (_, _, _) =>
        {
            entered.TrySetResult();
            await release.Task;
            return fixture.Admitted;
        };
        using var canceled = CancellationTokenSource.CreateLinkedTokenSource(Token);
        await using var service = new MpvPlaybackService(new RejectRemoteLeases(), new RejectSurface(), MissingRuntime(),
            downloads: fixture.Provider);
        var opening = service.OpenAsync(fixture.Request, canceled.Token);
        await entered.Task.WaitAsync(Deadline, Token);
        await canceled.CancelAsync();
        Assert.Equal(1, fixture.Provider.Subscribers);
        Assert.Equal(0, fixture.Reader.DisposeCount);
        release.TrySetResult();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => opening);
        Assert.Equal(1, fixture.Reader.DisposeCount);
        Assert.Equal(0, fixture.Provider.Subscribers);
        Assert.Equal(0, fixture.Provider.WriterMutations);
    }

    [Fact]
    public async Task CloseAccountReturnsBeforeLateAdmissionAndFencesItsResult()
    {
        var fixture = new Fixture();
        var entered = Signal();
        var release = Signal();
        CancellationToken admittedToken = default;
        int acquisitions = 0;
        fixture.Provider.Acquire = async (_, _, token) =>
        {
            Interlocked.Increment(ref acquisitions);
            admittedToken = token;
            entered.TrySetResult();
            await release.Task; // Account retirement is what releases this provider.
            return fixture.Admitted;
        };
        var surface = new RejectSurface();
        await using var service = new MpvPlaybackService(new RejectRemoteLeases(), surface, MissingRuntime(),
            downloads: fixture.Provider);
        var opening = service.OpenAsync(fixture.Request, Token);
        try
        {
            await entered.Task.WaitAsync(Deadline, Token);
            await service.CloseAccountAsync(fixture.Context.Account, Token).WaitAsync(Deadline, Token);
            Assert.True(admittedToken.IsCancellationRequested);
            Assert.False(opening.IsCompleted);
            Assert.Equal(0, fixture.Reader.DisposeCount);
            // Further downloads fail fast instead of growing a detached queue.
            var busy = await Assert.ThrowsAsync<FilewayException>(() => service.OpenAsync(new Fixture().Request, Token));
            Assert.Equal(ErrorCode.Busy, busy.Error.Code);
            Assert.Equal(1, acquisitions);
            // A different account can close/open ordinarily while old admission
            // waits. Missing-runtime failure proves it reached the native boundary.
            var other = new Fixture();
            await service.CloseAccountAsync(other.Context.Account, Token).WaitAsync(Deadline, Token);
            var ordinary = new PlaybackOpenRequest(new RemotePlaybackInput(other.Source));
            var runtime = await Assert.ThrowsAsync<FilewayException>(() => service.OpenAsync(ordinary, Token).WaitAsync(Deadline, Token));
            Assert.Equal("固定播放器运行库不可用。", runtime.Error.Message);
        }
        finally { release.TrySetResult(); }
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => opening);
        Assert.Equal(1, fixture.Reader.DisposeCount);
        Assert.Equal(0, fixture.Provider.Subscribers);
        Assert.Equal(0, surface.Calls);
        Assert.Equal(0, fixture.Provider.WriterMutations);
    }

    [Fact]
    public async Task DisposeReturnsBeforeLateAdmissionAndOriginalOpenReleasesItsReader()
    {
        var fixture = new Fixture();
        var entered = Signal();
        var release = Signal();
        CancellationToken admittedToken = default;
        fixture.Provider.Acquire = async (_, _, token) =>
        {
            admittedToken = token;
            entered.TrySetResult();
            await release.Task;
            return fixture.Admitted;
        };
        var surface = new RejectSurface();
        var service = new MpvPlaybackService(new RejectRemoteLeases(), surface, MissingRuntime(), downloads: fixture.Provider);
        var opening = service.OpenAsync(fixture.Request, Token);
        try
        {
            await entered.Task.WaitAsync(Deadline, Token);
            await service.DisposeAsync().AsTask().WaitAsync(Deadline, Token);
            Assert.True(admittedToken.IsCancellationRequested);
            Assert.False(opening.IsCompleted);
            Assert.Equal(0, fixture.Reader.DisposeCount);
            await Assert.ThrowsAsync<ObjectDisposedException>(() => service.OpenAsync(fixture.Request, Token));
        }
        finally { release.TrySetResult(); }
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => opening);
        await service.DisposeAsync();
        Assert.Equal(1, fixture.Reader.DisposeCount);
        Assert.Equal(0, fixture.Provider.Subscribers);
        Assert.Equal(0, surface.Calls);
    }

    [Fact]
    public async Task ClosingOtherAccountDoesNotRetireMatchingAdmission()
    {
        var fixture = new Fixture();
        var entered = Signal();
        var release = Signal();
        CancellationToken admittedToken = default;
        fixture.Provider.Acquire = async (_, _, token) =>
        {
            admittedToken = token;
            entered.TrySetResult();
            await release.Task;
            return fixture.Admitted;
        };
        await using var service = new MpvPlaybackService(new RejectRemoteLeases(), new RejectSurface(), MissingRuntime(),
            downloads: fixture.Provider);
        var opening = service.OpenAsync(fixture.Request, Token);
        try
        {
            await entered.Task.WaitAsync(Deadline, Token);
            await service.CloseAccountAsync(new Fixture().Context.Account, Token).WaitAsync(Deadline, Token);
            Assert.False(admittedToken.IsCancellationRequested);
        }
        finally { release.TrySetResult(); }
        var error = await Assert.ThrowsAsync<FilewayException>(() => opening);
        Assert.Equal("固定播放器运行库不可用。", error.Error.Message);
        Assert.Equal(1, fixture.Reader.DisposeCount);
    }

    [Fact]
    public async Task AccountCloseAlsoFencesNoncooperativeProgressReadBeforeNativeCreation()
    {
        var fixture = new Fixture();
        var progress = new BlockingProgressStore();
        var surface = new RejectSurface();
        await using var service = new MpvPlaybackService(new RejectRemoteLeases(), surface, MissingRuntime(), progress,
            downloads: fixture.Provider);
        var opening = service.OpenAsync(fixture.Request, Token);
        try
        {
            await progress.Entered.Task.WaitAsync(Deadline, Token);
            await service.CloseAccountAsync(fixture.Context.Account, Token).WaitAsync(Deadline, Token);
            Assert.True(progress.ReadToken.IsCancellationRequested);
            Assert.False(opening.IsCompleted);
        }
        finally { progress.Release.TrySetResult(); }
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => opening);
        Assert.Equal(1, fixture.Reader.DisposeCount);
        Assert.Equal(0, surface.Calls);
    }

    [Fact]
    public async Task AdmissionFailureRemovesSubscriptionWithoutTouchingWriter()
    {
        var fixture = new Fixture();
        fixture.Provider.Acquire = (_, _, _) => throw new FilewayException(new(ErrorCode.PermissionDenied, "fixture"));
        await using var service = new MpvPlaybackService(new RejectRemoteLeases(), new RejectSurface(), MissingRuntime(),
            downloads: fixture.Provider);
        var error = await Assert.ThrowsAsync<FilewayException>(() => service.OpenAsync(fixture.Request, Token));
        Assert.Equal(ErrorCode.PermissionDenied, error.Error.Code);
        Assert.Equal(0, fixture.Reader.DisposeCount);
        Assert.Equal(0, fixture.Provider.Subscribers);
        Assert.Equal(0, fixture.Provider.WriterMutations);
    }

    [Fact]
    public async Task MismatchedAdmittedRunIsRejectedAndReaderIsReleased()
    {
        var fixture = new Fixture();
        fixture.Provider.Acquire = (_, _, _) => Task.FromResult(fixture.Admitted with
        {
            Item = fixture.Item with { Snapshot = fixture.Item.Snapshot with { Generation = 2 } },
        });
        await using var service = new MpvPlaybackService(new RejectRemoteLeases(), new RejectSurface(), MissingRuntime(),
            downloads: fixture.Provider);
        var error = await Assert.ThrowsAsync<FilewayException>(() => service.OpenAsync(fixture.Request, Token));
        Assert.Equal(ErrorCode.ProtocolMismatch, error.Error.Code);
        Assert.Equal(1, fixture.Reader.DisposeCount);
        Assert.Equal(0, fixture.Provider.Subscribers);
    }

    [Fact]
    public async Task LatestEventDuringAdmissionIsRetainedAndOriginalFilenameIsUnchanged()
    {
        var fixture = new Fixture();
        await using var observation = fixture.Observe();
        for (int i = 2; i <= 1000; i++) fixture.Provider.Emit(fixture.At(i));
        observation.Admit(fixture.Admitted);
        Assert.Equal(1000, observation.Snapshot.Item.Snapshot.Revision);
        Assert.Equal(fixture.Source, observation.Snapshot.Item.Source);
        Assert.Equal("saved-as.mp4", observation.Snapshot.Item.Name);
        Assert.Equal(17, observation.Snapshot.ReadablePrefixBytes);
        Assert.Equal(0, fixture.Reader.DisposeCount);
    }

    [Fact]
    public async Task ExactContextTaskRunGenerationSourceAndRevisionFenceLateEvents()
    {
        var fixture = new Fixture();
        await using var observation = fixture.Observe();
        observation.Admit(fixture.Admitted);
        var updates = Channel.CreateUnbounded<PlaybackDownloadState>();
        observation.Start(state => updates.Writer.TryWrite(state));
        var newer = fixture.At(4);
        fixture.Provider.Emit(newer);
        var accepted = await updates.Reader.ReadAsync(Token).AsTask().WaitAsync(Deadline, Token);
        Assert.Equal(4, accepted.Item.Snapshot.Revision);
        var candidate = fixture.At(5);
        DownloadItem[] invalid =
        [
            fixture.At(3),
            candidate with { Context = candidate.Context with { ActivationId = Guid.NewGuid() } },
            candidate with { Context = candidate.Context with { SessionId = Guid.NewGuid() } },
            candidate with { Snapshot = candidate.Snapshot with { Task = candidate.Snapshot.Task with { Id = Guid.NewGuid() } } },
            candidate with { Snapshot = candidate.Snapshot with { Generation = 2 } },
            candidate with { Source = candidate.Source with { WirePath = "/different-source.mp4" } },
            candidate with { Progress = candidate.Progress! with { Run = fixture.Reader.Run with { Id = Guid.NewGuid() } } },
            candidate with { Snapshot = candidate.Snapshot with { TotalBytes = 101 } },
        ];
        foreach (var item in invalid) fixture.Provider.Emit(item);
        // A later valid event is a deterministic observation barrier. Invalid
        // events must not replace identity even if their revision looked newer.
        fixture.Provider.Emit(fixture.At(4, received: 50, elapsed: 50));
        accepted = await updates.Reader.ReadAsync(Token).AsTask().WaitAsync(Deadline, Token);
        Assert.Equal(4, accepted.Item.Snapshot.Revision);
        Assert.Equal(50, accepted.Item.Progress!.ReceivedBytes);
        Assert.Equal(fixture.Source, accepted.Item.Source);
        fixture.Provider.Emit(fixture.At(4, received: 49, elapsed: 60));
        fixture.Provider.Emit(fixture.At(4, received: 51, elapsed: 40));
        var terminal = fixture.At(6) with { Status = DownloadStatus.Completed, Progress = null,
            Snapshot = fixture.At(6).Snapshot with { Phase = TransferPhase.Completed, LocalPublished = true } };
        fixture.Provider.Emit(terminal);
        accepted = await updates.Reader.ReadAsync(Token).AsTask().WaitAsync(Deadline, Token);
        Assert.Equal(terminal, accepted.Item);
        Assert.Null(accepted.Item.Progress);
    }

    [Fact]
    public async Task PresentationSequenceOrdersSameDurableRevisionCancelAndFailure()
    {
        var fixture = new Fixture();
        await using var observation = fixture.Observe();
        var admitted = fixture.Admitted with { Item = fixture.Item with { PresentationRevision = 10 } };
        observation.Admit(admitted);
        var updates = Channel.CreateUnbounded<PlaybackDownloadState>();
        observation.Start(state => updates.Writer.TryWrite(state));
        var canceling = admitted.Item with { Status = DownloadStatus.Canceling, PresentationRevision = 11 };
        fixture.Provider.Emit(canceling);
        Assert.Equal(canceling, (await updates.Reader.ReadAsync(Token).AsTask().WaitAsync(Deadline, Token)).Item);
        var failed = canceling with { Status = DownloadStatus.Failed, PresentationRevision = 12,
            Snapshot = canceling.Snapshot with { Error = new(ErrorCode.SourceChanged, "fixture source changed") } };
        fixture.Provider.Emit(failed);
        Assert.Equal(failed, (await updates.Reader.ReadAsync(Token).AsTask().WaitAsync(Deadline, Token)).Item);
        fixture.Provider.Emit(canceling);
        fixture.Provider.Emit(admitted.Item);
        fixture.Provider.Emit(admitted.Item with { PresentationRevision = 13,
            Snapshot = admitted.Item.Snapshot with { Revision = 0 } });
        // A failed source has no current readable prefix, even if the provider
        // retains and later increases its historical completed-write count.
        fixture.Reader.Prefix = 30;
        var latestFailure = failed with { PresentationRevision = 14 };
        fixture.Provider.Emit(latestFailure);
        var final = await updates.Reader.ReadAsync(Token).AsTask().WaitAsync(Deadline, Token);
        Assert.Equal(latestFailure, final.Item);
        Assert.Null(final.ReadablePrefixBytes);
    }

    [Fact]
    public async Task NewerCancellationDuringPrefixSamplingCannotPublishTheOlderTaskState()
    {
        var fixture = new Fixture();
        await using var observation = fixture.Observe();
        observation.Admit(fixture.Admitted);
        var entered = Signal();
        var release = Signal();
        fixture.Reader.BeforePrefixRead = () =>
        {
            entered.TrySetResult();
            release.Task.GetAwaiter().GetResult();
        };
        var updates = Channel.CreateUnbounded<PlaybackDownloadState>();
        observation.Start(state => updates.Writer.TryWrite(state));
        try
        {
            await entered.Task.WaitAsync(Deadline, Token);
            var canceled = fixture.At(2) with { Status = DownloadStatus.Canceling };
            fixture.Provider.Emit(canceled);
            fixture.Reader.Prefix = 90;
            release.TrySetResult();
            var state = await updates.Reader.ReadAsync(Token).AsTask().WaitAsync(Deadline, Token);
            Assert.Equal(canceled, state.Item);
            Assert.Null(state.ReadablePrefixBytes);
            Assert.False(updates.Reader.TryRead(out _));
        }
        finally { release.TrySetResult(); }
    }

    [Fact]
    public async Task PublishingLocalFenceAndVerifiedCompletionKeepTheRetainedReaderEvidence()
    {
        var fixture = new Fixture();
        await using var observation = fixture.Observe();
        observation.Admit(fixture.Admitted);
        var updates = Channel.CreateUnbounded<PlaybackDownloadState>();
        observation.Start(state => updates.Writer.TryWrite(state));
        var publishing = fixture.At(2) with { Status = DownloadStatus.Publishing,
            Snapshot = fixture.At(2).Snapshot with { Phase = TransferPhase.Publishing, Uncertainty = TransferUncertainty.LocalPublish } };
        fixture.Provider.Emit(publishing);
        var state = await updates.Reader.ReadAsync(Token).AsTask().WaitAsync(Deadline, Token);
        Assert.Equal(publishing, state.Item);
        Assert.Equal(17, state.ReadablePrefixBytes);
        var completed = fixture.At(3) with { Status = DownloadStatus.Completed, Progress = null,
            Snapshot = fixture.At(3).Snapshot with { Phase = TransferPhase.Completed, LocalPublished = true } };
        fixture.Provider.Emit(completed);
        state = await updates.Reader.ReadAsync(Token).AsTask().WaitAsync(Deadline, Token);
        Assert.Equal(completed, state.Item);
        Assert.Equal(17, state.ReadablePrefixBytes);
        Assert.Equal(0, fixture.Reader.DisposeCount);
        Assert.Equal(0, fixture.Provider.WriterMutations);
    }

    [Fact]
    public async Task StoppingObservationClearsCurrentReadabilityAndKeepsOnlyHistoricalTaskEvidence()
    {
        var fixture = new Fixture();
        await using var observation = fixture.Observe();
        observation.Admit(fixture.Admitted);
        observation.Stop();
        Assert.Null(observation.Snapshot.ReadablePrefixBytes);
        Assert.Equal(fixture.Item, observation.Snapshot.Item);
        Assert.Equal(0, fixture.Reader.DisposeCount);
        Assert.Equal(0, fixture.Provider.WriterMutations);
        Assert.Equal(0, fixture.Provider.Subscribers);
    }

    [Fact]
    public async Task CoalescesBurstWithoutEngineEventsAndStopsAllSamplingAfterDisposal()
    {
        var fixture = new Fixture();
        var observation = fixture.Observe();
        observation.Admit(fixture.Admitted);
        var entered = Signal();
        var release = Signal();
        var terminal = Signal();
        var seen = new ConcurrentQueue<PlaybackDownloadState>();
        observation.Start(state =>
        {
            seen.Enqueue(state);
            if (state.Item.Snapshot.Revision == 2)
            {
                entered.TrySetResult();
                release.Task.GetAwaiter().GetResult();
            }
            if (state.Item.Snapshot.Revision == 1000) terminal.TrySetResult();
        });
        try
        {
            fixture.Provider.Emit(fixture.At(2));
            await entered.Task.WaitAsync(Deadline, Token);
            for (int i = 3; i <= 1000; i++) fixture.Provider.Emit(fixture.At(i));
            fixture.Reader.Prefix = 80;
            release.TrySetResult();
            await terminal.Task.WaitAsync(Deadline, Token);
            Assert.Equal(2, seen.Count);
            Assert.Equal(80, seen.Last().ReadablePrefixBytes);
            await observation.DisposeAsync();
            int reads = fixture.Reader.PrefixReads;
            var retainedCallback = fixture.Provider.LastSubscriber;
            retainedCallback?.Invoke(fixture.Provider, fixture.At(1001));
            fixture.Provider.Emit(fixture.At(1002));
            Assert.Equal(reads, fixture.Reader.PrefixReads);
            Assert.Equal(1000, observation.Snapshot.Item.Snapshot.Revision);
            Assert.Equal(0, fixture.Provider.Subscribers);
            Assert.Equal(0, fixture.Reader.DisposeCount);
        }
        finally { release.TrySetResult(); await observation.DisposeAsync(); }
    }

    [Fact]
    public async Task RevokedReadablePrefixIsUnknownAndDoesNotInventBufferedTime()
    {
        var fixture = new Fixture();
        var observation = fixture.Observe();
        observation.Admit(fixture.Admitted);
        var surface = new BlockingSurface();
        var session = new MpvPlaybackSession(fixture.Request, 9, null!, new RejectRemoteLeases(), surface,
            _ => { }, download: fixture.Admitted, downloadObservation: observation);
        var starting = session.StartAsync(Token);
        await surface.Entered.Task.WaitAsync(Deadline, Token);
        fixture.Reader.PrefixError = true;
        fixture.Provider.Emit(fixture.At(2));
        await using var updates = session.WatchAsync(Token).GetAsyncEnumerator(Token);
        PlaybackSnapshot snapshot;
        do
        {
            Assert.True(await updates.MoveNextAsync().AsTask().WaitAsync(Deadline, Token));
            snapshot = updates.Current;
        } while (snapshot.Download?.Item.Snapshot.Revision != 2);
        Assert.Null(snapshot.Download.ReadablePrefixBytes);
        Assert.Null(snapshot.BufferedRanges);
        Assert.Null(snapshot.BufferedUntil);
        Assert.Null(snapshot.Duration);
        Assert.Equal(fixture.Source.DisplayName, snapshot.DisplayName);
        Assert.Equal(fixture.Source.Account, session.Account);
        await session.StopAsync(Token);
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => starting);
        Assert.Equal(1, fixture.Reader.DisposeCount);
        Assert.Equal(0, fixture.Provider.WriterMutations);
    }

    [Fact]
    public async Task CloseDuringSurfaceStartupCancelsAndReleasesReaderExactlyOnce()
    {
        var fixture = new Fixture();
        var observation = fixture.Observe();
        observation.Admit(fixture.Admitted);
        var surface = new BlockingSurface();
        var recent = new RecentService();
        int released = 0;
        var remote = new RejectRemoteLeases();
        var session = new MpvPlaybackSession(fixture.Request, 7, null!, remote, surface,
            _ => Interlocked.Increment(ref released), recent, fixture.Admitted, observation);
        var starting = session.StartAsync(Token);
        await surface.Entered.Task.WaitAsync(Deadline, Token);
        await Task.WhenAll(session.StopAsync(Token), session.StopAsync(Token)).WaitAsync(Deadline, Token);
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => starting);
        await session.DisposeAsync();
        Assert.Equal(PlaybackStatus.Stopped, session.Snapshot.Status);
        Assert.Null(session.Snapshot.Download!.ReadablePrefixBytes);
        Assert.Equal(1, fixture.Reader.DisposeCount);
        Assert.Equal(1, released);
        Assert.Equal(0, fixture.Provider.Subscribers);
        Assert.Equal(0, remote.Calls);
        Assert.Equal(0, recent.RecordCount);
        Assert.Equal(0, fixture.Provider.WriterMutations);
        await Assert.ThrowsAsync<FilewayException>(() => session.StartAsync(Token));
    }

    [Fact]
    public async Task CallerCancelDoesNotReleaseReaderWhileStartupStillOwnsIt()
    {
        var fixture = new Fixture();
        var observation = fixture.Observe();
        observation.Admit(fixture.Admitted);
        var surface = new LateSurface();
        var session = new MpvPlaybackSession(fixture.Request, 7, null!, new RejectRemoteLeases(), surface,
            _ => { }, download: fixture.Admitted, downloadObservation: observation);
        var starting = session.StartAsync(Token);
        await surface.Entered.Task.WaitAsync(Deadline, Token);
        using var canceled = new CancellationTokenSource();
        await canceled.CancelAsync();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => session.StopAsync(canceled.Token));
        Assert.Equal(0, fixture.Reader.DisposeCount);
        Assert.Equal(0, fixture.Provider.Subscribers);
        surface.Release.TrySetResult();
        await Assert.ThrowsAsync<FilewayException>(() => starting);
        await session.StopAsync(Token).WaitAsync(Deadline, Token);
        Assert.Equal(1, fixture.Reader.DisposeCount);
        Assert.True(surface.Disposed);
    }

    [Fact]
    public async Task SurfaceFailureDisposesReaderWithoutRecordingRecentOpen()
    {
        var fixture = new Fixture();
        var observation = fixture.Observe();
        observation.Admit(fixture.Admitted);
        var recent = new RecentService();
        var session = new MpvPlaybackSession(fixture.Request, 7, null!, new RejectRemoteLeases(), new RejectSurface(),
            _ => { }, recent, fixture.Admitted, observation);
        await Assert.ThrowsAsync<InvalidOperationException>(() => session.StartAsync(Token));
        await session.DisposeAsync();
        Assert.Equal(1, fixture.Reader.DisposeCount);
        Assert.Equal(0, fixture.Provider.Subscribers);
        Assert.Equal(0, recent.RecordCount);
    }

    [Fact]
    public async Task SessionStoppedBeforeStartupReleasesReaderWithoutNativeInstance()
    {
        var fixture = new Fixture();
        var observation = fixture.Observe();
        observation.Admit(fixture.Admitted);
        var session = new MpvPlaybackSession(fixture.Request, 7, null!, new RejectRemoteLeases(), new RejectSurface(),
            _ => { }, download: fixture.Admitted, downloadObservation: observation);
        await session.StopAsync(Token).WaitAsync(Deadline, Token);
        await session.DisposeAsync();
        Assert.Equal(1, fixture.Reader.DisposeCount);
        Assert.Equal(0, fixture.Provider.Subscribers);
        Assert.Equal(PlaybackStatus.Stopped, session.Snapshot.Status);
    }

    private static string MissingRuntime() => Path.Combine(Path.GetTempPath(), "fileway-missing-runtime-" + Guid.NewGuid().ToString("N"));
    private static TaskCompletionSource Signal() => new(TaskCreationOptions.RunContinuationsAsynchronously);

    private sealed class Fixture
    {
        internal Fixture()
        {
            var account = new AccountKey(Guid.NewGuid(), Guid.NewGuid(), "fixture");
            Context = new(account, Guid.NewGuid(), Guid.NewGuid());
            var task = new TransferTaskRef(account, Guid.NewGuid());
            Source = new(account, "/opaque%20source.mp4", "original source.mp4", "/original source.mp4");
            Reader = new(new(task, Guid.NewGuid(), 1));
            Item = new(Context, Source, "saved-as.mp4", @"C:\Downloads\saved-as.mp4", DownloadStatus.Downloading,
                new(task, TransferIntent.Run, TransferPhase.Running, 10, 100, false, TransferUncertainty.None, null, 1, 1),
                new(Reader.Run, 20, 10, 100, TimeSpan.FromSeconds(1)));
            Admitted = new(Item, Reader);
            Input = new(Context, task);
            Provider = new(Context) { Acquire = (_, _, _) => Task.FromResult(Admitted) };
        }
        internal DownloadContext Context { get; }
        internal DownloadPlaybackInput Input { get; }
        internal PlaybackOpenRequest Request => new(Input);
        internal RemoteResourceRef Source { get; }
        internal TestReader Reader { get; }
        internal DownloadItem Item { get; }
        internal DownloadPlaybackSource Admitted { get; }
        internal Provider Provider { get; }
        internal DownloadPlaybackObservation Observe() => new(Provider, Input);
        internal DownloadItem At(long revision, long received = 20, double elapsed = 1) => Item with
        {
            Snapshot = Item.Snapshot with { Revision = revision },
            Progress = Item.Progress! with { ReceivedBytes = received, Elapsed = TimeSpan.FromSeconds(elapsed) },
        };
    }

    private sealed class TestReader(TransferRunToken run) : IDownloadReadLease
    {
        private int _disposed, _prefixReads;
        internal long Prefix = 17;
        internal bool PrefixError;
        internal Action? BeforePrefixRead;
        internal int DisposeCount => Volatile.Read(ref _disposed);
        internal int PrefixReads => Volatile.Read(ref _prefixReads);
        public TransferRunToken Run => run;
        public long TotalBytes => 100;
        public long ReadablePrefixBytes
        {
            get
            {
                Interlocked.Increment(ref _prefixReads);
                BeforePrefixRead?.Invoke();
                if (PrefixError) throw new FilewayException(new(ErrorCode.SourceChanged, "fixture revoked"));
                return Prefix;
            }
        }
        public ValueTask<int> ReadAsync(long offset, Memory<byte> buffer, CancellationToken cancellationToken)
            => throw new InvalidOperationException("No real native media read is expected in this managed lifecycle test.");
        public ValueTask DisposeAsync() { Interlocked.Increment(ref _disposed); return ValueTask.CompletedTask; }
    }

    private sealed class Provider(DownloadContext context) : IDownloadPlaybackSourceProvider
    {
        private EventHandler<DownloadItem>? _changed;
        internal EventHandler<DownloadItem>? LastSubscriber;
        internal int Subscribers;
        internal int WriterMutations;
        internal required Func<DownloadContext, TransferTaskRef, CancellationToken, Task<DownloadPlaybackSource>> Acquire;
        public event EventHandler<DownloadItem>? TaskChanged
        {
            add { _changed += value; LastSubscriber = value; Interlocked.Increment(ref Subscribers); }
            remove { _changed -= value; Interlocked.Decrement(ref Subscribers); }
        }
        internal void Emit(DownloadItem item) => _changed?.Invoke(this, item);
        public DownloadContext? CurrentContext => context;
        public Task<DownloadPlaybackSource> AcquirePlaybackAsync(DownloadContext input, TransferTaskRef task, CancellationToken cancellationToken)
            => Acquire(input, task, cancellationToken);
        public Task<DownloadPreview> PrepareAsync(DownloadContext input, RemoteResourceRef source, CancellationToken cancellationToken) => throw Mutation();
        public Task<DownloadPreview> PrepareAsAsync(DownloadContext input, RemoteResourceRef source, string localName, CancellationToken cancellationToken) => throw Mutation();
        public Task<DownloadItem> SubmitAsync(DownloadPreview preview, CancellationToken cancellationToken) => throw Mutation();
        public Task<DownloadPage> ListAsync(DownloadContext input, string? cursor, int limit, CancellationToken cancellationToken) => throw new InvalidOperationException();
        public Task<DownloadItem> CancelAsync(DownloadContext input, TransferTaskRef task, CancellationToken cancellationToken) => throw Mutation();
        public Task<DownloadItem> ReconcileLocalAsync(DownloadContext input, TransferTaskRef task, CancellationToken cancellationToken) => throw Mutation();
        private InvalidOperationException Mutation() { Interlocked.Increment(ref WriterMutations); return new("Playback must never mutate its writer."); }
    }

    private sealed class RejectRemoteLeases : IResourceLeaseProvider
    {
        internal int Calls;
        public Task<IResourceLease> AcquireAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
        { Interlocked.Increment(ref Calls); throw new InvalidOperationException("Download playback must not acquire remote HTTP media."); }
    }

    private sealed class RejectSurface : IPlaybackSurfaceFactory
    {
        internal int Calls;
        public Task<IPlaybackSurface> CreateAsync(IPlaybackSession session, CancellationToken cancellationToken)
        {
            Interlocked.Increment(ref Calls);
            throw new InvalidOperationException("No native surface is expected before admission/runtime validation.");
        }
    }

    private sealed class BlockingSurface : IPlaybackSurfaceFactory
    {
        internal TaskCompletionSource Entered { get; } = Signal();
        public async Task<IPlaybackSurface> CreateAsync(IPlaybackSession session, CancellationToken cancellationToken)
        {
            Entered.TrySetResult();
            await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
            throw new InvalidOperationException();
        }
    }

    private sealed class LateSurface : IPlaybackSurfaceFactory, IPlaybackSurface
    {
        internal TaskCompletionSource Entered { get; } = Signal();
        internal TaskCompletionSource Release { get; } = Signal();
        internal bool Disposed;
        public nint ParentHwnd => 0;
        public async Task<IPlaybackSurface> CreateAsync(IPlaybackSession session, CancellationToken cancellationToken)
        {
            Entered.TrySetResult();
            await Release.Task;
            return this;
        }
        public ValueTask DetachAsync(CancellationToken cancellationToken) => ValueTask.CompletedTask;
        public ValueTask DisposeAsync() { Disposed = true; return ValueTask.CompletedTask; }
    }

    private sealed class BlockingProgressStore : IPlaybackProgressStore
    {
        internal TaskCompletionSource Entered { get; } = Signal();
        internal TaskCompletionSource Release { get; } = Signal();
        internal CancellationToken ReadToken;
        public async Task<PlaybackBookmark?> ReadAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
        {
            ReadToken = cancellationToken;
            Entered.TrySetResult();
            await Release.Task;
            return null;
        }
        public Task WriteAsync(RemoteResourceRef resource, TimeSpan confirmedPosition, TimeSpan? duration, CancellationToken cancellationToken)
            => throw new InvalidOperationException();
        public Task ClearAsync(RemoteResourceRef resource, CancellationToken cancellationToken) => throw new InvalidOperationException();
    }

    private sealed class ProgressStore : IPlaybackProgressStore
    {
        internal RemoteResourceRef? Resource;
        public Task<PlaybackBookmark?> ReadAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
        { Resource = resource; return Task.FromResult<PlaybackBookmark?>(null); }
        public Task WriteAsync(RemoteResourceRef resource, TimeSpan confirmedPosition, TimeSpan? duration, CancellationToken cancellationToken)
            => throw new InvalidOperationException();
        public Task ClearAsync(RemoteResourceRef resource, CancellationToken cancellationToken) => throw new InvalidOperationException();
    }

    private sealed class RecentService : IRecentAccessService
    {
        internal int RecordCount;
        public Task<IReadOnlyList<RecentAccessEntry>> ListRecentAsync(AccountKey account, CancellationToken cancellationToken)
            => throw new InvalidOperationException();
        public Task<RecentAccessRecordResult> RecordOpenedAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
        { Interlocked.Increment(ref RecordCount); return Task.FromResult(new RecentAccessRecordResult(RecentAccessRecordOutcome.Confirmed)); }
    }
}
