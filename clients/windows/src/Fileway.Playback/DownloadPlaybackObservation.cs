using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Playback;

/// <summary>
/// A bounded presentation subscription, established before admission to retain the
/// latest task event across that await. It never owns or controls the download.
/// </summary>
internal sealed class DownloadPlaybackObservation : IAsyncDisposable
{
    private static readonly TimeSpan Interval = TimeSpan.FromMilliseconds(250);
    private readonly object _gate = new();
    private readonly IDownloadPlaybackSourceProvider _provider;
    private readonly DownloadPlaybackInput _input;
    private readonly CancellationTokenSource _stop = new();
    private DownloadPlaybackSource? _source;
    private DownloadItem? _latest;
    private PlaybackDownloadState? _snapshot;
    private Task? _pump, _disposal;
    private bool _stopped;

    internal DownloadPlaybackObservation(IDownloadPlaybackSourceProvider provider, DownloadPlaybackInput input)
    {
        _provider = provider;
        _input = input;
        _provider.TaskChanged += Changed;
    }

    internal PlaybackDownloadState Snapshot
    {
        get { lock (_gate) return _snapshot ?? throw new InvalidOperationException("A download must be admitted first."); }
    }

    internal void Admit(DownloadPlaybackSource source)
    {
        var item = source.Item;
        var run = source.Reader.Run;
        if (!MatchesInput(item) || item.Source.Account != _input.Context.Account ||
            run.Task != _input.Task || run.Id == Guid.Empty || run.Generation <= 0 ||
            item.Snapshot.Generation != run.Generation || item.Snapshot.Revision < 0 || item.PresentationRevision < 0 ||
            item.Snapshot.TotalBytes != source.Reader.TotalBytes || source.Reader.TotalBytes < 0 ||
            item.Progress is { } progress && progress.Run != run)
            throw new FilewayException(new ErrorInfo(ErrorCode.ProtocolMismatch, "下载播放来源与当前任务不一致。"));
        lock (_gate)
        {
            ObjectDisposedException.ThrowIf(_stopped, this);
            if (_source is not null) throw new InvalidOperationException("A download can only be admitted once.");
            var pending = _latest;
            _source = source;
            _latest = item;
            if (pending is not null) AcceptLocked(pending);
            _snapshot = ReadState(_latest, source.Reader);
        }
    }

    internal void Start(Action<PlaybackDownloadState> publish)
    {
        ArgumentNullException.ThrowIfNull(publish);
        lock (_gate)
        {
            ObjectDisposedException.ThrowIf(_stopped, this);
            if (_source is null) throw new InvalidOperationException("A download must be admitted first.");
            _pump ??= Task.Run(() => ObserveAsync(publish));
        }
    }

    private bool MatchesInput(DownloadItem item) => item.Context == _input.Context &&
        item.Snapshot.Task == _input.Task && item.Context.Account == _input.Task.Account;

    private void Changed(object? sender, DownloadItem item)
    {
        lock (_gate)
        {
            if (_stopped || !MatchesInput(item)) return;
            if (_source is null)
            {
                // One retained item, even if a provider sends unbounded events
                // while native setup or source admission is waiting.
                if (_latest is null || item.Snapshot.Generation > _latest.Snapshot.Generation ||
                    item.Snapshot.Generation == _latest.Snapshot.Generation && IsNewer(item, _latest))
                    _latest = item;
            }
            else AcceptLocked(item);
        }
    }

    private void AcceptLocked(DownloadItem item)
    {
        var source = _source!;
        if (item.Source != source.Item.Source || item.Snapshot.Generation != source.Reader.Run.Generation ||
            item.Snapshot.TotalBytes != source.Reader.TotalBytes ||
            item.Progress is { } progress && progress.Run != source.Reader.Run ||
            !IsNewer(item, _latest!)) return;
        // The durable generation identifies one execution of this exact task.
        // Terminal snapshots can legitimately omit the progress/run payload.
        _latest = item;
    }

    private static bool IsNewer(DownloadItem next, DownloadItem previous)
    {
        if (next.PresentationRevision < 0 || next.Snapshot.Revision < previous.Snapshot.Revision) return false;
        // Application presentation sequence orders transient Canceling/failure
        // evidence that can change without a new durable task revision.
        if (next.PresentationRevision > 0 && previous.PresentationRevision > 0)
            return next.PresentationRevision > previous.PresentationRevision;
        if (next.Snapshot.Revision != previous.Snapshot.Revision)
            return next.Snapshot.Revision > previous.Snapshot.Revision;
        // Progress can advance between durable revisions. Never allow an old
        // same-revision progress event to replace newer or terminal evidence.
        return next.Snapshot == previous.Snapshot && next.Status == previous.Status &&
            next.Progress is { } progress && (previous.Progress is not { } old ||
                progress.Elapsed >= old.Elapsed && progress.ReceivedBytes >= old.ReceivedBytes &&
                (progress.Elapsed > old.Elapsed || progress.ReceivedBytes > old.ReceivedBytes));
    }

    private async Task ObserveAsync(Action<PlaybackDownloadState> publish)
    {
        using var timer = new PeriodicTimer(Interval);
        try
        {
            do
            {
                DownloadItem item;
                IDownloadReadLease reader;
                lock (_gate)
                {
                    if (_stopped) return;
                    item = _latest!;
                    reader = _source!.Reader;
                }
                var snapshot = ReadState(item, reader);
                lock (_gate)
                {
                    if (_stopped) return;
                    if (_snapshot == snapshot) continue;
                    _snapshot = snapshot;
                }
                publish(snapshot);
            } while (await timer.WaitForNextTickAsync(_stop.Token).ConfigureAwait(false));
        }
        catch (OperationCanceledException) when (_stop.IsCancellationRequested) { }
    }

    private static PlaybackDownloadState ReadState(DownloadItem item, IDownloadReadLease reader)
    {
        long? prefix = null;
        try
        {
            long observed = reader.ReadablePrefixBytes;
            if (observed >= 0 && observed <= reader.TotalBytes) prefix = observed;
        }
        catch (Exception) { /* Revocation or unavailable evidence must not invent a readable prefix. */ }
        return new(item, prefix);
    }

    internal void Stop()
    {
        lock (_gate)
        {
            if (_stopped) return;
            _stopped = true;
            _provider.TaskChanged -= Changed;
            _ = _stop.CancelAsync();
        }
    }

    public ValueTask DisposeAsync()
    {
        Stop();
        lock (_gate) return new(_disposal ??= DisposeCoreAsync());
    }

    private async Task DisposeCoreAsync()
    {
        if (_pump is not null) await _pump.ConfigureAwait(false);
        _stop.Dispose();
    }
}
