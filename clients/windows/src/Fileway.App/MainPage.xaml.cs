using System.Collections.ObjectModel;
using Fileway.Core.Contracts;
using Fileway.App.Views;
using Fileway.App.Branding;
using Fileway.App.Controls.Browser;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Windows.System;

namespace Fileway.App;

public sealed partial class MainPage : Page, IDisposable
{
    private IConnectionService? _connection;
    private IFileRepository? _files;
    private IResourceCollectionsService? _collections;
    private IBrowserPreferencesService? _browserPreferencesService;
    private BrowserPreferences _browserPreferences = new();
    private Task _preferenceSaves = Task.CompletedTask;
    private long _preferenceRevision;
    private bool _applyingPreferences;
    private IImageContentService? _images;
    private IThumbnailContentService? _thumbnails;
    private readonly BrowserThumbnailController _thumbnailController;
    private IPlaybackService? _playback;
    private IConnectionCatalog? _catalog;
    private ILocalStorageSettingsService? _storageSettings;
    private nint _storageOwnerHwnd;
    private ContentDialog? _storageDialog;
    private StorageSettingsControl? _storageControl;
    private ConnectionProfile? _selectedProfile;
    private CancellationTokenSource? _connectionCancellation;
    private bool _refreshingSavedConnections;
    private Task _connectionOperation = Task.CompletedTask;
    public Task ConnectionCompletion => _connectionOperation;
    private ConnectionSession? _session;
    private RemoteResourceRef? _root;
    private RemoteResourceRef? _directory;
    private readonly List<RemoteResourceRef> _trail = [];
    private readonly Stack<RemoteResourceRef[]> _history = [];
    private readonly List<RemoteFile> _items = [];
    private readonly ObservableCollection<FileRow> _rows = [];
    private readonly HashSet<ImageWindow> _imageWindows = [];
    private CancellationTokenSource? _loadCancellation;
    private readonly CancellationTokenSource _lifetime = new();
    private long _generation;
    private bool _connecting;
    private bool _disposed;
    private bool _changingView;
    private bool _detailsVisible;
    private string? _loadStateTitle;
    private string? _loadStateDescription;
    private ListViewBase ActiveFiles => FileGrid.Visibility == Visibility.Visible ? FileGrid : FileList;

    public MainPage()
    {
        InitializeComponent();
        InitializePresentation();
        _thumbnailController = new BrowserThumbnailController(_lifetime.Token, FileList, FileGrid);
        CollectionsPanel.CloseRequested += (_, _) => HideCollections();
        CollectionsPanel.OpenResourceAsync = OpenCollectionResourceAsync;
        CollectionsPanel.SnapshotChanged += (_, snapshot) =>
        {
            if (_disposed || _session?.Account != snapshot.Account) return;
            SidebarCollections.SetSnapshot(snapshot); UpdateCollectionActions();
        };
        CollectionsPanel.StatusChanged += (_, status) =>
        {
            // Background synchronization succeeds quietly; warnings and failures remain visible.
            if (!_disposed && CollectionsPanel.Visibility != Visibility.Visible && status.Severity != InfoBarSeverity.Success)
                SetNotice("收藏与标签", status.Message, status.Severity);
        };
        CollectionsPanel.CommandsChanged += (_, _) => UpdateCollectionActions();
        CollectionsPanel.ReceiptWarningChanged += (_, warning) => RememberReceiptWarning(warning.Account, warning.Message);
        SidebarCollections.FavoriteRequested += async (_, favorite) =>
        {
            if (_disposed || _connecting || _session?.Account != favorite.Account) return;
            HideCollections(); UpdateCollectionsContext(); await CollectionsPanel.OpenReferenceAsync(favorite.Reference);
        };
        SidebarCollections.TagRequested += async (_, tag) =>
        {
            if (_disposed || _connecting || _session?.Account != tag.Account) return;
            CloseSidebarOverlay();
            TransfersPanel.Visibility = Visibility.Collapsed; HideRecentAccessPane(); HideRemoteToolsPanes();
            UpdateCollectionsContext(); CollectionsPanel.Visibility = Visibility.Visible; Workspace.Visibility = Visibility.Collapsed;
            UpdateThumbnailContext(); await CollectionsPanel.ShowTagResourcesAsync(tag.Id);
        };
        FileList.ItemsSource = FileGrid.ItemsSource = _rows;
        FileGrid.Loaded += (_, _) => UpdateGridMetrics();
        FileGrid.SizeChanged += (_, _) => UpdateGridMetrics();
        InitializeBrowserShell();
        InitializeTransferCenter();
        InitializeRecentAccess();
        InitializeRemoteTools();
        InitializeApplicationMenus();
        ApplyBrowserPresentation();
        Loaded += (_, _) => { ApplyTheme(); UpdateThumbnailContext(); };
        Unloaded += (_, _) => _thumbnailController.SetContext(null, null);
    }

    public void SetStorageSettings(ILocalStorageSettingsService settings, nint ownerHwnd)
    {
        if (_disposed) return;
        _storageSettings = settings; _storageOwnerHwnd = ownerHwnd;
        StorageSettingsButton.IsEnabled = ownerHwnd != 0;
    }

    public void ShowStorageWarning(string message)
    {
        if (!_disposed) SetNotice("本地缓存暂不可用", message, InfoBarSeverity.Warning);
    }

    private async void StorageSettings_Click(object sender, RoutedEventArgs args)
    {
        if (_disposed || _storageSettings is null || _storageOwnerHwnd == 0 || _storageDialog is not null) return;
        using var control = new StorageSettingsControl(_storageSettings, _storageOwnerHwnd, _lifetime.Token);
        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot, Title = "缓存与存储", Content = control,
            PrimaryButtonText = "保存", CloseButtonText = "关闭", IsPrimaryButtonEnabled = false,
            RequestedTheme = Shell.RequestedTheme,
        };
        _storageDialog = dialog; _storageControl = control;
        dialog.Closed += (_, _) => control.Dispose();
        void StateChanged(object? source, EventArgs eventArgs) { if (!_disposed) dialog.IsPrimaryButtonEnabled = control.CanSave; }
        control.StateChanged += StateChanged;
        dialog.PrimaryButtonClick += async (_, click) =>
        {
            click.Cancel = true;
            var deferral = click.GetDeferral();
            try { await control.SaveAsync(); }
            finally { deferral.Complete(); }
        };
        try
        {
            var completion = dialog.ShowAsync();
            _ = LoadStorageControlAsync();
            await completion;
        }
        catch (OperationCanceledException) { }
        catch (Exception) { if (!_disposed) SetNotice("存储设置未能打开", "请关闭其他对话框后重试。", InfoBarSeverity.Error); }
        finally
        {
            control.StateChanged -= StateChanged;
            if (ReferenceEquals(_storageDialog, dialog)) { _storageDialog = null; _storageControl = null; }
        }
        async Task LoadStorageControlAsync()
        {
            try { await control.LoadAsync(); }
            catch (OperationCanceledException) { }
            catch (Exception)
            {
                if (!_disposed && ReferenceEquals(_storageDialog, dialog))
                    SetNotice("存储设置未能加载", "请关闭设置后重试。", InfoBarSeverity.Error);
            }
        }
    }
    public async Task SetServicesAsync(IConnectionService connection, IFileRepository files, IImageContentService images, IPlaybackService playback, IConnectionCatalog catalog, IThumbnailContentService? thumbnails = null)
    {
        _connection = connection; _files = files; _collections = files as IResourceCollectionsService; _browserPreferencesService = files as IBrowserPreferencesService; _recentAccess = files as IRecentAccessService; _images = images; _playback = playback; _catalog = catalog; _thumbnails = thumbnails;
        UpdateThumbnailContext();
        _connection.SessionUpdated += Connection_SessionUpdated;
        _connection.ConnectionIssue += Connection_Issue;
        ConnectButton.IsEnabled = true;
        SaveConnectionButton.IsEnabled = true;
        LoginNotice.IsOpen = false;
        Notice.IsOpen = false;
        UpdateApplicationMenus();
        try
        {
            await RefreshSavedConnectionsAsync(selectMostRecent: true);
            if (SavedConnectionBox.SelectedItem is ConnectionChoice { Saved.KeepSignedIn: true })
                await RestoreSelectedAsync(silent: true);
        }
        catch (OperationCanceledException) { }
        catch (Exception exception) { LoginError(ErrorMessage(exception)); }
    }
    public void ShowStartupError(string message) { LoginNotice.Severity = InfoBarSeverity.Error; LoginNotice.Message = message; SetNotice("核心服务启动失败", message, InfoBarSeverity.Error); }
    public void Shutdown() => Dispose();
    public void Dispose()
    {
        if (_disposed) return;
        _remoteClipboard.Clear();
        DisposePresentation();
        DisposeApplicationMenus();
        DisposeUploadPresentation();
        DisposeDownloadPresentation();
        TransfersPanel.Dispose();
        RetireChecksumDialog();
        _browserDialog?.Hide();
        _storageDialog?.Hide(); _storageControl?.Dispose();
        _disposed = true; CollectionsPanel.Dispose(); RecentPane.Dispose(); SearchPane.Dispose(); NasTasksPane.Dispose(); TrashPane.Dispose(); _thumbnailController.Dispose(); _lifetime.Cancel(); _loadCancellation?.Cancel();
        _connectionCancellation?.Cancel();
        if (_connection is not null)
        {
            _connection.SessionUpdated -= Connection_SessionUpdated;
            _connection.ConnectionIssue -= Connection_Issue;
        }
        foreach (var window in _imageWindows.ToArray()) window.Close();
        _connectionCancellation?.Dispose(); _loadCancellation?.Dispose(); _lifetime.Dispose();
    }

    private async void Connect_Click(object sender, RoutedEventArgs e) => await ConnectFromFormAsync();

    private async Task ConnectFromFormAsync()
    {
        if (_connection is null || _files is null || _connecting) return;
        if (!Uri.TryCreate(ServerBox.Text.Trim(), UriKind.Absolute, out var address) || (address.Scheme != "https" && address.Scheme != "http") || !string.IsNullOrEmpty(address.UserInfo) || !string.IsNullOrEmpty(address.Query) || !string.IsNullOrEmpty(address.Fragment))
        { LoginError("请输入有效的 HTTP 或 HTTPS 服务器地址，且不要包含账号、查询参数或片段。"); return; }
        var login = AuthenticationBox.SelectedIndex == 1
            ? new LoginInput(AuthenticationMode.NoAuthentication, KeepSignedIn: KeepSignedInBox.IsChecked == true)
            : new LoginInput(AuthenticationMode.Password, UsernameBox.Text, PasswordBox.Password, KeepSignedInBox.IsChecked == true);
        var displayName = string.IsNullOrWhiteSpace(ConnectionNameBox.Text) ? address.Host : ConnectionNameBox.Text.Trim();
        var profile = _selectedProfile is { } saved
            ? saved with { DisplayName = displayName, BaseAddress = address, SourceRevision = saved.BaseAddress.AbsoluteUri == address.AbsoluteUri ? saved.SourceRevision : Guid.NewGuid() }
            : new ConnectionProfile(Guid.NewGuid(), Guid.NewGuid(), displayName, address, NetworkMode.SystemNetwork, new ProxyPolicy(ProxyMode.Direct));
        await RunConnectionAsync(token => _connection.ConnectAsync(profile, login, token), "正在连接服务器…");
    }

    private Task RunConnectionAsync(Func<CancellationToken, Task<ConnectionSession>> connect, string? message, bool silent = false)
        => _connectionOperation = RunConnectionCoreAsync(connect, message, silent);

    private async Task RunConnectionCoreAsync(Func<CancellationToken, Task<ConnectionSession>> connect, string? message, bool silent)
    {
        if (_connection is null || _files is null || _connecting || _disposed) return;
        _connectionCancellation?.Dispose();
        _connectionCancellation = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
        var token = _connectionCancellation.Token;
        SetConnecting(true);
        if (silent)
            LoginNotice.IsOpen = false;
        else
        {
            LoginNotice.Severity = InfoBarSeverity.Informational;
            LoginNotice.Message = message ?? "正在连接服务器…";
            LoginNotice.IsOpen = true;
        }
        try
        {
            if (_session is not null)
            {
                foreach (var window in _imageWindows.ToArray()) window.Close();
                if (_playback is not null) await _playback.CloseAccountAsync(_session.Account, token);
                await _connection.DisconnectAsync(_session.Account, token);
                ClearActiveWorkspace();
            }
            _session = await connect(token);
            PasswordBox.Password = "";
            _root = await _files.GetRootAsync(_session, token);
            _trail.Clear(); _trail.Add(_root); _history.Clear();
            ConnectionLabel.Text = _session.DisplayName; SidebarConnectionActionLabel.Text = "切换连接";
            LogoutButton.Visibility = Visibility.Visible;
            DismissConnection.Visibility = Visibility.Visible; SetConnectionView(false);
            NavigationCommands.IsEnabled = !_connecting;
            await LoadAsync(_root);
            try { await RefreshSavedConnectionsAsync(); }
            catch (Exception exception) when (exception is not OperationCanceledException)
            { SetNotice("已连接，列表更新失败", ErrorMessage(exception), InfoBarSeverity.Warning); }
        }
        catch (OperationCanceledException) { LoginError("连接已取消。"); }
        catch (Exception exception) { LoginError(ErrorMessage(exception)); }
        finally { SetConnecting(false); PasswordBox.Password = ""; }
    }

    private void SetConnecting(bool connecting)
    {
        _connecting = connecting;
        CollectionsButton.IsEnabled = !connecting && _session is not null && _collections is not null;
        SidebarCollections.IsEnabled = CollectionsButton.IsEnabled;
        if (connecting) { HideCollections(); CollectionsPanel.SetContext(null, null, null, null); SidebarCollections.SetSnapshot(null); }
        UpdateThumbnailContext();
        ConnectButton.IsEnabled = !connecting && _connection is not null;
        SaveConnectionButton.IsEnabled = NewConnectionButton.IsEnabled = !connecting && _connection is not null;
        RestoreSavedButton.IsEnabled = ForgetSavedButton.IsEnabled = SavedConnectionBox.IsEnabled = !connecting;
        ConnectionNameBox.IsEnabled = ServerBox.IsEnabled = AuthenticationBox.IsEnabled = !connecting;
        UsernameBox.IsEnabled = PasswordBox.IsEnabled = KeepSignedInBox.IsEnabled = !connecting;
        CancelConnectionButton.Visibility = connecting ? Visibility.Visible : Visibility.Collapsed;
        NavigationCommands.IsEnabled = !connecting && _session is not null;
        SortBox.IsEnabled = !connecting;
        UpdateSelection();
        UpdateBreadcrumbs();
        if (!connecting && _session is not null && _collections is not null) _ = CollectionsPanel.RefreshAsync();
    }

    private void CancelConnection_Click(object sender, RoutedEventArgs args) => _connectionCancellation?.Cancel();

    private async Task RefreshSavedConnectionsAsync(bool selectMostRecent = false)
    {
        if (_catalog is null || _disposed) return;
        var saved = await _catalog.ListAsync(_lifetime.Token);
        if (_disposed) return;
        var selected = _selectedProfile?.Id;
        _refreshingSavedConnections = true;
        try
        {
            var choices = saved.Select(item => new ConnectionChoice(item)).ToArray();
            SavedConnectionBox.ItemsSource = choices;
            SavedConnectionsPanel.Visibility = choices.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
            SavedConnectionBox.SelectedItem = choices.FirstOrDefault(item => item.Saved.Profile.Id == selected)
                ?? (selectMostRecent ? choices.FirstOrDefault() : null);
        }
        finally { _refreshingSavedConnections = false; }
        FillSavedSelection();
    }

    private void SavedConnection_Changed(object sender, SelectionChangedEventArgs args)
    {
        if (!_refreshingSavedConnections && !_connecting) FillSavedSelection();
    }

    private void FillSavedSelection()
    {
        if (SavedConnectionBox.SelectedItem is not ConnectionChoice choice)
        {
            _selectedProfile = null;
            ConnectionModeTitle.Text = "新建连接";
            RestoreSavedButton.Visibility = ForgetSavedButton.Visibility = Visibility.Collapsed;
            return;
        }
        var saved = choice.Saved;
        _selectedProfile = saved.Profile;
        ConnectionModeTitle.Text = "编辑已保存连接";
        ConnectionNameBox.Text = saved.Profile.DisplayName;
        ServerBox.Text = saved.Profile.BaseAddress.AbsoluteUri;
        AuthenticationBox.SelectedIndex = saved.AuthenticationMode == AuthenticationMode.NoAuthentication ? 1 : 0;
        UsernameBox.Text = saved.Username;
        PasswordBox.Password = "";
        KeepSignedInBox.IsChecked = saved.KeepSignedIn;
        ForgetSavedButton.Visibility = Visibility.Visible;
        RestoreSavedButton.Visibility = saved.KeepSignedIn ? Visibility.Visible : Visibility.Collapsed;
    }

    private async void RestoreSaved_Click(object sender, RoutedEventArgs args) => await RestoreSelectedAsync();

    private async Task RestoreSelectedAsync(bool silent = false)
    {
        if (_connection is null || SavedConnectionBox.SelectedItem is not ConnectionChoice choice || _connecting) return;
        if (!choice.Saved.KeepSignedIn) { LoginError("此连接未保持登录，请输入密码重新连接。"); return; }
        await RunConnectionAsync(token => _connection.RestoreAsync(choice.Saved.Profile.Id, token), silent ? null : "正在恢复已保存连接…", silent);
    }

    private void NewConnection_Click(object sender, RoutedEventArgs args)
    {
        if (_connecting) return;
        _refreshingSavedConnections = true;
        try { SavedConnectionBox.SelectedItem = null; }
        finally { _refreshingSavedConnections = false; }
        _selectedProfile = null;
        ConnectionModeTitle.Text = "新建连接";
        ConnectionNameBox.Text = string.Empty;
        ServerBox.Text = string.Empty;
        AuthenticationBox.SelectedIndex = 0;
        UsernameBox.Text = string.Empty;
        PasswordBox.Password = string.Empty;
        KeepSignedInBox.IsChecked = true;
        RestoreSavedButton.Visibility = ForgetSavedButton.Visibility = Visibility.Collapsed;
        LoginNotice.IsOpen = false;
        ServerBox.Focus(FocusState.Programmatic);
    }

    private async void ForgetSaved_Click(object sender, RoutedEventArgs args)
    {
        if (_catalog is null || SavedConnectionBox.SelectedItem is not ConnectionChoice choice || _connecting) return;
        var confirm = new ContentDialog { XamlRoot = XamlRoot, Title = "忘记这个连接？", Content = "将移除本机连接档案与保存的会话。服务器上的文件不会删除。", PrimaryButtonText = "忘记连接", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Close };
        if (await confirm.ShowAsync() != ContentDialogResult.Primary) return;
        try
        {
            await _catalog.ForgetAsync(choice.Saved.Profile.Id, _lifetime.Token);
            if (_session?.Account.ProfileId == choice.Saved.Profile.Id)
            {
                if (_playback is not null) await _playback.CloseAccountAsync(_session.Account, _lifetime.Token);
                foreach (var window in _imageWindows.ToArray()) window.Close();
                ClearActiveWorkspace(); SetConnectionView(true);
            }
            await RefreshSavedConnectionsAsync();
            LoginNotice.Severity = InfoBarSeverity.Informational; LoginNotice.Message = "本机连接已移除。";
        }
        catch (OperationCanceledException) { }
        catch (Exception exception) { LoginError(ErrorMessage(exception)); }
    }

    private async void Logout_Click(object sender, RoutedEventArgs args)
    {
        if (_connection is null || _session is not { } session || _connecting) return;
        SetConnecting(true); LogoutButton.IsEnabled = false;
        try
        {
            await _connection.LogoutAsync(session.Account, _lifetime.Token);
            if (_playback is not null) await _playback.CloseAccountAsync(session.Account, _lifetime.Token);
            foreach (var window in _imageWindows.ToArray()) window.Close();
            ClearActiveWorkspace(); SetConnectionView(true);
            await RefreshSavedConnectionsAsync();
            LoginNotice.Severity = InfoBarSeverity.Informational; LoginNotice.Message = "已退出登录。连接档案保留，保存的会话已清除。";
        }
        catch (OperationCanceledException) { }
        catch (Exception exception) { SetNotice("退出登录尚未完成", ErrorMessage(exception), InfoBarSeverity.Error); }
        finally { LogoutButton.IsEnabled = true; SetConnecting(false); }
    }

    private void ClearActiveWorkspace()
    {
        _remoteClipboard.Clear();
        HideCollections();
        ClearUploadPresentation();
        ClearDownloadPresentation();
        _session = null; _root = null; _directory = null; ++_generation; _loadCancellation?.Cancel();
        CollectionsPanel.SetContext(null, null, null, null);
        SidebarCollections.SetSnapshot(null); SidebarCollections.IsEnabled = false;
        UpdateThumbnailContext();
        _items.Clear(); _rows.Clear(); _trail.Clear(); _history.Clear();
        NavigationCommands.IsEnabled = false; LogoutButton.Visibility = DismissConnection.Visibility = Visibility.Collapsed;
        ConnectionLabel.Text = "尚未连接"; SidebarConnectionActionLabel.Text = "连接服务器"; PathBox.Text = "尚未连接";
        UpdateBreadcrumbs(); ResetOperationContext();
        _loadStateTitle = _loadStateDescription = null;
        SetLoading(false); SetDetailsVisible(false); UpdateSelection(); ApplyFilter();
    }

    private void Connection_SessionUpdated(object? sender, ConnectionSession session) => DispatcherQueue.TryEnqueue(() =>
    {
        if (!_disposed && _session?.Id == session.Id && _session.Account == session.Account)
        { _session = session; ConnectionLabel.Text = session.DisplayName; UpdateSelection(); }
    });

    private void Connection_Issue(object? sender, ConnectionIssue issue) => DispatcherQueue.TryEnqueue(async () =>
    {
        if (_disposed || _session?.Id != issue.SessionId || _session.Account != issue.Account) return;
        SetNotice("连接状态需要处理", issue.Error.Message, InfoBarSeverity.Warning);
        if (!issue.SessionInvalidated) return;
        foreach (var window in _imageWindows.ToArray()) window.Close();
        ClearActiveWorkspace(); SetConnectionView(true); LoginError(issue.Error.Message);
        try { if (_playback is not null) await _playback.CloseAccountAsync(issue.Account, _lifetime.Token); }
        catch (OperationCanceledException) { }
        catch (Exception) { /* Session leases are already retired by the connection service. */ }
    });

    private async Task LoadAsync(RemoteResourceRef directory)
    {
        if (_files is null || _session is not { } session || _disposed) return;
        _loadCancellation?.Cancel(); _loadCancellation?.Dispose();
        _loadCancellation = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
        var token = _loadCancellation.Token;
        var generation = ++_generation;
        _thumbnailController.Invalidate();
        bool CurrentLoad() => !_disposed && generation == _generation && _session?.Id == session.Id && _session.Account == session.Account;
        _loadStateTitle = _loadStateDescription = null;
        _directory = directory; PathBox.Text = directory.WirePath; UpdateBreadcrumbs(); CloseSidebarOverlay();
        var preferenceRevision = ++_preferenceRevision;
        RestoreDirectoryTypeFilters(directory); _browserPreferences = new BrowserPreferences(); ApplyBrowserPresentation();
        _ = ReadBrowserPreferencesAsync(directory, session, generation, preferenceRevision, token);
        _items.Clear(); _rows.Clear(); SearchBox.Text = "";
        SetLoading(true); Notice.IsOpen = false; EmptyState.Visibility = Visibility.Collapsed;
        try
        {
            var query = Query();
            string? cursor = null;
            var seenCursors = new HashSet<string>(StringComparer.Ordinal);
            do
            {
                var page = await _files.ListAsync(directory, query with { Cursor = cursor }, token);
                if (!CurrentLoad()) return;
                token.ThrowIfCancellationRequested();
                _items.AddRange(page.Items); cursor = page.NextCursor;
                if (cursor is not null && !seenCursors.Add(cursor)) throw new FilewayException(new ErrorInfo(ErrorCode.ProtocolMismatch, "服务器返回了重复的分页标记。"));
                ApplyFilter();
            } while (cursor is not null);
            UpdateSelection();
        }
        catch (OperationCanceledException)
        {
            if (CurrentLoad()) { _loadStateTitle = "加载已取消"; _loadStateDescription = "可重新加载当前目录。已取得的项目会保留。"; SetNotice(_loadStateTitle, _loadStateDescription, InfoBarSeverity.Informational); }
        }
        catch (Exception exception)
        {
            if (CurrentLoad()) { _loadStateTitle = "无法加载目录"; _loadStateDescription = ErrorMessage(exception); SetNotice(_loadStateTitle, _loadStateDescription, InfoBarSeverity.Error); }
        }
        finally { if (CurrentLoad()) { SetLoading(false); ApplyFilter(); BackButton.IsEnabled = _history.Count > 0; UpButton.IsEnabled = _trail.Count > 1; } }
    }
    private DirectoryQuery Query() => new(_browserPreferences.SortBy, _browserPreferences.Descending);
    private void ApplyFilter()
    {
        var filter = SearchBox.Text.Trim();
        var filtered = _items.Where(item => item.Resource.DisplayName.Contains(filter, StringComparison.CurrentCultureIgnoreCase) && MatchesTypeFilters(item));
        var matching = SortFiles(filtered).ToArray();
        var append = _rows.Count <= matching.Length && _rows.Select((row, index) => ReferenceEquals(row.File, matching[index])).All(same => same);
        var selected = ActiveFiles.SelectedItems.Cast<FileRow>().Select(row => row.File.Resource).ToHashSet();
        _changingView = true;
        try
        {
            if (!append) { _thumbnailController.Invalidate(); _rows.Clear(); }
            for (var index = _rows.Count; index < matching.Length; index++) _rows.Add(new FileRow(matching[index], _browserPreferences));
            if (!append)
                foreach (var row in _rows.Where(row => selected.Contains(row.File.Resource))) ActiveFiles.SelectedItems.Add(row);
        }
        finally { _changingView = false; }
        UpdateSelection();
        if (LoadingRing.IsActive) return;
        EmptyState.Visibility = _rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        var filtering = filter.Length > 0 || _typeFilters.Count > 0;
        EmptyTitle.Text = _session is null ? "你的文件，在这里相遇" : _loadStateTitle ?? (filtering ? "没有匹配的文件" : "这个目录还没有文件");
        EmptyDescription.Text = _session is null ? "连接服务器后即可浏览文件、打开图片和播放视频。" : _loadStateDescription ?? (filtering ? "可清除名称或类型筛选；筛选仅覆盖当前目录。" : "可以刷新目录，查看服务器上的最新内容。");
        RetryLoadButton.Visibility = _session is not null && _loadStateTitle is not null ? Visibility.Visible : Visibility.Collapsed;
    }
    private async Task OpenSelectedAsync()
    {
        if (_disposed || _connecting || _session is not { } session || ActiveFiles.SelectedItem is not FileRow row) return;
        await OpenFileAsync(row, _items.Where(item => new FileRow(item).IsImage).Select(item => item.Resource).ToArray());
    }
    private async Task OpenFileAsync(FileRow row, IReadOnlyList<RemoteResourceRef> images)
    {
        if (_disposed || _connecting || _session is not { } session || row.File.Resource.Account != session.Account) return;
        var generation = _generation;
        bool CurrentOpen() => !_disposed && generation == _generation && _session?.Id == session.Id && _session.Account == session.Account;
        if (row.File.IsDirectory) { _history.Push(_trail.ToArray()); _trail.Add(row.File.Resource); await LoadAsync(row.File.Resource); return; }
        try
        {
            if (row.IsImage && _images is not null)
            {
                var window = new ImageWindow(_images, row.File.Resource, images, _files as IRecentAccessService); _imageWindows.Add(window);
                window.SetTheme(Shell.RequestedTheme);
                window.Closed += (_, _) => _imageWindows.Remove(window); window.Activate();
            }
            else if (row.IsMedia && _playback is not null)
            {
                SetNotice(row.IsAudio ? "正在打开音频" : "正在打开视频", row.Name, InfoBarSeverity.Informational);
                await _playback.OpenAsync(new PlaybackOpenRequest(new RemotePlaybackInput(row.File.Resource)), _lifetime.Token);
                if (CurrentOpen()) Notice.IsOpen = false;
            }
            else SetNotice("暂不支持打开此文件", "本次原生预览支持图片、视频与音频。你仍可浏览其他文件的名称、大小和修改时间。", InfoBarSeverity.Informational);
        }
        catch (OperationCanceledException) { if (CurrentOpen()) SetNotice("打开已取消", "可以重新选择文件。", InfoBarSeverity.Informational); }
        catch (Exception exception) { if (CurrentOpen()) SetNotice("无法打开文件", ErrorMessage(exception), InfoBarSeverity.Error); }
    }
    private async void Collections_Click(object sender, RoutedEventArgs e)
    {
        if (_disposed || _connecting || _session is null || _collections is null) return;
        CloseSidebarOverlay(); TransfersPanel.Visibility = Visibility.Collapsed; HideRecentAccessPane(); HideRemoteToolsPanes(); CollectionsPanel.Visibility = Visibility.Visible; Workspace.Visibility = Visibility.Collapsed;
        _thumbnailController.SetContext(null, null);
        UpdateCollectionsContext();
        CollectionsPanel.EnterManagementView();
        await CollectionsPanel.RefreshAsync();
    }
    private void UpdateCollectionsContext() => CollectionsPanel.SetContext(_collections, _files, _connecting ? null : _session,
        ActiveFiles.SelectedItems.Count == 1 && ActiveFiles.SelectedItem is FileRow selected ? selected.File.Resource : null);
    private void UpdateCollectionActions()
    {
        if (FavoriteActionButton is null) return;
        var enabled = !_disposed && !_connecting && CollectionsPanel.CanManageCurrentResource;
        SidebarCollections.IsEnabled = !_disposed && !_connecting && CollectionsPanel.CanNavigate;
        FavoriteActionButton.IsEnabled = TagActionButton.IsEnabled = DetailsFavoriteButton.IsEnabled = DetailsTagButton.IsEnabled = enabled;
        var favorite = CollectionsPanel.IsCurrentResourceFavorite;
        FavoriteActionButton.Label = favorite ? "取消收藏" : "收藏";
        ToolTipService.SetToolTip(DetailsFavoriteButton, favorite ? "取消收藏" : "收藏");
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(DetailsFavoriteButton, favorite ? "取消收藏" : "收藏");
    }
    private async void FavoriteResource_Click(object sender, RoutedEventArgs args)
    {
        if (_disposed || _connecting) return;
        SetDetailsVisible(false);
        UpdateCollectionsContext(); await CollectionsPanel.ToggleCurrentFavoriteAsync();
    }
    private async void TagResource_Click(object sender, RoutedEventArgs args)
    {
        if (_disposed || _connecting) return;
        SetDetailsVisible(false);
        UpdateCollectionsContext(); await CollectionsPanel.ManageCurrentResourceTagsAsync();
    }
    private void HideCollections()
    {
        CollectionsPanel.Visibility = Visibility.Collapsed; TransfersPanel.Visibility = Visibility.Collapsed; HideRecentAccessPane(); HideRemoteToolsPanes();
        Workspace.Visibility = ConnectionPanel.Visibility == Visibility.Visible ? Visibility.Collapsed : Visibility.Visible;
        CollectionsPanel.SuspendView();
        UpdateThumbnailContext();
        UpdateSelection();
    }
    private async Task OpenCollectionResourceAsync(RemoteFile file)
    {
        if (_disposed || _connecting || _session is not { } session || file.Resource.Account != session.Account) return;
        HideCollections();
        if (file.IsDirectory)
        {
            await NavigateToResourceAsync(file.Resource);
        }
        else await OpenFileAsync(new FileRow(file), [file.Resource]);
    }
    private void SetLoading(bool loading) { LoadingRing.IsActive = loading; LoadingRing.Visibility = loading ? Visibility.Visible : Visibility.Collapsed; CancelButton.Visibility = loading ? Visibility.Visible : Visibility.Collapsed; if (loading) StatusText.Text = "正在加载目录…"; UpdateApplicationMenus(); }
    private void SetNotice(string title, string message, InfoBarSeverity severity, bool repeat = true) { Notice.Title = title; Notice.Message = message; Notice.Severity = severity; ShowToast(Notice, title + message, repeat); }
    private void LoginError(string message) { LoginNotice.Severity = InfoBarSeverity.Error; LoginNotice.Message = message; ShowToast(LoginNotice, message, repeat: true); }
    internal static string ErrorMessage(Exception exception) => exception is FilewayException known ? known.Error.Message : "操作未能完成。请检查服务器连接后重试。";
    private void Server_Changed(object sender, TextChangedEventArgs e) { }
    private void Authentication_Changed(object sender, SelectionChangedEventArgs e)
    {
        if (CredentialFields is null) return;
        CredentialFields.Visibility = AuthenticationBox.SelectedIndex == 1 ? Visibility.Collapsed : Visibility.Visible;
        if (AuthenticationBox.SelectedIndex == 1) PasswordBox.Password = "";
    }
    private void Connection_Click(object sender, RoutedEventArgs e) { CloseSidebarOverlay(); SetConnectionView(true); }
    private void DismissConnection_Click(object sender, RoutedEventArgs e) => SetConnectionView(false);
    private void Search_Changed(AutoSuggestBox sender, AutoSuggestBoxTextChangedEventArgs args) { if (FileList is not null) ApplyFilter(); }
    private void Sort_Changed(object sender, SelectionChangedEventArgs e)
    {
        if (_applyingPreferences || FileList is null) return;
        var sort = (SortBox.SelectedIndex / 2) switch { 1 => FileSortField.Modified, 2 => FileSortField.Size, 3 => FileSortField.Type, _ => FileSortField.Name };
        ChangeBrowserPreferences(_browserPreferences with { SortBy = sort, Descending = SortBox.SelectedIndex % 2 == 1 });
    }
    private async void Refresh_Click(object sender, RoutedEventArgs e)
    {
        if (TransfersPanel.Visibility == Visibility.Visible) { await RefreshTransferCenterAsync(); return; }
        if (RecentPane.Visibility == Visibility.Visible) { await RecentPane.RefreshAsync(); return; }
        if (SearchPane.Visibility == Visibility.Visible) { await SearchPane.RefreshAsync(); return; }
        if (NasTasksPane.Visibility == Visibility.Visible) { await NasTasksPane.RefreshAsync(); return; }
        if (TrashPane.Visibility == Visibility.Visible) { await TrashPane.RefreshAsync(); return; }
        if (_directory is not null) await LoadAsync(_directory);
        if (!_disposed && !_connecting && _session is not null) { UpdateCollectionsContext(); await CollectionsPanel.RefreshAsync(); }
    }
    private async void Root_Click(object sender, RoutedEventArgs e) { HideCollections(); if (_root is null) { SetConnectionView(true); return; } _history.Push(_trail.ToArray()); _trail.Clear(); _trail.Add(_root); await LoadAsync(_root); }
    private async void Back_Click(object sender, RoutedEventArgs e) { if (!_history.TryPop(out var previous)) return; HideCollections(); _trail.Clear(); _trail.AddRange(previous); await LoadAsync(_trail[^1]); }
    private async void Up_Click(object sender, RoutedEventArgs e) { if (_trail.Count < 2) return; HideCollections(); _history.Push(_trail.ToArray()); _trail.RemoveAt(_trail.Count - 1); await LoadAsync(_trail[^1]); }
    private void Cancel_Click(object sender, RoutedEventArgs e) => _loadCancellation?.Cancel();
    private async void FileList_DoubleTapped(object sender, DoubleTappedRoutedEventArgs e)
    {
        var source = e.OriginalSource as DependencyObject;
        while (source is not null && !ReferenceEquals(source, sender) && source is not SelectorItem)
            source = Microsoft.UI.Xaml.Media.VisualTreeHelper.GetParent(source);
        if (source is SelectorItem item && sender is ListViewBase view)
        {
            view.SelectedItem = item.Content;
            await OpenSelectedAsync();
        }
    }
    private void FileList_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Handled || IsTextInput(e.OriginalSource as DependencyObject)) return;
        var control = Microsoft.UI.Input.InputKeyboardSource.GetKeyStateForCurrentThread(VirtualKey.Control).HasFlag(Windows.UI.Core.CoreVirtualKeyStates.Down);
        if (control && e.Key == VirtualKey.A) { e.Handled = true; ActiveFiles.SelectAll(); return; }
        if (e.Key == VirtualKey.F2) { e.Handled = true; Rename_Click(sender, e); return; }
        if (e.Key == VirtualKey.Delete) { e.Handled = true; Trash_Click(sender, e); return; }
        if (e.Key == VirtualKey.Escape && _detailsVisible) { e.Handled = true; SetDetailsVisible(false); }
    }
    private void Details_Click(object sender, RoutedEventArgs e) { SetDetailsVisible(!_detailsVisible); UpdateSelection(); }
    private void CloseDetails_Click(object sender, RoutedEventArgs e) { SetDetailsVisible(false); ActiveFiles.Focus(FocusState.Keyboard); }
    private void DetailsPanel_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Handled || e.Key != VirtualKey.Escape) return;
        e.Handled = true; CloseDetails_Click(sender, e);
    }
    private async void DetailsOpen_Click(object sender, RoutedEventArgs e) { SetDetailsVisible(false); await OpenSelectedAsync(); }
    private void FileList_SelectionChanged(object sender, SelectionChangedEventArgs e) { if (StatusText is not null && !_changingView && ReferenceEquals(sender, ActiveFiles)) UpdateSelection(); }
    private void UpdateSelection()
    {
        if (DetailsName is null) return;
        var count = ActiveFiles.SelectedItems.Count;
        var row = count == 1 ? ActiveFiles.SelectedItem as FileRow : null;
        StatusText.Text = LoadingRing.IsActive ? $"正在加载目录… · {_rows.Count} 个项目" : $"{_rows.Count} 个项目 · 已选择 {count} 个 · 双击或按 Enter 打开";
        DetailsName.Text = row?.Name ?? (count > 1 ? $"已选择 {count} 个项目" : "选择一个项目");
        DetailsMetadata.Text = row is not null ? $"类型：{row.Kind}\n大小：{row.Size}\n修改时间：{row.Modified}" : "选择单个文件或目录，查看详细信息。";
        DetailsPath.Text = row?.File.Resource.DisplayPath ?? "";
        DetailsOpenButton.IsEnabled = row is not null && !_connecting;
        DetailsFileIcon.IconName = row?.IconName ?? "file";
        DetailsDownloadButton.IsEnabled = row is not null && CanDownloadSelection;
        DetailsCopyButton.IsEnabled = CanCaptureClipboard(FileTransferAction.Copy);
        DetailsCutButton.IsEnabled = CanCaptureClipboard(FileTransferAction.Move);
        DetailsRenameButton.IsEnabled = row is not null && CanWrite(p => p.Rename);
        DetailsMoveButton.IsEnabled = row is not null && CanWrite(p => p.Rename && p.Create);
        DetailsTrashButton.IsEnabled = row is not null && CanWrite(p => p.Delete);
        CollectionsButton.IsEnabled = !_connecting && _session is not null && _collections is not null;
        SidebarCollections.IsEnabled = CollectionsButton.IsEnabled;
        UpdateCollectionsContext(); UpdateCollectionActions(); UpdateBrowserCommands(); UpdateUploadContext(); UpdateDownloadContext(); UpdateRecentAccessContext(); UpdateRemoteToolsContext(); UpdateChecksumContext();
        UpdateApplicationMenus();
    }
    private void ViewMode_Click(object sender, RoutedEventArgs e)
    {
        if (_applyingPreferences || sender is not FrameworkElement { Tag: string mode } || !Enum.TryParse<BrowserViewMode>(mode, out var view)) return;
        ChangeBrowserPreferences(_browserPreferences with { ViewMode = view });
    }
    private void IconSize_Click(object sender, RoutedEventArgs e)
    {
        if (_applyingPreferences || sender is not FrameworkElement { Tag: string value } || !Enum.TryParse<BrowserIconSize>(value, out var size)) return;
        ChangeBrowserPreferences(_browserPreferences.ViewMode == BrowserViewMode.WindowsIcons ? _browserPreferences with { WindowsIconSize = size } : _browserPreferences with { CompactGridSize = size });
    }
    private void ChangeBrowserPreferences(BrowserPreferences preferences)
    {
        ++_preferenceRevision; _browserPreferences = preferences; ApplyBrowserPresentation(); RebuildPresentation(); QueuePreferenceSave();
    }
    private void ApplyBrowserPresentation()
    {
        if (FileList is null) return;
        var selected = ActiveFiles.SelectedItems.Cast<FileRow>().Select(row => row.File.Resource).ToHashSet();
        _changingView = true;
        _applyingPreferences = true;
        try
        {
            var mode = _browserPreferences.ViewMode;
            var grid = mode != BrowserViewMode.Details;
            FileGrid.Visibility = grid ? Visibility.Visible : Visibility.Collapsed;
            FileList.Visibility = ListHeader.Visibility = grid ? Visibility.Collapsed : Visibility.Visible;
            FileGrid.ItemTemplate = (DataTemplate)Resources[mode == BrowserViewMode.CompactList ? "BrowserCompactListTemplate" : "BrowserGridTemplate"];
            MosaicViewItem.IsChecked = mode == BrowserViewMode.Mosaic; CompactGridViewItem.IsChecked = mode == BrowserViewMode.CompactGrid; WindowsIconsViewItem.IsChecked = mode == BrowserViewMode.WindowsIcons; DetailsViewItem.IsChecked = mode == BrowserViewMode.Details; CompactListViewItem.IsChecked = mode == BrowserViewMode.CompactList;
            IconSizesMenu.Visibility = mode is BrowserViewMode.CompactGrid or BrowserViewMode.WindowsIcons ? Visibility.Visible : Visibility.Collapsed;
            var size = mode == BrowserViewMode.WindowsIcons ? _browserPreferences.WindowsIconSize : _browserPreferences.CompactGridSize;
            SmallIconItem.IsChecked = size == BrowserIconSize.Small; MediumIconItem.IsChecked = size == BrowserIconSize.Medium; LargeIconItem.IsChecked = size == BrowserIconSize.Large; ExtraLargeIconItem.IsChecked = size == BrowserIconSize.ExtraLarge;
            UpdateTypeFilterMenu();
            ViewOptionsButton.Content = mode switch { BrowserViewMode.Mosaic => "详细网格", BrowserViewMode.CompactGrid => "紧凑网格", BrowserViewMode.WindowsIcons => "无界网格", BrowserViewMode.CompactList => "紧凑列表", _ => "详细列表" };
            SortBox.SelectedIndex = (_browserPreferences.SortBy switch { FileSortField.Modified => 1, FileSortField.Size => 2, FileSortField.Type => 3, _ => 0 }) * 2 + (_browserPreferences.Descending ? 1 : 0);
            ActiveFiles.SelectedItems.Clear();
            foreach (var row in _rows.Where(row => selected.Contains(row.File.Resource))) ActiveFiles.SelectedItems.Add(row);
        }
        finally { _applyingPreferences = false; _changingView = false; }
        UpdateGridMetrics();
        _thumbnailController.RefreshVisibility();
        UpdateSelection();
    }
    private void UpdateGridMetrics()
    {
        if (FileGrid.ItemsPanelRoot is not ItemsWrapGrid panel) return;
        var mode = _browserPreferences.ViewMode;
        var size = mode == BrowserViewMode.WindowsIcons ? _browserPreferences.WindowsIconSize : _browserPreferences.CompactGridSize;
        var visual = FileRow.IconSize(mode, size);
        panel.ItemWidth = mode == BrowserViewMode.CompactList ? Math.Max(240, (FilesArea.ActualWidth - 24) / (FilesArea.ActualWidth >= 650 ? 2 : 1)) : mode == BrowserViewMode.Mosaic ? 180 : mode == BrowserViewMode.WindowsIcons ? size switch { BrowserIconSize.Small => 112, BrowserIconSize.Large => 224, BrowserIconSize.ExtraLarge => 288, _ => 160 } : size switch { BrowserIconSize.Small => 100, BrowserIconSize.Large => 168, BrowserIconSize.ExtraLarge => 228, _ => 124 };
        panel.ItemHeight = mode == BrowserViewMode.CompactList ? 64 : mode == BrowserViewMode.Mosaic ? 216 : visual + 96;
    }
    private void RebuildPresentation()
    {
        var selected = ActiveFiles.SelectedItems.Cast<FileRow>().Select(row => row.File.Resource).ToHashSet();
        _thumbnailController.Invalidate(); _changingView = true;
        try { _rows.Clear(); }
        finally { _changingView = false; }
        ApplyFilter();
        foreach (var row in _rows.Where(row => selected.Contains(row.File.Resource))) ActiveFiles.SelectedItems.Add(row);
    }
    private IEnumerable<RemoteFile> SortFiles(IEnumerable<RemoteFile> files)
    {
        var preferences = _browserPreferences;
        var comparer = Comparer<RemoteFile>.Create((left, right) =>
        {
            if (left.IsDirectory != right.IsDirectory) return left.IsDirectory ? -1 : 1;
            var order = preferences.SortBy switch
            {
                FileSortField.Size => left.Size.CompareTo(right.Size),
                FileSortField.Modified => Nullable.Compare(left.Modified, right.Modified),
                FileSortField.Type => StringComparer.OrdinalIgnoreCase.Compare(left.MediaType, right.MediaType),
                _ => StringComparer.OrdinalIgnoreCase.Compare(left.Resource.DisplayName, right.Resource.DisplayName),
            };
            if (order == 0) order = StringComparer.Ordinal.Compare(left.Resource.WirePath, right.Resource.WirePath);
            return preferences.Descending ? -Math.Sign(order) : order;
        });
        return files.OrderBy(item => item, comparer);
    }
    private async Task ReadBrowserPreferencesAsync(RemoteResourceRef directory, ConnectionSession session, long generation, long revision, CancellationToken token)
    {
        if (_browserPreferencesService is not { } service) return;
        bool CurrentRead() => !_disposed && !token.IsCancellationRequested && _generation == generation && _preferenceRevision == revision && _directory == directory && _session?.Id == session.Id && _session.Account == session.Account;
        try
        {
            await _preferenceSaves;
            if (!CurrentRead()) return;
            var preferences = await service.ReadBrowserPreferencesAsync(directory, token);
            if (!CurrentRead()) return;
            _browserPreferences = preferences; ApplyBrowserPresentation(); RebuildPresentation();
        }
        catch (OperationCanceledException) { }
        catch (Exception) { if (CurrentRead()) SetNotice("视图偏好未能读取", "已使用默认视图；仍可继续浏览和调整布局。", InfoBarSeverity.Warning); }
    }
    private void QueuePreferenceSave()
    {
        if (_browserPreferencesService is not { } service || _directory is not { } directory || _session is not { } session) return;
        var previous = _preferenceSaves;
        var preferences = _browserPreferences; var revision = _preferenceRevision; var generation = _generation;
        _preferenceSaves = SaveAfterPreviousAsync();
        async Task SaveAfterPreviousAsync()
        {
            await previous;
            if (_disposed || _session?.Id != session.Id || _session.Account != session.Account) return;
            try { await service.SaveBrowserPreferencesAsync(directory, preferences, _lifetime.Token); }
            catch (OperationCanceledException) { }
            catch (Exception)
            {
                if (!_disposed && _session?.Id == session.Id && _session.Account == session.Account && _directory == directory && _generation == generation && _preferenceRevision == revision)
                    SetNotice("视图偏好未保存", "本次已应用，未保存。可以继续浏览，稍后调整视图时会再次保存。", InfoBarSeverity.Warning);
            }
        }
    }
    private void UpdateThumbnailContext()
    {
        var visible = !_connecting && ConnectionPanel.Visibility != Visibility.Visible && Workspace.Visibility == Visibility.Visible;
        _thumbnailController.SetContext(visible ? _thumbnails : null, visible ? _session : null);
    }
    private void SetDetailsVisible(bool visible)
    {
        if (!visible) { _detailsPopup?.Dispose(); _detailsPopup = null; _detailsVisible = false; return; }
        if (_detailsPopup is not null || _storageOwnerHwnd == 0 || XamlRoot is null) return;
        _detailsVisible = true; DetailsPanel.Visibility = Visibility.Visible;
        try
        {
            _detailsPopup = new MediaSurfaces.PlayerSettingsWindow(_storageOwnerHwnd, DetailsPanel, null,
                () => { _detailsPopup = null; _detailsVisible = false; if (!_disposed) UpdateApplicationMenus(); },
                theme: ActualTheme == ElementTheme.Dark ? ElementTheme.Dark : ElementTheme.Light, wrapContent: false, lightDismiss: false, movable: true);
            _detailsPopup.Title = "详细信息";
            _detailsPopup.ShowCentered();
        }
        catch (Exception) { _detailsPopup?.Dispose(); _detailsPopup = null; _detailsVisible = false; SetNotice("详情未能打开", "请重新选择项目后重试。", InfoBarSeverity.Warning); }
    }
    private void BrowserBody_SizeChanged(object sender, SizeChangedEventArgs e) { UpdateDetailsLayout(); UpdateGridMetrics(); }
    private void FilesArea_SizeChanged(object sender, SizeChangedEventArgs e) => UpdateGridMetrics();
    private void UpdateDetailsLayout()
    {
        if (DetailsColumn is null) return;
        DetailsColumn.Width = new GridLength(0);
        DetailsRow.Height = new GridLength(0);
    }
    private void ApplyTheme()
    {
        if (Shell is null) return;
        var theme = _applicationTheme;
        if (XamlRoot?.Content is FrameworkElement windowRoot) windowRoot.RequestedTheme = theme;
        Shell.RequestedTheme = theme;
        if (_uploadDialog is not null) _uploadDialog.RequestedTheme = theme;
        if (_downloadDialog is not null) _downloadDialog.RequestedTheme = theme;
        foreach (var window in _imageWindows) window.SetTheme(theme);
        UpdateApplicationMenus();
    }
    private void Workspace_SizeChanged(object sender, SizeChangedEventArgs e) { UpdateDetailsLayout(); }
}

public sealed record ConnectionChoice(SavedConnection Saved)
{
    public string Label => string.IsNullOrWhiteSpace(Saved.Username)
        ? $"{Saved.Profile.DisplayName} · 无需认证"
        : $"{Saved.Profile.DisplayName} · {Saved.Username}";
    public override string ToString() => "ConnectionChoice (profile redacted)";
}

public sealed class FileRow(RemoteFile file, BrowserPreferences? preferences = null)
{
    private readonly BrowserPreferences _presentation = preferences ?? new();
    private static readonly HashSet<string> ImageExtensions = new(StringComparer.OrdinalIgnoreCase) { ".jpg", ".jpeg", ".png", ".bmp", ".gif", ".webp", ".tif", ".tiff", ".heic", ".avif" };
    private static readonly HashSet<string> VideoExtensions = new(StringComparer.OrdinalIgnoreCase) { ".mp4", ".mkv", ".mov", ".webm", ".avi", ".m4v", ".ts", ".m2ts", ".wmv" };
    internal static readonly HashSet<string> AudioExtensions = new(StringComparer.OrdinalIgnoreCase) { ".mp3", ".flac", ".wav", ".aac", ".ogg", ".opus", ".m4a", ".wma", ".ape", ".mka", ".aiff" };
    public RemoteFile File { get; } = file;
    public string Name => File.Resource.DisplayName;
    public bool IsImage => !File.IsDirectory && (File.MediaType?.StartsWith("image/", StringComparison.OrdinalIgnoreCase) == true || ImageExtensions.Contains(Path.GetExtension(Name)));
    public bool IsVideo => !File.IsDirectory && (File.MediaType?.StartsWith("video/", StringComparison.OrdinalIgnoreCase) == true || VideoExtensions.Contains(Path.GetExtension(Name)));
    public bool IsAudio => !File.IsDirectory && (File.MediaType?.StartsWith("audio/", StringComparison.OrdinalIgnoreCase) == true || AudioExtensions.Contains(Path.GetExtension(Name)));
    public bool IsMedia => IsVideo || IsAudio;
    public string Glyph => File.IsDirectory ? "\uE8B7" : IsImage ? "\uEB9F" : IsVideo ? "\uE714" : "\uE8A5";
    public string IconName => File.IsDirectory ? "folder" : IsImage ? "image" : IsVideo ? "film-strip" : File.MediaType?.StartsWith("audio/", StringComparison.OrdinalIgnoreCase) == true ? "headphones" : Path.GetExtension(Name).ToLowerInvariant() switch { ".srt" or ".ass" or ".txt" or ".md" => "article", ".cs" or ".py" or ".go" or ".json" => "file-code", _ => "file" };
    public string Kind => File.IsDirectory ? "文件夹" : IsImage ? "图片" : IsVideo ? "视频" : IsAudio ? "音频" : string.IsNullOrEmpty(Path.GetExtension(Name)) ? "文件" : Path.GetExtension(Name).TrimStart('.').ToUpperInvariant() + " 文件";
    public string Size => File.IsDirectory ? "—" : FormatSize(File.Size);
    public string Summary => File.IsDirectory ? Kind : $"{Kind} · {Size}";
    public string Metadata => $"{Kind} · {Size} · {Modified}";
    public Visibility GridMetadataVisibility => _presentation.ViewMode == BrowserViewMode.Mosaic ? Visibility.Visible : Visibility.Collapsed;
    public double VisualSize => IconSize(_presentation.ViewMode, _presentation.ViewMode == BrowserViewMode.WindowsIcons ? _presentation.WindowsIconSize : _presentation.CompactGridSize);
    public override string ToString() => Name;
    public static double IconSize(BrowserViewMode mode, BrowserIconSize size) => mode == BrowserViewMode.Mosaic ? 72 : mode == BrowserViewMode.WindowsIcons
        ? size switch { BrowserIconSize.Small => 48, BrowserIconSize.Large => 160, BrowserIconSize.ExtraLarge => 256, _ => 96 }
        : size switch { BrowserIconSize.Small => 48, BrowserIconSize.Large => 104, BrowserIconSize.ExtraLarge => 156, _ => 72 };
    public static (string Glyph, string Kind) NameTypeHint(string name)
    {
        var extension = Path.GetExtension(name);
        return ImageExtensions.Contains(extension) ? ("\uEB9F", "图片") : VideoExtensions.Contains(extension) ? ("\uE714", "视频") : string.IsNullOrEmpty(extension) ? ("\uE8B7", "文件或文件夹") : ("\uE8A5", extension.TrimStart('.').ToUpperInvariant() + " 文件");
    }
    private static readonly Dictionary<BrowserFileTypeFilter, HashSet<string>> TypeExtensions = new()
    {
        [BrowserFileTypeFilter.Image] = new(["jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "avif"], StringComparer.OrdinalIgnoreCase),
        [BrowserFileTypeFilter.Audio] = new(["mp3", "m4a", "aac", "flac", "wav", "ogg", "opus"], StringComparer.OrdinalIgnoreCase),
        [BrowserFileTypeFilter.Video] = new(["mp4", "mkv", "avi", "mov", "webm", "m4v", "ts"], StringComparer.OrdinalIgnoreCase),
        [BrowserFileTypeFilter.Pdf] = new(["pdf"], StringComparer.OrdinalIgnoreCase),
        [BrowserFileTypeFilter.Markdown] = new(["md", "markdown", "mdown", "mkd"], StringComparer.OrdinalIgnoreCase),
        [BrowserFileTypeFilter.Config] = new(["json", "json5", "yaml", "yml", "toml", "ini", "conf", "config", "env", "xml", "properties"], StringComparer.OrdinalIgnoreCase),
        [BrowserFileTypeFilter.Code] = new(["java", "py", "go", "js", "jsx", "ts", "tsx", "vue", "rs", "c", "h", "cpp", "hpp", "cs", "php", "rb", "kt", "kts", "swift", "sh", "ps1", "sql"], StringComparer.OrdinalIgnoreCase),
    };
    public static bool MatchesType(RemoteFile file, BrowserFileTypeFilter filter)
    {
        if (filter == BrowserFileTypeFilter.All) return true;
        if (file.IsDirectory || !TypeExtensions.TryGetValue(filter, out var extensions)) return false;
        var name = file.Resource.DisplayName;
        var extension = name.StartsWith('.') && !name.AsSpan(1).Contains('.') ? name[1..] : name.LastIndexOf('.') is var index && index >= 0 ? name[(index + 1)..] : "";
        return extensions.Contains(extension);
    }
    public string Modified => File.Modified?.LocalDateTime.ToString("yyyy/M/d HH:mm", System.Globalization.CultureInfo.CurrentCulture) ?? "—";
    private static string FormatSize(long bytes) => bytes < 1024 ? $"{bytes} B" : bytes < 1024 * 1024 ? $"{bytes / 1024d:0.#} KB" : bytes < 1024L * 1024 * 1024 ? $"{bytes / (1024d * 1024):0.#} MB" : $"{bytes / (1024d * 1024 * 1024):0.#} GB";
}
