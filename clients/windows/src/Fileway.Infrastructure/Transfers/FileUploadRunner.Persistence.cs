using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Transfers;

public sealed partial class FileUploadRunner
{
    private const int MetadataAttempts = 16;

    private async Task<TransferTaskRecord> ReadRequiredAsync(TransferTaskRef task, CancellationToken cancellationToken)
    {
        try
        {
            using var budget = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            budget.CancelAfter(_options.PersistenceTimeout);
            return await _store.GetTransferAsync(task, budget.Token).WaitAsync(budget.Token).ConfigureAwait(false)
                ?? throw Safe(ErrorCode.InvalidRequest);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested) { throw Safe(ErrorCode.LocalStorageUnavailable); }
        catch (FilewayException error) { throw Safe(error.Error.Code, error.Error.HttpStatus); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidOperationException)
        { throw Safe(ErrorCode.LocalStorageUnavailable); }
    }

    private async Task<TransferTaskRecord> SetIntentAsync(TransferTaskRef task, TransferIntent intent, CancellationToken cancellationToken)
    {
        for (int attempt = 0; attempt < MetadataAttempts; attempt++)
        {
            var record = await ReadRequiredAsync(task, cancellationToken).ConfigureAwait(false);
            if (record.Plan.Direction != TransferDirection.Upload) throw Safe(ErrorCode.InvalidRequest);
            if (IsTerminal(record) || record.Intent == intent || record.Intent == TransferIntent.Cancel) return record;
            try
            {
                using var budget = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                budget.CancelAfter(_options.PersistenceTimeout);
                return await _store.SetTransferIntentAsync(task, record.Revision, intent, budget.Token)
                    .WaitAsync(budget.Token).ConfigureAwait(false);
            }
            catch (FilewayException error) when (error.Error.Code == ErrorCode.Conflict) { }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested) { throw Safe(ErrorCode.LocalStorageUnavailable); }
            catch (FilewayException error) { throw Safe(error.Error.Code, error.Error.HttpStatus); }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidOperationException)
            { throw Safe(ErrorCode.LocalStorageUnavailable); }
        }
        throw Safe(ErrorCode.Conflict);
    }

    private async Task<TransferTaskRecord> ClaimAsync(TransferTaskRecord record, bool recovery, CancellationToken cancellationToken)
    {
        Guid runId = Guid.NewGuid();
        for (int attempt = 0; attempt < MetadataAttempts; attempt++)
        {
            if (IsTerminal(record) || !recovery && record.Intent != TransferIntent.Run) return record;
            try
            {
                using var budget = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                budget.CancelAfter(_options.PersistenceTimeout);
                return await _store.ClaimTransferAsync(record.Plan.Reference, record.Revision, runId, recovery, budget.Token)
                    .WaitAsync(budget.Token).ConfigureAwait(false);
            }
            catch (FilewayException error) when (error.Error.Code == ErrorCode.Conflict)
            {
                var fresh = await ReadRequiredAsync(record.Plan.Reference, cancellationToken).ConfigureAwait(false);
                if (fresh.Revision == record.Revision) throw Safe(ErrorCode.Conflict);
                if (fresh.Plan != record.Plan) throw Safe(ErrorCode.SourceChanged);
                record = fresh;
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested) { throw Safe(ErrorCode.LocalStorageUnavailable); }
            catch (FilewayException error) { throw Safe(error.Error.Code, error.Error.HttpStatus); }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidOperationException)
            { throw Safe(ErrorCode.LocalStorageUnavailable); }
        }
        throw Safe(ErrorCode.Conflict);
    }

    private async Task SaveAsync(RunState state, TransferCheckpoint checkpoint, bool release)
    {
        // An ACK belongs to the original token even after caller cancellation, logout or a changed intent.
        for (int attempt = 0; attempt < MetadataAttempts; attempt++)
        {
            try
            {
                using var budget = new CancellationTokenSource(_options.PersistenceTimeout);
                state.Record = await _store.SaveTransferCheckpointAsync(state.Run, state.Record.Revision, checkpoint, release, budget.Token)
                    .WaitAsync(budget.Token).ConfigureAwait(false);
                QueueProgress(state);
                return;
            }
            catch (FilewayException error) when (error.Error.Code == ErrorCode.Conflict)
            {
                var fresh = await ReadRequiredAsync(state.Run.Task, CancellationToken.None).ConfigureAwait(false);
                if (fresh.Run != state.Run || fresh.Generation != state.Run.Generation || fresh.Plan != state.Record.Plan)
                    throw Safe(ErrorCode.Conflict);
                if (fresh.Revision == state.Record.Revision) throw Safe(ErrorCode.LocalStorageUnavailable);
                state.Record = fresh;
            }
            catch (Exception error) when (error is FilewayException or OperationCanceledException or IOException or
                UnauthorizedAccessException or InvalidOperationException)
            { throw Safe(ErrorCode.LocalStorageUnavailable); }
        }
        throw Safe(ErrorCode.LocalStorageUnavailable);
    }

    private async Task CheckIntentAsync(RunState state, bool cancellation)
    {
        var fresh = await ReadRequiredAsync(state.Run.Task, state.Execution.Stop.Token).ConfigureAwait(false);
        if (fresh.Run != state.Run || fresh.Plan != state.Record.Plan) throw Safe(ErrorCode.Conflict);
        state.Record = fresh;
        state.Execution.Stop.Token.ThrowIfCancellationRequested();
        if (cancellation ? fresh.Intent != TransferIntent.Cancel : fresh.Intent != TransferIntent.Run)
            throw new OperationCanceledException(state.Execution.Stop.Token);
    }

    private async Task<TransferTaskRecord> ReleaseFailureAsync(RunState state, FilewayException failure)
    {
        // Do not overwrite a protected dispatch when the disk cannot save its original acknowledgement.
        if (failure.Error.Code == ErrorCode.LocalStorageUnavailable) throw failure;
        var fresh = await ReadRequiredAsync(state.Run.Task, CancellationToken.None).ConfigureAwait(false);
        if (fresh.Run != state.Run) throw Safe(ErrorCode.Conflict);
        state.Record = fresh;
        if (failure.Error.Code == ErrorCode.Canceled && fresh.Intent == TransferIntent.Run)
        {
            fresh = await SetIntentAsync(state.Run.Task, TransferIntent.Pause, CancellationToken.None).ConfigureAwait(false);
            if (fresh.Run != state.Run) throw Safe(ErrorCode.Conflict);
            state.Record = fresh;
        }
        var phase = fresh.Checkpoint.Uncertainty != TransferUncertainty.None ? TransferPhase.OutcomeUnknown : failure.Error.Code switch
        {
            ErrorCode.Canceled => TransferPhase.Paused,
            ErrorCode.AuthenticationExpired or ErrorCode.CredentialsUnavailable => TransferPhase.WaitingAuthentication,
            ErrorCode.NetworkUnavailable or ErrorCode.Timeout or ErrorCode.HostExited => TransferPhase.WaitingNetwork,
            ErrorCode.Conflict => TransferPhase.Conflict,
            ErrorCode.SourceChanged => TransferPhase.SourceChanged,
            _ => TransferPhase.Failed,
        };
        await SaveAsync(state, fresh.Checkpoint with { Phase = phase, Error = failure.Error }, release: true).ConfigureAwait(false);
        return state.Record;
    }

    private async Task<T> AcquireOwnedAsync<T>(Func<CancellationToken, Task<T>> acquire, CancellationToken cancellationToken,
        bool source = false) where T : IAsyncDisposable
    {
        using var budget = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        budget.CancelAfter(_options.ControlCallTimeout);
        Task<T> pending;
        try { pending = acquire(budget.Token); }
        catch (Exception error) { throw Normalize(error, source); }
        try { return await pending.WaitAsync(budget.Token).ConfigureAwait(false); }
        catch (OperationCanceledException)
        {
            _ = ReleaseLateAcquisitionAsync(pending);
            throw Safe(cancellationToken.IsCancellationRequested ? ErrorCode.Canceled : ErrorCode.Timeout);
        }
        catch (Exception error) { throw Normalize(error, source); }
    }

    private static async Task ReleaseLateAcquisitionAsync<T>(Task<T> pending) where T : IAsyncDisposable
    {
        try { await (await pending.ConfigureAwait(false)).DisposeAsync().ConfigureAwait(false); }
        catch (Exception) { /* A late, rejected capability is never handed to another execution. */ }
    }

    private async Task<ITransferExecutionLock> AcquireLockAsync(TransferTaskRef task, CancellationToken cancellationToken)
    {
        var ownership = await AcquireOwnedAsync(token => _executionLocks.AcquireAsync(task, token), cancellationToken).ConfigureAwait(false);
        if (ownership.Task == task) return ownership;
        await ownership.DisposeAsync().ConfigureAwait(false);
        throw Safe(ErrorCode.ProtocolMismatch);
    }

    private Task<IUploadSource> OpenSourceAsync(string path, CancellationToken cancellationToken) =>
        AcquireOwnedAsync(token => _sources.OpenAsync(path, token), cancellationToken, source: true);

    private async Task VerifySourceAsync(IUploadSource source, TransferSourceSnapshot expected, CancellationToken cancellationToken)
    {
        using var budget = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        budget.CancelAfter(_options.ControlCallTimeout);
        try { await source.VerifyAsync(expected, budget.Token).WaitAsync(budget.Token).ConfigureAwait(false); }
        catch (OperationCanceledException) { throw Safe(cancellationToken.IsCancellationRequested ? ErrorCode.Canceled : ErrorCode.Timeout); }
        catch (Exception error) { throw Normalize(error, source: true); }
    }

    private static bool IsTerminal(TransferTaskRecord record) => record.Checkpoint.Phase is
        TransferPhase.Completed or TransferPhase.Canceled or TransferPhase.Skipped;

    private static FilewayException Normalize(Exception error, bool source = false) => error switch
    {
        FilewayException safe => Safe(safe.Error.Code, safe.Error.HttpStatus),
        OperationCanceledException => Safe(ErrorCode.Canceled),
        UnauthorizedAccessException => Safe(ErrorCode.PermissionDenied),
        IOException when source => Safe(ErrorCode.SourceChanged),
        HttpRequestException or IOException => Safe(ErrorCode.NetworkUnavailable),
        _ => Safe(ErrorCode.Unknown),
    };

    private static FilewayException Safe(ErrorCode code, int? status = null) => new(new(code, code switch
    {
        ErrorCode.AuthenticationExpired or ErrorCode.CredentialsUnavailable => "上传账户需要重新登录。",
        ErrorCode.PermissionDenied => "账户或本地文件不允许此上传操作。",
        ErrorCode.NetworkUnavailable => "上传连接已中断，请核实服务器进度后恢复。",
        ErrorCode.Timeout => "上传长时间没有进展，已停止本次连接。",
        ErrorCode.Canceled => "上传已停止，已确认的进度会保留。",
        ErrorCode.Conflict => "上传任务或目标状态已改变，请重新读取。",
        ErrorCode.SourceChanged => "上传来源或账户连接已改变，请核实后恢复。",
        ErrorCode.OutcomeUnknown => "上传结果尚未确认，需要先核实服务器进度。",
        ErrorCode.HostExited => "上传连接服务已退出，请重新连接。",
        ErrorCode.ProtocolMismatch => "上传服务返回的协议数据无效。",
        ErrorCode.InvalidRequest => "上传任务参数无效。",
        ErrorCode.Busy => "此上传任务正在执行，或执行数量已达上限。",
        ErrorCode.LocalStorageUnavailable => "无法保存上传进度，原执行记录已保留，需恢复核实。",
        _ => "无法完成上传，请核实连接和任务状态。",
    }, status is >= 100 and <= 599 ? status : null,
        code is ErrorCode.NetworkUnavailable or ErrorCode.Timeout or ErrorCode.Busy or ErrorCode.LocalStorageUnavailable));
}
