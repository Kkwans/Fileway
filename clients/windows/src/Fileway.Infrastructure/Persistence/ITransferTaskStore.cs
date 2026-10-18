using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Persistence;

/// <summary>Same-store durable tasks and compare-and-swap claims; opening the store never executes or replays work.</summary>
public interface ITransferTaskStore
{
    bool IsPersistent { get; }
    Task RemoveTransferAsync(TransferTaskRef task, long expectedRevision, CancellationToken cancellationToken) => Task.FromException(new NotSupportedException());
    Task<TransferTaskRecord> CreateTransferAsync(TransferPlan plan, CancellationToken cancellationToken);
    Task<TransferTaskRecord?> GetTransferAsync(TransferTaskRef task, CancellationToken cancellationToken);
    Task<TransferTaskPage> ListTransfersAsync(AccountKey account, string? cursor, int limit, CancellationToken cancellationToken);
    Task<bool> HasProtectedTransfersAsync(AccountKey account, CancellationToken cancellationToken);
    /// <summary>Changes desired control only; the running owner may still record an actual final acknowledgement.</summary>
    Task<TransferTaskRecord> SetTransferIntentAsync(TransferTaskRef task, long expectedRevision,
        TransferIntent intent, CancellationToken cancellationToken);
    /// <summary>
    /// Recovery preserves uncertainty and fences the previous run. The caller must first establish exclusive
    /// native file access and stop/drain prior execution; this metadata claim alone does not prove either.
    /// </summary>
    Task<TransferTaskRecord> ClaimTransferAsync(TransferTaskRef task, long expectedRevision, Guid runId,
        bool recovery, CancellationToken cancellationToken);
    /// <summary>Late evidence may persist after logout; it cannot replace intent, plan or a newer run's ownership.</summary>
    Task<TransferTaskRecord> SaveTransferCheckpointAsync(TransferRunToken run, long expectedRevision,
        TransferCheckpoint checkpoint, bool releaseRun, CancellationToken cancellationToken);
}
