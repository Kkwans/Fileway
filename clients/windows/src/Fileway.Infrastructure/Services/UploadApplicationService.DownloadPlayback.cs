using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

public sealed partial class UploadApplicationService
{
    // One outstanding admission, including rejected-reader cleanup. A late provider cannot grow
    // an unbounded collection of detached cleanups or retain the account command semaphore.
    private object? _downloadPlaybackAdmission;
    private const int MaximumPlaybackReaders = 4;
    private readonly HashSet<PlaybackReadLease> _downloadPlaybackReaders = [];

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
            CancellationToken executionStop;
            lock (_gate)
            {
                binding = RequireDownloadContext(context, task);
                RequireDownloadPermission(binding);
                cancellationToken.ThrowIfCancellationRequested();
                provider = _downloads!.Runner as IFileDownloadReadLeaseProvider
                    ?? throw DownloadSafe(ErrorCode.CapabilityMissing);
                if (!_downloadExecutions.TryGetValue(task, out work!)) throw DownloadSafe(ErrorCode.SourceChanged);
                _ = RequirePlaybackWorkLocked(binding, context, work);
                if (_downloadPlaybackAdmission is not null || _downloadPlaybackReaders.Count >= MaximumPlaybackReaders)
                    throw DownloadSafe(ErrorCode.Busy);
                executionStop = work.Stop.Token;
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
                var reader = new PlaybackReadLease(this, binding, context, work, acquired, run, total,
                    original.Plan.Remote, original.Checkpoint.Source!, executionStop);
                _downloadPlaybackReaders.Add(reader);
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

    // Caller holds only the application gate. Cancellation marks the fence immediately and runs
    // token callbacks elsewhere; no network operation, native callback or final reader disposal waits here.
    private Task RevokePlaybackReadersLocked(Binding binding, TransferTaskRef? task = null) => Task.WhenAll(
        _downloadPlaybackReaders.Where(reader => ReferenceEquals(reader.Binding, binding) &&
            (task is null || reader.Run.Task == task)).Select(reader => reader.RequestRevocation()).ToArray());

    // Existing readers may outlive the successfully published writer, but never their account,
    // permission or exact execution. This check uses accepted in-memory evidence only.
    private void RequirePlaybackReaderLocked(Binding binding, DownloadContext context, DownloadWork work,
        TransferRunToken run, long total)
    {
        if (!ReferenceEquals(RequireDownloadContext(context, run.Task), binding)) throw DownloadSafe(ErrorCode.SourceChanged);
        RequireDownloadPermission(binding);
        if (binding.DownloadDraining || !binding.DownloadItems.TryGetValue(run.Task, out var item) ||
            item.Control is not null || item.Snapshot is not { } snapshot || snapshot.Generation != run.Generation ||
            snapshot.TotalBytes != total || snapshot.Intent != TransferIntent.Run || snapshot.Error is not null)
            throw DownloadSafe(ErrorCode.SourceChanged);
        bool complete = snapshot.Phase == TransferPhase.Completed && snapshot.LocalPublished;
        bool protectedExecution = item.Scheduled && snapshot.Uncertainty == TransferUncertainty.LocalPublish &&
            (snapshot.Phase is TransferPhase.Publishing or TransferPhase.Reconciling);
        if ((snapshot.Uncertainty != TransferUncertainty.None && !protectedExecution) ||
            (!complete && (snapshot.Phase is not (TransferPhase.Running or TransferPhase.Verifying or TransferPhase.Publishing or TransferPhase.Reconciling) ||
                !item.Scheduled || !_downloadExecutions.TryGetValue(run.Task, out var active) || !ReferenceEquals(active, work))))
            throw DownloadSafe(ErrorCode.SourceChanged);
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
    private sealed class PlaybackReadLease : IDownloadReadLease
    {
        private readonly UploadApplicationService _service;
        private readonly DownloadContext _context;
        private readonly DownloadWork _work;
        private readonly IDownloadReadLease _inner;
        private readonly RemoteResourceRef _source;
        private readonly TransferSourceSnapshot _observed;
        private readonly object _disposeGate = new();
        private readonly SemaphoreSlim _queue = new(1, 1);
        private readonly CancellationTokenSource _stop = new();
        private readonly CancellationTokenRegistration _executionStop;
        private readonly TaskCompletionSource _readsDrained = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private TaskCompletionSource? _disposal;
        private TaskCompletionSource? _revocation;
        private DownloadPlaybackRangeReader? _remote;
        private FilewayException? _failure;
        private int _operations;

        internal PlaybackReadLease(UploadApplicationService service, Binding binding, DownloadContext context,
            DownloadWork work, IDownloadReadLease inner, TransferRunToken run, long total,
            RemoteResourceRef source, TransferSourceSnapshot observed, CancellationToken executionStop)
        {
            _service = service;
            Binding = binding;
            _context = context;
            _work = work;
            _inner = inner;
            _source = source;
            _observed = observed;
            Run = run;
            TotalBytes = total;
            // This callback only starts a read drain. The native bridge retains the reader object
            // and its delegates until mpv destruction; task cancellation never waits for that lifetime.
            _executionStop = executionStop.UnsafeRegister(static state =>
                _ = ((PlaybackReadLease)state!).RequestRevocation(), this);
        }

        internal Binding Binding { get; }
        public TransferRunToken Run { get; }
        public long TotalBytes { get; }
        public long ReadablePrefixBytes
        {
            get
            {
                try
                {
                    RequireAvailable();
                    long prefix = _inner.ReadablePrefixBytes;
                    RequireAvailable();
                    if (prefix < 0 || prefix > TotalBytes) throw DownloadSafe(ErrorCode.ProtocolMismatch);
                    return prefix;
                }
                catch (Exception error) { throw NormalizeDownload(error); }
            }
        }
        public async ValueTask<int> ReadAsync(long offset, Memory<byte> buffer, CancellationToken cancellationToken)
        {
            bool entered = false, queued = false;
            try
            {
                cancellationToken.ThrowIfCancellationRequested();
                RequireAvailable();
                if (offset < 0 || offset > TotalBytes) throw DownloadSafe(ErrorCode.InvalidRequest);
                lock (_disposeGate)
                {
                    if (_disposal is not null || _revocation is not null) throw DownloadSafe(ErrorCode.SourceChanged);
                    _operations++;
                    entered = true;
                }
                using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, _stop.Token);
                await _queue.WaitAsync(linked.Token).ConfigureAwait(false);
                queued = true;
                RequireAvailable();
                long prefix = ReadablePrefixBytes;
                if (buffer.IsEmpty || offset == TotalBytes) return 0;
                int received;
                if (offset < prefix || _observed.Modified is null)
                {
                    // The verified prefix uses the same retained object. Missing modification
                    // evidence keeps the original waiting behavior instead of rejecting local playback.
                    int count = offset < prefix ? (int)Math.Min(buffer.Length, prefix - offset) : buffer.Length;
                    received = await _inner.ReadAsync(offset, buffer[..count], linked.Token).ConfigureAwait(false);
                    if (received > count) throw DownloadSafe(ErrorCode.ProtocolMismatch);
                }
                else
                {
                    // At most four admitted readers retain at most one 1 MiB range buffer each.
                    // Its observed source fence is playback-only: never persisted and never resume authority.
                    _remote ??= new DownloadPlaybackRangeReader(_service._downloads!.Sources, _source,
                        _observed, RequireAvailable, _stop.Token);
                    try { received = await _remote.ReadAsync(offset, buffer, linked.Token).ConfigureAwait(false); }
                    catch (Exception error)
                    {
                        var failure = NormalizeDownload(error);
                        if (!cancellationToken.IsCancellationRequested || failure.Error.Code == ErrorCode.OutcomeUnknown)
                        {
                            lock (_disposeGate) _failure ??= failure;
                            _ = RequestRevocation();
                        }
                        throw;
                    }
                }
                RequireAvailable();
                cancellationToken.ThrowIfCancellationRequested();
                if (received < 0 || received > buffer.Length || received > TotalBytes - offset ||
                    received == 0 && buffer.Length != 0 && offset < TotalBytes)
                    throw DownloadSafe(ErrorCode.ProtocolMismatch);
                return received;
            }
            catch (Exception error)
            {
                // A revoked operation must expose its current account/permission fence, including
                // a late noncooperative provider, rather than collapsing every retirement to Canceled.
                if (!cancellationToken.IsCancellationRequested)
                    try { RequireAvailable(); }
                    catch (Exception authorityError) { throw NormalizeDownload(authorityError); }
                throw NormalizeDownload(error);
            }
            finally
            {
                if (queued) _queue.Release();
                if (entered)
                    lock (_disposeGate)
                        if (--_operations == 0 && _revocation is not null) _readsDrained.TrySetResult();
            }
        }
        private void RequireAvailable()
        {
            lock (_service._gate) _service.RequirePlaybackReaderLocked(Binding, _context, _work, Run, TotalBytes);
            lock (_disposeGate)
            {
                if (_failure is not null) throw _failure;
                if (_disposal is not null || _revocation is not null) throw DownloadSafe(ErrorCode.SourceChanged);
            }
        }

        internal Task RequestRevocation()
        {
            TaskCompletionSource completion;
            lock (_disposeGate)
            {
                if (_revocation is not null) return _revocation.Task;
                _revocation = completion = new(TaskCreationOptions.RunContinuationsAsynchronously);
                if (_operations == 0) _readsDrained.TrySetResult();
            }
            _ = RevokeCoreAsync(completion);
            return completion.Task;
        }

        private async Task RevokeCoreAsync(TaskCompletionSource completion)
        {
            Exception? failure = null;
            try { await _stop.CancelAsync().ConfigureAwait(false); }
            catch (Exception error) { failure = error; }
            await _readsDrained.Task.ConfigureAwait(false);
            try { _remote?.RequireReleased(); }
            catch (Exception releaseError) { failure = releaseError; }
            // A read only drains after its range helper has released the actual remote capability.
            // Do not wait for mpv_destroy, final reader Dispose, or any window close here.
            if (failure is null) completion.TrySetResult();
            else completion.TrySetException(NormalizeDownload(failure));
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
            Exception? failure = null;
            try { await RequestRevocation().ConfigureAwait(false); }
            catch (Exception error) { failure = error; }
            try { if (_remote is not null) await _remote.DisposeAsync().ConfigureAwait(false); }
            catch (Exception error) { failure ??= error; }
            try { await _inner.DisposeAsync().ConfigureAwait(false); }
            catch (Exception error) { failure ??= error; }
            try { await _executionStop.DisposeAsync().ConfigureAwait(false); }
            catch (Exception error) { failure ??= error; }
            _queue.Dispose();
            _stop.Dispose();
            lock (_service._gate) _service._downloadPlaybackReaders.Remove(this);
            if (failure is null) completion.TrySetResult();
            else completion.TrySetException(NormalizeDownload(failure));
        }
    }
}
