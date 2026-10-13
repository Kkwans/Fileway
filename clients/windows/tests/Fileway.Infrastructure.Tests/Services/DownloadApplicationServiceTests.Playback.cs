using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;
using Fileway.Infrastructure.Services;

namespace Fileway.Infrastructure.Tests.Services;

public sealed partial class DownloadApplicationServiceTests
{
    [Fact]
    public async Task PlaybackAdmitsOnlyOriginalActiveTaskAndDoesNotStartOrCancelWriter()
    {
        await using var fixture = new PlaybackFixture();
        var task = await fixture.StartAsync();
        var observed = new TaskCompletionSource<DownloadItem>(TaskCreationOptions.RunContinuationsAsynchronously);
        fixture.Downloads.TaskChanged += (_, item) => { if (item.Snapshot.Generation > 0) observed.TrySetResult(item); };

        var source = await fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token);

        Assert.Equal(task, source.Item.Snapshot.Task);
        Assert.Equal(DownloadStatus.Downloading, source.Item.Status);
        Assert.False(source.Item.Snapshot.LocalPublished);
        Assert.Equal(8, source.Reader.TotalBytes);
        Assert.Equal(source.Item.Snapshot.Generation, source.Reader.Run.Generation);
        Assert.Equal(8, (await observed.Task.WaitAsync(Timeout, Token)).Snapshot.TotalBytes);
        Assert.Single(fixture.Runner.Inner.Runs);
        Assert.Equal(0, fixture.Runner.Inner.Cancels);
        await source.Reader.DisposeAsync();
        await source.Reader.DisposeAsync();
        Assert.Equal(1, fixture.Runner.LastReader!.Disposals);
        Assert.Equal(0, fixture.Runner.Inner.Cancels);
        Assert.DoesNotContain("/private/movie", source.ToString(), StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("account")]
    [InlineData("session")]
    [InlineData("activation")]
    [InlineData("task-account")]
    [InlineData("empty-task")]
    [InlineData("other-task")]
    public async Task PlaybackRejectsForgedContextAndTaskBeforeReaderAcquisition(string field)
    {
        await using var fixture = new PlaybackFixture();
        var task = await fixture.StartAsync();
        var context = fixture.Context;
        var other = context.Account with { ServerUserId = "other" };
        context = field switch
        {
            "account" => context with { Account = other },
            "session" => context with { SessionId = Guid.NewGuid() },
            "activation" => context with { ActivationId = Guid.NewGuid() },
            _ => context,
        };
        task = field switch
        {
            "task-account" => task with { Account = other },
            "empty-task" => task with { Id = Guid.Empty },
            "other-task" => task with { Id = Guid.NewGuid() },
            _ => task,
        };
        var expected = field is "task-account" or "empty-task" ? ErrorCode.InvalidRequest : ErrorCode.SourceChanged;
        await FailureAsync(expected, () => fixture.Playback.AcquirePlaybackAsync(context, task, Token));
        Assert.Equal(0, fixture.Runner.Acquisitions);
    }

    [Fact]
    public async Task PlaybackOptionalCapabilityFailsWithoutStartingAnotherTask()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var item = await fixture.Downloads.SubmitAsync(await fixture.PreviewAsync(), Token);
        var playback = Assert.IsAssignableFrom<IDownloadPlaybackSourceProvider>(fixture.Bridge);
        await FailureAsync(ErrorCode.CapabilityMissing, () => playback.AcquirePlaybackAsync(fixture.Context, item.Snapshot.Task, Token));
        Assert.Single(fixture.Runner.Runs);
    }

    [Fact]
    public async Task PlaybackRejectsReadOnlyHistoryAndPreparingEvidenceWithoutAcquiringReader()
    {
        await using var fixture = new PlaybackFixture();
        var task = await fixture.StartAsync();
        fixture.Store.Transform = record => record with { Checkpoint = record.Checkpoint with { Phase = TransferPhase.Preparing } };
        await FailureAsync(ErrorCode.Busy, () => fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token));
        fixture.Store.Transform = null;
        var current = await fixture.Store.Inner.GetTransferAsync(task, Token);
        var history = await fixture.Store.Inner.CreateTransferAsync(current!.Plan with { Reference = task with { Id = Guid.NewGuid() } }, Token);
        await fixture.Downloads.ListAsync(fixture.Context, null, 50, Token);
        await FailureAsync(ErrorCode.SourceChanged, () => fixture.Playback.AcquirePlaybackAsync(fixture.Context, history.Plan.Reference, Token));
        Assert.Equal(0, fixture.Runner.Acquisitions);
        Assert.Single(fixture.Runner.Inner.Runs);
    }

    [Theory]
    [InlineData("account")]
    [InlineData("task")]
    [InlineData("run")]
    [InlineData("generation")]
    [InlineData("total")]
    [InlineData("negative-prefix")]
    [InlineData("overflow-prefix")]
    public async Task PlaybackRejectsWrongReaderEvidenceAndDisposesExactlyOnce(string field)
    {
        await using var fixture = new PlaybackFixture();
        var task = await fixture.StartAsync();
        fixture.Runner.Transform = reader =>
        {
            switch (field)
            {
                case "account": reader.Identity = reader.Identity with { Task = task with { Account = task.Account with { ServerUserId = "other" } } }; break;
                case "task": reader.Identity = reader.Identity with { Task = task with { Id = Guid.NewGuid() } }; break;
                case "run": reader.Identity = reader.Identity with { Id = Guid.NewGuid() }; break;
                case "generation": reader.Identity = reader.Identity with { Generation = reader.Identity.Generation + 1 }; break;
                case "total": reader.Total = 9; break;
                case "negative-prefix": reader.Prefix = -1; break;
                case "overflow-prefix": reader.Prefix = 9; break;
            }
        };
        await FailureAsync(ErrorCode.ProtocolMismatch, () => fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token));
        Assert.Equal(1, fixture.Runner.LastReader!.Disposals);
        Assert.Equal(0, fixture.Runner.Inner.Cancels);
    }

    [Theory]
    [InlineData("source-version")]
    [InlineData("source-modified")]
    [InlineData("source-length")]
    [InlineData("native-identity")]
    [InlineData("partial-identity")]
    [InlineData("partial-path")]
    [InlineData("run")]
    [InlineData("generation")]
    [InlineData("remote-path")]
    [InlineData("local-path")]
    public async Task PlaybackRejectsSourceAndExecutionChangesDuringAdmission(string field)
    {
        await using var fixture = new PlaybackFixture();
        var task = await fixture.StartAsync();
        fixture.Runner.Transform = _ => fixture.Store.Transform = record => field switch
        {
            "source-version" => record with { Checkpoint = record.Checkpoint with { Source = record.Checkpoint.Source! with { SourceVersion = "private changed version" } } },
            "source-modified" => record with { Checkpoint = record.Checkpoint with { Source = record.Checkpoint.Source! with { Modified = DateTimeOffset.UtcNow } } },
            "source-length" => record with { Checkpoint = record.Checkpoint with { Source = record.Checkpoint.Source! with { Length = 9 } } },
            "native-identity" => record with { Checkpoint = record.Checkpoint with { Source = record.Checkpoint.Source! with { NativeFileIdentity = "private changed source" } } },
            "partial-identity" => record with { Checkpoint = record.Checkpoint with { Files = record.Checkpoint.Files! with { PartialIdentity = "private changed partial" } } },
            "partial-path" => record with { Checkpoint = record.Checkpoint with { Files = record.Checkpoint.Files! with { PartialPath = @"C:\private\changed.part" } } },
            "run" => record with { Run = record.Run!.Value with { Id = Guid.NewGuid() } },
            "generation" => record with { Generation = record.Generation + 1, Run = record.Run!.Value with { Generation = record.Generation + 1 } },
            "remote-path" => record with { Plan = record.Plan with { Remote = record.Plan.Remote with { WirePath = "/private/other" } } },
            "local-path" => record with { Plan = record.Plan with { LocalPath = @"C:\private\other" } },
            _ => record,
        };
        var expected = field is "remote-path" or "local-path" ? ErrorCode.ProtocolMismatch : ErrorCode.SourceChanged;
        await FailureAsync(expected, () => fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token));
        Assert.Equal(1, fixture.Runner.LastReader!.Disposals);
        Assert.Equal(0, fixture.Runner.Inner.Cancels);
    }

    [Theory]
    [InlineData("disconnect")]
    [InlineData("logout")]
    [InlineData("reconnect")]
    [InlineData("invalidate")]
    [InlineData("permission")]
    [InlineData("dispose")]
    public async Task PlaybackReadinessNeverBlocksAccountRetirementAndLateReaderIsRejected(string retirement)
    {
        await using var fixture = new PlaybackFixture();
        var task = await fixture.StartAsync();
        var original = fixture.Context;
        var release = fixture.Gate();
        fixture.Runner.WaitBeforeReturn = release.Task;
        var pending = fixture.Playback.AcquirePlaybackAsync(original, task, Token);
        await fixture.Runner.AcquireEntered.Task.WaitAsync(Timeout, Token);
        switch (retirement)
        {
            case "disconnect": await fixture.Bridge.DisconnectAsync(original.Account, Token).WaitAsync(Timeout, Token); break;
            case "logout": await fixture.Bridge.LogoutAsync(original.Account, Token).WaitAsync(Timeout, Token); break;
            case "reconnect": await fixture.ConnectAsync().WaitAsync(Timeout, Token); break;
            case "invalidate": fixture.Connection.Invalidate(); break;
            case "permission": fixture.Connection.EmitSession(fixture.Connection.Active! with { Permissions = fixture.Connection.Active.Permissions with { Download = false } }); break;
            case "dispose": await fixture.Bridge.DisposeAsync().AsTask().WaitAsync(Timeout, Token); break;
        }
        Assert.False(pending.IsCompleted);
        release.TrySetResult();
        await FailureAsync(retirement == "permission" ? ErrorCode.PermissionDenied : ErrorCode.SourceChanged,
            () => pending.WaitAsync(Timeout, Token));
        Assert.Equal(1, fixture.Runner.LastReader!.Disposals);
    }

    [Fact]
    public async Task PlaybackRejectsCompletionDuringReadinessWithoutReopeningPublishedFile()
    {
        await using var fixture = new PlaybackFixture();
        var task = await fixture.StartAsync();
        var release = fixture.Gate();
        fixture.Runner.WaitBeforeReturn = release.Task;
        var pending = fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token);
        await fixture.Runner.AcquireEntered.Task.WaitAsync(Timeout, Token);
        await fixture.Runner.Inner.CompleteAsync(task, Token);
        release.TrySetResult();
        await FailureAsync(ErrorCode.SourceChanged, () => pending.WaitAsync(Timeout, Token));
        Assert.Equal(1, fixture.Runner.LastReader!.Disposals);
        await FailureAsync(ErrorCode.SourceChanged, () => fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token));
        Assert.Equal(1, fixture.Runner.Acquisitions);
        Assert.Single(fixture.Runner.Inner.Runs);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task PlaybackCanceledLateAcquisitionReleasesOnceAndPreservesCleanupFailure(bool cleanupFails)
    {
        await using var fixture = new PlaybackFixture();
        var task = await fixture.StartAsync();
        var release = fixture.Gate();
        var disposal = fixture.Gate();
        fixture.Runner.WaitBeforeReturn = release.Task;
        fixture.Runner.Transform = reader => { reader.DisposalWait = disposal.Task; reader.DisposalFails = cleanupFails; };
        using var cancellation = CancellationTokenSource.CreateLinkedTokenSource(Token);
        var pending = fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, cancellation.Token);
        await fixture.Runner.AcquireEntered.Task.WaitAsync(Timeout, Token);
        await cancellation.CancelAsync();
        release.TrySetResult();
        await fixture.Runner.LastReader!.DisposalEntered.Task.WaitAsync(Timeout, Token);
        await FailureAsync(ErrorCode.Busy, () => fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token));
        Assert.False(pending.IsCompleted);
        Assert.Equal(0, fixture.Runner.Inner.Cancels);
        disposal.TrySetResult();
        await FailureAsync(cleanupFails ? ErrorCode.LocalStorageUnavailable : ErrorCode.Canceled, () => pending.WaitAsync(Timeout, Token));
        Assert.Equal(1, fixture.Runner.LastReader.Disposals);
        fixture.Runner.Transform = null;
        fixture.Runner.WaitBeforeReturn = null;
        var next = await fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token);
        await next.Reader.DisposeAsync();
    }

    [Fact]
    public async Task PlaybackRejectCleanupMayWaitForAccountCommandWithoutDeadlock()
    {
        await using var fixture = new PlaybackFixture();
        var task = await fixture.StartAsync();
        fixture.Runner.Transform = reader =>
        {
            reader.Total = 99;
            reader.OnDispose = () => fixture.Bridge.DisconnectAsync(fixture.Context.Account, Token);
        };
        await FailureAsync(ErrorCode.ProtocolMismatch,
            () => fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token).WaitAsync(Timeout, Token));
        Assert.Equal(1, fixture.Runner.LastReader!.Disposals);
        Assert.Contains("disconnect", fixture.Connection.Calls);
    }

    [Fact]
    public async Task PlaybackPreCanceledAcquisitionDoesNotEnterProviderOrStopWriter()
    {
        await using var fixture = new PlaybackFixture();
        var task = await fixture.StartAsync();
        using var cancellation = CancellationTokenSource.CreateLinkedTokenSource(Token);
        await cancellation.CancelAsync();
        await FailureAsync(ErrorCode.Canceled, () => fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, cancellation.Token));
        Assert.Equal(0, fixture.Runner.Acquisitions);
        Assert.Equal(0, fixture.Runner.Inner.Cancels);
    }

    [Fact]
    public async Task PlaybackReaderDisposalAndReadErrorsAreSafeAndRepeatedDisposalDoesNotRetry()
    {
        await using var fixture = new PlaybackFixture();
        var task = await fixture.StartAsync();
        fixture.Runner.Transform = reader => reader.DisposalFails = true;
        var source = await fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token);
        await FailureAsync(ErrorCode.LocalStorageUnavailable, () => source.Reader.ReadAsync(0, new byte[1], Token).AsTask());
        await FailureAsync(ErrorCode.LocalStorageUnavailable, () => source.Reader.DisposeAsync().AsTask());
        await FailureAsync(ErrorCode.LocalStorageUnavailable, () => source.Reader.DisposeAsync().AsTask());
        Assert.Equal(1, fixture.Runner.LastReader!.Disposals);
        Assert.Equal(0, fixture.Runner.Inner.Cancels);
    }

    [Fact]
    public async Task PlaybackConcurrentAdmissionIsBoundedUntilProviderReturns()
    {
        await using var fixture = new PlaybackFixture();
        var task = await fixture.StartAsync();
        var release = fixture.Gate();
        fixture.Runner.WaitBeforeReturn = release.Task;
        var pending = fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token);
        await fixture.Runner.AcquireEntered.Task.WaitAsync(Timeout, Token);
        await FailureAsync(ErrorCode.Busy, () => fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token));
        Assert.Equal(1, fixture.Runner.Acquisitions);
        release.TrySetResult();
        var source = await pending.WaitAsync(Timeout, Token);
        await source.Reader.DisposeAsync();
        Assert.Equal(0, fixture.Runner.Inner.Cancels);
    }

    [Theory]
    [InlineData("throw", ErrorCode.LocalStorageUnavailable)]
    [InlineData("null", ErrorCode.ProtocolMismatch)]
    [InlineData("getter", ErrorCode.LocalStorageUnavailable)]
    public async Task PlaybackFailedProviderAndMetadataAreSanitizedAndReleaseAdmissionSlot(string failure, ErrorCode code)
    {
        await using var fixture = new PlaybackFixture();
        var task = await fixture.StartAsync();
        fixture.Runner.Failure = failure;
        await FailureAsync(code, () => fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token));
        if (failure == "getter") Assert.Equal(1, fixture.Runner.LastReader!.Disposals);
        fixture.Runner.Failure = null;
        var source = await fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token);
        await source.Reader.DisposeAsync();
        Assert.Equal(0, fixture.Runner.Inner.Cancels);
        Assert.Single(fixture.Runner.Inner.Runs);
    }

    [Theory]
    [InlineData("run")]
    [InlineData("source")]
    [InlineData("partial")]
    [InlineData("uncertain")]
    [InlineData("canceled")]
    public async Task PlaybackRejectsIncompleteOrUnsafeAcceptedEvidenceBeforeAcquisition(string field)
    {
        await using var fixture = new PlaybackFixture();
        var task = await fixture.StartAsync();
        fixture.Store.Transform = record => field switch
        {
            "run" => record with { Run = null },
            "source" => record with { Checkpoint = record.Checkpoint with { Source = null } },
            "partial" => record with { Checkpoint = record.Checkpoint with { Files = null } },
            "uncertain" => record with { Checkpoint = record.Checkpoint with { Uncertainty = TransferUncertainty.LocalPublish } },
            "canceled" => record with { Intent = TransferIntent.Cancel },
            _ => record,
        };
        await FailureAsync(field is "uncertain" or "canceled" ? ErrorCode.SourceChanged : ErrorCode.ProtocolMismatch,
            () => fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token));
        Assert.Equal(0, fixture.Runner.Acquisitions);
    }

    private sealed class PlaybackFixture : IAsyncDisposable
    {
        private readonly List<TaskCompletionSource> _gates = [];
        internal PlaybackStore Store { get; } = new();
        internal FakeConnection Connection { get; }
        internal PlaybackRunner Runner { get; }
        internal UploadApplicationService Bridge { get; }
        internal IDownloadService Downloads => Bridge;
        internal IDownloadPlaybackSourceProvider Playback => Bridge;
        internal DownloadContext Context => Assert.IsType<DownloadContext>(Downloads.CurrentContext);
        internal PlaybackFixture()
        {
            Connection = new(Store.Inner);
            Runner = new(Store.Inner);
            var sources = new FakeSources();
            Bridge = new(Connection, Connection, sources, Store, new FakeUpload(), downloads: new(Runner, sources, new FakeSettings()));
        }
        internal TaskCompletionSource Gate() { var gate = Signal(); _gates.Add(gate); return gate; }
        internal Task<ConnectionSession> ConnectAsync() => Bridge.ConnectAsync(Connection.Profile, new(AuthenticationMode.NoAuthentication), Token);
        internal async Task<TransferTaskRef> StartAsync()
        {
            await ConnectAsync();
            var preview = await Downloads.PrepareAsync(Context, new(Connection.Account, "/private/movie", "movie.mp4", "/private/movie"), Token);
            var item = await Downloads.SubmitAsync(preview, Token);
            await Runner.Inner.Started.Task.WaitAsync(Timeout, Token);
            return item.Snapshot.Task;
        }
        public async ValueTask DisposeAsync()
        {
            foreach (var gate in _gates) gate.TrySetResult();
            await Bridge.DisposeAsync().AsTask().WaitAsync(Timeout, Token);
        }
    }

    private sealed class PlaybackRunner(InMemoryProfileStore store) : IFileDownloadRunner, IFileDownloadReadLeaseProvider
    {
        internal FakeDownload Inner { get; } = new(store);
        internal TaskCompletionSource AcquireEntered { get; } = Signal();
        internal Task? WaitBeforeReturn { get; set; }
        internal Action<PlaybackReader>? Transform { get; set; }
        internal PlaybackReader? LastReader { get; private set; }
        internal int Acquisitions;
        internal string? Failure { get; set; }
        public event EventHandler<DownloadTaskProgress>? ProgressChanged
        { add => Inner.ProgressChanged += value; remove => Inner.ProgressChanged -= value; }
        public Task<TransferTaskRecord> RunAsync(TransferTaskRef task, CancellationToken cancellationToken) => Inner.RunAsync(task, cancellationToken);
        public Task<TransferTaskRecord> CancelAsync(TransferTaskRef task, CancellationToken cancellationToken) => Inner.CancelAsync(task, cancellationToken);
        public Task<TransferTaskRecord> ReconcileAsync(TransferTaskRef task, CancellationToken cancellationToken) => Inner.ReconcileAsync(task, cancellationToken);
        public Task StopAndDrainAsync(CancellationToken cancellationToken) => Inner.StopAndDrainAsync(cancellationToken);
        public ValueTask DisposeAsync() => Inner.DisposeAsync();
        public async Task<IDownloadReadLease> AcquireReadLeaseAsync(TransferTaskRef task, CancellationToken cancellationToken)
        {
            Acquisitions++;
            if (Failure == "throw") throw new IOException("private provider path and credential");
            if (Failure == "null") return null!;
            var record = (await store.GetTransferAsync(task, cancellationToken))!;
            LastReader = new(record.Run!.Value, record.Checkpoint.Source!.Length) { GetterFails = Failure == "getter" };
            Transform?.Invoke(LastReader);
            AcquireEntered.TrySetResult();
            if (WaitBeforeReturn is { } wait) await wait; // Deliberately noncooperative late provider.
            return LastReader;
        }
    }

    private sealed class PlaybackReader(TransferRunToken run, long total) : IDownloadReadLease
    {
        internal TransferRunToken Identity { get; set; } = run;
        internal long Total { get; set; } = total;
        internal long Prefix { get; set; }
        internal int Disposals;
        internal bool DisposalFails { get; set; }
        internal bool GetterFails { get; set; }
        internal Task? DisposalWait { get; set; }
        internal Func<Task>? OnDispose { get; set; }
        internal TaskCompletionSource DisposalEntered { get; } = Signal();
        public TransferRunToken Run => GetterFails ? throw new IOException("private reader identity") : Identity;
        public long TotalBytes => Total;
        public long ReadablePrefixBytes => Prefix;
        public ValueTask<int> ReadAsync(long offset, Memory<byte> buffer, CancellationToken cancellationToken) =>
            ValueTask.FromException<int>(new IOException("private local path and token"));
        public async ValueTask DisposeAsync()
        {
            Interlocked.Increment(ref Disposals);
            DisposalEntered.TrySetResult();
            if (OnDispose is { } action) await action();
            if (DisposalWait is { } wait) await wait;
            if (DisposalFails) throw new IOException("private dispose path and credential");
        }
    }

    private sealed class PlaybackStore : ITransferTaskStore
    {
        internal InMemoryProfileStore Inner { get; } = new();
        internal Func<TransferTaskRecord, TransferTaskRecord>? Transform { get; set; }
        public bool IsPersistent => false;
        public Task<TransferTaskRecord> CreateTransferAsync(TransferPlan plan, CancellationToken cancellationToken) => Inner.CreateTransferAsync(plan, cancellationToken);
        public async Task<TransferTaskRecord?> GetTransferAsync(TransferTaskRef task, CancellationToken cancellationToken)
        { var record = await Inner.GetTransferAsync(task, cancellationToken); return record is not null && Transform is { } transform ? transform(record) : record; }
        public Task<TransferTaskPage> ListTransfersAsync(AccountKey account, string? cursor, int limit, CancellationToken cancellationToken) => Inner.ListTransfersAsync(account, cursor, limit, cancellationToken);
        public Task<bool> HasProtectedTransfersAsync(AccountKey account, CancellationToken cancellationToken) => Inner.HasProtectedTransfersAsync(account, cancellationToken);
        public Task<TransferTaskRecord> SetTransferIntentAsync(TransferTaskRef task, long expectedRevision, TransferIntent intent, CancellationToken cancellationToken) => Inner.SetTransferIntentAsync(task, expectedRevision, intent, cancellationToken);
        public Task<TransferTaskRecord> ClaimTransferAsync(TransferTaskRef task, long expectedRevision, Guid runId, bool recovery, CancellationToken cancellationToken) => Inner.ClaimTransferAsync(task, expectedRevision, runId, recovery, cancellationToken);
        public Task<TransferTaskRecord> SaveTransferCheckpointAsync(TransferRunToken run, long expectedRevision, TransferCheckpoint checkpoint, bool releaseRun, CancellationToken cancellationToken) => Inner.SaveTransferCheckpointAsync(run, expectedRevision, checkpoint, releaseRun, cancellationToken);
    }
}
