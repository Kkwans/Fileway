using Fileway.Core.Integration;

namespace Fileway.Core.Contracts;

/// <summary>One file inside a duplicate group (server filesystem path).</summary>
public sealed record DuplicateFileInfo(string Path, long Size, DateTimeOffset? Modified, bool HasIdentity);

/// <summary>One group of identical files; the server suggests which copy to keep.</summary>
public sealed record DuplicateGroup(string Sha256, long Size, int TotalFiles, long ReclaimableBytes,
    IReadOnlyList<DuplicateFileInfo> Files, string? SuggestedKeepPath, string? KeepReason);

/// <summary>Completed duplicate-scan report; bounded by the server's result limits.</summary>
public sealed record DuplicateReport(IReadOnlyList<string> Scopes, long ScannedFiles, long ScannedBytes,
    long CandidateFiles, int DuplicateGroups, int DuplicateFiles, long ReclaimableBytes,
    IReadOnlyList<DuplicateGroup> Groups, int SkippedCount, bool Truncated)
{
    public override string ToString() => "DuplicateReport (private paths redacted)";
}

/// <summary>User choice for one group: keep this path, clean the group's other copies.</summary>
public sealed record DuplicateCleanupSelection(string Sha256, string KeepPath);

public sealed record DuplicateCleanupFileResult(string Path, string Status, string? TrashId, string? Reason);

public sealed record DuplicateCleanupResult(string ReportId, IReadOnlyList<(string Sha256, string KeepPath,
    IReadOnlyList<DuplicateCleanupFileResult> Files)> Groups)
{
    public override string ToString() => "DuplicateCleanupResult (private paths redacted)";
}

/// <summary>
/// Server-side duplicate analysis and recycle-bin-based cleanup. Cleanup only ever trashes the
/// non-kept copies of user-selected groups; nothing is deleted outright and nothing runs itself.
/// </summary>
public interface IDuplicateAnalysisService
{
    Task<ServerTaskRef> StartScanAsync(RemoteResourceRef directory, CancellationToken cancellationToken);
    Task<DuplicateReport?> GetReportAsync(ServerTaskRef task, CancellationToken cancellationToken);
    Task<ServerTask> GetTaskAsync(ServerTaskRef task, CancellationToken cancellationToken);
    /// <summary>Submits a recycle-bin cleanup for the selected groups; returns the cleanup task id.</summary>
    Task<ServerTaskRef> StartCleanupAsync(AccountKey account, string reportId, IReadOnlyList<DuplicateCleanupSelection> selections,
        CancellationToken cancellationToken);
    /// <summary>Existing cleanup task for a report, or null when none was started (404).</summary>
    Task<ServerTask?> GetCleanupForReportAsync(AccountKey account, string reportId, CancellationToken cancellationToken);
    /// <summary>Finished cleanup result, or null while the cleanup task is still running (404).</summary>
    Task<DuplicateCleanupResult?> GetCleanupResultAsync(ServerTaskRef cleanupTask, CancellationToken cancellationToken);
}
