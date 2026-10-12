using System.Text.Json;

namespace Fileway.App.MediaSurfaces;

// Numeric, application-owned events only. Never pass media metadata, input text,
// exception messages, native handles or account/resource objects to this recorder.
internal enum PlaybackDiagnosticEvent
{
    CaptureStarted,
    PointerPressed,
    FullscreenInput,
    SettingsInput,
    SettingsPresentBegin,
    NativeFullscreenInput,
    FullscreenUiEntered,
    FullscreenPresenterBegin,
    FullscreenPresenterEnd,
    SettingsCreateBegin,
    SettingsCreateEnd,
    SettingsMeasureBegin,
    SettingsMeasureEnd,
    SettingsShowBegin,
    SettingsShowEnd,
    SettingsLayoutBegin,
    SettingsLayoutEnd,
    SettingsActivateBegin,
    SettingsActivateEnd,
    HitTestDuration,
    SnapshotApplyDuration,
    BoundsDuration,
    OverlayActivation,
    SettingsActivation,
    OverlayVisibility,
    UiProbeQueued,
    UiProbeCompleted,
    UiProbeRejected,
    UiProbePending,
}

internal sealed record PlaybackDiagnosticEntry(long Sequence, long ElapsedMicroseconds,
    PlaybackDiagnosticEvent Event, long Value);

internal sealed record PlaybackDiagnosticReport(int Format, long EventsRecorded, long ContendedWrites,
    long HitTests, long SnapshotApplications, bool ProbePending, long ProbeAgeMicroseconds,
    PlaybackDiagnosticEntry[] Entries);

/// <summary>Opt-in, bounded local evidence. Producers never wait or perform I/O.</summary>
internal sealed class PlaybackResponsivenessDiagnostics : IDisposable
{
    internal const string EnvironmentVariable = "FILEWAY_PLAYER_RESPONSIVENESS_DIAGNOSTICS";
    internal const int Capacity = 512;
    internal const int MaximumFileBytes = 256 * 1024;
    private static int _activeCapture;
    private readonly PlaybackDiagnosticEntry?[] _ring;
    private readonly long[] _lastSample = new long[Enum.GetValues<PlaybackDiagnosticEvent>().Length];
    private readonly Func<Action, bool> _enqueue;
    private readonly Func<PlaybackDiagnosticReport, CancellationToken, Task> _persist;
    private readonly Func<TimeSpan, CancellationToken, Task> _delay;
    private readonly TimeProvider _clock;
    private readonly TimeSpan _interval;
    private readonly TimeSpan _duration;
    private readonly Action? _onStopped;
    private readonly CancellationTokenSource _stop = new();
    private readonly long _started;
    private readonly Task _worker;
    private long _sequence, _contendedWrites, _hitTests, _snapshotApplications, _probeSequence;
    private Probe? _pendingProbe;
    private int _stopped, _dispatchRejected;

    private sealed record Probe(long Sequence, long Started);

    internal static PlaybackResponsivenessDiagnostics? TryCreate(Func<Action, bool> enqueue,
        Func<string, string?>? environment = null)
    {
        environment ??= Environment.GetEnvironmentVariable;
        var admitted = false;
        try
        {
            if (environment(EnvironmentVariable) != "1") return null;
            // One capture per process. All players together retain one report and
            // one temporary file, rather than unbounded per-session log files.
            if (Interlocked.CompareExchange(ref _activeCapture, 1, 0) != 0) return null;
            admitted = true;
            var root = environment("FILEWAY_WINDOWS_DATA_ROOT")
                ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Fileway", "Preview");
            var directory = Path.Combine(root, "diagnostics");
            return new PlaybackResponsivenessDiagnostics(enqueue,
                (report, cancellation) => PersistAsync(directory, report, cancellation),
                onStopped: () => Volatile.Write(ref _activeCapture, 0));
        }
        catch (Exception)
        {
            if (admitted) Volatile.Write(ref _activeCapture, 0);
            return null;
        }
    }

    // Injection keeps scheduling, shutdown and overflow tests independent of WinUI.
    internal PlaybackResponsivenessDiagnostics(Func<Action, bool> enqueue,
        Func<PlaybackDiagnosticReport, CancellationToken, Task> persist,
        TimeProvider? clock = null, Func<TimeSpan, CancellationToken, Task>? delay = null,
        int capacity = Capacity, TimeSpan? interval = null, TimeSpan? duration = null, Action? onStopped = null)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(capacity, 1);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(capacity, Capacity);
        _ring = new PlaybackDiagnosticEntry[capacity];
        _enqueue = enqueue;
        _persist = persist;
        _clock = clock ?? TimeProvider.System;
        _delay = delay ?? ((period, cancellation) => Task.Delay(period, _clock, cancellation));
        _interval = interval ?? TimeSpan.FromSeconds(1);
        _duration = duration ?? TimeSpan.FromMinutes(2);
        ArgumentOutOfRangeException.ThrowIfLessThanOrEqual(_interval, TimeSpan.Zero);
        ArgumentOutOfRangeException.ThrowIfLessThanOrEqual(_duration, TimeSpan.Zero);
        _onStopped = onStopped;
        _started = _clock.GetTimestamp();
        Record(PlaybackDiagnosticEvent.CaptureStarted);
        _worker = Task.Run(RunAsync);
    }

    internal Task Completion => _worker;
    internal long Timestamp => _clock.GetTimestamp();

    internal void Record(PlaybackDiagnosticEvent kind, long value = 0)
    {
        if (Volatile.Read(ref _stopped) != 0) return;
        var sequence = Interlocked.Increment(ref _sequence);
        var entry = new PlaybackDiagnosticEntry(sequence, Microseconds(_started, _clock.GetTimestamp()), kind, value);
        ref var slot = ref _ring[(sequence - 1) % _ring.Length];
        var previous = Volatile.Read(ref slot);
        // One CAS, no lock or spin. A delayed producer cannot overwrite a newer
        // entry in the same slot; contention loses evidence instead of delaying UI.
        if ((previous is not null && previous.Sequence >= sequence)
            || !ReferenceEquals(Interlocked.CompareExchange(ref slot, entry, previous), previous))
            Interlocked.Increment(ref _contendedWrites);
    }

    internal void RecordDuration(PlaybackDiagnosticEvent kind, long started)
    {
        if (Volatile.Read(ref _stopped) != 0) return;
        if (kind == PlaybackDiagnosticEvent.HitTestDuration) Interlocked.Increment(ref _hitTests);
        if (kind == PlaybackDiagnosticEvent.SnapshotApplyDuration) Interlocked.Increment(ref _snapshotApplications);
        var now = _clock.GetTimestamp();
        var elapsed = Microseconds(started, now);
        ref var last = ref _lastSample[(int)kind];
        var previous = Volatile.Read(ref last);
        // Keep ordinary hot-path samples to one per second; retain slow calls.
        if (elapsed < 50_000 && previous != 0 && Microseconds(previous, now) < 1_000_000) return;
        if (Interlocked.CompareExchange(ref last, now, previous) == previous) Record(kind, elapsed);
    }

    internal PlaybackDiagnosticReport Capture()
    {
        var last = Interlocked.Read(ref _sequence);
        var first = Math.Max(1, last - _ring.Length + 1);
        var entries = new List<PlaybackDiagnosticEntry>(_ring.Length);
        foreach (ref var slot in _ring.AsSpan())
        {
            var entry = Volatile.Read(ref slot);
            if (entry is not null && entry.Sequence >= first && entry.Sequence <= last) entries.Add(entry);
        }
        entries.Sort(static (left, right) => left.Sequence.CompareTo(right.Sequence));
        var pending = Volatile.Read(ref _pendingProbe);
        return new PlaybackDiagnosticReport(1, last, Interlocked.Read(ref _contendedWrites),
            Interlocked.Read(ref _hitTests), Interlocked.Read(ref _snapshotApplications), pending is not null,
            pending is null ? 0 : Microseconds(pending.Started, _clock.GetTimestamp()), entries.ToArray());
    }

    internal void ProbeUiQueue()
    {
        if (Volatile.Read(ref _stopped) != 0) return;
        var pending = Volatile.Read(ref _pendingProbe);
        if (pending is not null)
        {
            Record(PlaybackDiagnosticEvent.UiProbePending, Microseconds(pending.Started, _clock.GetTimestamp()));
            return;
        }
        var probe = new Probe(Interlocked.Increment(ref _probeSequence), _clock.GetTimestamp());
        if (Interlocked.CompareExchange(ref _pendingProbe, probe, null) is not null) return;
        Record(PlaybackDiagnosticEvent.UiProbeQueued, probe.Sequence);
        try
        {
            if (_enqueue(() =>
            {
                if (ReferenceEquals(Interlocked.CompareExchange(ref _pendingProbe, null, probe), probe))
                    Record(PlaybackDiagnosticEvent.UiProbeCompleted, Microseconds(probe.Started, _clock.GetTimestamp()));
            })) return;
        }
        catch (Exception) { /* Diagnostics never change the host's behavior. */ }
        Interlocked.CompareExchange(ref _pendingProbe, null, probe);
        Record(PlaybackDiagnosticEvent.UiProbeRejected, probe.Sequence);
        Volatile.Write(ref _dispatchRejected, 1);
    }

    private async Task RunAsync()
    {
        try
        {
            while (Volatile.Read(ref _stopped) == 0 && _clock.GetElapsedTime(_started) < _duration)
            {
                ProbeUiQueue();
                await _persist(Capture(), _stop.Token).ConfigureAwait(false);
                if (Volatile.Read(ref _dispatchRejected) != 0) break;
                await _delay(_interval, _stop.Token).ConfigureAwait(false);
            }
        }
        catch (Exception) { /* Cancellation and unavailable local storage are nonfatal. */ }
        finally
        {
            Interlocked.Exchange(ref _stopped, 1);
            Interlocked.Exchange(ref _pendingProbe, null);
            await CancelNoThrowAsync().ConfigureAwait(false);
            _stop.Dispose();
            try { _onStopped?.Invoke(); }
            catch (Exception) { /* Cleanup diagnostics must not fault an unobserved worker. */ }
        }
    }

    private long Microseconds(long start, long end) => _clock.GetElapsedTime(start, end).Ticks / 10;

    internal static async Task PersistAsync(string directory, PlaybackDiagnosticReport report, CancellationToken cancellation)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(report);
        if (bytes.Length > MaximumFileBytes) return;
        cancellation.ThrowIfCancellationRequested();
        Directory.CreateDirectory(directory);
        var temporary = Path.Combine(directory, "player-responsiveness.tmp");
        await File.WriteAllBytesAsync(temporary, bytes, cancellation).ConfigureAwait(false);
        cancellation.ThrowIfCancellationRequested();
        File.Move(temporary, Path.Combine(directory, "player-responsiveness.json"), overwrite: true);
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _stopped, 1) != 0) return;
        // Callback cancellation runs away from the UI. Completion is observed
        // by tests, never synchronously waited on by the playback surface.
        _ = CancelNoThrowAsync();
    }

    private async Task CancelNoThrowAsync()
    {
        try { await _stop.CancelAsync().ConfigureAwait(false); }
        catch (Exception) { /* Includes a concurrent natural-stop/disposed-source race. */ }
    }
}
