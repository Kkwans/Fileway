namespace Fileway.Core.Contracts;

/// <summary>Account-bound restoration of the main browser directory, never viewer or transient pane state.</summary>
public interface INavigationStateService
{
    Task<RemoteResourceRef?> GetLastDirectoryAsync(AccountKey account, CancellationToken cancellationToken);
    Task RememberDirectoryAsync(RemoteResourceRef directory, CancellationToken cancellationToken);
}
