using Fileway.App;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Tests.Trash;

public sealed class TrashListControllerTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;
    private static readonly ConnectionSession Session = new(Guid.NewGuid(), new(Guid.NewGuid(), Guid.NewGuid(), "user"), "test", new(true, true, true, true, true, false), new());
    private static TrashItem Item(ConnectionSession session) => new(new(session.Account, "owned"), session.Account.ServerUserId, "/display/%2F.bin", "available", false, 10, "accurate");

    [Theory]
    [InlineData(FileConflictPolicy.Fail, false)]
    [InlineData(FileConflictPolicy.KeepBoth, false)]
    [InlineData(FileConflictPolicy.Replace, false)]
    [InlineData(FileConflictPolicy.Skip, true)]
    public async Task ConfirmedRestorePreservesPolicyAndSkipKeepsOriginalItem(FileConflictPolicy policy, bool skip)
    {
        var service = new Fake(Session); service.Result = new(OperationOutcome.Confirmed, skip, service.Row.DisplayPath);
        using var controller = new TrashListController(); controller.SetContext(service, service, Session);
        await controller.RefreshAsync(Token);
        var result = await controller.RestoreAsync(service.Row, policy, Token);
        Assert.Equal(policy, service.LastPolicy); Assert.Equal(OperationOutcome.Confirmed, result!.Outcome);
        Assert.Equal(skip ? 1 : 0, controller.Items.Count); Assert.Equal(1, service.Restores);
    }
    [Fact]
    public async Task MissingJournalOrReplacePermissionCannotDispatch()
    {
        var service = new Fake(Session);
        using var controller = new TrashListController(); controller.SetContext(service, null, Session);
        await controller.RefreshAsync(Token); Assert.False(controller.CanRestore(service.Row, FileConflictPolicy.Fail));
        controller.SetContext(service, service, Session with { Permissions = Session.Permissions with { Delete = false } });
        await controller.RefreshAsync(Token);
        Assert.True(controller.CanRestore(service.Row, FileConflictPolicy.Fail));
        Assert.False(controller.CanRestore(service.Row, FileConflictPolicy.Replace));
        Assert.Null(await controller.RestoreAsync(service.Row, FileConflictPolicy.Replace, Token)); Assert.Equal(0, service.Restores);
    }
    [Fact]
    public async Task RestorePreflightFindsOlderUnknownAndObservationDoesNotAuthorizeReplay()
    {
        var service = new Fake(Session);
        using var controller = new TrashListController(); controller.SetContext(service, service, Session);
        await controller.RefreshAsync(Token);
        var operation = new OperationRef(Session.Account, Guid.NewGuid());
        service.Unknown = new(new(operation, Session.Id, OperationKind.Restore, [], new(RecordId: "owned", Conflict: FileConflictPolicy.Fail)), OperationPhase.OutcomeUnknown, 1, DateTimeOffset.UtcNow, DateTimeOffset.UtcNow);
        Assert.Null(await controller.RestoreAsync(service.Row, FileConflictPolicy.Fail, Token));
        Assert.Equal(0, service.Restores); Assert.True(service.OlderPages > 0);
        await controller.ReconcileAsync(Assert.Single(controller.UnresolvedOperations), Token);
        Assert.False(controller.CanRestore(service.Row, FileConflictPolicy.Fail));
        service.Resolution = OperationResolution.NotSent;
        await controller.ReconcileAsync(Assert.Single(controller.UnresolvedOperations), Token);
        Assert.True(controller.CanRestore(service.Row, FileConflictPolicy.Fail)); Assert.Equal(0, service.Restores);
    }
    [Fact]
    public async Task RetiredRestoreKeepsFlightAndCannotRemoveNewAccountRow()
    {
        var old = new Fake(Session); using var controller = new TrashListController(); controller.SetContext(old, old, Session);
        await controller.RefreshAsync(Token);
        old.Pending = new(TaskCreationOptions.RunContinuationsAsynchronously);
        var restoring = controller.RestoreAsync(old.Row, FileConflictPolicy.Fail, Token);
        var nextSession = Session with { Id = Guid.NewGuid(), Account = Session.Account with { ServerUserId = "next" } };
        var next = new Fake(nextSession); controller.SetContext(next, next, nextSession);
        await controller.RefreshAsync(Token);
        Assert.False(controller.CanRestore(next.Row, FileConflictPolicy.Fail));
        Assert.Null(await controller.RestoreAsync(next.Row, FileConflictPolicy.Fail, Token));
        old.Pending.SetResult(new(OperationOutcome.Confirmed, false, old.Row.DisplayPath));
        await restoring.WaitAsync(TimeSpan.FromSeconds(5), Token);
        Assert.Same(next.Row, Assert.Single(controller.Items)); Assert.Equal(0, next.Restores);
        Assert.True(controller.CanRestore(next.Row, FileConflictPolicy.Fail));
    }
    [Fact]
    public async Task ConfirmedReconciliationRequiresFreshEvidenceAndCannotResendRestoredItem()
    {
        var service = new Fake(Session);
        var operation = new OperationRef(Session.Account, Guid.NewGuid());
        service.Unknown = new(new(operation, Session.Id, OperationKind.Restore, [], new(RecordId: "owned", Conflict: FileConflictPolicy.Fail)), OperationPhase.OutcomeUnknown, 1, DateTimeOffset.UtcNow, DateTimeOffset.UtcNow);
        using var controller = new TrashListController(); controller.SetContext(service, service, Session);
        await controller.RefreshAsync(Token); service.Resolution = OperationResolution.Confirmed;
        await controller.ReconcileAsync(Assert.Single(controller.UnresolvedOperations), Token);
        Assert.False(controller.CanRestore(service.Row, FileConflictPolicy.Fail));
        service.Unknown = service.Unknown with { Phase = OperationPhase.Acknowledged,
            Acknowledgement = new(OperationAckKind.TrashRestored, RecordId: "owned", Skipped: false, DisplayPath: service.Row.DisplayPath) };
        await controller.RefreshAsync(Token);
        Assert.Empty(controller.Items); Assert.Null(await controller.RestoreAsync(service.Row, FileConflictPolicy.Fail, Token)); Assert.Equal(0, service.Restores);
    }
    [Fact]
    public async Task ForeignListIsRejectedAndNeverGetsRestoreAuthority()
    {
        var service = new Fake(Session); service.Row = service.Row with { OwnerUserId = "foreign" };
        using var controller = new TrashListController(); controller.SetContext(service, service, Session);
        await controller.RefreshAsync(Token); Assert.Empty(controller.Items); Assert.NotNull(controller.Error);
        Assert.False(controller.CanRestore(service.Row, FileConflictPolicy.Fail)); Assert.Equal(0, service.Restores);
    }
    private sealed class Fake(ConnectionSession session) : ITrashService, IOperationHistoryService
    {
        internal TrashItem Row = Item(session);
        internal TrashRestoreResult Result = new(OperationOutcome.Confirmed, false, "/restored.bin");
        internal OperationRecord? Unknown;
        internal OperationResolution Resolution = OperationResolution.ObservedDesiredState;
        internal TaskCompletionSource<TrashRestoreResult>? Pending;
        internal int Restores, OlderPages;
        internal FileConflictPolicy LastPolicy;
        public Task<IReadOnlyList<TrashItem>> ListTrashAsync(AccountKey account, CancellationToken cancellationToken) => Task.FromResult<IReadOnlyList<TrashItem>>([Row]);
        public Task<TrashRestoreResult> RestoreTrashAsync(TrashItemRef item, FileConflictPolicy conflict, CancellationToken cancellationToken)
        { ++Restores; LastPolicy = conflict; return Pending?.Task ?? Task.FromResult(Result); }
        public Task<OperationPage> ListOperationsAsync(AccountKey account, string? cursor, int limit, CancellationToken cancellationToken)
        {
            if (Unknown is null) return Task.FromResult(new OperationPage([], null));
            if (cursor is null) return Task.FromResult(new OperationPage([], "older"));
            ++OlderPages; return Task.FromResult(new OperationPage([Unknown], null));
        }
        public Task<OperationReconciliation> ReconcileAsync(OperationRef operation, CancellationToken cancellationToken) => Task.FromResult(new OperationReconciliation(operation, Resolution));
    }
}
