namespace Fileway.Core.Contracts;

public readonly record struct TrashItemRef(AccountKey Account, string Id);
public sealed record TrashItem(TrashItemRef Reference, string OwnerUserId, string DisplayPath, string Status,
    bool IsDirectory, long? Size, string? SizeState, DateTimeOffset? DeletedAt = null, DateTimeOffset? UndoUntil = null)
{
    public override string ToString() => "TrashItem (server details redacted)";
}
public sealed record TrashRestoreResult(OperationOutcome Outcome, bool? Skipped = null,
    string? RestoredDisplayPath = null, OperationReceipt? Receipt = null, ErrorInfo? Error = null);

public interface ITrashService
{
    Task<IReadOnlyList<TrashItem>> ListTrashAsync(AccountKey account, CancellationToken cancellationToken);
    Task<TrashRestoreResult> RestoreTrashAsync(TrashItemRef item, FileConflictPolicy conflict, CancellationToken cancellationToken);
}

public interface ITrashManagementService : ITrashService
{
    Task<FileOperationResult> DeletePermanentlyAsync(TrashItemRef item, CancellationToken cancellationToken);
}
