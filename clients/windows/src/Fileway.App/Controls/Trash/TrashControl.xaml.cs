using System.Collections.ObjectModel;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
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
    private bool _deleting;
    private ConnectionSession? _session;
    private long _epoch;
    private bool _disposed, _ready, _rendering;
    private (string Title, string Detail, InfoBarSeverity Severity, string? Path)? _feedback;
    public event EventHandler? CloseRequested;
    public bool HasOpenDialog => _dialog is not null;
    private bool Busy => _deleting || _controller.IsLoading || _controller.IsRestoring || _controller.IsReconciling || HasOpenDialog;
    private const FileConflictPolicy Conflict = FileConflictPolicy.Fail;

    public TrashControl()
    {
        InitializeComponent();
        FloatingNotice.Attach(Notice, automatic: false);
        FloatingNotice.Attach(StatusToast, automatic: false);
        Records.ItemsSource = _rows; UnknownRecords.ItemsSource = _unknown;
        _ready = true; Unloaded += (_, _) => SuspendView(); Render();
    }
    public void SetContext(ITrashService? trash, IOperationHistoryService? history, ConnectionSession? session)
    {
        if (_disposed) return;
        var before = _controller.Generation; _session = session; _trash = trash;
        _controller.SetContext(trash, history, session);
        if (before != _controller.Generation) { Retire(); _feedback = null; Render(); }
        else Render(synchronize: false);
    }
    public async Task RefreshAsync()
    {
        if (_disposed || Visibility != Visibility.Visible || Busy || _session is null) return;
        var epoch = _epoch; _feedback = null;
        StatusToast.IsOpen = false;
        await RunAsync(() => _controller.RefreshAsync(), epoch);
    }
    private async Task RunAsync(Func<Task> action, long epoch)
    {
        try { var pending = action(); Render(synchronize: false); await pending; }
        catch (Exception) { if (Current(epoch)) SetFeedback("操作未能完成", "请刷新并核实结果，不要重复提交。", InfoBarSeverity.Warning); }
        if (Current(epoch)) Render();
    }
    private bool Current(long epoch) => !_disposed && epoch == _epoch && Visibility == Visibility.Visible;

    private void SetFeedback(string title, string detail, InfoBarSeverity severity, string? path = null)
    {
        if (severity == InfoBarSeverity.Success && detail.Length == 0 && path is null)
        {
            _feedback = null;
            StatusToast.Title = title;
            FloatingNotice.Show(StatusToast, title, repeat: true, compact: true);
            return;
        }
        StatusToast.IsOpen = false;
        _feedback = (title, detail, severity, path);
    }

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
            var readError = _controller.ReadError;
            var error = _controller.Error;
            if (_rows.Count == 0 && readError == error) error = null;
            error ??= _controller.OperationsError;
            var status = _feedback ?? (Title: error is null ? "" : "回收站需要处理", Detail: error ?? "",
                Severity: InfoBarSeverity.Warning, Path: (string?)null);
            if (Notice.Title != status.Title || Notice.Message != status.Detail || Notice.Severity != status.Severity ||
                RestoredPathBlock.Text != (status.Path ?? ""))
            {
                Notice.IsOpen = false;
                Notice.Title = status.Title;
                Notice.Message = status.Detail;
                Notice.Severity = status.Severity;
                RestoredPathBlock.Text = status.Path ?? "";
                RestoredPathBlock.Visibility = status.Path is null ? Visibility.Collapsed : Visibility.Visible;
                Notice.IsOpen = status.Title.Length > 0;
            }
            EmptyHost.Visibility = _rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            HeaderRow.Visibility = _rows.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
            EmptyHost.Title = _controller.IsLoading ? "正在读取回收站…" : _session is null ? "连接后查看回收站"
                : readError is not null ? "无法读取回收站" : _controller.HasLoadedItems ? "暂无回收站项目" : "尚未读取回收站";
            EmptyHost.Description = readError ?? (_controller.HasLoadedItems && !_controller.IsLoading
                ? "删除的文件和文件夹会先出现在这里，可在彻底删除前恢复。" : "");
            BusyRing.IsActive = Busy;
            RefreshButton.IsEnabled = _session is not null && !Busy;
            ReadRetryButton.Visibility = _session is not null && !_controller.IsLoading &&
                (readError is not null || !_controller.HasLoadedItems) ? Visibility.Visible : Visibility.Collapsed;
            ReadRetryButton.IsEnabled = RefreshButton.IsEnabled;
            RestoreButton.IsEnabled = !Busy && Records.SelectedItem is TrashRow selectedRow && _controller.CanRestore(selectedRow.Item, Conflict);
            PermanentDeleteButton.IsEnabled = !Busy && Records.SelectedItem is TrashRow deleteRow && _controller.CanDelete(deleteRow.Item);
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
        var choice = new ChoiceBox { HorizontalAlignment = HorizontalAlignment.Stretch };
        AutomationProperties.SetName(choice, "遇到同名项目时");
        AutomationProperties.SetAutomationId(choice, "TrashRestorePolicy");
        foreach (var label in new[] { "不覆盖，报告冲突", "保留两份", "替换同名项目", "跳过同名项目" }) choice.Items.Add(label);
        choice.SelectedIndex = 0;
        var panel = new StackPanel { Spacing = 12, MaxWidth = 480 };
        panel.Children.Add(TrashTarget(row));
        panel.Children.Add(DialogOrdering.FieldRow("遇到同名项目时", choice, 128));
        var policyHint = new TextBlock { Style = SurfaceGeometry.Style("FwCaptionTextStyle"), TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
        panel.Children.Add(policyHint);
        var dialog = new ContentDialog { XamlRoot = XamlRoot, RequestedTheme = ActualTheme,
            Title = "恢复此项目？", Content = panel,
            PrimaryButtonText = "恢复", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Primary,
            PrimaryButtonStyle = Application.Current.Resources["FwPrimaryButtonStyle"] as Style,
            CloseButtonStyle = Application.Current.Resources["FwSecondaryButtonStyle"] as Style };
        choice.SelectionChanged += (_, _) =>
        {
            var selected = (FileConflictPolicy)choice.SelectedIndex;
            dialog.PrimaryButtonText = selected == FileConflictPolicy.Replace ? "替换并恢复" : "恢复";
            dialog.IsPrimaryButtonEnabled = _controller.CanRestore(row.Item, selected);
            policyHint.Text = selected switch
            {
                FileConflictPolicy.KeepBoth => "原位置有同名项目时，为恢复项另取名称。",
                FileConflictPolicy.Replace => "原位置的同名项目会移入回收站，再恢复此项目。",
                FileConflictPolicy.Skip => "原位置有同名项目时，此记录留在回收站。",
                _ => "",
            };
            policyHint.Visibility = policyHint.Text.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        };
        _dialog = dialog; Render(synchronize: false);
        bool confirmed = false;
        try { confirmed = await dialog.ShowAsync() == ContentDialogResult.Primary; }
        catch (Exception) { if (Current(epoch)) SetFeedback("确认窗口未能打开", "请关闭其他对话框后重试。", InfoBarSeverity.Error); }
        finally { if (ReferenceEquals(_dialog, dialog)) _dialog = null; }
        var policy = (FileConflictPolicy)choice.SelectedIndex;
        if (!confirmed || !Current(epoch) || _controller.Generation != generation || _session?.Id != session.Id || _session.Account != session.Account ||
            !ReferenceEquals((Records.SelectedItem as TrashRow)?.Item, row.Item) || !_controller.CanRestore(row.Item, policy)) { if (Current(epoch)) Render(synchronize: false); return; }
        await RunAsync(async () =>
        {
            var previous = policy == FileConflictPolicy.Replace
                ? _controller.Items.Select(item => item.Reference).ToHashSet() : null;
            var result = await _controller.RestoreAsync(row.Item, policy);
            if (!Current(epoch) || result is null) return;
            if (result.Outcome == OperationOutcome.OutcomeUnknown)
            {
                SetFeedback("恢复结果尚未确认", "请刷新后核实操作记录，避免重复恢复。", InfoBarSeverity.Warning);
                return;
            }
            if (result.Outcome != OperationOutcome.Confirmed)
            {
                if (result.Error?.Code == ErrorCode.Conflict)
                    SetFeedback("恢复发生冲突", "原位置有同名项目时，可选择保留两份、替换或跳过。当前记录仍可刷新核对。", InfoBarSeverity.Warning);
                else SetFeedback("恢复未能完成", result.Error?.Message ?? "请刷新后核对这条记录。", InfoBarSeverity.Error);
                return;
            }
            var detail = result.Skipped == true ? "该记录仍在回收站。" : "";
            if (previous is not null)
            {
                await _controller.RefreshAsync();
                if (!Current(epoch)) return;
                if (_controller.Items.Any(item => !previous.Contains(item.Reference) &&
                    string.Equals(item.DisplayPath, row.Path, StringComparison.Ordinal)))
                    detail = "原位置的同名项目已移入回收站。";
            }
            if (result.Receipt is { AckPersisted: false })
                detail += "本地确认记录未保存，请保留此结果。";
            var restoredPath = result.Skipped == false && result.RestoredDisplayPath != row.Path ? result.RestoredDisplayPath : null;
            SetFeedback(result.Skipped == true ? "已跳过同名项目" : "恢复已完成", detail, InfoBarSeverity.Success, restoredPath);
        }, epoch);
    }

    private static StackPanel TrashTarget(TrashRow row)
    {
        var panel = new StackPanel { Spacing = 4 };
        var path = new TextBlock
        {
            Text = row.Path, TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true,
            FontSize = 14, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
        };
        AutomationProperties.SetAutomationId(path, "TrashOriginalPath");
        ToolTipService.SetToolTip(path, row.Path);
        panel.Children.Add(path);
        if (row.Item.DeletedAt is not null)
            panel.Children.Add(new TextBlock { Text = $"删除于 {row.Deleted}", Style = SurfaceGeometry.Style("FwCaptionTextStyle") });
        return panel;
    }
    private async void Reconcile_Click(object sender, RoutedEventArgs args)
    {
        if (_disposed || Busy || UnknownRecords.SelectedItem is not TrashOperationRow row || !_controller.CanReconcile(row.Record)) return;
        var epoch = _epoch;
        await RunAsync(async () =>
        {
            var result = await _controller.ReconcileAsync(row.Record);
            if (Current(epoch) && result is not null)
                SetFeedback("核实结果", result.Resolution switch
                {
                    OperationResolution.Confirmed => "已核实原操作确认记录，可刷新回收站。",
                    OperationResolution.NotSent => "已确认原操作未发送，可刷新后重新选择。",
                    _ => "原操作结果仍待核实，未重新提交。",
                }, InfoBarSeverity.Informational);
        }, epoch);
    }
    private void Records_RightTapped(object sender, RightTappedRoutedEventArgs args)
    {
        DependencyObject? source = args.OriginalSource as DependencyObject;
        while (source is not null && source is not ListViewItem && source != Records) source = VisualTreeHelper.GetParent(source);
        if (source is not ListViewItem { Content: TrashRow row }) return;
        Records.SelectedItem = row;
        var menu = new MenuFlyout();
        Fileway.App.Controls.SurfaceStyles.Attach(menu);
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
        title.Children.Add(new TextBlock { Text = "回收站项目详情", FontSize = 15, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
        header.Children.Add(title);
        var close = new Button { Content = new Fileway.App.Controls.LucideIcon { IconName = "x", Width = 18, Height = 18 }, Width = 32, Height = 32, Padding = new Thickness(0), Style = Application.Current.Resources["FwIconButtonStyle"] as Style };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(close, "关闭回收站详情");
        ToolTipService.SetToolTip(close, "关闭");
        Grid.SetColumn(close,1); header.Children.Add(close); root.Children.Add(header);
        var summary = new Grid { ColumnSpacing = 14 };
        summary.ColumnDefinitions.Add(new() { Width = GridLength.Auto });
        summary.ColumnDefinitions.Add(new() { Width = new(1, GridUnitType.Star) });
        var iconTile = new Border { Width = 56, Height = 56, CornerRadius = new CornerRadius(16), VerticalAlignment = VerticalAlignment.Center, Style = (Style)Application.Current.Resources["FwIconTileStyle"] };
        iconTile.Child = new Fileway.App.Controls.LucideIcon { IconName = row.IconName, Width = 30, Height = 30, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
        summary.Children.Add(iconTile);
        var nameBlock = new StackPanel { Spacing = 3, VerticalAlignment = VerticalAlignment.Center };
        nameBlock.Children.Add(new TextBlock { Text = row.Name, FontSize = 17, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true });
        nameBlock.Children.Add(new TextBlock { Text = row.Item.IsDirectory ? "文件夹" : "文件", Style = (Style)Application.Current.Resources["FwCaptionTextStyle"] });
        Grid.SetColumn(nameBlock, 1); summary.Children.Add(nameBlock);
        root.Children.Add(summary);
        StackPanel Field(string label, TextBlock value)
        {
            var field = new StackPanel { Spacing = 4 };
            field.Children.Add(new TextBlock { Text = label, Style = (Style)Application.Current.Resources["FwCaptionTextStyle"] });
            field.Children.Add(value);
            return field;
        }
        var info = new StackPanel { Spacing = 12 };
        info.Children.Add(Field("原位置", new TextBlock { Text = row.Path, FontSize = 13, LineHeight = 20, TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true }));
        var metrics = new Grid { ColumnSpacing = 16 };
        metrics.ColumnDefinitions.Add(new() { Width = new(1, GridUnitType.Star) });
        metrics.ColumnDefinitions.Add(new() { Width = new(1, GridUnitType.Star) });
        metrics.Children.Add(Field("大小", new TextBlock { Text = row.Size, FontSize = 13, TextWrapping = TextWrapping.Wrap }));
        var deleted = Field("删除时间", new TextBlock { Text = row.Deleted, FontSize = 13, TextWrapping = TextWrapping.Wrap });
        Grid.SetColumn(deleted, 1); metrics.Children.Add(deleted);
        info.Children.Add(metrics);
        info.Children.Add(Field("状态", new TextBlock { Text = row.Status, FontSize = 13 }));
        root.Children.Add(info);
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var restore = new Button { Content = new Fileway.App.Controls.LucideIcon { IconName = "arrow-counter-clockwise", Width = 20, Height = 20 }, IsEnabled = _controller.CanRestore(row.Item, Conflict), Style = Application.Current.Resources["FwIconButtonStyle"] as Style, Width = 36, Height = 36, Padding = new Thickness(0) };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(restore, "恢复项目");
        ToolTipService.SetToolTip(restore,"恢复"); restore.Click += (_, _) => { _detailsPopup?.Dispose(); Records.SelectedItem = row; Restore_Click(this,new()); };
        var delete = new Button { Content = new Fileway.App.Controls.LucideIcon { IconName = "trash", Width = 20, Height = 20 }, IsEnabled = _controller.CanDelete(row.Item), Style = Application.Current.Resources["FwIconButtonStyle"] as Style, Width = 36, Height = 36, Padding = new Thickness(0) };
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
        string warning = row.Item.IsDirectory
            ? "将永久删除此文件夹及其全部内容，不经过回收站，且无法恢复。"
            : "将永久删除此文件，不经过回收站，且无法恢复。";
        var panel = new StackPanel { Spacing = 12, MaxWidth = 480 };
        panel.Children.Add(TrashTarget(row));
        panel.Children.Add(new TextBlock { Text = warning, Style = SurfaceGeometry.Style("FwDangerTextStyle"), FontSize = 14 });
        var dialog = new ContentDialog { XamlRoot = XamlRoot, RequestedTheme = ActualTheme, Title = "永久删除此项目？",
            Content = panel, PrimaryButtonText = "永久删除", CloseButtonText = "取消", DefaultButton = ContentDialogButton.None };
        Fileway.App.Controls.DialogOrdering.FocusCloseButton(dialog);
        _dialog = dialog; bool accepted = false;
        try { accepted = await dialog.ShowAsync() == ContentDialogResult.Primary; }
        catch (Exception) { if (Current(epoch)) SetFeedback("确认窗口未能打开", "", InfoBarSeverity.Error); }
        finally { if (ReferenceEquals(_dialog,dialog)) _dialog = null; }
        if (!accepted || !Current(epoch) || _session?.Id != session.Id || _session.Account != session.Account || !_controller.CanDelete(row.Item)) { if(Current(epoch)) Render(false); return; }
        _deleting = true;
        try
        {
            await RunAsync(async () =>
            {
                var result = await _controller.DeleteAsync(row.Item);
                if (!Current(epoch) || result is null) return;
                if (result.Outcome == OperationOutcome.Confirmed)
                    SetFeedback("已永久删除", result.Receipt is { AckPersisted: false } ? "本地确认记录未保存，请保留此结果。" : "", InfoBarSeverity.Success);
                else if (result.Outcome == OperationOutcome.OutcomeUnknown)
                    SetFeedback("永久删除结果尚未确认", "请刷新并核实记录，避免重复操作。", InfoBarSeverity.Warning);
                else SetFeedback("永久删除未完成", result.Error?.Message ?? "请刷新并核对这条记录。", InfoBarSeverity.Error);
                await _controller.RefreshAsync();
            }, epoch);
        }
        finally { _deleting = false; if (Current(epoch)) Render(); }
    }
    private void Retire() { ++_epoch; StatusToast.IsOpen = false; _detailsPopup?.Dispose(); _detailsPopup = null; var dialog = _dialog; _dialog = null; try { dialog?.Hide(); } catch (Exception) { } }
    public void SuspendView() { if (_disposed) return; Retire(); _controller.SuspendView(); }
    public void Dispose() { if (_disposed) return; Retire(); FloatingNotice.Detach(Notice); FloatingNotice.Detach(StatusToast); _disposed = true; _controller.Dispose(); _rows.Clear(); _unknown.Clear(); CloseRequested = null; }
    private void Delete_Click(object sender, RoutedEventArgs args)
    {
        if (Records.SelectedItem is TrashRow row) DeleteRow(row);
    }
    private void Records_PreviewKeyDown(object sender, KeyRoutedEventArgs args)
    {
        if (args.Key != VirtualKey.Delete || args.Handled || !PermanentDeleteButton.IsEnabled) return;
        args.Handled = true;
        Delete_Click(this, new RoutedEventArgs());
    }
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
    public string RowDetail => Path + " · " + Status;
    public string RowSummary => Size + " · " + Deleted;
    public string Deleted => Item.DeletedAt?.ToLocalTime().ToString("yyyy/M/d HH:mm", System.Globalization.CultureInfo.CurrentCulture) ?? "—";
    public string Status => Item.Status switch { "available" => "可恢复", "failed" => "上次操作未完成", "restoring" => "正在恢复", _ => "暂不可恢复" };
    public string Summary => (Item.IsDirectory ? "文件夹" : "文件") + " · " + (Item.Size is { } size ? DownloadPresentationPolicy.FormatBytes(size) : "大小待确认") + " · " + (Item.Status switch { "available" => "可恢复", "failed" => "上次操作失败", "restoring" => "正在恢复", _ => "当前状态不可恢复" });
    public override string ToString() => "TrashRow (details redacted)";
}
public sealed class TrashOperationRow(OperationRecord record)
{
    public OperationRecord Record { get; } = record;
    public string Label => (Record.Intent.Kind == OperationKind.DeleteTrashPermanent ? "永久删除 · " : "恢复 · ") +
        (Record.Intent.Options?.DisplayPath ?? "原位置未知") + " · " +
        Record.CreatedAt.ToLocalTime().ToString("yyyy/M/d HH:mm", System.Globalization.CultureInfo.CurrentCulture);
    public override string ToString() => "TrashOperationRow (details redacted)";
}
