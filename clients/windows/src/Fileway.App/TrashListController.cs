using System.Text;
using Fileway.Core.Contracts;

namespace Fileway.App;

/// <summary>
/// Own-account trash and durable restore evidence. A retired provider keeps the single
/// restore/reconciliation slot until it settles; observation never replays a restore.
/// </summary>
internal sealed class TrashListController : IDisposable
{
    private const int OperationPageLimit = 100;
    private const int MaximumOperationPages = 1024;
    private static readonly UTF8Encoding StrictUtf8 = new(false, true);
    private readonly object _gate = new();
    private readonly Dictionary<TrashItemRef, HashSet<Guid>> _unknownRestores = [];
    private readonly HashSet<TrashItemRef> _restoredItems = [];
    private readonly HashSet<OperationRef> _resolvedOperations = [];
    private ITrashService? _trash;
    private IOperationHistoryService? _history;
    private ConnectionSession? _session;
    private IReadOnlyList<TrashItem> _items = Array.Empty<TrashItem>();
    private IReadOnlyList<OperationRecord> _operations = Array.Empty<OperationRecord>();
    private RequestCancellation? _read;
    private RequestCancellation? _flight;
    private long _flightGeneration;
    private long _flightViewGeneration;
    private bool _flightIsRestore;
    private string? _error;
    private string? _operationsError;
    private long _generation;
    private long _viewGeneration;
    private bool _hasVerifiedOperations;
    private bool _viewActive = true;
    private bool _disposed;

    internal IReadOnlyList<TrashItem> Items { get { lock (_gate) return _items; } }
    internal IReadOnlyList<OperationRecord> UnresolvedOperations { get { lock (_gate) return _operations; } }
    internal string? Error { get { lock (_gate) return _error; } }
    internal string? OperationsError { get { lock (_gate) return _operationsError; } }
    internal bool IsLoading { get { lock (_gate) return _read is not null; } }
    internal bool IsRestoring { get { lock (_gate) return _flight is not null && _flightIsRestore; } }
    internal bool IsReconciling { get { lock (_gate) return _flight is not null && !_flightIsRestore; } }
    internal bool HasVerifiedOperations { get { lock (_gate) return _hasVerifiedOperations; } }
    internal long Generation { get { lock (_gate) return _generation; } }
    internal long ViewGeneration { get { lock (_gate) return _viewGeneration; } }

    internal void SetContext(ITrashService? trash, IOperationHistoryService? history, ConnectionSession? session)
    {
        RequestCancellation?[] retire;
        lock (_gate)
        {
            if (_disposed) return;
            if (ReferenceEquals(_trash, trash) && ReferenceEquals(_history, history) &&
                _session?.Account == session?.Account && _session?.Id == session?.Id)
            { _session = session; return; }
            retire = RetireCore();
            _trash = trash; _history = history; _session = session;
            ++_generation; ++_viewGeneration;
            ClearRowsCore();
            // Local evidence is keyed by the complete account identity. A reconnect or a
            // round trip through another account cannot make an unrecorded unknown safe.
        }
        Cancel(retire);
    }

    internal async Task RefreshAsync(CancellationToken cancellationToken = default)
    {
        RequestContext context;
        RequestCancellation request;
        RequestCancellation? previous;
        lock (_gate)
        {
            if (_disposed || _trash is null || _session is null || CurrentFlightCore()) return;
            _viewActive = true;
            previous = _read; request = new(cancellationToken); _read = request;
            _hasVerifiedOperations = false; _error = _operationsError = null;
            context = CaptureContext();
        }
        previous?.Cancel();
        try
        {
            request.Token.ThrowIfCancellationRequested();
            Task<IReadOnlyList<TrashItem>> pending;
            lock (_gate)
            {
                if (!IsCurrent(context, request, _read) || request.Token.IsCancellationRequested) return;
                pending = context.Trash.ListTrashAsync(context.Account, request.Token);
            }
            var rows = await pending.ConfigureAwait(false);
            lock (_gate)
            {
                if (!IsCurrent(context, request, _read) || request.Token.IsCancellationRequested) return;
                _items = Array.AsReadOnly(ValidateItems(rows, context.Account).Where(row => !_restoredItems.Contains(row.Reference)).ToArray());
            }
            _ = await VerifyOperationsAsync(context, request, forRestore: false).ConfigureAwait(false);
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            lock (_gate)
                if (IsCurrent(context, request, _read) && !request.Token.IsCancellationRequested)
                    _error = SafeError((error as FilewayException)?.Error, "暂时无法读取回收站，请重试。");
        }
        finally
        {
            lock (_gate) if (ReferenceEquals(_read, request)) _read = null;
            request.Dispose();
        }
    }

    internal bool CanRestore(TrashItem item, FileConflictPolicy conflict)
    {
        ArgumentNullException.ThrowIfNull(item);
        lock (_gate) return CanRestoreCore(item, conflict);
    }

    internal bool CanDelete(TrashItem item)
    {
        ArgumentNullException.ThrowIfNull(item);
        lock (_gate) return CanReadCore() && _read is null && _flight is null &&
            _hasVerifiedOperations && _trash is ITrashManagementService && IsEligibleDeleteCore(item);
    }

    internal async Task<FileOperationResult?> DeleteAsync(TrashItem item, CancellationToken cancellationToken = default)
    {
        RequestContext context;
        RequestCancellation request;
        lock (_gate)
        {
            if (!CanDelete(item)) return null;
            context = CaptureContext(); request = StartFlightCore(restore: true, cancellationToken);
            _error = null;
        }
        FileOperationResult? result = null;
        bool invoked = false;
        try
        {
            if (!await VerifyOperationsAsync(context, request, forRestore: true).ConfigureAwait(false)) return null;
            Task<FileOperationResult> pending;
            lock (_gate)
            {
                if (!IsCurrent(context, request, _flight) || request.Token.IsCancellationRequested || !IsEligibleDeleteCore(item)) return null;
                invoked = true;
                pending = ((ITrashManagementService)context.Trash).DeletePermanentlyAsync(item.Reference, request.Token);
            }
            result = await pending.ConfigureAwait(false);
            if (result is null || !Enum.IsDefined(result.Outcome) ||
                result.Receipt is { OperationId: var id } && id == Guid.Empty ||
                result.Outcome == OperationOutcome.Confirmed && result.Error is not null)
                result = new(OperationOutcome.OutcomeUnknown, Receipt: result?.Receipt);
        }
        catch (Exception) when (invoked) { result = new(OperationOutcome.OutcomeUnknown, Receipt: result?.Receipt); }
        catch (Exception error)
        {
            lock (_gate) if (IsCurrent(context, request, _flight))
            { _hasVerifiedOperations = false; _operationsError = SafeError((error as FilewayException)?.Error, JournalUnavailableText); }
        }
        finally
        {
            lock (_gate)
            {
                if (!_disposed && result is not null)
                {
                    if (result.Outcome == OperationOutcome.OutcomeUnknown)
                    {
                        if (!_unknownRestores.TryGetValue(item.Reference, out var ids)) _unknownRestores.Add(item.Reference, ids = []);
                        ids.Add(result.Receipt?.OperationId ?? Guid.Empty);
                    }
                    else if (result.Outcome == OperationOutcome.Confirmed)
                    {
                        _restoredItems.Add(item.Reference);
                        if (result.Receipt is { } receipt)
                        { var operation = new OperationRef(item.Reference.Account, receipt.OperationId); _resolvedOperations.Add(operation); ClearUnknownCore(operation); }
                    }
                    if (IsCurrent(context, request, _flight))
                    {
                        if (result.Outcome == OperationOutcome.Confirmed) _items = Array.AsReadOnly(_items.Where(row => !ReferenceEquals(row, item)).ToArray());
                        if (result.Outcome == OperationOutcome.OutcomeUnknown) { _hasVerifiedOperations = false; _error = UnknownText; }
                    }
                }
                if (ReferenceEquals(_flight, request)) _flight = null;
            }
            request.Dispose();
        }
        return result;
    }

    internal async Task<TrashRestoreResult?> RestoreAsync(TrashItem item, FileConflictPolicy conflict,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(item);
        RequestContext context;
        RequestCancellation request;
        lock (_gate)
        {
            if (!CanRestoreCore(item, conflict)) return null;
            context = CaptureContext(); request = StartFlightCore(restore: true, cancellationToken);
            _error = null;
        }
        TrashRestoreResult? result = null;
        bool invoked = false;
        try
        {
            request.Token.ThrowIfCancellationRequested();
            // Another component may have written since the last refresh. Hold the action
            // slot while inspecting every persistent page, before the final send fence.
            if (!await VerifyOperationsAsync(context, request, forRestore: true).ConfigureAwait(false)) return null;
            Task<TrashRestoreResult> pending;
            lock (_gate)
            {
                if (!IsCurrent(context, request, _flight) || request.Token.IsCancellationRequested) return null;
                if (!IsEligibleItemCore(item, conflict))
                {
                    if (_unknownRestores.ContainsKey(item.Reference)) _error = UnknownText;
                    return null;
                }
                invoked = true;
                pending = context.Trash.RestoreTrashAsync(item.Reference, conflict, request.Token);
            }
            result = await pending.ConfigureAwait(false);
            if (!ValidRestore(result, conflict)) result = UnknownRestore(result?.Receipt);
        }
        catch (OperationCanceledException) when (!invoked) { }
        catch (Exception error) when (!invoked)
        {
            lock (_gate)
                if (IsCurrent(context, request, _flight))
                {
                    _hasVerifiedOperations = false;
                    _operationsError = SafeError((error as FilewayException)?.Error, JournalUnavailableText);
                }
        }
        catch (Exception)
        {
            // Cancellation or failure after the provider starts is not evidence of zero
            // sends. Its eventual ACK is retained even if it synchronously retires us.
            result = UnknownRestore(result?.Receipt);
        }
        finally
        {
            lock (_gate)
            {
                if (!_disposed && result is not null) RememberRestoreCore(item.Reference, result);
                if (IsCurrent(context, request, _flight) && result is not null)
                {
                    if (result.Outcome == OperationOutcome.Confirmed && result.Skipped == false)
                        _items = Array.AsReadOnly(_items.Where(row => !ReferenceEquals(row, item)).ToArray());
                    if (result.Outcome == OperationOutcome.OutcomeUnknown) _hasVerifiedOperations = false;
                    _error = RestoreError(result);
                }
                if (ReferenceEquals(_flight, request)) _flight = null;
            }
            request.Dispose();
        }
        return result;
    }

    private async Task<bool> VerifyOperationsAsync(RequestContext context, RequestCancellation request, bool forRestore)
    {
        lock (_gate)
        {
            if (!IsCurrent(context, request, forRestore ? _flight : _read) || request.Token.IsCancellationRequested) return false;
            _hasVerifiedOperations = false; _operationsError = null;
            if (context.History is null)
            { _operationsError = JournalUnavailableText; return false; }
        }
        try
        {
            string? cursor = null;
            var cursors = new HashSet<string>(StringComparer.Ordinal);
            var ids = new HashSet<Guid>();
            var evidence = new List<OperationRecord>();
            for (int index = 0; index < MaximumOperationPages; ++index)
            {
                request.Token.ThrowIfCancellationRequested();
                Task<OperationPage> pending;
                lock (_gate)
                {
                    if (!IsCurrent(context, request, forRestore ? _flight : _read) || request.Token.IsCancellationRequested) return false;
                    pending = context.History!.ListOperationsAsync(context.Account, cursor, OperationPageLimit, request.Token);
                }
                var page = await pending.ConfigureAwait(false);
                var rows = ValidateOperations(page, context.Account);
                foreach (var row in rows)
                {
                    if (!ids.Add(row.Intent.Reference.Id)) throw InvalidResponse();
                    if (row.Intent.Kind is OperationKind.Restore or OperationKind.DeleteTrashPermanent) evidence.Add(row);
                }
                if (page.NextCursor is null)
                {
                    lock (_gate)
                    {
                        if (!IsCurrent(context, request, forRestore ? _flight : _read) || request.Token.IsCancellationRequested) return false;
                        foreach (var row in evidence) RememberOperationCore(row);
                        _items = Array.AsReadOnly(_items.Where(row => !_restoredItems.Contains(row.Reference)).ToArray());
                        _operations = Array.AsReadOnly(evidence.Where(row => NeedsReconciliation(row) &&
                            !_resolvedOperations.Contains(row.Intent.Reference)).ToArray());
                        _hasVerifiedOperations = true; _operationsError = null;
                        return true;
                    }
                }
                if (!cursors.Add(page.NextCursor)) throw InvalidResponse();
                cursor = page.NextCursor;
            }
            throw new FilewayException(new(ErrorCode.ResponseTooLarge, JournalUnavailableText));
        }
        catch (OperationCanceledException) { return false; }
        catch (Exception error)
        {
            lock (_gate)
                if (IsCurrent(context, request, forRestore ? _flight : _read) && !request.Token.IsCancellationRequested)
                    _operationsError = SafeError((error as FilewayException)?.Error, JournalUnavailableText);
            return false;
        }
    }

    internal bool CanReconcile(OperationRecord record)
    {
        ArgumentNullException.ThrowIfNull(record);
        lock (_gate) return CanReconcileCore(record);
    }

    internal async Task<OperationReconciliation?> ReconcileAsync(OperationRecord record,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(record);
        RequestContext context;
        RequestCancellation request;
        lock (_gate)
        {
            if (!CanReconcileCore(record)) return null;
            context = CaptureContext(); request = StartFlightCore(restore: false, cancellationToken);
            _operationsError = null;
        }
        try
        {
            request.Token.ThrowIfCancellationRequested();
            Task<OperationReconciliation> pending;
            lock (_gate)
            {
                if (!IsCurrent(context, request, _flight) || request.Token.IsCancellationRequested || !ContainsOperationCore(record)) return null;
                pending = context.History!.ReconcileAsync(record.Intent.Reference, request.Token);
            }
            var result = await pending.ConfigureAwait(false);
            lock (_gate)
            {
                if (!IsCurrent(context, request, _flight) || request.Token.IsCancellationRequested) return null;
                ValidateReconciliation(result, record.Intent.Reference);
                if (IsExactResolution(result))
                {
                    _resolvedOperations.Add(record.Intent.Reference);
                    ClearUnknownCore(record.Intent.Reference);
                    _operations = Array.AsReadOnly(_operations.Where(row => !ReferenceEquals(row, record)).ToArray());
                    // Confirmed alone does not carry the original skipped flag. Read the
                    // current trash and persisted ACK before enabling another restore.
                    if (result.Resolution == OperationResolution.Confirmed) _hasVerifiedOperations = false;
                }
                else
                {
                    _operations = Array.AsReadOnly(_operations.Select(row => ReferenceEquals(row, record)
                        ? row with { Reconciliation = result } : row).ToArray());
                }
                _operationsError = ReconciliationError(result);
                return result;
            }
        }
        catch (OperationCanceledException) { return null; }
        catch (Exception error)
        {
            lock (_gate)
                if (IsCurrent(context, request, _flight) && !request.Token.IsCancellationRequested)
                    _operationsError = SafeError((error as FilewayException)?.Error,
                        "核实未完成，原恢复结果仍需核实；未重新提交恢复。");
            return null;
        }
        finally
        {
            lock (_gate) if (ReferenceEquals(_flight, request)) _flight = null;
            request.Dispose();
        }
    }

    internal void SuspendView()
    {
        RequestCancellation?[] retire;
        lock (_gate)
        {
            if (_disposed) return;
            _viewActive = false; ++_viewGeneration; _hasVerifiedOperations = false;
            retire = RetireCore();
        }
        Cancel(retire);
    }

    public void Dispose()
    {
        RequestCancellation?[] retire;
        lock (_gate)
        {
            if (_disposed) return;
            _disposed = true; _viewActive = false; ++_generation; ++_viewGeneration;
            retire = RetireCore(); ClearRowsCore();
            _trash = null; _history = null; _session = null;
            _unknownRestores.Clear(); _restoredItems.Clear(); _resolvedOperations.Clear();
        }
        Cancel(retire);
    }

    private bool CanReadCore() => !_disposed && _viewActive && _trash is not null && _session is not null;
    private bool CurrentFlightCore() => _flight is not null && _flightGeneration == _generation && _flightViewGeneration == _viewGeneration;
    private bool CanRestoreCore(TrashItem item, FileConflictPolicy conflict) => CanReadCore() && _read is null &&
        _flight is null && _hasVerifiedOperations && IsEligibleItemCore(item, conflict);
    private bool IsEligibleDeleteCore(TrashItem item) => _session is not null && _session.Permissions.Delete &&
        item.Reference.Account == _session.Account && item.OwnerUserId == _session.Account.ServerUserId &&
        _items.Any(row => ReferenceEquals(row, item)) && item.Status is "available" or "failed" &&
        !_unknownRestores.ContainsKey(item.Reference) && !_restoredItems.Contains(item.Reference);
    private bool IsEligibleItemCore(TrashItem item, FileConflictPolicy conflict) => _session is not null &&
        item.Reference.Account == _session.Account && item.OwnerUserId == _session.Account.ServerUserId &&
        _items.Any(row => ReferenceEquals(row, item)) && item.Status is "available" or "failed" &&
        Enum.IsDefined(conflict) && _session.Permissions.Create &&
        (conflict != FileConflictPolicy.Replace || _session.Permissions.Delete) &&
        !_unknownRestores.ContainsKey(item.Reference) && !_restoredItems.Contains(item.Reference);
    private bool ContainsOperationCore(OperationRecord record) => _session is not null &&
        record.Intent.Reference.Account == _session.Account && (record.Intent.Kind is OperationKind.Restore or OperationKind.DeleteTrashPermanent) &&
        _operations.Any(row => ReferenceEquals(row, record)) && NeedsReconciliation(record) &&
        !_resolvedOperations.Contains(record.Intent.Reference);
    private bool CanReconcileCore(OperationRecord record) => CanReadCore() && _history is not null &&
        _read is null && _flight is null && ContainsOperationCore(record);
    private RequestContext CaptureContext() => new(_trash!, _history, _session!.Account, _session.Id, _generation, _viewGeneration);
    private bool IsCurrent(RequestContext context, RequestCancellation request, RequestCancellation? slot) =>
        CanReadCore() && _generation == context.Generation && _viewGeneration == context.ViewGeneration &&
        _session!.Account == context.Account && _session.Id == context.SessionId &&
        ReferenceEquals(_trash, context.Trash) && ReferenceEquals(_history, context.History) && ReferenceEquals(slot, request);
    private RequestCancellation StartFlightCore(bool restore, CancellationToken caller)
    {
        var request = new RequestCancellation(caller);
        _flight = request; _flightIsRestore = restore;
        _flightGeneration = _generation; _flightViewGeneration = _viewGeneration;
        return request;
    }
    private RequestCancellation?[] RetireCore()
    {
        RequestCancellation?[] retire = [_read, _flight];
        _read = null;
        // Only the awaiting operation may release _flight: cancellation is cooperative.
        return retire;
    }
    private void ClearRowsCore()
    {
        _items = Array.Empty<TrashItem>(); _operations = Array.Empty<OperationRecord>();
        _hasVerifiedOperations = false; _error = _operationsError = null;
    }
    private void RememberRestoreCore(TrashItemRef item, TrashRestoreResult result)
    {
        if (result.Outcome == OperationOutcome.OutcomeUnknown)
        {
            if (!_unknownRestores.TryGetValue(item, out var operations)) _unknownRestores.Add(item, operations = []);
            operations.Add(result.Receipt?.OperationId ?? Guid.Empty);
        }
        else if (result.Outcome == OperationOutcome.Confirmed)
        {
            if (result.Receipt is { } receipt)
            { var operation = new OperationRef(item.Account, receipt.OperationId); _resolvedOperations.Add(operation); ClearUnknownCore(operation); }
            if (result.Skipped == false) _restoredItems.Add(item);
        }
    }
    private void RememberOperationCore(OperationRecord record)
    {
        var operation = record.Intent.Reference;
        var item = new TrashItemRef(operation.Account, record.Intent.Options!.RecordId!);
        if (record.Phase == OperationPhase.Acknowledged && (record.Acknowledgement!.Skipped == false || record.Intent.Kind == OperationKind.DeleteTrashPermanent))
            _restoredItems.Add(item);
        if (_resolvedOperations.Contains(operation)) return;
        if (NeedsReconciliation(record))
        {
            if (!_unknownRestores.TryGetValue(item, out var operations)) _unknownRestores.Add(item, operations = []);
            operations.Add(operation.Id);
        }
        else if (record.Phase is OperationPhase.Acknowledged or OperationPhase.Rejected or OperationPhase.NotSent ||
            IsExactResolution(record.Reconciliation))
        {
            _resolvedOperations.Add(operation); ClearUnknownCore(operation);
            if (record.Phase == OperationPhase.Acknowledged && (record.Acknowledgement!.Skipped == false || record.Intent.Kind == OperationKind.DeleteTrashPermanent))
                _restoredItems.Add(item);
        }
    }
    private void ClearUnknownCore(OperationRef operation)
    {
        foreach (var pair in _unknownRestores.Where(pair => pair.Key.Account == operation.Account).ToArray())
            if (pair.Value.Remove(operation.Id) && pair.Value.Count == 0) _unknownRestores.Remove(pair.Key);
    }
    private static bool NeedsReconciliation(OperationRecord record) => (record.Intent.Kind is OperationKind.Restore or OperationKind.DeleteTrashPermanent) &&
        (record.Phase is OperationPhase.Intent or OperationPhase.DispatchMarked or OperationPhase.OutcomeUnknown) &&
        !IsExactResolution(record.Reconciliation);
    private static bool IsExactResolution(OperationReconciliation? result) => result is not null && result.Error is null &&
        result.Resolution is OperationResolution.NotSent or OperationResolution.Confirmed;

    private static TrashItem[] ValidateItems(IReadOnlyList<TrashItem> items, AccountKey account)
    {
        if (items is null) throw InvalidResponse();
        var ids = new HashSet<string>(StringComparer.Ordinal);
        var rows = new TrashItem[items.Count];
        for (int index = 0; index < rows.Length; ++index)
        {
            var row = items[index];
            if (row is null || row.Reference.Account != account || row.OwnerUserId != account.ServerUserId ||
                !ValidRecordId(row.Reference.Id) || !ids.Add(row.Reference.Id) ||
                string.IsNullOrWhiteSpace(row.DisplayPath) || row.DisplayPath.Contains('\0') ||
                string.IsNullOrWhiteSpace(row.Status) || row.Size < 0) throw InvalidResponse();
            rows[index] = row;
        }
        return rows;
    }
    private static OperationRecord[] ValidateOperations(OperationPage page, AccountKey account)
    {
        if (page is null || page.Items is null || page.Items.Count > OperationPageLimit) throw InvalidResponse();
        var rows = new OperationRecord[page.Items.Count];
        for (int index = 0; index < rows.Length; ++index)
        {
            var row = page.Items[index];
            if (row?.Intent is null || row.Intent.Reference.Account != account || row.Intent.Reference.Id == Guid.Empty ||
                row.Intent.OriginSessionId == Guid.Empty || row.Intent.Items is null || !Enum.IsDefined(row.Intent.Kind) ||
                !Enum.IsDefined(row.Phase) || row.Revision < 0 || row.Intent.Items.Any(item => item is null ||
                    item.Source?.Resource is { } source && source.Account != account ||
                    item.Target?.Resource is { } target && target.Account != account) ||
                row.Acknowledgement is { } acknowledgement && (!Enum.IsDefined(acknowledgement.Kind) ||
                    !Enum.IsDefined(acknowledgement.Outcome) || acknowledgement.Task is { } task && task.Account != account))
                throw InvalidResponse();
            if (row.Reconciliation is { } reconciliation) ValidateReconciliation(reconciliation, row.Intent.Reference);
            if (row.Intent.Kind == OperationKind.Restore)
            {
                if (!ValidRecordId(row.Intent.Options?.RecordId) || row.Intent.Options?.Conflict is not { } conflict ||
                    !Enum.IsDefined(conflict) || row.Intent.Items.Count != 0 ||
                    row.Phase == OperationPhase.Acknowledged && row.Acknowledgement is null ||
                    row.Acknowledgement is { } ack && (ack.Kind != OperationAckKind.TrashRestored ||
                        ack.Outcome != OperationOutcome.Confirmed || ack.RecordId != row.Intent.Options.RecordId ||
                        ack.Task is not null || ack.Skipped is null || !ValidDisplayPath(ack.DisplayPath) ||
                        ack.Skipped == true && conflict != FileConflictPolicy.Skip)) throw InvalidResponse();
            }
            else if (row.Intent.Kind == OperationKind.DeleteTrashPermanent)
            {
                if (!ValidRecordId(row.Intent.Options?.RecordId) || row.Intent.Items.Count != 0 ||
                    row.Phase == OperationPhase.Acknowledged && row.Acknowledgement is null ||
                    row.Acknowledgement is { } deleted && (deleted.Kind != OperationAckKind.MetadataConfirmed || deleted.Outcome != OperationOutcome.Confirmed || deleted.RecordId != row.Intent.Options!.RecordId || deleted.Task is not null)) throw InvalidResponse();
            }
            rows[index] = row;
        }
        return rows;
    }
    private static void ValidateReconciliation(OperationReconciliation result, OperationRef operation)
    {
        if (result is null || result.Reference != operation || !Enum.IsDefined(result.Resolution) || result.Task is { } task &&
            (task.Reference.Account != operation.Account || task.OwnerUserId != operation.Account.ServerUserId ||
                !ValidRecordId(task.Reference.Id))) throw InvalidResponse();
    }
    private static bool ValidRecordId(string? id)
    {
        if (string.IsNullOrWhiteSpace(id) || id.Length > 1024 ||
            id.Any(character => character is '/' or '\\' or '\0' || char.IsControl(character))) return false;
        try { _ = StrictUtf8.GetByteCount(id); return true; }
        catch (EncoderFallbackException) { return false; }
    }
    private static bool ValidDisplayPath(string? path) => path is not null && path.StartsWith('/') && !path.Contains('\0');
    private static bool ValidRestore(TrashRestoreResult? result, FileConflictPolicy conflict) => result is not null &&
        Enum.IsDefined(result.Outcome) && (result.Receipt is null || result.Receipt.OperationId != Guid.Empty) &&
        (result.Outcome switch
        {
            OperationOutcome.Confirmed => result.Error is null && result.Skipped is not null &&
                ValidDisplayPath(result.RestoredDisplayPath) && (result.Skipped != true || conflict == FileConflictPolicy.Skip),
            OperationOutcome.Partial => result.Error is not null && result.Error.Code != ErrorCode.OutcomeUnknown,
            OperationOutcome.OutcomeUnknown => true,
            _ => false,
        });
    private const string UnknownText = "原操作结果未知，请核实操作记录；不要重复提交恢复或删除。";
    private const string JournalUnavailableText = "操作记录尚未完整核实，恢复和删除已暂停；请重试读取。";
    private static TrashRestoreResult UnknownRestore(OperationReceipt? receipt) =>
        new(OperationOutcome.OutcomeUnknown, Receipt: receipt, Error: new(ErrorCode.OutcomeUnknown, UnknownText));
    private static FilewayException InvalidResponse() => new(new(ErrorCode.ProtocolMismatch,
        "回收站或操作记录响应与当前请求不一致，请刷新后核实。"));
    private static string? RestoreError(TrashRestoreResult result) => result.Outcome switch
    {
        OperationOutcome.OutcomeUnknown => UnknownText,
        OperationOutcome.Partial => SafeError(result.Error, "恢复未确认，请刷新后核实。"),
        OperationOutcome.Confirmed when result.Receipt is { AckPersisted: false } =>
            "服务器已确认恢复结果，但本地确认记录未保存；请保留结果并核实记录。",
        _ => null,
    };
    private static string? ReconciliationError(OperationReconciliation result) => IsExactResolution(result) ? null :
        result.Resolution == OperationResolution.Pending ? "原恢复操作仍在处理，请等待后再核实。" :
        result.Error is { Code: not ErrorCode.OutcomeUnknown } error
            ? SafeError(error, "原恢复结果仍需人工核实；未重新提交恢复。") :
            "原恢复结果仍未知；当前状态观察不能确认原操作，请人工核实；未重新提交恢复。";
    private static string SafeError(ErrorInfo? error, string fallback) => error?.HttpStatus switch
    {
        403 => "当前账号没有操作权限。",
        404 => "此回收站项目或操作记录已不存在，请刷新后核实。",
        _ => error?.Code switch
        {
            ErrorCode.AuthenticationExpired or ErrorCode.CredentialsUnavailable => "登录已过期，请重新连接。",
            ErrorCode.PermissionDenied => "当前账号没有操作权限。",
            ErrorCode.NetworkUnavailable => "网络暂不可用，请重试读取。",
            ErrorCode.Timeout => "读取请求超时，请重试。",
            ErrorCode.SourceChanged => "连接已变更，请刷新后核实。",
            ErrorCode.HostExited => "连接服务已退出，请重新连接。",
            ErrorCode.ProtocolMismatch => "回收站或操作记录响应与当前请求不一致，请刷新后核实。",
            ErrorCode.Conflict => "恢复发生冲突，请选择冲突策略并核实当前项目。",
            ErrorCode.OutcomeUnknown => UnknownText,
            ErrorCode.Busy => "操作仍在处理，请等待结果。",
            ErrorCode.LocalStorageUnavailable => "本地操作记录暂不可用，请恢复后核实。",
            _ => fallback,
        },
    };
    private static void Cancel(IEnumerable<RequestCancellation?> requests)
    { foreach (var request in requests) request?.Cancel(); }

    private sealed class RequestCancellation : IDisposable
    {
        private readonly object _gate = new();
        private readonly CancellationTokenSource _source;
        private Task? _cancellation;
        private bool _disposed;
        internal RequestCancellation(CancellationToken caller)
        { _source = CancellationTokenSource.CreateLinkedTokenSource(caller); Token = _source.Token; }
        internal CancellationToken Token { get; }
        internal void Cancel()
        {
            Task cancellation;
            lock (_gate) { if (_disposed) return; cancellation = _cancellation ??= _source.CancelAsync(); }
            _ = ObserveAsync(cancellation);
        }
        public void Dispose()
        {
            Task? cancellation;
            lock (_gate)
            {
                if (_disposed) return;
                _disposed = true; cancellation = _cancellation;
                if (cancellation is null || cancellation.IsCompleted) { _source.Dispose(); return; }
            }
            _ = DisposeAsync(_source, cancellation);
        }
        private static async Task ObserveAsync(Task cancellation)
        { try { await cancellation.ConfigureAwait(false); } catch (Exception) { /* Provider callbacks cannot escape retirement. */ } }
        private static async Task DisposeAsync(CancellationTokenSource source, Task cancellation)
        { await ObserveAsync(cancellation).ConfigureAwait(false); source.Dispose(); }
    }

    private readonly record struct RequestContext(ITrashService Trash, IOperationHistoryService? History,
        AccountKey Account, Guid SessionId, long Generation, long ViewGeneration);
}
