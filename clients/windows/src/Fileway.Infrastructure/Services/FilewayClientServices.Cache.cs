using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices : ICachedResourceLeaseProvider
{
    private readonly LocalStorageSettingsService? _storageSettings;

    public async Task<IResourceLease> AcquireCachedAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
        => await AcquireContentLeaseAsync(resource, thumbnail: false, cancellationToken).ConfigureAwait(false);

    private async Task<IResourceLease> AcquireContentLeaseAsync(RemoteResourceRef resource, bool thumbnail,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(resource);
        ValidateWirePath(resource.WirePath);
        var state = GetResourceState(resource);
        // Always fetch real typed metadata first. A caller's display path or supplied
        // SourceVersion cannot stand in for this authenticated metadata response.
        var metadata = await GetAsync(resource, cancellationToken).ConfigureAwait(false);
        EnsureActive(state);
        if (metadata.IsDirectory) throw Safe(ErrorCode.InvalidRequest, "目录不能作为媒体打开。");
        string operation = thumbnail ? "preview" : "lease";
        object ordinary = thumbnail ? new { wirePath = resource.WirePath, scope = "contain" } : new { wirePath = resource.WirePath };
        if (_storageSettings is null)
        {
            PublishCacheUnavailable(state);
            return await AcquireLeaseAsync(state, resource, ordinary, cancellationToken, operation).ConfigureAwait(false);
        }
        try { await _storageSettings.RegisterCacheUseAsync(resource.Account, _profiles as ICacheRootStore, cancellationToken).ConfigureAwait(false); }
        catch (FilewayException error) when (error.Error.Code == ErrorCode.LocalStorageUnavailable)
        {
            PublishCacheUnavailable(state);
            return await AcquireLeaseAsync(state, resource, ordinary, cancellationToken, operation).ConfigureAwait(false);
        }
        string rawIdentity = CacheOwnership.Identity(new
        {
            account = resource.Account,
            wirePath = resource.WirePath,
            size = metadata.Size,
            modified = metadata.Modified?.UtcDateTime.Ticks,
            sourceVersion = metadata.Resource.SourceVersion,
            // Without a reliable source version, size-only content may not cross sessions.
            ephemeralSession = metadata.Modified is null && metadata.Resource.SourceVersion is null ? state.Session.Id : (Guid?)null,
        });
        // Raw media retains its 0.2.1 identity. Preview bytes occupy the same pool with a distinct representation key.
        string cacheKey = thumbnail ? CacheOwnership.Identity(new { rawIdentity, representation = "contain-512" }) : rawIdentity;
        object cached = thumbnail ? new
        {
            wirePath = resource.WirePath, scope = "contain", cacheKey, cacheOwner = CacheOwnership.Owner(resource.Account),
        } : new
        {
            wirePath = resource.WirePath, cacheKey, cacheOwner = CacheOwnership.Owner(resource.Account),
        };
        try { return await AcquireLeaseAsync(state, resource, cached, cancellationToken, operation).ConfigureAwait(false); }
        catch (FilewayException error) when (error.Error.Code == ErrorCode.LocalStorageUnavailable ||
            error.Error.Code == ErrorCode.NetworkUnavailable && error.Error.HttpStatus is null)
        {
            EnsureActive(state);
            PublishCacheUnavailable(state);
            return await AcquireLeaseAsync(state, resource, ordinary, cancellationToken, operation).ConfigureAwait(false);
        }
    }

    private async Task<IResourceLease> AcquireLeaseAsync(SessionState state, RemoteResourceRef resource, object parameters,
        CancellationToken cancellationToken, string operation = "lease")
    {
        var result = await SessionRequestAsync(state, parameters, requireDownload: true, HostCallSafety.ReadOnly,
            cancellationToken, operation: operation).ConfigureAwait(false);
        string address = RequiredText(result);
        if (!Uri.TryCreate(address, UriKind.Absolute, out var uri) || uri.Scheme != Uri.UriSchemeHttp ||
            uri.Host != "127.0.0.1" || uri.Port <= 0 || uri.IsDefaultPort || uri.UserInfo.Length != 0 ||
            uri.Query.Length != 0 || uri.Fragment.Length != 0)
            throw InvalidResponse();
        var lease = new ResourceLease(this, state, resource, uri);
        lock (_gate)
        {
            if (state.Active) { state.Leases.Add(lease); return lease; }
        }
        await lease.DisposeAsync().ConfigureAwait(false);
        throw Safe(ErrorCode.SourceChanged, "账户连接已关闭，请重新连接。");
    }

    private async Task<bool> CompleteLogoutCleanupAsync(AccountKey account, CancellationToken cancellationToken,
        CancellationToken? cacheCancellationToken = null)
    {
        // Preserve the established auth boundary: retire even if the caller canceled;
        // a failed retirement leaves both durable intent and original vault token intact.
        await RetireExactAccountAsync(account).ConfigureAwait(false);
        await CredentialsAsync(() => _credentials.DeleteTokenAsync(account, cancellationToken)).ConfigureAwait(false);
        if (_passwords is not null)
            await CredentialsAsync(() => _passwords.DeletePasswordAsync(account, cancellationToken)).ConfigureAwait(false);
        try
        {
            if (_storageSettings is not null && _profiles is ICacheRootStore roots)
                await _storageSettings.RegisterCacheCleanupAsync(account, roots, cancellationToken).ConfigureAwait(false);
            await ClearRegisteredAccountCacheAsync(account, cacheCancellationToken ?? cancellationToken).ConfigureAwait(false);
        }
        catch (FilewayException error) when (error.Error.Code == ErrorCode.Canceled &&
            cacheCancellationToken.HasValue && !cancellationToken.IsCancellationRequested)
        { return false; } // Cache recovery deadline never becomes an authentication cancellation token.
        catch (FilewayException error) when (error.Error.Code != ErrorCode.Canceled)
        { return false; } // Auth is retired; unavailable cache storage must not block other accounts.
        await StorageAsync(() => _profiles.CompleteLogoutAsync(account, cancellationToken)).ConfigureAwait(false);
        return true;
    }

    private async Task ClearRegisteredAccountCacheAsync(AccountKey account, CancellationToken cancellationToken)
    {
        if (_profiles is not ICacheRootStore roots) return; // No registered cached lease could have been issued.
        var registered = await StorageAsync(() => roots.ListCacheRootsAsync(cancellationToken)).ConfigureAwait(false);
        using var budget = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        budget.CancelAfter(TimeSpan.FromSeconds(2));
        bool failed = false;
        foreach (var root in registered.Where(root => root.Account == account))
        {
            try
            {
                await CacheOwnership.RequireRegisteredRootAvailableAsync(root.CacheRoot, budget.Token).ConfigureAwait(false);
                await CallAsync("cache_cleanup_owner", new
                {
                    cacheOwner = CacheOwnership.Owner(account), cacheRoot = root.CacheRoot,
                }, null, budget.Token, HostCallSafety.Control).ConfigureAwait(false);
                // Only confirmed owner cleanup can remove the corresponding durable registration.
                await StorageAsync(() => roots.RemoveCacheRootAsync(account, root.CacheRoot, budget.Token)).ConfigureAwait(false);
            }
            catch (FilewayException error) when (error.Error.Code == ErrorCode.Canceled && !cancellationToken.IsCancellationRequested)
            { failed = true; break; }
            catch (FilewayException error) when (error.Error.Code != ErrorCode.Canceled) { failed = true; }
        }
        if (failed) throw Safe(ErrorCode.LocalStorageUnavailable, Message(ErrorCode.LocalStorageUnavailable));
    }

    private void PublishCacheUnavailable(SessionState state)
    {
        if (Interlocked.Exchange(ref state.CacheWarningPublished, 1) != 0) return;
        PublishEvent(ConnectionIssue, new ConnectionIssue(state.Session.Account, state.Session.Id,
            new ErrorInfo(ErrorCode.LocalStorageUnavailable, "本地媒体缓存暂不可用，继续使用服务器数据。"), false));
    }

}
