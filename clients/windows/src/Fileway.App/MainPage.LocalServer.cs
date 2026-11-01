using Fileway.App.Controls.Server;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App;

public sealed partial class MainPage
{
    /// <summary>The page owns its window handle for pickers, so context arrives after the window exists.</summary>
    public void SetLocalServerManagement(ILocalServerManagementService service)
    {
        if (_disposed) return;
        LocalServerPane.SetContext(service, _storageOwnerHwnd);
        LocalServerPane.ConnectRequested += LocalServer_ConnectRequested;
        LocalServerPane.CloseRequested += (_, _) => ReturnFromLocalServer();
    }

    private async void LocalServer_Click(object sender, RoutedEventArgs args)
    {
        if (_disposed || _connecting) return;
        CloseSidebarOverlay();
        HideCollections();
        HideRemoteToolsPanes();
        HideRecentAccessPane();
        TransfersPanel.Visibility = Visibility.Collapsed;
        Workspace.Visibility = Visibility.Collapsed;
        ConnectionPanel.Visibility = Visibility.Collapsed;
        LocalServerPane.SetReturnContext(_session is not null && _directory is not null);
        LocalServerPane.Visibility = Visibility.Visible;
        await LocalServerPane.ShowAsync();
        UpdateApplicationMenus();
    }

    private void ReturnFromLocalServer()
    {
        if (_disposed) return;
        LocalServerPane.Visibility = Visibility.Collapsed;
        LocalServerPane.SuspendView();
        if (_session is null || _directory is null) SetConnectionView(true);
        else
        {
            Workspace.Visibility = Visibility.Visible;
            ActiveFiles.Focus(FocusState.Keyboard);
        }
        UpdateApplicationMenus();
    }

    /// <summary>Hands the loopback endpoint to the connection form; the page never builds URLs itself.</summary>
    private void LocalServer_ConnectRequested(object? sender, LocalServerConnectRequest request)
    {
        if (_disposed) return;
        LocalServerPane.Visibility = Visibility.Collapsed;
        LocalServerPane.SuspendView();
        SetConnectionView(true);
        ServerBox.Text = request.Endpoint;
        UsernameBox.Text = request.Username;
        PasswordBox.Password = string.Empty;
        _ = PasswordBox.Focus(FocusState.Programmatic);
        SetNotice("本地服务端", "地址与用户名已填好，输入部署账号的密码即可连接。可在“本地服务端”页面查看密码。", InfoBarSeverity.Informational);
    }
}
