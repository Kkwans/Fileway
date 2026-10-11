using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices : ICachedResourceLeaseProvider
{
    private readonly LocalStorageSettingsService? _storageSettings;

    public async Task<IResourceLease> AcquireCachedAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(resource);
        ValidateWirePath(resource.WirePath);
        var state = GetResourceState(resource);
        // Always fetch real typed metadata first. A caller's display path or supplied
        // SourceVersion cannot stand in for this authenticated metadata response.
        var metadata = await GetAsync(resource, cancellationToken).ConfigureAwait(false);
        EnsureActive(state);
        if (metadata.IsDirectory) throw Safe(ErrorCode.InvalidRequest, "目录不能作为视频打开。");
        if (_storageSettings is null)
        {
            PublishCacheUnavailable(state);
            return await AcquireLeaseAsync(state, resource, new { wirePath = resource.WirePath }, cancellationToken).ConfigureAwait(false);
        }
        try { await _storageSettings.EnsureCacheConfiguredAsync(cancellationToken).ConfigureAwait(false); }
        catch (FilewayException error) when (error.Error.Code == ErrorCode.LocalStorageUnavailable)
        {
            PublishCacheUnavailable(state);
            return await AcquireLeaseAsync(state, resource, new { wirePath = resource.WirePath }, cancellationToken).ConfigureAwait(false);
        }
        string cacheKey = CacheIdentity(new
        {
            account = resource.Account,
            wirePath = resource.WirePath,
            size = metadata.Size,
            modified = metadata.Modified?.UtcDateTime.Ticks,
            sourceVersion = metadata.Resource.SourceVersion,
            // Without a reliable source version, size-only content may not cross sessions.
            ephemeralSession = metadata.Modified is null && metadata.Resource.SourceVersion is null ? state.Session.Id : (Guid?)null,
        });
        return await AcquireLeaseAsync(state, resource, new
        {
            wirePath = resource.WirePath, cacheKey, cacheOwner = CacheOwner(resource.Account),
        }, cancellationToken).ConfigureAwait(false);
    }

    private async Task<IResourceLease> AcquireLeaseAsync(SessionState state, RemoteResourceRef resource, object parameters,
        CancellationToken cancellationToken)
    {
        var result = await SessionRequestAsync(state, parameters, requireDownload: true, HostCallSafety.ReadOnly,
            cancellationToken, operation: "lease").ConfigureAwait(false);
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

    private async Task<bool> CompleteLogoutCleanupAsync(AccountKey account, CancellationToken cancellationToken)
    {
        await RetireExactAccountAsync(account).ConfigureAwait(false);
        FilewayException? failure = null;
        bool cacheClean = true;
        try { await CredentialsAsync(() => _credentials.DeleteTokenAsync(account, cancellationToken)).ConfigureAwait(false); }
        catch (FilewayException error) { failure = error; }
        if (_storageSettings is not null)
        {
            try { await _storageSettings.ClearOwnerCacheAsync(CacheOwner(account), cancellationToken).ConfigureAwait(false); }
            catch (FilewayException error) when (error.Error.Code == ErrorCode.Canceled) { failure ??= error; }
            catch (FilewayException) { cacheClean = false; }
        }
        if (failure is not null) throw failure; // Leave the durable logout intent for recovery.
        if (!cacheClean) return false; // Auth is retired; unavailable cache storage must not block other accounts.
        await StorageAsync(() => _profiles.CompleteLogoutAsync(account, cancellationToken)).ConfigureAwait(false);
        return true;
    }

    private void PublishCacheUnavailable(SessionState state)
    {
        if (Interlocked.Exchange(ref state.CacheWarningPublished, 1) != 0) return;
        PublishEvent(ConnectionIssue, new ConnectionIssue(state.Session.Account, state.Session.Id,
            new ErrorInfo(ErrorCode.LocalStorageUnavailable, "本地视频缓存暂不可用，播放继续使用服务器数据。"), false));
    }

    private static string CacheOwner(AccountKey account) => CacheIdentity(new
    {
        profileId = account.ProfileId, sourceRevision = account.SourceRevision, serverUserId = account.ServerUserId,
    });

    private static string CacheIdentity<T>(T value) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(value)))).ToLowerInvariant();
}
