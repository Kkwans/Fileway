namespace Fileway.Core.Contracts;

public enum ServerTaskStatus { Unknown, Queued, Running, Completed, Failed, Canceled, Interrupted }
public enum ServerTaskCategory { File, Background }
public enum ServerTaskSubmissionOutcome { Accepted, Rejected, OutcomeUnknown, NotSubmitted }
public enum ServerTaskActionKind { CancellationRequested, RetryAccepted, Archived, Unarchived }
public readonly record struct ServerTaskRef(AccountKey Account, string Id);

public sealed record ServerTask(ServerTaskRef Reference, string OwnerUserId, string RawType, string RawStatus,
    ServerTaskStatus Status, string Title, DateTimeOffset CreatedAt,
    long TotalItems, long ProcessedItems, long TotalBytes, long ProcessedBytes,
    DateTimeOffset? StartedAt = null, DateTimeOffset? FinishedAt = null, DateTimeOffset? UndoUntil = null,
    DateTimeOffset? ArchivedAt = null, string? RetryOf = null, string? SourceDisplayPath = null,
    string? OutputDisplayPath = null, ErrorInfo? Error = null)
{
    public override string ToString() => "ServerTask (server details redacted)";
}

public sealed record ServerTaskQuery(IReadOnlyList<ServerTaskStatus>? Statuses = null, string? Type = null,
    bool Archived = false, string? Text = null, DateTimeOffset? From = null, DateTimeOffset? To = null,
    ServerTaskCategory? Category = null, int Limit = 30, string? Cursor = null)
{
    public override string ToString() => "ServerTaskQuery (filter redacted)";
}
public sealed record ServerTaskPage(IReadOnlyList<ServerTask> Items, string? NextCursor, long Total,
    IReadOnlyDictionary<string, long> Counts, IReadOnlyDictionary<string, long> CategoryCounts);
public sealed record ServerTaskSubmissionResult(ServerTaskSubmissionOutcome Outcome, ServerTask? AcceptedTask = null,
    OperationReceipt? Receipt = null, ErrorInfo? Error = null);
public sealed record ServerTaskActionResult(OperationOutcome Outcome, ServerTaskActionKind Kind,
    ServerTask? Task = null, OperationReceipt? Receipt = null, ErrorInfo? Error = null);

/// <summary>Own-user tasks only, including when the connected user also has server administrator rights.</summary>
public interface IServerTaskService
{
    Task<ServerTaskPage> ListTasksAsync(AccountKey account, ServerTaskQuery query, CancellationToken cancellationToken);
    Task<ServerTask> GetTaskAsync(ServerTaskRef task, CancellationToken cancellationToken);
    Task<ServerTaskActionResult> CancelTaskAsync(ServerTaskRef task, CancellationToken cancellationToken);
    Task<ServerTaskActionResult> RetryTaskAsync(ServerTaskRef task, CancellationToken cancellationToken);
    Task<ServerTaskActionResult> ArchiveTaskAsync(ServerTaskRef task, CancellationToken cancellationToken);
    Task<ServerTaskActionResult> UnarchiveTaskAsync(ServerTaskRef task, CancellationToken cancellationToken);
}
