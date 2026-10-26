using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App.Controls.StorageTools;

/// <summary>
/// Server-side storage analysis panel: one bounded scan task per run, polled through the server's
/// own task status, with the completed report rendered from server-provided numbers only.
/// </summary>
public sealed class StorageAnalysisControl : UserControl, IDisposable
{
    private readonly CancellationTokenSource _lifetime = new();
    private readonly CancellationToken _token;
    private bool _disposed;
    private bool _contextAssigned;
    private ContentDialog? _activeDialog;
    private long _adminReadRevision;
    private readonly TextBox _scopeBox = new() { Header = "分析范围（服务器路径）", IsReadOnly = true };
    private readonly Button _start = new() { Content = "开始分析" };
    private readonly InfoBar _notice = new() { IsOpen = false, IsClosable = true, Severity = InfoBarSeverity.Informational };
    private readonly ProgressRing _progress = new() { IsActive = false, Visibility = Visibility.Collapsed };
    private readonly StackPanel _results = new() { Spacing = 10 };
    private RemoteResourceRef? _directory;
    private bool _busy;
    private IDuplicateAnalysisService? _duplicates;
    private string? _duplicateReportId;
    private ServerTaskRef? _duplicateTask;
    private readonly StackPanel _duplicatesRoot = new() { Spacing = 12 };
    private readonly TextBox _duplicateScopeBox = new() { Header = "扫描范围（服务器路径）", IsReadOnly = true };
    private readonly Button _duplicateStart = new() { Content = "开始扫描" };
    private readonly ScrollViewer _duplicateScroll = new() { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, MaxHeight = 560 };
    private readonly StackPanel _duplicateResults = new() { Spacing = 10 };
    private readonly InfoBar _duplicateNotice = new() { IsOpen = false, IsClosable = true };
    private readonly Dictionary<string, List<RadioButton>> _keepChoices = [];
    private IAdminUserService? _adminUsers;
    private bool _isAdmin;
    private readonly RadioButton _adminTabButton = new() { Content = "用户管理", Visibility = Visibility.Collapsed };
    private readonly Grid _adminHost = new();
    private readonly StackPanel _adminRoot = new() { Spacing = 12 };
    private readonly InfoBar _adminNotice = new() { IsOpen = false, IsClosable = true, Title = "服务器用户管理" };
    private readonly ListView _adminList = new() { SelectionMode = ListViewSelectionMode.Single, MaxHeight = 460 };
    private readonly Button _adminCreate = new() { Content = "新建用户…" };
    private readonly Button _adminPassword = new() { Content = "重置密码…", IsEnabled = false };
    private readonly Button _adminToggleAdmin = new() { Content = "切换管理员", IsEnabled = false };
    private readonly Button _adminToggleLock = new() { Content = "切换密码锁定", IsEnabled = false };
    private readonly Button _adminDelete = new() { Content = "删除用户…", IsEnabled = false };
    private List<AdminUser> _adminUsersCache = [];
    private readonly RadioButton _analysisTabButton = new() { Content = "容量分析" };
    private readonly RadioButton _duplicatesTabButton = new() { Content = "重复文件" };

    public StorageAnalysisControl()
    {
        _token = _lifetime.Token;
        AutomationProperties.SetAutomationId(this, "StorageAnalysisView");
        foreach (var (element, id) in new (DependencyObject, string)[] {
            (_start, "StorageAnalysisStart"), (_notice, "StorageAnalysisNotice"),
            (_duplicateStart, "StorageDuplicatesStart"), (_analysisTabButton, "StorageAnalysisTab"),
            (_duplicateNotice, "StorageDuplicatesNotice"),
            (_duplicatesTabButton, "StorageDuplicatesTab"), (_adminTabButton, "StorageAdminTab"),
            (_adminList, "StorageAdminUsers"), (_adminCreate, "StorageAdminCreate"),
            (_adminNotice, "StorageAdminNotice"),
            (_adminPassword, "StorageAdminPassword"), (_adminToggleAdmin, "StorageAdminToggleAdmin"),
            (_adminToggleLock, "StorageAdminToggleLock"), (_adminDelete, "StorageAdminDelete") })
            AutomationProperties.SetAutomationId(element, id);
        var analysisRoot = new StackPanel { Spacing = 12 };
        analysisRoot.Children.Add(new TextBlock { Text = "由服务器统计范围内的文件数、目录数与总大小，并列出最大的文件与目录；分析在服务器后台执行，客户端只呈现服务器给出的数字。", TextWrapping = TextWrapping.Wrap, Foreground = new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.Colors.Gray) });
        analysisRoot.Children.Add(_scopeBox);
        _start.Click += async (_, _) => await StartAsync();
        analysisRoot.Children.Add(_start);
        analysisRoot.Children.Add(_notice);
        analysisRoot.Children.Add(_progress);
        analysisRoot.Children.Add(_results);

        _duplicateStart.Click += async (_, _) => await StartDuplicateScanAsync();
        _duplicatesRoot.Children.Add(new TextBlock { Text = "服务器按内容找出重复文件并给出保留建议；勾选每组要保留的副本后提交，其余副本移入回收站（可恢复）。绝不直接删除，也不会改动组外文件。", TextWrapping = TextWrapping.Wrap, Foreground = new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.Colors.Gray) });
        _duplicatesRoot.Children.Add(_duplicateScopeBox);
        _duplicatesRoot.Children.Add(_duplicateStart);
        _duplicatesRoot.Children.Add(_duplicateNotice);
        _duplicateScroll.Content = _duplicateResults;
        _duplicatesRoot.Children.Add(_duplicateScroll);

        var analysisHost = new Grid();
        analysisHost.Children.Add(analysisRoot);
        var duplicatesHost = new Grid { Visibility = Visibility.Collapsed };
        duplicatesHost.Children.Add(_duplicatesRoot);
        var adminHost = new Grid { Visibility = Visibility.Collapsed };
        BuildAdminRoot();
        adminHost.Children.Add(_adminRoot);

        var root = new StackPanel { Spacing = 12, Padding = new Thickness(24, 18, 24, 24), MaxWidth = 920, HorizontalAlignment = HorizontalAlignment.Stretch };
        root.Children.Add(new TextBlock { Text = "存储工具", FontSize = 20, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
        var tabs = new RadioButtons { SelectedIndex = 0 };
        tabs.Items.Add(_analysisTabButton);
        tabs.Items.Add(_duplicatesTabButton);
        tabs.Items.Add(_adminTabButton);
        tabs.SelectionChanged += (_, _) =>
        {
            int selected = tabs.SelectedIndex;
            analysisHost.Visibility = selected == 0 ? Visibility.Visible : Visibility.Collapsed;
            duplicatesHost.Visibility = selected == 1 ? Visibility.Visible : Visibility.Collapsed;
            adminHost.Visibility = selected == 2 ? Visibility.Visible : Visibility.Collapsed;
            if (selected == 2) _ = RefreshAdminAsync();
        };
        root.Children.Add(tabs);
        root.Children.Add(analysisHost);
        root.Children.Add(duplicatesHost);
        root.Children.Add(adminHost);
        Content = new ScrollViewer { Content = root, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
    }

    public void SetContext(RemoteResourceRef? directory)
    {
        if (_contextAssigned) throw new InvalidOperationException("Storage views cannot be rebound to another context.");
        _contextAssigned = true;
        _directory = directory;
        _scopeBox.Text = directory?.DisplayPath ?? "连接后可选择要分析的目录";
        _duplicateScopeBox.Text = _scopeBox.Text;
        SetBusy(false);
        _duplicateReportId = null;
        _duplicateTask = null;
        _keepChoices.Clear();
        _duplicateResults.Children.Clear();
        if (directory is null) { _results.Children.Clear(); _notice.IsOpen = false; }
    }

    private void SetBusy(bool busy)
    {
        _busy = busy;
        bool active = !_token.IsCancellationRequested && _directory is not null && !busy;
        _start.IsEnabled = active && _service is not null;
        _duplicateStart.IsEnabled = active && _duplicates is not null;
        UpdateAdminButtons();
    }

    private void SetNotice(string message, InfoBarSeverity severity)
    {
        _notice.Message = message;
        _notice.Severity = severity;
        _notice.IsOpen = true;
    }


    private async Task StartDuplicateScanAsync()
    {
        if (_busy || _duplicates is null || _lifetime.IsCancellationRequested) return;
        if (_directory is not { } directory) return;
        SetBusy(true);
        _duplicateStart.IsEnabled = false;
        _duplicateResults.Children.Clear();
        _keepChoices.Clear();
        try
        {
            var task = await _duplicates.StartScanAsync(directory, _token);
            _duplicateReportId = task.Id;
            _duplicateTask = task;
            AddDuplicateStatus("扫描任务已提交，等待服务器完成…");
            var deadline = System.Diagnostics.Stopwatch.GetTimestamp() + 900L * System.Diagnostics.Stopwatch.Frequency;
            while (true)
            {
                _token.ThrowIfCancellationRequested();
                var current = await _duplicates.GetTaskAsync(task, _token);
                if (current.Status is ServerTaskStatus.Completed or ServerTaskStatus.Failed or ServerTaskStatus.Canceled or ServerTaskStatus.Interrupted)
                {
                    if (current.Status != ServerTaskStatus.Completed)
                        throw new FilewayException(new ErrorInfo(ErrorCode.Unknown,
                            $"扫描未完成（{current.Status}）" + (current.Error is { } taskError ? "：" + taskError.Message : "")));
                    break;
                }
                if (System.Diagnostics.Stopwatch.GetTimestamp() >= deadline)
                    throw new FilewayException(new ErrorInfo(ErrorCode.Timeout, "扫描超过 15 分钟仍未完成，请稍后再试。"));
                await Task.Delay(TimeSpan.FromSeconds(1.5), _token);
            }
            var report = await _duplicates.GetReportAsync(task, _token)
                ?? throw new FilewayException(new ErrorInfo(ErrorCode.OutcomeUnknown, "服务器尚未发布扫描结果，请稍后重试。"));
            RenderDuplicateReport(report);
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            AddDuplicateStatus(error is FilewayException fileway ? fileway.Error.Message : "重复扫描未能完成，请检查连接后重试。", InfoBarSeverity.Error);
        }
        finally { SetBusy(false); }
    }

    private void RenderDuplicateReport(DuplicateReport report)
    {
        AddDuplicateStatus($"扫描完成：{report.ScannedFiles:N0} 个文件 · {FormatBytes(report.ScannedBytes)} · " +
            $"{report.DuplicateGroups} 组重复 · 可回收 {FormatBytes(report.ReclaimableBytes)}" +
            (report.Truncated ? "（结果被服务器截断）" : "") + (report.SkippedCount > 0 ? $" · {report.SkippedCount} 项跳过" : ""),
            report.DuplicateGroups > 0 ? InfoBarSeverity.Warning : InfoBarSeverity.Success);
        if (report.Groups.Count == 0) return;
        var submit = new Button { Content = "提交清理（移入回收站）", HorizontalAlignment = HorizontalAlignment.Left };
        AutomationProperties.SetAutomationId(submit, "StorageDuplicateCleanupTop");
        submit.Click += async (_, _) => await SubmitCleanupAsync(report);
        _duplicateResults.Children.Add(submit);
        for (int groupIndex = 0; groupIndex < report.Groups.Count; groupIndex++)
        {
            var group = report.Groups[groupIndex];
            var card = new StackPanel { Spacing = 4 };
            card.Children.Add(new TextBlock
            {
                Text = $"第 {groupIndex + 1} 组 · 每个副本 {FormatBytes(group.Size)} · 可回收 {FormatBytes(group.ReclaimableBytes)}" +
                    (group.KeepReason is { } reason ? $" · 服务器建议保留：{KeepReasonLabel(reason)}" : ""),
                FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap,
            });
            var choices = new List<RadioButton>();
            foreach (var file in group.Files)
            {
                var choice = new RadioButton
                {
                    GroupName = "keep-" + group.Sha256,
                    Content = file.Path,
                    IsChecked = group.SuggestedKeepPath is { } suggested
                        ? string.Equals(file.Path, suggested, StringComparison.Ordinal)
                        : file == group.Files[0],
                };
                choices.Add(choice);
                card.Children.Add(choice);
            }
            _keepChoices[group.Sha256] = choices;
            _duplicateResults.Children.Add(new Border
            {
                Padding = new Thickness(10, 8, 10, 8),
                CornerRadius = new CornerRadius(6),
                Background = (Microsoft.UI.Xaml.Media.Brush)Microsoft.UI.Xaml.Application.Current.Resources["LayerFillColorDefaultBrush"],
                Child = card,
            });
        }
        var confirmBar = new Button { Content = "提交清理（移入回收站）", HorizontalAlignment = HorizontalAlignment.Left };
        AutomationProperties.SetAutomationId(confirmBar, "StorageDuplicateCleanupBottom");
        confirmBar.Click += async (_, _) => await SubmitCleanupAsync(report);
        _duplicateResults.Children.Add(confirmBar);
    }

    private static string KeepReasonLabel(string reason) => reason switch
    {
        "oldest-created" => "最早的创建时间",
        "missing-created" => "缺少创建时间",
        "tied-created" => "创建时间相同",
        _ => reason,
    };

    private async Task SubmitCleanupAsync(DuplicateReport report)
    {
        if (_duplicates is null || _duplicateReportId is not { } reportId || _busy) return;
        var selections = new List<DuplicateCleanupSelection>();
        foreach (var group in report.Groups)
        {
            if (!_keepChoices.TryGetValue(group.Sha256, out var choices)) continue;
            var keep = choices.FirstOrDefault(choice => choice.IsChecked == true);
            if (keep is null) { AddDuplicateStatus($"第 {group.Sha256[..8]}… 组未选择保留副本，已跳过。", InfoBarSeverity.Warning); continue; }
            selections.Add(new(group.Sha256, keep.Content.ToString()!));
        }
        if (selections.Count == 0) return;
        long reclaimable = report.Groups.Where(group => selections.Any(selection => selection.Sha256 == group.Sha256))
            .Sum(group => group.ReclaimableBytes);
        var panel = new StackPanel { Spacing = 10, MinWidth = 320, MaxWidth = 440 };
        panel.Children.Add(new TextBlock { Text = $"将 {selections.Count} 组重复文件中未保留的副本移入回收站？可回收 {FormatBytes(reclaimable)}。", TextWrapping = TextWrapping.Wrap });
        panel.Children.Add(new TextBlock { Text = "清理只动所选组内未保留的副本，经回收站可恢复；保留的副本与组外文件不受影响。", FontSize = 12, Opacity = 0.75, TextWrapping = TextWrapping.Wrap });
        var confirm = new ContentDialog { Title = "提交清理", Content = panel, PrimaryButtonText = "移入回收站", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Primary };
        if (await ShowDialogAsync(confirm) != ContentDialogResult.Primary || _token.IsCancellationRequested) return;
        SetBusy(true);
        _duplicateStart.IsEnabled = false;
        AddDuplicateStatus("正在提交清理任务…", InfoBarSeverity.Informational);
        try
        {
            var cleanupTask = await _duplicates.StartCleanupAsync(_directory!.Account, reportId, selections, _token);
            AddDuplicateStatus("清理任务已提交，等待服务器逐文件执行…", InfoBarSeverity.Informational);
            var deadline = System.Diagnostics.Stopwatch.GetTimestamp() + 600L * System.Diagnostics.Stopwatch.Frequency;
            while (true)
            {
                _token.ThrowIfCancellationRequested();
                var result = await _duplicates.GetCleanupResultAsync(cleanupTask, _token);
                if (result is { } finished)
                {
                    int success = 0, skipped = 0, failed = 0;
                    foreach (var (_, _, files) in finished.Groups)
                        foreach (var file in files)
                            switch (file.Status) { case "success": success++; break; case "skipped": skipped++; break; default: failed++; break; }
                    AddDuplicateStatus($"清理完成：{success} 个移入回收站 · {skipped} 个跳过 · {failed} 个失败。可在回收站中恢复任何条目。",
                        failed > 0 ? InfoBarSeverity.Warning : InfoBarSeverity.Success);
                    break;
                }
                var current = await _duplicates.GetTaskAsync(cleanupTask, _token);
                if (current.Status is ServerTaskStatus.Failed or ServerTaskStatus.Canceled or ServerTaskStatus.Interrupted)
                    throw new FilewayException(current.Error ?? new ErrorInfo(ErrorCode.Unknown,
                        $"清理任务未完成（{current.Status}），请在文件任务中查看；不会重新提交。"));
                if (System.Diagnostics.Stopwatch.GetTimestamp() >= deadline)
                    throw new FilewayException(new ErrorInfo(ErrorCode.Timeout, "清理超过 10 分钟仍未完成，请稍后在文件任务中查看结果。"));
                await Task.Delay(TimeSpan.FromSeconds(1.5), _token);
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            AddDuplicateStatus(error is FilewayException fileway ? fileway.Error.Message : "清理未能完成，请检查连接后重试。", InfoBarSeverity.Error);
        }
        finally { SetBusy(false); }
    }

    private void AddDuplicateStatus(string message, InfoBarSeverity severity = InfoBarSeverity.Informational)
    {
        if (_token.IsCancellationRequested) return;
        _duplicateNotice.Message = message;
        _duplicateNotice.Severity = severity;
        _duplicateNotice.IsOpen = true;
    }

    private void BuildAdminRoot()
    {
        _adminRoot.Children.Add(new TextBlock { Text = "服务器用户管理：列表、创建、重置密码、权限与删除。每次改动需要输入管理员（当前账户）密码以确认身份；所有操作由服务器校验权限。", TextWrapping = TextWrapping.Wrap, Foreground = new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.Colors.Gray) });
        _adminRoot.Children.Add(_adminNotice);
        Fileway.App.Controls.FloatingNotice.Attach(_adminNotice);
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        _adminCreate.Click += async (_, _) => await CreateAdminUserAsync();
        _adminPassword.Click += async (_, _) => await MutateSelectedAsync(MutationKind.ResetPassword);
        _adminToggleAdmin.Click += async (_, _) => await MutateSelectedAsync(MutationKind.ToggleAdmin);
        _adminToggleLock.Click += async (_, _) => await MutateSelectedAsync(MutationKind.ToggleLock);
        _adminDelete.Click += async (_, _) => await MutateSelectedAsync(MutationKind.Delete);
        actions.Children.Add(_adminCreate);
        actions.Children.Add(_adminPassword);
        actions.Children.Add(_adminToggleAdmin);
        actions.Children.Add(_adminToggleLock);
        actions.Children.Add(_adminDelete);
        _adminRoot.Children.Add(new ScrollViewer { Content = actions, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollMode = ScrollMode.Auto, VerticalScrollBarVisibility = ScrollBarVisibility.Disabled, VerticalScrollMode = ScrollMode.Disabled });
        _adminList.SelectionChanged += (_, _) => UpdateAdminButtons();
        _adminRoot.Children.Add(_adminList);
    }

    private void UpdateAdminButtons()
    {
        bool active = _isAdmin && !_busy && !_token.IsCancellationRequested;
        _adminCreate.IsEnabled = active;
        bool has = active && _adminList.SelectedItem is StackPanel;
        _adminPassword.IsEnabled = has;
        _adminToggleAdmin.IsEnabled = has;
        _adminToggleLock.IsEnabled = has;
        _adminDelete.IsEnabled = has;
    }

    private AdminUser? SelectedAdminUser()
    {
        if (_adminList.SelectedItem is not StackPanel { Tag: AdminUser user }) return null;
        return _adminUsersCache.FirstOrDefault(candidate => candidate.Id == user.Id);
    }

    private async Task RefreshAdminAsync()
    {
        if (_adminUsers is null || _isAdmin is false || _lifetime.IsCancellationRequested) return;
        long revision = ++_adminReadRevision;
        try
        {
            var users = await _adminUsers.ListAsync(_directory?.Account ?? default, _token);
            if (_token.IsCancellationRequested || revision != _adminReadRevision) return;
            _adminUsersCache = users.ToList();
            _adminList.Items.Clear();
            foreach (var user in users)
            {
                var row = new StackPanel
                {
                    Orientation = Orientation.Horizontal, Spacing = 10, Padding = new Thickness(8, 3, 8, 3),
                    Tag = new AdminUser(user.Id, user.Username, user.IsAdmin, user.LockPassword, user.HideDotfiles,
                        user.SingleClick, user.Permissions),
                };
                row.Children.Add(new TextBlock { Text = user.Username, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, MinWidth = 160, TextTrimming = TextTrimming.CharacterEllipsis });
                row.Children.Add(new TextBlock { Text = user.IsAdmin ? "管理员" : "普通用户", FontSize = 12, Opacity = 0.75, MinWidth = 70 });
                row.Children.Add(new TextBlock { Text = user.LockPassword ? "密码已锁定" : "", FontSize = 12, Opacity = 0.65 });
                _adminList.Items.Add(row);
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (!_token.IsCancellationRequested && revision == _adminReadRevision)
                AddAdminStatus(error is FilewayException fileway ? fileway.Error.Message : "用户列表未能读取。", InfoBarSeverity.Error);
        }
    }

    private async Task<string?> RequireAdminPasswordAsync(string title, string confirmLabel)
    {
        var password = new PasswordBox { Header = "管理员（当前账户）密码", Width = 320 };
        var confirm = new ContentDialog { Title = title, Content = password, PrimaryButtonText = confirmLabel, CloseButtonText = "取消", DefaultButton = ContentDialogButton.Primary };
        if (await ShowDialogAsync(confirm) != ContentDialogResult.Primary || _token.IsCancellationRequested) return null;
        return password.Password;
    }

    private enum MutationKind { ResetPassword, ToggleAdmin, ToggleLock, Delete }

    private async Task CreateAdminUserAsync()
    {
        if (_adminUsers is null || _busy || _lifetime.IsCancellationRequested) return;
        var username = new TextBox { Header = "用户名", Width = 320 };
        var password = new PasswordBox { Header = "初始密码", Width = 320 };
        var admin = new CheckBox { Content = "管理员权限", };
        var perms = new StackPanel { Spacing = 4 };
        var download = new CheckBox { Content = "下载", IsChecked = true };
        var create = new CheckBox { Content = "上传/新建" };
        var modify = new CheckBox { Content = "修改" };
        var rename = new CheckBox { Content = "重命名" };
        var delete = new CheckBox { Content = "删除" };
        var execute = new CheckBox { Content = "执行命令" };
        perms.Children.Add(download); perms.Children.Add(create); perms.Children.Add(modify);
        perms.Children.Add(rename); perms.Children.Add(delete); perms.Children.Add(execute);
        var panel = new StackPanel { Spacing = 10, MinWidth = 320 };
        panel.Children.Add(username); panel.Children.Add(password); panel.Children.Add(admin);
        panel.Children.Add(new TextBlock { Text = "权限", FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
        panel.Children.Add(perms);
        var confirm = new ContentDialog { Title = "新建用户", Content = panel, PrimaryButtonText = "创建", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Primary, IsPrimaryButtonEnabled = false };
        username.TextChanged += (_, _) => confirm.IsPrimaryButtonEnabled = username.Text.Trim().Length > 0 && password.Password.Length > 0;
        password.PasswordChanged += (_, _) => confirm.IsPrimaryButtonEnabled = username.Text.Trim().Length > 0 && password.Password.Length > 0;
        if (await ShowDialogAsync(confirm) != ContentDialogResult.Primary || _token.IsCancellationRequested) return;
        var adminPassword = await RequireAdminPasswordAsync("确认管理员身份", "创建");
        if (adminPassword is null) return;
        SetBusy(true);
        try
        {
            var permissions = new PermissionSet(download.IsChecked == true, create.IsChecked == true, rename.IsChecked == true,
                modify.IsChecked == true, delete.IsChecked == true, execute.IsChecked == true);
            var id = await _adminUsers.CreateAsync(_directory?.Account ?? default,
                new AdminUserCreate(username.Text.Trim(), password.Password, admin.IsChecked == true, permissions), adminPassword, _token);
            AddAdminStatus($"用户 {username.Text.Trim()}（#{id}）已创建。", InfoBarSeverity.Success);
            await RefreshAdminAsync();
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            AddAdminStatus(error is FilewayException fileway ? fileway.Error.Message : "创建未能完成。", InfoBarSeverity.Error);
        }
        finally { SetBusy(false); }
    }

    private async Task MutateSelectedAsync(MutationKind kind)
    {
        if (_adminUsers is null || _busy || _lifetime.IsCancellationRequested) return;
        if (SelectedAdminUser() is not { } user) return;
        string title = kind switch
        {
            MutationKind.ResetPassword => $"重置 {user.Username} 的密码",
            MutationKind.ToggleAdmin => user.IsAdmin ? $"收回 {user.Username} 的管理员权限" : $"将 {user.Username} 设为管理员",
            MutationKind.ToggleLock => user.LockPassword ? $"解除 {user.Username} 的密码锁定" : $"锁定 {user.Username} 的密码",
            _ => $"删除用户 {user.Username}",
        };
        string confirmLabel = kind switch
        {
            MutationKind.ResetPassword => "重置",
            MutationKind.ToggleAdmin or MutationKind.ToggleLock => "应用",
            _ => "删除",
        };
        string? newPassword = null;
        var panel = new StackPanel { Spacing = 10, MinWidth = 320 };
        if (kind == MutationKind.ResetPassword)
        {
            var box = new PasswordBox { Header = "新密码", Width = 320 };
            panel.Children.Add(box);
            var dialog = new ContentDialog { Title = title, Content = panel, PrimaryButtonText = confirmLabel, CloseButtonText = "取消", DefaultButton = ContentDialogButton.Primary, IsPrimaryButtonEnabled = false };
            box.PasswordChanged += (_, _) => dialog.IsPrimaryButtonEnabled = box.Password.Length > 0;
            if (await ShowDialogAsync(dialog) != ContentDialogResult.Primary || _token.IsCancellationRequested) return;
            newPassword = box.Password;
        }
        panel.Children.Add(new TextBlock
        {
            Text = kind == MutationKind.Delete
                ? "该用户将被永久删除（其配置不可恢复）。确认管理员密码以继续。"
                : "确认管理员密码以继续。服务器将校验后执行。",
            TextWrapping = TextWrapping.Wrap,
        });
        var password = new PasswordBox { Header = "管理员（当前账户）密码", Width = 320 };
        panel.Children.Add(password);
        var confirm = new ContentDialog { Title = title, Content = panel, PrimaryButtonText = confirmLabel, CloseButtonText = "取消", DefaultButton = ContentDialogButton.Primary };
        if (await ShowDialogAsync(confirm) != ContentDialogResult.Primary || _token.IsCancellationRequested) return;
        if (password.Password.Length == 0) return;
        SetBusy(true);
        try
        {
            switch (kind)
            {
                case MutationKind.ResetPassword:
                    await _adminUsers.UpdateAsync(_directory?.Account ?? default,
                        new AdminUserUpdate(user.Id, Password: newPassword, null, null, null), password.Password, _token);
                    AddAdminStatus($"{user.Username} 的密码已重置。", InfoBarSeverity.Success);
                    break;
                case MutationKind.ToggleAdmin:
                    await _adminUsers.UpdateAsync(_directory?.Account ?? default,
                        new AdminUserUpdate(user.Id, null, IsAdmin: !user.IsAdmin, null, null), password.Password, _token);
                    AddAdminStatus($"{user.Username} 管理员权限已{(user.IsAdmin ? "收回" : "授予")}。", InfoBarSeverity.Success);
                    break;
                case MutationKind.ToggleLock:
                    await _adminUsers.UpdateAsync(_directory?.Account ?? default,
                        new AdminUserUpdate(user.Id, null, null, LockPassword: !user.LockPassword, null), password.Password, _token);
                    AddAdminStatus($"{user.Username} 密码锁定已{(user.LockPassword ? "解除" : "启用")}。", InfoBarSeverity.Success);
                    break;
                case MutationKind.Delete:
                    if (user.Username.Equals(_sessionAdminUsername, StringComparison.OrdinalIgnoreCase))
                    { AddAdminStatus("不能删除当前登录的管理员账户。", InfoBarSeverity.Warning); return; }
                    await _adminUsers.DeleteAsync(_directory?.Account ?? default, user.Id, password.Password, _token);
                    AddAdminStatus($"用户 {user.Username} 已删除。", InfoBarSeverity.Success);
                    break;
            }
            await RefreshAdminAsync();
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            AddAdminStatus(error is FilewayException fileway ? fileway.Error.Message : "操作未能完成。", InfoBarSeverity.Error);
        }
        finally { SetBusy(false); }
    }

    private string _sessionAdminUsername = "";

    private void AddAdminStatus(string message, InfoBarSeverity severity)
    {
        if (_token.IsCancellationRequested) return;
        _adminNotice.Message = message;
        _adminNotice.Severity = severity;
        Fileway.App.Controls.FloatingNotice.Show(_adminNotice);
    }

    private async Task StartAsync()
    {
        if (_busy || _lifetime.IsCancellationRequested) return;
        if (_directory is not { } directory) return;
        SetBusy(true);
        _start.IsEnabled = false;
        _results.Children.Clear();
        _progress.IsActive = true;
        _progress.Visibility = Visibility.Visible;
        try
        {
            var task = await _service.StartAsync(directory, _token);
            SetNotice("分析任务已提交，等待服务器完成…", InfoBarSeverity.Informational);
            var completed = await WaitAsync(task);
            if (completed is null) return;
            if (completed.Status != ServerTaskStatus.Completed)
            {
                SetNotice($"分析未完成：{completed.Title}（{completed.Status}）" +
                    (completed.Error is { } error ? "：" + error.Message : ""), InfoBarSeverity.Error);
                return;
            }
            var report = await _service.GetReportAsync(completed.Reference, _token);
            if (report is null)
            {
                SetNotice("服务器尚未发布分析结果，请稍后在文件任务中重试读取。", InfoBarSeverity.Warning);
                return;
            }
            SetNotice($"分析完成：{report.ScannedFiles:N0} 个文件 · {report.ScannedDirectories:N0} 个目录 · {FormatBytes(report.ScannedBytes)}" +
                (report.Truncated ? "（结果被服务器截断）" : "") +
                (report.SkippedCount > 0 ? $" · {report.SkippedCount} 项跳过" : ""), InfoBarSeverity.Success);
            Render(report);
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            SetNotice(error is FilewayException fileway ? fileway.Error.Message : "存储分析未能完成，请检查连接后重试。", InfoBarSeverity.Error);
        }
        finally
        {
            SetBusy(false);
            _progress.IsActive = false;
            _progress.Visibility = Visibility.Collapsed;
        }
    }

    private async Task<ServerTask?> WaitAsync(ServerTaskRef task)
    {
        var deadline = System.Diagnostics.Stopwatch.GetTimestamp() + 600L * System.Diagnostics.Stopwatch.Frequency;
        while (true)
        {
            _token.ThrowIfCancellationRequested();
            if (System.Diagnostics.Stopwatch.GetTimestamp() >= deadline) throw new FilewayException(new ErrorInfo(ErrorCode.Timeout, "分析超过 10 分钟仍未完成，请稍后在文件任务中查看结果。"));
            var current = await _service.GetTaskAsync(task, _token);
            if (current.Status is ServerTaskStatus.Completed or ServerTaskStatus.Failed or ServerTaskStatus.Canceled or ServerTaskStatus.Interrupted)
                return current;
            await Task.Delay(TimeSpan.FromSeconds(1.5), _token);
        }
    }

    private void Render(StorageAnalysisReport report)
    {
        _results.Children.Clear();
        foreach (var scope in report.Scopes)
        {
            _results.Children.Add(new Border
            {
                Padding = new Thickness(12, 8, 12, 8),
                CornerRadius = new CornerRadius(6),
                Background = (Microsoft.UI.Xaml.Media.Brush)Microsoft.UI.Xaml.Application.Current.Resources["LayerFillColorDefaultBrush"],
                Child = new TextBlock { Text = $"{scope.Path}\n{FormatBytes(scope.Bytes)} · {scope.Files:N0} 个文件 · {scope.Directories:N0} 个目录", TextWrapping = TextWrapping.Wrap },
            });
        }
        if (report.LargestFiles.Count > 0)
        {
            _results.Children.Add(Section("最大的文件"));
            foreach (var file in report.LargestFiles.Take(10))
                _results.Children.Add(new TextBlock { Text = $"{FormatBytes(file.Size)} · {file.Path}", TextTrimming = TextTrimming.CharacterEllipsis, Margin = new Thickness(8, 0, 0, 2) });
        }
        if (report.LargestDirectories.Count > 0)
        {
            _results.Children.Add(Section("最大的目录"));
            foreach (var directory in report.LargestDirectories.Take(10))
                _results.Children.Add(new TextBlock { Text = $"{FormatBytes(directory.Bytes)} · {directory.Files:N0} 个文件 · {directory.Path}", TextTrimming = TextTrimming.CharacterEllipsis, Margin = new Thickness(8, 0, 0, 2) });
        }
    }

    private static TextBlock Section(string title) => new() { Text = title, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, Margin = new Thickness(0, 8, 0, 0) };

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _adminNotice.IsOpen = false;
        _activeDialog?.Hide();
        _lifetime.Cancel();
        _lifetime.Dispose();
    }

    private async Task<ContentDialogResult> ShowDialogAsync(ContentDialog dialog)
    {
        if (_token.IsCancellationRequested || _activeDialog is not null || XamlRoot is null) return ContentDialogResult.None;
        dialog.XamlRoot = XamlRoot;
        _activeDialog = dialog;
        try { return await dialog.ShowAsync(); }
        finally
        {
            _activeDialog = null;
            // The reset-password flow reuses its panel in the identity confirmation.
            dialog.Content = null;
        }
    }

    internal static string FormatBytes(long bytes) => bytes switch
    {
        >= 1_000_000_000_000 => $"{bytes / 1_000_000_000_000.0:0.##} TB",
        >= 1_000_000_000 => $"{bytes / 1_000_000_000.0:0.##} GB",
        >= 1_000_000 => $"{bytes / 1_000_000.0:0.##} MB",
        >= 1_000 => $"{bytes / 1_000.0:0.##} KB",
        _ => $"{bytes} B",
    };

    private IStorageAnalysisService _service = null!;

    public void Attach(IStorageAnalysisService service, IDuplicateAnalysisService? duplicates)
    {
        _service = service;
        _duplicates = duplicates;
        _duplicateStart.IsEnabled = duplicates is not null && _directory is not null && !_busy;
    }

    /// <summary>Enables the admin tab when the signed-in session carries the server admin flag.</summary>
    public void AttachAdmin(IAdminUserService service, string adminUsername)
    {
        _adminUsers = service;
        _sessionAdminUsername = adminUsername;
        try { _isAdmin = service.IsAvailable(_directory?.Account ?? default); }
        catch (Exception) { _isAdmin = false; }
        _adminTabButton.Visibility = _isAdmin ? Visibility.Visible : Visibility.Collapsed;
        UpdateAdminButtons();
    }
}
