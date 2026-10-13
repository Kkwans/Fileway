using Fileway.Core.Contracts;

namespace Fileway.Core.Integration;

/// <summary>
/// A presentation snapshot and its admitted, generation-bound reader. The receiver owns Reader disposal.
/// This is not a path-based authority and acquiring it never starts, resumes, or completes a download.
/// </summary>
public sealed record DownloadPlaybackSource(DownloadItem Item, IDownloadReadLease Reader)
{
    public override string ToString() => "DownloadPlaybackSource (private task details redacted)";
}

/// <summary>Optional application capability which authorizes an exact download activation before granting reads.</summary>
public interface IDownloadPlaybackSourceProvider : IDownloadService
{
    /// <summary>
    /// Acquires only an already-active task's reader. Session, permission, task and run identity are rechecked
    /// after asynchronous admission; a stale or rejected reader must be released without canceling the writer.
    /// </summary>
    Task<DownloadPlaybackSource> AcquirePlaybackAsync(DownloadContext context, TransferTaskRef task,
        CancellationToken cancellationToken);
}
