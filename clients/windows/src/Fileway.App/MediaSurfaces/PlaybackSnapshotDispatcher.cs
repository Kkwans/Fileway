using Fileway.Core.Contracts;

namespace Fileway.App.MediaSurfaces;

/// <summary>One replaceable snapshot and one queued UI callback per playback surface.</summary>
internal sealed class PlaybackSnapshotDispatcher(
    Guid sessionId,
    long generation,
    Func<Action, bool> enqueue,
    Action<PlaybackSnapshot> apply)
{
    private readonly object _gate = new();
    private PlaybackSnapshot? _pending;
    private bool _callbackPending;
    private bool _closing;
    private bool _stopped;

    internal bool Owns(PlaybackSnapshot snapshot) => snapshot.SessionId == sessionId && snapshot.Generation == generation;

    internal static bool IsLifecycleSnapshot(PlaybackSnapshot snapshot) =>
        snapshot.Status is PlaybackStatus.Closing or PlaybackStatus.Stopped or PlaybackStatus.Failed || snapshot.Error is not null;

    internal void Publish(PlaybackSnapshot snapshot)
    {
        if (!Owns(snapshot)) return;
        lock (_gate)
        {
            if (_stopped || (_closing && !IsLifecycleSnapshot(snapshot))) return;
            if (snapshot.Status is PlaybackStatus.Closing or PlaybackStatus.Stopped) _closing = true;
            // Property updates must not erase an unrendered error/close, and a
            // stopped snapshot must still reach the owner's close path.
            if (_pending is { Status: PlaybackStatus.Stopped } && snapshot.Status != PlaybackStatus.Stopped) return;
            if (_pending is { } pending && IsLifecycleSnapshot(pending) && !IsLifecycleSnapshot(snapshot)) return;
            _pending = snapshot;
            if (_callbackPending) return;
            _callbackPending = true;
        }
        EnqueuePending();
    }

    internal void BeginClosing()
    {
        lock (_gate)
        {
            _closing = true;
            if (_pending is { } pending && !IsLifecycleSnapshot(pending)) _pending = null;
        }
    }

    internal void Stop()
    {
        lock (_gate)
        {
            _stopped = true;
            _pending = null;
        }
    }

    private void EnqueuePending()
    {
        if (!enqueue(DispatchPending)) Stop();
    }

    private void DispatchPending()
    {
        PlaybackSnapshot? snapshot;
        lock (_gate)
        {
            snapshot = _stopped ? null : _pending;
            _pending = null;
        }
        try
        {
            if (snapshot is not null) apply(snapshot);
        }
        finally
        {
            bool enqueueNext;
            lock (_gate)
            {
                enqueueNext = !_stopped && _pending is not null;
                if (!enqueueNext) _callbackPending = false;
            }
            // Render one snapshot per callback. Yield to other UI work rather
            // than draining an event stream that may never become empty.
            if (enqueueNext) EnqueuePending();
        }
    }
}
