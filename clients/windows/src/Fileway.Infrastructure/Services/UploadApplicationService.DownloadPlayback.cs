using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

public sealed partial class UploadApplicationService
{
    // One outstanding admission, including rejected-reader cleanup. A late provider cannot grow
    // an unbounded collection of detached cleanups or retain the account command semaphore.
    private object? _downloadPlaybackAdmission;

    async Task<DownloadPlaybackSource> IDownloadPlaybackSourceProvider.AcquirePlaybackAsync(
        DownloadContext context, TransferTaskRef task, CancellationToken cancellationToken)
    {
        object? admission = null;
        IDownloadReadLease? acquired = null;
        CancellationTokenSource? stop = null;
        try
        {
            Binding binding;
            DownloadWork work;
            IFileDownloadReadLeaseProvider provider;
            lock (_gate)
            {
                binding = RequireDownloadContext(context, task);
                RequireDownloadPermission(binding);
                cancellationToken.ThrowIfCancellationRequested();
                provider = _downloads!.Runner as IFileDownloadReadLeaseProvider
                    ?? throw DownloadSafe(ErrorCode.CapabilityMissing);
                if (!_downloadExecutions.TryGetValue(task, out work!)) throw DownloadSafe(ErrorCode.SourceChanged);
                _ = RequirePlaybackWorkLocked(binding, context, work);
                if (_downloadPlaybackAdmission is not null) throw DownloadSafe(ErrorCode.Busy);
                stop = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, work.Stop.Token);
                _downloadPlaybackAdmission = admission = new object();
            }

            // Pin already accepted evidence. Preparing work has no read authority yet and is not
            // queued here. Neither this durable read nor provider readiness holds an account lock.
            var original = await ReadAsync(task, stop.Token).ConfigureAwait(false);
            lock (_gate)
            {
                var presentation = RequirePlaybackWorkLocked(binding, context, work);
                stop.Token.ThrowIfCancellationRequested();
                ValidatePlaybackRecord(presentation, task, original);
            }
            acquired = await provider.AcquireReadLeaseAsync(task, stop.Token).ConfigureAwait(false)
                ?? throw DownloadSafe(ErrorCode.ProtocolMismatch);
            lock (_gate)
            {
                _ = RequirePlaybackWorkLocked(binding, context, work);
                stop.Token.ThrowIfCancellationRequested();
            }
            // A provider is allowed to finish after cancellation. Retain ownership until this
            // operation settles, including any required disposal, rather than abandoning a lease.
            var run = acquired.Run;
            long total = acquired.TotalBytes;
            long prefix = acquired.ReadablePrefixBytes;
            if (run != original.Run || total != original.Checkpoint.Source!.Length || prefix < 0 || prefix > total)
                throw DownloadSafe(ErrorCode.ProtocolMismatch);
            var current = await ReadAsync(task, stop.Token).ConfigureAwait(false);
            lock (_gate)
            {
                var presentation = RequirePlaybackWorkLocked(binding, context, work);
                stop.Token.ThrowIfCancellationRequested();
                ValidatePlaybackRecord(presentation, task, current);
                if (current.Run != original.Run || current.Plan != original.Plan || current.Revision < original.Revision ||
                    current.Checkpoint.Source != original.Checkpoint.Source ||
                    current.Checkpoint.Files!.PartialIdentity != original.Checkpoint.Files!.PartialIdentity ||
                    current.Checkpoint.Files.PartialPath != original.Checkpoint.Files.PartialPath)
                    throw DownloadSafe(ErrorCode.SourceChanged);
                var item = ObserveDownload(binding, current);
                var reader = new PlaybackReadLease(acquired, run, total);
                acquired = null; // Ownership transfers only after the final authority/evidence check.
                return new(item, reader);
            }
        }
        catch (Exception error)
        {
            if (acquired is not null)
            {
                try { await acquired.DisposeAsync().ConfigureAwait(false); }
                catch (Exception cleanupError)
                {
                    // A failed release is material even when cancellation or stale authority was
                    // the initial reason for rejection. Do not retry or claim successful cleanup.
                    throw NormalizeDownload(cleanupError);
                }
            }
            throw NormalizeDownload(error);
        }
        finally
        {
            stop?.Dispose();
            if (admission is not null)
                lock (_gate)
                    if (ReferenceEquals(_downloadPlaybackAdmission, admission)) _downloadPlaybackAdmission = null;
        }
    }

    // Caller holds only the short in-memory gate; this method never invokes the reader/provider.
    private DownloadPresentation RequirePlaybackWorkLocked(Binding binding, DownloadContext context, DownloadWork work)
    {
        if (!ReferenceEquals(RequireDownloadContext(context, work.Task), binding)) throw DownloadSafe(ErrorCode.SourceChanged);
        RequireDownloadPermission(binding);
        if (binding.DownloadDraining || !ReferenceEquals(work.Binding, binding) ||
            !_downloadExecutions.TryGetValue(work.Task, out var active) || !ReferenceEquals(active, work) ||
            !binding.DownloadItems.TryGetValue(work.Task, out var item) || !item.Scheduled || item.Control is not null)
            throw DownloadSafe(ErrorCode.SourceChanged);
        return item;
    }

    private static void ValidatePlaybackRecord(DownloadPresentation item, TransferTaskRef task, TransferTaskRecord record)
    {
        if (record.Plan.Reference != task || record.Plan.Direction != TransferDirection.Download ||
            record.Plan.Remote != item.Source || record.Plan.LocalPath != item.Destination)
            throw DownloadSafe(ErrorCode.ProtocolMismatch);
        if (record.Intent != TransferIntent.Run || record.Checkpoint.Error is not null ||
            record.Checkpoint.Uncertainty != TransferUncertainty.None)
            throw DownloadSafe(ErrorCode.SourceChanged);
        if (record.Checkpoint.Phase is TransferPhase.Queued or TransferPhase.Preparing)
            throw DownloadSafe(ErrorCode.Busy);
        if (record.Checkpoint.Phase is not (TransferPhase.Running or TransferPhase.Verifying))
            throw DownloadSafe(ErrorCode.SourceChanged);
        if (record.Run is not { } run || run.Task != task || run.Id == Guid.Empty || run.Generation <= 0 ||
            record.Generation != run.Generation || record.Checkpoint.Source is not { Length: >= 0 } ||
            record.Checkpoint.Files is not { PartialIdentity.Length: > 0, PartialPath.Length: > 0 })
            throw DownloadSafe(ErrorCode.ProtocolMismatch);
        if (item.Snapshot is { } accepted && (accepted.Generation > record.Generation ||
            accepted.Generation == record.Generation && accepted.Revision > record.Revision))
            throw DownloadSafe(ErrorCode.SourceChanged);
    }

    // Keep private implementation exceptions behind the application boundary after admission too.
    // Disposal is idempotent, preserves a failed result, and releases only this reader.
    private sealed class PlaybackReadLease(IDownloadReadLease inner, TransferRunToken run, long total) : IDownloadReadLease
    {
        private readonly object _disposeGate = new();
        private TaskCompletionSource? _disposal;
        public TransferRunToken Run { get; } = run;
        public long TotalBytes { get; } = total;
        public long ReadablePrefixBytes
        {
            get
            {
                try
                {
                    long prefix = inner.ReadablePrefixBytes;
                    if (prefix < 0 || prefix > TotalBytes) throw DownloadSafe(ErrorCode.ProtocolMismatch);
                    return prefix;
                }
                catch (Exception error) { throw NormalizeDownload(error); }
            }
        }
        public async ValueTask<int> ReadAsync(long offset, Memory<byte> buffer, CancellationToken cancellationToken)
        {
            try
            {
                lock (_disposeGate) if (_disposal is not null) throw DownloadSafe(ErrorCode.SourceChanged);
                return await inner.ReadAsync(offset, buffer, cancellationToken).ConfigureAwait(false);
            }
            catch (Exception error) { throw NormalizeDownload(error); }
        }
        public ValueTask DisposeAsync()
        {
            TaskCompletionSource completion;
            lock (_disposeGate)
            {
                if (_disposal is not null) return new(_disposal.Task);
                _disposal = completion = new(TaskCreationOptions.RunContinuationsAsynchronously);
            }
            _ = DisposeCoreAsync(completion);
            return new(completion.Task);
        }
        private async Task DisposeCoreAsync(TaskCompletionSource completion)
        {
            try { await inner.DisposeAsync().ConfigureAwait(false); completion.TrySetResult(); }
            catch (Exception error) { completion.TrySetException(NormalizeDownload(error)); }
        }
    }
}
