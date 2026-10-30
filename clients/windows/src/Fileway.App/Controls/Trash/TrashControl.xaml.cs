using System.Collections.ObjectModel;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Windows.System;
using Fileway.App.MediaSurfaces;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Controls.Primitives;

namespace Fileway.App.Controls.Trash;

public sealed partial class TrashControl : UserControl, IDisposable
{
    private readonly TrashListController _controller = new();
    private readonly ObservableCollection<TrashRow> _rows = [];
    private readonly ObservableCollection<TrashOperationRow> _unknown = [];
    private ContentDialog? _dialog;
    private ITrashService? _trash;
    private PlayerSettingsWindow? _detailsPopup;
    private readonly Microsoft.UI.Dispatching.DispatcherQueueTimer _noticeTimer;
    private bool _deleting;
    private ConnectionSession? _session;
    private long _epoch;
    private bool _disposed, _ready, _rendering;
    private string? _message;
    public event EventHandler? CloseRequested;
    public bool HasOpenDialog => _dialog is not null;
    private bool Busy => _deleting || _controller.IsLoading || _controller.IsRestoring || _controller.IsReconciling || HasOpenDialog;
    private const FileConflictPolicy Conflict = FileConflictPolicy.Fail;

    public TrashControl()
    {
        InitializeComponent(); Fileway.App.Controls.FloatingNotice.Attach(Notice); Records.ItemsSource = _rows; UnknownRecords.ItemsSource = _unknown;
        _noticeTimer = DispatcherQueue.CreateTimer(); _noticeTimer.IsRepeating = false; _noticeTimer.Tick += (_, _) => Notice.IsOpen = false;
        _ready = true; Unloaded += (_, _) => SuspendView(); Render();
    }
    public void SetContext(ITrashService? trash, IOperationHistoryService? history, ConnectionSession? session)
    {
        if (_disposed) return;
        var before = _controller.Generation; _session = session; _trash = trash;
        _controller.SetContext(trash, history, session);
        if (before != _controller.Generation) { Retire(); _message = null; Render(); }
        else Render(synchronize: false);
    }
    public async Task RefreshAsync()
    {
        if (_disposed || Visibility != Visibility.Visible || Busy || _session is null) return;
        var epoch = _epoch; _message = null;
        await RunAsync(() => _controller.RefreshAsync(), epoch);
    }
    private async Task RunAsync(Func<Task> action, long epoch)
    {
        try { var pending = action(); Render(synchronize: false); await pending; }
        catch (Exception) { if (Current(epoch)) _message = "操作未能完成，请刷新并核实结果，不要重复提交。"; }
        if (Current(epoch)) Render();
    }
    private bool Current(long epoch) => !_disposed && epoch == _epoch && Visibility == Visibility.Visible;
    private void Render(bool synchronize = true)
    {
        if (!_ready || _disposed) return;
        _rendering = true;
        try
        {
            if (synchronize)
            {
                var selected = (Records.SelectedItem as TrashRow)?.Item.Reference;
                var known = _rows.ToDictionary(row => row.Item.Reference);
                var items = _controller.Items;
                for (var i = _rows.Count - 1; i >= 0; --i)
                    if (!items.Any(item => ReferenceEquals(item, _rows[i].Item))) _rows.RemoveAt(i);
                for (var i = 0; i < items.Count; ++i)
                {
                    var item = items[i];
                    var row = known.GetValueOrDefault(item.Reference);
                    if (row is null || !ReferenceEquals(row.Item, item)) row = new(item);
                    if (!_rows.Contains(row)) _rows.Insert(i, row);
                    else if (_rows.IndexOf(row) != i) _rows.Move(_rows.IndexOf(row), i);
                }
                Records.SelectedItem = _rows.FirstOrDefault(row => row.Item.Reference == selected);
                var operation = (UnknownRecords.SelectedItem as TrashOperationRow)?.Record.Intent.Reference;
                _unknown.Clear();
                foreach (var record in _controller.UnresolvedOperations) _unknown.Add(new(record));
                UnknownRecords.SelectedItem = _unknown.FirstOrDefault(row => row.Record.Intent.Reference == operation);
            }
            var error = _controller.Error ?? _controller.OperationsError;
            var message = _message ?? error ?? "";
            if (Notice.Message != message) { Notice.IsOpen = message.Length > 0; if (Notice.IsOpen) { _noticeTimer.Interval = TimeSpan.FromSeconds(PresentationPreferences.ToastSeconds); _noticeTimer.Start(); } }
            Notice.Title = error is null ? "操作结果" : "回收站需要处理";
            Notice.Message = message;
            Notice.Severity = error is null ? InfoBarSeverity.Informational : InfoBarSeverity.Warning;
            EmptyHost.Visibility = _rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            HeaderRow.Visibility = _rows.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
            Empty.Text = _controller.IsLoading ? "正在读取回收站…" : _session is null ? "连接后查看回收站" : "暂无回收站项目";
            BusyRing.IsActive = Busy;
            RefreshButton.IsEnabled = _session is not null && !Busy;
            RestoreButton.IsEnabled = !Busy && Records.SelectedItem is TrashRow selectedRow && _controller.CanRestore(selectedRow.Item, Conflict);
            ReconcileButton.IsEnabled = !Busy && UnknownRecords.SelectedItem is TrashOperationRow selectedOperation && _controller.CanReconcile(selectedOperation.Record);
            PendingPanel.Visibility = _unknown.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        }
        finally { _rendering = false; }
    }
    private async void Restore_Click(object sender, RoutedEventArgs args)
    {
        if (_disposed || Busy || Records.SelectedItem is not TrashRow row || _session is not { } session || !_controller.CanRestore(row.Item, Conflict)) return;
        _detailsPopup?.Dispose(); _detailsPopup = null;
        var epoch = _epoch; var generation = _controller.Generation;
        var choice = new Fileway.App.Controls.ChoiceBox { Header = "遇到同名项目时", HorizontalAlignment = HorizontalAlignment.Stretch };
        foreach (var label in new[] { "不覆盖，报告冲突", "保留两份", "替换同名项目", "跳过同名项目" }) choice.Items.Add(label);
        choice.SelectedIndex = 0;
        var panel = new StackPanel { Spacing = 16, MaxWidth = 440 };
        panel.Children.Add(new TextBlock { Text = row.Name + "\n\n恢复到：" + row.Path, TextWrapping = TextWrapping.Wrap }); panel.Children.Add(choice);
        var dialog = new ContentDialog { XamlRoot = XamlRoot, RequestedTheme = ActualTheme,
            Title = "恢复此项目？", Content = panel,
            PrimaryButtonText = "恢复", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Primary,
            PrimaryButtonStyle = Application.Current.Resources["FwPrimaryButtonStyle"] as Style,
            CloseButtonStyle = Application.Current.Resources["FwSecondaryButtonStyle"] as Style };
        choice.SelectionChanged += (_, _) => { dialog.PrimaryButtonText = choice.SelectedIndex == 2 ? "覆盖并恢复" : "恢复"; dialog.IsPrimaryButtonEnabled = _controller.CanRestore(row.Item, (FileConflictPolicy)choice.SelectedIndex); };
        _dialog = dialog; Render(synchronize: false);
        bool confirmed = false;
        try { confirmed = await dialog.ShowAsync() == ContentDialogResult.Primary; }
        catch (Exception) { if (Current(epoch)) _message = "确认窗口未能打开，请关闭其他对话框后重试。"; }
        finally { if (ReferenceEquals(_dialog, dialog)) _dialog = null; }
        var policy = (FileConflictPolicy)choice.SelectedIndex;
        if (!confirmed || !Current(epoch) || _controller.Generation != generation || _session?.Id != session.Id || _session.Account != session.Account ||
            !ReferenceEquals((Records.SelectedItem as TrashRow)?.Item, row.Item) || !_controller.CanRestore(row.Item, policy)) { if (Current(epoch)) Render(synchronize: false); return; }
        await RunAsync(async () =>
        {
            var result = await _controller.RestoreAsync(row.Item, policy);
            if (!Current(epoch) || result is null) return;
            _message = result.Outcome == OperationOutcome.Confirmed ? result.Skipped == true ? "已确认跳过同名项目，未恢复。" : "服务器已确认恢复。" :
                result.Outcome == OperationOutcome.OutcomeUnknown ? "恢复结果未知。请刷新后选择操作记录核实，不要重复恢复。" : "恢复未确认，请检查冲突或权限后重试。";
            if (result.Receipt is { AckPersisted: false }) _message += " 本地确认记录未保存，请保留此结果并核实。";
        }, epoch);
    }
    private async void Reconcile_Click(object sender, RoutedEventArgs args)
    {
        if (_disposed || Busy || UnknownRecords.SelectedItem is not TrashOperationRow row || !_controller.CanReconcile(row.Record)) return;
        var epoch = _epoch;
        await RunAsync(async () =>
        {
            var result = await _controller.ReconcileAsync(row.Record);
            if (Current(epoch) && result is not null) _message = result.Resolution switch
            { OperationResolution.Confirmed => "已核实原操作确认记录，可刷新回收站。", OperationResolution.NotSent => "已确认原操作未发送，可刷新后重新选择。", _ => "原操作结果仍待核实，未重新提交。" };
        }, epoch);
    }
    private void Records_RightTapped(object sender, RightTappedRoutedEventArgs args)
    {
        DependencyObject? source = args.OriginalSource as DependencyObject;
        while (source is not null && source is not ListViewItem && source != Records) source = VisualTreeHelper.GetParent(source);
        if (source is not ListViewItem { Content: TrashRow row }) return;
        Records.SelectedItem = row;
        var menu = new MenuFlyout();
        void Add(string text, string icon, Action action, bool enabled)
        {
            var item = new MenuFlyoutItem { Text = text, Icon = Fileway.App.Controls.LucideIcon.Action(icon), IsEnabled = enabled };
            item.Click += (_, _) => action(); menu.Items.Add(item);
        }
        Add("恢复", "arrow-counter-clockwise", () => Restore_Click(this, new()), !Busy && _controller.CanRestore(row.Item, Conflict));
        Add("永久删除…", "trash", () => DeleteRow(row), !Busy && _controller.CanDelete(row.Item));
        Add("查看详情", "info", () => ShowDetails(row), !Busy);
        args.Handled = true; menu.ShowAt(Records, new FlyoutShowOptions { Position = args.GetPosition(Records) });
    }
    private void Records_DoubleTapped(object sender, DoubleTappedRoutedEventArgs args)
    { if (!Busy && Records.SelectedItem is TrashRow row) { args.Handled = true; ShowDetails(row); } }

    private void ShowDetails(TrashRow row)
    {
        if (XamlRoot is null || Busy) return;
        _detailsPopup?.Dispose();
        var root = new StackPanel { Width = 480, Padding = new Thickness(20), Spacing = 16 };
        var header = new Grid { Height = 32 }; header.ColumnDefinitions.Add(new() { Width = new(1, GridUnitType.Star) }); header.ColumnDefinitions.Add(new() { Width = GridLength.Auto });
        Microsoft.UI.Xaml.Controls.ToolTipService.SetToolTip(header, "按住标题区域拖动移动窗口");
        var title = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, VerticalAlignment = VerticalAlignment.Center };
        title.Children.Add(new Fileway.App.Controls.LucideIcon { IconName = "dots-three", Width = 16, Height = 16 });
        title.Children.Add(new TextBlock { Text = "回收站项目详情", FontSize = 16, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
        header.Children.Add(title);
        var close = new Button { Content = new Fileway.App.Controls.LucideIcon { IconName = "x", Width = 18, Height = 18 }, Width = 32, Height = 32, Padding = new Thickness(0), Style = Application.Current.Resources["FwIconButtonStyle"] as Style };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(close, "关闭回收站详情");
        ToolTipService.SetToolTip(close, "关闭");
        Grid.SetColumn(close,1); header.Children.Add(close); root.Children.Add(header);
        root.Children.Add(new Fileway.App.Controls.LucideIcon { IconName = row.IconName, Width = 48, Height = 48, HorizontalAlignment = HorizontalAlignment.Left });
        root.Children.Add(new TextBlock { Text = row.Name, TextWrapping = TextWrapping.Wrap, FontSize = 18, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, IsTextSelectionEnabled = true });
        root.Children.Add(new TextBlock { Text = "原位置\n" + row.Path + "\n\n大小：" + row.Size + "\n删除时间：" + row.Deleted + "\n状态：" + row.Status, LineHeight = 25, TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true });
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var restore = new Button { Content = new Fileway.App.Controls.LucideIcon { IconName = "arrow-counter-clockwise", Width = 20, Height = 20 }, IsEnabled = _controller.CanRestore(row.Item, Conflict), Style = Application.Current.Resources["FwIconButtonStyle"] as Style, Width = 34, Height = 34, Padding = new Thickness(0) };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(restore, "恢复项目");
        ToolTipService.SetToolTip(restore,"恢复"); restore.Click += (_, _) => { _detailsPopup?.Dispose(); Records.SelectedItem = row; Restore_Click(this,new()); };
        var delete = new Button { Content = new Fileway.App.Controls.LucideIcon { IconName = "trash", Width = 20, Height = 20 }, IsEnabled = _controller.CanDelete(row.Item), Style = Application.Current.Resources["FwIconButtonStyle"] as Style, Width = 34, Height = 34, Padding = new Thickness(0) };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(delete, "永久删除项目");
        ToolTipService.SetToolTip(delete,"永久删除"); delete.Click += (_, _) => { _detailsPopup?.Dispose(); DeleteRow(row); };
        actions.Children.Add(restore); actions.Children.Add(delete); root.Children.Add(actions);
        var owner = Microsoft.UI.Win32Interop.GetWindowFromWindowId(XamlRoot.ContentIslandEnvironment.AppWindowId);
        var popup = new PlayerSettingsWindow(owner, root, null, () => _detailsPopup = null, theme: ActualTheme, wrapContent:false, lightDismiss:false, movable:true);
        popup.Title = "回收站项目详情";
        _detailsPopup = popup; close.Click += (_, _) => popup.Dispose(); popup.ShowCentered();
    }

    private async void DeleteRow(TrashRow row)
    {
        if (Busy || _trash is not ITrashManagementService || _session is not { } session || !_controller.CanDelete(row.Item)) return;
        _detailsPopup?.Dispose(); var epoch = _epoch;
        var dialog = new ContentDialog { XamlRoot = XamlRoot, RequestedTheme = ActualTheme, Title = "永久删除此项目？",
            Content = new TextBlock { Text = row.Name + "\n\n此操作无法恢复。", TextWrapping = TextWrapping.Wrap }, PrimaryButtonText = "永久删除", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Close };
        _dialog = dialog; bool accepted = false;
        try { accepted = await dialog.ShowAsync() == ContentDialogResult.Primary; }
        catch (Exception) { if (Current(epoch)) _message = "确认窗口未能打开。"; }
        finally { if (ReferenceEquals(_dialog,dialog)) _dialog = null; }
        if (!accepted || !Current(epoch) || _session?.Id != session.Id || _session.Account != session.Account || !_controller.CanDelete(row.Item)) { if(Current(epoch)) Render(false); return; }
        _deleting = true;
        try
        {
            await RunAsync(async () =>
            {
                var result = await _controller.DeleteAsync(row.Item);
                if (!Current(epoch) || result is null) return;
                _message = result.Outcome == OperationOutcome.Confirmed ? "已永久删除。" : result.Outcome == OperationOutcome.OutcomeUnknown ? "永久删除结果尚未确认，请刷新并核实，不要重复操作。" : result.Error?.Message ?? "永久删除未完成。";
                await _controller.RefreshAsync();
            }, epoch);
        }
        finally { _deleting = false; if (Current(epoch)) Render(); }
    }
    private void Retire() { ++_epoch; _detailsPopup?.Dispose(); _detailsPopup = null; var dialog = _dialog; _dialog = null; try { dialog?.Hide(); } catch (Exception) { } }
    public void SuspendView() { if (_disposed) return; Retire(); _controller.SuspendView(); }
    public void Dispose() { if (_disposed) return; Retire(); _noticeTimer.Stop(); _disposed = true; _controller.Dispose(); _rows.Clear(); _unknown.Clear(); CloseRequested = null; }
    private async void Refresh_Click(object sender, RoutedEventArgs args) => await RefreshAsync();
    private void Close_Click(object sender, RoutedEventArgs args) => CloseRequested?.Invoke(this, EventArgs.Empty);
    private void Selection_Changed(object sender, SelectionChangedEventArgs args) { if (_ready && !_rendering) Render(synchronize: false); }
    private async void Panel_KeyDown(object sender, KeyRoutedEventArgs args)
    { if (args.Handled || HasOpenDialog) return; if (args.Key == VirtualKey.F5) { args.Handled = true; await RefreshAsync(); } else if (args.Key == VirtualKey.Escape) { args.Handled = true; Close_Click(this, new()); } }
    private void Panel_SizeChanged(object sender, SizeChangedEventArgs args)
    { if (sender is Grid grid) grid.Padding = new Thickness(args.NewSize.Width < 600 ? 12 : 20, 16, args.NewSize.Width < 600 ? 12 : 20, 12); }
}

public sealed class TrashRow(TrashItem item)
{
    public TrashItem Item { get; } = item;
    public string Path => Item.DisplayPath;
    public string Name => Path.TrimEnd('/').Split('/').LastOrDefault() ?? Path;
    public string IconName => Item.IsDirectory ? "folder" : "file";
    public string Size => Item.Size is { } value ? DownloadPresentationPolicy.FormatBytes(value) : "—";
    public string Deleted => Item.DeletedAt?.ToLocalTime().ToString("yyyy/M/d HH:mm", System.Globalization.CultureInfo.CurrentCulture) ?? "—";
    public string Status => Item.Status switch { "available" => "可恢复", "failed" => "上次操作未完成", "restoring" => "正在恢复", _ => "暂不可恢复" };
    public string Summary => (Item.IsDirectory ? "文件夹" : "文件") + " · " + (Item.Size is { } size ? DownloadPresentationPolicy.FormatBytes(size) : "大小待确认") + " · " + (Item.Status switch { "available" => "可恢复", "failed" => "上次操作失败", "restoring" => "正在恢复", _ => "当前状态不可恢复" });
    public override string ToString() => "TrashRow (details redacted)";
}
public sealed class TrashOperationRow(OperationRecord record)
{
    public OperationRecord Record { get; } = record;
    public string Label => (Record.Intent.Kind == OperationKind.DeleteTrashPermanent ? "永久删除 · " : "恢复 · ") + (Record.Intent.Options?.DisplayPath?.TrimEnd('/' ).Split('/').LastOrDefault() ?? "结果待核实");
    public override string ToString() => "TrashOperationRow (details redacted)";
}
