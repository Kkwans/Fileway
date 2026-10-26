using Fileway.Core.Contracts;
using Fileway.Infrastructure.Persistence;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices : INavigationStateService
{
    public async Task<RemoteResourceRef?> GetLastDirectoryAsync(AccountKey account, CancellationToken cancellationToken)
    {
        var state = GetState(account);
        if (_profiles is not INavigationStateStore navigation) return null;
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        var path = await StorageAsync(() => navigation.ReadLastDirectoryAsync(account, linked.Token)).ConfigureAwait(false);
        if (path is null || path == "/") return null;
        try
        {
            ValidateWirePath(path);
            var file = await GetAsync(new RemoteResourceRef(account, path, path, path), linked.Token).ConfigureAwait(false);
            EnsureActive(state);
            return file.IsDirectory ? file.Resource : null;
        }
        catch (FilewayException error) when (error.Error.HttpStatus is 403 or 404 || error.Error.Code == ErrorCode.InvalidRequest)
        { return null; }
    }

    public async Task RememberDirectoryAsync(RemoteResourceRef directory, CancellationToken cancellationToken)
    {
        var state = GetResourceState(directory);
        ValidateWirePath(directory.WirePath);
        if (_profiles is not INavigationStateStore navigation) return;
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        await _persistence.WaitAsync(linked.Token).ConfigureAwait(false);
        try
        {
            EnsureActive(state);
            await StorageAsync(() => navigation.WriteLastDirectoryAsync(directory.Account, directory.WirePath, linked.Token)).ConfigureAwait(false);
        }
        finally { _persistence.Release(); }
    }
}
