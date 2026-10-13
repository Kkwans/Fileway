using System.Collections.ObjectModel;
using System.ComponentModel;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Windows.System;

namespace Fileway.App.Controls.Transfers;

/// <summary>Presentation only. Page navigation and record selection never execute transfer work.</summary>
public sealed partial class TransferCenterControl : UserControl
{
    private TransferDirection _direction;
    private ObservableCollection<TransferCenterRow>? _rows;
    public event EventHandler? CloseRequested;
    public event EventHandler? DirectionChanged;
    public event EventHandler? RefreshRequested;
    public event EventHandler? MoreRequested;
    public event EventHandler<TransferTaskRef>? DetailsRequested;
    public TransferDirection Direction => _direction;

    public TransferCenterControl() => InitializeComponent();

    public void SelectDirection(TransferDirection direction)
    {
        _direction = direction;
        UploadsTab.IsChecked = direction == TransferDirection.Upload;
        DownloadsTab.IsChecked = direction == TransferDirection.Download;
        HintText.Text = direction == TransferDirection.Upload
            ? "上传进度显示服务器已确认的字节。打开记录不会开始上传；恢复与核实需要在详情中明确操作。"
            : "接收进度不代表文件已保存。当前下载不提供暂停或续传；未确认的本地结果可在详情中手动核实。";
    }

    public void SetAvailability(bool uploads, bool downloads)
    {
        UploadsTab.IsEnabled = uploads; DownloadsTab.IsEnabled = downloads;
    }

    public void Present(ObservableCollection<TransferCenterRow> rows, bool loading, bool hasLoaded, bool canLoadMore, string? error)
    {
        if (!ReferenceEquals(_rows, rows)) { _rows = rows; Records.ItemsSource = rows; }
        Loading.IsActive = loading;
        RefreshButton.IsEnabled = !loading;
        MoreButton.IsEnabled = !loading;
        MoreButton.Visibility = hasLoaded && (canLoadMore || loading) ? Visibility.Visible : Visibility.Collapsed;
        LoadError.IsOpen = error is not null;
        LoadError.Message = error;
        EmptyState.Visibility = rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        EmptyTitle.Text = loading ? "正在读取记录" : error is not null ? "记录暂不可用" : !hasLoaded ? "等待加载记录"
            : canLoadMore ? "已加载范围内暂无记录" : "暂无传输记录";
        EmptyText.Text = loading ? "已有任务会继续执行，请稍候。" : error is not null ? "可以重试读取记录，现有任务不会重新提交。"
            : canLoadMore && hasLoaded ? "还有未读取的记录，请选择“加载更多”。" : "从文件列表明确提交上传或下载后，可在这里查看。";
        LoadedText.Text = rows.Count == 0 ? "仅显示当前账号记录" : $"已显示 {rows.Count} 条记录" + (canLoadMore ? " · 还有更多记录" : "");
    }

    public void FocusRecords() => Records.Focus(FocusState.Keyboard);
    private void Close_Click(object sender, RoutedEventArgs e) => CloseRequested?.Invoke(this, EventArgs.Empty);
    private void Refresh_Click(object sender, RoutedEventArgs e) => RefreshRequested?.Invoke(this, EventArgs.Empty);
    private void More_Click(object sender, RoutedEventArgs e) => MoreRequested?.Invoke(this, EventArgs.Empty);
    private void Uploads_Click(object sender, RoutedEventArgs e) => ChangeDirection(TransferDirection.Upload);
    private void Downloads_Click(object sender, RoutedEventArgs e) => ChangeDirection(TransferDirection.Download);
    private void ChangeDirection(TransferDirection direction)
    {
        var changed = _direction != direction;
        SelectDirection(direction);
        if (changed) DirectionChanged?.Invoke(this, EventArgs.Empty);
    }
    private void Record_Click(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is TransferCenterRow row) DetailsRequested?.Invoke(this, row.Task);
    }
    private void Records_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key == VirtualKey.Enter && Records.SelectedItem is TransferCenterRow row)
        { e.Handled = true; DetailsRequested?.Invoke(this, row.Task); }
    }
    private void Panel_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        if (sender is Grid grid) grid.Padding = new Thickness(e.NewSize.Width < 500 ? 12 : 20, 12, e.NewSize.Width < 500 ? 12 : 20, 12);
    }
}

public sealed class TransferCenterRow : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;
    public TransferTaskRef Task { get; }
    public string Glyph { get; }
    public string ProgressName { get; }
    public string Name { get; private set; } = "";
    public string Stage { get; private set; } = "";
    public string Bytes { get; private set; } = "";
    public string Destination { get; private set; } = "";
    public string AccessibleName => $"{Name}，{Stage}，{Bytes}，{Destination}，按 Enter 查看详情";
    public double Progress { get; private set; }
    public bool Indeterminate { get; private set; }

    internal TransferCenterRow(TransferTaskRef task, TransferDirection direction)
    {
        Task = task; Glyph = direction == TransferDirection.Upload ? "\uE898" : "\uE896";
        ProgressName = direction == TransferDirection.Upload ? "服务器确认上传进度" : "下载接收活动，完成仍需校验与保存";
    }
    internal void Update(UploadItem item)
    {
        var progress = UploadPresentationPolicy.Progress(item);
        Update(item.Name, UploadPresentationPolicy.UploadItemStatusLabel(item), progress.ConfirmedText,
            "上传到：" + item.DestinationDisplayPath, progress.Value, progress.IsIndeterminate);
    }
    internal void Update(DownloadItem item)
    {
        var progress = DownloadPresentationPolicy.Progress(item);
        Update(item.Name, DownloadPresentationPolicy.Stage(item), progress.ActivityText + "\n" + progress.FlushedText,
            "保存到：" + item.DestinationDisplayPath, progress.Value, progress.IsIndeterminate);
    }
    private void Update(string name, string stage, string bytes, string destination, double progress, bool indeterminate)
    {
        if (Name == name && Stage == stage && Bytes == bytes && Destination == destination && Progress == progress && Indeterminate == indeterminate) return;
        Name = name; Stage = stage; Bytes = bytes; Destination = destination; Progress = progress; Indeterminate = indeterminate;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(null));
    }
}
