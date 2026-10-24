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
    Verifying, Publishing, Reconciling, Completed, Canceled, Paused,
}

/// <summary>Safe evidence. ConfirmedOffset is the flushed local prefix; received body bytes are separate.</summary>
public sealed record DownloadTaskSnapshot(TransferTaskRef Task, TransferIntent Intent, TransferPhase Phase,
    long? ConfirmedOffset, long? TotalBytes, bool LocalPublished, TransferUncertainty Uncertainty,
    ErrorInfo? Error, long Revision, long Generation, bool CanReconcileLocally = false,
    DateTimeOffset? CreatedAt = null, DateTimeOffset? UpdatedAt = null, bool CanResumeLocally = false);

/// <summary>
/// Presentation data only; no private plan, owned file identity, partial path or read capability.
/// PresentationRevision orders in-memory updates within this application instance; zero means unavailable.
/// It is distinct from the durable checkpoint revision and must not be persisted as execution evidence.
/// </summary>
public sealed record DownloadItem(DownloadContext Context, RemoteResourceRef Source, string Name,
    string DestinationDisplayPath, DownloadStatus Status, DownloadTaskSnapshot Snapshot,
    DownloadTaskProgress? Progress = null, long PresentationRevision = 0, bool CanPause = false, bool CanResume = false)
{
    public override string ToString() => "DownloadItem (private display locations redacted)";
}

/// <summary>Explicit current-process writer control; restart resume requires pinned-version checkpoint evidence.</summary>
public interface IDownloadControlService
{
    Task<DownloadItem> SetPausedAsync(DownloadContext context, TransferTaskRef task, bool paused, CancellationToken cancellationToken);
    /// <summary>
    /// Explicit durable resume of an interrupted task with pinned-version partial evidence. It re-enters
    /// the same bounded FIFO writer queue; a live in-process task is refused in favour of pause control.
    /// </summary>
    Task<DownloadItem> ResumeAsync(DownloadContext context, TransferTaskRef task, CancellationToken cancellationToken);
    Task RemoveAsync(DownloadContext context, TransferTaskRef task, bool deleteLocalFile, CancellationToken cancellationToken);
    /// <summary>
    /// Verifies a completed download's published local file (identity, length, ancestors) and, on
    /// success, reports that the confirmed path may be opened by the system default app. It opens
    /// nothing itself, grants no write/delete authority, and never touches the NAS original.
    /// </summary>
    Task<bool> VerifyLocalOpenAsync(DownloadContext context, TransferTaskRef task, CancellationToken cancellationToken);
}

public sealed record DownloadPage(IReadOnlyList<DownloadItem> Items, string? NextCursor);

/// <summary>One explicitly confirmed server-side ZIP export; ItemWirePaths are opaque entries under Directory.</summary>
public sealed record ArchiveDownloadRequest(DownloadContext Context, RemoteResourceRef Directory,
    IReadOnlyList<string> ItemWirePaths, string SuggestedName)
{
    public override string ToString() => "ArchiveDownloadRequest (private locations redacted)";
}

/// <summary>
/// One-shot archive export streamed to the effective download directory. It creates no durable task,
/// offers no resume, and is canceled/drained with the owning account like every download.
/// </summary>
public interface IArchiveDownloadService
{
    /// <summary>Returns the published local file identity of the saved archive.</summary>
    Task<string> DownloadArchiveAsync(ArchiveDownloadRequest request, IProgress<long>? progress, CancellationToken cancellationToken);
}

/// <summary>One enumerated file of a bounded directory tree; RelativePath is from the selected root
/// and MappedRelativePath is its Windows-safe rendering (illegal/reserved segments sanitized).</summary>
public sealed record DownloadTreeEntry(RemoteResourceRef Resource, string RelativePath, string MappedRelativePath)
{
    public override string ToString() => "DownloadTreeEntry (private locations redacted)";
}

/// <summary>Bounded enumeration evidence for a batch preview; enumeration never starts transfers.</summary>
public sealed record DownloadTreeEnumeration(IReadOnlyList<DownloadTreeEntry> Files,
    IReadOnlyList<string> FailedDirectories, bool Truncated)
{
    public override string ToString() => "DownloadTreeEnumeration (private locations redacted)";
}

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
    /// <summary>
    /// Enqueues every durably resumable interrupted download of this account through the same bounded
    /// FIFO admission; listing itself never starts work, and ineligible tasks are simply not returned.
    /// </summary>
    Task<IReadOnlyList<DownloadItem>> ResumeEligibleAsync(DownloadContext context, CancellationToken cancellationToken);
    /// <summary>Bounded recursive enumeration of one directory for a batch preview; never starts transfers.</summary>
    Task<DownloadTreeEnumeration> EnumerateTreeAsync(DownloadContext context, RemoteResourceRef directory, CancellationToken cancellationToken);
    /// <summary>
    /// Prepares one enumerated tree entry under the effective download directory, preserving the mapped
    /// relative structure. The fixed batch identity deduplicates submissions; the preview stays a draft.
    /// </summary>
    Task<DownloadPreview> PrepareTreeAsync(DownloadContext context, RemoteResourceRef root, DownloadTreeEntry entry,
        Guid batchId, int ordinal, CancellationToken cancellationToken);
}
