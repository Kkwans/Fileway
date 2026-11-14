namespace Fileway.Core.Contracts;

public enum FileConflictPolicy { Fail, KeepBoth, Replace, Skip }
public enum OperationKind
{
    CreateEmptyFile, CreateDirectory, Rename, BatchRename, Copy, Move, Trash, Restore,
    TaskCancel, TaskRetry, TaskArchive, TaskUnarchive,
    AddFavorite, UpdateFavorite, RemoveFavorite, CreateFavoriteGroup, UpdateFavoriteGroup,
    RemoveFavoriteGroup, ReorderFavorites, ReorderFavoriteGroups, CreateTag, UpdateTag, RemoveTag,
    AddTagResource, RemoveTagResource, DeleteTrashPermanent,
}
public enum OperationPhase { Intent, DispatchMarked, Acknowledged, Rejected, OutcomeUnknown, NotSent }
public enum OperationAckKind
{
    FileWriteConfirmed, BatchRenameConfirmed, TaskAccepted, CancellationRequested,
    RetryAccepted, Archived, Unarchived, TrashRestored, MetadataConfirmed,
}
public enum OperationResolution { Pending, NotSent, Confirmed, ObservedDesiredState, ObservedTaskState, NeedsReview }

public readonly record struct OperationRef(AccountKey Account, Guid Id);
public sealed record OperationReceipt(Guid OperationId, bool AckPersisted, ErrorInfo? PersistenceWarning = null);
public sealed record OperationResourceSnapshot(RemoteResourceRef Resource, bool? IsDirectory = null,
    long? Size = null, DateTimeOffset? Modified = null);
public sealed record OperationItem(int Ordinal, OperationResourceSnapshot? Source = null,
    OperationResourceSnapshot? Target = null, FileConflictPolicy? Conflict = null,
    FileOperationItemOutcome? Outcome = null, ErrorInfo? Error = null);

/// <summary>Allowlisted business fields; never stores raw Host JSON, endpoints, credentials or lease URLs.</summary>
public sealed record OperationOptions(string? RecordId = null, string? Name = null, string? GroupId = null,
    string? Color = null, IReadOnlyList<string>? RecordIds = null, FileConflictPolicy? Conflict = null,
    Guid? BatchId = null, string? DisplayPath = null);

public sealed record OperationIntent(OperationRef Reference, Guid OriginSessionId, OperationKind Kind,
    IReadOnlyList<OperationItem> Items, OperationOptions? Options = null)
{
    public override string ToString() => "OperationIntent (business data redacted)";
}

public sealed record OperationAcknowledgement(OperationAckKind Kind, OperationOutcome Outcome = OperationOutcome.Confirmed,
    string? RecordId = null, ServerTaskRef? Task = null, bool? Skipped = null, string? DisplayPath = null,
    IReadOnlyList<OperationItem>? Items = null);
public sealed record OperationReconciliation(OperationRef Reference, OperationResolution Resolution,
    ServerTask? Task = null, ErrorInfo? Error = null);
public sealed record OperationRecord(OperationIntent Intent, OperationPhase Phase, long Revision,
    DateTimeOffset CreatedAt, DateTimeOffset UpdatedAt, OperationAcknowledgement? Acknowledgement = null,
    ErrorInfo? Error = null, OperationReconciliation? Reconciliation = null)
{
    public override string ToString() => "OperationRecord (business data redacted)";
}
public sealed record OperationPage(IReadOnlyList<OperationRecord> Items, string? NextCursor);

public interface IOperationHistoryService
{
    Task<OperationPage> ListOperationsAsync(AccountKey account, string? cursor, int limit, CancellationToken cancellationToken);
    Task<OperationReconciliation> ReconcileAsync(OperationRef operation, CancellationToken cancellationToken);
}
