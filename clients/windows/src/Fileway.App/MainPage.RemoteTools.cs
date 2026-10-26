using Fileway.Core.Contracts;
using Fileway.App.Controls.StorageTools;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Windows.System;

namespace Fileway.App;

public sealed partial class MainPage
{
    private StorageAnalysisControl? _storageAnalysisView;
    private bool CanShowRemoteTools => !_disposed && !_connecting && _session is not null &&
        _browserDialog is null && _storageDialog is null && !CollectionsPanel.HasOpenDialog && !NasTasksPane.HasOpenDialog && !TrashPane.HasOpenDialog &&
        ConnectionPanel.Visibility != Visibility.Visible;

    private void InitializeRemoteTools()
    {
        SearchPane.OpenResourceAsync = OpenCollectionResourceAsync;
        SearchPane.CloseRequested += (_, _) => ReturnFromRemoteTools();
        NasTasksPane.CloseRequested += (_, _) => ReturnFromRemoteTools();
        TrashPane.CloseRequested += (_, _) => ReturnFromRemoteTools();
        UpdateRemoteToolsContext();
    }

    private void ReturnFromRemoteTools()
    {
        if (_disposed) return;
        var wasTrash = TrashPane.Visibility == Visibility.Visible;
        HideCollections();
        foreach (var item in _uploadItems.Values.ToArray()) TryRefreshCompletedUpload(item);
        ActiveFiles.Focus(FocusState.Keyboard);
        // Restoration returns display metadata, never a resource URL. Re-read the
        // original directory instead of deriving wire identity from that response.
        if (wasTrash && BrowserReady && _directory is { } directory) _ = LoadAsync(directory);
    }

    private void UpdateRemoteToolsContext()
    {
        if (SearchPane is null || NasTasksPane is null || TrashPane is null) return;
        var session = !_disposed && !_connecting ? _session : null;
        SearchPane.SetContext(_files as IServerSearchService, _files, session, _directory ?? _root);
        NasTasksPane.SetContext(_files as IServerTaskService, Operations, session);
        TrashPane.SetContext(_files as ITrashService, Operations, session);
        if (session is null) HideRemoteToolsPanes();
    }

    private void HideRemoteToolsPanes()
    {
        if (SearchPane is not null) { SearchPane.Visibility = Visibility.Collapsed; SearchPane.SuspendView(); }
        if (NasTasksPane is not null) { NasTasksPane.Visibility = Visibility.Collapsed; NasTasksPane.SuspendView(); }
        if (TrashPane is not null) { TrashPane.Visibility = Visibility.Collapsed; TrashPane.SuspendView(); }
        RetireStorageAnalysis();
    }

    private void RetireStorageAnalysis()
    {
        _storageAnalysisView?.Dispose();
        _storageAnalysisView = null;
        if (StorageToolsHost is not null) { StorageToolsHost.Visibility = Visibility.Collapsed; StorageToolsHost.Content = null; }
    }

    private async Task ShowSearchAsync(string? query = null)
    {
        if (!CanShowRemoteTools || _files is not IServerSearchService || (_directory ?? _root) is null) return;
        // Re-focusing an active search must not retire its stream or original results.
        if (SearchPane.Visibility == Visibility.Visible && string.IsNullOrWhiteSpace(query))
        { SearchPane.FocusQuery(); return; }
        CloseSidebarOverlay(); HideCollections(); UpdateRemoteToolsContext();
        Workspace.Visibility = Visibility.Collapsed;
        SearchPane.Visibility = Visibility.Visible;
        _thumbnailController.SetContext(null, null);
        UpdateSelection();
        if (string.IsNullOrWhiteSpace(query)) SearchPane.FocusQuery();
        else await SearchPane.SearchAsync(query, SearchScope.Recursive);
    }

    private async void ServerSearch_Click(object sender, RoutedEventArgs args) => await ShowSearchAsync();
    private async void Search_QuerySubmitted(AutoSuggestBox sender, AutoSuggestBoxQuerySubmittedEventArgs args)
    {
        // Explicit submission starts NAS search; typing alone remains a local list filter.
        // AutoSuggestBox suppresses submission while the input method is composing text.
        if (!string.IsNullOrWhiteSpace(args.QueryText)) await ShowSearchAsync(args.QueryText);
    }

    private void StorageTools_Click(object sender, RoutedEventArgs args)
    {
        if (!CanShowRemoteTools || _storageAnalysis is null || _session is not { } session || (_directory ?? _root) is not { } directory) return;
        if (StorageToolsHost.Visibility == Visibility.Visible) return;
        CloseSidebarOverlay(); HideCollections(); UpdateRemoteToolsContext();
        // A view owns one immutable account/directory context. Retiring it cancels
        // observation; a later visit cannot reuse its canceled token or old callbacks.
        var view = new StorageAnalysisControl();
        view.Attach(_storageAnalysis, _storageAnalysis as IDuplicateAnalysisService);
        view.SetContext(directory);
        if (_storageAnalysis is IAdminUserService admin) view.AttachAdmin(admin, session.DisplayName);
        _storageAnalysisView = view;
        StorageToolsHost.Content = view;
        Workspace.Visibility = Visibility.Collapsed;
        StorageToolsHost.Visibility = Visibility.Visible;
        _thumbnailController.SetContext(null, null);
        UpdateSelection();
    }

    private async void NasTasks_Click(object sender, RoutedEventArgs args)
    {
        if (!CanShowRemoteTools || _files is not IServerTaskService) return;
        CloseSidebarOverlay(); HideCollections(); UpdateRemoteToolsContext();
        Workspace.Visibility = Visibility.Collapsed;
        NasTasksPane.Visibility = Visibility.Visible;
        _thumbnailController.SetContext(null, null);
        UpdateSelection();
        await NasTasksPane.RefreshAsync();
    }

    private void AddSearchAccelerator(UIElement owner)
    {
        var accelerator = new KeyboardAccelerator { Key = VirtualKey.F, Modifiers = VirtualKeyModifiers.Control };
        accelerator.Invoked += async (_, args) =>
        {
            if (!CanShowRemoteTools || _files is not IServerSearchService) return;
            args.Handled = true;
            await ShowSearchAsync();
        };
        owner.KeyboardAccelerators.Add(accelerator);
    }

    private async void TrashPage_Click(object sender, RoutedEventArgs args)
    {
        if (!CanShowRemoteTools || _files is not ITrashService) return;
        CloseSidebarOverlay(); HideCollections(); UpdateRemoteToolsContext();
        Workspace.Visibility = Visibility.Collapsed;
        TrashPane.Visibility = Visibility.Visible;
        _thumbnailController.SetContext(null, null);
        UpdateSelection();
        await TrashPane.RefreshAsync();
    }
}
