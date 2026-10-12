using Fileway.Core.Integration;

namespace Fileway.Core.Contracts;

/// <summary>Presentation authority for one verified application upload activation.</summary>
public readonly record struct UploadContext(AccountKey Account, Guid SessionId, Guid ActivationId);

/// <summary>An explicitly confirmed local file and original destination. RequestId prevents duplicate task creation.</summary>
public sealed record UploadSubmission(Guid RequestId, UploadContext Context, RemoteResourceRef Parent, string LocalPath)
{
    public override string ToString() => "UploadSubmission (private locations redacted)";
}

public enum UploadStatus
{
    NotStarted, Queued, Preparing, Uploading, Pausing, Paused, WaitingNetwork,
    WaitingAuthentication, Canceling, Conflict, SourceChanged, Failed, OutcomeUnknown,
    Verifying, Publishing, Reconciling, Completed, Canceled, Skipped,
}

/// <summary>Safe application projection. Parent identity is distinct from display text; no execution plan or capability is exposed.</summary>
public sealed record UploadItem(UploadContext Context, RemoteResourceRef Parent, string Name, string DestinationDisplayPath,
    UploadStatus Status, UploadTaskSnapshot Snapshot, UploadTaskProgress? Progress = null)
{
    public override string ToString() => "UploadItem (private display locations redacted)";
}

public sealed record UploadPage(IReadOnlyList<UploadItem> Items, string? NextCursor);

/// <summary>Explicit upload commands. Listing, connecting and opening a status view never start or resume a task.</summary>
public interface IUploadService
{
    event EventHandler<UploadItem>? TaskChanged;
    UploadContext? CurrentContext { get; }
    Task<UploadItem> SubmitAsync(UploadSubmission submission, CancellationToken cancellationToken);
    Task<UploadPage> ListAsync(UploadContext context, string? cursor, int limit, CancellationToken cancellationToken);
    Task<UploadItem> PauseAsync(UploadContext context, TransferTaskRef task, CancellationToken cancellationToken);
    Task<UploadItem> ResumeAsync(UploadContext context, TransferTaskRef task, CancellationToken cancellationToken);
    Task<UploadItem> CancelAsync(UploadContext context, TransferTaskRef task, CancellationToken cancellationToken);
}
