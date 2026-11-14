using Fileway.Core.Contracts;
using Fileway.Infrastructure.Persistence;

namespace Fileway.Infrastructure.Transfers;

public sealed partial class FileDownloadRunner
{
    private readonly HashSet<TaskCompletionSource> _cancellations = [];
    private TaskCompletionSource RegisterCancellation()
    {
        lock (_gate)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            if (_cancellations.Count >= 128) throw Safe(ErrorCode.Busy);
            var registration = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            _cancellations.Add(registration); return registration;
        }
    }
    private async Task<TransferTaskRecord?> CancelUnstartedAsync(TransferTaskRef task)
    {
        // Cancel is already durable. This separate task lock never waits for the
        // unrelated active file's lock, creates a partial, or acquires a source lease.
        await using var ownership = await AcquireLockAsync(task, CancellationToken.None).ConfigureAwait(false);
        for (var attempt = 0; attempt < MetadataAttempts; attempt++)
        {
            var record = await ReadRequiredAsync(task, CancellationToken.None).ConfigureAwait(false);
            RequirePlan(record);
            if (IsTerminal(record)) return record;
            if (!TransferTaskValidation.IsUnstartedDownload(record) || record.Intent != TransferIntent.Cancel) return null;
            using var budget = new CancellationTokenSource(_options.PersistenceTimeout);
            try
            {
                return await _store.CancelUnstartedDownloadAsync(task, record.Revision, Guid.NewGuid(), budget.Token).ConfigureAwait(false);
            }
            catch (NotSupportedException) { return null; } // Older injected stores retain their original reconciliation path.
            catch (FilewayException error) when (error.Error.Code == ErrorCode.Conflict) { }
        }
        throw Safe(ErrorCode.Conflict);
    }
    private async Task FinishDrainCancellationsAsync(TaskCompletionSource completion, FilewayException? failure)
    {
        while (true)
        {
            Task[] pending;
            lock (_gate)
            {
                pending = _cancellations.Select(value => value.Task).ToArray();
                if (pending.Length == 0)
                {
                    _draining = false;
                    if (failure is null) completion.TrySetResult(); else completion.TrySetException(failure);
                    return;
                }
            }
            await Task.WhenAll(pending).ConfigureAwait(false);
        }
    }
}
