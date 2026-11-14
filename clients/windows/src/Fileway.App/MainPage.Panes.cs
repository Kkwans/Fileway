using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App;

public sealed partial class MainPage
{
    private enum MainPane
    {
        Connection,
        Browser,
        Recent,
        Search,
        Tasks,
        Trash,
        Transfers,
        Storage,
        Collections,
        LocalServer,
        Settings,
        ServerUsers,
    }

    private MainPane _currentPane = MainPane.Connection;

    private void ShowPane(MainPane pane)
    {
        if (_disposed || Workspace is null || ConnectionPanel is null) return;
        var collectionStatusChanged = _currentPane != pane &&
            (_currentPane == MainPane.Collections || pane == MainPane.Collections);
        if (_currentPane != pane && pane is MainPane.Search or MainPane.Trash)
        {
            if (Notice.Severity == InfoBarSeverity.Success) Notice.IsOpen = false;
            foreach (var notice in _noticeLayouts.Keys)
                if (Controls.FloatingNotice.IsCompact(notice)) notice.IsOpen = false;
        }
        if (_currentPane != pane) ++_navigationRevision;
        _currentPane = pane;
        if (pane != MainPane.ServerUsers) RetireServerUsers();

        Workspace.Visibility = pane == MainPane.Browser ? Visibility.Visible : Visibility.Collapsed;
        ConnectionPanel.Visibility = pane == MainPane.Connection ? Visibility.Visible : Visibility.Collapsed;
        if (RecentPane is not null) RecentPane.Visibility = pane == MainPane.Recent ? Visibility.Visible : Visibility.Collapsed;
        if (SearchPane is not null) SearchPane.Visibility = pane == MainPane.Search ? Visibility.Visible : Visibility.Collapsed;
        if (NasTasksPane is not null) NasTasksPane.Visibility = pane == MainPane.Tasks ? Visibility.Visible : Visibility.Collapsed;
        if (TrashPane is not null) TrashPane.Visibility = pane == MainPane.Trash ? Visibility.Visible : Visibility.Collapsed;
        if (TransfersPanel is not null) TransfersPanel.Visibility = pane == MainPane.Transfers ? Visibility.Visible : Visibility.Collapsed;
        if (StorageToolsHost is not null) StorageToolsHost.Visibility = pane == MainPane.Storage ? Visibility.Visible : Visibility.Collapsed;
        _storageAnalysisView?.SetViewActive(pane == MainPane.Storage);
        if (CollectionsPanel is not null) CollectionsPanel.Visibility = pane == MainPane.Collections ? Visibility.Visible : Visibility.Collapsed;
        if (collectionStatusChanged) UpdateReceiptWarning();
        if (LocalServerPane is not null) LocalServerPane.Visibility = pane == MainPane.LocalServer ? Visibility.Visible : Visibility.Collapsed;
        if (SettingsPane is not null) SettingsPane.Visibility = pane == MainPane.Settings ? Visibility.Visible : Visibility.Collapsed;
        if (ServerUsersHost is not null) ServerUsersHost.Visibility = pane == MainPane.ServerUsers ? Visibility.Visible : Visibility.Collapsed;

        if (pane != MainPane.Recent) RecentPane?.SuspendView();
        if (pane != MainPane.Search) SearchPane?.SuspendView();
        if (pane != MainPane.Tasks) NasTasksPane?.SuspendView();
        if (pane != MainPane.Trash) TrashPane?.SuspendView();
        if (pane != MainPane.Collections) CollectionsPanel?.SuspendView();
        if (pane != MainPane.LocalServer) LocalServerPane?.SuspendView();

        if (pane == MainPane.Connection)
        {
            PaneButton.Visibility = Visibility.Collapsed;
            SidebarColumn.Width = new GridLength(0);
            Sidebar.Visibility = Visibility.Collapsed;
            SidebarResize.Visibility = Visibility.Collapsed;
            SidebarDismiss.Visibility = Visibility.Collapsed;
        }
        else
        {
            PaneButton.Visibility = Visibility.Visible;
            UpdateSidebarLayout();
        }

        UpdateApplicationMenus();
        UpdateNetworkPane();
        UpdateThumbnailContext();
        UpdateSelection();
    }

    private void ReturnToBrowserOrConnection() =>
        ShowPane(_currentPane == MainPane.Connection || _session is null || _directory is null
            ? MainPane.Connection
            : MainPane.Browser);
}
