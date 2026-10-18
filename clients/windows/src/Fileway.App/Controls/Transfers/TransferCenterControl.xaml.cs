using System.Collections.ObjectModel;
using System.ComponentModel;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Windows.System;

namespace Fileway.App.Controls.Transfers;

/// <summary>Presentation only. Page navigation and record selection never execute transfer work.</summary>
public sealed partial class TransferCenterControl : UserControl, IDisposable
{
    private TransferDirection _direction = TransferDirection.Download;
    private readonly Microsoft.UI.Dispatching.DispatcherQueueTimer _clock;
    private readonly Microsoft.UI.Dispatching.DispatcherQueueTimer _noticeTimer;
    private ObservableCollection<TransferCenterRow>? _rows;
    public event EventHandler? CloseRequested;
    public event EventHandler? DirectionChanged;
    public event EventHandler? RefreshRequested;
    public event EventHandler? MoreRequested;
    public event EventHandler<TransferTaskRef>? DetailsRequested;
    public event EventHandler<TransferTaskRef>? PauseRequested;
    public event EventHandler<TransferTaskRef>? RemoveRequested;
    public TransferDirection Direction => _direction;

    public TransferCenterControl()
    {
        InitializeComponent(); Fileway.App.Controls.FloatingNotice.Attach(LoadError);
        _clock = DispatcherQueue.CreateTimer(); _clock.Interval = TimeSpan.FromSeconds(1);
        _clock.Tick += (_, _) => { if (_rows is not null) foreach (var row in _rows) row.RefreshClock(); };
        _noticeTimer = DispatcherQueue.CreateTimer(); _noticeTimer.IsRepeating = false;
        _noticeTimer.Tick += (_, _) => LoadError.IsOpen = false;
        Loaded += (_, _) => _clock.Start(); Unloaded += (_, _) => _clock.Stop();
    }
    public void Dispose() { _clock.Stop(); _noticeTimer.Stop(); }

    public void SelectDirection(TransferDirection direction)
    {
        _direction = direction;
        UploadsTab.IsChecked = direction == TransferDirection.Upload;
        DownloadsTab.IsChecked = direction == TransferDirection.Download;
        HintText.Text = direction == TransferDirection.Upload
            ? "上传进度显示服务器已确认的字节。打开记录不会开始上传；恢复与核实需要在详情中明确操作。"
            : "下载";
    }

    public void SetAvailability(bool uploads, bool downloads)
    {
        UploadsTab.IsEnabled = uploads; DownloadsTab.IsEnabled = downloads;
    }

    public void Present(ObservableCollection<TransferCenterRow> rows, bool loading, bool hasLoaded, bool canLoadMore, string? error)
    {
        if (!ReferenceEquals(_rows, rows)) { _rows = rows; Records.ItemsSource = rows; }
        HistoryLoadingRing.IsActive = loading;
        RefreshButton.IsEnabled = !loading;
        MoreButton.IsEnabled = !loading;
        MoreButton.Visibility = hasLoaded && (canLoadMore || loading) ? Visibility.Visible : Visibility.Collapsed;
        if (error != LoadError.Message)
        {
            LoadError.IsOpen = error is not null;
            if (error is not null) { _noticeTimer.Interval = TimeSpan.FromSeconds(PresentationPreferences.ToastSeconds); _noticeTimer.Start(); }
        }
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
    private void Pause_Click(object sender, RoutedEventArgs args) { if (sender is FrameworkElement { DataContext: TransferCenterRow row }) PauseRequested?.Invoke(this, row.Task); }
    private void Remove_Click(object sender, RoutedEventArgs args) { if (sender is FrameworkElement { DataContext: TransferCenterRow row }) RemoveRequested?.Invoke(this, row.Task); }
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
    public string IconName { get; }
    public string ProgressName { get; }
    public string Name { get; private set; } = "";
    public string Stage { get; private set; } = "";
    public string Bytes { get; private set; } = "";
    public string Destination { get; private set; } = "";
    public string Metrics { get; private set; } = "";
    public string AccessibleName => $"{Name}，{Stage}，{Bytes}，{Metrics}";
    public double Progress { get; private set; }
    public bool Indeterminate { get; private set; }
    public bool CanTogglePause { get; private set; }
    public bool CanRemove { get; private set; }
    public string PauseIcon { get; private set; } = "pause";
    public string PauseLabel { get; private set; } = "暂停下载";
    public Visibility ActionsVisibility { get; }
    private DownloadItem? _download;
    private long _lastBytes, _lastObservation;
    private TimeSpan _lastElapsed;
    private double _rate;
    internal TransferCenterRow(TransferTaskRef task, TransferDirection direction)
    {
        Task = task; Glyph = direction == TransferDirection.Upload ? "\uE898" : "\uE896";
        IconName = direction == TransferDirection.Upload ? "upload-simple" : "download-simple";
        ProgressName = direction == TransferDirection.Upload ? "服务器确认上传进度" : "下载接收进度";
        ActionsVisibility = direction == TransferDirection.Download ? Visibility.Visible : Visibility.Collapsed;
    }
    internal void Update(UploadItem item)
    {
        var p = UploadPresentationPolicy.Progress(item);
        Update(item.Name, UploadPresentationPolicy.UploadItemStatusLabel(item), p.ConfirmedText, item.DestinationDisplayPath, p.Value, p.IsIndeterminate);
    }
    internal void Update(DownloadItem item)
    {
        var previous = _download; _download = item;
        var p = DownloadPresentationPolicy.Progress(item);
        if (item.Progress is { } progress && (previous?.Progress is null || progress.Elapsed != _lastElapsed))
        {
            var seconds = (progress.Elapsed - _lastElapsed).TotalSeconds;
            if (seconds > 0 && progress.ReceivedBytes >= _lastBytes && item.Status == DownloadStatus.Downloading)
            {
                var sample = (progress.ReceivedBytes - _lastBytes) / seconds;
                _rate = _rate <= 0 ? sample : _rate * 0.6 + sample * 0.4;
            }
            _lastBytes = progress.ReceivedBytes; _lastElapsed = progress.Elapsed; _lastObservation = TimeProvider.System.GetTimestamp();
        }
        if (item.Status != DownloadStatus.Downloading) _rate = 0;
        CanTogglePause = item.CanPause || item.CanResume;
        CanRemove = item.Snapshot.Uncertainty == TransferUncertainty.None && item.Status is not (DownloadStatus.Publishing or DownloadStatus.Reconciling or DownloadStatus.Canceling or DownloadStatus.OutcomeUnknown);
        PauseIcon = item.CanResume ? "play" : "pause"; PauseLabel = item.CanResume ? "继续下载" : "暂停下载";
        Update(item.Name, DownloadPresentationPolicy.Stage(item), p.ActivityText, item.DestinationDisplayPath, p.Value, p.IsIndeterminate);
        RefreshClock();
    }
    internal void RefreshClock()
    {
        if (_download is not { } item) return;
        var speed = _lastObservation != 0 && TimeProvider.System.GetElapsedTime(_lastObservation).TotalSeconds < 3 ? _rate : 0;
        var end = item.Status is DownloadStatus.Completed or DownloadStatus.Canceled or DownloadStatus.Failed ? item.Snapshot.UpdatedAt ?? DateTimeOffset.UtcNow : DateTimeOffset.UtcNow;
        var elapsed = item.Snapshot.CreatedAt is { } start ? end - start : item.Progress?.Elapsed ?? TimeSpan.Zero;
        var remaining = Math.Max(0, (item.Snapshot.TotalBytes ?? 0) - (item.Progress?.ReceivedBytes ?? item.Snapshot.ConfirmedOffset ?? 0));
        var eta = speed > 0 ? Duration(TimeSpan.FromSeconds(Math.Min(remaining / speed, TimeSpan.MaxValue.TotalSeconds / 2))) : "—";
        var rate = speed > 0 ? DownloadPresentationPolicy.FormatBytes((long)speed) + "/s" : "0 KB/s";
        var metrics = item.Status == DownloadStatus.Completed ? "总耗时 " + Duration(elapsed) : $"{rate}  ·  剩余 {eta}  ·  已用 {Duration(elapsed)}";
        if (Metrics == metrics) return; Metrics = metrics; PropertyChanged?.Invoke(this, new(nameof(Metrics)));
    }
    private static string Duration(TimeSpan value) => value < TimeSpan.Zero ? "0:00" : value.TotalHours >= 1 ? $"{(int)value.TotalHours}:{value.Minutes:00}:{value.Seconds:00}" : $"{(int)value.TotalMinutes}:{value.Seconds:00}";
    private void Update(string name, string stage, string bytes, string destination, double progress, bool indeterminate)
    {
        Name = name; Stage = stage; Bytes = bytes; Destination = destination; Progress = progress; Indeterminate = indeterminate;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(null));
    }
}