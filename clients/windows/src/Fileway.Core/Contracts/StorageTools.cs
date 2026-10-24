using Fileway.Core.Integration;

namespace Fileway.Core.Contracts;

/// <summary>One analyzed scope of a server storage scan (server filesystem path).</summary>
public sealed record StorageAnalysisScope(string Path, bool IsDirectory, long Files, long Directories, long Bytes);

public sealed record StorageAnalysisLargestFile(string Path, long Size, DateTimeOffset? Modified);

public sealed record StorageAnalysisLargestDirectory(string Path, long Files, long Bytes);

/// <summary>Completed report of one storage analysis task; bounded by the server's result limits.</summary>
public sealed record StorageAnalysisReport(IReadOnlyList<StorageAnalysisScope> Scopes,
    long ScannedFiles, long ScannedDirectories, long ScannedBytes,
    IReadOnlyList<StorageAnalysisLargestFile> LargestFiles, IReadOnlyList<StorageAnalysisLargestDirectory> LargestDirectories,
    int SkippedCount, bool Truncated)
{
    public override string ToString() => "StorageAnalysisReport (private paths redacted)";
}

/// <summary>
/// Server-side storage analysis: starts a bounded scan task and reads its completed report.
/// Scopes are server filesystem paths (as presented by the server's own metadata); the server
/// enforces permission checks and result limits, so the client never guesses sizes locally.
/// </summary>
public interface IStorageAnalysisService
{
    /// <summary>Starts one analysis task over the directory; the returned task id is server-owned.</summary>
    Task<ServerTaskRef> StartAsync(RemoteResourceRef directory, CancellationToken cancellationToken);

    /// <summary>Current task status snapshot for the referenced analysis task.</summary>
    Task<ServerTask> GetTaskAsync(ServerTaskRef task, CancellationToken cancellationToken);

    /// <summary>Completed report, or null while the task is still running (409/404 coalesce).</summary>
    Task<StorageAnalysisReport?> GetReportAsync(ServerTaskRef task, CancellationToken cancellationToken);
}
