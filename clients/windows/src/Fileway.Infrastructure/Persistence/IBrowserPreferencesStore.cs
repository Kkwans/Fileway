using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Persistence;

/// <summary>Non-sensitive presentation owned by a saved account and decoded opaque directory identity (uppercase hexadecimal bytes).</summary>
public interface IBrowserPreferencesStore
{
    Task<BrowserPreferences?> ReadBrowserPreferencesAsync(AccountKey account, string directoryIdentity, CancellationToken cancellationToken);
    Task SaveBrowserPreferencesAsync(AccountKey account, string directoryIdentity, BrowserPreferences preferences, CancellationToken cancellationToken);
}
