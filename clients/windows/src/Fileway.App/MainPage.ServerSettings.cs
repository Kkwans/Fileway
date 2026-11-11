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

    private void UpdateServerSettingsContext()
    {
        var session = !_disposed && !_connecting ? _session : null;
        bool admin = session?.Permissions.Admin == true;
        bool share = session?.Permissions is { Share: true, Download: true };
        SettingsPane?.SetServerManagement(admin && _files is IServerSettingsService,
            admin && _storageAnalysis is IAdminUserService, share && _files is IShareService);
        _storageAnalysisView?.SetAdminAvailability(admin, session?.Capabilities.EnableExec,
            session?.Capabilities.AuthMethod != "noauth");
        if (_serverSettingsCurrent?.Invoke() == false)
            _serverSettingsDialog?.Hide();
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
            CloseButtonText = "关闭",
            Content = content,
        };
        dialog.Closing += (_, args) => { if (content.IsSaving && IsCurrent()) args.Cancel = true; };
        _serverSettingsDialog = dialog;
        _serverSettingsCurrent = IsCurrent;
        try { await ShowBrowserDialogAsync(dialog); }
        finally { _serverSettingsDialog = null; _serverSettingsCurrent = null; }
    }
}
