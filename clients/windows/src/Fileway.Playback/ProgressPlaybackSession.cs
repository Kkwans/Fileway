using System.Diagnostics;
using System.Runtime.CompilerServices;
using System.Threading.Channels;
using Fileway.Core.Contracts;

namespace Fileway.Playback;

/// <summary>Persists observed engine positions and presents one real remote resume offer.</summary>
internal sealed class ProgressPlaybackSession : IPlaybackSession, IPlaybackResumeControl, IPlaybackResourceSource
{
    RemoteResourceRef? IPlaybackResourceSource.Resource => (_inner as IPlaybackResourceSource)?.Resource;
    private static readonly TimeSpan SaveInterval = TimeSpan.FromSeconds(5);
    private static readonly TimeSpan FinalSaveDeadline = TimeSpan.FromSeconds(5);
    private readonly IPlaybackSession _inner;
    private readonly IPlaybackProgressStore _store;
    private readonly RemoteResourceRef _resource;
    private readonly long _generation;
    private readonly object _gate = new();
    private readonly SemaphoreSlim _mutations = new(1, 1);
    private readonly CancellationTokenSource _lifetime = new();
    private readonly CancellationToken _lifetimeToken;
    private readonly List<Channel<PlaybackSnapshot>> _watchers = [];
    private readonly Channel<bool> _saveRequests = Channel.CreateBounded<bool>(new BoundedChannelOptions(1)
        { FullMode = BoundedChannelFullMode.DropOldest, SingleReader = true });
    private readonly Task _observer;
    private readonly Task _timer;
    private readonly Task _writer;
    private PlaybackResumePrompt? _resumePrompt;
    private ErrorInfo? _progressError;
    private PlaybackStatus _lastStatus;
    private TimeSpan? _lastAttemptedPosition;
    private TimeSpan? _lastAttemptedDuration;
    private Task? _stop;
    private bool _closing;
    private bool _mediaReady;
    private bool _resumeConfirmed;
    private bool _restartPending;
    private bool _requiresReconciliation;
    private bool _unknownClear;
    private bool _persistenceDisabled;

    internal ProgressPlaybackSession(IPlaybackSession inner, RemoteResourceRef resource, IPlaybackProgressStore store,
        PlaybackResumePrompt? resumePrompt, ErrorInfo? progressError)
    {
        _inner = inner;
        _resource = resource;
        _store = store;
        _generation = inner.Snapshot.Generation;
        _resumePrompt = resumePrompt;
        _progressError = progressError;
        _lastStatus = inner.Snapshot.Status;
        _lifetimeToken = _lifetime.Token;
        _observer = Task.Run(ObserveAsync);
        _timer = Task.Run(TickAsync);
        _writer = Task.Run(WriteLoopAsync);
    }

    public Guid Id => _inner.Id;
    public PlaybackSnapshot Snapshot { get { lock (_gate) return Decorate(_inner.Snapshot); } }

    private bool Owns(PlaybackSnapshot snapshot) => snapshot.SessionId == Id && snapshot.Generation == _generation;

    private PlaybackSnapshot Decorate(PlaybackSnapshot snapshot)
    {
        var prompt = _mediaReady && (_resumePrompt?.Mode != PlaybackResumeMode.Resume || _resumeConfirmed)
            ? _resumePrompt : null;
        return snapshot with { ResumePrompt = prompt, Diagnostics = snapshot.Diagnostics with { ProgressError = _progressError } };
    }

    private void PublishLocked(PlaybackSnapshot snapshot)
    {
        if (!Owns(snapshot)) return;
        var presented = Decorate(snapshot);
        foreach (var watcher in _watchers) watcher.Writer.TryWrite(presented);
    }

    private async Task ObserveAsync()
    {
        try
        {
            await foreach (var snapshot in _inner.WatchAsync(_lifetimeToken).ConfigureAwait(false))
            {
                bool flush;
                lock (_gate)
                {
                    if (!Owns(snapshot)) continue;
                    if (snapshot.ConfirmedPosition is { } confirmed)
                    {
                        _mediaReady |= snapshot.Status is PlaybackStatus.Playing or PlaybackStatus.Paused or PlaybackStatus.Buffering;
                        if (_resumePrompt is { Mode: PlaybackResumeMode.Resume } prompt && confirmed >= prompt.Position - TimeSpan.FromSeconds(2))
                            _resumeConfirmed = true;
                        if (_restartPending && confirmed <= TimeSpan.FromSeconds(2)) _restartPending = false;
                    }
                    flush = (snapshot.Status is PlaybackStatus.Paused or PlaybackStatus.Ended) && snapshot.Status != _lastStatus;
                    _lastStatus = snapshot.Status;
                    PublishLocked(snapshot);
                }
                if (flush) _saveRequests.Writer.TryWrite(true);
            }
        }
        catch (OperationCanceledException) when (_lifetimeToken.IsCancellationRequested) { }
        catch (Exception error) { RecordFailure("读取播放器状态", error); }
    }

    private async Task TickAsync()
    {
        using var timer = new PeriodicTimer(SaveInterval);
        try
        {
            while (await timer.WaitForNextTickAsync(_lifetimeToken).ConfigureAwait(false))
                _saveRequests.Writer.TryWrite(true);
        }
        catch (OperationCanceledException) when (_lifetimeToken.IsCancellationRequested) { }
    }

    private async Task WriteLoopAsync()
    {
        try
        {
            await foreach (bool _ in _saveRequests.Reader.ReadAllAsync(_lifetimeToken).ConfigureAwait(false))
                await SaveConfirmedAsync(false, _lifetimeToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (_lifetimeToken.IsCancellationRequested) { }
    }

    private async Task SaveConfirmedAsync(bool final, CancellationToken cancellationToken)
    {
        bool acquired = false;
        try
        {
            await _mutations.WaitAsync(cancellationToken).ConfigureAwait(false);
            acquired = true;
            PlaybackSnapshot snapshot;
            lock (_gate)
            {
                snapshot = _inner.Snapshot;
                if (!Owns(snapshot) || (_closing && !final) || _restartPending || _persistenceDisabled ||
                    snapshot.Status == PlaybackStatus.Preparing || snapshot.ConfirmedPosition is not { } position ||
                    position < TimeSpan.FromSeconds(1) || snapshot.Duration < TimeSpan.Zero) return;
            }

            // A lost mutation reply is reconciled through a read before any
            // later observed position can be submitted. The old write is never replayed.
            if (_requiresReconciliation)
            {
                var bookmark = await _store.ReadAsync(_resource, cancellationToken).ConfigureAwait(false);
                lock (_gate)
                {
                    if ((_unknownClear && bookmark is null) || (bookmark is not null &&
                        bookmark.Position == _lastAttemptedPosition && bookmark.Duration == _lastAttemptedDuration))
                        _progressError = null;
                    _requiresReconciliation = false;
                    _unknownClear = false;
                    PublishLocked(_inner.Snapshot);
                }
            }
            var confirmedPosition = snapshot.ConfirmedPosition!.Value;
            lock (_gate)
            {
                if (_lastAttemptedPosition == confirmedPosition && _lastAttemptedDuration == snapshot.Duration) return;
                _lastAttemptedPosition = confirmedPosition;
                _lastAttemptedDuration = snapshot.Duration;
            }
            await _store.WriteAsync(_resource, confirmedPosition, snapshot.Duration, cancellationToken).ConfigureAwait(false);
            lock (_gate) { _progressError = null; PublishLocked(_inner.Snapshot); }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            if (final) RecordFailure("保存播放进度", new FilewayException(new ErrorInfo(ErrorCode.Timeout, "最终播放进度保存超时。")));
        }
        catch (Exception error) { RecordFailure("保存播放进度", error); }
        finally { if (acquired) _mutations.Release(); }
    }

    private void RecordFailure(string operation, Exception error)
    {
        ErrorCode code = error is FilewayException fileway ? fileway.Error.Code :
            error is OperationCanceledException ? ErrorCode.Canceled : ErrorCode.Unknown;
        lock (_gate)
        {
            if (code == ErrorCode.OutcomeUnknown) _requiresReconciliation = true;
            if (code is ErrorCode.SourceChanged or ErrorCode.AuthenticationExpired or ErrorCode.PermissionDenied or ErrorCode.CapabilityMissing)
                _persistenceDisabled = true;
            _progressError = new ErrorInfo(code, code == ErrorCode.OutcomeUnknown
                ? operation + "结果尚未确认，将先核对服务器记录。" : operation + "失败，视频可继续播放。");
            PublishLocked(_inner.Snapshot);
        }
        Trace.TraceWarning("Fileway playback progress operation failed: {0} ({1}).", operation, code);
    }

    public async IAsyncEnumerable<PlaybackSnapshot> WatchAsync([EnumeratorCancellation] CancellationToken cancellationToken)
    {
        var channel = Channel.CreateBounded<PlaybackSnapshot>(new BoundedChannelOptions(1) { FullMode = BoundedChannelFullMode.DropOldest });
        lock (_gate)
        {
            _watchers.Add(channel);
            channel.Writer.TryWrite(Decorate(_inner.Snapshot));
            if (_stop?.IsCompleted == true) channel.Writer.TryComplete();
        }
        try { await foreach (var snapshot in channel.Reader.ReadAllAsync(cancellationToken).ConfigureAwait(false)) yield return snapshot; }
        finally { lock (_gate) _watchers.Remove(channel); }
    }

    public async Task SetPausedAsync(bool paused, CancellationToken cancellationToken)
    {
        await _inner.SetPausedAsync(paused, cancellationToken).ConfigureAwait(false);
        if (paused) _saveRequests.Writer.TryWrite(true);
    }

    public async Task SeekAsync(TimeSpan position, CancellationToken cancellationToken)
    {
        await _inner.SeekAsync(position, cancellationToken).ConfigureAwait(false);
        lock (_gate) { _resumePrompt = null; PublishLocked(_inner.Snapshot); }
    }

    public Task SetVolumeAsync(double volume, CancellationToken cancellationToken) => _inner.SetVolumeAsync(volume, cancellationToken);
    public Task SetMutedAsync(bool muted, CancellationToken cancellationToken) => _inner.SetMutedAsync(muted, cancellationToken);
    public Task SetRateAsync(double rate, CancellationToken cancellationToken) => _inner.SetRateAsync(rate, cancellationToken);
    public Task SelectTrackAsync(MediaTrackKind kind, long? trackId, CancellationToken cancellationToken) => _inner.SelectTrackAsync(kind, trackId, cancellationToken);
    public Task SetSubtitlesAsync(SubtitlePreferences preferences, CancellationToken cancellationToken) => _inner.SetSubtitlesAsync(preferences, cancellationToken);
    public Task SetSubtitleSafeAreaAsync(double bottomInsetFraction, CancellationToken cancellationToken) => _inner.SetSubtitleSafeAreaAsync(bottomInsetFraction, cancellationToken);
    public Task AddSubtitleAsync(PlaybackInput subtitle, CancellationToken cancellationToken) => _inner.AddSubtitleAsync(subtitle, cancellationToken);

    public async Task RestartFromBeginningAsync(CancellationToken cancellationToken)
    {
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, _lifetimeToken);
        await _mutations.WaitAsync(linked.Token).ConfigureAwait(false);
        try
        {
            lock (_gate)
            {
                if (_closing) throw new FilewayException(new ErrorInfo(ErrorCode.Canceled, "播放会话已关闭。"));
                // This barrier blocks queued saves of the pre-restart position
                // until the engine has actually confirmed the seek to zero.
                _restartPending = true;
                _resumePrompt = null;
                PublishLocked(_inner.Snapshot);
            }
            // Respond to the local playback action before waiting on the
            // network. The mutation gate still orders clear before new saves.
            try { await _inner.SeekAsync(TimeSpan.Zero, linked.Token).ConfigureAwait(false); }
            catch
            {
                lock (_gate) _restartPending = false;
                throw;
            }
            try
            {
                bool alreadyCleared = false;
                if (_requiresReconciliation)
                {
                    var bookmark = await _store.ReadAsync(_resource, linked.Token).ConfigureAwait(false);
                    lock (_gate)
                    {
                        alreadyCleared = _unknownClear && bookmark is null;
                        _requiresReconciliation = false;
                        _unknownClear = false;
                    }
                }
                if (!alreadyCleared) await _store.ClearAsync(_resource, linked.Token).ConfigureAwait(false);
                lock (_gate) { _lastAttemptedPosition = null; _lastAttemptedDuration = null; _progressError = null; }
            }
            catch (OperationCanceledException) when (linked.IsCancellationRequested) { throw; }
            catch (Exception error)
            {
                if (error is FilewayException { Error.Code: ErrorCode.OutcomeUnknown })
                    lock (_gate) _unknownClear = true;
                RecordFailure("清除播放进度", error);
            }
            lock (_gate) PublishLocked(_inner.Snapshot);
        }
        finally { _mutations.Release(); }
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        Task stop;
        lock (_gate)
        {
            if (_stop is null)
            {
                _closing = true;
                _stop = StopCoreAsync();
            }
            stop = _stop;
        }
        return stop.WaitAsync(cancellationToken);
    }

    private async Task StopCoreAsync()
    {
        try
        {
            _saveRequests.Writer.TryComplete();
            await _lifetime.CancelAsync().ConfigureAwait(false);
            await Task.WhenAll(_observer, _timer, _writer).ConfigureAwait(false);
            using var deadline = new CancellationTokenSource(FinalSaveDeadline);
            await SaveConfirmedAsync(true, deadline.Token).ConfigureAwait(false);
        }
        finally
        {
            try { await _inner.StopAsync(CancellationToken.None).ConfigureAwait(false); }
            finally
            {
                lock (_gate)
                {
                    PublishLocked(_inner.Snapshot);
                    foreach (var watcher in _watchers) watcher.Writer.TryComplete();
                }
                _lifetime.Dispose();
            }
        }
    }

    public ValueTask DisposeAsync() => new(StopAsync(CancellationToken.None));
}
