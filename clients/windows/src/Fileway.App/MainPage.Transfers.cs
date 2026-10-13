using System.Collections.ObjectModel;
using Fileway.App.Controls.Transfers;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;

namespace Fileway.App;

public sealed partial class MainPage
{
    private readonly TransferCenterPageState<UploadItem> _transferUploads = new(item => item.Snapshot.Task, UploadPresentationPolicy.PreferUploadItem);
    private readonly TransferCenterPageState<DownloadItem> _transferDownloads = new(item => item.Snapshot.Task, DownloadPresentationPolicy.Prefer);
    private readonly ObservableCollection<TransferCenterRow> _transferUploadRows = [];
    private readonly ObservableCollection<TransferCenterRow> _transferDownloadRows = [];
    private readonly Dictionary<TransferTaskRef, TransferCenterRow> _transferUploadRowIndex = [];
    private readonly Dictionary<TransferTaskRef, TransferCenterRow> _transferDownloadRowIndex = [];
    private UploadContext? _transferUploadContext;
    private DownloadContext? _transferDownloadContext;

    private void InitializeTransferCenter()
    {
        TransfersPanel.SelectDirection(TransferDirection.Upload);
        TransfersPanel.CloseRequested += (_, _) =>
        {
            HideCollections();
            // A completion observed while this page hid the browser could not refresh its origin.
            // Reuse the original account/directory/generation checks once that browser is visible.
            foreach (var item in _uploadItems.Values.ToArray()) TryRefreshCompletedUpload(item);
            ActiveFiles.Focus(FocusState.Keyboard);
        };
        TransfersPanel.DirectionChanged += async (_, _) => { RenderTransferCenter(); await LoadInitialTransferPageAsync(); };
        TransfersPanel.MoreRequested += async (_, _) => await LoadTransferPageAsync();
        TransfersPanel.RefreshRequested += async (_, _) =>
        {
            if (TransfersPanel.Direction == TransferDirection.Upload) _transferUploads.BeginRefresh();
            else _transferDownloads.BeginRefresh();
            await LoadTransferPageAsync();
        };
        TransfersPanel.DetailsRequested += async (_, task) =>
        {
            if (TransfersPanel.Direction == TransferDirection.Upload) await ShowUploadDetailsAsync(task);
            else await ShowDownloadDetailsAsync(task);
        };
        RenderTransferCenter();
    }

    private async void TransferCenter_Click(object sender, RoutedEventArgs e) => await ShowTransferCenterAsync(
        TransfersPanel.Direction == TransferDirection.Upload && ActiveUploadContext is null ? TransferDirection.Download
        : TransfersPanel.Direction == TransferDirection.Download && ActiveDownloadContext is null ? TransferDirection.Upload : TransfersPanel.Direction);

    private async Task ShowTransferCenterAsync(TransferDirection direction)
    {
        if (_disposed || _connecting || _session is null || _browserDialog is not null) return;
        UpdateTransferCenterContext();
        if (direction == TransferDirection.Upload ? ActiveUploadContext is null : ActiveDownloadContext is null) return;
        CloseSidebarOverlay(); HideCollections();
        Workspace.Visibility = Visibility.Collapsed;
        TransfersPanel.Visibility = Visibility.Visible;
        TransfersPanel.SelectDirection(direction);
        _thumbnailController.SetContext(null, null);
        // Seed real current-session observations; opening this view never admits execution.
        foreach (var item in _uploadItems.Values) if (IsCurrentUploadContext(item.Context)) _transferUploads.Upsert(item);
        foreach (var item in _downloadItems.Values) if (IsCurrentDownloadContext(item.Context)) _transferDownloads.Upsert(item);
        RenderTransferCenter();
        UpdateSelection();
        await LoadInitialTransferPageAsync();
    }

    private Task LoadInitialTransferPageAsync() => (TransfersPanel.Direction == TransferDirection.Upload ? _transferUploads.HasLoaded : _transferDownloads.HasLoaded)
        ? Task.CompletedTask : LoadTransferPageAsync();

    private void ClearTransferUploadPresentation()
    {
        _transferUploadContext = null; _transferUploads.Reset(); _transferUploadRows.Clear(); _transferUploadRowIndex.Clear(); RenderTransferCenter();
    }
    private void ClearTransferDownloadPresentation()
    {
        _transferDownloadContext = null; _transferDownloads.Reset(); _transferDownloadRows.Clear(); _transferDownloadRowIndex.Clear(); RenderTransferCenter();
    }

    private void UpdateTransferCenterContext()
    {
        if (TransfersPanel is null) return;
        var upload = ActiveUploadContext;
        var download = ActiveDownloadContext;
        var changed = _transferUploadContext != upload || _transferDownloadContext != download;
        if (_transferUploadContext != upload)
        {
            _transferUploadContext = upload; _transferUploads.Reset(); _transferUploadRows.Clear(); _transferUploadRowIndex.Clear();
        }
        if (_transferDownloadContext != download)
        {
            _transferDownloadContext = download; _transferDownloads.Reset(); _transferDownloadRows.Clear(); _transferDownloadRowIndex.Clear();
        }
        TransfersPanel.SetAvailability(upload is not null, download is not null);
        TransferCenterButton.IsEnabled = !_disposed && !_connecting && (upload is not null || download is not null);
        if (!TransferCenterButton.IsEnabled) TransfersPanel.Visibility = Visibility.Collapsed;
        if (changed) RenderTransferCenter(synchronizeRows: false);
    }

    private void ObserveTransferUpload(UploadItem item)
    {
        if (!IsCurrentUploadContext(item.Context)) return;
        UpdateTransferCenterContext(); _transferUploads.Upsert(item);
        if (TransfersPanel.Visibility == Visibility.Visible && TransfersPanel.Direction == TransferDirection.Upload)
        { ApplyTransferUploadRow(item); RenderTransferCenter(synchronizeRows: false); }
    }
    private void ObserveTransferDownload(DownloadItem item)
    {
        if (!IsCurrentDownloadContext(item.Context)) return;
        UpdateTransferCenterContext(); _transferDownloads.Upsert(item);
        if (TransfersPanel.Visibility == Visibility.Visible && TransfersPanel.Direction == TransferDirection.Download)
        { ApplyTransferDownloadRow(item); RenderTransferCenter(synchronizeRows: false); }
    }

    private async Task LoadTransferPageAsync()
    {
        UpdateTransferCenterContext();
        if (TransfersPanel.Visibility != Visibility.Visible || _disposed) return;
        if (TransfersPanel.Direction == TransferDirection.Upload)
        {
            if (_uploads is not { } service || ActiveUploadContext is not { } context
                || !_transferUploads.TryBeginLoad(out var token, out var cursor)) return;
            RenderTransferCenter(synchronizeRows: false);
            try
            {
                var page = await service.ListAsync(context, cursor, 30, _lifetime.Token);
                if (!IsCurrentUploadContext(context) || !ReferenceEquals(service, _uploads)) return;
                var items = page.Items.Where(item => item.Context == context && item.Snapshot.Task.Account == context.Account).ToArray();
                if (!_transferUploads.CompleteLoad(token, items, page.NextCursor)) return;
                foreach (var item in items)
                    _uploadItems[item.Snapshot.Task] = _uploadItems.TryGetValue(item.Snapshot.Task, out var current)
                        ? UploadPresentationPolicy.PreferUploadItem(current, item) : item;
            }
            catch (OperationCanceledException) { _transferUploads.FailLoad(token, "记录读取已停止，可以重试。"); }
            catch (Exception error) { if (IsCurrentUploadContext(context)) _transferUploads.FailLoad(token, ErrorMessage(error)); }
        }
        else
        {
            if (_downloads is not { } service || ActiveDownloadContext is not { } context
                || !_transferDownloads.TryBeginLoad(out var token, out var cursor)) return;
            RenderTransferCenter(synchronizeRows: false);
            try
            {
                var page = await service.ListAsync(context, cursor, 30, _lifetime.Token);
                if (!IsCurrentDownloadContext(context) || !ReferenceEquals(service, _downloads)) return;
                var items = page.Items.Where(item => item.Context == context && item.Snapshot.Task.Account == context.Account).ToArray();
                if (!_transferDownloads.CompleteLoad(token, items, page.NextCursor)) return;
                foreach (var item in items)
                    _downloadItems[item.Snapshot.Task] = _downloadItems.TryGetValue(item.Snapshot.Task, out var current)
                        ? DownloadPresentationPolicy.Prefer(current, item) : item;
            }
            catch (OperationCanceledException) { _transferDownloads.FailLoad(token, "记录读取已停止，可以重试。"); }
            catch (Exception error) { if (IsCurrentDownloadContext(context)) _transferDownloads.FailLoad(token, ErrorMessage(error)); }
        }
        if (!_disposed) { RenderTransferCenter(); UpdateDownloadContext(); }
    }

    private void ApplyTransferUploadRow(UploadItem item)
    {
        if (!_transferUploadRowIndex.TryGetValue(item.Snapshot.Task, out var row))
        { row = new(item.Snapshot.Task, TransferDirection.Upload); _transferUploadRowIndex.Add(row.Task, row); _transferUploadRows.Add(row); }
        row.Update(item);
    }
    private void ApplyTransferDownloadRow(DownloadItem item)
    {
        if (!_transferDownloadRowIndex.TryGetValue(item.Snapshot.Task, out var row))
        { row = new(item.Snapshot.Task, TransferDirection.Download); _transferDownloadRowIndex.Add(row.Task, row); _transferDownloadRows.Add(row); }
        row.Update(item);
    }

    private void RenderTransferCenter(bool synchronizeRows = true)
    {
        if (TransfersPanel is null || TransfersPanel.Visibility != Visibility.Visible) return;
        // Hidden/opposite-tab events only update the safe cache. Progress changes update one visible
        // row; whole-tab mapping is reserved for entering a tab and completing an explicit page read.
        if (TransfersPanel.Direction == TransferDirection.Upload)
        {
            if (synchronizeRows) foreach (var item in _transferUploads.Items) ApplyTransferUploadRow(item);
            TransfersPanel.Present(_transferUploadRows, _transferUploads.IsLoading, _transferUploads.HasLoaded, _transferUploads.CanLoadMore, _transferUploads.Error);
        }
        else
        {
            if (synchronizeRows) foreach (var item in _transferDownloads.Items) ApplyTransferDownloadRow(item);
            TransfersPanel.Present(_transferDownloadRows, _transferDownloads.IsLoading, _transferDownloads.HasLoaded, _transferDownloads.CanLoadMore, _transferDownloads.Error);
        }
    }
}
