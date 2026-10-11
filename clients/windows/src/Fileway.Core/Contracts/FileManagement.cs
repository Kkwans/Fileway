namespace Fileway.Core.Contracts;

public sealed record ResourceMetadataResult(RemoteResourceRef Requested, RemoteFile? File = null, ErrorInfo? Error = null);
public sealed record BatchRenameItem(RemoteResourceRef Source, string NewName);
public sealed record BatchRenameDraft(Guid Id, IReadOnlyList<BatchRenameItem> Items);
public readonly record struct BatchRenamePreviewRef(AccountKey Account, Guid SessionId, Guid Id);
public sealed record BatchRenamePreviewItem(RemoteResourceRef Source, RemoteResourceRef Target,
    bool Valid, string? Status = null, ErrorInfo? Error = null);
public sealed record BatchRenamePreview(Guid DraftId, AccountKey Account, Guid SessionId, bool Valid,
    IReadOnlyList<BatchRenamePreviewItem> Items, BatchRenamePreviewRef? Executable = null, ErrorInfo? Error = null);
public sealed record BatchRenameResult(OperationOutcome Outcome, bool Valid, bool Executed,
    IReadOnlyList<BatchRenamePreviewItem> Items, OperationReceipt? Receipt = null, ErrorInfo? Error = null);
public enum FileTransferAction { Copy, Move }
public sealed record FileTransferItem(RemoteResourceRef Source, FileConflictPolicy Conflict = FileConflictPolicy.KeepBoth);
public sealed record FileTransferRequest(FileTransferAction Action, RemoteResourceRef Destination,
    IReadOnlyList<FileTransferItem> Items);

public interface IFileManagementService
{
    Task<FileOperationResult> CreateEmptyFileAsync(RemoteResourceRef parent, string name, CancellationToken cancellationToken);
    Task<IReadOnlyList<ResourceMetadataResult>> GetMetadataBatchAsync(IReadOnlyList<RemoteResourceRef> resources, CancellationToken cancellationToken);
    Task<BatchRenamePreview> PreviewBatchRenameAsync(BatchRenameDraft draft, CancellationToken cancellationToken);
    Task<BatchRenameResult> ExecuteBatchRenameAsync(BatchRenamePreviewRef preview, CancellationToken cancellationToken);
    Task<ServerTaskSubmissionResult> SubmitTransferAsync(FileTransferRequest request, CancellationToken cancellationToken);
}
