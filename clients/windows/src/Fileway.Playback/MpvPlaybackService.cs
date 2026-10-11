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
    private readonly Lazy<MpvNative> _native;
    private readonly ConcurrentDictionary<Guid, PlaybackEntry> _sessions = new();
    private readonly SemaphoreSlim _lifecycle = new(1, 1);
    private readonly object _disposeGate = new();
    private Task? _disposeTask;
    private long _generation;
    private int _disposed;

    private sealed record PlaybackEntry(MpvPlaybackSession Native, IPlaybackSession Presented);

    public MpvPlaybackService(IResourceLeaseProvider leases, IPlaybackSurfaceFactory surfaces, string runtimeDirectory,
        IPlaybackProgressStore? progressStore = null)
    {
        ArgumentNullException.ThrowIfNull(leases);
        ArgumentNullException.ThrowIfNull(surfaces);
        ArgumentException.ThrowIfNullOrWhiteSpace(runtimeDirectory);
        _leases = leases;
        _surfaces = surfaces;
        _progressStore = progressStore;
        _native = new Lazy<MpvNative>(() => new MpvNative(runtimeDirectory));
    }

    public async Task<IPlaybackSession> OpenAsync(PlaybackOpenRequest request, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(request);
        ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed) != 0, this);
        await _lifecycle.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed) != 0, this);
            PlaybackResumePrompt? prompt = null;
            ErrorInfo? progressError = null;
            var remote = request.Input as RemotePlaybackInput;
            if (remote is not null && _progressStore is not null)
            {
                try
                {
                    var bookmark = await _progressStore.ReadAsync(remote.Resource, cancellationToken).ConfigureAwait(false);
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
            var native = await Task.Run(() => _native.Value, cancellationToken).ConfigureAwait(false);
            var session = new MpvPlaybackSession(request, Interlocked.Increment(ref _generation), native, _leases, _surfaces,
                id => _sessions.TryRemove(id, out _));
            IPlaybackSession presented = remote is not null && _progressStore is not null
                ? new ProgressPlaybackSession(session, remote.Resource, _progressStore, prompt, progressError) : session;
            session.PresentationSession = presented;
            _sessions.TryAdd(session.Id, new PlaybackEntry(session, presented));
            try
            {
                await session.StartAsync(cancellationToken).ConfigureAwait(false);
                return presented;
            }
            catch
            {
                await presented.DisposeAsync().ConfigureAwait(false);
                throw;
            }
        }
        finally { _lifecycle.Release(); }
    }

    private static TimeSpan ResumeMinimum(TimeSpan? minimum)
    {
        double seconds = minimum?.TotalSeconds ?? 10;
        return TimeSpan.FromSeconds(seconds > 0 ? Math.Clamp(seconds, 5, 600) : 10);
    }

    public async Task CloseAccountAsync(AccountKey account, CancellationToken cancellationToken)
    {
        await _lifecycle.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            foreach (var session in _sessions.Values.Where(session => session.Native.Account == account))
                await session.Presented.StopAsync(cancellationToken).ConfigureAwait(false);
        }
        finally { _lifecycle.Release(); }
    }

    public ValueTask DisposeAsync()
    {
        lock (_disposeGate)
        {
            // Publish disposal intent before waiting: later opens must reject,
            // while the shared task still waits for the in-flight open to finish.
            Volatile.Write(ref _disposed, 1);
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
