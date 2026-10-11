using Fileway.Core.Contracts;
using Fileway.Infrastructure.Persistence;
using Microsoft.Data.Sqlite;

namespace Fileway.Infrastructure.Tests.Persistence;

public sealed class TransferTaskStoreVerificationTests
{
    private static readonly string Hash = new('a', 64);
    private static readonly TransferPhase[] ReleasingPhases =
    [
        TransferPhase.Paused, TransferPhase.WaitingNetwork, TransferPhase.WaitingAuthentication,
        TransferPhase.Conflict, TransferPhase.SourceChanged, TransferPhase.Canceled,
        TransferPhase.Failed, TransferPhase.OutcomeUnknown, TransferPhase.Skipped,
    ];
    private static readonly TransferPhase[] OwningPhases =
    [
        TransferPhase.Preparing, TransferPhase.Running, TransferPhase.Verifying,
        TransferPhase.Publishing, TransferPhase.Reconciling, TransferPhase.CancelRequested,
    ];
    private static readonly bool[] ProfileReplacementModes = [false, true];
    private static readonly string[] RouteQueryFields = ["TransferRouteFirstSql", "TransferRouteLastSql"];
    private static readonly string[] ClaimQueryFields = ["TransferActiveRunsSql", "TransferTargetHistorySql"];
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Theory]
    [InlineData(false, TransferIntent.Pause)]
    [InlineData(true, TransferIntent.Pause)]
    [InlineData(false, TransferIntent.Cancel)]
    [InlineData(true, TransferIntent.Cancel)]
    public async Task ControlIntentDoesNotLoseLatePublicationAcknowledgement(bool sqlite, TransferIntent intent)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var running = await RunningUploadAsync(store);
        var controlled = await store.SetTransferIntentAsync(running.Plan.Reference, running.Revision, intent, Token);
        Assert.Equal(running.Checkpoint, controlled.Checkpoint);
        Assert.Equal(running.Run, controlled.Run);
        Assert.Equal(running.Generation, controlled.Generation);
        Assert.Equal(running.Revision + 1, controlled.Revision);

        var actual = running.Checkpoint with { Phase = TransferPhase.Completed, ConfirmedOffset = 10, RemotePublished = true };
        var stale = await Assert.ThrowsAsync<FilewayException>(() => store.SaveTransferCheckpointAsync(
            running.Run!.Value, running.Revision, actual, true, Token));
        Assert.Equal(ErrorCode.Conflict, stale.Error.Code);
        Assert.Equal(controlled, await ReadAsync(store, running.Plan.Reference));
        var acknowledged = await store.SaveTransferCheckpointAsync(running.Run!.Value, controlled.Revision, actual, true, Token);
        Assert.Equal(intent, acknowledged.Intent);
        Assert.Equal(actual, acknowledged.Checkpoint);
        Assert.Null(acknowledged.Run);
        Assert.False(await store.HasProtectedTransfersAsync(running.Plan.Reference.Account, Token));
        Assert.Equal(running.Plan, acknowledged.Plan);
        Assert.Equal(running.CreatedAt, acknowledged.CreatedAt);

        // An already completed task is immutable even when a user control request arrives afterward.
        var terminal = await store.SetTransferIntentAsync(acknowledged.Plan.Reference, acknowledged.Revision,
            TransferIntent.Run, Token);
        Assert.Equal(acknowledged, terminal);
        await Assert.ThrowsAsync<FilewayException>(() => store.SetTransferIntentAsync(acknowledged.Plan.Reference,
            controlled.Revision, TransferIntent.Cancel, Token));
        Assert.Equal(acknowledged, await ReadAsync(store, acknowledged.Plan.Reference));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task AcknowledgementWinningCasLeavesCancelRetryBoundToActualEvidence(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var running = await RunningUploadAsync(store);
        var acknowledged = await store.SaveTransferCheckpointAsync(running.Run!.Value, running.Revision,
            running.Checkpoint with { ConfirmedOffset = 7 }, false, Token);
        await Assert.ThrowsAsync<FilewayException>(() => store.SetTransferIntentAsync(running.Plan.Reference,
            running.Revision, TransferIntent.Cancel, Token));
        var canceled = await store.SetTransferIntentAsync(running.Plan.Reference, acknowledged.Revision, TransferIntent.Cancel, Token);
        Assert.Equal(7, canceled.Checkpoint.ConfirmedOffset);
        Assert.Equal(TransferPhase.Running, canceled.Checkpoint.Phase);
        Assert.Equal(acknowledged.Run, canceled.Run);
        await Assert.ThrowsAsync<FilewayException>(() => store.SetTransferIntentAsync(running.Plan.Reference,
            canceled.Revision, TransferIntent.Pause, Token));
        await Assert.ThrowsAsync<FilewayException>(() => store.SetTransferIntentAsync(running.Plan.Reference,
            canceled.Revision, TransferIntent.Run, Token));
        Assert.Equal(canceled, await ReadAsync(store, running.Plan.Reference));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task RecoveryFencesBothOldRunIdAndGenerationEvenAtFreshRevision(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var running = await RunningUploadAsync(store);
        var original = running.Run!.Value;
        await Assert.ThrowsAsync<FilewayException>(() => store.ClaimTransferAsync(running.Plan.Reference,
            running.Revision, original.Id, true, Token));
        var recovered = await store.ClaimTransferAsync(running.Plan.Reference, running.Revision, Guid.NewGuid(), true, Token);
        Assert.Equal(TransferPhase.Reconciling, recovered.Checkpoint.Phase);
        Assert.Equal(running.Checkpoint with { Phase = TransferPhase.Reconciling }, recovered.Checkpoint);
        Assert.Equal(original.Generation + 1, recovered.Generation);
        var current = recovered.Run!.Value;
        await Assert.ThrowsAsync<FilewayException>(() => store.SaveTransferCheckpointAsync(original,
            recovered.Revision, recovered.Checkpoint, false, Token));
        await Assert.ThrowsAsync<FilewayException>(() => store.SaveTransferCheckpointAsync(current with { Id = original.Id },
            recovered.Revision, recovered.Checkpoint, false, Token));
        await Assert.ThrowsAsync<FilewayException>(() => store.SaveTransferCheckpointAsync(current with { Generation = original.Generation },
            recovered.Revision, recovered.Checkpoint, false, Token));
        Assert.Equal(recovered, await ReadAsync(store, recovered.Plan.Reference));
        var safePrefix = await store.SaveTransferCheckpointAsync(current, recovered.Revision,
            recovered.Checkpoint with { Phase = TransferPhase.Running, ConfirmedOffset = 1 }, false, Token);
        Assert.Equal(1, safePrefix.Checkpoint.ConfirmedOffset);
        await Assert.ThrowsAsync<FilewayException>(() => store.SaveTransferCheckpointAsync(current, safePrefix.Revision,
            safePrefix.Checkpoint with { ConfirmedOffset = 0 }, false, Token));
        Assert.Equal(safePrefix, await ReadAsync(store, safePrefix.Plan.Reference));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ReleasedRunIdCannotBeReusedAfterReopenAndRecoveryPreservesCancelIntent(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var running = await RunningUploadAsync(store);
        var old = running.Run!.Value;
        var paused = await store.SaveTransferCheckpointAsync(old, running.Revision,
            running.Checkpoint with { Phase = TransferPhase.Paused }, true, Token);
        var reopened = sqlite ? Store(true, directory) : store;
        Assert.Equal(paused, await ReadAsync(reopened, paused.Plan.Reference));
        await Assert.ThrowsAsync<FilewayException>(() => reopened.ClaimTransferAsync(paused.Plan.Reference,
            paused.Revision, old.Id, false, Token));
        await Assert.ThrowsAsync<FilewayException>(() => reopened.ClaimTransferAsync(paused.Plan.Reference,
            paused.Revision, old.Id, true, Token));
        var cancel = await reopened.SetTransferIntentAsync(paused.Plan.Reference, paused.Revision, TransferIntent.Cancel, Token);
        await Assert.ThrowsAsync<FilewayException>(() => reopened.ClaimTransferAsync(cancel.Plan.Reference,
            cancel.Revision, Guid.NewGuid(), false, Token));
        var recovered = await reopened.ClaimTransferAsync(cancel.Plan.Reference, cancel.Revision, Guid.NewGuid(), true, Token);
        Assert.Equal(TransferIntent.Cancel, recovered.Intent);
        Assert.Equal(cancel.Checkpoint with { Phase = TransferPhase.Reconciling }, recovered.Checkpoint);
        Assert.Equal(old.Generation + 1, recovered.Generation);
        await Assert.ThrowsAsync<FilewayException>(() => reopened.SaveTransferCheckpointAsync(old, recovered.Revision,
            recovered.Checkpoint, false, Token));
        Assert.Equal(recovered, await ReadAsync(reopened, recovered.Plan.Reference));
    }

    [Theory]
    [InlineData(false, TransferPhase.Conflict)]
    [InlineData(true, TransferPhase.Conflict)]
    [InlineData(false, TransferPhase.SourceChanged)]
    [InlineData(true, TransferPhase.SourceChanged)]
    public async Task ReleasedConflictRecoveryRequiresCancelIntentWithoutOtherProtection(bool sqlite, TransferPhase phase)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var running = await RunningUploadAsync(store);
        var released = await store.SaveTransferCheckpointAsync(running.Run!.Value, running.Revision,
            running.Checkpoint with { Phase = phase }, true, Token);
        await Assert.ThrowsAsync<FilewayException>(() => store.ClaimTransferAsync(released.Plan.Reference,
            released.Revision, Guid.NewGuid(), true, Token));
        var paused = await store.SetTransferIntentAsync(released.Plan.Reference, released.Revision, TransferIntent.Pause, Token);
        await Assert.ThrowsAsync<FilewayException>(() => store.ClaimTransferAsync(paused.Plan.Reference,
            paused.Revision, Guid.NewGuid(), true, Token));
        var canceled = await store.SetTransferIntentAsync(paused.Plan.Reference, paused.Revision, TransferIntent.Cancel, Token);
        var recovered = await store.ClaimTransferAsync(canceled.Plan.Reference, canceled.Revision, Guid.NewGuid(), true, Token);
        Assert.Equal(TransferIntent.Cancel, recovered.Intent);
        Assert.Equal(canceled.Checkpoint with { Phase = TransferPhase.Reconciling }, recovered.Checkpoint);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task SameSourceRevisionCannotChangeRouteUnderQueuedTaskButDisplayMetadataCanChange(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var profiles = (IProfileStore)store;
        var saved = Saved();
        await profiles.SaveAsync(saved, Token);
        var queued = await store.CreateTransferAsync(Plan(saved.Account), Token);
        var changes = new List<SavedConnection>
        {
            saved with { Profile = saved.Profile with { BaseAddress = new Uri("https://other-fixture.invalid/") } },
            saved with { Profile = saved.Profile with { Network = NetworkMode.SystemTailnet } },
            saved with { Profile = saved.Profile with { Proxy = new(ProxyMode.System) } },
        };
        foreach (var change in changes)
        {
            var rejection = await Assert.ThrowsAsync<FilewayException>(() => profiles.SaveAsync(change, Token));
            Assert.Equal(ErrorCode.SourceChanged, rejection.Error.Code);
            Assert.Equal(saved, await profiles.GetAsync(saved.Profile.Id, Token));
            Assert.Equal(queued, await ReadAsync(store, queued.Plan.Reference));
        }
        var displayed = saved with { Profile = saved.Profile with { DisplayName = "updated display" }, Username = "updated-user", KeepSignedIn = false };
        await profiles.SaveAsync(displayed, Token);
        Assert.Equal(displayed, await profiles.GetAsync(saved.Profile.Id, Token));
        var claimed = await store.ClaimTransferAsync(queued.Plan.Reference, queued.Revision, Guid.NewGuid(), false, Token);
        Assert.Equal(queued.Plan, claimed.Plan);
        Assert.NotNull(claimed.Run);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task TaskUuidIsGlobalWhileBatchOrdinalAndReadsRemainBoundToFullOwner(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var first = Saved();
        var second = Saved();
        await ((IProfileStore)store).SaveAsync(first, Token);
        await ((IProfileStore)store).SaveAsync(second, Token);
        var batch = Guid.NewGuid();
        var plan = Plan(first.Account) with { BatchId = batch, Ordinal = 2 };
        var original = await store.CreateTransferAsync(plan, Token);
        var reopened = sqlite ? Store(true, directory) : store;
        var foreignPlan = Plan(second.Account) with { Reference = new(second.Account, plan.Reference.Id) };
        await Assert.ThrowsAsync<FilewayException>(() => reopened.CreateTransferAsync(foreignPlan, Token));
        Assert.Null(await reopened.GetTransferAsync(foreignPlan.Reference, Token));
        await Assert.ThrowsAsync<FilewayException>(() => reopened.SetTransferIntentAsync(foreignPlan.Reference,
            original.Revision, TransferIntent.Cancel, Token));
        await Assert.ThrowsAsync<FilewayException>(() => reopened.ClaimTransferAsync(foreignPlan.Reference,
            original.Revision, Guid.NewGuid(), false, Token));
        Assert.Equal(original, await ReadAsync(reopened, plan.Reference));
        await Assert.ThrowsAsync<FilewayException>(() => reopened.CreateTransferAsync(
            Plan(first.Account) with { BatchId = batch, Ordinal = 2 }, Token));
        var otherBatchOwner = await reopened.CreateTransferAsync(Plan(second.Account) with { BatchId = batch, Ordinal = 2 }, Token);
        Assert.Equal(second.Account, otherBatchOwner.Plan.Reference.Account);
        Assert.Equal(otherBatchOwner, Assert.Single((await reopened.ListTransfersAsync(second.Account, null, 10, Token)).Items));
        Assert.Equal(original, Assert.Single((await reopened.ListTransfersAsync(first.Account, null, 10, Token)).Items));
    }

    [Theory]
    [InlineData(false, false)]
    [InlineData(true, false)]
    [InlineData(false, true)]
    [InlineData(true, true)]
    public async Task ForgottenQueuedOrTerminalHistoryKeepsRouteIdentityAcrossServerUserChanges(bool sqlite, bool terminal)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var saved = Saved();
        var profiles = (IProfileStore)store;
        await profiles.SaveAsync(saved, Token);
        var retained = await store.CreateTransferAsync(Plan(saved.Account), Token);
        if (terminal)
        {
            var claimed = await store.ClaimTransferAsync(retained.Plan.Reference, retained.Revision, Guid.NewGuid(), false, Token);
            var running = await store.SaveTransferCheckpointAsync(claimed.Run!.Value, claimed.Revision,
                new(TransferPhase.Running, new(10, NativeFileIdentity: "historical-source"), 0), false, Token);
            retained = await store.SaveTransferCheckpointAsync(running.Run!.Value, running.Revision,
                running.Checkpoint with { Phase = TransferPhase.Completed, ConfirmedOffset = 10, RemotePublished = true }, true, Token);
        }
        await profiles.ForgetAsync(saved.Account, Token);
        var reopened = sqlite ? Store(true, directory) : store;
        profiles = (IProfileStore)reopened;
        var changedRoutes = new List<ConnectionProfile>
        {
            saved.Profile with { BaseAddress = new Uri("https://changed-fixture.invalid/") },
            saved.Profile with { Network = NetworkMode.SystemTailnet },
            saved.Profile with { Proxy = new(ProxyMode.Manual, new Uri("http://proxy-fixture.invalid:8080/")) },
        };
        foreach (var profile in changedRoutes)
        {
            foreach (bool otherUser in ProfileReplacementModes)
            {
                var changed = saved with { Profile = profile, ServerUserId = otherUser ? "replacement-user" : saved.ServerUserId };
                var rejection = await Assert.ThrowsAsync<FilewayException>(() => profiles.SaveAsync(changed, Token));
                Assert.Equal(ErrorCode.SourceChanged, rejection.Error.Code);
                Assert.Null(await profiles.GetAsync(saved.Profile.Id, Token));
                Assert.Equal(retained, await ReadAsync(reopened, retained.Plan.Reference));
            }
        }
        var sameRouteOtherUser = saved with { ServerUserId = "replacement-user" };
        await profiles.SaveAsync(sameRouteOtherUser, Token);
        Assert.Equal(sameRouteOtherUser, await profiles.GetAsync(saved.Profile.Id, Token));
        Assert.Empty((await reopened.ListTransfersAsync(sameRouteOtherUser.Account, null, 10, Token)).Items);
        await profiles.ForgetAsync(sameRouteOtherUser.Account, Token);
        var newSource = saved with
        { Profile = saved.Profile with { SourceRevision = Guid.NewGuid(), BaseAddress = new Uri("https://new-source-fixture.invalid/") } };
        await profiles.SaveAsync(newSource, Token);
        Assert.Equal(newSource, await profiles.GetAsync(saved.Profile.Id, Token));
        Assert.Empty((await reopened.ListTransfersAsync(newSource.Account, null, 10, Token)).Items);
        await Assert.ThrowsAsync<FilewayException>(() => reopened.ClaimTransferAsync(retained.Plan.Reference,
            retained.Revision, Guid.NewGuid(), false, Token));
        await profiles.ForgetAsync(newSource.Account, Token);
        await profiles.SaveAsync(saved, Token);
        Assert.Equal(saved, await profiles.GetAsync(saved.Profile.Id, Token));
        if (terminal)
        {
            await Assert.ThrowsAsync<FilewayException>(() => reopened.ClaimTransferAsync(retained.Plan.Reference,
                retained.Revision, Guid.NewGuid(), false, Token));
            Assert.Equal(retained, await ReadAsync(reopened, retained.Plan.Reference));
        }
        else
        {
            var claimed = await reopened.ClaimTransferAsync(retained.Plan.Reference, retained.Revision, Guid.NewGuid(), false, Token);
            Assert.Equal(retained.Plan, claimed.Plan);
            Assert.NotNull(claimed.Run);
        }
    }

    [Fact]
    public async Task SqliteConcurrentInstancesEnforceGlobalCapacityAndRecoveryCountsReplacementOnce()
    {
        using var directory = new StorageTestDirectory();
        var store = Store(true, directory);
        var saved = Saved();
        await ((IProfileStore)store).SaveAsync(saved, Token);
        var tasks = new List<TransferTaskRecord>();
        for (int index = 0; index < 6; index++)
            tasks.Add(await store.CreateTransferAsync(Plan(saved.Account), Token));
        var claims = await Task.WhenAll(tasks.Select(task => TryAsync(() => Store(true, directory).ClaimTransferAsync(
            task.Plan.Reference, task.Revision, Guid.NewGuid(), false, Token))));
        Assert.Equal(3, claims.Count(result => result));
        var records = (await store.ListTransfersAsync(saved.Account, null, 20, Token)).Items;
        Assert.Equal(3, records.Count(record => record.Run is not null));
        Assert.Equal(3, records.Count(record => record.Checkpoint.Phase == TransferPhase.Queued && record.Revision == 0));
        var active = records.First(record => record.Run is not null);
        var replacement = await Store(true, directory).ClaimTransferAsync(active.Plan.Reference, active.Revision,
            Guid.NewGuid(), true, Token);
        Assert.Equal(active.Generation + 1, replacement.Generation);
        Assert.Equal(3, (await store.ListTransfersAsync(saved.Account, null, 20, Token)).Items.Count(record => record.Run is not null));
        await Assert.ThrowsAsync<FilewayException>(() => store.SaveTransferCheckpointAsync(active.Run!.Value,
            replacement.Revision, replacement.Checkpoint, false, Token));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ConcurrentClaimsAtOneRevisionElectExactlyOneRunWithoutStealingIt(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var saved = Saved();
        await ((IProfileStore)store).SaveAsync(saved, Token);
        var queued = await store.CreateTransferAsync(Plan(saved.Account), Token);
        var other = sqlite ? Store(true, directory) : store;
        var firstRunId = Guid.NewGuid();
        var secondRunId = Guid.NewGuid();
        var claims = await TryConcurrentlyAsync(
            () => store.ClaimTransferAsync(queued.Plan.Reference, queued.Revision, firstRunId, false, Token),
            () => other.ClaimTransferAsync(queued.Plan.Reference, queued.Revision, secondRunId, false, Token));
        Assert.Single(claims, success => success);
        var elected = await ReadAsync(other, queued.Plan.Reference);
        Assert.Equal(claims[0] ? firstRunId : secondRunId, elected.Run!.Value.Id);
        Assert.Equal(1, elected.Revision);
        Assert.Equal(1, elected.Generation);
        Assert.Equal(TransferPhase.Preparing, elected.Checkpoint.Phase);
        Assert.Equal(queued.Plan, elected.Plan);
        await Assert.ThrowsAsync<FilewayException>(() => store.ClaimTransferAsync(elected.Plan.Reference,
            elected.Revision, Guid.NewGuid(), false, Token));
        Assert.Equal(elected, await ReadAsync(store, elected.Plan.Reference));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task DownloadReservationCrossesAccountsAndRemainsUntilUncertaintyIsResolved(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var competitor = sqlite ? Store(true, directory) : store;
        var firstAccount = Saved();
        var secondAccount = Saved();
        await ((IProfileStore)store).SaveAsync(firstAccount, Token);
        await ((IProfileStore)store).SaveAsync(secondAccount, Token);
        var first = await store.CreateTransferAsync(Plan(firstAccount.Account, TransferDirection.Download) with
        { LocalPath = @"C:\transfer-fixtures\downloads\shared.bin" }, Token);
        var second = await competitor.CreateTransferAsync(Plan(secondAccount.Account, TransferDirection.Download) with
        { LocalPath = @"c:\TRANSFER-FIXTURES\downloads\shared.bin" }, Token);
        var claimed = await store.ClaimTransferAsync(first.Plan.Reference, 0, Guid.NewGuid(), false, Token);
        await Assert.ThrowsAsync<FilewayException>(() => competitor.ClaimTransferAsync(second.Plan.Reference, 0, Guid.NewGuid(), false, Token));
        var unknown = await store.SaveTransferCheckpointAsync(claimed.Run!.Value, claimed.Revision,
            new(TransferPhase.OutcomeUnknown, Uncertainty: TransferUncertainty.LocalPublish), true, Token);
        Assert.Null(unknown.Run);
        Assert.True(await competitor.HasProtectedTransfersAsync(firstAccount.Account, Token));
        await Assert.ThrowsAsync<FilewayException>(() => competitor.ClaimTransferAsync(second.Plan.Reference, 0, Guid.NewGuid(), false, Token));
        var recovering = await competitor.ClaimTransferAsync(first.Plan.Reference, unknown.Revision, Guid.NewGuid(), true, Token);
        var resolved = await competitor.SaveTransferCheckpointAsync(recovering.Run!.Value, recovering.Revision,
            recovering.Checkpoint with { Phase = TransferPhase.Paused, Uncertainty = TransferUncertainty.None }, true, Token);
        Assert.False(await store.HasProtectedTransfersAsync(firstAccount.Account, Token));
        Assert.Null(resolved.Run);
        Assert.NotNull((await store.ClaimTransferAsync(second.Plan.Reference, 0, Guid.NewGuid(), false, Token)).Run);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task UploadReservationCanonicalizesPercentHexButPreservesWireSpellingAndAccount(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var saved = Saved();
        var foreign = Saved();
        await ((IProfileStore)store).SaveAsync(saved, Token);
        await ((IProfileStore)store).SaveAsync(foreign, Token);
        var lower = Plan(saved.Account) with { Remote = Remote(saved.Account, "/folder/%ff.bin") };
        var upper = Plan(saved.Account) with { Remote = Remote(saved.Account, "/folder/%FF.bin") };
        var distinctCase = Plan(saved.Account) with { Remote = Remote(saved.Account, "/FOLDER/%FF.bin") };
        var distinctAccount = Plan(foreign.Account) with { Remote = Remote(foreign.Account, upper.Remote.WirePath) };
        var first = await store.CreateTransferAsync(lower, Token);
        var collision = await store.CreateTransferAsync(upper, Token);
        var caseTask = await store.CreateTransferAsync(distinctCase, Token);
        var foreignTask = await store.CreateTransferAsync(distinctAccount, Token);
        var claimed = await store.ClaimTransferAsync(first.Plan.Reference, 0, Guid.NewGuid(), false, Token);
        await Assert.ThrowsAsync<FilewayException>(() => store.ClaimTransferAsync(collision.Plan.Reference, 0, Guid.NewGuid(), false, Token));
        Assert.Equal("/folder/%ff.bin", (await ReadAsync(store, first.Plan.Reference)).Plan.Remote.WirePath);
        Assert.NotNull((await store.ClaimTransferAsync(caseTask.Plan.Reference, 0, Guid.NewGuid(), false, Token)).Run);
        Assert.NotNull((await store.ClaimTransferAsync(foreignTask.Plan.Reference, 0, Guid.NewGuid(), false, Token)).Run);
        await store.SaveTransferCheckpointAsync(claimed.Run!.Value, claimed.Revision, new(TransferPhase.Paused), true, Token);
        Assert.NotNull((await store.ClaimTransferAsync(collision.Plan.Reference, 0, Guid.NewGuid(), false, Token)).Run);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ProtectedForgetAndAccountReplacementRaceCannotOrphanAClaim(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var profiles = (IProfileStore)store;
        foreach (bool replace in ProfileReplacementModes)
        {
            var saved = Saved();
            await profiles.SaveAsync(saved, Token);
            var task = await store.CreateTransferAsync(Plan(saved.Account), Token);
            var other = sqlite ? Store(true, directory) : store;
            var replacement = saved with { Profile = saved.Profile with { SourceRevision = Guid.NewGuid() }, ServerUserId = "replacement" };
            var results = await TryConcurrentlyAsync(
                () => store.ClaimTransferAsync(task.Plan.Reference, 0, Guid.NewGuid(), false, Token),
                () => replace ? ((IProfileStore)other).SaveAsync(replacement, Token) : ((IProfileStore)other).ForgetAsync(saved.Account, Token));
            Assert.Single(results, success => success);
            var retained = await ReadAsync(other, task.Plan.Reference);
            var profile = await profiles.GetAsync(saved.Profile.Id, Token);
            if (results[0])
            {
                Assert.NotNull(retained.Run);
                Assert.Equal(saved, profile);
                Assert.True(await other.HasProtectedTransfersAsync(saved.Account, Token));
                await store.SaveTransferCheckpointAsync(retained.Run!.Value, retained.Revision, new(TransferPhase.Paused), true, Token);
            }
            else
            {
                Assert.Null(retained.Run);
                Assert.Equal(0, retained.Revision);
                Assert.Equal(replace ? replacement : null, profile);
                Assert.Equal(saved.Account, retained.Plan.Reference.Account);
            }
        }
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task UncertainReleasedRunProtectsProfileButResolvedHistorySurvivesForget(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var saved = Saved();
        var running = await RunningUploadAsync(store, saved);
        var profiles = (IProfileStore)store;
        var uncertain = await store.SaveTransferCheckpointAsync(running.Run!.Value, running.Revision,
            running.Checkpoint with { Phase = TransferPhase.OutcomeUnknown, Uncertainty = TransferUncertainty.RemoteWrite }, true, Token);
        Assert.Null(uncertain.Run);
        await Assert.ThrowsAsync<FilewayException>(() => profiles.ForgetAsync(saved.Account, Token));
        var replacement = saved with { Profile = saved.Profile with { SourceRevision = Guid.NewGuid() } };
        await Assert.ThrowsAsync<FilewayException>(() => profiles.SaveAsync(replacement, Token));
        Assert.Equal(saved, await profiles.GetAsync(saved.Profile.Id, Token));
        Assert.Equal(uncertain, await ReadAsync(store, running.Plan.Reference));
        var recovering = await store.ClaimTransferAsync(running.Plan.Reference, uncertain.Revision, Guid.NewGuid(), true, Token);
        var resolved = await store.SaveTransferCheckpointAsync(recovering.Run!.Value, recovering.Revision,
            recovering.Checkpoint with { Phase = TransferPhase.Paused, Uncertainty = TransferUncertainty.None }, true, Token);
        await profiles.ForgetAsync(saved.Account, Token);
        Assert.Null(await profiles.GetAsync(saved.Profile.Id, Token));
        Assert.Equal(resolved, await ReadAsync(store, running.Plan.Reference));
        Assert.Equal(resolved, Assert.Single((await store.ListTransfersAsync(saved.Account, null, 10, Token)).Items));
        await profiles.SaveAsync(replacement, Token);
        Assert.Empty((await store.ListTransfersAsync(replacement.Account, null, 10, Token)).Items);
        await Assert.ThrowsAsync<FilewayException>(() => store.ClaimTransferAsync(resolved.Plan.Reference,
            resolved.Revision, Guid.NewGuid(), false, Token));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task LogoutBlocksNewOwnershipWhileControlAndCurrentRunEvidenceRemainWritable(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var saved = Saved();
        var running = await RunningUploadAsync(store, saved);
        var queued = await store.CreateTransferAsync(Plan(saved.Account), Token);
        var profiles = (IProfileStore)store;
        await profiles.BeginLogoutAsync(saved.Account, Token);
        var other = sqlite ? Store(true, directory) : store;
        await Assert.ThrowsAsync<FilewayException>(() => other.CreateTransferAsync(Plan(saved.Account), Token));
        await Assert.ThrowsAsync<FilewayException>(() => other.ClaimTransferAsync(queued.Plan.Reference, queued.Revision, Guid.NewGuid(), false, Token));
        await Assert.ThrowsAsync<FilewayException>(() => other.ClaimTransferAsync(running.Plan.Reference, running.Revision, Guid.NewGuid(), true, Token));
        var cancel = await other.SetTransferIntentAsync(running.Plan.Reference, running.Revision, TransferIntent.Cancel, Token);
        var acknowledged = await other.SaveTransferCheckpointAsync(running.Run!.Value, cancel.Revision,
            running.Checkpoint with { Phase = TransferPhase.Completed, ConfirmedOffset = 10, RemotePublished = true }, true, Token);
        await profiles.ForgetAsync(saved.Account, Token);
        Assert.Null(await profiles.GetAsync(saved.Profile.Id, Token));
        Assert.Equal(acknowledged, await ReadAsync(other, running.Plan.Reference));
        Assert.Equal(saved.Account, Assert.Single(await profiles.ListPendingLogoutsAsync(Token)));
        Assert.Equal(TransferIntent.Cancel, acknowledged.Intent);
        Assert.True(acknowledged.Checkpoint.RemotePublished);
        Assert.False(acknowledged.Checkpoint.RemoteCanceled);
        Assert.Equal(queued, await ReadAsync(other, queued.Plan.Reference));
    }

    [Fact]
    public async Task CurrentRunEvidenceAfterMissingProfileDoesNotRecreateConnectionOrLogoutState()
    {
        using var directory = new StorageTestDirectory();
        var store = Store(true, directory);
        var running = await RunningUploadAsync(store);
        using var database = Database(directory.DatabasePath);
        // Simulate externally missing metadata in this disposable database; Forget itself must protect a run.
        Execute(database, "DELETE FROM saved_connections;");
        var reopened = Store(true, directory);
        var canceled = await reopened.SetTransferIntentAsync(running.Plan.Reference, running.Revision, TransferIntent.Cancel, Token);
        var completed = await reopened.SaveTransferCheckpointAsync(running.Run!.Value, canceled.Revision,
            running.Checkpoint with { Phase = TransferPhase.Completed, ConfirmedOffset = 10, RemotePublished = true }, true, Token);
        Assert.Equal(TransferPhase.Completed, completed.Checkpoint.Phase);
        Assert.Equal(TransferIntent.Cancel, completed.Intent);
        Assert.Empty(await ((IProfileStore)reopened).ListAsync(Token));
        Assert.Empty(await ((IProfileStore)reopened).ListPendingLogoutsAsync(Token));
        Assert.Equal(0L, Scalar(database, "SELECT COUNT(*) FROM saved_connections"));
        Assert.Equal(0L, Scalar(database, "SELECT COUNT(*) FROM pending_logouts"));
        Assert.Equal(completed, await ReadAsync(reopened, running.Plan.Reference));
    }

    [Theory]
    [InlineData(false, TransferDirection.Upload)]
    [InlineData(true, TransferDirection.Upload)]
    [InlineData(false, TransferDirection.Download)]
    [InlineData(true, TransferDirection.Download)]
    public async Task CancelRequestedStillAcceptsActualCompletionProofInEitherDirection(bool sqlite, TransferDirection direction)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var saved = Saved();
        await ((IProfileStore)store).SaveAsync(saved, Token);
        var plan = Plan(saved.Account, direction);
        await store.CreateTransferAsync(plan, Token);
        var claimed = await store.ClaimTransferAsync(plan.Reference, 0, Guid.NewGuid(), false, Token);
        var source = new TransferSourceSnapshot(10,
            SourceVersion: direction == TransferDirection.Download ? "v1" : null,
            NativeFileIdentity: direction == TransferDirection.Upload ? "native-source" : null);
        var running = await store.SaveTransferCheckpointAsync(claimed.Run!.Value, claimed.Revision,
            new(TransferPhase.Running, source, 3), false, Token);
        var cancel = await store.SetTransferIntentAsync(plan.Reference, running.Revision, TransferIntent.Cancel, Token);
        var requested = await store.SaveTransferCheckpointAsync(cancel.Run!.Value, cancel.Revision,
            cancel.Checkpoint with { Phase = TransferPhase.CancelRequested }, false, Token);
        var evidence = requested.Checkpoint with
        {
            Phase = TransferPhase.Completed, ConfirmedOffset = 10,
            RemotePublished = direction == TransferDirection.Upload,
            Files = direction == TransferDirection.Download ? new(PublishedFileIdentity: "published-native-id") : null,
            LocalSha256 = direction == TransferDirection.Download ? Hash : null,
            ServerSha256 = direction == TransferDirection.Download ? Hash : null,
        };
        var completed = await store.SaveTransferCheckpointAsync(requested.Run!.Value, requested.Revision, evidence, true, Token);
        Assert.Equal(evidence, completed.Checkpoint);
        Assert.Equal(TransferIntent.Cancel, completed.Intent);
        Assert.Null(completed.Run);
        Assert.False(completed.Checkpoint.RemoteCanceled);
    }

    [Theory]
    [InlineData(false, TransferDirection.Upload)]
    [InlineData(true, TransferDirection.Upload)]
    [InlineData(false, TransferDirection.Download)]
    [InlineData(true, TransferDirection.Download)]
    public async Task CancellationRemainsRecoverableUntilUncertaintyIsResolvedThenKnownPartMayExpire(bool sqlite, TransferDirection direction)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var saved = Saved();
        await ((IProfileStore)store).SaveAsync(saved, Token);
        var created = await store.CreateTransferAsync(Plan(saved.Account, direction), Token);
        var claimed = await store.ClaimTransferAsync(created.Plan.Reference, created.Revision, Guid.NewGuid(), false, Token);
        var source = new TransferSourceSnapshot(10,
            SourceVersion: direction == TransferDirection.Download ? "v1" : null,
            NativeFileIdentity: direction == TransferDirection.Upload ? "native-source" : null);
        var running = await store.SaveTransferCheckpointAsync(claimed.Run!.Value, claimed.Revision,
            new(TransferPhase.Running, source, 3), false, Token);
        var canceled = await store.SetTransferIntentAsync(created.Plan.Reference, running.Revision, TransferIntent.Cancel, Token);
        var uncertainty = direction == TransferDirection.Upload ? TransferUncertainty.RemoteWrite : TransferUncertainty.LocalPublish;
        var uncertainCanceled = canceled.Checkpoint with { Phase = TransferPhase.Canceled, Uncertainty = uncertainty };
        await Assert.ThrowsAsync<FilewayException>(() => store.SaveTransferCheckpointAsync(canceled.Run!.Value,
            canceled.Revision, uncertainCanceled, true, Token));
        Assert.Equal(canceled, await ReadAsync(store, canceled.Plan.Reference));
        var unknown = await store.SaveTransferCheckpointAsync(canceled.Run!.Value, canceled.Revision,
            uncertainCanceled with { Phase = TransferPhase.OutcomeUnknown }, true, Token);
        Assert.Null(unknown.Run);
        Assert.True(await store.HasProtectedTransfersAsync(saved.Account, Token));
        var recovery = await store.ClaimTransferAsync(unknown.Plan.Reference, unknown.Revision, Guid.NewGuid(), true, Token);
        Assert.Equal(uncertainty, recovery.Checkpoint.Uncertainty);
        await Assert.ThrowsAsync<FilewayException>(() => store.SaveTransferCheckpointAsync(recovery.Run!.Value,
            recovery.Revision, recovery.Checkpoint with { Phase = TransferPhase.Canceled }, true, Token));
        Assert.Equal(recovery, await ReadAsync(store, recovery.Plan.Reference));
        var resolved = await store.SaveTransferCheckpointAsync(recovery.Run!.Value, recovery.Revision,
            recovery.Checkpoint with { Phase = TransferPhase.Canceled, Uncertainty = TransferUncertainty.None }, true, Token);
        Assert.Equal(TransferIntent.Cancel, resolved.Intent);
        Assert.False(resolved.Checkpoint.RemoteCanceled);
        Assert.Null(resolved.Run);
        Assert.False(await store.HasProtectedTransfersAsync(saved.Account, Token));
        await ((IProfileStore)store).ForgetAsync(saved.Account, Token);
        Assert.Equal(resolved, await ReadAsync(store, resolved.Plan.Reference));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task PinnedSourceOffsetAndHashRejectChangesWithoutPartiallySavingEvidence(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var running = await RunningUploadAsync(store);
        var pinned = await store.SaveTransferCheckpointAsync(running.Run!.Value, running.Revision,
            running.Checkpoint with { LocalSha256 = Hash.ToUpperInvariant() }, false, Token);
        Assert.Equal(Hash, pinned.Checkpoint.LocalSha256);
        var invalid = new List<TransferCheckpoint>
        {
            pinned.Checkpoint with { Source = pinned.Checkpoint.Source! with { Length = 11 } },
            pinned.Checkpoint with { Source = pinned.Checkpoint.Source! with { NativeFileIdentity = "replacement-source" } },
            pinned.Checkpoint with { Source = null },
            pinned.Checkpoint with { ConfirmedOffset = 2 },
            pinned.Checkpoint with { ConfirmedOffset = 11 },
            pinned.Checkpoint with { LocalSha256 = new string('b', 64) },
            pinned.Checkpoint with { LocalSha256 = null },
            pinned.Checkpoint with { ServerSha256 = "not-a-sha256" },
            pinned.Checkpoint with { Files = new TransferWorkFiles(PartialPath: @"C:\transfer-fixtures\part.bin") },
            pinned.Checkpoint with { RemotePublished = true, RemoteCanceled = true },
        };
        foreach (var checkpoint in invalid)
        {
            await Assert.ThrowsAsync<FilewayException>(() => store.SaveTransferCheckpointAsync(pinned.Run!.Value,
                pinned.Revision, checkpoint, false, Token));
            Assert.Equal(pinned, await ReadAsync(store, pinned.Plan.Reference));
        }
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task MissingPartialResetRequiresRecoveryZeroOffsetAndNoPublishEvidence(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var saved = Saved();
        await ((IProfileStore)store).SaveAsync(saved, Token);
        var plan = Plan(saved.Account, TransferDirection.Download) with { LocalPath = @"C:\transfer-fixtures\downloads\file.bin" };
        var created = await store.CreateTransferAsync(plan, Token);
        var claimed = await store.ClaimTransferAsync(plan.Reference, created.Revision, Guid.NewGuid(), false, Token);
        var files = new TransferWorkFiles(@"C:\transfer-fixtures\downloads\file.part", "partial-old");
        var running = await store.SaveTransferCheckpointAsync(claimed.Run!.Value, claimed.Revision,
            new(TransferPhase.Running, new(10, SourceVersion: "source-v1"), 4, files), false, Token);
        var replacement = files with { PartialPath = @"C:\transfer-fixtures\downloads\replacement.part", PartialIdentity = "partial-new" };
        await Assert.ThrowsAsync<FilewayException>(() => store.SaveTransferCheckpointAsync(running.Run!.Value,
            running.Revision, running.Checkpoint with { Files = replacement, ConfirmedOffset = 0 }, false, Token));
        var recovery = await store.ClaimTransferAsync(plan.Reference, running.Revision, Guid.NewGuid(), true, Token);
        await Assert.ThrowsAsync<FilewayException>(() => store.SaveTransferCheckpointAsync(recovery.Run!.Value,
            recovery.Revision, recovery.Checkpoint with { Files = replacement }, false, Token));
        await Assert.ThrowsAsync<FilewayException>(() => store.SaveTransferCheckpointAsync(recovery.Run!.Value,
            recovery.Revision, recovery.Checkpoint with { Files = replacement, ConfirmedOffset = 0, Uncertainty = TransferUncertainty.LocalPublish }, false, Token));
        var reset = await store.SaveTransferCheckpointAsync(recovery.Run!.Value, recovery.Revision,
            recovery.Checkpoint with { Phase = TransferPhase.Running, Files = replacement, ConfirmedOffset = 0 }, false, Token);
        Assert.Equal(running.Checkpoint.Source, reset.Checkpoint.Source);
        Assert.Equal(replacement, reset.Checkpoint.Files);
        Assert.Equal(0, reset.Checkpoint.ConfirmedOffset);
        Assert.Equal(recovery.Generation, reset.Generation);
        await Assert.ThrowsAsync<FilewayException>(() => store.SaveTransferCheckpointAsync(reset.Run!.Value,
            reset.Revision, reset.Checkpoint with { Files = files, ConfirmedOffset = 0 }, false, Token));
        Assert.Equal(reset, await ReadAsync(store, plan.Reference));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task DownloadWorkEvidenceCannotEscapeItsParentOrChangePinnedOwnership(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var saved = Saved();
        await ((IProfileStore)store).SaveAsync(saved, Token);
        var plan = Plan(saved.Account, TransferDirection.Download) with { LocalPath = @"C:\transfer-fixtures\downloads\owned.bin" };
        await store.CreateTransferAsync(plan, Token);
        var claimed = await store.ClaimTransferAsync(plan.Reference, 0, Guid.NewGuid(), false, Token);
        var source = new TransferSourceSnapshot(10, SourceVersion: "v1");
        var invalid = new List<TransferWorkFiles>
        {
            new(PartialIdentity: "identity-without-path"),
            new(PartialPath: plan.LocalPath),
            new(PartialPath: @"C:\transfer-fixtures\other\owned.part"),
            new(PartialPath: @"C:\transfer-fixtures\downloads\..\owned.part"),
            new(PartialPath: @"C:\transfer-fixtures\downloads\CON.part"),
            new(PartialPath: @"C:\transfer-fixtures\downloads\owned.part "),
            new(PartialPath: @"\\?\C:\transfer-fixtures\downloads\owned.part"),
            new(PartialPath: @"C:\transfer-fixtures\downloads\owned.part", BackupPath: @"c:\TRANSFER-FIXTURES\downloads\owned.part"),
            new(BackupIdentity: "backup-identity-without-path"),
        };
        foreach (var files in invalid)
        {
            await Assert.ThrowsAsync<FilewayException>(() => store.SaveTransferCheckpointAsync(claimed.Run!.Value,
                claimed.Revision, new(TransferPhase.Running, source, 0, files), false, Token));
            Assert.Equal(claimed, await ReadAsync(store, plan.Reference));
        }
        var owned = new TransferWorkFiles(@"C:\transfer-fixtures\downloads\owned.part", "part-native-id",
            "original-native-id", @"C:\transfer-fixtures\downloads\owned.backup", "backup-native-id");
        var pinned = await store.SaveTransferCheckpointAsync(claimed.Run!.Value, claimed.Revision,
            new(TransferPhase.Running, source, 3, owned), false, Token);
        var recovery = await store.ClaimTransferAsync(plan.Reference, pinned.Revision, Guid.NewGuid(), true, Token);
        await Assert.ThrowsAsync<FilewayException>(() => store.SaveTransferCheckpointAsync(recovery.Run!.Value,
            recovery.Revision, recovery.Checkpoint with
            {
                ConfirmedOffset = 0,
                Files = owned with { PartialPath = @"C:\transfer-fixtures\downloads\new.part", PartialIdentity = "new-part" },
            }, false, Token));
        await Assert.ThrowsAsync<FilewayException>(() => store.SaveTransferCheckpointAsync(recovery.Run!.Value,
            recovery.Revision, recovery.Checkpoint with { Files = owned with { BackupIdentity = "replacement-backup" } }, false, Token));
        await Assert.ThrowsAsync<FilewayException>(() => store.SaveTransferCheckpointAsync(recovery.Run!.Value,
            recovery.Revision, recovery.Checkpoint with { Files = owned with { OriginalTargetIdentity = "replacement-target" } }, false, Token));
        Assert.Equal(recovery, await ReadAsync(store, plan.Reference));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task UnknownOffsetNeverBecomesZeroWithoutEvidenceAndSourceTimestampIsUtc(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var saved = Saved();
        await ((IProfileStore)store).SaveAsync(saved, Token);
        var created = await store.CreateTransferAsync(Plan(saved.Account), Token);
        Assert.Null(created.Checkpoint.ConfirmedOffset);
        Assert.Equal(TransferPhase.Queued, created.Checkpoint.Phase);
        Assert.Equal(TimeSpan.Zero, created.CreatedAt.Offset);
        Assert.Equal(TimeSpan.Zero, created.UpdatedAt.Offset);
        var claimed = await store.ClaimTransferAsync(created.Plan.Reference, created.Revision, Guid.NewGuid(), false, Token);
        Assert.Null(claimed.Checkpoint.ConfirmedOffset);
        var localTime = new DateTimeOffset(2026, 10, 11, 10, 20, 30, TimeSpan.FromHours(8));
        var snapshot = new TransferSourceSnapshot(0, localTime, NativeFileIdentity: "empty-native-id");
        await Assert.ThrowsAsync<FilewayException>(() => store.SaveTransferCheckpointAsync(claimed.Run!.Value,
            claimed.Revision, new(TransferPhase.Running, snapshot), false, Token));
        var running = await store.SaveTransferCheckpointAsync(claimed.Run!.Value, claimed.Revision,
            new(TransferPhase.Running, snapshot, 0), false, Token);
        Assert.Equal(localTime.ToUniversalTime(), running.Checkpoint.Source!.Modified);
        Assert.Equal(TimeSpan.Zero, running.Checkpoint.Source!.Modified!.Value.Offset);
        var completed = await store.SaveTransferCheckpointAsync(running.Run!.Value, running.Revision,
            running.Checkpoint with { Phase = TransferPhase.Completed, RemotePublished = true }, true, Token);
        Assert.Equal(0, completed.Checkpoint.ConfirmedOffset);
        Assert.Equal(TransferPhase.Completed, completed.Checkpoint.Phase);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task DownloadCompletionNeedsFullMatchingHashesAndPublishedIdentityBeforeReleasing(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var saved = Saved();
        await ((IProfileStore)store).SaveAsync(saved, Token);
        var plan = Plan(saved.Account, TransferDirection.Download) with { LocalPath = @"C:\transfer-fixtures\downloads\complete.bin" };
        await store.CreateTransferAsync(plan, Token);
        var claimed = await store.ClaimTransferAsync(plan.Reference, 0, Guid.NewGuid(), false, Token);
        var files = new TransferWorkFiles(@"C:\transfer-fixtures\downloads\complete.part", "partial-identity");
        var verifying = await store.SaveTransferCheckpointAsync(claimed.Run!.Value, claimed.Revision,
            new(TransferPhase.Verifying, new(10, SourceVersion: "v1"), 10, files, Hash, Hash), false, Token);
        var completedEvidence = verifying.Checkpoint with
        { Phase = TransferPhase.Completed, Files = files with { PublishedFileIdentity = "published-identity" } };
        await Assert.ThrowsAsync<FilewayException>(() => store.SaveTransferCheckpointAsync(verifying.Run!.Value,
            verifying.Revision, completedEvidence, true, Token));
        var publishing = await store.SaveTransferCheckpointAsync(verifying.Run!.Value, verifying.Revision,
            verifying.Checkpoint with { Phase = TransferPhase.Publishing, Uncertainty = TransferUncertainty.LocalPublish }, false, Token);
        var invalid = new List<TransferCheckpoint>
        {
            completedEvidence with { ConfirmedOffset = 9 },
            completedEvidence with { ServerSha256 = null },
            completedEvidence with { ServerSha256 = new string('b', 64) },
            completedEvidence with { Files = files },
            completedEvidence with { Uncertainty = TransferUncertainty.LocalPublish },
            completedEvidence with { RemotePublished = true },
        };
        foreach (var checkpoint in invalid)
        {
            await Assert.ThrowsAsync<FilewayException>(() => store.SaveTransferCheckpointAsync(publishing.Run!.Value,
                publishing.Revision, checkpoint, true, Token));
            Assert.Equal(publishing, await ReadAsync(store, plan.Reference));
        }
        var completed = await store.SaveTransferCheckpointAsync(publishing.Run!.Value, publishing.Revision,
            completedEvidence, true, Token);
        Assert.Null(completed.Run);
        Assert.Equal(completedEvidence, completed.Checkpoint);
        await Assert.ThrowsAsync<FilewayException>(() => store.ClaimTransferAsync(plan.Reference, completed.Revision, Guid.NewGuid(), true, Token));
        Assert.Equal(completed, await ReadAsync(store, plan.Reference));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ReleaseFlagMustMatchPhaseAndPublicationCannotBecomePausedOrCanceled(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var saved = Saved();
        await ((IProfileStore)store).SaveAsync(saved, Token);
        foreach (var phase in ReleasingPhases)
        {
            var created = await store.CreateTransferAsync(Plan(saved.Account) with { Conflict = FileConflictPolicy.Skip }, Token);
            var claimed = await store.ClaimTransferAsync(created.Plan.Reference, 0, Guid.NewGuid(), false, Token);
            if (phase == TransferPhase.Canceled)
                claimed = await store.SetTransferIntentAsync(created.Plan.Reference, claimed.Revision, TransferIntent.Cancel, Token);
            var checkpoint = new TransferCheckpoint(phase,
                Uncertainty: phase == TransferPhase.OutcomeUnknown ? TransferUncertainty.RemoteWrite : TransferUncertainty.None);
            await Assert.ThrowsAsync<FilewayException>(() => store.SaveTransferCheckpointAsync(claimed.Run!.Value,
                claimed.Revision, checkpoint, false, Token));
            Assert.Equal(claimed, await ReadAsync(store, created.Plan.Reference));
            var released = await store.SaveTransferCheckpointAsync(claimed.Run!.Value, claimed.Revision, checkpoint, true, Token);
            Assert.Null(released.Run);
            Assert.Equal(phase, released.Checkpoint.Phase);
            if (phase is TransferPhase.Canceled or TransferPhase.Skipped)
            {
                Assert.Equal(released, await store.SetTransferIntentAsync(released.Plan.Reference, released.Revision, TransferIntent.Run, Token));
                await Assert.ThrowsAsync<FilewayException>(() => store.ClaimTransferAsync(released.Plan.Reference,
                    released.Revision, Guid.NewGuid(), false, Token));
                await Assert.ThrowsAsync<FilewayException>(() => store.ClaimTransferAsync(released.Plan.Reference,
                    released.Revision, Guid.NewGuid(), true, Token));
                Assert.Equal(released, await ReadAsync(store, released.Plan.Reference));
            }
        }
        foreach (var phase in OwningPhases)
        {
            var running = await RunningUploadAsync(store, saved);
            if (phase == TransferPhase.CancelRequested)
                running = await store.SetTransferIntentAsync(running.Plan.Reference, running.Revision, TransferIntent.Cancel, Token);
            var checkpoint = running.Checkpoint with { Phase = phase, ConfirmedOffset = 10 };
            await Assert.ThrowsAsync<FilewayException>(() => store.SaveTransferCheckpointAsync(running.Run!.Value,
                running.Revision, checkpoint, true, Token));
            Assert.Equal(running, await ReadAsync(store, running.Plan.Reference));
            var recovery = await store.ClaimTransferAsync(running.Plan.Reference, running.Revision, Guid.NewGuid(), true, Token);
            var owning = await store.SaveTransferCheckpointAsync(recovery.Run!.Value, recovery.Revision, checkpoint, false, Token);
            Assert.NotNull(owning.Run);
            Assert.Equal(phase, owning.Checkpoint.Phase);
            var ending = await store.ClaimTransferAsync(owning.Plan.Reference, owning.Revision, Guid.NewGuid(), true, Token);
            await store.SaveTransferCheckpointAsync(ending.Run!.Value, ending.Revision,
                ending.Checkpoint with { Phase = TransferPhase.Failed }, true, Token);
        }
        var publishingSource = await RunningUploadAsync(store, saved);
        var publishing = await store.SaveTransferCheckpointAsync(publishingSource.Run!.Value, publishingSource.Revision,
            publishingSource.Checkpoint with { Phase = TransferPhase.Publishing, ConfirmedOffset = 10 }, false, Token);
        await Assert.ThrowsAsync<FilewayException>(() => store.SaveTransferCheckpointAsync(publishing.Run!.Value,
            publishing.Revision, publishing.Checkpoint with { Phase = TransferPhase.Paused }, true, Token));
        var canceled = await store.SetTransferIntentAsync(publishing.Plan.Reference, publishing.Revision, TransferIntent.Cancel, Token);
        await Assert.ThrowsAsync<FilewayException>(() => store.SaveTransferCheckpointAsync(canceled.Run!.Value,
            canceled.Revision, canceled.Checkpoint with { Phase = TransferPhase.Canceled }, true, Token));
        Assert.Equal(canceled, await ReadAsync(store, canceled.Plan.Reference));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task KeysetPagesDoNotMoveWhenEarlierTaskProgressChangesAndCursorIsFullOwnerBound(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var saved = Saved();
        await ((IProfileStore)store).SaveAsync(saved, Token);
        var expected = new HashSet<Guid>();
        for (int index = 0; index < 7; index++)
            expected.Add((await store.CreateTransferAsync(Plan(saved.Account), Token)).Plan.Reference.Id);
        var first = await store.ListTransfersAsync(saved.Account, null, 2, Token);
        Assert.Equal(2, first.Items.Count);
        Assert.NotNull(first.NextCursor);
        var changed = first.Items[0];
        await store.SetTransferIntentAsync(changed.Plan.Reference, changed.Revision, TransferIntent.Pause, Token);
        var seen = new HashSet<Guid>(first.Items.Select(record => record.Plan.Reference.Id));
        string? cursor = first.NextCursor;
        do
        {
            var page = await store.ListTransfersAsync(saved.Account, cursor, 2, Token);
            Assert.InRange(page.Items.Count, 1, 2);
            foreach (var record in page.Items) Assert.True(seen.Add(record.Plan.Reference.Id));
            cursor = page.NextCursor;
        } while (cursor is not null);
        Assert.True(expected.SetEquals(seen));
        AccountKey[] foreignOwners =
        [
            saved.Account with { ProfileId = Guid.NewGuid() },
            saved.Account with { SourceRevision = Guid.NewGuid() },
            saved.Account with { ServerUserId = "another-user" },
        ];
        foreach (var account in foreignOwners)
        {
            await Assert.ThrowsAsync<FilewayException>(() => store.ListTransfersAsync(account, first.NextCursor, 2, Token));
            Assert.Empty((await store.ListTransfersAsync(account, null, 2, Token)).Items);
        }
        await Assert.ThrowsAsync<FilewayException>(() => store.ListTransfersAsync(saved.Account, "not-a-versioned-cursor", 2, Token));
        await Assert.ThrowsAsync<FilewayException>(() => store.ListTransfersAsync(saved.Account, null, 0, Token));
        await Assert.ThrowsAsync<FilewayException>(() => store.ListTransfersAsync(saved.Account, null, 201, Token));
    }

    [Fact]
    public async Task TransferGuardsUseBoundedAccessPathsAcrossLargeHistoryWithoutDecodingUnrelatedPayloads()
    {
        using var directory = new StorageTestDirectory();
        var store = new SqliteProfileStore(directory.DatabasePath);
        var saved = Saved();
        await store.SaveAsync(saved, Token);
        var seed = await store.CreateTransferAsync(Plan(saved.Account), Token);
        using var database = Database(directory.DatabasePath);
        using (var history = database.CreateCommand())
        {
            // All clones are valid queued tasks for the seed's target, with independent task UUIDs.
            // The new task below uses a different target so its reservation guard never reads these rows.
            history.CommandText = """
                WITH digits(value) AS (VALUES(0),(1),(2),(3),(4),(5),(6),(7),(8),(9)),
                history(task_id) AS (
                    SELECT printf('11111111-2222-4333-8444-%012x',
                        a.value + 10*b.value + 100*c.value + 1000*d.value + 1)
                    FROM digits a CROSS JOIN digits b CROSS JOIN digits c CROSS JOIN digits d
                )
                INSERT INTO transfer_tasks
                    (profile_id, source_revision, server_user_id, task_id, direction, intent, phase, revision,
                     generation, run_id, last_run_id, uncertainty, created_at, created_ticks, updated_at,
                     batch_id, ordinal, target_key, record_json, route_key)
                SELECT source.profile_id, source.source_revision, source.server_user_id, history.task_id,
                    source.direction, source.intent, source.phase, source.revision, source.generation,
                    source.run_id, source.last_run_id, source.uncertainty, source.created_at, source.created_ticks,
                    source.updated_at, source.batch_id, source.ordinal, source.target_key,
                    json_set(source.record_json, '$.Plan.Reference.Id', history.task_id), source.route_key
                FROM transfer_tasks source CROSS JOIN history WHERE source.task_id = $seed
                """;
            history.Parameters.AddWithValue("$seed", seed.Plan.Reference.Id.ToString("D"));
            Assert.Equal(10_000, history.ExecuteNonQuery());
        }
        Assert.Equal(10_001L, Scalar(database, "SELECT COUNT(*) FROM transfer_tasks"));
        foreach (string fieldName in RouteQueryFields)
        {
            string actualSql = ProductionTransferQuery(fieldName);
            Assert.DoesNotContain("record_json", actualSql, StringComparison.OrdinalIgnoreCase);
            using var explain = database.CreateCommand();
            explain.CommandText = "EXPLAIN QUERY PLAN " + actualSql;
            explain.Parameters.AddWithValue("$profile", saved.Profile.Id.ToString("D"));
            explain.Parameters.AddWithValue("$revision", saved.Profile.SourceRevision.ToString("D"));
            var steps = new List<string>();
            using (var plan = explain.ExecuteReader())
                while (plan.Read()) steps.Add(plan.GetString(3));
            Assert.Contains("SEARCH transfer_tasks USING COVERING INDEX transfer_tasks_route_history",
                Assert.Single(steps), StringComparison.OrdinalIgnoreCase);
        }
        string seedTarget = Assert.IsType<string>(Scalar(database, "SELECT target_key FROM transfer_tasks LIMIT 1"));
        foreach (string fieldName in ClaimQueryFields)
        {
            using var explain = database.CreateCommand();
            explain.CommandText = "EXPLAIN QUERY PLAN " + ProductionTransferQuery(fieldName);
            explain.Parameters.AddWithValue("$task", seed.Plan.Reference.Id.ToString("D"));
            bool activeQuery = fieldName == "TransferActiveRunsSql";
            if (!activeQuery) explain.Parameters.AddWithValue("$target", seedTarget);
            var steps = new List<string>();
            using (var plan = explain.ExecuteReader())
                while (plan.Read()) steps.Add(plan.GetString(3));
            Assert.Contains(activeQuery ? "USING INDEX transfer_tasks_active_runs" :
                "SEARCH transfer_tasks USING INDEX transfer_tasks_target_history",
                Assert.Single(steps), StringComparison.OrdinalIgnoreCase);
        }
        var corruptReference = new TransferTaskRef(saved.Account, Guid.ParseExact("11111111-2222-4333-8444-000000001388", "D"));
        using (var corrupt = database.CreateCommand())
        {
            corrupt.CommandText = "UPDATE transfer_tasks SET record_json = '{!' WHERE task_id = $task";
            corrupt.Parameters.AddWithValue("$task", corruptReference.Id.ToString("D"));
            Assert.Equal(1, corrupt.ExecuteNonQuery());
        }
        var displayed = saved with { Profile = saved.Profile with { DisplayName = "large retained history" } };
        await store.SaveAsync(displayed, Token);
        var candidate = await store.CreateTransferAsync(Plan(saved.Account), Token);
        var claimed = await store.ClaimTransferAsync(candidate.Plan.Reference, candidate.Revision, Guid.NewGuid(), false, Token);
        var paused = await store.SaveTransferCheckpointAsync(claimed.Run!.Value, claimed.Revision, new(TransferPhase.Paused), true, Token);
        Assert.Equal(displayed, await store.GetAsync(saved.Profile.Id, Token));
        Assert.Equal(paused, await ReadAsync(store, paused.Plan.Reference));

        using var route = database.CreateCommand();
        route.CommandText = "SELECT route_key FROM transfer_tasks WHERE task_id = $seed";
        route.Parameters.AddWithValue("$seed", seed.Plan.Reference.Id.ToString("D"));
        string originalRoute = Assert.IsType<string>(route.ExecuteScalar());
        string changedRoute = (originalRoute[0] == '0' ? "1" : "0") + originalRoute[1..];
        var failures = new List<(string Key, ErrorCode Code)>
        {
            ("!" + new string('0', 63), ErrorCode.LocalStorageUnavailable),
            ("z" + new string('0', 63), ErrorCode.LocalStorageUnavailable),
            (changedRoute, ErrorCode.SourceChanged),
        };
        Execute(database, "PRAGMA ignore_check_constraints = ON;");
        foreach (var failure in failures)
        {
            using (var damage = database.CreateCommand())
            {
                damage.CommandText = "UPDATE transfer_tasks SET route_key = $route WHERE task_id = $seed";
                damage.Parameters.AddWithValue("$route", failure.Key);
                damage.Parameters.AddWithValue("$seed", seed.Plan.Reference.Id.ToString("D"));
                Assert.Equal(1, damage.ExecuteNonQuery());
            }
            var create = await Assert.ThrowsAsync<FilewayException>(() => store.CreateTransferAsync(Plan(saved.Account), Token));
            var claim = await Assert.ThrowsAsync<FilewayException>(() => store.ClaimTransferAsync(paused.Plan.Reference,
                paused.Revision, Guid.NewGuid(), false, Token));
            var save = await Assert.ThrowsAsync<FilewayException>(() => store.SaveAsync(saved, Token));
            Assert.Equal(failure.Code, create.Error.Code);
            Assert.Equal(failure.Code, claim.Error.Code);
            Assert.Equal(failure.Code, save.Error.Code);
            Assert.Equal(paused, await ReadAsync(store, paused.Plan.Reference));
        }
        using (var restoreRoute = database.CreateCommand())
        {
            restoreRoute.CommandText = "UPDATE transfer_tasks SET route_key = $route WHERE task_id = $seed";
            restoreRoute.Parameters.AddWithValue("$route", originalRoute);
            restoreRoute.Parameters.AddWithValue("$seed", seed.Plan.Reference.Id.ToString("D"));
            Assert.Equal(1, restoreRoute.ExecuteNonQuery());
        }
        var read = await Assert.ThrowsAsync<FilewayException>(() => store.GetTransferAsync(corruptReference, Token));
        Assert.Equal(ErrorCode.LocalStorageUnavailable, read.Error.Code);
        await Assert.ThrowsAsync<FilewayException>(() => store.HasProtectedTransfersAsync(saved.Account, Token));
        await Assert.ThrowsAsync<FilewayException>(() => store.ForgetAsync(saved.Account, Token));
        Assert.Equal(displayed, await store.GetAsync(saved.Profile.Id, Token));
        Assert.Equal(10_002L, Scalar(database, "SELECT COUNT(*) FROM transfer_tasks"));
    }

    [Fact]
    public async Task SchemaFiveWalBackupActuallyRestoresInIsolationAndRetainsCommittedMetadata()
    {
        using var directory = new StorageTestDirectory();
        var saved = Saved();
        var bootstrap = new SqliteProfileStore(directory.DatabasePath);
        await bootstrap.SaveAsync(saved, Token);
        var preferences = new BrowserPreferences(BrowserViewMode.Details, Descending: true);
        await bootstrap.SaveBrowserPreferencesAsync(saved.Account, "2FFF", preferences, Token);
        var intent = new OperationIntent(new(saved.Account, Guid.NewGuid()), Guid.NewGuid(), OperationKind.CreateDirectory, []);
        var operation = await bootstrap.CreateIntentAsync(intent, Token);
        operation = await bootstrap.MarkDispatchAsync(intent.Reference, operation.Revision, Token);
        using var writer = Database(directory.DatabasePath);
        Execute(writer, "DROP TABLE transfer_tasks; PRAGMA user_version = 5; PRAGMA wal_autocheckpoint = 0; PRAGMA wal_checkpoint(TRUNCATE);");
        Execute(writer, "UPDATE saved_connections SET display_name = 'committed WAL transfer migration';");
        Assert.True(new FileInfo(directory.DatabasePath + "-wal").Length > 0);

        var migrated = new SqliteProfileStore(directory.DatabasePath);
        Assert.Empty((await ((ITransferTaskStore)migrated).ListTransfersAsync(saved.Account, null, 10, Token)).Items);
        var observed = saved with { Profile = saved.Profile with { DisplayName = "committed WAL transfer migration" } };
        Assert.Equal(observed, await migrated.GetAsync(saved.Profile.Id, Token));
        Assert.Equivalent(operation, Assert.IsType<OperationRecord>(await migrated.GetOperationAsync(intent.Reference, Token)), strict: true);
        Assert.Equal(preferences, await migrated.ReadBrowserPreferencesAsync(saved.Account, "2FFF", Token));
        Assert.Equal(6L, Scalar(writer, "PRAGMA user_version"));
        string backupPath = Assert.Single(Directory.GetFiles(directory.DirectoryPath, "profiles.sqlite3.schema5-*.backup"));
        string restoredPath = Path.Combine(directory.DirectoryPath, "isolated-restore", "profiles.sqlite3");
        Directory.CreateDirectory(Path.GetDirectoryName(restoredPath)!);
        using (var backup = Database(backupPath))
        {
            Assert.Equal(5L, Scalar(backup, "PRAGMA user_version"));
            Assert.Equal("ok", Scalar(backup, "PRAGMA integrity_check"));
            Assert.Equal("committed WAL transfer migration", Scalar(backup, "SELECT display_name FROM saved_connections"));
            Assert.Equal(0L, Scalar(backup, "SELECT COUNT(*) FROM sqlite_schema WHERE name = 'transfer_tasks'"));
            using var restored = Database(restoredPath);
            backup.BackupDatabase(restored);
        }
        // Reopening the restored schema-five database exercises the real migration runner again.
        var isolated = new SqliteProfileStore(restoredPath);
        Assert.Equal(observed, await isolated.GetAsync(saved.Profile.Id, Token));
        Assert.Equivalent(operation, Assert.IsType<OperationRecord>(await isolated.GetOperationAsync(intent.Reference, Token)), strict: true);
        Assert.Equal(preferences, await isolated.ReadBrowserPreferencesAsync(saved.Account, "2FFF", Token));
        var isolatedTasks = isolated;
        var task = await isolatedTasks.CreateTransferAsync(Plan(saved.Account), Token);
        var claim = await isolatedTasks.ClaimTransferAsync(task.Plan.Reference, task.Revision, Guid.NewGuid(), false, Token);
        Assert.NotNull(claim.Run);
        Assert.Empty((await ((ITransferTaskStore)migrated).ListTransfersAsync(saved.Account, null, 10, Token)).Items);
        using var restoredDatabase = Database(restoredPath);
        Assert.Equal(6L, Scalar(restoredDatabase, "PRAGMA user_version"));
        Assert.Equal("ok", Scalar(restoredDatabase, "PRAGMA integrity_check"));
    }

    [Fact]
    public async Task LongDatabaseAndMigrationBackupPathsKeepWalMetadataAndTransferWrites()
    {
        using var directory = new StorageTestDirectory();
        const string filename = "profiles.sqlite3";
        int padding = Math.Max(1, 270 - directory.DirectoryPath.Length - filename.Length - 2);
        Assert.InRange(padding, 1, 255);
        string longDirectory = Path.Combine(directory.DirectoryPath, new string('l', padding));
        string longPath = Path.Combine(longDirectory, filename);
        Assert.True(longPath.Length > 260);
        var saved = Saved();
        var bootstrap = new SqliteProfileStore(longPath);
        await bootstrap.SaveAsync(saved, Token);
        // The fixture uses the production connection opener rather than accidentally retrying a native long path.
        using var writer = OpenForFixtureDiagnostics(bootstrap);
        Execute(writer, "DROP TABLE transfer_tasks; PRAGMA user_version = 5; PRAGMA wal_autocheckpoint = 0; PRAGMA wal_checkpoint(TRUNCATE);");
        Execute(writer, "UPDATE saved_connections SET display_name = 'long database committed WAL';");
        Assert.True(new FileInfo(longPath + "-wal").Length > 0);
        var plan = Plan(saved.Account);
        var migrated = new SqliteProfileStore(longPath);
        var created = await migrated.CreateTransferAsync(plan, Token);
        var claimed = await migrated.ClaimTransferAsync(plan.Reference, created.Revision, Guid.NewGuid(), false, Token);
        var running = await migrated.SaveTransferCheckpointAsync(claimed.Run!.Value, claimed.Revision,
            new(TransferPhase.Running, new(4, NativeFileIdentity: "long-path-source"), 1), false, Token);
        var reopened = new SqliteProfileStore(longPath);
        Assert.Equal(running, await ReadAsync(reopened, plan.Reference));
        Assert.Equal(plan, running.Plan);
        Assert.Equal(saved with { Profile = saved.Profile with { DisplayName = "long database committed WAL" } },
            await reopened.GetAsync(saved.Profile.Id, Token));
        Assert.True(File.Exists(longPath));
        Assert.Equal(6L, Scalar(writer, "PRAGMA user_version"));
        string backupPath = Assert.Single(Directory.GetFiles(longDirectory, "profiles.sqlite3.schema5-*.backup"));
        Assert.True(backupPath.Length > 260);
        using (var backup = Database(ProductionDataSourcePath(backupPath)))
        {
            Assert.Equal(5L, Scalar(backup, "PRAGMA user_version"));
            Assert.Equal("ok", Scalar(backup, "PRAGMA integrity_check"));
            Assert.Equal("long database committed WAL", Scalar(backup, "SELECT display_name FROM saved_connections"));
            Assert.Equal(0L, Scalar(backup, "SELECT COUNT(*) FROM sqlite_schema WHERE name = 'transfer_tasks'"));
        }
        // These are pure mappings, including UNC; this fixture does not connect to a network share.
        var mappings = new List<(string Input, string Expected)>
        {
            (@"C:\fixture\profiles.sqlite3", @"\\?\C:\fixture\profiles.sqlite3"),
            (@"\\fixture-server\share\profiles.sqlite3", @"\\?\UNC\fixture-server\share\profiles.sqlite3"),
            (@"\\?\C:\fixture\profiles.sqlite3", @"\\?\C:\fixture\profiles.sqlite3"),
            (@"\\?\UNC\fixture-server\share\profiles.sqlite3", @"\\?\UNC\fixture-server\share\profiles.sqlite3"),
        };
        foreach (var mapping in mappings)
            Assert.Equal(mapping.Expected, ProductionDataSourcePath(mapping.Input));
    }

    [Fact]
    public async Task FailedSchemaFiveIndexCreationRollsBackEntireMigrationAndFutureVersionNeverRetriesIt()
    {
        using var directory = new StorageTestDirectory();
        var saved = Saved();
        await new SqliteProfileStore(directory.DatabasePath).SaveAsync(saved, Token);
        using var database = Database(directory.DatabasePath);
        // The first new index collides after CREATE TABLE, proving DDL is inside the same transaction.
        Execute(database, "DROP TABLE transfer_tasks; CREATE TABLE transfer_tasks_history (marker TEXT); " +
            "INSERT INTO transfer_tasks_history VALUES ('keep schema-five fixture'); PRAGMA user_version = 5;");
        var store = Store(true, directory);
        var reference = Plan(saved.Account).Reference;
        var failed = await Assert.ThrowsAsync<FilewayException>(() => store.GetTransferAsync(reference, Token));
        Assert.Equal(ErrorCode.LocalStorageUnavailable, failed.Error.Code);
        Assert.Equal(5L, Scalar(database, "PRAGMA user_version"));
        Assert.Equal(0L, Scalar(database, "SELECT COUNT(*) FROM sqlite_schema WHERE name = 'transfer_tasks'"));
        Assert.Equal("keep schema-five fixture", Scalar(database, "SELECT marker FROM transfer_tasks_history"));
        Assert.Equal(saved.Profile.DisplayName, Scalar(database, "SELECT display_name FROM saved_connections"));
        Assert.Single(Directory.GetFiles(directory.DirectoryPath, "*.schema5-*.backup"));
        Execute(database, "PRAGMA user_version = 99;");
        var schemaBefore = Scalar(database, "SELECT group_concat(sql, '|') FROM sqlite_schema WHERE sql IS NOT NULL ORDER BY name");
        await Assert.ThrowsAsync<FilewayException>(() => Store(true, directory).CreateTransferAsync(Plan(saved.Account), Token));
        await Assert.ThrowsAsync<FilewayException>(() => Store(true, directory).ListTransfersAsync(saved.Account, null, 10, Token));
        Assert.Equal(99L, Scalar(database, "PRAGMA user_version"));
        Assert.Equal(schemaBefore, Scalar(database, "SELECT group_concat(sql, '|') FROM sqlite_schema WHERE sql IS NOT NULL ORDER BY name"));
        Assert.Equal("keep schema-five fixture", Scalar(database, "SELECT marker FROM transfer_tasks_history"));
        Assert.Single(Directory.GetFiles(directory.DirectoryPath, "*.backup"));
    }

    [Theory]
    [InlineData("json-syntax")]
    [InlineData("json-unknown-field")]
    [InlineData("json-oversized")]
    [InlineData("json-duplicate-member")]
    [InlineData("identity")]
    [InlineData("revision")]
    [InlineData("phase")]
    [InlineData("generation")]
    [InlineData("run-id")]
    [InlineData("route-key")]
    public async Task CorruptTypedRowsFailClosedWithoutDisappearingOrBeingRepaired(string corruption)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(true, directory);
        var running = await RunningUploadAsync(store);
        using var database = Database(directory.DatabasePath);
        string originalPayload = Assert.IsType<string>(Scalar(database, "SELECT record_json FROM transfer_tasks"));
        using (var command = database.CreateCommand())
        {
            command.CommandText = corruption switch
            {
                "json-syntax" => "UPDATE transfer_tasks SET record_json = '{'",
                "json-unknown-field" => "UPDATE transfer_tasks SET record_json = json_set(record_json, '$.UnknownVerificationField', 1)",
                "json-oversized" => "UPDATE transfer_tasks SET record_json = $payload",
                "json-duplicate-member" => "UPDATE transfer_tasks SET record_json = $payload",
                "identity" => "UPDATE transfer_tasks SET record_json = json_set(record_json, '$.Plan.Reference.Account.ServerUserId', 'wrong-owner')",
                "revision" => "UPDATE transfer_tasks SET revision = revision + 1",
                "phase" => "UPDATE transfer_tasks SET phase = 999",
                "generation" => "UPDATE transfer_tasks SET generation = generation + 1",
                "run-id" => "UPDATE transfer_tasks SET run_id = $run",
                "route-key" => "UPDATE transfer_tasks SET route_key = 'not-a-sha256-route-key'",
                _ => throw new ArgumentOutOfRangeException(nameof(corruption)),
            };
            // Test fixtures deliberately bypass CHECKs to represent damaged or externally tampered rows.
            Execute(database, "PRAGMA ignore_check_constraints = ON;");
            if (corruption == "json-oversized")
                command.Parameters.AddWithValue("$payload", originalPayload + new string(' ', 1024 * 1024 + 1));
            if (corruption == "json-duplicate-member")
                command.Parameters.AddWithValue("$payload", originalPayload[..^1] + ",\"Revision\":" + running.Revision.ToString(System.Globalization.CultureInfo.InvariantCulture) + "}");
            if (corruption == "run-id") command.Parameters.AddWithValue("$run", Guid.NewGuid().ToString("D"));
            Assert.Equal(1, command.ExecuteNonQuery());
        }
        const string corruptSnapshot = "SELECT quote(record_json) || '|' || revision || '|' || generation || '|' || phase || '|' || ifnull(run_id, '') || '|' || route_key FROM transfer_tasks";
        var before = Scalar(database, corruptSnapshot);
        var reopened = Store(true, directory);
        var read = await Assert.ThrowsAsync<FilewayException>(() => reopened.GetTransferAsync(running.Plan.Reference, Token));
        Assert.Equal(ErrorCode.LocalStorageUnavailable, read.Error.Code);
        await Assert.ThrowsAsync<FilewayException>(() => reopened.ListTransfersAsync(running.Plan.Reference.Account, null, 10, Token));
        await Assert.ThrowsAsync<FilewayException>(() => reopened.SetTransferIntentAsync(running.Plan.Reference,
            running.Revision, TransferIntent.Cancel, Token));
        Assert.Equal(1L, Scalar(database, "SELECT COUNT(*) FROM transfer_tasks"));
        Assert.Equal(before, Scalar(database, corruptSnapshot));
    }

    [Theory]
    [InlineData(TransferPhase.Canceled, TransferDirection.Upload)]
    [InlineData(TransferPhase.Canceled, TransferDirection.Download)]
    [InlineData(TransferPhase.Skipped, TransferDirection.Upload)]
    [InlineData(TransferPhase.Skipped, TransferDirection.Download)]
    public async Task CorruptTerminalUncertaintyCannotDecodeAsAValidPermanentlyProtectedTask(TransferPhase phase, TransferDirection direction)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(true, directory);
        var saved = Saved();
        await ((IProfileStore)store).SaveAsync(saved, Token);
        var created = await store.CreateTransferAsync(Plan(saved.Account, direction) with { Conflict = FileConflictPolicy.Skip }, Token);
        var claimed = await store.ClaimTransferAsync(created.Plan.Reference, created.Revision, Guid.NewGuid(), false, Token);
        if (phase == TransferPhase.Canceled)
            claimed = await store.SetTransferIntentAsync(created.Plan.Reference, claimed.Revision, TransferIntent.Cancel, Token);
        var terminal = await store.SaveTransferCheckpointAsync(claimed.Run!.Value, claimed.Revision, new(phase), true, Token);
        using var database = Database(directory.DatabasePath);
        Execute(database, "PRAGMA ignore_check_constraints = ON;");
        using (var corrupt = database.CreateCommand())
        {
            // Keep JSON and its redundant state column consistent so only terminal semantics can reject it.
            corrupt.CommandText = "UPDATE transfer_tasks SET uncertainty = $uncertainty, " +
                "record_json = json_set(record_json, '$.Checkpoint.Uncertainty', $uncertainty)";
            corrupt.Parameters.AddWithValue("$uncertainty", direction == TransferDirection.Upload ? 1 : 2);
            Assert.Equal(1, corrupt.ExecuteNonQuery());
        }
        var before = Scalar(database, "SELECT record_json FROM transfer_tasks");
        var reopened = Store(true, directory);
        var rejected = await Assert.ThrowsAsync<FilewayException>(() => reopened.GetTransferAsync(terminal.Plan.Reference, Token));
        Assert.Equal(ErrorCode.LocalStorageUnavailable, rejected.Error.Code);
        await Assert.ThrowsAsync<FilewayException>(() => reopened.HasProtectedTransfersAsync(saved.Account, Token));
        await Assert.ThrowsAsync<FilewayException>(() => ((IProfileStore)reopened).ForgetAsync(saved.Account, Token));
        Assert.Equal(saved, await ((IProfileStore)reopened).GetAsync(saved.Profile.Id, Token));
        Assert.Equal(before, Scalar(database, "SELECT record_json FROM transfer_tasks"));
    }

    private static ITransferTaskStore Store(bool sqlite, StorageTestDirectory directory) =>
        sqlite ? new SqliteProfileStore(directory.DatabasePath) : new InMemoryProfileStore();

    private static SavedConnection Saved() => new(new(Guid.NewGuid(), Guid.NewGuid(), "transfer verification",
        new Uri("https://fixture.invalid/"), NetworkMode.SystemNetwork, new(ProxyMode.Direct)),
        AuthenticationMode.Password, "fixture", "7", true, DateTimeOffset.UtcNow);

    private static RemoteResourceRef Remote(AccountKey account, string path) => new(account, path, "fixture.bin", "/fixture.bin");

    private static TransferPlan Plan(AccountKey account, TransferDirection direction = TransferDirection.Upload)
    {
        var id = Guid.NewGuid();
        return new(new(account, id), direction, Remote(account, "/fixtures/" + id.ToString("N") + ".bin"),
            @"C:\transfer-fixtures\downloads\" + id.ToString("N") + ".bin");
    }

    private static async Task<TransferTaskRecord> RunningUploadAsync(ITransferTaskStore store, SavedConnection? saved = null)
    {
        saved ??= Saved();
        await ((IProfileStore)store).SaveAsync(saved, Token);
        var created = await store.CreateTransferAsync(Plan(saved.Account), Token);
        var claimed = await store.ClaimTransferAsync(created.Plan.Reference, 0, Guid.NewGuid(), false, Token);
        return await store.SaveTransferCheckpointAsync(claimed.Run!.Value, claimed.Revision,
            new(TransferPhase.Running, new(10, NativeFileIdentity: "source-identity"), 3), false, Token);
    }

    private static async Task<TransferTaskRecord> ReadAsync(ITransferTaskStore store, TransferTaskRef reference) =>
        Assert.IsType<TransferTaskRecord>(await store.GetTransferAsync(reference, Token));

    private static async Task<bool> TryAsync(Func<Task> action)
    {
        try { await action(); return true; }
        catch (FilewayException) { return false; }
    }

    private static async Task<bool[]> TryConcurrentlyAsync(params Func<Task>[] actions)
    {
        var start = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var pending = actions.Select(action => Task.Run(async () =>
        {
            await start.Task.WaitAsync(Token);
            return await TryAsync(action);
        }, Token)).ToArray();
        start.SetResult();
        return await Task.WhenAll(pending);
    }

    private static SqliteConnection Database(string path)
    {
        var connection = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = path, Pooling = false }.ToString());
        connection.Open();
        return connection;
    }

    private static string ProductionTransferQuery(string fieldName)
    {
        var field = typeof(SqliteProfileStore).GetField(fieldName,
            System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Static);
        Assert.NotNull(field);
        Assert.True(field.IsLiteral);
        return Assert.IsType<string>(field.GetRawConstantValue());
    }

    private static SqliteConnection OpenForFixtureDiagnostics(SqliteProfileStore store)
    {
        var method = typeof(SqliteProfileStore).GetMethod("Open",
            System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Instance);
        Assert.NotNull(method);
        try { return Assert.IsType<SqliteConnection>(method.Invoke(store, null)); }
        catch (System.Reflection.TargetInvocationException error) when (error.InnerException is not null)
        {
            System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(error.InnerException).Throw();
            throw;
        }
    }

    private static string ProductionDataSourcePath(string path)
    {
        var method = typeof(SqliteProfileStore).GetMethod("SqliteDataSourcePath",
            System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Static);
        Assert.NotNull(method);
        return Assert.IsType<string>(method.Invoke(null, [path]));
    }

    private static void Execute(SqliteConnection database, string sql)
    {
        using var command = database.CreateCommand();
        command.CommandText = sql;
        command.ExecuteNonQuery();
    }

    private static object? Scalar(SqliteConnection database, string sql)
    {
        using var command = database.CreateCommand();
        command.CommandText = sql;
        return command.ExecuteScalar();
    }
}
