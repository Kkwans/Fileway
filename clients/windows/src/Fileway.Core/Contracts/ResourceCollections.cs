namespace Fileway.Core.Contracts;

/// <summary>A display alias never establishes resource identity. Null Resource remains visible but cannot be opened or unlinked by path.</summary>
public sealed record CollectionResource(string DisplayPath, RemoteResourceRef? Resource);

public sealed record ResourceFavorite(
    AccountKey Account,
    string Id,
    string Name,
    string? GroupId,
    int Order,
    DateTimeOffset? AddedAt,
    CollectionResource Reference);

public sealed record FavoriteGroup(AccountKey Account, string Id, string Name, string? Color, int Order);

public sealed record ResourceTag(
    AccountKey Account,
    string Id,
    string Name,
    string Color,
    DateTimeOffset? CreatedAt,
    IReadOnlyList<CollectionResource> Resources);

/// <summary>Three account-owned server collections, not an atomic filesystem or metadata snapshot.</summary>
public sealed record ResourceCollectionsSnapshot(
    AccountKey Account,
    IReadOnlyList<ResourceFavorite> Favorites,
    IReadOnlyList<FavoriteGroup> FavoriteGroups,
    IReadOnlyList<ResourceTag> Tags);

/// <summary>Unknown writes must be refreshed and explained; never automatically replayed. RecordId is supplied only by a confirmed server acknowledgement.</summary>
public sealed record CollectionWriteResult(OperationOutcome Outcome, string? RecordId = null, ErrorInfo? Error = null);

/// <summary>Server-owned user metadata, separate from filesystem Create/Rename/Delete permissions. Every operation stays bound to its active account.</summary>
public interface IResourceCollectionsService
{
    Task<ResourceCollectionsSnapshot> GetCollectionsAsync(AccountKey account, CancellationToken cancellationToken);
    Task<CollectionWriteResult> AddFavoriteAsync(RemoteResourceRef resource, string name, string? groupId, CancellationToken cancellationToken);
    Task<CollectionWriteResult> UpdateFavoriteAsync(AccountKey account, string id, string name, string? groupId, CancellationToken cancellationToken);
    Task<CollectionWriteResult> RemoveFavoriteAsync(AccountKey account, string id, CancellationToken cancellationToken);
    Task<CollectionWriteResult> CreateFavoriteGroupAsync(AccountKey account, string name, string? color, CancellationToken cancellationToken);
    Task<CollectionWriteResult> UpdateFavoriteGroupAsync(AccountKey account, string id, string name, string? color, CancellationToken cancellationToken);
    Task<CollectionWriteResult> RemoveFavoriteGroupAsync(AccountKey account, string id, CancellationToken cancellationToken);
    Task<CollectionWriteResult> ReorderFavoritesAsync(AccountKey account, IReadOnlyList<string> ids, CancellationToken cancellationToken);
    Task<CollectionWriteResult> ReorderFavoriteGroupsAsync(AccountKey account, IReadOnlyList<string> ids, CancellationToken cancellationToken);
    Task<CollectionWriteResult> CreateTagAsync(AccountKey account, string name, string color, CancellationToken cancellationToken);
    Task<CollectionWriteResult> UpdateTagAsync(AccountKey account, string id, string name, string color, CancellationToken cancellationToken);
    Task<CollectionWriteResult> RemoveTagAsync(AccountKey account, string id, CancellationToken cancellationToken);
    Task<CollectionWriteResult> AddTagResourceAsync(string tagId, RemoteResourceRef resource, CancellationToken cancellationToken);
    Task<CollectionWriteResult> RemoveTagResourceAsync(string tagId, RemoteResourceRef resource, CancellationToken cancellationToken);
}
