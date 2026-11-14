using System.Globalization;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App.Controls.Settings;

/// <summary>A user list and its dialogs belong to one connected administrator session.</summary>
public sealed partial class ServerUserManagementControl : UserControl, IDisposable
{
    private readonly IAdminUserService _adminUsers;
    private readonly AccountKey _account;
    private readonly Func<bool> _isCurrent;
    private readonly Func<ContentDialog, Task<ContentDialogResult>> _showDialog;
    private readonly CancellationTokenSource _lifetime = new();
    private readonly CancellationToken _token;
    private readonly string _sessionAdminUsername;
    private bool _disposed, _busy, _refreshing, _hasList;
    private bool? _enableExec;
    private bool _adminRequiresPassword;
    private ContentDialog? _activeDialog;
    private bool _isAdmin => IsCurrent;

    private readonly ListView _adminList = new() { SelectionMode = ListViewSelectionMode.Single };
    private readonly Dictionary<ulong, ListViewItem> _rows = [];
    private readonly TextBlock _listSummary = new() { VerticalAlignment = VerticalAlignment.Center };
    private readonly TextBlock _listState = new()
    {
        HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
        TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center, Margin = new Thickness(16),
    };
    private readonly InfoBar _adminNotice = new() { IsClosable = true, Visibility = Visibility.Collapsed };
    private readonly InfoBar _feedback = new()
    {
        IsClosable = true, Visibility = Visibility.Collapsed, MaxWidth = 400,
        HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Top,
        Margin = new Thickness(12),
    };
    private readonly Button _back, _adminCreate, _adminPolicy, _adminRefresh, _more;
    private readonly MenuFlyout _adminActions = new();
    private readonly MenuFlyoutItem _adminPassword, _adminToggleAdmin, _adminToggleLock, _adminDelete;

    internal event EventHandler? CloseRequested;
    internal bool IsCurrent => !_disposed && _isCurrent();

    internal ServerUserManagementControl(IAdminUserService service, ConnectionSession session, Func<bool> isCurrent,
        Func<ContentDialog, Task<ContentDialogResult>> showDialog)
    {
        _adminUsers = service;
        _account = session.Account;
        _sessionAdminUsername = session.DisplayName;
        _isCurrent = isCurrent;
        _showDialog = showDialog;
        _token = _lifetime.Token;
        UpdateCapabilities(session.Capabilities);

        AutomationProperties.SetAutomationId(this, "ServerUserManagementView");
        AutomationProperties.SetAutomationId(_adminList, "StorageAdminUsers");
        AutomationProperties.SetName(_adminList, "当前服务器用户");
        AutomationProperties.SetAutomationId(_adminNotice, "StorageAdminNotice");
        AutomationProperties.SetAutomationId(_feedback, "ServerUsersFeedback");
        _listSummary.Style = SurfaceGeometry.Style("FwCaptionTextStyle");
        _listState.Style = SurfaceGeometry.Style("FwCaptionTextStyle");

        _back = CreateUserButton("返回设置", "BackFromServerUsers");
        _back.Click += (_, _) => CloseRequested?.Invoke(this, EventArgs.Empty);
        _adminCreate = CreateUserButton("新建用户…", "StorageAdminCreate", primary: true);
        _adminCreate.Click += async (_, _) => await CreateAdminUserAsync();
        _adminPolicy = CreateUserButton("权限与规则…", "EditServerUserPolicy");
        _adminPolicy.Click += async (_, _) => await EditUserPolicyAsync();
        _adminRefresh = CreateUserButton("刷新列表", "RefreshServerUsers");
        _adminRefresh.Click += async (_, _) => await RefreshAdminAsync();
        _more = CreateUserButton("更多", "MoreServerUserActions");
        AutomationProperties.SetName(_more, "更多用户操作");
        _adminPassword = AddAction("重置密码…", "StorageAdminPassword", MutationKind.ResetPassword);
        _adminToggleAdmin = AddAction("切换管理员…", "StorageAdminToggleAdmin", MutationKind.ToggleAdmin);
        _adminToggleLock = AddAction("切换密码锁定…", "StorageAdminToggleLock", MutationKind.ToggleLock);
        _adminActions.Items.Add(new MenuFlyoutSeparator());
        _adminDelete = AddAction("删除用户…", "StorageAdminDelete", MutationKind.Delete);
        _more.Flyout = _adminActions;
        SurfaceStyles.Attach(_adminActions);

        var toolbar = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Spacing = 6 };
        foreach (var button in new[] { _adminCreate, _adminPolicy, _adminRefresh, _more })
        {
            button.Padding = new Thickness(12, 0, 12, 0);
            toolbar.Children.Add(button);
        }

        var commands = new Grid { ColumnSpacing = 10 };
        commands.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        commands.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        commands.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        commands.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        Grid.SetColumn(toolbar, 1);
        commands.Children.Add(_listSummary);
        commands.Children.Add(toolbar);
        commands.SizeChanged += (_, args) =>
        {
            bool narrow = args.NewSize.Width < 560;
            commands.RowSpacing = narrow ? 6 : 0;
            Grid.SetRow(toolbar, narrow ? 1 : 0);
            Grid.SetColumn(toolbar, narrow ? 0 : 1);
            Grid.SetColumnSpan(toolbar, narrow ? 2 : 1);
        };

        foreach (var bar in new[] { _adminNotice, _feedback })
        {
            FloatingNotice.Attach(bar, automatic: false);
            bar.RegisterPropertyChangedCallback(InfoBar.IsOpenProperty, static (sender, _) =>
            {
                var notice = (InfoBar)sender;
                notice.Visibility = notice.IsOpen ? Visibility.Visible : Visibility.Collapsed;
            });
        }
        _adminList.SelectionChanged += (_, _) => UpdateAdminButtons();
        var body = new Grid();
        body.Children.Add(_adminList);
        body.Children.Add(_listState);
        body.Children.Add(_feedback);
        var layout = new Grid { Padding = new Thickness(20, 12, 20, 12), RowSpacing = 12, MaxWidth = 1120 };
        for (int i = 0; i < 3; i++) layout.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        layout.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        layout.Children.Add(new FwPageHeader
        {
            Title = "用户管理", Subtitle = "管理当前服务器的用户、权限与规则。", Actions = _back,
        });
        Grid.SetRow(commands, 1);
        layout.Children.Add(commands);
        Grid.SetRow(_adminNotice, 2);
        layout.Children.Add(_adminNotice);
        Grid.SetRow(body, 3);
        layout.Children.Add(body);
        Content = layout;
        UpdateAdminButtons();
    }

    internal void UpdateCapabilities(ServerCapabilities capabilities)
    {
        _enableExec = capabilities.EnableExec;
        _adminRequiresPassword = !capabilities.AuthMethod.Equals("noauth", StringComparison.OrdinalIgnoreCase);
    }

    internal void FocusBack()
    {
        if (!_back.Focus(FocusState.Keyboard))
            DispatcherQueue.TryEnqueue(() => { if (IsCurrent) _back.Focus(FocusState.Keyboard); });
    }

    internal Task RefreshAsync() => RefreshAdminAsync();

    private static Button CreateUserButton(string label, string id, bool primary = false)
    {
        var button = new Button { Content = label, Style = SurfaceGeometry.Style(primary ? "FwPrimaryButtonStyle" : "FwSecondaryButtonStyle") };
        AutomationProperties.SetAutomationId(button, id);
        return button;
    }

    private MenuFlyoutItem AddAction(string label, string id, MutationKind kind)
    {
        var item = new MenuFlyoutItem { Text = label };
        AutomationProperties.SetAutomationId(item, id);
        item.Click += async (_, _) => await MutateSelectedAsync(kind);
        _adminActions.Items.Add(item);
        return item;
    }

    private void SetBusy(bool busy)
    {
        _busy = busy;
        UpdateAdminButtons();
    }

    private void UpdateAdminButtons()
    {
        bool ready = IsCurrent && !_busy && !_refreshing;
        var user = SelectedAdminUser();
        bool canEdit = ready && user is not null;
        _adminCreate.IsEnabled = _adminRefresh.IsEnabled = ready;
        _adminPolicy.IsEnabled = _more.IsEnabled = canEdit;
        _adminPassword.IsEnabled = _adminToggleAdmin.IsEnabled = _adminToggleLock.IsEnabled = canEdit;
        _adminDelete.IsEnabled = canEdit && user is not null && !IsOwnAccount(user);
        _adminToggleAdmin.Text = user?.IsAdmin == true ? "收回管理员权限…" : "设为管理员…";
        _adminToggleLock.Text = user?.LockPassword == true ? "解除密码锁定…" : "锁定密码…";
    }

    private AdminUser? SelectedAdminUser() => (_adminList.SelectedItem as ListViewItem)?.Tag as AdminUser;

    private bool IsOwnAccount(AdminUser user) =>
        user.Id.ToString(CultureInfo.InvariantCulture).Equals(_account.ServerUserId, StringComparison.Ordinal) ||
        user.Username.Equals(_sessionAdminUsername, StringComparison.OrdinalIgnoreCase);

    private async Task RefreshAdminAsync()
    {
        if (!IsCurrent || _refreshing) return;
        _refreshing = true;
        _listSummary.Text = "正在读取用户列表…";
        if (!_hasList) ShowListState("正在读取当前服务器的用户…");
        UpdateAdminButtons();
        try
        {
            var users = await _adminUsers.ListAsync(_account, _token);
            if (!IsCurrent) return;
            ApplyRows(users);
            _hasList = true;
            if (_adminNotice.IsOpen && _adminNotice.Title == "用户列表未能读取") _adminNotice.IsOpen = false;
            ShowListState(_rows.Count == 0 ? "当前服务器没有用户。" : "");
        }
        catch (OperationCanceledException)
        {
            if (IsCurrent && !_hasList) ShowListState("用户列表读取已取消。请选择「刷新列表」重试。");
        }
        catch (Exception error)
        {
            if (!IsCurrent) return;
            if (!_hasList) ShowListState("用户列表暂不可用。请选择「刷新列表」重试。");
            PresentAdminError("用户列表未能读取", error is FilewayException known ? known.Error.Message : "请检查服务器连接后刷新列表。", InfoBarSeverity.Error);
        }
        finally
        {
            _refreshing = false;
            if (IsCurrent)
            {
                _listSummary.Text = _hasList ? $"共 {_rows.Count} 位用户" : "用户列表";
                UpdateAdminButtons();
            }
        }
    }

    private void ApplyRows(IReadOnlyList<AdminUser> users)
    {
        var selected = SelectedAdminUser()?.Id;
        var ids = users.Select(user => user.Id).ToHashSet();
        foreach (var id in _rows.Keys.Where(id => !ids.Contains(id)).ToArray())
        {
            _adminList.Items.Remove(_rows[id]);
            _rows.Remove(id);
        }

        foreach (var (user, index) in users.Select((user, index) => (user, index)))
        {
            if (!_rows.TryGetValue(user.Id, out var item))
            {
                item = new ListViewItem { Style = SurfaceGeometry.Style("FwListRecordItemStyle") };
                _rows.Add(user.Id, item);
                _adminList.Items.Insert(index, item);
            }
            if (item.Tag is not AdminUser previous || previous.Username != user.Username ||
                previous.IsAdmin != user.IsAdmin || previous.LockPassword != user.LockPassword)
                RenderUser(item, user);
            item.Tag = user;
            if (index < _adminList.Items.Count && ReferenceEquals(_adminList.Items[index], item)) continue;
            _adminList.Items.Remove(item);
            _adminList.Items.Insert(index, item);
        }
        if (selected is { } idToRestore && _rows.TryGetValue(idToRestore, out var selection))
            _adminList.SelectedItem = selection;
    }

    private static void RenderUser(ListViewItem item, AdminUser user)
    {
        var role = user.IsAdmin ? "管理员" : "普通用户";
        var summary = user.LockPassword ? $"{role} · 密码已锁定" : role;
        var row = new Grid { ColumnSpacing = 12, Padding = new Thickness(10, 8, 10, 8) };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var name = new TextBlock { Text = user.Username, FontWeight = FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis };
        ToolTipService.SetToolTip(name, user.Username);
        row.Children.Add(name);
        var status = new TextBlock { Text = summary, Style = SurfaceGeometry.Style("FwCaptionTextStyle"), VerticalAlignment = VerticalAlignment.Center };
        Grid.SetColumn(status, 1);
        row.Children.Add(status);
        AutomationProperties.SetName(item, user.Username);
        AutomationProperties.SetItemStatus(item, summary);
        item.Content = row;
    }

    private void ShowListState(string text)
    {
        _listState.Text = text;
        _listState.Visibility = text.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
    }

    private async Task<ContentDialogResult> ShowDialogAsync(ContentDialog dialog)
    {
        if (!IsCurrent || _refreshing || _activeDialog is not null || XamlRoot is null) return ContentDialogResult.None;
        _activeDialog = dialog;
        UpdateAdminButtons();
        try
        {
            var result = await _showDialog(dialog);
            return IsCurrent ? result : ContentDialogResult.None;
        }
        finally
        {
            _activeDialog = null;
            if (IsCurrent) UpdateAdminButtons();
        }
    }

    private void AddAdminStatus(string message, InfoBarSeverity severity)
    {
        if (!IsCurrent) return;
        if (severity is InfoBarSeverity.Error or InfoBarSeverity.Warning)
        {
            PresentAdminError("用户管理", message, severity);
            return;
        }
        _adminNotice.IsOpen = false;
        _feedback.Title = message;
        _feedback.Severity = severity;
        FloatingNotice.Show(_feedback, message, repeat: true, compact: true);
    }

    private void PresentAdminError(string title, string message, InfoBarSeverity severity)
    {
        _feedback.IsOpen = false;
        _adminNotice.Title = title;
        _adminNotice.Message = message;
        _adminNotice.Severity = severity;
        FloatingNotice.Show(_adminNotice);
    }

    private enum MutationKind { ResetPassword, ToggleAdmin, ToggleLock, Delete }

    private async Task CreateAdminUserAsync()
    {
        if (!IsCurrent || _busy || _refreshing) return;
        var username = new TextBox { Header = "用户名", Width = 320 };
        var password = new PasswordBox { Header = "初始密码", Width = 320 };
        var admin = new CheckBox { Content = "管理员权限" };
        var download = new CheckBox { Content = "下载", IsChecked = true };
        var share = new CheckBox { Content = "分享" };
        download.Unchecked += (_, _) => { share.IsChecked = false; share.IsEnabled = false; };
        download.Checked += (_, _) => share.IsEnabled = true;
        var create = new CheckBox { Content = "上传/新建" };
        var modify = new CheckBox { Content = "修改" };
        var rename = new CheckBox { Content = "重命名" };
        var delete = new CheckBox { Content = "删除" };
        var execute = new CheckBox { Content = "执行命令" };
        var permissions = new StackPanel { Spacing = 4 };
        foreach (var permission in new[] { download, share, create, modify, rename, delete, execute })
            permissions.Children.Add(permission);
        var panel = new StackPanel { Spacing = 10, MinWidth = 320 };
        panel.Children.Add(username);
        panel.Children.Add(password);
        panel.Children.Add(admin);
        panel.Children.Add(new TextBlock { Text = "权限", FontWeight = FontWeights.SemiBold });
        panel.Children.Add(permissions);
        var dialog = new ContentDialog
        {
            Title = "新建用户", Content = new ScrollViewer
            {
                MaxHeight = DialogOrdering.ContentMaxHeight(this, 472, reserved: 180, minimum: 160),
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = panel,
            },
            PrimaryButtonText = "创建", CloseButtonText = "取消",
            DefaultButton = ContentDialogButton.Primary, IsPrimaryButtonEnabled = false,
        };
        void Validate() => dialog.IsPrimaryButtonEnabled = username.Text.Trim().Length > 0 && password.Password.Length > 0;
        username.TextChanged += (_, _) => Validate();
        password.PasswordChanged += (_, _) => Validate();
        string initialPassword;
        try
        {
            if (await ShowDialogAsync(dialog) != ContentDialogResult.Primary || !IsCurrent) return;
            initialPassword = password.Password;
        }
        finally { password.Password = ""; }

        string name = username.Text.Trim();
        var adminPassword = _adminRequiresPassword
            ? await ConfirmAdminActionAsync($"创建用户 {name}", "创建", "在当前服务器创建此用户。")
            : "";
        if (adminPassword is null || !IsCurrent) return;
        SetBusy(true);
        try
        {
            var allowed = new PermissionSet(download.IsChecked == true, create.IsChecked == true, rename.IsChecked == true,
                modify.IsChecked == true, delete.IsChecked == true, execute.IsChecked == true, Share: share.IsChecked == true);
            await _adminUsers.CreateAsync(_account, new AdminUserCreate(name, initialPassword, admin.IsChecked == true, allowed), adminPassword, _token);
            AddAdminStatus($"用户 {name} 已创建。", InfoBarSeverity.Success);
            await RefreshAdminAsync();
        }
        catch (OperationCanceledException) { }
        catch (Exception error) { await ReportAdminMutationFailureAsync(error, "创建未能完成。"); }
        finally { SetBusy(false); }
    }

    private async Task<string?> ConfirmAdminActionAsync(string title, string confirmLabel, string description)
    {
        if (!IsCurrent) return null;
        var panel = new StackPanel { Spacing = 10, MinWidth = 320, MaxWidth = 420 };
        panel.Children.Add(new TextBlock { Text = description, TextWrapping = TextWrapping.Wrap });
        var password = new PasswordBox { Header = "当前管理员密码" };
        bool requiresPassword = _adminRequiresPassword;
        if (requiresPassword) panel.Children.Add(password);
        var dialog = new ContentDialog
        {
            Title = title, Content = panel, PrimaryButtonText = confirmLabel, CloseButtonText = "取消",
            DefaultButton = ContentDialogButton.Close, IsPrimaryButtonEnabled = !requiresPassword,
        };
        password.PasswordChanged += (_, _) => dialog.IsPrimaryButtonEnabled = password.Password.Length > 0;
        try
        {
            if (await ShowDialogAsync(dialog) != ContentDialogResult.Primary || !IsCurrent) return null;
            return requiresPassword ? password.Password : "";
        }
        finally { password.Password = ""; }
    }

    private async Task MutateSelectedAsync(MutationKind kind)
    {
        if (!IsCurrent || _busy || _refreshing || SelectedAdminUser() is not { } user) return;
        if (kind == MutationKind.Delete && IsOwnAccount(user)) return;
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
        if (kind == MutationKind.ResetPassword)
        {
            var box = new PasswordBox { Header = "新密码", Width = 320 };
            var dialog = new ContentDialog
            {
                Title = title, Content = box, PrimaryButtonText = "继续", CloseButtonText = "取消",
                DefaultButton = ContentDialogButton.Primary, IsPrimaryButtonEnabled = false,
            };
            box.PasswordChanged += (_, _) => dialog.IsPrimaryButtonEnabled = box.Password.Length > 0;
            try
            {
                if (await ShowDialogAsync(dialog) != ContentDialogResult.Primary || !IsCurrent) return;
                newPassword = box.Password;
            }
            finally { box.Password = ""; }
        }
        var adminPassword = await ConfirmAdminActionAsync(title, confirmLabel, kind == MutationKind.Delete
            ? "此用户将被永久删除，账户设置无法恢复。"
            : "此更改将在当前服务器生效。");
        if (adminPassword is null || !IsCurrent) return;
        SetBusy(true);
        try
        {
            if (kind == MutationKind.Delete)
                await _adminUsers.DeleteAsync(_account, user.Id, adminPassword, _token);
            else
                await _adminUsers.UpdateAsync(_account, new AdminUserUpdate(user.Id, newPassword,
                    kind == MutationKind.ToggleAdmin ? !user.IsAdmin : null,
                    kind == MutationKind.ToggleLock ? !user.LockPassword : null, null), adminPassword, _token);
            AddAdminStatus(kind switch
            {
                MutationKind.ResetPassword => $"{user.Username} 的密码已重置。",
                MutationKind.ToggleAdmin => $"{user.Username} 的管理员权限已{(user.IsAdmin ? "收回" : "授予")}。",
                MutationKind.ToggleLock => $"{user.Username} 的密码锁定已{(user.LockPassword ? "解除" : "启用")}。",
                _ => $"用户 {user.Username} 已删除。",
            }, InfoBarSeverity.Success);
            await RefreshAdminAsync();
        }
        catch (OperationCanceledException) { }
        catch (Exception error) { await ReportAdminMutationFailureAsync(error, "操作未能完成。"); }
        finally { SetBusy(false); }
    }

    private async Task ReportAdminMutationFailureAsync(Exception error, string fallback)
    {
        if (error is FilewayException known && (known.Error.Code is ErrorCode.OutcomeUnknown or ErrorCode.Conflict ||
            known.Error.HttpStatus is >= 500))
        {
            await RefreshAdminAsync();
            AddAdminStatus("请核对服务器上的当前结果。应用没有重复提交。", InfoBarSeverity.Warning);
        }
        else AddAdminStatus(error is FilewayException fileway ? fileway.Error.Message : fallback, InfoBarSeverity.Error);
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _lifetime.Cancel();
        _activeDialog?.Hide();
        _adminActions.Hide();
        _adminList.Items.Clear();
        _rows.Clear();
        _adminNotice.IsOpen = _feedback.IsOpen = false;
        FloatingNotice.Detach(_adminNotice);
        FloatingNotice.Detach(_feedback);
        _lifetime.Dispose();
    }
}
