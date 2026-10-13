using Fileway.App.Controls;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Windows.System;

namespace Fileway.App;

public sealed partial class MainPage
{
    private double _sidebarWidth = 224;
    private bool _sidebarOverlay;
    private bool _sidebarOpen;
    private bool _pathNavigating;
    private long _navigationRevision;

    internal void AttachTitleBar(WindowTitleBar titleBar)
    {
        _applicationTitleBar = titleBar;
        Shell.Children.Remove(ApplicationMenus);
        titleBar.SetBrowserMenu(ApplicationMenus, [ApplicationMenus]);
        AddNavigationAccelerators(titleBar);
        titleBar.KeyDown += Overlay_KeyDown;
    }

    private void InitializeBrowserShell()
    {
        AddNavigationAccelerators(this);
        KeyDown += Overlay_KeyDown;
        UpdateBreadcrumbs();
        FileList.ContextFlyout = CreateBrowserMenu();
        FileGrid.ContextFlyout = CreateBrowserMenu();
        FilesArea.ContextFlyout = CreateBrowserMenu();
        FileList.ContextFlyout.Opening += (_, _) => PopulateBrowserMenu((MenuFlyout)FileList.ContextFlyout);
        FileGrid.ContextFlyout.Opening += (_, _) => PopulateBrowserMenu((MenuFlyout)FileGrid.ContextFlyout);
        FilesArea.ContextFlyout.Opening += (_, _) => PopulateBrowserMenu((MenuFlyout)FilesArea.ContextFlyout);
    }

    private void AddNavigationAccelerators(UIElement owner)
    {
        Add(VirtualKey.L, VirtualKeyModifiers.Control, () => BeginPathEdit());
        Add(VirtualKey.Left, VirtualKeyModifiers.Menu, () => Back_Click(this, new RoutedEventArgs()));
        Add(VirtualKey.Up, VirtualKeyModifiers.Menu, () => Up_Click(this, new RoutedEventArgs()));
        Add(VirtualKey.F5, VirtualKeyModifiers.None, () => Refresh_Click(this, new RoutedEventArgs()));
        void Add(VirtualKey key, VirtualKeyModifiers modifiers, Action action)
        {
            var accelerator = new KeyboardAccelerator { Key = key, Modifiers = modifiers };
            accelerator.Invoked += (_, args) => { if (_session is null || _connecting || _disposed) return; args.Handled = true; action(); };
            owner.KeyboardAccelerators.Add(accelerator);
        }
    }

    private void Shell_SizeChanged(object sender, SizeChangedEventArgs args)
    {
        if (Sidebar is null || SearchBox is null) return;
        var overlay = args.NewSize.Width < 960;
        if (_sidebarOverlay != overlay) { _sidebarOverlay = overlay; _sidebarOpen = false; }
        SearchBox.Width = args.NewSize.Width < 1000 ? 160 : 240;
        UpdateSidebarLayout();
    }

    private void UpdateSidebarLayout()
    {
        if (SidebarColumn is null) return;
        var width = Math.Clamp(_sidebarWidth, 192, Math.Min(320, Math.Max(192, ActualWidth - 480)));
        SidebarColumn.Width = new GridLength(_sidebarOverlay ? 0 : width);
        Sidebar.Width = _sidebarOverlay ? width : double.NaN;
        Sidebar.HorizontalAlignment = _sidebarOverlay ? HorizontalAlignment.Left : HorizontalAlignment.Stretch;
        Grid.SetColumnSpan(Sidebar, _sidebarOverlay ? 2 : 1);
        Sidebar.Visibility = !_sidebarOverlay || _sidebarOpen ? Visibility.Visible : Visibility.Collapsed;
        PaneButton.Visibility = _sidebarOverlay ? Visibility.Visible : Visibility.Collapsed;
        SidebarDismiss.Visibility = _sidebarOverlay && _sidebarOpen ? Visibility.Visible : Visibility.Collapsed;
        SidebarResize.Visibility = Sidebar.Visibility;
        Grid.SetColumnSpan(SidebarResize, _sidebarOverlay ? 2 : 1);
        SidebarResize.HorizontalAlignment = _sidebarOverlay ? HorizontalAlignment.Left : HorizontalAlignment.Right;
        SidebarResize.Margin = _sidebarOverlay ? new Thickness(width - 4, 0, 0, 0) : new Thickness(0);
    }

    private void Pane_Click(object sender, RoutedEventArgs args) { _sidebarOpen = !_sidebarOpen; UpdateSidebarLayout(); }
    private void PaneDismiss_Click(object sender, RoutedEventArgs args) => CloseSidebarOverlay();
    private void CloseSidebarOverlay() { if (!_sidebarOpen) return; _sidebarOpen = false; UpdateSidebarLayout(); PaneButton.Focus(FocusState.Keyboard); }
    private void Overlay_KeyDown(object sender, KeyRoutedEventArgs args)
    {
        if (args.Handled || args.Key != VirtualKey.Escape || !_sidebarOverlay || !_sidebarOpen || IsTextInput(args.OriginalSource as DependencyObject)
            || _browserDialog is not null || _storageDialog is not null || CollectionsPanel.HasOpenDialog
            || Microsoft.UI.Xaml.Media.VisualTreeHelper.GetOpenPopupsForXamlRoot(XamlRoot).Count > 0) return;
        args.Handled = true; CloseSidebarOverlay();
    }
    private void SidebarResize_DragDelta(object sender, DragDeltaEventArgs args)
    {
        _sidebarWidth = Math.Clamp(_sidebarWidth + args.HorizontalChange, 192, 320);
        UpdateSidebarLayout();
    }
    private void SidebarResize_KeyDown(object sender, KeyRoutedEventArgs args)
    {
        if (args.Key is not (VirtualKey.Left or VirtualKey.Right or VirtualKey.Home or VirtualKey.End)) return;
        args.Handled = true;
        _sidebarWidth = args.Key switch { VirtualKey.Home => 192, VirtualKey.End => 320, VirtualKey.Left => Math.Max(192, _sidebarWidth - 8), _ => Math.Min(320, _sidebarWidth + 8) };
        UpdateSidebarLayout();
    }

    private void UpdateBreadcrumbs()
    {
        if (PathBreadcrumb is null) return;
        PathBreadcrumb.ItemsSource = _trail.Select((resource, index) => new BreadcrumbEntry(index == 0 ? "我的文件" : resource.DisplayName, resource)).ToArray();
        EditPathButton.IsEnabled = _directory is not null && !_connecting;
        if (_directory is null) EndPathEdit();
    }
    private void EditPath_Click(object sender, RoutedEventArgs args) => BeginPathEdit();
    private void BeginPathEdit()
    {
        if (_directory is null || _disposed || _connecting || Workspace.Visibility != Visibility.Visible || ConnectionPanel.Visibility == Visibility.Visible) return;
        PathBox.Text = _directory.WirePath;
        PathBreadcrumb.Visibility = Visibility.Collapsed; PathBox.Visibility = Visibility.Visible;
        EditPathButton.Visibility = Visibility.Collapsed;
        PathBox.Focus(FocusState.Keyboard); PathBox.SelectAll();
    }
    private void EndPathEdit()
    {
        PathBox.Visibility = Visibility.Collapsed; PathBreadcrumb.Visibility = Visibility.Visible;
        EditPathButton.Visibility = Visibility.Visible;
    }
    private void PathBox_LostFocus(object sender, RoutedEventArgs args) { if (!_pathNavigating) EndPathEdit(); }
    private async void PathBox_KeyDown(object sender, KeyRoutedEventArgs args)
    {
        if (args.Key == VirtualKey.Escape) { args.Handled = true; EndPathEdit(); ActiveFiles.Focus(FocusState.Keyboard); }
        if (args.Key != VirtualKey.Enter || _pathNavigating) return;
        args.Handled = true;
        if (_session is not { } session) return;
        var raw = PathBox.Text; // No Trim/Unescape/Windows.Path: this is the actual wire identity.
        if (!IsValidRawPath(raw)) { SetNotice("原始路径无效", "请输入以 / 开始的原始路径；保留百分号编码，不使用 . 或 .. 段。", InfoBarSeverity.Warning); return; }
        _pathNavigating = true;
        try { if (await NavigateToResourceAsync(new RemoteResourceRef(session.Account, raw, raw, raw))) EndPathEdit(); }
        finally { _pathNavigating = false; }
    }
    private async void Breadcrumb_Click(BreadcrumbBar sender, BreadcrumbBarItemClickedEventArgs args)
    {
        if (args.Item is BreadcrumbEntry entry) await NavigateToResourceAsync(entry.Resource);
    }

    private async Task<bool> NavigateToResourceAsync(RemoteResourceRef requested)
    {
        if (_files is null || _root is not { } root || _session is not { } session || _connecting || requested.Account != session.Account) return false;
        var generation = _generation; var navigation = ++_navigationRevision;
        bool Current() => !_disposed && !_connecting && _generation == generation && _navigationRevision == navigation && _session?.Id == session.Id && _session.Account == session.Account;
        try
        {
            if (!IsValidRawPath(requested.WirePath)) throw new FilewayException(new ErrorInfo(ErrorCode.InvalidRequest, "原始目录路径无效。"));
            var trail = new List<RemoteResourceRef> { root };
            if (requested.WirePath != root.WirePath)
            {
                // Split only at server path boundaries. Display labels never become identities.
                var segments = requested.WirePath.Split('/').Skip(1).ToArray();
                var prefix = "";
                foreach (var segment in segments)
                {
                    prefix += "/" + segment;
                    var metadata = await _files.GetAsync(new RemoteResourceRef(session.Account, prefix, segment, prefix), _lifetime.Token);
                    if (!Current()) return false;
                    if (!metadata.IsDirectory) throw new FilewayException(new ErrorInfo(ErrorCode.InvalidRequest, "目标不是文件夹。"));
                    trail.Add(metadata.Resource);
                }
            }
            if (!Current()) return false;
            _history.Push(_trail.ToArray()); _trail.Clear(); _trail.AddRange(trail);
            HideCollections(); await LoadAsync(trail[^1]); return true;
        }
        catch (OperationCanceledException) { return false; }
        catch (Exception error) { if (Current()) SetNotice("无法导航到目录", ErrorMessage(error), InfoBarSeverity.Error); return false; }
    }

    private static bool IsValidRawPath(string raw)
    {
        if (raw.Length == 0 || raw[0] != '/' || raw.Any(c => c is '?' or '#' or '\\' || char.IsControl(c))) return false;
        if (raw == "/") return true;
        foreach (var segment in raw.Split('/').Skip(1))
        {
            if (segment.Length == 0) return false;
            var validation = new List<int>();
            for (var i = 0; i < segment.Length; i++)
            {
                var value = (int)segment[i];
                if (value == '%')
                {
                    if (i + 2 >= segment.Length || !byte.TryParse(segment.AsSpan(i + 1, 2), System.Globalization.NumberStyles.HexNumber, System.Globalization.CultureInfo.InvariantCulture, out var parsed)) return false;
                    value = parsed; i += 2;
                }
                if (value is 0 or 47) return false;
                validation.Add(value);
            }
            if (validation.Count is 1 or 2 && validation.All(value => value == '.')) return false;
        }
        return true;
    }

    private sealed record BreadcrumbEntry(string Label, RemoteResourceRef Resource);
}
