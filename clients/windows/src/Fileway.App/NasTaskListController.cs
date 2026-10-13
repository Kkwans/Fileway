using System.Collections.ObjectModel;
using Fileway.Core.Contracts;

namespace Fileway.App;

/// <summary>
/// Own-account NAS file tasks and their operation journal. Reads are replaceable; a single
/// action/reconciliation slot remains occupied until its provider settles, even after retirement.
/// </summary>
internal sealed class NasTaskListController : IDisposable
{
    private readonly object _gate = new();
    private IServerTaskService? _tasks;
    private IOperationHistoryService? _history;
    private ConnectionSession? _session;
    private IReadOnlyList<ServerTask> _items = Array.Empty<ServerTask>();
    private IReadOnlyList<OperationRecord> _operations = Array.Empty<OperationRecord>();
    private IReadOnlyDictionary<string, long> _counts = EmptyCounts();
    private readonly HashSet<string> _taskCursors = new(StringComparer.Ordinal);
    private readonly HashSet<string> _operationCursors = new(StringComparer.Ordinal);
    private readonly Dictionary<ServerTaskRef, HashSet<Guid>> _unknownActions = [];
    private readonly HashSet<(ServerTaskRef Task, ServerTaskActionKind Kind)> _acceptedActions = [];
    private RequestCancellation? _listRequest;
    private RequestCancellation? _detailRequest;
    private RequestCancellation? _operationsRequest;
    private RequestCancellation? _verificationRequest;
    private RequestCancellation? _actionFlight;
    private ServerTaskQuery _filter = new(Category: ServerTaskCategory.File);
    private ServerTask? _detail;
    private ServerTaskActionResult? _lastActionResult;
    private OperationReconciliation? _lastReconciliation;
    private string? _nextCursor;
    private string? _operationNextCursor;
    private string? _error;
    private string? _operationsError;
    private string? _verificationError;
    private long _total;
    private long _generation;
    private long _viewGeneration;
    private bool _hasLoaded;
    private bool _hasLoadedOperations;
    private bool _hasVerifiedOperations;
    private bool _isActing;
    private bool _viewActive = true;
    private bool _disposed;

    internal IReadOnlyList<ServerTask> Items { get { lock (_gate) return _items; } }
    internal IReadOnlyList<OperationRecord> Operations { get { lock (_gate) return _operations; } }
    internal IReadOnlyDictionary<string, long> Counts { get { lock (_gate) return _counts; } }
    internal ServerTaskQuery Filter { get { lock (_gate) return _filter; } }
    internal ServerTask? Detail { get { lock (_gate) return _detail; } }
    internal ServerTaskActionResult? LastActionResult { get { lock (_gate) return _lastActionResult; } }
    internal OperationReconciliation? LastReconciliation { get { lock (_gate) return _lastReconciliation; } }
    internal string? NextCursor { get { lock (_gate) return _nextCursor; } }
    internal string? OperationNextCursor { get { lock (_gate) return _operationNextCursor; } }
    internal string? Error { get { lock (_gate) return _error; } }
    internal string? OperationsError { get { lock (_gate) return _operationsError ?? _verificationError; } }
    internal long Total { get { lock (_gate) return _total; } }
    internal long Generation { get { lock (_gate) return _generation; } }
    internal long ViewGeneration { get { lock (_gate) return _viewGeneration; } }
    internal bool IsLoading { get { lock (_gate) return _listRequest is not null; } }
    internal bool IsLoadingDetail { get { lock (_gate) return _detailRequest is not null; } }
    internal bool IsLoadingOperations { get { lock (_gate) return _operationsRequest is not null; } }
    internal bool IsActing { get { lock (_gate) return _isActing; } }
    internal bool HasLoaded { get { lock (_gate) return _hasLoaded; } }
    internal bool HasLoadedOperations { get { lock (_gate) return _hasLoadedOperations; } }
    internal bool HasVerifiedOperations { get { lock (_gate) return _hasVerifiedOperations; } }
    internal bool IsVerifyingOperations { get { lock (_gate) return _verificationRequest is not null; } }
    internal bool CanLoadMore { get { lock (_gate) return CanReadCore() && _listRequest is null &&
        !_isActing && _hasLoaded && _nextCursor is not null; } }
    internal bool CanLoadMoreOperations { get { lock (_gate) return CanReadCore() && _history is not null &&
        _operationsRequest is null && !_isActing && _hasLoadedOperations && _operationNextCursor is not null; } }

    internal void SetContext(IServerTaskService? tasks, IOperationHistoryService? history, ConnectionSession? session)
    {
        RequestCancellation?[] retire;
        lock (_gate)
        {
            if (_disposed) return;
            if (ReferenceEquals(_tasks, tasks) && ReferenceEquals(_history, history) &&
                _session?.Account == session?.Account && _session?.Id == session?.Id)
            { _session = session; return; }
            retire = RetireReadsCore();
            _tasks = tasks; _history = history; _session = session;
            ++_generation; ++_viewGeneration;
            ClearRowsCore();
            _unknownActions.Clear(); _acceptedActions.Clear();
            _lastActionResult = null; _lastReconciliation = null;
            _filter = new(Category: ServerTaskCategory.File);
        }
        Cancel(retire);
    }

    internal Task RefreshAsync(CancellationToken cancellationToken = default) =>
        LoadPageAsync(refresh: true, filter: null, cancellationToken);

    internal Task ApplyFilterAsync(ServerTaskQuery filter, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(filter);
        return LoadPageAsync(refresh: true, filter, cancellationToken);
    }

    internal Task LoadMoreAsync(CancellationToken cancellationToken = default) =>
        LoadPageAsync(refresh: false, filter: null, cancellationToken);

    private async Task LoadPageAsync(bool refresh, ServerTaskQuery? filter, CancellationToken caller)
    {
        RequestContext context;
        RequestCancellation request;
        RequestCancellation? previous;
        RequestCancellation? detail;
        ServerTaskQuery query;
        lock (_gate)
        {
            if (_disposed || _tasks is null || _session is null || _isActing) return;
            if (!refresh && (!CanReadCore() || _listRequest is not null || !_hasLoaded || _nextCursor is null)) return;
            var selected = filter ?? _filter;
            if (!TryFilter(selected, out var normalized))
            { _error = "任务筛选条件无效，请检查状态、类型、时间范围及每页数量。"; return; }
            if (refresh)
            {
                _viewActive = true;
                _hasVerifiedOperations = false;
                if (filter is not null)
                {
                    _items = Array.Empty<ServerTask>(); _counts = EmptyCounts();
                    _total = 0; _hasLoaded = false; _nextCursor = null; _taskCursors.Clear();
                }
                _filter = normalized;
            }
            query = normalized with { Cursor = refresh ? null : _nextCursor };
            previous = _listRequest; detail = _detailRequest;
            request = new(caller); _listRequest = request; _detailRequest = null; _detail = null; _error = null;
            context = CaptureContext();
        }
        previous?.Cancel(); detail?.Cancel();
        try
        {
            request.Token.ThrowIfCancellationRequested();
            var page = await context.Tasks.ListTasksAsync(context.Account, query, request.Token).ConfigureAwait(false);
            lock (_gate)
            {
                if (!IsCurrent(context, request, _listRequest) || request.Token.IsCancellationRequested) return;
                var rows = ValidatePage(page, context.Account, query.Limit);
                if (page.NextCursor is { } next && (next == query.Cursor || !refresh && _taskCursors.Contains(next)))
                    throw InvalidResponse();
                var merged = refresh ? rows : MergeTasks(_items, rows);
                var counts = page.CategoryCounts.TryGetValue("file", out var fileCounts)
                    ? SnapshotCounts(fileCounts) : EmptyCounts();
                _items = Array.AsReadOnly(merged); _counts = counts; _total = page.Total;
                if (refresh) { _taskCursors.Clear(); _acceptedActions.Clear(); }
                if (page.NextCursor is { } cursor) _taskCursors.Add(cursor);
                _nextCursor = page.NextCursor; _hasLoaded = true; _error = null;
            }
            // Visible history is paged independently. Action authority requires the complete
            // persistent journal, including unknown actions outside that first visible page.
            if (refresh) _ = await VerifyOperationsAsync(context, request.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            lock (_gate)
                if (IsCurrent(context, request, _listRequest) && !request.Token.IsCancellationRequested)
                    _error = ReadError(error, "暂时无法读取 NAS 文件任务，请重试。");
        }
        finally
        {
            lock (_gate) if (IsCurrent(context, request, _listRequest)) _listRequest = null;
            request.Dispose();
        }
    }

    internal async Task LoadDetailAsync(ServerTask task, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(task);
        RequestContext context;
        RequestCancellation request;
        RequestCancellation? previous;
        lock (_gate)
        {
            if (!CanReadCore() || _listRequest is not null || _isActing || !ContainsTask(task)) return;
            context = CaptureContext(); previous = _detailRequest;
            request = new(cancellationToken); _detailRequest = request; _error = null;
        }
        previous?.Cancel();
        try
        {
            request.Token.ThrowIfCancellationRequested();
            var detail = await context.Tasks.GetTaskAsync(task.Reference, request.Token).ConfigureAwait(false);
            lock (_gate)
            {
                if (!IsCurrent(context, request, _detailRequest) || request.Token.IsCancellationRequested) return;
                ValidateTask(detail, context.Account);
                if (detail.Reference != task.Reference) throw InvalidResponse();
                _detail = detail;
                _items = Array.AsReadOnly(_items.Select(row => row.Reference == detail.Reference ? detail : row).ToArray());
                _error = null;
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            lock (_gate)
                if (IsCurrent(context, request, _detailRequest) && !request.Token.IsCancellationRequested)
                    _error = ReadError(error, "暂时无法读取任务详情，请刷新后核实。");
        }
        finally
        {
            lock (_gate) if (IsCurrent(context, request, _detailRequest)) _detailRequest = null;
            request.Dispose();
        }
    }

    internal bool CanAct(ServerTask task, ServerTaskActionKind action)
    {
        ArgumentNullException.ThrowIfNull(task);
        lock (_gate) return CanActCore(task, action);
    }

    internal Task<ServerTaskActionResult?> CancelAsync(ServerTask task, CancellationToken cancellationToken = default) =>
        ActAsync(task, ServerTaskActionKind.CancellationRequested, cancellationToken);
    internal Task<ServerTaskActionResult?> RetryAsync(ServerTask task, CancellationToken cancellationToken = default) =>
        ActAsync(task, ServerTaskActionKind.RetryAccepted, cancellationToken);
    internal Task<ServerTaskActionResult?> ArchiveAsync(ServerTask task, CancellationToken cancellationToken = default) =>
        ActAsync(task, ServerTaskActionKind.Archived, cancellationToken);
    internal Task<ServerTaskActionResult?> UnarchiveAsync(ServerTask task, CancellationToken cancellationToken = default) =>
        ActAsync(task, ServerTaskActionKind.Unarchived, cancellationToken);

    private async Task<ServerTaskActionResult?> ActAsync(ServerTask task, ServerTaskActionKind action, CancellationToken caller)
    {
        ArgumentNullException.ThrowIfNull(task);
        RequestContext context;
        RequestCancellation request;
        lock (_gate)
        {
            if (!CanActCore(task, action)) return null;
            context = CaptureContext(); request = new(caller); _actionFlight = request;
            _isActing = true; _error = null;
        }
        ServerTaskActionResult? result = null;
        bool invoked = false;
        try
        {
            request.Token.ThrowIfCancellationRequested();
            // The slot is already held while checking durable evidence. A clean cached
            // projection alone never authorizes a second POST after another component wrote.
            if (!await VerifyOperationsAsync(context, request.Token).ConfigureAwait(false)) return null;
            Task<ServerTaskActionResult> pending;
            lock (_gate)
            {
                if (!IsCurrent(context, request, _actionFlight) || request.Token.IsCancellationRequested) return null;
                if (!IsEligibleTaskCore(task, action))
                {
                    if (_unknownActions.ContainsKey(task.Reference))
                        _error = "此任务已有结果未知的操作，请核实操作记录；不要重复提交。";
                    return null;
                }
                // The provider's synchronous start shares the last session/view fence.
                // Retirement after this handoff cannot turn an observed ACK into "not sent".
                invoked = true;
                pending = action switch
                {
                    ServerTaskActionKind.CancellationRequested => context.Tasks.CancelTaskAsync(task.Reference, request.Token),
                    ServerTaskActionKind.RetryAccepted => context.Tasks.RetryTaskAsync(task.Reference, request.Token),
                    ServerTaskActionKind.Archived => context.Tasks.ArchiveTaskAsync(task.Reference, request.Token),
                    ServerTaskActionKind.Unarchived => context.Tasks.UnarchiveTaskAsync(task.Reference, request.Token),
                    _ => throw new InvalidOperationException(),
                };
            }
            result = await pending.ConfigureAwait(false);
            if (!ValidAction(result, task, action)) result = UnknownAction(action, result?.Receipt);
        }
        catch (OperationCanceledException) when (!invoked) { }
        catch (Exception error) when (!invoked)
        {
            lock (_gate)
                if (IsCurrent(context, request, _actionFlight))
                {
                    _hasVerifiedOperations = false;
                    _verificationError = ReadError(error, "操作记录尚未完整核实，任务操作已暂停；请重试读取。");
                }
        }
        catch (Exception)
        {
            // A provider exception/cancellation after invocation carries no proof that a POST
            // was not sent. Never present it as a canceled or safely repeatable mutation.
            result = UnknownAction(action, result?.Receipt);
        }
        finally
        {
            lock (_gate)
            {
                // Retired views must not lose an uncertain mutation's duplicate-submission
                // fence. This bookkeeping stays with the same verified session, never a new account.
                if (IsSameContext(context) && result?.Outcome == OperationOutcome.OutcomeUnknown)
                    RememberUnknownActionCore(task.Reference, result.Receipt?.OperationId ?? Guid.Empty);
                if (IsCurrent(context, request, _actionFlight) && result is not null)
                {
                    _lastActionResult = result;
                    if (result.Outcome == OperationOutcome.Confirmed)
                    {
                        _acceptedActions.Add((task.Reference, action));
                        _detail = result.Task;
                        if (action != ServerTaskActionKind.RetryAccepted && result.Task is { } acknowledged)
                        {
                            _items = Array.AsReadOnly(_items.Where(row => row.Reference != task.Reference ||
                                    (acknowledged.ArchivedAt is not null) == _filter.Archived)
                                .Select(row => row.Reference == task.Reference ? acknowledged : row).ToArray());
                        }
                    }
                    _error = result.Outcome == OperationOutcome.Confirmed ? null : NasTaskPresentationPolicy.ActionText(result);
                }
                if (ReferenceEquals(_actionFlight, request)) { _actionFlight = null; _isActing = false; }
            }
            request.Dispose();
        }
        // The caller retains evidence even when an account/view fence prevents publication.
        return result;
    }

    private async Task<bool> VerifyOperationsAsync(RequestContext context, CancellationToken caller)
    {
        const int limit = 100;
        const int maximumPages = 1024;
        RequestCancellation request;
        RequestCancellation? previous;
        lock (_gate)
        {
            if (!CanReadCore() || !IsSameContext(context) || _viewGeneration != context.ViewGeneration) return false;
            _hasVerifiedOperations = false; _verificationError = null;
            if (context.History is null)
            { _verificationError = "操作记录服务不可用，尚未完整核实未知操作；任务操作已暂停。"; return false; }
            previous = _verificationRequest; request = new(caller); _verificationRequest = request;
        }
        previous?.Cancel();
        try
        {
            string? cursor = null;
            var cursors = new HashSet<string>(StringComparer.Ordinal);
            var ids = new HashSet<Guid>();
            var evidence = new List<OperationRecord>();
            for (int pageIndex = 0; pageIndex < maximumPages; ++pageIndex)
            {
                request.Token.ThrowIfCancellationRequested();
                var page = await context.History!.ListOperationsAsync(context.Account, cursor, limit, request.Token).ConfigureAwait(false);
                var rows = ValidateOperations(page, context.Account, limit);
                foreach (var row in rows)
                {
                    if (!ids.Add(row.Intent.Reference.Id)) throw InvalidResponse();
                    if (NasTaskPresentationPolicy.IsTaskAction(row.Intent.Kind)) evidence.Add(row);
                }
                if (page.NextCursor is null)
                {
                    lock (_gate)
                    {
                        if (!IsCurrent(context, request, _verificationRequest) || request.Token.IsCancellationRequested) return false;
                        foreach (var row in evidence) UpdateUnknownActionCore(row);
                        _hasVerifiedOperations = true; _verificationError = null;
                        return true;
                    }
                }
                if (!cursors.Add(page.NextCursor)) throw InvalidResponse();
                cursor = page.NextCursor;
                lock (_gate)
                    if (!IsCurrent(context, request, _verificationRequest) || request.Token.IsCancellationRequested) return false;
            }
            throw new FilewayException(new(ErrorCode.ResponseTooLarge, "操作记录尚未完整核实，任务操作已暂停。"));
        }
        catch (OperationCanceledException) { return false; }
        catch (Exception error)
        {
            lock (_gate)
                if (IsCurrent(context, request, _verificationRequest) && !request.Token.IsCancellationRequested)
                    _verificationError = ReadError(error, "操作记录尚未完整核实，任务操作已暂停；请重试读取。");
            return false;
        }
        finally
        {
            lock (_gate) if (IsCurrent(context, request, _verificationRequest)) _verificationRequest = null;
            request.Dispose();
        }
    }

    internal Task RefreshOperationsAsync(CancellationToken cancellationToken = default) =>
        LoadOperationsAsync(refresh: true, cancellationToken);
    internal Task LoadMoreOperationsAsync(CancellationToken cancellationToken = default) =>
        LoadOperationsAsync(refresh: false, cancellationToken);

    private async Task LoadOperationsAsync(bool refresh, CancellationToken caller)
    {
        RequestContext context;
        RequestCancellation request;
        RequestCancellation? previous;
        string? cursor;
        const int limit = 30;
        lock (_gate)
        {
            if (!CanReadCore() || _history is null || _isActing) return;
            if (!refresh && (_operationsRequest is not null || !_hasLoadedOperations || _operationNextCursor is null)) return;
            context = CaptureContext(); previous = _operationsRequest; request = new(caller);
            _operationsRequest = request; _operationsError = null; cursor = refresh ? null : _operationNextCursor;
        }
        previous?.Cancel();
        try
        {
            request.Token.ThrowIfCancellationRequested();
            var page = await context.History!.ListOperationsAsync(context.Account, cursor, limit, request.Token).ConfigureAwait(false);
            lock (_gate)
            {
                if (!IsCurrent(context, request, _operationsRequest) || request.Token.IsCancellationRequested) return;
                var rows = ValidateOperations(page, context.Account, limit);
                if (page.NextCursor is { } next && (next == cursor || !refresh && _operationCursors.Contains(next)))
                    throw InvalidResponse();
                var visible = rows.Where(row => NasTaskPresentationPolicy.IsTaskOperation(row.Intent.Kind)).ToArray();
                var merged = refresh ? visible : MergeOperations(_operations, visible);
                _operations = Array.AsReadOnly(merged);
                if (refresh) _operationCursors.Clear();
                if (page.NextCursor is { } nextCursor) _operationCursors.Add(nextCursor);
                _operationNextCursor = page.NextCursor; _hasLoadedOperations = true; _operationsError = null;
                foreach (var row in rows) UpdateUnknownActionCore(row);
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            lock (_gate)
                if (IsCurrent(context, request, _operationsRequest) && !request.Token.IsCancellationRequested)
                {
                    _hasVerifiedOperations = false;
                    _operationsError = ReadError(error, "暂时无法读取操作记录；未知结果仍需核实。");
                }
        }
        finally
        {
            lock (_gate) if (IsCurrent(context, request, _operationsRequest)) _operationsRequest = null;
            request.Dispose();
        }
    }

    internal bool CanReconcile(OperationRecord record)
    {
        ArgumentNullException.ThrowIfNull(record);
        lock (_gate) return CanReconcileCore(record);
    }

    internal async Task<OperationReconciliation?> ReconcileAsync(OperationRecord record, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(record);
        RequestContext context;
        RequestCancellation request;
        lock (_gate)
        {
            if (!CanReconcileCore(record)) return null;
            context = CaptureContext(); request = new(cancellationToken); _actionFlight = request;
            _isActing = true; _operationsError = null;
        }
        try
        {
            request.Token.ThrowIfCancellationRequested();
            var result = await context.History!.ReconcileAsync(record.Intent.Reference, request.Token).ConfigureAwait(false);
            lock (_gate)
            {
                if (!IsCurrent(context, request, _actionFlight) || request.Token.IsCancellationRequested) return null;
                if (result is null || result.Reference != record.Intent.Reference || !Enum.IsDefined(result.Resolution))
                    throw InvalidResponse();
                if (result.Task is { } task) ValidateTask(task, context.Account);
                _lastReconciliation = result;
                if (NasTaskPresentationPolicy.IsResolved(result))
                    ClearUnknownActionCore(record.Intent.Reference);
                _operationsError = result.Error is null ? null : NasTaskPresentationPolicy.ReconciliationText(result);
                return result;
            }
        }
        catch (OperationCanceledException) { return null; }
        catch (Exception error)
        {
            lock (_gate)
                if (IsCurrent(context, request, _actionFlight) && !request.Token.IsCancellationRequested)
                    _operationsError = ReadError(error, "核实未完成，原操作结果仍需核实；未重新提交操作。");
            return null;
        }
        finally
        {
            lock (_gate) if (ReferenceEquals(_actionFlight, request)) { _actionFlight = null; _isActing = false; }
            request.Dispose();
        }
    }

    internal void SuspendView()
    {
        RequestCancellation?[] retire;
        lock (_gate)
        {
            if (_disposed) return;
            _viewActive = false; ++_viewGeneration;
            _hasVerifiedOperations = false;
            retire = RetireReadsCore();
            _error = _operationsError = null;
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
            retire = RetireReadsCore(); ClearRowsCore();
            _tasks = null; _history = null; _session = null;
            _unknownActions.Clear(); _acceptedActions.Clear();
            _lastActionResult = null; _lastReconciliation = null;
        }
        Cancel(retire);
    }

    private bool CanReadCore() => !_disposed && _viewActive && _tasks is not null && _session is not null;
    private bool ContainsTask(ServerTask task) => _session is not null && task.Reference.Account == _session.Account &&
        task.OwnerUserId == _session.Account.ServerUserId &&
        (ReferenceEquals(_detail, task) || _items.Any(row => ReferenceEquals(row, task)));
    private bool CanActCore(ServerTask task, ServerTaskActionKind action) => CanReadCore() && _listRequest is null &&
        _detailRequest is null && _operationsRequest is null && _verificationRequest is null && _actionFlight is null &&
        _hasVerifiedOperations && IsEligibleTaskCore(task, action);
    private bool IsEligibleTaskCore(ServerTask task, ServerTaskActionKind action) => ContainsTask(task) &&
        !_unknownActions.ContainsKey(task.Reference) && !_acceptedActions.Contains((task.Reference, action)) &&
        NasTaskPresentationPolicy.CanAct(task, _session!.Permissions, action);
    private bool CanReconcileCore(OperationRecord record) => CanReadCore() && _history is not null && _actionFlight is null &&
        _operationsRequest is null && _verificationRequest is null && record.Intent.Reference.Account == _session!.Account &&
        _operations.Any(row => ReferenceEquals(row, record)) && NasTaskPresentationPolicy.NeedsReconciliation(record);
    private RequestContext CaptureContext() => new(_tasks!, _history, _session!.Account, _session.Id, _generation, _viewGeneration);
    private bool IsSameContext(RequestContext context) => !_disposed && _generation == context.Generation &&
        _session is not null &&
        _session.Account == context.Account && _session.Id == context.SessionId &&
        ReferenceEquals(_tasks, context.Tasks) && ReferenceEquals(_history, context.History);
    private bool IsCurrent(RequestContext context, RequestCancellation request, RequestCancellation? slot) =>
        CanReadCore() && IsSameContext(context) && _viewGeneration == context.ViewGeneration && ReferenceEquals(slot, request);

    private RequestCancellation?[] RetireReadsCore()
    {
        RequestCancellation?[] retire = [_listRequest, _detailRequest, _operationsRequest, _verificationRequest, _actionFlight];
        _listRequest = _detailRequest = _operationsRequest = _verificationRequest = null; _isActing = false;
        // Do not release _actionFlight here: a retired provider may ignore cancellation.
        return retire;
    }

    private void ClearRowsCore()
    {
        _items = Array.Empty<ServerTask>(); _operations = Array.Empty<OperationRecord>(); _counts = EmptyCounts();
        _detail = null; _total = 0; _nextCursor = _operationNextCursor = null;
        _hasLoaded = _hasLoadedOperations = _hasVerifiedOperations = false; _error = _operationsError = _verificationError = null;
        _taskCursors.Clear(); _operationCursors.Clear();
    }

    private void UpdateUnknownActionCore(OperationRecord record)
    {
        if (!NasTaskPresentationPolicy.IsTaskAction(record.Intent.Kind) || record.Intent.Options?.RecordId is not { } id) return;
        var reference = new ServerTaskRef(record.Intent.Reference.Account, id);
        if (NasTaskPresentationPolicy.NeedsReconciliation(record)) RememberUnknownActionCore(reference, record.Intent.Reference.Id);
        else if (record.Phase is OperationPhase.Acknowledged or OperationPhase.Rejected or OperationPhase.NotSent ||
            NasTaskPresentationPolicy.IsResolved(record.Reconciliation)) ClearUnknownActionCore(record.Intent.Reference);
    }

    private void ClearUnknownActionCore(OperationRef operation)
    {
        foreach (var pair in _unknownActions.Where(pair => pair.Key.Account == operation.Account).ToArray())
            if (pair.Value.Remove(operation.Id) && pair.Value.Count == 0) _unknownActions.Remove(pair.Key);
    }

    private void RememberUnknownActionCore(ServerTaskRef task, Guid operationId)
    {
        if (!_unknownActions.TryGetValue(task, out var operations)) _unknownActions.Add(task, operations = []);
        operations.Add(operationId);
    }

    private static bool TryFilter(ServerTaskQuery value, out ServerTaskQuery normalized)
    {
        normalized = value;
        if (value.Limit is < 1 or > 100 || value.From > value.To ||
            value.Type is { Length: > 0 } type && !NasTaskPresentationPolicy.IsKnownFileType(type) ||
            value.Statuses is { } statuses && statuses.Any(status => status == ServerTaskStatus.Unknown || !Enum.IsDefined(status))) return false;
        normalized = value with
        {
            Category = ServerTaskCategory.File, Cursor = null, Type = string.IsNullOrEmpty(value.Type) ? null : value.Type,
            Statuses = value.Statuses is null ? null : Array.AsReadOnly(value.Statuses.Distinct().ToArray()),
        };
        return true;
    }

    private static ServerTask[] ValidatePage(ServerTaskPage page, AccountKey account, int limit)
    {
        if (page is null || page.Items is null || page.Items.Count > limit || page.Total < page.Items.Count ||
            page.Counts is null || page.Counts.Any(count => count.Value < 0) || page.CategoryCounts is null || page.CategoryCounts.Any(category =>
                category.Value is null || category.Value.Any(count => count.Value < 0))) throw InvalidResponse();
        var ids = new HashSet<string>(StringComparer.Ordinal);
        var rows = new ServerTask[page.Items.Count];
        for (int index = 0; index < rows.Length; ++index)
        {
            var row = page.Items[index]; ValidateTask(row, account);
            if (!ids.Add(row.Reference.Id)) throw InvalidResponse();
            rows[index] = row;
        }
        return rows;
    }

    private static void ValidateTask(ServerTask task, AccountKey account)
    {
        if (task is null || task.Reference.Account != account || task.OwnerUserId != account.ServerUserId ||
            string.IsNullOrWhiteSpace(task.Reference.Id) || string.IsNullOrWhiteSpace(task.RawType) ||
            string.IsNullOrWhiteSpace(task.RawStatus) || task.TotalItems < 0 || task.ProcessedItems < 0 ||
            task.TotalBytes < 0 || task.ProcessedBytes < 0) throw InvalidResponse();
    }

    private static OperationRecord[] ValidateOperations(OperationPage page, AccountKey account, int limit)
    {
        if (page is null || page.Items is null || page.Items.Count > limit) throw InvalidResponse();
        var ids = new HashSet<Guid>();
        var rows = new OperationRecord[page.Items.Count];
        for (int index = 0; index < rows.Length; ++index)
        {
            var row = page.Items[index];
            if (row?.Intent is null || row.Intent.Reference.Account != account || row.Intent.Reference.Id == Guid.Empty ||
                row.Intent.Items is null || !ids.Add(row.Intent.Reference.Id) || !Enum.IsDefined(row.Phase) ||
                !Enum.IsDefined(row.Intent.Kind) || row.Revision < 0 ||
                NasTaskPresentationPolicy.IsTaskAction(row.Intent.Kind) && string.IsNullOrWhiteSpace(row.Intent.Options?.RecordId) ||
                row.Reconciliation is { } reconciliation && reconciliation.Reference != row.Intent.Reference ||
                row.Acknowledgement?.Task is { } task && task.Account != account ||
                row.Intent.Items.Any(item => item is null || item.Source?.Resource is { } source && source.Account != account ||
                    item.Target?.Resource is { } target && target.Account != account)) throw InvalidResponse();
            if (row.Reconciliation?.Task is { } observedTask) ValidateTask(observedTask, account);
            rows[index] = row;
        }
        return rows;
    }

    private static bool ValidAction(ServerTaskActionResult? result, ServerTask original, ServerTaskActionKind action)
    {
        if (result is null || result.Kind != action || !Enum.IsDefined(result.Outcome) ||
            result.Outcome == OperationOutcome.Confirmed && result.Error is not null ||
            result.Outcome == OperationOutcome.Partial && result.Error?.Code == ErrorCode.OutcomeUnknown) return false;
        if (result.Task is { } task)
        {
            try { ValidateTask(task, original.Reference.Account); }
            catch (FilewayException) { return false; }
            if (task.RawType != original.RawType || (action == ServerTaskActionKind.RetryAccepted
                ? task.Reference.Id == original.Reference.Id || task.RetryOf != original.Reference.Id
                : task.Reference != original.Reference)) return false;
        }
        if (result.Outcome != OperationOutcome.Confirmed) return true;
        return result.Task is { } confirmed && (action switch
        {
            ServerTaskActionKind.Archived => confirmed.ArchivedAt is not null,
            ServerTaskActionKind.Unarchived => confirmed.ArchivedAt is null,
            _ => true,
        });
    }

    private static ServerTask[] MergeTasks(IReadOnlyList<ServerTask> current, IReadOnlyList<ServerTask> incoming)
    {
        var merged = current.ToList();
        foreach (var row in incoming)
        {
            int index = merged.FindIndex(existing => existing.Reference == row.Reference);
            if (index < 0) merged.Add(row); else merged[index] = row;
        }
        return merged.ToArray();
    }

    private static OperationRecord[] MergeOperations(IReadOnlyList<OperationRecord> current, IReadOnlyList<OperationRecord> incoming)
    {
        var merged = current.ToList();
        foreach (var row in incoming)
        {
            int index = merged.FindIndex(existing => existing.Intent.Reference == row.Intent.Reference);
            if (index < 0) merged.Add(row); else if (row.Revision >= merged[index].Revision) merged[index] = row;
        }
        return merged.ToArray();
    }

    private static ReadOnlyDictionary<string, long> SnapshotCounts(IReadOnlyDictionary<string, long> counts)
    {
        if (counts.Any(pair => pair.Value < 0)) throw InvalidResponse();
        return new ReadOnlyDictionary<string, long>(new Dictionary<string, long>(counts, StringComparer.Ordinal));
    }
    private static ReadOnlyDictionary<string, long> EmptyCounts() =>
        new ReadOnlyDictionary<string, long>(new Dictionary<string, long>(StringComparer.Ordinal));
    private static ServerTaskActionResult UnknownAction(ServerTaskActionKind action, OperationReceipt? receipt) =>
        new(OperationOutcome.OutcomeUnknown, action, Receipt: receipt,
            Error: new(ErrorCode.OutcomeUnknown, "操作结果未知，请核实操作记录；不要重复提交。"));
    private static FilewayException InvalidResponse() => new(new(ErrorCode.ProtocolMismatch,
        "任务响应与当前请求不一致，请刷新后核实。"));
    private static string ReadError(Exception error, string fallback) =>
        NasTaskPresentationPolicy.SafeError((error as FilewayException)?.Error, fallback);
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

    private readonly record struct RequestContext(IServerTaskService Tasks, IOperationHistoryService? History,
        AccountKey Account, Guid SessionId, long Generation, long ViewGeneration);
}
