using Fileway.Core.Contracts;
using Fileway.Infrastructure.Persistence;
using Microsoft.Data.Sqlite;

namespace Fileway.Infrastructure.Tests.Persistence;

public sealed class OperationJournalTests
{
    private static readonly int[] ExpectedItemOrdinals = [2, 7];
    private static readonly string[] ExpectedRecordIds = ["owned-a", "owned-b"];
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task TransitionsUseCasAndLateUnknownCannotDowngradeAcknowledgement(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        Assert.Equal(sqlite, store.IsPersistent);
        var intent = Intent() with { Kind = OperationKind.Copy };
        await SaveAccountAsync(store, intent.Reference.Account);
        var created = await store.CreateIntentAsync(intent, Token);
        Assert.Equal(OperationPhase.Intent, created.Phase);
        Assert.Equal(0, created.Revision);
        Assert.True(await store.HasUnresolvedOperationsAsync(intent.Reference.Account, Token));
        var dispatched = await store.MarkDispatchAsync(intent.Reference, created.Revision, Token);
        Assert.Equal(OperationPhase.DispatchMarked, dispatched.Phase);
        Assert.Equal(1, dispatched.Revision);
        var stale = await Assert.ThrowsAsync<FilewayException>(() => store.RecordAckAsync(intent.Reference, created.Revision,
            new(OperationAckKind.FileWriteConfirmed), Token));
        Assert.Equal(ErrorCode.Conflict, stale.Error.Code);
        var unknown = await store.RecordRejectedOrUnknownAsync(intent.Reference, dispatched.Revision,
            OperationPhase.OutcomeUnknown, new(ErrorCode.Timeout, "unsafe server error"), Token);
        var ack = await store.RecordAckAsync(intent.Reference, unknown.Revision,
            new(OperationAckKind.TaskAccepted, Task: new(intent.Reference.Account, "task-own")), Token);
        Assert.Equal(OperationPhase.Acknowledged, ack.Phase);
        Assert.False(await store.HasUnresolvedOperationsAsync(intent.Reference.Account, Token));
        await Assert.ThrowsAsync<FilewayException>(() => store.RecordRejectedOrUnknownAsync(intent.Reference, unknown.Revision,
            OperationPhase.OutcomeUnknown, new(ErrorCode.Canceled, "late"), Token));
        await Assert.ThrowsAsync<FilewayException>(() => store.RecordRejectedOrUnknownAsync(intent.Reference, ack.Revision,
            OperationPhase.OutcomeUnknown, null, Token));
        var retained = Assert.IsType<OperationRecord>(await store.GetOperationAsync(intent.Reference, Token));
        Assert.Equal(ack.Acknowledgement, retained.Acknowledgement);
        Assert.Equal(ack.Revision, retained.Revision);
        Assert.Equal(OperationPhase.Acknowledged, retained.Phase);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task FullAccountIsolatesSameOperationIdAndBoundPagination(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var intent = Intent();
        await SaveAccountAsync(store, intent.Reference.Account);
        await store.CreateIntentAsync(intent, Token);
        AccountKey[] foreigners = [intent.Reference.Account with { ProfileId = Guid.NewGuid() },
            intent.Reference.Account with { SourceRevision = Guid.NewGuid() }, intent.Reference.Account with { ServerUserId = "other" }];
        foreach (var foreign in foreigners)
        {
            var reference = intent.Reference with { Account = foreign };
            Assert.Null(await store.GetOperationAsync(reference, Token));
            Assert.Empty((await store.ListOperationsAsync(foreign, null, 10, Token)).Items);
            Assert.False(await store.HasUnresolvedOperationsAsync(foreign, Token));
            await Assert.ThrowsAsync<FilewayException>(() => store.MarkDispatchAsync(reference, 0, Token));
            if (foreign.ProfileId != intent.Reference.Account.ProfileId)
            {
                await SaveAccountAsync(store, foreign);
                await store.CreateIntentAsync(Intent(foreign, intent.Reference.Id), Token);
            }
        }
        await store.CreateIntentAsync(Intent(intent.Reference.Account), Token);
        await store.CreateIntentAsync(Intent(intent.Reference.Account), Token);
        var seen = new HashSet<Guid>();
        var first = await store.ListOperationsAsync(intent.Reference.Account, null, 1, Token);
        Assert.NotNull(first.NextCursor);
        foreach (var foreign in foreigners)
            await Assert.ThrowsAsync<FilewayException>(() => store.ListOperationsAsync(foreign, first.NextCursor, 1, Token));
        string? cursor = null;
        do
        {
            var page = await store.ListOperationsAsync(intent.Reference.Account, cursor, 1, Token);
            Assert.True(seen.Add(Assert.Single(page.Items).Intent.Reference.Id));
            cursor = page.NextCursor;
        } while (cursor is not null);
        Assert.Equal(3, seen.Count);
    }

    [Fact]
    public async Task ReopenPreservesCrashEvidenceAndDoesNotRetireLiveOperationsFromAnotherInstance()
    {
        using var directory = new StorageTestDirectory();
        var store = Store(true, directory);
        var intent = Intent();
        var dispatchIntent = Intent(intent.Reference.Account);
        var ackIntent = Intent(intent.Reference.Account) with { Kind = OperationKind.Copy };
        await SaveAccountAsync(store, intent.Reference.Account);
        await store.CreateIntentAsync(intent, Token);
        await store.CreateIntentAsync(dispatchIntent, Token);
        await store.MarkDispatchAsync(dispatchIntent.Reference, 0, Token);
        await store.CreateIntentAsync(ackIntent, Token);
        await store.MarkDispatchAsync(ackIntent.Reference, 0, Token);
        await store.RecordAckAsync(ackIntent.Reference, 1, new(OperationAckKind.TaskAccepted,
            Task: new(intent.Reference.Account, "running-task")), Token);
        var reopened = Store(true, directory);
        // Startup/session retirement is a service decision; Open is also used by concurrent live callers.
        Assert.Equal(OperationPhase.Intent, (await reopened.GetOperationAsync(intent.Reference, Token))!.Phase);
        Assert.Equal(OperationPhase.DispatchMarked, (await reopened.GetOperationAsync(dispatchIntent.Reference, Token))!.Phase);
        var ack = (await reopened.GetOperationAsync(ackIntent.Reference, Token))!;
        Assert.Equal(OperationPhase.Acknowledged, ack.Phase);
        Assert.Equal("running-task", ack.Acknowledgement!.Task!.Value.Id);
        var unknown = await reopened.RecordRejectedOrUnknownAsync(dispatchIntent.Reference, 1, OperationPhase.OutcomeUnknown,
            new(ErrorCode.OutcomeUnknown, "retired session"), Token);
        Assert.Equal(OperationPhase.OutcomeUnknown, unknown.Phase);
        await reopened.RecordRejectedOrUnknownAsync(intent.Reference, 0, OperationPhase.NotSent, null, Token);
        Assert.Equal(3, (await reopened.ListOperationsAsync(intent.Reference.Account, null, 10, Token)).Items.Count);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task LogoutBlocksNewIntentAndDispatchButRetainsFinalEvidenceWithoutRevivingProfile(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var journal = Store(sqlite, directory);
        var profiles = (IProfileStore)journal;
        var saved = Saved();
        await profiles.SaveAsync(saved, Token);
        var beforeDispatch = Intent(saved.Account);
        var inFlight = Intent(saved.Account);
        await journal.CreateIntentAsync(beforeDispatch, Token);
        await journal.CreateIntentAsync(inFlight, Token);
        await journal.MarkDispatchAsync(inFlight.Reference, 0, Token);
        await profiles.BeginLogoutAsync(saved.Account, Token);
        var otherStore = sqlite ? Store(true, directory) : journal;
        await Assert.ThrowsAsync<FilewayException>(() => otherStore.CreateIntentAsync(Intent(saved.Account), Token));
        await Assert.ThrowsAsync<FilewayException>(() => otherStore.MarkDispatchAsync(beforeDispatch.Reference, 0, Token));
        await otherStore.RecordRejectedOrUnknownAsync(beforeDispatch.Reference, 0, OperationPhase.NotSent, null, Token);
        await otherStore.RecordRejectedOrUnknownAsync(inFlight.Reference, 1, OperationPhase.OutcomeUnknown, null, Token);
        await otherStore.RecordAckAsync(inFlight.Reference, 2, new(OperationAckKind.FileWriteConfirmed), Token);
        await profiles.ForgetAsync(saved.Account, Token);
        Assert.Null(await profiles.GetAsync(saved.Profile.Id, Token));
        Assert.Equal(OperationPhase.Acknowledged, (await journal.GetOperationAsync(inFlight.Reference, Token))!.Phase);
        Assert.Equal(saved.Account, Assert.Single(await profiles.ListPendingLogoutsAsync(Token)));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task PendingJournalAtomicallyBlocksForgetAndReplacingAccountButAllowsRenewalAndNewProfile(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var journal = Store(sqlite, directory);
        var profiles = (IProfileStore)journal;
        var saved = Saved();
        await profiles.SaveAsync(saved, Token);
        var intent = Intent(saved.Account);
        await journal.CreateIntentAsync(intent, Token);
        var second = sqlite ? (IProfileStore)Store(true, directory) : profiles;
        SavedConnection[] replacements = [saved with { Profile = saved.Profile with { SourceRevision = Guid.NewGuid() } },
            saved with { ServerUserId = "other" }];
        foreach (var replacement in replacements)
        {
            var blocked = await Assert.ThrowsAsync<FilewayException>(() => second.SaveAsync(replacement, Token));
            Assert.Equal(ErrorCode.Conflict, blocked.Error.Code);
            Assert.Equal("此连接还有待核实操作，请先核实，或新建独立连接", blocked.Error.Message);
        }
        await Assert.ThrowsAsync<FilewayException>(() => second.ForgetAsync(saved.Account, Token));
        await second.SaveAsync(saved with { KeepSignedIn = false }, Token);
        var independent = saved with { Profile = saved.Profile with { Id = Guid.NewGuid() } };
        await second.SaveAsync(independent, Token);
        Assert.NotNull(await profiles.GetAsync(independent.Profile.Id, Token));
        var dispatched = await journal.MarkDispatchAsync(intent.Reference, 0, Token);
        var unknown = await journal.RecordRejectedOrUnknownAsync(intent.Reference, dispatched.Revision,
            OperationPhase.OutcomeUnknown, null, Token);
        var observed = await journal.SaveReconciliationAsync(intent.Reference, unknown.Revision,
            new(intent.Reference, OperationResolution.ObservedDesiredState), Token);
        Assert.True(await journal.HasUnresolvedOperationsAsync(saved.Account, Token));
        await Assert.ThrowsAsync<FilewayException>(() => second.ForgetAsync(saved.Account, Token));
        await Assert.ThrowsAsync<FilewayException>(() => journal.SaveReconciliationAsync(intent.Reference, observed.Revision,
            new(intent.Reference, OperationResolution.Confirmed), Token));
        await Assert.ThrowsAsync<FilewayException>(() => journal.SaveReconciliationAsync(intent.Reference, observed.Revision,
            new(intent.Reference, OperationResolution.NotSent), Token));
        var acknowledged = await journal.RecordAckAsync(intent.Reference, observed.Revision,
            new(OperationAckKind.FileWriteConfirmed), Token);
        await journal.SaveReconciliationAsync(intent.Reference, acknowledged.Revision,
            new(intent.Reference, OperationResolution.Confirmed), Token);
        Assert.False(await journal.HasUnresolvedOperationsAsync(saved.Account, Token));
        await second.SaveAsync(replacements[0], Token);
        await second.ForgetAsync(saved.Account, Token);
        Assert.Equal(replacements[0], await profiles.GetAsync(saved.Profile.Id, Token));
        Assert.NotNull(await journal.GetOperationAsync(intent.Reference, Token));
    }

    [Theory]
    [InlineData(false, false)]
    [InlineData(false, true)]
    [InlineData(true, false)]
    [InlineData(true, true)]
    public async Task ReplacementOrForgetWinningFirstBlocksStaleIntentAndDispatch(bool sqlite, bool forget)
    {
        using var directory = new StorageTestDirectory();
        var journal = Store(sqlite, directory);
        var profiles = (IProfileStore)journal;
        var saved = Saved();
        await profiles.SaveAsync(saved, Token);
        var intent = Intent(saved.Account);
        await journal.CreateIntentAsync(intent, Token);
        // Explicitly resolved as never sent, allowing the account to be retired while phase evidence is retained.
        await journal.SaveReconciliationAsync(intent.Reference, 0, new(intent.Reference, OperationResolution.NotSent), Token);
        var otherProfiles = sqlite ? (IProfileStore)Store(true, directory) : profiles;
        if (forget) await otherProfiles.ForgetAsync(saved.Account, Token);
        else await otherProfiles.SaveAsync(saved with { Profile = saved.Profile with { SourceRevision = Guid.NewGuid() } }, Token);
        var otherJournal = sqlite ? Store(true, directory) : journal;
        await Assert.ThrowsAsync<FilewayException>(() => otherJournal.CreateIntentAsync(Intent(saved.Account), Token));
        await Assert.ThrowsAsync<FilewayException>(() => otherJournal.MarkDispatchAsync(intent.Reference, 1, Token));
        Assert.Equal(OperationPhase.Intent, (await journal.GetOperationAsync(intent.Reference, Token))!.Phase);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task NullSnapshotsOpaqueIdentityItemOutcomesAndAllowlistedOptionsRoundTrip(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var account = Saved().Account;
        var resource = new RemoteResourceRef(account, "/%FF/literal%252F+a%25%0A", "同名�\n", "/显示/同名�\n", "etag");
        var items = new List<OperationItem>
        {
            new(7, new(resource, false, 42, DateTimeOffset.UnixEpoch), null, FileConflictPolicy.KeepBoth,
                FileOperationItemOutcome.OutcomeUnknown, new(ErrorCode.OutcomeUnknown, "private error")),
            new(2, null, new(resource), Outcome: FileOperationItemOutcome.NotAttempted),
        };
        var ids = new List<string> { "owned-a", "owned-b" };
        const string originalDisplayPath = "/原路径/同名\n\t%FF.txt";
        var intent = new OperationIntent(new(account, Guid.NewGuid()), Guid.NewGuid(), OperationKind.BatchRename, items,
            new("record", "name\n", "group", "blue", ids, FileConflictPolicy.KeepBoth, Guid.NewGuid(), originalDisplayPath));
        await SaveAccountAsync(store, account);
        var invalidDisplayPath = intent with { Options = intent.Options! with { DisplayPath = "/原路径/\0.txt" } };
        await Assert.ThrowsAsync<FilewayException>(() => store.CreateIntentAsync(invalidDisplayPath, Token));
        Assert.Null(await store.GetOperationAsync(intent.Reference, Token));
        await store.CreateIntentAsync(intent, Token);
        items.Clear(); ids.Clear();
        var retained = (await store.GetOperationAsync(intent.Reference, Token))!;
        Assert.Equal(ExpectedItemOrdinals, retained.Intent.Items.Select(item => item.Ordinal));
        Assert.Null(retained.Intent.Items[0].Source);
        Assert.Null(retained.Intent.Items[0].Target!.IsDirectory);
        Assert.Null(retained.Intent.Items[0].Target!.Size);
        Assert.Null(retained.Intent.Items[1].Target);
        Assert.Equal(resource, retained.Intent.Items[1].Source!.Resource);
        Assert.Equal(FileOperationItemOutcome.OutcomeUnknown, retained.Intent.Items[1].Outcome);
        Assert.Equal(FileOperationItemOutcome.NotAttempted, retained.Intent.Items[0].Outcome);
        Assert.Equal(ExpectedRecordIds, retained.Intent.Options!.RecordIds);
        Assert.Equal(originalDisplayPath, retained.Intent.Options.DisplayPath);
        Assert.DoesNotContain("private", retained.Intent.Items[1].Error!.Message);
        ((IList<OperationItem>)retained.Intent.Items)[0] = new(99);
        var unmodified = (await store.GetOperationAsync(intent.Reference, Token))!;
        Assert.Equal(2, unmodified.Intent.Items[0].Ordinal);
        await store.MarkDispatchAsync(intent.Reference, 0, Token);
        await Assert.ThrowsAsync<FilewayException>(() => store.RecordAckAsync(intent.Reference, 1,
            new(OperationAckKind.BatchRenameConfirmed, OperationOutcome.Partial, Items: unmodified.Intent.Items), Token));
        var knownItems = unmodified.Intent.Items.Select(item => item.Outcome == FileOperationItemOutcome.OutcomeUnknown
            ? item with { Outcome = FileOperationItemOutcome.Failed, Error = new(ErrorCode.Conflict, "explicit rejection") } : item).ToArray();
        await store.RecordAckAsync(intent.Reference, 1, new(OperationAckKind.BatchRenameConfirmed,
            OperationOutcome.Partial, DisplayPath: resource.DisplayPath, Items: knownItems), Token);
        var ack = (await store.GetOperationAsync(intent.Reference, Token))!.Acknowledgement!;
        Assert.Equal(resource.DisplayPath, ack.DisplayPath);
        Assert.Equal(FileOperationItemOutcome.NotAttempted, ack.Items![0].Outcome);
        Assert.Equal(FileOperationItemOutcome.Failed, ack.Items[1].Outcome);
        Assert.Equal(FileOperationItemOutcome.OutcomeUnknown, (await store.GetOperationAsync(intent.Reference, Token))!.Intent.Items[1].Outcome);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task InvalidForeignSnapshotsAckTasksAndReconciliationsNeverChangeRecord(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var store = Store(sqlite, directory);
        var intent = Intent();
        await SaveAccountAsync(store, intent.Reference.Account);
        var foreign = intent.Reference.Account with { ServerUserId = "foreign" };
        var bad = intent with { Items = [new(0, new(new(foreign, "/a", "a", "/a")))] };
        await Assert.ThrowsAsync<FilewayException>(() => store.CreateIntentAsync(bad, Token));
        Assert.Null(await store.GetOperationAsync(intent.Reference, Token));
        await store.CreateIntentAsync(intent, Token);
        await Assert.ThrowsAsync<FilewayException>(() => store.RecordAckAsync(intent.Reference, 0, new(OperationAckKind.MetadataConfirmed), Token));
        await store.MarkDispatchAsync(intent.Reference, 0, Token);
        await Assert.ThrowsAsync<FilewayException>(() => store.RecordAckAsync(intent.Reference, 1,
            new(OperationAckKind.FileWriteConfirmed, OperationOutcome.OutcomeUnknown), Token));
        await Assert.ThrowsAsync<FilewayException>(() => store.RecordAckAsync(intent.Reference, 1,
            new(OperationAckKind.TaskAccepted), Token));
        await Assert.ThrowsAsync<FilewayException>(() => store.RecordAckAsync(intent.Reference, 1,
            new(OperationAckKind.RetryAccepted), Token));
        await Assert.ThrowsAsync<FilewayException>(() => store.RecordAckAsync(intent.Reference, 1,
            new(OperationAckKind.TaskAccepted, Task: new(foreign, "task")), Token));
        await Assert.ThrowsAsync<FilewayException>(() => store.SaveReconciliationAsync(intent.Reference, 1,
            new(intent.Reference, OperationResolution.NotSent), Token));
        await Assert.ThrowsAsync<FilewayException>(() => store.SaveReconciliationAsync(intent.Reference, 1,
            new(new(foreign, intent.Reference.Id), OperationResolution.Confirmed), Token));
        Assert.Equal(1, (await store.GetOperationAsync(intent.Reference, Token))!.Revision);
        using var canceled = new CancellationTokenSource();
        canceled.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => store.RecordAckAsync(intent.Reference, 1,
            new(OperationAckKind.FileWriteConfirmed), canceled.Token));
        Assert.Equal(OperationPhase.DispatchMarked, (await store.GetOperationAsync(intent.Reference, Token))!.Phase);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task HelperProofOfNotSentAfterDispatchMarkerIsDifferentFromReadOnlyObservation(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var journal = Store(sqlite, directory);
        var intent = Intent();
        await SaveAccountAsync(journal, intent.Reference.Account);
        await journal.CreateIntentAsync(intent, Token);
        await journal.MarkDispatchAsync(intent.Reference, 0, Token);
        await Assert.ThrowsAsync<FilewayException>(() => journal.SaveReconciliationAsync(intent.Reference, 1,
            new(intent.Reference, OperationResolution.NotSent), Token));
        Assert.True(await journal.HasUnresolvedOperationsAsync(intent.Reference.Account, Token));
        var notSent = await journal.RecordRejectedOrUnknownAsync(intent.Reference, 1, OperationPhase.NotSent,
            new(ErrorCode.Canceled, "caller canceled before entering CallAsync"), Token);
        Assert.Equal(OperationPhase.NotSent, notSent.Phase);
        Assert.False(await journal.HasUnresolvedOperationsAsync(intent.Reference.Account, Token));
        await ((IProfileStore)journal).ForgetAsync(intent.Reference.Account, Token);
        Assert.Equal(OperationPhase.NotSent, (await journal.GetOperationAsync(intent.Reference, Token))!.Phase);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task AckKindAndTaskIdentityMustMatchIntentAndMetadataKeepsItsOwnProof(bool sqlite)
    {
        using var directory = new StorageTestDirectory();
        var journal = Store(sqlite, directory);
        var account = Saved().Account;
        await SaveAccountAsync(journal, account);
        foreach (var kind in new[] { OperationKind.TaskCancel, OperationKind.TaskRetry, OperationKind.TaskArchive, OperationKind.TaskUnarchive })
        {
            var intent = Intent(account) with { Kind = kind, Options = new(RecordId: "original-task") };
            await journal.CreateIntentAsync(intent, Token);
            await journal.MarkDispatchAsync(intent.Reference, 0, Token);
            var ackKind = kind switch
            {
                OperationKind.TaskCancel => OperationAckKind.CancellationRequested,
                OperationKind.TaskRetry => OperationAckKind.RetryAccepted,
                OperationKind.TaskArchive => OperationAckKind.Archived,
                _ => OperationAckKind.Unarchived,
            };
            await Assert.ThrowsAsync<FilewayException>(() => journal.RecordAckAsync(intent.Reference, 1,
                new(OperationAckKind.FileWriteConfirmed), Token));
            await Assert.ThrowsAsync<FilewayException>(() => journal.RecordAckAsync(intent.Reference, 1,
                new(ackKind, Task: new(account, kind == OperationKind.TaskRetry ? "original-task" : "foreign-task")), Token));
            await journal.RecordAckAsync(intent.Reference, 1,
                new(ackKind, Task: new(account, kind == OperationKind.TaskRetry ? "retry-task" : "original-task")), Token);
        }
        var metadata = Intent(account) with { Kind = OperationKind.UpdateFavorite };
        await journal.CreateIntentAsync(metadata, Token);
        await journal.MarkDispatchAsync(metadata.Reference, 0, Token);
        await Assert.ThrowsAsync<FilewayException>(() => journal.RecordAckAsync(metadata.Reference, 1,
            new(OperationAckKind.FileWriteConfirmed), Token));
        await journal.RecordAckAsync(metadata.Reference, 1, new(OperationAckKind.MetadataConfirmed), Token);
        var nulDisplay = Intent(account) with { Items = [new(0, new(new(account, "/a", "a\0", "/a")))] };
        await Assert.ThrowsAsync<FilewayException>(() => journal.CreateIntentAsync(nulDisplay, Token));
    }

    [Fact]
    public async Task CompetingSqliteWritersOnlyOneExpectedRevisionWins()
    {
        using var directory = new StorageTestDirectory();
        var store = Store(true, directory);
        var intent = Intent();
        await SaveAccountAsync(store, intent.Reference.Account);
        await store.CreateIntentAsync(intent, Token);
        var results = await Task.WhenAll(Enumerable.Range(0, 4).Select(async _ =>
        {
            try { await Store(true, directory).MarkDispatchAsync(intent.Reference, 0, Token); return true; }
            catch (FilewayException error) when (error.Error.Code == ErrorCode.Conflict) { return false; }
        }));
        Assert.Single(results, result => result);
        Assert.Equal(1, (await store.GetOperationAsync(intent.Reference, Token))!.Revision);
    }

    [Theory]
    [InlineData(1)]
    [InlineData(2)]
    [InlineData(3)]
    [InlineData(4)]
    public async Task LegacySchemaWalMigrationBacksUpAndPreservesProfilesPreferencesAndLedgers(int version)
    {
        using var directory = new StorageTestDirectory();
        var saved = Saved();
        var bootstrap = new SqliteProfileStore(directory.DatabasePath);
        await bootstrap.SaveAsync(saved, Token);
        var settings = new ClientStorageSettings(Path.Combine(directory.DirectoryPath, "cache"), Path.Combine(directory.DirectoryPath, "downloads"));
        await bootstrap.SaveStorageSettingsAsync(settings, Token);
        await bootstrap.RegisterCacheRootAsync(saved.Account, settings.CacheDirectory, Token);
        var preferences = new BrowserPreferences(BrowserViewMode.Details, Descending: true);
        await bootstrap.SaveBrowserPreferencesAsync(saved.Account, "2FFF", preferences, Token);
        var pending = saved.Account with { ServerUserId = "pending" };
        using var writer = Database(directory.DatabasePath);
        using (var downgrade = writer.CreateCommand())
        {
            downgrade.CommandText = "DROP TABLE navigation_state; " +
                (version >= 2 ? "ALTER TABLE client_storage_settings DROP COLUMN auto_resume_downloads; " : "") +
                "DROP TABLE transfer_tasks; DROP TABLE operation_journal_items; DROP TABLE operation_journal; " +
                (version < 4 ? "DROP TABLE browser_preferences; " : "") +
                (version < 3 ? "DROP TABLE cache_roots; " : "") +
                (version < 2 ? "DROP TABLE client_storage_settings; " : "") +
                "PRAGMA user_version = " + version + "; PRAGMA wal_autocheckpoint = 0; PRAGMA wal_checkpoint(TRUNCATE);";
            downgrade.ExecuteNonQuery();
        }
        using (var committed = writer.CreateCommand())
        {
            committed.CommandText = "UPDATE saved_connections SET display_name = 'committed WAL'; INSERT INTO pending_logouts VALUES ($profile, $revision, $user);";
            committed.Parameters.AddWithValue("$profile", pending.ProfileId.ToString("D"));
            committed.Parameters.AddWithValue("$revision", pending.SourceRevision.ToString("D"));
            committed.Parameters.AddWithValue("$user", pending.ServerUserId);
            committed.ExecuteNonQuery();
        }
        Assert.True(new FileInfo(directory.DatabasePath + "-wal").Length > 0);
        var migrated = new SqliteProfileStore(directory.DatabasePath);
        Assert.Empty((await migrated.ListOperationsAsync(saved.Account, null, 10, Token)).Items);
        Assert.Equal("committed WAL", (await migrated.GetAsync(saved.Profile.Id, Token))!.Profile.DisplayName);
        Assert.Equal(pending, Assert.Single(await migrated.ListPendingLogoutsAsync(Token)));
        if (version >= 2) Assert.Equal(settings, await migrated.ReadStorageSettingsAsync(Token));
        if (version >= 3) Assert.Equal(settings.CacheDirectory, Assert.Single(await migrated.ListCacheRootsAsync(Token)).CacheRoot);
        if (version >= 4) Assert.Equal(preferences, await migrated.ReadBrowserPreferencesAsync(saved.Account, "2FFF", Token));
        using var backup = Database(Assert.Single(Directory.GetFiles(directory.DirectoryPath, $"profiles.sqlite3.schema{version}-*.backup")));
        using var check = backup.CreateCommand();
        check.CommandText = "PRAGMA user_version";
        Assert.Equal((long)version, check.ExecuteScalar());
        check.CommandText = "SELECT display_name FROM saved_connections";
        Assert.Equal("committed WAL", check.ExecuteScalar());
        check.CommandText = "PRAGMA integrity_check";
        Assert.Equal("ok", check.ExecuteScalar());
        using var current = writer.CreateCommand();
        current.CommandText = "PRAGMA user_version";
        Assert.Equal(8L, current.ExecuteScalar());
    }

    [Fact]
    public async Task FailedSchemaFourMigrationRollsBackAndFutureSchemaRejectsJournalWrites()
    {
        using var directory = new StorageTestDirectory();
        await new SqliteProfileStore(directory.DatabasePath).SaveAsync(Saved(), Token);
        using (var database = Database(directory.DatabasePath))
        using (var downgrade = database.CreateCommand())
        {
            // Deliberately collide with the child-table name after the parent-table creation.
            downgrade.CommandText = "DROP TABLE transfer_tasks; DROP TABLE operation_journal_items; DROP TABLE operation_journal; CREATE TABLE operation_journal_items (marker TEXT); PRAGMA user_version = 4;";
            downgrade.ExecuteNonQuery();
        }
        var failure = await Assert.ThrowsAsync<FilewayException>(() => Store(true, directory).CreateIntentAsync(Intent(), Token));
        Assert.Equal(ErrorCode.LocalStorageUnavailable, failure.Error.Code);
        using var current = Database(directory.DatabasePath);
        using var check = current.CreateCommand();
        check.CommandText = "PRAGMA user_version";
        Assert.Equal(4L, check.ExecuteScalar());
        check.CommandText = "SELECT COUNT(*) FROM sqlite_schema WHERE name = 'operation_journal'";
        Assert.Equal(0L, check.ExecuteScalar());
        Assert.Single(Directory.GetFiles(directory.DirectoryPath, "*.schema4-*.backup"));
        check.CommandText = "PRAGMA user_version = 99";
        check.ExecuteNonQuery();
        await Assert.ThrowsAsync<FilewayException>(() => Store(true, directory).CreateIntentAsync(Intent(), Token));
        check.CommandText = "PRAGMA user_version";
        Assert.Equal(99L, check.ExecuteScalar());
        Assert.Single(Directory.GetFiles(directory.DirectoryPath, "*.backup"));
    }

    [Fact]
    public async Task PersistedTypedPayloadsOmitExternalErrorTextAndHaveNoCredentialOrRequestColumns()
    {
        using var directory = new StorageTestDirectory();
        var store = Store(true, directory);
        var intent = Intent();
        await SaveAccountAsync(store, intent.Reference.Account);
        await store.CreateIntentAsync(intent, Token);
        await store.MarkDispatchAsync(intent.Reference, 0, Token);
        await store.RecordRejectedOrUnknownAsync(intent.Reference, 1, OperationPhase.OutcomeUnknown,
            new(ErrorCode.Timeout, "PRIVATE_RAW_ERROR_SENTINEL", 503, true), Token);
        using var database = Database(directory.DatabasePath);
        using var command = database.CreateCommand();
        command.CommandText = "SELECT error_code, error_http_status, error_retryable FROM operation_journal";
        using (var reader = command.ExecuteReader())
        {
            Assert.True(reader.Read());
            Assert.Equal((int)ErrorCode.Timeout, reader.GetInt32(0));
            Assert.Equal(503, reader.GetInt32(1));
            Assert.Equal(1, reader.GetInt32(2));
        }
        command.CommandText = "SELECT sql FROM sqlite_schema WHERE name IN ('operation_journal', 'operation_journal_items')";
        using (var reader = command.ExecuteReader())
        {
            while (reader.Read())
            {
                string schema = reader.GetString(0);
                foreach (string prohibited in new[] { "endpoint", "password", "token", "credential", "raw_request" })
                    Assert.DoesNotContain(prohibited, schema, StringComparison.OrdinalIgnoreCase);
            }
        }
        var text = System.Text.Encoding.UTF8.GetString(await ReadSharedBytesAsync(directory.DatabasePath));
        Assert.DoesNotContain("PRIVATE_RAW_ERROR_SENTINEL", text);
        if (File.Exists(directory.DatabasePath + "-wal"))
            Assert.DoesNotContain("PRIVATE_RAW_ERROR_SENTINEL", System.Text.Encoding.UTF8.GetString(await ReadSharedBytesAsync(directory.DatabasePath + "-wal")));
    }

    private static async Task<byte[]> ReadSharedBytesAsync(string path)
    {
        await using var file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete,
            4096, FileOptions.Asynchronous | FileOptions.SequentialScan);
        using var bytes = new MemoryStream();
        await file.CopyToAsync(bytes, Token);
        return bytes.ToArray();
    }

    private static IOperationJournalStore Store(bool sqlite, StorageTestDirectory directory) =>
        sqlite ? new SqliteProfileStore(directory.DatabasePath) : new InMemoryProfileStore();

    private static Task SaveAccountAsync(IOperationJournalStore store, AccountKey account)
    {
        var saved = Saved();
        return ((IProfileStore)store).SaveAsync(saved with
        {
            Profile = saved.Profile with { Id = account.ProfileId, SourceRevision = account.SourceRevision },
            ServerUserId = account.ServerUserId,
        }, Token);
    }

    private static OperationIntent Intent(AccountKey? account = null, Guid? id = null) => new(
        new(account ?? Saved().Account, id ?? Guid.NewGuid()), Guid.NewGuid(), OperationKind.CreateDirectory, []);

    private static SavedConnection Saved() => new(new(Guid.NewGuid(), Guid.NewGuid(), "journal fixture", new Uri("https://fixture.invalid/"),
        NetworkMode.SystemNetwork, new(ProxyMode.Direct)), AuthenticationMode.Password, "fixture", "7", true, DateTimeOffset.UtcNow);

    private static SqliteConnection Database(string path)
    {
        var connection = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = path, Pooling = false }.ToString());
        connection.Open();
        return connection;
    }
}
