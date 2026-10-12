using System.Collections.Concurrent;
using System.Globalization;
using System.Net;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;
using Fileway.Infrastructure.Transfers;

namespace Fileway.Infrastructure.Tests.Transfers;

public sealed class UploadCoordinatorRunnerIntegrationTests
{
    private static readonly byte[] Data = [3, 1, 4, 1, 5, 9, 2, 6, 5];
    private static readonly string[] CompletionMethods = ["POST", "HEAD", "PATCH", "PATCH", "PATCH", "HEAD"];
    private static readonly string[] InterruptedMethods = ["POST", "HEAD", "PATCH"];
    private static readonly long[] ChunkOffsets = [0, 4, 8];
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Fact]
    public async Task EnqueueRunsRealUploaderToPublicationAndTerminalCancelDoesNoMoreIo()
    {
        await using var fixture = await Fixture.CreateAsync();
        var completed = new TaskCompletionSource<UploadQueueUpdate>(TaskCreationOptions.RunContinuationsAsynchronously);
        fixture.Coordinator.TaskChanged += (_, update) =>
        {
            if (update.Task.Task == fixture.Task && update.Task.Phase == TransferPhase.Completed)
                completed.TrySetResult(update);
        };
        Assert.Empty(fixture.Http.Requests);
        Assert.Empty(fixture.Sources.Opened);

        var queued = await fixture.Coordinator.EnqueueAsync(fixture.Activation, fixture.Task, Token);
        var update = await completed.Task.WaitAsync(TimeSpan.FromSeconds(10), Token);
        var durable = await fixture.ReadAsync(Token);

        Assert.Equal(TransferPhase.Queued, queued.Phase);
        Assert.Equal(fixture.Activation, update.Activation);
        Assert.Equal(fixture.Task, update.Task.Task);
        Assert.Equal(TransferPhase.Completed, update.Task.Phase);
        Assert.True(update.Task.RemotePublished);
        Assert.False(update.Task.RemoteCanceled);
        Assert.Equal(Data.LongLength, update.Task.ConfirmedOffset);
        Assert.Equal(Data.LongLength, update.Task.TotalBytes);
        Assert.Equal(TransferUncertainty.None, update.Task.Uncertainty);
        Assert.Null(update.Task.Error);
        Assert.Equal(durable.Revision, update.Task.Revision);
        Assert.Equal(1, update.Task.Generation);
        Assert.Null(durable.Run);
        Assert.Equal(CompletionMethods, fixture.Http.Requests.Select(request => request.Method));
        Assert.Equal(ChunkOffsets, fixture.Http.Requests.Where(request => request.Method == "PATCH").Select(request => request.Offset!.Value));
        Assert.Equal(Data, fixture.Http.RemoteBytes);
        var leases = fixture.Leases.Requests.ToArray();
        Assert.Equal(2, leases.Length);
        Assert.False(leases[0].Resume);
        Assert.True(leases[1].Resume);
        Assert.All(leases, request => Assert.Equal(fixture.Task.Id, request.TransferId));
        AssertReleased(fixture);

        var canceled = await fixture.Coordinator.CancelAsync(fixture.Activation, fixture.Task, Token);

        Assert.Equal(update.Task, canceled);
        Assert.Equal(durable, await fixture.ReadAsync(Token));
        Assert.Equal(CompletionMethods, fixture.Http.Requests.Select(request => request.Method));
        Assert.Equal(2, fixture.Leases.Requests.Count);
        Assert.Equal(1, fixture.Locks.Acquisitions);
        Assert.Single(fixture.Sources.Opened);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task PauseOrStopPersistsIntentAndWaitsForRealRunnerCleanupWithoutDelete(bool stopAll)
    {
        await using var fixture = await Fixture.CreateAsync();
        using var caller = CancellationTokenSource.CreateLinkedTokenSource(Token);
        fixture.Http.BlockPatchAcknowledgement = true;
        fixture.Sources.HoldDisposal = true;
        await fixture.Coordinator.EnqueueAsync(fixture.Activation, fixture.Task, Token);
        await fixture.Http.PatchEntered.Task.WaitAsync(TimeSpan.FromSeconds(10), Token);
        var inFlight = await fixture.ReadAsync(Token);
        Assert.Equal(0, inFlight.Checkpoint.ConfirmedOffset);
        Assert.Equal(TransferUncertainty.RemoteWrite, inFlight.Checkpoint.Uncertainty);
        Assert.NotNull(inFlight.Run);

        Task<UploadTaskSnapshot>? pause = null;
        Task stopping = stopAll
            ? fixture.Coordinator.StopAndDrainAsync(fixture.Activation, caller.Token)
            : (pause = fixture.Coordinator.PauseAsync(fixture.Activation, fixture.Task, caller.Token));
        var intentAtStop = await fixture.Http.PatchCanceledIntent.Task.WaitAsync(TimeSpan.FromSeconds(10), Token);
        await fixture.Sources.DisposalEntered.Task.WaitAsync(TimeSpan.FromSeconds(10), Token);
        await caller.CancelAsync();

        Assert.Equal(TransferIntent.Pause, intentAtStop);
        Assert.False(stopping.IsCompleted);
        Assert.True(fixture.Locks.IsHeld(fixture.Task));
        Assert.False(Assert.Single(fixture.Sources.Opened).Disposed);
        // A released durable run is not proof that the real source handle and execution lock are released.
        Assert.Null((await fixture.ReadAsync(Token)).Run);
        fixture.Sources.DisposalRelease.TrySetResult();
        await stopping.WaitAsync(TimeSpan.FromSeconds(10), Token);
        var durable = await fixture.ReadAsync(Token);

        Assert.Equal(TransferIntent.Pause, durable.Intent);
        Assert.Equal(TransferPhase.OutcomeUnknown, durable.Checkpoint.Phase);
        Assert.Equal(0, durable.Checkpoint.ConfirmedOffset);
        Assert.Equal(TransferUncertainty.RemoteWrite, durable.Checkpoint.Uncertainty);
        Assert.False(durable.Checkpoint.RemotePublished);
        Assert.False(durable.Checkpoint.RemoteCanceled);
        Assert.Null(durable.Run);
        Assert.Equal(4, fixture.Http.RemoteOffset);
        Assert.Equal(InterruptedMethods, fixture.Http.Requests.Select(request => request.Method));
        AssertReleased(fixture);
        if (pause is not null)
        {
            var snapshot = await pause;
            Assert.Equal(durable.Checkpoint.Phase, snapshot.Phase);
            Assert.Equal(durable.Revision, snapshot.Revision);
        }
        if (stopAll)
        {
            var error = await Assert.ThrowsAsync<FilewayException>(() =>
                fixture.Coordinator.EnqueueAsync(fixture.Activation, fixture.Task, Token));
            Assert.Equal(ErrorCode.SourceChanged, error.Error.Code);
        }
    }

    private static void AssertReleased(Fixture fixture)
    {
        Assert.True(Assert.Single(fixture.Sources.Opened).Disposed);
        Assert.NotEmpty(fixture.Leases.Issued);
        Assert.All(fixture.Leases.Issued, lease => Assert.True(lease.Disposed));
        Assert.False(fixture.Locks.IsHeld(fixture.Task));
    }

    private sealed class Fixture : IAsyncDisposable
    {
        private Fixture(InMemoryProfileStore store, TransferPlan plan)
        {
            Store = store;
            Task = plan.Reference;
            Http = new(this);
            var runner = new FileUploadRunner(store, Leases, Sources, Locks, new UnusedFiles(), Http,
                new FileUploadRunnerOptions { ChunkSizeBytes = 4 });
            Coordinator = new(store, runner, new UploadCoordinatorOptions { MaximumConcurrentUploads = 1 });
        }

        internal InMemoryProfileStore Store { get; }
        internal TransferTaskRef Task { get; }
        internal UploadActivationToken Activation { get; private set; }
        internal UploadCoordinator Coordinator { get; }
        internal FakeSources Sources { get; } = new();
        internal FakeLocks Locks { get; } = new();
        internal FakeLeases Leases { get; } = new();
        internal TusHandler Http { get; }

        internal static async Task<Fixture> CreateAsync()
        {
            var store = new InMemoryProfileStore();
            var saved = new SavedConnection(new(Guid.NewGuid(), Guid.NewGuid(), "coordinator fixture", new("https://fixture.invalid/"),
                NetworkMode.SystemNetwork, new(ProxyMode.Direct)), AuthenticationMode.Password, "fixture", "7", false, DateTimeOffset.UtcNow);
            await store.SaveAsync(saved, Token);
            var plan = new TransferPlan(new(saved.Account, Guid.NewGuid()), TransferDirection.Upload,
                new(saved.Account, "/queue.bin", "queue.bin", "/queue.bin"), @"C:\synthetic-coordinator\queue.bin");
            await store.CreateTransferAsync(plan, Token);
            var fixture = new Fixture(store, plan);
            fixture.Activation = await fixture.Coordinator.ActivateAsync(new(Guid.NewGuid(), saved.Account, "fixture",
                new(true, true, true, true, true, false), new()), Token);
            return fixture;
        }

        internal async Task<TransferTaskRecord> ReadAsync(CancellationToken cancellationToken) =>
            Assert.IsType<TransferTaskRecord>(await Store.GetTransferAsync(Task, cancellationToken));

        public ValueTask DisposeAsync()
        {
            Sources.DisposalRelease.TrySetResult();
            Http.PatchAcknowledgementRelease.TrySetResult();
            return Coordinator.DisposeAsync();
        }
    }

    private sealed class FakeSources : IUploadSourceProvider
    {
        internal TransferSourceSnapshot Snapshot { get; } = new(Data.LongLength,
            new DateTimeOffset(2026, 10, 12, 0, 0, 0, TimeSpan.Zero), NativeFileIdentity: "fixture-volume:queue-1");
        internal ConcurrentBag<FakeSource> Opened { get; } = new();
        internal bool HoldDisposal { get; set; }
        internal TaskCompletionSource DisposalEntered { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal TaskCompletionSource DisposalRelease { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public Task<IUploadSource> OpenAsync(string fullPath, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var source = new FakeSource(this);
            Opened.Add(source);
            return Task.FromResult<IUploadSource>(source);
        }
    }

    private sealed class FakeSource(FakeSources owner) : IUploadSource
    {
        private readonly MemoryStream _content = new(Data, writable: false);
        private int _state;
        internal bool Disposed => Volatile.Read(ref _state) == 2;
        public Stream Content => _content;
        public TransferSourceSnapshot Snapshot => owner.Snapshot;
        public Task VerifyAsync(TransferSourceSnapshot expected, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (expected != Snapshot) throw new FilewayException(new(ErrorCode.SourceChanged, "fixture source changed"));
            return Task.CompletedTask;
        }
        public async ValueTask DisposeAsync()
        {
            if (Interlocked.CompareExchange(ref _state, 1, 0) != 0) return;
            owner.DisposalEntered.TrySetResult();
            if (owner.HoldDisposal) await owner.DisposalRelease.Task;
            _content.Dispose();
            Volatile.Write(ref _state, 2);
        }
    }

    private sealed class FakeLocks : ITransferExecutionLockProvider
    {
        private readonly object _gate = new();
        private readonly HashSet<TransferTaskRef> _held = [];
        internal int Acquisitions { get; private set; }
        internal bool IsHeld(TransferTaskRef task) { lock (_gate) return _held.Contains(task); }
        public Task<ITransferExecutionLock> AcquireAsync(TransferTaskRef task, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            lock (_gate)
            {
                if (!_held.Add(task)) throw new FilewayException(new(ErrorCode.Busy, "fixture lock held"));
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
        public Task<IUploadLease> AcquireUploadAsync(UploadLeaseRequest request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Requests.Enqueue(request);
            var lease = new FakeLease(request, Interlocked.Increment(ref _next));
            Issued.Enqueue(lease);
            return Task.FromResult<IUploadLease>(lease);
        }
    }

    private sealed class FakeLease(UploadLeaseRequest request, int ordinal) : IUploadLease
    {
        private int _disposed;
        internal bool Disposed => Volatile.Read(ref _disposed) != 0;
        public RemoteResourceRef Resource { get; } = request.Target;
        public Uri Address { get; } = new("http://127.0.0.1:32450/stream/queue" + ordinal.ToString(CultureInfo.InvariantCulture));
        public Guid TransferId { get; } = request.TransferId;
        public long Length { get; } = request.Length;
        public bool IsResume { get; } = request.Resume;
        public Task<UploadLeaseStatistics?> ReadUploadStatisticsAsync(CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return Task.FromResult<UploadLeaseStatistics?>(Disposed ? null : new(0, null, 0, Length, 0, TimeSpan.Zero, false));
        }
        public ValueTask DisposeAsync() { Interlocked.Exchange(ref _disposed, 1); return ValueTask.CompletedTask; }
    }

    private sealed class UnusedFiles : IFileRepository
    {
        public Task<RemoteFile> GetAsync(RemoteResourceRef resource, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<RemoteResourceRef> GetRootAsync(ConnectionSession session, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<DirectoryPage> ListAsync(RemoteResourceRef directory, DirectoryQuery query, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<FileOperationResult> CreateDirectoryAsync(RemoteResourceRef parent, string name, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<FileOperationResult> RenameAsync(RemoteResourceRef resource, string name, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<FileOperationResult> TrashAsync(IReadOnlyList<RemoteResourceRef> resources, CancellationToken cancellationToken) => throw new NotSupportedException();
    }

    private sealed record ObservedRequest(string Method, long? Offset);

    private sealed class TusHandler(Fixture fixture) : HttpMessageHandler
    {
        internal ConcurrentQueue<ObservedRequest> Requests { get; } = new();
        internal byte[] RemoteBytes { get; } = new byte[Data.Length];
        internal long RemoteOffset { get; private set; }
        internal bool BlockPatchAcknowledgement { get; set; }
        internal TaskCompletionSource PatchEntered { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal TaskCompletionSource PatchAcknowledgementRelease { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal TaskCompletionSource<TransferIntent> PatchCanceledIntent { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var durable = await fixture.ReadAsync(cancellationToken);
            Assert.NotNull(durable.Run);
            Assert.Equal(TransferUncertainty.RemoteWrite, durable.Checkpoint.Uncertainty);
            Assert.Equal(fixture.Sources.Snapshot, durable.Checkpoint.Source);
            Assert.True(fixture.Locks.IsHeld(fixture.Task));
            Assert.Equal("1.0.0", request.Headers.GetValues("Tus-Resumable").Single());
            long? offset = request.Headers.TryGetValues("Upload-Offset", out var values)
                ? long.Parse(values.Single(), CultureInfo.InvariantCulture) : null;
            Requests.Enqueue(new(request.Method.Method, offset));
            if (request.Method == HttpMethod.Post)
            {
                Assert.Equal(Data.Length.ToString(CultureInfo.InvariantCulture), request.Headers.GetValues("Upload-Length").Single());
                var created = Response(HttpStatusCode.Created);
                created.Headers.Location = request.RequestUri;
                return created;
            }
            if (request.Method == HttpMethod.Head)
            {
                var head = Response(HttpStatusCode.OK);
                head.Headers.Add("Upload-Length", Data.Length.ToString(CultureInfo.InvariantCulture));
                head.Headers.Add("Upload-Offset", RemoteOffset.ToString(CultureInfo.InvariantCulture));
                return head;
            }
            Assert.Equal(HttpMethod.Patch, request.Method);
            Assert.Equal(RemoteOffset, offset);
            byte[] body = await request.Content!.ReadAsByteArrayAsync(cancellationToken);
            body.CopyTo(RemoteBytes, checked((int)RemoteOffset));
            RemoteOffset += body.Length;
            PatchEntered.TrySetResult();
            if (BlockPatchAcknowledgement)
            {
                try { await PatchAcknowledgementRelease.Task.WaitAsync(cancellationToken); }
                catch (OperationCanceledException)
                {
                    PatchCanceledIntent.TrySetResult((await fixture.ReadAsync(CancellationToken.None)).Intent);
                    throw;
                }
            }
            var patched = Response(HttpStatusCode.NoContent);
            patched.Headers.Add("Upload-Offset", RemoteOffset.ToString(CultureInfo.InvariantCulture));
            return patched;
        }

        private static HttpResponseMessage Response(HttpStatusCode status)
        {
            var response = new HttpResponseMessage(status);
            response.Headers.Add("Tus-Resumable", "1.0.0");
            return response;
        }
    }
}
