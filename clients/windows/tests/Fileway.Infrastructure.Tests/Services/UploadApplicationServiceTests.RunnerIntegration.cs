using System.Collections.Concurrent;
using System.Globalization;
using System.Net;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;
using Fileway.Infrastructure.Services;
using Fileway.Infrastructure.Transfers;

namespace Fileway.Infrastructure.Tests.Services;

public sealed partial class UploadApplicationServiceTests
{
    private static readonly byte[] IntegrationData = [3, 1, 4, 1, 5, 9, 2, 6, 5];

    [Fact]
    public async Task ApplicationSubmitRunsRealQueueAndUploaderToPublicationWithoutLeakingExecutionData()
    {
        await using var fixture = new RunnerFixture();
        await fixture.ConnectAsync();
        var context = fixture.Context;
        Assert.Empty(fixture.Http.Requests);
        Assert.Empty(fixture.Sources.Opened);
        Assert.Empty((await fixture.Service.ListAsync(context, null, 50, Token)).Items);
        var completed = new TaskCompletionSource<UploadItem>(TaskCreationOptions.RunContinuationsAsynchronously);
        fixture.Service.TaskChanged += (_, item) =>
        {
            if (item.Snapshot.Task == fixture.Task && item.Status == UploadStatus.Completed) completed.TrySetResult(item);
        };

        var queued = await fixture.Service.SubmitAsync(fixture.Submission(), Token);
        var item = await completed.Task.WaitAsync(GateTimeout, Token);
        var record = await fixture.ReadAsync(Token);

        Assert.Equal(context, item.Context);
        Assert.Equal(fixture.Task, queued.Snapshot.Task);
        Assert.Equal(fixture.Task, item.Snapshot.Task);
        Assert.Equal(fixture.Parent, item.Parent);
        Assert.Equal("literal%2F #é.bin", item.Name);
        Assert.Equal("/visible destination/literal%2F #é.bin", item.DestinationDisplayPath);
        Assert.Equal(TransferPhase.Completed, record.Checkpoint.Phase);
        Assert.Equal(IntegrationData.LongLength, item.Snapshot.ConfirmedOffset);
        Assert.Equal(IntegrationData.LongLength, item.Snapshot.TotalBytes);
        Assert.True(item.Snapshot.RemotePublished);
        Assert.False(item.Snapshot.RemoteCanceled);
        Assert.Equal(TransferUncertainty.None, item.Snapshot.Uncertainty);
        Assert.Null(record.Run);
        Assert.Equal(record.Revision, item.Snapshot.Revision);
        Assert.Equal(1, item.Snapshot.Generation);
        Assert.Equal(CompleteHttpMethods, fixture.Http.Requests);
        Assert.Equal(IntegrationData, fixture.Http.RemoteBytes);
        Assert.Equal(IntegrationOffsets, fixture.Http.Offsets);
        Assert.Equal(2, fixture.Leases.Requests.Count);
        Assert.All(fixture.Leases.Requests, request =>
        {
            Assert.Equal(fixture.RequestId, request.TransferId);
            Assert.Equal("/opaque%252Fparent%25/literal%252F%20%23%C3%A9.bin", request.Target.WirePath);
        });
        AssertRunnerReleased(fixture);
        Assert.DoesNotContain(PrivateLocalPath, item.ToString(), StringComparison.Ordinal);
        Assert.DoesNotContain(PrivateLocalPath, System.Text.Json.JsonSerializer.Serialize(item), StringComparison.Ordinal);

        var historical = Assert.Single((await fixture.Service.ListAsync(context, null, 50, Token)).Items);
        Assert.Equal(UploadStatus.Completed, historical.Status);
        Assert.Null(historical.Progress);
        Assert.Equal(item.Snapshot, historical.Snapshot);
        var canceled = await fixture.Service.CancelAsync(context, fixture.Task, Token);
        Assert.Equal(item.Snapshot, canceled.Snapshot);
        Assert.Equal(6, fixture.Http.Requests.Count);
        Assert.Single(fixture.Sources.Opened);
    }

    [Theory]
    [InlineData("disconnect")]
    [InlineData("logout")]
    [InlineData("forget")]
    public async Task ApplicationTeardownWaitsForRealRunnerSourceAndLeaseCleanupBeforeRetirement(string operation)
    {
        await using var fixture = new RunnerFixture();
        await fixture.ConnectAsync();
        fixture.Http.BlockPatchAcknowledgement = true;
        fixture.Sources.HoldDisposal = true;
        await fixture.Service.SubmitAsync(fixture.Submission(), Token);
        await fixture.Http.PatchEntered.Task.WaitAsync(GateTimeout, Token);
        using var caller = CancellationTokenSource.CreateLinkedTokenSource(Token);
        var context = fixture.Context;
        var teardown = operation switch
        {
            "disconnect" => fixture.Service.DisconnectAsync(context.Account, caller.Token),
            "logout" => fixture.Service.LogoutAsync(context.Account, caller.Token),
            _ => fixture.Service.ForgetAsync(context.Account.ProfileId, caller.Token),
        };
        await fixture.Sources.DisposalEntered.Task.WaitAsync(GateTimeout, Token);
        await caller.CancelAsync();

        Assert.Null(fixture.Service.CurrentContext);
        Assert.False(teardown.IsCompleted);
        Assert.DoesNotContain(operation, fixture.Connection.Calls);
        Assert.True(fixture.Locks.IsHeld(fixture.Task));
        Assert.False(Assert.Single(fixture.Sources.Opened).Disposed);
        var interrupted = await fixture.ReadAsync(Token);
        Assert.Equal(TransferIntent.Pause, interrupted.Intent);
        Assert.Equal(TransferPhase.OutcomeUnknown, interrupted.Checkpoint.Phase);
        Assert.Equal(TransferUncertainty.RemoteWrite, interrupted.Checkpoint.Uncertainty);
        Assert.Null(interrupted.Run);
        fixture.Sources.DisposalRelease.TrySetResult();

        if (operation == "forget")
        {
            // The inner catalog still protects unresolved remote evidence after the local owner drains.
            var error = await Assert.ThrowsAsync<FilewayException>(() => teardown.WaitAsync(GateTimeout, Token));
            Assert.Equal(ErrorCode.Conflict, error.Error.Code);
            Assert.NotNull(await fixture.Store.Inner.GetAsync(context.Account.ProfileId, Token));
        }
        else await teardown.WaitAsync(GateTimeout, Token);

        Assert.Contains(operation, fixture.Connection.Calls);
        AssertRunnerReleased(fixture);
        Assert.Equal(InterruptedHttpMethods, fixture.Http.Requests);
        Assert.Equal(4, fixture.Http.RemoteOffset);
        Assert.Equal(0, (await fixture.ReadAsync(Token)).Checkpoint.ConfirmedOffset);
        Assert.DoesNotContain("DELETE", fixture.Http.Requests);
    }

    [Fact]
    public async Task ApplicationReconnectDoesNotOpenReplacementUntilRealSourceHandleIsReleasedOrAutoResumeHistory()
    {
        await using var fixture = new RunnerFixture();
        var original = await fixture.ConnectAsync();
        fixture.Http.BlockPatchAcknowledgement = true;
        fixture.Sources.HoldDisposal = true;
        await fixture.Service.SubmitAsync(fixture.Submission(), Token);
        await fixture.Http.PatchEntered.Task.WaitAsync(GateTimeout, Token);

        var reconnect = fixture.ConnectAsync();
        await fixture.Sources.DisposalEntered.Task.WaitAsync(GateTimeout, Token);
        Assert.False(reconnect.IsCompleted);
        Assert.Null(fixture.Service.CurrentContext);
        Assert.Equal(original, fixture.Connection.Active);
        Assert.Single(fixture.Connection.Calls);
        fixture.Sources.DisposalRelease.TrySetResult();
        var replacement = await reconnect.WaitAsync(GateTimeout, Token);

        Assert.NotEqual(original.Id, replacement.Id);
        AssertRunnerReleased(fixture);
        Assert.Equal(3, fixture.Http.Requests.Count);
        Assert.Single(fixture.Sources.Opened);
        var historical = Assert.Single((await fixture.Service.ListAsync(fixture.Context, null, 50, Token)).Items);
        Assert.Equal(UploadStatus.OutcomeUnknown, historical.Status);
        Assert.Equal(TransferUncertainty.RemoteWrite, historical.Snapshot.Uncertainty);
        Assert.Equal(TransferIntent.Pause, historical.Snapshot.Intent);
        Assert.Null(historical.Progress);
    }

    [Fact]
    public async Task ApplicationInvalidationRevokesAuthorityImmediatelyWhileRealDrainRetainsOwnershipUntilCleanup()
    {
        await using var fixture = new RunnerFixture();
        await fixture.ConnectAsync();
        var context = fixture.Context;
        fixture.Http.BlockPatchAcknowledgement = true;
        fixture.Sources.HoldDisposal = true;
        await fixture.Service.SubmitAsync(fixture.Submission(), Token);
        await fixture.Http.PatchEntered.Task.WaitAsync(GateTimeout, Token);

        fixture.Connection.EmitIssue(new(context.Account, context.SessionId,
            new(ErrorCode.AuthenticationExpired, "Fixture authentication expired."), true));

        Assert.Null(fixture.Service.CurrentContext);
        await fixture.Sources.DisposalEntered.Task.WaitAsync(GateTimeout, Token);
        Assert.True(fixture.Locks.IsHeld(fixture.Task));
        var dispose = fixture.Service.DisposeAsync().AsTask();
        Assert.False(dispose.IsCompleted);
        fixture.Sources.DisposalRelease.TrySetResult();
        await dispose.WaitAsync(GateTimeout, Token);

        AssertRunnerReleased(fixture);
        Assert.Equal(0, fixture.Connection.Disposals);
        Assert.Equal(3, fixture.Http.Requests.Count);
        Assert.Equal(TransferIntent.Pause, (await fixture.ReadAsync(Token)).Intent);
    }

    private static void AssertRunnerReleased(RunnerFixture fixture)
    {
        Assert.True(Assert.Single(fixture.Sources.Opened).Disposed);
        Assert.NotEmpty(fixture.Leases.Issued);
        Assert.All(fixture.Leases.Issued, lease => Assert.True(lease.Disposed));
        Assert.False(fixture.Locks.IsHeld(fixture.Task));
    }

    private sealed class RunnerFixture : IAsyncDisposable
    {
        internal Guid RequestId { get; } = Guid.NewGuid();
        internal ObservedStore Store { get; }
        internal FakeConnection Connection { get; }
        internal FakeFiles Files { get; } = new();
        internal RunnerSources Sources { get; } = new();
        internal RunnerLocks Locks { get; } = new();
        internal RunnerLeases Leases { get; } = new();
        internal RunnerHttp Http { get; }
        internal UploadApplicationService Service { get; }
        internal UploadContext Context => Assert.IsType<UploadContext>(Service.CurrentContext);
        internal TransferTaskRef Task => new(Connection.Account, RequestId);
        internal RemoteResourceRef Parent => new(Connection.Account, "/opaque%252Fparent%25", "visible destination", "/visible destination", "parent-version");

        internal RunnerFixture()
        {
            var order = new ConcurrentQueue<string>();
            Store = new(new InMemoryProfileStore(), order);
            Connection = new(Store.Inner, order);
            Http = new(this);
            Files.OnGet = (resource, _) => resource.WirePath == Parent.WirePath
                ? System.Threading.Tasks.Task.FromResult(new RemoteFile(resource, true, 0, null))
                : System.Threading.Tasks.Task.FromException<RemoteFile>(new FilewayException(new(ErrorCode.SourceChanged, "fixture missing", 404)));
            var runner = new FileUploadRunner(Store, Leases, Sources, Locks, Files, Http,
                new FileUploadRunnerOptions { ChunkSizeBytes = 4 });
            var queue = new UploadCoordinator(Store, runner, new UploadCoordinatorOptions { MaximumConcurrentUploads = 1 });
            Service = new(Connection, Connection, Files, Store, queue);
            Connection.BeforeAction = (action, _) =>
            {
                if (action is "disconnect" or "logout" or "forget" || Connection.Calls.Count > 1)
                    Assert.False(Locks.IsHeld(Task));
                return System.Threading.Tasks.Task.CompletedTask;
            };
        }

        internal Task<ConnectionSession> ConnectAsync() => Service.ConnectAsync(Connection.Profile,
            new(AuthenticationMode.Password, "fixture", "synthetic-password"), Token);
        internal UploadSubmission Submission() => new(RequestId, Context, Parent, PrivateLocalPath);
        internal async Task<TransferTaskRecord> ReadAsync(CancellationToken cancellationToken) =>
            Assert.IsType<TransferTaskRecord>(await Store.Inner.GetTransferAsync(Task, cancellationToken));
        public async ValueTask DisposeAsync()
        {
            Sources.DisposalRelease.TrySetResult();
            Http.PatchRelease.TrySetResult();
            await Service.DisposeAsync().AsTask().WaitAsync(GateTimeout, Token);
        }
    }

    private sealed class RunnerSources : IUploadSourceProvider
    {
        internal TransferSourceSnapshot Snapshot { get; } = new(IntegrationData.LongLength,
            new DateTimeOffset(2026, 10, 12, 0, 0, 0, TimeSpan.Zero), NativeFileIdentity: "fixture-volume:application-1");
        internal ConcurrentBag<RunnerSource> Opened { get; } = new();
        internal bool HoldDisposal { get; set; }
        internal TaskCompletionSource DisposalEntered { get; } = Signal();
        internal TaskCompletionSource DisposalRelease { get; } = Signal();
        public Task<IUploadSource> OpenAsync(string fullPath, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Assert.Equal(PrivateLocalPath, fullPath);
            var source = new RunnerSource(this);
            Opened.Add(source);
            return System.Threading.Tasks.Task.FromResult<IUploadSource>(source);
        }
    }

    private sealed class RunnerSource(RunnerSources owner) : IUploadSource
    {
        private readonly MemoryStream _content = new(IntegrationData, writable: false);
        private int _state;
        internal bool Disposed => Volatile.Read(ref _state) == 2;
        public Stream Content => _content;
        public TransferSourceSnapshot Snapshot => owner.Snapshot;
        public Task VerifyAsync(TransferSourceSnapshot expected, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Assert.Equal(Snapshot, expected);
            return System.Threading.Tasks.Task.CompletedTask;
        }
        public async ValueTask DisposeAsync()
        {
            if (Interlocked.CompareExchange(ref _state, 1, 0) != 0) return;
            owner.DisposalEntered.TrySetResult();
            if (owner.HoldDisposal) await owner.DisposalRelease.Task.WaitAsync(GateTimeout);
            _content.Dispose();
            Volatile.Write(ref _state, 2);
        }
    }

    private sealed class RunnerLocks : ITransferExecutionLockProvider
    {
        private readonly object _gate = new();
        private readonly HashSet<TransferTaskRef> _held = [];
        internal bool IsHeld(TransferTaskRef task) { lock (_gate) return _held.Contains(task); }
        public Task<ITransferExecutionLock> AcquireAsync(TransferTaskRef task, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            lock (_gate) Assert.True(_held.Add(task));
            return System.Threading.Tasks.Task.FromResult<ITransferExecutionLock>(new RunnerLock(task, () => { lock (_gate) _held.Remove(task); }));
        }
    }

    private sealed class RunnerLock(TransferTaskRef task, Action release) : ITransferExecutionLock
    {
        private int _disposed;
        public TransferTaskRef Task { get; } = task;
        public ValueTask DisposeAsync() { if (Interlocked.Exchange(ref _disposed, 1) == 0) release(); return ValueTask.CompletedTask; }
    }

    private sealed class RunnerLeases : IUploadLeaseProvider
    {
        private int _next;
        internal ConcurrentQueue<UploadLeaseRequest> Requests { get; } = new();
        internal ConcurrentQueue<RunnerLease> Issued { get; } = new();
        public Task<IUploadLease> AcquireUploadAsync(UploadLeaseRequest request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Requests.Enqueue(request);
            var lease = new RunnerLease(request, Interlocked.Increment(ref _next));
            Issued.Enqueue(lease);
            return System.Threading.Tasks.Task.FromResult<IUploadLease>(lease);
        }
    }

    private sealed class RunnerLease(UploadLeaseRequest request, int ordinal) : IUploadLease
    {
        private int _disposed;
        internal bool Disposed => Volatile.Read(ref _disposed) != 0;
        public RemoteResourceRef Resource { get; } = request.Target;
        public Uri Address { get; } = new("http://127.0.0.1:32450/stream/application" + ordinal.ToString(CultureInfo.InvariantCulture));
        public Guid TransferId { get; } = request.TransferId;
        public long Length { get; } = request.Length;
        public bool IsResume { get; } = request.Resume;
        public Task<UploadLeaseStatistics?> ReadUploadStatisticsAsync(CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return System.Threading.Tasks.Task.FromResult<UploadLeaseStatistics?>(Disposed ? null : new(0, null, 0, Length, 0, TimeSpan.Zero, false));
        }
        public ValueTask DisposeAsync() { Interlocked.Exchange(ref _disposed, 1); return ValueTask.CompletedTask; }
    }

    private sealed class RunnerHttp(RunnerFixture fixture) : HttpMessageHandler
    {
        internal ConcurrentQueue<string> Requests { get; } = new();
        internal ConcurrentQueue<long> Offsets { get; } = new();
        internal byte[] RemoteBytes { get; } = new byte[IntegrationData.Length];
        internal long RemoteOffset { get; private set; }
        internal bool BlockPatchAcknowledgement { get; set; }
        internal TaskCompletionSource PatchEntered { get; } = Signal();
        internal TaskCompletionSource PatchRelease { get; } = Signal();
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var durable = await fixture.ReadAsync(cancellationToken);
            Assert.NotNull(durable.Run);
            Assert.Equal(TransferUncertainty.RemoteWrite, durable.Checkpoint.Uncertainty);
            Assert.Equal(fixture.Sources.Snapshot, durable.Checkpoint.Source);
            Assert.True(fixture.Locks.IsHeld(fixture.Task));
            Assert.Equal("1.0.0", request.Headers.GetValues("Tus-Resumable").Single());
            Requests.Enqueue(request.Method.Method);
            if (request.Method == HttpMethod.Post)
            {
                Assert.Equal(IntegrationData.Length.ToString(CultureInfo.InvariantCulture), request.Headers.GetValues("Upload-Length").Single());
                var response = Response(HttpStatusCode.Created);
                response.Headers.Location = request.RequestUri;
                return response;
            }
            if (request.Method == HttpMethod.Head)
            {
                var response = Response(HttpStatusCode.OK);
                response.Headers.Add("Upload-Length", IntegrationData.Length.ToString(CultureInfo.InvariantCulture));
                response.Headers.Add("Upload-Offset", RemoteOffset.ToString(CultureInfo.InvariantCulture));
                return response;
            }
            Assert.Equal(HttpMethod.Patch, request.Method);
            long offset = long.Parse(request.Headers.GetValues("Upload-Offset").Single(), CultureInfo.InvariantCulture);
            Assert.Equal(RemoteOffset, offset);
            Offsets.Enqueue(offset);
            byte[] body = await request.Content!.ReadAsByteArrayAsync(cancellationToken);
            body.CopyTo(RemoteBytes, checked((int)RemoteOffset));
            RemoteOffset += body.Length;
            PatchEntered.TrySetResult();
            if (BlockPatchAcknowledgement) await PatchRelease.Task.WaitAsync(GateTimeout, cancellationToken);
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
