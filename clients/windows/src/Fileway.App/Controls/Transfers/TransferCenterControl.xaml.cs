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
        Records.ContainerContentChanging += Records_ContainerContentChanging;
        _clock = DispatcherQueue.CreateTimer(); _clock.Interval = TimeSpan.FromSeconds(1);
        _clock.Tick += (_, _) => { if (_rows is not null) foreach (var row in _rows) row.RefreshClock(); };
        Loaded += (_, _) => _clock.Start(); Unloaded += (_, _) => _clock.Stop();
    }
    public void Dispose() { _clock.Stop(); FloatingNotice.Detach(LoadError); }

    public void SelectDirection(TransferDirection direction)
    {
        _direction = direction;
        UploadsTab.IsChecked = direction == TransferDirection.Upload;
        DownloadsTab.IsChecked = direction == TransferDirection.Download;
        EmptyState.IconName = direction == TransferDirection.Upload ? "upload-simple" : "download-simple";
    }

    public void SetAvailability(bool uploads, bool downloads)
    {
        UploadsTab.IsEnabled = uploads; DownloadsTab.IsEnabled = downloads;
    }

    public void Present(ObservableCollection<TransferCenterRow> rows, bool loading, bool hasLoaded, bool canLoadMore, string? error)
    {
        if (!ReferenceEquals(_rows, rows)) { _rows = rows; Records.ItemsSource = rows; }
        var count = rows.Count(row => !row.IsBatchHeader);
        HistoryLoadingRing.IsActive = loading;
        HistoryLoadingRing.Visibility = loading ? Visibility.Visible : Visibility.Collapsed;
        RefreshButton.IsEnabled = !loading;
        MoreButton.IsEnabled = !loading;
        MoreButton.Visibility = hasLoaded && (canLoadMore || loading) ? Visibility.Visible : Visibility.Collapsed;
        if (error != LoadError.Message)
        {
            LoadError.IsOpen = error is not null;
        }
        LoadError.Message = error;
        EmptyState.Visibility = count == 0 ? Visibility.Visible : Visibility.Collapsed;
        var emptyTitle = _direction == TransferDirection.Upload ? "暂无上传记录" : "暂无下载记录";
        var emptyHint = _direction == TransferDirection.Upload
            ? "从文件列表上传文件后，可在这里查看服务器确认进度。"
            : "从文件列表下载文件后，可在这里查看接收进度与保存位置。";
        EmptyState.Title = loading ? "正在读取记录" : error is not null ? "记录暂不可用" : !hasLoaded ? "等待加载记录"
            : canLoadMore ? "已加载范围内暂无记录" : emptyTitle;
        EmptyState.Description = loading ? "已有任务会继续执行，请稍候。" : error is not null ? "可以重试读取记录，现有任务不会重新提交。"
            : canLoadMore && hasLoaded ? "还有未读取的记录，请选择“加载更多”。" : emptyHint;
        LoadedText.Text = count == 0 ? "仅显示当前账号记录" : $"已显示 {count} 条记录" + (canLoadMore ? " · 还有更多记录" : "");
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
        if (e.ClickedItem is TransferCenterRow { IsBatchHeader: false } row) DetailsRequested?.Invoke(this, row.Task);
    }
    private static void Records_ContainerContentChanging(ListViewBase sender, ContainerContentChangingEventArgs args)
    {
        if (!args.InRecycleQueue && args.Item is TransferCenterRow row)
            args.ItemContainer.Margin = row.RowMargin;
        else
            args.ItemContainer.ClearValue(FrameworkElement.MarginProperty);
    }
    private void Pause_Click(object sender, RoutedEventArgs args) { if (sender is FrameworkElement { DataContext: TransferCenterRow { IsBatchHeader: false } row }) PauseRequested?.Invoke(this, row.Task); }
    private void Remove_Click(object sender, RoutedEventArgs args) { if (sender is FrameworkElement { DataContext: TransferCenterRow { IsBatchHeader: false } row }) RemoveRequested?.Invoke(this, row.Task); }
    private void Records_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key == VirtualKey.Enter && Records.SelectedItem is TransferCenterRow { IsBatchHeader: false } row)
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
    public string ProgressName { get; private set; }
    public bool IsBatchHeader { get; }
    public Guid? BatchId { get; }
    public Visibility HeaderVisibility { get; }
    public Visibility DetailVisibility { get; }
    public Thickness RowMargin { get; }
    internal UploadStatus Status { get; private set; }
    private long? ConfirmedBytes { get; set; }
    private long? TotalBytes { get; set; }
    private readonly UploadBatchInfo? _batch;
    public string Name { get; private set; } = "";
    public string Stage { get; private set; } = "";
    public string Bytes { get; private set; } = "";
    public Visibility BytesVisibility => string.IsNullOrEmpty(Bytes) ? Visibility.Collapsed : Visibility.Visible;
    public string Destination { get; private set; } = "";
    public string Metrics { get; private set; } = "";
    public Visibility MetricsVisibility => string.IsNullOrEmpty(Metrics) ? Visibility.Collapsed : Visibility.Visible;
    public string AccessibleName => IsBatchHeader ? $"{Name}，文件夹上传批次，{Stage}，{Bytes}"
        : string.Join("，", new[] { Name, Stage, Bytes, Metrics }.Where(text => text.Length > 0));
    public double Progress { get; private set; }
    public bool Indeterminate { get; private set; }
    public Visibility ProgressVisibility { get; private set; } = Visibility.Visible;
    public bool CanTogglePause { get; private set; }
    public Visibility PauseVisibility => CanTogglePause ? Visibility.Visible : Visibility.Collapsed;
    public bool CanRemove { get; private set; }
    public string PauseIcon { get; private set; } = "pause";
    public string PauseLabel { get; private set; } = "暂停下载";
    public Visibility ActionsVisibility { get; }
    private DownloadItem? _download;
    private long _lastBytes, _lastObservation;
    private TimeSpan _lastElapsed;
    private double _rate;
    internal TransferCenterRow(TransferTaskRef task, TransferDirection direction, Guid? batchId = null)
    {
        BatchId = batchId;
        Task = task; Glyph = direction == TransferDirection.Upload ? "\uE898" : "\uE896";
        IconName = direction == TransferDirection.Upload ? "upload-simple" : "download-simple";
        ProgressName = direction == TransferDirection.Upload ? "服务器确认上传进度" : "下载接收进度";
        ActionsVisibility = direction == TransferDirection.Download ? Visibility.Visible : Visibility.Collapsed;
        HeaderVisibility = Visibility.Collapsed; DetailVisibility = Visibility.Visible;
        RowMargin = new Thickness(batchId.HasValue ? 24 : 0, 0, 0, 6);
    }
    internal TransferCenterRow(UploadBatchInfo batch)
    {
        _batch = batch; BatchId = batch.Id; IsBatchHeader = true;
        Task = default; Glyph = ""; IconName = "folder"; ProgressName = "文件夹上传总进度";
        Name = batch.Name; ActionsVisibility = Visibility.Collapsed;
        HeaderVisibility = Visibility.Visible; DetailVisibility = Visibility.Collapsed;
        RowMargin = new Thickness(0, 0, 0, 6);
    }
    internal void UpdateBatch(IReadOnlyList<TransferCenterRow> children)
    {
        if (_batch is null) return;
        var total = (int)Math.Clamp(_batch.ItemCount, 0, int.MaxValue);
        var done = children.Count(row => row.Status == UploadStatus.Completed);
        var failed = children.Count(row => row.Status is UploadStatus.Failed or UploadStatus.OutcomeUnknown
            or UploadStatus.Conflict or UploadStatus.SourceChanged);
        Stage = failed > 0 ? $"{done}/{total} 已完成 · {failed} 项需处理" : $"{done}/{total} 已完成";

        var summary = new List<string> { $"计划 {total} 个文件" };
        if (_batch.TotalBytes > 0) summary.Add("总大小 " + DownloadPresentationPolicy.FormatBytes(_batch.TotalBytes));
        if (children.Count < total) summary.Add($"已显示 {children.Count} 条任务");
        var active = children.Count(row => UploadPresentationPolicy.IsActiveUpload(row.Status));
        if (active > 0) summary.Add($"{active} 项处理中");
        var waiting = children.Count(row => row.Status is UploadStatus.WaitingNetwork or UploadStatus.WaitingAuthentication or UploadStatus.NotStarted);
        if (waiting > 0) summary.Add($"{waiting} 项等待");
        var paused = children.Count(row => row.Status == UploadStatus.Paused);
        if (paused > 0) summary.Add($"{paused} 项已暂停");
        var canceled = children.Count(row => row.Status == UploadStatus.Canceled);
        if (canceled > 0) summary.Add($"{canceled} 项已取消");
        var skipped = children.Count(row => row.Status == UploadStatus.Skipped);
        if (skipped > 0) summary.Add($"{skipped} 项已跳过");
        Bytes = string.Join(" · ", summary);

        // Percent is meaningful only when the whole batch and its actual source sizes are known.
        var uploading = children.Any(row => row.Status == UploadStatus.Uploading);
        var knownSizes = children.Count == total && children.All(row => row.TotalBytes.HasValue);
        var totalBytes = knownSizes ? children.Sum(row => (double)row.TotalBytes.GetValueOrDefault()) : 0;
        var confirmed = children.Sum(row => (double)row.ConfirmedBytes.GetValueOrDefault());
        Progress = totalBytes > 0 ? Math.Clamp(confirmed * 100 / totalBytes, 0, 100) : 0;
        ProgressVisibility = uploading && (!knownSizes || totalBytes > 0) ? Visibility.Visible : Visibility.Collapsed;
        Indeterminate = uploading && (!knownSizes || !children.Any(row => row.ConfirmedBytes.HasValue));
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(null));
    }
    internal void Update(UploadItem item)
    {
        Status = item.Status;
        ConfirmedBytes = item.Snapshot.ConfirmedOffset;
        TotalBytes = item.Snapshot.TotalBytes;
        ProgressVisibility = item.Status is UploadStatus.Uploading or UploadStatus.Paused or UploadStatus.Failed or UploadStatus.Canceled
            ? Visibility.Visible : Visibility.Collapsed;
        var p = UploadPresentationPolicy.Progress(item);
        Update(item.Name, UploadPresentationPolicy.UploadItemStatusLabel(item), p.ConfirmedText, item.DestinationDisplayPath, p.Value, p.IsIndeterminate);
    }
    internal void Update(DownloadItem item)
    {
        var previous = _download; _download = item;
        var status = DownloadPresentationPolicy.EffectiveStatus(item);
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
        var showReceived = status == DownloadStatus.Downloading;
        var showConfirmed = !showReceived && DownloadPresentationPolicy.HasRetainedProgress(item);
        ProgressVisibility = showReceived || showConfirmed ? Visibility.Visible : Visibility.Collapsed;
        ProgressName = showConfirmed ? "磁盘确认写入进度" : "下载接收进度";
        CanTogglePause = item.CanPause || item.CanResume;
        CanRemove = item.Snapshot.Uncertainty == TransferUncertainty.None && item.Status is not (DownloadStatus.Publishing or DownloadStatus.Reconciling or DownloadStatus.Canceling or DownloadStatus.OutcomeUnknown);
        PauseIcon = item.CanResume ? "play" : "pause"; PauseLabel = item.CanResume ? "继续下载" : "暂停下载";
        var bytes = status == DownloadStatus.Completed ? DownloadPresentationPolicy.FileSizeText(item)
            : showConfirmed ? p.FlushedText : showReceived ? p.ActivityText : string.Empty;
        Update(item.Name, DownloadPresentationPolicy.Stage(item), bytes, item.DestinationDisplayPath,
            showConfirmed ? p.ConfirmedValue : p.Value, showReceived && p.IsIndeterminate);
        RefreshClock();
    }
    internal void RefreshClock()
    {
        if (_download is not { } item) return;
        var speed = _lastObservation != 0 && TimeProvider.System.GetElapsedTime(_lastObservation).TotalSeconds < 3 ? _rate : 0;
        var status = DownloadPresentationPolicy.EffectiveStatus(item);
        var end = status == DownloadStatus.Completed ? item.Snapshot.UpdatedAt ?? DateTimeOffset.UtcNow : DateTimeOffset.UtcNow;
        var elapsed = item.Snapshot.CreatedAt is { } start ? end - start : item.Progress?.Elapsed ?? TimeSpan.Zero;
        var elapsedText = Duration(elapsed);
        var remaining = Math.Max(0, (item.Snapshot.TotalBytes ?? 0) - (item.Progress?.ReceivedBytes ?? item.Snapshot.ConfirmedOffset ?? 0));
        var metrics = status switch
        {
            DownloadStatus.Downloading when speed > 0 =>
                $"{DownloadPresentationPolicy.FormatBytes((long)speed)}/s  ·  剩余 " +
                $"{Duration(TimeSpan.FromSeconds(Math.Min(remaining / speed, TimeSpan.MaxValue.TotalSeconds / 2)))}  ·  已用 {elapsedText}",
            DownloadStatus.Downloading or DownloadStatus.Paused => "已用 " + elapsedText,
            DownloadStatus.Completed => "总耗时 " + elapsedText,
            _ => string.Empty,
        };
        if (Metrics == metrics) return;
        Metrics = metrics;
        PropertyChanged?.Invoke(this, new(nameof(Metrics)));
        PropertyChanged?.Invoke(this, new(nameof(MetricsVisibility)));
        PropertyChanged?.Invoke(this, new(nameof(AccessibleName)));
    }
    private static string Duration(TimeSpan value) => value < TimeSpan.Zero ? "0:00" : value.TotalHours >= 1 ? $"{(int)value.TotalHours}:{value.Minutes:00}:{value.Seconds:00}" : $"{(int)value.TotalMinutes}:{value.Seconds:00}";
    private void Update(string name, string stage, string bytes, string destination, double progress, bool indeterminate)
    {
        Name = name; Stage = stage; Bytes = bytes; Destination = destination; Progress = progress; Indeterminate = indeterminate;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(null));
    }
}
