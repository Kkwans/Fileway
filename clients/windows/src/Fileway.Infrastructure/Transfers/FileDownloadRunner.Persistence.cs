using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Transfers;

public sealed partial class FileDownloadRunner
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
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        { throw Safe(ErrorCode.LocalStorageUnavailable); }
        catch (FilewayException error) { throw Safe(error.Error.Code, error.Error.HttpStatus); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidOperationException)
        { throw Safe(ErrorCode.LocalStorageUnavailable); }
    }

    private async Task<TransferTaskRecord> SetCancelAsync(TransferTaskRef task, CancellationToken cancellationToken)
    {
        for (int attempt = 0; attempt < MetadataAttempts; attempt++)
        {
            var record = await ReadRequiredAsync(task, cancellationToken).ConfigureAwait(false);
            RequirePlan(record);
            if (IsTerminal(record) || record.Intent == TransferIntent.Cancel) return record;
            try
            {
                using var budget = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                budget.CancelAfter(_options.PersistenceTimeout);
                return await _store.SetTransferIntentAsync(task, record.Revision, TransferIntent.Cancel, budget.Token)
                    .WaitAsync(budget.Token).ConfigureAwait(false);
            }
            catch (FilewayException error) when (error.Error.Code == ErrorCode.Conflict) { }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            { throw Safe(ErrorCode.LocalStorageUnavailable); }
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
            RequirePlan(record);
            if (IsTerminal(record)) return record;
            if (!recovery) RequireFresh(record);
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
                if (fresh.Plan != record.Plan) throw Safe(ErrorCode.SourceChanged);
                if (fresh.Revision == record.Revision) throw Safe(ErrorCode.Conflict);
                record = fresh;
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            { throw Safe(ErrorCode.LocalStorageUnavailable); }
            catch (FilewayException error) { throw Safe(error.Error.Code, error.Error.HttpStatus); }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidOperationException)
            { throw Safe(ErrorCode.LocalStorageUnavailable); }
        }
        throw Safe(ErrorCode.Conflict);
    }

    private async Task SaveAsync(RunState state, TransferCheckpoint checkpoint, bool release)
    {
        // Durable observed truth belongs to this run even after caller cancellation or a changed intent.
        for (int attempt = 0; attempt < MetadataAttempts; attempt++)
        {
            try
            {
                using var budget = new CancellationTokenSource(_options.PersistenceTimeout);
                state.Record = await _store.SaveTransferCheckpointAsync(state.Run, state.Record.Revision, checkpoint, release, budget.Token)
                    .WaitAsync(budget.Token).ConfigureAwait(false);
                QueueProgress(state, force: true);
                return;
            }
            catch (FilewayException error) when (error.Error.Code == ErrorCode.Conflict)
            {
                var fresh = await ReadRequiredAsync(state.Run.Task, CancellationToken.None).ConfigureAwait(false);
                if (fresh.Run != state.Run || fresh.Generation != state.Run.Generation || fresh.Plan != state.Record.Plan)
                    throw Safe(ErrorCode.Conflict);
                if (fresh.Revision == state.Record.Revision)
                { state.PersistenceFailed = true; throw Safe(ErrorCode.LocalStorageUnavailable); }
                state.Record = fresh;
            }
            catch (Exception error) when (error is FilewayException or OperationCanceledException or IOException or
                UnauthorizedAccessException or InvalidOperationException)
            { state.PersistenceFailed = true; throw Safe(ErrorCode.LocalStorageUnavailable); }
        }
        state.PersistenceFailed = true;
        throw Safe(ErrorCode.LocalStorageUnavailable);
    }

    private async Task CheckIntentAsync(RunState state)
    {
        var fresh = await ReadRequiredAsync(state.Run.Task, state.Execution.Stop.Token).ConfigureAwait(false);
        if (fresh.Run != state.Run || fresh.Plan != state.Record.Plan) throw Safe(ErrorCode.Conflict);
        state.Record = fresh;
        state.Execution.Stop.Token.ThrowIfCancellationRequested();
        if (fresh.Intent != TransferIntent.Run) throw new OperationCanceledException(state.Execution.Stop.Token);
    }

    private async Task<T> ControlAsync<T>(Func<CancellationToken, Task<T>> operation, CancellationToken cancellationToken)
    {
        using var budget = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        budget.CancelAfter(_options.ControlCallTimeout);
        try
        {
            // Await the real completion: an uncooperative provider cannot outlive the session's drain acknowledgement.
            var result = await operation(budget.Token).ConfigureAwait(false);
            budget.Token.ThrowIfCancellationRequested();
            return result;
        }
        catch (OperationCanceledException)
        { throw Safe(cancellationToken.IsCancellationRequested ? ErrorCode.Canceled : ErrorCode.Timeout); }
    }

    private async Task<IDownloadDestination> AcquireFreshDestinationAsync(RunState state)
    {
        using var budget = CancellationTokenSource.CreateLinkedTokenSource(state.Execution.Stop.Token);
        budget.CancelAfter(_options.ControlCallTimeout);
        IDownloadDestination? captured = null;
        Exception failure;
        try
        {
            captured = await _destinations.CreateFreshAsync(state.Record.Plan.LocalPath, budget.Token).ConfigureAwait(false);
            if (captured is null) throw Safe(ErrorCode.ProtocolMismatch);
            budget.Token.ThrowIfCancellationRequested();
            return captured;
        }
        catch (OperationCanceledException)
        {
            bool canceled = state.Execution.Stop.IsCancellationRequested;
            state.RejectedDestinationWasCanceled = captured is not null && canceled;
            failure = Safe(canceled ? ErrorCode.Canceled : ErrorCode.Timeout);
        }
        catch (Exception error) { failure = error; }
        if (captured is not null)
        {
            try
            {
                bool valid = true;
                try { RequireFreshDestination(state, captured); }
                catch (Exception) { valid = false; failure = Safe(ErrorCode.OutcomeUnknown); }
                if (valid)
                {
                    try { await captured.DeleteOwnedPartialAsync(CancellationToken.None).ConfigureAwait(false); }
                    catch (Exception)
                    {
                        // The caller never received this capability. Retain its validated ownership
                        // before releasing handles, so restart can reconcile an unconfirmed deletion.
                        failure = Safe(ErrorCode.OutcomeUnknown);
                        try { await RetainRejectedDestinationAsync(state, captured).ConfigureAwait(false); }
                        catch (Exception error) { state.PersistenceFailed = true; failure = error; }
                    }
                }
            }
            finally
            {
                try { await captured.DisposeAsync().ConfigureAwait(false); }
                catch (Exception error)
                {
                    state.RejectedAcquisitionReleaseFailure = NormalizeReleaseFailure(error);
                }
            }
        }
        return await Task.FromException<IDownloadDestination>(failure).ConfigureAwait(false);
    }

    private async Task RetainRejectedDestinationAsync(RunState state, IDownloadDestination destination)
    {
        try { RequireFreshDestination(state, destination); }
        catch (FilewayException) { return; /* Untrusted file evidence cannot become deletion authority. */ }
        await SaveAsync(state, state.Record.Checkpoint with { Files = destination.Files,
            ConfirmedOffset = 0, Uncertainty = TransferUncertainty.LocalPublish }, release: false).ConfigureAwait(false);
    }

    private Task<T> AcquireOwnedAsync<T>(Func<CancellationToken, Task<T>> acquire,
        CancellationToken cancellationToken) where T : class, IAsyncDisposable =>
        AcquireOwnedAsync(acquire, rejectedReleaseState: null, cancellationToken);

    private async Task<T> AcquireOwnedAsync<T>(Func<CancellationToken, Task<T>> acquire, RunState? rejectedReleaseState,
        CancellationToken cancellationToken) where T : class, IAsyncDisposable
    {
        using var budget = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        budget.CancelAfter(_options.ControlCallTimeout);
        T? captured = default;
        bool returned = false;
        try
        {
            captured = await acquire(budget.Token).ConfigureAwait(false);
            if (captured is null) throw Safe(ErrorCode.ProtocolMismatch);
            budget.Token.ThrowIfCancellationRequested();
            returned = true;
            return captured;
        }
        catch (OperationCanceledException)
        { throw Safe(cancellationToken.IsCancellationRequested ? ErrorCode.Canceled : ErrorCode.Timeout); }
        finally
        {
            if (!returned && captured is not null)
            {
                try { await captured.DisposeAsync().ConfigureAwait(false); }
                catch (Exception error) when (rejectedReleaseState is not null)
                {
                    // A late lease cannot mask cancellation or bypass local cleanup. Surface
                    // its release failure only after the owned file's durable truth is settled.
                    rejectedReleaseState.RejectedAcquisitionReleaseFailure = NormalizeReleaseFailure(error);
                }
            }
        }
    }

    private async Task<ITransferExecutionLock> AcquireLockAsync(TransferTaskRef task, CancellationToken cancellationToken)
    {
        var ownership = await AcquireOwnedAsync(token => _executionLocks.AcquireAsync(task, token), cancellationToken).ConfigureAwait(false);
        if (ownership.Task == task) return ownership;
        await ownership.DisposeAsync().ConfigureAwait(false);
        throw Safe(ErrorCode.ProtocolMismatch);
    }

    private static bool IsTerminal(TransferTaskRecord record) => record.Checkpoint.Phase is
        TransferPhase.Completed or TransferPhase.Canceled or TransferPhase.Skipped;

    private static FilewayException NormalizeReleaseFailure(Exception error)
    {
        var failure = Normalize(error);
        // An interrupted lifetime release is not proof that ordinary cancellation finished cleanup.
        return failure.Error.Code == ErrorCode.Canceled ? Safe(ErrorCode.Unknown) : failure;
    }

    private static FilewayException Normalize(Exception error) => error switch
    {
        FilewayException known => Safe(known.Error.Code, known.Error.HttpStatus),
        OperationCanceledException => Safe(ErrorCode.Canceled),
        HttpRequestException => Safe(ErrorCode.NetworkUnavailable),
        IOException or UnauthorizedAccessException => Safe(ErrorCode.LocalStorageUnavailable),
        ObjectDisposedException => Safe(ErrorCode.SourceChanged),
        _ => Safe(ErrorCode.Unknown),
    };

    private static FilewayException Safe(ErrorCode code, int? status = null) => new(new(code, code switch
    {
        ErrorCode.Canceled => "本次下载已停止，请查看任务确认状态。",
        ErrorCode.Conflict => "同名目标或下载记录存在冲突，现有文件不会被覆盖。",
        ErrorCode.SourceChanged => "下载来源或内容观察已变化，请重新选择文件。",
        ErrorCode.OutcomeUnknown => "本地保存结果仍待核实，请勿重新发布或删除目标。",
        ErrorCode.CapabilityMissing => "当前仅支持全新单文件下载，此版本能力尚不受支持。",
        ErrorCode.ProtocolMismatch => "下载响应或本地文件证据无效，未确认完成。",
        ErrorCode.AuthenticationExpired or ErrorCode.CredentialsUnavailable => "下载账户需要重新登录。",
        ErrorCode.PermissionDenied => "当前账户或本地目录不允许此下载操作。",
        ErrorCode.NetworkUnavailable or ErrorCode.HostExited => "下载连接已中断，本次全新下载已停止。",
        ErrorCode.Timeout => "下载长时间没有进展，本次连接已停止。",
        ErrorCode.InvalidRequest => "下载请求无效，请重新选择文件与保存位置。",
        ErrorCode.Busy => "已有下载正在执行，或传输数量已达上限。",
        ErrorCode.LocalStorageUnavailable => "本地文件或记录暂不可用，保存结果需要核实。",
        _ => "下载未能完成，请查看原任务状态。",
    }, status is >= 100 and <= 599 ? status : null,
        code is ErrorCode.NetworkUnavailable or ErrorCode.Timeout or ErrorCode.Busy or ErrorCode.LocalStorageUnavailable));
}
