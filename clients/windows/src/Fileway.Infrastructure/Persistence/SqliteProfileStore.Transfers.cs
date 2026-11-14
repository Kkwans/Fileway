using System.Globalization;
using Fileway.Core.Contracts;
using Microsoft.Data.Sqlite;

namespace Fileway.Infrastructure.Persistence;

public sealed partial class SqliteProfileStore
{
    public Task<TransferTaskRecord> CancelUnstartedDownloadAsync(TransferTaskRef task, long expectedRevision, Guid cancellationRunId, CancellationToken cancellationToken) => RunAsync(connection =>
    {
        TransferTaskValidation.Reference(task); TransferTaskValidation.ExpectedRevision(expectedRevision);
        using var transaction = connection.BeginTransaction(deferred: false);
        var stored = ReadTransfer(connection, transaction, task) ?? throw TransferTaskValidation.Conflict();
        var next = TransferTaskValidation.CancelUnstartedDownload(stored.Record, expectedRevision, cancellationRunId, stored.LastRunId);
        UpdateTransferRow(connection, transaction, stored.Record, next, cancellationRunId);
        cancellationToken.ThrowIfCancellationRequested(); transaction.Commit(); return next;
    }, cancellationToken);

    public Task RemoveTransferAsync(TransferTaskRef task, long expectedRevision, CancellationToken cancellationToken) => RunAsync(connection =>
    {
        using var transaction = connection.BeginTransaction(deferred: false);
        EnsureJournalNotLoggingOut(connection, transaction, task.Account);
        var record = ReadTransfer(connection, transaction, task)?.Record ?? throw TransferTaskValidation.Conflict();
        if (record.Revision != expectedRevision || TransferTaskValidation.IsProtected(record) || record.Checkpoint.Phase is not (TransferPhase.Completed or TransferPhase.Canceled or TransferPhase.Skipped)) throw TransferTaskValidation.Conflict();
        using var command = Command(connection, "DELETE FROM transfer_tasks WHERE " + TransferPredicate + " AND revision = $expected", transaction);
        BindTransfer(command, task); command.Parameters.AddWithValue("$expected", expectedRevision);
        if (command.ExecuteNonQuery() != 1) throw TransferTaskValidation.Conflict();
        cancellationToken.ThrowIfCancellationRequested(); transaction.Commit(); return true;
    }, cancellationToken);
    private const string TransferColumns = "profile_id, source_revision, server_user_id, task_id, direction, intent, phase, revision, generation, run_id, last_run_id, uncertainty, created_at, created_ticks, updated_at, batch_id, ordinal, target_key, record_json, route_key";
    private const string TransferPredicate = AccountPredicate + " AND task_id = $task";
    private const string TransferActiveRunsSql = "SELECT " + TransferColumns + " FROM transfer_tasks INDEXED BY transfer_tasks_active_runs WHERE run_id IS NOT NULL AND task_id <> $task";
    private const string TransferTargetHistorySql = "SELECT " + TransferColumns + " FROM transfer_tasks WHERE target_key = $target AND task_id <> $task";
    private const string TransferRouteFirstSql = """
        SELECT route_key FROM transfer_tasks WHERE profile_id = $profile AND source_revision = $revision
        ORDER BY route_key ASC LIMIT 1
        """;
    private const string TransferRouteLastSql = """
        SELECT route_key FROM transfer_tasks WHERE profile_id = $profile AND source_revision = $revision
        ORDER BY route_key DESC LIMIT 1
        """;
    private sealed record StoredTransfer(TransferTaskRecord Record, Guid? LastRunId);

    public Task<TransferTaskRecord> CreateTransferAsync(TransferPlan plan, CancellationToken cancellationToken) => RunAsync(connection =>
    {
        var normalized = TransferTaskValidation.Plan(plan);
        using var transaction = connection.BeginTransaction(deferred: false);
        EnsureJournalNotLoggingOut(connection, transaction, normalized.Reference.Account);
        var saved = ReadTransferSavedAccount(connection, transaction, normalized.Reference.Account);
        GuardTransferRouteIdentity(connection, transaction, saved);
        var record = TransferTaskValidation.Create(normalized);
        string payload = TransferTaskValidation.Encode(record);
        using var command = Command(connection, """
            INSERT INTO transfer_tasks
                (profile_id, source_revision, server_user_id, task_id, direction, intent, phase, revision,
                 generation, run_id, last_run_id, uncertainty, created_at, created_ticks, updated_at, batch_id, ordinal, target_key, record_json, route_key)
            VALUES ($profile, $revision, $user, $task, $direction, $intent, $phase, $nextRevision,
                $generation, $run, $lastRun, $uncertainty, $created, $ticks, $updated, $batch, $ordinal, $target, $record, $route)
            """, transaction);
        BindTransfer(command, record.Plan.Reference);
        BindTransferState(command, record, null, payload);
        command.Parameters.AddWithValue("$direction", (int)record.Plan.Direction);
        command.Parameters.AddWithValue("$created", record.CreatedAt.ToString("O", CultureInfo.InvariantCulture));
        command.Parameters.AddWithValue("$ticks", record.CreatedAt.UtcTicks);
        command.Parameters.AddWithValue("$batch", record.Plan.BatchId is { } batch ? batch.ToString("D") : DBNull.Value);
        command.Parameters.AddWithValue("$ordinal", record.Plan.Ordinal);
        command.Parameters.AddWithValue("$target", TransferTaskValidation.TargetKey(record.Plan));
        command.Parameters.AddWithValue("$route", TransferTaskValidation.RouteKey(saved.Profile));
        ExecuteTransferWrite(command);
        cancellationToken.ThrowIfCancellationRequested();
        transaction.Commit();
        return record;
    }, cancellationToken);

    public Task<TransferTaskRecord?> GetTransferAsync(TransferTaskRef task, CancellationToken cancellationToken) => RunAsync(connection =>
    {
        TransferTaskValidation.Reference(task);
        using var transaction = connection.BeginTransaction(deferred: true);
        var record = ReadTransfer(connection, transaction, task)?.Record;
        transaction.Commit();
        return record;
    }, cancellationToken);

    public Task<TransferTaskPage> ListTransfersAsync(AccountKey account, string? cursor, int limit, CancellationToken cancellationToken) => RunAsync(connection =>
    {
        var boundary = TransferTaskValidation.Cursor(cursor, account, limit);
        using var transaction = connection.BeginTransaction(deferred: true);
        using var command = Command(connection, "SELECT " + TransferColumns + " FROM transfer_tasks WHERE " + AccountPredicate +
            (boundary is null ? "" : " AND (created_ticks < $ticks OR (created_ticks = $ticks AND task_id < $after))") +
            " ORDER BY created_ticks DESC, task_id DESC LIMIT $limit", transaction);
        BindAccount(command, account);
        command.Parameters.AddWithValue("$limit", limit + 1);
        if (boundary is { } after)
        {
            command.Parameters.AddWithValue("$ticks", after.Ticks);
            command.Parameters.AddWithValue("$after", after.Id.ToString("D"));
        }
        var records = new List<TransferTaskRecord>();
        using (var reader = command.ExecuteReader())
        {
            while (reader.Read())
            {
                cancellationToken.ThrowIfCancellationRequested();
                records.Add(ReadTransferRow(reader).Record);
            }
        }
        var page = records.Take(limit).ToArray();
        transaction.Commit();
        return new TransferTaskPage(page, records.Count > limit ? TransferTaskValidation.NextCursor(page[^1]) : null);
    }, cancellationToken);

    public Task<bool> HasProtectedTransfersAsync(AccountKey account, CancellationToken cancellationToken) => RunAsync(connection =>
    {
        TransferTaskValidation.Account(account);
        using var transaction = connection.BeginTransaction(deferred: true);
        bool value = HasProtectedTransfers(connection, transaction, account);
        transaction.Commit();
        return value;
    }, cancellationToken);

    public Task<TransferTaskRecord> SetTransferIntentAsync(TransferTaskRef task, long expectedRevision,
        TransferIntent intent, CancellationToken cancellationToken) => RunAsync(connection =>
    {
        TransferTaskValidation.Reference(task);
        TransferTaskValidation.ExpectedRevision(expectedRevision);
        using var transaction = connection.BeginTransaction(deferred: false);
        var stored = ReadTransfer(connection, transaction, task) ?? throw TransferTaskValidation.Conflict();
        var next = TransferTaskValidation.Intent(stored.Record, expectedRevision, intent);
        if (!ReferenceEquals(next, stored.Record)) UpdateTransferRow(connection, transaction, stored.Record, next, stored.LastRunId);
        cancellationToken.ThrowIfCancellationRequested();
        transaction.Commit();
        return next;
    }, cancellationToken);

    public Task<TransferTaskRecord> ClaimTransferAsync(TransferTaskRef task, long expectedRevision, Guid runId,
        bool recovery, CancellationToken cancellationToken) => RunAsync(connection =>
    {
        TransferTaskValidation.Reference(task);
        TransferTaskValidation.ExpectedRevision(expectedRevision);
        using var transaction = connection.BeginTransaction(deferred: false);
        EnsureJournalNotLoggingOut(connection, transaction, task.Account);
        GuardTransferRouteIdentity(connection, transaction, ReadTransferSavedAccount(connection, transaction, task.Account));
        var stored = ReadTransfer(connection, transaction, task) ?? throw TransferTaskValidation.Conflict();
        var next = TransferTaskValidation.Claim(stored.Record, expectedRevision, runId, stored.LastRunId, recovery);
        GuardTransferClaim(connection, transaction, next);
        UpdateTransferRow(connection, transaction, stored.Record, next, runId);
        cancellationToken.ThrowIfCancellationRequested();
        transaction.Commit();
        return next;
    }, cancellationToken);

    public Task<TransferTaskRecord> SaveTransferCheckpointAsync(TransferRunToken run, long expectedRevision,
        TransferCheckpoint checkpoint, bool releaseRun, CancellationToken cancellationToken) => RunAsync(connection =>
    {
        TransferTaskValidation.Reference(run.Task);
        TransferTaskValidation.ExpectedRevision(expectedRevision);
        using var transaction = connection.BeginTransaction(deferred: false);
        // Existing owners may preserve real evidence after logout without restoring a saved profile.
        var stored = ReadTransfer(connection, transaction, run.Task) ?? throw TransferTaskValidation.Conflict();
        var next = TransferTaskValidation.Progress(stored.Record, run, expectedRevision, checkpoint, releaseRun);
        UpdateTransferRow(connection, transaction, stored.Record, next, stored.LastRunId);
        cancellationToken.ThrowIfCancellationRequested();
        transaction.Commit();
        return next;
    }, cancellationToken);

    private static void GuardTransferClaim(SqliteConnection connection, SqliteTransaction transaction, TransferTaskRecord next)
    {
        int active = 0;
        using (var command = Command(connection, TransferActiveRunsSql, transaction))
        {
            command.Parameters.AddWithValue("$task", next.Plan.Reference.Id.ToString("D"));
            using var reader = command.ExecuteReader();
            while (reader.Read())
            {
                _ = ReadTransferRow(reader);
                active++;
            }
        }
        if (active >= 3) throw TransferTaskValidation.Capacity();
        using var reserved = Command(connection, TransferTargetHistorySql, transaction);
        reserved.Parameters.AddWithValue("$target", TransferTaskValidation.TargetKey(next.Plan));
        reserved.Parameters.AddWithValue("$task", next.Plan.Reference.Id.ToString("D"));
        using var targets = reserved.ExecuteReader();
        while (targets.Read())
            if (TransferTaskValidation.IsProtected(ReadTransferRow(targets).Record)) throw TransferTaskValidation.Conflict();
    }

    private static void UpdateTransferRow(SqliteConnection connection, SqliteTransaction transaction,
        TransferTaskRecord current, TransferTaskRecord next, Guid? lastRunId)
    {
        using var command = Command(connection, """
            UPDATE transfer_tasks SET intent = $intent, phase = $phase, revision = $nextRevision,
                generation = $generation, run_id = $run, last_run_id = $lastRun, uncertainty = $uncertainty,
                updated_at = $updated, record_json = $record
            WHERE profile_id = $profile AND source_revision = $revision AND server_user_id = $user AND task_id = $task
                AND revision = $expected AND generation = $oldGeneration AND run_id IS $oldRun
            """, transaction);
        BindTransfer(command, current.Plan.Reference);
        BindTransferState(command, next, lastRunId, TransferTaskValidation.Encode(next));
        command.Parameters.AddWithValue("$expected", current.Revision);
        command.Parameters.AddWithValue("$oldGeneration", current.Generation);
        command.Parameters.AddWithValue("$oldRun", current.Run is { } oldRun ? oldRun.Id.ToString("D") : DBNull.Value);
        if (ExecuteTransferWrite(command) != 1) throw TransferTaskValidation.Conflict();
    }

    private static int ExecuteTransferWrite(SqliteCommand command)
    {
        try { return command.ExecuteNonQuery(); }
        catch (SqliteException error) when (error.SqliteErrorCode == 19) { throw TransferTaskValidation.Conflict(); }
    }

    private static void BindTransfer(SqliteCommand command, TransferTaskRef task)
    {
        BindAccount(command, task.Account);
        command.Parameters.AddWithValue("$task", task.Id.ToString("D"));
    }

    private static void BindTransferState(SqliteCommand command, TransferTaskRecord record, Guid? lastRunId, string payload)
    {
        command.Parameters.AddWithValue("$intent", (int)record.Intent);
        command.Parameters.AddWithValue("$phase", (int)record.Checkpoint.Phase);
        command.Parameters.AddWithValue("$nextRevision", record.Revision);
        command.Parameters.AddWithValue("$generation", record.Generation);
        command.Parameters.AddWithValue("$run", record.Run is { } run ? run.Id.ToString("D") : DBNull.Value);
        command.Parameters.AddWithValue("$lastRun", lastRunId is { } last ? last.ToString("D") : DBNull.Value);
        command.Parameters.AddWithValue("$uncertainty", (int)record.Checkpoint.Uncertainty);
        command.Parameters.AddWithValue("$updated", record.UpdatedAt.ToString("O", CultureInfo.InvariantCulture));
        command.Parameters.AddWithValue("$record", payload);
    }

    private static StoredTransfer? ReadTransfer(SqliteConnection connection, SqliteTransaction transaction, TransferTaskRef task)
    {
        using var command = Command(connection, "SELECT " + TransferColumns + " FROM transfer_tasks WHERE " + TransferPredicate, transaction);
        BindTransfer(command, task);
        using var reader = command.ExecuteReader();
        return reader.Read() ? ReadTransferRow(reader) : null;
    }

    private static StoredTransfer ReadTransferRow(SqliteDataReader reader)
    {
        Guid? lastRunId = NullableGuid(reader, 10);
        var record = TransferTaskValidation.Decode(reader.GetString(18), lastRunId);
        var account = new AccountKey(Guid.ParseExact(reader.GetString(0), "D"), Guid.ParseExact(reader.GetString(1), "D"), reader.GetString(2));
        if (record.Plan.Reference != new TransferTaskRef(account, Guid.ParseExact(reader.GetString(3), "D")) ||
            (int)record.Plan.Direction != TransferInteger(reader, 4) || (int)record.Intent != TransferInteger(reader, 5) ||
            (int)record.Checkpoint.Phase != TransferInteger(reader, 6) || record.Revision != TransferInteger(reader, 7) ||
            record.Generation != TransferInteger(reader, 8) || record.Run?.Id != NullableGuid(reader, 9) ||
            (int)record.Checkpoint.Uncertainty != TransferInteger(reader, 11) ||
            record.CreatedAt.ToString("O", CultureInfo.InvariantCulture) != reader.GetString(12) || record.CreatedAt.UtcTicks != TransferInteger(reader, 13) ||
            record.UpdatedAt.ToString("O", CultureInfo.InvariantCulture) != reader.GetString(14) || record.Plan.BatchId != NullableGuid(reader, 15) ||
            record.Plan.Ordinal != TransferInteger(reader, 16) || TransferTaskValidation.TargetKey(record.Plan) != reader.GetString(17) ||
            !TransferTaskValidation.IsRouteKey(reader.GetString(19)))
            throw ProfileStoreValidation.Unavailable();
        return new(record, lastRunId);
    }

    private static Guid? NullableGuid(SqliteDataReader reader, int ordinal) => reader.IsDBNull(ordinal) ? null : Guid.ParseExact(reader.GetString(ordinal), "D");
    private static long TransferInteger(SqliteDataReader reader, int ordinal) => reader.GetValue(ordinal) is long value ? value : throw ProfileStoreValidation.Unavailable();

    private static bool HasProtectedTransfers(SqliteConnection connection, SqliteTransaction transaction, AccountKey account)
    {
        // Read every row in this account, including released rows, so corrupt state cannot make Forget look safe.
        using var command = Command(connection, "SELECT " + TransferColumns + " FROM transfer_tasks WHERE " + AccountPredicate, transaction);
        BindAccount(command, account);
        using var reader = command.ExecuteReader();
        while (reader.Read())
            if (TransferTaskValidation.IsProtected(ReadTransferRow(reader).Record)) return true;
        return false;
    }

    private static void GuardTransferAccountReplacement(SqliteConnection connection, SqliteTransaction transaction, SavedConnection replacement)
    {
        GuardTransferRouteIdentity(connection, transaction, replacement);
        SavedConnection? previous;
        using (var command = Command(connection, "SELECT " + Columns + " FROM saved_connections WHERE profile_id = $profile", transaction))
        {
            command.Parameters.AddWithValue("$profile", replacement.Profile.Id.ToString("D"));
            using var reader = command.ExecuteReader();
            previous = reader.Read() ? ReadConnection(reader) : null;
        }
        if (previous is null) return;
        TransferTaskValidation.RouteIdentity(previous, replacement);
        if (previous.Account != replacement.Account && HasProtectedTransfers(connection, transaction, previous.Account))
            throw TransferTaskValidation.ProtectedConflict();
    }

    private static SavedConnection ReadTransferSavedAccount(SqliteConnection connection, SqliteTransaction transaction, AccountKey account)
    {
        using var command = Command(connection, "SELECT " + Columns + " FROM saved_connections WHERE " + AccountPredicate, transaction);
        BindAccount(command, account);
        using var reader = command.ExecuteReader();
        return reader.Read() ? ReadConnection(reader) : throw ProfileStoreValidation.Unavailable();
    }

    private static void GuardTransferRouteIdentity(SqliteConnection connection, SqliteTransaction transaction, SavedConnection replacement)
    {
        string route = TransferTaskValidation.RouteKey(replacement.Profile);
        object? first;
        using (var command = Command(connection, TransferRouteFirstSql, transaction))
        {
            command.Parameters.AddWithValue("$profile", replacement.Profile.Id.ToString("D"));
            command.Parameters.AddWithValue("$revision", replacement.Profile.SourceRevision.ToString("D"));
            first = command.ExecuteScalar();
        }
        object? last;
        using (var command = Command(connection, TransferRouteLastSql, transaction))
        {
            command.Parameters.AddWithValue("$profile", replacement.Profile.Id.ToString("D"));
            command.Parameters.AddWithValue("$revision", replacement.Profile.SourceRevision.ToString("D"));
            last = command.ExecuteScalar();
        }
        if (first is null && last is null) return;
        if (first is not string lower || last is not string upper ||
            !TransferTaskValidation.IsRouteKey(lower) || !TransferTaskValidation.IsRouteKey(upper))
            throw ProfileStoreValidation.Unavailable();
        if (lower != route || upper != route) throw TransferTaskValidation.RouteChanged();
    }

    private static void CreateTransferTasks(SqliteConnection connection, SqliteTransaction transaction)
    {
        using var command = Command(connection, """
            CREATE TABLE transfer_tasks (
                profile_id TEXT NOT NULL CHECK(length(profile_id) = 36),
                source_revision TEXT NOT NULL CHECK(length(source_revision) = 36),
                server_user_id TEXT NOT NULL CHECK(length(server_user_id) BETWEEN 1 AND 1024),
                task_id TEXT NOT NULL UNIQUE CHECK(length(task_id) = 36),
                direction INTEGER NOT NULL CHECK(typeof(direction) = 'integer' AND direction IN (0, 1)),
                intent INTEGER NOT NULL CHECK(typeof(intent) = 'integer' AND intent IN (0, 1, 2)),
                phase INTEGER NOT NULL CHECK(typeof(phase) = 'integer' AND phase BETWEEN 0 AND 16),
                revision INTEGER NOT NULL CHECK(typeof(revision) = 'integer' AND revision >= 0),
                generation INTEGER NOT NULL CHECK(typeof(generation) = 'integer' AND generation >= 0 AND revision >= generation),
                run_id TEXT NULL CHECK(run_id IS NULL OR length(run_id) = 36),
                last_run_id TEXT NULL CHECK(last_run_id IS NULL OR length(last_run_id) = 36),
                uncertainty INTEGER NOT NULL CHECK(typeof(uncertainty) = 'integer' AND uncertainty BETWEEN 0 AND 3),
                created_at TEXT NOT NULL, created_ticks INTEGER NOT NULL CHECK(typeof(created_ticks) = 'integer'), updated_at TEXT NOT NULL,
                batch_id TEXT NULL CHECK(batch_id IS NULL OR length(batch_id) = 36),
                ordinal INTEGER NOT NULL CHECK(typeof(ordinal) = 'integer' AND ordinal >= 0),
                target_key TEXT NOT NULL CHECK(length(target_key) > 0),
                record_json TEXT NOT NULL CHECK(length(record_json) BETWEEN 2 AND 1048576),
                route_key TEXT NOT NULL CHECK(length(route_key) = 64 AND route_key = lower(route_key) AND route_key NOT GLOB '*[^0-9a-f]*'),
                CHECK((generation = 0) = (last_run_id IS NULL)),
                CHECK((generation = 0) = (phase = 0)),
                CHECK((run_id IS NOT NULL) = (phase IN (1, 2, 8, 9, 11, 14))),
                CHECK(run_id IS NULL OR run_id = last_run_id),
                CHECK((direction = 0 AND uncertainty IN (0, 1)) OR (direction = 1 AND uncertainty IN (0, 2))),
                CHECK(phase != 15 OR uncertainty != 0),
                CHECK(phase NOT IN (10, 12, 16) OR uncertainty = 0),
                CHECK(phase NOT IN (11, 12) OR intent = 2),
                PRIMARY KEY (profile_id, source_revision, server_user_id, task_id)
            );
            CREATE INDEX transfer_tasks_history ON transfer_tasks
                (profile_id, source_revision, server_user_id, created_ticks DESC, task_id DESC);
            CREATE INDEX transfer_tasks_route_history ON transfer_tasks
                (profile_id, source_revision, route_key);
            CREATE INDEX transfer_tasks_target_history ON transfer_tasks
                (target_key, task_id);
            CREATE INDEX transfer_tasks_active_runs ON transfer_tasks
                (task_id) WHERE run_id IS NOT NULL;
            CREATE UNIQUE INDEX transfer_tasks_batch_slot ON transfer_tasks
                (profile_id, source_revision, server_user_id, batch_id, ordinal) WHERE batch_id IS NOT NULL;
            CREATE UNIQUE INDEX transfer_tasks_target_reservation ON transfer_tasks
                (target_key) WHERE run_id IS NOT NULL OR uncertainty != 0;
            PRAGMA user_version = 6;
            """, transaction);
        command.ExecuteNonQuery();
    }
}
