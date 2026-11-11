using System.Collections.ObjectModel;
using Fileway.Core.Contracts;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
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
    private (string Query, SearchScope Scope)? _submittedSearch;
    private string? _presentationError;
    private long _viewRevision, _handoffRevision, _compositionRevision, _renderedGeneration = -1;
    private int _renderQueued;
    private volatile bool _disposed;
    private bool _viewActive, _suspended, _rendering, _lastActionOpening, _queryComposing;
    private bool _openingCanceled;

    public event EventHandler? CloseRequested;
    public Func<RemoteFile, Task>? OpenResourceAsync
    {
        get => _openResourceAsync;
        set { if (!_disposed) { _openResourceAsync = value; QueueRender(); } }
    }

    public SearchResultsControl()
    {
        InitializeComponent();
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
            _submittedSearch = null; _presentationError = null; _suspended = false;
            _lastActionOpening = _openingCanceled = false;
            ResetRows();
            UpdateSubmittedSummary();
        }
        // A repeated context refresh must leave the user's unsubmitted query/scope untouched.
        QueueRender();
    });

    public Task SearchAsync(string query, SearchScope scope) => OnUiAsync(() => SearchCoreAsync(query, scope, copyToDraft: true));

    public Task RefreshAsync() => OnUiAsync(() =>
    {
        if (_submittedSearch is not { } submitted || string.IsNullOrWhiteSpace(submitted.Query))
        { FocusQuery(); return Task.CompletedTask; }
        if (!CanRetrySearch) return Task.CompletedTask;
        return SearchCoreAsync(submitted.Query, submitted.Scope);
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
    private bool CanRetrySearch => HasContext && !_queryComposing && !_controller.IsSearching && !_controller.IsOpening;
    private SearchScope? DraftScope => ScopeBox.SelectedIndex switch { 0 => SearchScope.Current, 1 => SearchScope.Recursive, _ => null };

    private async Task SearchCoreAsync(string query, SearchScope scope, bool copyToDraft = false)
    {
        if (_disposed) return;
        _viewActive = true; _suspended = false; _lastActionOpening = false;
        _openingCanceled = false;
        var revision = ++_viewRevision; ++_handoffRevision;
        _submittedSearch = (query, scope);
        _presentationError = null;
        if (copyToDraft)
        {
            QueryBox.Text = query;
            ScopeBox.SelectedIndex = scope switch { SearchScope.Current => 0, SearchScope.Recursive => 1, _ => -1 };
        }
        UpdateSubmittedSummary();
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
                _presentationError = "搜索未能完成，请重试。";
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
        var state = _controller.ReadSnapshot();
        var items = state.Items;
        if (_renderedGeneration != state.Generation)
        {
            ResetRows(); _renderedGeneration = state.Generation;
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

        var hasRows = _rows.Count > 0;
        var busy = state.IsSearching || state.IsOpening;
        var status = ResultStatus(state.End, _presentationError ?? state.Error, hasRows);
        SearchActivityRing.IsActive = busy;
        SearchActivityText.Text = state.IsOpening ? "正在核对资源…" : "正在搜索…";
        SearchActivityText.Visibility = state.IsOpening || state.IsSearching && hasRows ? Visibility.Visible : Visibility.Collapsed;
        SearchActions.Visibility = busy ? Visibility.Visible : Visibility.Collapsed;

        if (hasRows && status is { } notice)
        {
            SearchNotice.Title = notice.Title;
            SearchNotice.Message = notice.Description;
            SearchNotice.Severity = notice.Severity;
        }
        SearchNotice.IsOpen = hasRows && status is not null;
        SearchNotice.Visibility = SearchNotice.IsOpen ? Visibility.Visible : Visibility.Collapsed;

        SearchHeaders.Visibility = hasRows ? Visibility.Visible : Visibility.Collapsed;
        SearchEmptyState.Visibility = hasRows ? Visibility.Collapsed : Visibility.Visible;
        (SearchEmptyState.Title, SearchEmptyState.Description) = !HasContext
            ? ("连接服务器后搜索", "连接服务器后，输入条件并选择搜索范围。")
            : state.IsSearching ? ("正在搜索服务器", "结果会逐步显示，你可以随时取消。")
            : status is { } emptyStatus ? (emptyStatus.Title, emptyStatus.Description)
            : state.End?.Reason == SearchEndReason.Completed ? ("没有找到匹配项", _submittedSearch?.Scope == SearchScope.Current
                ? "可调整搜索条件，或选择含子目录后重新搜索。" : "可调整搜索条件后重新搜索。")
            : ("搜索服务器文件", "输入条件并选择范围，然后开始搜索。");

        SearchCount.Text = items.Count > _rows.Count ? $"已显示 {_rows.Count} / 已收到 {items.Count} 项"
            : state.End?.Reason == SearchEndReason.Completed ? $"{items.Count} 项结果" : $"已收到 {items.Count} 项";
        if (state.End?.ServerCount is { } serverCount && serverCount != items.Count)
            SearchCount.Text += $" · 服务器报告 {serverCount} 项";
        SearchCount.Visibility = state.IsSearching && items.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
        UpdateCommands(state);
        if (_viewActive && !_suspended && _rows.Count < items.Count) QueueRender();
    }

    private (string Title, string Description, InfoBarSeverity Severity)? ResultStatus(SearchEnd? end, string? error, bool hasRows)
    {
        const InfoBarSeverity warning = InfoBarSeverity.Warning;
        const InfoBarSeverity info = InfoBarSeverity.Informational;
        if (_suspended && _submittedSearch is not null && error is null)
            return ("搜索已暂停", hasRows ? "重新搜索后可核对并打开结果。" : "可以重新执行上次搜索。", info);
        if (_openingCanceled)
            return ("资源核对已取消", "可再次打开选中的搜索结果。", info);
        if (_lastActionOpening && error is not null)
            return ("无法打开搜索结果", error, warning);
        error ??= end?.Error?.Message;
        var retained = hasRows ? "已保留收到的部分结果。" : "尚未收到结果。";
        return end?.Reason switch
        {
            SearchEndReason.LimitReached => ("已达到搜索上限", retained + "可缩小搜索条件或范围后重试。", warning),
            SearchEndReason.ServerTimeout => ("服务器搜索超时", retained + "可重新搜索或缩小范围。", warning),
            SearchEndReason.Canceled => ("搜索已取消", hasRows ? retained + "仍可核对并打开。" : retained, info),
            SearchEndReason.SessionChanged => ("连接已变更", "请在当前连接下重新搜索。", warning),
            SearchEndReason.Failed when end?.Error?.Code == ErrorCode.Timeout => ("搜索请求超时", retained + "可重新搜索。", warning),
            SearchEndReason.Failed => ("搜索未能完成", end?.Error?.Code == ErrorCode.Unknown || error is null
                ? retained + "可重新搜索。" : hasRows ? error + " " + retained : error, warning),
            _ when error is not null => ("搜索需要处理", error, warning),
            _ => null,
        };
    }

    private void ResetRows()
    {
        _rendering = true;
        try { if (_rows.Count > 0) _rows.Clear(); }
        finally { _rendering = false; }
    }

    private void UpdateCommands(SearchResultsController.Snapshot? snapshot = null)
    {
        if (_disposed || SearchButton is null) return;
        var state = snapshot ?? _controller.ReadSnapshot();
        var busy = state.IsSearching || state.IsOpening;
        SearchButton.IsEnabled = HasContext && !_queryComposing && !state.IsOpening && !string.IsNullOrWhiteSpace(QueryBox.Text)
            && DraftScope is not null;
        RetrySearchButton.Visibility = _submittedSearch is { } submitted && !string.IsNullOrWhiteSpace(submitted.Query) && !busy
            ? Visibility.Visible : Visibility.Collapsed;
        RetrySearchButton.IsEnabled = HasContext && !_queryComposing && !busy;
        OpenSelectedButton.Visibility = SearchRecords.SelectedItem is SearchResultRow ? Visibility.Visible : Visibility.Collapsed;
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
        _presentationError = null; _lastActionOpening = true; _openingCanceled = false;
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

    private Task SubmitQueryAsync() => DraftScope is { } scope
        ? SearchCoreAsync(QueryBox.Text, scope) : Task.CompletedTask;

    private void CancelSearch()
    {
        ++_handoffRevision; _presentationError = null; _lastActionOpening = false;
        _openingCanceled = _controller.IsOpening;
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
    private void Query_Changed(object sender, TextChangedEventArgs args) { UpdateDraftHint(); UpdateCommands(); }
    private void UpdateSubmittedSummary()
    {
        if (_submittedSearch is { } submitted)
        {
            var scope = submitted.Scope switch { SearchScope.Current => "当前目录", SearchScope.Recursive => "含子目录", _ => "范围待确认" };
            var summary = $"已提交 · {scope} · “{submitted.Query}”";
            var directory = _directory is { } source ? $"起始目录：{source.DisplayPath}" : "";
            SearchSubmittedSummary.Text = summary;
            AutomationProperties.SetHelpText(SearchSubmittedSummary, directory);
            ToolTipService.SetToolTip(SearchSubmittedSummary, new TextBlock
            {
                Text = $"{directory}\n{summary}", MaxWidth = 480, MaxLines = 6,
                TextWrapping = TextWrapping.Wrap, TextTrimming = TextTrimming.CharacterEllipsis,
            });
            SearchSummaryBar.Visibility = Visibility.Visible;
        }
        else
        {
            SearchSummaryBar.Visibility = Visibility.Collapsed;
            SearchSubmittedSummary.Text = "";
            AutomationProperties.SetHelpText(SearchSubmittedSummary, "");
            ToolTipService.SetToolTip(SearchSubmittedSummary, null);
        }
        UpdateDraftHint();
    }
    private void UpdateDraftHint()
    {
        if (SearchDraftHint is null || QueryBox is null || ScopeBox is null) return;
        var changed = _submittedSearch is { } submitted && (!string.Equals(submitted.Query, QueryBox.Text, StringComparison.Ordinal)
            || DraftScope != submitted.Scope);
        SearchDraftHint.Visibility = changed ? Visibility.Visible : Visibility.Collapsed;
    }
    private void Scope_Changed(object sender, SelectionChangedEventArgs args) { UpdateDraftHint(); UpdateCommands(); }
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
