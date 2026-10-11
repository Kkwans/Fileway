using Fileway.Core.Contracts;
using Fileway.Infrastructure.Persistence;
using Microsoft.Data.Sqlite;

namespace Fileway.Infrastructure.Tests.Persistence;

public sealed class TransferTaskStoreTests
{
    private static readonly string Hash = new('a', 64);
    private static readonly string[] InvalidLocalPaths =
    [
        @"relative\file.bin", @"C:relative.bin", @"\rooted.bin", @"C:\", @"\\server\share",
        @"\\?\C:\fixtures\file.bin", @"\\.\C:\fixtures\file.bin", @"C:\fixtures\..\file.bin",
        @"C:\fixtures\file.bin.", @"C:\fixtures\file.bin ", @"C:\fixtures\NUL.txt",
        @"C:\fixtures\COM¹.log", @"C:\fixtures\CONIN$", @"C:\fixtures\file.bin:stream",
        @"C:\fixtures\\file.bin", @"C:\fixtures\bad?.bin",
    ];
    private static readonly string[] InvalidWirePaths =
    [
        "/", "/directory/", "/empty//file", "/./file", "/%2e%2E/file", "/encoded%2Fslash",
        "/nul%00file", "/broken%", "/broken%q0", "/query?x", "/fragment#x", "/back\\slash", "/control\nfile",
    ];
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task CreationHasImmutablePlanStoreUtcUnknownOffsetAndHonestPersistence(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var saved = Saved();
        await ((IProfileStore)store).SaveAsync(saved, Token);
        var plan = Plan(saved.Account) with
        {
            Remote = new(saved.Account, "/old/%ff%5C%09%3F.bin", "original byte filename", "/original byte filename"),
            LocalPath = @"C:/transfer-fixtures/source.bin", BatchId = Guid.NewGuid(), Ordinal = 3,
        };
        var before = DateTimeOffset.UtcNow;
        var created = await store.CreateTransferAsync(plan, Token);
        Assert.Equal(sqlite, store.IsPersistent);
        Assert.Equal(plan, created.Plan);
        Assert.Equal(TransferIntent.Run, created.Intent);
        Assert.Equal(new TransferCheckpoint(TransferPhase.Queued), created.Checkpoint);
        Assert.Equal(0, created.Revision);
        Assert.Equal(0, created.Generation);
        Assert.Null(created.Run);
        Assert.Equal(TimeSpan.Zero, created.CreatedAt.Offset);
        Assert.Equal(created.CreatedAt, created.UpdatedAt);
        Assert.InRange(created.CreatedAt, before, DateTimeOffset.UtcNow);
        Assert.False(await store.HasProtectedTransfersAsync(saved.Account, Token));
        var changedInput = plan with { LocalPath = @"C:\transfer-fixtures\another.bin" };
        Assert.NotEqual(changedInput, created.Plan);
        Assert.Equal(created, await ReadAsync(store, created.Plan.Reference));
        if (sqlite)
        {
            var reopened = Store(true, directory);
            Assert.Equal(created, await ReadAsync(reopened, plan.Reference));
            using var database = Database(directory.DatabasePath);
            using var version = database.CreateCommand();
            version.CommandText = "PRAGMA user_version";
            Assert.Equal(6L, version.ExecuteScalar());
        }
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task PlanValidationRejectsWrongOwnerInvalidIdentitiesAndUnsafeLocalOrWirePaths(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var saved = Saved();
        var plan = Plan(saved.Account);
        await Assert.ThrowsAsync<FilewayException>(() => store.CreateTransferAsync(plan, Token));
        await ((IProfileStore)store).SaveAsync(saved, Token);
        var invalidPlans = new List<TransferPlan>
        {
            plan with { Reference = new(saved.Account, Guid.Empty) },
            plan with { Reference = new(saved.Account with { SourceRevision = Guid.Empty }, plan.Reference.Id) },
            plan with { Remote = plan.Remote with { Account = saved.Account with { ServerUserId = "foreign" } } },
            plan with { BatchId = Guid.Empty }, plan with { Ordinal = -1 },
            plan with { Direction = (TransferDirection)999 }, plan with { Conflict = (FileConflictPolicy)999 },
            plan with { Remote = plan.Remote with { DisplayName = "nul\0name" } },
            plan with { Remote = plan.Remote with { SourceVersion = "control\nversion" } },
        };
        invalidPlans.AddRange(InvalidLocalPaths.Select(path => plan with { LocalPath = path }));
        invalidPlans.AddRange(InvalidWirePaths.Select(path => plan with { Remote = plan.Remote with { WirePath = path } }));
        foreach (var invalid in invalidPlans)
            await Assert.ThrowsAsync<FilewayException>(() => store.CreateTransferAsync(invalid, Token));
        Assert.Empty((await store.ListTransfersAsync(saved.Account, null, 10, Token)).Items);
        Assert.Null(await store.GetTransferAsync(plan.Reference, Token));
        var good = await store.CreateTransferAsync(plan, Token);
        Assert.Equal(plan, good.Plan);
    }

    [Theory]
    [InlineData(false, 0L)]
    [InlineData(true, 0L)]
    [InlineData(false, 8L)]
    [InlineData(true, 8L)]
    public async Task UploadPersistsNormalizedSourceAndHashesThroughVerifiedPublication(bool sqlite, long length)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var saved = Saved();
        await ((IProfileStore)store).SaveAsync(saved, Token);
        var created = await store.CreateTransferAsync(Plan(saved.Account), Token);
        var claimed = await store.ClaimTransferAsync(created.Plan.Reference, 0, Guid.NewGuid(), false, Token);
        Assert.Equal(1, claimed.Revision);
        Assert.Equal(1, claimed.Generation);
        Assert.Equal(TransferPhase.Preparing, claimed.Checkpoint.Phase);
        var observed = new TransferSourceSnapshot(length, new DateTimeOffset(2026, 10, 11, 18, 0, 0, TimeSpan.FromHours(8)),
            NativeFileIdentity: "volume-file-source");
        var running = await store.SaveTransferCheckpointAsync(claimed.Run!.Value, claimed.Revision,
            new(TransferPhase.Running, observed, 0), false, Token);
        Assert.Equal(observed with { Modified = observed.Modified!.Value.ToUniversalTime() }, running.Checkpoint.Source);
        Assert.Equal(TimeSpan.Zero, running.Checkpoint.Source!.Modified!.Value.Offset);
        var verifying = await store.SaveTransferCheckpointAsync(running.Run!.Value, running.Revision,
            running.Checkpoint with { Phase = TransferPhase.Verifying, ConfirmedOffset = length, LocalSha256 = Hash.ToUpperInvariant() }, false, Token);
        Assert.Equal(Hash, verifying.Checkpoint.LocalSha256);
        var publishing = await store.SaveTransferCheckpointAsync(verifying.Run!.Value, verifying.Revision,
            verifying.Checkpoint with { Phase = TransferPhase.Publishing }, false, Token);
        var completed = await store.SaveTransferCheckpointAsync(publishing.Run!.Value, publishing.Revision,
            publishing.Checkpoint with { Phase = TransferPhase.Completed, RemotePublished = true }, true, Token);
        Assert.Equal(TransferPhase.Completed, completed.Checkpoint.Phase);
        Assert.True(completed.Checkpoint.RemotePublished);
        Assert.False(completed.Checkpoint.RemoteCanceled);
        Assert.Equal(length, completed.Checkpoint.ConfirmedOffset);
        Assert.Null(completed.Run);
        Assert.Equal(created.Plan, completed.Plan);
        Assert.Equal(created.CreatedAt, completed.CreatedAt);
        Assert.Equal(claimed.Generation, completed.Generation);
        Assert.False(await store.HasProtectedTransfersAsync(saved.Account, Token));
        Assert.Equal(completed, await ReadAsync(sqlite ? Store(true, directory) : store, completed.Plan.Reference));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task DownloadCompletionKeepsOwnedWorkEvidenceMatchingHashesAndPublishedIdentity(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var saved = Saved();
        await ((IProfileStore)store).SaveAsync(saved, Token);
        var plan = Plan(saved.Account, TransferDirection.Download) with { LocalPath = @"C:\transfer-fixtures\download.bin", Conflict = FileConflictPolicy.Replace };
        var created = await store.CreateTransferAsync(plan, Token);
        var claimed = await store.ClaimTransferAsync(plan.Reference, 0, Guid.NewGuid(), false, Token);
        var files = new TransferWorkFiles(@"C:\transfer-fixtures\download.part", "part-identity", "old-target-identity");
        var running = await store.SaveTransferCheckpointAsync(claimed.Run!.Value, claimed.Revision,
            new(TransferPhase.Running, new(4, SourceVersion: "server-version"), 0, files), false, Token);
        var verifying = await store.SaveTransferCheckpointAsync(running.Run!.Value, running.Revision,
            running.Checkpoint with { Phase = TransferPhase.Verifying, ConfirmedOffset = 4, LocalSha256 = Hash, ServerSha256 = Hash.ToUpperInvariant() }, false, Token);
        var publishing = await store.SaveTransferCheckpointAsync(verifying.Run!.Value, verifying.Revision,
            verifying.Checkpoint with
            {
                Phase = TransferPhase.Publishing,
                Files = files with { BackupPath = @"C:\transfer-fixtures\download.backup", BackupIdentity = "backup-identity" },
                Uncertainty = TransferUncertainty.LocalPublish,
            }, false, Token);
        var completed = await store.SaveTransferCheckpointAsync(publishing.Run!.Value, publishing.Revision,
            publishing.Checkpoint with
            {
                Phase = TransferPhase.Completed, Uncertainty = TransferUncertainty.None,
                Files = publishing.Checkpoint.Files! with { PublishedFileIdentity = "published-identity" },
            }, true, Token);
        Assert.Equal(Hash, completed.Checkpoint.LocalSha256);
        Assert.Equal(Hash, completed.Checkpoint.ServerSha256);
        Assert.Equal("published-identity", completed.Checkpoint.Files!.PublishedFileIdentity);
        Assert.Equal(files.PartialPath, completed.Checkpoint.Files.PartialPath);
        Assert.Equal("backup-identity", completed.Checkpoint.Files.BackupIdentity);
        Assert.Equal("old-target-identity", completed.Checkpoint.Files.OriginalTargetIdentity);
        Assert.Equal(created.Plan, completed.Plan);
        Assert.Null(completed.Run);
        Assert.False(await store.HasProtectedTransfersAsync(saved.Account, Token));
        Assert.Equal(completed, await ReadAsync(sqlite ? Store(true, directory) : store, plan.Reference));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task InvalidCheckpointShapesCannotAcquireEvidenceOrAdvanceRevision(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var saved = Saved();
        await ((IProfileStore)store).SaveAsync(saved, Token);
        var created = await store.CreateTransferAsync(Plan(saved.Account), Token);
        var claimed = await store.ClaimTransferAsync(created.Plan.Reference, 0, Guid.NewGuid(), false, Token);
        var valid = new TransferCheckpoint(TransferPhase.Running, new(5, NativeFileIdentity: "source"), 0);
        var invalid = new List<TransferCheckpoint>
        {
            valid with { Phase = (TransferPhase)999 }, valid with { Source = new(-1, NativeFileIdentity: "source") },
            valid with { ConfirmedOffset = null }, valid with { ConfirmedOffset = 6 },
            valid with { Source = new(5) }, valid with { Source = new(5, SourceVersion: "server-version", NativeFileIdentity: "source") },
            valid with { Source = new(5, NativeFileIdentity: "control\nidentity") },
            valid with { Files = new(@"C:\transfer-fixtures\part.bin", "part") },
            valid with { LocalSha256 = "bad-hash" }, valid with { Uncertainty = (TransferUncertainty)4 },
            valid with { Uncertainty = TransferUncertainty.LocalPublish }, valid with { RemotePublished = true, RemoteCanceled = true },
            valid with { Error = new((ErrorCode)999, "raw") }, valid with { Error = new(ErrorCode.Timeout, "raw", 600) },
        };
        foreach (var checkpoint in invalid)
        {
            await Assert.ThrowsAsync<FilewayException>(() => store.SaveTransferCheckpointAsync(claimed.Run!.Value,
                claimed.Revision, checkpoint, false, Token));
            Assert.Equal(claimed, await ReadAsync(store, claimed.Plan.Reference));
        }
        var running = await store.SaveTransferCheckpointAsync(claimed.Run!.Value, claimed.Revision, valid, false, Token);
        Assert.Equal(valid, running.Checkpoint);
        Assert.Equal(claimed.Revision + 1, running.Revision);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ErrorPayloadUsesFixedSafeMessageAndTypedStatusWithoutPrivateExceptionText(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var saved = Saved();
        await ((IProfileStore)store).SaveAsync(saved, Token);
        var created = await store.CreateTransferAsync(Plan(saved.Account), Token);
        var claimed = await store.ClaimTransferAsync(created.Plan.Reference, 0, Guid.NewGuid(), false, Token);
        const string raw = "PRIVATE_TRANSFER_ERROR_SENTINEL https://fixture.invalid/private?credential=redacted";
        var failed = await store.SaveTransferCheckpointAsync(claimed.Run!.Value, claimed.Revision,
            new(TransferPhase.Failed, Error: new(ErrorCode.Timeout, raw, 503, true)), true, Token);
        Assert.Equal(ErrorCode.Timeout, failed.Checkpoint.Error!.Code);
        Assert.Equal(503, failed.Checkpoint.Error.HttpStatus);
        Assert.True(failed.Checkpoint.Error.Retryable);
        Assert.Equal("操作未完成，请查看状态后重试。", failed.Checkpoint.Error.Message);
        Assert.DoesNotContain("PRIVATE", failed.ToString(), StringComparison.Ordinal);
        Assert.Equal(failed, await ReadAsync(sqlite ? Store(true, directory) : store, failed.Plan.Reference));
        if (sqlite)
        {
            using var database = Database(directory.DatabasePath);
            using var payload = database.CreateCommand();
            payload.CommandText = "SELECT record_json FROM transfer_tasks";
            Assert.DoesNotContain(raw, Assert.IsType<string>(payload.ExecuteScalar()), StringComparison.Ordinal);
        }
    }

    [Fact]
    public async Task ReopeningSqliteNeverClaimsReplaysOrChangesAnActiveRun()
    {
        using var directory = new StorageTestDirectory();
        var store = Store(true, directory);
        var saved = Saved();
        await ((IProfileStore)store).SaveAsync(saved, Token);
        var created = await store.CreateTransferAsync(Plan(saved.Account), Token);
        var active = await store.ClaimTransferAsync(created.Plan.Reference, created.Revision, Guid.NewGuid(), false, Token);
        var reopened = Store(true, directory);
        Assert.Equal(active, await ReadAsync(reopened, active.Plan.Reference));
        Assert.Equal(active, Assert.Single((await reopened.ListTransfersAsync(saved.Account, null, 20, Token)).Items));
        Assert.True(await reopened.HasProtectedTransfersAsync(saved.Account, Token));
        Assert.Equal(active, await ReadAsync(reopened, active.Plan.Reference));
        Assert.Equal(saved, await ((IProfileStore)reopened).GetAsync(saved.Profile.Id, Token));
    }

    private static ITransferTaskStore Store(bool sqlite, StorageTestDirectory directory) =>
        sqlite ? new SqliteProfileStore(directory.DatabasePath) : new InMemoryProfileStore();

    private static SavedConnection Saved() => new(new(Guid.NewGuid(), Guid.NewGuid(), "transfer fixture", new Uri("https://fixture.invalid/"),
        NetworkMode.SystemNetwork, new(ProxyMode.Direct)), AuthenticationMode.Password, "fixture", "7", true, DateTimeOffset.UtcNow);

    private static TransferPlan Plan(AccountKey account, TransferDirection direction = TransferDirection.Upload)
    {
        var id = Guid.NewGuid();
        return new(new(account, id), direction, new(account, "/fixtures/" + id.ToString("N") + ".bin", "fixture.bin", "/fixture.bin"),
            @"C:\transfer-fixtures\" + id.ToString("N") + ".bin");
    }

    private static async Task<TransferTaskRecord> ReadAsync(ITransferTaskStore store, TransferTaskRef task) =>
        Assert.IsType<TransferTaskRecord>(await store.GetTransferAsync(task, Token));

    private static SqliteConnection Database(string path)
    {
        var connection = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = path, Pooling = false }.ToString());
        connection.Open();
        return connection;
    }
}
