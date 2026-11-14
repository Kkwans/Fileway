namespace Fileway.Core.Contracts;

public enum ShareExpiryUnit { Seconds, Minutes, Hours, Days }

/// <summary>Optional password is redacted from diagnostics; expiry null means a permanent link.</summary>
public sealed record ShareCreationRequest(int? Expires, ShareExpiryUnit Unit, string? Password)
{
    public override string ToString() => "ShareCreationRequest (password redacted)";
}

/// <summary>A server-owned share link. DisplayPath is a decoded display alias, never a resource identity. URLs are built service-side.</summary>
public sealed record ShareLink(
    AccountKey Account,
    string Hash,
    string DisplayPath,
    DateTimeOffset? ExpiresAt,
    bool HasPassword,
    string ShareUrl,
    string? DirectDownloadUrl)
{
    public bool IsPermanent => ExpiresAt is null;
    public override string ToString() => "ShareLink (url redacted)";
}

/// <summary>Unknown writes must be refreshed and explained; never automatically replayed.</summary>
public sealed record ShareWriteResult(OperationOutcome Outcome, ShareLink? Link = null, ErrorInfo? Error = null);

public interface IShareService
{
    Task<IReadOnlyList<ShareLink>> ListAsync(AccountKey account, CancellationToken cancellationToken);
    Task<IReadOnlyList<ShareLink>> GetForResourceAsync(RemoteResourceRef resource, CancellationToken cancellationToken);
    Task<ShareWriteResult> CreateAsync(RemoteResourceRef resource, ShareCreationRequest request, CancellationToken cancellationToken);
    Task<ShareWriteResult> DeleteAsync(AccountKey account, string hash, CancellationToken cancellationToken);
}