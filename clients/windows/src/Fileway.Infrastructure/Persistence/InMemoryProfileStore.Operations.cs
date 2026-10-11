using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Persistence;

public sealed partial class InMemoryProfileStore
{
    private readonly Dictionary<OperationRef, OperationRecord> _operations = [];
    public bool IsPersistent => false;

    public Task<bool> HasUnresolvedOperationsAsync(AccountKey account, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        OperationJournalValidation.Account(account);
        lock (_gate) return Task.FromResult(_operations.Values.Any(record => record.Intent.Reference.Account == account &&
            OperationJournalValidation.IsUnresolved(record)));
    }

    public Task<OperationRecord> CreateIntentAsync(OperationIntent intent, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var normalized = OperationJournalValidation.Intent(intent);
        lock (_gate)
        {
            cancellationToken.ThrowIfCancellationRequested();
            EnsureJournalAccount(normalized.Reference.Account);
            if (_operations.ContainsKey(normalized.Reference)) throw OperationJournalValidation.Conflict();
            var now = DateTimeOffset.UtcNow;
            var record = new OperationRecord(normalized, OperationPhase.Intent, 0, now, now);
            _operations.Add(normalized.Reference, record);
            return Task.FromResult(OperationJournalValidation.Copy(record));
        }
    }

    public Task<OperationRecord> MarkDispatchAsync(OperationRef operation, long expectedRevision, CancellationToken cancellationToken) =>
        UpdateOperation(operation, expectedRevision, current => OperationJournalValidation.Transition(current, expectedRevision,
            OperationPhase.DispatchMarked), checkLogout: true, cancellationToken);

    public Task<OperationRecord> RecordAckAsync(OperationRef operation, long expectedRevision,
        OperationAcknowledgement acknowledgement, CancellationToken cancellationToken) =>
        UpdateOperation(operation, expectedRevision, current => OperationJournalValidation.Transition(current, expectedRevision,
            OperationPhase.Acknowledged, OperationJournalValidation.Ack(acknowledgement, current.Intent)), checkLogout: false, cancellationToken);

    public Task<OperationRecord> RecordRejectedOrUnknownAsync(OperationRef operation, long expectedRevision,
        OperationPhase phase, ErrorInfo? failure, CancellationToken cancellationToken) => UpdateOperation(operation, expectedRevision, current =>
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

    public Task<OperationRecord?> GetOperationAsync(OperationRef operation, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        OperationJournalValidation.Reference(operation);
        lock (_gate) return Task.FromResult(_operations.TryGetValue(operation, out var record) ? OperationJournalValidation.Copy(record) : null);
    }

    public Task<OperationPage> ListOperationsAsync(AccountKey account, string? cursor, int limit, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var boundary = OperationJournalValidation.Cursor(cursor, account, limit);
        lock (_gate)
        {
            var records = _operations.Values.Where(record => record.Intent.Reference.Account == account &&
                (boundary is not { } after || record.CreatedAt.UtcTicks < after.Ticks || record.CreatedAt.UtcTicks == after.Ticks &&
                    StringComparer.Ordinal.Compare(record.Intent.Reference.Id.ToString("D"), after.Id.ToString("D")) < 0))
                .OrderByDescending(record => record.CreatedAt.UtcTicks)
                .ThenByDescending(record => record.Intent.Reference.Id.ToString("D"), StringComparer.Ordinal)
                .Take(limit + 1).ToArray();
            var page = records.Take(limit).Select(OperationJournalValidation.Copy).ToArray();
            return Task.FromResult(new OperationPage(page, records.Length > limit ? OperationJournalValidation.NextCursor(page[^1]) : null));
        }
    }

    private Task<OperationRecord> UpdateOperation(OperationRef operation, long expectedRevision,
        Func<OperationRecord, OperationRecord> update, bool checkLogout, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        OperationJournalValidation.Reference(operation);
        lock (_gate)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (checkLogout) EnsureJournalAccount(operation.Account);
            if (!_operations.TryGetValue(operation, out var current)) throw OperationJournalValidation.Conflict();
            OperationJournalValidation.Revision(current, expectedRevision);
            var next = update(current);
            _operations[operation] = next;
            return Task.FromResult(OperationJournalValidation.Copy(next));
        }
    }

    private void EnsureJournalAccount(AccountKey account)
    {
        if (!_saved.TryGetValue(account.ProfileId, out var saved) || saved.Account != account || _pending.Contains(account))
            throw ProfileStoreValidation.Unavailable();
    }
}
