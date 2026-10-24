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
    Task<DownloadLocalFileHash> CalculateSha256Async(CancellationToken cancellationToken);
    Task<string> PublishNoReplaceAsync(CancellationToken cancellationToken);
    Task DeleteOwnedPartialAsync(CancellationToken cancellationToken);
}

public interface IDownloadDestinationProvider
{
    /// <summary>Exclusively creates a fresh same-directory partial; never opens an existing name as an owned writable partial or replaces it.</summary>
    Task<IDownloadDestination> CreateFreshAsync(string finalPath, CancellationToken cancellationToken);
    /// <summary>Reopens only positively identified owned partial/final evidence; never creates, renames or deletes.</summary>
    Task<IDownloadDestination> OpenOwnedAsync(string finalPath, TransferWorkFiles files, CancellationToken cancellationToken);
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
