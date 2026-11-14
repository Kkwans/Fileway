using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Credentials;
using Fileway.Infrastructure.Persistence;
using Fileway.Infrastructure.Services;

namespace Fileway.Infrastructure.Tests.Services;

/// <summary>Faults at the service/storage/Host boundary, rather than storage-only transitions.</summary>
public sealed class JournalLifecycleTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task FailedPreparationNeverDispatchesTheRemoteWrite(bool failIntent)
    {
        await using var host = Host();
        var store = new FaultStore { FailIntent = failIntent, FailMarker = !failIntent };
        await using var services = new FilewayClientServices(host, store, new InMemoryCredentialVault());
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var failure = await Assert.ThrowsAsync<FilewayException>(() =>
            services.CreateDirectoryAsync(Root(session), "new-folder", Token));

        Assert.Empty(Mutations(host));
        Assert.Equal(ErrorCode.LocalStorageUnavailable, failure.Error.Code);
        if (failIntent)
        {
            Assert.Empty((await store.ListOperationsAsync(session.Account, null, 20, Token)).Items);
        }
        else
        {
            Assert.Equal(OperationPhase.NotSent, (await SingleRecord(store, session.Account)).Phase);
        }
    }

    [Fact]
    public async Task AckStorageFailureKeepsConfirmedRemoteResultAndDispatchEvidence()
    {
        await using var host = Host();
        var store = new FaultStore { FailAck = true };
        await using var services = new FilewayClientServices(host, store, new InMemoryCredentialVault());
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var result = await services.CreateDirectoryAsync(Root(session), "new-folder", Token);

        Assert.Equal(OperationOutcome.Confirmed, result.Outcome);
        Assert.Null(result.Error);
        Assert.Single(Mutations(host));
        Assert.False(result.Receipt!.AckPersisted);
        Assert.Equal(ErrorCode.LocalStorageUnavailable, result.Receipt.PersistenceWarning!.Code);
        var record = await SingleRecord(store, session.Account);
        Assert.Equal(result.Receipt.OperationId, record.Intent.Reference.Id);
        Assert.Equal(OperationPhase.DispatchMarked, record.Phase);
        Assert.Null(record.Acknowledgement);
        Assert.True(await store.HasUnresolvedOperationsAsync(session.Account, Token));
    }

    [Fact]
    public async Task CallerCancellationAfterReplyDoesNotEraseAcknowledgement()
    {
        using var caller = CancellationTokenSource.CreateLinkedTokenSource(Token);
        await using var host = Host((_, _) =>
        {
            caller.Cancel();
            return Task.FromResult(CoreReadHost.Http(new { }));
        });
        var store = new FaultStore();
        await using var services = new FilewayClientServices(host, store, new InMemoryCredentialVault());
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var result = await services.CreateDirectoryAsync(Root(session), "new-folder", caller.Token);

        Assert.Equal(OperationOutcome.Confirmed, result.Outcome);
        Assert.Single(Mutations(host));
        Assert.Equal(OperationPhase.Acknowledged, (await SingleRecord(store, session.Account)).Phase);
        Assert.NotNull(result.Receipt!.PersistenceWarning); // An in-memory store is never called durable.
    }

    [Fact]
    public async Task LogoutDuringWriteRetainsLateAckWithoutRevivingTheSession()
    {
        var sent = Signal();
        var reply = Signal();
        await using var host = Host(async (_, _) =>
        {
            sent.TrySetResult();
            await reply.Task.WaitAsync(Token);
            return CoreReadHost.Http(new { });
        });
        var store = new FaultStore();
        await using var services = new FilewayClientServices(host, store, new InMemoryCredentialVault());
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var root = Root(session);
        var write = services.CreateDirectoryAsync(root, "new-folder", Token);
        try
        {
            await sent.Task.WaitAsync(TimeSpan.FromSeconds(3), Token);
            await services.LogoutAsync(session.Account, Token).WaitAsync(TimeSpan.FromSeconds(3), Token);
        }
        finally { reply.TrySetResult(); }

        var result = await write.WaitAsync(TimeSpan.FromSeconds(3), Token);

        Assert.Equal(OperationOutcome.Confirmed, result.Outcome);
        Assert.Single(Mutations(host));
        Assert.Equal(OperationPhase.Acknowledged, (await SingleRecord(store, session.Account)).Phase);
        var failure = await Assert.ThrowsAsync<FilewayException>(() => services.GetAsync(root, Token));
        Assert.Equal(ErrorCode.SourceChanged, failure.Error.Code);
    }

    [Theory]
    [InlineData(403, OperationPhase.Rejected)]
    [InlineData(503, OperationPhase.OutcomeUnknown)]
    public async Task DefiniteRejectionAndUncertainDispatchRemainDifferent(int status, OperationPhase phase)
    {
        await using var host = Host((_, _) => Task.FromResult(CoreReadHost.Http(null, status)));
        var store = new FaultStore();
        await using var services = new FilewayClientServices(host, store, new InMemoryCredentialVault());
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        if (phase == OperationPhase.Rejected)
        {
            var failure = await Assert.ThrowsAsync<FilewayException>(() =>
                services.CreateDirectoryAsync(Root(session), "new-folder", Token));
            Assert.Equal(ErrorCode.PermissionDenied, failure.Error.Code);
            Assert.Equal(status, failure.Error.HttpStatus);
        }
        else
        {
            var result = await services.CreateDirectoryAsync(Root(session), "new-folder", Token);
            Assert.Equal(OperationOutcome.OutcomeUnknown, result.Outcome);
            Assert.Equal(status, result.Error!.HttpStatus);
        }
        Assert.Single(Mutations(host));
        Assert.Equal(phase, (await SingleRecord(store, session.Account)).Phase);
    }

    [Fact]
    public async Task ReconciliationCannotStealRevisionWhileAckIsBeingSaved()
    {
        var saving = Signal();
        var release = Signal();
        var store = new FaultStore
        {
            BeforeAck = async cancellationToken =>
            {
                saving.TrySetResult();
                await release.Task.WaitAsync(cancellationToken);
            },
        };
        await using var host = Host();
        await using var services = new FilewayClientServices(host, store, new InMemoryCredentialVault());
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var write = services.CreateDirectoryAsync(Root(session), "new-folder", Token);
        try
        {
            await saving.Task.WaitAsync(TimeSpan.FromSeconds(3), Token);
            var record = await SingleRecord(store, session.Account);
            var reconciliation = await services.ReconcileAsync(record.Intent.Reference, Token)
                .WaitAsync(TimeSpan.FromSeconds(3), Token);
            Assert.Equal(OperationResolution.Pending, reconciliation.Resolution);
            Assert.Equal(record.Revision, (await SingleRecord(store, session.Account)).Revision);
        }
        finally { release.TrySetResult(); }
        Assert.Equal(OperationOutcome.Confirmed, (await write.WaitAsync(TimeSpan.FromSeconds(3), Token)).Outcome);
        Assert.Equal(OperationPhase.Acknowledged, (await SingleRecord(store, session.Account)).Phase);
        Assert.Single(Mutations(host));
    }

    [Fact]
    public async Task ReconciliationCannotReplaceNotSentEvidenceWhileFailureIsBeingSaved()
    {
        using var caller = CancellationTokenSource.CreateLinkedTokenSource(Token);
        var saving = Signal();
        var release = Signal();
        var store = new FaultStore
        {
            AfterMarker = () => caller.Cancel(),
            BeforeFailure = async cancellationToken =>
            {
                saving.TrySetResult();
                await release.Task.WaitAsync(cancellationToken);
            },
        };
        await using var host = Host();
        await using var services = new FilewayClientServices(host, store, new InMemoryCredentialVault());
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var write = services.CreateDirectoryAsync(Root(session), "new-folder", caller.Token);
        try
        {
            await saving.Task.WaitAsync(TimeSpan.FromSeconds(3), Token);
            var record = await SingleRecord(store, session.Account);
            var reconciliation = await services.ReconcileAsync(record.Intent.Reference, Token)
                .WaitAsync(TimeSpan.FromSeconds(3), Token);
            Assert.Equal(OperationResolution.Pending, reconciliation.Resolution);
            Assert.Equal(record.Revision, (await SingleRecord(store, session.Account)).Revision);
        }
        finally { release.TrySetResult(); }
        var failure = await Assert.ThrowsAsync<FilewayException>(async () =>
            await write.WaitAsync(TimeSpan.FromSeconds(3), Token));
        Assert.Equal(ErrorCode.Canceled, failure.Error.Code);
        Assert.Equal(OperationPhase.NotSent, (await SingleRecord(store, session.Account)).Phase);
        Assert.Empty(Mutations(host));
    }

    private static TaskCompletionSource Signal() => new(TaskCreationOptions.RunContinuationsAsynchronously);
    private static RemoteResourceRef Root(ConnectionSession session) => new(session.Account, "/", "根目录", "/");
    private static async Task<OperationRecord> SingleRecord(FaultStore store, AccountKey account) =>
        Assert.Single((await store.ListOperationsAsync(account, null, 20, Token)).Items);
    private static HostCall[] Mutations(CoreReadHost host) => host.Calls.Where(call =>
        call.Operation == "request" && call.Safety == HostCallSafety.Mutation).ToArray();

    private static CoreReadHost Host(Func<HostCall, CancellationToken, Task<object>>? mutation = null)
    {
        var host = new CoreReadHost();
        host.Dispatch = (call, cancellationToken) =>
        {
            if (call.Operation == "request" && call.Safety == HostCallSafety.Mutation)
                return mutation?.Invoke(call, cancellationToken) ?? Task.FromResult(CoreReadHost.Http(new { }));
            return Task.FromResult(CoreReadHost.Endpoint(call) switch
            {
                "/api/resources/?metadata=1" => CoreReadHost.Http(CoreReadHost.Metadata("/", directory: true)),
                "/api/resources/new-folder?metadata=1" => CoreReadHost.Http(null, 404),
                _ => throw new InvalidOperationException("Unexpected fixture request."),
            });
        };
        return host;
    }

    private sealed class FaultStore : IProfileStore, IOperationJournalStore
    {
        private readonly InMemoryProfileStore _inner = new();
        internal bool FailIntent { get; init; }
        internal bool FailMarker { get; init; }
        internal bool FailAck { get; init; }
        internal Action? AfterMarker { get; init; }
        internal Func<CancellationToken, Task>? BeforeAck { get; init; }
        internal Func<CancellationToken, Task>? BeforeFailure { get; init; }
        public bool IsPersistent => false;
        public Task<IReadOnlyList<SavedConnection>> ListAsync(CancellationToken cancellationToken) => _inner.ListAsync(cancellationToken);
        public Task<SavedConnection?> GetAsync(Guid profileId, CancellationToken cancellationToken) => _inner.GetAsync(profileId, cancellationToken);
        public Task SaveAsync(SavedConnection connection, CancellationToken cancellationToken) => _inner.SaveAsync(connection, cancellationToken);
        public Task ForgetAsync(AccountKey account, CancellationToken cancellationToken) => _inner.ForgetAsync(account, cancellationToken);
        public Task<IReadOnlyList<AccountKey>> ListPendingLogoutsAsync(CancellationToken cancellationToken) => _inner.ListPendingLogoutsAsync(cancellationToken);
        public Task BeginLogoutAsync(AccountKey account, CancellationToken cancellationToken) => _inner.BeginLogoutAsync(account, cancellationToken);
        public Task CompleteLogoutAsync(AccountKey account, CancellationToken cancellationToken) => _inner.CompleteLogoutAsync(account, cancellationToken);
        public Task<bool> HasUnresolvedOperationsAsync(AccountKey account, CancellationToken cancellationToken) => _inner.HasUnresolvedOperationsAsync(account, cancellationToken);
        public Task<OperationRecord> CreateIntentAsync(OperationIntent intent, CancellationToken cancellationToken) => FailIntent
            ? Task.FromException<OperationRecord>(new IOException("fixture storage failure"))
            : _inner.CreateIntentAsync(intent, cancellationToken);
        public async Task<OperationRecord> MarkDispatchAsync(OperationRef operation, long expectedRevision, CancellationToken cancellationToken)
        {
            if (FailMarker) throw new IOException("fixture storage failure");
            var result = await _inner.MarkDispatchAsync(operation, expectedRevision, cancellationToken);
            AfterMarker?.Invoke();
            return result;
        }
        public async Task<OperationRecord> RecordAckAsync(OperationRef operation, long expectedRevision,
            OperationAcknowledgement acknowledgement, CancellationToken cancellationToken)
        {
            if (FailAck) throw new IOException("fixture storage failure");
            if (BeforeAck is { } before) await before(cancellationToken);
            return await _inner.RecordAckAsync(operation, expectedRevision, acknowledgement, cancellationToken);
        }
        public async Task<OperationRecord> RecordRejectedOrUnknownAsync(OperationRef operation, long expectedRevision,
            OperationPhase phase, ErrorInfo? error, CancellationToken cancellationToken)
        {
            if (BeforeFailure is { } before) await before(cancellationToken);
            return await _inner.RecordRejectedOrUnknownAsync(operation, expectedRevision, phase, error, cancellationToken);
        }
        public Task<OperationPage> ListOperationsAsync(AccountKey account, string? cursor, int limit, CancellationToken cancellationToken) =>
            _inner.ListOperationsAsync(account, cursor, limit, cancellationToken);
        public Task<OperationRecord?> GetOperationAsync(OperationRef operation, CancellationToken cancellationToken) =>
            _inner.GetOperationAsync(operation, cancellationToken);
        public Task<OperationRecord> SaveReconciliationAsync(OperationRef operation, long expectedRevision,
            OperationReconciliation reconciliation, CancellationToken cancellationToken) =>
            _inner.SaveReconciliationAsync(operation, expectedRevision, reconciliation, cancellationToken);
    }
}
