namespace Fileway.Core.Contracts;

/// <summary>Saved connection metadata only. Credentials never cross the view boundary.</summary>
public sealed record SavedConnection(
    ConnectionProfile Profile,
    AuthenticationMode AuthenticationMode,
    string Username,
    string ServerUserId,
    bool KeepSignedIn,
    DateTimeOffset LastConnectedAt)
{
    public AccountKey Account => new(Profile.Id, Profile.SourceRevision, ServerUserId);
    public override string ToString() => "SavedConnection (profile redacted)";
}

/// <summary>Listing a profile never restores authentication or exposes its token.</summary>
public interface IConnectionCatalog
{
    Task<IReadOnlyList<SavedConnection>> ListAsync(CancellationToken cancellationToken);
    Task<SavedConnection?> GetAsync(Guid profileId, CancellationToken cancellationToken);
    Task ForgetAsync(Guid profileId, CancellationToken cancellationToken);
}

/// <summary>A safe connection warning, independent of an already confirmed operation result.</summary>
public sealed record ConnectionIssue(AccountKey Account, Guid SessionId, ErrorInfo Error, bool SessionInvalidated);
