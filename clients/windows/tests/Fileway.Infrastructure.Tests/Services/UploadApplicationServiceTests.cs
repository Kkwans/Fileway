using System.Collections.Concurrent;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;
using Fileway.Infrastructure.Services;

namespace Fileway.Infrastructure.Tests.Services;

public sealed partial class UploadApplicationServiceTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;
    private static readonly TimeSpan GateTimeout = TimeSpan.FromSeconds(10);
    private static readonly string[] InitialConnectOrder = [ "connect", "activate"];
    private static readonly string[] SubmitOrder = [ "create", "enqueue"];
    private static readonly string[] ControlledReplacementOrder = [ "connect", "activate", "pause", "stop", "drained", "disconnect", "connect", "activate"];
    private static readonly string[] DisposeOrder = [ "connect", "activate", "stop", "drained", "dispose"];
    private static readonly string[] CompleteHttpMethods = [ "POST", "HEAD", "PATCH", "PATCH", "PATCH", "HEAD"];
    private static readonly string[] InterruptedHttpMethods = [ "POST", "HEAD", "PATCH"];
    private static readonly long[] IntegrationOffsets = [ 0, 4, 8];
    private const string PrivateLocalPath = @"C:\synthetic-private-source\literal%2F #é.bin";

    [Fact]
    public async Task ConstructionIsInactiveAndDoesNotReadOrScheduleHistory()
    {
        await using var fixture = new Fixture();

        Assert.Null(fixture.Service.CurrentContext);
        Assert.Empty(fixture.Connection.Calls);
        Assert.Empty(fixture.Coordinator.Calls);
        Assert.Equal(0, fixture.Store.Reads);
        Assert.Equal(0, fixture.Store.ListReads);
        await AssertSafeFailureAsync(ErrorCode.SourceChanged, () => fixture.Service.ListAsync(
            new(fixture.Account, Guid.NewGuid(), Guid.NewGuid()), null, 50, Token));
        Assert.Empty(fixture.Coordinator.Calls);
    }

    [Fact]
    public async Task ConnectActivatesOnlyTheVerifiedSession()
    {
        await using var fixture = new Fixture();
        var session = await fixture.ConnectAsync();
        var context = Assert.IsType<UploadContext>(fixture.Service.CurrentContext);

        Assert.Equal(session.Account, context.Account);
        Assert.Equal(session.Id, context.SessionId);
        Assert.NotEqual(Guid.Empty, context.ActivationId);
        Assert.Equal(new UploadActivationToken(context.Account, context.SessionId, context.ActivationId), fixture.Coordinator.Active);
        Assert.Equal(InitialConnectOrder, fixture.Order);
        Assert.NotNull(await fixture.Store.Inner.GetAsync(session.Account.ProfileId, Token));
        Assert.Equal(0, fixture.Store.ListReads);
    }

    [Fact]
    public async Task RestoreAndListNeverEnqueueAutomaticResumeHistory()
    {
        await using var fixture = new Fixture();
        await fixture.SaveProfileAsync();
        var historical = await fixture.CreateStoredAsync(automaticResume: true);

        var session = await fixture.Service.RestoreAsync(fixture.Profile.Id, Token);
        var context = Assert.IsType<UploadContext>(fixture.Service.CurrentContext);
        var page = await fixture.Service.ListAsync(context, null, 50, Token);

        Assert.Equal(session.Id, context.SessionId);
        var item = Assert.Single(page.Items);
        Assert.Equal(historical.Plan.Reference, item.Snapshot.Task);
        Assert.Equal(UploadStatus.NotStarted, item.Status);
        Assert.Equal(TransferPhase.Queued, item.Snapshot.Phase);
        Assert.Null(item.Progress);
        Assert.DoesNotContain(fixture.Coordinator.Calls, call => call is "enqueue" or "resume");
        Assert.Equal(0, (await fixture.ReadAsync(historical.Plan.Reference)).Generation);
    }

    [Fact]
    public async Task SubmitPersistsExactOriginalPlanBeforeEnqueueAndEncodesOnlyTheLeaf()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var submission = fixture.Submission();
        var entered = fixture.Gate();
        var release = fixture.Gate();
        fixture.Store.BeforeCreate = async (_, cancellationToken) =>
        {
            entered.TrySetResult();
            await release.Task.WaitAsync(GateTimeout, cancellationToken);
        };

        var submitting = fixture.Service.SubmitAsync(submission, Token);
        await entered.Task.WaitAsync(GateTimeout, Token);
        Assert.DoesNotContain("enqueue", fixture.Coordinator.Calls);
        Assert.Empty((await fixture.Store.Inner.ListTransfersAsync(fixture.Account, null, 50, Token)).Items);
        release.TrySetResult();
        var item = await submitting.WaitAsync(GateTimeout, Token);
        var record = await fixture.ReadAsync(item.Snapshot.Task);

        Assert.Equal(new TransferTaskRef(fixture.Account, submission.RequestId), record.Plan.Reference);
        Assert.Equal(TransferDirection.Upload, record.Plan.Direction);
        Assert.Equal(PrivateLocalPath, record.Plan.LocalPath);
        Assert.Equal(FileConflictPolicy.Fail, record.Plan.Conflict);
        Assert.False(record.Plan.AutomaticResume);
        Assert.Null(record.Plan.BatchId);
        Assert.Equal("/opaque%252Fparent%25/" + Uri.EscapeDataString("literal%2F #é.bin"), record.Plan.Remote.WirePath);
        Assert.Equal("literal%2F #é.bin", record.Plan.Remote.DisplayName);
        Assert.Equal("/visible destination/literal%2F #é.bin", record.Plan.Remote.DisplayPath);
        Assert.Equal(submission.Parent, item.Parent);
        Assert.Equal("literal%2F #é.bin", item.Name);
        Assert.Equal(record.Plan.Remote.DisplayPath, item.DestinationDisplayPath);
        Assert.Equal(submission.Context, item.Context);
        Assert.Equal(SubmitOrder, fixture.Order.Skip(2));
        Assert.Single(fixture.Coordinator.Enqueued);
        Assert.Equal(record.Plan.Reference, Assert.Single(fixture.Coordinator.Enqueued));
        Assert.DoesNotContain(PrivateLocalPath, submission.ToString(), StringComparison.Ordinal);
        Assert.DoesNotContain(PrivateLocalPath, item.ToString(), StringComparison.Ordinal);
        Assert.DoesNotContain(PrivateLocalPath, JsonSerializer.Serialize(item), StringComparison.Ordinal);
        AssertNoExecutionProperties();
    }

    [Theory]
    [InlineData("/")]
    [InlineData("/already%252Fopaque")]
    public async Task OriginalParentWireIsNeverDecodedOrLocallyNormalized(string parentWire)
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var parent = fixture.Parent with { WirePath = parentWire, DisplayPath = "/" };
        var submission = fixture.Submission() with { Parent = parent, LocalPath = @"C:\synthetic\%25 file.bin" };

        var item = await fixture.Service.SubmitAsync(submission, Token);
        var record = await fixture.ReadAsync(item.Snapshot.Task);

        Assert.Equal(parentWire.TrimEnd('/') + "/%2525%20file.bin", record.Plan.Remote.WirePath);
        Assert.Equal(parent, item.Parent);
        Assert.Equal("/%25 file.bin", item.DestinationDisplayPath);
    }

    [Fact]
    public async Task SameRequestAndImmutablePlanDeduplicateWithoutReenqueueing()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var submission = fixture.Submission();

        var first = await fixture.Service.SubmitAsync(submission, Token);
        var second = await fixture.Service.SubmitAsync(submission, Token);

        Assert.Equal(first.Snapshot.Task, second.Snapshot.Task);
        Assert.Equal(1, fixture.Store.Creates);
        Assert.Single(fixture.Coordinator.Enqueued);
        Assert.Single((await fixture.Store.Inner.ListTransfersAsync(fixture.Account, null, 50, Token)).Items);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ReusingRequestForDifferentImmutablePlanFailsWithoutChangingTheOriginal(bool changeParent)
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var submission = fixture.Submission();
        var original = await fixture.Service.SubmitAsync(submission, Token);
        var altered = changeParent
            ? submission with { Parent = fixture.Parent with { WirePath = "/another%252Fparent" } }
            : submission with { LocalPath = @"C:\synthetic-private-source\another.bin" };

        await AssertSafeFailureAsync(ErrorCode.Conflict, () => fixture.Service.SubmitAsync(altered, Token));

        Assert.Equal(PrivateLocalPath, (await fixture.ReadAsync(original.Snapshot.Task)).Plan.LocalPath);
        Assert.Equal(1, fixture.Store.Creates);
        Assert.Single(fixture.Coordinator.Enqueued);
    }

    [Fact]
    public async Task StoredSameRequestIsDisplayedWithoutImplicitSchedulingAfterReconnect()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var submission = fixture.Submission();
        var first = await fixture.Service.SubmitAsync(submission, Token);
        await fixture.Service.DisconnectAsync(fixture.Account, Token);
        await fixture.ConnectAsync();

        var second = await fixture.Service.SubmitAsync(submission with { Context = fixture.Context }, Token);

        Assert.Equal(first.Snapshot.Task, second.Snapshot.Task);
        Assert.Equal(1, fixture.Store.Creates);
        Assert.Single(fixture.Coordinator.Enqueued);
        Assert.Equal(UploadStatus.NotStarted, second.Status);
    }

    [Fact]
    public async Task FailedPersistenceNeverAdmitsExecution()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        fixture.Store.BeforeCreate = (_, _) => throw new IOException("private database location fixture");

        var error = await Assert.ThrowsAsync<FilewayException>(() => fixture.Service.SubmitAsync(fixture.Submission(), Token));

        Assert.Equal(ErrorCode.LocalStorageUnavailable, error.Error.Code);
        Assert.DoesNotContain("private database", error.Message, StringComparison.Ordinal);
        Assert.Empty(fixture.Coordinator.Enqueued);
        Assert.Empty((await fixture.Store.Inner.ListTransfersAsync(fixture.Account, null, 50, Token)).Items);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(1)]
    [InlineData(2)]
    public async Task SubmitRejectsEveryStaleContextDimensionBeforePersistence(int dimension)
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var context = fixture.Context;
        context = dimension switch
        {
            0 => context with { Account = context.Account with { SourceRevision = Guid.NewGuid() } },
            1 => context with { SessionId = Guid.NewGuid() },
            _ => context with { ActivationId = Guid.NewGuid() },
        };

        await AssertSafeFailureAsync(ErrorCode.SourceChanged, () => fixture.Service.SubmitAsync(fixture.Submission() with { Context = context }, Token));

        Assert.Equal(0, fixture.Store.Creates);
        Assert.Empty(fixture.Coordinator.Enqueued);
        Assert.Empty(fixture.Files.Requests);
    }

    [Fact]
    public async Task SubmitRequiresCreatePermissionAndADirectoryInTheSameAccount()
    {
        await using var fixture = new Fixture();
        fixture.Connection.CreatePermission = false;
        await fixture.ConnectAsync();
        await AssertSafeFailureAsync(ErrorCode.PermissionDenied, () => fixture.Service.SubmitAsync(fixture.Submission(), Token));
        Assert.Empty(fixture.Files.Requests);

        fixture.Connection.EmitSession(fixture.Connection.Active! with
            { Permissions = fixture.Connection.Active!.Permissions with { Create = true } });
        var wrongParent = fixture.Parent with { Account = fixture.Account with { ServerUserId = "another-user" } };
        await AssertSafeFailureAsync(ErrorCode.InvalidRequest, () => fixture.Service.SubmitAsync(fixture.Submission() with { Parent = wrongParent }, Token));
        fixture.Files.Directory = false;
        await AssertSafeFailureAsync(ErrorCode.InvalidRequest, () => fixture.Service.SubmitAsync(fixture.Submission(), Token));

        Assert.Equal(0, fixture.Store.Creates);
        Assert.Empty(fixture.Coordinator.Enqueued);
    }

    [Theory]
    [InlineData("")]
    [InlineData("relative.bin")]
    [InlineData(@"C:\synthetic\")]
    [InlineData(@"C:\synthetic\..\private.bin")]
    [InlineData(@"C:\synthetic\reserved:stream")]
    public async Task InvalidLocalFilesCannotCreateTasks(string path)
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();

        await AssertSafeFailureAsync(ErrorCode.InvalidRequest, () => fixture.Service.SubmitAsync(fixture.Submission() with { LocalPath = path }, Token));

        Assert.Equal(0, fixture.Store.Creates);
        Assert.Empty(fixture.Coordinator.Enqueued);
    }

    [Fact]
    public async Task ListRetainsOpaqueOriginalParentAndFiltersDownloadTasks()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var historical = await fixture.CreateStoredAsync(wire: "/original%252Fparent/%2525%20file.bin", display: "/original visible/%25 file.bin");
        await fixture.CreateStoredAsync(direction: TransferDirection.Download);

        var page = await fixture.Service.ListAsync(fixture.Context, null, 50, Token);

        var item = Assert.Single(page.Items);
        Assert.Equal(historical.Plan.Reference, item.Snapshot.Task);
        Assert.Equal("/original%252Fparent", item.Parent.WirePath);
        Assert.Equal("/original visible", item.Parent.DisplayPath);
        Assert.Equal(historical.Plan.Remote.DisplayName, item.Name);
        Assert.Null(item.Progress);
        Assert.DoesNotContain("enqueue", fixture.Coordinator.Calls);
    }

    [Theory]
    [InlineData(TransferPhase.Preparing)]
    [InlineData(TransferPhase.Running)]
    [InlineData(TransferPhase.Verifying)]
    [InlineData(TransferPhase.Publishing)]
    [InlineData(TransferPhase.Reconciling)]
    public async Task HistoricalActiveEvidenceNeverClaimsTheCurrentProcessIsExecuting(TransferPhase phase)
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var record = await fixture.CreateStoredAsync();
        record = await fixture.Store.Inner.ClaimTransferAsync(record.Plan.Reference, record.Revision,
            Guid.NewGuid(), recovery: phase == TransferPhase.Reconciling, Token);
        if (phase is not TransferPhase.Preparing and not TransferPhase.Reconciling)
        {
            var source = new TransferSourceSnapshot(8, NativeFileIdentity: "private-historical-source-identity");
            long offset = phase == TransferPhase.Running ? 0 : 8;
            record = await fixture.Store.Inner.SaveTransferCheckpointAsync(record.Run!.Value, record.Revision,
                new(TransferPhase.Running, source, offset), releaseRun: false, Token);
            if (phase != TransferPhase.Running)
                record = await fixture.Store.Inner.SaveTransferCheckpointAsync(record.Run!.Value, record.Revision,
                    record.Checkpoint with { Phase = phase }, releaseRun: false, Token);
        }

        var item = Assert.Single((await fixture.Service.ListAsync(fixture.Context, null, 50, Token)).Items);

        Assert.Equal(phase, item.Snapshot.Phase);
        Assert.Equal(UploadStatus.NotStarted, item.Status);
        Assert.Null(item.Progress);
        Assert.Empty(fixture.Coordinator.Enqueued);
        Assert.DoesNotContain("resume", fixture.Coordinator.Calls);
        Assert.DoesNotContain("private-historical-source-identity", JsonSerializer.Serialize(item), StringComparison.Ordinal);
    }

    [Fact]
    public async Task HistoricalAcceptedCancellationNeedsReconciliationAndResumeUsesCancelWithoutReversingIntent()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var record = await fixture.CreateStoredAsync();
        record = await fixture.Store.Inner.SetTransferIntentAsync(record.Plan.Reference, record.Revision, TransferIntent.Cancel, Token);

        var historical = Assert.Single((await fixture.Service.ListAsync(fixture.Context, null, 50, Token)).Items);
        Assert.Equal(UploadStatus.OutcomeUnknown, historical.Status);
        Assert.Equal(TransferIntent.Cancel, historical.Snapshot.Intent);
        Assert.Empty(fixture.Coordinator.Enqueued);
        var reconciled = await fixture.Service.ResumeAsync(fixture.Context, record.Plan.Reference, Token);

        Assert.Equal(TransferIntent.Cancel, reconciled.Snapshot.Intent);
        Assert.Equal(TransferIntent.Cancel, (await fixture.ReadAsync(record.Plan.Reference)).Intent);
        Assert.Single(fixture.Coordinator.Calls, call => call == "cancel");
        Assert.DoesNotContain("resume", fixture.Coordinator.Calls);
        Assert.Empty(fixture.Coordinator.Enqueued);
    }

    [Fact]
    public async Task PreviouslySkippedUploadRemainsTerminalHistoryWithoutScheduling()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var plan = new TransferPlan(new(fixture.Account, Guid.NewGuid()), TransferDirection.Upload,
            new(fixture.Account, "/historical-skip.bin", "historical-skip.bin", "/historical-skip.bin"),
            @"C:\synthetic-history\historical-skip.bin", FileConflictPolicy.Skip);
        var record = await fixture.Store.Inner.CreateTransferAsync(plan, Token);
        record = await fixture.Store.Inner.ClaimTransferAsync(plan.Reference, record.Revision, Guid.NewGuid(), false, Token);
        record = await fixture.Store.Inner.SaveTransferCheckpointAsync(record.Run!.Value, record.Revision,
            new(TransferPhase.Skipped), releaseRun: true, Token);

        var item = Assert.Single((await fixture.Service.ListAsync(fixture.Context, null, 50, Token)).Items);

        Assert.Equal(UploadStatus.Skipped, item.Status);
        Assert.Equal(Snapshot(record), item.Snapshot);
        Assert.Empty(fixture.Coordinator.Enqueued);
        Assert.DoesNotContain("resume", fixture.Coordinator.Calls);
    }

    [Theory]
    [InlineData("pause")]
    [InlineData("resume")]
    [InlineData("cancel")]
    public async Task ControlsRequireCurrentContextAndAccountOwnedUploadTask(string control)
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var upload = await fixture.CreateStoredAsync();
        var download = await fixture.CreateStoredAsync(direction: TransferDirection.Download);
        var foreign = upload.Plan.Reference with { Account = fixture.Account with { ServerUserId = "foreign" } };
        var stale = fixture.Context with { ActivationId = Guid.NewGuid() };

        await AssertSafeFailureAsync(ErrorCode.SourceChanged, () => ControlAsync(fixture, control, stale, upload.Plan.Reference));
        await AssertSafeFailureAsync(ErrorCode.InvalidRequest, () => ControlAsync(fixture, control, fixture.Context, foreign));
        await AssertSafeFailureAsync(ErrorCode.InvalidRequest, () => ControlAsync(fixture, control, fixture.Context, download.Plan.Reference));
        Assert.DoesNotContain(control, fixture.Coordinator.Calls);

        var item = await ControlAsync(fixture, control, fixture.Context, upload.Plan.Reference);
        Assert.Equal(upload.Plan.Reference, item.Snapshot.Task);
        Assert.Equal(fixture.Context, item.Context);
        Assert.Single(fixture.Coordinator.Calls, call => call == control);
    }

    private static Task<UploadItem> ControlAsync(Fixture fixture, string control, UploadContext context, TransferTaskRef task) => control switch
    {
        "pause" => fixture.Service.PauseAsync(context, task, Token),
        "resume" => fixture.Service.ResumeAsync(context, task, Token),
        "cancel" => fixture.Service.CancelAsync(context, task, Token),
        _ => throw new ArgumentOutOfRangeException(nameof(control)),
    };

    private static async Task AssertSafeFailureAsync(ErrorCode code, Func<Task> action)
    {
        var error = await Assert.ThrowsAsync<FilewayException>(action);
        Assert.Equal(code, error.Error.Code);
        Assert.DoesNotContain(PrivateLocalPath, error.Message, StringComparison.Ordinal);
    }

    private static void AssertNoExecutionProperties()
    {
        var names = typeof(UploadItem).GetProperties().Select(property => property.Name).ToArray();
        Assert.DoesNotContain("LocalPath", names);
        Assert.DoesNotContain("Plan", names);
        Assert.DoesNotContain("Record", names);
        Assert.DoesNotContain("Capabilities", names);
        Assert.DoesNotContain("Lease", names);
    }

    private static TaskCompletionSource Signal() => new(TaskCreationOptions.RunContinuationsAsynchronously);

    private sealed class Fixture : IAsyncDisposable
    {
        private readonly List<TaskCompletionSource> _gates = [];
        internal ConcurrentQueue<string> Order { get; } = new();
        internal ObservedStore Store { get; }
        internal FakeConnection Connection { get; }
        internal FakeFiles Files { get; } = new();
        internal FakeCoordinator Coordinator { get; }
        internal UploadApplicationService Service { get; }
        internal ConnectionProfile Profile => Connection.Profile;
        internal AccountKey Account => Connection.Account;
        internal UploadContext Context => Assert.IsType<UploadContext>(Service.CurrentContext);
        internal RemoteResourceRef Parent => new(Account, "/opaque%252Fparent%25", "visible destination", "/visible destination", "parent-version");

        internal Fixture()
        {
            Store = new(new InMemoryProfileStore(), Order);
            Connection = new(Store.Inner, Order);
            Coordinator = new(Store, Order);
            Service = new(Connection, Connection, Files, Store, Coordinator);
        }

        internal TaskCompletionSource Gate()
        {
            var gate = Signal();
            _gates.Add(gate);
            return gate;
        }

        internal Task<ConnectionSession> ConnectAsync() => Service.ConnectAsync(Profile, new(AuthenticationMode.Password, "fixture", "synthetic-password"), Token);
        internal Task SaveProfileAsync() => Store.Inner.SaveAsync(Connection.Saved(), Token);
        internal UploadSubmission Submission() => new(Guid.NewGuid(), Context, Parent, PrivateLocalPath);
        internal async Task<TransferTaskRecord> ReadAsync(TransferTaskRef task) => Assert.IsType<TransferTaskRecord>(await Store.Inner.GetTransferAsync(task, Token));

        internal Task<TransferTaskRecord> CreateStoredAsync(bool automaticResume = false, TransferDirection direction = TransferDirection.Upload,
            string? wire = null, string? display = null) => Store.Inner.CreateTransferAsync(new(new(Account, Guid.NewGuid()), direction,
                new(Account, wire ?? "/opaque%252Fparent%25/history.bin", "history.bin", display ?? "/visible destination/history.bin"),
                @"C:\synthetic-history\history.bin", AutomaticResume: automaticResume), Token);

        public async ValueTask DisposeAsync()
        {
            foreach (var gate in _gates) gate.TrySetResult();
            await Service.DisposeAsync().AsTask().WaitAsync(GateTimeout, Token);
        }
    }

    private sealed class ObservedStore(InMemoryProfileStore inner, ConcurrentQueue<string> order) : ITransferTaskStore
    {
        internal InMemoryProfileStore Inner { get; } = inner;
        internal Func<TransferPlan, CancellationToken, Task>? BeforeCreate { get; set; }
        internal int Creates;
        internal int Reads;
        internal int ListReads;
        public bool IsPersistent => false;
        public async Task<TransferTaskRecord> CreateTransferAsync(TransferPlan plan, CancellationToken cancellationToken)
        {
            if (BeforeCreate is { } before) await before(plan, cancellationToken);
            var record = await Inner.CreateTransferAsync(plan, cancellationToken);
            Interlocked.Increment(ref Creates);
            order.Enqueue("create");
            return record;
        }
        public Task<TransferTaskRecord?> GetTransferAsync(TransferTaskRef task, CancellationToken cancellationToken)
        { Interlocked.Increment(ref Reads); return Inner.GetTransferAsync(task, cancellationToken); }
        public Task<TransferTaskPage> ListTransfersAsync(AccountKey account, string? cursor, int limit, CancellationToken cancellationToken)
        { Interlocked.Increment(ref ListReads); return Inner.ListTransfersAsync(account, cursor, limit, cancellationToken); }
        public Task<bool> HasProtectedTransfersAsync(AccountKey account, CancellationToken cancellationToken) => Inner.HasProtectedTransfersAsync(account, cancellationToken);
        public Task<TransferTaskRecord> SetTransferIntentAsync(TransferTaskRef task, long expectedRevision, TransferIntent intent, CancellationToken cancellationToken) => Inner.SetTransferIntentAsync(task, expectedRevision, intent, cancellationToken);
        public Task<TransferTaskRecord> ClaimTransferAsync(TransferTaskRef task, long expectedRevision, Guid runId, bool recovery, CancellationToken cancellationToken) => Inner.ClaimTransferAsync(task, expectedRevision, runId, recovery, cancellationToken);
        public Task<TransferTaskRecord> SaveTransferCheckpointAsync(TransferRunToken run, long expectedRevision, TransferCheckpoint checkpoint, bool releaseRun, CancellationToken cancellationToken) => Inner.SaveTransferCheckpointAsync(run, expectedRevision, checkpoint, releaseRun, cancellationToken);
    }

    private sealed class FakeConnection(InMemoryProfileStore store, ConcurrentQueue<string> order) : IConnectionService, IConnectionCatalog, IAsyncDisposable
    {
        internal ConnectionProfile Profile { get; } = new(Guid.NewGuid(), Guid.NewGuid(), "bridge fixture", new("https://fixture.invalid/"), NetworkMode.SystemNetwork, new(ProxyMode.Direct));
        internal AccountKey Account => new(Profile.Id, Profile.SourceRevision, "7");
        internal ConnectionSession? Active { get; set; }
        internal ConcurrentQueue<string> Calls { get; } = new();
        internal bool CreatePermission { get; set; } = true;
        internal Exception? ConnectFailure { get; set; }
        internal Func<string, CancellationToken, Task>? BeforeAction { get; set; }
        internal Func<ConnectionSession, CancellationToken, Task>? BeforeReturn { get; set; }
        internal int Disposals;
        public event EventHandler<ConnectionSession>? SessionUpdated;
        public event EventHandler<ConnectionIssue>? ConnectionIssue;
        internal SavedConnection Saved() => new(Profile, AuthenticationMode.Password, "fixture", "7", false, DateTimeOffset.UtcNow);
        internal void EmitSession(ConnectionSession session) { Active = session; SessionUpdated?.Invoke(this, session); }
        internal void EmitIssue(ConnectionIssue issue) => ConnectionIssue?.Invoke(this, issue);
        public async Task<ConnectionSession> ConnectAsync(ConnectionProfile profile, LoginInput login, CancellationToken cancellationToken)
        {
            await ActionAsync("connect", cancellationToken);
            if (ConnectFailure is { } failure) throw failure;
            await store.SaveAsync(Saved(), cancellationToken);
            var session = Active = new(Guid.NewGuid(), Account, "verified session", new(true, CreatePermission, true, true, true, false), new());
            if (BeforeReturn is { } before) await before(session, cancellationToken);
            return session;
        }
        public async Task<ConnectionSession> RestoreAsync(Guid profileId, CancellationToken cancellationToken)
        {
            await ActionAsync("restore", cancellationToken);
            if (ConnectFailure is { } failure) throw failure;
            _ = await store.GetAsync(profileId, cancellationToken) ?? throw new FilewayException(new(ErrorCode.InvalidRequest, "Unknown fixture connection."));
            var session = Active = new(Guid.NewGuid(), Account, "restored session", new(true, CreatePermission, true, true, true, false), new());
            if (BeforeReturn is { } before) await before(session, cancellationToken);
            return session;
        }
        public async Task DisconnectAsync(AccountKey account, CancellationToken cancellationToken) { await ActionAsync("disconnect", cancellationToken); Active = null; }
        public async Task LogoutAsync(AccountKey account, CancellationToken cancellationToken) { await ActionAsync("logout", cancellationToken); Active = null; }
        public Task<IReadOnlyList<SavedConnection>> ListAsync(CancellationToken cancellationToken) => store.ListAsync(cancellationToken);
        public Task<SavedConnection?> GetAsync(Guid profileId, CancellationToken cancellationToken) => store.GetAsync(profileId, cancellationToken);
        public async Task ForgetAsync(Guid profileId, CancellationToken cancellationToken)
        {
            await ActionAsync("forget", cancellationToken);
            var saved = await store.GetAsync(profileId, cancellationToken);
            if (saved is not null) await store.ForgetAsync(saved.Account, cancellationToken);
            Active = null;
        }
        private async Task ActionAsync(string name, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Calls.Enqueue(name);
            order.Enqueue(name);
            if (BeforeAction is { } before) await before(name, cancellationToken);
        }
        public ValueTask DisposeAsync() { Interlocked.Increment(ref Disposals); return ValueTask.CompletedTask; }
    }

    private sealed class FakeFiles : IFileRepository
    {
        internal bool Directory { get; set; } = true;
        internal ConcurrentQueue<RemoteResourceRef> Requests { get; } = new();
        internal Func<RemoteResourceRef, CancellationToken, Task<RemoteFile>>? OnGet { get; set; }
        public Task<RemoteFile> GetAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Requests.Enqueue(resource);
            return OnGet?.Invoke(resource, cancellationToken) ?? Task.FromResult(new RemoteFile(resource, Directory, 0, null));
        }
        public Task<RemoteResourceRef> GetRootAsync(ConnectionSession session, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<DirectoryPage> ListAsync(RemoteResourceRef directory, DirectoryQuery query, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<FileOperationResult> CreateDirectoryAsync(RemoteResourceRef parent, string name, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<FileOperationResult> RenameAsync(RemoteResourceRef resource, string name, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<FileOperationResult> TrashAsync(IReadOnlyList<RemoteResourceRef> resources, CancellationToken cancellationToken) => throw new NotSupportedException();
    }

    private sealed class FakeCoordinator(ObservedStore store, ConcurrentQueue<string> order) : IUploadCoordinator
    {
        internal UploadActivationToken? Active { get; private set; }
        internal ConcurrentQueue<string> Calls { get; } = new();
        internal ConcurrentQueue<TransferTaskRef> Enqueued { get; } = new();
        internal Func<UploadActivationToken, CancellationToken, Task>? BeforeStop { get; set; }
        internal Func<ConnectionSession, CancellationToken, Task>? BeforeActivate { get; set; }
        internal Func<string, CancellationToken, Task>? BeforeControl { get; set; }
        internal ConcurrentQueue<ConnectionSession> ActivatedSessions { get; } = new();
        internal int Disposals;
        public event EventHandler<UploadQueueUpdate>? TaskChanged;
        internal void Emit(UploadQueueUpdate update) => TaskChanged?.Invoke(this, update);
        public async Task<UploadActivationToken> ActivateAsync(ConnectionSession session, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Calls.Enqueue("activate"); order.Enqueue("activate");
            ActivatedSessions.Enqueue(session);
            var activation = new UploadActivationToken(session.Account, session.Id, Guid.NewGuid());
            Active = activation;
            if (BeforeActivate is { } before) await before(session, cancellationToken);
            return activation;
        }
        public async Task<UploadTaskSnapshot> EnqueueAsync(UploadActivationToken activation, TransferTaskRef task, CancellationToken cancellationToken)
        {
            Validate(activation);
            var record = Assert.IsType<TransferTaskRecord>(await store.Inner.GetTransferAsync(task, cancellationToken));
            Enqueued.Enqueue(task); Calls.Enqueue("enqueue"); order.Enqueue("enqueue");
            return Snapshot(record);
        }
        public Task<UploadTaskSnapshot> ResumeAsync(UploadActivationToken activation, TransferTaskRef task, CancellationToken cancellationToken) => ControlAsync("resume", activation, task, cancellationToken);
        public Task<UploadTaskSnapshot> PauseAsync(UploadActivationToken activation, TransferTaskRef task, CancellationToken cancellationToken) => ControlAsync("pause", activation, task, cancellationToken);
        public Task<UploadTaskSnapshot> CancelAsync(UploadActivationToken activation, TransferTaskRef task, CancellationToken cancellationToken) => ControlAsync("cancel", activation, task, cancellationToken);
        private async Task<UploadTaskSnapshot> ControlAsync(string name, UploadActivationToken activation, TransferTaskRef task, CancellationToken cancellationToken)
        {
            Validate(activation);
            Calls.Enqueue(name); order.Enqueue(name);
            if (BeforeControl is { } before) await before(name, cancellationToken);
            return Snapshot(Assert.IsType<TransferTaskRecord>(await store.Inner.GetTransferAsync(task, cancellationToken)));
        }
        public async Task StopAndDrainAsync(UploadActivationToken activation, CancellationToken cancellationToken)
        {
            Validate(activation);
            Calls.Enqueue("stop"); order.Enqueue("stop");
            Active = null;
            if (BeforeStop is { } before) await before(activation, cancellationToken);
            order.Enqueue("drained");
        }
        private void Validate(UploadActivationToken activation) { if (activation != Active) throw new FilewayException(new(ErrorCode.SourceChanged, "Retired fixture activation.")); }
        public ValueTask DisposeAsync() { Calls.Enqueue("dispose"); order.Enqueue("dispose"); Interlocked.Increment(ref Disposals); return ValueTask.CompletedTask; }
    }

    private static UploadTaskSnapshot Snapshot(TransferTaskRecord record) => new(record.Plan.Reference, record.Intent,
        record.Checkpoint.Phase, record.Checkpoint.ConfirmedOffset, record.Checkpoint.Source?.Length,
        record.Checkpoint.RemotePublished, record.Checkpoint.RemoteCanceled, record.Checkpoint.Uncertainty,
        record.Checkpoint.Error, record.Revision, record.Generation);
}
