using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Playback;

/// <summary>Joins command acceptance and actual playback readiness, in either arrival order.</summary>
internal sealed class RecentPlaybackOpenRecording : IDisposable
{
    private readonly object _gate = new();
    private readonly Guid _sessionId;
    private readonly long _generation;
    private readonly RecentOpenRecording _recording;
    private readonly CancellationTokenSource _lifetime;
    private bool _accepted, _ready, _stopped, _disposed;
    private Task _cancellation = Task.CompletedTask;
    internal Task Completion { get; private set; } = Task.CompletedTask;

    internal RecentPlaybackOpenRecording(IRecentAccessService service, RemoteResourceRef resource,
        Guid sessionId, long generation, CancellationToken opening, CancellationToken lifetime)
    {
        _sessionId = sessionId;
        _generation = generation;
        _recording = new RecentOpenRecording(service, resource, generation);
        _lifetime = CancellationTokenSource.CreateLinkedTokenSource(opening, lifetime);
    }

    internal void Accepted()
    {
        lock (_gate) { if (_stopped) return; _accepted = true; ConfirmLocked(); }
    }

    internal void Observe(PlaybackSnapshot snapshot)
    {
        lock (_gate)
        {
            if (_stopped || snapshot.SessionId != _sessionId || snapshot.Generation != _generation) return;
            if (snapshot.Status is PlaybackStatus.Failed or PlaybackStatus.Closing or PlaybackStatus.Stopped or PlaybackStatus.Ended)
            { StopLocked(); return; }
            // ConfirmedPosition is populated by PLAYBACK_RESTART, never just by loadfile acceptance.
            _ready |= snapshot.ConfirmedPosition is not null && snapshot.Status is
                PlaybackStatus.Playing or PlaybackStatus.Paused or PlaybackStatus.Buffering;
            ConfirmLocked();
        }
    }

    private void ConfirmLocked()
    {
        if (_accepted && _ready && !_lifetime.IsCancellationRequested)
            Completion = _recording.ConfirmOpenedAsync(_generation, _lifetime.Token);
    }

    private void StopLocked()
    {
        _stopped = true;
        _cancellation = CancelAsync();
    }

    private async Task CancelAsync()
    {
        try { await _lifetime.CancelAsync().ConfigureAwait(false); }
        catch (AggregateException) { /* Provider callbacks cannot interrupt playback shutdown. */ }
    }

    public void Dispose()
    {
        lock (_gate)
        {
            if (_disposed) return;
            _disposed = true;
            if (!_stopped) StopLocked();
        }
        // Dispose only after the bounded attempt has stopped linking/using the lifetime token.
        _ = Task.WhenAll(Completion, _cancellation).ContinueWith(_ => _lifetime.Dispose(), CancellationToken.None,
            TaskContinuationOptions.ExecuteSynchronously, TaskScheduler.Default);
    }
}
