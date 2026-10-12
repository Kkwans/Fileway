using Fileway.App.MediaSurfaces;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Tests.Playback;

public sealed class PlaybackSnapshotDispatcherTests
{
    [Fact]
    public void SnapshotBurstQueuesOneCallbackAndRendersOnlyTheLatestValue()
    {
        var origin = Snapshot();
        var queue = new UiQueue();
        var rendered = new List<PlaybackSnapshot>();
        var dispatcher = Create(origin, queue, rendered.Add);
        for (var index = 0; index < 10_000; index++)
            dispatcher.Publish(origin with { Position = TimeSpan.FromMilliseconds(index) });

        Assert.Equal(1, queue.Count);
        queue.RunNext();
        Assert.Equal(TimeSpan.FromMilliseconds(9_999), Assert.Single(rendered).Position);
        Assert.Equal(0, queue.Count);

        dispatcher.Publish(origin with { Volume = 37 });
        queue.RunNext();
        Assert.Equal(37, rendered[^1].Volume);
        Assert.Equal(1, queue.MaximumPending);
    }

    [Fact]
    public void UpdatesDuringRenderingGetOneSuccessorAndYieldToOtherUiWork()
    {
        var origin = Snapshot();
        var queue = new UiQueue();
        var rendered = new List<PlaybackSnapshot>();
        PlaybackSnapshotDispatcher? dispatcher = null;
        dispatcher = Create(origin, queue, snapshot =>
        {
            rendered.Add(snapshot);
            if (rendered.Count != 1) return;
            for (var index = 1; index <= 100; index++)
                dispatcher!.Publish(origin with { Position = TimeSpan.FromSeconds(index) });
        });
        dispatcher.Publish(origin);
        var inputHandled = false;
        queue.EnqueueLow(() => inputHandled = true);

        queue.RunNext();
        Assert.Single(rendered);
        Assert.False(inputHandled);
        queue.RunNext();
        Assert.True(inputHandled);
        Assert.Single(rendered);
        queue.RunNext();
        Assert.Equal(TimeSpan.FromSeconds(100), rendered[^1].Position);
        Assert.Equal(2, rendered.Count);
        Assert.Equal(0, queue.Count);
    }

    [Fact]
    public void NormalInputRunsAheadOfQueuedSnapshotAndDeferredSettingsStillGetATurn()
    {
        var origin = Snapshot();
        var queue = new UiQueue();
        var rendered = new List<PlaybackSnapshot>();
        PlaybackSnapshotDispatcher? dispatcher = null;
        dispatcher = Create(origin, queue, snapshot =>
        {
            rendered.Add(snapshot);
            dispatcher!.Publish(origin with { Position = TimeSpan.FromSeconds(rendered.Count) });
        });
        dispatcher.Publish(origin);
        var inputHandled = false;
        var settingsOpened = false;
        queue.EnqueueNormal(() => inputHandled = true);
        queue.EnqueueLow(() => settingsOpened = true);

        queue.RunNext();
        Assert.True(inputHandled);
        Assert.Empty(rendered);
        queue.RunNext();
        Assert.Single(rendered);
        Assert.False(settingsOpened);
        queue.RunNext();
        Assert.True(settingsOpened);
        Assert.Single(rendered);
        dispatcher.Stop();
        queue.RunNext();
        Assert.Equal(0, queue.Count);
    }

    [Fact]
    public void ConcurrentPublishersCannotBuildAUiCallbackBacklog()
    {
        var origin = Snapshot();
        var queue = new UiQueue();
        var rendered = new List<PlaybackSnapshot>();
        var dispatcher = Create(origin, queue, rendered.Add);
        Parallel.For(0, 2_000, index => dispatcher.Publish(origin with { Volume = index % 101 }));
        var latest = origin with { Volume = 19, IsMuted = true };
        dispatcher.Publish(latest);

        Assert.Equal(1, queue.Count);
        queue.RunNext();
        Assert.Same(latest, Assert.Single(rendered));
        Assert.Equal(1, queue.MaximumPending);
    }

    [Fact]
    public void OtherSessionsAndGenerationsCannotReplaceTheCurrentSnapshot()
    {
        var origin = Snapshot();
        var queue = new UiQueue();
        var rendered = new List<PlaybackSnapshot>();
        var dispatcher = Create(origin, queue, rendered.Add);
        dispatcher.Publish(origin);
        dispatcher.Publish(origin with { SessionId = Guid.NewGuid(), Status = PlaybackStatus.Stopped });
        dispatcher.Publish(origin with { Generation = origin.Generation + 1, Status = PlaybackStatus.Closing });

        queue.RunNext();
        Assert.Same(origin, Assert.Single(rendered));
        Assert.False(dispatcher.Owns(origin with { SessionId = Guid.NewGuid() }));
        Assert.False(dispatcher.Owns(origin with { Generation = 99 }));
    }

    [Fact]
    public void ClosingDiscardsQueuedPlaybackAndStillRendersLifecycleUpdates()
    {
        var origin = Snapshot();
        var queue = new UiQueue();
        var rendered = new List<PlaybackSnapshot>();
        var dispatcher = Create(origin, queue, rendered.Add);
        dispatcher.Publish(origin);
        dispatcher.BeginClosing();
        dispatcher.Publish(origin with { Status = PlaybackStatus.Paused });
        queue.RunNext();
        Assert.Empty(rendered);
        Assert.Equal(0, queue.Count);

        var closing = origin with { Status = PlaybackStatus.Closing };
        dispatcher.Publish(closing);
        dispatcher.Publish(origin);
        queue.RunNext();
        Assert.Same(closing, Assert.Single(rendered));
        var stopped = origin with { Status = PlaybackStatus.Stopped };
        dispatcher.Publish(stopped);
        queue.RunNext();
        Assert.Same(stopped, rendered[^1]);
    }

    [Fact]
    public void ObservedClosingAlsoFencesLatePlaybackUpdates()
    {
        var origin = Snapshot();
        var queue = new UiQueue();
        var rendered = new List<PlaybackSnapshot>();
        var dispatcher = Create(origin, queue, rendered.Add);
        var closing = origin with { Status = PlaybackStatus.Closing };
        dispatcher.Publish(closing);
        queue.RunNext();
        dispatcher.Publish(origin);

        Assert.Same(closing, Assert.Single(rendered));
        Assert.Equal(0, queue.Count);
    }

    [Theory]
    [InlineData(PlaybackStatus.Failed)]
    [InlineData(PlaybackStatus.Closing)]
    [InlineData(PlaybackStatus.Stopped)]
    public void PendingLifecycleStateCannotBeErasedByRoutineUpdates(PlaybackStatus status)
    {
        var origin = Snapshot();
        var queue = new UiQueue();
        var rendered = new List<PlaybackSnapshot>();
        var dispatcher = Create(origin, queue, rendered.Add);
        var lifecycle = origin with { Status = status };
        dispatcher.Publish(lifecycle);
        dispatcher.Publish(origin with { Position = TimeSpan.FromSeconds(50) });

        queue.RunNext();
        Assert.Same(lifecycle, Assert.Single(rendered));
    }

    [Fact]
    public void PendingErrorSurvivesRoutineUpdatesEvenWithoutFailedStatus()
    {
        var origin = Snapshot();
        var queue = new UiQueue();
        var rendered = new List<PlaybackSnapshot>();
        var dispatcher = Create(origin, queue, rendered.Add);
        var failure = origin with { Error = new ErrorInfo(ErrorCode.Unknown, "fixture error") };
        dispatcher.Publish(failure);
        dispatcher.Publish(origin);

        queue.RunNext();
        Assert.Same(failure, Assert.Single(rendered));
    }

    [Fact]
    public void StoppedSurvivesAllLaterLifecycleUpdatesUntilRendered()
    {
        var origin = Snapshot();
        var queue = new UiQueue();
        var rendered = new List<PlaybackSnapshot>();
        var dispatcher = Create(origin, queue, rendered.Add);
        var stopped = origin with { Status = PlaybackStatus.Stopped };
        dispatcher.Publish(stopped);
        dispatcher.Publish(origin with { Status = PlaybackStatus.Closing });
        dispatcher.Publish(origin with { Status = PlaybackStatus.Failed });

        queue.RunNext();
        Assert.Same(stopped, Assert.Single(rendered));
    }

    [Fact]
    public void CloseFailureReplacesPreparingToCloseState()
    {
        var origin = Snapshot();
        var queue = new UiQueue();
        var rendered = new List<PlaybackSnapshot>();
        var dispatcher = Create(origin, queue, rendered.Add);
        dispatcher.Publish(origin with { Status = PlaybackStatus.Closing });
        var failure = origin with { Status = PlaybackStatus.Failed, Error = new ErrorInfo(ErrorCode.Timeout, "fixture error") };
        dispatcher.Publish(failure);

        queue.RunNext();
        Assert.Same(failure, Assert.Single(rendered));
    }

    [Fact]
    public void EndedSessionCanReplayWithoutAClosingFence()
    {
        var origin = Snapshot();
        var queue = new UiQueue();
        var rendered = new List<PlaybackSnapshot>();
        var dispatcher = Create(origin, queue, rendered.Add);
        dispatcher.Publish(origin with { Status = PlaybackStatus.Ended });
        queue.RunNext();
        Assert.Equal(PlaybackStatus.Ended, Assert.Single(rendered).Status);
        dispatcher.Publish(origin with { Status = PlaybackStatus.Seeking });
        dispatcher.Publish(origin);
        queue.RunNext();
        Assert.Same(origin, rendered[^1]);
    }

    [Fact]
    public void DetachOrDisposalDropsPendingUpdatesAndPreventsFurtherDispatch()
    {
        var origin = Snapshot();
        var queue = new UiQueue();
        var rendered = new List<PlaybackSnapshot>();
        var dispatcher = Create(origin, queue, rendered.Add);
        dispatcher.Publish(origin);
        dispatcher.Stop();
        dispatcher.Publish(origin with { Status = PlaybackStatus.Stopped });
        queue.RunNext();

        Assert.Empty(rendered);
        Assert.Equal(0, queue.Count);
    }

    [Fact]
    public void StopDuringRenderingClearsTheSuccessor()
    {
        var origin = Snapshot();
        var queue = new UiQueue();
        PlaybackSnapshotDispatcher? dispatcher = null;
        var rendered = 0;
        dispatcher = Create(origin, queue, _ =>
        {
            rendered++;
            dispatcher!.Publish(origin with { Volume = 39 });
            dispatcher.Stop();
        });
        dispatcher.Publish(origin);
        queue.RunNext();

        Assert.Equal(1, rendered);
        Assert.Equal(0, queue.Count);
    }

    [Fact]
    public void ShutdownQueueRejectionDoesNotRetryForever()
    {
        var origin = Snapshot();
        var attempts = 0;
        var dispatcher = new PlaybackSnapshotDispatcher(origin.SessionId, origin.Generation,
            _ => { attempts++; return false; }, _ => Assert.Fail("Rejected queue cannot render."));
        dispatcher.Publish(origin);
        dispatcher.Publish(origin with { Volume = 31 });

        Assert.Equal(1, attempts);
    }

    private static PlaybackSnapshotDispatcher Create(PlaybackSnapshot origin, UiQueue queue, Action<PlaybackSnapshot> apply) =>
        new(origin.SessionId, origin.Generation, queue.EnqueueLow, apply);

    private static PlaybackSnapshot Snapshot() => new(Guid.NewGuid(), 7, "fixture.mp4", PlaybackStatus.Playing,
        TimeSpan.Zero, TimeSpan.Zero, TimeSpan.FromMinutes(1), true, [],
        new SubtitleCapabilities(true, true, true, true, true), new PlaybackDiagnostics());

    private sealed class UiQueue
    {
        private readonly object _gate = new();
        private readonly Queue<Action> _normal = new();
        private readonly Queue<Action> _pending = new();
        internal int MaximumPending { get; private set; }
        internal int Count { get { lock (_gate) return _normal.Count + _pending.Count; } }

        internal bool EnqueueLow(Action callback)
        {
            lock (_gate)
            {
                _pending.Enqueue(callback);
                MaximumPending = Math.Max(MaximumPending, _normal.Count + _pending.Count);
            }
            return true;
        }

        internal bool EnqueueNormal(Action callback)
        {
            lock (_gate)
            {
                _normal.Enqueue(callback);
                MaximumPending = Math.Max(MaximumPending, _normal.Count + _pending.Count);
            }
            return true;
        }

        internal void RunNext()
        {
            Action callback;
            lock (_gate) callback = _normal.Count > 0 ? _normal.Dequeue() : _pending.Dequeue();
            callback();
        }
    }
}
