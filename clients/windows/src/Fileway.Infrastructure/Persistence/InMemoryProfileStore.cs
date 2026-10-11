using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Persistence;

/// <summary>Process-local store for callers that do not opt into persistence.</summary>
public sealed partial class InMemoryProfileStore : IProfileStore, IStorageSettingsStore, ICacheRootStore, IBrowserPreferencesStore, IOperationJournalStore
{
    private readonly object _gate = new();
    private readonly Dictionary<Guid, SavedConnection> _saved = [];
    private readonly HashSet<AccountKey> _pending = [];
    private ClientStorageSettings? _storageSettings;
    private readonly List<RegisteredCacheRoot> _cacheRoots = [];

    public Task<IReadOnlyList<SavedConnection>> ListAsync(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (_gate) return Task.FromResult<IReadOnlyList<SavedConnection>>(
            _saved.Values.OrderByDescending(value => value.LastConnectedAt).ThenBy(value => value.Profile.Id).ToArray());
    }

    public Task<SavedConnection?> GetAsync(Guid profileId, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (_gate) return Task.FromResult(_saved.GetValueOrDefault(profileId));
    }

    public Task SaveAsync(SavedConnection connection, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (_gate)
        {
            ProfileStoreValidation.Validate(connection);
            if (_pending.Contains(connection.Account)) throw ProfileStoreValidation.Unavailable();
            if (_saved.TryGetValue(connection.Profile.Id, out var previous) && previous.Account != connection.Account &&
                _operations.Values.Any(record => record.Intent.Reference.Account == previous.Account && OperationJournalValidation.IsUnresolved(record)))
                throw OperationJournalValidation.UnresolvedConflict();
            _saved[connection.Profile.Id] = connection;
        }
        return Task.CompletedTask;
    }

    public Task ForgetAsync(AccountKey account, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        ProfileStoreValidation.Validate(account);
        lock (_gate)
        {
            if (_operations.Values.Any(record => record.Intent.Reference.Account == account && OperationJournalValidation.IsUnresolved(record)))
                throw OperationJournalValidation.UnresolvedConflict();
            if (_saved.TryGetValue(account.ProfileId, out var connection) && connection.Account == account)
                _saved.Remove(account.ProfileId);
            foreach (var key in _browserPreferences.Keys.Where(key => key.Account == account).ToArray())
                _browserPreferences.Remove(key);
        }
        return Task.CompletedTask;
    }

    public Task<IReadOnlyList<AccountKey>> ListPendingLogoutsAsync(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (_gate) return Task.FromResult<IReadOnlyList<AccountKey>>(_pending.ToArray());
    }

    public Task BeginLogoutAsync(AccountKey account, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (_gate)
        {
            ProfileStoreValidation.Validate(account);
            _pending.Add(account);
            if (_saved.TryGetValue(account.ProfileId, out var connection) && connection.Account == account)
                _saved[account.ProfileId] = connection with { KeepSignedIn = false };
        }
        return Task.CompletedTask;
    }

    public Task CompleteLogoutAsync(AccountKey account, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        ProfileStoreValidation.Validate(account);
        lock (_gate) _pending.Remove(account);
        return Task.CompletedTask;
    }

    public Task<ClientStorageSettings?> ReadStorageSettingsAsync(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (_gate) return Task.FromResult(_storageSettings);
    }

    public Task SaveStorageSettingsAsync(ClientStorageSettings settings, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var normalized = StorageSettingsValidation.Normalize(settings);
        lock (_gate) _storageSettings = normalized;
        return Task.CompletedTask;
    }

    public Task<IReadOnlyList<RegisteredCacheRoot>> ListCacheRootsAsync(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (_gate) return Task.FromResult<IReadOnlyList<RegisteredCacheRoot>>(_cacheRoots.ToArray());
    }

    public Task RegisterCacheRootAsync(AccountKey account, string cacheRoot, CancellationToken cancellationToken) =>
        RegisterCacheRootAsync(account, cacheRoot, forCleanup: false, cancellationToken);

    public Task RegisterCacheRootForCleanupAsync(AccountKey account, string cacheRoot, CancellationToken cancellationToken) =>
        RegisterCacheRootAsync(account, cacheRoot, forCleanup: true, cancellationToken);

    private Task RegisterCacheRootAsync(AccountKey account, string cacheRoot, bool forCleanup, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        ProfileStoreValidation.Validate(account);
        string normalized = StorageSettingsValidation.DirectoryPath(cacheRoot);
        lock (_gate)
        {
            if (_pending.Contains(account) != forCleanup) throw ProfileStoreValidation.Unavailable();
            if (!_cacheRoots.Any(root => root.Account == account && StorageSettingsValidation.SameDirectory(root.CacheRoot, normalized)))
                _cacheRoots.Add(new(account, normalized));
        }
        return Task.CompletedTask;
    }

    public Task RemoveCacheRootAsync(AccountKey account, string cacheRoot, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        ProfileStoreValidation.Validate(account);
        string normalized = StorageSettingsValidation.DirectoryPath(cacheRoot);
        lock (_gate) _cacheRoots.RemoveAll(root => root.Account == account && StorageSettingsValidation.SameDirectory(root.CacheRoot, normalized));
        return Task.CompletedTask;
    }

    public override string ToString() => "InMemoryProfileStore (metadata redacted)";
}
