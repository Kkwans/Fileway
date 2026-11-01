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
    private async void LocalServer_ConnectRequested(object? sender, LocalServerConnectRequest request)
    {
        if (_disposed) return;
        LocalServerPane.Visibility = Visibility.Collapsed;
        LocalServerPane.SuspendView();
        SetConnectionView(true);
        ServerBox.Text = request.Endpoint;
        if (request.Username is not string username)
        {
            // A server this client did not deploy keeps its own secret; only the address is known.
            PasswordBox.Password = string.Empty;
            SetNotice("本地服务端", "地址已填好，输入该服务端的账号和密码即可连接。", InfoBarSeverity.Informational);
            _ = UsernameBox.Focus(FocusState.Programmatic);
            return;
        }
        AuthenticationBox.SelectedIndex = 0;
        UsernameBox.Text = username;
        PasswordBox.Password = request.Password ?? string.Empty;
        await ConnectFromFormAsync();
    }
}
