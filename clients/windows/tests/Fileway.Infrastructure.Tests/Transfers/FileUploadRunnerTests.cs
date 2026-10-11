using System.Collections.Concurrent;
using System.Globalization;
using System.Net;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;
using Fileway.Infrastructure.Transfers;

namespace Fileway.Infrastructure.Tests.Transfers;

public sealed class FileUploadRunnerTests
{
    private static readonly byte[] Data = [3, 1, 4, 1, 5, 9, 2, 6, 5];
    private static readonly string[] FileMethods = ["POST", "HEAD", "PATCH", "PATCH", "PATCH", "HEAD"];
    private static readonly string[] CreationMethods = ["POST", "HEAD"];
    private static readonly string[] CancellationMethods = ["HEAD", "DELETE"];
    private static readonly int[] ChunkLengths = [4, 4, 1];
    private static readonly long[] ChunkOffsets = [0, 4, 8];
    private static readonly long[] ResumedOffsets = [4, 8];
    private static readonly FileUploadRunnerOptions SmallChunks = new() { ChunkSizeBytes = 4 };
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Fact]
    public async Task FileUsesDurableDispatchExactChunksAndFreshPublicationHead()
    {
        await using var fixture = await Fixture.CreateAsync(FileConflictPolicy.Replace);
        var progressObserved = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        fixture.Runner.ProgressChanged += (_, _) => { progressObserved.TrySetResult(); throw new InvalidOperationException("subscriber fixture"); };

        var result = await fixture.Runner.RunAsync(fixture.Task, recovery: false, Token);

        Assert.Equal(TransferPhase.Completed, result.Checkpoint.Phase);
        Assert.True(result.Checkpoint.RemotePublished);
        Assert.Equal(Data.LongLength, result.Checkpoint.ConfirmedOffset);
        Assert.Equal(Data, fixture.Http.RemoteBytes);
        Assert.Equal(FileMethods, fixture.Http.Requests.Select(item => item.Method));
        Assert.Equal(ChunkLengths, fixture.Http.Requests.Where(item => item.Method == "PATCH").Select(item => item.Body.Length));
        Assert.Equal(ChunkOffsets, fixture.Http.Requests.Where(item => item.Method == "PATCH").Select(item => item.Offset!.Value));
        var leases = fixture.Leases.Requests.ToArray();
        Assert.Equal(2, leases.Length);
        Assert.False(leases[0].Resume);
        Assert.True(leases[1].Resume);
        Assert.All(leases, item => { Assert.True(item.Overwrite); Assert.Equal(fixture.Task.Id, item.TransferId); });
        Assert.NotSame(fixture.Plan.Remote, leases[0].Target);
        Assert.Same(leases[0].Target, leases[1].Target);
        Assert.All(fixture.Sources.Opened, source => Assert.True(source.Disposed));
        Assert.False(fixture.Locks.IsHeld(fixture.Task));
        await progressObserved.Task.WaitAsync(Token);
    }

    [Fact]
    public async Task EmptyCreationNeedsAuthoritativeZeroHeadBeforeCompletion()
    {
        await using var fixture = await Fixture.CreateAsync(data: Array.Empty<byte>());

        var result = await fixture.Runner.RunAsync(fixture.Task, recovery: false, Token);

        Assert.Equal(TransferPhase.Completed, result.Checkpoint.Phase);
        Assert.True(result.Checkpoint.RemotePublished);
        Assert.Equal(0, result.Checkpoint.ConfirmedOffset);
        Assert.Equal(CreationMethods, fixture.Http.Requests.Select(item => item.Method));
        Assert.Null(Assert.Single(fixture.Http.Requests, item => item.Method == "POST").ConfirmedBeforeSend);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task SkipUsesTypedExistenceAndOnlyDefinitive404AllowsCreation(bool exists)
    {
        await using var fixture = await Fixture.CreateAsync(FileConflictPolicy.Skip);
        fixture.Files.Exists = exists;

        var result = await fixture.Runner.RunAsync(fixture.Task, recovery: false, Token);

        Assert.Equal(exists ? TransferPhase.Skipped : TransferPhase.Completed, result.Checkpoint.Phase);
        Assert.Equal(1, fixture.Files.Reads);
        if (exists) { Assert.Empty(fixture.Http.Requests); Assert.Empty(fixture.Leases.Requests); }
        else Assert.All(fixture.Leases.Requests, request => Assert.False(request.Overwrite));
    }

    [Fact]
    public async Task SkipMetadataPermissionFailureDoesNotBecomeAbsent()
    {
        await using var fixture = await Fixture.CreateAsync(FileConflictPolicy.Skip);
        fixture.Files.Failure = new(ErrorCode.PermissionDenied, "fixture metadata rejection", 403);

        var result = await fixture.Runner.RunAsync(fixture.Task, recovery: false, Token);

        Assert.Equal(TransferPhase.Failed, result.Checkpoint.Phase);
        Assert.Equal(ErrorCode.PermissionDenied, result.Checkpoint.Error!.Code);
        Assert.Empty(fixture.Http.Requests);
        Assert.Empty(fixture.Leases.Requests);
    }

    [Fact]
    public async Task FailureToPersistDispatchSendsNothingAndRetainsOwnershipEvidence()
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Store.BeforeSave = (_, _, checkpoint, _, _) => checkpoint.Uncertainty == TransferUncertainty.RemoteWrite
            ? Task.FromException(new FilewayException(new(ErrorCode.LocalStorageUnavailable, "fixture disk failure"))) : Task.CompletedTask;

        var error = await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.RunAsync(fixture.Task, recovery: false, Token));

        Assert.Equal(ErrorCode.LocalStorageUnavailable, error.Error.Code);
        Assert.Empty(fixture.Http.Requests);
        var durable = await fixture.ReadAsync();
        Assert.NotNull(durable.Run);
        Assert.Null(durable.Checkpoint.ConfirmedOffset);
        Assert.True(await fixture.Store.HasProtectedTransfersAsync(fixture.Task.Account, Token));
        Assert.All(fixture.Sources.Opened, source => Assert.True(source.Disposed));
    }

    [Fact]
    public async Task AcknowledgementRetriesOnlyMetadataAfterCancelIntentCasRace()
    {
        await using var fixture = await Fixture.CreateAsync();
        int raced = 0;
        fixture.Store.BeforeSave = async (run, revision, checkpoint, _, cancellationToken) =>
        {
            if (checkpoint.Phase == TransferPhase.Running && checkpoint.ConfirmedOffset == 4 &&
                Interlocked.CompareExchange(ref raced, 1, 0) == 0)
                await fixture.Store.Inner.SetTransferIntentAsync(run.Task, revision, TransferIntent.Cancel, cancellationToken);
        };

        var stopped = await fixture.Runner.RunAsync(fixture.Task, recovery: false, Token);

        Assert.Equal(TransferIntent.Cancel, stopped.Intent);
        Assert.Equal(4, stopped.Checkpoint.ConfirmedOffset);
        Assert.Equal(TransferPhase.Paused, stopped.Checkpoint.Phase);
        Assert.Single(fixture.Http.Requests, item => item.Method == "PATCH");
        Assert.Equal(TransferUncertainty.None, stopped.Checkpoint.Uncertainty);
        Assert.Equal(1, stopped.Generation);
        Assert.DoesNotContain(fixture.Http.Requests, item => item.Method == "DELETE");
    }

    [Fact]
    public async Task AckStorageFailureKeepsDispatchAndMetadataCancelDiscoversPublication()
    {
        await using var fixture = await Fixture.CreateAsync(data: Data[..4]);
        fixture.Store.BeforeSave = (_, _, checkpoint, _, _) => checkpoint.ConfirmedOffset == 4
            ? Task.FromException(new FilewayException(new(ErrorCode.LocalStorageUnavailable, "fixture ACK disk failure"))) : Task.CompletedTask;

        var error = await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.RunAsync(fixture.Task, recovery: false, Token));

        Assert.Equal(ErrorCode.LocalStorageUnavailable, error.Error.Code);
        var interrupted = await fixture.ReadAsync();
        Assert.NotNull(interrupted.Run);
        Assert.Equal(0, interrupted.Checkpoint.ConfirmedOffset);
        Assert.Equal(TransferUncertainty.RemoteWrite, interrupted.Checkpoint.Uncertainty);
        fixture.Store.BeforeSave = null;
        fixture.Sources.FailOpen = true;
        int opened = fixture.Sources.OpenCount;
        var result = await fixture.Runner.CancelAsync(fixture.Task, Token);
        Assert.Equal(TransferPhase.Completed, result.Checkpoint.Phase);
        Assert.Equal(4, result.Checkpoint.ConfirmedOffset);
        Assert.True(result.Checkpoint.RemotePublished);
        Assert.Equal(opened, fixture.Sources.OpenCount);
        Assert.Single(fixture.Http.Requests, item => item.Method == "POST");
        Assert.Single(fixture.Http.Requests, item => item.Method == "PATCH");
        Assert.DoesNotContain(fixture.Http.Requests, item => item.Method == "DELETE");
    }

    [Fact]
    public async Task LostChunkAckResumesWithNewHeadAndNeverRecreatesTransfer()
    {
        await using var fixture = await Fixture.CreateAsync();
        int lost = 0;
        fixture.Http.AfterPatch = _ => Interlocked.CompareExchange(ref lost, 1, 0) == 0
            ? Task.FromException<HttpResponseMessage>(new HttpRequestException("fixture lost acknowledgement")) : null;

        var unknown = await fixture.Runner.RunAsync(fixture.Task, recovery: false, Token);

        Assert.Equal(TransferPhase.OutcomeUnknown, unknown.Checkpoint.Phase);
        Assert.Equal(0, unknown.Checkpoint.ConfirmedOffset);
        Assert.Equal(TransferUncertainty.RemoteWrite, unknown.Checkpoint.Uncertainty);
        int previousRequests = fixture.Http.Requests.Count;
        var resumed = await fixture.Runner.RunAsync(fixture.Task, recovery: true, Token);
        Assert.Equal(TransferPhase.Completed, resumed.Checkpoint.Phase);
        Assert.Equal("HEAD", fixture.Http.Requests.ToArray()[previousRequests].Method);
        Assert.Single(fixture.Http.Requests, item => item.Method == "POST");
        Assert.Equal(ChunkOffsets, fixture.Http.Requests.Where(item => item.Method == "PATCH").Select(item => item.Offset!.Value));
        Assert.Equal(Data, fixture.Http.RemoteBytes);
    }

    [Fact]
    public async Task PausePersistsIntentBeforeStoppingAndKeepsInterruptedWriteUnknown()
    {
        await using var fixture = await Fixture.CreateAsync();
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        fixture.Http.AfterPatch = async cancellationToken =>
        {
            entered.TrySetResult();
            try { await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken); }
            catch (OperationCanceledException)
            {
                Assert.Equal(TransferIntent.Pause, (await fixture.ReadAsync()).Intent);
                throw;
            }
            throw new InvalidOperationException("unreachable fixture");
        };
        var running = fixture.Runner.RunAsync(fixture.Task, recovery: false, Token);
        await entered.Task.WaitAsync(Token);

        var paused = await fixture.Runner.PauseAsync(fixture.Task, Token);

        Assert.Equal(TransferIntent.Pause, paused.Intent);
        Assert.Equal(TransferPhase.OutcomeUnknown, paused.Checkpoint.Phase);
        Assert.Equal(TransferUncertainty.RemoteWrite, paused.Checkpoint.Uncertainty);
        Assert.Equal(paused, await running);
        Assert.DoesNotContain(fixture.Http.Requests, item => item.Method == "DELETE");
        Assert.All(fixture.Sources.Opened, source => Assert.True(source.Disposed));
        Assert.False(fixture.Locks.IsHeld(fixture.Task));
    }

    [Fact]
    public async Task QueuedCancelNeedsNeitherSourceNorRemoteCapabilityAndTerminalDoesNoMoreIo()
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Sources.FailOpen = true;

        var canceled = await fixture.Runner.CancelAsync(fixture.Task, Token);
        int acquisitions = fixture.Locks.Acquisitions;
        var repeated = await fixture.Runner.RunAsync(fixture.Task, recovery: true, Token);

        Assert.Equal(TransferPhase.Canceled, canceled.Checkpoint.Phase);
        Assert.False(canceled.Checkpoint.RemoteCanceled);
        Assert.Equal(canceled, repeated);
        Assert.Equal(acquisitions, fixture.Locks.Acquisitions);
        Assert.Equal(0, fixture.Sources.OpenCount);
        Assert.Empty(fixture.Leases.Requests);
        Assert.Empty(fixture.Http.Requests);
    }

    [Fact]
    public async Task KnownPartialCancelRequiresHeadThenExactDeleteAndReportsDeniedCleanup()
    {
        await using var fixture = await Fixture.CreateAsync();
        await fixture.SeedUnknownAsync(2);
        fixture.Http.RemoteOffset = 2;
        fixture.Http.DeleteStatus = HttpStatusCode.Forbidden;
        fixture.Sources.FailOpen = true;

        var result = await fixture.Runner.CancelAsync(fixture.Task, Token);

        Assert.Equal(CancellationMethods, fixture.Http.Requests.Select(item => item.Method));
        Assert.Equal(TransferPhase.Canceled, result.Checkpoint.Phase);
        Assert.False(result.Checkpoint.RemoteCanceled);
        Assert.Equal(TransferUncertainty.None, result.Checkpoint.Uncertainty);
        Assert.Equal(ErrorCode.PermissionDenied, result.Checkpoint.Error!.Code);
        Assert.Equal(0, fixture.Sources.OpenCount);
    }

    [Fact]
    public async Task ExplicitRemoteCancelRecordsOnlyExact204()
    {
        await using var fixture = await Fixture.CreateAsync();
        await fixture.SeedUnknownAsync(2);
        fixture.Http.RemoteOffset = 2;

        var result = await fixture.Runner.CancelAsync(fixture.Task, Token);

        Assert.Equal(TransferPhase.Canceled, result.Checkpoint.Phase);
        Assert.True(result.Checkpoint.RemoteCanceled);
        Assert.Equal(2, result.Checkpoint.ConfirmedOffset);
        Assert.Equal(CancellationMethods, fixture.Http.Requests.Select(item => item.Method));
        Assert.Equal(0, fixture.Sources.OpenCount);
    }

    [Fact]
    public async Task SourceChangeAfterHeadStopsBeforeAnyChunkAndUsesSafeError()
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Http.AfterHead = () => fixture.Sources.Changed = true;

        var result = await fixture.Runner.RunAsync(fixture.Task, recovery: false, Token);

        Assert.Equal(TransferPhase.SourceChanged, result.Checkpoint.Phase);
        Assert.Equal(0, result.Checkpoint.ConfirmedOffset);
        Assert.DoesNotContain(fixture.Http.Requests, item => item.Method == "PATCH");
        Assert.DoesNotContain(fixture.Plan.LocalPath, result.Checkpoint.Error!.Message, StringComparison.Ordinal);
        Assert.All(fixture.Sources.Opened, source => Assert.True(source.Disposed));
    }

    [Theory]
    [InlineData("account")]
    [InlineData("transfer")]
    [InlineData("length")]
    [InlineData("resume")]
    [InlineData("address")]
    public async Task WrongLeaseAuthorityNeverReceivesHttp(string mismatch)
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Leases.Mutate = lease =>
        {
            switch (mismatch)
            {
                case "account": lease.Resource = lease.Resource with { Account = lease.Resource.Account with { ServerUserId = "other" } }; break;
                case "transfer": lease.TransferId = Guid.NewGuid(); break;
                case "length": lease.Length++; break;
                case "resume": lease.IsResume = !lease.IsResume; break;
                default: lease.Address = new("http://127.0.0.1:32450/stream/unsafe/extra"); break;
            }
        };

        var result = await fixture.Runner.RunAsync(fixture.Task, recovery: false, Token);

        Assert.Equal(TransferPhase.Failed, result.Checkpoint.Phase);
        Assert.Equal(ErrorCode.ProtocolMismatch, result.Checkpoint.Error!.Code);
        Assert.Empty(fixture.Http.Requests);
        Assert.All(fixture.Leases.Issued, lease => Assert.True(lease.Disposed));
    }

    [Theory]
    [InlineData(HttpStatusCode.NotFound)]
    [InlineData(HttpStatusCode.Gone)]
    public async Task RecoveryMissingHeadPreservesOldUnknownAndNeverBlindlyPosts(HttpStatusCode status)
    {
        await using var fixture = await Fixture.CreateAsync();
        await fixture.SeedUnknownAsync(4);
        fixture.Http.HeadStatus = status;

        var result = await fixture.Runner.RunAsync(fixture.Task, recovery: true, Token);

        Assert.Equal(TransferPhase.OutcomeUnknown, result.Checkpoint.Phase);
        Assert.Equal(4, result.Checkpoint.ConfirmedOffset);
        Assert.Equal(TransferUncertainty.RemoteWrite, result.Checkpoint.Uncertainty);
        Assert.Equal(ErrorCode.OutcomeUnknown, result.Checkpoint.Error!.Code);
        Assert.Equal("HEAD", Assert.Single(fixture.Http.Requests).Method);
    }

    [Fact]
    public async Task LowerAuthoritativeResumeOffsetCanReplaceOldPrefixOnlyAfterRecoveryHead()
    {
        await using var fixture = await Fixture.CreateAsync();
        await fixture.SeedUnknownAsync(8);
        fixture.Http.RemoteOffset = 4;
        Data.AsSpan(0, 4).CopyTo(fixture.Http.RemoteBytes);

        var result = await fixture.Runner.RunAsync(fixture.Task, recovery: true, Token);

        Assert.Equal(TransferPhase.Completed, result.Checkpoint.Phase);
        Assert.Equal("HEAD", fixture.Http.Requests.First().Method);
        Assert.Equal(ResumedOffsets, fixture.Http.Requests.Where(item => item.Method == "PATCH").Select(item => item.Offset!.Value));
        Assert.Equal(Data, fixture.Http.RemoteBytes);
    }

    [Fact]
    public async Task NoProgressTimeoutStopsBlockedRequestAndKeepsPublicationUncertain()
    {
        await using var fixture = await Fixture.CreateAsync(options: SmallChunks with
        { RequestIdleTimeout = TimeSpan.FromMilliseconds(60), ControlCallTimeout = TimeSpan.FromMilliseconds(100) });
        fixture.Http.BeforeHead = async cancellationToken =>
        {
            await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
            throw new InvalidOperationException("unreachable fixture");
        };

        var result = await fixture.Runner.RunAsync(fixture.Task, recovery: false, Token);

        Assert.Equal(TransferPhase.OutcomeUnknown, result.Checkpoint.Phase);
        Assert.Equal(ErrorCode.Timeout, result.Checkpoint.Error!.Code);
        Assert.Equal(TransferUncertainty.RemoteWrite, result.Checkpoint.Uncertainty);
        Assert.DoesNotContain(fixture.Http.Requests, item => item.Method == "PATCH" || item.Method == "DELETE");
        Assert.All(fixture.Sources.Opened, source => Assert.True(source.Disposed));
    }

    [Fact]
    public async Task BusyCrossInstanceLockPreventsClaimSourceAndHttp()
    {
        await using var fixture = await Fixture.CreateAsync();
        await using var held = await fixture.Locks.AcquireAsync(fixture.Task, Token);

        var error = await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.RunAsync(fixture.Task, recovery: true, Token));

        Assert.Equal(ErrorCode.Busy, error.Error.Code);
        Assert.Equal(0, fixture.Sources.OpenCount);
        Assert.Empty(fixture.Http.Requests);
        Assert.Null((await fixture.ReadAsync()).Run);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task CallerStopAndDisposeDrainHandlesWithoutImplicitRemoteDelete(bool dispose)
    {
        await using var fixture = await Fixture.CreateAsync();
        using var caller = CancellationTokenSource.CreateLinkedTokenSource(Token);
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        fixture.Http.AfterPatch = async cancellationToken =>
        {
            entered.TrySetResult();
            await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
            throw new InvalidOperationException("unreachable fixture");
        };
        var running = fixture.Runner.RunAsync(fixture.Task, recovery: false, caller.Token);
        await entered.Task.WaitAsync(Token);

        if (dispose) await fixture.Runner.DisposeAsync();
        else await caller.CancelAsync();
        var result = await running;

        Assert.Equal(TransferIntent.Pause, result.Intent);
        Assert.Equal(TransferPhase.OutcomeUnknown, result.Checkpoint.Phase);
        Assert.DoesNotContain(fixture.Http.Requests, item => item.Method == "DELETE");
        Assert.All(fixture.Sources.Opened, source => Assert.True(source.Disposed));
        Assert.False(fixture.Locks.IsHeld(fixture.Task));
    }

    [Fact]
    public async Task UploadStatisticsProgressExtendsIdleBudgetWithoutAWholeFileDeadline()
    {
        await using var fixture = await Fixture.CreateAsync(data: Data[..4], options: SmallChunks with
        { RequestIdleTimeout = TimeSpan.FromMilliseconds(350) });
        int samples = 0;
        fixture.Leases.StatisticsReader = cancellationToken =>
        {
            cancellationToken.ThrowIfCancellationRequested();
            return Task.FromResult<UploadLeaseStatistics?>(new(Interlocked.Increment(ref samples), null, 0, 4, 1, TimeSpan.Zero, true));
        };
        fixture.Http.PatchDelay = TimeSpan.FromMilliseconds(600);

        var result = await fixture.Runner.RunAsync(fixture.Task, recovery: false, Token);

        Assert.Equal(TransferPhase.Completed, result.Checkpoint.Phase);
        Assert.True(samples >= 2);
        Assert.Equal(Data[..4], fixture.Http.RemoteBytes);
    }

    [Theory]
    [InlineData("run")]
    [InlineData("pause")]
    [InlineData("cancel")]
    public async Task NonUploadControlsRejectBeforeIntentOwnershipOrSourceIo(string action)
    {
        await using var fixture = await Fixture.CreateAsync(direction: TransferDirection.Download);
        var original = await fixture.ReadAsync();

        var error = await Assert.ThrowsAsync<FilewayException>(() => action switch
        {
            "run" => fixture.Runner.RunAsync(fixture.Task, recovery: false, Token),
            "pause" => fixture.Runner.PauseAsync(fixture.Task, Token),
            _ => fixture.Runner.CancelAsync(fixture.Task, Token),
        });

        Assert.Equal(ErrorCode.InvalidRequest, error.Error.Code);
        Assert.Equal(original, await fixture.ReadAsync());
        Assert.Equal(0, fixture.Locks.Acquisitions);
        Assert.Equal(0, fixture.Sources.OpenCount);
        Assert.Empty(fixture.Http.Requests);
    }

    private sealed class Fixture : IAsyncDisposable
    {
        private Fixture(TransferPlan plan, FaultStore store, FakeSources sources, FileUploadRunnerOptions options)
        {
            Plan = plan;
            Store = store;
            Sources = sources;
            Files = new(plan.Remote);
            Http = new(this, sources.Bytes.Length);
            Runner = new(store, Leases, sources, Locks, Files, Http, options);
        }

        internal TransferPlan Plan { get; }
        internal TransferTaskRef Task => Plan.Reference;
        internal FaultStore Store { get; }
        internal FakeSources Sources { get; }
        internal FakeLeases Leases { get; } = new();
        internal FakeLocks Locks { get; } = new();
        internal FakeFiles Files { get; }
        internal TusHandler Http { get; }
        internal FileUploadRunner Runner { get; }

        internal static async Task<Fixture> CreateAsync(FileConflictPolicy conflict = FileConflictPolicy.Fail,
            byte[]? data = null, FileUploadRunnerOptions? options = null, TransferDirection direction = TransferDirection.Upload)
        {
            var inner = new InMemoryProfileStore();
            var saved = new SavedConnection(new(Guid.NewGuid(), Guid.NewGuid(), "runner fixture", new("https://fixture.invalid/"),
                NetworkMode.SystemNetwork, new(ProxyMode.Direct)), AuthenticationMode.Password, "fixture", "7", false, DateTimeOffset.UtcNow);
            await inner.SaveAsync(saved, Token);
            var plan = new TransferPlan(new(saved.Account, Guid.NewGuid()), direction,
                new(saved.Account, "/fixture.bin", "fixture.bin", "/fixture.bin"), @"C:\synthetic-upload\fixture.bin", conflict);
            await inner.CreateTransferAsync(plan, Token);
            return new(plan, new(inner), new(data ?? Data), options ?? SmallChunks);
        }

        internal async Task<TransferTaskRecord> ReadAsync() => Assert.IsType<TransferTaskRecord>(await Store.GetTransferAsync(Task, Token));

        internal async Task SeedUnknownAsync(long confirmed)
        {
            var record = await ReadAsync();
            var claimed = await Store.ClaimTransferAsync(Task, record.Revision, Guid.NewGuid(), recovery: false, Token);
            await Store.SaveTransferCheckpointAsync(claimed.Run!.Value, claimed.Revision,
                new(TransferPhase.OutcomeUnknown, Sources.Snapshot, confirmed, Uncertainty: TransferUncertainty.RemoteWrite), releaseRun: true, Token);
        }

        public ValueTask DisposeAsync() => Runner.DisposeAsync();
    }

    private sealed class FaultStore(InMemoryProfileStore inner) : ITransferTaskStore
    {
        internal InMemoryProfileStore Inner { get; } = inner;
        internal Func<TransferRunToken, long, TransferCheckpoint, bool, CancellationToken, Task>? BeforeSave { get; set; }
        public bool IsPersistent => false;
        public Task<TransferTaskRecord> CreateTransferAsync(TransferPlan plan, CancellationToken cancellationToken) => Inner.CreateTransferAsync(plan, cancellationToken);
        public Task<TransferTaskRecord?> GetTransferAsync(TransferTaskRef task, CancellationToken cancellationToken) => Inner.GetTransferAsync(task, cancellationToken);
        public Task<TransferTaskPage> ListTransfersAsync(AccountKey account, string? cursor, int limit, CancellationToken cancellationToken) =>
            Inner.ListTransfersAsync(account, cursor, limit, cancellationToken);
        public Task<bool> HasProtectedTransfersAsync(AccountKey account, CancellationToken cancellationToken) => Inner.HasProtectedTransfersAsync(account, cancellationToken);
        public Task<TransferTaskRecord> SetTransferIntentAsync(TransferTaskRef task, long expectedRevision, TransferIntent intent, CancellationToken cancellationToken) =>
            Inner.SetTransferIntentAsync(task, expectedRevision, intent, cancellationToken);
        public Task<TransferTaskRecord> ClaimTransferAsync(TransferTaskRef task, long expectedRevision, Guid runId, bool recovery, CancellationToken cancellationToken) =>
            Inner.ClaimTransferAsync(task, expectedRevision, runId, recovery, cancellationToken);
        public async Task<TransferTaskRecord> SaveTransferCheckpointAsync(TransferRunToken run, long expectedRevision,
            TransferCheckpoint checkpoint, bool releaseRun, CancellationToken cancellationToken)
        {
            if (BeforeSave is { } before) await before(run, expectedRevision, checkpoint, releaseRun, cancellationToken);
            return await Inner.SaveTransferCheckpointAsync(run, expectedRevision, checkpoint, releaseRun, cancellationToken);
        }
    }

    private sealed class FakeSources(byte[] bytes) : IUploadSourceProvider
    {
        internal byte[] Bytes { get; } = bytes;
        internal TransferSourceSnapshot Snapshot { get; } = new(bytes.LongLength, new DateTimeOffset(2026, 10, 12, 0, 0, 0, TimeSpan.Zero), NativeFileIdentity: "fixture-volume:file-1");
        internal ConcurrentBag<FakeSource> Opened { get; } = new();
        internal int OpenCount;
        internal bool FailOpen { get; set; }
        internal bool Changed { get; set; }
        public Task<IUploadSource> OpenAsync(string fullPath, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Interlocked.Increment(ref OpenCount);
            if (FailOpen) throw new FilewayException(new(ErrorCode.SourceChanged, fullPath));
            var source = new FakeSource(this);
            Opened.Add(source);
            return Task.FromResult<IUploadSource>(source);
        }
    }

    private sealed class FakeSource(FakeSources owner) : IUploadSource
    {
        private readonly MemoryStream _content = new(owner.Bytes, writable: false);
        private int _disposed;
        internal bool Disposed => Volatile.Read(ref _disposed) != 0;
        public Stream Content => _content;
        public TransferSourceSnapshot Snapshot => owner.Snapshot;
        public Task VerifyAsync(TransferSourceSnapshot expected, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (owner.Changed || expected != Snapshot) throw new FilewayException(new(ErrorCode.SourceChanged, @"private C:\synthetic-upload\fixture.bin"));
            return Task.CompletedTask;
        }
        public ValueTask DisposeAsync()
        {
            if (Interlocked.Exchange(ref _disposed, 1) == 0) _content.Dispose();
            return ValueTask.CompletedTask;
        }
    }

    private sealed class FakeLocks : ITransferExecutionLockProvider
    {
        private readonly object _gate = new();
        private readonly HashSet<TransferTaskRef> _held = [];
        internal int Acquisitions;
        internal bool IsHeld(TransferTaskRef task) { lock (_gate) return _held.Contains(task); }
        public Task<ITransferExecutionLock> AcquireAsync(TransferTaskRef task, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            lock (_gate)
            {
                if (!_held.Add(task)) throw new FilewayException(new(ErrorCode.Busy, "fixture lock busy"));
                Acquisitions++;
            }
            return Task.FromResult<ITransferExecutionLock>(new FakeLock(task, () => { lock (_gate) _held.Remove(task); }));
        }
    }

    private sealed class FakeLock(TransferTaskRef task, Action release) : ITransferExecutionLock
    {
        private int _disposed;
        public TransferTaskRef Task { get; } = task;
        public ValueTask DisposeAsync()
        {
            if (Interlocked.Exchange(ref _disposed, 1) == 0) release();
            return ValueTask.CompletedTask;
        }
    }

    private sealed class FakeLeases : IUploadLeaseProvider
    {
        private int _next;
        internal ConcurrentQueue<UploadLeaseRequest> Requests { get; } = new();
        internal ConcurrentQueue<FakeLease> Issued { get; } = new();
        internal Action<FakeLease>? Mutate { get; set; }
        internal Func<CancellationToken, Task<UploadLeaseStatistics?>>? StatisticsReader { get; set; }
        public Task<IUploadLease> AcquireUploadAsync(UploadLeaseRequest request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Requests.Enqueue(request);
            var lease = new FakeLease(request, Interlocked.Increment(ref _next));
            lease.StatisticsReader = StatisticsReader;
            Mutate?.Invoke(lease);
            Issued.Enqueue(lease);
            return Task.FromResult<IUploadLease>(lease);
        }
    }

    private sealed class FakeLease(UploadLeaseRequest request, int ordinal) : IUploadLease
    {
        private int _disposed;
        internal bool Disposed => Volatile.Read(ref _disposed) != 0;
        public RemoteResourceRef Resource { get; set; } = request.Target;
        public Uri Address { get; set; } = new("http://127.0.0.1:32450/stream/fixture" + ordinal.ToString(CultureInfo.InvariantCulture));
        public Guid TransferId { get; set; } = request.TransferId;
        public long Length { get; set; } = request.Length;
        public bool IsResume { get; set; } = request.Resume;
        internal Func<CancellationToken, Task<UploadLeaseStatistics?>>? StatisticsReader { get; set; }
        public Task<UploadLeaseStatistics?> ReadUploadStatisticsAsync(CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (!Disposed && StatisticsReader is { } read) return read(cancellationToken);
            return Task.FromResult<UploadLeaseStatistics?>(Disposed ? null : new(0, null, 0, Length, 0, TimeSpan.Zero, false));
        }
        public ValueTask DisposeAsync() { Interlocked.Exchange(ref _disposed, 1); return ValueTask.CompletedTask; }
    }

    private sealed class FakeFiles(RemoteResourceRef target) : IFileRepository
    {
        internal bool Exists { get; set; }
        internal ErrorInfo? Failure { get; set; }
        internal int Reads;
        public Task<RemoteFile> GetAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Interlocked.Increment(ref Reads);
            if (Failure is { } failure) throw new FilewayException(failure);
            if (!Exists) throw new FilewayException(new(ErrorCode.SourceChanged, "fixture missing", 404));
            return Task.FromResult(new RemoteFile(target, false, 1, null));
        }
        public Task<RemoteResourceRef> GetRootAsync(ConnectionSession session, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<DirectoryPage> ListAsync(RemoteResourceRef directory, DirectoryQuery query, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<FileOperationResult> CreateDirectoryAsync(RemoteResourceRef parent, string name, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<FileOperationResult> RenameAsync(RemoteResourceRef resource, string name, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<FileOperationResult> TrashAsync(IReadOnlyList<RemoteResourceRef> resources, CancellationToken cancellationToken) => throw new NotSupportedException();
    }

    private sealed record ObservedRequest(string Method, long? Offset, byte[] Body, long? ConfirmedBeforeSend);

    private sealed class TusHandler(Fixture fixture, int length) : HttpMessageHandler
    {
        internal ConcurrentQueue<ObservedRequest> Requests { get; } = new();
        internal byte[] RemoteBytes { get; } = new byte[length];
        internal long RemoteOffset { get; set; }
        internal HttpStatusCode HeadStatus { get; set; } = HttpStatusCode.OK;
        internal HttpStatusCode DeleteStatus { get; set; } = HttpStatusCode.NoContent;
        internal Func<CancellationToken, Task<HttpResponseMessage>?>? AfterPatch { get; set; }
        internal Func<CancellationToken, Task<HttpResponseMessage>>? BeforeHead { get; set; }
        internal Action? AfterHead { get; set; }
        internal TimeSpan PatchDelay { get; set; }

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var durable = await fixture.Store.GetTransferAsync(fixture.Task, cancellationToken);
            Assert.NotNull(durable);
            Assert.NotNull(durable.Run);
            Assert.Equal(TransferUncertainty.RemoteWrite, durable.Checkpoint.Uncertainty);
            Assert.Equal(fixture.Sources.Snapshot, durable.Checkpoint.Source);
            if (request.Method == HttpMethod.Post || request.Method == HttpMethod.Patch)
                Assert.Contains(fixture.Sources.Opened, source => !source.Disposed);
            Assert.Equal("1.0.0", request.Headers.GetValues("Tus-Resumable").Single());
            byte[] body = request.Content is null ? Array.Empty<byte>() : await request.Content.ReadAsByteArrayAsync(cancellationToken);
            long? offset = request.Headers.TryGetValues("Upload-Offset", out var values)
                ? long.Parse(values.Single(), CultureInfo.InvariantCulture) : null;
            Requests.Enqueue(new(request.Method.Method, offset, body, durable.Checkpoint.ConfirmedOffset));
            if (request.Method == HttpMethod.Post)
            {
                Assert.Empty(body);
                Assert.Equal(0, request.Content!.Headers.ContentLength);
                Assert.Equal(length.ToString(CultureInfo.InvariantCulture), request.Headers.GetValues("Upload-Length").Single());
                var created = Response(HttpStatusCode.Created);
                created.Headers.Location = request.RequestUri;
                return created;
            }
            if (request.Method == HttpMethod.Head)
            {
                if (BeforeHead is { } before) return await before(cancellationToken);
                var response = Response(HeadStatus);
                if (HeadStatus == HttpStatusCode.OK)
                {
                    response.Headers.Add("Upload-Length", length.ToString(CultureInfo.InvariantCulture));
                    response.Headers.Add("Upload-Offset", RemoteOffset.ToString(CultureInfo.InvariantCulture));
                }
                AfterHead?.Invoke();
                return response;
            }
            if (request.Method == HttpMethod.Patch)
            {
                Assert.Equal(RemoteOffset, offset);
                Assert.Equal(body.LongLength, request.Content!.Headers.ContentLength);
                Assert.Equal("application/offset+octet-stream", request.Content.Headers.ContentType!.MediaType);
                body.CopyTo(RemoteBytes, checked((int)RemoteOffset));
                RemoteOffset += body.Length;
                if (PatchDelay > TimeSpan.Zero) await Task.Delay(PatchDelay, cancellationToken);
                if (AfterPatch?.Invoke(cancellationToken) is { } after) return await after;
                var response = Response(HttpStatusCode.NoContent);
                response.Headers.Add("Upload-Offset", RemoteOffset.ToString(CultureInfo.InvariantCulture));
                return response;
            }
            Assert.Equal(HttpMethod.Delete, request.Method);
            return Response(DeleteStatus);
        }

        private static HttpResponseMessage Response(HttpStatusCode status)
        {
            var response = new HttpResponseMessage(status);
            response.Headers.Add("Tus-Resumable", "1.0.0");
            return response;
        }
    }
}
