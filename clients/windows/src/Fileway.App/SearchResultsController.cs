using System.Collections.Immutable;
using System.Text;
using Fileway.Core.Contracts;

namespace Fileway.App;

/// <summary>
/// Read-only search state. Original result objects and opaque resource identities survive streaming;
/// context, query, view and metadata requests have independent retirement fences.
/// </summary>
internal sealed class SearchResultsController : IDisposable
{
    // FilewayClientServices.Search parses at most 32 records from one Host poll. This bounds one
    // state publication, not the total result count or the NAS's search limit.
    private const int MaximumBatchItems = 32;
    private static readonly UTF8Encoding StrictUtf8 = new(false, true);
    private readonly object _gate = new();
    private IServerSearchService? _search;
    private IFileRepository? _files;
    private ConnectionSession? _session;
    private RemoteResourceRef? _directory;
    private ImmutableList<SearchHit> _items = ImmutableList<SearchHit>.Empty;
    private RequestCancellation? _searchCancellation;
    private RequestCancellation? _openCancellation;
    private long _generation, _viewGeneration, _searchEpoch, _openEpoch, _receivedCount;
    private bool _viewActive = true, _disposed, _isSearching, _isOpening;
    private bool _changedDeferred;
    private SearchEnd? _end;
    private string? _error;

    // Notifications carry no stale snapshot. A UI consumer dispatches and reads the current state.
    internal event EventHandler? Changed;
    internal IReadOnlyList<SearchHit> Items { get { lock (_gate) return _items; } }
    internal long ReceivedCount { get { lock (_gate) return _receivedCount; } }
    internal bool IsSearching { get { lock (_gate) return _isSearching; } }
    internal bool IsOpening { get { lock (_gate) return _isOpening; } }
    internal SearchEnd? End { get { lock (_gate) return _end; } }
    internal string? Error { get { lock (_gate) return _error; } }
    internal long Generation { get { lock (_gate) return _generation; } }
    internal long ViewGeneration { get { lock (_gate) return _viewGeneration; } }

    internal void SetContext(IServerSearchService? search, IFileRepository? files, ConnectionSession? session,
        RemoteResourceRef? baseDirectory)
    {
        RequestCancellation? previousSearch, previousOpen;
        lock (_gate)
        {
            if (_disposed) return;
            if (ReferenceEquals(_search, search) && ReferenceEquals(_files, files) &&
                _session?.Account == session?.Account && _session?.Id == session?.Id && _directory == baseDirectory)
            { _session = session; return; }
            _search = search; _files = files; _session = session; _directory = baseDirectory;
            ++_generation; ++_viewGeneration; ++_searchEpoch; ++_openEpoch;
            previousSearch = _searchCancellation; previousOpen = _openCancellation;
            _searchCancellation = _openCancellation = null;
            _isSearching = _isOpening = false;
            _items = ImmutableList<SearchHit>.Empty; _receivedCount = 0; _end = null; _error = null;
        }
        previousSearch?.Cancel(); previousOpen?.Cancel(); PublishChanged();
    }

    internal async Task SearchAsync(string query, SearchScope scope, CancellationToken cancellationToken = default)
    {
        RequestContext context;
        RequestCancellation? cancellation;
        RequestCancellation? previousSearch, previousOpen;
        long epoch;
        lock (_gate)
        {
            if (!HasContext()) return;
            _viewActive = true; ++_viewGeneration; epoch = ++_searchEpoch; ++_openEpoch;
            context = CaptureContext();
            previousSearch = _searchCancellation; previousOpen = _openCancellation;
            _searchCancellation = _openCancellation = null;
            _items = ImmutableList<SearchHit>.Empty; _receivedCount = 0; _end = null; _error = null;
            _isOpening = false;
            if (!ValidQuery(query, scope))
            {
                _end = FailureEnd(ErrorCode.InvalidRequest); _error = _end.Error!.Message;
                cancellation = null; _isSearching = false;
            }
            else
            {
                cancellation = new RequestCancellation(cancellationToken);
                _searchCancellation = cancellation; _isSearching = true;
            }
        }
        previousSearch?.Cancel(); previousOpen?.Cancel(); PublishChanged();
        if (cancellation is null) return;
        try
        {
            cancellation.Token.ThrowIfCancellationRequested();
            await foreach (var update in context.Search.SearchAsync(new(context.Directory, query, scope), cancellation.Token)
                .WithCancellation(cancellation.Token))
            {
                bool terminal;
                lock (_gate)
                {
                    if (!IsCurrentSearch(context, epoch, cancellation) || cancellation.IsCancellationRequested) return;
                    var rows = ValidateBatch(update, context.Account, _receivedCount, _items.Count);
                    // Persistent immutable snapshots append without repeatedly copying the entire result set.
                    _items = _items.AddRange(rows); _receivedCount = update.ReceivedCount;
                    _end = SafeEnd(update.End); _error = _end?.Error?.Message;
                    terminal = _end is not null;
                    if (terminal) _isSearching = false;
                }
                PublishChanged();
                if (terminal) return; // Disposes the iterator, including its original search handle.
            }
            lock (_gate)
                if (IsCurrentSearch(context, epoch, cancellation) && !cancellation.IsCancellationRequested && _end is null)
                { _end = FailureEnd(ErrorCode.ProtocolMismatch); _error = _end.Error!.Message; }
        }
        catch (Exception error)
        {
            lock (_gate)
                if (IsCurrentSearch(context, epoch, cancellation))
                {
                    _end = cancellation.IsCancellationRequested ? new(SearchEndReason.Canceled) : FailureEnd(ErrorCodeOf(error));
                    _error = _end.Error?.Message;
                }
        }
        finally
        {
            bool current;
            lock (_gate)
            {
                current = IsCurrentSearch(context, epoch, cancellation);
                if (current)
                {
                    if (cancellation.IsCancellationRequested && _end is null) _end = new(SearchEndReason.Canceled);
                    _isSearching = false; _searchCancellation = null;
                }
            }
            cancellation.Dispose();
            if (current) PublishChanged();
        }
    }

    internal void Cancel()
    {
        RequestCancellation? search, open;
        lock (_gate)
        {
            if (_disposed) return;
            search = _searchCancellation; open = _openCancellation;
            _searchCancellation = _openCancellation = null; ++_searchEpoch; ++_openEpoch;
            if (_isSearching) _end = new(SearchEndReason.Canceled);
            _isSearching = _isOpening = false; _error = null;
        }
        search?.Cancel(); open?.Cancel(); PublishChanged();
    }

    internal bool CanOpen(SearchHit hit)
    {
        ArgumentNullException.ThrowIfNull(hit);
        lock (_gate) return CanOpenCore(hit);
    }

    internal async Task OpenAsync(SearchHit hit, Func<RemoteFile, Task> opened,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(hit); ArgumentNullException.ThrowIfNull(opened);
        RequestContext context;
        RequestCancellation cancellation;
        RequestCancellation? search;
        long epoch;
        lock (_gate)
        {
            if (!CanOpenCore(hit)) return;
            search = _searchCancellation; _searchCancellation = null; ++_searchEpoch;
            if (_isSearching) _end = new(SearchEndReason.Canceled);
            _isSearching = false; _error = null;
            context = CaptureContext(); epoch = ++_openEpoch;
            cancellation = new RequestCancellation(cancellationToken); _openCancellation = cancellation; _isOpening = true;
        }
        search?.Cancel(); PublishChanged();
        try
        {
            cancellation.Token.ThrowIfCancellationRequested();
            var file = await context.Files.GetAsync(hit.Resource!, cancellation.Token);
            Task openedTask;
            lock (_gate)
            {
                if (!IsCurrentOpen(context, epoch, cancellation) || cancellation.IsCancellationRequested || !Contains(hit)) return;
                if (file?.Resource is not { } resource || resource.Account != context.Account ||
                    !string.Equals(resource.WirePath, hit.Resource!.WirePath, StringComparison.Ordinal) ||
                    file.IsDirectory != hit.IsDirectory || file.Size < 0)
                    throw InvalidResponse();
                // Match RecentAccessListController's final handoff gate. Once started, retirement
                // fences this request but does not close a media surface already owned by the caller.
                openedTask = opened(file);
            }
            // A synchronous handoff may reenter this controller. Its notifications become
            // visible only after the outer handoff gate is released, before awaiting media work.
            FlushDeferredChanged();
            await openedTask;
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            lock (_gate)
                if (IsCurrentOpen(context, epoch, cancellation) && !cancellation.IsCancellationRequested)
                    _error = SafeMessage(ErrorCodeOf(error), opening: true, error is FilewayException known ? known.Error.HttpStatus : null);
        }
        finally
        {
            bool current;
            lock (_gate)
            {
                current = IsCurrentOpen(context, epoch, cancellation);
                if (current) { _isOpening = false; _openCancellation = null; }
            }
            cancellation.Dispose();
            if (current) PublishChanged();
            else FlushDeferredChanged(); // Also flush when a reentrant callback retires and throws.
        }
    }

    internal void SuspendView()
    {
        RequestCancellation? search, open;
        lock (_gate)
        {
            if (_disposed) return;
            _viewActive = false; ++_viewGeneration; ++_searchEpoch; ++_openEpoch;
            search = _searchCancellation; open = _openCancellation;
            _searchCancellation = _openCancellation = null;
            if (_isSearching) _end = new(SearchEndReason.Canceled);
            _isSearching = _isOpening = false; _error = null;
        }
        search?.Cancel(); open?.Cancel(); PublishChanged();
    }

    public void Dispose()
    {
        RequestCancellation? search, open;
        lock (_gate)
        {
            if (_disposed) return;
            _disposed = true; _viewActive = false; ++_generation; ++_viewGeneration; ++_searchEpoch; ++_openEpoch;
            search = _searchCancellation; open = _openCancellation; _searchCancellation = _openCancellation = null;
            _search = null; _files = null; _session = null; _directory = null;
            _items = ImmutableList<SearchHit>.Empty; _receivedCount = 0;
            _isSearching = _isOpening = false; _end = null; _error = null;
        }
        search?.Cancel(); open?.Cancel(); PublishChanged();
    }

    private bool HasContext() => !_disposed && _search is not null && _files is not null &&
        _session is { Id: var id } && id != Guid.Empty && _directory?.Account == _session.Account;
    private bool CanOpenCore(SearchHit hit) => HasContext() && _viewActive && !_isOpening &&
        _end?.Reason != SearchEndReason.SessionChanged && Contains(hit) &&
        hit.PathConfidence is SearchPathConfidence.ServerWire or SearchPathConfidence.LegacyUtf8 &&
        hit.Resource is { } resource && resource.Account == _session!.Account && !string.IsNullOrEmpty(resource.WirePath);
    private bool Contains(SearchHit hit) => _items.Any(item => ReferenceEquals(item, hit));
    private RequestContext CaptureContext() => new(_search!, _files!, _directory!, _session!.Account, _session.Id, _generation, _viewGeneration);
    private bool IsCurrent(RequestContext context) => HasContext() && _viewActive && _generation == context.Generation &&
        _viewGeneration == context.ViewGeneration && _session!.Account == context.Account && _session.Id == context.SessionId &&
        ReferenceEquals(_search, context.Search) && ReferenceEquals(_files, context.Files) && _directory == context.Directory;
    private bool IsCurrentSearch(RequestContext context, long epoch, RequestCancellation cancellation) =>
        IsCurrent(context) && epoch == _searchEpoch && ReferenceEquals(_searchCancellation, cancellation);
    private bool IsCurrentOpen(RequestContext context, long epoch, RequestCancellation cancellation) =>
        IsCurrent(context) && epoch == _openEpoch && ReferenceEquals(_openCancellation, cancellation);

    private static bool ValidQuery(string query, SearchScope scope)
    {
        if (string.IsNullOrWhiteSpace(query) || !Enum.IsDefined(scope)) return false;
        try { return StrictUtf8.GetByteCount(query) <= 8192; }
        catch (EncoderFallbackException) { return false; }
    }

    private static SearchHit[] ValidateBatch(SearchUpdate update, AccountKey account, long previousCount, int previousItems)
    {
        if (update?.Items is not { } rows || rows.Count > MaximumBatchItems || update.ReceivedCount < previousCount ||
            update.ReceivedCount < (long)previousItems + rows.Count ||
            update.End is { } end && (!Enum.IsDefined(end.Reason) || end.ServerCount < 0 ||
                update.ReceivedCount != (long)previousItems + rows.Count)) throw InvalidResponse();
        var snapshot = new SearchHit[rows.Count];
        for (int index = 0; index < rows.Count; ++index)
        {
            var hit = rows[index];
            if (hit is null || string.IsNullOrEmpty(hit.RelativeDisplayPath) || string.IsNullOrEmpty(hit.DisplayName) ||
                hit.Size < 0 || hit.Resource is { } resource && resource.Account != account) throw InvalidResponse();
            snapshot[index] = hit;
        }
        return snapshot;
    }

    private static SearchEnd? SafeEnd(SearchEnd? end) => end?.Error is { } error
        ? end with { Error = new(error.Code, SafeMessage(error.Code, opening: false, error.HttpStatus), error.HttpStatus, error.Retryable) }
        : end;
    private static SearchEnd FailureEnd(ErrorCode code) => new(code switch
    {
        ErrorCode.SourceChanged => SearchEndReason.SessionChanged,
        ErrorCode.Canceled => SearchEndReason.Canceled,
        _ => SearchEndReason.Failed,
    }, Error: new(code, SafeMessage(code, opening: false)));
    private static FilewayException InvalidResponse() => new(new(ErrorCode.ProtocolMismatch, SafeMessage(ErrorCode.ProtocolMismatch, opening: false)));
    private static ErrorCode ErrorCodeOf(Exception error) => error switch
    {
        FilewayException known => known.Error.Code,
        OperationCanceledException => ErrorCode.Timeout,
        _ => ErrorCode.Unknown,
    };
    private static string SafeMessage(ErrorCode code, bool opening, int? httpStatus = null) => httpStatus switch
    {
        403 => "当前账号没有访问权限。",
        404 => opening ? "此搜索结果对应的文件已不存在，请重新搜索。" : "搜索服务暂不可用，请重试。",
        _ => code switch
        {
            ErrorCode.InvalidRequest => "请输入有效的搜索条件并选择搜索范围。",
            ErrorCode.AuthenticationExpired => "登录已过期，请重新连接。",
            ErrorCode.PermissionDenied => "当前账号没有访问权限。",
            ErrorCode.SourceChanged => "连接已变更，请重新搜索。",
            ErrorCode.Canceled => "搜索已取消，已保留收到的结果。",
            ErrorCode.NetworkUnavailable => "网络暂不可用，请重试。",
            ErrorCode.Timeout => "请求超时，已保留收到的搜索结果。",
            ErrorCode.ProtocolMismatch => "搜索结果与当前资源不一致，请重新搜索。",
            ErrorCode.HostExited => "连接服务已退出，请重新连接。",
            _ => opening ? "暂时无法打开此搜索结果，请重试。" : "搜索未能完成，已保留收到的结果。",
        },
    };

    private void PublishChanged()
    {
        if (Monitor.IsEntered(_gate))
        {
            // Exiting a reentrant method's lock does not release its caller's handoff lock.
            _changedDeferred = true;
            return;
        }
        lock (_gate) _changedDeferred = false;
        var handlers = Changed;
        if (handlers is null) return;
        foreach (EventHandler handler in handlers.GetInvocationList())
            try { handler(this, EventArgs.Empty); }
            catch (Exception) { /* A presentation subscriber never owns request authority or progress. */ }
    }

    private void FlushDeferredChanged()
    {
        if (Monitor.IsEntered(_gate)) return; // A nested handoff leaves this to its outer owner.
        bool deferred;
        lock (_gate) { deferred = _changedDeferred; _changedDeferred = false; }
        if (deferred) PublishChanged();
    }

    // Same retirement pattern as RecentAccessListController: cancellation callbacks do not run
    // on the caller's UI stack, and source disposal waits for their actual completion.
    private sealed class RequestCancellation : IDisposable
    {
        private readonly object _gate = new();
        private readonly CancellationTokenSource _source;
        private Task? _cancelTask;
        private bool _disposed;
        internal RequestCancellation(CancellationToken caller)
        {
            _source = CancellationTokenSource.CreateLinkedTokenSource(caller);
            Token = _source.Token;
        }
        internal CancellationToken Token { get; }
        internal bool IsCancellationRequested => Token.IsCancellationRequested;
        internal void Cancel()
        {
            lock (_gate)
            {
                if (_disposed) return;
                _cancelTask ??= _source.CancelAsync();
                _ = ObserveAsync(_cancelTask);
            }
        }
        public void Dispose()
        {
            Task? cancellation;
            lock (_gate)
            {
                if (_disposed) return;
                _disposed = true; cancellation = _cancelTask;
                if (cancellation is null || cancellation.IsCompleted) { _source.Dispose(); return; }
            }
            _ = DisposeAfterCancellationAsync(_source, cancellation);
        }
        private static async Task ObserveAsync(Task cancellation)
        {
            try { await cancellation.ConfigureAwait(false); }
            catch (Exception) { /* Provider token callbacks cannot replace a search's safe terminal. */ }
        }
        private static async Task DisposeAfterCancellationAsync(CancellationTokenSource source, Task cancellation)
        { await ObserveAsync(cancellation).ConfigureAwait(false); source.Dispose(); }
    }

    private readonly record struct RequestContext(IServerSearchService Search, IFileRepository Files, RemoteResourceRef Directory,
        AccountKey Account, Guid SessionId, long Generation, long ViewGeneration);
}
