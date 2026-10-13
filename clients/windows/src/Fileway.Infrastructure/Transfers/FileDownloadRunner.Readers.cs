using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Transfers;

public sealed partial class FileDownloadRunner
{
    private const int MaximumReaders = 4;
    private const int MaximumReadBytes = 128 * 1024;
    private readonly HashSet<ReaderReservation> _readerReservations = [];
    private readonly Dictionary<TransferRunToken, ReaderOwner> _readerOwners = [];

    public Task<IDownloadReadLease> AcquireReadLeaseAsync(TransferTaskRef task, CancellationToken cancellationToken)
    {
        ReaderReservation reservation;
        lock (_gate)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            cancellationToken.ThrowIfCancellationRequested();
            if (_draining) throw Safe(ErrorCode.Busy);
            if (_active is not { Mode: ExecutionMode.Fresh } execution || execution.Task != task ||
                execution.Stop.IsCancellationRequested) throw Safe(ErrorCode.SourceChanged);
            if (_readerReservations.Count >= MaximumReaders) throw Safe(ErrorCode.Busy);
            reservation = new(execution, _admissionEpoch, cancellationToken);
            _readerReservations.Add(reservation);
        }
        // The reservation is visible to drain before an optional native provider can run synchronously.
        _ = Task.Run(() => AcquireReaderCoreAsync(reservation), CancellationToken.None);
        return reservation.Completion.Task;
    }

    private async Task AcquireReaderCoreAsync(ReaderReservation reservation)
    {
        IDownloadReadLease? captured = null;
        bool returned = false;
        try
        {
            reservation.Stop.CancelAfter(_options.ControlCallTimeout);
            var owner = await reservation.Execution.ReaderReady.Task.WaitAsync(reservation.Stop.Token).ConfigureAwait(false)
                ?? throw Safe(ErrorCode.CapabilityMissing);
            lock (_gate)
            {
                RequireReaderAdmission(reservation, owner);
                reservation.Owner = owner;
            }
            // A canceled or timed-out factory must actually finish, then release any late capability.
            captured = await owner.Destination.AcquireReadLeaseAsync(owner.Run, owner.TotalBytes,
                reservation.Stop.Token).ConfigureAwait(false);
            reservation.Stop.Token.ThrowIfCancellationRequested();
            if (captured is null || captured.Run != owner.Run || captured.TotalBytes != owner.TotalBytes ||
                captured.ReadablePrefixBytes < 0 || captured.ReadablePrefixBytes > owner.TotalBytes)
                throw Safe(ErrorCode.ProtocolMismatch);
            lock (_gate)
            {
                RequireReaderAdmission(reservation, owner);
                reservation.Lease = new(this, reservation, captured);
                returned = true;
                reservation.Completion.TrySetResult(reservation.Lease);
            }
        }
        catch (Exception error)
        {
            FilewayException failure = error is OperationCanceledException
                ? Safe(reservation.Caller.IsCancellationRequested || reservation.Revoked ? ErrorCode.Canceled : ErrorCode.Timeout)
                : Normalize(error);
            if (captured is not null && !returned)
            {
                try { await captured.DisposeAsync().ConfigureAwait(false); }
                catch (Exception releaseError)
                {
                    failure = NormalizeReleaseFailure(releaseError);
                    lock (_gate) reservation.UnconfirmedCapture = captured;
                    if (reservation.Owner is { } owner)
                    {
                        try { await RevokeReaderOwnerAsync(owner).ConfigureAwait(false); }
                        catch (Exception revokeError) { failure = NormalizeReleaseFailure(revokeError); }
                    }
                }
            }
            reservation.Completion.TrySetException(failure);
        }
        finally
        {
            reservation.Stop.Dispose();
            lock (_gate)
            {
                reservation.AdmissionFinished.TrySetResult();
                if (!returned && (reservation.UnconfirmedCapture is null || reservation.Owner?.RevokeConfirmed == true))
                    ReleaseReaderReservation(reservation);
            }
        }
    }

    private void RequireReaderAdmission(ReaderReservation reservation, ReaderOwner owner)
    {
        reservation.Stop.Token.ThrowIfCancellationRequested();
        if (_disposed || _draining || reservation.Epoch != _admissionEpoch ||
            !ReferenceEquals(_active, reservation.Execution) || reservation.Execution.Stop.IsCancellationRequested ||
            !ReferenceEquals(reservation.Execution.ReaderOwner, owner) || !owner.Accepting || owner.Revoked)
            throw Safe(ErrorCode.SourceChanged);
    }

    private void AcceptReaderDestination(RunState state, IDownloadDestination destination)
    {
        lock (_gate)
        {
            if (destination is not IReadableDownloadDestination readable || state.Execution.Stop.IsCancellationRequested ||
                _draining || _disposed || !ReferenceEquals(_active, state.Execution))
            { state.Execution.ReaderReady.TrySetResult(null); return; }
            var owner = new ReaderOwner(state.Execution, state.Run,
                state.Record.Checkpoint.Source!.Length, readable);
            state.Execution.ReaderOwner = owner;
            _readerOwners.Add(owner.Run, owner);
            state.Execution.ReaderReady.TrySetResult(owner);
        }
    }

    private void CloseReaderAdmission(Execution execution)
    {
        lock (_gate) if (execution.ReaderOwner is { } owner) owner.Accepting = false;
    }

    private async Task StopExecutionReadersAsync(Execution execution)
    {
        ReaderReservation[] pending;
        ReaderOwner? owner;
        lock (_gate)
        {
            execution.ReaderReady.TrySetResult(null);
            owner = execution.ReaderOwner;
            if (owner is not null) owner.Accepting = false;
            pending = _readerReservations.Where(value => ReferenceEquals(value.Execution, execution) &&
                !value.AdmissionFinished.Task.IsCompleted).ToArray();
            foreach (var value in pending) value.Revoked = true;
        }
        FilewayException? failure = await CancelReaderAdmissionsAsync(pending).ConfigureAwait(false);
        if (owner is not null)
        {
            try { await RevokeReaderOwnerAsync(owner).ConfigureAwait(false); }
            catch (Exception error) { failure ??= NormalizeReleaseFailure(error); }
        }
        if (failure is not null) throw failure;
    }

    private async Task FinishReaderExecutionAsync(Execution execution, bool completed)
    {
        try
        {
            if (!completed || execution.Stop.IsCancellationRequested)
                await StopExecutionReadersAsync(execution).ConfigureAwait(false);
            else
            {
                // Existing readers survive a successful writer. Acquisitions that have not been handed
                // off must still finish and release before the writer's completion is acknowledged.
                ReaderReservation[] pending;
                lock (_gate)
                {
                    execution.ReaderReady.TrySetResult(null);
                    if (execution.ReaderOwner is { } owner) owner.Accepting = false;
                    pending = _readerReservations.Where(value => ReferenceEquals(value.Execution, execution) &&
                        !value.AdmissionFinished.Task.IsCompleted).ToArray();
                    foreach (var value in pending) value.Revoked = true;
                }
                if (await CancelReaderAdmissionsAsync(pending).ConfigureAwait(false) is { } failure) throw failure;
            }
        }
        finally
        {
            lock (_gate)
                if (execution.ReaderOwner is { } owner)
                { owner.Finished = true; ForgetUnusedReaderOwner(owner); }
        }
    }

    private async Task DrainRetainedReadersAsync(ReaderOwner[] recheckOwners)
    {
        ReaderReservation[] pending;
        ReaderOwner[] owners;
        lock (_gate)
        {
            owners = _readerOwners.Values.ToArray();
            foreach (var owner in owners) owner.Accepting = false;
            pending = _readerReservations.Where(value => !value.AdmissionFinished.Task.IsCompleted).ToArray();
            foreach (var value in pending) value.Revoked = true;
        }
        FilewayException? failure = await CancelReaderAdmissionsAsync(pending).ConfigureAwait(false);
        foreach (var owner in owners)
        {
            try { await RevokeReaderOwnerAsync(owner,
                recheckUnconfirmed: Array.IndexOf(recheckOwners, owner) >= 0).ConfigureAwait(false); }
            catch (Exception error) { failure ??= NormalizeReleaseFailure(error); }
        }
        if (failure is not null) throw failure;
    }

    private static async Task<FilewayException?> CancelReaderAdmissionsAsync(ReaderReservation[] pending)
    {
        FilewayException? failure = null;
        foreach (var value in pending)
        {
            try { await value.Stop.CancelAsync().ConfigureAwait(false); }
            catch (ObjectDisposedException) { }
            catch (Exception error) { failure ??= Normalize(error); }
        }
        foreach (var value in pending)
        {
            try { await value.AdmissionFinished.Task.ConfigureAwait(false); }
            catch (Exception error) { failure ??= Normalize(error); }
        }
        return failure;
    }

    private Task RevokeReaderOwnerAsync(ReaderOwner owner, bool recheckUnconfirmed = false)
    {
        TaskCompletionSource completion;
        lock (_gate)
        {
            owner.Accepting = false;
            owner.Revoked = true;
            if (owner.Revocation is not null)
            {
                if (!recheckUnconfirmed || !owner.Revocation.IsCompleted ||
                    owner.RevocationFailure?.Error.Code != ErrorCode.OutcomeUnknown) return owner.Revocation;
                // A new explicit account drain may re-await a timed-out native single flight. This
                // cannot restore admission, restart a reader, or repeat any file mutation.
                owner.Revocation = null;
                owner.RevocationFailure = null;
            }
            completion = new(TaskCreationOptions.RunContinuationsAsynchronously);
            owner.Revocation = completion.Task;
        }
        _ = Task.Run(async () =>
        {
            try
            {
                await owner.Destination.RevokeReadersAsync(CancellationToken.None).ConfigureAwait(false);
                lock (_gate)
                {
                    owner.RevokeConfirmed = true;
                    foreach (var value in _readerReservations.Where(value => ReferenceEquals(value.Owner, owner) &&
                        (value.Lease is not null || value.UnconfirmedCapture is not null &&
                            value.AdmissionFinished.Task.IsCompleted)).ToArray()) ReleaseReaderReservation(value);
                    ForgetUnusedReaderOwner(owner);
                    completion.TrySetResult();
                }
            }
            catch (Exception error)
            {
                var failure = NormalizeReleaseFailure(error);
                lock (_gate)
                {
                    owner.RevocationFailure = failure;
                    completion.TrySetException(failure);
                }
            }
        }, CancellationToken.None);
        return completion.Task;
    }

    // Requires _gate. A failed revocation remains registered even if clients stop observing it.
    private void ReleaseReaderReservation(ReaderReservation reservation)
    {
        _readerReservations.Remove(reservation);
        if (reservation.Owner is { } owner) ForgetUnusedReaderOwner(owner);
    }

    private void ForgetUnusedReaderOwner(ReaderOwner owner)
    {
        if (owner.Finished && (!owner.Revoked || owner.RevokeConfirmed) &&
            !_readerReservations.Any(value => ReferenceEquals(value.Owner, owner)))
            _readerOwners.Remove(owner.Run);
    }

    private sealed class ReaderOwner(Execution execution, TransferRunToken run, long totalBytes,
        IReadableDownloadDestination destination)
    {
        internal Execution Execution { get; } = execution;
        internal TransferRunToken Run { get; } = run;
        internal long TotalBytes { get; } = totalBytes;
        internal IReadableDownloadDestination Destination { get; } = destination;
        internal bool Accepting = true;
        internal bool Finished, Revoked;
        internal volatile bool RevokeConfirmed;
        internal Task? Revocation;
        internal FilewayException? RevocationFailure;
    }

    private sealed class ReaderReservation(Execution execution, long epoch, CancellationToken caller)
    {
        internal Execution Execution { get; } = execution;
        internal long Epoch { get; } = epoch;
        internal CancellationToken Caller { get; } = caller;
        internal CancellationTokenSource Stop { get; } = CancellationTokenSource.CreateLinkedTokenSource(caller);
        internal TaskCompletionSource<IDownloadReadLease> Completion { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal TaskCompletionSource AdmissionFinished { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal ReaderOwner? Owner;
        internal RunnerReadLease? Lease;
        internal IDownloadReadLease? UnconfirmedCapture;
        internal volatile bool Revoked;
    }

    private sealed class RunnerReadLease(FileDownloadRunner runner, ReaderReservation reservation,
        IDownloadReadLease inner) : IDownloadReadLease
    {
        private readonly object _disposeGate = new();
        private Task? _dispose;
        private int _closed;
        public TransferRunToken Run => reservation.Owner!.Run;
        public long TotalBytes => reservation.Owner!.TotalBytes;
        public long ReadablePrefixBytes
        {
            get
            {
                try
                {
                    long value = inner.ReadablePrefixBytes;
                    return value >= 0 && value <= TotalBytes ? value : throw Safe(ErrorCode.ProtocolMismatch);
                }
                catch (Exception error) { throw Normalize(error); }
            }
        }
        public async ValueTask<int> ReadAsync(long offset, Memory<byte> buffer, CancellationToken cancellationToken)
        {
            if (offset < 0 || offset > TotalBytes || buffer.Length > MaximumReadBytes) throw Safe(ErrorCode.InvalidRequest);
            lock (runner._gate)
                if (Volatile.Read(ref _closed) != 0 || reservation.Owner!.Revoked) throw Safe(ErrorCode.SourceChanged);
            try
            {
                int received = await inner.ReadAsync(offset, buffer, cancellationToken).ConfigureAwait(false);
                if (received < 0 || received > buffer.Length || received > Math.Max(0, TotalBytes - offset) ||
                    received == 0 && buffer.Length != 0 && offset < TotalBytes)
                    throw Safe(ErrorCode.ProtocolMismatch);
                return received;
            }
            catch (Exception error) { throw Normalize(error); }
        }
        public ValueTask DisposeAsync()
        {
            lock (_disposeGate)
            {
                if (_dispose is not null) return new(_dispose);
                Volatile.Write(ref _closed, 1);
                _dispose = Task.Run(DisposeCoreAsync, CancellationToken.None);
                return new(_dispose);
            }
        }
        private async Task DisposeCoreAsync()
        {
            FilewayException? failure = null;
            try { await inner.DisposeAsync().ConfigureAwait(false); }
            catch (Exception error)
            {
                failure = NormalizeReleaseFailure(error);
                try { await runner.RevokeReaderOwnerAsync(reservation.Owner!).ConfigureAwait(false); }
                catch (Exception revokeError) { failure ??= NormalizeReleaseFailure(revokeError); }
            }
            if (failure is null || reservation.Owner!.RevokeConfirmed)
                lock (runner._gate) runner.ReleaseReaderReservation(reservation);
            if (failure is not null) throw failure;
        }
        public override string ToString() => "DownloadReadLease (private ownership redacted)";
    }
}
