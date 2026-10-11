using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Persistence;

/// <summary>Application-created cache ownership only; roots are never inferred by scanning a disk.</summary>
public sealed record RegisteredCacheRoot(AccountKey Account, string CacheRoot)
{
    public override string ToString() => "RegisteredCacheRoot (private location redacted)";
}

public interface ICacheRootStore
{
    Task<IReadOnlyList<RegisteredCacheRoot>> ListCacheRootsAsync(CancellationToken cancellationToken);
    Task RegisterCacheRootAsync(AccountKey account, string cacheRoot, CancellationToken cancellationToken);
    /// <summary>Cleanup-only seed for a trusted startup root; requires an existing durable logout intent.</summary>
    Task RegisterCacheRootForCleanupAsync(AccountKey account, string cacheRoot, CancellationToken cancellationToken);
    Task RemoveCacheRootAsync(AccountKey account, string cacheRoot, CancellationToken cancellationToken);
}
