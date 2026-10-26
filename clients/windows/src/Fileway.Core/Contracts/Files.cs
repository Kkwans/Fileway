using System.Text.Json.Serialization;

namespace Fileway.Core.Contracts;

public enum FileSortField
{
    Name,
    Size,
    Modified,
    Type,
}

public sealed record DirectoryQuery(FileSortField SortBy = FileSortField.Name, bool Descending = false, string? Cursor = null);

public sealed record RemoteFile(
    RemoteResourceRef Resource,
    bool IsDirectory,
    long Size,
    DateTimeOffset? Modified,
    string? MediaType = null);

public sealed record DirectoryPage(
    RemoteResourceRef Directory,
    IReadOnlyList<RemoteFile> Items,
    string? NextCursor,
    string? SnapshotId);

public enum OperationOutcome
{
    Confirmed,
    Partial,
    OutcomeUnknown,
}

public enum FileOperationItemOutcome
{
    Confirmed,
    Failed,
    OutcomeUnknown,
    NotAttempted,
}

/// <summary>One requested resource's actual result; unattempted items must never be reported as canceled writes.</summary>
public sealed record FileOperationItemResult(
    RemoteResourceRef Resource,
    FileOperationItemOutcome Outcome,
    ErrorInfo? Error = null,
    OperationReceipt? Receipt = null);

public sealed record FileOperationResult(
    OperationOutcome Outcome,
    string? ServerTaskId = null,
    ErrorInfo? Error = null,
    IReadOnlyList<FileOperationItemResult>? Items = null,
    OperationReceipt? Receipt = null);

public enum AuthenticationMode
{
    Password,
    NoAuthentication,
}

/// <summary>Ephemeral login input. Secrets are excluded from serialization; retention requires KeepSignedIn and an account-bound vault.</summary>
public sealed record LoginInput(
    AuthenticationMode Mode,
    string Username = "",
    [property: JsonIgnore] string Password = "",
    bool KeepSignedIn = false)
{
    public override string ToString() => "LoginInput (credentials redacted)";
}

public interface IConnectionService
{
    /// <summary>Publishes verified metadata for the same active account; never contains credentials.</summary>
    event EventHandler<ConnectionSession>? SessionUpdated;
    event EventHandler<ConnectionIssue>? ConnectionIssue;
    Task<ConnectionSession> ConnectAsync(ConnectionProfile profile, LoginInput login, CancellationToken cancellationToken);
    Task<ConnectionSession> RestoreAsync(Guid profileId, CancellationToken cancellationToken);
    Task DisconnectAsync(AccountKey account, CancellationToken cancellationToken);
    Task LogoutAsync(AccountKey account, CancellationToken cancellationToken);
}

public interface IFileRepository
{
    Task<RemoteResourceRef> GetRootAsync(ConnectionSession session, CancellationToken cancellationToken);
    Task<DirectoryPage> ListAsync(RemoteResourceRef directory, DirectoryQuery query, CancellationToken cancellationToken);
    Task<RemoteFile> GetAsync(RemoteResourceRef resource, CancellationToken cancellationToken);
    Task<FileOperationResult> CreateDirectoryAsync(RemoteResourceRef parent, string name, CancellationToken cancellationToken);
    Task<FileOperationResult> RenameAsync(RemoteResourceRef resource, string name, CancellationToken cancellationToken);
    Task<FileOperationResult> TrashAsync(IReadOnlyList<RemoteResourceRef> resources, CancellationToken cancellationToken);
}
