using System.Collections.ObjectModel;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Windows.System;

namespace Fileway.App.Controls.Trash;

public sealed partial class TrashControl : UserControl, IDisposable
{
    private readonly TrashListController _controller = new();
    private readonly ObservableCollection<TrashRow> _rows = [];
    private readonly ObservableCollection<TrashOperationRow> _unknown = [];
    private ContentDialog? _dialog;
    private ConnectionSession? _session;
    private long _epoch;
    private bool _disposed, _ready, _rendering;
    private string? _message;
    public event EventHandler? CloseRequested;
    public bool HasOpenDialog => _dialog is not null;
    private bool Busy => _controller.IsLoading || _controller.IsRestoring || _controller.IsReconciling || HasOpenDialog;
    private FileConflictPolicy Conflict => (FileConflictPolicy)Math.Clamp(ConflictBox.SelectedIndex, 0, 3);

    public TrashControl()
    {
        InitializeComponent(); Records.ItemsSource = _rows; UnknownRecords.ItemsSource = _unknown;
        _ready = true; Unloaded += (_, _) => SuspendView(); Render();
    }
    public void SetContext(ITrashService? trash, IOperationHistoryService? history, ConnectionSession? session)
    {
        if (_disposed) return;
        var before = _controller.Generation; _session = session;
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
            Notice.IsOpen = _message is not null || error is not null;
            Notice.Title = error is null ? "恢复结果" : "回收站需要处理";
            Notice.Message = _message ?? error ?? "";
            Notice.Severity = error is null ? InfoBarSeverity.Informational : InfoBarSeverity.Warning;
            Empty.Visibility = _rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            Empty.Text = _controller.IsLoading ? "正在读取回收站…" : _session is null ? "连接后查看回收站" : "暂无回收站项目";
            BusyRing.IsActive = Busy;
            RefreshButton.IsEnabled = _session is not null && !Busy;
            ConflictBox.IsEnabled = !Busy;
            RestoreButton.IsEnabled = !Busy && Records.SelectedItem is TrashRow selectedRow && _controller.CanRestore(selectedRow.Item, Conflict);
            ReconcileButton.IsEnabled = !Busy && UnknownRecords.SelectedItem is TrashOperationRow selectedOperation && _controller.CanReconcile(selectedOperation.Record);
            DetailText.Text = Records.SelectedItem is TrashRow detail ? detail.Path + "\n" + detail.Summary + "\n删除时间：" +
                (detail.Item.DeletedAt?.ToLocalTime().ToString("g", System.Globalization.CultureInfo.CurrentCulture) ?? "未提供") : "选择项目查看详情";
        }
        finally { _rendering = false; }
    }
    private async void Restore_Click(object sender, RoutedEventArgs args)
    {
        if (_disposed || Busy || Records.SelectedItem is not TrashRow row || _session is not { } session || !_controller.CanRestore(row.Item, Conflict)) return;
        var epoch = _epoch; var generation = _controller.Generation; var policy = Conflict;
        var dialog = new ContentDialog { XamlRoot = XamlRoot, RequestedTheme = ActualTheme,
            Title = policy == FileConflictPolicy.Replace ? "确认恢复并允许覆盖同名目标？" : "恢复此项目？",
            Content = new TextBlock { Text = row.Path + "\n\n" + (policy == FileConflictPolicy.Replace ? "服务器中的同名目标可能被替换。此操作需要创建和删除权限。" : "恢复到服务器原位置，按所选策略处理同名冲突。"), TextWrapping = TextWrapping.Wrap, MaxWidth = 440 },
            PrimaryButtonText = policy == FileConflictPolicy.Replace ? "允许覆盖并恢复" : "恢复", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Close };
        _dialog = dialog; Render(synchronize: false);
        bool confirmed = false;
        try { confirmed = await dialog.ShowAsync() == ContentDialogResult.Primary; }
        catch (Exception) { if (Current(epoch)) _message = "确认窗口未能打开，请关闭其他对话框后重试。"; }
        finally { if (ReferenceEquals(_dialog, dialog)) _dialog = null; }
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
            { OperationResolution.Confirmed => "已核实原操作确认记录，可刷新回收站。", OperationResolution.NotSent => "已确认原操作未发送，可刷新后重新选择。", _ => "原恢复结果仍待核实，未重新提交恢复。" };
        }, epoch);
    }
    private void Retire() { ++_epoch; var dialog = _dialog; _dialog = null; try { dialog?.Hide(); } catch (Exception) { } }
    public void SuspendView() { if (_disposed) return; Retire(); _controller.SuspendView(); }
    public void Dispose() { if (_disposed) return; Retire(); _disposed = true; _controller.Dispose(); _rows.Clear(); _unknown.Clear(); CloseRequested = null; }
    private async void Refresh_Click(object sender, RoutedEventArgs args) => await RefreshAsync();
    private void Close_Click(object sender, RoutedEventArgs args) => CloseRequested?.Invoke(this, EventArgs.Empty);
    private void Selection_Changed(object sender, SelectionChangedEventArgs args) { if (_ready && !_rendering) Render(synchronize: false); }
    private void Conflict_Changed(object sender, SelectionChangedEventArgs args) { if (_ready && !_rendering) Render(synchronize: false); }
    private async void Panel_KeyDown(object sender, KeyRoutedEventArgs args)
    { if (args.Handled || HasOpenDialog) return; if (args.Key == VirtualKey.F5) { args.Handled = true; await RefreshAsync(); } else if (args.Key == VirtualKey.Escape) { args.Handled = true; Close_Click(this, new()); } }
    private void Panel_SizeChanged(object sender, SizeChangedEventArgs args)
    { if (!_ready) return; var narrow = args.NewSize.Width < 760; DetailsColumn.Width = new(narrow ? 0 : 320); DetailsRow.Height = narrow ? new(1, GridUnitType.Star) : new(0); Grid.SetColumn(Details, narrow ? 0 : 1); Grid.SetRow(Details, narrow ? 1 : 0); Grid.SetColumnSpan(Details, narrow ? 2 : 1); Grid.SetColumnSpan(ListArea, narrow ? 2 : 1); }
}

public sealed class TrashRow(TrashItem item)
{
    public TrashItem Item { get; } = item;
    public string Path => Item.DisplayPath;
    public string Summary => (Item.IsDirectory ? "文件夹" : "文件") + " · " + (Item.Size is { } size ? DownloadPresentationPolicy.FormatBytes(size) : "大小待确认") + " · " + (Item.Status switch { "available" => "可恢复", "failed" => "上次操作失败", "restoring" => "正在恢复", _ => "当前状态不可恢复" });
    public override string ToString() => "TrashRow (details redacted)";
}
public sealed class TrashOperationRow(OperationRecord record)
{
    public OperationRecord Record { get; } = record;
    public string Label => Record.Intent.Options?.DisplayPath ?? "恢复操作待核实";
    public override string ToString() => "TrashOperationRow (details redacted)";
}
