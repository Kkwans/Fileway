using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App;

public sealed partial class MainPage
{
    private async Task ToggleDownloadPausedAsync(TransferTaskRef task)
    {
        if (_downloadOperationBusy || _downloads is not IDownloadControlService service || !_downloadItems.TryGetValue(task, out var item) || !IsCurrentDownloadContext(item.Context) || !item.CanPause && !item.CanResume) return;
        var owner = _downloadDetails;
        _downloadOperationBusy = true;
        ClearDownloadDetailError(task);
        try
        {
            UpdateDownloadContext();
            // The presentation exposes Paused only while a live writer is scheduled.
            bool inProcess = item.CanPause || item.Status == DownloadStatus.Paused;
            if (inProcess)
            {
                AcceptDownloadItem(await service.SetPausedAsync(item.Context, task, item.CanPause, _lifetime.Token));
                if (item.CanPause && IsCurrentDownloadContext(item.Context)) SetNotice("下载已暂停", string.Empty, InfoBarSeverity.Informational);
            }
            else
            {
                AcceptDownloadItem(await service.ResumeAsync(item.Context, task, _lifetime.Token));
                if (IsCurrentDownloadContext(item.Context) && _downloadDetailTask != task)
                    SetNotice("下载已重新排队", string.Empty, InfoBarSeverity.Informational);
            }
        }
        catch (Exception error)
        {
            if (IsCurrentDownloadContext(item.Context))
                ShowDownloadError("下载操作未完成", error, task, owner);
        }
        finally { _downloadOperationBusy = false; if (!_disposed) UpdateDownloadContext(); }
    }
    private async Task RemoveDownloadAsync(TransferTaskRef task)
    {
        if (_downloadOperationBusy || _downloads is not IDownloadControlService service || !_downloadItems.TryGetValue(task, out var item) || !IsCurrentDownloadContext(item.Context)) return;
        var local = new CheckBox { Content = "同时删除已下载的本地文件", IsChecked = false, Visibility = item.Status == DownloadStatus.Completed ? Visibility.Visible : Visibility.Collapsed };
        var panel = new StackPanel { Spacing = 16, MaxWidth = 420 };
        panel.Children.Add(new TextBlock { Text = item.Name + "\n\n" + (item.Status == DownloadStatus.Completed ? "默认只移除任务记录，本地文件保留。" : "将停止此任务并清理其临时文件。") + "\n不会删除 NAS 原文件。", TextWrapping = TextWrapping.Wrap });
        panel.Children.Add(local);
        if (await ShowBrowserDialogAsync(new ContentDialog { Title = "删除下载任务？", Content = panel, PrimaryButtonText = "删除任务", CloseButtonText = "取消", DefaultButton = ContentDialogButton.None }) != ContentDialogResult.Primary || !IsCurrentDownloadContext(item.Context)) return;
        _downloadOperationBusy = true;
        try
        {
            await service.RemoveAsync(item.Context, task, local.IsChecked == true, _lifetime.Token);
            if (!IsCurrentDownloadContext(item.Context)) return;
            _removedDownloadTasks.Add(task); _downloadItems.Remove(task); _transferDownloads.Remove(task);
            if (_pendingExternalDownloadOpens.Remove(task, out var pending)) pending.TrySetCanceled();
            if (_transferDownloadRowIndex.Remove(task, out var row)) _transferDownloadRows.Remove(row);
            _pendingDownloadNotices.Remove(task);
            RenderTransferCenter(synchronizeRows: false); UpdateDownloadContext();
            SetNotice(local.IsChecked == true ? "下载记录和本地文件已删除" : "已删除下载记录",
                string.Empty, InfoBarSeverity.Success);
        }
        catch (Exception error)
        {
            if (IsCurrentDownloadContext(item.Context))
                ShowDownloadError("任务删除未完成", error);
        }
        finally { _downloadOperationBusy = false; if (!_disposed) UpdateDownloadContext(); }
    }
}
