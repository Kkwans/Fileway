using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.System;

namespace Fileway.App;

public sealed partial class MainPage
{
    private bool _fileWriteBusy;
    private ContentDialog? _browserDialog;
    private readonly Dictionary<AccountKey, List<OperationRef>> _pendingWrites = [];
    private readonly HashSet<AccountKey> _untrackedUnknownWrites = [];
    private readonly Dictionary<AccountKey, ServerTaskRef> _submittedTasks = [];
    private readonly Dictionary<AccountKey, (string Label, FileOperationResult Result)> _fileResults = [];
    private readonly Dictionary<AccountKey, HashSet<string>> _receiptWarnings = [];
    private ServerTaskRef? SubmittedTask => _session is { } session && _submittedTasks.TryGetValue(session.Account, out var task) ? task : null;
    private IFileManagementService? FileManagement => _files as IFileManagementService;
    private IOperationHistoryService? Operations => _files as IOperationHistoryService;
    private bool HasUnresolvedWrite => _session is { } session && (_untrackedUnknownWrites.Contains(session.Account) || _pendingWrites.GetValueOrDefault(session.Account)?.Count > 0);
    private bool BrowserReady => !_disposed && !_connecting && _session is not null && _directory is not null && _files is not null && Workspace.Visibility == Visibility.Visible;
    private bool CanWrite(Func<PermissionSet, bool> allowed) => BrowserReady && !LoadingRing.IsActive && !_fileWriteBusy && !HasUnresolvedWrite && allowed(_session!.Permissions);
    private FileRow[] SelectedRows() => ActiveFiles.SelectedItems.Cast<FileRow>().ToArray();
    private bool SameBrowser(ConnectionSession session, RemoteResourceRef directory, long generation) => BrowserReady && _session?.Id == session.Id && _session.Account == session.Account && _directory == directory && _generation == generation;

    private void UpdateBrowserCommands()
    {
        if (NewButton is null) return;
        NewButton.IsEnabled = CanWrite(p => p.Create);
        NewButton.Visibility = _session?.Permissions.Create == true ? Visibility.Visible : Visibility.Collapsed;
        NewFileMenuItem.IsEnabled = FileManagement is not null;
        LastResultsButton.IsEnabled = _session is { } owner && _fileResults.ContainsKey(owner.Account);
        UpdateReceiptWarning();
        if (HasUnresolvedWrite && !Notice.IsOpen) SetNotice("操作结果仍待核实", "当前账号的文件写入已暂停。请核实服务器结果，不要重复提交。", InfoBarSeverity.Warning);
        UpdateOperationAction();
    }
    private MenuFlyout CreateBrowserMenu() { var menu = new MenuFlyout(); PopulateBrowserMenu(menu); return menu; }
    private void PopulateBrowserMenu(MenuFlyout menu)
    {
        menu.Items.Clear();
        var rows = SelectedRows(); var selected = rows.Length > 0; var permissions = _session?.Permissions;
        if (rows.Length == 1) Add("打开", async () => await OpenSelectedAsync(), BrowserReady);
        if (rows.Length == 1 && !rows[0].File.IsDirectory && permissions?.Download == true)
        {
            Add("下载到默认目录…", () => DownloadFile_Click(this, new RoutedEventArgs()), CanDownloadSelection);
            Add("下载另存为…", () => DownloadSaveAs_Click(this, new RoutedEventArgs()), CanDownloadSelection);
        }
        if (rows.Length == 1 && !rows[0].File.IsDirectory && permissions?.Download == true && _files is IFileChecksumService)
            Add("计算校验和…", () => FileChecksum_Click(this, new RoutedEventArgs()), CanChecksumSelection);
        if (selected)
        {
            Add("详细信息", () => { SetDetailsVisible(true); UpdateSelection(); }, BrowserReady);
            if (permissions?.Rename == true)
            {
                if (rows.Length == 1 || FileManagement is not null) Add(rows.Length == 1 ? "重命名" : "批量重命名…", () => Rename_Click(this, new RoutedEventArgs()), CanWrite(p => p.Rename));
                if (FileManagement is not null) Add("移动到…", () => Transfer_Click(FileTransferAction.Move), CanWrite(p => p.Rename));
            }
            if (permissions?.Create == true && FileManagement is not null) Add("复制到…", () => Transfer_Click(FileTransferAction.Copy), CanWrite(p => p.Create));
            if (permissions?.Delete == true) Add("移入回收站…", () => Trash_Click(this, new RoutedEventArgs()), CanWrite(p => p.Delete));
            if (rows.Length == 1 && _collections is not null)
            {
                menu.Items.Add(new MenuFlyoutSeparator());
                Add(CollectionsPanel.IsCurrentResourceFavorite ? "取消收藏" : "收藏", () => FavoriteResource_Click(this, new RoutedEventArgs()), !_connecting && CollectionsPanel.CanManageCurrentResource);
                Add("标记…", () => TagResource_Click(this, new RoutedEventArgs()), !_connecting && CollectionsPanel.CanManageCurrentResource);
            }
            menu.Items.Add(new MenuFlyoutSeparator());
        }
        if (permissions?.Create == true)
        {
            var create = new MenuFlyoutSubItem { Text = "新建", IsEnabled = CanWrite(p => p.Create) };
            var folder = new MenuFlyoutItem { Text = "文件夹…" }; folder.Click += CreateDirectory_Click; create.Items.Add(folder);
            if (FileManagement is not null) { var file = new MenuFlyoutItem { Text = "空文件…" }; file.Click += CreateEmptyFile_Click; create.Items.Add(file); }
            menu.Items.Add(create);
        }
        Add("刷新", () => Refresh_Click(this, new RoutedEventArgs()), BrowserReady);
        Add("全选", () => ActiveFiles.SelectAll(), BrowserReady && _rows.Count > 0);
        var views = new MenuFlyoutSubItem { Text = "视图" };
        foreach (var choice in new[] { ("详细网格", BrowserViewMode.Mosaic), ("紧凑网格", BrowserViewMode.CompactGrid), ("无界网格", BrowserViewMode.WindowsIcons), ("详细列表", BrowserViewMode.Details), ("紧凑列表", BrowserViewMode.CompactList) })
        {
            var item = new MenuFlyoutItem { Text = choice.Item1 };
            item.Click += (_, _) => ChangeBrowserPreferences(_browserPreferences with { ViewMode = choice.Item2 }); views.Items.Add(item);
        }
        menu.Items.Add(views);
        void Add(string label, Action action, bool enabled)
        {
            var item = new MenuFlyoutItem { Text = label, IsEnabled = enabled };
            item.Click += (_, _) => action(); menu.Items.Add(item);
        }
    }

    private void FileResource_RightTapped(object sender, RightTappedRoutedEventArgs args)
    {
        if (sender is not ListViewBase view || IsTextInput(args.OriginalSource as DependencyObject)) return;
        var source = args.OriginalSource as DependencyObject;
        while (source is not null && !ReferenceEquals(source, view) && source is not SelectorItem) source = VisualTreeHelper.GetParent(source);
        if (source is SelectorItem { Content: FileRow row })
        {
            if (!view.SelectedItems.Contains(row)) { view.SelectedItems.Clear(); view.SelectedItem = row; }
        }
        else view.SelectedItems.Clear();
        UpdateSelection();
        var menu = (MenuFlyout)view.ContextFlyout;
        PopulateBrowserMenu(menu); args.Handled = true;
        menu.ShowAt(view, new FlyoutShowOptions { Position = args.GetPosition(view) });
    }
    private void FilesArea_RightTapped(object sender, RightTappedRoutedEventArgs args)
    {
        if (args.Handled || IsTextInput(args.OriginalSource as DependencyObject)) return;
        ActiveFiles.SelectedItems.Clear(); UpdateSelection();
        var menu = (MenuFlyout)FilesArea.ContextFlyout;
        PopulateBrowserMenu(menu); args.Handled = true;
        menu.ShowAt(FilesArea, new FlyoutShowOptions { Position = args.GetPosition(FilesArea) });
    }
    private static bool IsTextInput(DependencyObject? source)
    {
        while (source is not null) { if (source is TextBox or Microsoft.UI.Xaml.Controls.PasswordBox or AutoSuggestBox) return true; source = VisualTreeHelper.GetParent(source); }
        return false;
    }

    private async Task<ContentDialogResult> ShowBrowserDialogAsync(ContentDialog dialog)
    {
        if (_browserDialog is not null) return ContentDialogResult.None;
        dialog.XamlRoot = XamlRoot; dialog.RequestedTheme = Shell.RequestedTheme;
        _browserDialog = dialog;
        try { return await dialog.ShowAsync(); }
        catch (OperationCanceledException) { return ContentDialogResult.None; }
        catch (Exception) { if (!_disposed) SetNotice("对话框未能打开", "请关闭其他对话框后重试。", InfoBarSeverity.Warning); return ContentDialogResult.None; }
        finally { if (ReferenceEquals(_browserDialog, dialog)) _browserDialog = null; }
    }
    private async Task<string?> AskNameAsync(string title, string initial = "")
    {
        var name = new TextBox { Header = "名称", Text = initial, MinWidth = 320, MaxLength = 255 };
        var hint = new TextBlock { Text = "不能包含 /、\\、控制字符，或仅使用 .、..。同名项目会报告冲突。", TextWrapping = TextWrapping.Wrap, MaxWidth = 420 };
        var content = new StackPanel { Spacing = 12 }; content.Children.Add(name); content.Children.Add(hint);
        var dialog = new ContentDialog { Title = title, Content = content, PrimaryButtonText = "确定", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Primary, IsPrimaryButtonEnabled = ValidNewName(initial) };
        name.TextChanged += (_, _) => dialog.IsPrimaryButtonEnabled = ValidNewName(name.Text);
        dialog.Opened += (_, _) => { name.Focus(FocusState.Keyboard); name.SelectAll(); };
        return await ShowBrowserDialogAsync(dialog) == ContentDialogResult.Primary ? name.Text : null;
    }
    private static bool ValidNewName(string name) => !string.IsNullOrWhiteSpace(name) && name.Trim() is not ("." or "..") && !name.Any(c => c is '/' or '\\' || char.IsControl(c));
    private async void CreateDirectory_Click(object sender, RoutedEventArgs args) => await CreateItemAsync(false);
    private async void CreateEmptyFile_Click(object sender, RoutedEventArgs args) => await CreateItemAsync(true);
    private async Task CreateItemAsync(bool emptyFile)
    {
        if (!CanWrite(p => p.Create) || _session is not { } session || _directory is not { } directory || _files is null || emptyFile && FileManagement is null) return;
        var generation = _generation;
        var name = await AskNameAsync(emptyFile ? "新建空文件" : "新建文件夹");
        if (name is null || !SameBrowser(session, directory, generation) || !CanWrite(p => p.Create)) return;
        await RunFileWriteAsync(session, directory, emptyFile ? "新建空文件" : "新建文件夹", () => emptyFile
            ? FileManagement!.CreateEmptyFileAsync(directory, name, _lifetime.Token)
            : _files.CreateDirectoryAsync(directory, name, _lifetime.Token));
    }
    private async void Rename_Click(object sender, RoutedEventArgs args)
    {
        if (!CanWrite(p => p.Rename) || _session is not { } session || _directory is not { } directory || _files is null) return;
        var rows = SelectedRows(); var generation = _generation;
        if (rows.Length == 0) return;
        if (rows.Length > 1) { await RenameBatchAsync(rows, session, directory, generation); return; }
        var resource = rows[0].File.Resource;
        var name = await AskNameAsync("重命名", resource.DisplayName);
        if (name is null || !SameBrowser(session, directory, generation) || !CanWrite(p => p.Rename)) return;
        await RunFileWriteAsync(session, directory, "重命名", () => _files.RenameAsync(resource, name, _lifetime.Token));
    }

    private async Task RenameBatchAsync(FileRow[] rows, ConnectionSession session, RemoteResourceRef directory, long generation)
    {
        if (FileManagement is not { } management || rows.Length > 500) { SetNotice("无法批量重命名", "一次最多选择 500 个同目录项目。", InfoBarSeverity.Warning); return; }
        var fields = rows.Select(row => new TextBox { Header = row.Name, Text = row.Name, MinWidth = 360, MaxLength = 255 }).ToArray();
        var names = new StackPanel { Spacing = 12 }; foreach (var field in fields) names.Children.Add(field);
        var edit = new ContentDialog { Title = $"批量重命名 {rows.Length} 个项目", Content = new ScrollViewer { Content = names, MaxHeight = 360, VerticalScrollBarVisibility = ScrollBarVisibility.Auto }, PrimaryButtonText = "预览", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Close };
        foreach (var field in fields) field.TextChanged += (_, _) => edit.IsPrimaryButtonEnabled = fields.All(f => ValidNewName(f.Text));
        if (await ShowBrowserDialogAsync(edit) != ContentDialogResult.Primary || !SameBrowser(session, directory, generation) || !CanWrite(p => p.Rename)) return;
        _fileWriteBusy = true; UpdateBrowserCommands();
        try
        {
            var draft = new BatchRenameDraft(Guid.NewGuid(), rows.Select((row, index) => new BatchRenameItem(row.File.Resource, fields[index].Text)).ToArray());
            var preview = await management.PreviewBatchRenameAsync(draft, _lifetime.Token);
            if (!SameBrowser(session, directory, generation)) return;
            var summary = new TextBlock { Text = string.Join("\n", preview.Items.Select(item => $"{item.Source.DisplayName} → {item.Target.DisplayName}" + (item.Valid ? "" : $"\n  {item.Error?.Message ?? item.Status ?? "无效"}"))), TextWrapping = TextWrapping.Wrap, MaxWidth = 480 };
            var confirm = new ContentDialog { Title = preview.Valid ? "确认批量重命名" : "请修改命名冲突", Content = new ScrollViewer { Content = summary, MaxHeight = 360 }, PrimaryButtonText = "执行重命名", IsPrimaryButtonEnabled = preview.Valid && preview.Executable is not null, CloseButtonText = "取消", DefaultButton = ContentDialogButton.Close };
            if (await ShowBrowserDialogAsync(confirm) != ContentDialogResult.Primary || preview.Executable is not { } executable || !SameBrowser(session, directory, generation) || _session?.Permissions.Rename != true || HasUnresolvedWrite) return;
            var result = await management.ExecuteBatchRenameAsync(executable, _lifetime.Token);
            var items = result.Items.Select(item => new FileOperationItemResult(item.Source, result.Executed && item.Status == "completed" ? FileOperationItemOutcome.Confirmed : result.Outcome == OperationOutcome.OutcomeUnknown ? FileOperationItemOutcome.OutcomeUnknown : item.Error is not null ? FileOperationItemOutcome.Failed : FileOperationItemOutcome.NotAttempted, item.Error, result.Receipt)).ToArray();
            await PresentFileWriteAsync(session, directory, generation, "批量重命名", new FileOperationResult(result.Outcome, Error: result.Error, Items: items, Receipt: result.Receipt));
        }
        catch (Exception error) { PresentWriteException(session, directory, generation, "批量重命名", error); }
        finally { _fileWriteBusy = false; UpdateBrowserCommands(); }
    }

    private async void Trash_Click(object sender, RoutedEventArgs args)
    {
        if (!CanWrite(p => p.Delete) || _session is not { } session || _directory is not { } directory || _files is null) return;
        var resources = SelectedRows().Select(row => row.File.Resource).ToArray(); var generation = _generation;
        if (resources.Length == 0) return;
        var names = string.Join("\n", resources.Take(8).Select(resource => resource.DisplayName));
        var confirm = new ContentDialog { Title = $"将 {resources.Length} 个项目移入回收站？", Content = $"{names}{(resources.Length > 8 ? "\n…" : "")}\n\n文件将移入当前账号的服务器回收站。", PrimaryButtonText = "移入回收站", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Close };
        if (await ShowBrowserDialogAsync(confirm) != ContentDialogResult.Primary || !SameBrowser(session, directory, generation) || !CanWrite(p => p.Delete)) return;
        await RunFileWriteAsync(session, directory, "移入回收站", () => _files.TrashAsync(resources, _lifetime.Token));
    }

    private async Task RunFileWriteAsync(ConnectionSession session, RemoteResourceRef directory, string label, Func<Task<FileOperationResult>> write)
    {
        var generation = _generation;
        _fileWriteBusy = true; UpdateBrowserCommands();
        try { await PresentFileWriteAsync(session, directory, generation, label, await write()); }
        catch (Exception error) { PresentWriteException(session, directory, generation, label, error); }
        finally { _fileWriteBusy = false; UpdateBrowserCommands(); }
    }
    private async Task PresentFileWriteAsync(ConnectionSession session, RemoteResourceRef directory, long generation, string label, FileOperationResult result)
    {
        _fileResults[session.Account] = (label, result with { Items = result.Items?.ToArray() });
        ConsumeReceipt(session.Account, result.Receipt);
        foreach (var item in result.Items ?? []) ConsumeReceipt(session.Account, item.Receipt);
        var unknownItems = result.Items?.Where(item => item.Outcome == FileOperationItemOutcome.OutcomeUnknown).ToArray() ?? [];
        if (result.Outcome == OperationOutcome.OutcomeUnknown)
        {
            var receipts = unknownItems.Select(item => item.Receipt).Append(result.Receipt).OfType<OperationReceipt>().Select(receipt => new OperationRef(session.Account, receipt.OperationId)).Distinct().ToArray();
            TrackUnknown(session.Account, receipts);
        }
        if (!await RefreshOriginAsync(session, directory, generation)) return;
        var message = result.Outcome switch
        {
            OperationOutcome.Confirmed => $"{label}已确认。",
            OperationOutcome.Partial => "操作部分完成。请核对以下逐项结果后处理未完成项目。",
            _ => "操作结果尚未确认，已暂停当前账号的文件写入。请先核实结果，不要重复提交。",
        };
        if (result.Items is { Count: > 0 } items)
        {
            message += "\n" + string.Join("\n", items.Take(12).Select(item => $"{item.Resource.DisplayName}：{ItemOutcomeLabel(item.Outcome)}{(item.Error is null ? "" : " · " + item.Error.Message)}"));
            if (items.Count > 12) message += $"\n另有 {items.Count - 12} 项；从命令栏‘上次操作的完整结果’查看全部项目。";
        }
        if (result.Error is not null) message += "\n" + result.Error.Message;
        SetNotice(label, message, result.Outcome == OperationOutcome.Confirmed ? InfoBarSeverity.Success : InfoBarSeverity.Warning);
        UpdateOperationAction();
    }
    private static string ItemOutcomeLabel(FileOperationItemOutcome outcome) => outcome switch { FileOperationItemOutcome.Confirmed => "已确认", FileOperationItemOutcome.Failed => "失败", FileOperationItemOutcome.NotAttempted => "未执行", _ => "结果未知" };
    private void PresentWriteException(ConnectionSession session, RemoteResourceRef directory, long generation, string label, Exception error)
    {
        if (error is FilewayException { Error.Code: ErrorCode.OutcomeUnknown }) TrackUnknown(session.Account, []);
        if (!SameBrowser(session, directory, generation)) return;
        var message = error is OperationCanceledException ? "操作已取消。" : ErrorMessage(error);
        var unknown = error is FilewayException { Error.Code: ErrorCode.OutcomeUnknown };
        SetNotice(label + (unknown ? "结果未知" : "未完成"), message, InfoBarSeverity.Warning); UpdateOperationAction();
    }
    private void TrackUnknown(AccountKey account, IEnumerable<OperationRef> operations)
    {
        var references = operations.ToArray();
        if (references.Length == 0) { _untrackedUnknownWrites.Add(account); return; }
        if (!_pendingWrites.TryGetValue(account, out var pending)) _pendingWrites[account] = pending = [];
        foreach (var operation in references) if (!pending.Contains(operation)) pending.Add(operation);
    }

    private async Task<bool> RefreshOriginAsync(ConnectionSession session, RemoteResourceRef directory, long generation)
    {
        if (!SameBrowser(session, directory, generation)) return false;
        var refresh = LoadAsync(directory);
        var refreshGeneration = _generation;
        await refresh;
        return SameBrowser(session, directory, refreshGeneration);
    }
    private void ConsumeReceipt(AccountKey account, OperationReceipt? receipt)
    {
        if (receipt?.PersistenceWarning is { } warning) RememberReceiptWarning(account, warning.Message);
        else if (receipt is { AckPersisted: false }) RememberReceiptWarning(account, "本机操作记录未能完整保存。");
    }
    private void RememberReceiptWarning(AccountKey account, string message)
    {
        if (!_receiptWarnings.TryGetValue(account, out var warnings)) _receiptWarnings[account] = warnings = [];
        warnings.Add(message); UpdateReceiptWarning();
    }
    private void UpdateReceiptWarning()
    {
        if (ReceiptWarning is null) return;
        var warnings = _session is { } session ? _receiptWarnings.GetValueOrDefault(session.Account) : null;
        ReceiptWarning.IsOpen = warnings is { Count: > 0 };
        ReceiptWarning.Message = warnings is null ? "" : "此提示不改变服务器已返回的操作结果，请勿因此重复提交。\n" + string.Join("\n", warnings);
    }
    private async void ResultDetails_Click(object sender, RoutedEventArgs args)
    {
        if (_session is not { } session || !_fileResults.TryGetValue(session.Account, out var saved)) return;
        var text = saved.Result.Items is { Count: > 0 } items
            ? string.Join("\n\n", items.Select(item => $"{item.Resource.DisplayPath}\n{ItemOutcomeLabel(item.Outcome)}{(item.Error is null ? "" : " · " + item.Error.Message)}"))
            : saved.Result.Error?.Message ?? (saved.Result.Outcome == OperationOutcome.Confirmed ? "操作已确认。" : "操作结果仍需核实。");
        await ShowBrowserDialogAsync(new ContentDialog { Title = saved.Label + " · 完整逐项结果", CloseButtonText = "关闭", Content = new ScrollViewer { MaxHeight = 420, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled, Content = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true, MaxWidth = 520 } } });
    }

    private async void Transfer_Click(FileTransferAction action)
    {
        bool Allowed(PermissionSet permissions) => action == FileTransferAction.Copy ? permissions.Create : permissions.Rename;
        if (!CanWrite(Allowed) || _session is not { } session || _directory is not { } directory || FileManagement is not { } management || _files is null) return;
        var resources = SelectedRows().Select(row => row.File.Resource).ToArray(); var generation = _generation;
        if (resources.Length == 0) return;
        var path = new TextBox { Header = "目标文件夹的原始路径", Text = directory.WirePath, MinWidth = 360 };
        var conflicts = new ComboBox { Header = "同名项目", HorizontalAlignment = HorizontalAlignment.Stretch, SelectedIndex = 0 };
        foreach (var label in new[] { "保留两者（自动改名）", "遇到冲突停止", "跳过同名项目", "替换同名项目" }) conflicts.Items.Add(label);
        var explanation = new TextBlock { Text = $"已选择 {resources.Length} 项。目标会在提交前向服务器核实。复制或移动由服务器任务执行，提交接受不表示完成。", TextWrapping = TextWrapping.Wrap, MaxWidth = 460 };
        var content = new StackPanel { Spacing = 16 }; content.Children.Add(path); content.Children.Add(conflicts); content.Children.Add(explanation);
        var dialog = new ContentDialog { Title = action == FileTransferAction.Copy ? "复制到" : "移动到", Content = content, PrimaryButtonText = "提交任务", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Close };
        if (await ShowBrowserDialogAsync(dialog) != ContentDialogResult.Primary || !SameBrowser(session, directory, generation) || !CanWrite(Allowed)) return;
        if (!IsValidRawPath(path.Text)) { SetNotice("目标路径无效", "请输入绝对原始路径并保留百分号编码。", InfoBarSeverity.Warning); return; }
        var conflict = conflicts.SelectedIndex switch { 1 => FileConflictPolicy.Fail, 2 => FileConflictPolicy.Skip, 3 => FileConflictPolicy.Replace, _ => FileConflictPolicy.KeepBoth };
        if (conflict == FileConflictPolicy.Replace)
        {
            var replace = new ContentDialog { Title = "允许替换目标中的同名项目？", Content = "目标中同名文件可能被覆盖。请确认目标目录与所选项目。", PrimaryButtonText = "允许替换", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Close };
            if (await ShowBrowserDialogAsync(replace) != ContentDialogResult.Primary || !SameBrowser(session, directory, generation) || !CanWrite(Allowed)) return;
        }
        _fileWriteBusy = true; UpdateBrowserCommands();
        try
        {
            var destination = await _files.GetAsync(new RemoteResourceRef(session.Account, path.Text, path.Text, path.Text), _lifetime.Token);
            if (!SameBrowser(session, directory, generation) || _session is null || !Allowed(_session.Permissions) || HasUnresolvedWrite) return;
            if (!destination.IsDirectory) { SetNotice("目标不是文件夹", "请选择服务器上的文件夹原始路径。", InfoBarSeverity.Warning); return; }
            var transferItems = resources.Select(resource => new FileTransferItem(resource, conflict)).ToArray();
            var skipped = 0;
            if (conflict == FileConflictPolicy.Skip)
            {
                var targets = resources.Select(resource =>
                {
                    var wire = (destination.Resource.WirePath == "/" ? "" : destination.Resource.WirePath) + resource.WirePath[resource.WirePath.LastIndexOf('/')..];
                    return new RemoteResourceRef(session.Account, wire, resource.DisplayName, wire);
                }).ToArray();
                var metadata = await management.GetMetadataBatchAsync(targets, _lifetime.Token);
                if (!SameBrowser(session, directory, generation) || _session is null || !Allowed(_session.Permissions) || HasUnresolvedWrite) return;
                if (metadata.Count != targets.Length) throw new FilewayException(new ErrorInfo(ErrorCode.ProtocolMismatch, "目标元数据数量不匹配，未提交任务。"));
                for (var index = 0; index < targets.Length; index++)
                {
                    var target = metadata[index];
                    if (target.Requested != targets[index]) throw new FilewayException(new ErrorInfo(ErrorCode.ProtocolMismatch, "目标元数据身份不匹配，未提交任务。"));
                    if (target.File is not null) { transferItems[index] = new FileTransferItem(resources[index], FileConflictPolicy.Skip); skipped++; }
                    else if (target.Error?.HttpStatus == 404) transferItems[index] = new FileTransferItem(resources[index], FileConflictPolicy.Fail);
                    else throw new FilewayException(target.Error ?? new ErrorInfo(ErrorCode.ProtocolMismatch, "无法确认目标是否存在，未提交任务。"));
                }
                if (skipped == resources.Length) { SetNotice("未创建任务", $"{skipped} 项均已跳过，未创建服务器任务。", InfoBarSeverity.Informational); return; }
            }
            var result = await management.SubmitTransferAsync(new FileTransferRequest(action, destination.Resource, transferItems), _lifetime.Token);
            ConsumeReceipt(session.Account, result.Receipt);
            if (result.Outcome == ServerTaskSubmissionOutcome.Accepted && result.AcceptedTask is { } accepted) _submittedTasks[session.Account] = accepted.Reference;
            if (result.Outcome == ServerTaskSubmissionOutcome.OutcomeUnknown) TrackUnknown(session.Account, result.Receipt is { } receipt ? [new OperationRef(session.Account, receipt.OperationId)] : []);
            if (!SameBrowser(session, directory, generation)) return;
            if (result.Outcome == ServerTaskSubmissionOutcome.Accepted && result.AcceptedTask is { } task)
            {
                SetNotice("已提交服务器任务", $"{(action == FileTransferAction.Copy ? "复制" : "移动")}已提交 {resources.Length - skipped} 项，跳过 {skipped} 项；尚未确认完成。当前状态：{TaskStatusLabel(task.Status)}。" + (conflict == FileConflictPolicy.Skip ? " 核实后新出现的同名冲突仍会被拒绝，请查看任务状态。" : ""), InfoBarSeverity.Informational);
            }
            else SetNotice(result.Outcome == ServerTaskSubmissionOutcome.OutcomeUnknown ? "任务提交结果未知" : "任务未提交", (result.Error?.Message ?? "服务器未确认任务接受。") + (result.Outcome == ServerTaskSubmissionOutcome.OutcomeUnknown ? " 请先核实，不要重复提交。" : ""), InfoBarSeverity.Warning);
            UpdateOperationAction();
        }
        catch (Exception error) { PresentWriteException(session, directory, generation, action == FileTransferAction.Copy ? "复制" : "移动", error); }
        finally { _fileWriteBusy = false; UpdateBrowserCommands(); }
    }

    private void ResetOperationContext() { OperationActionButton.Visibility = Visibility.Collapsed; UpdateReceiptWarning(); }
    private void UpdateOperationAction()
    {
        var canReconcile = _session is { } session && _pendingWrites.GetValueOrDefault(session.Account)?.Count > 0 && Operations is not null;
        OperationActionButton.Content = canReconcile ? "核实结果" : "查看任务状态";
        OperationActionButton.Visibility = canReconcile || SubmittedTask is { } task && task.Account == _session?.Account && _files is IServerTaskService ? Visibility.Visible : Visibility.Collapsed;
        OperationActionButton.IsEnabled = !_fileWriteBusy && !_connecting;
    }
    private async void OperationAction_Click(object sender, RoutedEventArgs args)
    {
        if (_session is not { } session || _directory is not { } directory || _fileWriteBusy || _connecting) return;
        var generation = _generation;
        _fileWriteBusy = true; UpdateBrowserCommands();
        try
        {
            if (_pendingWrites.GetValueOrDefault(session.Account) is { Count: > 0 } pending && Operations is { } operations)
            {
                var messages = new List<string>();
                foreach (var reference in pending.ToArray())
                {
                    var result = await operations.ReconcileAsync(reference, _lifetime.Token);
                    if (result.Error is null && result.Resolution is (OperationResolution.Confirmed or OperationResolution.NotSent or OperationResolution.ObservedDesiredState or OperationResolution.ObservedTaskState)) pending.Remove(reference);
                    messages.Add(result.Resolution switch { OperationResolution.Confirmed => "服务器操作已确认。", OperationResolution.NotSent => "已核实请求未发送。", OperationResolution.ObservedDesiredState => "已观察到目标状态；请按当前目录结果继续。", OperationResolution.ObservedTaskState => "已查到服务器任务状态。", _ => result.Error?.Message ?? "仍无法确认结果，需要人工核对服务器。" });
                }
                if (!await RefreshOriginAsync(session, directory, generation)) return;
                SetNotice(pending.Count == 0 ? "核实完成" : "结果仍待核实", string.Join("\n", messages), pending.Count == 0 ? InfoBarSeverity.Informational : InfoBarSeverity.Warning);
            }
            else if (SubmittedTask is { } task && task.Account == session.Account && _files is IServerTaskService tasks)
            {
                var state = await tasks.GetTaskAsync(task, _lifetime.Token);
                if (!SameBrowser(session, directory, generation)) return;
                if (state.Status == ServerTaskStatus.Completed && !await RefreshOriginAsync(session, directory, generation)) return;
                SetNotice("服务器任务", $"{TaskStatusLabel(state.Status)} · {state.ProcessedItems}/{state.TotalItems} 项" + (state.Error is null ? "" : "\n" + state.Error.Message), state.Status == ServerTaskStatus.Completed ? InfoBarSeverity.Success : state.Status is ServerTaskStatus.Failed or ServerTaskStatus.Interrupted ? InfoBarSeverity.Warning : InfoBarSeverity.Informational);
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception error) { if (SameBrowser(session, directory, generation)) SetNotice("无法核实结果", ErrorMessage(error), InfoBarSeverity.Warning); }
        finally { _fileWriteBusy = false; UpdateBrowserCommands(); }
    }
    private static string TaskStatusLabel(ServerTaskStatus status) => status switch { ServerTaskStatus.Queued => "排队中", ServerTaskStatus.Running => "执行中", ServerTaskStatus.Completed => "已完成", ServerTaskStatus.Failed => "失败", ServerTaskStatus.Canceled => "已取消", ServerTaskStatus.Interrupted => "已中断", _ => "状态未知" };
}
