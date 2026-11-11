using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;

namespace Fileway.App.Controls.StorageTools;

/// <summary>
/// Server-side storage analysis panel: one bounded scan task per run, polled through the server's
/// own task status, with the completed report rendered from server-provided numbers only.
/// </summary>
public sealed partial class StorageAnalysisControl : UserControl, IDisposable
{
    internal event EventHandler? CloseRequested;
    private readonly CancellationTokenSource _lifetime = new();
    private readonly CancellationToken _token;
    private bool _disposed;
    private bool _contextAssigned;
    private Guid _sessionId;
    private bool _canDownload, _canDelete;
    private TaskCompletionSource? _resumeObservation = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private ContentDialog? _activeDialog;
    private long _adminReadRevision;
    private readonly TextBox _scopeBox = new() { IsReadOnly = true };
    private readonly Button _start = new() { Content = "开始分析" };
    private readonly InfoBar _notice = new() { IsOpen = false, IsClosable = true, Severity = InfoBarSeverity.Informational, Visibility = Visibility.Collapsed };
    private readonly ProgressRing _progress = new() { IsActive = false, Visibility = Visibility.Collapsed };
    private readonly StackPanel _results = new() { Spacing = 10 };
    private readonly Button _capacityRetry = new() { Content = "重试读取报告", Visibility = Visibility.Collapsed };
    private ServerTaskRef? _capacityTask;
    private ServerTaskRef? _duplicateTask;
    private readonly Button _duplicateRetry = new() { Content = "重试读取报告", Visibility = Visibility.Collapsed };
    private readonly TextBlock _capacityPermission = new() { Visibility = Visibility.Collapsed };
    private readonly TextBlock _duplicatePermission = new() { Visibility = Visibility.Collapsed };
    private RemoteResourceRef? _directory;
    private bool _busy;
    private IDuplicateAnalysisService? _duplicates;
    private readonly StackPanel _duplicatesRoot = new() { Spacing = 12 };
    private readonly TextBox _duplicateScopeBox = new() { IsReadOnly = true };
    private readonly Button _duplicateStart = new() { Content = "开始扫描" };
    private readonly StackPanel _duplicateResults = new() { Spacing = 10 };
    private readonly InfoBar _duplicateNotice = new() { IsOpen = false, IsClosable = true, Visibility = Visibility.Collapsed };
    private IAdminUserService? _adminUsers;
    private bool _isAdmin;
    private bool? _enableExec;
    private bool _adminRequiresPassword;
    private readonly Action<int> _selectTab;
    private readonly ToggleButton _adminTabButton = new() { Content = "用户管理", Visibility = Visibility.Collapsed, Width = 120, HorizontalContentAlignment = HorizontalAlignment.Center };
    private readonly Grid _adminHost = new();
    private readonly StackPanel _adminRoot = new() { Spacing = 12 };
    private readonly InfoBar _adminNotice = new() { IsOpen = false, IsClosable = true, Title = "服务器用户管理" };
    private readonly ListView _adminList = new() { SelectionMode = ListViewSelectionMode.Single, MaxHeight = 460 };
    private readonly Button _adminCreate = new() { Content = "新建用户…" };
    private readonly Button _adminPassword = new() { Content = "重置密码…", IsEnabled = false };
    private readonly Button _adminPolicy = new() { Content = "权限与规则…", IsEnabled = false };
    private readonly Button _adminRefresh = new() { Content = "刷新列表" };
    private readonly Button _adminToggleAdmin = new() { Content = "切换管理员", IsEnabled = false };
    private readonly Button _adminToggleLock = new() { Content = "切换密码锁定", IsEnabled = false };
    private readonly Button _adminDelete = new() { Content = "删除用户…", IsEnabled = false };
    private List<AdminUser> _adminUsersCache = [];
    private readonly ToggleButton _analysisTabButton = new() { Content = "容量分析", Width = 120, HorizontalContentAlignment = HorizontalAlignment.Center };
    private readonly ToggleButton _duplicatesTabButton = new() { Content = "重复文件", Width = 120, HorizontalContentAlignment = HorizontalAlignment.Center };

    public StorageAnalysisControl()
    {
        _token = _lifetime.Token;
        AutomationProperties.SetAutomationId(this, "StorageAnalysisView");
        foreach (var (element, id) in new (DependencyObject, string)[] {
            (_start, "StorageAnalysisStart"), (_notice, "StorageAnalysisNotice"),
            (_capacityRetry, "StorageAnalysisRetry"), (_duplicateRetry, "StorageDuplicateScanRetry"),
            (_cleanupRetry, "StorageDuplicateCleanupRetry"),
            (_duplicateStart, "StorageDuplicatesStart"), (_analysisTabButton, "StorageAnalysisTab"),
            (_duplicateNotice, "StorageDuplicatesNotice"),
            (_duplicatesTabButton, "StorageDuplicatesTab"), (_adminTabButton, "StorageAdminTab"),
            (_adminList, "StorageAdminUsers"), (_adminCreate, "StorageAdminCreate"),
            (_adminNotice, "StorageAdminNotice"),
            (_adminPassword, "StorageAdminPassword"), (_adminToggleAdmin, "StorageAdminToggleAdmin"),
            (_adminToggleLock, "StorageAdminToggleLock"), (_adminDelete, "StorageAdminDelete"),
            (_adminPolicy, "EditServerUserPolicy"), (_adminRefresh, "RefreshServerUsers") })
            AutomationProperties.SetAutomationId(element, id);
        var resources = Application.Current.Resources;
        _start.Style = (Style)resources["FwPrimaryButtonStyle"];
        _duplicateStart.Style = (Style)resources["FwPrimaryButtonStyle"];
        _scopeBox.Style = (Style)resources["FwTextBoxStyle"];
        _scopeBox.HorizontalAlignment = HorizontalAlignment.Stretch;
        _duplicateScopeBox.Style = (Style)resources["FwTextBoxStyle"];
        _duplicateScopeBox.HorizontalAlignment = HorizontalAlignment.Stretch;
        var analysisRoot = new StackPanel { Spacing = 12 };
        _start.Click += async (_, _) => await StartAsync();
        _capacityRetry.Click += async (_, _) => await ReadCapacityAsync();
        _duplicateRetry.Click += async (_, _) => await ReadDuplicateScanAsync();
        _cleanupRetry.Click += async (_, _) => await RetryCleanupAsync();
        foreach (var permission in new[] { _capacityPermission, _duplicatePermission })
        {
            permission.Style = (Style)resources["FwCaptionTextStyle"];
            permission.Text = "当前账户缺少下载权限，无法扫描。";
            permission.TextWrapping = TextWrapping.Wrap;
        }
        foreach (var retry in new[] { _capacityRetry, _duplicateRetry, _cleanupRetry })
            retry.Style = (Style)resources["FwSecondaryButtonStyle"];
        _notice.ActionButton = _capacityRetry;
        _duplicateNotice.ActionButton = _duplicateRetry;
        foreach (var bar in new[] { _notice, _duplicateNotice })
            bar.RegisterPropertyChangedCallback(InfoBar.IsOpenProperty, static (sender, _) =>
            {
                var notice = (InfoBar)sender;
                notice.Visibility = notice.IsOpen ? Visibility.Visible : Visibility.Collapsed;
            });
        analysisRoot.Children.Add(ScanCard(
            "在服务器后台统计文件、文件夹和大小，并列出占用最多的项目。",
            "分析范围", _scopeBox, _start, _capacityPermission));
        analysisRoot.Children.Add(_notice);
        analysisRoot.Children.Add(_progress);
        analysisRoot.Children.Add(_results);

        _duplicateStart.Click += async (_, _) => await StartDuplicateScanAsync();
        _duplicatesRoot.Children.Add(ScanCard(
            "按内容查找重复文件。确认保留的副本后，其余副本移入回收站，可恢复。",
            "扫描范围", _duplicateScopeBox, _duplicateStart, _duplicatePermission));
        _duplicatesRoot.Children.Add(_duplicateNotice);
        _duplicatesRoot.Children.Add(_duplicateResults);

        var analysisHost = new Grid();
        analysisHost.Children.Add(analysisRoot);
        var duplicatesHost = new Grid { Visibility = Visibility.Collapsed };
        duplicatesHost.Children.Add(_duplicatesRoot);
        var adminHost = new Grid { Visibility = Visibility.Collapsed };
        BuildAdminRoot();
        adminHost.Children.Add(_adminRoot);

        var root = new StackPanel { Spacing = 12, Padding = new Thickness(20, 12, 20, 12) };
        var back = new Button { Content = "返回文件", Style = (Style)resources["FwSecondaryButtonStyle"], VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetName(back, "离开存储工具，返回文件");
        back.Click += (_, _) => CloseRequested?.Invoke(this, EventArgs.Empty);
        root.Children.Add(new FwPageHeader { Title = "存储工具", Subtitle = "在服务器后台统计容量和重复文件。", Actions = back });
        void SelectTab(int selected)
        {
            analysisHost.Visibility = selected == 0 ? Visibility.Visible : Visibility.Collapsed;
            duplicatesHost.Visibility = selected == 1 ? Visibility.Visible : Visibility.Collapsed;
            adminHost.Visibility = selected == 2 ? Visibility.Visible : Visibility.Collapsed;
            _analysisTabButton.IsChecked = selected == 0;
            _duplicatesTabButton.IsChecked = selected == 1;
            _adminTabButton.IsChecked = selected == 2;
            if (selected == 2) _ = RefreshAdminAsync();
        }
        _selectTab = SelectTab;
        var tabs = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4 };
        foreach (var (button, index) in new[] { (_analysisTabButton, 0), (_duplicatesTabButton, 1), (_adminTabButton, 2) })
        {
            button.Style = (Style)resources["FwSegmentedChoiceStyle"];
            button.Click += (_, _) => SelectTab(index);
            tabs.Children.Add(button);
        }
        root.Children.Add(tabs);
        root.Children.Add(analysisHost);
        root.Children.Add(duplicatesHost);
        root.Children.Add(adminHost);
        SelectTab(0);
        Content = new ScrollViewer { Content = root, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
    }

    internal void SetContext(RemoteResourceRef directory, ConnectionSession session)
    {
        if (_contextAssigned) throw new InvalidOperationException("Storage views cannot be rebound to another context.");
        _contextAssigned = true;
        _directory = directory;
        _sessionId = session.Id;
        _scopeBox.Text = directory.DisplayPath;
        _duplicateScopeBox.Text = _scopeBox.Text;
        SetPermissions(session.Permissions);
    }

    internal bool HasSession(ConnectionSession? session) =>
        session is not null && session.Id == _sessionId && session.Account == _directory?.Account;

    internal bool HasDirectory(RemoteResourceRef directory) =>
        _directory is { } current && current.Account == directory.Account && current.WirePath == directory.WirePath;

    internal void SetPermissions(PermissionSet permissions)
    {
        _canDownload = permissions.Download;
        _canDelete = permissions.Delete;
        _capacityPermission.Visibility = _duplicatePermission.Visibility = _canDownload ? Visibility.Collapsed : Visibility.Visible;
        SetBusy(_busy);
    }

    internal void SetViewActive(bool active)
    {
        if (active)
        {
            var resume = _resumeObservation;
            _resumeObservation = null;
            resume?.TrySetResult();
        }
        else
        {
            _resumeObservation ??= new(TaskCreationOptions.RunContinuationsAsynchronously);
            _activeDialog?.Hide();
        }
    }

    private async Task WaitForViewAsync()
    {
        while (_resumeObservation is { } resume)
            await resume.Task.WaitAsync(_token);
    }

    private void SetBusy(bool busy)
    {
        _busy = busy;
        bool active = !_token.IsCancellationRequested && _directory is not null && !busy;
        _start.IsEnabled = active && _canDownload && _service is not null;
        _duplicateStart.IsEnabled = active && _canDownload && _duplicates is not null &&
            (_cleanup is null || _cleanupFinished);
        _capacityRetry.IsEnabled = _duplicateRetry.IsEnabled = active && _canDownload;
        UpdateCleanupControls();
        UpdateAdminButtons();
    }

    private void SetNotice(string message, InfoBarSeverity severity)
    {
        _notice.Message = message;
        _notice.Severity = severity;
        _notice.IsClosable = _capacityRetry.Visibility != Visibility.Visible;
        _notice.IsOpen = true;
    }


    private void AddDuplicateStatus(string message, InfoBarSeverity severity = InfoBarSeverity.Informational)
    {
        if (_token.IsCancellationRequested) return;
        _duplicateNotice.Message = message;
        _duplicateNotice.Severity = severity;
        _duplicateNotice.IsClosable = _duplicateNotice.ActionButton is not { Visibility: Visibility.Visible };
        _duplicateNotice.IsOpen = true;
    }

    private static Border FormCard(string description, params UIElement[] fields)
    {
        var resources = Application.Current.Resources;
        var content = new StackPanel { Spacing = 8 };
        var text = new TextBlock { Text = description, FontSize = 13, TextWrapping = TextWrapping.Wrap };
        text.Style = (Style)resources["FwCaptionTextStyle"];
        text.FontSize = 13;
        content.Children.Add(text);
        foreach (var field in fields) content.Children.Add(field);
        var card = new Border
        {
            HorizontalAlignment = HorizontalAlignment.Stretch,
            Child = content
        };
        card.Style = (Style)resources["FwFormCardStyle"];
        return card;
    }

    private static Border ScanCard(string description, string label, TextBox path, Button action, TextBlock permission)
    {
        var row = DialogOrdering.FieldRow(label, path, 76);
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        action.HorizontalAlignment = HorizontalAlignment.Right;
        action.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(action, 2);
        row.Children.Add(action);
        return FormCard(description, row, permission);
    }

    private void BuildAdminRoot()
    {
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        _adminCreate.Click += async (_, _) => await CreateAdminUserAsync();
        _adminPolicy.Click += async (_, _) => await EditUserPolicyAsync();
        _adminRefresh.Click += async (_, _) => await RefreshAdminAsync();
        _adminPassword.Click += async (_, _) => await MutateSelectedAsync(MutationKind.ResetPassword);
        _adminToggleAdmin.Click += async (_, _) => await MutateSelectedAsync(MutationKind.ToggleAdmin);
        _adminToggleLock.Click += async (_, _) => await MutateSelectedAsync(MutationKind.ToggleLock);
        _adminDelete.Click += async (_, _) => await MutateSelectedAsync(MutationKind.Delete);
        actions.Children.Add(_adminCreate);
        actions.Children.Add(_adminPolicy);
        actions.Children.Add(_adminPassword);
        actions.Children.Add(_adminToggleAdmin);
        actions.Children.Add(_adminToggleLock);
        actions.Children.Add(_adminDelete);
        actions.Children.Add(_adminRefresh);
        var actionsScroll = new ScrollViewer { Content = actions, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollMode = ScrollMode.Auto, VerticalScrollBarVisibility = ScrollBarVisibility.Disabled, VerticalScrollMode = ScrollMode.Disabled };
        _adminRoot.Children.Add(FormCard(
            "在当前服务器创建用户、管理权限与规则。密码认证的服务器在保存敏感设置时需要验证当前管理员密码。",
            actionsScroll));
        _adminRoot.Children.Add(_adminNotice);
        FloatingNotice.Attach(_adminNotice);
        _adminList.SelectionChanged += (_, _) => UpdateAdminButtons();
        _adminRoot.Children.Add(_adminList);
    }

    private void UpdateAdminButtons()
    {
        bool active = _isAdmin && !_busy && !_token.IsCancellationRequested;
        _adminCreate.IsEnabled = active;
        _adminRefresh.IsEnabled = active;
        bool has = active && _adminList.SelectedItem is StackPanel;
        _adminPassword.IsEnabled = has;
        _adminPolicy.IsEnabled = has;
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
                    Tag = user,
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
        if (!_adminRequiresPassword) return "";
        var password = new PasswordBox { Header = "管理员（当前账户）密码", Width = 320 };
        var confirm = new ContentDialog { Title = title, Content = password, PrimaryButtonText = confirmLabel, CloseButtonText = "取消", DefaultButton = ContentDialogButton.Primary };
        if (await ShowDialogAsync(confirm) != ContentDialogResult.Primary || _token.IsCancellationRequested) return null;
        string value = password.Password;
        password.Password = "";
        return value.Length == 0 ? null : value;
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
        var share = new CheckBox { Content = "分享", IsChecked = false };
        download.Unchecked += (_, _) => { share.IsChecked = false; share.IsEnabled = false; };
        download.Checked += (_, _) => share.IsEnabled = true;
        var create = new CheckBox { Content = "上传/新建" };
        var modify = new CheckBox { Content = "修改" };
        var rename = new CheckBox { Content = "重命名" };
        var delete = new CheckBox { Content = "删除" };
        var execute = new CheckBox { Content = "执行命令" };
        perms.Children.Add(download); perms.Children.Add(share); perms.Children.Add(create); perms.Children.Add(modify);
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
                modify.IsChecked == true, delete.IsChecked == true, execute.IsChecked == true, Share: share.IsChecked == true);
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
        if (_adminRequiresPassword) panel.Children.Add(password);
        var confirm = new ContentDialog { Title = title, Content = panel, PrimaryButtonText = confirmLabel, CloseButtonText = "取消", DefaultButton = ContentDialogButton.Primary };
        if (await ShowDialogAsync(confirm) != ContentDialogResult.Primary || _token.IsCancellationRequested) return;
        if (_adminRequiresPassword && password.Password.Length == 0) return;
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
        if (!_start.IsEnabled || _directory is not { } directory) return;
        _capacityTask = null;
        _capacityRetry.Visibility = Visibility.Collapsed;
        _results.Children.Clear();
        SetBusy(true);
        try
        {
            SetNotice("正在提交分析…", InfoBarSeverity.Informational);
            _capacityTask = await _service.StartAsync(directory, _token);
            await ObserveCapacityAsync(_capacityTask.Value);
        }
        catch (OperationCanceledException) { }
        catch (Exception error) { ReportCapacityReadFailure(error); }
        finally { SetBusy(false); }
    }

    private async Task ReadCapacityAsync()
    {
        if (_busy || !_canDownload || _capacityTask is not { } task) return;
        SetBusy(true);
        _capacityRetry.Visibility = Visibility.Collapsed;
        try { await ObserveCapacityAsync(task); }
        catch (OperationCanceledException) { }
        catch (Exception error) { ReportCapacityReadFailure(error); }
        finally { SetBusy(false); }
    }

    private void ReportCapacityReadFailure(Exception error)
    {
        if (_token.IsCancellationRequested) return;
        _capacityRetry.Visibility = _capacityTask is null ? Visibility.Collapsed : Visibility.Visible;
        SetNotice(error is FilewayException known ? known.Error.Message : "暂时无法读取容量报告，请检查连接。", InfoBarSeverity.Warning);
    }

    private async Task ObserveCapacityAsync(ServerTaskRef task)
    {
        SetNotice("正在读取分析进度…", InfoBarSeverity.Informational);
        _progress.IsActive = true;
        _progress.Visibility = Visibility.Visible;
        try
        {
            var completed = await WaitForTaskAsync(task, _service.GetTaskAsync, TimeSpan.FromMinutes(10), "分析", SetNotice);
            if (completed.Status != ServerTaskStatus.Completed)
            {
                SetNotice(TaskStateLabel("分析", completed) +
                    (completed.Error is { } error ? $"。{error.Message}" : ""), InfoBarSeverity.Warning);
                return;
            }
            var report = await _service.GetReportAsync(completed.Reference, _token);
            await WaitForViewAsync();
            _token.ThrowIfCancellationRequested();
            if (report is null)
            {
                _capacityRetry.Visibility = Visibility.Visible;
                SetNotice("分析已结束，报告暂未发布。", InfoBarSeverity.Warning);
                return;
            }
            _notice.IsOpen = false;
            _start.Content = "重新分析";
            Render(report);
        }
        finally
        {
            _progress.IsActive = false;
            _progress.Visibility = Visibility.Collapsed;
        }
    }

    private async Task<ServerTask> WaitForTaskAsync(ServerTaskRef task,
        Func<ServerTaskRef, CancellationToken, Task<ServerTask>> read, TimeSpan limit,
        string operation, Action<string, InfoBarSeverity> publish)
    {
        var elapsed = System.Diagnostics.Stopwatch.StartNew();
        while (true)
        {
            await WaitForViewAsync();
            _token.ThrowIfCancellationRequested();
            var current = await read(task, _token);
            await WaitForViewAsync();
            _token.ThrowIfCancellationRequested();
            if (current.Status is ServerTaskStatus.Completed or ServerTaskStatus.Failed or ServerTaskStatus.Canceled or ServerTaskStatus.Interrupted)
                return current;
            if (elapsed.Elapsed >= limit)
                throw new FilewayException(new(ErrorCode.Timeout, $"暂未等到{operation}结束，可继续读取同一任务。"));
            string items = current.TotalItems > 0 ? $" · 已处理 {current.ProcessedItems:N0}/{current.TotalItems:N0} 项" : "";
            publish(TaskStateLabel(operation, current) + items, InfoBarSeverity.Informational);
            await Task.Delay(TimeSpan.FromSeconds(1.5), _token);
        }
    }

    private static string TaskStateLabel(string operation, ServerTask task) => task.Status switch
    {
        ServerTaskStatus.Queued => $"{operation}等待服务器处理",
        ServerTaskStatus.Running => $"正在{operation}",
        ServerTaskStatus.Completed => $"{operation}完成",
        ServerTaskStatus.Failed => $"{operation}未全部完成",
        ServerTaskStatus.Canceled => $"{operation}已取消",
        ServerTaskStatus.Interrupted => $"{operation}已中断",
        _ => $"{operation}状态待更新",
    };

    private void Render(StorageAnalysisReport report)
    {
        _results.Children.Clear();
        var summary = new StackPanel { Spacing = 4 };
        summary.Children.Add(Section("容量报告"));
        summary.Children.Add(ReportCaption($"{FormatBytes(report.ScannedBytes)} · {report.ScannedFiles:N0} 个文件 · {report.ScannedDirectories:N0} 个目录"));
        if (report.Truncated || report.SkippedCount > 0)
            summary.Children.Add(ReportCaption((report.Truncated ? "报告仅显示服务器返回的部分结果。" : "") +
                (report.SkippedCount > 0 ? $"分析跳过 {report.SkippedCount:N0} 项。" : "")));
        _results.Children.Add(summary);
        if (report.LargestFiles.Count > 0)
            AddCapacitySection("最大的文件", report.LargestFiles.Take(10)
                .Select(file => ReportRow(file.Path, "file", FormatBytes(file.Size))));
        if (report.LargestDirectories.Count > 0)
            AddCapacitySection("最大的目录", report.LargestDirectories.Take(10)
                .Select(directory => ReportRow(directory.Path, "folder", $"{FormatBytes(directory.Bytes)} · {directory.Files:N0} 个文件")));
    }

    private void AddCapacitySection(string title, IEnumerable<FwResourceRow> rows)
    {
        var list = new StackPanel { Spacing = 2 };
        foreach (var row in rows) list.Children.Add(row);
        _results.Children.Add(Section(title));
        _results.Children.Add(ReportCard(list));
    }

    private static TextBlock ReportCaption(string text) =>
        new() { Text = text, Style = SurfaceGeometry.Style("FwCaptionTextStyle"), TextWrapping = TextWrapping.Wrap };

    private static (string Name, string Directory) ReportPath(string path)
    {
        string name = path.TrimEnd('/');
        int separator = name.LastIndexOf('/');
        return (name.Length == 0 ? "/" : name[(separator + 1)..], separator > 0 ? name[..separator] : "");
    }

    private static FwResourceRow ReportRow(string path, string icon, string summary)
    {
        var (name, directory) = ReportPath(path);
        var row = new FwResourceRow
        {
            Title = name,
            Detail = directory,
            Summary = summary,
            IconName = icon,
        };
        ToolTipService.SetToolTip(row, path);
        AutomationProperties.SetName(row, summary.Length == 0 ? path : $"{path}，{summary}");
        return row;
    }

    private static Border ReportCard(UIElement content) => new()
    {
        Style = SurfaceGeometry.Style("FwSubtlePanelStyle"),
        CornerRadius = new CornerRadius(10),
        Padding = new Thickness(10, 6, 10, 6),
        Child = content,
    };

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
        dialog.RequestedTheme = ActualTheme;
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
        SetBusy(_busy);
    }

    /// <summary>Enables the admin tab when the signed-in session carries the server admin flag.</summary>
    public void AttachAdmin(IAdminUserService service, string adminUsername, bool? enableExec, bool requiresPassword)
    {
        _adminUsers = service;
        _sessionAdminUsername = adminUsername;
        bool isAdmin;
        try { isAdmin = service.IsAvailable(_directory?.Account ?? default); }
        catch (Exception) { isAdmin = false; }
        SetAdminAvailability(isAdmin, enableExec, requiresPassword);
    }

    internal void ShowUsers()
    {
        if (_isAdmin) _selectTab(2);
    }

    internal void ShowAnalysisTools()
    {
        if (_adminTabButton.IsChecked == true) _selectTab(0);
    }

    internal void SetAdminAvailability(bool admin, bool? enableExec, bool requiresPassword)
    {
        _enableExec = enableExec;
        _adminRequiresPassword = requiresPassword;
        bool wasAdmin = _isAdmin;
        _isAdmin = admin && _adminUsers is not null;
        if (wasAdmin && !_isAdmin)
        {
            _adminReadRevision++;
            _adminUsersCache.Clear();
            _adminList.Items.Clear();
            if (_adminTabButton.IsChecked == true)
            {
                _activeDialog?.Hide();
                _selectTab(0);
            }
        }
        _adminTabButton.Visibility = _isAdmin ? Visibility.Visible : Visibility.Collapsed;
        UpdateAdminButtons();
    }
}
