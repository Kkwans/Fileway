using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Persistence;

/// <summary>Account-isolated, compare-and-swap operation history; ACK cannot be downgraded by late cancellation.</summary>
public interface IOperationJournalStore
{
    bool IsPersistent { get; }
    Task<bool> HasUnresolvedOperationsAsync(AccountKey account, CancellationToken cancellationToken);
    Task<OperationRecord> CreateIntentAsync(OperationIntent intent, CancellationToken cancellationToken);
    Task<OperationRecord> MarkDispatchAsync(OperationRef operation, long expectedRevision, CancellationToken cancellationToken);
    Task<OperationRecord> RecordAckAsync(OperationRef operation, long expectedRevision, OperationAcknowledgement acknowledgement, CancellationToken cancellationToken);
    Task<OperationRecord> RecordRejectedOrUnknownAsync(OperationRef operation, long expectedRevision, OperationPhase phase,
        ErrorInfo? failure, CancellationToken cancellationToken);
    Task<OperationPage> ListOperationsAsync(AccountKey account, string? cursor, int limit, CancellationToken cancellationToken);
    Task<OperationRecord?> GetOperationAsync(OperationRef operation, CancellationToken cancellationToken);
    Task<OperationRecord> SaveReconciliationAsync(OperationRef operation, long expectedRevision,
        OperationReconciliation reconciliation, CancellationToken cancellationToken);
}
