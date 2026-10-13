using System.Collections.Concurrent;
using System.Diagnostics;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Playback.Contracts;
using Fileway.Playback.Native;

namespace Fileway.Playback;

public sealed class MpvPlaybackService : IPlaybackService
{
    private readonly IResourceLeaseProvider _leases;
    private readonly IPlaybackSurfaceFactory _surfaces;
    private readonly IPlaybackProgressStore? _progressStore;
    private readonly IRecentAccessService? _recentAccess;
    private readonly IDownloadPlaybackSourceProvider? _downloads;
    private readonly Lazy<MpvNative> _native;
    private readonly ConcurrentDictionary<Guid, PlaybackEntry> _sessions = new();
    private readonly SemaphoreSlim _lifecycle = new(1, 1);
    private readonly object _disposeGate = new();
    private readonly HashSet<PendingOpen> _pendingOpens = [];
    private PendingOpen? _downloadAdmission;
    private Task? _disposeTask;
    private long _generation;
    private int _disposed;

    private sealed record PlaybackEntry(MpvPlaybackSession Native, IPlaybackSession Presented);

    private sealed class PendingOpen(AccountKey? account, CancellationToken cancellationToken) : IAsyncDisposable
    {
        private readonly CancellationTokenSource _stop = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        private Task _cancellation = Task.CompletedTask;
        internal AccountKey? Account { get; } = account;
        internal CancellationToken Token => _stop.Token;
        internal bool Retired { get; private set; }

        // Called under the service gate. Cancellation is signaled now, but no
        // provider callback is synchronously awaited by account retirement.
        internal void Retire()
        {
            if (Retired) return;
            Retired = true;
            _cancellation = CancelAsync();
        }

        private async Task CancelAsync()
        {
            try { await _stop.CancelAsync().ConfigureAwait(false); }
            catch (Exception) { /* A provider callback cannot veto account retirement. */ }
        }

        public async ValueTask DisposeAsync()
        {
            await _cancellation.ConfigureAwait(false);
            _stop.Dispose();
        }
    }

    public MpvPlaybackService(IResourceLeaseProvider leases, IPlaybackSurfaceFactory surfaces, string runtimeDirectory,
        IPlaybackProgressStore? progressStore = null, IRecentAccessService? recentAccess = null,
        IDownloadPlaybackSourceProvider? downloads = null)
    {
        ArgumentNullException.ThrowIfNull(leases);
        ArgumentNullException.ThrowIfNull(surfaces);
        ArgumentException.ThrowIfNullOrWhiteSpace(runtimeDirectory);
        _leases = leases;
        _surfaces = surfaces;
        _progressStore = progressStore;
        _recentAccess = recentAccess;
        _downloads = downloads;
        _native = new Lazy<MpvNative>(() => new MpvNative(runtimeDirectory));
    }

    public async Task<IPlaybackSession> OpenAsync(PlaybackOpenRequest request, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(request);
        PendingOpen pending;
        lock (_disposeGate)
        {
            ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed) != 0, this);
            cancellationToken.ThrowIfCancellationRequested();
            if (request.Input is DownloadPlaybackInput && _downloadAdmission is not null)
                throw MpvNative.Error(ErrorCode.Busy, "上一次下载播放读取仍在收尾。");
            var account = request.Input switch
            {
                DownloadPlaybackInput input => (AccountKey?)input.Context.Account,
                RemotePlaybackInput remote => remote.Resource.Account,
                LocalPlaybackInput local => local.Origin?.Account,
                _ => null,
            };
            pending = new PendingOpen(account, cancellationToken);
            _pendingOpens.Add(pending);
            if (request.Input is DownloadPlaybackInput) _downloadAdmission = pending;
        }
        cancellationToken = pending.Token;
        DownloadPlaybackSource? download = null;
        DownloadPlaybackObservation? observation = null;
        MpvPlaybackSession? session = null;
        IPlaybackSession? presented = null;
        try
        {
            cancellationToken.ThrowIfCancellationRequested();
            ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed) != 0, this);
            PlaybackResumePrompt? prompt = null;
            ErrorInfo? progressError = null;
            if (request.Input is DownloadPlaybackInput input)
            {
                if (_downloads is null) throw MpvNative.Error(ErrorCode.CapabilityMissing, "当前下载不支持播放读取。");
                observation = new DownloadPlaybackObservation(_downloads, input);
                download = await _downloads.AcquirePlaybackAsync(input.Context, input.Task, cancellationToken).ConfigureAwait(false);
                cancellationToken.ThrowIfCancellationRequested();
                observation.Admit(download);
            }
            var resource = (request.Input as RemotePlaybackInput)?.Resource ?? download?.Item.Source;
            if (resource is not null && _progressStore is not null)
            {
                try
                {
                    var bookmark = await _progressStore.ReadAsync(resource, cancellationToken).ConfigureAwait(false);
                    if (request.ResumePosition is null && bookmark is not null &&
                        bookmark.Position > ResumeMinimum(bookmark.MinimumPosition))
                    {
                        prompt = new PlaybackResumePrompt(bookmark.Position, bookmark.Mode);
                        if (bookmark.Mode == PlaybackResumeMode.Resume) request = request with { ResumePosition = bookmark.Position };
                    }
                }
                catch (Exception error)
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    var code = error is FilewayException fileway ? fileway.Error.Code : ErrorCode.Unknown;
                    progressError = new ErrorInfo(code, "未能读取远端播放进度，本次播放从指定位置开始。");
                    Trace.TraceWarning("Fileway playback progress read failed ({0}).", code);
                }
            }
            // Admission and progress may need account retirement to complete.
            // Neither may hold the native lifetime semaphore while awaiting a provider.
            await _lifecycle.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                cancellationToken.ThrowIfCancellationRequested();
                ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed) != 0, this);
                var native = await Task.Run(() => _native.Value, cancellationToken).ConfigureAwait(false);
                lock (_disposeGate)
                {
                    if (pending.Retired) throw new OperationCanceledException(cancellationToken);
                    cancellationToken.ThrowIfCancellationRequested();
                    ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed) != 0, this);
                    // Registration is atomic with account retirement. If close won,
                    // this open never creates a surface; if open won, close owns Stop.
                    session = new MpvPlaybackSession(request, Interlocked.Increment(ref _generation), native, _leases, _surfaces,
                        id => _sessions.TryRemove(id, out _), _recentAccess, download, observation);
                    download = null;
                    observation = null;
                    presented = resource is not null && _progressStore is not null
                        ? new ProgressPlaybackSession(session, resource, _progressStore, prompt, progressError) : session;
                    session.PresentationSession = presented;
                    _sessions.TryAdd(session.Id, new PlaybackEntry(session, presented));
                }
            }
            finally { _lifecycle.Release(); }
            // CloseAccount can now find the session and cancel blocked startup.
            await session.StartAsync(cancellationToken).ConfigureAwait(false);
            cancellationToken.ThrowIfCancellationRequested();
            return presented;
        }
        catch
        {
            if (session is not null) await (presented ?? session).DisposeAsync().ConfigureAwait(false);
            else
            {
                try { if (observation is not null) await observation.DisposeAsync().ConfigureAwait(false); }
                finally { if (download is not null) await download.Reader.DisposeAsync().ConfigureAwait(false); }
            }
            throw;
        }
        finally
        {
            lock (_disposeGate) _pendingOpens.Remove(pending);
            try { await pending.DisposeAsync().ConfigureAwait(false); }
            finally
            {
                // A late provider and its reader cleanup occupy this one slot
                // until this original Open settles. No detached cleanup queue.
                lock (_disposeGate)
                    if (ReferenceEquals(_downloadAdmission, pending)) _downloadAdmission = null;
            }
        }
    }

    private static TimeSpan ResumeMinimum(TimeSpan? minimum)
    {
        double seconds = minimum?.TotalSeconds ?? 10;
        return TimeSpan.FromSeconds(seconds > 0 ? Math.Clamp(seconds, 5, 600) : 10);
    }

    public async Task CloseAccountAsync(AccountKey account, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        PlaybackEntry[] sessions;
        lock (_disposeGate)
        {
            foreach (var pending in _pendingOpens.Where(open => open.Account == account)) pending.Retire();
            sessions = _sessions.Values.Where(entry => entry.Native.Account == account).ToArray();
        }
        // In particular, do not await noncooperative admission here: retiring
        // the account after this method returns may be what finally unblocks it.
        foreach (var session in sessions) await session.Presented.StopAsync(cancellationToken).ConfigureAwait(false);
    }

    public ValueTask DisposeAsync()
    {
        lock (_disposeGate)
        {
            // Fence pending work before any native lifetime wait. Original opens
            // retain responsibility for late provider results and reader cleanup.
            Volatile.Write(ref _disposed, 1);
            foreach (var pending in _pendingOpens) pending.Retire();
            _disposeTask ??= DisposeCoreAsync();
            return new ValueTask(_disposeTask);
        }
    }

    private async Task DisposeCoreAsync()
    {
        await _lifecycle.WaitAsync(CancellationToken.None).ConfigureAwait(false);
        try
        {
            foreach (var session in _sessions.Values) await session.Presented.DisposeAsync().ConfigureAwait(false);
            // Failed native teardown retains its module and surface rather than
            // unloading code which a native thread might still be executing.
            if (_sessions.IsEmpty && _native.IsValueCreated) _native.Value.Dispose();
        }
        finally { _lifecycle.Release(); }
    }
}
