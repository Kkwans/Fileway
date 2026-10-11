using System.Diagnostics;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;

namespace Fileway.Infrastructure.Services;

/// <summary>Startup directory snapshot and durable preferences; no existing data is moved or deleted.</summary>
public sealed partial class LocalStorageSettingsService : ILocalStorageSettingsService, ILocalCacheManagementService, IAsyncDisposable
{
    private readonly IStorageSettingsStore _store;
    private readonly ClientStorageSettings _defaults;
    private readonly IHostControlClient _host;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly object _lifecycle = new();
    private readonly TaskCompletionSource _drained = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private Task? _disposeTask;
    private int _participants;
    private ClientStorageSettings? _active;
    private ClientStorageSettings? _pending;
    private bool _cacheConfigured;

    public LocalStorageSettingsService(IStorageSettingsStore store, ClientStorageSettings defaults, IHostControlClient host)
    {
        _store = store ?? throw new ArgumentNullException(nameof(store));
        _defaults = StorageSettingsValidation.Normalize(defaults);
        _host = host ?? throw new ArgumentNullException(nameof(host));
    }

    public async Task<StorageSettingsState> ReadAsync(CancellationToken cancellationToken)
    {
        await EnterAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await InitializeAsync(cancellationToken).ConfigureAwait(false);
            return State();
        }
        finally { Exit(); }
    }

    /// <summary>Explicit startup initialization; statistics reads never configure or evict cache data.</summary>
    public async Task InitializeCacheAsync(CancellationToken cancellationToken)
    {
        await EnterAsync(cancellationToken).ConfigureAwait(false);
        bool gateHeld = true;
        try
        {
            await ConfigureLockedAsync(cancellationToken).ConfigureAwait(false);
            var profiles = _store as IProfileStore;
            var roots = _store as ICacheRootStore;
            string cacheRoot = (_active ?? throw Unavailable()).CacheDirectory;
            _gate.Release();
            gateHeld = false;
            if (profiles is null || roots is null) return;

            // Pre-ledger versions may have blocks before this process opens a new cached lease.
            // Seed only known full account keys and the trusted startup root, never disk-discovered owners.
            var saved = await RootStoreAsync(() => profiles.ListAsync(cancellationToken)).ConfigureAwait(false);
            var pending = (await RootStoreAsync(() => profiles.ListPendingLogoutsAsync(cancellationToken)).ConfigureAwait(false)).ToHashSet();
            foreach (var account in saved.Select(connection => connection.Account).Concat(pending).Distinct())
            {
                if (pending.Contains(account))
                    await RootStoreAsync(() => roots.RegisterCacheRootForCleanupAsync(account, cacheRoot, cancellationToken)).ConfigureAwait(false);
                else
                    await RootStoreAsync(() => roots.RegisterCacheRootAsync(account, cacheRoot, cancellationToken)).ConfigureAwait(false);
            }
        }
        finally
        {
            if (gateHeld) _gate.Release();
            EndParticipant();
        }
    }

    public async Task<StorageSettingsState> SaveAsync(ClientStorageSettings settings, CancellationToken cancellationToken)
    {
        var normalized = StorageSettingsValidation.Normalize(settings);
        await EnterAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await InitializeAsync(cancellationToken).ConfigureAwait(false);
            try { await _store.SaveStorageSettingsAsync(normalized, cancellationToken).ConfigureAwait(false); }
            catch (OperationCanceledException) { throw Canceled(); }
            catch (FilewayException error) when (error.Error.Code == ErrorCode.Canceled) { throw Canceled(); }
            catch (Exception) { throw Unavailable(); }
            // The durable write is confirmed. A later cache failure cannot turn it into a failed save.
            _pending = normalized;
            var active = _active ?? throw Unavailable();
            if (StorageSettingsValidation.SameDirectory(active.CacheDirectory, normalized.CacheDirectory))
            {
                var updated = active with { MaximumCacheBytes = normalized.MaximumCacheBytes };
                if (!_cacheConfigured) _active = updated;
                else
                {
                    try
                    {
                        await ConfigureAsync(updated, CancellationToken.None).ConfigureAwait(false);
                        _active = updated;
                    }
                    catch (FilewayException error)
                    {
                        Trace.TraceWarning("Fileway saved cache capacity is pending ({0}).", error.Error.Code);
                    }
                }
            }
            return State();
        }
        finally { Exit(); }
    }

    internal async Task EnsureCacheConfiguredAsync(CancellationToken cancellationToken)
    {
        await EnterAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await ConfigureLockedAsync(cancellationToken).ConfigureAwait(false);
        }
        finally { Exit(); }
    }

    private async Task ConfigureLockedAsync(CancellationToken cancellationToken)
    {
        await InitializeAsync(cancellationToken).ConfigureAwait(false);
        var active = _active ?? throw Unavailable();
        var pending = _pending ?? throw Unavailable();
        var effective = StorageSettingsValidation.SameDirectory(active.CacheDirectory, pending.CacheDirectory)
            ? active with { MaximumCacheBytes = pending.MaximumCacheBytes } : active;
        if (_cacheConfigured && effective == active) return;
        await ConfigureAsync(effective, cancellationToken).ConfigureAwait(false);
        _active = effective;
        _cacheConfigured = true;
    }

    private async Task InitializeAsync(CancellationToken cancellationToken)
    {
        if (_active is not null) return;
        ClientStorageSettings? stored;
        try { stored = await _store.ReadStorageSettingsAsync(cancellationToken).ConfigureAwait(false); }
        catch (OperationCanceledException) { throw Canceled(); }
        catch (FilewayException error) when (error.Error.Code == ErrorCode.Canceled) { throw Canceled(); }
        catch (Exception) { throw Unavailable(); }
        _active = StorageSettingsValidation.Normalize(stored ?? _defaults);
        _pending = _active;
    }

    private Task ConfigureAsync(ClientStorageSettings settings, CancellationToken cancellationToken) =>
        CacheCallAsync("cache_configure", new
        {
            cacheConfig = new { directory = settings.CacheDirectory, maxBytes = settings.MaximumCacheBytes, hours = 720, managed = true },
        }, cancellationToken);

    private async Task CacheCallAsync(string operation, object parameters, CancellationToken cancellationToken)
    {
        try
        {
            var reply = await _host.CallAsync(new HostCall(operation, JsonSerializer.SerializeToElement(parameters),
                Safety: HostCallSafety.Control), cancellationToken).ConfigureAwait(false);
            if (!reply.IsSuccess)
            {
                if (reply.Error?.Code == "Canceled") throw Canceled();
                throw Unavailable();
            }
        }
        catch (OperationCanceledException) { throw Canceled(); }
        catch (FilewayException error) when (error.Error.Code == ErrorCode.Canceled) { throw Canceled(); }
        catch (Exception) { throw Unavailable(); }
    }

    private StorageSettingsState State()
    {
        var active = _active ?? throw Unavailable();
        var pending = _pending ?? throw Unavailable();
        return new(active, pending,
            !StorageSettingsValidation.SameDirectory(active.CacheDirectory, pending.CacheDirectory) ||
            !StorageSettingsValidation.SameDirectory(active.DownloadDirectory, pending.DownloadDirectory) ||
            active.MaximumCacheBytes != pending.MaximumCacheBytes);
    }

    private static FilewayException Canceled() => new(new ErrorInfo(ErrorCode.Canceled, "操作已取消。"));
    private static FilewayException Unavailable() => new(new ErrorInfo(ErrorCode.LocalStorageUnavailable, "无法访问本地缓存设置或缓存数据，请稍后重试。"));
    private async Task EnterAsync(CancellationToken cancellationToken)
    {
        lock (_lifecycle)
        {
            ObjectDisposedException.ThrowIf(_disposeTask is not null, this);
            _participants++;
        }
        try { await _gate.WaitAsync(cancellationToken).ConfigureAwait(false); }
        catch { EndParticipant(); throw; }
    }

    private void Exit()
    {
        try { _gate.Release(); }
        finally { EndParticipant(); }
    }

    private void EndParticipant()
    {
        lock (_lifecycle)
            if (--_participants == 0 && _disposeTask is not null) _drained.TrySetResult();
    }

    public async ValueTask DisposeAsync()
    {
        Task disposal;
        lock (_lifecycle)
        {
            _disposeTask ??= DisposeCoreAsync();
            if (_participants == 0) _drained.TrySetResult();
            disposal = _disposeTask;
        }
        await disposal.ConfigureAwait(false);
        GC.SuppressFinalize(this);
    }

    private async Task DisposeCoreAsync()
    {
        await _drained.Task.ConfigureAwait(false);
        _gate.Dispose();
    }
    public override string ToString() => "LocalStorageSettingsService (private locations redacted)";
}
