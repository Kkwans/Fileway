using System.Diagnostics;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;

namespace Fileway.Infrastructure.Transfers;

/// <summary>Bounded transfer buffers and per-operation budgets; no deadline limits an entire file.</summary>
public sealed record FileUploadRunnerOptions
{
    public int ChunkSizeBytes { get; init; } = 2 * 1024 * 1024;
    public TimeSpan RequestIdleTimeout { get; init; } = TimeSpan.FromSeconds(30);
    public TimeSpan ProgressInterval { get; init; } = TimeSpan.FromMilliseconds(200);
    public TimeSpan ControlCallTimeout { get; init; } = TimeSpan.FromSeconds(30);
    public TimeSpan PersistenceTimeout { get; init; } = TimeSpan.FromSeconds(30);
}

/// <summary>Explicit, single-file TUS execution. Construction never discovers or starts transfers.</summary>
public sealed partial class FileUploadRunner : IFileUploadRunner
{
    private readonly ITransferTaskStore _store;
    private readonly IUploadLeaseProvider _leases;
    private readonly IUploadSourceProvider _sources;
    private readonly ITransferExecutionLockProvider _executionLocks;
    private readonly IFileRepository _files;
    private readonly FileUploadRunnerOptions _options;
    private readonly HttpClient _http;
    private readonly object _gate = new();
    private readonly Dictionary<TransferTaskRef, Execution> _active = [];
    private TaskCompletionSource? _disposeCompletion;
    private bool _disposed;

    public FileUploadRunner(ITransferTaskStore store, IUploadLeaseProvider leases, IUploadSourceProvider sources,
        ITransferExecutionLockProvider executionLocks, IFileRepository files, HttpMessageHandler? handler = null,
        FileUploadRunnerOptions? options = null)
    {
        ArgumentNullException.ThrowIfNull(store);
        ArgumentNullException.ThrowIfNull(leases);
        ArgumentNullException.ThrowIfNull(sources);
        ArgumentNullException.ThrowIfNull(executionLocks);
        ArgumentNullException.ThrowIfNull(files);
        _options = options ?? new();
        if (_options.ChunkSizeBytes is < 1 or > 2 * 1024 * 1024 ||
            _options.RequestIdleTimeout <= TimeSpan.Zero || _options.RequestIdleTimeout > TimeSpan.FromHours(1) ||
            _options.ProgressInterval < TimeSpan.FromMilliseconds(200) || _options.ProgressInterval > TimeSpan.FromSeconds(30) ||
            _options.ControlCallTimeout <= TimeSpan.Zero || _options.ControlCallTimeout > TimeSpan.FromMinutes(5) ||
            _options.PersistenceTimeout <= TimeSpan.Zero || _options.PersistenceTimeout > TimeSpan.FromMinutes(5))
            throw Safe(ErrorCode.InvalidRequest);
        _store = store;
        _leases = leases;
        _sources = sources;
        _executionLocks = executionLocks;
        _files = files;
        _http = new(handler ?? new SocketsHttpHandler { AllowAutoRedirect = false, UseProxy = false,
            UseCookies = false, ConnectTimeout = _options.ControlCallTimeout }, disposeHandler: true)
        { Timeout = Timeout.InfiniteTimeSpan };
    }

    public event EventHandler<UploadTaskProgress>? ProgressChanged;

    public async Task<TransferTaskRecord> RunAsync(TransferTaskRef task, bool recovery, CancellationToken cancellationToken)
    {
        EnsureAvailable();
        var record = await ReadRequiredAsync(task, cancellationToken).ConfigureAwait(false);
        if (record.Plan.Direction != TransferDirection.Upload) throw Safe(ErrorCode.InvalidRequest);
        if (IsTerminal(record)) return record;
        if (record.Intent == TransferIntent.Pause)
            record = await SetIntentAsync(task, TransferIntent.Run, cancellationToken).ConfigureAwait(false);
        return await StartExecution(task, recovery, record.Intent == TransferIntent.Cancel, cancellationToken).ConfigureAwait(false);
    }

    public async Task<TransferTaskRecord> PauseAsync(TransferTaskRef task, CancellationToken cancellationToken)
    {
        EnsureAvailable();
        var record = await SetIntentAsync(task, TransferIntent.Pause, cancellationToken).ConfigureAwait(false);
        if (IsTerminal(record)) return record;
        await StopLocalAsync(task).ConfigureAwait(false);
        // The OS lock also proves another instance has drained. A busy lock leaves the durable intent intact.
        await using var ownership = await AcquireLockAsync(task, cancellationToken).ConfigureAwait(false);
        return await ReadRequiredAsync(task, CancellationToken.None).ConfigureAwait(false);
    }

    public async Task<TransferTaskRecord> CancelAsync(TransferTaskRef task, CancellationToken cancellationToken)
    {
        EnsureAvailable();
        var record = await SetIntentAsync(task, TransferIntent.Cancel, cancellationToken).ConfigureAwait(false);
        if (IsTerminal(record)) return record;
        await StopLocalAsync(task).ConfigureAwait(false);
        record = await ReadRequiredAsync(task, CancellationToken.None).ConfigureAwait(false);
        if (IsTerminal(record)) return record;
        return await StartExecution(task, recovery: true, cancel: true, cancellationToken).ConfigureAwait(false);
    }

    private Task<TransferTaskRecord> StartExecution(TransferTaskRef task, bool recovery, bool cancel,
        CancellationToken cancellationToken)
    {
        Execution execution;
        lock (_gate)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            if (_active.ContainsKey(task)) throw Safe(ErrorCode.Busy);
            execution = new(task, cancel, cancellationToken);
            _active.Add(task, execution);
        }
        _ = ExecuteRegisteredAsync(execution, recovery);
        return execution.Completion.Task;
    }

    private async Task ExecuteRegisteredAsync(Execution execution, bool recovery)
    {
        TransferTaskRecord? result = null;
        Exception? failure = null;
        try { result = await ExecuteAsync(execution, recovery).ConfigureAwait(false); }
        catch (Exception error) { failure = Normalize(error); }
        finally
        {
            lock (_gate) _active.Remove(execution.Task);
            execution.Stop.Dispose();
        }
        // Completion means native handles and capabilities have already been released.
        if (failure is not null) execution.Completion.TrySetException(failure);
        else execution.Completion.TrySetResult(result!);
    }

    private async Task<TransferTaskRecord> ExecuteAsync(Execution execution, bool recovery)
    {
        var record = await ReadRequiredAsync(execution.Task, execution.Stop.Token).ConfigureAwait(false);
        if (record.Plan.Direction != TransferDirection.Upload) throw Safe(ErrorCode.InvalidRequest);
        if (IsTerminal(record)) return record;
        await using var ownership = await AcquireLockAsync(execution.Task, execution.Stop.Token).ConfigureAwait(false);
        record = await ReadRequiredAsync(execution.Task, execution.Stop.Token).ConfigureAwait(false);
        if (IsTerminal(record)) return record;
        if (record.Plan.Direction != TransferDirection.Upload) throw Safe(ErrorCode.InvalidRequest);
        if (record.Intent == TransferIntent.Pause && !execution.Cancel) return record;
        if (execution.Cancel || record.Intent == TransferIntent.Cancel)
            return await ExecuteCancellationAsync(execution, record).ConfigureAwait(false);

        IUploadSource? source = null;
        ErrorInfo? sourceError = null;
        try
        {
            source = await OpenSourceAsync(record.Plan.LocalPath, execution.Stop.Token).ConfigureAwait(false);
            var snapshot = source.Snapshot with { Modified = source.Snapshot.Modified?.ToUniversalTime() };
            if (snapshot.Length < 0 || string.IsNullOrWhiteSpace(snapshot.NativeFileIdentity) || snapshot.SourceVersion is not null ||
                !source.Content.CanRead || !source.Content.CanSeek) throw Safe(ErrorCode.SourceChanged);
            await VerifySourceAsync(source, record.Checkpoint.Source ?? snapshot, execution.Stop.Token).ConfigureAwait(false);
            if (record.Checkpoint.Source is not null && record.Checkpoint.Source != snapshot) throw Safe(ErrorCode.SourceChanged);
        }
        catch (Exception error) when (error is FilewayException or IOException or UnauthorizedAccessException or OperationCanceledException)
        { sourceError = Normalize(error, source: true).Error; }

        try
        {
            record = await ClaimAsync(record, recovery, execution.Stop.Token).ConfigureAwait(false);
            if (IsTerminal(record) || record.Run is null) return record;
            var state = new RunState(execution, record);
            try
            {
                if (sourceError is not null) throw new FilewayException(sourceError);
                if (source is null) throw Safe(ErrorCode.SourceChanged);
                if (record.Checkpoint.Source is null)
                    await SaveAsync(state, record.Checkpoint with
                    { Source = source.Snapshot with { Modified = source.Snapshot.Modified?.ToUniversalTime() } }, release: false).ConfigureAwait(false);
                return await UploadAsync(state, source).ConfigureAwait(false);
            }
            catch (Exception error) when (error is FilewayException or IOException or HttpRequestException or OperationCanceledException)
            { return await ReleaseFailureAsync(state, Normalize(error)).ConfigureAwait(false); }
        }
        finally
        {
            if (source is not null) await source.DisposeAsync().ConfigureAwait(false);
        }
    }

    private async Task StopLocalAsync(TransferTaskRef task)
    {
        Execution? execution;
        lock (_gate) _active.TryGetValue(task, out execution);
        if (execution is null) return;
        await StopAsync(execution).ConfigureAwait(false);
        try { _ = await execution.Completion.Task.ConfigureAwait(false); }
        catch (FilewayException error) when (error.Error.Code != ErrorCode.LocalStorageUnavailable) { }
    }

    public ValueTask DisposeAsync()
    {
        Execution[] executions;
        TaskCompletionSource completion;
        lock (_gate)
        {
            if (_disposeCompletion is not null) return new(_disposeCompletion.Task);
            _disposed = true;
            _disposeCompletion = completion = new(TaskCreationOptions.RunContinuationsAsynchronously);
            executions = [.. _active.Values];
        }
        _ = DisposeCoreAsync(executions, completion);
        return new(completion.Task);
    }

    private async Task DisposeCoreAsync(Execution[] executions, TaskCompletionSource completion)
    {
        try
        {
            foreach (var execution in executions)
            {
                try { _ = await SetIntentAsync(execution.Task, TransferIntent.Pause, CancellationToken.None).ConfigureAwait(false); }
                catch (FilewayException) { }
                await StopAsync(execution).ConfigureAwait(false);
            }
            foreach (var execution in executions)
            {
                try { _ = await execution.Completion.Task.ConfigureAwait(false); }
                catch (FilewayException) { }
            }
            _http.Dispose();
            completion.TrySetResult();
        }
        catch (Exception error) { completion.TrySetException(Normalize(error)); }
    }

    private void EnsureAvailable()
    {
        lock (_gate) ObjectDisposedException.ThrowIf(_disposed, this);
    }

    private static async Task StopAsync(Execution execution)
    {
        try { await execution.Stop.CancelAsync().ConfigureAwait(false); }
        catch (ObjectDisposedException) { /* The execution completed between registry lookup and stop. */ }
    }

    private void QueueProgress(RunState state)
    {
        if (ProgressChanged is null) return;
        var progress = new UploadTaskProgress(state.Run, Interlocked.Read(ref state.Execution.SentBytes),
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
                foreach (EventHandler<UploadTaskProgress> handler in handlers.GetInvocationList())
                {
                    try { handler(this, value); }
                    catch (Exception) { /* Presentation subscribers cannot fail execution. */ }
                }
            Interlocked.Exchange(ref execution.PublishingProgress, 0);
            if (Volatile.Read(ref execution.PendingProgress) is null ||
                Interlocked.CompareExchange(ref execution.PublishingProgress, 1, 0) != 0) return;
        }
    }

    private sealed class Execution(TransferTaskRef task, bool cancel, CancellationToken caller)
    {
        internal TransferTaskRef Task { get; } = task;
        internal bool Cancel { get; } = cancel;
        internal CancellationTokenSource Stop { get; } = CancellationTokenSource.CreateLinkedTokenSource(caller);
        internal TaskCompletionSource<TransferTaskRecord> Completion { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal long Started { get; } = Stopwatch.GetTimestamp();
        internal long SentBytes;
        internal UploadTaskProgress? PendingProgress;
        internal int PublishingProgress;
    }

    private sealed class RunState(Execution execution, TransferTaskRecord record)
    {
        internal Execution Execution { get; } = execution;
        internal TransferTaskRecord Record { get; set; } = record;
        internal TransferRunToken Run { get; } = record.Run ?? throw Safe(ErrorCode.Conflict);
        // A new object is deliberately bound by the provider to this run's current authorized account.
        internal RemoteResourceRef Target { get; } = new(record.Plan.Remote.Account, record.Plan.Remote.WirePath,
            record.Plan.Remote.DisplayName, record.Plan.Remote.DisplayPath, record.Plan.Remote.SourceVersion);
    }
}
