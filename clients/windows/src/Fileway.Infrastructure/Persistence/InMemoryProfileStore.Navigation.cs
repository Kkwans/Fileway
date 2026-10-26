using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Persistence;

public sealed partial class InMemoryProfileStore
{
    private readonly Dictionary<AccountKey, string> _lastDirectories = [];
    public Task<string?> ReadLastDirectoryAsync(AccountKey account, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested(); ProfileStoreValidation.Validate(account);
        lock (_gate) return Task.FromResult(_lastDirectories.GetValueOrDefault(account));
    }
    public Task WriteLastDirectoryAsync(AccountKey account, string wirePath, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested(); ProfileStoreValidation.Validate(account);
        if (string.IsNullOrEmpty(wirePath) || !wirePath.StartsWith('/')) throw ProfileStoreValidation.Unavailable();
        lock (_gate)
        {
            if (_pending.Contains(account) || !_saved.TryGetValue(account.ProfileId, out var saved) || saved.Account != account)
                throw ProfileStoreValidation.Unavailable();
            _lastDirectories[account] = wirePath;
        }
        return Task.CompletedTask;
    }
}
