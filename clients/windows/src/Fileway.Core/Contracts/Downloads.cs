using Fileway.Core.Integration;

namespace Fileway.Core.Contracts;

/// <summary>Presentation authority for one verified application transfer activation.</summary>
public readonly record struct DownloadContext(AccountKey Account, Guid SessionId, Guid ActivationId);

/// <summary>Opaque confirmation identity. Its fixed ID is also the durable request/task ID.</summary>
public readonly record struct DownloadDraftRef(Guid Id);

/// <summary>Read-only preview of the original source and effective destination; Name is the confirmed local filename.</summary>
public sealed record DownloadPreview(DownloadContext Context, DownloadDraftRef Draft, RemoteResourceRef Source,
    string Name, string DestinationDisplayPath, long TotalBytes)
{
    public override string ToString() => "DownloadPreview (private locations redacted)";
}

public enum DownloadStatus
{
    NotStarted, Queued, Preparing, Downloading, Canceling, Stopped, WaitingNetwork,
    WaitingAuthentication, Conflict, SourceChanged, Failed, OutcomeUnknown,
    Verifying, Publishing, Reconciling, Completed, Canceled,
}

/// <summary>Safe evidence. ConfirmedOffset is the flushed local prefix; received body bytes are separate.</summary>
public sealed record DownloadTaskSnapshot(TransferTaskRef Task, TransferIntent Intent, TransferPhase Phase,
    long? ConfirmedOffset, long? TotalBytes, bool LocalPublished, TransferUncertainty Uncertainty,
    ErrorInfo? Error, long Revision, long Generation, bool CanReconcileLocally = false);

/// <summary>Presentation data only; no private plan, owned file identity, partial path or read capability.</summary>
public sealed record DownloadItem(DownloadContext Context, RemoteResourceRef Source, string Name,
    string DestinationDisplayPath, DownloadStatus Status, DownloadTaskSnapshot Snapshot,
    DownloadTaskProgress? Progress = null)
{
    public override string ToString() => "DownloadItem (private display locations redacted)";
}

public sealed record DownloadPage(IReadOnlyList<DownloadItem> Items, string? NextCursor);

/// <summary>Explicit fresh downloads. Preparing, listing and connecting never start stored work; there is no resume.</summary>
public interface IDownloadService
{
    event EventHandler<DownloadItem>? TaskChanged;
    DownloadContext? CurrentContext { get; }
    Task<DownloadPreview> PrepareAsync(DownloadContext context, RemoteResourceRef source, CancellationToken cancellationToken);
    /// <summary>Explicit safe local filename within the effective download directory; source identity is unchanged.</summary>
    Task<DownloadPreview> PrepareAsAsync(DownloadContext context, RemoteResourceRef source, string localName, CancellationToken cancellationToken);
    Task<DownloadItem> SubmitAsync(DownloadPreview preview, CancellationToken cancellationToken);
    Task<DownloadPage> ListAsync(DownloadContext context, string? cursor, int limit, CancellationToken cancellationToken);
    /// <summary>Persisted cancellation drains writers and cleans only positively owned unpublished partials.</summary>
    Task<DownloadItem> CancelAsync(DownloadContext context, TransferTaskRef task, CancellationToken cancellationToken);
    /// <summary>Inspects local ownership/hash evidence only; never starts network reads, resumes or repeats publication.</summary>
    Task<DownloadItem> ReconcileLocalAsync(DownloadContext context, TransferTaskRef task, CancellationToken cancellationToken);
}
