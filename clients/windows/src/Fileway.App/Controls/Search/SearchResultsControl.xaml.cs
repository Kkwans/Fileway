using System.Collections.ObjectModel;
using Fileway.Core.Contracts;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using VirtualKey = Windows.System.VirtualKey;

namespace Fileway.App.Controls.Search;

/// <summary>A read-only streaming search view; displayed paths never establish resource identity.</summary>
public sealed partial class SearchResultsControl : UserControl, IDisposable
{
    private const int MaximumRowsPerRender = 128;
    private readonly SearchResultsController _controller = new();
    private readonly ObservableCollection<SearchResultRow> _rows = [];
    private IServerSearchService? _search;
    private IFileRepository? _files;
    private ConnectionSession? _session;
    private RemoteResourceRef? _directory;
    private Func<RemoteFile, Task>? _openResourceAsync;
    private string? _lastQuery;
    private SearchScope _lastScope = SearchScope.Recursive;
    private string? _presentationError;
    private long _viewRevision, _handoffRevision, _compositionRevision, _renderedGeneration = -1;
    private int _renderQueued;
    private volatile bool _disposed;
    private bool _viewActive, _suspended, _rendering, _lastActionOpening, _queryComposing;

    public event EventHandler? CloseRequested;
    public Func<RemoteFile, Task>? OpenResourceAsync
    {
        get => _openResourceAsync;
        set { if (!_disposed) { _openResourceAsync = value; QueueRender(); } }
    }

    public SearchResultsControl()
    {
        InitializeComponent(); Fileway.App.Controls.FloatingNotice.Attach(SearchNotice);
        SearchRecords.ItemsSource = _rows;
        _controller.Changed += Controller_Changed;
        QueryBox.TextCompositionStarted += (_, _) => { ++_compositionRevision; _queryComposing = true; UpdateCommands(); };
        QueryBox.TextCompositionEnded += (_, _) =>
        {
            // An IME commit can finish before its Enter/Escape reaches bubbling KeyDown.
            // Retain the guard until that input message has finished dispatching.
            var revision = ++_compositionRevision;
            DispatcherQueue.TryEnqueue(() =>
            {
                if (!_disposed && revision == _compositionRevision) { _queryComposing = false; UpdateCommands(); }
            });
        };
        Unloaded += (_, _) => SuspendView();
        Render();
    }

    public void SetContext(IServerSearchService? search, IFileRepository? files, ConnectionSession? session,
        RemoteResourceRef? baseDirectory) => OnUi(() =>
    {
        var generation = _controller.Generation;
        _search = search; _files = files; _session = session; _directory = baseDirectory;
        _controller.SetContext(search, files, session, baseDirectory);
        if (generation != _controller.Generation)
        {
            ++_viewRevision; ++_handoffRevision;
            ++_compositionRevision; _queryComposing = false;
            _lastQuery = null; _presentationError = null; _suspended = false;
            ResetRows();
        }
        // A repeated context refresh must leave the user's unsubmitted query/scope untouched.
        QueueRender();
    });

    public Task SearchAsync(string query, SearchScope scope) => OnUiAsync(() => SearchCoreAsync(query, scope));

    public Task RefreshAsync() => OnUiAsync(() =>
    {
        if (string.IsNullOrWhiteSpace(_lastQuery)) { FocusQuery(); return Task.CompletedTask; }
        return SearchCoreAsync(_lastQuery, _lastScope);
    });

    public void FocusQuery() => OnUi(() =>
    {
        _viewActive = true;
        QueryBox.Focus(FocusState.Keyboard); QueryBox.SelectAll();
        QueueRender();
    });

    public void SuspendView() => OnUi(() =>
    {
        _viewActive = false; _suspended = true;
        ++_compositionRevision; _queryComposing = false;
        ++_viewRevision; ++_handoffRevision;
        _controller.SuspendView();
        UpdateCommands();
    });

    private bool HasContext => !_disposed && _search is not null && _files is not null
        && _session is { Id: var id } && id != Guid.Empty && _directory?.Account == _session.Account;

    private async Task SearchCoreAsync(string query, SearchScope scope)
    {
        if (_disposed) return;
        _viewActive = true; _suspended = false; _lastActionOpening = false;
        var revision = ++_viewRevision; ++_handoffRevision;
        _lastQuery = query; _lastScope = scope; _presentationError = null;
        QueryBox.Text = query;
        ScopeBox.SelectedIndex = scope switch { SearchScope.Current => 0, SearchScope.Recursive => 1, _ => -1 };
        ResetRows();
        if (!HasContext)
        {
            _presentationError = "连接不可用，请连接服务器后重新搜索。";
            Render(); return;
        }
        try
        {
            var pending = _controller.SearchAsync(query, scope);
            Render();
            await pending;
        }
        catch (Exception)
        {
            if (!_disposed && revision == _viewRevision)
                _presentationError = "搜索未能完成，请重试。已收到的结果会保留。";
        }
        if (!_disposed && revision == _viewRevision) QueueRender();
    }

    private void Controller_Changed(object? sender, EventArgs args) => QueueRender();

    private void QueueRender()
    {
        if (_disposed || Interlocked.Exchange(ref _renderQueued, 1) != 0) return;
        if (!DispatcherQueue.TryEnqueue(DispatcherQueuePriority.Low, () =>
        {
            Interlocked.Exchange(ref _renderQueued, 0);
            if (!_disposed) Render(); // Read current state, never a notification's old snapshot.
        })) Interlocked.Exchange(ref _renderQueued, 0);
    }

    private void Render()
    {
        if (_disposed) return;
        var generation = _controller.Generation;
        var items = _controller.Items;
        if (_renderedGeneration != generation)
        {
            ResetRows(); _renderedGeneration = generation;
        }
        if (_viewActive && !_suspended)
        {
            if (_rows.Count > items.Count || _rows.Count > 0 &&
                !ReferenceEquals(_rows[^1].Hit, items[_rows.Count - 1])) ResetRows();
            _rendering = true;
            try
            {
                var count = Math.Min(items.Count, _rows.Count + MaximumRowsPerRender);
                while (_rows.Count < count) _rows.Add(new SearchResultRow(items[_rows.Count]));
            }
            finally { _rendering = false; }
        }

        var searching = _controller.IsSearching;
        var opening = _controller.IsOpening;
        var end = _controller.End;
        var error = _presentationError ?? _controller.Error;
        ConfirmedQueryText.Visibility = _lastQuery is null ? Visibility.Collapsed : Visibility.Visible;
        ConfirmedQueryText.Text = _lastQuery is null ? "" : "搜索条件：" + _lastQuery;
        ToolTipService.SetToolTip(ConfirmedQueryText, _lastQuery);
        SearchActivityRing.IsActive = searching || opening;
        SearchActivityRing.Visibility = searching || opening ? Visibility.Visible : Visibility.Collapsed;
        SearchActivityText.Text = opening ? "正在核对资源…" : searching ? "正在搜索…" : "";
        SearchNotice.IsOpen = error is not null || end is not null || _suspended && _lastQuery is not null;
        SearchNotice.Title = _suspended && error is null ? "搜索已暂停" : error is not null && _lastActionOpening ? "无法打开搜索结果"
            : end?.Reason switch
            {
                SearchEndReason.Completed => "搜索完成",
                SearchEndReason.LimitReached => "已达到搜索上限",
                SearchEndReason.ServerTimeout => "服务器搜索超时",
                SearchEndReason.Canceled => "搜索已取消",
                SearchEndReason.SessionChanged => "连接已变更",
                SearchEndReason.Failed when end.Error?.Code == ErrorCode.Timeout => "搜索请求超时",
                SearchEndReason.Failed => "搜索未能完成",
                _ => "搜索需要处理",
            };
        SearchNotice.Message = error ?? (_suspended ? "重新搜索后可继续打开结果。"
            : end?.Reason switch
            {
                SearchEndReason.Completed => "本次搜索已完成。",
                SearchEndReason.LimitReached => "已保留收到的结果。可缩小搜索条件或范围后重试。",
                SearchEndReason.ServerTimeout => "已保留收到的结果。可重新搜索或缩小范围。",
                SearchEndReason.Canceled => "已保留收到的结果，仍可核对并打开。",
                SearchEndReason.SessionChanged => "请在当前连接下重新搜索；旧连接的结果不会打开。",
                SearchEndReason.Failed => "已保留收到的结果，可以重新搜索。",
                _ => "",
            });
        SearchNotice.Severity = error is not null || end?.Reason is SearchEndReason.LimitReached
            or SearchEndReason.ServerTimeout or SearchEndReason.Failed or SearchEndReason.SessionChanged
            ? InfoBarSeverity.Warning : end?.Reason == SearchEndReason.Completed && !_suspended
                ? InfoBarSeverity.Success : InfoBarSeverity.Informational;

        SearchEmptyState.Visibility = _rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        SearchEmptyTitle.Text = !HasContext ? "连接服务器后搜索" : searching ? "正在搜索服务器"
            : error is not null ? "搜索需要处理" : end?.Reason == SearchEndReason.Completed ? "没有找到匹配项"
            : end?.Reason == SearchEndReason.SessionChanged ? "连接已变更" : "搜索服务器文件";
        SearchEmptyText.Text = !HasContext ? "连接服务器后，输入条件并选择搜索范围。"
            : searching ? "结果会逐步显示，你可以随时取消。"
            : end?.Reason == SearchEndReason.Completed ? "可调整搜索条件，或选择含子目录后重试。"
            : error is not null ? "检查连接或调整搜索条件后重试。" : "输入条件并选择范围，然后开始搜索。";
        var scope = _lastQuery is null ? "尚未开始搜索" : _lastScope switch
            { SearchScope.Current => "当前目录", SearchScope.Recursive => "含子目录", _ => "范围待确认" };
        SearchCount.Text = items.Count > _rows.Count
            ? $"{scope} · 已显示 {_rows.Count} / 已收到 {items.Count} 项"
            : $"{scope} · {items.Count} 项结果";
        if (end?.ServerCount is { } serverCount && serverCount != items.Count)
            SearchCount.Text += $" · 服务器报告 {serverCount} 项";
        UpdateCommands();
        if (_viewActive && !_suspended && _rows.Count < items.Count) QueueRender();
    }

    private void ResetRows()
    {
        _rendering = true;
        try { if (_rows.Count > 0) _rows.Clear(); }
        finally { _rendering = false; }
    }

    private void UpdateCommands()
    {
        if (_disposed || SearchButton is null) return;
        var busy = _controller.IsSearching || _controller.IsOpening;
        SearchButton.IsEnabled = HasContext && !_queryComposing && !_controller.IsOpening && !string.IsNullOrWhiteSpace(QueryBox.Text)
            && ScopeBox.SelectedIndex is 0 or 1;
        RetrySearchButton.Visibility = !string.IsNullOrWhiteSpace(_lastQuery) ? Visibility.Visible : Visibility.Collapsed;
        RetrySearchButton.IsEnabled = HasContext && !busy;
        CancelSearchButton.Visibility = busy ? Visibility.Visible : Visibility.Collapsed;
        OpenSelectedButton.IsEnabled = _viewActive && !_suspended && Visibility == Visibility.Visible
            && _openResourceAsync is not null && SearchRecords.SelectedItem is SearchResultRow row && _controller.CanOpen(row.Hit);
    }

    private async Task OpenSelectedAsync()
    {
        if (_disposed || !_viewActive || _suspended || Visibility != Visibility.Visible || _openResourceAsync is not { } open
            || _session is not { } session || SearchRecords.SelectedItem is not SearchResultRow row || !_controller.CanOpen(row.Hit)) return;
        var generation = _controller.Generation;
        var viewGeneration = _controller.ViewGeneration;
        var viewRevision = _viewRevision;
        var handoffRevision = ++_handoffRevision;
        _presentationError = null; _lastActionOpening = true;
        try
        {
            await _controller.OpenAsync(row.Hit, file => DispatchOpenAsync(file, open, session.Account,
                session.Id, generation, viewGeneration, viewRevision, handoffRevision));
        }
        catch (Exception)
        {
            if (!_disposed && viewRevision == _viewRevision && handoffRevision == _handoffRevision)
                _presentationError = "暂时无法打开此搜索结果，请重新搜索后重试。";
        }
        QueueRender();
    }

    private Task DispatchOpenAsync(RemoteFile file, Func<RemoteFile, Task> open, AccountKey account, Guid sessionId,
        long generation, long viewGeneration, long viewRevision, long handoffRevision)
    {
        var completion = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!DispatcherQueue.TryEnqueue(async () =>
        {
            // The controller's metadata fence precedes this dispatch. Recheck after the UI queue wait.
            if (_disposed || !_viewActive || _suspended || Visibility != Visibility.Visible
                || _viewRevision != viewRevision || _handoffRevision != handoffRevision
                || _controller.Generation != generation || _controller.ViewGeneration != viewGeneration
                || _session is not { } currentSession || currentSession.Account != account || currentSession.Id != sessionId
                || file.Resource.Account != account
                || !ReferenceEquals(_openResourceAsync, open))
            { completion.TrySetCanceled(); return; }
            try { await open(file); completion.TrySetResult(); }
            catch (OperationCanceledException) { completion.TrySetCanceled(); }
            catch (Exception error) { completion.TrySetException(error); } // Controller converts this into safe UI copy.
        })) completion.TrySetCanceled();
        return completion.Task;
    }

    private Task SubmitQueryAsync() => SearchAsync(QueryBox.Text,
        ScopeBox.SelectedIndex == 0 ? SearchScope.Current : SearchScope.Recursive);

    private void CancelSearch()
    {
        ++_handoffRevision; _presentationError = null; _lastActionOpening = false;
        _controller.Cancel(); QueueRender();
    }

    private void Close_Click(object sender, RoutedEventArgs args)
    {
        SuspendView();
        try { CloseRequested?.Invoke(this, EventArgs.Empty); }
        catch (Exception) { _presentationError = "暂时无法返回文件，请重试。"; QueueRender(); }
    }
    private async void Search_Click(object sender, RoutedEventArgs args) => await SubmitQueryAsync();
    private async void Retry_Click(object sender, RoutedEventArgs args) => await RefreshAsync();
    private void Cancel_Click(object sender, RoutedEventArgs args) => CancelSearch();
    private async void Open_Click(object sender, RoutedEventArgs args) => await OpenSelectedAsync();
    private void Query_Changed(object sender, TextChangedEventArgs args) => UpdateCommands();
    private void Scope_Changed(object sender, SelectionChangedEventArgs args) => UpdateCommands();
    private void Record_Changed(object sender, SelectionChangedEventArgs args) { if (!_rendering) UpdateCommands(); }
    private async void Query_KeyDown(object sender, KeyRoutedEventArgs args)
    {
        if (args.Handled || _queryComposing || args.Key != VirtualKey.Enter || args.KeyStatus.WasKeyDown) return;
        args.Handled = true;
        if (SearchButton.IsEnabled) await SubmitQueryAsync();
    }
    private async void Records_PreviewKeyDown(object sender, KeyRoutedEventArgs args)
    {
        if (args.Handled || args.Key != VirtualKey.Enter || args.KeyStatus.WasKeyDown) return;
        foreach (var key in new[] { VirtualKey.Control, VirtualKey.Menu, VirtualKey.Shift })
            if (Microsoft.UI.Input.InputKeyboardSource.GetKeyStateForCurrentThread(key)
                .HasFlag(Windows.UI.Core.CoreVirtualKeyStates.Down)) return;
        args.Handled = true;
        await OpenSelectedAsync();
    }
    private async void Records_DoubleTapped(object sender, DoubleTappedRoutedEventArgs args)
    {
        var source = args.OriginalSource as DependencyObject;
        while (source is not null && !ReferenceEquals(source, SearchRecords) && source is not SelectorItem)
            source = VisualTreeHelper.GetParent(source);
        if (source is not SelectorItem { Content: SearchResultRow row }) return;
        args.Handled = true; SearchRecords.SelectedItem = row;
        await OpenSelectedAsync();
    }
    private void Panel_KeyDown(object sender, KeyRoutedEventArgs args)
    {
        if (args.Handled || _queryComposing || args.Key != VirtualKey.Escape) return;
        args.Handled = true;
        if (_controller.IsSearching || _controller.IsOpening) CancelSearch();
        else Close_Click(this, new RoutedEventArgs());
    }
    private void Panel_SizeChanged(object sender, SizeChangedEventArgs args)
    {
        if (sender is Grid grid) grid.Padding = new Thickness(args.NewSize.Width < 560 ? 12 : 20, 12,
            args.NewSize.Width < 560 ? 12 : 20, 12);
    }

    private void OnUi(Action action)
    {
        if (_disposed) return;
        if (DispatcherQueue.HasThreadAccess) action();
        else DispatcherQueue.TryEnqueue(() => { if (!_disposed) action(); });
    }
    private Task OnUiAsync(Func<Task> action)
    {
        if (_disposed) return Task.CompletedTask;
        if (DispatcherQueue.HasThreadAccess) return action();
        var completion = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!DispatcherQueue.TryEnqueue(async () =>
        {
            if (_disposed) { completion.TrySetResult(); return; }
            try { await action(); completion.TrySetResult(); }
            catch (Exception)
            {
                _presentationError = "搜索暂时不可用，请重试。";
                QueueRender(); completion.TrySetResult();
            }
        })) completion.TrySetResult();
        return completion.Task;
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _controller.Changed -= Controller_Changed;
        _controller.Dispose(); _openResourceAsync = null;
        if (DispatcherQueue.HasThreadAccess) _rows.Clear();
        else DispatcherQueue.TryEnqueue(() => _rows.Clear());
    }
}
