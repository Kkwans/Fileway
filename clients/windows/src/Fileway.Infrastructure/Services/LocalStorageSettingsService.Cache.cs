using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;

namespace Fileway.Infrastructure.Services;

public sealed partial class LocalStorageSettingsService
{
    // A successful registration is required before any disk-backed lease is issued.
    // Callers without a ledger remain source-compatible and stream without caching.
    internal async Task RegisterCacheUseAsync(AccountKey account, ICacheRootStore? roots, CancellationToken cancellationToken)
    {
        await EnterAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (roots is null) throw Unavailable();
            await ConfigureLockedAsync(cancellationToken).ConfigureAwait(false);
            await RootStoreAsync(() => roots.RegisterCacheRootAsync(account,
                (_active ?? throw Unavailable()).CacheDirectory, cancellationToken)).ConfigureAwait(false);
        }
        finally { Exit(); }
    }

    internal async Task RegisterCacheCleanupAsync(AccountKey account, ICacheRootStore roots, CancellationToken cancellationToken)
    {
        await EnterAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            // Upgrade from pre-ledger versions may already have owner blocks in this trusted startup root.
            // No arbitrary historical disk is inferred and no cache configuration is needed for cleanup.
            await InitializeAsync(cancellationToken).ConfigureAwait(false);
            await RootStoreAsync(() => roots.RegisterCacheRootForCleanupAsync(account,
                (_active ?? throw Unavailable()).CacheDirectory, cancellationToken)).ConfigureAwait(false);
        }
        finally { Exit(); }
    }

    public async Task<LocalCacheUsage> ReadCacheUsageAsync(CancellationToken cancellationToken)
    {
        var snapshot = await BeginCacheManagementAsync(cancellationToken).ConfigureAwait(false);
        try { return await ReadCacheUsageAsync(snapshot, cancellationToken).ConfigureAwait(false); }
        finally { EndParticipant(); }
    }

    public async Task<LocalCacheUsage> ClearInactiveCacheAsync(CancellationToken cancellationToken)
    {
        var snapshot = await BeginCacheManagementAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            bool failed = false;
            foreach (var root in snapshot.Roots)
            {
                try
                {
                    await CacheOwnership.RequireRegisteredRootAvailableAsync(root.CacheRoot, cancellationToken).ConfigureAwait(false);
                    await CacheCallAsync("cache_cleanup_owner", new
                    {
                        cacheOwner = CacheOwnership.Owner(root.Account), cacheRoot = root.CacheRoot, inactiveOnly = true,
                    }, cancellationToken).ConfigureAwait(false);
                }
                catch (FilewayException error) when (error.Error.Code != ErrorCode.Canceled) { failed = true; }
            }
            // Never remove registrations here: active groups may still belong to these roots.
            var usage = await ReadCacheUsageAsync(snapshot, cancellationToken).ConfigureAwait(false);
            if (failed) throw Unavailable();
            return usage;
        }
        finally { EndParticipant(); }
    }

    private sealed record CacheManagementSnapshot(ClientStorageSettings Active, RegisteredCacheRoot[] Roots)
    {
        public override string ToString() => "CacheManagementSnapshot (private locations redacted)";
    }

    private async Task<CacheManagementSnapshot> BeginCacheManagementAsync(CancellationToken cancellationToken)
    {
        await EnterAsync(cancellationToken).ConfigureAwait(false);
        bool captured = false;
        try
        {
            await InitializeAsync(cancellationToken).ConfigureAwait(false);
            if (!_cacheConfigured) throw Unavailable();
            var roots = _store as ICacheRootStore ?? throw Unavailable();
            var registered = await RootStoreAsync(() => roots.ListCacheRootsAsync(cancellationToken)).ConfigureAwait(false);
            var snapshot = new CacheManagementSnapshot(_active ?? throw Unavailable(), DistinctRoots(registered).ToArray());
            captured = true;
            return snapshot;
        }
        finally
        {
            _gate.Release();
            if (!captured) EndParticipant();
        }
        // Successful callers retain their lifecycle participant until all out-of-lock I/O has finished.
    }

    private async Task<LocalCacheUsage> ReadCacheUsageAsync(CacheManagementSnapshot snapshot, CancellationToken cancellationToken)
    {
        var active = snapshot.Active;
        var current = await ReadCacheStatisticsAsync(new { }, cancellationToken).ConfigureAwait(false);
        if (!current.ActivePool) throw Unavailable();
        long previousBytes = 0;
        var unavailable = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var root in snapshot.Roots)
        {
            if (StorageSettingsValidation.SameDirectory(root.CacheRoot, active.CacheDirectory)) continue;
            try
            {
                await CacheOwnership.RequireRegisteredRootAvailableAsync(root.CacheRoot, cancellationToken).ConfigureAwait(false);
                var statistics = await ReadCacheStatisticsAsync(new
                {
                    cacheOwner = CacheOwnership.Owner(root.Account), cacheRoot = root.CacheRoot,
                }, cancellationToken).ConfigureAwait(false);
                // Go compares filesystem identity too, so junction/symlink aliases of the current pool are not counted twice.
                if (!statistics.ActivePool) previousBytes = checked(previousBytes + statistics.Bytes);
            }
            catch (FilewayException error) when (error.Error.Code != ErrorCode.Canceled) { unavailable.Add(root.CacheRoot); }
            catch (OverflowException) { throw Unavailable(); }
        }
        return new(current.Bytes, active.MaximumCacheBytes, current.Files, current.Protected,
            previousBytes, unavailable.Count);
    }

    private static IEnumerable<RegisteredCacheRoot> DistinctRoots(IEnumerable<RegisteredCacheRoot> roots) =>
        roots.DistinctBy(root => (root.CacheRoot.ToUpperInvariant(), CacheOwnership.Owner(root.Account)));

    private readonly record struct CacheStatistics(long Bytes, int Files, int Protected, bool ActivePool);

    private async Task<CacheStatistics> ReadCacheStatisticsAsync(object parameters, CancellationToken cancellationToken)
    {
        try
        {
            var reply = await _host.CallAsync(new HostCall("cache_stats", JsonSerializer.SerializeToElement(parameters),
                Safety: HostCallSafety.ReadOnly), cancellationToken).ConfigureAwait(false);
            if (!reply.IsSuccess)
            {
                if (reply.Error?.Code == "Canceled") throw Canceled();
                throw Unavailable();
            }
            var result = reply.Result ?? throw Unavailable();
            long bytes = result.GetProperty("bytes").GetInt64();
            int files = result.GetProperty("files").GetInt32();
            int protectedCount = result.GetProperty("protected").GetInt32();
            long protectedBytes = result.GetProperty("protectedBytes").GetInt64();
            bool activePool = result.GetProperty("activePool").GetBoolean();
            if (bytes < 0 || files < 0 || protectedCount < 0 || protectedCount > files || protectedBytes < 0 || protectedBytes > bytes)
                throw Unavailable();
            return new(bytes, files, protectedCount, activePool);
        }
        catch (OperationCanceledException) { throw Canceled(); }
        catch (FilewayException error) when (error.Error.Code == ErrorCode.Canceled) { throw Canceled(); }
        catch (Exception) { throw Unavailable(); }
    }

    private static async Task<T> RootStoreAsync<T>(Func<Task<T>> operation)
    {
        try { return await operation().ConfigureAwait(false); }
        catch (OperationCanceledException) { throw Canceled(); }
        catch (FilewayException error) when (error.Error.Code == ErrorCode.Canceled) { throw Canceled(); }
        catch (Exception) { throw Unavailable(); }
    }

    private static async Task RootStoreAsync(Func<Task> operation) =>
        await RootStoreAsync(async () => { await operation().ConfigureAwait(false); return true; }).ConfigureAwait(false);
}
