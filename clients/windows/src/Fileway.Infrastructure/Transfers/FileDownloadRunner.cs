using System.Diagnostics;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;

namespace Fileway.Infrastructure.Transfers;

/// <summary>Bounded buffers and per-operation budgets. The checksum provider owns its longer hashing budget.</summary>
public sealed record FileDownloadRunnerOptions
{
    public int BufferSizeBytes { get; init; } = 2 * 1024 * 1024;
    public TimeSpan RequestIdleTimeout { get; init; } = TimeSpan.FromSeconds(30);
    public TimeSpan ProgressInterval { get; init; } = TimeSpan.FromMilliseconds(200);
    public TimeSpan ControlCallTimeout { get; init; } = TimeSpan.FromSeconds(30);
    public TimeSpan PersistenceTimeout { get; init; } = TimeSpan.FromSeconds(30);
}

/// <summary>One explicit fresh file at a time. Construction, listing and reconciliation never initiate a GET.</summary>
public sealed partial class FileDownloadRunner : IFileDownloadRunner
{
    private readonly ITransferTaskStore _store;
    private readonly IDownloadSourceProvider _sources;
    private readonly IDownloadDestinationProvider _destinations;
    private readonly ITransferExecutionLockProvider _executionLocks;
    private readonly FileDownloadRunnerOptions _options;
    private readonly HttpClient _http;
    private readonly object _gate = new();
    private Execution? _active;
    private Task? _drain;
    private long _admissionEpoch;
    private bool _draining;
    private bool _disposed;
    private TaskCompletionSource? _disposeCompletion;

    public FileDownloadRunner(ITransferTaskStore store, IDownloadSourceProvider sources,
        IDownloadDestinationProvider destinations, ITransferExecutionLockProvider executionLocks,
        HttpMessageHandler? handler = null, FileDownloadRunnerOptions? options = null)
    {
        ArgumentNullException.ThrowIfNull(store);
        ArgumentNullException.ThrowIfNull(sources);
        ArgumentNullException.ThrowIfNull(destinations);
        ArgumentNullException.ThrowIfNull(executionLocks);
        _options = options ?? new();
        if (_options.BufferSizeBytes is < 1 or > 2 * 1024 * 1024 ||
            _options.RequestIdleTimeout <= TimeSpan.Zero || _options.RequestIdleTimeout > TimeSpan.FromHours(1) ||
            _options.ProgressInterval < TimeSpan.FromMilliseconds(200) || _options.ProgressInterval > TimeSpan.FromSeconds(30) ||
            _options.ControlCallTimeout <= TimeSpan.Zero || _options.ControlCallTimeout > TimeSpan.FromMinutes(5) ||
            _options.PersistenceTimeout <= TimeSpan.Zero || _options.PersistenceTimeout > TimeSpan.FromMinutes(5))
            throw Safe(ErrorCode.InvalidRequest);
        _store = store; _sources = sources; _destinations = destinations; _executionLocks = executionLocks;
        _http = new(handler ?? new SocketsHttpHandler
        {
            AllowAutoRedirect = false, UseProxy = false, UseCookies = false,
            AutomaticDecompression = System.Net.DecompressionMethods.None, ConnectTimeout = _options.ControlCallTimeout,
        }, disposeHandler: true) { Timeout = Timeout.InfiniteTimeSpan };
    }

    public event EventHandler<DownloadTaskProgress>? ProgressChanged;

    public async Task<TransferTaskRecord> RunAsync(TransferTaskRef task, CancellationToken cancellationToken)
    {
        long epoch = AdmissionEpoch();
        var record = await ReadRequiredAsync(task, cancellationToken).ConfigureAwait(false);
        cancellationToken.ThrowIfCancellationRequested();
        RequirePlan(record);
        if (IsTerminal(record)) return record;
        RequireFresh(record);
        return await StartExecution(task, ExecutionMode.Fresh, epoch, cancellationToken).ConfigureAwait(false);
    }

    public async Task<TransferTaskRecord> CancelAsync(TransferTaskRef task, CancellationToken cancellationToken)
    {
        long epoch = AdmissionEpoch();
        var record = await SetCancelAsync(task, cancellationToken).ConfigureAwait(false);
        Execution? active;
        lock (_gate) active = _active?.Task == task ? _active : null;
        if (active is not null)
        {
            var stopFailure = await StopAsync(active).ConfigureAwait(false);
            // Once Cancel is durable, caller cancellation cannot abandon local release or reverse it.
            var completed = await active.Completion.Task.ConfigureAwait(false);
            if (stopFailure is not null) throw stopFailure;
            return completed;
        }
        if (IsTerminal(record)) return record;
        return await StartExecution(task, ExecutionMode.Reconcile, epoch, CancellationToken.None).ConfigureAwait(false);
    }

    public async Task<TransferTaskRecord> ReconcileAsync(TransferTaskRef task, CancellationToken cancellationToken)
    {
        long epoch = AdmissionEpoch();
        var record = await ReadRequiredAsync(task, cancellationToken).ConfigureAwait(false);
        cancellationToken.ThrowIfCancellationRequested();
        RequirePlan(record);
        if (IsTerminal(record)) return record;
        if (record.Checkpoint.Phase == TransferPhase.Queued && record.Intent != TransferIntent.Cancel)
            throw Safe(ErrorCode.InvalidRequest);
        return await StartExecution(task, ExecutionMode.Reconcile, epoch, cancellationToken).ConfigureAwait(false);
    }

    private Task<TransferTaskRecord> StartExecution(TransferTaskRef task, ExecutionMode mode, long epoch, CancellationToken caller)
    {
        Execution execution;
        lock (_gate)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            caller.ThrowIfCancellationRequested();
            if (epoch != _admissionEpoch) throw Safe(ErrorCode.SourceChanged);
            if (_draining || _active is not null) throw Safe(ErrorCode.Busy);
            execution = new(task, mode, caller);
            _active = execution;
        }
        // Providers may return synchronously completed tasks and do native work before their first await.
        // The registration is visible to drain first; never run that work on an application's state gate/UI thread.
        _ = Task.Run(() => ExecuteRegisteredAsync(execution), CancellationToken.None);
        return execution.Completion.Task;
    }

    private async Task ExecuteRegisteredAsync(Execution execution)
    {
        TransferTaskRecord? result = null;
        Exception? failure = null;
        try { result = await ExecuteAsync(execution).ConfigureAwait(false); }
        catch (Exception error)
        {
            failure = Normalize(error);
            if (failure is FilewayException { Error.Code: ErrorCode.Canceled })
            {
                // Drain can cancel a registered worker before it acquires its lock/claim. Finish that
                // already-requested cancellation locally instead of leaving a never-started Cancel task queued.
                try
                {
                    var canceled = execution.Mode == ExecutionMode.Fresh
                        ? await SetCancelAsync(execution.Task, CancellationToken.None).ConfigureAwait(false)
                        : await ReadRequiredAsync(execution.Task, CancellationToken.None).ConfigureAwait(false);
                    if (IsTerminal(canceled)) { result = canceled; failure = null; }
                    else if (canceled.Intent == TransferIntent.Cancel)
                    {
                        var cleanup = new Execution(execution.Task, ExecutionMode.Reconcile, CancellationToken.None);
                        try { result = await ExecuteAsync(cleanup).ConfigureAwait(false); failure = null; }
                        finally { cleanup.Stop.Dispose(); }
                    }
                }
                catch (Exception cleanupError) { failure = Normalize(cleanupError); }
            }
        }
        finally
        {
            lock (_gate) if (ReferenceEquals(_active, execution)) _active = null;
            execution.Stop.Dispose();
        }
        // All owned native handles, requests and resource capabilities have drained before this acknowledgement.
        if (failure is not null) execution.Completion.TrySetException(failure);
        else execution.Completion.TrySetResult(result!);
    }

    private async Task<TransferTaskRecord> ExecuteAsync(Execution execution)
    {
        await using var ownership = await AcquireLockAsync(execution.Task, execution.Stop.Token).ConfigureAwait(false);
        var record = await ReadRequiredAsync(execution.Task, execution.Stop.Token).ConfigureAwait(false);
        RequirePlan(record);
        if (IsTerminal(record)) return record;
        if (execution.Mode == ExecutionMode.Fresh) RequireFresh(record);
        record = await ClaimAsync(record, execution.Mode != ExecutionMode.Fresh, execution.Stop.Token).ConfigureAwait(false);
        if (record.Run is null || IsTerminal(record)) return record;
        var state = new RunState(execution, record);
        return execution.Mode == ExecutionMode.Fresh
            ? await DownloadAsync(state).ConfigureAwait(false)
            : await ReconcileLocalAsync(state).ConfigureAwait(false);
    }

    private async Task<TransferTaskRecord> DownloadAsync(RunState state)
    {
        IDownloadDestination? destination = null;
        IDownloadLease? lease = null;
        try
        {
            await CheckIntentAsync(state).ConfigureAwait(false);
            var metadata = await ControlAsync(token => _sources.PrepareDownloadAsync(state.Source, token),
                state.Execution.Stop.Token).ConfigureAwait(false);
            RequireRemote(state, metadata.Resource);
            if (metadata.IsDirectory || metadata.Size < 0) throw Safe(ErrorCode.ProtocolMismatch);
            var snapshot = new TransferSourceSnapshot(metadata.Size, metadata.Modified?.ToUniversalTime());
            await SaveAsync(state, state.Record.Checkpoint with { Source = snapshot }, release: false).ConfigureAwait(false);
            await CheckIntentAsync(state).ConfigureAwait(false);
            destination = await AcquireFreshDestinationAsync(state).ConfigureAwait(false);
            RequireFreshDestination(state, destination);
            state.DestinationAccepted = true;
            await SaveAsync(state, state.Record.Checkpoint with
            { Phase = TransferPhase.Running, Files = destination.Files, ConfirmedOffset = 0 }, release: false).ConfigureAwait(false);
            await CheckIntentAsync(state).ConfigureAwait(false);
            lease = await AcquireOwnedAsync(token => _sources.AcquireDownloadAsync(new(state.Source, snapshot.Length), token),
                state.Execution.Stop.Token).ConfigureAwait(false);
            RequireRemote(state, lease.Resource);
            if (lease.SourceVersion is not null) throw Safe(ErrorCode.CapabilityMissing);
            if (lease.Length != snapshot.Length) throw Safe(ErrorCode.SourceChanged);
            RequireAddress(lease.Address);
            await ReceiveAsync(state, destination, lease).ConfigureAwait(false);
            await CheckIntentAsync(state).ConfigureAwait(false);
            long flushed = await destination.FlushToDiskAsync(state.Execution.Stop.Token).ConfigureAwait(false);
            if (flushed != snapshot.Length || destination.Length != snapshot.Length) throw Safe(ErrorCode.SourceChanged);
            var local = await destination.CalculateSha256Async(state.Execution.Stop.Token).ConfigureAwait(false);
            if (local.Length != snapshot.Length) throw Safe(ErrorCode.SourceChanged);
            string localHash = RequireHash(local.Sha256);
            await SaveAsync(state, state.Record.Checkpoint with
            { Phase = TransferPhase.Verifying, ConfirmedOffset = flushed, LocalSha256 = localHash }, release: false).ConfigureAwait(false);
            await CheckIntentAsync(state).ConfigureAwait(false);
            // Do not apply the short control budget to the provider's existing sixteen-minute checksum read.
            var server = await _sources.CalculateDownloadChecksumAsync(new(state.Source, snapshot.Length),
                state.Execution.Stop.Token).ConfigureAwait(false);
            RequireRemote(state, server.Resource);
            if (server.Algorithm != ChecksumAlgorithm.Sha256) throw Safe(ErrorCode.ProtocolMismatch);
            if (server.Size != snapshot.Length || server.Modified?.ToUniversalTime() != snapshot.Modified)
                throw Safe(ErrorCode.SourceChanged);
            string serverHash = RequireHash(server.Digest);
            if (!StringComparer.Ordinal.Equals(localHash, serverHash)) throw Safe(ErrorCode.SourceChanged);
            await SaveAsync(state, state.Record.Checkpoint with { ServerSha256 = serverHash }, release: false).ConfigureAwait(false);
            await CheckIntentAsync(state).ConfigureAwait(false);
            await SaveAsync(state, state.Record.Checkpoint with
            { Phase = TransferPhase.Publishing, Uncertainty = TransferUncertainty.LocalPublish }, release: false).ConfigureAwait(false);
            await CheckIntentAsync(state).ConfigureAwait(false);
            state.PublishAttempted = true;
            string identity = await destination.PublishNoReplaceAsync(state.Execution.Stop.Token).ConfigureAwait(false);
            RequirePublication(state, destination, identity);
            await CompleteAsync(state, destination, identity).ConfigureAwait(false);
            return state.Record;
        }
        catch (Exception error) when (error is FilewayException or OperationCanceledException or IOException or
            UnauthorizedAccessException or HttpRequestException or InvalidOperationException)
        {
            var result = await FinishFailureAsync(state, destination, Normalize(error)).ConfigureAwait(false);
            if (state.RejectedDestinationReleaseFailure is { } releaseFailure) throw releaseFailure;
            return result;
        }
        finally
        {
            try { if (lease is not null) await lease.DisposeAsync().ConfigureAwait(false); }
            finally { if (destination is not null) await destination.DisposeAsync().ConfigureAwait(false); }
        }
    }

    private async Task<TransferTaskRecord> ReconcileLocalAsync(RunState state)
    {
        IDownloadDestination? destination = null;
        try
        {
            var checkpoint = state.Record.Checkpoint;
            if (checkpoint.Files is not { PartialPath: not null, PartialIdentity: not null } files)
            {
                if (checkpoint.Uncertainty != TransferUncertainty.None) return await UnknownAsync(state).ConfigureAwait(false);
                await SaveAsync(state, checkpoint with
                { Phase = state.Record.Intent == TransferIntent.Cancel ? TransferPhase.Canceled : TransferPhase.Failed,
                    Error = state.Record.Intent == TransferIntent.Cancel ? null : Safe(ErrorCode.SourceChanged).Error }, release: true).ConfigureAwait(false);
                return state.Record;
            }
            // This operation neither creates a file nor makes any request to the remote source.
            destination = await AcquireOwnedAsync(token => _destinations.OpenOwnedAsync(state.Record.Plan.LocalPath, files, token),
                state.Execution.Stop.Token).ConfigureAwait(false);
            RequireOwnedEvidence(state, destination);
            if (destination.IsPublished)
            {
                if (checkpoint.Source is null || checkpoint.ConfirmedOffset != checkpoint.Source.Length ||
                    checkpoint.LocalSha256 is null || checkpoint.ServerSha256 is null ||
                    RequireHash(checkpoint.LocalSha256) != RequireHash(checkpoint.ServerSha256))
                    return await UnknownAsync(state).ConfigureAwait(false);
                var local = await destination.CalculateSha256Async(state.Execution.Stop.Token).ConfigureAwait(false);
                if (local.Length != checkpoint.Source.Length || RequireHash(local.Sha256) != checkpoint.LocalSha256)
                    return await UnknownAsync(state).ConfigureAwait(false);
                string identity = destination.Files.PublishedFileIdentity ?? throw Safe(ErrorCode.OutcomeUnknown);
                RequirePublication(state, destination, identity);
                await CompleteAsync(state, destination, identity).ConfigureAwait(false);
                return state.Record;
            }
            await destination.DeleteOwnedPartialAsync(CancellationToken.None).ConfigureAwait(false);
            await SaveAsync(state, state.Record.Checkpoint with
            { Phase = state.Record.Intent == TransferIntent.Cancel ? TransferPhase.Canceled : TransferPhase.Failed,
                Uncertainty = TransferUncertainty.None,
                Error = state.Record.Intent == TransferIntent.Cancel ? null : Safe(ErrorCode.SourceChanged).Error }, release: true).ConfigureAwait(false);
            return state.Record;
        }
        catch (Exception error) when (error is FilewayException or OperationCanceledException or IOException or
            UnauthorizedAccessException or InvalidOperationException)
        {
            if (state.PersistenceFailed) throw Safe(ErrorCode.LocalStorageUnavailable);
            // Missing or replaced files never grant permission to delete an entry or replay a publication.
            return await UnknownAsync(state, Normalize(error).Error).ConfigureAwait(false);
        }
        finally { if (destination is not null) await destination.DisposeAsync().ConfigureAwait(false); }
    }

    private Task CompleteAsync(RunState state, IDownloadDestination destination, string identity) =>
        SaveAsync(state, state.Record.Checkpoint with { Phase = TransferPhase.Completed,
            Files = destination.Files with { PublishedFileIdentity = identity },
            Uncertainty = TransferUncertainty.None, Error = null }, release: true);

    private async Task<TransferTaskRecord> FinishFailureAsync(RunState state, IDownloadDestination? destination, FilewayException failure)
    {
        if (failure.Error.Code == ErrorCode.Canceled || state.RejectedDestinationWasCanceled)
        {
            // Caller cancellation remains durable even when an unconfirmed rejected-file
            // cleanup takes precedence over its Canceled result.
            var canceled = await SetCancelAsync(state.Run.Task, CancellationToken.None).ConfigureAwait(false);
            if (canceled.Run != state.Run) throw Safe(ErrorCode.Conflict);
            state.Record = canceled;
        }
        if (failure.Error.Code == ErrorCode.OutcomeUnknown || destination is not null && !state.DestinationAccepted)
        {
            if (state.PersistenceFailed) throw Safe(ErrorCode.LocalStorageUnavailable);
            return await UnknownAsync(state, failure.Error).ConfigureAwait(false);
        }
        if (destination is not null && destination.IsPublished || state.PublishAttempted && failure.Error.Code != ErrorCode.Conflict)
        {
            // Even a cancellation or failed acknowledgement after rename cannot erase the final file.
            if (state.PersistenceFailed) throw Safe(ErrorCode.LocalStorageUnavailable);
            return await UnknownAsync(state, failure.Error).ConfigureAwait(false);
        }
        if (destination is not null && state.DestinationAccepted)
        {
            try { await destination.DeleteOwnedPartialAsync(CancellationToken.None).ConfigureAwait(false); }
            catch (Exception error) when (error is FilewayException or IOException or UnauthorizedAccessException or InvalidOperationException)
            {
                if (state.PersistenceFailed) throw Safe(ErrorCode.LocalStorageUnavailable);
                return await UnknownAsync(state, Normalize(error).Error).ConfigureAwait(false);
            }
        }
        if (state.PersistenceFailed) throw Safe(ErrorCode.LocalStorageUnavailable);
        var fresh = await ReadRequiredAsync(state.Run.Task, CancellationToken.None).ConfigureAwait(false);
        if (fresh.Run != state.Run || fresh.Plan != state.Record.Plan) throw Safe(ErrorCode.Conflict);
        state.Record = fresh;
        if (fresh.Checkpoint.Phase == TransferPhase.Publishing)
            await SaveAsync(state, fresh.Checkpoint with { Phase = TransferPhase.Reconciling,
                Uncertainty = TransferUncertainty.None }, release: false).ConfigureAwait(false);
        var phase = state.Record.Intent == TransferIntent.Cancel ? TransferPhase.Canceled : failure.Error.Code switch
        {
            ErrorCode.Conflict => TransferPhase.Conflict,
            ErrorCode.SourceChanged => TransferPhase.SourceChanged,
            _ => TransferPhase.Failed,
        };
        await SaveAsync(state, state.Record.Checkpoint with { Phase = phase,
            Uncertainty = TransferUncertainty.None, Error = phase == TransferPhase.Canceled ? null : failure.Error }, release: true).ConfigureAwait(false);
        return state.Record;
    }

    private async Task<TransferTaskRecord> UnknownAsync(RunState state, ErrorInfo? error = null)
    {
        await SaveAsync(state, state.Record.Checkpoint with { Phase = TransferPhase.OutcomeUnknown,
            Uncertainty = TransferUncertainty.LocalPublish, Error = error ?? Safe(ErrorCode.OutcomeUnknown).Error }, release: true).ConfigureAwait(false);
        return state.Record;
    }

    public Task StopAndDrainAsync(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        EnsureAvailable();
        return BeginDrain();
    }

    private Task BeginDrain()
    {
        TaskCompletionSource completion;
        Execution? execution;
        lock (_gate)
        {
            if (_drain is { IsCompleted: false }) return _drain;
            _admissionEpoch = checked(_admissionEpoch + 1);
            _draining = true;
            completion = new(TaskCreationOptions.RunContinuationsAsynchronously);
            _drain = completion.Task;
            execution = _active;
        }
        _ = DrainCoreAsync(execution, completion);
        return completion.Task;
    }

    private async Task DrainCoreAsync(Execution? execution, TaskCompletionSource completion)
    {
        FilewayException? failure = null;
        try
        {
            if (execution is not null)
            {
                try { _ = await SetCancelAsync(execution.Task, CancellationToken.None).ConfigureAwait(false); }
                catch (Exception error) { failure = Normalize(error); }
                var stopFailure = await StopAsync(execution).ConfigureAwait(false);
                failure ??= stopFailure;
                try { _ = await execution.Completion.Task.ConfigureAwait(false); }
                catch (Exception error) { failure ??= Normalize(error); }
            }
        }
        catch (Exception error) { failure ??= Normalize(error); }
        finally
        {
            lock (_gate)
            {
                // Reopen admission and settle this exact single flight atomically. Otherwise a new
                // worker could register while BeginDrain still returned the old, incomplete drain task.
                _draining = false;
                if (failure is null) completion.TrySetResult();
                else completion.TrySetException(failure);
            }
        }
    }

    public ValueTask DisposeAsync()
    {
        TaskCompletionSource completion;
        lock (_gate)
        {
            if (_disposeCompletion is not null) return new(_disposeCompletion.Task);
            _disposed = true;
            _disposeCompletion = completion = new(TaskCreationOptions.RunContinuationsAsynchronously);
        }
        _ = DisposeCoreAsync(completion);
        return new(completion.Task);
    }

    private async Task DisposeCoreAsync(TaskCompletionSource completion)
    {
        FilewayException? failure = null;
        try { await BeginDrain().ConfigureAwait(false); }
        catch (Exception error) { failure = Normalize(error); }
        finally
        {
            try { _http.Dispose(); }
            catch (Exception error) { failure ??= Normalize(error); }
        }
        if (failure is null) completion.TrySetResult();
        else completion.TrySetException(failure);
    }

    private void EnsureAvailable() { lock (_gate) ObjectDisposedException.ThrowIf(_disposed, this); }
    private long AdmissionEpoch()
    {
        lock (_gate)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            if (_draining) throw Safe(ErrorCode.Busy);
            return _admissionEpoch;
        }
    }
    private static async Task<FilewayException?> StopAsync(Execution execution)
    {
        try { await execution.Stop.CancelAsync().ConfigureAwait(false); return null; }
        catch (ObjectDisposedException) { return null; }
        catch (Exception error) { return Normalize(error); }
    }

    private void QueueProgress(RunState state, bool force = false)
    {
        if (!force && Stopwatch.GetElapsedTime(state.Execution.LastProgress) < _options.ProgressInterval) return;
        state.Execution.LastProgress = Stopwatch.GetTimestamp();
        if (ProgressChanged is null) return;
        var progress = new DownloadTaskProgress(state.Run, Interlocked.Read(ref state.Execution.ReceivedBytes),
            state.Record.Checkpoint.ConfirmedOffset, state.Record.Checkpoint.Source?.Length ?? 0,
            Stopwatch.GetElapsedTime(state.Execution.Started));
        Interlocked.Exchange(ref state.Execution.PendingProgress, progress);
        if (Interlocked.CompareExchange(ref state.Execution.PublishingProgress, 1, 0) != 0) return;
        _ = Task.Run(() => PublishProgress(state.Execution));
    }

    private void PublishProgress(Execution execution)
    {
        while (true)
        {
            var value = Interlocked.Exchange(ref execution.PendingProgress, null);
            if (value is not null && ProgressChanged is { } handlers)
                foreach (EventHandler<DownloadTaskProgress> handler in handlers.GetInvocationList())
                { try { handler(this, value); } catch (Exception) { } }
            Interlocked.Exchange(ref execution.PublishingProgress, 0);
            if (Volatile.Read(ref execution.PendingProgress) is null ||
                Interlocked.CompareExchange(ref execution.PublishingProgress, 1, 0) != 0) return;
        }
    }

    private enum ExecutionMode { Fresh, Reconcile }
    private sealed class Execution(TransferTaskRef task, ExecutionMode mode, CancellationToken caller)
    {
        internal TransferTaskRef Task { get; } = task;
        internal ExecutionMode Mode { get; } = mode;
        internal CancellationTokenSource Stop { get; } = CancellationTokenSource.CreateLinkedTokenSource(caller);
        internal TaskCompletionSource<TransferTaskRecord> Completion { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal long Started { get; } = Stopwatch.GetTimestamp();
        internal long LastProgress;
        internal long ReceivedBytes;
        internal DownloadTaskProgress? PendingProgress;
        internal int PublishingProgress;
    }
    private sealed class RunState(Execution execution, TransferTaskRecord record)
    {
        internal Execution Execution { get; } = execution;
        internal TransferTaskRecord Record { get; set; } = record;
        internal TransferRunToken Run { get; } = record.Run ?? throw Safe(ErrorCode.Conflict);
        internal RemoteResourceRef Source { get; } = new(record.Plan.Remote.Account, record.Plan.Remote.WirePath,
            record.Plan.Remote.DisplayName, record.Plan.Remote.DisplayPath);
        internal bool DestinationAccepted;
        internal bool PublishAttempted;
        internal bool PersistenceFailed;
        internal bool RejectedDestinationWasCanceled;
        internal FilewayException? RejectedDestinationReleaseFailure;
    }
}
