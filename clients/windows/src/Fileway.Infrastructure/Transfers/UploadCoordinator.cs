using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;

namespace Fileway.Infrastructure.Transfers;

public sealed record UploadCoordinatorOptions
{
    public int MaximumConcurrentUploads { get; init; } = 3;
    public TimeSpan PersistenceTimeout { get; init; } = TimeSpan.FromSeconds(30);
}

/// <summary>
/// Explicit, account-bound scheduling of existing file plans. Owns the injected runner's lifetime;
/// neither construction nor activation discovers tasks or enables automatic restart.
/// </summary>
public sealed partial class UploadCoordinator : IUploadCoordinator
{
    private readonly ITransferTaskStore _store;
    private readonly IFileUploadRunner _runner;
    private readonly UploadCoordinatorOptions _options;
    private readonly object _gate = new();
    private readonly SemaphoreSlim _commands = new(1, 1);
    private readonly Dictionary<TransferTaskRef, Work> _work = [];
    private readonly LinkedList<Work> _pending = [];
    private readonly Dictionary<TransferTaskRef, UploadQueueUpdate> _updates = [];
    private readonly Dictionary<TransferTaskRef, UploadQueueUpdate> _lastUpdates = [];
    private UploadActivationToken? _activation;
    private bool _admission;
    private bool _commandActive;
    private bool _publishing;
    private bool _disposed;
    private TaskCompletionSource? _disposeCompletion;

    public UploadCoordinator(ITransferTaskStore store, IFileUploadRunner runner, UploadCoordinatorOptions? options = null)
    {
        ArgumentNullException.ThrowIfNull(store);
        ArgumentNullException.ThrowIfNull(runner);
        _options = options ?? new();
        if (_options.MaximumConcurrentUploads is < 1 or > 3 || _options.PersistenceTimeout <= TimeSpan.Zero ||
            _options.PersistenceTimeout > TimeSpan.FromMinutes(5)) throw Safe(ErrorCode.InvalidRequest);
        _store = store;
        _runner = runner;
        _runner.ProgressChanged += Runner_ProgressChanged;
    }

    public event EventHandler<UploadQueueUpdate>? TaskChanged;

    public async Task<UploadActivationToken> ActivateAsync(ConnectionSession session, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(session);
        TransferTaskValidation.Account(session.Account);
        if (session.Id == Guid.Empty) throw Safe(ErrorCode.InvalidRequest);
        await BeginCommandAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await DrainAsync().ConfigureAwait(false);
            var activation = new UploadActivationToken(session.Account, session.Id, Guid.NewGuid());
            lock (_gate)
            {
                ObjectDisposedException.ThrowIf(_disposed, this);
                _activation = activation;
                _admission = true;
            }
            return activation;
        }
        finally { EndCommand(); }
    }

    public Task<UploadTaskSnapshot> EnqueueAsync(UploadActivationToken activation, TransferTaskRef task,
        CancellationToken cancellationToken) => AdmitAsync(activation, task, resume: false, cancellationToken);

    public Task<UploadTaskSnapshot> ResumeAsync(UploadActivationToken activation, TransferTaskRef task,
        CancellationToken cancellationToken) => AdmitAsync(activation, task, resume: true, cancellationToken);

    private async Task<UploadTaskSnapshot> AdmitAsync(UploadActivationToken activation, TransferTaskRef task,
        bool resume, CancellationToken cancellationToken)
    {
        await BeginCommandAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            Validate(activation, task);
            var record = await ReadAsync(task, cancellationToken).ConfigureAwait(false);
            if (!IsTerminal(record))
            {
                EnsureRunnable(record);
                if (resume) record = await SetIntentAsync(record, TransferIntent.Run, cancellationToken).ConfigureAwait(false);
                else if (record.Intent != TransferIntent.Run) throw Safe(ErrorCode.Conflict);
                AddWork(activation, record, cancel: false);
            }
            var snapshot = Snapshot(record);
            Publish(new(activation, snapshot));
            return snapshot;
        }
        finally { EndCommand(); }
    }

    public async Task<UploadTaskSnapshot> PauseAsync(UploadActivationToken activation, TransferTaskRef task,
        CancellationToken cancellationToken)
    {
        await BeginCommandAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            Validate(activation, task);
            var record = await ReadAsync(task, cancellationToken).ConfigureAwait(false);
            if (!IsTerminal(record))
            {
                record = await SetIntentAsync(record, TransferIntent.Pause, cancellationToken).ConfigureAwait(false);
            }
            // A terminal ACK can precede source/lease cleanup; it does not prove local release.
            await StopTaskAsync(task).ConfigureAwait(false);
            record = await ReadAsync(task, CancellationToken.None).ConfigureAwait(false);
            var snapshot = Snapshot(record);
            Publish(new(activation, snapshot));
            return snapshot;
        }
        finally { EndCommand(); }
    }

    public async Task<UploadTaskSnapshot> CancelAsync(UploadActivationToken activation, TransferTaskRef task,
        CancellationToken cancellationToken)
    {
        Work? cancellation = null;
        UploadTaskSnapshot? snapshot = null;
        await BeginCommandAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            Validate(activation, task);
            var record = await ReadAsync(task, cancellationToken).ConfigureAwait(false);
            if (!IsTerminal(record))
            {
                record = await SetIntentAsync(record, TransferIntent.Cancel, cancellationToken).ConfigureAwait(false);
                lock (_gate) _work.TryGetValue(task, out cancellation);
                if (cancellation is not { Cancel: true })
                {
                    await StopTaskAsync(task).ConfigureAwait(false);
                    record = await ReadAsync(task, CancellationToken.None).ConfigureAwait(false);
                    if (!IsTerminal(record)) cancellation = AddWork(activation, record, cancel: true);
                    else cancellation = null;
                }
            }
            else
            {
                await StopTaskAsync(task).ConfigureAwait(false);
                record = await ReadAsync(task, CancellationToken.None).ConfigureAwait(false);
            }
            snapshot = Snapshot(record);
            Publish(new(activation, snapshot));
        }
        finally { EndCommand(); }
        if (cancellation is null) return snapshot!;
        // Once Cancel is durable, caller cancellation does not abandon its local drain or reverse the intent.
        await cancellation.Completion.Task.ConfigureAwait(false);
        if (cancellation.Failure is not null) throw cancellation.Failure;
        return Snapshot(cancellation.Record);
    }

    public async Task StopAndDrainAsync(UploadActivationToken activation, CancellationToken cancellationToken)
    {
        await BeginCommandAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            Validate(activation);
            await DrainAsync().ConfigureAwait(false);
        }
        finally { EndCommand(); }
    }

    private Work AddWork(UploadActivationToken activation, TransferTaskRecord record, bool cancel)
    {
        lock (_gate)
        {
            if (_work.TryGetValue(record.Plan.Reference, out var existing)) return existing;
            var work = new Work(activation, record, cancel, TransferTaskValidation.TargetKey(record.Plan));
            _work.Add(work.Task, work);
            work.Node = _pending.AddLast(work);
            return work;
        }
    }

    private async Task BeginCommandAsync(CancellationToken cancellationToken)
    {
        lock (_gate) ObjectDisposedException.ThrowIf(_disposed, this);
        await _commands.WaitAsync(cancellationToken).ConfigureAwait(false);
        lock (_gate)
        {
            if (_disposed) { _commands.Release(); throw new ObjectDisposedException(nameof(UploadCoordinator)); }
            _commandActive = true;
        }
    }

    private void EndCommand()
    {
        lock (_gate) _commandActive = false;
        _commands.Release();
        Dispatch();
    }

    private void Validate(UploadActivationToken activation, TransferTaskRef? task = null)
    {
        lock (_gate)
        {
            if (!_admission || _activation != activation) throw Safe(ErrorCode.SourceChanged);
            if (task is { } reference && (reference.Account != activation.Account || reference.Id == Guid.Empty))
                throw Safe(ErrorCode.InvalidRequest);
        }
    }

    private void Dispatch()
    {
        List<Work> start = [];
        lock (_gate)
        {
            if (!_admission || _commandActive || _disposed) return;
            int count = _work.Values.Count(item => item.Active);
            var targets = _work.Values.Where(item => item.Active).Select(item => item.Target).ToHashSet(StringComparer.Ordinal);
            for (var node = _pending.First; node is not null && count < _options.MaximumConcurrentUploads;)
            {
                var next = node.Next;
                var work = node.Value;
                if (targets.Add(work.Target))
                {
                    _pending.Remove(node);
                    work.Node = null;
                    work.Active = true;
                    start.Add(work);
                    count++;
                }
                node = next;
            }
        }
        foreach (var work in start) _ = ExecuteAsync(work);
    }

    private async Task ExecuteAsync(Work work)
    {
        try
        {
            var record = await ReadAsync(work.Task, work.Stop.Token).ConfigureAwait(false);
            work.Record = record;
            if (!IsTerminal(record) && (work.Cancel ? record.Intent == TransferIntent.Cancel : record.Intent == TransferIntent.Run))
            {
                var result = work.Cancel
                    ? await _runner.CancelAsync(work.Task, work.Stop.Token).ConfigureAwait(false)
                    : await _runner.RunAsync(work.Task, NeedsRecovery(record), work.Stop.Token).ConfigureAwait(false);
                if (result.Plan.Reference != work.Task || result.Plan.Direction != TransferDirection.Upload) throw Safe(ErrorCode.ProtocolMismatch);
                work.Record = result;
            }
        }
        catch (Exception error)
        {
            work.Failure = Normalize(error);
            try { work.Record = await ReadAsync(work.Task, CancellationToken.None).ConfigureAwait(false); }
            catch (FilewayException) { /* Keep the last observed record; never manufacture a checkpoint. */ }
        }
        finally
        {
            lock (_gate)
            {
                if (_work.TryGetValue(work.Task, out var current) && ReferenceEquals(current, work)) _work.Remove(work.Task);
                work.Active = false;
            }
            work.Stop.Dispose();
            work.Completion.TrySetResult();
        }
        var snapshot = Snapshot(work.Record);
        if (work.Failure is not null)
        {
            lock (_gate)
            {
                // A failed fresh read cannot discard a newer checkpoint already observed for this
                // activation. Attach the local failure to that safe evidence without inventing a phase.
                if (work.ObservedSnapshot.Revision > snapshot.Revision) snapshot = work.ObservedSnapshot;
            }
            snapshot = snapshot with { Error = work.Failure.Error };
        }
        PublishFinished(work, snapshot);
        Dispatch();
    }

    private static bool NeedsRecovery(TransferTaskRecord record) => record.Run is not null ||
        record.Checkpoint.Uncertainty != TransferUncertainty.None ||
        record.Checkpoint.Phase is TransferPhase.Reconciling or TransferPhase.OutcomeUnknown;

    private static bool IsTerminal(TransferTaskRecord record) => record.Checkpoint.Phase is
        TransferPhase.Completed or TransferPhase.Canceled or TransferPhase.Skipped;

    private static void EnsureRunnable(TransferTaskRecord record)
    {
        if (record.Intent == TransferIntent.Cancel) throw Safe(ErrorCode.Conflict);
        if (!NeedsRecovery(record) && record.Checkpoint.Phase is not (TransferPhase.Queued or TransferPhase.Paused or
            TransferPhase.WaitingNetwork or TransferPhase.WaitingAuthentication or TransferPhase.Failed))
            throw Safe(record.Checkpoint.Phase == TransferPhase.SourceChanged ? ErrorCode.SourceChanged : ErrorCode.Conflict);
    }

    private sealed class Work(UploadActivationToken activation, TransferTaskRecord record, bool cancel, string target)
    {
        internal UploadActivationToken Activation { get; } = activation;
        internal TransferTaskRef Task { get; } = record.Plan.Reference;
        internal bool Cancel { get; } = cancel;
        internal string Target { get; } = target;
        internal CancellationTokenSource Stop { get; } = new();
        internal TaskCompletionSource Completion { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal TransferTaskRecord Record { get; set; } = record;
        internal UploadTaskSnapshot ObservedSnapshot { get; set; } = Snapshot(record);
        internal FilewayException? Failure { get; set; }
        internal LinkedListNode<Work>? Node { get; set; }
        internal bool Active { get; set; }
        internal UploadTaskProgress? PendingProgress { get; set; }
        internal bool ReadingProgress { get; set; }
    }
}
