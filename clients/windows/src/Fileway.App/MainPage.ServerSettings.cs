using Fileway.App.Controls;
using Fileway.App.Controls.Settings;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App;

public sealed partial class MainPage
{
    private ContentDialog? _serverSettingsDialog;
    private Func<bool>? _serverSettingsCurrent;
    private ServerUserManagementControl? _serverUsersView;
    private IAdminUserService? ServerAdminUsers => _files as IAdminUserService ?? _storageAnalysis as IAdminUserService;

    private void UpdateServerSettingsContext()
    {
        var session = !_disposed && !_connecting ? _session : null;
        bool admin = session?.Permissions.Admin == true;
        bool share = session?.Permissions is { Share: true, Download: true };
        SettingsPane?.SetServerManagement(admin && _files is IServerSettingsService,
            admin && ServerAdminUsers is not null, share && _files is IShareService);
        if (_serverUsersView is { } users)
        {
            if (session is not null && users.IsCurrent) users.UpdateCapabilities(session.Capabilities);
            else if (_currentPane == MainPane.ServerUsers) ShowPane(session is null ? MainPane.Connection : MainPane.Settings);
            else RetireServerUsers();
        }
        if (_serverSettingsCurrent?.Invoke() == false)
            _serverSettingsDialog?.Hide();
    }

    private void OpenServerUsers()
    {
        if (_currentPane != MainPane.Settings || !CanShowRemoteTools ||
            _session is not { Permissions.Admin: true } session || ServerAdminUsers is not { } service) return;

        CloseSidebarOverlay();
        bool IsCurrent() => !_disposed && !_connecting && _currentPane == MainPane.ServerUsers &&
            ReferenceEquals(ServerAdminUsers, service) && _session is { Permissions.Admin: true } current &&
            current.Id == session.Id && current.Account == session.Account;
        var view = new ServerUserManagementControl(service, session, IsCurrent, ShowBrowserDialogAsync);
        view.CloseRequested += (_, _) =>
        {
            if (!ReferenceEquals(_serverUsersView, view) || !IsCurrent()) return;
            ShowPane(MainPane.Settings);
            if (!SettingsPane.FocusServerUsers())
                DispatcherQueue.TryEnqueue(() =>
                {
                    if (_currentPane == MainPane.Settings) SettingsPane.FocusServerUsers();
                });
        };
        _serverUsersView = view;
        ServerUsersHost.Content = view;
        ShowPane(MainPane.ServerUsers);
        view.FocusBack();
        _ = view.RefreshAsync();
    }

    private void RetireServerUsers()
    {
        if (_serverUsersView is not { } view) return;
        _serverUsersView = null;
        view.Dispose();
        ServerUsersHost.Content = null;
    }

    private async void ServerSettings_Click(object sender, RoutedEventArgs e)
    {
        if (!CanShowRemoteTools || _session is not { Permissions.Admin: true } session || _files is not IServerSettingsService service) return;
        bool IsCurrent() => !_disposed && !_connecting && _session is { Permissions.Admin: true } current &&
            current.Id == session.Id && current.Account == session.Account;
        using var content = new ServerSettingsContent(service, session.Account, IsCurrent,
            DialogOrdering.ContentMaxHeight(this, 472, reserved: 230, minimum: 240), _lifetime.Token);
        var dialog = new ContentDialog
        {
            Title = "服务器全局设置",
            Content = content,
        };
        content.CloseRequested += (_, _) => dialog.Hide();
        dialog.Closing += (_, args) => { if (content.IsSaving && IsCurrent()) args.Cancel = true; };
        _serverSettingsDialog = dialog;
        _serverSettingsCurrent = IsCurrent;
        try { await ShowBrowserDialogAsync(dialog); }
        finally { _serverSettingsDialog = null; _serverSettingsCurrent = null; }
    }
}
