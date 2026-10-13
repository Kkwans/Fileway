using System.Collections.Concurrent;
using System.Globalization;
using System.Net;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;
using Fileway.Infrastructure.Transfers;

namespace Fileway.Infrastructure.Tests.Transfers;

public sealed class FileUploadRunnerCallbackTests
{
    private static readonly byte[] Data = [8, 6, 7, 5, 3, 0, 9];
    private static readonly TimeSpan Budget = TimeSpan.FromSeconds(5);
    private static readonly TimeSpan PendingObservation = TimeSpan.FromMilliseconds(100);
    private const string CallbackSecret = "private callback detail";
    private const string StoreSecret = "private persistence detail";
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Theory]
    [InlineData(TransferIntent.Pause)]
    [InlineData(TransferIntent.Cancel)]
    public async Task ThrowingCancellationCallbackReportsSafeControlFailureOnlyAfterExecutionDrains(TransferIntent intent)
    {
        await using var fixture = await Fixture.CreateAsync();
        var worker = fixture.Workers[0];
        var running = fixture.Start(worker);
        await worker.Entered.Task.WaitAsync(Budget, Token);

        var control = intent == TransferIntent.Pause
            ? fixture.Runner.PauseAsync(worker.Plan.Reference, Token)
            : fixture.Runner.CancelAsync(worker.Plan.Reference, Token);
        await worker.CancellationObserved.Task.WaitAsync(Budget, Token);

        Assert.Equal(intent, (await fixture.ReadAsync(worker)).Intent);
        await Assert.ThrowsAsync<TimeoutException>(() => control.WaitAsync(PendingObservation, Token));
        AssertHeld(worker);
        worker.AllowDrain.TrySetResult();

        var error = await Assert.ThrowsAsync<FilewayException>(() => control.WaitAsync(Budget, Token));
        AssertSafe(error, worker);
        var result = await running.WaitAsync(Budget, Token);
        AssertStopped(result, intent);
        Assert.Equal(result, await fixture.ReadAsync(worker));
        AssertDrained(worker);
        Assert.Equal(1, worker.CallbackCalls);
        Assert.Single(worker.Methods, method => method == "PATCH");
        Assert.DoesNotContain("DELETE", worker.Methods);
        Assert.Equal(1, fixture.Leases.Acquisitions);
        Assert.False(fixture.Http.Disposed);
    }

    [Fact]
    public async Task DisposeSignalsAllWorkersAndSharesSafeFailureAfterEveryHandleAndHttpHandlerDrain()
    {
        await using var fixture = await Fixture.CreateAsync(twoWorkers: true);
        var runs = fixture.Workers.Select(fixture.Start).ToArray();
        await Task.WhenAll(fixture.Workers.Select(worker => worker.Entered.Task)).WaitAsync(Budget, Token);

        var first = fixture.Runner.DisposeAsync().AsTask();
        var concurrent = fixture.Runner.DisposeAsync().AsTask();
        Assert.Same(first, concurrent);
        await Task.WhenAll(fixture.Workers.Select(worker => worker.CancellationObserved.Task)).WaitAsync(Budget, Token);

        await Assert.ThrowsAsync<TimeoutException>(() => first.WaitAsync(PendingObservation, Token));
        Assert.False(fixture.Http.Disposed);
        foreach (var worker in fixture.Workers)
        {
            AssertHeld(worker);
            Assert.Equal(TransferIntent.Pause, (await fixture.ReadAsync(worker)).Intent);
        }
        fixture.Workers[0].AllowDrain.TrySetResult();
        AssertStopped(await runs[0].WaitAsync(Budget, Token), TransferIntent.Pause);
        AssertDrained(fixture.Workers[0]);
        await Assert.ThrowsAsync<TimeoutException>(() => first.WaitAsync(PendingObservation, Token));
        AssertHeld(fixture.Workers[1]);
        Assert.False(fixture.Http.Disposed);
        fixture.Workers[1].AllowDrain.TrySetResult();

        var error = await Assert.ThrowsAsync<FilewayException>(() => first.WaitAsync(Budget, Token));
        var concurrentError = await Assert.ThrowsAsync<FilewayException>(() => concurrent.WaitAsync(Budget, Token));
        var repeated = fixture.Runner.DisposeAsync().AsTask();
        Assert.Same(first, repeated);
        var repeatedError = await Assert.ThrowsAsync<FilewayException>(() => repeated.WaitAsync(Budget, Token));
        Assert.Same(error, concurrentError);
        Assert.Same(error, repeatedError);
        foreach (var worker in fixture.Workers)
        {
            AssertSafe(error, worker);
            AssertDrained(worker);
            AssertStopped(await worker.Running!.WaitAsync(Budget, Token), TransferIntent.Pause);
            Assert.DoesNotContain("DELETE", worker.Methods);
            Assert.Single(worker.Methods, method => method == "PATCH");
        }
        Assert.Equal(1, fixture.Workers[0].CallbackCalls);
        Assert.Equal(0, fixture.Workers[1].CallbackCalls);
        Assert.Equal(1, fixture.Http.DisposeCount);
    }

    [Fact]
    public async Task UnexpectedIntentFailureStillSignalsEveryWorkerAndSharesSafeDisposeFailure()
    {
        await using var fixture = await Fixture.CreateAsync(twoWorkers: true, throwCallback: false);
        var runs = fixture.Workers.Select(fixture.Start).ToArray();
        await Task.WhenAll(fixture.Workers.Select(worker => worker.Entered.Task)).WaitAsync(Budget, Token);
        fixture.Store.IntentFaultTask = fixture.Workers[0].Plan.Reference;

        var first = fixture.Runner.DisposeAsync().AsTask();
        var concurrent = fixture.Runner.DisposeAsync().AsTask();
        Assert.Same(first, concurrent);
        await Task.WhenAll(fixture.Workers.Select(worker => worker.CancellationObserved.Task)).WaitAsync(Budget, Token);
        await Assert.ThrowsAsync<TimeoutException>(() => first.WaitAsync(PendingObservation, Token));
        Assert.False(fixture.Http.Disposed);
        foreach (var worker in fixture.Workers) AssertHeld(worker);
        foreach (var worker in fixture.Workers) worker.AllowDrain.TrySetResult();

        var error = await Assert.ThrowsAsync<FilewayException>(() => first.WaitAsync(Budget, Token));
        Assert.Same(error, await Assert.ThrowsAsync<FilewayException>(() => concurrent.WaitAsync(Budget, Token)));
        var repeated = fixture.Runner.DisposeAsync().AsTask();
        Assert.Same(first, repeated);
        Assert.Same(error, await Assert.ThrowsAsync<FilewayException>(() => repeated.WaitAsync(Budget, Token)));
        foreach (var worker in fixture.Workers)
        {
            AssertSafe(error, worker);
            AssertDrained(worker);
            Assert.DoesNotContain("DELETE", worker.Methods);
            Assert.Equal(0, worker.CallbackCalls);
        }
        foreach (var result in await Task.WhenAll(runs).WaitAsync(Budget, Token)) AssertStopped(result, TransferIntent.Pause);
        Assert.Equal(1, fixture.Store.IntentFaults);
        Assert.Equal(1, fixture.Http.DisposeCount);
    }

    [Theory]
    [InlineData(TransferIntent.Pause)]
    [InlineData(TransferIntent.Cancel)]
    public async Task ReleasePersistenceFailureTakesPriorityOverCallbackFailureAfterControlDrains(TransferIntent intent)
    {
        await using var fixture = await Fixture.CreateAsync();
        var worker = fixture.Workers[0];
        var running = fixture.Start(worker);
        await worker.Entered.Task.WaitAsync(Budget, Token);
        fixture.Store.FailReleaseCheckpoint = true;

        var control = intent == TransferIntent.Pause
            ? fixture.Runner.PauseAsync(worker.Plan.Reference, Token)
            : fixture.Runner.CancelAsync(worker.Plan.Reference, Token);
        await worker.CancellationObserved.Task.WaitAsync(Budget, Token);
        await Assert.ThrowsAsync<TimeoutException>(() => control.WaitAsync(PendingObservation, Token));
        AssertHeld(worker);
        worker.AllowDrain.TrySetResult();

        var error = await Assert.ThrowsAsync<FilewayException>(() => control.WaitAsync(Budget, Token));
        AssertSafe(error, worker, ErrorCode.LocalStorageUnavailable);
        var runError = await Assert.ThrowsAsync<FilewayException>(() => running.WaitAsync(Budget, Token));
        AssertSafe(runError, worker, ErrorCode.LocalStorageUnavailable);
        AssertDrained(worker);
        Assert.Equal(1, worker.CallbackCalls);
        Assert.Equal(1, fixture.Store.ReleaseFaults);
        await AssertReleaseRemainsProtectedAsync(fixture, worker, intent);
        Assert.DoesNotContain("DELETE", worker.Methods);
        Assert.False(fixture.Http.Disposed);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task DisposePreservesWorkerFilewayFailureSwallowingAfterReleasePersistenceFails(bool throwCallback)
    {
        await using var fixture = await Fixture.CreateAsync(throwCallback: throwCallback);
        var worker = fixture.Workers[0];
        var running = fixture.Start(worker);
        await worker.Entered.Task.WaitAsync(Budget, Token);
        fixture.Store.FailReleaseCheckpoint = true;

        var first = fixture.Runner.DisposeAsync().AsTask();
        var concurrent = fixture.Runner.DisposeAsync().AsTask();
        Assert.Same(first, concurrent);
        await worker.CancellationObserved.Task.WaitAsync(Budget, Token);
        await Assert.ThrowsAsync<TimeoutException>(() => first.WaitAsync(PendingObservation, Token));
        AssertHeld(worker);
        Assert.False(fixture.Http.Disposed);
        worker.AllowDrain.TrySetResult();

        if (throwCallback)
        {
            var error = await Assert.ThrowsAsync<FilewayException>(() => first.WaitAsync(Budget, Token));
            AssertSafe(error, worker);
            Assert.Same(error, await Assert.ThrowsAsync<FilewayException>(() => concurrent.WaitAsync(Budget, Token)));
            var repeated = fixture.Runner.DisposeAsync().AsTask();
            Assert.Same(first, repeated);
            Assert.Same(error, await Assert.ThrowsAsync<FilewayException>(() => repeated.WaitAsync(Budget, Token)));
        }
        else
        {
            await first.WaitAsync(Budget, Token);
            await concurrent.WaitAsync(Budget, Token);
            var repeated = fixture.Runner.DisposeAsync().AsTask();
            Assert.Same(first, repeated);
            await repeated.WaitAsync(Budget, Token);
        }
        var runError = await Assert.ThrowsAsync<FilewayException>(() => running.WaitAsync(Budget, Token));
        AssertSafe(runError, worker, ErrorCode.LocalStorageUnavailable);
        AssertDrained(worker);
        Assert.Equal(throwCallback ? 1 : 0, worker.CallbackCalls);
        Assert.Equal(1, fixture.Store.ReleaseFaults);
        await AssertReleaseRemainsProtectedAsync(fixture, worker, TransferIntent.Pause);
        Assert.DoesNotContain("DELETE", worker.Methods);
        Assert.Equal(1, fixture.Http.DisposeCount);
    }

    private static async Task AssertReleaseRemainsProtectedAsync(Fixture fixture, Worker worker, TransferIntent intent)
    {
        var durable = await fixture.ReadAsync(worker);
        Assert.Equal(intent, durable.Intent);
        Assert.NotNull(durable.Run);
        Assert.Equal(TransferUncertainty.RemoteWrite, durable.Checkpoint.Uncertainty);
        Assert.Equal(0, durable.Checkpoint.ConfirmedOffset);
        Assert.False(durable.Checkpoint.RemoteCanceled);
        Assert.False(durable.Checkpoint.RemotePublished);
        Assert.True(await fixture.Store.HasProtectedTransfersAsync(worker.Plan.Reference.Account, Token));
    }

    private static void AssertSafe(FilewayException error, Worker worker, ErrorCode expectedCode = ErrorCode.Unknown)
    {
        Assert.Equal(expectedCode, error.Error.Code);
        Assert.Null(error.InnerException);
        Assert.DoesNotContain(CallbackSecret, error.ToString());
        Assert.DoesNotContain(StoreSecret, error.ToString());
        Assert.DoesNotContain(worker.Plan.LocalPath, error.ToString());
        Assert.DoesNotContain(worker.Plan.Remote.WirePath, error.ToString());
        Assert.DoesNotContain(worker.Lease!.Address.AbsoluteUri, error.ToString());
    }

    private static void AssertStopped(TransferTaskRecord result, TransferIntent intent)
    {
        Assert.Equal(intent, result.Intent);
        Assert.Equal(TransferPhase.OutcomeUnknown, result.Checkpoint.Phase);
        Assert.Equal(TransferUncertainty.RemoteWrite, result.Checkpoint.Uncertainty);
        Assert.Equal(0, result.Checkpoint.ConfirmedOffset);
        Assert.False(result.Checkpoint.RemoteCanceled);
        Assert.False(result.Checkpoint.RemotePublished);
        Assert.Null(result.Run);
    }

    private static void AssertHeld(Worker worker)
    {
        Assert.False(worker.RequestReleased);
        Assert.False(worker.Source!.Disposed);
        Assert.False(worker.Lease!.Disposed);
        Assert.True(worker.LockHeld);
    }

    private static void AssertDrained(Worker worker)
    {
        Assert.True(worker.RequestReleased);
        Assert.True(worker.Source!.Disposed);
        Assert.True(worker.Lease!.Disposed);
        Assert.False(worker.LockHeld);
    }

    private sealed class Worker(TransferPlan plan, bool throwCallback)
    {
        internal TransferPlan Plan { get; } = plan;
        internal bool ThrowCallback { get; } = throwCallback;
        internal TaskCompletionSource Entered { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal TaskCompletionSource CancellationObserved { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal TaskCompletionSource AllowDrain { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal ConcurrentQueue<string> Methods { get; } = new();
        internal Source? Source;
        internal Lease? Lease;
        internal Task<TransferTaskRecord>? Running;
        internal int CallbackCalls;
        internal bool LockHeld;
        internal bool RequestReleased;
    }

    private sealed class Fixture : IAsyncDisposable
    {
        private readonly CancellationTokenSource _caller = CancellationTokenSource.CreateLinkedTokenSource(Token);
        private Fixture(InMemoryProfileStore store, Worker[] workers)
        {
            Store = new(store);
            Workers = workers;
            Leases = new(workers);
            Http = new(this);
            Runner = new(Store, Leases, new Sources(workers), new Locks(workers), new UnusedFiles(), Http,
                new() { ChunkSizeBytes = 4 });
        }
        internal FaultStore Store { get; }
        internal Worker[] Workers { get; }
        internal Leases Leases { get; }
        internal Handler Http { get; }
        internal FileUploadRunner Runner { get; }
        internal static async Task<Fixture> CreateAsync(bool twoWorkers = false, bool throwCallback = true)
        {
            var store = new InMemoryProfileStore();
            var saved = new SavedConnection(new(Guid.NewGuid(), Guid.NewGuid(), "callback fixture", new("https://fixture.invalid/"),
                NetworkMode.SystemNetwork, new(ProxyMode.Direct)), AuthenticationMode.Password, "fixture", "42", false, DateTimeOffset.UtcNow);
            await store.SaveAsync(saved, Token);
            var workers = new Worker[twoWorkers ? 2 : 1];
            for (int i = 0; i < workers.Length; i++)
            {
                string name = "private-callback-" + i.ToString(CultureInfo.InvariantCulture);
                var plan = new TransferPlan(new(saved.Account, Guid.NewGuid()), TransferDirection.Upload,
                    new(saved.Account, "/" + name + ".bin", name + ".bin", "/" + name + ".bin"), @"C:\synthetic-callback\" + name + ".bin");
                await store.CreateTransferAsync(plan, Token);
                workers[i] = new(plan, throwCallback: throwCallback && i == 0);
            }
            return new(store, workers);
        }
        internal Task<TransferTaskRecord> Start(Worker worker) => worker.Running = Runner.RunAsync(worker.Plan.Reference, recovery: false, _caller.Token);
        internal async Task<TransferTaskRecord> ReadAsync(Worker worker) =>
            Assert.IsType<TransferTaskRecord>(await Store.GetTransferAsync(worker.Plan.Reference, Token));
        public async ValueTask DisposeAsync()
        {
            foreach (var worker in Workers) worker.AllowDrain.TrySetResult();
            // Test cleanup must also drain a regression's abandoned second worker.
            try { await _caller.CancelAsync().WaitAsync(Budget, CancellationToken.None); }
            catch (AggregateException) { }
            try { await Runner.DisposeAsync().AsTask().WaitAsync(Budget, CancellationToken.None); }
            catch (FilewayException) { }
            try { await Task.WhenAll(Workers.Where(worker => worker.Running is not null).Select(worker => worker.Running!)).WaitAsync(Budget, CancellationToken.None); }
            catch (FilewayException) { }
            _caller.Dispose();
        }
    }

    private sealed class FaultStore(InMemoryProfileStore inner) : ITransferTaskStore
    {
        internal TransferTaskRef? IntentFaultTask { get; set; }
        internal bool FailReleaseCheckpoint { get; set; }
        internal int IntentFaults;
        internal int ReleaseFaults;
        public bool IsPersistent => false;
        public Task<TransferTaskRecord> CreateTransferAsync(TransferPlan plan, CancellationToken cancellationToken) => inner.CreateTransferAsync(plan, cancellationToken);
        public Task<TransferTaskRecord?> GetTransferAsync(TransferTaskRef task, CancellationToken cancellationToken) => inner.GetTransferAsync(task, cancellationToken);
        public Task<TransferTaskPage> ListTransfersAsync(AccountKey account, string? cursor, int limit, CancellationToken cancellationToken) => inner.ListTransfersAsync(account, cursor, limit, cancellationToken);
        public Task<bool> HasProtectedTransfersAsync(AccountKey account, CancellationToken cancellationToken) => inner.HasProtectedTransfersAsync(account, cancellationToken);
        public Task<TransferTaskRecord> SetTransferIntentAsync(TransferTaskRef task, long expectedRevision, TransferIntent intent, CancellationToken cancellationToken)
        {
            if (task == IntentFaultTask && Interlocked.CompareExchange(ref IntentFaults, 1, 0) == 0)
                throw new ArgumentException(StoreSecret);
            return inner.SetTransferIntentAsync(task, expectedRevision, intent, cancellationToken);
        }
        public Task<TransferTaskRecord> ClaimTransferAsync(TransferTaskRef task, long expectedRevision, Guid runId, bool recovery, CancellationToken cancellationToken) =>
            inner.ClaimTransferAsync(task, expectedRevision, runId, recovery, cancellationToken);
        public Task<TransferTaskRecord> SaveTransferCheckpointAsync(TransferRunToken run, long expectedRevision, TransferCheckpoint checkpoint, bool releaseRun, CancellationToken cancellationToken)
        {
            if (releaseRun && FailReleaseCheckpoint)
            {
                Interlocked.Increment(ref ReleaseFaults);
                throw new FilewayException(new(ErrorCode.LocalStorageUnavailable, StoreSecret));
            }
            return inner.SaveTransferCheckpointAsync(run, expectedRevision, checkpoint, releaseRun, cancellationToken);
        }
    }

    private sealed class Sources(Worker[] workers) : IUploadSourceProvider
    {
        public Task<IUploadSource> OpenAsync(string fullPath, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var worker = Assert.Single(workers, worker => worker.Plan.LocalPath == fullPath);
            Assert.Null(worker.Source);
            worker.Source = new();
            return Task.FromResult<IUploadSource>(worker.Source);
        }
    }

    private sealed class Source : IUploadSource
    {
        private readonly MemoryStream _content = new(Data, writable: false);
        internal bool Disposed { get; private set; }
        public Stream Content => _content;
        public TransferSourceSnapshot Snapshot { get; } = new(Data.Length, NativeFileIdentity: "callback-volume:file");
        public Task VerifyAsync(TransferSourceSnapshot expected, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Assert.False(Disposed);
            Assert.Equal(Snapshot, expected);
            return Task.CompletedTask;
        }
        public ValueTask DisposeAsync() { Disposed = true; _content.Dispose(); return ValueTask.CompletedTask; }
    }

    private sealed class Locks(Worker[] workers) : ITransferExecutionLockProvider
    {
        public Task<ITransferExecutionLock> AcquireAsync(TransferTaskRef task, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var worker = Assert.Single(workers, worker => worker.Plan.Reference == task);
            Assert.False(worker.LockHeld);
            worker.LockHeld = true;
            return Task.FromResult<ITransferExecutionLock>(new Ownership(worker));
        }
    }

    private sealed class Ownership(Worker worker) : ITransferExecutionLock
    {
        public TransferTaskRef Task => worker.Plan.Reference;
        public ValueTask DisposeAsync() { worker.LockHeld = false; return ValueTask.CompletedTask; }
    }

    private sealed class Leases(Worker[] workers) : IUploadLeaseProvider
    {
        internal int Acquisitions;
        public Task<IUploadLease> AcquireUploadAsync(UploadLeaseRequest request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Interlocked.Increment(ref Acquisitions);
            var worker = Assert.Single(workers, worker => worker.Plan.Remote.WirePath == request.Target.WirePath);
            Assert.Null(worker.Lease);
            worker.Lease = new(request);
            return Task.FromResult<IUploadLease>(worker.Lease);
        }
    }

    private sealed class Lease(UploadLeaseRequest request) : IUploadLease
    {
        internal bool Disposed { get; private set; }
        public RemoteResourceRef Resource => request.Target;
        public Uri Address { get; } = new("http://127.0.0.1:32452/stream/" + request.TransferId.ToString("N"));
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
        internal int DisposeCount;
        internal bool Disposed => Volatile.Read(ref DisposeCount) != 0;
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var worker = Assert.Single(fixture.Workers, worker => worker.Lease?.Address == request.RequestUri);
            var durable = await fixture.ReadAsync(worker);
            Assert.NotNull(durable.Run);
            Assert.True(worker.LockHeld);
            Assert.Equal(TransferUncertainty.RemoteWrite, durable.Checkpoint.Uncertainty);
            worker.Methods.Enqueue(request.Method.Method);
            if (request.Method == HttpMethod.Post)
            {
                var created = Response(HttpStatusCode.Created);
                created.Headers.Location = request.RequestUri;
                return created;
            }
            if (request.Method == HttpMethod.Head)
            {
                var head = Response(HttpStatusCode.OK);
                head.Headers.Add("Upload-Offset", "0");
                head.Headers.Add("Upload-Length", Data.Length.ToString(CultureInfo.InvariantCulture));
                return head;
            }
            Assert.Equal(HttpMethod.Patch, request.Method);
            Assert.Equal(Data.AsSpan(0, 4).ToArray(), await request.Content!.ReadAsByteArrayAsync(cancellationToken));
            // Registration order matters: callbacks are LIFO, so the throwing callback must run
            // before the cooperative delay can resume and dispose its registration.
            var canceled = Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
            using var registration = worker.ThrowCallback ? cancellationToken.Register(() =>
            {
                Interlocked.Increment(ref worker.CallbackCalls);
                throw new InvalidOperationException(CallbackSecret + " " + worker.Plan.LocalPath + " " + request.RequestUri);
            }) : default;
            worker.Entered.TrySetResult();
            try { await canceled; }
            finally
            {
                worker.CancellationObserved.TrySetResult();
                await worker.AllowDrain.Task.WaitAsync(Budget, CancellationToken.None);
                worker.RequestReleased = true;
            }
            throw new InvalidOperationException("The canceled fixture request cannot acknowledge a write.");
        }
        protected override void Dispose(bool disposing)
        {
            if (disposing) Interlocked.Increment(ref DisposeCount);
            base.Dispose(disposing);
        }
        private static HttpResponseMessage Response(HttpStatusCode status)
        {
            var response = new HttpResponseMessage(status);
            response.Headers.Add("Tus-Resumable", "1.0.0");
            return response;
        }
    }

    private sealed class UnusedFiles : IFileRepository
    {
        public Task<RemoteFile> GetAsync(RemoteResourceRef resource, CancellationToken cancellationToken) => throw new InvalidOperationException("Unexpected metadata access.");
        public Task<RemoteResourceRef> GetRootAsync(ConnectionSession session, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<DirectoryPage> ListAsync(RemoteResourceRef directory, DirectoryQuery query, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<FileOperationResult> CreateDirectoryAsync(RemoteResourceRef parent, string name, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<FileOperationResult> RenameAsync(RemoteResourceRef resource, string name, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<FileOperationResult> TrashAsync(IReadOnlyList<RemoteResourceRef> resources, CancellationToken cancellationToken) => throw new NotSupportedException();
    }
}
