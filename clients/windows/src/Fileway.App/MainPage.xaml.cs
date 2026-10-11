using System.Collections.ObjectModel;
using Fileway.Core.Contracts;
using Fileway.App.Views;
using Fileway.App.Branding;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Windows.System;

namespace Fileway.App;

public sealed partial class MainPage : Page, IDisposable
{
    private IConnectionService? _connection;
    private IFileRepository? _files;
    private IImageContentService? _images;
    private IPlaybackService? _playback;
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

    public MainPage()
    {
        InitializeComponent(); FileList.ItemsSource = _rows;
        BrandName.Text = AppBranding.DisplayName;
        Loaded += (_, _) => ApplyTheme();
    }
    public void SetServices(IConnectionService connection, IFileRepository files, IImageContentService images, IPlaybackService playback)
    {
        _connection = connection; _files = files; _images = images; _playback = playback;
        ConnectButton.IsEnabled = true;
        LoginNotice.Message = "核心服务已就绪。填写服务器地址并选择认证方式即可连接。";
        Notice.IsOpen = false;
    }
    public void ShowStartupError(string message) { LoginNotice.Severity = InfoBarSeverity.Error; LoginNotice.Message = message; SetNotice("核心服务启动失败", message, InfoBarSeverity.Error); }
    public void Shutdown() => Dispose();
    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true; _lifetime.Cancel(); _loadCancellation?.Cancel();
        foreach (var window in _imageWindows.ToArray()) window.Close();
        _loadCancellation?.Dispose(); _lifetime.Dispose();
    }

    private async void Connect_Click(object sender, RoutedEventArgs e)
    {
        if (_connection is null || _files is null || _connecting) return;
        if (!Uri.TryCreate(ServerBox.Text.Trim(), UriKind.Absolute, out var address) || (address.Scheme != "https" && address.Scheme != "http") || !string.IsNullOrEmpty(address.UserInfo) || !string.IsNullOrEmpty(address.Query) || !string.IsNullOrEmpty(address.Fragment))
        { LoginError("请输入有效的 HTTP 或 HTTPS 服务器地址，且不要包含账号、查询参数或片段。"); return; }
        if (address.Scheme == "http" && HttpConsent.IsChecked != true) { LoginError("此地址使用 HTTP。请确认你了解明文传输风险后继续。"); return; }
        var login = AuthenticationBox.SelectedIndex == 1
            ? new LoginInput(AuthenticationMode.NoAuthentication)
            : new LoginInput(AuthenticationMode.Password, UsernameBox.Text, PasswordBox.Password);
        _connecting = true; ConnectButton.IsEnabled = false; ServerBox.IsEnabled = false;
        AuthenticationBox.IsEnabled = false; UsernameBox.IsEnabled = false; PasswordBox.IsEnabled = false;
        LoginNotice.Severity = InfoBarSeverity.Informational; LoginNotice.Message = "正在连接服务器…";
        try
        {
            if (_session is not null)
            {
                foreach (var window in _imageWindows.ToArray()) window.Close();
                if (_playback is not null) await _playback.CloseAccountAsync(_session.Account, _lifetime.Token);
                await _connection.DisconnectAsync(_session.Account, _lifetime.Token);
                _session = null; _root = null; _directory = null; _loadCancellation?.Cancel();
                _items.Clear(); _rows.Clear(); NavigationCommands.IsEnabled = false;
                DismissConnection.Visibility = Visibility.Collapsed; ConnectionLabel.Text = "尚未连接";
            }
            var profile = new ConnectionProfile(Guid.NewGuid(), Guid.NewGuid(), address.Host, address, NetworkMode.SystemNetwork, new ProxyPolicy(ProxyMode.Direct));
            _session = await _connection.ConnectAsync(profile, login, _lifetime.Token);
            PasswordBox.Password = "";
            _root = await _files.GetRootAsync(_session, _lifetime.Token);
            _trail.Clear(); _trail.Add(_root); _history.Clear();
            ConnectionLabel.Text = _session.DisplayName; ConnectionButton.Content = "切换连接";
            DismissConnection.Visibility = Visibility.Visible; ConnectionPanel.Visibility = Visibility.Collapsed;
            NavigationCommands.IsEnabled = true;
            await LoadAsync(_root);
        }
        catch (OperationCanceledException) { LoginError("连接已取消。"); }
        catch (Exception exception) { LoginError(ErrorMessage(exception)); }
        finally { _connecting = false; ConnectButton.IsEnabled = _connection is not null; ServerBox.IsEnabled = true; AuthenticationBox.IsEnabled = true; UsernameBox.IsEnabled = true; PasswordBox.IsEnabled = true; PasswordBox.Password = ""; }
    }

    private async Task LoadAsync(RemoteResourceRef directory)
    {
        if (_files is null) return;
        _loadCancellation?.Cancel(); _loadCancellation?.Dispose();
        _loadCancellation = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
        var token = _loadCancellation.Token;
        var generation = ++_generation;
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
                if (generation != _generation) return;
                _items.AddRange(page.Items); cursor = page.NextCursor;
                if (cursor is not null && !seenCursors.Add(cursor)) throw new FilewayException(new ErrorInfo(ErrorCode.ProtocolMismatch, "服务器返回了重复的分页标记。"));
                ApplyFilter();
            } while (cursor is not null);
            Subheading.Text = $"{_items.Count} 个项目 · 双击或按 Enter 打开";
            StatusText.Text = $"已加载 {_items.Count} 个项目";
        }
        catch (OperationCanceledException) { if (generation == _generation) SetNotice("加载已取消", "可刷新当前目录重新加载。", InfoBarSeverity.Informational); }
        catch (Exception exception) { if (generation == _generation) SetNotice("无法加载目录", ErrorMessage(exception), InfoBarSeverity.Error); }
        finally { if (generation == _generation) { SetLoading(false); ApplyFilter(); BackButton.IsEnabled = _history.Count > 0; UpButton.IsEnabled = _trail.Count > 1; } }
    }
    private DirectoryQuery Query() => SortBox.SelectedIndex switch { 1 => new(FileSortField.Name, true), 2 => new(FileSortField.Modified, true), 3 => new(FileSortField.Size, true), 4 => new(FileSortField.Type), _ => new() };
    private void ApplyFilter()
    {
        var filter = SearchBox.Text.Trim(); _rows.Clear();
        foreach (var item in _items.Where(item => item.Resource.DisplayName.Contains(filter, StringComparison.CurrentCultureIgnoreCase))) _rows.Add(new FileRow(item));
        if (LoadingRing.IsActive) return;
        EmptyState.Visibility = _rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        EmptyTitle.Text = filter.Length == 0 ? "这个目录还没有文件" : "没有匹配的文件";
        EmptyDescription.Text = filter.Length == 0 ? "可以刷新目录，查看服务器上的最新内容。" : "试试更短的名称，搜索仅覆盖当前目录。";
    }
    private async Task OpenSelectedAsync()
    {
        if (FileList.SelectedItem is not FileRow row) return;
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
                Notice.IsOpen = false;
            }
            else SetNotice("暂不支持打开此文件", "本次原生预览支持图片与视频。你仍可浏览其他文件的名称、大小和修改时间。", InfoBarSeverity.Informational);
        }
        catch (OperationCanceledException) { SetNotice("打开已取消", "可以重新选择文件。", InfoBarSeverity.Informational); }
        catch (Exception exception) { SetNotice("无法打开文件", ErrorMessage(exception), InfoBarSeverity.Error); }
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
        while (source is not null && source != FileList && source is not ListViewItem)
            source = Microsoft.UI.Xaml.Media.VisualTreeHelper.GetParent(source);
        if (source is ListViewItem item)
        {
            FileList.SelectedItem = item.Content;
            await OpenSelectedAsync();
        }
    }
    private async void FileList_KeyDown(object sender, KeyRoutedEventArgs e) { if (e.Key == VirtualKey.Enter && e.OriginalSource is not TextBox) { e.Handled = true; await OpenSelectedAsync(); } }
    private async void Details_Click(object sender, RoutedEventArgs e)
    {
        if (FileList.SelectedItem is not FileRow row) { SetNotice("请选择文件", "选中文件或目录后查看详情。", InfoBarSeverity.Informational); return; }
        var details = new StackPanel { Spacing = 12 };
        details.Children.Add(new TextBlock { Text = row.Name, TextWrapping = TextWrapping.Wrap, FontSize = 20, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, IsTextSelectionEnabled = true });
        details.Children.Add(new TextBlock { Text = $"类型：{row.Kind}\n大小：{row.Size}\n修改时间：{row.Modified}", TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true });
        details.Children.Add(new TextBlock { Text = row.File.Resource.DisplayPath, TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true });
        var dialog = new ContentDialog { XamlRoot = XamlRoot, Title = "文件详情", Content = details, PrimaryButtonText = "打开", CloseButtonText = "关闭", DefaultButton = ContentDialogButton.Close };
        if (await dialog.ShowAsync() == ContentDialogResult.Primary) await OpenSelectedAsync();
    }
    private void FileList_SelectionChanged(object sender, SelectionChangedEventArgs e) { if (StatusText is null) return; StatusText.Text = FileList.SelectedItems.Count == 1 && FileList.SelectedItem is FileRow row ? $"{row.Name} · {row.Kind} · {row.Size}" : $"{_rows.Count} 个项目 · 已选择 {FileList.SelectedItems.Count} 个"; }
    private void Theme_Changed(object sender, SelectionChangedEventArgs e) => ApplyTheme();
    private void ApplyTheme()
    {
        if (Shell is null || ThemeBox is null) return;
        var theme = ThemeBox.SelectedIndex switch { 1 => ElementTheme.Light, 2 => ElementTheme.Dark, _ => ElementTheme.Default };
        if (XamlRoot?.Content is FrameworkElement windowRoot) windowRoot.RequestedTheme = theme;
        Shell.RequestedTheme = theme;
        foreach (var window in _imageWindows) window.SetTheme(theme);
    }
    private void Workspace_SizeChanged(object sender, SizeChangedEventArgs e) { if (SidebarColumn is null || SearchBox is null) return; SidebarColumn.Width = new GridLength(ActualWidth < 880 ? 190 : 244); SearchBox.Width = e.NewSize.Width < 650 ? 150 : 240; }
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
    public string Modified => File.Modified?.LocalDateTime.ToString("yyyy/M/d HH:mm", System.Globalization.CultureInfo.CurrentCulture) ?? "—";
    private static string FormatSize(long bytes) => bytes < 1024 ? $"{bytes} B" : bytes < 1024 * 1024 ? $"{bytes / 1024d:0.#} KB" : bytes < 1024L * 1024 * 1024 ? $"{bytes / (1024d * 1024):0.#} MB" : $"{bytes / (1024d * 1024 * 1024):0.#} GB";
}
