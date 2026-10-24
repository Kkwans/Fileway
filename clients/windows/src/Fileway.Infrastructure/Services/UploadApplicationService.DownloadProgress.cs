using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;

namespace Fileway.Infrastructure.Services;

public sealed partial class UploadApplicationService
{
    // In-memory ordering for status/error changes between durable checkpoints.
    // ProjectDownload is called under the short application gate; no disk revision is invented.
    private long _downloadPresentationRevision;
    private async Task CompleteDownloadAsync(DownloadWork work, Task<TransferTaskRecord> pending)
    {
        TransferTaskRecord? record = null;
        FilewayException? failure = null;
        try
        {
            try { record = await pending.ConfigureAwait(false); }
            catch (Exception error)
            {
                failure = NormalizeDownload(error);
                record = await ReadAsync(work.Task, CancellationToken.None).ConfigureAwait(false);
                if (work.Started && work.Stop.IsCancellationRequested && TransferTaskValidation.IsUnstartedDownload(record))
                    record = await _downloads!.Runner.CancelAsync(work.Task, CancellationToken.None).ConfigureAwait(false);
                if (record.Checkpoint.Phase == TransferPhase.Canceled) failure = null;
            }
            lock (_gate)
            {
                if (!_disposed && ReferenceEquals(_current, work.Binding))
                {
                    if (record.Plan.Reference != work.Task) throw DownloadSafe(ErrorCode.ProtocolMismatch);
                    work.Binding.DownloadItems[work.Task].Scheduled = false;
                    work.Binding.DownloadItems[work.Task].Control = null;
                    _ = ObserveDownload(work.Binding, record);
                    if (failure is not null) RecordDownloadFailure(work.Binding, work.Task, failure);
                }
            }
        }
        catch (Exception error) { failure = NormalizeDownload(error); RecordDownloadFailure(work.Binding, work.Task, failure); }
        finally
        {
            if (work.StopRequested is { } stopping)
                try { await stopping.ConfigureAwait(false); } catch (Exception error) { failure ??= NormalizeDownload(error); }
            work.StopRegistration.Dispose(); work.Stop.Dispose();
            lock (_gate)
            {
                if (_downloadExecutions.TryGetValue(work.Task, out var current) && ReferenceEquals(current, work)) _downloadExecutions.Remove(work.Task);
                if (ReferenceEquals(_activeDownloadWork, work)) _activeDownloadWork = null;
                work.PendingProgress = null;
                work.Completion.TrySetResult(new DownloadCompletion(record, failure));
                QueueDownloadDispatchLocked();
            }
        }
    }

    private async Task DrainDownloadsAsync(Binding binding)
    {
        if (_downloads is null) return;
        DownloadWork[] pending;
        Task readers;
        lock (_gate)
        {
            CancelDownloadWorkLocked(binding);
            pending = _downloadExecutions.Values.Where(work => ReferenceEquals(work.Binding, binding)).ToArray();
            readers = RevokePlaybackReadersLocked(binding);
        }
        FilewayException? failure = null;
        try { await Task.WhenAll(_downloads.Runner.StopAndDrainAsync(CancellationToken.None), readers).ConfigureAwait(false); }
        catch (Exception error) { failure = NormalizeDownload(error); }
        if (pending.Length != 0)
        {
            var results = await Task.WhenAll(pending.Select(work => work.Completion.Task)).ConfigureAwait(false);
            failure ??= results.Select(result => result.Failure).FirstOrDefault(error => error is not null);
        }
        if (failure is not null) throw failure;
    }

    private void CancelDownloadWorkLocked(Binding binding)
    {
        _ = RevokePlaybackReadersLocked(binding);
        foreach (var work in _downloadExecutions.Values.Where(work => ReferenceEquals(work.Binding, binding)).ToArray())
        {
            work.StopRequested ??= work.Stop.CancelAsync();
            if (!work.Started) CancelQueuedDownloadLocked(work);
            if (ReferenceEquals(_current, binding) && binding.DownloadItems.TryGetValue(work.Task, out var item))
            {
                item.Control = DownloadStatus.Canceling;
                QueueDownloadUpdateLocked(ProjectDownload(binding, item));
            }
        }
    }
    private async Task DrainDownloadPermissionAsync(Binding binding)
    {
        try { await DrainDownloadsAsync(binding).ConfigureAwait(false); }
        finally { lock (_gate) binding.DownloadDraining = false; }
    }

    private void DownloadProgressChanged(object? sender, DownloadTaskProgress progress)
    {
        DownloadWork work;
        lock (_gate)
        {
            if (_disposed || _downloads is null || !ReferenceEquals(sender, _downloads.Runner) ||
                !_downloadExecutions.TryGetValue(progress.Run.Task, out work!) || !ReferenceEquals(_current, work.Binding)) return;
            work.PendingProgress = progress;
            if (work.ReadingProgress) return;
            work.ReadingProgress = true;
        }
        _ = ReadDownloadProgressAsync(work);
    }

    private async Task ReadDownloadProgressAsync(DownloadWork work)
    {
        while (true)
        {
            DownloadTaskProgress? progress;
            lock (_gate)
            {
                progress = work.PendingProgress;
                work.PendingProgress = null;
                if (progress is null || _disposed || !ReferenceEquals(_current, work.Binding) ||
                    !_downloadExecutions.TryGetValue(work.Task, out var current) || !ReferenceEquals(current, work))
                { work.ReadingProgress = false; return; }
            }
            try
            {
                var record = await ReadAsync(work.Task, CancellationToken.None).ConfigureAwait(false);
                lock (_gate)
                {
                    if (_disposed || !ReferenceEquals(_current, work.Binding) ||
                        !_downloadExecutions.TryGetValue(work.Task, out var current) || !ReferenceEquals(current, work))
                    { work.ReadingProgress = false; return; }
                    if (record.Run != progress.Run || record.Plan.Reference != work.Task ||
                        record.Checkpoint.Source?.Length != progress.TotalBytes || progress.ReceivedBytes < 0 ||
                        progress.ReceivedBytes > progress.TotalBytes || progress.Elapsed < TimeSpan.Zero ||
                        progress.ConfirmedOffset is { } offset && (offset < 0 || offset > progress.TotalBytes)) continue;
                    _ = ObserveDownload(work.Binding, record, progress);
                }
            }
            catch (Exception) { /* Presentation reads never acquire a file handle, restart a task or fail the runner. */ }
        }
    }

    private static bool DownloadTerminal(TransferPhase phase) => phase is TransferPhase.Completed or TransferPhase.Canceled or TransferPhase.Skipped;

    private static DownloadTaskSnapshot DownloadSnapshot(TransferTaskRecord record)
    {
        var checkpoint = record.Checkpoint;
        bool published = checkpoint.Files?.PublishedFileIdentity is not null && checkpoint.LocalSha256 is not null &&
            checkpoint.LocalSha256 == checkpoint.ServerSha256 && checkpoint.Source is { } source &&
            checkpoint.ConfirmedOffset == source.Length && checkpoint.Uncertainty == TransferUncertainty.None &&
            checkpoint.Phase == TransferPhase.Completed;
        bool localEvidence = checkpoint.Files is { } files &&
            (files.PartialPath is not null && files.PartialIdentity is not null || files.PublishedFileIdentity is not null);
        // Resume evidence is durable fact (pinned version + owned unpublished partial +
        // confirmed offset), not a UI state; the presentation layer adds liveness to it.
        bool resumable = !DownloadTerminal(checkpoint.Phase) && record.Intent != TransferIntent.Cancel &&
            checkpoint.Uncertainty == TransferUncertainty.None && checkpoint.Source?.SourceVersion is not null &&
            checkpoint.Files is { PartialPath: not null, PartialIdentity: not null, PublishedFileIdentity: null } &&
            checkpoint.ConfirmedOffset is not null &&
            checkpoint.Phase is TransferPhase.Paused or TransferPhase.Running or TransferPhase.WaitingNetwork or
                TransferPhase.WaitingAuthentication or TransferPhase.Failed;
        return new(record.Plan.Reference, record.Intent, checkpoint.Phase, checkpoint.ConfirmedOffset, checkpoint.Source?.Length,
            published, checkpoint.Uncertainty, checkpoint.Error is { } error ? DownloadSafe(error.Code, error.HttpStatus, error.Retryable).Error : null,
            record.Revision, record.Generation, localEvidence && !DownloadTerminal(checkpoint.Phase), record.CreatedAt, record.UpdatedAt, resumable);
    }

    private DownloadItem ObserveDownload(Binding binding, TransferTaskRecord record, DownloadTaskProgress? progress = null, bool publish = true)
    {
        if (record.Plan.Direction != TransferDirection.Download || record.Plan.Reference.Account != binding.DownloadContext.Account)
            throw DownloadSafe(ErrorCode.ProtocolMismatch);
        lock (_gate)
        {
            RequireCurrent(binding);
            if (!binding.DownloadItems.TryGetValue(record.Plan.Reference, out var item))
            {
                item = new(record.Plan.Remote, record.Plan.LocalPath);
                binding.DownloadItems.Add(record.Plan.Reference, item);
            }
            var snapshot = DownloadSnapshot(record);
            if (item.Snapshot is { } previous && (snapshot.Generation < previous.Generation ||
                snapshot.Generation == previous.Generation && (snapshot.Revision < previous.Revision ||
                    DownloadTerminal(previous.Phase) && !DownloadTerminal(snapshot.Phase)))) return ProjectDownload(binding, item);
            if (item.Snapshot is { } accepted && snapshot.Generation == accepted.Generation && snapshot.Revision == accepted.Revision &&
                accepted.Error is not null && snapshot.Error is null) snapshot = snapshot with { Error = accepted.Error };
            if (item.Progress is { } old && old.Run.Generation != snapshot.Generation) item.Progress = null;
            item.Snapshot = snapshot;
            if (DownloadTerminal(snapshot.Phase) || snapshot.Error is not null)
            { item.Scheduled = false; item.Control = null; item.Progress = null; }
            if (!DownloadTerminal(snapshot.Phase) && progress is not null && progress.Run.Task == snapshot.Task &&
                progress.Run.Generation == snapshot.Generation && progress.TotalBytes == snapshot.TotalBytes &&
                (item.Progress is not { } previousProgress || previousProgress.Run != progress.Run ||
                    previousProgress.Elapsed <= progress.Elapsed && previousProgress.ReceivedBytes <= progress.ReceivedBytes &&
                    (previousProgress.ConfirmedOffset is null || progress.ConfirmedOffset >= previousProgress.ConfirmedOffset)))
                item.Progress = progress;
            var projected = ProjectDownload(binding, item);
            if (publish) QueueDownloadUpdateLocked(projected);
            return projected;
        }
    }

    private DownloadItem ProjectDownload(Binding binding, DownloadPresentation item)
    {
        var snapshot = item.Snapshot ?? throw DownloadSafe(ErrorCode.ProtocolMismatch);
        bool protectedExecution = item.Scheduled && snapshot.Error is null &&
            snapshot.Phase is TransferPhase.Publishing or TransferPhase.Reconciling;
        DownloadStatus status;
        if ((snapshot.Uncertainty != TransferUncertainty.None && !protectedExecution) || snapshot.Error?.Code == ErrorCode.OutcomeUnknown)
            status = item.Control ?? DownloadStatus.OutcomeUnknown;
        else if (snapshot.Phase == TransferPhase.Completed)
            status = snapshot.LocalPublished ? DownloadStatus.Completed : DownloadStatus.OutcomeUnknown;
        else if (snapshot.Phase == TransferPhase.Canceled) status = DownloadStatus.Canceled;
        else if (item.Control is { } control) status = control;
        else if (snapshot.Intent == TransferIntent.Pause && item.Scheduled) status = DownloadStatus.Paused;
        else if (snapshot.Intent == TransferIntent.Cancel) status = DownloadStatus.OutcomeUnknown;
        else if (snapshot.Error is not null && snapshot.Phase is TransferPhase.Queued or TransferPhase.Preparing or TransferPhase.Running or
            TransferPhase.Verifying or TransferPhase.Publishing or TransferPhase.Reconciling) status = DownloadStatus.Failed;
        else if (!item.Scheduled && snapshot.Phase == TransferPhase.Queued) status = DownloadStatus.NotStarted;
        else if (!item.Scheduled && snapshot.Phase is TransferPhase.Preparing or TransferPhase.Running or TransferPhase.Verifying or
            TransferPhase.Publishing or TransferPhase.Reconciling) status = DownloadStatus.Stopped;
        else status = snapshot.Phase switch
        {
            TransferPhase.Queued => DownloadStatus.Queued,
            TransferPhase.Preparing => DownloadStatus.Preparing,
            TransferPhase.Running => DownloadStatus.Downloading,
            TransferPhase.Paused => item.Scheduled ? DownloadStatus.Paused : DownloadStatus.Stopped,
            TransferPhase.Skipped => DownloadStatus.Stopped,
            TransferPhase.WaitingNetwork => DownloadStatus.WaitingNetwork,
            TransferPhase.WaitingAuthentication => DownloadStatus.WaitingAuthentication,
            TransferPhase.Conflict => DownloadStatus.Conflict,
            TransferPhase.SourceChanged => DownloadStatus.SourceChanged,
            TransferPhase.Verifying => DownloadStatus.Verifying,
            TransferPhase.Publishing => DownloadStatus.Publishing,
            TransferPhase.Reconciling => DownloadStatus.Reconciling,
            TransferPhase.OutcomeUnknown => DownloadStatus.OutcomeUnknown,
            _ => DownloadStatus.Failed,
        };
        return new(binding.DownloadContext, item.Source, item.Source.DisplayName, item.Destination, status, snapshot, item.Progress,
            checked(++_downloadPresentationRevision), CanPause: item.Scheduled && status == DownloadStatus.Downloading,
            CanResume: item.Scheduled && status == DownloadStatus.Paused || !item.Scheduled && snapshot.CanResumeLocally);
    }

    private void RecordDownloadFailure(Binding binding, TransferTaskRef task, Exception error)
    {
        lock (_gate)
        {
            if (_disposed || !ReferenceEquals(_current, binding) || !binding.DownloadItems.TryGetValue(task, out var item) ||
                item.Snapshot is not { } snapshot) return;
            item.Control = null;
            item.Scheduled = false;
            item.Progress = null;
            item.Snapshot = snapshot with { Error = NormalizeDownload(error).Error };
            QueueDownloadUpdateLocked(ProjectDownload(binding, item));
        }
    }

    private void QueueDownloadUpdateLocked(DownloadItem item)
    {
        _downloadUpdates[item.Snapshot.Task] = item;
        if (_downloadPublishing) return;
        _downloadPublishing = true;
        _ = Task.Run(PublishDownloadUpdates);
    }

    private void PublishDownloadUpdates()
    {
        while (true)
        {
            DownloadItem? item;
            lock (_gate)
            {
                if (_downloadUpdates.Count == 0) { _downloadPublishing = false; return; }
                var first = _downloadUpdates.First();
                _downloadUpdates.Remove(first.Key);
                item = !_disposed && _current?.DownloadContext == first.Value.Context ? first.Value : null;
            }
            if (item is not null) PublishEvent(this, DownloadChanged, item);
        }
    }
}
