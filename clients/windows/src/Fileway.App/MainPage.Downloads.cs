using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Markup;
using Microsoft.UI.Xaml.Media;
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
    private bool CanDownloadSelection => DownloadDisabledReason() is null;
    private bool CanDownloadSaveAsSelection => CanDownloadSelection && SelectedRows() is [var row] && !row.File.IsDirectory;

    private string? DownloadDisabledReason()
    {
        if (_session?.Permissions.Download == false) return "当前账号没有下载权限。";
        if (_disposed || _downloads is null || ActiveDownloadContext is null) return "下载尚未就绪，请检查当前连接。";
        if (!BrowserReady) return "请先打开一个目录。";
        if (LoadingRing.IsActive) return "目录仍在加载，请稍候。";
        if (_downloadOperationBusy || _downloadPreparing) return "下载操作进行中，请稍候。";
        return ActiveFiles.SelectedItems.Count == 0 ? "请先选择要下载的项目。" : null;
    }

    private void UpdateDownloadAvailability()
    {
        if (DownloadFileButtonHost is null || DetailsDownloadButton is null) return;
        string? reason = DownloadDisabledReason();
        DownloadFileButton.IsEnabled = DetailsDownloadButton.IsEnabled = reason is null;
        DownloadFileButtonHost.Visibility = _session?.Permissions.Download == false ? Visibility.Collapsed : Visibility.Visible;
        ToolTipService.SetToolTip(DownloadFileButtonHost, reason);
        AutomationProperties.SetHelpText(DownloadFileButton, reason ?? "");
        AutomationProperties.SetHelpText(DetailsDownloadButton, reason ?? "");
    }

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
        UpdateDownloadAvailability();
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
        var confirm = new ContentDialog { Title = "下载为 ZIP", Content = panel, PrimaryButtonText = "开始下载", CloseButtonText = "取消", DefaultButton = ContentDialogButton.None };
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
            var caption = (Style)Application.Current.Resources["FwCaptionTextStyle"];
            var content = new StackPanel { Spacing = 12, MinWidth = 320, MaxWidth = 440 };
            content.Children.Add(new TextBlock { Text = preview.Name, FontSize = 18, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap });
            var details = new StackPanel { Spacing = 10 };
            var sizeField = new StackPanel { Spacing = 2 };
            sizeField.Children.Add(new TextBlock { Text = "大小", Style = caption });
            sizeField.Children.Add(new TextBlock { Text = DownloadPolicy.FormatBytes(preview.TotalBytes), FontSize = 13 });
            details.Children.Add(sizeField);
            var destinationField = new StackPanel { Spacing = 2 };
            destinationField.Children.Add(new TextBlock { Text = "保存到", Style = caption });
            destinationField.Children.Add(new TextBlock { Text = preview.DestinationDisplayPath, FontSize = 13, TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true });
            details.Children.Add(destinationField);
            content.Children.Add(new Border { Style = (Style)Application.Current.Resources["FwPreviewCardStyle"], Child = details });
            content.Children.Add(new TextBlock { Text = "使用已生效的默认下载目录。仅下载这个文件；同名文件会保留并报告冲突。", Style = caption });
            var confirm = new ContentDialog { Title = "确认下载位置", Content = new ScrollViewer { Content = content, MaxHeight = 400, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled }, PrimaryButtonText = "开始下载", CloseButtonText = "取消", DefaultButton = ContentDialogButton.None };
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
            var caption = (Style)Application.Current.Resources["FwCaptionTextStyle"];
            var remoteLabel = new TextBlock { Text = "远端文件", FontSize = 12, Style = caption };
            var remoteName = new TextBlock { Text = source.DisplayName, FontSize = 14, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap };
            var remotePanel = new StackPanel { Spacing = 2 };
            remotePanel.Children.Add(remoteLabel); remotePanel.Children.Add(remoteName);
            var name = new TextBox { Header = "本地文件名", Text = source.DisplayName, MaxLength = 255, Style = (Style)Application.Current.Resources["FwTextBoxStyle"] };
            var hint = new TextBlock { Text = "只修改本地文件名，保存目录仍使用已生效的默认下载目录。不会修改远端名称或覆盖已有文件。", FontSize = 12, TextWrapping = TextWrapping.Wrap, Style = caption };
            var validation = new TextBlock { Text = "请输入有效的 Windows 文件名。不能包含路径、保留名称，或以空格、句点结尾。", FontSize = 12, TextWrapping = TextWrapping.Wrap, Style = caption };
            var progress = new ProgressRing { Width = 20, Height = 20, HorizontalAlignment = HorizontalAlignment.Left, IsActive = false };
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(name, "下载另存为的本地文件名");
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(progress, "正在核对另存为位置");
            var previewPanel = new StackPanel { Spacing = 8 };
            var localName = new TextBlock { FontSize = 13, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap };
            var size = new TextBlock { FontSize = 13, TextWrapping = TextWrapping.Wrap };
            var destination = new TextBlock { FontSize = 13, TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true };
            StackPanel Field(string label, TextBlock value)
            {
                var row = new StackPanel { Spacing = 2 };
                row.Children.Add(new TextBlock { Text = label, FontSize = 12, Style = caption });
                row.Children.Add(value);
                return row;
            }
            previewPanel.Children.Add(Field("本地文件名", localName));
            previewPanel.Children.Add(Field("大小", size));
            previewPanel.Children.Add(Field("保存到", destination));
            var previewCard = new Border
            {
                Visibility = Visibility.Collapsed,
                Style = (Style)Application.Current.Resources["FwPreviewCardStyle"],
                Child = previewPanel,
            };
            var error = new InfoBar { IsOpen = false, IsClosable = false, Severity = InfoBarSeverity.Warning, Title = "未能核对保存位置" };
            content.Children.Add(remotePanel); content.Children.Add(name); content.Children.Add(hint);
            content.Children.Add(validation); content.Children.Add(progress); content.Children.Add(previewCard); content.Children.Add(error);
            var confirm = new ContentDialog
            {
                Title = "下载另存为", Content = new ScrollViewer { Content = content, MaxHeight = Fileway.App.Controls.DialogOrdering.ContentMaxHeight(this, 400),
                    VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled },
                PrimaryButtonText = "核对保存位置", CloseButtonText = "取消", DefaultButton = ContentDialogButton.None,
            };
            void Render()
            {
                if (state.IsClosed || !CurrentSource()) return;
                var valid = DownloadPolicy.IsSafeLocalName(state.Name);
                validation.Visibility = valid ? Visibility.Collapsed : Visibility.Visible;
                progress.IsActive = state.IsPreparing;
                progress.Visibility = state.IsPreparing ? Visibility.Visible : Visibility.Collapsed;
                previewCard.Visibility = state.Preview is null ? Visibility.Collapsed : Visibility.Visible;
                if (state.Preview is { } preview)
                {
                    remoteName.Text = preview.Source.DisplayName;
                    localName.Text = preview.Name;
                    size.Text = DownloadPolicy.FormatBytes(preview.TotalBytes);
                    destination.Text = preview.DestinationDisplayPath;
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
        if (_downloadDialog is not null) { DownloadNotice.IsOpen = false; return; }
        if (_downloadErrorMessage is { } error && ActiveDownloadContext is not null)
        {
            DownloadNotice.Title = _downloadErrorTitle; DownloadNotice.Message = error;
            DownloadNotice.Severity = InfoBarSeverity.Warning; DownloadDetailsButton.IsEnabled = _latestDownload is not null && !_downloadOperationBusy;
            ShowToast(DownloadNotice, _downloadErrorTitle + error);
            return;
        }
        if (_downloadPreparing && ActiveDownloadContext is not null)
        {
            DownloadNotice.Title = "正在核对下载目标";
            DownloadNotice.Message = string.Empty;
            DownloadNotice.Severity = InfoBarSeverity.Informational; DownloadDetailsButton.IsEnabled = false;
            ShowToast(DownloadNotice, "preparing");
            return;
        }
        if (_latestDownload is not { } item || !IsCurrentDownloadContext(item.Context)) { DownloadNotice.IsOpen = false; return; }
        DownloadNotice.Title = DownloadPolicy.Stage(item);
        DownloadNotice.Severity = DownloadPolicy.Tone(item) switch
        {
            DownloadPresentationTone.Success => InfoBarSeverity.Success,
            DownloadPresentationTone.Warning => InfoBarSeverity.Warning,
            _ => InfoBarSeverity.Informational,
        };
        DownloadNotice.Message = DownloadNotice.Severity == InfoBarSeverity.Warning
            ? $"{item.Name}\n{DownloadPolicy.Progress(item).ActivityText}" : string.Empty;
        DownloadDetailsButton.IsEnabled = !_downloadOperationBusy;
        ShowToast(DownloadNotice, item.Snapshot.Task.Id.ToString() + DownloadNotice.Title);
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
        var dialog = new ContentDialog { Title = "文件下载", Content = new ScrollViewer { Content = view.Content, MaxHeight = 400, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled }, CloseButtonText = "关闭", DefaultButton = ContentDialogButton.None };
        _downloadDialog = dialog; _downloadDetails = view; _downloadDetailTask = task;
        view.Play.Click += async (_, _) => { if (_downloadDetailTask is { } current) await PlayDownloadAsync(current); };
        dialog.SecondaryButtonClick += (_, click) =>
        {
            click.Cancel = true;
            if (_downloadOperationBusy || !ReferenceEquals(_downloadDetails, view)) return;
            if (view.RetryPreview is not null) view.RetryPreview = null;
            else view.CancelConfirmation.Visibility = view.CancelConfirmation.Visibility == Visibility.Visible
                ? Visibility.Collapsed : Visibility.Visible;
            RenderDownloadDetails();
        };
        dialog.PrimaryButtonClick += async (sender, click) =>
        {
            click.Cancel = true; var deferral = click.GetDeferral();
            try
            {
                if (!ReferenceEquals(_downloadDialog, sender) || !ReferenceEquals(_downloadDetails, view) || _downloadDetailTask is not { } current ||
                    !_downloadItems.TryGetValue(current, out var visibleItem)) return;
                if (view.RetryPreview is { } preview)
                    await RetryDownloadAsync(current, view, preview);
                else if (view.CancelConfirmation.Visibility == Visibility.Visible)
                    await RunDownloadControlAsync(current, cancel: true);
                else if (visibleItem.CanResume)
                    await ToggleDownloadPausedAsync(current);
                else if (DownloadPolicy.EffectiveStatus(visibleItem) == DownloadStatus.Completed)
                    await OpenDownloadLocallyAsync(current);
                else if (DownloadPolicy.CanReconcile(visibleItem))
                    await RunDownloadControlAsync(current, cancel: false);
                else if (DownloadPolicy.CanRetryFresh(visibleItem))
                    _ = RetryDownloadAsync(current, view);
            }
            finally { deferral.Complete(); }
        };
        RenderDownloadNotice(); RenderDownloadDetails();
        try { await ShowBrowserDialogAsync(dialog); }
        finally
        {
            if (ReferenceEquals(_downloadDialog, dialog))
            {
                _downloadDialog = null; _downloadDetails = null; _downloadDetailTask = null;
                ResetToast(DownloadNotice); RenderDownloadNotice();
            }
        }
    }

    private void RenderDownloadDetails()
    {
        if (_downloadDialog is not { } dialog || _downloadDetails is not { } view || _downloadDetailTask is not { } task
            || !_downloadItems.TryGetValue(task, out var item) || !IsCurrentDownloadContext(item.Context)) return;
        var canRetry = DownloadPolicy.CanRetryFresh(item);
        if (!canRetry) view.RetryPreview = null;
        var reviewingRetry = view.RetryPreview is not null;
        view.Name.Text = DownloadPolicy.DestinationName(item) ?? item.Name;
        view.Destination.Text = (reviewingRetry ? "原保存位置：" : "保存到：") + item.DestinationDisplayPath;
        view.RetryReview.Visibility = reviewingRetry ? Visibility.Visible : Visibility.Collapsed;
        view.RetryDestination.Text = view.RetryPreview?.DestinationDisplayPath ?? string.Empty;
        view.ProgressCard.Visibility = reviewingRetry ? Visibility.Collapsed : Visibility.Visible;
        view.Stage.Text = DownloadPolicy.Stage(item);
        var status = DownloadPolicy.EffectiveStatus(item);
        var completed = status == DownloadStatus.Completed;
        var progress = DownloadPolicy.Progress(item);
        var showProgress = DownloadPolicy.IsActive(status) ||
            status == DownloadStatus.Paused && progress.HasReceivedBytes || DownloadPolicy.HasRetainedProgress(item);
        var canOpen = completed && _downloads is IDownloadControlService;
        view.Progress.IsIndeterminate = progress.IsIndeterminate; view.Progress.Value = progress.Value;
        view.ProgressTrack.Visibility = showProgress ? Visibility.Visible : Visibility.Collapsed;
        view.Activity.Text = completed ? DownloadPolicy.FileSizeText(item) : showProgress ? progress.ActivityText : string.Empty;
        view.Activity.Visibility = view.Activity.Text.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        view.Flushed.Text = showProgress ? progress.FlushedText : string.Empty;
        view.Flushed.Visibility = showProgress ? Visibility.Visible : Visibility.Collapsed;
        view.Detail.Text = _downloadErrorMessage ?? (view.RetryInProgress ? "正在准备重新下载…" :
            completed || reviewingRetry ? string.Empty : DownloadPolicy.Detail(item));
        view.Detail.Visibility = view.Detail.Text.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        view.Play.Visibility = DownloadPolicy.CanPlay(item) && _downloads is IDownloadPlaybackSourceProvider && _playback is not null
            ? Visibility.Visible : Visibility.Collapsed;
        var cancel = DownloadPolicy.CanCancel(item);
        if (!cancel) view.CancelConfirmation.Visibility = Visibility.Collapsed;
        var confirmingCancel = view.CancelConfirmation.Visibility == Visibility.Visible;
        view.Play.IsEnabled = !confirmingCancel && !_downloadPlaybackOpening && !_downloadOperationBusy;
        view.Actions.Visibility = !confirmingCancel && !reviewingRetry && view.Play.Visibility == Visibility.Visible
            ? Visibility.Visible : Visibility.Collapsed;
        dialog.SecondaryButtonText = reviewingRetry || confirmingCancel ? "返回" : cancel ? "取消下载…" : string.Empty;
        dialog.IsSecondaryButtonEnabled = (reviewingRetry || cancel) && !_downloadOperationBusy;
        var hadPrimary = !string.IsNullOrEmpty(dialog.PrimaryButtonText);
        var primary = DownloadPolicy.PrimaryLabel(item);
        dialog.PrimaryButtonText = reviewingRetry ? "确认重新下载" : confirmingCancel ? "确认取消" :
            canOpen ? "打开文件" : primary.Length == 0 && canRetry ? "重新下载" : primary;
        if (hadPrimary != !string.IsNullOrEmpty(dialog.PrimaryButtonText) && dialog.IsLoaded)
            Controls.DialogOrdering.Reorder(dialog);
        dialog.IsPrimaryButtonEnabled = !_downloadOperationBusy && (reviewingRetry || confirmingCancel || canOpen || canRetry || DownloadPolicy.CanReconcile(item) ||
            item.CanResume && _downloads is IDownloadControlService);
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

    private async Task RetryDownloadAsync(TransferTaskRef task, DownloadDetailsView owner, DownloadPreview? confirmed = null)
    {
        bool OwnsDetail() => ReferenceEquals(_downloadDetails, owner) && _downloadDetailTask == task;
        if (_downloadOperationBusy || _downloads is not { } service || !_downloadItems.TryGetValue(task, out var item)
            || !OwnsDetail() || !IsCurrentDownloadContext(item.Context) || !DownloadPolicy.CanRetryFresh(item)
            || confirmed != owner.RetryPreview) return;
        if (DownloadPolicy.DestinationName(item) is not { } localName)
        {
            ShowDownloadError("重新下载未能提交", "无法确认原保存文件名。请重新选择下载位置。");
            return;
        }
        var context = item.Context;
        _downloadErrorTitle = _downloadErrorMessage = null;
        _downloadOperationBusy = true;
        owner.RetryInProgress = true;
        try
        {
            RenderDownloadDetails();
            var preview = confirmed ?? await service.PrepareAsAsync(context, item.Source, localName, _lifetime.Token);
            if (!IsCurrentDownloadContext(context) || !_downloadItems.TryGetValue(task, out var latest)
                || !DownloadPolicy.CanRetryFresh(latest)) return;
            if (preview.Context != context || preview.Draft.Id == Guid.Empty || preview.Source.Account != context.Account ||
                !string.Equals(preview.Source.WirePath, item.Source.WirePath, StringComparison.Ordinal) ||
                !string.Equals(preview.Name, localName, StringComparison.Ordinal) ||
                !string.Equals(Path.GetFileName(preview.DestinationDisplayPath), localName, StringComparison.Ordinal))
                throw new FilewayException(new ErrorInfo(ErrorCode.ProtocolMismatch, "重新下载的预检结果与原任务不一致。"));
            if (confirmed is null && !string.Equals(preview.DestinationDisplayPath, item.DestinationDisplayPath, StringComparison.Ordinal))
            {
                if (OwnsDetail()) owner.RetryPreview = preview;
                return;
            }

            // Closing an already submitted retry never redirects another task's detail.
            var changed = await service.SubmitAsync(preview, _lifetime.Token);
            if (!IsCurrentDownloadContext(context)) return;
            if (changed.Context != context || changed.Snapshot.Task != new TransferTaskRef(context.Account, preview.Draft.Id) ||
                !string.Equals(changed.DestinationDisplayPath, preview.DestinationDisplayPath, StringComparison.Ordinal))
                throw new FilewayException(new ErrorInfo(ErrorCode.ProtocolMismatch, "重新下载的任务与确认记录不一致。"));
            AcceptDownloadItem(changed);
            if (OwnsDetail())
            {
                owner.CancelConfirmation.Visibility = Visibility.Collapsed;
                _downloadDetailTask = changed.Snapshot.Task;
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (!IsCurrentDownloadContext(context)) return;
            if (OwnsDetail()) ShowDownloadError("重新下载未能提交", error);
            else SetNotice("重新下载未能提交", ErrorMessage(error), InfoBarSeverity.Warning);
        }
        finally
        {
            _downloadOperationBusy = false;
            owner.RetryInProgress = false;
            if (confirmed is not null) owner.RetryPreview = null;
            if (!_disposed)
            {
                UpdateDownloadContext();
                if (OwnsDetail() && owner.RetryPreview is not null)
                    DispatcherQueue.TryEnqueue(() =>
                    {
                        if (OwnsDetail() && owner.RetryPreview is not null && _downloadDialog is { IsLoaded: true } dialog)
                            Controls.DialogOrdering.FocusSecondaryButton(dialog);
                    });
            }
        }
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
        const string unavailable = "无法打开已保存的文件。请检查文件是否仍在原位置。";
        if (_downloadOperationBusy || _downloads is not IDownloadControlService service ||
            !_downloadItems.TryGetValue(task, out var item) || !IsCurrentDownloadContext(item.Context) ||
            DownloadPolicy.EffectiveStatus(item) != DownloadStatus.Completed) return;
        _downloadOperationBusy = true;
        _downloadErrorTitle = _downloadErrorMessage = null;
        try
        {
            RenderDownloadDetails();
            // The service verifies identity/length/ancestors before this window opens anything.
            var verified = await service.VerifyLocalOpenAsync(item.Context, task, _lifetime.Token);
            if (!IsCurrentDownloadContext(item.Context)) return;
            if (!verified)
            {
                ShowDownloadError("本地打开未完成", unavailable);
                return;
            }
            var file = await Windows.Storage.StorageFile.GetFileFromPathAsync(item.DestinationDisplayPath);
            if (!IsCurrentDownloadContext(item.Context)) return;
            var opened = await Windows.System.Launcher.LaunchFileAsync(file);
            if (!opened && IsCurrentDownloadContext(item.Context))
                ShowDownloadError("本地打开未完成", "Windows 没有可用的应用来打开此文件。");
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested) { }
        catch (Exception)
        {
            if (IsCurrentDownloadContext(item.Context))
                ShowDownloadError("本地打开未完成", unavailable);
        }
        finally { _downloadOperationBusy = false; if (!_disposed) UpdateDownloadContext(); }
    }

    private async void DownloadHistory_Click(object sender, RoutedEventArgs args) => await ShowTransferCenterAsync(TransferDirection.Download);

    private void ShowDownloadError(string title, Exception error) => ShowDownloadError(title, ErrorMessage(error));

    private void ShowDownloadError(string title, string message)
    {
        _downloadErrorTitle = title; _downloadErrorMessage = message;
        RenderDownloadNotice(); RenderDownloadDetails();
    }

    private sealed class DownloadDetailsView
    {
        public StackPanel Content { get; } = (StackPanel)XamlReader.Load("""
            <StackPanel xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation" xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml" Spacing="16" MinWidth="320" MaxWidth="440">
              <StackPanel Spacing="4"><TextBlock x:Name="Name" FontSize="18" FontWeight="SemiBold" TextWrapping="Wrap"/><TextBlock x:Name="Destination" FontSize="12" TextWrapping="Wrap" IsTextSelectionEnabled="True" Foreground="{ThemeResource TextFillColorSecondaryBrush}"/></StackPanel>
              <Border x:Name="ProgressCard" Padding="16" CornerRadius="10" Background="{ThemeResource FwSurfaceBrush}" BorderBrush="{ThemeResource FwStrokeBrush}" BorderThickness="1"><StackPanel Spacing="8"><TextBlock x:Name="Stage" FontWeight="SemiBold" TextWrapping="Wrap"/><Border x:Name="ProgressTrack" Height="6" CornerRadius="3" Background="{ThemeResource FwStrokeBrush}"><ProgressBar x:Name="Progress" Minimum="0" Maximum="100" Height="4" VerticalAlignment="Center" Background="Transparent" CornerRadius="2" AutomationProperties.Name="下载接收活动进度，完成还需校验与保存"/></Border><TextBlock x:Name="Activity" FontSize="12" TextWrapping="Wrap"/><TextBlock x:Name="Flushed" FontSize="12" TextWrapping="Wrap" Foreground="{ThemeResource TextFillColorSecondaryBrush}"/></StackPanel></Border>
              <TextBlock x:Name="Detail" FontSize="12" TextWrapping="Wrap" Foreground="{ThemeResource TextFillColorSecondaryBrush}" AutomationProperties.LiveSetting="Polite"/>
              <StackPanel x:Name="Actions" Orientation="Horizontal" Spacing="8" Visibility="Collapsed"><Button x:Name="Play" Content="边下边播" HorizontalAlignment="Left" Style="{StaticResource FwSecondaryButtonStyle}" Visibility="Collapsed" AutomationProperties.Name="播放正在下载的视频" ToolTipService.ToolTip="读取已下载内容；未下载片段需要等待，部分视频需取得更多数据才能开始。"/></StackPanel>
              <Border x:Name="RetryReview" Visibility="Collapsed" Padding="12" CornerRadius="10" Background="{ThemeResource FwSurfaceBrush}" BorderThickness="1" BorderBrush="{ThemeResource FwStrokeBrush}">
                <StackPanel Spacing="6">
                  <TextBlock Text="保存位置已变化" FontWeight="SemiBold" AutomationProperties.LiveSetting="Polite"/>
                  <TextBlock Text="新保存位置" FontSize="12" Foreground="{ThemeResource TextFillColorSecondaryBrush}"/>
                  <TextBlock x:Name="RetryDestination" TextWrapping="Wrap" IsTextSelectionEnabled="True"/>
                </StackPanel>
              </Border>
              <Border x:Name="CancelConfirmation" Visibility="Collapsed" Padding="12" CornerRadius="10" Background="{ThemeResource FwSurfaceBrush}" BorderThickness="1" BorderBrush="{ThemeResource FwStrokeBrush}"><TextBlock Text="确认取消当前下载？只会清理本任务拥有的未发布临时文件，已经保存的文件会保留。" TextWrapping="Wrap"/></Border>
            </StackPanel>
            """);
        public DownloadDetailsView()
        {
            var resources = Application.Current.Resources;
            if (resources["FwSecondaryButtonStyle"] is Style secondary)
            {
                Play.Style = secondary;
            }
        }
        public TextBlock Name => (TextBlock)Content.FindName("Name");
        public TextBlock Destination => (TextBlock)Content.FindName("Destination");
        public TextBlock Stage => (TextBlock)Content.FindName("Stage");
        public ProgressBar Progress => (ProgressBar)Content.FindName("Progress");
        public Border ProgressCard => (Border)Content.FindName("ProgressCard");
        public Border ProgressTrack => (Border)Content.FindName("ProgressTrack");
        public TextBlock Activity => (TextBlock)Content.FindName("Activity");
        public TextBlock Flushed => (TextBlock)Content.FindName("Flushed");
        public TextBlock Detail => (TextBlock)Content.FindName("Detail");
        public StackPanel Actions => (StackPanel)Content.FindName("Actions");
        public Button Play => (Button)Content.FindName("Play");
        public bool RetryInProgress { get; set; }
        public DownloadPreview? RetryPreview { get; set; }
        public Border RetryReview => (Border)Content.FindName("RetryReview");
        public TextBlock RetryDestination => (TextBlock)Content.FindName("RetryDestination");
        public Border CancelConfirmation => (Border)Content.FindName("CancelConfirmation");
    }
}
