using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Transfers;

namespace Fileway.Infrastructure.Services;

public sealed partial class UploadApplicationService : IDownloadControlService
{
    public async Task<DownloadItem> SetPausedAsync(DownloadContext context, TransferTaskRef task, bool paused, CancellationToken cancellationToken)
    {
        await EnterAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var binding = RequireDownloadContext(context, task);
            if (_downloads?.Runner is not IActiveDownloadPauseControl control) throw DownloadSafe(ErrorCode.CapabilityMissing);
            if (!paused) RequireDownloadPermission(binding);
            var result = await control.SetPausedAsync(task, paused, cancellationToken).ConfigureAwait(false);
            RequireCurrent(binding);
            return ObserveDownload(binding, result);
        }
        finally { _commands.Release(); }
    }

    public async Task<bool> VerifyLocalOpenAsync(DownloadContext context, TransferTaskRef task, CancellationToken cancellationToken)
    {
        var record = await ReadAsync(task, cancellationToken).ConfigureAwait(false);
        if (record.Plan.Reference != task || record.Plan.Direction != TransferDirection.Download ||
            record.Checkpoint.Phase != TransferPhase.Completed || record.Checkpoint.Uncertainty != TransferUncertainty.None ||
            record.Checkpoint.Error is not null ||
            record.Checkpoint.Files is not { PublishedFileIdentity: not null } files ||
            record.Checkpoint.Source is not { Length: > 0 } source ||
            string.IsNullOrEmpty(record.Checkpoint.LocalSha256) || record.Checkpoint.LocalSha256 != record.Checkpoint.ServerSha256)
            throw DownloadSafe(ErrorCode.InvalidRequest);
        await Task.Run(() => Fileway.Infrastructure.Transfers.WindowsDownloadFileAccess
            .VerifyPublishedForOpenAsync(record.Plan.LocalPath, files, source.Length, cancellationToken), cancellationToken).ConfigureAwait(false);
        return true;
    }

    public async Task RemoveAsync(DownloadContext context, TransferTaskRef task, bool deleteLocalFile, CancellationToken cancellationToken)
    {
        var binding = RequireDownloadContext(context, task);
        var record = await ReadAsync(task, cancellationToken).ConfigureAwait(false);
        if (record.Plan.Direction != TransferDirection.Download) throw DownloadSafe(ErrorCode.InvalidRequest);
        if (record.Checkpoint.Phase != TransferPhase.Completed)
            _ = await ((IDownloadService)this).CancelAsync(context, task, cancellationToken).ConfigureAwait(false);
        Task drain;
        lock (_gate) drain = _downloadExecutions.TryGetValue(task, out var work) ? work.Completion.Task : Task.CompletedTask;
        await drain.ConfigureAwait(false);
        await EnterAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            RequireCurrent(binding);
            record = await ReadAsync(task, cancellationToken).ConfigureAwait(false);
            if (record.Run is not null || record.Checkpoint.Uncertainty != TransferUncertainty.None) throw DownloadSafe(ErrorCode.OutcomeUnknown);
            Task readers;
            lock (_gate) readers = RevokePlaybackReadersLocked(binding, task);
            await readers.ConfigureAwait(false);
            RequireCurrent(binding);
            if (deleteLocalFile)
            {
                if (record.Checkpoint.Phase != TransferPhase.Completed || record.Checkpoint.Files is not { PublishedFileIdentity: not null } files)
                    throw DownloadSafe(ErrorCode.InvalidRequest);
                await Task.Run(() => new WindowsDownloadFileAccess().DeletePublishedAsync(record.Plan.LocalPath, files, cancellationToken), cancellationToken).ConfigureAwait(false);
            }
            RequireCurrent(binding);
            await _tasks.RemoveTransferAsync(task, record.Revision, cancellationToken).ConfigureAwait(false);
            lock (_gate) { binding.DownloadItems.Remove(task); _downloadUpdates.Remove(task); }
        }
        finally { _commands.Release(); }
    }
}
