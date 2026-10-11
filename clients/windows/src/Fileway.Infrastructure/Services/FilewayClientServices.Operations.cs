using Fileway.Core.Contracts;
using Fileway.Infrastructure.Persistence;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices
{
    public async Task<OperationPage> ListOperationsAsync(AccountKey account, string? cursor, int limit, CancellationToken cancellationToken)
    {
        var state = GetState(account);
        await RefreshSessionAsync(state, cancellationToken).ConfigureAwait(false);
        var store = OperationStore();
        var page = await StorageAsync(() => store.ListOperationsAsync(account, cursor, limit, cancellationToken)).ConfigureAwait(false);
        if (page.Items.Any(record => record.Intent.Reference.Account != account)) throw InvalidResponse();
        EnsureActive(state);
        return page;
    }

    public async Task<OperationReconciliation> ReconcileAsync(OperationRef operation, CancellationToken cancellationToken)
    {
        if (operation.Id == Guid.Empty) throw Safe(ErrorCode.InvalidRequest, "操作记录标识无效。");
        var state = GetState(operation.Account);
        if (IsJournalOperationInFlight(operation))
            return new OperationReconciliation(operation, OperationResolution.Pending,
                Error: new ErrorInfo(ErrorCode.Busy, "此操作仍在处理，请等待结果。"));
        await RefreshSessionAsync(state, cancellationToken).ConfigureAwait(false);
        var store = OperationStore();
        for (int attempt = 0; attempt < 2; attempt++)
        {
            var record = await StorageAsync(() => store.GetOperationAsync(operation, cancellationToken)).ConfigureAwait(false)
                ?? throw Safe(ErrorCode.InvalidRequest, "操作记录不存在或不属于当前账户。");
            if (record.Intent.Reference != operation) throw InvalidResponse();
            EnsureActive(state);
            if (IsJournalOperationInFlight(operation) || record.Intent.OriginSessionId == state.Session.Id &&
                record.Phase is OperationPhase.Intent or OperationPhase.DispatchMarked)
                return new OperationReconciliation(operation, OperationResolution.Pending,
                    Error: new ErrorInfo(ErrorCode.Busy, "原会话可能仍在处理此操作，请等待结果后再核实。"));

            var reconciliation = await ReadOperationEvidenceAsync(record, cancellationToken).ConfigureAwait(false);
            EnsureActive(state);
            if (IsJournalOperationInFlight(operation))
                return new OperationReconciliation(operation, OperationResolution.Pending,
                    Error: new ErrorInfo(ErrorCode.Busy, "此操作仍在处理，请等待结果。"));
            try
            {
                var saved = await StorageAsync(() => store.SaveReconciliationAsync(operation, record.Revision,
                    reconciliation, cancellationToken)).ConfigureAwait(false);
                if (saved.Intent.Reference != operation || saved.Reconciliation is not { } result) throw InvalidResponse();
                EnsureActive(state);
                return result;
            }
            catch (FilewayException error) when (error.Error.Code == ErrorCode.Conflict && attempt == 0)
            {
                // A late ACK wins. Read its new revision; never overwrite it
                // with evidence gathered from an earlier dispatch marker.
            }
        }
        throw Safe(ErrorCode.Conflict, "操作记录正在更新，请重新读取。");
    }

    private async Task<OperationReconciliation> ReadOperationEvidenceAsync(OperationRecord record, CancellationToken cancellationToken)
    {
        var operation = record.Intent.Reference;
        if (record.Phase is OperationPhase.Intent or OperationPhase.NotSent)
            return new OperationReconciliation(operation, OperationResolution.NotSent);
        if (record.Phase == OperationPhase.Acknowledged && record.Acknowledgement is { } acknowledgement)
        {
            if (acknowledgement.Task is { } task)
            {
                if (task.Account != operation.Account) throw InvalidResponse();
                try
                {
                    var current = await GetTaskAsync(task, cancellationToken).ConfigureAwait(false);
                    return new OperationReconciliation(operation, OperationResolution.ObservedTaskState, current);
                }
                catch (FilewayException error) when (error.Error.Code is not (ErrorCode.Canceled or ErrorCode.SourceChanged or ErrorCode.AuthenticationExpired))
                {
                    return new OperationReconciliation(operation, OperationResolution.NeedsReview, Error: error.Error);
                }
            }
            return new OperationReconciliation(operation, OperationResolution.Confirmed);
        }
        if (record.Phase == OperationPhase.Rejected)
            return new OperationReconciliation(operation, OperationResolution.NeedsReview, Error: record.Error);

        if (record.Intent.Kind is OperationKind.TaskCancel or OperationKind.TaskRetry or OperationKind.TaskArchive or OperationKind.TaskUnarchive &&
            record.Intent.Options?.RecordId is { } originalTaskId)
        {
            try
            {
                var original = await GetTaskAsync(new ServerTaskRef(operation.Account, originalTaskId), cancellationToken).ConfigureAwait(false);
                // Retry observes only the original known task. It cannot infer
                // whether a new retry was created or manufacture its new ID.
                return new OperationReconciliation(operation, OperationResolution.ObservedTaskState, original, UnknownMutationError());
            }
            catch (FilewayException error) when (error.Error.Code is not (ErrorCode.Canceled or ErrorCode.SourceChanged or ErrorCode.AuthenticationExpired))
            {
                return new OperationReconciliation(operation, OperationResolution.NeedsReview, Error: error.Error);
            }
        }

        // No public task ID exists for an unknown transfer. Neither a matching
        // title/time/retryOf nor metadata equality can prove which request ran.
        // Read known resources only; these observations never close the unknown.
        var references = record.Intent.Items.SelectMany(item => new[] { item.Source?.Resource, item.Target?.Resource })
            .Where(resource => resource is not null).Cast<RemoteResourceRef>()
            .DistinctBy(resource => FileOperationWireIdentity(resource.WirePath, true)).ToArray();
        foreach (var resource in references)
        {
            try { _ = await GetAsync(resource, cancellationToken).ConfigureAwait(false); }
            catch (FilewayException error) when (error.Error.Code is not (ErrorCode.Canceled or ErrorCode.SourceChanged or ErrorCode.AuthenticationExpired))
            { /* Missing/denied metadata is an observation, never a success proof. */ }
        }
        if (record.Intent.Kind == OperationKind.Restore)
        {
            _ = await ListTrashAsync(operation.Account, cancellationToken).ConfigureAwait(false);
            // A disappeared trash ID still cannot prove restoration rather
            // than another user's deletion/cleanup or a different operation.
        }
        return new OperationReconciliation(operation, OperationResolution.NeedsReview, Error: UnknownMutationError());
    }

    private IOperationJournalStore OperationStore() => _profiles as IOperationJournalStore
        ?? throw Safe(ErrorCode.LocalStorageUnavailable, "当前本地存储不支持操作记录。");
}
