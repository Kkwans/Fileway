using System.Reflection;
using Fileway.App.Branding;
using Fileway.App.Controls;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;

namespace Fileway.App;

public sealed partial class MainPage
{
    private WindowTitleBar? _applicationTitleBar;
    private ElementTheme _applicationTheme = UiPreferences.LoadTheme();
    private bool _applicationMenusReady;
    private readonly List<(UIElement Element, long Token)> _applicationMenuViews = [];

    private void InitializeApplicationMenus()
    {
        _applicationMenusReady = true;
        foreach (var view in new UIElement[] { Workspace, ConnectionPanel, RecentPane, TransfersPanel, CollectionsPanel, SearchPane, NasTasksPane, TrashPane })
        {
            var token = view.RegisterPropertyChangedCallback(UIElement.VisibilityProperty, (_, _) => UpdateApplicationMenus());
            _applicationMenuViews.Add((view, token));
        }
        UpdateApplicationMenus();
    }

    private void DisposeApplicationMenus()
    {
        _applicationMenusReady = false;
        foreach (var (element, token) in _applicationMenuViews)
            element.UnregisterPropertyChangedCallback(UIElement.VisibilityProperty, token);
        _applicationMenuViews.Clear();
    }

    private void ApplicationMenus_GettingFocus(UIElement sender, GettingFocusEventArgs args) => UpdateApplicationMenus();
    private void ApplicationMenus_PointerEntered(object sender, PointerRoutedEventArgs args) => UpdateApplicationMenus();

    private void UpdateApplicationMenus()
    {
        if (!_applicationMenusReady || _disposed) return;
        var connectionView = ConnectionPanel.Visibility == Visibility.Visible;
        var browser = BrowserReady && !connectionView;
        var selected = SelectedRows();
        var hasSelection = browser && selected.Length > 0;
        var connected = !_connecting && _session is not null;

        MenuConnectionItem.Text = _session is null ? "连接服务器…" : "切换连接…";
        SidebarConnectionActionLabel.Text = _session is null ? "连接服务器" : "切换连接";
        AutomationProperties.SetName(ConnectionButton, SidebarConnectionActionLabel.Text);
        ToolTipService.SetToolTip(ConnectionButton, SidebarConnectionActionLabel.Text);
        MenuConnectionItem.IsEnabled = ConnectionButton.IsEnabled = _connection is not null && !_connecting;
        MenuOpenItem.IsEnabled = browser && selected.Length == 1;
        MenuNewItem.IsEnabled = browser && CanWrite(p => p.Create);
        MenuNewFileItem.IsEnabled = FileManagement is not null;
        MenuUploadItem.IsEnabled = browser && CanSubmitUpload;
        MenuDownloadItem.IsEnabled = browser && CanDownloadSelection;
        MenuSaveAsItem.IsEnabled = browser && CanDownloadSaveAsSelection;
        MenuRenameItem.Text = selected.Length > 1 ? "批量重命名…" : "重命名…";
        MenuRenameItem.IsEnabled = hasSelection && CanWrite(p => p.Rename) && (selected.Length == 1 || FileManagement is not null);
        MenuCopyItem.IsEnabled = hasSelection && FileManagement is not null && CanWrite(p => p.Create);
        MenuMoveItem.IsEnabled = hasSelection && FileManagement is not null && CanWrite(p => p.Create && p.Rename);
        MenuClipboardCopyItem.IsEnabled = CanCaptureClipboard(FileTransferAction.Copy);
        MenuClipboardCutItem.IsEnabled = CanCaptureClipboard(FileTransferAction.Move);
        MenuClipboardPasteItem.IsEnabled = CanPasteClipboard;
        MenuTrashItem.IsEnabled = hasSelection && CanWrite(p => p.Delete);
        MenuSelectAllItem.IsEnabled = browser && _rows.Count > 0;
        MenuClearSelectionItem.IsEnabled = hasSelection;
        MenuRefreshItem.IsEnabled = connected && !connectionView;
        MenuDetailsItem.IsEnabled = MenuLayoutItem.IsEnabled = browser;
        MenuDetailsItem.IsChecked = _detailsVisible;
        MyFilesButton.IsEnabled = connected && !connectionView;
        RecentAccessButton.IsEnabled = connected && !connectionView && _recentAccess is not null && _files is not null;
        TransferCenterButton.IsEnabled = connected && !connectionView && (ActiveUploadContext is not null || ActiveDownloadContext is not null);
        SidebarCollections.IsEnabled = connected && !connectionView && CollectionsPanel.CanNavigate;
        MenuRecentItem.IsEnabled = RecentAccessButton.IsEnabled;
        MenuTransfersItem.IsEnabled = TransferCenterButton.IsEnabled;
        MenuServerSearchItem.IsEnabled = connected && !connectionView && _files is IServerSearchService && (_directory ?? _root) is not null;
        MenuNasTasksItem.IsEnabled = NasTasksButton.IsEnabled = connected && !connectionView && _files is IServerTaskService;
        MenuTrashPageItem.IsEnabled = TrashPageButton.IsEnabled = connected && !connectionView && _files is ITrashService;
        StorageToolsButton.IsEnabled = connected && !connectionView && _storageAnalysis is not null && (_directory ?? _root) is not null;
        CollectionsButton.IsEnabled = connected && _collections is not null && !connectionView;
        LogoutButton.IsEnabled = connected;
        ChangePasswordButton.Visibility = connected && _session is { } session &&
            session.Capabilities.AuthMethod.Equals("json", StringComparison.OrdinalIgnoreCase)
            ? Visibility.Visible : Visibility.Collapsed;

        MenuMosaicItem.IsChecked = _browserPreferences.ViewMode == BrowserViewMode.Mosaic;
        MenuCompactGridItem.IsChecked = _browserPreferences.ViewMode == BrowserViewMode.CompactGrid;
        MenuWindowsIconsItem.IsChecked = _browserPreferences.ViewMode == BrowserViewMode.WindowsIcons;
        MenuDetailsViewItem.IsChecked = _browserPreferences.ViewMode == BrowserViewMode.Details;
        MenuCompactListItem.IsChecked = _browserPreferences.ViewMode == BrowserViewMode.CompactList;
        MenuSystemThemeItem.IsChecked = _applicationTheme == ElementTheme.Default;
        MenuLightThemeItem.IsChecked = _applicationTheme == ElementTheme.Light;
        MenuDarkThemeItem.IsChecked = _applicationTheme == ElementTheme.Dark;
        MenuCloseToTrayItem.IsChecked = ((App)Application.Current).CloseToTray;

        PathLocationHost.Visibility = browser ? Visibility.Visible : Visibility.Collapsed;
        NavigationCommands.Visibility = NavigationHeader.Visibility = browser ? Visibility.Visible : Visibility.Collapsed;
        WorkspaceTitle.Visibility = Visibility.Collapsed;
        WorkspaceTitle.Text = connectionView ? "连接服务器" : RecentPane.Visibility == Visibility.Visible ? "最近访问"
            : SearchPane.Visibility == Visibility.Visible ? "搜索结果" : NasTasksPane.Visibility == Visibility.Visible ? "文件任务"
            : TrashPane.Visibility == Visibility.Visible ? "回收站"
            : TransfersPanel.Visibility == Visibility.Visible ? "传输中心" : CollectionsPanel.Visibility == Visibility.Visible ? "收藏与标签" : "我的文件";
        SearchBox.Visibility = browser ? Visibility.Visible : Visibility.Collapsed;
        SearchBox.IsEnabled = BrowserCommands.IsEnabled = browser;
        SetSidebarCurrent(MyFilesButton, browser);
        SetSidebarCurrent(RecentAccessButton, !connectionView && RecentPane.Visibility == Visibility.Visible);
        SetSidebarCurrent(TransferCenterButton, !connectionView && TransfersPanel.Visibility == Visibility.Visible);
        SetSidebarCurrent(NasTasksButton, !connectionView && NasTasksPane.Visibility == Visibility.Visible);
        SetSidebarCurrent(TrashPageButton, !connectionView && TrashPane.Visibility == Visibility.Visible);
    }

    private void SetSidebarCurrent(Button button, bool current)
    {
        button.Style = (Style)Resources[current ? "SidebarSelectedNavigationButtonStyle" : "SidebarNavigationButtonStyle"];
        AutomationProperties.SetItemStatus(button, current ? "当前位置" : "");
    }

    private void CopySelection_Click(object sender, RoutedEventArgs args) => Transfer_Click(FileTransferAction.Copy);
    private void MoveSelection_Click(object sender, RoutedEventArgs args) => Transfer_Click(FileTransferAction.Move);
    private void SelectAll_Click(object sender, RoutedEventArgs args) { if (BrowserReady) ActiveFiles.SelectAll(); }
    private void ClearSelection_Click(object sender, RoutedEventArgs args) { if (BrowserReady) ActiveFiles.SelectedItems.Clear(); }
    private void ApplicationExit_Click(object sender, RoutedEventArgs args) => ((App)Application.Current).RequestExit();
    private void CloseToTray_Click(object sender, RoutedEventArgs args)
    {
        try { ((App)Application.Current).SetCloseToTray(MenuCloseToTrayItem.IsChecked); }
        catch (Exception) { MenuCloseToTrayItem.IsChecked = ((App)Application.Current).CloseToTray; SetNotice("设置未保存", "无法保存关闭偏好，请检查当前用户的设置权限。", InfoBarSeverity.Error); }
    }

    private void ApplicationTheme_Click(object sender, RoutedEventArgs args)
    {
        if (_disposed) return;
        if (sender is not RadioMenuFlyoutItem { Tag: string value } || !Enum.TryParse<ElementTheme>(value, out var theme)) return;
        _applicationTheme = theme;
        ApplyTheme();
        UiPreferences.SaveTheme(theme);
    }

    private async void KeyboardHelp_Click(object sender, RoutedEventArgs args)
    {
        if (_disposed) return;
        var rows = new Grid { ColumnSpacing = 24, RowSpacing = 12, MinWidth = 320 };
        rows.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        rows.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        foreach (var (key, action) in new[]
        {
            ("Alt + Left", "返回上一位置"), ("Alt + Up", "打开上一级目录"), ("Ctrl + L", "编辑原始路径"),
            ("F5", "刷新当前视图"), ("Ctrl + F", "搜索当前目录与子目录"), ("Ctrl + B", "展开或折叠导航侧栏"), ("Enter", "打开选中的文件或文件夹"), ("Ctrl + A", "全选当前文件"),
            ("F2", "重命名选中项目"), ("Delete", "将选中项目移入回收站"), ("Escape", "取消路径编辑或关闭详情 / 临时侧栏"),
            ("Ctrl + C / X / V", "复制 / 剪切 / 粘贴服务器文件；文本输入保留系统行为"),
        })
        {
            var row = rows.RowDefinitions.Count;
            rows.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            var shortcut = new TextBlock { Text = key, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold };
            var description = new TextBlock { Text = action, TextWrapping = TextWrapping.Wrap };
            Grid.SetRow(shortcut, row); Grid.SetRow(description, row); Grid.SetColumn(description, 1);
            rows.Children.Add(shortcut); rows.Children.Add(description);
        }
        await ShowBrowserDialogAsync(new ContentDialog { Title = "键盘快捷键", Content = new ScrollViewer { Content = rows, MaxHeight = 400, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled }, CloseButtonText = "关闭" });
    }

    private async void AboutApplication_Click(object sender, RoutedEventArgs args)
    {
        if (_disposed) return;
        var version = typeof(App).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion.Split('+')[0]
            ?? typeof(App).Assembly.GetName().Version?.ToString() ?? "未知";
        var content = new StackPanel { Spacing = 12, MinWidth = 320 };
        content.Children.Add(new TextBlock { Text = "Windows 原生文件客户端", FontSize = 16 });
        content.Children.Add(new TextBlock { Text = "版本 " + version });
        content.Children.Add(new HyperlinkButton { Content = "项目主页与源代码", NavigateUri = new Uri("https://github.com/Kkwans/Fileway"), Padding = new Thickness(0), HorizontalAlignment = HorizontalAlignment.Left });
        content.Children.Add(new HyperlinkButton { Content = "许可证", NavigateUri = new Uri("https://github.com/Kkwans/Fileway/blob/codex/windows-native-client/clients/windows/LICENSE"), Padding = new Thickness(0), HorizontalAlignment = HorizontalAlignment.Left });
        await ShowBrowserDialogAsync(new ContentDialog { Title = AppBranding.DisplayName, Content = content, CloseButtonText = "关闭" });
    }
}
