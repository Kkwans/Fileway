using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;

namespace Fileway.App;

public sealed partial class MainPage
{
    private IRecentAccessService? _recentAccess;

    private void InitializeRecentAccess()
    {
        RecentPane.OpenResourceAsync = OpenCollectionResourceAsync;
        RecentPane.CloseRequested += (_, _) =>
        {
            HideCollections();
            // Completion while history hid the browser must recheck its original folder fence.
            foreach (var item in _uploadItems.Values.ToArray()) TryRefreshCompletedUpload(item);
            ActiveFiles.Focus(FocusState.Keyboard);
        };
        UpdateRecentAccessContext();
    }

    private void UpdateRecentAccessContext()
    {
        if (RecentPane is null) return;
        var session = !_disposed && !_connecting ? _session : null;
        RecentPane.SetContext(_recentAccess, _files, session);
        RecentAccessButton.IsEnabled = session is not null && _recentAccess is not null && _files is not null;
        if (!RecentAccessButton.IsEnabled) HideRecentAccessPane();
    }

    private void HideRecentAccessPane()
    {
        if (RecentPane is null) return;
        RecentPane.Visibility = Visibility.Collapsed;
        RecentPane.SuspendView();
    }

    private async void RecentAccess_Click(object sender, RoutedEventArgs e)
    {
        if (_disposed || _connecting || _session is null || _recentAccess is null || _files is null
            || _browserDialog is not null || _storageDialog is not null || CollectionsPanel.HasOpenDialog) return;
        CloseSidebarOverlay(); HideCollections();
        UpdateRecentAccessContext();
        Workspace.Visibility = Visibility.Collapsed;
        RecentPane.Visibility = Visibility.Visible;
        _thumbnailController.SetContext(null, null);
        UpdateSelection();
        await RecentPane.RefreshAsync();
    }
}
