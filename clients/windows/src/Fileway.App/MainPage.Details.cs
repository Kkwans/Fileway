using System.Globalization;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;

namespace Fileway.App;

public sealed partial class MainPage
{
    private RemoteResourceRef? _folderStatsSource;
    private CancellationTokenSource? _folderStatsRequest;

    private void UpdateFolderStatsContext(RemoteResourceRef? source)
    {
        if (DetailsFolderStats is null) return;
        if (!_detailsVisible || _connecting || _files is not IDirectoryStatisticsService) source = null;
        if (ReferenceEquals(source, _folderStatsSource)) return;
        _folderStatsRequest?.Cancel();
        _folderStatsRequest = null;
        _folderStatsSource = source;
        DetailsFolderStats.Visibility = source is null ? Visibility.Collapsed : Visibility.Visible;
        DetailsFolderStatsStatus.Text = "仅统计当前账号可见的文件和子文件夹。";
        DetailsFolderStatsButton.Content = "统计";
        DetailsFolderStatsButton.IsEnabled = true;
    }

    private async void DetailsFolderStats_Click(object sender, RoutedEventArgs e)
    {
        if (_folderStatsSource is not { } source || _files is not IDirectoryStatisticsService service) return;
        if (_folderStatsRequest is { } current) { current.Cancel(); return; }
        using var request = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
        _folderStatsRequest = request;
        DetailsFolderStatsButton.Content = "取消";
        DetailsFolderStatsStatus.Text = "正在统计…";
        try
        {
            var stats = await service.ReadDirectoryStatisticsAsync(source, request.Token);
            if (ReferenceEquals(_folderStatsRequest, request) && !request.IsCancellationRequested)
                DetailsFolderStatsStatus.Text = string.Create(CultureInfo.CurrentCulture,
                    $"{stats.Files:N0} 个文件 · {stats.Directories:N0} 个子文件夹\n总大小 {DownloadPresentationPolicy.FormatBytes(stats.Bytes)}（{stats.Bytes:N0} 字节）");
        }
        catch (Exception error)
        {
            if (ReferenceEquals(_folderStatsRequest, request))
                DetailsFolderStatsStatus.Text = request.IsCancellationRequested
                    ? "统计已取消。"
                    : error is FilewayException { Error.Code: ErrorCode.ResponseTooLarge }
                        ? "目录内容较多，请使用存储工具进行后台分析。"
                        : "未能统计此文件夹，请重试。";
        }
        finally
        {
            if (ReferenceEquals(_folderStatsRequest, request))
            {
                _folderStatsRequest = null;
                if (request.IsCancellationRequested) DetailsFolderStatsStatus.Text = "统计已取消。";
                DetailsFolderStatsButton.Content = "重新统计";
            }
        }
    }

    private async void DetailsLocation_Click(object sender, RoutedEventArgs e)
    {
        if (_disposed || _connecting || _session is not { } session || ActiveFiles.SelectedItem is not FileRow row) return;
        var wire = row.File.Resource.WirePath;
        var display = row.File.Resource.DisplayPath;
        var cut = wire.LastIndexOf('/');
        var parentWire = cut <= 0 ? "/" : wire[..cut];
        var displayCut = display.LastIndexOf('/');
        var parentDisplay = displayCut <= 0 ? "/" : display[..displayCut];
        var parentName = parentDisplay == "/" ? "我的文件" : parentDisplay[(parentDisplay.LastIndexOf('/') + 1)..];
        SetDetailsVisible(false);
        await NavigateToResourceAsync(new RemoteResourceRef(session.Account, parentWire, parentName, parentDisplay));
    }

    private void DetailsChecksum_Click(object sender, RoutedEventArgs e) => FileChecksum_Click(sender, e);

    private async void DetailsRefreshMetadata_Click(object sender, RoutedEventArgs e)
    {
        if (_disposed || _connecting || _session is not { } session || _files is null || ActiveFiles.SelectedItem is not FileRow row) return;
        var source = row.File.Resource;
        var generation = _generation;
        try
        {
            var metadata = await _files.GetAsync(source, _lifetime.Token);
            if (_disposed || _generation != generation || _session?.Id != session.Id) return;
            var index = _rows.IndexOf(row);
            if (index >= 0) _rows[index] = new FileRow(metadata, _browserPreferences);
            UpdateSelection();
            SetNotice("元数据已刷新", metadata.Resource.DisplayName, Microsoft.UI.Xaml.Controls.InfoBarSeverity.Success);
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (_generation == generation) SetNotice("元数据刷新失败", ErrorMessage(error), Microsoft.UI.Xaml.Controls.InfoBarSeverity.Warning);
        }
    }
}
