using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Persistence;

public sealed partial class InMemoryProfileStore
{
    private readonly Dictionary<TransferTaskRef, TransferTaskRecord> _transfers = [];
    private readonly Dictionary<TransferTaskRef, Guid> _transferRunIds = [];
    private readonly Dictionary<TransferTaskRef, string> _transferRoutes = [];

    public Task<TransferTaskRecord> CreateTransferAsync(TransferPlan plan, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var normalized = TransferTaskValidation.Plan(plan);
        lock (_gate)
        {
            EnsureJournalAccount(normalized.Reference.Account);
            var saved = _saved[normalized.Reference.Account.ProfileId];
            GuardTransferRouteIdentity(saved);
            if (_transfers.Values.Any(record => record.Plan.Reference.Id == normalized.Reference.Id ||
                normalized.BatchId is not null && record.Plan.Reference.Account == normalized.Reference.Account &&
                record.Plan.BatchId == normalized.BatchId && record.Plan.Ordinal == normalized.Ordinal)) throw TransferTaskValidation.Conflict();
            var record = TransferTaskValidation.Create(normalized);
            string route = TransferTaskValidation.RouteKey(saved.Profile);
            _ = TransferTaskValidation.Encode(record);
            cancellationToken.ThrowIfCancellationRequested();
            _transfers.Add(record.Plan.Reference, record);
            _transferRoutes.Add(record.Plan.Reference, route);
            return Task.FromResult(record);
        }
    }

    public Task<TransferTaskRecord?> GetTransferAsync(TransferTaskRef task, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        TransferTaskValidation.Reference(task);
        lock (_gate) return Task.FromResult(_transfers.GetValueOrDefault(task));
    }

    public Task<TransferTaskPage> ListTransfersAsync(AccountKey account, string? cursor, int limit, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var boundary = TransferTaskValidation.Cursor(cursor, account, limit);
        lock (_gate)
        {
            var records = _transfers.Values.Where(record => record.Plan.Reference.Account == account &&
                (boundary is not { } after || record.CreatedAt.UtcTicks < after.Ticks || record.CreatedAt.UtcTicks == after.Ticks &&
                    StringComparer.Ordinal.Compare(record.Plan.Reference.Id.ToString("D"), after.Id.ToString("D")) < 0))
                .OrderByDescending(record => record.CreatedAt.UtcTicks)
                .ThenByDescending(record => record.Plan.Reference.Id.ToString("D"), StringComparer.Ordinal)
                .Take(limit + 1).ToArray();
            var page = records.Take(limit).ToArray();
            cancellationToken.ThrowIfCancellationRequested();
            return Task.FromResult(new TransferTaskPage(page, records.Length > limit ? TransferTaskValidation.NextCursor(page[^1]) : null));
        }
    }

    public Task<bool> HasProtectedTransfersAsync(AccountKey account, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        TransferTaskValidation.Account(account);
        lock (_gate) return Task.FromResult(HasProtectedTransfers(account));
    }

    public Task<TransferTaskRecord> SetTransferIntentAsync(TransferTaskRef task, long expectedRevision,
        TransferIntent intent, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        TransferTaskValidation.Reference(task);
        TransferTaskValidation.ExpectedRevision(expectedRevision);
        lock (_gate)
        {
            var current = _transfers.GetValueOrDefault(task) ?? throw TransferTaskValidation.Conflict();
            var next = TransferTaskValidation.Intent(current, expectedRevision, intent);
            _ = TransferTaskValidation.Encode(next);
            cancellationToken.ThrowIfCancellationRequested();
            _transfers[task] = next;
            return Task.FromResult(next);
        }
    }

    public Task<TransferTaskRecord> ClaimTransferAsync(TransferTaskRef task, long expectedRevision, Guid runId,
        bool recovery, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        TransferTaskValidation.Reference(task);
        TransferTaskValidation.ExpectedRevision(expectedRevision);
        lock (_gate)
        {
            EnsureJournalAccount(task.Account);
            var current = _transfers.GetValueOrDefault(task) ?? throw TransferTaskValidation.Conflict();
            Guid? lastRunId = _transferRunIds.TryGetValue(task, out var last) ? last : null;
            var next = TransferTaskValidation.Claim(current, expectedRevision, runId, lastRunId, recovery);
            if (_transfers.Values.Count(record => record.Plan.Reference != task && record.Run is not null) >= 3)
                throw TransferTaskValidation.Capacity();
            string target = TransferTaskValidation.TargetKey(next.Plan);
            if (_transfers.Values.Any(record => record.Plan.Reference != task && TransferTaskValidation.IsProtected(record) &&
                TransferTaskValidation.TargetKey(record.Plan) == target)) throw TransferTaskValidation.Conflict();
            _ = TransferTaskValidation.Encode(next);
            cancellationToken.ThrowIfCancellationRequested();
            _transferRunIds[task] = runId;
            _transfers[task] = next;
            return Task.FromResult(next);
        }
    }

    public Task<TransferTaskRecord> SaveTransferCheckpointAsync(TransferRunToken run, long expectedRevision,
        TransferCheckpoint checkpoint, bool releaseRun, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        TransferTaskValidation.Reference(run.Task);
        TransferTaskValidation.ExpectedRevision(expectedRevision);
        lock (_gate)
        {
            var current = _transfers.GetValueOrDefault(run.Task) ?? throw TransferTaskValidation.Conflict();
            var next = TransferTaskValidation.Progress(current, run, expectedRevision, checkpoint, releaseRun);
            _ = TransferTaskValidation.Encode(next);
            cancellationToken.ThrowIfCancellationRequested();
            _transfers[run.Task] = next;
            return Task.FromResult(next);
        }
    }

    private bool HasProtectedTransfers(AccountKey account) => _transfers.Values.Any(record =>
        record.Plan.Reference.Account == account && TransferTaskValidation.IsProtected(record));

    private void GuardTransferRouteIdentity(SavedConnection replacement)
    {
        string route = TransferTaskValidation.RouteKey(replacement.Profile);
        if (_transfers.Keys.Any(task => task.Account.ProfileId == replacement.Profile.Id &&
            task.Account.SourceRevision == replacement.Profile.SourceRevision && _transferRoutes[task] != route))
            throw TransferTaskValidation.RouteChanged();
    }
}
