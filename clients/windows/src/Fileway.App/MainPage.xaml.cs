using System.Collections.ObjectModel;
using Fileway.Core.Contracts;
using Fileway.App.Views;
using Fileway.App.Branding;
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
    private IImageContentService? _images;
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
        InitializeComponent(); FileList.ItemsSource = FileGrid.ItemsSource = _rows;
        BrandName.Text = AppBranding.DisplayName;
        Loaded += (_, _) => ApplyTheme();
    }

    public void SetStorageSettings(ILocalStorageSettingsService settings, nint ownerHwnd)
    {
        if (_disposed) return;
        _storageSettings = settings; _storageOwnerHwnd = ownerHwnd;
        StorageSettingsButton.IsEnabled = ownerHwnd != 0;
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
            await control.LoadAsync();
            await completion;
        }
        catch (OperationCanceledException) { }
        catch (Exception) { if (!_disposed) SetNotice("存储设置未能打开", "请关闭其他对话框后重试。", InfoBarSeverity.Error); }
        finally
        {
            control.StateChanged -= StateChanged;
            if (ReferenceEquals(_storageDialog, dialog)) { _storageDialog = null; _storageControl = null; }
        }
    }
    public async Task SetServicesAsync(IConnectionService connection, IFileRepository files, IImageContentService images, IPlaybackService playback, IConnectionCatalog catalog)
    {
        _connection = connection; _files = files; _images = images; _playback = playback; _catalog = catalog;
        _connection.SessionUpdated += Connection_SessionUpdated;
        _connection.ConnectionIssue += Connection_Issue;
        ConnectButton.IsEnabled = true;
        LoginNotice.Message = "核心服务已就绪。填写服务器地址并选择认证方式即可连接。";
        Notice.IsOpen = false;
        try
        {
            await RefreshSavedConnectionsAsync();
            if (SavedConnectionBox.SelectedItem is ConnectionChoice { Saved.KeepSignedIn: true } choice)
            {
                if (choice.Saved.Profile.BaseAddress.Scheme == "http")
                    LoginNotice.Message = "此连接使用 HTTP。确认明文传输提示后，可恢复已保存登录。";
                else await RestoreSelectedAsync();
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception exception) { LoginError(ErrorMessage(exception)); }
    }
    public void ShowStartupError(string message) { LoginNotice.Severity = InfoBarSeverity.Error; LoginNotice.Message = message; SetNotice("核心服务启动失败", message, InfoBarSeverity.Error); }
    public void Shutdown() => Dispose();
    public void Dispose()
    {
        if (_disposed) return;
        _storageDialog?.Hide(); _storageControl?.Dispose();
        _disposed = true; _lifetime.Cancel(); _loadCancellation?.Cancel();
        _connectionCancellation?.Cancel();
        if (_connection is not null)
        {
            _connection.SessionUpdated -= Connection_SessionUpdated;
            _connection.ConnectionIssue -= Connection_Issue;
        }
        foreach (var window in _imageWindows.ToArray()) window.Close();
        _connectionCancellation?.Dispose(); _loadCancellation?.Dispose(); _lifetime.Dispose();
    }

    private async void Connect_Click(object sender, RoutedEventArgs e)
    {
        if (_connection is null || _files is null || _connecting) return;
        if (!Uri.TryCreate(ServerBox.Text.Trim(), UriKind.Absolute, out var address) || (address.Scheme != "https" && address.Scheme != "http") || !string.IsNullOrEmpty(address.UserInfo) || !string.IsNullOrEmpty(address.Query) || !string.IsNullOrEmpty(address.Fragment))
        { LoginError("请输入有效的 HTTP 或 HTTPS 服务器地址，且不要包含账号、查询参数或片段。"); return; }
        if (address.Scheme == "http" && HttpConsent.IsChecked != true) { LoginError("此地址使用 HTTP。请确认你了解明文传输风险后继续。"); return; }
        var login = AuthenticationBox.SelectedIndex == 1
            ? new LoginInput(AuthenticationMode.NoAuthentication, KeepSignedIn: KeepSignedInBox.IsChecked == true)
            : new LoginInput(AuthenticationMode.Password, UsernameBox.Text, PasswordBox.Password, KeepSignedInBox.IsChecked == true);
        var displayName = string.IsNullOrWhiteSpace(ConnectionNameBox.Text) ? address.Host : ConnectionNameBox.Text.Trim();
        var profile = _selectedProfile is { } saved
            ? saved with { DisplayName = displayName, BaseAddress = address, SourceRevision = saved.BaseAddress.AbsoluteUri == address.AbsoluteUri ? saved.SourceRevision : Guid.NewGuid() }
            : new ConnectionProfile(Guid.NewGuid(), Guid.NewGuid(), displayName, address, NetworkMode.SystemNetwork, new ProxyPolicy(ProxyMode.Direct));
        await RunConnectionAsync(token => _connection.ConnectAsync(profile, login, token), "正在连接服务器…");
    }

    private Task RunConnectionAsync(Func<CancellationToken, Task<ConnectionSession>> connect, string message)
        => _connectionOperation = RunConnectionCoreAsync(connect, message);

    private async Task RunConnectionCoreAsync(Func<CancellationToken, Task<ConnectionSession>> connect, string message)
    {
        if (_connection is null || _files is null || _connecting || _disposed) return;
        _connectionCancellation?.Dispose();
        _connectionCancellation = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
        var token = _connectionCancellation.Token;
        SetConnecting(true);
        LoginNotice.Severity = InfoBarSeverity.Informational; LoginNotice.Message = message;
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
            ConnectionLabel.Text = _session.DisplayName; ConnectionButton.Content = "切换连接";
            LogoutButton.Visibility = Visibility.Visible;
            DismissConnection.Visibility = Visibility.Visible; ConnectionPanel.Visibility = Visibility.Collapsed;
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
        ConnectButton.IsEnabled = !connecting && _connection is not null;
        RestoreSavedButton.IsEnabled = ForgetSavedButton.IsEnabled = SavedConnectionBox.IsEnabled = !connecting;
        ConnectionNameBox.IsEnabled = ServerBox.IsEnabled = AuthenticationBox.IsEnabled = !connecting;
        UsernameBox.IsEnabled = PasswordBox.IsEnabled = KeepSignedInBox.IsEnabled = !connecting;
        CancelConnectionButton.Visibility = connecting ? Visibility.Visible : Visibility.Collapsed;
        NavigationCommands.IsEnabled = !connecting && _session is not null;
        SortBox.IsEnabled = !connecting;
        UpdateSelection();
    }

    private void CancelConnection_Click(object sender, RoutedEventArgs args) => _connectionCancellation?.Cancel();

    private async Task RefreshSavedConnectionsAsync()
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
            SavedConnectionBox.SelectedItem = choices.FirstOrDefault(item => item.Saved.Profile.Id == selected) ?? choices.FirstOrDefault();
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
        if (SavedConnectionBox.SelectedItem is not ConnectionChoice choice) { _selectedProfile = null; return; }
        var saved = choice.Saved;
        _selectedProfile = saved.Profile;
        ConnectionNameBox.Text = saved.Profile.DisplayName;
        ServerBox.Text = saved.Profile.BaseAddress.AbsoluteUri;
        AuthenticationBox.SelectedIndex = saved.AuthenticationMode == AuthenticationMode.NoAuthentication ? 1 : 0;
        UsernameBox.Text = saved.Username;
        PasswordBox.Password = "";
        KeepSignedInBox.IsChecked = saved.KeepSignedIn;
        RestoreSavedButton.Visibility = saved.KeepSignedIn ? Visibility.Visible : Visibility.Collapsed;
    }

    private async void RestoreSaved_Click(object sender, RoutedEventArgs args) => await RestoreSelectedAsync();

    private async Task RestoreSelectedAsync()
    {
        if (_connection is null || SavedConnectionBox.SelectedItem is not ConnectionChoice choice || _connecting) return;
        if (!choice.Saved.KeepSignedIn) { LoginError("此连接未保持登录，请输入密码重新连接。"); return; }
        if (choice.Saved.Profile.BaseAddress.Scheme == "http" && HttpConsent.IsChecked != true)
        { LoginError("此地址使用 HTTP。请确认明文传输提示后恢复登录。"); return; }
        await RunConnectionAsync(token => _connection.RestoreAsync(choice.Saved.Profile.Id, token), "正在核实已保存的账号与权限…");
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
                ClearActiveWorkspace(); ConnectionPanel.Visibility = Visibility.Visible;
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
            ClearActiveWorkspace(); ConnectionPanel.Visibility = Visibility.Visible;
            await RefreshSavedConnectionsAsync();
            LoginNotice.Severity = InfoBarSeverity.Informational; LoginNotice.Message = "已退出登录。连接档案保留，保存的会话已清除。";
        }
        catch (OperationCanceledException) { }
        catch (Exception exception) { SetNotice("退出登录尚未完成", ErrorMessage(exception), InfoBarSeverity.Error); }
        finally { LogoutButton.IsEnabled = true; SetConnecting(false); }
    }

    private void ClearActiveWorkspace()
    {
        _session = null; _root = null; _directory = null; ++_generation; _loadCancellation?.Cancel();
        _items.Clear(); _rows.Clear(); _trail.Clear(); _history.Clear();
        NavigationCommands.IsEnabled = false; LogoutButton.Visibility = DismissConnection.Visibility = Visibility.Collapsed;
        ConnectionLabel.Text = "尚未连接"; ConnectionButton.Content = "连接服务器"; PathBox.Text = "尚未连接";
        Heading.Text = "我的文件"; Subheading.Text = "连接你的服务器，继续浏览。";
        _loadStateTitle = _loadStateDescription = null;
        SetLoading(false); SetDetailsVisible(false); UpdateSelection(); ApplyFilter();
    }

    private void Connection_SessionUpdated(object? sender, ConnectionSession session) => DispatcherQueue.TryEnqueue(() =>
    {
        if (!_disposed && _session?.Id == session.Id && _session.Account == session.Account)
        { _session = session; ConnectionLabel.Text = session.DisplayName; }
    });

    private void Connection_Issue(object? sender, ConnectionIssue issue) => DispatcherQueue.TryEnqueue(async () =>
    {
        if (_disposed || _session?.Id != issue.SessionId || _session.Account != issue.Account) return;
        SetNotice("连接状态需要处理", issue.Error.Message, InfoBarSeverity.Warning);
        if (!issue.SessionInvalidated) return;
        foreach (var window in _imageWindows.ToArray()) window.Close();
        ClearActiveWorkspace(); ConnectionPanel.Visibility = Visibility.Visible; LoginError(issue.Error.Message);
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
        bool CurrentLoad() => !_disposed && generation == _generation && _session?.Id == session.Id && _session.Account == session.Account;
        _loadStateTitle = _loadStateDescription = null;
        _directory = directory; PathBox.Text = directory.DisplayPath;
        Heading.Text = _trail.Count <= 1 ? "我的文件" : directory.DisplayName;
        _items.Clear(); _rows.Clear(); SearchBox.Text = "";
        SetLoading(true); Notice.IsOpen = false; EmptyState.Visibility = Visibility.Collapsed;
        try
        {
            string? cursor = null;
            var seenCursors = new HashSet<string>(StringComparer.Ordinal);
            do
            {
                var page = await _files.ListAsync(directory, Query() with { Cursor = cursor }, token);
                if (!CurrentLoad()) return;
                token.ThrowIfCancellationRequested();
                _items.AddRange(page.Items); cursor = page.NextCursor;
                if (cursor is not null && !seenCursors.Add(cursor)) throw new FilewayException(new ErrorInfo(ErrorCode.ProtocolMismatch, "服务器返回了重复的分页标记。"));
                ApplyFilter();
            } while (cursor is not null);
            Subheading.Text = $"{_items.Count} 个项目 · 双击或按 Enter 打开";
            StatusText.Text = $"已加载 {_items.Count} 个项目";
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
    private DirectoryQuery Query() => SortBox.SelectedIndex switch { 1 => new(FileSortField.Name, true), 2 => new(FileSortField.Modified, true), 3 => new(FileSortField.Size, true), 4 => new(FileSortField.Type), _ => new() };
    private void ApplyFilter()
    {
        var filter = SearchBox.Text.Trim();
        var matching = _items.Where(item => item.Resource.DisplayName.Contains(filter, StringComparison.CurrentCultureIgnoreCase)).ToArray();
        var append = _rows.Count <= matching.Length && _rows.Select((row, index) => ReferenceEquals(row.File, matching[index])).All(same => same);
        var selected = ActiveFiles.SelectedItems.Cast<FileRow>().Select(row => row.File.Resource).ToHashSet();
        _changingView = true;
        try
        {
            if (!append) _rows.Clear();
            for (var index = _rows.Count; index < matching.Length; index++) _rows.Add(new FileRow(matching[index]));
            if (!append)
                foreach (var row in _rows.Where(row => selected.Contains(row.File.Resource))) ActiveFiles.SelectedItems.Add(row);
        }
        finally { _changingView = false; }
        UpdateSelection();
        if (LoadingRing.IsActive) return;
        EmptyState.Visibility = _rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        EmptyTitle.Text = _session is null ? "你的文件，在这里相遇" : _loadStateTitle ?? (filter.Length == 0 ? "这个目录还没有文件" : "没有匹配的文件");
        EmptyDescription.Text = _session is null ? "连接服务器后即可浏览文件、打开图片和播放视频。" : _loadStateDescription ?? (filter.Length == 0 ? "可以刷新目录，查看服务器上的最新内容。" : "试试更短的名称，搜索仅覆盖当前目录。");
        RetryLoadButton.Visibility = _session is not null && _loadStateTitle is not null ? Visibility.Visible : Visibility.Collapsed;
    }
    private async Task OpenSelectedAsync()
    {
        if (_disposed || _connecting || _session is not { } session || ActiveFiles.SelectedItem is not FileRow row) return;
        var generation = _generation;
        bool CurrentOpen() => !_disposed && generation == _generation && _session?.Id == session.Id && _session.Account == session.Account;
        if (row.File.IsDirectory) { _history.Push(_trail.ToArray()); _trail.Add(row.File.Resource); await LoadAsync(row.File.Resource); return; }
        try
        {
            if (row.IsImage && _images is not null)
            {
                var images = _items.Where(item => new FileRow(item).IsImage).Select(item => item.Resource).ToArray();
                var window = new ImageWindow(_images, row.File.Resource, images); _imageWindows.Add(window);
                window.SetTheme(Shell.RequestedTheme);
                window.Closed += (_, _) => _imageWindows.Remove(window); window.Activate();
            }
            else if (row.IsVideo && _playback is not null)
            {
                SetNotice("正在打开视频", row.Name, InfoBarSeverity.Informational);
                await _playback.OpenAsync(new PlaybackOpenRequest(new RemotePlaybackInput(row.File.Resource)), _lifetime.Token);
                if (CurrentOpen()) Notice.IsOpen = false;
            }
            else SetNotice("暂不支持打开此文件", "本次原生预览支持图片与视频。你仍可浏览其他文件的名称、大小和修改时间。", InfoBarSeverity.Informational);
        }
        catch (OperationCanceledException) { if (CurrentOpen()) SetNotice("打开已取消", "可以重新选择文件。", InfoBarSeverity.Informational); }
        catch (Exception exception) { if (CurrentOpen()) SetNotice("无法打开文件", ErrorMessage(exception), InfoBarSeverity.Error); }
    }
    private void SetLoading(bool loading) { LoadingRing.IsActive = loading; LoadingRing.Visibility = loading ? Visibility.Visible : Visibility.Collapsed; CancelButton.Visibility = loading ? Visibility.Visible : Visibility.Collapsed; if (loading) StatusText.Text = "正在加载目录…"; }
    private void SetNotice(string title, string message, InfoBarSeverity severity) { Notice.Title = title; Notice.Message = message; Notice.Severity = severity; Notice.IsOpen = true; }
    private void LoginError(string message) { LoginNotice.Severity = InfoBarSeverity.Error; LoginNotice.Message = message; LoginNotice.IsOpen = true; }
    internal static string ErrorMessage(Exception exception) => exception is FilewayException known ? known.Error.Message : "操作未能完成。请检查服务器连接后重试。";
    private void Server_Changed(object sender, TextChangedEventArgs e) { if (HttpConsent is not null) { HttpConsent.Visibility = ServerBox.Text.TrimStart().StartsWith("http:", StringComparison.OrdinalIgnoreCase) ? Visibility.Visible : Visibility.Collapsed; HttpConsent.IsChecked = false; } }
    private void Authentication_Changed(object sender, SelectionChangedEventArgs e)
    {
        if (CredentialFields is null) return;
        CredentialFields.Visibility = AuthenticationBox.SelectedIndex == 1 ? Visibility.Collapsed : Visibility.Visible;
        if (AuthenticationBox.SelectedIndex == 1) PasswordBox.Password = "";
    }
    private void Connection_Click(object sender, RoutedEventArgs e) => ConnectionPanel.Visibility = Visibility.Visible;
    private void DismissConnection_Click(object sender, RoutedEventArgs e) => ConnectionPanel.Visibility = Visibility.Collapsed;
    private void Search_Changed(AutoSuggestBox sender, AutoSuggestBoxTextChangedEventArgs args) { if (FileList is not null) ApplyFilter(); }
    private async void Sort_Changed(object sender, SelectionChangedEventArgs e) { if (_directory is not null) await LoadAsync(_directory); }
    private async void Refresh_Click(object sender, RoutedEventArgs e) { if (_directory is not null) await LoadAsync(_directory); }
    private async void Root_Click(object sender, RoutedEventArgs e) { if (_root is null) { ConnectionPanel.Visibility = Visibility.Visible; return; } _history.Push(_trail.ToArray()); _trail.Clear(); _trail.Add(_root); await LoadAsync(_root); }
    private async void Back_Click(object sender, RoutedEventArgs e) { if (!_history.TryPop(out var previous)) return; _trail.Clear(); _trail.AddRange(previous); await LoadAsync(_trail[^1]); }
    private async void Up_Click(object sender, RoutedEventArgs e) { if (_trail.Count < 2) return; _history.Push(_trail.ToArray()); _trail.RemoveAt(_trail.Count - 1); await LoadAsync(_trail[^1]); }
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
    private async void FileList_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Handled || e.OriginalSource is TextBox) return;
        if (e.Key == VirtualKey.Enter) { e.Handled = true; await OpenSelectedAsync(); }
        else if (e.Key == VirtualKey.Escape && _detailsVisible) { e.Handled = true; SetDetailsVisible(false); }
    }
    private void Details_Click(object sender, RoutedEventArgs e) { SetDetailsVisible(!_detailsVisible); UpdateSelection(); }
    private void CloseDetails_Click(object sender, RoutedEventArgs e) { SetDetailsVisible(false); ActiveFiles.Focus(FocusState.Keyboard); }
    private void DetailsPanel_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Handled || e.Key != VirtualKey.Escape) return;
        e.Handled = true; CloseDetails_Click(sender, e);
    }
    private async void DetailsOpen_Click(object sender, RoutedEventArgs e) => await OpenSelectedAsync();
    private void FileList_SelectionChanged(object sender, SelectionChangedEventArgs e) { if (StatusText is not null && !_changingView && ReferenceEquals(sender, ActiveFiles)) UpdateSelection(); }
    private void UpdateSelection()
    {
        if (DetailsName is null) return;
        var count = ActiveFiles.SelectedItems.Count;
        var row = count == 1 ? ActiveFiles.SelectedItem as FileRow : null;
        StatusText.Text = row is not null ? $"{row.Name} · {row.Kind} · {row.Size}" : LoadingRing.IsActive ? "正在加载目录…" : _loadStateTitle ?? $"{_rows.Count} 个项目 · 已选择 {count} 个";
        DetailsName.Text = row?.Name ?? (count > 1 ? $"已选择 {count} 个项目" : "选择一个项目");
        DetailsMetadata.Text = row is not null ? $"类型：{row.Kind}\n大小：{row.Size}\n修改时间：{row.Modified}" : "选择单个文件或目录，查看详细信息。";
        DetailsPath.Text = row?.File.Resource.DisplayPath ?? "";
        DetailsOpenButton.IsEnabled = row is not null && !_connecting;
    }
    private void ViewMode_Click(object sender, RoutedEventArgs e)
    {
        var grid = ReferenceEquals(sender, GridModeButton);
        var selected = ActiveFiles.SelectedItems.Cast<FileRow>().ToArray();
        _changingView = true;
        try
        {
            FileGrid.Visibility = grid ? Visibility.Visible : Visibility.Collapsed;
            FileList.Visibility = ListHeader.Visibility = grid ? Visibility.Collapsed : Visibility.Visible;
            GridModeButton.IsChecked = grid; ListModeButton.IsChecked = !grid;
            ActiveFiles.SelectedItems.Clear();
            foreach (var row in selected) ActiveFiles.SelectedItems.Add(row);
        }
        finally { _changingView = false; }
        UpdateSelection();
    }
    private void SetDetailsVisible(bool visible) { _detailsVisible = visible; DetailsPanel.Visibility = visible ? Visibility.Visible : Visibility.Collapsed; UpdateDetailsLayout(); }
    private void BrowserBody_SizeChanged(object sender, SizeChangedEventArgs e) => UpdateDetailsLayout();
    private void UpdateDetailsLayout()
    {
        if (DetailsColumn is null) return;
        var narrow = Workspace.ActualWidth < 900;
        Grid.SetColumn(DetailsPanel, narrow ? 0 : 1); Grid.SetRow(DetailsPanel, narrow ? 1 : 0); Grid.SetColumnSpan(DetailsPanel, narrow ? 2 : 1);
        DetailsColumn.Width = new GridLength(_detailsVisible && !narrow ? 288 : 0);
        DetailsRow.Height = new GridLength(_detailsVisible && narrow ? Math.Min(240, BrowserBody.ActualHeight * 0.45) : 0);
        DetailsPanel.BorderThickness = narrow ? new Thickness(0, 1, 0, 0) : new Thickness(1, 0, 0, 0);
    }
    private void Theme_Changed(object sender, SelectionChangedEventArgs e) => ApplyTheme();
    private void ApplyTheme()
    {
        if (Shell is null || ThemeBox is null) return;
        var theme = ThemeBox.SelectedIndex switch { 1 => ElementTheme.Light, 2 => ElementTheme.Dark, _ => ElementTheme.Default };
        if (XamlRoot?.Content is FrameworkElement windowRoot) windowRoot.RequestedTheme = theme;
        Shell.RequestedTheme = theme;
        foreach (var window in _imageWindows) window.SetTheme(theme);
    }
    private void Workspace_SizeChanged(object sender, SizeChangedEventArgs e) { if (SidebarColumn is null || SearchBox is null) return; SidebarColumn.Width = new GridLength(ActualWidth < 880 ? 190 : 244); SearchBox.Width = e.NewSize.Width < 650 ? 150 : 240; UpdateDetailsLayout(); }
}

public sealed record ConnectionChoice(SavedConnection Saved)
{
    public string Label => $"{Saved.Profile.DisplayName} · {Saved.Username}";
    public override string ToString() => "ConnectionChoice (profile redacted)";
}

public sealed class FileRow(RemoteFile file)
{
    private static readonly HashSet<string> ImageExtensions = new(StringComparer.OrdinalIgnoreCase) { ".jpg", ".jpeg", ".png", ".bmp", ".gif", ".webp", ".tif", ".tiff", ".heic", ".avif" };
    private static readonly HashSet<string> VideoExtensions = new(StringComparer.OrdinalIgnoreCase) { ".mp4", ".mkv", ".mov", ".webm", ".avi", ".m4v", ".ts", ".m2ts", ".wmv" };
    public RemoteFile File { get; } = file;
    public string Name => File.Resource.DisplayName;
    public bool IsImage => !File.IsDirectory && (File.MediaType?.StartsWith("image/", StringComparison.OrdinalIgnoreCase) == true || ImageExtensions.Contains(Path.GetExtension(Name)));
    public bool IsVideo => !File.IsDirectory && (File.MediaType?.StartsWith("video/", StringComparison.OrdinalIgnoreCase) == true || VideoExtensions.Contains(Path.GetExtension(Name)));
    public string Glyph => File.IsDirectory ? "\uE8B7" : IsImage ? "\uEB9F" : IsVideo ? "\uE714" : "\uE8A5";
    public string Kind => File.IsDirectory ? "文件夹" : IsImage ? "图片" : IsVideo ? "视频" : string.IsNullOrEmpty(Path.GetExtension(Name)) ? "文件" : Path.GetExtension(Name).TrimStart('.').ToUpperInvariant() + " 文件";
    public string Size => File.IsDirectory ? "—" : FormatSize(File.Size);
    public string Summary => File.IsDirectory ? Kind : $"{Kind} · {Size}";
    public string Modified => File.Modified?.LocalDateTime.ToString("yyyy/M/d HH:mm", System.Globalization.CultureInfo.CurrentCulture) ?? "—";
    private static string FormatSize(long bytes) => bytes < 1024 ? $"{bytes} B" : bytes < 1024 * 1024 ? $"{bytes / 1024d:0.#} KB" : bytes < 1024L * 1024 * 1024 ? $"{bytes / (1024d * 1024):0.#} MB" : $"{bytes / (1024d * 1024 * 1024):0.#} GB";
}
