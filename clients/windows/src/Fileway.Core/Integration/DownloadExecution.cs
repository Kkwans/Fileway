using Fileway.Core.Contracts;

namespace Fileway.Core.Integration;

/// <summary>Length and SHA-256 reread from the held, owned local file after its writes have drained.</summary>
public sealed record DownloadLocalFileHash(long Length, string Sha256);

/// <summary>
/// Owned local destination. Raw handles remain in Infrastructure. Dispose releases handles; only
/// DeleteOwnedPartialAsync deletes, and it must refuse any published or no-longer-owned file.
/// </summary>
public interface IDownloadDestination : IAsyncDisposable
{
    TransferWorkFiles Files { get; }
    bool IsPublished { get; }
    long Length { get; }
    Task WriteAsync(ReadOnlyMemory<byte> bytes, CancellationToken cancellationToken);
    Task<long> FlushToDiskAsync(CancellationToken cancellationToken);
    /// <summary>Same-handle truncation of an owned unpublished partial; it never extends or replaces the file.</summary>
    Task TruncateAsync(long length, CancellationToken cancellationToken) => Task.FromException(NotSupported());
    Task<DownloadLocalFileHash> CalculateSha256Async(CancellationToken cancellationToken);
    Task<string> PublishNoReplaceAsync(CancellationToken cancellationToken);
    Task DeleteOwnedPartialAsync(CancellationToken cancellationToken);

    private static NotSupportedException NotSupported() => new("此本地文件能力不支持截断已持有的下载临时文件。");
}

public interface IDownloadDestinationProvider
{
    /// <summary>Exclusively creates a fresh same-directory partial; never opens an existing name as an owned writable partial or replaces it.</summary>
    Task<IDownloadDestination> CreateFreshAsync(string finalPath, CancellationToken cancellationToken);
    /// <summary>Reopens only positively identified owned partial/final evidence; never creates, renames or deletes.</summary>
    Task<IDownloadDestination> OpenOwnedAsync(string finalPath, TransferWorkFiles files, CancellationToken cancellationToken);

    /// <summary>
    /// Reopens an owned unpublished partial with read admission over its verified prefix. Durable
    /// resume uses this after truncating to the confirmed offset; it never grants write or delete
    /// authority beyond the owning execution and is not a publication path.
    /// </summary>
    Task<IDownloadDestination> OpenOwnedReadableAsync(string finalPath, TransferWorkFiles files,
        CancellationToken cancellationToken) => Task.FromException<IDownloadDestination>(
            new NotSupportedException("此本地文件能力不支持重开下载临时文件的读取。"));
}

/// <summary>Received bytes are distinct from the successfully flushed local prefix.</summary>
public sealed record DownloadTaskProgress(TransferRunToken Run, long ReceivedBytes, long? ConfirmedOffset,
    long TotalBytes, TimeSpan Elapsed);

/// <summary>One fresh writer at a time. Reconcile is local-only; optional pause control does not imply restart/Range resume.</summary>
public interface IFileDownloadRunner : IAsyncDisposable
{
    event EventHandler<DownloadTaskProgress>? ProgressChanged;
    Task<TransferTaskRecord> RunAsync(TransferTaskRef task, CancellationToken cancellationToken);
    Task<TransferTaskRecord> CancelAsync(TransferTaskRef task, CancellationToken cancellationToken);
    Task<TransferTaskRecord> ReconcileAsync(TransferTaskRef task, CancellationToken cancellationToken);
    /// <summary>Persists cancellation and drains all active writers/capabilities before the original connection retires.</summary>
    Task StopAndDrainAsync(CancellationToken cancellationToken);
}

public interface IActiveDownloadPauseControl
{
    Task<TransferTaskRecord> SetPausedAsync(TransferTaskRef task, bool paused, CancellationToken cancellationToken);
}

/// <summary>
/// Optional durable resume. It requires checkpoint evidence with a pinned source version, a
/// confirmed flushed offset and positively owned partial identity; a fresh-only runner or an
/// unversioned (legacy-server) task never gains resume through this surface.
/// </summary>
public interface IFileDownloadResumeControl
{
    Task<TransferTaskRecord> ResumeAsync(TransferTaskRef task, CancellationToken cancellationToken);
}
