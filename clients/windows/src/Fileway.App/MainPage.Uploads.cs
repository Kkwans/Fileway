using Fileway.Core.Contracts;
using Microsoft.Windows.Storage.Pickers;
using static Fileway.App.UploadPresentationPolicy;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Markup;

namespace Fileway.App;

public sealed partial class MainPage
{
    private IUploadService? _uploads;
    private Microsoft.UI.WindowId _uploadOwner;
    private UploadContext? _uploadContext;
    private readonly Dictionary<TransferTaskRef, UploadItem> _uploadItems = [];
    private readonly Dictionary<TransferTaskRef, UploadOrigin> _uploadOrigins = [];
    private readonly HashSet<TransferTaskRef> _uploadRefreshes = [];
    private readonly HashSet<Guid> _uploadBatchRefreshes = [];
    private readonly HashSet<(TransferTaskRef Task, long Generation, UploadStatus Status)> _uploadNoticeResults = [];
    private readonly HashSet<(Guid BatchId, bool Complete)> _uploadBatchNoticeResults = [];
    private Guid? _submittingFolderBatch;
    private TransferTaskRef? _uploadNoticeTask;
    private bool _uploadNoticeOpensHistory;
    private UploadItem? _latestUpload;
    private ContentDialog? _uploadDialog;
    private UploadDetailsView? _uploadDetails;
    private TransferTaskRef? _uploadDetailTask;
    private bool _uploadPickerOpen;
    private bool _uploadCommandBusy;
    private long _uploadViewGeneration;
    private string? _uploadErrorTitle;
    private string? _uploadErrorMessage;

    public void SetUploadService(IUploadService uploads, Microsoft.UI.WindowId owner)
    {
        if (_disposed) return;
        if (_uploads is not null) _uploads.TaskChanged -= Upload_TaskChanged;
        ClearUploadPresentation();
        _uploads = uploads; _uploadOwner = owner;
        _uploads.TaskChanged += Upload_TaskChanged;
        UpdateUploadContext();
    }

    private UploadContext? ActiveUploadContext => !_disposed && !_connecting && _session is { } session
        && _uploads?.CurrentContext is { } context && context.Account == session.Account && context.SessionId == session.Id
        ? context : null;
    private bool IsCurrentUploadContext(UploadContext context) => ActiveUploadContext == context;
    private bool CanSubmitUpload => _uploads is not null && _uploadOwner.Value != 0 && ActiveUploadContext is not null
        && CanWrite(p => p.Create) && !_uploadPickerOpen && !_uploadCommandBusy;

    private void UpdateUploadContext()
    {
        if (UploadFileButton is null) return;
        var current = ActiveUploadContext;
        if (_uploadContext != current)
        {
            ClearUploadPresentation();
            _uploadContext = current;
        }
        UploadFileButton.IsEnabled = CanSubmitUpload;
        UploadFileButton.Visibility = _session?.Permissions.Create == false ? Visibility.Collapsed : Visibility.Visible;
        UploadHistoryButton.IsEnabled = current is not null && !_uploadCommandBusy;
        RenderUploadNotice();
        RenderUploadDetails();
        UpdateTransferCenterContext();
    }

    private void ClearUploadPresentation()
    {
        ++_uploadViewGeneration;
        _uploadDialog?.Hide();
        _uploadDialog = null; _uploadDetails = null; _uploadDetailTask = null;
        _uploadContext = null; _latestUpload = null;
        _uploadNoticeTask = null; _uploadNoticeOpensHistory = false;
        _uploadNoticeResults.Clear(); _uploadBatchNoticeResults.Clear();
        _uploadErrorTitle = _uploadErrorMessage = null;
        _uploadItems.Clear(); _uploadOrigins.Clear(); _uploadRefreshes.Clear(); _uploadBatchRefreshes.Clear();
        if (UploadNotice is not null) UploadNotice.IsOpen = false;
        ClearTransferUploadPresentation();
    }

    private void DisposeUploadPresentation()
    {
        if (_uploads is not null) _uploads.TaskChanged -= Upload_TaskChanged;
        ClearUploadPresentation();
    }

    private async void UploadFile_Click(object sender, RoutedEventArgs args)
    {
        if (!CanSubmitUpload || _uploads is not { } service || _session is not { } session
            || _directory is not { } parent || ActiveUploadContext is not { } context) return;
        var generation = _generation;
        var viewGeneration = _uploadViewGeneration;
        bool CurrentDestination() => viewGeneration == _uploadViewGeneration && IsCurrentUploadContext(context)
            && SameBrowser(session, parent, generation) && _session?.Permissions.Create == true && !HasUnresolvedWrite;
        _uploadPickerOpen = true; UpdateUploadContext();
        try
        {
            string filePath;
            try
            {
                var picker = new Windows.Storage.Pickers.FileOpenPicker
                {
                    CommitButtonText = "选择文件",
                    SuggestedStartLocation = Windows.Storage.Pickers.PickerLocationId.DocumentsLibrary,
                    ViewMode = Windows.Storage.Pickers.PickerViewMode.List,
                };
                WinRT.Interop.InitializeWithWindow.Initialize(picker, Microsoft.UI.Win32Interop.GetWindowFromWindowId(_uploadOwner));
                picker.FileTypeFilter.Add("*");
                var file = await picker.PickSingleFileAsync().AsTask(_lifetime.Token);
                if (file is null || !CurrentDestination()) return;
                filePath = file.Path;
            }
            catch (OperationCanceledException) { return; }
            catch (Exception)
            {
                if (CurrentDestination()) ShowUploadMessage("无法打开文件选择器", "文件选择器未能打开。请重试，或把文件拖入窗口上传。");
                return;
            }
            var name = Path.GetFileName(filePath);
            var content = new StackPanel { Spacing = 12, MinWidth = 320, MaxWidth = 440 };
            content.Children.Add(new TextBlock { Text = name, FontSize = 18, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap });
            var destinationLabel = new TextBlock { Text = "上传到" };
            destinationLabel.Style = (Style)Application.Current.Resources["FwCaptionTextStyle"];
            var destinationPath = new TextBlock { Text = parent.DisplayPath, FontSize = 13, TextWrapping = TextWrapping.Wrap };
            content.Children.Add(new StackPanel { Spacing = 4, Children = { destinationLabel, destinationPath } });
            var note = new TextBlock { Text = "仅上传这个文件。遇到同名项目会停止并报告冲突，不覆盖现有文件。" };
            note.Style = (Style)Application.Current.Resources["FwCaptionTextStyle"];
            content.Children.Add(note);
            var confirm = new ContentDialog { Title = "确认上传目标", Content = content, PrimaryButtonText = "开始上传", CloseButtonText = "取消", DefaultButton = ContentDialogButton.None };
            _uploadDialog = confirm;
            ContentDialogResult confirmed;
            try { confirmed = await ShowBrowserDialogAsync(confirm); }
            finally { if (ReferenceEquals(_uploadDialog, confirm)) _uploadDialog = null; }
            if (confirmed != ContentDialogResult.Primary || !CurrentDestination()) return;
            _uploadErrorTitle = _uploadErrorMessage = null;
            _uploadCommandBusy = true; UpdateUploadContext();
            var item = await service.SubmitAsync(new UploadSubmission(Guid.NewGuid(), context, parent, filePath), _lifetime.Token);
            if (!IsCurrentUploadContext(context)) return;
            _uploadOrigins[item.Snapshot.Task] = new UploadOrigin(session, parent, generation);
            AcceptUploadItem(item);
            _uploadCommandBusy = false; UpdateUploadContext();
            await ShowUploadDetailsAsync(item.Snapshot.Task);
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (CurrentDestination()) ShowUploadError("上传未能提交", error);
        }
        finally
        {
            _uploadPickerOpen = false; _uploadCommandBusy = false;
            if (!_disposed) UpdateUploadContext();
        }
    }

    private const int MaximumUploadTreeFiles = 1000;

    private async void UploadFolder_Click(object sender, RoutedEventArgs args)
    {
        if (!CanSubmitUpload || _uploads is not { } service || _session is not { } session
            || _directory is not { } parent || ActiveUploadContext is not { } context) return;
        var generation = _generation;
        var viewGeneration = _uploadViewGeneration;
        bool CurrentDestination() => viewGeneration == _uploadViewGeneration && IsCurrentUploadContext(context)
            && SameBrowser(session, parent, generation) && _session?.Permissions.Create == true && !HasUnresolvedWrite;
        _uploadPickerOpen = true; UpdateUploadContext();
        try
        {
            string folderPath;
            try
            {
                var picker = new Windows.Storage.Pickers.FolderPicker
                {
                    CommitButtonText = "选择文件夹",
                    SuggestedStartLocation = Windows.Storage.Pickers.PickerLocationId.DocumentsLibrary,
                };
                WinRT.Interop.InitializeWithWindow.Initialize(picker, Microsoft.UI.Win32Interop.GetWindowFromWindowId(_uploadOwner));
                picker.FileTypeFilter.Add("*");
                var folder = await picker.PickSingleFolderAsync().AsTask(_lifetime.Token);
                if (folder is null || !CurrentDestination()) return;
                folderPath = folder.Path;
            }
            catch (OperationCanceledException) { return; }
            catch (Exception)
            {
                if (CurrentDestination()) ShowUploadMessage("无法打开文件夹选择器", "文件夹选择器未能打开。请重试，或把文件夹拖入窗口上传。");
                return;
            }
            await SubmitUploadTreeAsync(service, context, session, parent, generation, viewGeneration, folderPath);
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (CurrentDestination()) ShowUploadError("文件夹上传未完成", error);
        }
        finally
        {
            _uploadPickerOpen = false; _uploadCommandBusy = false;
            if (!_disposed) UpdateUploadContext();
        }
    }

    /// <summary>Shared confirm-and-submit flow for a named local folder (picker or drop).</summary>
    private async Task SubmitUploadTreeAsync(IUploadService service, UploadContext context, ConnectionSession session,
        RemoteResourceRef parent, long generation, long viewGeneration, string folderPath)
    {
        bool CurrentDestination() => viewGeneration == _uploadViewGeneration && IsCurrentUploadContext(context)
            && SameBrowser(session, parent, generation) && _session?.Permissions.Create == true && !HasUnresolvedWrite;
        Guid? submittingBatch = null;
        try
        {
            if (!CurrentDestination()) return;
            var localDirectory = Path.TrimEndingDirectorySeparator(folderPath);
            var folderLabel = Path.GetFileName(localDirectory);
            if (!Path.IsPathFullyQualified(folderPath) || Path.GetPathRoot(folderPath) is not { } volume ||
                string.Equals(localDirectory, Path.TrimEndingDirectorySeparator(volume), StringComparison.OrdinalIgnoreCase) ||
                string.IsNullOrWhiteSpace(folderLabel))
            {
                if (CurrentDestination()) ShowUploadMessage("请选择具体文件夹", "请选择磁盘或共享中的一个文件夹。");
                return;
            }
            if (!CanPreserveDirectoryName(folderLabel) ||
                (File.GetAttributes(localDirectory) & FileAttributes.ReparsePoint) != 0)
            {
                if (CurrentDestination()) ShowUploadMessage("无法保留此文件夹", "请选择名称可原样保存且不是链接的文件夹。");
                return;
            }
            var (files, emptyDirectories, failures, truncated) = EnumerateLocalUploadTree(localDirectory, MaximumUploadTreeFiles);
            long total = 0;
            foreach (var (path, _) in files)
                try { total = checked(total + new FileInfo(path).Length); } catch (IOException) { }
            var content = new StackPanel { Spacing = 12, MinWidth = 320, MaxWidth = 440 };
            content.Children.Add(new TextBlock { Text = folderLabel, FontSize = 18, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap });
            var destinationLabel = parent.DisplayPath.TrimEnd('/') + "/" + folderLabel;
            var emptySummary = emptyDirectories.Count > 0 ? $" · {emptyDirectories.Count} 个空目录" : "";
            content.Children.Add(new TextBlock { Text = $"上传到：{destinationLabel}\n共 {files.Count} 个文件 · {DownloadPresentationPolicy.FormatBytes(total)}{emptySummary}", TextWrapping = TextWrapping.Wrap });
            content.Children.Add(new TextBlock { Text = "保留源文件夹及内部结构。同名文件夹合并；同名文件报告冲突，保留已有文件。", TextWrapping = TextWrapping.Wrap, FontSize = 12 });
            if (truncated) content.Children.Add(new TextBlock { Text = $"枚举达到上限（{MaximumUploadTreeFiles} 个文件），超出部分未包含。", TextWrapping = TextWrapping.Wrap, FontSize = 12 });
            foreach (var failure in failures.Take(8))
                content.Children.Add(new TextBlock { Text = failure + "：无法读取或保留名称，未包含。", TextWrapping = TextWrapping.Wrap, FontSize = 12 });
            if (failures.Count > 8) content.Children.Add(new TextBlock { Text = $"另有 {failures.Count - 8} 个项目未包含。", FontSize = 12 });
            var confirm = new ContentDialog { Title = "确认上传文件夹", Content = content, PrimaryButtonText = "开始上传", CloseButtonText = "取消", DefaultButton = ContentDialogButton.None,
                IsPrimaryButtonEnabled = files.Count > 0 || emptyDirectories.Count > 0 || failures.Count == 0 };
            _uploadDialog = confirm;
            ContentDialogResult confirmed;
            try { confirmed = await ShowBrowserDialogAsync(confirm); }
            finally { if (ReferenceEquals(_uploadDialog, confirm)) _uploadDialog = null; }
            if (confirmed != ContentDialogResult.Primary || !CurrentDestination()) return;
            _uploadErrorTitle = _uploadErrorMessage = null;
            _uploadCommandBusy = true; UpdateUploadContext();
            var batchId = Guid.NewGuid();
            _submittingFolderBatch = submittingBatch = batchId;
            var batch = new UploadBatchInfo(batchId, folderLabel, files.Count, total, IsFolder: true);
            var destination = await service.EnsureRemoteTreeAsync(context, parent, folderLabel, _lifetime.Token);
            if (!CurrentDestination()) return;
            var parents = new Dictionary<string, RemoteResourceRef>(StringComparer.OrdinalIgnoreCase) { [""] = destination };
            var deadDirectories = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            int unconfirmedDirectories = 0;
            foreach (var relative in emptyDirectories)
            {
                if (!CurrentDestination()) break;
                try { await service.EnsureRemoteTreeAsync(context, destination, relative, _lifetime.Token); }
                catch (Exception error) when (error is not OperationCanceledException) { unconfirmedDirectories++; }
            }
            int added = 0, ordinal = 0;
            foreach (var (path, relative) in files)
            {
                if (!CurrentDestination()) break;
                string relativeDirectory = Path.GetDirectoryName(relative) ?? "";
                if (deadDirectories.Any(dead => relativeDirectory.StartsWith(dead + "\\", StringComparison.OrdinalIgnoreCase) ||
                    string.Equals(relativeDirectory, dead, StringComparison.OrdinalIgnoreCase)))
                    continue;
                try
                {
                    if (!parents.TryGetValue(relativeDirectory, out var targetParent))
                    {
                        targetParent = await service.EnsureRemoteTreeAsync(context, destination, relativeDirectory, _lifetime.Token);
                        parents[relativeDirectory] = targetParent;
                    }
                    if (!CurrentDestination()) break;
                    var item = await service.SubmitAsync(new UploadSubmission(Guid.NewGuid(), context, targetParent, path, batchId, ordinal++, batch), _lifetime.Token);
                    if (IsCurrentUploadContext(context))
                    {
                        _uploadOrigins[item.Snapshot.Task] = new UploadOrigin(session, parent, generation);
                        AcceptUploadItem(item);
                    }
                    added++;
                }
                catch (Exception error) when (error is not OperationCanceledException)
                {
                    // One failing directory must not poison its siblings; its remaining files are
                    // skipped without resubmitting an unconfirmed draft.
                    deadDirectories.Add(relativeDirectory);
                }
            }
            if (IsCurrentUploadContext(context))
            {
                // The remote folder tree exists as soon as the first file is accepted;
                // refresh once so the new folder appears without waiting for every file.
                if (!await RefreshOriginAsync(session, parent, generation) || !IsCurrentUploadContext(context)) return;
                foreach (var item in _uploadItems.Values)
                    if (item.Batch?.Id == batchId && _uploadOrigins.TryGetValue(item.Snapshot.Task, out var origin) &&
                        origin.Generation == generation)
                        _uploadOrigins[item.Snapshot.Task] = origin with { Generation = _generation };
                var unconfirmed = files.Count - added;
                if (unconfirmed == 0 && unconfirmedDirectories == 0)
                {
                    if (added == 0) SetNotice("文件夹已创建", string.Empty, InfoBarSeverity.Success);
                }
                else
                    SetNotice("文件夹上传", $"已提交 {added} 个文件。{unconfirmed} 个文件、{unconfirmedDirectories} 个空目录未确认，请核实结果。", InfoBarSeverity.Warning);
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (CurrentDestination()) ShowUploadError("文件夹上传未完成", error);
        }
        finally
        {
            if (submittingBatch is not null && _submittingFolderBatch == submittingBatch)
                _submittingFolderBatch = null;
            _uploadPickerOpen = false; _uploadCommandBusy = false;
            if (!_disposed) UpdateUploadContext();
        }
    }

    /// <summary>Bounded local enumeration for a confirmed upload; reparse points are never traversed.</summary>
    private static (List<(string Path, string Relative)> Files, List<string> EmptyDirectories, List<string> Failures, bool Truncated)
        EnumerateLocalUploadTree(string root, int maximum)
    {
        var files = new List<(string Path, string Relative)>();
        var emptyDirectories = new List<string>();
        var failures = new List<string>();
        bool truncated = false;
        var pending = new Queue<(string Directory, string Relative)>();
        pending.Enqueue((root, ""));
        while (pending.Count != 0 && !truncated)
        {
            var (directory, relative) = pending.Dequeue();
            try
            {
                var hasEntries = false;
                foreach (var entry in System.IO.Directory.EnumerateFileSystemEntries(directory))
                {
                    string name = Path.GetFileName(entry);
                    var relativeEntry = relative.Length == 0 ? name : Path.Combine(relative, name);
                    System.IO.FileAttributes attributes;
                    try { attributes = System.IO.File.GetAttributes(entry); }
                    catch (Exception error) when (error is IOException or UnauthorizedAccessException)
                    {
                        failures.Add(relativeEntry);
                        hasEntries = true;
                        continue;
                    }
                    if ((attributes & System.IO.FileAttributes.Directory) != 0)
                    {
                        // Reparse points (junctions/links) are never traversed: their content is
                        // outside the confirmed folder and may recurse infinitely.
                        if ((attributes & System.IO.FileAttributes.ReparsePoint) != 0) continue;
                        hasEntries = true;
                        if (!CanPreserveDirectoryName(name))
                        {
                            failures.Add(relativeEntry);
                            continue;
                        }
                        if (files.Count >= maximum) { truncated = true; break; }
                        pending.Enqueue((entry, relativeEntry));
                    }
                    else
                    {
                        hasEntries = true;
                        if (files.Count >= maximum) { truncated = true; break; }
                        files.Add((entry, relativeEntry));
                    }
                }
                if (!hasEntries && relative.Length > 0) emptyDirectories.Add(relative);
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException)
            { failures.Add(relative.Length == 0 ? "(文件夹根目录)" : relative); }
        }
        return (files, emptyDirectories, failures, truncated);
    }

    private static bool CanPreserveDirectoryName(string name) => name.Length > 0 && name == name.Trim().TrimEnd('.');

    private void FilesArea_DragOver(object sender, DragEventArgs args)
    {
        if (IsFileMove(args))
        {
            DropOverlay.Visibility = Visibility.Collapsed;
            MoveFilesDragOver(args);
            return;
        }
        ClearMoveDropTarget();
        if (!CanSubmitUpload || args.DataView is null ||
            !args.DataView.Contains(Windows.ApplicationModel.DataTransfer.StandardDataFormats.StorageItems))
        {
            args.AcceptedOperation = Windows.ApplicationModel.DataTransfer.DataPackageOperation.None;
            DropOverlay.Visibility = Visibility.Collapsed;
            return;
        }
        args.AcceptedOperation = Windows.ApplicationModel.DataTransfer.DataPackageOperation.Copy;
        string target = _directory?.DisplayName is { Length: > 0 } name ? name : "当前目录";
        if (args.DragUIOverride is { } appearance)
        {
            appearance.Caption = "上传到 " + target;
            appearance.IsCaptionVisible = true;
            appearance.IsContentVisible = false;
        }
        DropOverlayText.Text = "松开以上传到 " + target;
        DropOverlay.Visibility = Visibility.Visible;
    }

    private void FilesArea_DragLeave(object sender, DragEventArgs args)
    {
        DropOverlay.Visibility = Visibility.Collapsed;
        ClearMoveDropTarget();
    }

    private async void FilesArea_Drop(object sender, DragEventArgs args)
    {
        DropOverlay.Visibility = Visibility.Collapsed;
        if (IsFileMove(args))
        {
            await MoveFilesDropAsync(args);
            return;
        }
        if (!CanSubmitUpload || _uploads is not { } service || _session is not { } session ||
            _directory is not { } parent || ActiveUploadContext is not { } context || args.DataView is null ||
            !args.DataView.Contains(Windows.ApplicationModel.DataTransfer.StandardDataFormats.StorageItems)) return;
        var generation = _generation;
        var viewGeneration = _uploadViewGeneration;
        var deferral = args.GetDeferral();
        try
        {
            var items = await args.DataView.GetStorageItemsAsync();
            var files = new List<string>();
            var folders = new List<string>();
            foreach (var item in items)
            {
                if (string.IsNullOrEmpty(item.Path)) continue;
                if (item is Windows.Storage.StorageFolder) folders.Add(item.Path);
                else files.Add(item.Path);
            }
            if (files.Count != 0)
                await SubmitDroppedFilesAsync(service, context, session, parent, generation, viewGeneration, files);
            foreach (var folderPath in folders)
            {
                if (viewGeneration != _uploadViewGeneration || !IsCurrentUploadContext(context) ||
                    _session?.Permissions.Create != true) break;
                await SubmitUploadTreeAsync(service, context, session, parent, generation, viewGeneration, folderPath);
            }
        }
        catch (Exception) { /* 拖放是非权威入口；单项失败由任务自身状态在传输中心呈现。 */ }
        finally { deferral.Complete(); }
    }

    private async Task SubmitDroppedFilesAsync(IUploadService service, UploadContext context, ConnectionSession session,
        RemoteResourceRef parent, long generation, long viewGeneration, List<string> files)
    {
        bool CurrentDestination() => viewGeneration == _uploadViewGeneration && IsCurrentUploadContext(context)
            && SameBrowser(session, parent, generation) && _session?.Permissions.Create == true && !HasUnresolvedWrite;
        if (files.Count > MaximumUploadTreeFiles) files = files[..MaximumUploadTreeFiles];
        long total = 0;
        foreach (var path in files)
            try { total = checked(total + new FileInfo(path).Length); } catch (IOException) { }
        var content = new StackPanel { Spacing = 12, MinWidth = 320, MaxWidth = 440 };
        content.Children.Add(new TextBlock { Text = $"上传 {files.Count} 个文件 · {DownloadPresentationPolicy.FormatBytes(total)}", FontSize = 18, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap });
        content.Children.Add(new TextBlock { Text = $"上传到：{parent.DisplayPath}\n同名项目停止并报告冲突，不覆盖现有文件。", TextWrapping = TextWrapping.Wrap });
        foreach (var path in files.Take(12))
        {
            long bytes = 0;
            try { bytes = new FileInfo(path).Length; } catch (IOException) { }
            content.Children.Add(new TextBlock { Text = Path.GetFileName(path) + " · " + DownloadPresentationPolicy.FormatBytes(bytes), TextWrapping = TextWrapping.Wrap, FontSize = 12 });
        }
        if (files.Count > 12) content.Children.Add(new TextBlock { Text = $"其余 {files.Count - 12} 个文件略。", FontSize = 12 });
        var confirm = new ContentDialog { Title = "确认上传拖入的文件", Content = content, PrimaryButtonText = "开始上传", CloseButtonText = "取消", DefaultButton = ContentDialogButton.None,
            IsPrimaryButtonEnabled = files.Count > 0 };
        _uploadDialog = confirm;
        ContentDialogResult confirmed;
        try { confirmed = await ShowBrowserDialogAsync(confirm); }
        finally { if (ReferenceEquals(_uploadDialog, confirm)) _uploadDialog = null; }
        if (confirmed != ContentDialogResult.Primary || !CurrentDestination() || files.Count == 0) return;
        _uploadErrorTitle = _uploadErrorMessage = null;
        _uploadCommandBusy = true; UpdateUploadContext();
        var batchId = Guid.NewGuid();
        int added = 0, unconfirmed = 0;
        foreach (var path in files)
        {
            if (!CurrentDestination()) break;
            try
            {
                var item = await service.SubmitAsync(new UploadSubmission(Guid.NewGuid(), context, parent, path, batchId, added), _lifetime.Token);
                if (IsCurrentUploadContext(context))
                {
                    _uploadOrigins[item.Snapshot.Task] = new UploadOrigin(session, parent, generation);
                    AcceptUploadItem(item);
                }
                added++;
            }
            catch (Exception) { unconfirmed++; }
        }
        if (IsCurrentUploadContext(context))
            SetNotice("拖入上传", $"已提交 {added} 个文件。" + (unconfirmed > 0 ? $"{unconfirmed} 个文件未确认提交，请到传输中心核实，勿重复提交。" : ""), unconfirmed > 0 ? InfoBarSeverity.Warning : InfoBarSeverity.Success);
        _uploadCommandBusy = false;
        if (!_disposed) UpdateUploadContext();
    }

    private void Upload_TaskChanged(object? sender, UploadItem item) => DispatcherQueue.TryEnqueue(() =>
    {
        if (!ReferenceEquals(sender, _uploads) || !IsCurrentUploadContext(item.Context)) return;
        UpdateUploadContext();
        AcceptUploadItem(item);
    });

    private void AcceptUploadItem(UploadItem item)
    {
        if (!IsCurrentUploadContext(item.Context) || item.Snapshot.Task.Account != item.Context.Account) return;
        if (_uploadItems.TryGetValue(item.Snapshot.Task, out var previous)
            && ReferenceEquals(PreferUploadItem(previous, item), previous))
        {
            // Submission may register its origin after a newer completion event was already displayed.
            TryRefreshCompletedUpload(previous);
            return;
        }
        _uploadErrorTitle = _uploadErrorMessage = null;
        _uploadItems[item.Snapshot.Task] = item; _latestUpload = item;
        RenderUploadNotice(); RenderUploadDetails();
        ObserveTransferUpload(item);
        TryRefreshCompletedUpload(item);
    }

    private void TryRefreshCompletedUpload(UploadItem item)
    {
        if (item.Status != UploadStatus.Completed || !item.Snapshot.RemotePublished) return;
        // A reload changes the origin generation used while admitting a folder tree.
        if (_submittingFolderBatch is not null) return;
        if (!_uploadOrigins.TryGetValue(item.Snapshot.Task, out var origin)
            || !SameBrowser(origin.Session, origin.Parent, origin.Generation)) return;
        // A folder batch only needs one refresh: wait until no member is still uploading,
        // so a large tree does not reload the directory once per file.
        if (item.Batch is { } batch)
        {
            if (!BatchSettled(batch.Id) || !_uploadBatchRefreshes.Add(batch.Id)) return;
        }
        if (!_uploadRefreshes.Add(item.Snapshot.Task)) return;
        _ = RefreshCompletedUploadAsync(item, origin);
    }

    private bool BatchSettled(Guid batchId) => !_uploadItems.Values.Any(item => item.Batch?.Id == batchId
        && item.Status is UploadStatus.Queued or UploadStatus.Preparing or UploadStatus.Uploading or UploadStatus.Pausing
            or UploadStatus.Canceling or UploadStatus.Verifying or UploadStatus.Publishing or UploadStatus.Reconciling
            or UploadStatus.WaitingNetwork or UploadStatus.WaitingAuthentication);

    private async Task RefreshCompletedUploadAsync(UploadItem item, UploadOrigin origin)
    {
        try
        {
            if (IsCurrentUploadContext(item.Context) && SameBrowser(origin.Session, origin.Parent, origin.Generation))
                await LoadAsync(origin.Parent);
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (IsCurrentUploadContext(item.Context)) ShowUploadError("上传已完成，目录刷新失败", error);
        }
    }

    private void RenderUploadNotice()
    {
        if (UploadNotice is null) return;
        if (_uploadDialog is not null) { UploadNotice.IsOpen = false; return; }

        UploadDetailsButton.IsEnabled = _uploadNoticeTask is not null && !_uploadCommandBusy;
        if (_uploadErrorMessage is { } message && ActiveUploadContext is not null)
        {
            _uploadNoticeTask = _latestUpload?.Snapshot.Task;
            _uploadNoticeOpensHistory = false;
            UploadDetailsButton.Content = "上传详情";
            UploadDetailsButton.Visibility = _uploadNoticeTask is null ? Visibility.Collapsed : Visibility.Visible;
            UploadDetailsButton.IsEnabled = _uploadNoticeTask is not null && !_uploadCommandBusy;
            UploadNotice.Title = _uploadErrorTitle;
            UploadNotice.Message = message;
            UploadNotice.Severity = InfoBarSeverity.Warning;
            ShowToast(UploadNotice, _uploadErrorTitle + message);
            return;
        }

        if (_latestUpload is not { } item || !IsCurrentUploadContext(item.Context)) { UploadNotice.IsOpen = false; return; }
        if (item.Batch is { IsFolder: true } batch)
        {
            RenderFolderBatchNotice(item, batch);
            return;
        }

        var complete = item.Status == UploadStatus.Completed && item.Snapshot.RemotePublished;
        var needsAttention = item.Status is UploadStatus.Conflict or UploadStatus.SourceChanged
            or UploadStatus.Failed or UploadStatus.OutcomeUnknown;
        if ((!complete && !needsAttention) || !_uploadNoticeResults.Add((item.Snapshot.Task, item.Snapshot.Generation, item.Status))) return;

        _uploadNoticeTask = item.Snapshot.Task;
        _uploadNoticeOpensHistory = false;
        UploadDetailsButton.Content = "上传详情";
        UploadDetailsButton.Visibility = Visibility.Visible;
        UploadDetailsButton.IsEnabled = !_uploadCommandBusy;
        UploadNotice.Title = UploadItemStatusLabel(item);
        UploadNotice.Message = complete ? string.Empty : item.Name;
        UploadNotice.Severity = complete ? InfoBarSeverity.Success : InfoBarSeverity.Warning;
        ShowToast(UploadNotice, item.Snapshot.Task.Id.ToString() + UploadNotice.Title, repeat: true);
    }

    private void RenderFolderBatchNotice(UploadItem item, UploadBatchInfo batch)
    {
        if (_submittingFolderBatch == batch.Id || !BatchSettled(batch.Id)) return;
        var items = _uploadItems.Values.Where(candidate => candidate.Batch?.Id == batch.Id).ToArray();
        if (items.LongLength != batch.ItemCount) return;

        var completed = items.Count(candidate => candidate.Status == UploadStatus.Completed && candidate.Snapshot.RemotePublished);
        var complete = completed == batch.ItemCount;
        if (!_uploadBatchNoticeResults.Add((batch.Id, complete))) return;

        _uploadNoticeTask = item.Snapshot.Task;
        _uploadNoticeOpensHistory = true;
        UploadDetailsButton.Content = "上传记录";
        UploadDetailsButton.Visibility = Visibility.Visible;
        UploadDetailsButton.IsEnabled = !_uploadCommandBusy;
        UploadNotice.Title = complete ? "文件夹上传已完成" : "文件夹上传有待处理项";
        UploadNotice.Message = string.Empty;
        UploadNotice.Severity = complete ? InfoBarSeverity.Success : InfoBarSeverity.Warning;
        ShowToast(UploadNotice, batch.Id.ToString() + UploadNotice.Title);
    }

    private async void UploadDetails_Click(object sender, RoutedEventArgs args)
    {
        if (_uploadNoticeTask is not { } task) return;
        UploadNotice.IsOpen = false;
        if (_uploadNoticeOpensHistory) await ShowTransferCenterAsync(TransferDirection.Upload);
        else await ShowUploadDetailsAsync(task);
    }

    private async Task ShowUploadDetailsAsync(TransferTaskRef task)
    {
        if (_browserDialog is not null || _uploadDialog is not null || !_uploadItems.TryGetValue(task, out var item)
            || !IsCurrentUploadContext(item.Context)) return;
        UploadDetailsView view;
        try { view = new UploadDetailsView(); }
        catch (Exception error) { ShowUploadError("上传详情未能打开", error); return; }
        var dialog = new ContentDialog { Title = "文件上传", Content = view.Content, CloseButtonText = "关闭", DefaultButton = ContentDialogButton.None };
        _uploadDialog = dialog; _uploadDetails = view; _uploadDetailTask = task;
        view.Cancel.Click += (_, _) => { view.CancelConfirmation.Visibility = Visibility.Visible; view.Cancel.IsEnabled = false; RenderUploadDetails(); };
        view.Keep.Click += (_, _) => { view.CancelConfirmation.Visibility = Visibility.Collapsed; RenderUploadDetails(); };
        view.ConfirmCancel.Click += async (_, _) => await RunUploadCommandAsync(task, UploadCommand.Cancel);
        dialog.PrimaryButtonClick += async (_, click) =>
        {
            click.Cancel = true;
            var deferral = click.GetDeferral();
            try
            {
                if (_uploadItems.TryGetValue(task, out var current) && PrimaryUploadCommand(current) is { } command)
                    await RunUploadCommandAsync(task, command);
            }
            finally { deferral.Complete(); }
        };
        RenderUploadNotice(); RenderUploadDetails();
        try { await ShowBrowserDialogAsync(dialog); }
        finally
        {
            if (ReferenceEquals(_uploadDialog, dialog))
            {
                _uploadDialog = null; _uploadDetails = null; _uploadDetailTask = null;
                ResetToast(UploadNotice); RenderUploadNotice();
            }
        }
    }

    private void RenderUploadDetails()
    {
        if (_uploadDialog is not { } dialog || _uploadDetails is not { } view || _uploadDetailTask is not { } task
            || !_uploadItems.TryGetValue(task, out var item) || !IsCurrentUploadContext(item.Context)) return;
        view.Name.Text = item.Name;
        view.Destination.Text = $"目标：{item.DestinationDisplayPath}";
        view.Stage.Text = UploadItemStatusLabel(item);
        var progress = Progress(item);
        view.Confirmed.Text = progress.ConfirmedText;
        view.Progress.IsIndeterminate = progress.IsIndeterminate;
        view.Progress.Value = progress.Value;
        view.ProgressTrack.Visibility = item.Status == UploadStatus.Completed ? Visibility.Collapsed : Visibility.Visible;
        view.Detail.Text = _uploadErrorMessage ?? UploadDetailLabel(item);
        var cancel = CanCancelUpload(item.Status);
        if (!cancel) view.CancelConfirmation.Visibility = Visibility.Collapsed;
        var confirmingCancel = view.CancelConfirmation.Visibility == Visibility.Visible;
        view.Cancel.Visibility = cancel && !confirmingCancel ? Visibility.Visible : Visibility.Collapsed;
        view.Cancel.IsEnabled = cancel && !_uploadCommandBusy;
        view.Keep.IsEnabled = !_uploadCommandBusy;
        view.ConfirmCancel.IsEnabled = cancel && !_uploadCommandBusy;
        dialog.PrimaryButtonText = confirmingCancel ? string.Empty : PrimaryButtonLabel(item);
        dialog.IsPrimaryButtonEnabled = !confirmingCancel && !_uploadCommandBusy && CanUsePrimary(item, _session?.Permissions.Create == true);
    }

    private async Task RunUploadCommandAsync(TransferTaskRef task, UploadCommand command)
    {
        if (_uploadCommandBusy || _uploads is not { } service || !_uploadItems.TryGetValue(task, out var item)
            || !IsCurrentUploadContext(item.Context)) return;
        if (command == UploadCommand.Cancel ? !CanCancelUpload(item.Status) : PrimaryUploadCommand(item) != command) return;
        var context = item.Context;
        _uploadErrorTitle = _uploadErrorMessage = null;
        _uploadCommandBusy = true; UpdateUploadContext();
        try
        {
            if ((command is UploadCommand.Resume or UploadCommand.Reconcile) && _session is { } session && _directory is { } directory
                && directory.Account == item.Parent.Account && string.Equals(directory.WirePath, item.Parent.WirePath, StringComparison.Ordinal))
                _uploadOrigins[task] = new UploadOrigin(session, directory, _generation);
            var changed = command switch
            {
                UploadCommand.Pause => await service.PauseAsync(context, task, _lifetime.Token),
                UploadCommand.Cancel => await service.CancelAsync(context, task, _lifetime.Token),
                _ => await service.ResumeAsync(context, task, _lifetime.Token),
            };
            AcceptUploadItem(changed);
            if (_uploadDetails is { } view) view.CancelConfirmation.Visibility = Visibility.Collapsed;
        }
        catch (OperationCanceledException) { }
        catch (Exception error) { if (IsCurrentUploadContext(context)) ShowUploadError("上传操作未能完成", error); }
        finally { _uploadCommandBusy = false; if (!_disposed) UpdateUploadContext(); }
    }

    private async void UploadHistory_Click(object sender, RoutedEventArgs args) => await ShowTransferCenterAsync(TransferDirection.Upload);

    private void ShowUploadError(string title, Exception error)
    {
        _uploadErrorTitle = title; _uploadErrorMessage = ErrorMessage(error);
        RenderUploadNotice(); RenderUploadDetails();
    }

    private void ShowUploadMessage(string title, string message)
    {
        _uploadErrorTitle = title; _uploadErrorMessage = message;
        RenderUploadNotice(); RenderUploadDetails();
    }

    private sealed record UploadOrigin(ConnectionSession Session, RemoteResourceRef Parent, long Generation);

    // Theme resources are resolved by WinUI; the view never controls execution on dismissal.
    private sealed class UploadDetailsView
    {
        public StackPanel Content { get; } = (StackPanel)XamlReader.Load("""
            <StackPanel xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation" xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml" Spacing="16" MinWidth="320" MaxWidth="440">
              <StackPanel Spacing="4"><TextBlock x:Name="Name" FontSize="18" FontWeight="SemiBold" TextWrapping="Wrap"/><TextBlock x:Name="Destination" FontSize="12" TextWrapping="Wrap" IsTextSelectionEnabled="True" Foreground="{ThemeResource TextFillColorSecondaryBrush}"/></StackPanel>
              <Border Padding="16" CornerRadius="10" Background="{ThemeResource FwSurfaceBrush}" BorderBrush="{ThemeResource FwStrokeBrush}" BorderThickness="1"><StackPanel Spacing="8"><TextBlock x:Name="Stage" FontWeight="SemiBold" TextWrapping="Wrap"/><Border x:Name="ProgressTrack" Height="6" CornerRadius="3" Background="{ThemeResource FwStrokeBrush}"><ProgressBar x:Name="Progress" Minimum="0" Maximum="100" Height="4" VerticalAlignment="Center" Background="Transparent" CornerRadius="2" AutomationProperties.Name="上传接收活动进度，完成还需服务器确认与保存"/></Border><TextBlock x:Name="Confirmed" FontSize="12" TextWrapping="Wrap" Foreground="{ThemeResource TextFillColorSecondaryBrush}"/></StackPanel></Border>
              <TextBlock x:Name="Detail" FontSize="12" TextWrapping="Wrap" Foreground="{ThemeResource TextFillColorSecondaryBrush}"/>
              <Button x:Name="Cancel" Content="取消上传…" HorizontalAlignment="Left" Style="{StaticResource FwSecondaryButtonStyle}"/>
              <Border x:Name="CancelConfirmation" Visibility="Collapsed" Padding="12" CornerRadius="10" Background="{ThemeResource FwSurfaceBrush}" BorderThickness="1" BorderBrush="{ThemeResource FwStrokeBrush}"><StackPanel Spacing="10"><TextBlock Text="确认取消这个上传？已传输的数据需要由服务器核实并清理；取消请求不代表远端已取消。" TextWrapping="Wrap"/><StackPanel Orientation="Horizontal" Spacing="8"><Button x:Name="Keep" Content="继续上传" Style="{StaticResource FwSecondaryButtonStyle}"/><Button x:Name="ConfirmCancel" Content="确认取消" Style="{StaticResource FwPrimaryButtonStyle}"/></StackPanel></StackPanel></Border>
            </StackPanel>
            """);
        public UploadDetailsView()
        {
            var resources = Application.Current.Resources;
            if (resources["FwSecondaryButtonStyle"] is Style secondary) { Cancel.Style = secondary; Keep.Style = secondary; }
            if (resources["FwPrimaryButtonStyle"] is Style primary) ConfirmCancel.Style = primary;
        }
        public TextBlock Name => (TextBlock)Content.FindName("Name");
        public TextBlock Destination => (TextBlock)Content.FindName("Destination");
        public TextBlock Stage => (TextBlock)Content.FindName("Stage");
        public ProgressBar Progress => (ProgressBar)Content.FindName("Progress");
        public Border ProgressTrack => (Border)Content.FindName("ProgressTrack");
        public TextBlock Confirmed => (TextBlock)Content.FindName("Confirmed");
        public TextBlock Detail => (TextBlock)Content.FindName("Detail");
        public Button Cancel => (Button)Content.FindName("Cancel");
        public Border CancelConfirmation => (Border)Content.FindName("CancelConfirmation");
        public Button Keep => (Button)Content.FindName("Keep");
        public Button ConfirmCancel => (Button)Content.FindName("ConfirmCancel");
    }
}
