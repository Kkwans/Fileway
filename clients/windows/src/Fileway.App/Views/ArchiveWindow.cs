using Fileway.App.Branding;
using Fileway.App.Controls;
using Fileway.App.Windowing;
using Fileway.Core.Contracts;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace Fileway.App.Views;

/// <summary>
/// Explorer-style browser for one remote ZIP archive: folder navigation, multi-select extraction
/// into the effective download directory. Existing targets are never overwritten; every outcome is listed.
/// </summary>
public sealed class ArchiveWindow : Window, IAsyncDisposable
{
    private sealed record ArchiveRow(ArchiveEntryInfo Entry, string Name, bool IsDirectory, string Size)
    {
        public override string ToString() => Name;
    }

    private readonly IArchiveBrowseService _service;
    private readonly ILocalStorageSettingsService _settings;
    private readonly WindowTitleBar _titleBar = new();
    private readonly FramelessWindowChrome _chrome;
    private readonly RemoteResourceRef _archive;
    private readonly CancellationTokenSource _lifetime = new();
    private readonly TextBlock _title = new() { FontSize = 14, FontWeight = FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis };
    private readonly TextBlock _summary = new() { FontSize = 12 };
    private readonly TextBlock _status = new() { FontSize = 12, VerticalAlignment = VerticalAlignment.Center };
    private readonly ProgressRing _progress = new() { IsActive = false, Width = 18, Height = 18, VerticalAlignment = VerticalAlignment.Center, Visibility = Visibility.Collapsed };
    private readonly ListView _list = new()
    {
        SelectionMode = ListViewSelectionMode.Extended,
        HorizontalContentAlignment = HorizontalAlignment.Stretch,
        IsItemClickEnabled = false,
    };
    private readonly StackPanel _breadcrumb = new() { Orientation = Orientation.Horizontal, Spacing = 2, VerticalAlignment = VerticalAlignment.Center };
    private readonly Border _banner;
    private readonly TextBlock _bannerText;
    private readonly Button _extractSelected;
    private readonly Button _extractAll;
    private readonly TextBlock _empty;
    private readonly Grid _root;
    private readonly Grid _windowRoot;
    private readonly Border _toolbar;
    private readonly Border _crumbBar;
    private readonly Border _statusBar;
    private IArchiveBrowseSession? _session;
    private readonly List<ArchiveRow> _rows = [];
    private string _prefix = "";
    private bool _closed;
    private bool _started;
    private bool _busy;
    private ElementTheme _theme = ElementTheme.Dark;

    public ArchiveWindow(IArchiveBrowseService service, ILocalStorageSettingsService settings, RemoteResourceRef archive)
    {
        _service = service;
        _settings = settings;
        _archive = archive;
        Title = _titleBar.Caption = archive.DisplayName + " · " + AppBranding.DisplayName;
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"));
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1080, 800));

        _title.Text = archive.DisplayName;
        var titleStack = new StackPanel { Spacing = 2, VerticalAlignment = VerticalAlignment.Center };
        titleStack.Children.Add(_title);
        titleStack.Children.Add(_summary);

        _extractAll = new Button { Content = "全部解压", Style = (Style)Application.Current.Resources["FwPrimaryButtonStyle"], Height = 32, MinHeight = 32, Padding = new Thickness(16, 0, 16, 0), IsEnabled = false };
        _extractSelected = new Button { Content = "解压选中", Style = (Style)Application.Current.Resources["FwSecondaryButtonStyle"], Height = 32, MinHeight = 32, Padding = new Thickness(14, 0, 14, 0), IsEnabled = false };
        AutomationProperties.SetAutomationId(_extractAll, "ArchiveExtractAllButton");
        AutomationProperties.SetAutomationId(_extractSelected, "ArchiveExtractButton");
        _extractAll.Click += async (_, _) => await ExtractAsync(all: true);
        _extractSelected.Click += async (_, _) => await ExtractAsync(all: false);
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, VerticalAlignment = VerticalAlignment.Center };
        actions.Children.Add(_progress);
        actions.Children.Add(_extractSelected);
        actions.Children.Add(_extractAll);

        var toolbarGrid = new Grid { Padding = new Thickness(20, 0, 16, 0), Height = 58 };
        toolbarGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        toolbarGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        toolbarGrid.Children.Add(titleStack);
        Grid.SetColumn(actions, 1);
        toolbarGrid.Children.Add(actions);
        _toolbar = new Border { Child = toolbarGrid, BorderThickness = new Thickness(0, 0, 0, 1) };

        var crumbGrid = new Grid { Padding = new Thickness(14, 0, 16, 0), Height = 42 };
        crumbGrid.Children.Add(_breadcrumb);
        _crumbBar = new Border { Child = crumbGrid, BorderThickness = new Thickness(0, 0, 0, 1) };

        _bannerText = new TextBlock { FontSize = 13, TextWrapping = TextWrapping.Wrap, VerticalAlignment = VerticalAlignment.Center };
        _banner = new Border { Visibility = Visibility.Collapsed, Padding = new Thickness(20, 10, 16, 10), BorderThickness = new Thickness(0, 0, 0, 1), Child = _bannerText };

        _list.DoubleTapped += List_DoubleTapped;
        _list.SelectionChanged += (_, _) => UpdateCommands();
        _empty = new TextBlock { Text = "这个文件夹是空的", FontSize = 14, Opacity = 0.6, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Visibility = Visibility.Collapsed, IsHitTestVisible = false };
        var listHost = new Grid();
        listHost.Children.Add(_list);
        listHost.Children.Add(_empty);

        _statusBar = new Border { BorderThickness = new Thickness(0, 1, 0, 0), Padding = new Thickness(20, 0, 20, 0), Height = 30 };
        var statusGrid = new Grid();
        statusGrid.Children.Add(_status);
        _statusBar.Child = statusGrid;

        _root = new Grid();
        _root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _root.RowDefinitions.Add(new RowDefinition());
        _root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _root.Children.Add(_toolbar);
        Grid.SetRow(_crumbBar, 1); _root.Children.Add(_crumbBar);
        Grid.SetRow(_banner, 2); _root.Children.Add(_banner);
        Grid.SetRow(listHost, 3); _root.Children.Add(listHost);
        Grid.SetRow(_statusBar, 4); _root.Children.Add(_statusBar);

        _windowRoot = new Grid();
        _windowRoot.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _windowRoot.RowDefinitions.Add(new RowDefinition());
        _windowRoot.Children.Add(_titleBar);
        Grid.SetRow(_root, 1);
        _windowRoot.Children.Add(_root);
        Content = _windowRoot;
        _chrome = new FramelessWindowChrome(this, _titleBar);
        ApplyTheme(_theme);
        _root.Loaded += async (_, _) =>
        {
            if (_started) return;
            _started = true;
            ApplyTheme(_root.ActualTheme);
            await LoadAsync();
        };
        Closed += async (_, _) =>
        {
            _closed = true;
            _lifetime.Cancel();
            if (_session is { } session) await session.DisposeAsync().ConfigureAwait(false);
            _chrome.Dispose();
            _lifetime.Dispose();
        };
    }

    public ValueTask DisposeAsync()
    {
        // The Closed handler owns teardown; this covers an explicit dispose before closing.
        if (!_closed) { _closed = true; _lifetime.Cancel(); _chrome.Dispose(); _lifetime.Dispose(); }
        return ValueTask.CompletedTask;
    }

    public void SetTheme(ElementTheme theme) => ApplyTheme(theme);

    private Brush ThemeBrush(string key)
    {
        var name = _theme == ElementTheme.Light ? "Light" : "Default";
        if (Application.Current.Resources.ThemeDictionaries.TryGetValue(name, out var dictionary) &&
            dictionary is ResourceDictionary resources && resources.TryGetValue(key, out var value) && value is Brush brush)
            return brush;
        return Application.Current.Resources.TryGetValue(key, out var fallback) && fallback is Brush brushValue
            ? brushValue
            : new SolidColorBrush(_theme == ElementTheme.Light ? Windows.UI.Color.FromArgb(255, 243, 244, 246) : Windows.UI.Color.FromArgb(255, 23, 25, 29));
    }

    private void ApplyTheme(ElementTheme theme)
    {
        _theme = theme;
        if (Content is FrameworkElement root) root.RequestedTheme = theme;
        var canvas = ThemeBrush("FwCanvasBrush");
        _root.Background = canvas;
        _windowRoot.Background = canvas;
        _toolbar.Background = ThemeBrush("FwSurfaceBrush");
        _toolbar.BorderBrush = ThemeBrush("FwStrokeBrush");
        _crumbBar.Background = ThemeBrush("FwSurfaceBrush");
        _crumbBar.BorderBrush = ThemeBrush("FwStrokeBrush");
        _statusBar.BorderBrush = ThemeBrush("FwStrokeBrush");
        _title.Foreground = ThemeBrush("FwTextPrimaryBrush");
        _summary.Foreground = ThemeBrush("FwTextSecondaryBrush");
        _status.Foreground = ThemeBrush("FwTextSecondaryBrush");
        _bannerText.Foreground = ThemeBrush("FwTextPrimaryBrush");
        _banner.Background = ThemeBrush("FwSurfaceRaisedBrush");
        _banner.BorderBrush = ThemeBrush("FwStrokeBrush");
    }

    private async Task LoadAsync()
    {
        _progress.IsActive = true;
        _progress.Visibility = Visibility.Visible;
        _summary.Text = "正在读取压缩包…";
        try
        {
            var session = await _service.OpenAsync(_archive, _lifetime.Token);
            if (_closed) { await session.DisposeAsync(); return; }
            _session = session;
            _extractAll.IsEnabled = session.Entries.Any(entry => !entry.IsDirectory);
            _summary.Text = $"{session.Entries.Count} 个条目 · 解压总量 {DownloadPresentationPolicy.FormatBytes(session.TotalUncompressedBytes)}";
            ShowBanner("压缩包已打开。双击文件夹进入，多选后解压。", error: false);
            NavigateTo("");
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (_closed) return;
            ShowBanner(error is FilewayException fileway ? fileway.Error.Message : "压缩包未能打开，请检查连接后重试。", error: true);
        }
        finally
        {
            _progress.IsActive = false;
            _progress.Visibility = Visibility.Collapsed;
        }
    }

    private void NavigateTo(string prefix)
    {
        if (_session is not { } session) return;
        _prefix = prefix;
        _rows.Clear();
        _list.Items.Clear();
        _list.SelectedItems.Clear();
        var childPrefix = prefix.Length == 0 ? "" : prefix + "/";
        var directories = new HashSet<string>(StringComparer.Ordinal);
        foreach (var entry in session.Entries)
        {
            if (!entry.FullName.StartsWith(childPrefix, StringComparison.Ordinal)) continue;
            var remainder = entry.FullName[childPrefix.Length..].Trim('/');
            if (remainder.Length == 0) continue;
            var slash = remainder.IndexOf('/');
            if (slash >= 0)
            {
                var directory = remainder[..slash];
                if (directories.Add(directory))
                    _rows.Add(new ArchiveRow(entry with { FullName = childPrefix + directory, IsDirectory = true }, directory, true, "—"));
                continue;
            }
            if (entry.IsDirectory)
            {
                if (directories.Add(remainder))
                    _rows.Add(new ArchiveRow(entry, remainder, true, "—"));
            }
            else _rows.Add(new ArchiveRow(entry, remainder, false, DownloadPresentationPolicy.FormatBytes(entry.Size)));
        }
        var sorted = _rows.OrderByDescending(row => row.IsDirectory).ThenBy(row => row.Name, StringComparer.CurrentCultureIgnoreCase).ToArray();
        _rows.Clear(); _rows.AddRange(sorted);
        foreach (var row in sorted) _list.Items.Add(BuildRow(row));
        _empty.Visibility = sorted.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
        RenderBreadcrumb();
        _status.Text = $"{sorted.Length} 个条目" + (prefix.Length == 0 ? " · 根目录" : " · " + prefix);
        UpdateCommands();
    }

    private static ListViewItem BuildRow(ArchiveRow row)
    {
        var icon = new LucideIcon { IconName = row.IsDirectory ? "folder" : IconFor(row.Name), Width = 20, Height = 20, VerticalAlignment = VerticalAlignment.Center };
        var name = new TextBlock { Text = row.Name, FontSize = 13.5, TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center };
        var size = new TextBlock { Text = row.Size, FontSize = 12, Opacity = 0.65, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Center };
        var grid = new Grid { ColumnSpacing = 12, Padding = new Thickness(4, 0, 8, 0) };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(28) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(96) });
        grid.Children.Add(icon);
        Grid.SetColumn(name, 1); grid.Children.Add(name);
        Grid.SetColumn(size, 2); grid.Children.Add(size);
        var item = new ListViewItem { Content = grid, Tag = row, MinHeight = 40 };
        AutomationProperties.SetName(item, row.Name);
        return item;
    }

    private static string IconFor(string name)
    {
        var extension = Path.GetExtension(name).ToLowerInvariant();
        return extension switch
        {
            ".png" or ".jpg" or ".jpeg" or ".gif" or ".webp" or ".bmp" or ".svg" => "image",
            ".mp4" or ".mkv" or ".mov" or ".avi" or ".webm" => "film-strip",
            ".mp3" or ".flac" or ".wav" or ".m4a" => "headphones",
            ".txt" or ".md" or ".log" or ".json" or ".xml" or ".yaml" or ".yml" or ".csv" => "article",
            ".cs" or ".go" or ".js" or ".ts" or ".py" or ".java" or ".kt" or ".rs" or ".sh" => "file-code",
            _ => "file",
        };
    }

    private void RenderBreadcrumb()
    {
        _breadcrumb.Children.Clear();
        _breadcrumb.Children.Add(CrumbButton("压缩包", ""));
        var segments = _prefix.Length == 0 ? [] : _prefix.Split('/');
        var accumulated = "";
        foreach (var segment in segments)
        {
            _breadcrumb.Children.Add(new TextBlock { Text = "›", FontSize = 13, Opacity = 0.45, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(2, 0, 2, 0) });
            accumulated = accumulated.Length == 0 ? segment : accumulated + "/" + segment;
            var target = accumulated;
            _breadcrumb.Children.Add(CrumbButton(segment, target));
        }
    }

    private Button CrumbButton(string text, string target)
    {
        var button = new Button
        {
            Content = new TextBlock { Text = text, FontSize = 13, FontWeight = FontWeights.Normal },
            Style = (Style)Application.Current.Resources["FwTextIconButtonStyle"],
            Height = 28,
            MinHeight = 28,
            Padding = new Thickness(8, 0, 8, 0),
        };
        button.Click += (_, _) => NavigateTo(target);
        return button;
    }

    private void List_DoubleTapped(object sender, Microsoft.UI.Xaml.Input.DoubleTappedRoutedEventArgs args)
    {
        if (_busy) return;
        for (DependencyObject? source = args.OriginalSource as DependencyObject; source is not null && source != _list; source = VisualTreeHelper.GetParent(source))
        {
            if (source is not ListViewItem { Tag: ArchiveRow row }) continue;
            if (row.IsDirectory) { NavigateTo(row.Entry.FullName.Trim('/')); args.Handled = true; }
            return;
        }
    }

    private List<int> SelectedFileIndexes(bool all)
    {
        if (_session is not { } session) return [];
        if (all) return session.Entries.Where(entry => !entry.IsDirectory).Select(entry => entry.Index).ToList();
        var indexes = new List<int>();
        foreach (var item in _list.SelectedItems)
        {
            if (item is not ListViewItem { Tag: ArchiveRow row }) continue;
            if (!row.IsDirectory) { indexes.Add(row.Entry.Index); continue; }
            var prefix = row.Entry.FullName.TrimEnd('/') + "/";
            indexes.AddRange(session.Entries.Where(entry => !entry.IsDirectory && entry.FullName.StartsWith(prefix, StringComparison.Ordinal)).Select(entry => entry.Index));
        }
        return indexes.Distinct().ToList();
    }

    private async Task ExtractAsync(bool all)
    {
        if (_session is not { } session || _busy || _closed) return;
        var indexes = SelectedFileIndexes(all);
        if (indexes.Count == 0) { ShowBanner("请先选择要解压的文件或文件夹。", error: false); return; }
        try
        {
            var state = await _settings.ReadAsync(_lifetime.Token);
            if (_closed) return;
            string leaf = Path.GetFileNameWithoutExtension(_archive.DisplayName);
            leaf = DownloadPresentationPolicy.IsSafeLocalName(leaf) ? leaf : "archive";
            string target = state.Active.DownloadDirectory.TrimEnd('\\') + "\\archive-extract\\" + leaf;
            var panel = new StackPanel { Spacing = 10, MinWidth = 340, MaxWidth = 460 };
            panel.Children.Add(new TextBlock { Text = $"解压 {indexes.Count} 个文件到：", TextWrapping = TextWrapping.Wrap });
            panel.Children.Add(new TextBlock { Text = target, TextWrapping = TextWrapping.Wrap, FontSize = 12, IsTextSelectionEnabled = true });
            panel.Children.Add(new TextBlock { Text = "已存在的同名文件将跳过且不覆盖；解压结果逐条列明。", FontSize = 12, Opacity = 0.75, TextWrapping = TextWrapping.Wrap });
            var confirm = new ContentDialog
            {
                XamlRoot = _root.XamlRoot,
                RequestedTheme = _theme,
                Title = all ? "解压全部文件" : "解压选中条目",
                Content = panel,
                PrimaryButtonText = "开始解压",
                CloseButtonText = "取消",
                DefaultButton = ContentDialogButton.Primary,
            };
            if (await confirm.ShowAsync() != ContentDialogResult.Primary || _closed) return;
            _busy = true;
            _progress.IsActive = true; _progress.Visibility = Visibility.Visible;
            _extractAll.IsEnabled = false; _extractSelected.IsEnabled = false;
            ShowBanner("正在解压…", error: false);
            var result = await session.ExtractAsync(indexes, target, _lifetime.Token);
            if (_closed) return;
            ShowBanner($"解压完成：{result.Extracted} 成功 · {result.Skipped} 跳过 · {result.Failed} 失败。目标：{target}", error: result.Failed > 0);
            _status.Text = $"解压完成 · {result.Extracted} 成功 · {result.Skipped} 跳过 · {result.Failed} 失败";
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (_closed) return;
            ShowBanner(error is FilewayException fileway ? fileway.Error.Message : "解压未能完成，请重试。", error: true);
        }
        finally
        {
            _busy = false;
            _progress.IsActive = false; _progress.Visibility = Visibility.Collapsed;
            UpdateCommands();
        }
    }

    private void UpdateCommands()
    {
        if (_extractSelected is null) return;
        _extractSelected.IsEnabled = !_busy && _session is not null && _list.SelectedItems.Count > 0;
        _extractAll.IsEnabled = !_busy && _session is not null && _session.Entries.Any(entry => !entry.IsDirectory);
    }

    private Microsoft.UI.Dispatching.DispatcherQueueTimer? _bannerTimer;

    private void ShowBanner(string message, bool error)
    {
        _bannerText.Text = message;
        _banner.BorderBrush = error ? ThemeBrush("FwDangerBrush") : ThemeBrush("FwStrokeBrush");
        _banner.Visibility = Visibility.Visible;
        _bannerTimer?.Stop();
        if (error) return;
        _bannerTimer ??= DispatcherQueue.CreateTimer();
        _bannerTimer.IsRepeating = false;
        _bannerTimer.Interval = TimeSpan.FromSeconds(4);
        _bannerTimer.Tick -= BannerTimer_Tick;
        _bannerTimer.Tick += BannerTimer_Tick;
        _bannerTimer.Start();
    }

    private void BannerTimer_Tick(object? sender, object args)
    {
        _bannerTimer?.Stop();
        _banner.Visibility = Visibility.Collapsed;
    }
}
