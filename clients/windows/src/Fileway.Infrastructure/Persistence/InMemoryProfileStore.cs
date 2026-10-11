using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Persistence;

/// <summary>Process-local store for callers that do not opt into persistence.</summary>
public sealed class InMemoryProfileStore : IProfileStore
{
    private readonly object _gate = new();
    private readonly Dictionary<Guid, SavedConnection> _saved = [];
    private readonly HashSet<AccountKey> _pending = [];

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
            if (_saved.TryGetValue(account.ProfileId, out var connection) && connection.Account == account)
                _saved.Remove(account.ProfileId);
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

    public override string ToString() => "InMemoryProfileStore (metadata redacted)";
}
