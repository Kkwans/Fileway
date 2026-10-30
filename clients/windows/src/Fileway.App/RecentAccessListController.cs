using System.Globalization;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;

namespace Fileway.App;

/// <summary>A read-only projection of the server's original history row.</summary>
public sealed class RecentAccessRow
{
    public RecentAccessRow(RecentAccessEntry entry, Func<DateTimeOffset, string>? formatter = null)
    {
        ArgumentNullException.ThrowIfNull(entry);
        Entry = entry;
        try
        {
            AccessedTime = formatter is null
                ? entry.AccessedAt.ToLocalTime().ToString("g", CultureInfo.CurrentCulture)
                : formatter(entry.AccessedAt);
        }
        catch (Exception)
        {
            AccessedTime = entry.AccessedAt.ToUniversalTime().ToString("u", CultureInfo.InvariantCulture);
        }
    }

    public RecentAccessEntry Entry { get; }
    public string Name => Entry.DisplayName;
    public string DisplayPath => Entry.DisplayPath;
    public string IconName => Entry.IsDirectory ? "folder" : "file";
    public string AccessedTime { get; }
    public string IdentityHint => Entry.PathConfidence switch
    {
        RecentAccessPathConfidence.ServerWire when CanOpen => Hint("服务器路径", Decode(Entry.Resource!.WirePath)),
        RecentAccessPathConfidence.LegacyUtf8 when CanOpen => Hint("兼容路径", Decode(Entry.Resource!.WirePath)),
        _ => "路径待确认",
    };
    public Visibility IdentityVisibility => IdentityHint.Length == 0 ? Visibility.Collapsed : Visibility.Visible;

    private string Hint(string label, string path) =>
        string.Equals(path, Entry.DisplayPath, StringComparison.Ordinal) ? "" : label + " · " + path;

    private static string Decode(string wirePath)
    {
        try { return Uri.UnescapeDataString(wirePath); }
        catch (Exception) { return wirePath; }
    }
    public string AccessibleName => $"{Name}，{DisplayPath}，{AccessedTime}，{IdentityHint}";
    public bool CanOpen => HasTrustedResource(Entry);

    internal static bool HasTrustedResource(RecentAccessEntry entry) =>
        entry.PathConfidence is RecentAccessPathConfidence.ServerWire or RecentAccessPathConfidence.LegacyUtf8 &&
        entry.Resource is { } resource && resource.Account == entry.Account && !string.IsNullOrEmpty(resource.WirePath);
}

/// <summary>
/// Bounded, read-only recent history state. Context, list requests and view/open activations
/// have separate fences; a retired request cannot update a replacement view or open media.
/// </summary>
internal sealed class RecentAccessListController : IDisposable
{
    private readonly object _gate = new();
    private IRecentAccessService? _recent;
    private IFileRepository? _files;
    private ConnectionSession? _session;
    private IReadOnlyList<RecentAccessEntry> _items = Array.Empty<RecentAccessEntry>();
    private RequestCancellation? _listCancellation;
    private RequestCancellation? _openCancellation;
    private long _generation;
    private long _viewGeneration;
    private long _listEpoch;
    private long _openEpoch;
    private bool _viewActive = true;
    private bool _disposed;
    private bool _isLoading;
    private bool _isOpening;
    private bool _hasLoaded;
    private string? _error;

    internal IReadOnlyList<RecentAccessEntry> Items { get { lock (_gate) return _items; } }
    internal bool IsLoading { get { lock (_gate) return _isLoading; } }
    internal bool IsOpening { get { lock (_gate) return _isOpening; } }
    internal bool HasLoaded { get { lock (_gate) return _hasLoaded; } }
    internal string? Error { get { lock (_gate) return _error; } }
    internal long Generation { get { lock (_gate) return _generation; } }
    internal long ViewGeneration { get { lock (_gate) return _viewGeneration; } }

    internal void SetContext(IRecentAccessService? recent, IFileRepository? files, ConnectionSession? session)
    {
        RequestCancellation? list;
        RequestCancellation? open;
        lock (_gate)
        {
            if (_disposed) return;
            if (ReferenceEquals(_recent, recent) && ReferenceEquals(_files, files) &&
                _session?.Account == session?.Account && _session?.Id == session?.Id)
            {
                _session = session;
                return;
            }
            _recent = recent;
            _files = files;
            _session = session;
            ++_generation;
            ++_viewGeneration;
            ++_listEpoch;
            ++_openEpoch;
            list = _listCancellation;
            open = _openCancellation;
            _listCancellation = _openCancellation = null;
            _isLoading = _isOpening = _hasLoaded = false;
            _items = Array.Empty<RecentAccessEntry>();
            _error = null;
        }
        Cancel(list);
        Cancel(open);
    }

    internal async Task RefreshAsync(CancellationToken cancellationToken = default)
    {
        RequestContext context;
        long epoch;
        RequestCancellation cancellation;
        RequestCancellation? previousList;
        RequestCancellation? previousOpen;
        lock (_gate)
        {
            if (_disposed || _recent is null || _files is null || _session is null) return;
            _viewActive = true;
            ++_viewGeneration;
            epoch = ++_listEpoch;
            ++_openEpoch;
            context = CaptureContext();
            previousList = _listCancellation;
            previousOpen = _openCancellation;
            cancellation = new RequestCancellation(cancellationToken);
            _listCancellation = cancellation;
            _openCancellation = null;
            _isLoading = true;
            _isOpening = false;
            _error = null;
        }
        Cancel(previousList);
        Cancel(previousOpen);
        try
        {
            cancellation.Token.ThrowIfCancellationRequested();
            var entries = await context.Recent.ListRecentAsync(context.Account, cancellation.Token);
            RequestCancellation? staleOpen;
            lock (_gate)
            {
                if (!IsCurrentList(context, epoch, cancellation) || cancellation.IsCancellationRequested) return;
                var snapshot = ValidateList(entries, context.Account);
                // New server rows replace old row objects, even when their display/ID is unchanged.
                ++_openEpoch;
                staleOpen = _openCancellation;
                _openCancellation = null;
                _isOpening = false;
                _items = Array.AsReadOnly(snapshot);
                _hasLoaded = true;
                _error = null;
            }
            Cancel(staleOpen);
        }
        catch (OperationCanceledException)
        {
            // Cancellation is a view/request retirement, not a user-facing read failure.
        }
        catch (Exception error)
        {
            lock (_gate)
                if (IsCurrentList(context, epoch, cancellation) && !cancellation.IsCancellationRequested)
                    _error = ReadError(error, opening: false);
        }
        finally
        {
            lock (_gate)
            {
                if (IsCurrentList(context, epoch, cancellation))
                {
                    _isLoading = false;
                    _listCancellation = null;
                }
            }
            cancellation.Dispose();
        }
    }

    internal bool CanOpen(RecentAccessEntry entry)
    {
        ArgumentNullException.ThrowIfNull(entry);
        lock (_gate) return CanOpenCore(entry);
    }

    internal async Task OpenAsync(RecentAccessEntry entry, Func<RemoteFile, Task> opened,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(entry);
        ArgumentNullException.ThrowIfNull(opened);
        RequestContext context;
        long epoch;
        RequestCancellation cancellation;
        lock (_gate)
        {
            if (!CanOpenCore(entry)) return;
            context = CaptureContext();
            epoch = ++_openEpoch;
            cancellation = new RequestCancellation(cancellationToken);
            _openCancellation = cancellation;
            _isOpening = true;
            _error = null;
        }
        try
        {
            cancellation.Token.ThrowIfCancellationRequested();
            // Use the retained opaque reference exactly; display paths never become identity.
            var file = await context.Files.GetAsync(entry.Resource!, cancellation.Token);
            Task openTask;
            lock (_gate)
            {
                // Fence immediately after metadata resolution, before invoking the callback.
                if (!IsCurrentOpen(context, epoch, cancellation) || cancellation.IsCancellationRequested ||
                    !ContainsEntry(entry)) return;
                if (file is null || file.Resource is null || file.Resource.Account != context.Account ||
                    !string.Equals(file.Resource.WirePath, entry.Resource!.WirePath, StringComparison.Ordinal))
                    throw InvalidResponse();
                openTask = opened(file);
            }
            // Once the callback has begun, view retirement never closes its media surface.
            await openTask;
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception error)
        {
            lock (_gate)
                if (IsCurrentOpen(context, epoch, cancellation) && !cancellation.IsCancellationRequested)
                    _error = ReadError(error, opening: true);
        }
        finally
        {
            lock (_gate)
            {
                if (IsCurrentOpen(context, epoch, cancellation))
                {
                    _isOpening = false;
                    _openCancellation = null;
                }
            }
            cancellation.Dispose();
        }
    }

    internal void SuspendView()
    {
        RequestCancellation? list;
        RequestCancellation? open;
        lock (_gate)
        {
            if (_disposed) return;
            _viewActive = false;
            ++_viewGeneration;
            ++_listEpoch;
            ++_openEpoch;
            list = _listCancellation;
            open = _openCancellation;
            _listCancellation = _openCancellation = null;
            _isLoading = _isOpening = false;
            _error = null;
        }
        Cancel(list);
        Cancel(open);
    }

    public void Dispose()
    {
        RequestCancellation? list;
        RequestCancellation? open;
        lock (_gate)
        {
            if (_disposed) return;
            _disposed = true;
            _viewActive = false;
            ++_generation;
            ++_viewGeneration;
            ++_listEpoch;
            ++_openEpoch;
            list = _listCancellation;
            open = _openCancellation;
            _listCancellation = _openCancellation = null;
            _isLoading = _isOpening = _hasLoaded = false;
            _error = null;
            _recent = null;
            _files = null;
            _session = null;
            _items = Array.Empty<RecentAccessEntry>();
        }
        Cancel(list);
        Cancel(open);
    }

    private bool CanOpenCore(RecentAccessEntry entry) => !_disposed && _viewActive && !_isLoading && !_isOpening &&
        _recent is not null && _files is not null && _session is not null && entry.Account == _session.Account &&
        RecentAccessRow.HasTrustedResource(entry) && ContainsEntry(entry);

    private bool ContainsEntry(RecentAccessEntry entry) => _items.Any(item => ReferenceEquals(item, entry));

    private RequestContext CaptureContext() => new(_recent!, _files!, _session!.Account, _session.Id,
        _generation, _viewGeneration);

    private bool IsCurrentContext(RequestContext context) => !_disposed && _viewActive &&
        _generation == context.Generation && _viewGeneration == context.ViewGeneration &&
        _session?.Account == context.Account && _session?.Id == context.SessionId &&
        ReferenceEquals(_recent, context.Recent) && ReferenceEquals(_files, context.Files);

    private bool IsCurrentList(RequestContext context, long epoch, RequestCancellation cancellation) =>
        IsCurrentContext(context) && epoch == _listEpoch && ReferenceEquals(_listCancellation, cancellation);

    private bool IsCurrentOpen(RequestContext context, long epoch, RequestCancellation cancellation) =>
        IsCurrentContext(context) && epoch == _openEpoch && ReferenceEquals(_openCancellation, cancellation);

    private static RecentAccessEntry[] ValidateList(IReadOnlyList<RecentAccessEntry> entries, AccountKey account)
    {
        if (entries is null || entries.Count > 100) throw InvalidResponse();
        var ids = new HashSet<string>(StringComparer.Ordinal);
        var snapshot = new RecentAccessEntry[entries.Count];
        for (int index = 0; index < entries.Count; ++index)
        {
            var entry = entries[index];
            if (entry is null || entry.Account != account || string.IsNullOrWhiteSpace(entry.Id) || !ids.Add(entry.Id) ||
                entry.Resource is { } resource && resource.Account != account)
                throw InvalidResponse();
            snapshot[index] = entry;
        }
        return snapshot;
    }

    private static FilewayException InvalidResponse() => new(new(ErrorCode.ProtocolMismatch,
        "最近访问记录与当前连接不一致，请刷新后重试。"));

    private static string ReadError(Exception error, bool opening) => error is FilewayException fileway
        ? fileway.Error.HttpStatus switch
        {
            403 => "当前账号没有访问权限。",
            404 => opening ? "此记录对应的文件已不存在，请刷新后重试。" : "最近访问服务暂不可用，请重试。",
            _ => ErrorMessage(fileway.Error.Code, opening),
        }
        : opening ? "暂时无法打开此记录，请刷新后重试。" : "暂时无法读取最近访问，请重试。";

    private static string ErrorMessage(ErrorCode code, bool opening) => code switch
        {
            ErrorCode.AuthenticationExpired => "登录已过期，请重新连接。",
            ErrorCode.PermissionDenied => "当前账号没有访问权限。",
            ErrorCode.NetworkUnavailable => "网络暂不可用，请重试。",
            ErrorCode.Timeout => "请求超时，请重试。",
            ErrorCode.SourceChanged => "连接已变更，请刷新后重试。",
            ErrorCode.ProtocolMismatch => "最近访问记录与当前连接不一致，请刷新后重试。",
            ErrorCode.HostExited => "连接服务已退出，请重新连接。",
            _ => opening ? "暂时无法打开此记录，请刷新后重试。" : "暂时无法读取最近访问，请重试。",
        };

    private static void Cancel(RequestCancellation? cancellation) => cancellation?.Cancel();

    /// <summary>Retirement signals cancellation immediately without running provider callbacks on the UI thread.</summary>
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
            Task cancellation;
            lock (_gate)
            {
                if (_disposed) return;
                cancellation = _cancelTask ??= _source.CancelAsync();
            }
            _ = ObserveAsync(cancellation);
        }

        public void Dispose()
        {
            Task? cancellation;
            lock (_gate)
            {
                if (_disposed) return;
                _disposed = true;
                cancellation = _cancelTask;
                if (cancellation is null || cancellation.IsCompleted)
                {
                    _source.Dispose();
                    return;
                }
            }
            // The request owns disposal, deferred until any in-flight provider callbacks finish.
            _ = DisposeAfterCancellationAsync(_source, cancellation);
        }

        private static async Task ObserveAsync(Task cancellation)
        {
            try { await cancellation.ConfigureAwait(false); }
            catch (Exception) { /* A provider cancellation callback must not surface as a current read failure. */ }
        }

        private static async Task DisposeAfterCancellationAsync(CancellationTokenSource source, Task cancellation)
        {
            await ObserveAsync(cancellation).ConfigureAwait(false);
            source.Dispose();
        }
    }

    private readonly record struct RequestContext(IRecentAccessService Recent, IFileRepository Files,
        AccountKey Account, Guid SessionId, long Generation, long ViewGeneration);
}
