using System.Collections.Concurrent;
using System.Globalization;
using System.Net;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;
using Fileway.Infrastructure.Transfers;

namespace Fileway.Infrastructure.Tests.Transfers;

public sealed class FileUploadRunnerVerificationTests
{
    private static readonly byte[] Data = [8, 6, 7, 5, 3, 0, 9];
    private static readonly string[] UnknownDeleteMethods = ["HEAD", "DELETE", "HEAD"];
    private static readonly string[] InvalidDeleteMethods = ["HEAD", "DELETE"];
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Theory]
    [InlineData("2.0.0", false)]
    [InlineData("1.0.0", true)]
    public async Task Delete204WithConflictingOrDuplicateTusVersionKeepsUnknownWithoutAutomaticRetry(string version, bool duplicate)
    {
        await using var fixture = await Fixture.CreateAsync();
        await fixture.SeedUnknownAsync(2);
        fixture.Http.Offset = 2;
        fixture.Http.DeleteTusVersionOverride = version;
        fixture.Http.DuplicateDeleteTusVersion = duplicate;

        var result = await fixture.Runner.CancelAsync(fixture.Task, Token);

        Assert.Equal(TransferPhase.OutcomeUnknown, result.Checkpoint.Phase);
        Assert.Equal(TransferIntent.Cancel, result.Intent);
        Assert.Equal(TransferUncertainty.RemoteWrite, result.Checkpoint.Uncertainty);
        Assert.Equal(2, result.Checkpoint.ConfirmedOffset);
        Assert.False(result.Checkpoint.RemoteCanceled);
        Assert.False(result.Checkpoint.RemotePublished);
        Assert.Null(result.Run);
        Assert.Equal(ErrorCode.ProtocolMismatch, result.Checkpoint.Error!.Code);
        Assert.True(await fixture.Store.HasProtectedTransfersAsync(fixture.Task.Account, Token));
        Assert.Equal(InvalidDeleteMethods, fixture.Http.Methods);
        Assert.Equal(result, await fixture.ReadAsync());
        Assert.Equal(0, fixture.Sources.OpenCount);
        Assert.All(fixture.Leases.Issued, lease => Assert.True(lease.Disposed));
        Assert.False(fixture.Locks.Held);
    }

    [Fact]
    public async Task LostDeleteAcknowledgementFollowedByGoneHeadRemainsUnknownWithoutAnotherDelete()
    {
        await using var fixture = await Fixture.CreateAsync();
        await fixture.SeedUnknownAsync(2);
        fixture.Http.Offset = 2;
        fixture.Http.LoseDeleteAcknowledgement = true;

        var first = await fixture.Runner.CancelAsync(fixture.Task, Token);
        fixture.Http.HeadStatus = HttpStatusCode.Gone;
        var second = await fixture.Runner.CancelAsync(fixture.Task, Token);

        Assert.Equal(TransferPhase.OutcomeUnknown, first.Checkpoint.Phase);
        Assert.Equal(TransferPhase.OutcomeUnknown, second.Checkpoint.Phase);
        Assert.Equal(TransferIntent.Cancel, second.Intent);
        Assert.Equal(TransferUncertainty.RemoteWrite, second.Checkpoint.Uncertainty);
        Assert.Equal(2, second.Checkpoint.ConfirmedOffset);
        Assert.False(second.Checkpoint.RemoteCanceled);
        Assert.False(second.Checkpoint.RemotePublished);
        Assert.Null(second.Run);
        Assert.Equal(ErrorCode.OutcomeUnknown, second.Checkpoint.Error!.Code);
        Assert.Equal(410, second.Checkpoint.Error.HttpStatus);
        Assert.Equal(UnknownDeleteMethods, fixture.Http.Methods);
        Assert.Equal(0, fixture.Sources.OpenCount);
        Assert.All(fixture.Leases.Requests, request => Assert.True(request.Resume));
        Assert.All(fixture.Leases.Issued, lease => Assert.True(lease.Disposed));
        Assert.False(fixture.Locks.Held);
    }

    [Theory]
    [InlineData(TransferIntent.Pause)]
    [InlineData(TransferIntent.Cancel)]
    public async Task FullHeadEvidenceSurvivesLateControlCasAndCannotBeTurnedIntoRemoteDelete(TransferIntent intent)
    {
        await using var fixture = await Fixture.CreateAsync();
        await fixture.SeedUnknownAsync(2);
        fixture.Http.Offset = Data.Length;
        int raced = 0;
        fixture.Store.BeforeSave = async (run, revision, checkpoint, cancellationToken) =>
        {
            if (checkpoint.Phase == TransferPhase.Running && checkpoint.ConfirmedOffset == Data.Length &&
                Interlocked.CompareExchange(ref raced, 1, 0) == 0)
                await fixture.Store.Inner.SetTransferIntentAsync(run.Task, revision, intent, cancellationToken);
        };

        var completed = await fixture.Runner.RunAsync(fixture.Task, recovery: true, Token);
        var afterCancel = await fixture.Runner.CancelAsync(fixture.Task, Token);

        Assert.Equal(1, raced);
        Assert.Equal(TransferPhase.Completed, completed.Checkpoint.Phase);
        Assert.Equal(intent, completed.Intent);
        Assert.Equal(Data.Length, completed.Checkpoint.ConfirmedOffset);
        Assert.True(completed.Checkpoint.RemotePublished);
        Assert.False(completed.Checkpoint.RemoteCanceled);
        Assert.Equal(TransferUncertainty.None, completed.Checkpoint.Uncertainty);
        Assert.Equal(completed, afterCancel);
        Assert.Equal("HEAD", Assert.Single(fixture.Http.Methods));
        Assert.True(Assert.Single(fixture.Leases.Requests).Resume);
        Assert.False(fixture.Locks.Held);
    }

    [Theory]
    [InlineData("run")]
    [InlineData("pause")]
    [InlineData("cancel")]
    public async Task DownloadWithExistingPauseIntentIsRejectedBeforeAnyMutationOrCapability(string action)
    {
        await using var fixture = await Fixture.CreateAsync(TransferDirection.Download);
        var created = await fixture.ReadAsync();
        var original = await fixture.Store.Inner.SetTransferIntentAsync(fixture.Task, created.Revision, TransferIntent.Pause, Token);

        var error = await Assert.ThrowsAsync<FilewayException>(() => action switch
        {
            "run" => fixture.Runner.RunAsync(fixture.Task, recovery: true, Token),
            "pause" => fixture.Runner.PauseAsync(fixture.Task, Token),
            _ => fixture.Runner.CancelAsync(fixture.Task, Token),
        });

        Assert.Equal(ErrorCode.InvalidRequest, error.Error.Code);
        Assert.Equal(original, await fixture.ReadAsync());
        Assert.Equal(0, fixture.Store.IntentWrites);
        Assert.Equal(0, fixture.Store.Claims);
        Assert.Equal(0, fixture.Locks.Acquisitions);
        Assert.Equal(0, fixture.Sources.OpenCount);
        Assert.Empty(fixture.Leases.Requests);
        Assert.Empty(fixture.Http.Methods);
    }

    [Fact]
    public async Task WrongExecutionLockOwnerIsDisposedBeforeClaimSourceOrRemoteAccess()
    {
        await using var fixture = await Fixture.CreateAsync();
        var original = await fixture.ReadAsync();
        fixture.Locks.ReportedTask = fixture.Task with { Account = fixture.Task.Account with { SourceRevision = Guid.NewGuid() } };

        var error = await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.RunAsync(fixture.Task, recovery: false, Token));

        Assert.Equal(ErrorCode.ProtocolMismatch, error.Error.Code);
        Assert.Equal(original, await fixture.ReadAsync());
        Assert.Equal(0, fixture.Store.Claims);
        Assert.Equal(0, fixture.Sources.OpenCount);
        Assert.Empty(fixture.Leases.Requests);
        Assert.Empty(fixture.Http.Methods);
        Assert.False(fixture.Locks.Held);
        Assert.Equal(1, fixture.Locks.Releases);
    }

    [Fact]
    public async Task AckCannotBeSavedAgainstReplacementRunAndDoesNotReplayItsPatch()
    {
        await using var fixture = await Fixture.CreateAsync();
        await fixture.SeedUnknownAsync(0);
        TransferRunToken? replacement = null;
        fixture.Store.BeforeSave = async (run, revision, checkpoint, cancellationToken) =>
        {
            if (checkpoint.Phase == TransferPhase.Running && checkpoint.ConfirmedOffset == 4 && replacement is null)
                replacement = (await fixture.Store.Inner.ClaimTransferAsync(run.Task, revision, Guid.NewGuid(), recovery: true, cancellationToken)).Run;
        };

        var error = await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.RunAsync(fixture.Task, recovery: true, Token));

        var durable = await fixture.ReadAsync();
        Assert.Equal(ErrorCode.Conflict, error.Error.Code);
        Assert.NotNull(replacement);
        Assert.Equal(replacement, durable.Run);
        Assert.Equal(0, durable.Checkpoint.ConfirmedOffset);
        Assert.Equal(TransferUncertainty.RemoteWrite, durable.Checkpoint.Uncertainty);
        Assert.Equal(4, fixture.Http.Offset); // Bytes arrived, but the old executor cannot write new ownership's evidence.
        Assert.Equal("PATCH", Assert.Single(fixture.Http.Methods, method => method == "PATCH"));
        Assert.DoesNotContain("POST", fixture.Http.Methods);
        Assert.DoesNotContain("DELETE", fixture.Http.Methods);
        Assert.All(fixture.Leases.Issued, lease => Assert.True(lease.Disposed));
        Assert.All(fixture.Sources.Opened, source => Assert.True(source.Disposed));
        Assert.False(fixture.Locks.Held);
    }

    private sealed class Fixture : IAsyncDisposable
    {
        private Fixture(TransferPlan plan, ObservedStore store)
        {
            Plan = plan;
            Store = store;
            Http = new(this);
            Runner = new(store, Leases, Sources, Locks, new MissingFiles(), Http, new() { ChunkSizeBytes = 4 });
        }

        internal TransferPlan Plan { get; }
        internal TransferTaskRef Task => Plan.Reference;
        internal ObservedStore Store { get; }
        internal Sources Sources { get; } = new();
        internal Locks Locks { get; } = new();
        internal Leases Leases { get; } = new();
        internal Handler Http { get; }
        internal FileUploadRunner Runner { get; }

        internal static async Task<Fixture> CreateAsync(TransferDirection direction = TransferDirection.Upload)
        {
            var inner = new InMemoryProfileStore();
            var saved = new SavedConnection(new(Guid.NewGuid(), Guid.NewGuid(), "independent fixture", new("https://fixture.invalid/"),
                NetworkMode.SystemNetwork, new(ProxyMode.Direct)), AuthenticationMode.Password, "fixture", "42", false, DateTimeOffset.UtcNow);
            await inner.SaveAsync(saved, Token);
            var plan = new TransferPlan(new(saved.Account, Guid.NewGuid()), direction,
                new(saved.Account, "/independent.bin", "independent.bin", "/independent.bin"), @"C:\synthetic-independent\source.bin");
            await inner.CreateTransferAsync(plan, Token);
            return new(plan, new(inner));
        }

        internal async Task<TransferTaskRecord> ReadAsync() => Assert.IsType<TransferTaskRecord>(await Store.GetTransferAsync(Task, Token));

        internal async Task SeedUnknownAsync(long offset)
        {
            var record = await ReadAsync();
            var claimed = await Store.Inner.ClaimTransferAsync(Task, record.Revision, Guid.NewGuid(), recovery: false, Token);
            await Store.Inner.SaveTransferCheckpointAsync(claimed.Run!.Value, claimed.Revision,
                new(TransferPhase.OutcomeUnknown, Sources.Snapshot, offset, Uncertainty: TransferUncertainty.RemoteWrite), releaseRun: true, Token);
        }

        public ValueTask DisposeAsync() => Runner.DisposeAsync();
    }

    private sealed class ObservedStore(InMemoryProfileStore inner) : ITransferTaskStore
    {
        internal InMemoryProfileStore Inner { get; } = inner;
        internal Func<TransferRunToken, long, TransferCheckpoint, CancellationToken, Task>? BeforeSave { get; set; }
        internal int IntentWrites;
        internal int Claims;
        public bool IsPersistent => false;
        public Task<TransferTaskRecord> CreateTransferAsync(TransferPlan plan, CancellationToken cancellationToken) => Inner.CreateTransferAsync(plan, cancellationToken);
        public Task<TransferTaskRecord?> GetTransferAsync(TransferTaskRef task, CancellationToken cancellationToken) => Inner.GetTransferAsync(task, cancellationToken);
        public Task<TransferTaskPage> ListTransfersAsync(AccountKey account, string? cursor, int limit, CancellationToken cancellationToken) => Inner.ListTransfersAsync(account, cursor, limit, cancellationToken);
        public Task<bool> HasProtectedTransfersAsync(AccountKey account, CancellationToken cancellationToken) => Inner.HasProtectedTransfersAsync(account, cancellationToken);
        public Task<TransferTaskRecord> SetTransferIntentAsync(TransferTaskRef task, long expectedRevision, TransferIntent intent, CancellationToken cancellationToken)
        {
            Interlocked.Increment(ref IntentWrites);
            return Inner.SetTransferIntentAsync(task, expectedRevision, intent, cancellationToken);
        }
        public Task<TransferTaskRecord> ClaimTransferAsync(TransferTaskRef task, long expectedRevision, Guid runId, bool recovery, CancellationToken cancellationToken)
        {
            Interlocked.Increment(ref Claims);
            return Inner.ClaimTransferAsync(task, expectedRevision, runId, recovery, cancellationToken);
        }
        public async Task<TransferTaskRecord> SaveTransferCheckpointAsync(TransferRunToken run, long expectedRevision,
            TransferCheckpoint checkpoint, bool releaseRun, CancellationToken cancellationToken)
        {
            if (BeforeSave is { } before) await before(run, expectedRevision, checkpoint, cancellationToken);
            return await Inner.SaveTransferCheckpointAsync(run, expectedRevision, checkpoint, releaseRun, cancellationToken);
        }
    }

    private sealed class Sources : IUploadSourceProvider
    {
        internal TransferSourceSnapshot Snapshot { get; } = new(Data.Length, NativeFileIdentity: "independent-volume:file");
        internal List<Source> Opened { get; } = [];
        internal int OpenCount;
        public Task<IUploadSource> OpenAsync(string fullPath, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Interlocked.Increment(ref OpenCount);
            var source = new Source(Snapshot);
            Opened.Add(source);
            return Task.FromResult<IUploadSource>(source);
        }
    }

    private sealed class Source(TransferSourceSnapshot snapshot) : IUploadSource
    {
        private readonly MemoryStream _stream = new(Data, writable: false);
        internal bool Disposed { get; private set; }
        public Stream Content => _stream;
        public TransferSourceSnapshot Snapshot => snapshot;
        public Task VerifyAsync(TransferSourceSnapshot expected, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Assert.False(Disposed);
            Assert.Equal(snapshot, expected);
            return Task.CompletedTask;
        }
        public ValueTask DisposeAsync() { Disposed = true; _stream.Dispose(); return ValueTask.CompletedTask; }
    }

    private sealed class Locks : ITransferExecutionLockProvider
    {
        internal TransferTaskRef? ReportedTask { get; set; }
        internal bool Held { get; private set; }
        internal int Acquisitions;
        internal int Releases;
        public Task<ITransferExecutionLock> AcquireAsync(TransferTaskRef task, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Assert.False(Held);
            Held = true;
            Acquisitions++;
            return Task.FromResult<ITransferExecutionLock>(new Ownership(ReportedTask ?? task, () => { Held = false; Releases++; }));
        }
    }

    private sealed class Ownership(TransferTaskRef task, Action release) : ITransferExecutionLock
    {
        private int _disposed;
        public TransferTaskRef Task => task;
        public ValueTask DisposeAsync() { if (Interlocked.Exchange(ref _disposed, 1) == 0) release(); return ValueTask.CompletedTask; }
    }

    private sealed class Leases : IUploadLeaseProvider
    {
        internal List<UploadLeaseRequest> Requests { get; } = [];
        internal List<Lease> Issued { get; } = [];
        public Task<IUploadLease> AcquireUploadAsync(UploadLeaseRequest request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Requests.Add(request);
            var lease = new Lease(request);
            Issued.Add(lease);
            return Task.FromResult<IUploadLease>(lease);
        }
    }

    private sealed class Lease(UploadLeaseRequest request) : IUploadLease
    {
        internal bool Disposed { get; private set; }
        public RemoteResourceRef Resource => request.Target;
        public Uri Address { get; } = new("http://127.0.0.1:32451/stream/independent");
        public Guid TransferId => request.TransferId;
        public long Length => request.Length;
        public bool IsResume => request.Resume;
        public Task<UploadLeaseStatistics?> ReadUploadStatisticsAsync(CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return Task.FromResult<UploadLeaseStatistics?>(new(0, null, 0, Length, 0, TimeSpan.Zero, false));
        }
        public ValueTask DisposeAsync() { Disposed = true; return ValueTask.CompletedTask; }
    }

    private sealed class Handler(Fixture fixture) : HttpMessageHandler
    {
        internal ConcurrentQueue<string> Methods { get; } = new();
        internal long Offset { get; set; }
        internal HttpStatusCode HeadStatus { get; set; } = HttpStatusCode.OK;
        internal bool LoseDeleteAcknowledgement { get; set; }
        internal string? DeleteTusVersionOverride { get; set; }
        internal bool DuplicateDeleteTusVersion { get; set; }

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var durable = await fixture.ReadAsync();
            Assert.NotNull(durable.Run);
            Assert.True(fixture.Locks.Held);
            Assert.Equal(TransferUncertainty.RemoteWrite, durable.Checkpoint.Uncertainty);
            Methods.Enqueue(request.Method.Method);
            if (request.Method == HttpMethod.Head)
            {
                var response = Response(HeadStatus);
                if (HeadStatus == HttpStatusCode.OK)
                {
                    response.Headers.Add("Upload-Length", Data.Length.ToString(CultureInfo.InvariantCulture));
                    response.Headers.Add("Upload-Offset", Offset.ToString(CultureInfo.InvariantCulture));
                }
                return response;
            }
            if (request.Method == HttpMethod.Delete)
            {
                Assert.Equal(TransferIntent.Cancel, durable.Intent);
                if (LoseDeleteAcknowledgement) throw new HttpRequestException("Synthetic lost DELETE acknowledgement.");
                var response = Response(HttpStatusCode.NoContent);
                if (DeleteTusVersionOverride is { } version)
                {
                    response.Headers.Remove("Tus-Resumable");
                    response.Headers.Add("Tus-Resumable", version);
                }
                if (DuplicateDeleteTusVersion) response.Headers.Add("Tus-Resumable", "1.0.0");
                return response;
            }
            Assert.Equal(HttpMethod.Patch, request.Method);
            Assert.NotNull(request.Content);
            Assert.Equal(Offset.ToString(CultureInfo.InvariantCulture), Assert.Single(request.Headers.GetValues("Upload-Offset")));
            byte[] body = await request.Content.ReadAsByteArrayAsync(cancellationToken);
            Assert.Equal(Data.AsSpan(checked((int)Offset), body.Length).ToArray(), body);
            Offset += body.Length;
            var acknowledged = Response(HttpStatusCode.NoContent);
            acknowledged.Headers.Add("Upload-Offset", Offset.ToString(CultureInfo.InvariantCulture));
            return acknowledged;
        }

        private static HttpResponseMessage Response(HttpStatusCode status)
        {
            var response = new HttpResponseMessage(status);
            response.Headers.Add("Tus-Resumable", "1.0.0");
            return response;
        }
    }

    private sealed class MissingFiles : IFileRepository
    {
        public Task<RemoteFile> GetAsync(RemoteResourceRef resource, CancellationToken cancellationToken) => throw new FilewayException(new(ErrorCode.SourceChanged, "fixture missing", 404));
        public Task<RemoteResourceRef> GetRootAsync(ConnectionSession session, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<DirectoryPage> ListAsync(RemoteResourceRef directory, DirectoryQuery query, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<FileOperationResult> CreateDirectoryAsync(RemoteResourceRef parent, string name, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<FileOperationResult> RenameAsync(RemoteResourceRef resource, string name, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<FileOperationResult> TrashAsync(IReadOnlyList<RemoteResourceRef> resources, CancellationToken cancellationToken) => throw new NotSupportedException();
    }
}
