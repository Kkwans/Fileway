using System.Collections.Concurrent;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices
{
    private readonly ConcurrentDictionary<Guid, JournalContext> _activeJournalOperations = new();

    private static void ValidateJournalRequest(string operation, object parameters, HostCallSafety safety)
    {
        var payload = JsonSerializer.SerializeToElement(parameters);
        if (operation != "request" || safety != HostCallSafety.Mutation ||
            !payload.TryGetProperty("method", out var method) || method.ValueKind != JsonValueKind.String ||
            method.GetString()?.ToUpperInvariant() is not ("POST" or "PUT" or "PATCH" or "DELETE"))
            throw Safe(ErrorCode.InvalidRequest, "此请求不属于可记录的业务写入，尚未发送。");
    }

    private sealed class JournalContext(OperationIntent intent, Func<JsonElement, OperationAcknowledgement> acknowledge,
        Func<IReadOnlyList<OperationItem>>? captureItems, Func<OperationOptions?>? captureOptions)
    {
        internal OperationIntent Intent { get; set; } = intent;
        internal Func<JsonElement, OperationAcknowledgement> Acknowledge { get; } = acknowledge;
        internal Func<IReadOnlyList<OperationItem>>? CaptureItems { get; } = captureItems;
        internal Func<OperationOptions?>? CaptureOptions { get; } = captureOptions;
        internal IOperationJournalStore? Store { get; set; }
        internal OperationRecord? Record { get; set; }
        internal OperationReceipt? Receipt { get; set; }
        internal bool CallStarted { get; set; }
        internal int Used;
    }

    private JournalContext PrepareJournalContext(SessionState state, OperationKind kind, IReadOnlyList<OperationItem> items,
        Func<JsonElement, OperationAcknowledgement> acknowledge, OperationOptions? options = null,
        Func<IReadOnlyList<OperationItem>>? captureItems = null, Func<OperationOptions?>? captureOptions = null)
    {
        ArgumentNullException.ThrowIfNull(items);
        ArgumentNullException.ThrowIfNull(acknowledge);
        EnsureActive(state);
        var snapshot = options is null ? null : options with { RecordIds = options.RecordIds?.ToArray() };
        return new JournalContext(new OperationIntent(new OperationRef(state.Session.Account, Guid.NewGuid()),
            state.Session.Id, kind, items.ToArray(), snapshot), acknowledge, captureItems, captureOptions);
    }

    private async Task MarkJournalDispatchAsync(SessionState state, JournalContext context, CancellationToken cancellationToken)
    {
        if (Interlocked.Exchange(ref context.Used, 1) != 0)
            throw Safe(ErrorCode.InvalidRequest, "此操作已提交过，请先核实结果，不要重复提交。");
        if (context.Intent.Reference.Account != state.Session.Account || context.Intent.OriginSessionId != state.Session.Id)
            throw Safe(ErrorCode.SourceChanged, Message(ErrorCode.SourceChanged));
        var store = _profiles as IOperationJournalStore
            ?? throw Safe(ErrorCode.LocalStorageUnavailable, "操作记录暂不可用，尚未发送请求。");
        context.Store = store;
        if (context.CaptureItems is { } capture) context.Intent = context.Intent with { Items = capture().ToArray() };
        if (context.CaptureOptions is { } captureOptions)
        {
            var options = captureOptions();
            context.Intent = context.Intent with { Options = options is null ? null : options with { RecordIds = options.RecordIds?.ToArray() } };
        }
        _activeJournalOperations.TryAdd(context.Intent.Reference.Id, context);
        // Profile replacement/forget/logout share this brief preparation gate.
        // ACK finalization deliberately does not take it: logout may already be
        // holding it while waiting for the session operation gate to drain.
        await _persistence.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            EnsureActive(state);
            var created = await StorageAsync(() => store.CreateIntentAsync(context.Intent, cancellationToken)).ConfigureAwait(false);
            if (created.Intent.Reference != context.Intent.Reference || created.Phase != OperationPhase.Intent)
                throw Safe(ErrorCode.LocalStorageUnavailable, "操作意图未得到本地存储确认，尚未发送请求。");
            context.Record = created;
            context.Receipt = new OperationReceipt(context.Intent.Reference.Id, false);
            EnsureActive(state);
            context.Record = await StorageAsync(() => store.MarkDispatchAsync(context.Intent.Reference,
                created.Revision, cancellationToken)).ConfigureAwait(false);
            if (context.Record.Intent.Reference != context.Intent.Reference || context.Record.Phase != OperationPhase.DispatchMarked
                || context.Record.Revision <= created.Revision)
                throw Safe(ErrorCode.LocalStorageUnavailable, "操作发送标记未得到本地存储确认，尚未发送请求。");
        }
        finally { _persistence.Release(); }
    }

    private static OperationAcknowledgement CaptureJournalAcknowledgement(JournalContext context, JsonElement response)
    {
        try { return context.Acknowledge(response); }
        catch (Exception)
        {
            throw Safe(ErrorCode.OutcomeUnknown, "服务器回复尚不足以确认结果，请刷新核实，不要重复提交。");
        }
    }

    private static async Task RecordJournalAcknowledgementAsync(JournalContext context, OperationAcknowledgement acknowledgement)
    {
        using var finish = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        try
        {
            if (context.Store is not { } store || context.Record is not { } record) throw InvalidResponse();
            context.Record = await store.RecordAckAsync(context.Intent.Reference, record.Revision, acknowledgement, finish.Token)
                .WaitAsync(finish.Token).ConfigureAwait(false);
            if (context.Record.Intent.Reference != context.Intent.Reference || context.Record.Phase != OperationPhase.Acknowledged
                || context.Record.Acknowledgement is not { } saved || saved.Kind != acknowledgement.Kind
                || saved.Outcome != acknowledgement.Outcome || saved.RecordId != acknowledgement.RecordId || saved.Task != acknowledgement.Task)
                throw InvalidResponse();
            context.Receipt = new OperationReceipt(context.Intent.Reference.Id, store.IsPersistent,
                store.IsPersistent ? null : new ErrorInfo(ErrorCode.LocalStorageUnavailable, "当前调用仅使用进程内记录，不能在程序退出后恢复此记录。"));
        }
        catch (Exception)
        {
            // The remote ACK remains the current call's result even if durable
            // storage fails. The dispatch marker still supports conservative recovery.
            context.Receipt = new OperationReceipt(context.Intent.Reference.Id, false,
                new ErrorInfo(ErrorCode.LocalStorageUnavailable, "服务器已确认操作，但本地记录尚未保存；重启后可能仍需核实。"));
        }
    }

    private static async Task RecordJournalFailureAsync(JournalContext context, ErrorInfo error)
    {
        if (context.Store is not { } store || context.Record is not { } record) return;
        var phase = !context.CallStarted ? OperationPhase.NotSent
            : error.HttpStatus is >= 400 and < 500 and not 408 ? OperationPhase.Rejected : OperationPhase.OutcomeUnknown;
        using var finish = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        try
        {
            context.Record = await store.RecordRejectedOrUnknownAsync(context.Intent.Reference, record.Revision, phase,
                error, finish.Token).WaitAsync(finish.Token).ConfigureAwait(false);
            context.Receipt = new OperationReceipt(context.Intent.Reference.Id, false,
                store.IsPersistent ? null : new ErrorInfo(ErrorCode.LocalStorageUnavailable, "当前调用仅使用进程内记录，不能在程序退出后恢复此记录。"));
        }
        catch (Exception)
        {
            context.Receipt = new OperationReceipt(context.Intent.Reference.Id, false,
                new ErrorInfo(ErrorCode.LocalStorageUnavailable, "本地操作结果尚未保存，请在恢复后核实。"));
        }
    }

    private bool IsJournalOperationInFlight(OperationRef operation) =>
        _activeJournalOperations.TryGetValue(operation.Id, out var context) && context.Intent.Reference == operation;

    private async Task EnsureOperationHistoryResolvedAsync(AccountKey account, CancellationToken cancellationToken)
    {
        if (_profiles is IOperationJournalStore store &&
            await StorageAsync(() => store.HasUnresolvedOperationsAsync(account, cancellationToken)).ConfigureAwait(false))
            throw Safe(ErrorCode.Conflict, "此连接还有待核实操作，请先核实，或新建独立连接；当前档案尚未遗忘或覆盖。");
        if (_profiles is ITransferTaskStore transfers &&
            await StorageAsync(() => transfers.HasProtectedTransfersAsync(account, cancellationToken)).ConfigureAwait(false))
            throw Safe(ErrorCode.Conflict, "此连接仍有正在运行或待核实的传输，请先暂停并核实；当前档案尚未遗忘或覆盖。");
    }
}
