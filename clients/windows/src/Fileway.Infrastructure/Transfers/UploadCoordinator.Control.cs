using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Transfers;

public sealed partial class UploadCoordinator
{
    private async Task<TransferTaskRecord> ReadAsync(TransferTaskRef task, CancellationToken cancellationToken)
    {
        try
        {
            using var budget = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            budget.CancelAfter(_options.PersistenceTimeout);
            var record = await _store.GetTransferAsync(task, budget.Token).WaitAsync(budget.Token).ConfigureAwait(false)
                ?? throw Safe(ErrorCode.InvalidRequest);
            if (record.Plan.Reference != task || record.Plan.Direction != TransferDirection.Upload) throw Safe(ErrorCode.InvalidRequest);
            return record;
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested) { throw Safe(ErrorCode.LocalStorageUnavailable); }
        catch (Exception error) { throw Normalize(error); }
    }

    private async Task<TransferTaskRecord> SetIntentAsync(TransferTaskRecord record, TransferIntent intent,
        CancellationToken cancellationToken)
    {
        for (int attempt = 0; attempt < 16; attempt++)
        {
            if (IsTerminal(record) || record.Intent == intent) return record;
            if (record.Intent == TransferIntent.Cancel)
            {
                if (intent == TransferIntent.Run) throw Safe(ErrorCode.Conflict);
                return record;
            }
            try
            {
                using var budget = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                budget.CancelAfter(_options.PersistenceTimeout);
                return await _store.SetTransferIntentAsync(record.Plan.Reference, record.Revision, intent, budget.Token)
                    .WaitAsync(budget.Token).ConfigureAwait(false);
            }
            catch (FilewayException error) when (error.Error.Code == ErrorCode.Conflict)
            { record = await ReadAsync(record.Plan.Reference, cancellationToken).ConfigureAwait(false); }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested) { throw Safe(ErrorCode.LocalStorageUnavailable); }
            catch (Exception error) { throw Normalize(error); }
        }
        throw Safe(ErrorCode.Conflict);
    }

    private async Task StopTaskAsync(TransferTaskRef task)
    {
        Work? work;
        lock (_gate)
        {
            _work.TryGetValue(task, out work);
            if (work?.Node is { } node)
            {
                _pending.Remove(node);
                work.Node = null;
                _work.Remove(task);
            }
        }
        if (work is null) return;
        if (!work.Active)
        {
            try { work.Record = await ReadAsync(task, CancellationToken.None).ConfigureAwait(false); }
            catch (FilewayException error) { work.Failure = error; }
            work.Stop.Dispose();
            work.Completion.TrySetResult();
        }
        else
        {
            FilewayException? stopFailure = null;
            try { await StopAsync(work).ConfigureAwait(false); }
            catch (Exception error) { stopFailure = Normalize(error); }
            await work.Completion.Task.ConfigureAwait(false);
            if (stopFailure is not null) throw stopFailure;
        }
        if (work.Failure?.Error.Code == ErrorCode.LocalStorageUnavailable) throw work.Failure;
    }

    private async Task DrainAsync()
    {
        Work[] active;
        Work[] pending;
        lock (_gate)
        {
            _admission = false;
            _updates.Clear();
            _lastUpdates.Clear();
            active = _work.Values.Where(item => item.Active).ToArray();
            pending = _pending.ToArray();
            _pending.Clear();
            foreach (var work in pending) { work.Node = null; _work.Remove(work.Task); }
        }
        // Every writer gets its own stop attempt. Unrelated pending reads and one failed/slow
        // intent write must not delay initiating cancellation of the other local writers.
        var failures = await Task.WhenAll(active.Select(PauseForDrainAsync)).ConfigureAwait(false);
        FilewayException? failure = failures.FirstOrDefault(error => error is not null);
        foreach (var work in active)
        {
            await work.Completion.Task.ConfigureAwait(false);
            if (work.Failure?.Error.Code != ErrorCode.Canceled) failure ??= work.Failure;
        }
        foreach (var work in pending)
        {
            try { work.Record = await ReadAsync(work.Task, CancellationToken.None).ConfigureAwait(false); }
            catch (FilewayException error) { work.Failure = error; failure ??= error; }
            work.Stop.Dispose();
            work.Completion.TrySetResult();
        }
        if (failure is not null) throw failure;
    }

    private async Task<FilewayException?> PauseForDrainAsync(Work work)
    {
        FilewayException? failure = null;
        try
        {
            var record = await ReadAsync(work.Task, CancellationToken.None).ConfigureAwait(false);
            _ = await SetIntentAsync(record, TransferIntent.Pause, CancellationToken.None).ConfigureAwait(false);
        }
        catch (FilewayException error) { failure = error; }
        try { await StopAsync(work).ConfigureAwait(false); }
        catch (Exception error) { failure ??= Normalize(error); }
        return failure;
    }

    private static async Task StopAsync(Work work)
    {
        try { await work.Stop.CancelAsync().ConfigureAwait(false); }
        catch (ObjectDisposedException) { /* Runner completion already released this execution. */ }
    }

    public ValueTask DisposeAsync()
    {
        TaskCompletionSource completion;
        lock (_gate)
        {
            if (_disposeCompletion is not null) return new(_disposeCompletion.Task);
            _disposed = true;
            _admission = false;
            _disposeCompletion = completion = new(TaskCreationOptions.RunContinuationsAsynchronously);
        }
        _runner.ProgressChanged -= Runner_ProgressChanged;
        _ = DisposeCoreAsync(completion);
        return new(completion.Task);
    }

    private async Task DisposeCoreAsync(TaskCompletionSource completion)
    {
        FilewayException? failure = null;
        await _commands.WaitAsync().ConfigureAwait(false);
        try
        {
            try { await DrainAsync().ConfigureAwait(false); }
            catch (Exception error) { failure = Normalize(error); }
            try { await _runner.DisposeAsync().ConfigureAwait(false); }
            catch (Exception error) { failure ??= Normalize(error); }
        }
        finally { _commands.Release(); }
        if (failure is not null) completion.TrySetException(failure);
        else completion.TrySetResult();
    }

    private static UploadTaskSnapshot Snapshot(TransferTaskRecord record) => new(record.Plan.Reference, record.Intent,
        record.Checkpoint.Phase, record.Checkpoint.ConfirmedOffset, record.Checkpoint.Source?.Length,
        record.Checkpoint.RemotePublished, record.Checkpoint.RemoteCanceled, record.Checkpoint.Uncertainty,
        record.Checkpoint.Error is { } error ? Safe(error.Code, error.HttpStatus).Error : null, record.Revision, record.Generation);

    private static FilewayException Normalize(Exception error) => error switch
    {
        FilewayException safe => Safe(safe.Error.Code, safe.Error.HttpStatus),
        OperationCanceledException => Safe(ErrorCode.Canceled),
        IOException or UnauthorizedAccessException or InvalidOperationException => Safe(ErrorCode.LocalStorageUnavailable),
        _ => Safe(ErrorCode.Unknown),
    };

    private static FilewayException Safe(ErrorCode code, int? status = null) => new(new(code, code switch
    {
        ErrorCode.InvalidRequest => "上传队列参数无效。",
        ErrorCode.SourceChanged => "上传账户或会话已更换，请重新选择原任务。",
        ErrorCode.Conflict => "上传任务状态已改变，请重新读取。",
        ErrorCode.Busy => "上传任务或执行数量暂不可用，请稍后重试。",
        ErrorCode.LocalStorageUnavailable => "无法保存上传队列状态，本机执行已停止或原记录已保留，请核实后恢复。",
        ErrorCode.Canceled => "上传本机执行已停止，已确认的进度保留。",
        ErrorCode.AuthenticationExpired or ErrorCode.CredentialsUnavailable => "上传账户需要重新登录。",
        ErrorCode.PermissionDenied => "账户不允许此上传操作。",
        ErrorCode.NetworkUnavailable or ErrorCode.Timeout or ErrorCode.HostExited => "上传连接已中断，请核实进度后恢复。",
        ErrorCode.ProtocolMismatch => "上传执行返回的身份或状态无效。",
        ErrorCode.OutcomeUnknown => "上传结果尚未确认，请先核实原任务。",
        _ => "无法完成上传队列操作，请核实任务状态。",
    }, status is >= 100 and <= 599 ? status : null,
        code is ErrorCode.Busy or ErrorCode.LocalStorageUnavailable or ErrorCode.NetworkUnavailable or ErrorCode.Timeout));
}
