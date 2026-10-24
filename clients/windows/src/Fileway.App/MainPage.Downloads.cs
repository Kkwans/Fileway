using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Markup;
using DownloadPolicy = Fileway.App.DownloadPresentationPolicy;

namespace Fileway.App;

public sealed partial class MainPage
{
    private IDownloadService? _downloads;
    private DownloadContext? _downloadContext;
    private readonly Dictionary<TransferTaskRef, DownloadItem> _downloadItems = [];
    private DownloadItem? _latestDownload;
    private ContentDialog? _downloadDialog;
    private DownloadDetailsView? _downloadDetails;
    private TransferTaskRef? _downloadDetailTask;
    private bool _downloadOperationBusy;
    private bool _downloadPreparing;
    private bool _downloadPlaybackOpening;
    private long _downloadViewGeneration;
    private string? _downloadErrorTitle;
    private string? _downloadErrorMessage;
    private DownloadContext? _downloadAutoResumeApplied;

    public void SetDownloadService(IDownloadService downloads)
    {
        if (_disposed) return;
        if (_downloads is not null) _downloads.TaskChanged -= Download_TaskChanged;
        ClearDownloadPresentation(); _downloads = downloads;
        _downloads.TaskChanged += Download_TaskChanged;
        UpdateDownloadContext();
    }

    private DownloadContext? ActiveDownloadContext => !_disposed && !_connecting && _session is { } session
        && _downloads?.CurrentContext is { } context && context.Account == session.Account && context.SessionId == session.Id ? context : null;
    private bool IsCurrentDownloadContext(DownloadContext context) => ActiveDownloadContext == context;
    private bool CanDownloadSelection
    {
        get
        {
            if (_disposed || _downloads is null) return false;
            var selected = SelectedRows();
            return _session?.Permissions.Download == true && ActiveDownloadContext is not null && BrowserReady &&
                !LoadingRing.IsActive && !_downloadOperationBusy && !_downloadPreparing && selected.Length > 0;
        }
    }
    private bool CanDownloadSaveAsSelection => CanDownloadSelection && SelectedRows() is [var row] && !row.File.IsDirectory;

    private void UpdateDownloadContext()
    {
        if (DownloadFileButton is null) return;
        var current = ActiveDownloadContext;
        if (_downloadContext != current) { ClearDownloadPresentation(); _downloadContext = current; }
        if (current is { } active && _downloadAutoResumeApplied != active)
        {
            // One shot per connection: the stored preference decides whether interrupted
            // durable-resumable downloads re-enter the queue automatically.
            _downloadAutoResumeApplied = active;
            _ = AutoResumeDownloadsAsync(active);
        }
        DownloadFileButton.IsEnabled = CanDownloadSelection;
        DownloadFileButton.Visibility = _session?.Permissions.Download == false ? Visibility.Collapsed : Visibility.Visible;
        DownloadHistoryButton.IsEnabled = current is not null && !_downloadOperationBusy;
        RenderDownloadNotice(); RenderDownloadDetails();
        UpdateTransferCenterContext();
    }

    private async Task AutoResumeDownloadsAsync(DownloadContext context)
    {
        try
        {
            if (_storageSettings is null || _downloads is not { } service) return;
            var state = await _storageSettings.ReadAsync(_lifetime.Token);
            if (!IsCurrentDownloadContext(context) || !state.Active.AutoResumeDownloads) return;
            var resumed = await service.ResumeEligibleAsync(context, _lifetime.Token);
            if (resumed.Count > 0 && IsCurrentDownloadContext(context))
                SetNotice("已继续未完成的下载", $"{resumed.Count} 个中断任务已重新排队，将从已确认的保存位置继续并核对远端版本。", InfoBarSeverity.Informational);
        }
        catch (Exception) { /* 自动继续是便利功能；单项失败由任务自身状态呈现。 */ }
    }

    private async Task DownloadSelectionAsArchiveAsync()
    {
        if (!CanDownloadSelection || _downloads is not IArchiveDownloadService archives || _session is not { } session ||
            _directory is not { } parent || ActiveDownloadContext is not { } context) return;
        var selection = SelectedRows().Select(row => row.File.Resource).ToArray();
        if (selection.Length == 0 || selection.Length > 1000 || selection.Any(resource => resource.Account != context.Account)) return;
        string suggested = (selection.Length == 1 ? selection[0].DisplayName : "_" + parent.DisplayName) + ".zip";
        var generation = _generation;
        bool Current() => IsCurrentDownloadContext(context) && SameBrowser(session, parent, generation) && _session?.Permissions.Download == true;
        var name = DownloadPresentationPolicy.IsSafeLocalName(suggested) ? suggested : "fileway-export.zip";
        var panel = new StackPanel { Spacing = 12, MinWidth = 320, MaxWidth = 440 };
        panel.Children.Add(new TextBlock { Text = $"将 {selection.Length} 个项目打包为：{name}", TextWrapping = TextWrapping.Wrap, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
        panel.Children.Add(new TextBlock { Text = "由服务器实时生成 ZIP 并流式保存到已生效的默认下载目录。\n打包内容动态生成：不支持断点续传，也不会显示总大小；同名本地文件会直接失败。", TextWrapping = TextWrapping.Wrap, FontSize = 12 });
        var confirm = new ContentDialog { Title = "下载为 ZIP", Content = panel, PrimaryButtonText = "开始下载", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Close };
        _downloadDialog = confirm;
        ContentDialogResult choice;
        try { choice = await ShowBrowserDialogAsync(confirm); }
        finally { if (ReferenceEquals(_downloadDialog, confirm)) _downloadDialog = null; }
        if (choice != ContentDialogResult.Primary || !Current()) return;
        var progressText = new TextBlock { Text = "正在打包下载…", TextWrapping = TextWrapping.Wrap };
        var progressPanel = new StackPanel { Spacing = 12, MinWidth = 320 } ;
        progressPanel.Children.Add(new ProgressBar { IsIndeterminate = true });
        progressPanel.Children.Add(progressText);
        var progress = new ContentDialog { Title = "下载为 ZIP", Content = progressPanel, CloseButtonText = "取消" };
        _downloadDialog = progress;
        var reporter = new Progress<long>(bytes =>
        {
            if (!_disposed) progressText.Text = "已接收 " + DownloadPresentationPolicy.FormatBytes(bytes);
        });
        _downloadOperationBusy = true; UpdateDownloadContext();
        try
        {
            var running = archives.DownloadArchiveAsync(new(context, parent, selection.Select(resource => resource.WirePath).ToArray(), name), reporter, _lifetime.Token);
            var hidden = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            progress.CloseButtonClick += (_, _) => hidden.TrySetResult();
            var finished = await Task.WhenAny(running, hidden.Task).ConfigureAwait(true);
            if (ReferenceEquals(finished, hidden.Task))
            {
                progress.Hide();
                if (ReferenceEquals(_downloadDialog, progress)) _downloadDialog = null;
                SetNotice("ZIP 下载已取消", "正在停止服务器打包流；未完成的临时文件会被清理。", InfoBarSeverity.Informational);
                try { await running.ConfigureAwait(true); }
                catch (Exception) { /* 取消后的清理结果由服务端与本地删除路径负责。 */ }
                return;
            }
            await running.ConfigureAwait(true);
            if (ReferenceEquals(_downloadDialog, progress)) _downloadDialog = null;
            progress.Hide();
            if (Current()) SetNotice("ZIP 已保存", name + " 已保存到已生效的下载目录。", InfoBarSeverity.Success);
        }
        catch (Exception error)
        {
            if (ReferenceEquals(_downloadDialog, progress)) _downloadDialog = null;
            try { progress.Hide(); } catch (Exception) { }
            if (Current()) ShowDownloadError("ZIP 下载未完成", error);
        }
        finally { _downloadOperationBusy = false; if (!_disposed) UpdateDownloadContext(); }
    }

    private void ClearDownloadPresentation()
    {
        ++_downloadViewGeneration;
        _downloadDialog?.Hide(); _downloadDialog = null; _downloadDetails = null; _downloadDetailTask = null;
        _downloadContext = null; _latestDownload = null; _downloadItems.Clear(); _downloadPreparing = false;
        _downloadErrorTitle = _downloadErrorMessage = null; _downloadAutoResumeApplied = null;
        if (DownloadNotice is not null) DownloadNotice.IsOpen = false;
        ClearTransferDownloadPresentation();
    }

    private void DisposeDownloadPresentation()
    {
        if (_downloads is not null) _downloads.TaskChanged -= Download_TaskChanged;
        ClearDownloadPresentation();
    }

    private async void DownloadFile_Click(object sender, RoutedEventArgs args)
    {
        SetDetailsVisible(false);
        if (!CanDownloadSelection || _downloads is not { } service || _session is not { } session
            || _directory is not { } directory || ActiveDownloadContext is not { } context) return;
        if (SelectedRows().Length != 1 || SelectedRows()[0].File.IsDirectory)
        { await DownloadBatchSelectionAsync(); return; }
        var source = SelectedRows()[0].File.Resource;
        var generation = _generation; var viewGeneration = _downloadViewGeneration;
        var submitted = false;
        bool CurrentSource()
        {
            if (viewGeneration != _downloadViewGeneration || !IsCurrentDownloadContext(context)
                || !SameBrowser(session, directory, generation) || _session?.Permissions.Download != true) return false;
            var selected = SelectedRows();
            return selected.Length == 1 && !selected[0].File.IsDirectory && selected[0].File.Resource == source;
        }
        _downloadErrorTitle = _downloadErrorMessage = null;
        _downloadOperationBusy = true; _downloadPreparing = true; UpdateDownloadContext();
        try
        {
            var preview = await service.PrepareAsync(context, source, _lifetime.Token);
            _downloadPreparing = false; UpdateDownloadContext();
            if (!CurrentSource()) return;
            if (preview.Context != context || preview.Source.Account != source.Account
                || !string.Equals(preview.Source.WirePath, source.WirePath, StringComparison.Ordinal))
                throw new FilewayException(new ErrorInfo(ErrorCode.ProtocolMismatch, "下载确认的源文件与原选择不一致。"));
            var content = new StackPanel { Spacing = 12, MinWidth = 320, MaxWidth = 440 };
            content.Children.Add(new TextBlock { Text = preview.Name, FontSize = 18, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap });
            content.Children.Add(new TextBlock { Text = $"大小：{DownloadPolicy.FormatBytes(preview.TotalBytes)}", TextWrapping = TextWrapping.Wrap });
            content.Children.Add(new TextBlock { Text = $"保存到：{preview.DestinationDisplayPath}", TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true });
            content.Children.Add(new TextBlock { Text = "使用已生效的默认下载目录。仅下载这个文件；同名文件会保留并报告冲突。", FontSize = 12, TextWrapping = TextWrapping.Wrap });
            var confirm = new ContentDialog { Title = "确认下载位置", Content = new ScrollViewer { Content = content, MaxHeight = 400, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled }, PrimaryButtonText = "开始下载", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Close };
            _downloadDialog = confirm;
            ContentDialogResult result;
            try { result = await ShowBrowserDialogAsync(confirm); }
            finally { if (ReferenceEquals(_downloadDialog, confirm)) _downloadDialog = null; }
            if (result != ContentDialogResult.Primary || !CurrentSource()) return;
            submitted = true;
            var item = await service.SubmitAsync(preview, _lifetime.Token);
            if (!IsCurrentDownloadContext(context)) return;
            if (item.Snapshot.Task.Id != preview.Draft.Id)
                throw new FilewayException(new ErrorInfo(ErrorCode.ProtocolMismatch, "下载任务与确认记录不一致。"));
            AcceptDownloadItem(item);
            _downloadOperationBusy = false; UpdateDownloadContext();
            await ShowDownloadDetailsAsync(item.Snapshot.Task);
        }
        catch (OperationCanceledException) { }
        catch (Exception error) { if (submitted ? IsCurrentDownloadContext(context) : CurrentSource()) ShowDownloadError("下载未能提交", error); }
        finally { _downloadPreparing = false; _downloadOperationBusy = false; if (!_disposed) UpdateDownloadContext(); }
    }

    private async void DownloadSaveAs_Click(object sender, RoutedEventArgs args)
    {
        if (!CanDownloadSaveAsSelection || _browserDialog is not null || _storageDialog is not null
            || _downloads is not { } service || _session is not { } session
            || _directory is not { } directory || ActiveDownloadContext is not { } context) return;
        var source = SelectedRows()[0].File.Resource;
        var generation = _generation; var viewGeneration = _downloadViewGeneration;
        var state = new DownloadSaveAsState(context, source, source.DisplayName);
        long nameRevision = 0;
        DownloadPreview? confirmed = null;
        var submitted = false;
        bool CurrentSource()
        {
            if (viewGeneration != _downloadViewGeneration || !IsCurrentDownloadContext(context)
                || !SameBrowser(session, directory, generation) || _session?.Permissions.Download != true) return false;
            var selected = SelectedRows();
            return selected.Length == 1 && !selected[0].File.IsDirectory && selected[0].File.Resource == source;
        }
        _downloadErrorTitle = _downloadErrorMessage = null;
        _downloadOperationBusy = true; UpdateDownloadContext();
        try
        {
            var content = new StackPanel { Spacing = 12, MinWidth = 320, MaxWidth = 440 };
            var remoteName = new TextBlock { Text = $"远端文件：{source.DisplayName}", TextWrapping = TextWrapping.Wrap };
            var name = new TextBox { Header = "本地文件名", Text = source.DisplayName, MaxLength = 255 };
            var hint = new TextBlock { Text = "只修改本地文件名，保存目录仍使用已生效的默认下载目录。不会修改远端名称或覆盖已有文件。", FontSize = 12, TextWrapping = TextWrapping.Wrap };
            var validation = new TextBlock { Text = "请输入有效的 Windows 文件名。不能包含路径、保留名称，或以空格、句点结尾。", FontSize = 12, TextWrapping = TextWrapping.Wrap };
            var progress = new ProgressRing { Width = 20, Height = 20, HorizontalAlignment = HorizontalAlignment.Left, IsActive = false };
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(name, "下载另存为的本地文件名");
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(progress, "正在核对另存为位置");
            var previewPanel = new StackPanel { Spacing = 8, Visibility = Visibility.Collapsed };
            var localName = new TextBlock { FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap };
            var size = new TextBlock { TextWrapping = TextWrapping.Wrap };
            var destination = new TextBlock { TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true };
            previewPanel.Children.Add(localName); previewPanel.Children.Add(size); previewPanel.Children.Add(destination);
            var error = new InfoBar { IsOpen = false, IsClosable = false, Severity = InfoBarSeverity.Warning, Title = "未能核对保存位置" };
            content.Children.Add(remoteName); content.Children.Add(name); content.Children.Add(hint);
            content.Children.Add(validation); content.Children.Add(progress); content.Children.Add(previewPanel); content.Children.Add(error);
            var confirm = new ContentDialog
            {
                Title = "下载另存为", Content = new ScrollViewer { Content = content, MaxHeight = 400,
                    VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled },
                PrimaryButtonText = "核对保存位置", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Close,
            };
            void Render()
            {
                if (state.IsClosed || !CurrentSource()) return;
                var valid = DownloadPolicy.IsSafeLocalName(state.Name);
                validation.Visibility = valid ? Visibility.Collapsed : Visibility.Visible;
                progress.IsActive = state.IsPreparing;
                progress.Visibility = state.IsPreparing ? Visibility.Visible : Visibility.Collapsed;
                previewPanel.Visibility = state.Preview is null ? Visibility.Collapsed : Visibility.Visible;
                if (state.Preview is { } preview)
                {
                    remoteName.Text = $"远端文件：{preview.Source.DisplayName}";
                    localName.Text = $"本地文件名：{preview.Name}";
                    size.Text = $"大小：{DownloadPolicy.FormatBytes(preview.TotalBytes)}";
                    destination.Text = $"保存到：{preview.DestinationDisplayPath}";
                }
                confirm.PrimaryButtonText = state.Preview is null ? "核对保存位置" : "开始下载";
                confirm.IsPrimaryButtonEnabled = valid && !state.IsPreparing && !state.IsSubmitted;
            }
            name.TextChanged += (_, _) => { ++nameRevision; state.EditName(name.Text); error.IsOpen = false; Render(); };
            confirm.Opened += (_, _) => { name.Focus(FocusState.Keyboard); name.SelectAll(); };
            confirm.Closed += (_, _) => state.Close();
            confirm.PrimaryButtonClick += async (_, click) =>
            {
                click.Cancel = true;
                if (!CurrentSource() || state.IsClosed || state.IsPreparing || state.IsSubmitted) return;
                if (state.Preview is not null)
                {
                    if (state.TryConfirm(out var preview)) { confirmed = preview; click.Cancel = false; }
                    return;
                }
                if (!state.TryBeginPrepare(out var preparation)) return;
                var preparedNameRevision = nameRevision;
                error.IsOpen = false; _downloadPreparing = true; Render(); UpdateDownloadContext();
                try
                {
                    // Preparing is read-only. Closing or editing fences its late result before any UI/submit.
                    var preview = await service.PrepareAsAsync(context, source, preparation.Name, _lifetime.Token);
                    if (!CurrentSource() || state.IsClosed || preparedNameRevision != nameRevision) return;
                    if (!state.AcceptPreview(preparation, preview))
                        throw new FilewayException(new(ErrorCode.ProtocolMismatch, "另存为确认与原文件或本地名称不一致。"));
                }
                catch (OperationCanceledException) { }
                catch (Exception failure)
                {
                    if (!state.IsClosed && CurrentSource() && preparedNameRevision == nameRevision)
                    { error.Message = ErrorMessage(failure); error.IsOpen = true; }
                }
                finally
                {
                    state.FinishPrepare(preparation);
                    if (!state.IsClosed && CurrentSource() && ReferenceEquals(_downloadDialog, confirm))
                    { _downloadPreparing = state.IsPreparing; Render(); UpdateDownloadContext(); }
                }
            };
            _downloadDialog = confirm; Render();
            ContentDialogResult result;
            try { result = await ShowBrowserDialogAsync(confirm); }
            finally { state.Close(); if (ReferenceEquals(_downloadDialog, confirm)) _downloadDialog = null; }
            if (result != ContentDialogResult.Primary || confirmed is null || !CurrentSource()) return;
            submitted = true;
            var item = await service.SubmitAsync(confirmed, _lifetime.Token);
            if (!IsCurrentDownloadContext(context)) return;
            if (item.Snapshot.Task.Id != confirmed.Draft.Id)
                throw new FilewayException(new(ErrorCode.ProtocolMismatch, "下载任务与另存为确认记录不一致。"));
            AcceptDownloadItem(item);
            _downloadOperationBusy = false; UpdateDownloadContext();
            await ShowDownloadDetailsAsync(item.Snapshot.Task);
        }
        catch (OperationCanceledException) { }
        catch (Exception error) { if (submitted ? IsCurrentDownloadContext(context) : CurrentSource()) ShowDownloadError("下载另存为未能提交", error); }
        finally { state.Close(); _downloadPreparing = false; _downloadOperationBusy = false; if (!_disposed) UpdateDownloadContext(); }
    }

    private void Download_TaskChanged(object? sender, DownloadItem item) => DispatcherQueue.TryEnqueue(() =>
    {
        if (!ReferenceEquals(sender, _downloads) || !IsCurrentDownloadContext(item.Context)) return;
        UpdateDownloadContext(); AcceptDownloadItem(item);
    });

    private void AcceptDownloadItem(DownloadItem item)
    {
        if (!IsCurrentDownloadContext(item.Context) || item.Snapshot.Task.Account != item.Context.Account || _removedDownloadTasks.Contains(item.Snapshot.Task)) return;
        if (_downloadItems.TryGetValue(item.Snapshot.Task, out var previous)
            && ReferenceEquals(DownloadPolicy.Prefer(previous, item), previous))
        {
            UpdateDownloadContext();
            return;
        }
        _downloadItems[item.Snapshot.Task] = item; _latestDownload = item;
        _downloadErrorTitle = _downloadErrorMessage = null;
        UpdateDownloadContext();
        ObserveTransferDownload(item);
    }

    private void RenderDownloadNotice()
    {
        if (DownloadNotice is null) return;
        if (_downloadErrorMessage is { } error && ActiveDownloadContext is not null)
        {
            DownloadNotice.Title = _downloadErrorTitle; DownloadNotice.Message = error;
            ShowToast(DownloadNotice, _downloadErrorTitle + error);
            DownloadNotice.Severity = InfoBarSeverity.Warning; DownloadDetailsButton.IsEnabled = _latestDownload is not null && !_downloadOperationBusy;
            return;
        }
        if (_downloadPreparing && ActiveDownloadContext is not null)
        {
            DownloadNotice.Title = "正在核对下载目标";
            ShowToast(DownloadNotice, "preparing");
            DownloadNotice.Message = "正在读取文件信息与已生效的默认下载目录…";
            DownloadNotice.Severity = InfoBarSeverity.Informational; DownloadDetailsButton.IsEnabled = false;
            return;
        }
        if (_latestDownload is not { } item || !IsCurrentDownloadContext(item.Context)) { DownloadNotice.IsOpen = false; return; }
        DownloadNotice.Title = DownloadPolicy.Stage(item);
        ShowToast(DownloadNotice, item.Snapshot.Task.Id.ToString() + DownloadNotice.Title);
        DownloadNotice.Message = $"{item.Name} · {DownloadPolicy.Progress(item).ActivityText}";
        DownloadNotice.Severity = DownloadPolicy.Tone(item) switch
        {
            DownloadPresentationTone.Success => InfoBarSeverity.Success,
            DownloadPresentationTone.Warning => InfoBarSeverity.Warning,
            _ => InfoBarSeverity.Informational,
        };
        DownloadDetailsButton.IsEnabled = !_downloadOperationBusy;
    }

    private async void DownloadDetails_Click(object sender, RoutedEventArgs args)
    {
        if (_latestDownload is { } item) await ShowDownloadDetailsAsync(item.Snapshot.Task);
    }

    private async Task ShowDownloadDetailsAsync(TransferTaskRef task)
    {
        if (_browserDialog is not null || _downloadDialog is not null || !_downloadItems.TryGetValue(task, out var item)
            || !IsCurrentDownloadContext(item.Context)) return;
        DownloadDetailsView view;
        try { view = new DownloadDetailsView(); }
        catch (Exception error) { ShowDownloadError("下载详情未能打开", error); return; }
        var dialog = new ContentDialog { Title = "文件下载", Content = new ScrollViewer { Content = view.Content, MaxHeight = 400, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled }, CloseButtonText = "关闭", DefaultButton = ContentDialogButton.Close };
        _downloadDialog = dialog; _downloadDetails = view; _downloadDetailTask = task;
        view.Cancel.Click += (_, _) => { view.CancelConfirmation.Visibility = Visibility.Visible; view.Cancel.IsEnabled = false; };
        view.Keep.Click += (_, _) => { view.CancelConfirmation.Visibility = Visibility.Collapsed; RenderDownloadDetails(); };
        view.ConfirmCancel.Click += async (_, _) => await RunDownloadControlAsync(task, cancel: true);
        view.Play.Click += async (_, _) => await PlayDownloadAsync(task);
        view.OpenLocal.Click += async (_, _) => await OpenDownloadLocallyAsync(task);
        view.Retry.Click += async (_, _) => await RetryDownloadAsync(task);
        dialog.PrimaryButtonClick += async (_, click) =>
        {
            click.Cancel = true; var deferral = click.GetDeferral();
            try { await RunDownloadControlAsync(task, cancel: false); }
            finally { deferral.Complete(); }
        };
        RenderDownloadDetails();
        try { await ShowBrowserDialogAsync(dialog); }
        finally { if (ReferenceEquals(_downloadDialog, dialog)) { _downloadDialog = null; _downloadDetails = null; _downloadDetailTask = null; } }
    }

    private void RenderDownloadDetails()
    {
        if (_downloadDialog is not { } dialog || _downloadDetails is not { } view || _downloadDetailTask is not { } task
            || !_downloadItems.TryGetValue(task, out var item) || !IsCurrentDownloadContext(item.Context)) return;
        view.Name.Text = item.Name; view.Destination.Text = $"保存到：{item.DestinationDisplayPath}";
        view.Stage.Text = DownloadPolicy.Stage(item);
        var progress = DownloadPolicy.Progress(item);
        view.Progress.IsIndeterminate = progress.IsIndeterminate; view.Progress.Value = progress.Value;
        view.Activity.Text = progress.ActivityText; view.Flushed.Text = progress.FlushedText;
        view.Detail.Text = _downloadErrorMessage ?? DownloadPolicy.Detail(item);
        view.Play.Visibility = DownloadPolicy.CanPlay(item) && _downloads is IDownloadPlaybackSourceProvider && _playback is not null
            ? Visibility.Visible : Visibility.Collapsed;
        view.OpenLocal.Visibility = item.Status == DownloadStatus.Completed && item.Snapshot.LocalPublished &&
            _downloads is IDownloadControlService ? Visibility.Visible : Visibility.Collapsed;
        view.OpenLocal.IsEnabled = !_downloadOperationBusy;
        view.Play.IsEnabled = !_downloadPlaybackOpening && !_downloadOperationBusy;
        view.Retry.Visibility = DownloadPolicy.CanRetryFresh(item) ? Visibility.Visible : Visibility.Collapsed;
        view.Retry.IsEnabled = !_downloadOperationBusy;
        dialog.PrimaryButtonText = DownloadPolicy.PrimaryLabel(item);
        dialog.IsPrimaryButtonEnabled = !_downloadOperationBusy && DownloadPolicy.CanReconcile(item);
        var cancel = DownloadPolicy.CanCancel(item);
        view.Cancel.Visibility = cancel ? Visibility.Visible : Visibility.Collapsed;
        view.Cancel.IsEnabled = cancel && !_downloadOperationBusy && view.CancelConfirmation.Visibility != Visibility.Visible;
        view.ConfirmCancel.IsEnabled = cancel && !_downloadOperationBusy;
        if (!cancel) view.CancelConfirmation.Visibility = Visibility.Collapsed;
    }

    private async Task RunDownloadControlAsync(TransferTaskRef task, bool cancel)
    {
        if (_downloadOperationBusy || _downloads is not { } service || !_downloadItems.TryGetValue(task, out var item)
            || !IsCurrentDownloadContext(item.Context) || (cancel ? !DownloadPolicy.CanCancel(item) : !DownloadPolicy.CanReconcile(item))) return;
        var context = item.Context;
        _downloadErrorTitle = _downloadErrorMessage = null;
        _downloadOperationBusy = true; UpdateDownloadContext();
        try
        {
            var changed = cancel ? await service.CancelAsync(context, task, _lifetime.Token)
                : await service.ReconcileLocalAsync(context, task, _lifetime.Token);
            AcceptDownloadItem(changed);
            if (_downloadDetails is { } view) view.CancelConfirmation.Visibility = Visibility.Collapsed;
        }
        catch (OperationCanceledException) { }
        catch (Exception error) { if (IsCurrentDownloadContext(context)) ShowDownloadError("下载操作未能完成", error); }
        finally { _downloadOperationBusy = false; if (!_disposed) UpdateDownloadContext(); }
    }

    private async Task RetryDownloadAsync(TransferTaskRef task)
    {
        if (_downloadOperationBusy || _downloads is not { } service || !_downloadItems.TryGetValue(task, out var item)
            || !IsCurrentDownloadContext(item.Context) || !DownloadPolicy.CanRetryFresh(item)) return;
        var context = item.Context;
        _downloadErrorTitle = _downloadErrorMessage = null;
        _downloadOperationBusy = true; RenderDownloadDetails();
        try
        {
            // Retry is deliberately a new task and a new preview. It never opens or
            // appends to the old partial, so the UI cannot imply unsupported resume.
            var preview = await service.PrepareAsAsync(context, item.Source, item.Name, _lifetime.Token);
            var changed = await service.SubmitAsync(preview, _lifetime.Token);
            if (!IsCurrentDownloadContext(context)) return;
            AcceptDownloadItem(changed);
            _downloadDetailTask = changed.Snapshot.Task;
        }
        catch (OperationCanceledException) { }
        catch (Exception error) { if (IsCurrentDownloadContext(context)) ShowDownloadError("重新下载未能提交", error); }
        finally { _downloadOperationBusy = false; if (!_disposed) UpdateDownloadContext(); }
    }

    private async Task PlayDownloadAsync(TransferTaskRef task)
    {
        if (_downloadPlaybackOpening || _downloadOperationBusy || _playback is not { } playback ||
            _downloads is not IDownloadPlaybackSourceProvider || !_downloadItems.TryGetValue(task, out var item) ||
            !IsCurrentDownloadContext(item.Context) || !DownloadPolicy.CanPlay(item)) return;
        var context = item.Context;
        _downloadPlaybackOpening = true;
        RenderDownloadDetails();
        _downloadDialog?.Hide();
        try
        {
            var session = await playback.OpenAsync(new PlaybackOpenRequest(new DownloadPlaybackInput(context, task)), _lifetime.Token);
            if (!IsCurrentDownloadContext(context)) await session.DisposeAsync();
        }
        catch (OperationCanceledException) { }
        catch (Exception error) { if (IsCurrentDownloadContext(context)) ShowDownloadError("下载视频暂时无法播放", error); }
        finally { _downloadPlaybackOpening = false; if (!_disposed) RenderDownloadDetails(); }
    }

    private async Task OpenDownloadLocallyAsync(TransferTaskRef task)
    {
        if (_downloadOperationBusy || _downloads is not IDownloadControlService service ||
            !_downloadItems.TryGetValue(task, out var item) || !IsCurrentDownloadContext(item.Context) ||
            item.Status != DownloadStatus.Completed || !item.Snapshot.LocalPublished) return;
        _downloadOperationBusy = true;
        try
        {
            // The service verifies identity/length/ancestors before this window opens anything.
            if (await service.VerifyLocalOpenAsync(item.Context, task, _lifetime.Token) is not true) return;
            var file = await Windows.Storage.StorageFile.GetFileFromPathAsync(item.DestinationDisplayPath);
            _ = await Windows.System.Launcher.LaunchFileAsync(file);
        }
        catch (Exception error) { if (IsCurrentDownloadContext(item.Context)) ShowDownloadError("本地打开未完成", error); }
        finally { _downloadOperationBusy = false; if (!_disposed) UpdateDownloadContext(); }
    }

    private async void DownloadHistory_Click(object sender, RoutedEventArgs args) => await ShowTransferCenterAsync(TransferDirection.Download);

    private void ShowDownloadError(string title, Exception error)
    {
        _downloadErrorTitle = title; _downloadErrorMessage = ErrorMessage(error);
        RenderDownloadNotice(); RenderDownloadDetails();
    }

    private sealed class DownloadDetailsView
    {
        public StackPanel Content { get; } = (StackPanel)XamlReader.Load("""
            <StackPanel xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation" xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml" Spacing="16" MinWidth="320" MaxWidth="440">
              <StackPanel Spacing="4"><TextBlock x:Name="Name" FontSize="18" FontWeight="SemiBold" TextWrapping="Wrap"/><TextBlock x:Name="Destination" FontSize="12" TextWrapping="Wrap" IsTextSelectionEnabled="True" Foreground="{ThemeResource TextFillColorSecondaryBrush}"/></StackPanel>
              <Border Padding="16" CornerRadius="8" Background="{ThemeResource LayerFillColorDefaultBrush}" BorderBrush="{ThemeResource CardStrokeColorDefaultBrush}" BorderThickness="1"><StackPanel Spacing="8"><TextBlock x:Name="Stage" FontWeight="SemiBold" TextWrapping="Wrap"/><ProgressBar x:Name="Progress" Minimum="0" Maximum="100" Height="4" AutomationProperties.Name="下载接收活动进度，完成还需校验与保存"/><TextBlock x:Name="Activity" FontSize="12" TextWrapping="Wrap"/><TextBlock x:Name="Flushed" FontSize="12" TextWrapping="Wrap" Foreground="{ThemeResource TextFillColorSecondaryBrush}"/></StackPanel></Border>
              <TextBlock x:Name="Detail" FontSize="12" TextWrapping="Wrap" Foreground="{ThemeResource TextFillColorSecondaryBrush}"/>
               <StackPanel Orientation="Horizontal" Spacing="8"><Button x:Name="Play" Content="边下边播" HorizontalAlignment="Left" Visibility="Collapsed" AutomationProperties.Name="播放正在下载的视频" ToolTipService.ToolTip="读取已下载内容；未下载片段需要等待，部分视频需取得更多数据才能开始。"/><Button x:Name="OpenLocal" Content="打开文件" HorizontalAlignment="Left" Visibility="Collapsed" AutomationProperties.Name="用系统默认应用打开已下载的文件" ToolTipService.ToolTip="先核实本地文件身份，再交给系统默认应用打开；不会改动 NAS 原文件。"/><Button x:Name="Retry" Content="重新下载（新任务）" HorizontalAlignment="Left" Visibility="Collapsed" AutomationProperties.Name="重新下载并创建新任务"/></StackPanel>
              <Button x:Name="Cancel" Content="取消下载…" HorizontalAlignment="Left"/>
              <Border x:Name="CancelConfirmation" Visibility="Collapsed" Padding="12" CornerRadius="8" BorderThickness="1" BorderBrush="{ThemeResource ControlStrokeColorDefaultBrush}"><StackPanel Spacing="10"><TextBlock Text="确认取消当前下载？只会清理本任务拥有的未发布临时文件，已经保存的文件会保留。" TextWrapping="Wrap"/><StackPanel Orientation="Horizontal" Spacing="8"><Button x:Name="Keep" Content="返回"/><Button x:Name="ConfirmCancel" Content="确认取消"/></StackPanel></StackPanel></Border>
            </StackPanel>
            """);
        public TextBlock Name => (TextBlock)Content.FindName("Name");
        public TextBlock Destination => (TextBlock)Content.FindName("Destination");
        public TextBlock Stage => (TextBlock)Content.FindName("Stage");
        public ProgressBar Progress => (ProgressBar)Content.FindName("Progress");
        public TextBlock Activity => (TextBlock)Content.FindName("Activity");
        public TextBlock Flushed => (TextBlock)Content.FindName("Flushed");
        public TextBlock Detail => (TextBlock)Content.FindName("Detail");
        public Button Cancel => (Button)Content.FindName("Cancel");
        public Button Play => (Button)Content.FindName("Play");
        public Button OpenLocal => (Button)Content.FindName("OpenLocal");
        public Button Retry => (Button)Content.FindName("Retry");
        public Border CancelConfirmation => (Border)Content.FindName("CancelConfirmation");
        public Button Keep => (Button)Content.FindName("Keep");
        public Button ConfirmCancel => (Button)Content.FindName("ConfirmCancel");
    }
}
