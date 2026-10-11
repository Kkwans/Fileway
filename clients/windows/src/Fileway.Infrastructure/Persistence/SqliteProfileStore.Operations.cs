using System.Globalization;
using Fileway.Core.Contracts;
using Microsoft.Data.Sqlite;

namespace Fileway.Infrastructure.Persistence;

public sealed partial class SqliteProfileStore
{
    public bool IsPersistent => true;

    public Task<bool> HasUnresolvedOperationsAsync(AccountKey account, CancellationToken cancellationToken) => RunAsync(connection =>
    {
        OperationJournalValidation.Account(account);
        using var transaction = connection.BeginTransaction(deferred: true);
        bool unresolved = HasUnresolvedOperations(connection, transaction, account);
        transaction.Commit();
        return unresolved;
    }, cancellationToken);

    public Task<OperationRecord> CreateIntentAsync(OperationIntent intent, CancellationToken cancellationToken) =>
        RunAsync(connection =>
        {
            var normalized = OperationJournalValidation.Intent(intent);
            using var transaction = connection.BeginTransaction(deferred: false);
            EnsureJournalNotLoggingOut(connection, transaction, normalized.Reference.Account);
            if (ReadOperation(connection, normalized.Reference, transaction) is not null) throw OperationJournalValidation.Conflict();
            var now = DateTimeOffset.UtcNow;
            var record = new OperationRecord(normalized, OperationPhase.Intent, 0, now, now);
            using (var command = Command(connection, """
                INSERT INTO operation_journal
                    (profile_id, source_revision, server_user_id, operation_id, origin_session_id, kind,
                     phase, revision, created_at, created_ticks, updated_at, options_json)
                VALUES ($profile, $revision, $user, $operation, $session, $kind, $phase, 0, $created, $ticks, $updated, $options)
                """, transaction))
            {
                BindOperation(command, normalized.Reference);
                command.Parameters.AddWithValue("$session", normalized.OriginSessionId.ToString("D"));
                command.Parameters.AddWithValue("$kind", (int)normalized.Kind);
                command.Parameters.AddWithValue("$phase", (int)record.Phase);
                command.Parameters.AddWithValue("$created", now.ToString("O", CultureInfo.InvariantCulture));
                command.Parameters.AddWithValue("$ticks", now.UtcTicks);
                command.Parameters.AddWithValue("$updated", now.ToString("O", CultureInfo.InvariantCulture));
                command.Parameters.AddWithValue("$options", normalized.Options is null ? DBNull.Value : OperationJournalValidation.Encode(normalized.Options));
                command.ExecuteNonQuery();
            }
            foreach (var item in normalized.Items)
            {
                using var command = Command(connection, """
                    INSERT INTO operation_journal_items
                        (profile_id, source_revision, server_user_id, operation_id, ordinal, item_json)
                    VALUES ($profile, $revision, $user, $operation, $ordinal, $item)
                    """, transaction);
                BindOperation(command, normalized.Reference);
                command.Parameters.AddWithValue("$ordinal", item.Ordinal);
                command.Parameters.AddWithValue("$item", OperationJournalValidation.Encode(item));
                command.ExecuteNonQuery();
            }
            cancellationToken.ThrowIfCancellationRequested();
            transaction.Commit();
            return OperationJournalValidation.Copy(record);
        }, cancellationToken);

    public Task<OperationRecord> MarkDispatchAsync(OperationRef operation, long expectedRevision, CancellationToken cancellationToken) =>
        UpdateOperation(operation, expectedRevision, current => OperationJournalValidation.Transition(current, expectedRevision,
            OperationPhase.DispatchMarked), checkLogout: true, cancellationToken);

    public Task<OperationRecord> RecordAckAsync(OperationRef operation, long expectedRevision,
        OperationAcknowledgement acknowledgement, CancellationToken cancellationToken) =>
        UpdateOperation(operation, expectedRevision, current => OperationJournalValidation.Transition(current, expectedRevision,
            OperationPhase.Acknowledged, OperationJournalValidation.Ack(acknowledgement, current.Intent)), checkLogout: false, cancellationToken);

    public Task<OperationRecord> RecordRejectedOrUnknownAsync(OperationRef operation, long expectedRevision, OperationPhase phase,
        ErrorInfo? failure, CancellationToken cancellationToken) => UpdateOperation(operation, expectedRevision, current =>
        {
            if (phase is not (OperationPhase.Rejected or OperationPhase.OutcomeUnknown or OperationPhase.NotSent)) throw OperationJournalValidation.Invalid();
            return OperationJournalValidation.Transition(current, expectedRevision, phase, error: failure);
        }, checkLogout: false, cancellationToken);

    public Task<OperationRecord> SaveReconciliationAsync(OperationRef operation, long expectedRevision,
        OperationReconciliation reconciliation, CancellationToken cancellationToken) =>
        UpdateOperation(operation, expectedRevision, current => current with
        {
            Revision = checked(current.Revision + 1), UpdatedAt = DateTimeOffset.UtcNow,
            Reconciliation = OperationJournalValidation.Reconciliation(reconciliation, current),
        }, checkLogout: false, cancellationToken);

    public Task<OperationRecord?> GetOperationAsync(OperationRef operation, CancellationToken cancellationToken) => RunAsync(connection =>
    {
        OperationJournalValidation.Reference(operation);
        using var transaction = connection.BeginTransaction(deferred: true);
        var record = ReadOperation(connection, operation, transaction);
        transaction.Commit();
        return record;
    }, cancellationToken);

    public Task<OperationPage> ListOperationsAsync(AccountKey account, string? cursor, int limit, CancellationToken cancellationToken) =>
        RunAsync(connection =>
        {
            var boundary = OperationJournalValidation.Cursor(cursor, account, limit);
            using var transaction = connection.BeginTransaction(deferred: true);
            var references = new List<OperationRef>();
            using (var command = Command(connection, """
                SELECT operation_id FROM operation_journal WHERE
                    profile_id = $profile AND source_revision = $revision AND server_user_id = $user
                """ + (boundary is null ? "" : " AND (created_ticks < $ticks OR (created_ticks = $ticks AND operation_id < $after))") +
                " ORDER BY created_ticks DESC, operation_id DESC LIMIT $limit", transaction))
            {
                BindAccount(command, account);
                command.Parameters.AddWithValue("$limit", limit + 1);
                if (boundary is { } after)
                {
                    command.Parameters.AddWithValue("$ticks", after.Ticks);
                    command.Parameters.AddWithValue("$after", after.Id.ToString("D"));
                }
                using var reader = command.ExecuteReader();
                while (reader.Read())
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    references.Add(new(account, Guid.ParseExact(reader.GetString(0), "D")));
                }
            }
            var records = references.Take(limit).Select(reference => ReadOperation(connection, reference, transaction)
                ?? throw ProfileStoreValidation.Unavailable()).ToArray();
            transaction.Commit();
            return new OperationPage(records, references.Count > limit ? OperationJournalValidation.NextCursor(records[^1]) : null);
        }, cancellationToken);

    private Task<OperationRecord> UpdateOperation(OperationRef operation, long expectedRevision,
        Func<OperationRecord, OperationRecord> update, bool checkLogout, CancellationToken cancellationToken) => RunAsync(connection =>
    {
        OperationJournalValidation.Reference(operation);
        using var transaction = connection.BeginTransaction(deferred: false);
        if (checkLogout) EnsureJournalNotLoggingOut(connection, transaction, operation.Account);
        var current = ReadOperation(connection, operation, transaction) ?? throw OperationJournalValidation.Conflict();
        OperationJournalValidation.Revision(current, expectedRevision);
        var next = update(current);
        if (ReferenceEquals(next, current)) return current;
        using var command = Command(connection, """
            UPDATE operation_journal SET phase = $phase, revision = $next, updated_at = $updated,
                acknowledgement_json = $ack, error_code = $error, error_http_status = $http,
                error_retryable = $retryable, reconciliation_json = $reconciliation, reconciliation_resolution = $resolution
            WHERE profile_id = $profile AND source_revision = $revision AND server_user_id = $user
                AND operation_id = $operation AND revision = $expected
            """, transaction);
        BindOperation(command, operation);
        command.Parameters.AddWithValue("$phase", (int)next.Phase);
        command.Parameters.AddWithValue("$next", next.Revision);
        command.Parameters.AddWithValue("$updated", next.UpdatedAt.ToString("O", CultureInfo.InvariantCulture));
        command.Parameters.AddWithValue("$ack", next.Acknowledgement is null ? DBNull.Value : OperationJournalValidation.Encode(next.Acknowledgement));
        command.Parameters.AddWithValue("$error", next.Error is null ? DBNull.Value : (int)next.Error.Code);
        command.Parameters.AddWithValue("$http", (object?)next.Error?.HttpStatus ?? DBNull.Value);
        command.Parameters.AddWithValue("$retryable", next.Error?.Retryable == true ? 1 : 0);
        command.Parameters.AddWithValue("$reconciliation", next.Reconciliation is null ? DBNull.Value : OperationJournalValidation.Encode(next.Reconciliation));
        command.Parameters.AddWithValue("$resolution", next.Reconciliation is null ? DBNull.Value : (int)next.Reconciliation.Resolution);
        command.Parameters.AddWithValue("$expected", expectedRevision);
        if (command.ExecuteNonQuery() != 1) throw OperationJournalValidation.Conflict();
        cancellationToken.ThrowIfCancellationRequested();
        transaction.Commit();
        return OperationJournalValidation.Copy(next);
    }, cancellationToken);

    private static OperationRecord? ReadOperation(SqliteConnection connection, OperationRef operation, SqliteTransaction transaction)
    {
        Guid session;
        OperationKind kind;
        OperationPhase phase;
        long revision;
        DateTimeOffset created, updated;
        OperationOptions? options;
        OperationAcknowledgement? ack;
        OperationReconciliation? reconciliation;
        ErrorInfo? error;
        using (var command = Command(connection, """
            SELECT origin_session_id, kind, phase, revision, created_at, updated_at, options_json,
                acknowledgement_json, error_code, error_http_status, error_retryable, reconciliation_json, created_ticks, reconciliation_resolution
            FROM operation_journal WHERE profile_id = $profile AND source_revision = $revision
                AND server_user_id = $user AND operation_id = $operation
            """, transaction))
        {
            BindOperation(command, operation);
            using var reader = command.ExecuteReader();
            if (!reader.Read()) return null;
            session = Guid.ParseExact(reader.GetString(0), "D");
            kind = (OperationKind)reader.GetInt32(1);
            phase = (OperationPhase)reader.GetInt32(2);
            revision = reader.GetInt64(3);
            created = DateTimeOffset.ParseExact(reader.GetString(4), "O", CultureInfo.InvariantCulture);
            updated = DateTimeOffset.ParseExact(reader.GetString(5), "O", CultureInfo.InvariantCulture);
            options = reader.IsDBNull(6) ? null : OperationJournalValidation.Decode<OperationOptions>(reader.GetString(6));
            ack = reader.IsDBNull(7) ? null : OperationJournalValidation.Decode<OperationAcknowledgement>(reader.GetString(7));
            error = reader.IsDBNull(8) ? null : OperationJournalValidation.SafeError(new((ErrorCode)reader.GetInt32(8), "",
                reader.IsDBNull(9) ? null : reader.GetInt32(9), reader.GetInt32(10) == 1));
            reconciliation = reader.IsDBNull(11) ? null : OperationJournalValidation.Decode<OperationReconciliation>(reader.GetString(11));
            if (!Enum.IsDefined(phase) || revision < 0 || created.UtcTicks != reader.GetInt64(12) ||
                (phase == OperationPhase.Acknowledged) != (ack is not null) ||
                (reconciliation is null) != reader.IsDBNull(13) || reconciliation is not null && (int)reconciliation.Resolution != reader.GetInt32(13))
                throw ProfileStoreValidation.Unavailable();
        }
        var items = new List<OperationItem>();
        using (var command = Command(connection, """
            SELECT ordinal, item_json FROM operation_journal_items
            WHERE profile_id = $profile AND source_revision = $revision AND server_user_id = $user
                AND operation_id = $operation ORDER BY ordinal
            """, transaction))
        {
            BindOperation(command, operation);
            using var reader = command.ExecuteReader();
            while (reader.Read())
            {
                var item = OperationJournalValidation.Decode<OperationItem>(reader.GetString(1));
                if (item.Ordinal != reader.GetInt32(0)) throw ProfileStoreValidation.Unavailable();
                items.Add(item);
            }
        }
        var intent = OperationJournalValidation.Intent(new(operation, session, kind, items, options));
        var record = new OperationRecord(intent, phase, revision, created, updated,
            ack is null ? null : OperationJournalValidation.Ack(ack, intent), error);
        return record with { Reconciliation = reconciliation is null ? null : OperationJournalValidation.Reconciliation(reconciliation, record) };
    }

    private static void EnsureJournalNotLoggingOut(SqliteConnection connection, SqliteTransaction transaction, AccountKey account)
    {
        using (var saved = Command(connection, "SELECT 1 FROM saved_connections WHERE " + AccountPredicate, transaction))
        {
            BindAccount(saved, account);
            if (saved.ExecuteScalar() is null) throw ProfileStoreValidation.Unavailable();
        }
        using var pending = Command(connection, "SELECT 1 FROM pending_logouts WHERE " + AccountPredicate, transaction);
        BindAccount(pending, account);
        if (pending.ExecuteScalar() is not null) throw ProfileStoreValidation.Unavailable();
    }

    private static bool HasUnresolvedOperations(SqliteConnection connection, SqliteTransaction transaction, AccountKey account)
    {
        using var command = Command(connection, """
            SELECT 1 FROM operation_journal WHERE profile_id = $profile AND source_revision = $revision
                AND server_user_id = $user AND phase IN (0, 1, 4)
                AND (reconciliation_resolution IS NULL OR reconciliation_resolution NOT IN (1, 2)) LIMIT 1
            """, transaction);
        BindAccount(command, account);
        return command.ExecuteScalar() is not null;
    }

    private static void GuardJournalAccountReplacement(SqliteConnection connection, SqliteTransaction transaction, AccountKey replacement)
    {
        AccountKey? previous = null;
        using (var command = Command(connection, "SELECT source_revision, server_user_id FROM saved_connections WHERE profile_id = $profile", transaction))
        {
            command.Parameters.AddWithValue("$profile", replacement.ProfileId.ToString("D"));
            using var reader = command.ExecuteReader();
            if (reader.Read()) previous = new(replacement.ProfileId, Guid.ParseExact(reader.GetString(0), "D"), reader.GetString(1));
        }
        if (previous is { } account && account != replacement && HasUnresolvedOperations(connection, transaction, account))
            throw OperationJournalValidation.UnresolvedConflict();
    }

    private static void BindOperation(SqliteCommand command, OperationRef operation)
    {
        BindAccount(command, operation.Account);
        command.Parameters.AddWithValue("$operation", operation.Id.ToString("D"));
    }

    private static void CreateOperationJournal(SqliteConnection connection, SqliteTransaction transaction)
    {
        using var command = Command(connection, """
            CREATE TABLE operation_journal (
                profile_id TEXT NOT NULL CHECK(length(profile_id) = 36),
                source_revision TEXT NOT NULL CHECK(length(source_revision) = 36),
                server_user_id TEXT NOT NULL CHECK(length(server_user_id) > 0),
                operation_id TEXT NOT NULL CHECK(length(operation_id) = 36),
                origin_session_id TEXT NOT NULL CHECK(length(origin_session_id) = 36),
                kind INTEGER NOT NULL CHECK(kind BETWEEN 0 AND 24),
                phase INTEGER NOT NULL CHECK(phase BETWEEN 0 AND 5),
                revision INTEGER NOT NULL CHECK(typeof(revision) = 'integer' AND revision >= 0),
                created_at TEXT NOT NULL, created_ticks INTEGER NOT NULL, updated_at TEXT NOT NULL,
                options_json TEXT NULL, acknowledgement_json TEXT NULL,
                error_code INTEGER NULL, error_http_status INTEGER NULL CHECK(error_http_status BETWEEN 100 AND 599),
                error_retryable INTEGER NOT NULL DEFAULT 0 CHECK(error_retryable IN (0, 1)),
                reconciliation_json TEXT NULL, reconciliation_resolution INTEGER NULL CHECK(reconciliation_resolution BETWEEN 0 AND 5),
                CHECK ((reconciliation_json IS NULL) = (reconciliation_resolution IS NULL)),
                CHECK ((phase = 2 AND acknowledgement_json IS NOT NULL) OR (phase != 2 AND acknowledgement_json IS NULL)),
                PRIMARY KEY (profile_id, source_revision, server_user_id, operation_id)
            );
            CREATE INDEX operation_journal_history ON operation_journal
                (profile_id, source_revision, server_user_id, created_ticks DESC, operation_id DESC);
            CREATE TABLE operation_journal_items (
                profile_id TEXT NOT NULL, source_revision TEXT NOT NULL, server_user_id TEXT NOT NULL,
                operation_id TEXT NOT NULL, ordinal INTEGER NOT NULL CHECK(typeof(ordinal) = 'integer' AND ordinal >= 0),
                item_json TEXT NOT NULL,
                PRIMARY KEY (profile_id, source_revision, server_user_id, operation_id, ordinal),
                FOREIGN KEY (profile_id, source_revision, server_user_id, operation_id)
                    REFERENCES operation_journal (profile_id, source_revision, server_user_id, operation_id)
            );
            PRAGMA user_version = 5;
            """, transaction);
        command.ExecuteNonQuery();
    }
}
