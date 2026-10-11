using Fileway.Core.Contracts;
using Fileway.Infrastructure.Persistence;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices
{
    public async Task<BrowserPreferences> ReadBrowserPreferencesAsync(RemoteResourceRef directory, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(directory);
        var state = GetResourceState(directory);
        var store = BrowserPreferencesStore();
        string identity = FileOperationWireIdentity(directory.WirePath, allowRoot: true);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        var preferences = await StorageAsync(() => store.ReadBrowserPreferencesAsync(directory.Account, identity, linked.Token))
            .ConfigureAwait(false);
        EnsureActive(state);
        return preferences ?? new BrowserPreferences();
    }

    public async Task SaveBrowserPreferencesAsync(RemoteResourceRef directory, BrowserPreferences preferences, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(directory);
        ArgumentNullException.ThrowIfNull(preferences);
        if (!Enum.IsDefined(preferences.ViewMode) || !Enum.IsDefined(preferences.CompactGridSize)
            || !Enum.IsDefined(preferences.WindowsIconSize) || !Enum.IsDefined(preferences.TypeFilter)
            || !Enum.IsDefined(preferences.SortBy))
            throw Safe(ErrorCode.InvalidRequest, "视图设置无效，请重新选择。");
        var state = GetResourceState(directory);
        var store = BrowserPreferencesStore();
        string identity = FileOperationWireIdentity(directory.WirePath, allowRoot: true);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        // Share the logout/profile-save gate. A stale UI callback cannot publish
        // local settings after durable logout intent has been recorded.
        await _persistence.WaitAsync(linked.Token).ConfigureAwait(false);
        try
        {
            EnsureActive(state);
            await StorageAsync(() => store.SaveBrowserPreferencesAsync(directory.Account, identity, preferences, linked.Token))
                .ConfigureAwait(false);
            EnsureActive(state);
        }
        finally { _persistence.Release(); }
    }

    private IBrowserPreferencesStore BrowserPreferencesStore() => _profiles as IBrowserPreferencesStore
        ?? throw Safe(ErrorCode.LocalStorageUnavailable, "本机视图设置暂不可用。本次布局仍可使用，但尚未保存。");
}
