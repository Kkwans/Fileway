using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Persistence;

public sealed partial class InMemoryProfileStore
{
    private readonly Dictionary<(AccountKey Account, string DirectoryIdentity), BrowserPreferences> _browserPreferences = [];

    public Task<BrowserPreferences?> ReadBrowserPreferencesAsync(AccountKey account, string directoryIdentity, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        string identity = BrowserPreferencesValidation.DirectoryIdentity(account, directoryIdentity);
        lock (_gate) return Task.FromResult(_browserPreferences.GetValueOrDefault((account, identity)));
    }

    public Task SaveBrowserPreferencesAsync(AccountKey account, string directoryIdentity, BrowserPreferences preferences, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        string identity = BrowserPreferencesValidation.DirectoryIdentity(account, directoryIdentity);
        BrowserPreferencesValidation.Validate(preferences);
        lock (_gate)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (!_saved.TryGetValue(account.ProfileId, out var saved) || saved.Account != account || _pending.Contains(account))
                throw BrowserPreferencesValidation.Unavailable();
            _browserPreferences[(account, identity)] = preferences;
        }
        return Task.CompletedTask;
    }
}
