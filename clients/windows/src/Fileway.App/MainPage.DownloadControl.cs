using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App;

public sealed partial class MainPage
{
    private async Task ToggleDownloadPausedAsync(TransferTaskRef task)
    {
        if (_downloadOperationBusy || _downloads is not IDownloadControlService service || !_downloadItems.TryGetValue(task, out var item) || !IsCurrentDownloadContext(item.Context) || !item.CanPause && !item.CanResume) return;
        _downloadOperationBusy = true;
        try
        {
            AcceptDownloadItem(await service.SetPausedAsync(item.Context, task, item.CanPause, _lifetime.Token));
            if (item.CanPause && IsCurrentDownloadContext(item.Context)) SetNotice("下载已暂停", "可在本次运行中继续；彻底退出后尚不能续传，关闭到托盘会保留。", InfoBarSeverity.Informational);
        }
        catch (Exception error) { if (IsCurrentDownloadContext(item.Context)) SetNotice("下载操作未完成", ErrorMessage(error), InfoBarSeverity.Warning); }
        finally { _downloadOperationBusy = false; if (!_disposed) UpdateDownloadContext(); }
    }
    private async Task RemoveDownloadAsync(TransferTaskRef task)
    {
        if (_downloadOperationBusy || _downloads is not IDownloadControlService service || !_downloadItems.TryGetValue(task, out var item) || !IsCurrentDownloadContext(item.Context)) return;
        var local = new CheckBox { Content = "同时删除已下载的本地文件", IsChecked = false, Visibility = item.Status == DownloadStatus.Completed ? Visibility.Visible : Visibility.Collapsed };
        var panel = new StackPanel { Spacing = 16, MaxWidth = 420 };
        panel.Children.Add(new TextBlock { Text = item.Name + "\n\n" + (item.Status == DownloadStatus.Completed ? "默认只移除任务记录，本地文件保留。" : "将停止此任务并清理其临时文件。") + "\n不会删除 NAS 原文件。", TextWrapping = TextWrapping.Wrap });
        panel.Children.Add(local);
        if (await ShowBrowserDialogAsync(new ContentDialog { Title = "删除下载任务？", Content = panel, PrimaryButtonText = "删除任务", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Close }) != ContentDialogResult.Primary || !IsCurrentDownloadContext(item.Context)) return;
        _downloadOperationBusy = true;
        try
        {
            await service.RemoveAsync(item.Context, task, local.IsChecked == true, _lifetime.Token);
            if (!IsCurrentDownloadContext(item.Context)) return;
            _removedDownloadTasks.Add(task); _downloadItems.Remove(task); _transferDownloads.Remove(task);
            if (_transferDownloadRowIndex.Remove(task, out var row)) _transferDownloadRows.Remove(row);
            if (_latestDownload?.Snapshot.Task == task) _latestDownload = _downloadItems.Values.LastOrDefault(candidate => IsCurrentDownloadContext(candidate.Context));
            RenderTransferCenter(synchronizeRows: false); UpdateDownloadContext();
            SetNotice("已删除下载任务", local.IsChecked == true ? "本地文件与任务记录已删除。" : "任务记录已删除，NAS 原文件未改变。", InfoBarSeverity.Success);
        }
        catch (Exception error) { if (IsCurrentDownloadContext(item.Context)) SetNotice("任务删除未完成", ErrorMessage(error), InfoBarSeverity.Warning); }
        finally { _downloadOperationBusy = false; if (!_disposed) UpdateDownloadContext(); }
    }
}
