using System.Collections.ObjectModel;
using System.Globalization;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.System;

namespace Fileway.App.Controls.NasTasks;

/// <summary>Native own-account server tasks. All task authority stays in NasTaskListController.</summary>
public sealed partial class NasTasksControl : UserControl, IDisposable
{
    private readonly NasTaskListController _controller = new();
    private readonly ObservableCollection<NasTaskRow> _tasks = [];
    private readonly ObservableCollection<NasTaskOperationRow> _operations = [];
    private readonly ObservableCollection<NasTaskOperationItemRow> _operationItems = [];
    private CancellationTokenSource _viewCancellation = new();
    private ConnectionSession? _session;
    private IServerTaskService? _taskService;
    private IOperationHistoryService? _historyService;
    private ServerTaskRef? _detailReference;
    private ServerTask? _standaloneDetail;
    private OperationRecord? _shownOperation;
    private ContentDialog? _confirmation;
    private string? _uiMessage;
    private InfoBarSeverity _uiSeverity = InfoBarSeverity.Informational;
    private string? _relatedTaskId;
    private long _viewEpoch;
    private readonly long _visibilityCallback;
    private bool _active;
    private bool _historyMode;
    private bool _rendering;
    private bool _ready;
    private bool _disposed;
    private bool _narrow;

    public event EventHandler? CloseRequested;
    public bool HasOpenDialog => _confirmation is not null;

    public NasTasksControl()
    {
        InitializeComponent(); Fileway.App.Controls.FloatingNotice.Attach(Notice);
        Fileway.App.Controls.SurfaceStyles.Attach(MoreCommandsButton.Flyout);
        TaskRecords.ItemsSource = _tasks; OperationRecords.ItemsSource = _operations; OperationItems.ItemsSource = _operationItems;
        _visibilityCallback = RegisterPropertyChangedCallback(VisibilityProperty, (_, _) =>
        { if (Visibility != Visibility.Visible) SuspendView(); });
        Unloaded += (_, _) => SuspendView();
        _ready = true;
        Render();
    }

    public void SetContext(IServerTaskService? tasks, IOperationHistoryService? history, ConnectionSession? session)
    {
        if (_disposed) return;
        long generation = _controller.Generation;
        _controller.SetContext(tasks, history, session);
        _taskService = tasks; _historyService = history; _session = session;
        if (generation != _controller.Generation)
        {
            RetireUi(); _active = false; _historyMode = false; _detailReference = null; _standaloneDetail = null;
            _relatedTaskId = null; _shownOperation = null; _uiMessage = null;
            _rendering = true;
            try { StatusFilter.SelectedIndex = TypeFilter.SelectedIndex = 0; ArchivedFilter.IsChecked = false; }
            finally { _rendering = false; }
        }
        // Browser selection/renewal can repeat the same context frequently. Only
        // refresh authority in that case; do not reconcile every task row again.
        Render(synchronizeRows: generation != _controller.Generation);
    }

    public async Task RefreshAsync()
    {
        if (_disposed || Visibility != Visibility.Visible || _session is null || _taskService is null || Busy) return;
        if (!_active)
        {
            RetireUi(); _active = true;
            if (!await RunAsync(token => _controller.RefreshAsync(token))) return;
            if (_historyMode) await RunAsync(token => _controller.RefreshOperationsAsync(token));
            return;
        }
        await RunAsync(token => _historyMode ? _controller.RefreshOperationsAsync(token) : _controller.RefreshAsync(token));
    }

    public void SuspendView()
    {
        if (_disposed) return;
        _active = false; RetireUi(); _controller.SuspendView();
        Render(synchronizeRows: false);
    }

    internal void SelectSubmittedTask(ServerTaskRef reference)
    {
        if (!Active || _session?.Account != reference.Account) return;
        _historyMode = false;
        var row = _tasks.FirstOrDefault(item => item.Task.Reference == reference);
        _rendering = true;
        try { TaskRecords.SelectedItem = row; }
        finally { _rendering = false; }
        _detailReference = row?.Task.Reference;
        _standaloneDetail = null;
        Render(synchronizeRows: false);
    }

    private bool Active => !_disposed && _active && Visibility == Visibility.Visible && _session is not null && _taskService is not null;
    private bool Busy => _controller.IsLoading || _controller.IsLoadingDetail || _controller.IsLoadingOperations ||
        _controller.IsVerifyingOperations || _controller.IsActing || HasOpenDialog;
    private ServerTask? DetailTask => _detailReference is { } reference
        ? _controller.Detail is { } current && current.Reference == reference ? current :
            _tasks.FirstOrDefault(row => row.Task.Reference == reference)?.Task ??
            (_standaloneDetail?.Reference == reference ? _standaloneDetail : null)
        : null;
    private ServerTask? RetryTask
    {
        get
        {
            if (_controller.LastActionResult is not { Outcome: OperationOutcome.Confirmed,
                Kind: ServerTaskActionKind.RetryAccepted, Task: { } accepted }) return null;
            return _controller.Detail is { } detail && detail.Reference == accepted.Reference ? detail :
                _tasks.FirstOrDefault(row => row.Task.Reference == accepted.Reference)?.Task;
        }
    }

    private async Task<bool> RunAsync(Func<CancellationToken, Task> operation, bool synchronizeRows = true, bool synchronizeImmediately = false)
    {
        if (!Active || HasOpenDialog) return false;
        long epoch = _viewEpoch;
        try
        {
            var pending = operation(_viewCancellation.Token);
            Render(synchronizeRows: synchronizeImmediately); // Controller publishes loading before its first await.
            await pending;
            if (!IsCurrent(epoch)) return false;
            Render(synchronizeRows);
            return true;
        }
        catch (OperationCanceledException) { return false; }
        catch (Exception error)
        {
            if (IsCurrent(epoch))
            {
                _uiMessage = NasTaskPresentationPolicy.SafeError((error as FilewayException)?.Error, "状态未能更新，请先核实操作记录。");
                _uiSeverity = InfoBarSeverity.Warning;
            }
            return false;
        }
        finally { if (IsCurrent(epoch)) Render(synchronizeRows: false); }
    }

    private bool IsCurrent(long epoch) => Active && epoch == _viewEpoch;

    private void Render(bool synchronizeRows = true)
    {
        if (!_ready || _disposed) return;
        if (synchronizeRows) SynchronizeRows();
        _rendering = true;
        try
        {
            TasksTab.IsChecked = !_historyMode; HistoryTab.IsChecked = _historyMode;
            TaskRecords.Visibility = _historyMode ? Visibility.Collapsed : Visibility.Visible;
            OperationRecords.Visibility = _historyMode ? Visibility.Visible : Visibility.Collapsed;
            LoadingRing.IsActive = Busy;
            LoadingRing.Visibility = Busy ? Visibility.Visible : Visibility.Collapsed;
            RefreshLabel.Text = _historyMode ? "刷新记录" : "刷新任务";
            AutomationProperties.SetName(RefreshButton, RefreshLabel.Text);
            RefreshButton.IsEnabled = Active && !Busy;
            StatusFilter.IsEnabled = TypeFilter.IsEnabled = ArchivedFilter.IsEnabled = Active && !Busy;
            TasksTab.IsEnabled = Active && !Busy;
            HistoryTab.IsEnabled = Active && !Busy && _historyService is not null;
            NoticeHistoryButton.IsEnabled = RelatedHistoryButton.IsEnabled = Active && _historyService is not null && !Busy;
            NoticeHistoryButton.Visibility = _historyMode ? Visibility.Collapsed : Visibility.Visible;
            UpdateCounts(); UpdateNotice(); RenderDetails(); UpdateCommands(); UpdateLayoutState();
            int count = _historyMode ? _operations.Count : _tasks.Count;
            ListHeaders.Visibility = count == 0 ? Visibility.Collapsed : Visibility.Visible;
            ListHeaderTitle.Text = _historyMode ? "操作" : "任务";
            ListHeaderTime.Text = _historyMode ? "时间" : "创建时间";
            EmptyState.IconName = _historyMode ? "clipboard" : "file-code";
            EmptyState.Visibility = count == 0 ? Visibility.Visible : Visibility.Collapsed;
            var hasFilter = StatusFilter.SelectedIndex > 0 || TypeFilter.SelectedIndex > 0 || ArchivedFilter.IsChecked == true;
            var hasLoaded = _historyMode ? _controller.HasLoadedOperations : _controller.HasLoaded;
            (EmptyState.Title, EmptyState.Description) = (Busy, Active, hasLoaded, _historyMode, hasFilter) switch
            {
                (true, _, _, _, _) => ("正在读取服务器记录", ""),
                (_, false, _, _, _) => ("尚未连接服务器", "连接后可查看当前账号的文件任务。"),
                (_, _, false, _, _) => ("尚未读取任务记录", "可刷新后重试。"),
                (_, _, _, true, _) => ("暂无任务操作记录", "任务操作会记录在这里，结果未确认时可核实。"),
                (_, _, _, _, true) => ("暂无匹配的文件任务", "调整筛选条件，或刷新任务。"),
                _ => ("暂无文件任务", "从文件列表发起复制或移动后，任务会显示在这里。"),
            };
            LoadedText.Text = _historyMode
                ? _controller.HasLoadedOperations ? $"当前账号 · 已加载 {_operations.Count.ToString(CultureInfo.CurrentCulture)} 条任务操作记录" : "尚未读取操作记录"
                : _controller.HasLoaded ? $"当前筛选 · 已加载 {_tasks.Count.ToString(CultureInfo.CurrentCulture)} / {_controller.Total.ToString(CultureInfo.CurrentCulture)} 条服务器任务" : "尚未读取服务器任务";
            bool more = _historyMode ? _controller.OperationNextCursor is not null : _controller.NextCursor is not null;
            MoreButton.Visibility = more ? Visibility.Visible : Visibility.Collapsed;
            MoreButton.IsEnabled = Active && !HasOpenDialog && (_historyMode ? _controller.CanLoadMoreOperations : _controller.CanLoadMore);
            AuthorityHint.Visibility = !_controller.HasVerifiedOperations || _controller.IsVerifyingOperations ? Visibility.Visible : Visibility.Collapsed;
            AuthorityHint.Text = !Active ? "连接后仅显示当前账号的服务器任务。" : _controller.IsVerifyingOperations ? "正在核实操作记录，任务动作暂时不可用。" :
                !_controller.HasVerifiedOperations ? "操作记录尚未完整核实，任务动作已暂停。刷新任务可重新核实。" :
                _historyMode ? "核实只读取原操作证据，不会重新提交。仅观察任务状态时，原操作结果可能仍未知。" :
                    "取消表示请求已接受，最终状态以服务器为准。归档只整理任务记录，不删除文件。";
            NoticePanel.Visibility = Notice.IsOpen || AuthorityHint.Visibility == Visibility.Visible
                ? Visibility.Visible : Visibility.Collapsed;
        }
        finally { _rendering = false; }
    }

    private void SynchronizeRows()
    {
        var selectedTask = (TaskRecords.SelectedItem as NasTaskRow)?.Task.Reference;
        var selectedOperation = (OperationRecords.SelectedItem as NasTaskOperationRow)?.Record.Intent.Reference;
        var tasks = _controller.Items;
        var operations = _controller.Operations;
        _rendering = true;
        try
        {
            var taskKeys = tasks.Select(task => task.Reference).ToHashSet();
            for (int index = _tasks.Count - 1; index >= 0; --index)
                if (!taskKeys.Contains(_tasks[index].Task.Reference)) _tasks.RemoveAt(index);
            for (int index = 0; index < tasks.Count; ++index)
            {
                var task = tasks[index];
                var row = _tasks.FirstOrDefault(item => item.Task.Reference == task.Reference);
                if (row is null) _tasks.Insert(index, row = new(task));
                else if (_tasks.IndexOf(row) != index) _tasks.Move(_tasks.IndexOf(row), index);
                row.Update(task);
            }
            var operationKeys = operations.Select(record => record.Intent.Reference).ToHashSet();
            for (int index = _operations.Count - 1; index >= 0; --index)
                if (!operationKeys.Contains(_operations[index].Record.Intent.Reference)) _operations.RemoveAt(index);
            for (int index = 0; index < operations.Count; ++index)
            {
                var record = operations[index];
                var row = _operations.FirstOrDefault(item => item.Record.Intent.Reference == record.Intent.Reference);
                if (row is null) _operations.Insert(index, row = new(record));
                else if (_operations.IndexOf(row) != index) _operations.Move(_operations.IndexOf(row), index);
                row.Update(record);
            }
            if (selectedTask is { } taskReference) TaskRecords.SelectedItem = _tasks.FirstOrDefault(row => row.Task.Reference == taskReference);
            if (selectedOperation is { } operationReference)
                OperationRecords.SelectedItem = _operations.FirstOrDefault(row => row.Record.Intent.Reference == operationReference);
            if (_detailReference is { } detailReference && _controller.Detail?.Reference != detailReference &&
                !_tasks.Any(row => row.Task.Reference == detailReference))
            { _detailReference = null; _standaloneDetail = null; }
            SelectRelatedOperation();
        }
        finally { _rendering = false; }
    }

    private void UpdateCounts()
    {
        string[] labels = ["全部状态", "进行中", "需要处理", "已完成", "已取消"];
        string[] keys = ["all", "active", "attention", "completed", "canceled"];
        for (int index = 0; index < labels.Length; ++index)
            ((ComboBoxItem)StatusFilter.Items[index]).Content = _controller.Counts.TryGetValue(keys[index], out long count)
                ? labels[index] + " · " + count.ToString(CultureInfo.CurrentCulture) : labels[index];
        ArchivedFilter.Content = _controller.Counts.TryGetValue("archived", out long archived)
            ? "只看归档 · " + archived.ToString(CultureInfo.CurrentCulture) : "只看归档";
    }

    private void UpdateNotice()
    {
        var operationsError = _controller.OperationsError;
        if (_historyMode && operationsError is not null && _controller.LastReconciliation is { Error: not null } result &&
            _operations.Any(row => row.Record.Intent.Reference == result.Reference && row.Stage == operationsError))
            operationsError = null;
        var uiMessage = _uiMessage;
        var uiSeverity = _uiSeverity;
        if (_controller.LastActionResult is { Kind: ServerTaskActionKind.CancellationRequested,
                Outcome: OperationOutcome.Confirmed, Task: { } acknowledged } accepted &&
            uiMessage == NasTaskPresentationPolicy.ActionText(accepted))
        {
            var current = _historyMode ? null : DetailTask;
            if (current is null || current.Reference != acknowledged.Reference)
                uiMessage = null;
            else if (current.Status is not (ServerTaskStatus.Queued or ServerTaskStatus.Running))
            {
                uiMessage = accepted.Receipt is { AckPersisted: false } ? "本地确认记录未保存，可核实操作记录。" : null;
                uiSeverity = InfoBarSeverity.Warning;
            }
        }
        string? message = uiMessage ?? (_historyMode ? operationsError : _controller.Error ?? operationsError);
        Notice.IsOpen = message is not null;
        Notice.Message = message ?? string.Empty;
        Notice.Title = uiMessage is not null ? "最近操作" : "任务状态需要处理";
        Notice.Severity = uiMessage is not null ? uiSeverity : InfoBarSeverity.Warning;
    }

    private void RenderDetails()
    {
        var task = !_historyMode ? DetailTask : null;
        var operation = _historyMode ? OperationRecords.SelectedItem as NasTaskOperationRow : null;
        DetailPlaceholder.Visibility = task is null && operation is null ? Visibility.Visible : Visibility.Collapsed;
        TaskDetails.Visibility = task is null ? Visibility.Collapsed : Visibility.Visible;
        OperationDetails.Visibility = operation is null ? Visibility.Collapsed : Visibility.Visible;
        DetailTitle.Text = task is not null ? new NasTaskRow(task).Name : operation?.Name ?? (_historyMode ? "操作详情" : "任务详情");
        if (task is not null)
        {
            DetailStatus.Text = NasTaskPresentationPolicy.StatusText(task);
            var fraction = NasTaskPresentationPolicy.ProgressFraction(task);
            bool active = task.Status is ServerTaskStatus.Queued or ServerTaskStatus.Running;
            DetailProgressTrack.Visibility = fraction is < 1 || active ? Visibility.Visible : Visibility.Collapsed;
            DetailProgress.Value = (fraction ?? 0) * 100;
            DetailProgress.IsIndeterminate = fraction is null && active;
            DetailProgressText.Text = NasTaskPresentationPolicy.ProgressText(task);
            DetailTimes.Text = "创建：" + NasTaskRow.FormatTime(task.CreatedAt) +
                (task.StartedAt is { } started ? "\n开始：" + NasTaskRow.FormatTime(started) : string.Empty) +
                (task.FinishedAt is { } finished ? "\n结束：" + NasTaskRow.FormatTime(finished) : string.Empty);
            var source = task.SourceDisplayPath is { Length: > 0 } path ? "来源：" + path : "";
            var target = task.OutputDisplayPath is { Length: > 0 } output ? "目标：" + output : "";
            SurfaceGeometry.SetText(DetailPaths, source.Length > 0 && target.Length > 0 ? source + "\n" + target : source + target);
            SurfaceGeometry.SetText(DetailError, task.Error is null ? "" :
                NasTaskPresentationPolicy.SafeError(task.Error, "服务器报告任务异常，请核实任务状态。"));
            DetailTaskId.Text = task.Reference.Id + (task.RetryOf is { } original ? "\n重试来源：" + original : string.Empty);
        }
        NewRetryButton.Visibility = RetryTask is not null ? Visibility.Visible : Visibility.Collapsed;
        if (operation is not null)
        {
            var stage = operation.Stage;
            if (OperationStage.Text != stage)
            {
                OperationStage.Text = stage;
                FrameworkElementAutomationPeer.FromElement(OperationStage)?.RaiseAutomationEvent(AutomationEvents.LiveRegionChanged);
            }
            OperationExplanation.Text = "核实只读取原操作证据。任务执行结果以服务器状态为准。";
            OperationIdentity.Text = "记录编号：" + operation.Record.Intent.Reference.Id.ToString("D", CultureInfo.InvariantCulture) +
                (operation.Record.Acknowledgement?.Task is { } related ? "\n关联任务：" + related.Id :
                    operation.Record.Intent.Options?.RecordId is { } original ? "\n原任务：" + original : string.Empty);
            if (!ReferenceEquals(_shownOperation, operation.Record))
            {
                _shownOperation = operation.Record; _operationItems.Clear();
                var items = operation.Record.Acknowledgement?.Items ?? operation.Record.Intent.Items;
                foreach (var item in items) _operationItems.Add(new(item));
            }
            OperationItems.Visibility = _operationItems.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
            OperationItemsEmpty.Visibility = _operationItems.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        }
    }

    private void UpdateCommands()
    {
        var task = DetailTask;
        bool available = Active && !HasOpenDialog && !_historyMode && task is not null;
        DetailRefreshButton.IsEnabled = available && !Busy;
        MoreCommandsButton.IsEnabled = available;
        CancelTaskButton.IsEnabled = available && _controller.CanAct(task!, ServerTaskActionKind.CancellationRequested);
        RetryTaskButton.IsEnabled = available && _controller.CanAct(task!, ServerTaskActionKind.RetryAccepted);
        ArchiveTaskButton.IsEnabled = available && _controller.CanAct(task!, ServerTaskActionKind.Archived);
        UnarchiveTaskButton.IsEnabled = available && _controller.CanAct(task!, ServerTaskActionKind.Unarchived);
        ReconcileButton.IsEnabled = Active && !Busy && _historyMode && OperationRecords.SelectedItem is NasTaskOperationRow row &&
            !NasTaskPresentationPolicy.IsResolved(row.Observation) && _controller.CanReconcile(row.Record);
        DetailRefreshButton.Visibility = available ? Visibility.Visible : Visibility.Collapsed;
        CancelTaskButton.Visibility = CancelTaskButton.IsEnabled ? Visibility.Visible : Visibility.Collapsed;
        RetryTaskButton.Visibility = RetryTaskButton.IsEnabled ? Visibility.Visible : Visibility.Collapsed;
        MoreCommandsButton.Visibility = available && (ArchiveTaskButton.IsEnabled || UnarchiveTaskButton.IsEnabled) ? Visibility.Visible : Visibility.Collapsed;
        ArchiveTaskButton.Visibility = ArchiveTaskButton.IsEnabled ? Visibility.Visible : Visibility.Collapsed;
        UnarchiveTaskButton.Visibility = UnarchiveTaskButton.IsEnabled ? Visibility.Visible : Visibility.Collapsed;
        ReconcileButton.Visibility = ReconcileButton.IsEnabled ? Visibility.Visible : Visibility.Collapsed;
        RelatedHistoryButton.Visibility = available && _historyService is not null ? Visibility.Visible : Visibility.Collapsed;
        Commands.Visibility = available || ReconcileButton.IsEnabled ? Visibility.Visible : Visibility.Collapsed;
        NewRetryButton.IsEnabled = Active && !Busy;
    }

    private async Task ApplyFilterAsync()
    {
        if (!Active || Busy) return;
        IReadOnlyList<ServerTaskStatus>? statuses = StatusFilter.SelectedIndex switch
        {
            1 => [ServerTaskStatus.Queued, ServerTaskStatus.Running],
            2 => [ServerTaskStatus.Failed, ServerTaskStatus.Interrupted],
            3 => [ServerTaskStatus.Completed], 4 => [ServerTaskStatus.Canceled], _ => null,
        };
        var query = new ServerTaskQuery(Statuses: statuses,
            Type: TypeFilter.SelectedIndex switch { 1 => "file.copy", 2 => "file.move", _ => null },
            Archived: ArchivedFilter.IsChecked == true, Category: ServerTaskCategory.File);
        _uiMessage = null;
        await RunAsync(token => _controller.ApplyFilterAsync(query, token), synchronizeImmediately: true);
    }

    private async Task RefreshDetailAsync()
    {
        if (!Active || Busy || DetailTask is not { } task) return;
        await RunAsync(token => _controller.LoadDetailAsync(task, token));
    }

    private async Task ExecuteAsync(ServerTaskActionKind action)
    {
        if (!Active || HasOpenDialog || DetailTask is not { } task || !_controller.CanAct(task, action)) return;
        long epoch = _viewEpoch, generation = _controller.Generation;
        var account = _session!.Account; var sessionId = _session.Id;
        if (action != ServerTaskActionKind.Unarchived && !await ConfirmAsync(task, action)) return;
        if (!IsCurrent(epoch) || generation != _controller.Generation || _session?.Account != account || _session?.Id != sessionId ||
            !ReferenceEquals(DetailTask, task) || !_controller.CanAct(task, action)) return;
        ServerTaskActionResult? result = null;
        _uiMessage = null;
        bool current = await RunAsync(async token => result = await (action switch
        {
            ServerTaskActionKind.CancellationRequested => _controller.CancelAsync(task, token),
            ServerTaskActionKind.RetryAccepted => _controller.RetryAsync(task, token),
            ServerTaskActionKind.Archived => _controller.ArchiveAsync(task, token),
            _ => _controller.UnarchiveAsync(task, token),
        }));
        if (!current || !IsCurrent(epoch) || result is null) return;
        _uiMessage = NasTaskPresentationPolicy.ActionText(result);
        _uiSeverity = result.Outcome == OperationOutcome.Confirmed ? InfoBarSeverity.Informational : InfoBarSeverity.Warning;
        if (result.Outcome == OperationOutcome.OutcomeUnknown)
        {
            _relatedTaskId = task.Reference.Id;
            await ShowHistoryAsync();
        }
        Render(synchronizeRows: false);
        if (action == ServerTaskActionKind.CancellationRequested && result.Outcome == OperationOutcome.Confirmed &&
            result.Task is { Status: ServerTaskStatus.Queued or ServerTaskStatus.Running })
            await ObserveAcceptedCancellationAsync(task.Reference, epoch);
    }

    private async Task ObserveAcceptedCancellationAsync(ServerTaskRef reference, long epoch)
    {
        var token = _viewCancellation.Token;
        try
        {
            for (int attempt = 0; attempt < 12; attempt++)
            {
                await Task.Delay(500, token);
                if (!IsCurrent(epoch) || _historyMode || DetailTask is not { } current ||
                    current.Reference != reference || current.Status is not (ServerTaskStatus.Queued or ServerTaskStatus.Running))
                    return;
                if (Busy) continue;
                await _controller.LoadDetailAsync(current, token);
                if (!IsCurrent(epoch) || _historyMode || DetailTask?.Reference != reference) return;
                if (_controller.Error is not null || DetailTask?.Status is not (ServerTaskStatus.Queued or ServerTaskStatus.Running))
                {
                    Render();
                    return;
                }
            }
        }
        catch (OperationCanceledException) { }
    }

    private async Task<bool> ConfirmAsync(ServerTask task, ServerTaskActionKind action)
    {
        if (HasOpenDialog || XamlRoot is null) return false;
        long epoch = _viewEpoch;
        var (title, message, primary) = action switch
        {
            ServerTaskActionKind.CancellationRequested => ("请求取消此任务？", "取消请求接受后仍需等待服务器状态。已完成的项目不会自动回滚。", "请求取消"),
            ServerTaskActionKind.RetryAccepted => ("重试此任务？", "服务器会创建新任务。请先确认上次结果，避免重复提交。", "创建重试任务"),
            _ => ("归档任务记录？", "仅归档此任务记录，不删除文件。可在归档列表中移回。", "归档记录"),
        };
        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot, RequestedTheme = ActualTheme, Title = title,
            Content = new TextBlock { Text = new NasTaskRow(task).Name + "\n\n" + message, TextWrapping = TextWrapping.Wrap, MaxWidth = 440 },
            PrimaryButtonText = primary, CloseButtonText = "返回", DefaultButton = ContentDialogButton.None,
        };
        DialogOrdering.FocusCloseButton(dialog);
        _confirmation = dialog; Render(synchronizeRows: false);
        try { return await dialog.ShowAsync() == ContentDialogResult.Primary; }
        catch (Exception)
        {
            if (IsCurrent(epoch)) { _uiMessage = "确认窗口未能打开，请关闭其他对话框后重试。"; _uiSeverity = InfoBarSeverity.Warning; }
            return false;
        }
        finally
        {
            if (ReferenceEquals(_confirmation, dialog)) _confirmation = null;
            if (IsCurrent(epoch)) Render(synchronizeRows: false);
        }
    }

    private async Task ShowHistoryAsync()
    {
        if (!Active || Busy || _historyService is null) return;
        _historyMode = true; Render(synchronizeRows: false);
        await RunAsync(token => _controller.RefreshOperationsAsync(token));
    }

    private async Task ReconcileAsync()
    {
        if (!Active || Busy || OperationRecords.SelectedItem is not NasTaskOperationRow row ||
            !_controller.CanReconcile(row.Record)) return;
        _uiMessage = null;
        var epoch = _viewEpoch;
        await RunAsync(async token =>
        {
            var result = await _controller.ReconcileAsync(row.Record, token);
            if (result is not null && IsCurrent(epoch)) row.Observe(result);
        }, synchronizeRows: false);
    }

    private void SelectRelatedOperation()
    {
        if (_relatedTaskId is null) return;
        var row = _operations.FirstOrDefault(item => item.Record.Acknowledgement?.Task?.Id == _relatedTaskId ||
            item.Record.Intent.Options?.RecordId == _relatedTaskId);
        if (row is null) return;
        OperationRecords.SelectedItem = row; _relatedTaskId = null;
    }

    private void RetireUi()
    {
        ++_viewEpoch;
        var dialog = _confirmation; _confirmation = null;
        try { dialog?.Hide(); } catch (Exception) { /* A retired confirmation can never authorize an operation. */ }
        var source = _viewCancellation; _viewCancellation = new();
        _ = CancelAndDisposeAsync(source);
    }

    private static async Task CancelAndDisposeAsync(CancellationTokenSource source)
    {
        try { await source.CancelAsync().ConfigureAwait(false); }
        catch (Exception) { /* Provider callbacks must not block page retirement. */ }
        finally { source.Dispose(); }
    }

    private void UpdateLayoutState()
    {
        bool hasDetail = _historyMode ? OperationRecords.SelectedItem is not null : DetailTask is not null;
        bool fullDetail = _narrow && hasDetail;
        bool enteringDetail = fullDetail && BackToTasksButton.Visibility != Visibility.Visible;
        TaskPageHeader.Title = fullDetail ? DetailTitle.Text : "文件任务";
        TaskPageHeader.Subtitle = fullDetail
            ? _historyMode ? "操作记录详情" : "服务器任务详情"
            : "查看服务器任务及操作记录。";
        TaskTabs.Visibility = ListFooter.Visibility = RefreshButton.Visibility = ClosePageButton.Visibility =
            fullDetail ? Visibility.Collapsed : Visibility.Visible;
        Filters.Visibility = fullDetail || _historyMode ? Visibility.Collapsed : Visibility.Visible;
        TaskRecords.SingleSelectionFollowsFocus = OperationRecords.SingleSelectionFollowsFocus = !_narrow;
        ListArea.Visibility = fullDetail ? Visibility.Collapsed : Visibility.Visible;
        DetailColumn.Width = _narrow || !hasDetail ? new GridLength(0) : new GridLength(340);
        Grid.SetColumn(DetailsPane, _narrow ? 0 : 1);
        Grid.SetColumnSpan(ListArea, _narrow ? 2 : 1); Grid.SetColumnSpan(DetailsPane, _narrow ? 2 : 1);
        DetailsPane.Visibility = hasDetail ? Visibility.Visible : Visibility.Collapsed;
        DetailTitle.Visibility = fullDetail ? Visibility.Collapsed : Visibility.Visible;
        AutomationProperties.SetName(BackToTasksButton, _historyMode ? "返回操作记录列表" : "返回任务列表");
        BackToTasksButton.Visibility = fullDetail ? Visibility.Visible : Visibility.Collapsed;
        DetailsPane.BorderThickness = _narrow ? new Thickness(0) : new Thickness(1, 0, 0, 0);
        DetailsPane.Padding = _narrow ? new Thickness(0) : new Thickness(16, 4, 0, 4);
        if (enteringDetail)
            DispatcherQueue.TryEnqueue(() =>
            {
                if (Active && BackToTasksButton.Visibility == Visibility.Visible)
                    BackToTasksButton.Focus(FocusState.Programmatic);
            });
    }

    private void BackToTasks_Click(object sender, RoutedEventArgs e)
    {
        var list = _historyMode ? OperationRecords : TaskRecords;
        var selected = list.SelectedItem;
        _rendering = true;
        try { list.SelectedItem = null; }
        finally { _rendering = false; }
        _detailReference = null; _standaloneDetail = null;
        Render(synchronizeRows: false);
        DispatcherQueue.TryEnqueue(() =>
        {
            if (!Active || !_narrow || ListArea.Visibility != Visibility.Visible || list.Visibility != Visibility.Visible) return;
            var destination = selected is not null && list.ContainerFromItem(selected) is ListViewItem row ? (Control)row : list;
            destination.Focus(FocusState.Programmatic);
        });
    }

    private void Close_Click(object sender, RoutedEventArgs e) { SuspendView(); CloseRequested?.Invoke(this, EventArgs.Empty); }
    private async void Refresh_Click(object sender, RoutedEventArgs e) => await RefreshAsync();
    private async void DetailRefresh_Click(object sender, RoutedEventArgs e) => await RefreshDetailAsync();
    private async void CancelTask_Click(object sender, RoutedEventArgs e) => await ExecuteAsync(ServerTaskActionKind.CancellationRequested);
    private async void RetryTask_Click(object sender, RoutedEventArgs e) => await ExecuteAsync(ServerTaskActionKind.RetryAccepted);
    private async void ArchiveTask_Click(object sender, RoutedEventArgs e) => await ExecuteAsync(ServerTaskActionKind.Archived);
    private async void UnarchiveTask_Click(object sender, RoutedEventArgs e) => await ExecuteAsync(ServerTaskActionKind.Unarchived);
    private async void Reconcile_Click(object sender, RoutedEventArgs e) => await ReconcileAsync();
    private async void HistoryTab_Click(object sender, RoutedEventArgs e) => await ShowHistoryAsync();
    private void TasksTab_Click(object sender, RoutedEventArgs e) { if (Busy) return; _historyMode = false; Render(synchronizeRows: false); }
    private async void RelatedHistory_Click(object sender, RoutedEventArgs e) { _relatedTaskId = DetailTask?.Reference.Id; await ShowHistoryAsync(); }
    private async void NewRetry_Click(object sender, RoutedEventArgs e)
    {
        if (!Active || Busy || RetryTask is not { } retry) return;
        _rendering = true;
        try { TaskRecords.SelectedItem = null; }
        finally { _rendering = false; }
        _detailReference = retry.Reference; _standaloneDetail = retry;
        Render(synchronizeRows: false); await RefreshDetailAsync();
    }
    private async void More_Click(object sender, RoutedEventArgs e) =>
        await RunAsync(token => _historyMode ? _controller.LoadMoreOperationsAsync(token) : _controller.LoadMoreAsync(token));
    private async void Filter_Changed(object sender, SelectionChangedEventArgs e) { if (_ready && !_rendering) await ApplyFilterAsync(); }
    private async void Archived_Changed(object sender, RoutedEventArgs e) { if (_ready && !_rendering) await ApplyFilterAsync(); }
    private void Task_Changed(object sender, SelectionChangedEventArgs e)
    {
        if (_rendering || !_ready) return;
        _detailReference = (TaskRecords.SelectedItem as NasTaskRow)?.Task.Reference; _standaloneDetail = null;
        Render(synchronizeRows: false);
    }
    private void Operation_Changed(object sender, SelectionChangedEventArgs e) { if (!_rendering && _ready) Render(synchronizeRows: false); }
    private async void TaskRecords_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Handled || e.Key != VirtualKey.Enter) return;
        e.Handled = true;
        if (FocusedRow(TaskRecords, e.OriginalSource as DependencyObject) is NasTaskRow row)
            TaskRecords.SelectedItem = row;
        await RefreshDetailAsync();
    }
    private async void OperationRecords_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Handled || e.Key != VirtualKey.Enter) return;
        e.Handled = true;
        if (FocusedRow(OperationRecords, e.OriginalSource as DependencyObject) is NasTaskOperationRow row &&
            !ReferenceEquals(row, OperationRecords.SelectedItem))
            OperationRecords.SelectedItem = row;
        else await ReconcileAsync();
    }
    private static object? FocusedRow(ListView list, DependencyObject? source)
    {
        while (source is not null && !ReferenceEquals(source, list) && source is not ListViewItem)
            source = VisualTreeHelper.GetParent(source);
        return source is ListViewItem row && list.IndexFromContainer(row) >= 0 ? row.Content : null;
    }
    private async void TaskRecords_DoubleTapped(object sender, DoubleTappedRoutedEventArgs e)
    {
        var source = e.OriginalSource as DependencyObject;
        while (source is not null && !ReferenceEquals(source, TaskRecords) && source is not SelectorItem)
            source = VisualTreeHelper.GetParent(source);
        if (source is not SelectorItem item) return;
        TaskRecords.SelectedItem = item.Content; await RefreshDetailAsync();
    }
    private async void Panel_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Handled || HasOpenDialog) return;
        bool fullDetail = BackToTasksButton.Visibility == Visibility.Visible;
        if (e.Key == VirtualKey.F5)
        {
            e.Handled = true;
            if (fullDetail && !_historyMode) await RefreshDetailAsync();
            else await RefreshAsync();
        }
        else if (e.Key == VirtualKey.Escape)
        {
            e.Handled = true;
            if (fullDetail) BackToTasks_Click(this, new RoutedEventArgs());
            else Close_Click(this, new RoutedEventArgs());
        }
    }
    private void Panel_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        if (!_ready) return;
        _narrow = e.NewSize.Width < 880;
        PanelRoot.Padding = new Thickness(e.NewSize.Width < 560 ? 12 : 20, 12, e.NewSize.Width < 560 ? 12 : 20, 12);
        UpdateLayoutState();
    }

    public void Dispose()
    {
        if (_disposed) return;
        RetireUi(); _disposed = true; _active = false; _controller.Dispose();
        _viewCancellation.Dispose(); UnregisterPropertyChangedCallback(VisibilityProperty, _visibilityCallback);
        _tasks.Clear(); _operations.Clear(); _operationItems.Clear();
        _session = null; _taskService = null; _historyService = null; _standaloneDetail = null; _shownOperation = null;
        CloseRequested = null;
    }
}
