using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Transfers;

public sealed partial class FileDownloadRunner
{
    public async Task<TransferTaskRecord> SetPausedAsync(TransferTaskRef task, bool paused, CancellationToken cancellationToken)
    {
        Execution active;
        lock (_gate) active = _active?.Task == task ? _active : throw Safe(ErrorCode.Busy);
        for (var attempt = 0; attempt < MetadataAttempts; attempt++)
        {
            var record = await ReadRequiredAsync(task, cancellationToken).ConfigureAwait(false);
            if (IsTerminal(record)) return record;
            if (record.Run is null || paused && record.Checkpoint.Phase is not (TransferPhase.Running or TransferPhase.Paused) || record.Intent == TransferIntent.Cancel) throw Safe(ErrorCode.Busy);
            if (record.Intent == (paused ? TransferIntent.Pause : TransferIntent.Run)) return record;
            if (paused)
            {
                lock (_gate)
                {
                    if (!ReferenceEquals(_active, active)) throw Safe(ErrorCode.SourceChanged);
                    active.Paused = new(TaskCreationOptions.RunContinuationsAsynchronously);
                    active.ResumeGate = new(TaskCreationOptions.RunContinuationsAsynchronously);
                    active.Resumed = new(TaskCreationOptions.RunContinuationsAsynchronously);
                }
            }
            try
            {
                _ = await _store.SetTransferIntentAsync(task, record.Revision, paused ? TransferIntent.Pause : TransferIntent.Run, cancellationToken).ConfigureAwait(false);
                if (paused) await Task.WhenAny(active.Paused.Task, active.Completion.Task).WaitAsync(cancellationToken).ConfigureAwait(false);
                else
                {
                    active.ResumeGate.TrySetResult();
                    await Task.WhenAny(active.Resumed.Task, active.Completion.Task).WaitAsync(cancellationToken).ConfigureAwait(false);
                }
                return await ReadRequiredAsync(task, cancellationToken).ConfigureAwait(false);
            }
            catch (FilewayException error) when (error.Error.Code == ErrorCode.Conflict) { }
        }
        throw Safe(ErrorCode.Conflict);
    }
}
