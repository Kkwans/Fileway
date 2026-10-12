using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Transfers;

public sealed partial class UploadCoordinator
{
    private void Runner_ProgressChanged(object? sender, UploadTaskProgress progress)
    {
        Work? work;
        lock (_gate)
        {
            if (!_admission || _disposed || !_work.TryGetValue(progress.Run.Task, out work) || !work.Active ||
                work.Activation != _activation) return;
            work.PendingProgress = progress;
            if (work.ReadingProgress) return;
            work.ReadingProgress = true;
        }
        _ = ReadProgressAsync(work);
    }

    private async Task ReadProgressAsync(Work work)
    {
        while (true)
        {
            UploadTaskProgress? progress;
            lock (_gate)
            {
                progress = work.PendingProgress;
                work.PendingProgress = null;
                if (progress is null || !_admission || _disposed || !work.Active || work.Activation != _activation)
                { work.ReadingProgress = false; return; }
            }
            try
            {
                var record = await ReadAsync(work.Task, CancellationToken.None).ConfigureAwait(false);
                lock (_gate)
                {
                    if (!_admission || _disposed || !work.Active || work.Activation != _activation ||
                        !_work.TryGetValue(work.Task, out var current) || !ReferenceEquals(current, work))
                    { work.ReadingProgress = false; return; }
                }
                // A queued callback is not authority to display another generation's acknowledgement.
                if (record.Run == progress.Run && progress.SentBytes >= 0 && progress.TotalBytes >= 0 &&
                    (progress.ConfirmedOffset is null || progress.ConfirmedOffset >= 0 && progress.ConfirmedOffset <= progress.TotalBytes) &&
                    progress.Elapsed >= TimeSpan.Zero)
                    Publish(new(work.Activation, Snapshot(record), progress), work);
            }
            catch (FilewayException) { /* Presentation reads cannot fail or restart the byte executor. */ }
        }
    }

    private void Publish(UploadQueueUpdate update, Work? progressWork = null)
    {
        lock (_gate)
        {
            if (!_admission || _disposed || update.Activation != _activation) return;
            if (progressWork is not null && (!progressWork.Active ||
                !_work.TryGetValue(update.Task.Task, out var current) || !ReferenceEquals(current, progressWork))) return;
            if (_lastUpdates.TryGetValue(update.Task.Task, out var previous))
            {
                if (previous.Task.Revision > update.Task.Revision || previous.Task.Generation > update.Task.Generation ||
                    previous.Task.Phase is TransferPhase.Completed or TransferPhase.Canceled or TransferPhase.Skipped &&
                    update.Task.Phase != previous.Task.Phase) return;
                if (previous.Progress is { } oldProgress && update.Progress is { } progress && oldProgress.Run == progress.Run &&
                    (oldProgress.Elapsed > progress.Elapsed || oldProgress.SentBytes > progress.SentBytes)) return;
            }
            if (_work.TryGetValue(update.Task.Task, out var owner) && owner.Activation == update.Activation &&
                update.Task.Revision >= owner.ObservedSnapshot.Revision) owner.ObservedSnapshot = update.Task;
            _lastUpdates[update.Task.Task] = update;
            _updates[update.Task.Task] = update;
            if (_publishing) return;
            _publishing = true;
        }
        _ = Task.Run(PublishUpdates);
    }

    private void PublishFinished(Work work, UploadTaskSnapshot snapshot)
    {
        lock (_gate)
        {
            // Completion releases command waiters before presentation. An explicit retry may already
            // own the same task; the old result cannot decorate that replacement's state with its error.
            if (_work.TryGetValue(work.Task, out var replacement) && !ReferenceEquals(replacement, work)) return;
            Publish(new(work.Activation, snapshot));
        }
    }

    private void PublishUpdates()
    {
        while (true)
        {
            UploadQueueUpdate update;
            lock (_gate)
            {
                if (_updates.Count == 0) { _publishing = false; return; }
                var entry = _updates.First();
                _updates.Remove(entry.Key);
                update = entry.Value;
                if (!_admission || _disposed || update.Activation != _activation) continue;
            }
            if (TaskChanged is { } handlers)
                foreach (EventHandler<UploadQueueUpdate> handler in handlers.GetInvocationList())
                {
                    try { handler(this, update); }
                    catch (Exception) { /* UI subscribers cannot fail execution or hold its drain open. */ }
                }
        }
    }
}
