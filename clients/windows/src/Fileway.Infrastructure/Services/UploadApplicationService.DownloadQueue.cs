using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;

namespace Fileway.Infrastructure.Services;

public sealed partial class UploadApplicationService
{
    private const int MaximumDownloadQueue = 100;
    private readonly LinkedList<DownloadWork> _downloadQueue = [];
    private DownloadWork? _activeDownloadWork;
    private int _downloadControlOperations;
    private bool _downloadDispatchScheduled;

    private void RequireDownloadQueueCapacityLocked(Binding binding)
    {
        if (binding.DownloadDraining || _downloadExecutions.Count >= MaximumDownloadQueue) throw DownloadSafe(ErrorCode.Busy);
    }
    private void RegisterDownloadStopLocked(DownloadWork work)
    {
        work.StopRegistration = work.Stop.Token.UnsafeRegister(_ =>
        {
            _ = Task.Run(() => { lock (_gate) if (!work.Started) CancelQueuedDownloadLocked(work); });
        }, null);
    }
    private void CancelQueuedDownloadLocked(DownloadWork work)
    {
        if (work.Started || work.Canceling || work.Completion.Task.IsCompleted) return;
        work.Canceling = true;
        if (work.Node is { } node) { _downloadQueue.Remove(node); work.Node = null; }
        var pending = Task.Run(() => _downloads!.Runner.CancelAsync(work.Task, CancellationToken.None));
        _ = CompleteDownloadAsync(work, pending);
    }
    private void DispatchDownloads() { lock (_gate) QueueDownloadDispatchLocked(); }
    private void QueueDownloadDispatchLocked()
    {
        if (_downloadDispatchScheduled || _disposed || _downloadQueue.Count == 0 || _activeDownloadWork is not null || _downloadControlOperations != 0) return;
        _downloadDispatchScheduled = true;
        _ = Task.Run(DispatchDownloadCore);
    }
    private void DispatchDownloadCore()
    {
        DownloadWork? selected = null;
        Task<TransferTaskRecord>? pending = null;
        lock (_gate)
        {
            _downloadDispatchScheduled = false;
            if (_disposed || _activeDownloadWork is not null || _downloadControlOperations != 0) return;
            while (_downloadQueue.First is { } node)
            {
                var work = node.Value;
                if (!ReferenceEquals(_current, work.Binding) || work.Binding.DownloadDraining || work.Stop.IsCancellationRequested || !work.Binding.Session.Permissions.Download)
                { CancelQueuedDownloadLocked(work); continue; }
                _downloadQueue.RemoveFirst(); work.Node = null; work.Started = true;
                _activeDownloadWork = selected = work;
                // Runs under the same short authority gate as retirement; no await or disk I/O is performed here.
                pending = Task.Run(() => RunQueuedDownloadAsync(work));
                break;
            }
        }
        if (selected is not null) _ = CompleteDownloadAsync(selected, pending!);
    }
    private async Task<TransferTaskRecord> RunQueuedDownloadAsync(DownloadWork work)
    {
        long initialGeneration = -1;
        while (true)
        {
            work.Stop.Token.ThrowIfCancellationRequested();
            lock (_gate) { RequireCurrent(work.Binding); RequireDownloadPermission(work.Binding); }
            try
            {
                if (work.Resume) return await (_downloads!.Runner as IFileDownloadResumeControl ??
                    throw DownloadSafe(ErrorCode.CapabilityMissing)).ResumeAsync(work.Task, work.Stop.Token).ConfigureAwait(false);
                return await _downloads!.Runner.RunAsync(work.Task, work.Stop.Token).ConfigureAwait(false);
            }
            catch (FilewayException error) when (error.Error.Code == ErrorCode.Busy)
            {
                var record = await ReadAsync(work.Task, work.Stop.Token).ConfigureAwait(false);
                if (initialGeneration < 0) initialGeneration = record.Generation;
                bool retryable = work.Resume
                    ? record.Plan.Reference == work.Task && record.Intent == TransferIntent.Run &&
                        !TransferTaskValidation.IsUnstartedDownload(record) && !DownloadTerminal(record.Checkpoint.Phase) &&
                        (record.Run is null || record.Run.Value.Generation == initialGeneration)
                    : TransferTaskValidation.IsUnstartedDownload(record) && record.Intent == TransferIntent.Run;
                if (!retryable) throw;
                // Only retry a proved never-claimed admission when uploads occupy the shared
                // run budget. Never repeat a request that has source/file/run evidence.
                await Task.Delay(TimeSpan.FromMilliseconds(500), work.Stop.Token).ConfigureAwait(false);
            }
        }
    }
}
