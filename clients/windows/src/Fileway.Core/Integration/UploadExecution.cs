using Fileway.Core.Contracts;

namespace Fileway.Core.Integration;

/// <summary>Cross-instance execution ownership. Native lock release, rather than a metadata flag, proves old execution stopped.</summary>
public interface ITransferExecutionLock : IAsyncDisposable
{
    TransferTaskRef Task { get; }
}

public interface ITransferExecutionLockProvider
{
    Task<ITransferExecutionLock> AcquireAsync(TransferTaskRef task, CancellationToken cancellationToken);
}

/// <summary>Opened regular file; the Windows implementation denies writes/deletes and does not follow reparse points.</summary>
public interface IUploadSource : IAsyncDisposable
{
    Stream Content { get; }
    TransferSourceSnapshot Snapshot { get; }
    Task VerifyAsync(TransferSourceSnapshot expected, CancellationToken cancellationToken);
}

public interface IUploadSourceProvider
{
    Task<IUploadSource> OpenAsync(string fullPath, CancellationToken cancellationToken);
}

/// <summary>Safe progress; body reads are separate from durable server-confirmed offset.</summary>
public sealed record UploadTaskProgress(TransferRunToken Run, long SentBytes, long? ConfirmedOffset,
    long TotalBytes, TimeSpan Elapsed);

/// <summary>Infrastructure-only file executor. UI uses the eventual transfer coordinator, never private task/lease data.</summary>
public interface IFileUploadRunner : IAsyncDisposable
{
    event EventHandler<UploadTaskProgress>? ProgressChanged;
    Task<TransferTaskRecord> RunAsync(TransferTaskRef task, bool recovery, CancellationToken cancellationToken);
    Task<TransferTaskRecord> PauseAsync(TransferTaskRef task, CancellationToken cancellationToken);
    Task<TransferTaskRecord> CancelAsync(TransferTaskRef task, CancellationToken cancellationToken);
}
