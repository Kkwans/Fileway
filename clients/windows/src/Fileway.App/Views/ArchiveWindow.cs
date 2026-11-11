using System.Globalization;
using Fileway.App;
using Fileway.App.Branding;
using Fileway.App.Controls;
using Fileway.App.Windowing;
using Fileway.Core.Contracts;
using Fileway.Infrastructure.Services;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.System;

namespace Fileway.App.Views;

/// <summary>
/// Explorer-style browser for one remote ZIP archive: folder navigation, multi-select extraction
/// into the effective download directory. Existing targets are never overwritten; every outcome is listed.
/// </summary>
public sealed class ArchiveWindow : Window, IAsyncDisposable
{
    private sealed record ArchiveRow(ArchiveEntryInfo Entry, string Name, bool IsDirectory, string Size, string Type, string Modified)
    {
        public override string ToString() => Name;
    }

    private const double RowIconWidth = 28;
    private const double RowLeftPadding = 4;
    private const double RowRightPadding = 8;
    private const double ColumnGap = 12;
    private const double SizeColumnWidth = 96;
    private const double TypeColumnWidth = 96;
    private const double ModifiedColumnWidth = 150;
    private const double HeaderLeftPadding = RowLeftPadding + RowIconWidth + ColumnGap;
    private const double CompactColumnsWidth = 720;
    private const int MaximumExtractItems = 1000;

    private readonly IArchiveBrowseService _service;
    private readonly ILocalStorageSettingsService _settings;
    private readonly WindowTitleBar _titleBar = new();
    private readonly FramelessWindowChrome _chrome;
    private readonly RemoteResourceRef _archive;
    private readonly CancellationTokenSource _lifetime = new();
    private readonly TextBlock _summary = new() { FontSize = 12, VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis };
    private readonly ProgressRing _progress = new() { IsActive = false, Width = 18, Height = 18, VerticalAlignment = VerticalAlignment.Center, Visibility = Visibility.Collapsed };
    private readonly ListView _list = new()
    {
        SelectionMode = ListViewSelectionMode.Extended,
        HorizontalContentAlignment = HorizontalAlignment.Stretch,
        IsItemClickEnabled = false,
    };
    private readonly Button _rootCrumb;
    private readonly Button _parentCrumb;
    private readonly Button _ancestorButton;
    private readonly MenuFlyout _ancestors = new();
    private readonly TextBlock _currentCrumb = new()
    {
        FontSize = 13,
        FontWeight = FontWeights.SemiBold,
        VerticalAlignment = VerticalAlignment.Center,
        TextTrimming = TextTrimming.CharacterEllipsis,
    };
    private readonly Border _banner;
    private readonly TextBlock _bannerText;
    private readonly Grid _listHeader;
    private readonly Button _extractSelected;
    private readonly Button _extractAll;
    private readonly StackPanel _empty;
    private readonly TextBlock _emptyTitle;
    private readonly TextBlock _emptyDescription;
    private readonly Grid _root;
    private readonly Grid _windowRoot;
    private readonly KeyboardAccelerator _selectAll;
    private readonly Border _toolbar;
    private readonly Border _crumbBar;
    private readonly Border _progressHost;
    private readonly ProgressBar _extractBar;
    private readonly TextBlock _extractProgressText;
    private readonly Border _resultsHost;
    private readonly TextBlock _resultsHeader;
    private readonly TextBlock _resultsSummary;
    private readonly TextBlock _resultsMessage;
    private readonly Button _resultsToggle;
    private readonly ListView _resultsList;
    private IArchiveBrowseSession? _session;
    private readonly List<ArchiveRow> _rows = [];
    private readonly List<Window> _previewWindows = [];
    private string _prefix = "";
    private bool _closed;
    private bool _started;
    private bool _busy;
    private bool _confirming;
    private bool _compactColumns;
    private ElementTheme _theme = ElementTheme.Default;

    public ArchiveWindow(IArchiveBrowseService service, ILocalStorageSettingsService settings, RemoteResourceRef archive)
    {
        _service = service;
        _settings = settings;
        _archive = archive;
        Title = _titleBar.Caption = archive.DisplayName + " · " + AppBranding.DisplayName;
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"));
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1080, 800));

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

        var toolbarGrid = new Grid { Padding = new Thickness(20, 0, 16, 0), ColumnSpacing = 12, Height = 48 };
        toolbarGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        toolbarGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        toolbarGrid.Children.Add(_summary);
        Grid.SetColumn(actions, 1);
        toolbarGrid.Children.Add(actions);
        _toolbar = new Border { Child = toolbarGrid, BorderThickness = new Thickness(0, 0, 0, 1) };

        _rootCrumb = CrumbButton("压缩包", "ArchiveRootCrumb");
        _rootCrumb.Tag = "";
        AutomationProperties.SetHelpText(_rootCrumb, "压缩包根目录");
        _parentCrumb = CrumbButton("", "ArchiveParentCrumb");
        _parentCrumb.MaxWidth = 144;
        _ancestorButton = new Button
        {
            Style = (Style)Application.Current.Resources["FwIconButtonStyle"],
            Width = 32,
            Height = 32,
            MinHeight = 32,
            Content = new LucideIcon { IconName = "dots-three", Width = 16, Height = 16 },
            Flyout = _ancestors,
            Visibility = Visibility.Collapsed,
        };
        SurfaceStyles.Attach(_ancestors);
        AutomationProperties.SetAutomationId(_ancestorButton, "ArchiveAncestorMenu");
        AutomationProperties.SetName(_ancestorButton, "上级文件夹");
        ToolTipService.SetToolTip(_ancestorButton, "上级文件夹");
        AutomationProperties.SetAutomationId(_currentCrumb, "ArchiveCurrentPath");

        var pathActions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4, VerticalAlignment = VerticalAlignment.Center };
        pathActions.Children.Add(_rootCrumb);
        pathActions.Children.Add(_ancestorButton);
        pathActions.Children.Add(_parentCrumb);
        var crumbGrid = new Grid { Padding = new Thickness(20, 0, 16, 0), Height = 38 };
        crumbGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        crumbGrid.ColumnDefinitions.Add(StarColumn());
        crumbGrid.Children.Add(pathActions);
        Grid.SetColumn(_currentCrumb, 1);
        crumbGrid.Children.Add(_currentCrumb);
        _crumbBar = new Border { Child = crumbGrid, BorderThickness = new Thickness(0, 0, 0, 1) };
        RenderBreadcrumb();

        _bannerText = new TextBlock { FontSize = 13, TextWrapping = TextWrapping.Wrap, VerticalAlignment = VerticalAlignment.Center };
        _banner = new Border { Visibility = Visibility.Collapsed, Padding = new Thickness(20, 10, 16, 10), BorderThickness = new Thickness(0, 0, 0, 1), Child = _bannerText };
        _listHeader = new Grid
        {
            Padding = new Thickness(HeaderLeftPadding, 8, RowRightPadding, 6),
            ColumnSpacing = ColumnGap,
            Visibility = Visibility.Collapsed,
        };
        _listHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        _listHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(SizeColumnWidth) });
        _listHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(TypeColumnWidth) });
        _listHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(ModifiedColumnWidth) });
        _listHeader.Children.Add(HeaderText("名称", 0, HorizontalAlignment.Left));
        _listHeader.Children.Add(HeaderText("大小", 1, HorizontalAlignment.Right));
        _listHeader.Children.Add(HeaderText("类型", 2, HorizontalAlignment.Left));
        _listHeader.Children.Add(HeaderText("修改时间", 3, HorizontalAlignment.Right));

        _list.ItemContainerStyle = (Style)Application.Current.Resources["FwArchiveListItemStyle"];
        _list.DoubleTapped += List_DoubleTapped;
        _list.SelectionChanged += (_, _) => { UpdateCommands(); UpdateStatus(); };
        _list.SizeChanged += (_, _) => UpdateListLayout();
        _list.Loaded += (_, _) => UpdateListLayout();
        AutomationProperties.SetAutomationId(_list, "ArchiveList");
        _emptyTitle = new TextBlock
        {
            Text = "这个文件夹是空的",
            Style = (Style)Application.Current.Resources["FwTitleTextStyle"],
            TextAlignment = TextAlignment.Center,
            HorizontalAlignment = HorizontalAlignment.Center,
        };
        _emptyDescription = new TextBlock
        {
            Text = "压缩包里没有可显示的文件。",
            FontSize = 13,
            LineHeight = 20,
            TextAlignment = TextAlignment.Center,
            HorizontalAlignment = HorizontalAlignment.Center,
        };
        var emptyPanel = new StackPanel
        {
            Spacing = 14,
            MaxWidth = 420,
            Margin = new Thickness(16),
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
            IsHitTestVisible = false,
            Visibility = Visibility.Collapsed,
        };
        emptyPanel.Children.Add(new Border
        {
            Width = 72,
            Height = 72,
            CornerRadius = new CornerRadius(20),
            Style = (Style)Application.Current.Resources["FwIconTileStyle"],
            Child = new LucideIcon
            {
                IconName = "folder",
                Width = 32,
                Height = 32,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                Style = (Style)Application.Current.Resources["FwSecondaryIconStyle"],
            },
        });
        emptyPanel.Children.Add(_emptyTitle);
        emptyPanel.Children.Add(_emptyDescription);
        _empty = emptyPanel;
        var listHost = new Grid();
        listHost.Children.Add(_list);
        listHost.Children.Add(_empty);

        _extractBar = new ProgressBar
        {
            Height = 4,
            Minimum = 0,
            Maximum = 1,
            Value = 0,
            CornerRadius = new CornerRadius(2),
            Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent),
            VerticalAlignment = VerticalAlignment.Center,
        };
        _extractProgressText = new TextBlock { FontSize = 12, VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetAutomationId(_extractProgressText, "ArchiveExtractProgress");
        var progressGrid = new Grid { Padding = new Thickness(20, 8, 20, 8), ColumnSpacing = 12 };
        progressGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        progressGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        progressGrid.Children.Add(new Border
        {
            Height = 6,
            CornerRadius = new CornerRadius(3),
            Style = (Style)Application.Current.Resources["FwDividerStrongStyle"],
            VerticalAlignment = VerticalAlignment.Center,
            Child = _extractBar,
        });
        Grid.SetColumn(_extractProgressText, 1);
        progressGrid.Children.Add(_extractProgressText);
        _progressHost = new Border
        {
            Child = progressGrid,
            BorderThickness = new Thickness(0, 1, 0, 0),
            Visibility = Visibility.Collapsed,
        };

        _resultsHeader = new TextBlock { Text = "解压结果", FontSize = 14, FontWeight = FontWeights.SemiBold, VerticalAlignment = VerticalAlignment.Center };
        _resultsSummary = new TextBlock { FontSize = 12, VerticalAlignment = VerticalAlignment.Center };
        _resultsMessage = new TextBlock
        {
            Style = (Style)Application.Current.Resources["FwDangerTextStyle"],
            TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(0, 6, 0, 0),
            Visibility = Visibility.Collapsed,
        };
        _resultsToggle = new Button
        {
            Content = "收起",
            Style = (Style)Application.Current.Resources["FwSecondaryButtonStyle"],
            Height = 28,
            MinHeight = 28,
            Padding = new Thickness(12, 0, 12, 0),
        };
        _resultsList = new ListView
        {
            SelectionMode = ListViewSelectionMode.None,
            MaxHeight = 180,
            Margin = new Thickness(0, 6, 0, 0),
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
        };
        AutomationProperties.SetAutomationId(_resultsList, "ArchiveExtractResults");
        _resultsList.ItemContainerStyle = (Style)Application.Current.Resources["FwArchiveListItemStyle"];
        _resultsToggle.Click += (_, _) =>
        {
            var expand = _resultsList.Visibility != Visibility.Visible;
            _resultsList.Visibility = expand ? Visibility.Visible : Visibility.Collapsed;
            _resultsToggle.Content = expand ? "收起" : "展开";
        };
        var resultsHeaderGrid = new Grid { ColumnSpacing = 12 };
        resultsHeaderGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        resultsHeaderGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        resultsHeaderGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        resultsHeaderGrid.Children.Add(_resultsHeader);
        Grid.SetColumn(_resultsSummary, 1);
        resultsHeaderGrid.Children.Add(_resultsSummary);
        Grid.SetColumn(_resultsToggle, 2);
        resultsHeaderGrid.Children.Add(_resultsToggle);
        var resultsGrid = new Grid();
        resultsGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        resultsGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        resultsGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        resultsGrid.Children.Add(resultsHeaderGrid);
        Grid.SetRow(_resultsMessage, 1);
        resultsGrid.Children.Add(_resultsMessage);
        Grid.SetRow(_resultsList, 2);
        resultsGrid.Children.Add(_resultsList);
        _resultsHost = new Border
        {
            Child = resultsGrid,
            BorderThickness = new Thickness(0, 1, 0, 0),
            Padding = new Thickness(20, 8, 20, 10),
            Visibility = Visibility.Collapsed,
        };

        _root = new Grid();
        _root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _root.RowDefinitions.Add(new RowDefinition());
        _root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _root.Children.Add(_toolbar);
        Grid.SetRow(_crumbBar, 1); _root.Children.Add(_crumbBar);
        Grid.SetRow(_banner, 2); _root.Children.Add(_banner);
        Grid.SetRow(_listHeader, 3); _root.Children.Add(_listHeader);
        Grid.SetRow(listHost, 4); _root.Children.Add(listHost);
        Grid.SetRow(_progressHost, 5); _root.Children.Add(_progressHost);
        Grid.SetRow(_resultsHost, 6); _root.Children.Add(_resultsHost);

        _windowRoot = new Grid();
        _windowRoot.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _windowRoot.RowDefinitions.Add(new RowDefinition());
        _windowRoot.Children.Add(_titleBar);
        Grid.SetRow(_root, 1);
        _windowRoot.Children.Add(_root);
        Content = _windowRoot;
        _windowRoot.PreviewKeyDown += OnPreviewKeyDown;
        _selectAll = new KeyboardAccelerator { Key = VirtualKey.A, Modifiers = VirtualKeyModifiers.Control };
        _selectAll.Invoked += (_, args) =>
        {
            if (_busy || _confirming || _ancestors.IsOpen || _root.XamlRoot is not { } root ||
                !IsListKeyboardSource(FocusManager.GetFocusedElement(root) as DependencyObject)) return;
            SelectAllEntries();
            args.Handled = true;
        };
        _windowRoot.KeyboardAccelerators.Add(_selectAll);
        _chrome = new FramelessWindowChrome(this, _titleBar);
        ApplyTheme(_theme);
        _root.Loaded += async (_, _) =>
        {
            if (_started) return;
            _started = true;
            ApplyTheme(_root.ActualTheme);
            await LoadAsync();
            if (_closed) return;
            // Without an explicit target WinUI focuses the first caption button, so Enter/Space
            // would minimise the archive window instead of moving through its entries. The root
            // keeps focus and owns the keys (see OnPreviewKeyDown), because the list itself
            // refuses programmatic focus in a freshly activated window.
            _root.IsTabStop = true;
            _ = _root.Focus(FocusState.Programmatic);
            DispatcherQueue.TryEnqueue(() => { _ = _root.Focus(FocusState.Programmatic); });
        };
        Closed += async (_, _) =>
        {
            _closed = true;
            _bannerTimer?.Stop();
            _lifetime.Cancel();
            _chrome.Dispose();
            try { if (_session is { } session) await session.DisposeAsync().ConfigureAwait(false); }
            catch (FilewayException) { }
            finally { _lifetime.Dispose(); }
        };
    }

    public ValueTask DisposeAsync()
    {
        // The Closed handler owns teardown; this covers an explicit dispose before closing.
        if (!_closed)
        {
            _closed = true;
            _bannerTimer?.Stop();
            _lifetime.Cancel();
            _chrome.Dispose();
            _lifetime.Dispose();
        }
        return ValueTask.CompletedTask;
    }

    public void SetTheme(ElementTheme theme)
    {
        if (_closed) return;
        ApplyTheme(theme);
    }

    private SolidColorBrush ThemeBrush(string key) => Controls.ThemePalette.Brush(key, _theme);

    private void ApplyTheme(ElementTheme theme)
    {
        // The archive browser is a document surface and follows the app theme; each part
        // resolves a ThemeResource style rather than a brush captured from the app dictionary.
        _theme = theme;
        if (Content is FrameworkElement root) root.RequestedTheme = theme;
        var resources = Application.Current.Resources;
        _root.Style = (Style)resources["FwViewerCanvasStyle"];
        _windowRoot.Style = (Style)resources["FwViewerCanvasStyle"];
        _toolbar.Style = (Style)resources["FwDocumentPanelStyle"];
        _crumbBar.Style = (Style)resources["FwDocumentPanelStyle"];
        _banner.Style = (Style)resources["FwDocumentNoticeStyle"];
        _progressHost.Style = (Style)resources["FwDocumentPanelStyle"];
        _resultsHost.Style = (Style)resources["FwDocumentPanelStyle"];
        _summary.Style = (Style)resources["FwCaptionTextStyle"];
        _extractProgressText.Style = (Style)resources["FwCaptionTextStyle"];
        _resultsSummary.Style = (Style)resources["FwCaptionTextStyle"];
        _emptyDescription.Style = (Style)resources["FwCaptionTextStyle"];
        _emptyDescription.FontSize = 13;
        _titleBar.RequestedTheme = theme;
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
            NavigateTo("");
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (_closed) return;
            _summary.Text = "压缩包未打开";
            _listHeader.Visibility = Visibility.Collapsed;
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
        if (_closed || _busy || _confirming || _session is not { } session) return;
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
                {
                    var synthesized = entry with { FullName = childPrefix + directory, IsDirectory = true, Modified = null };
                    _rows.Add(new ArchiveRow(synthesized, directory, true, "—", "文件夹", "—"));
                }
                continue;
            }
            if (entry.IsDirectory)
            {
                if (directories.Add(remainder))
                    _rows.Add(new ArchiveRow(entry, remainder, true, "—", "文件夹", FormatModified(entry.Modified)));
            }
            else
            {
                _rows.Add(new ArchiveRow(entry, remainder, false, DownloadPresentationPolicy.FormatBytes(entry.Size),
                    TypeFor(remainder), FormatModified(entry.Modified)));
            }
        }
        var sorted = _rows.OrderByDescending(row => row.IsDirectory).ThenBy(row => row.Name, StringComparer.CurrentCultureIgnoreCase).ToArray();
        _rows.Clear(); _rows.AddRange(sorted);
        foreach (var row in sorted) _list.Items.Add(BuildRow(row));
        _empty.Visibility = sorted.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
        _emptyTitle.Text = prefix.Length == 0 ? "压缩包是空的" : "这个文件夹是空的";
        _emptyDescription.Text = prefix.Length == 0 ? "这个压缩包没有可浏览的条目。" : "压缩包里没有可显示的文件。";
        _listHeader.Visibility = sorted.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        RenderBreadcrumb();
        _list.SelectionMode = sorted.Length == 0 ? ListViewSelectionMode.None : ListViewSelectionMode.Extended;
        UpdateStatus();
        UpdateCommands();
        DispatcherQueue.TryEnqueue(() =>
        {
            UpdateHeaderPadding();
            // Re-entering content after navigation keeps keyboard focus off the caption buttons,
            // especially when the target folder is empty and no row is left to hold it.
            if (_closed || _busy || _confirming || _prefix != prefix) return;
            _ = _root.Focus(FocusState.Programmatic);
            DispatcherQueue.TryEnqueue(() =>
            {
                if (!_closed && !_busy && !_confirming && _prefix == prefix)
                    _ = _root.Focus(FocusState.Programmatic);
            });
        });
    }

    private ListViewItem BuildRow(ArchiveRow row)
    {
        var icon = new LucideIcon
        {
            IconName = row.IsDirectory ? "folder" : IconFor(row.Name),
            Width = 20,
            Height = 20,
            VerticalAlignment = VerticalAlignment.Center,
            Style = (Style)Application.Current.Resources["FwSecondaryIconStyle"],
        };
        var name = new TextBlock
        {
            Text = row.Name,
            FontSize = 13.5,
            TextTrimming = TextTrimming.CharacterEllipsis,
            VerticalAlignment = VerticalAlignment.Center,
        };
        ToolTipService.SetToolTip(name, $"{row.Name}\n{row.Type} · {row.Size} · {row.Modified}");
        var size = RowText(row.Size, HorizontalAlignment.Right);
        var type = RowText(row.Type, HorizontalAlignment.Left);
        var modified = RowText(row.Modified, HorizontalAlignment.Right);
        var grid = new Grid { ColumnSpacing = ColumnGap, Padding = new Thickness(RowLeftPadding, 0, RowRightPadding, 0) };
        grid.ColumnDefinitions.Add(FixedColumn(RowIconWidth));
        grid.ColumnDefinitions.Add(StarColumn());
        grid.ColumnDefinitions.Add(FixedColumn(SizeColumnWidth));
        grid.ColumnDefinitions.Add(FixedColumn(TypeColumnWidth));
        grid.ColumnDefinitions.Add(FixedColumn(ModifiedColumnWidth));
        grid.Children.Add(icon);
        Grid.SetColumn(name, 1); grid.Children.Add(name);
        Grid.SetColumn(size, 2); grid.Children.Add(size);
        Grid.SetColumn(type, 3); grid.Children.Add(type);
        Grid.SetColumn(modified, 4); grid.Children.Add(modified);
        SetTypeColumn(grid, 3, _compactColumns);
        var item = new ListViewItem { Content = grid, Tag = row, MinHeight = 40 };
        AutomationProperties.SetName(item, row.Name);
        AutomationProperties.SetHelpText(item, $"{row.Type} · {row.Size} · {row.Modified}");
        return item;
    }

    private static TextBlock RowText(string text, HorizontalAlignment alignment) => new()
    {
        Text = text,
        Style = (Style)Application.Current.Resources["FwCaptionTextStyle"],
        FontSize = 12,
        TextWrapping = TextWrapping.NoWrap,
        TextTrimming = TextTrimming.CharacterEllipsis,
        HorizontalAlignment = alignment,
        VerticalAlignment = VerticalAlignment.Center,
    };

    private static TextBlock HeaderText(string text, int column, HorizontalAlignment alignment)
    {
        var block = new TextBlock
        {
            Text = text,
            Style = (Style)Application.Current.Resources["FwCaptionTextStyle"],
            FontSize = 12,
            TextWrapping = TextWrapping.NoWrap,
            HorizontalAlignment = alignment,
            VerticalAlignment = VerticalAlignment.Center,
        };
        Grid.SetColumn(block, column);
        return block;
    }

    private static ColumnDefinition FixedColumn(double width) => new() { Width = new GridLength(width) };

    private static ColumnDefinition StarColumn() => new() { Width = new GridLength(1, GridUnitType.Star) };

    private static string FormatModified(DateTimeOffset? modified) => modified is { } value
        ? value.ToLocalTime().ToString("yyyy/MM/dd HH:mm", CultureInfo.CurrentCulture)
        : "—";

    private static string TypeFor(string name)
    {
        var extension = Path.GetExtension(name).ToLowerInvariant();
        return extension switch
        {
            ".png" => "PNG 图片",
            ".jpg" or ".jpeg" => "JPEG 图片",
            ".gif" => "GIF 图片",
            ".webp" => "WebP 图片",
            ".bmp" => "BMP 图片",
            ".svg" => "SVG 图片",
            ".mp4" => "MP4 视频",
            ".mkv" => "MKV 视频",
            ".mov" => "MOV 视频",
            ".avi" => "AVI 视频",
            ".webm" => "WebM 视频",
            ".mp3" => "MP3 音频",
            ".flac" => "FLAC 音频",
            ".wav" => "WAV 音频",
            ".m4a" => "M4A 音频",
            ".txt" or ".log" => "文本",
            ".md" or ".markdown" => "Markdown",
            ".json" => "JSON",
            ".xml" => "XML",
            ".yaml" or ".yml" => "YAML",
            ".csv" or ".tsv" => "表格",
            ".zip" => "ZIP 压缩包",
            ".pdf" => "PDF 文档",
            ".cs" or ".go" or ".js" or ".ts" or ".py" or ".java" or ".kt" or ".rs" or ".sh" => "代码",
            "" => "文件",
            _ => extension.TrimStart('.').ToUpperInvariant() + " 文件",
        };
    }

    private void UpdateStatus()
    {
        if (_session is not { } session) return;
        var selected = _list.SelectedItems.Count;
        var total = DownloadPresentationPolicy.FormatBytes(session.TotalUncompressedBytes);
        _summary.Text = $"{_rows.Count} 个条目" +
            (selected > 0 ? $" · 已选 {selected} 个" : $" · 解压总量 {total}");
        ToolTipService.SetToolTip(_summary, $"压缩包共 {session.Entries.Count} 个条目 · 解压总量 {total}");
    }

    private void UpdateListLayout()
    {
        bool compact = _list.ActualWidth > 0 && _list.ActualWidth < CompactColumnsWidth;
        if (compact != _compactColumns)
        {
            _compactColumns = compact;
            SetTypeColumn(_listHeader, 2, compact);
            foreach (var item in _list.Items.OfType<ListViewItem>())
                if (item.Content is Grid row) SetTypeColumn(row, 3, compact);
        }
        UpdateHeaderPadding();
    }

    private static void SetTypeColumn(Grid grid, int index, bool compact)
    {
        grid.ColumnDefinitions[index].Width = new GridLength(compact ? 0 : TypeColumnWidth);
        foreach (var child in grid.Children)
            if (child is FrameworkElement element && Grid.GetColumn(element) == index)
                element.Visibility = compact ? Visibility.Collapsed : Visibility.Visible;
    }

    private void UpdateHeaderPadding()
    {
        var scroll = FindScrollViewer(_list);
        double reserved = 0;
        if (scroll is not null && scroll.ComputedVerticalScrollBarVisibility == Visibility.Visible)
            reserved = Math.Max(0, scroll.ActualWidth - scroll.ViewportWidth);
        _listHeader.Padding = new Thickness(HeaderLeftPadding, 8, RowRightPadding + reserved, 6);
    }

    private static ScrollViewer? FindScrollViewer(DependencyObject node)
    {
        if (node is ScrollViewer viewer) return viewer;
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(node); index++)
        {
            if (FindScrollViewer(VisualTreeHelper.GetChild(node, index)) is { } found) return found;
        }
        return null;
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
        var segments = _prefix.Length == 0 ? Array.Empty<string>() : _prefix.Split('/');
        _rootCrumb.Visibility = segments.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        _parentCrumb.Visibility = segments.Length > 1 ? Visibility.Visible : Visibility.Collapsed;
        _ancestorButton.Visibility = segments.Length > 2 ? Visibility.Visible : Visibility.Collapsed;
        _ancestors.Items.Clear();
        var accumulated = "";
        for (int index = 0; index < segments.Length - 1; index++)
        {
            var segment = segments[index];
            accumulated = accumulated.Length == 0 ? segment : accumulated + "/" + segment;
            var target = accumulated;
            if (index == segments.Length - 2)
            {
                ((TextBlock)_parentCrumb.Content).Text = segment;
                _parentCrumb.Tag = target;
                AutomationProperties.SetName(_parentCrumb, segment);
                AutomationProperties.SetHelpText(_parentCrumb, "/" + target);
                ToolTipService.SetToolTip(_parentCrumb, "/" + target);
            }
            else
            {
                var item = new MenuFlyoutItem { Text = segment };
                AutomationProperties.SetHelpText(item, "/" + target);
                item.Click += (_, _) => NavigateTo(target);
                _ancestors.Items.Add(item);
            }
        }
        var current = segments.Length == 0 ? "压缩包" : segments[^1];
        _currentCrumb.Text = segments.Length == 0 ? current : "›  " + current;
        AutomationProperties.SetName(_currentCrumb, current);
        AutomationProperties.SetHelpText(_currentCrumb, "/" + _prefix);
        ToolTipService.SetToolTip(_currentCrumb, "/" + _prefix);
    }

    private Button CrumbButton(string text, string id)
    {
        var button = new Button
        {
            Content = new TextBlock { Text = text, FontSize = 13, TextTrimming = TextTrimming.CharacterEllipsis },
            Style = (Style)Application.Current.Resources["FwTextIconButtonStyle"],
            Height = 28,
            MinHeight = 28,
            Padding = new Thickness(8, 0, 8, 0),
        };
        AutomationProperties.SetAutomationId(button, id);
        AutomationProperties.SetName(button, text);
        button.Click += (_, _) =>
        {
            if (button.Tag is string target) NavigateTo(target);
        };
        return button;
    }

    private void List_DoubleTapped(object sender, Microsoft.UI.Xaml.Input.DoubleTappedRoutedEventArgs args)
    {
        if (_busy || _confirming) return;
        for (DependencyObject? source = args.OriginalSource as DependencyObject; source is not null && source != _list; source = VisualTreeHelper.GetParent(source))
        {
            if (source is not ListViewItem { Tag: ArchiveRow row }) continue;
            Activate(row);
            args.Handled = true;
            return;
        }
    }

    private void Activate(ArchiveRow row)
    {
        if (_busy || _confirming) return;
        if (row.IsDirectory) { NavigateTo(row.Entry.FullName.Trim('/')); return; }
        _ = PreviewAsync(row);
    }

    private async Task PreviewAsync(ArchiveRow row)
    {
        if (_session is not { } session || _busy || _confirming || _closed) return;
        var prefix = _prefix;
        var extension = Path.GetExtension(row.Name).ToLowerInvariant();
        try
        {
            if (FileRow.TextExtensions.Contains(extension))
            {
                if (row.Entry.Size > 4L * 1024 * 1024)
                {
                    ShowBanner("文本超过 4 MB 预览上限，可解压后查看。", error: false);
                    return;
                }
                var bytes = await session.ReadEntryAsync(row.Entry.Index, 4L * 1024 * 1024, _lifetime.Token);
                if (_closed || _busy || _confirming || _prefix != prefix) return;
                var (text, encoding) = FilewayClientServices.DecodeText(bytes.Bytes);
                var resource = PreviewResource(row);
                var content = new TextContent(resource, bytes.Bytes.LongLength, text, encoding);
                var window = new TextWindow(new ArchiveTextContentService(content), resource);
                TrackPreview(window);
                window.SetTheme(_theme);
                window.Activate();
                return;
            }
            if (FileRow.ImageExtensions.Contains(extension))
            {
                if (row.Entry.Size > 32L * 1024 * 1024)
                {
                    ShowBanner("图片超过 32 MB 预览上限，可解压后查看。", error: false);
                    return;
                }
                var bytes = await session.ReadEntryAsync(row.Entry.Index, 32L * 1024 * 1024, _lifetime.Token);
                if (_closed || _busy || _confirming || _prefix != prefix) return;
                var resource = PreviewResource(row);
                var window = new ImageWindow(new ArchiveImageContentService(bytes.Bytes), resource);
                TrackPreview(window);
                window.SetTheme(_theme);
                window.Activate();
                return;
            }
            ShowBanner("该类型暂不支持预览，可解压后打开。", error: false);
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (_closed || _busy || _confirming || _prefix != prefix) return;
            ShowBanner(error is FilewayException fileway ? fileway.Error.Message : "预览未能打开，请重试。", error: true);
        }
    }

    private RemoteResourceRef PreviewResource(ArchiveRow row) => new(
        _archive.Account,
        _archive.WirePath,
        row.Name,
        _archive.DisplayPath + " / " + row.Entry.FullName);

    private void TrackPreview(Window window)
    {
        _previewWindows.Add(window);
        window.Closed += (_, _) => _previewWindows.Remove(window);
    }

    private void OnPreviewKeyDown(object sender, KeyRoutedEventArgs args)
    {
        if (_confirming || _busy || _ancestors.IsOpen) return;
        if (args.Key == VirtualKey.Escape)
        {
            Close();
            args.Handled = true;
            return;
        }

        var source = args.OriginalSource as DependencyObject;
        if (!IsListKeyboardSource(source)) return;
        if (args.Key == VirtualKey.Enter && _list.SelectedItem is ListViewItem { Tag: ArchiveRow row })
        {
            Activate(row);
            args.Handled = true;
            return;
        }

        // Root focus is the fallback before the first item gets focus. Once inside the
        // ListView, WinUI handles arrows, including modified keys for range selection.
        var count = _list.Items.Count;
        if (!ReferenceEquals(source, _root) || count == 0) return;
        switch (args.Key)
        {
            case VirtualKey.Down:
                MoveSelection(1);
                break;
            case VirtualKey.Up:
                MoveSelection(-1);
                break;
            case VirtualKey.Home:
                SelectIndex(0);
                break;
            case VirtualKey.End:
                SelectIndex(count - 1);
                break;
            default:
                return;
        }

        args.Handled = true;
    }

    private bool IsListKeyboardSource(DependencyObject? source)
    {
        if (ReferenceEquals(source, _root)) return true;
        for (var current = source; current is not null && current != _root; current = VisualTreeHelper.GetParent(current))
        {
            if (current is ButtonBase or TextBox) return false;
            if (ReferenceEquals(current, _list)) return true;
        }
        return false;
    }

    private void SelectAllEntries() => _list.SelectAll();

    private void MoveSelection(int delta)
    {
        var count = _list.Items.Count;
        // With nothing selected yet, Down enters at the top and Up at the bottom, like Explorer.
        var target = _list.SelectedIndex < 0
            ? delta > 0 ? 0 : count - 1
            : Math.Clamp(_list.SelectedIndex + delta, 0, count - 1);
        SelectIndex(target);
    }

    private void SelectIndex(int index)
    {
        _list.SelectedIndex = index;
        _list.ScrollIntoView(_list.Items[index]);
        if (_list.ContainerFromIndex(index) is ListViewItem item) _ = item.Focus(FocusState.Keyboard);
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
        if (_session is not { } session || _busy || _confirming || _closed) return;
        var indexes = SelectedFileIndexes(all);
        if (indexes.Count == 0)
        {
            ShowBanner(_list.SelectedItems.Count == 0 ? "请先选择要解压的文件或文件夹。" : "所选条目没有可解压的文件。", error: false);
            return;
        }
        if (indexes.Count > MaximumExtractItems)
        {
            ShowBanner($"一次最多解压 {MaximumExtractItems} 个文件，请分批选择。", error: false);
            return;
        }

        var observed = new List<ArchiveExtractItem>();
        string? extractTarget = null;
        bool reporting = false;
        _confirming = true;
        UpdateCommands();
        try
        {
            var state = await _settings.ReadAsync(_lifetime.Token);
            if (_closed) return;
            string leaf = Path.GetFileNameWithoutExtension(_archive.DisplayName);
            leaf = DownloadPresentationPolicy.IsSafeLocalName(leaf) ? leaf : "archive";
            string defaultTarget = state.Active.DownloadDirectory.TrimEnd('\\') + "\\archive-extract\\" + leaf;
            var targetBox = new TextBox
            {
                Text = defaultTarget,
                Style = (Style)Application.Current.Resources["FwTextBoxStyle"],
                FontSize = 13,
                TextWrapping = TextWrapping.NoWrap,
                HorizontalAlignment = HorizontalAlignment.Stretch,
            };
            AutomationProperties.SetAutomationId(targetBox, "ArchiveExtractTarget");
            AutomationProperties.SetName(targetBox, "解压目标文件夹");
            var browse = new Button
            {
                Content = "浏览…",
                Style = (Style)Application.Current.Resources["FwSecondaryButtonStyle"],
                Height = 36,
                MinHeight = 36,
                Padding = new Thickness(14, 0, 14, 0),
                VerticalAlignment = VerticalAlignment.Center,
            };
            var targetGrid = new Grid { ColumnSpacing = 8 };
            targetGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            targetGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            targetGrid.Children.Add(targetBox);
            Grid.SetColumn(browse, 1);
            targetGrid.Children.Add(browse);
            var error = new TextBlock { Visibility = Visibility.Collapsed };
            error.Style = (Style)Application.Current.Resources["FwDangerTextStyle"];
            browse.Click += async (_, _) =>
            {
                try
                {
                    var picker = new Microsoft.Windows.Storage.Pickers.FolderPicker(AppWindow.Id)
                    {
                        SuggestedStartLocation = Microsoft.Windows.Storage.Pickers.PickerLocationId.ComputerFolder,
                        Title = "选择解压目标文件夹",
                        CommitButtonText = "选择此文件夹",
                    };
                    var folder = await picker.PickSingleFolderAsync().AsTask(_lifetime.Token);
                    if (folder is not null)
                    {
                        targetBox.Text = folder.Path;
                        error.Visibility = Visibility.Collapsed;
                    }
                }
                catch (OperationCanceledException) { }
                catch (Exception)
                {
                    error.Text = "文件夹选择器未能打开，请直接输入路径。";
                    error.Visibility = Visibility.Visible;
                }
            };
            var panel = new StackPanel { Spacing = 10, MinWidth = 300, MaxWidth = 480 };
            panel.Children.Add(new TextBlock { Text = $"解压 {indexes.Count} 个文件到：", TextWrapping = TextWrapping.Wrap });
            panel.Children.Add(targetGrid);
            panel.Children.Add(new TextBlock { Text = "已存在的同名文件将跳过且不覆盖；解压结果逐条列明。", FontSize = 12, Opacity = 0.75, TextWrapping = TextWrapping.Wrap });
            panel.Children.Add(error);
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
            confirm.PrimaryButtonClick += (_, args) =>
            {
                if (IsValidTarget(targetBox.Text)) return;
                error.Text = "请输入完整的目标文件夹路径；不能是已存在的文件。";
                error.Visibility = Visibility.Visible;
                args.Cancel = true;
            };
            var choice = await confirm.ShowAsync();
            if (choice != ContentDialogResult.Primary || _closed) return;
            string target = targetBox.Text.Trim();
            if (!IsValidTarget(target)) return;
            extractTarget = target;
            ClearExtractResults();
            _bannerTimer?.Stop();
            _banner.Visibility = Visibility.Collapsed;
            _extractBar.Maximum = indexes.Count;
            _extractBar.Value = 0;
            _extractProgressText.Text = $"正在解压 0/{indexes.Count}";
            _progressHost.Visibility = Visibility.Visible;
            _busy = true;
            _confirming = false;
            reporting = true;
            UpdateCommands();
            var progress = new Progress<ArchiveExtractItem>(item =>
            {
                if (_closed || !reporting) return;
                observed.Add(item);
                _extractBar.Value = Math.Min(indexes.Count, observed.Count);
                _extractProgressText.Text = $"正在解压 {Math.Min(indexes.Count, observed.Count)}/{indexes.Count}";
            });
            var result = await session.ExtractAsync(indexes, target, _lifetime.Token, progress);
            if (_closed) return;
            PresentExtractResults(result.Items, ExtractSummary(result.Extracted, result.Skipped, result.Failed), target);
        }
        catch (OperationCanceledException)
        {
            if (!_closed && extractTarget is not null)
                PresentInterruptedExtraction(observed, extractTarget, "解压已停止。");
        }
        catch (Exception error)
        {
            if (_closed) return;
            var message = error is FilewayException fileway ? fileway.Error.Message : "解压未能完成，请重试。";
            if (extractTarget is not null)
                PresentInterruptedExtraction(observed, extractTarget, message);
            else
                ShowBanner(message, error: true);
        }
        finally
        {
            reporting = false;
            _busy = false;
            _confirming = false;
            if (!_closed)
            {
                _progressHost.Visibility = Visibility.Collapsed;
                UpdateCommands();
            }
        }
    }

    private static bool IsValidTarget(string? path)
    {
        if (string.IsNullOrWhiteSpace(path)) return false;
        try
        {
            string target = path.Trim();
            return Path.IsPathFullyQualified(target) && !File.Exists(Path.GetFullPath(target));
        }
        catch (Exception) { return false; }
    }

    private void ClearExtractResults()
    {
        _resultsList.Items.Clear();
        _resultsHost.Visibility = Visibility.Collapsed;
        _progressHost.Visibility = Visibility.Collapsed;
        _resultsSummary.Text = "";
        _resultsMessage.Text = "";
    }

    private static string ExtractSummary(int extracted, int skipped, int failed) =>
        $"{extracted} 成功 · {skipped} 跳过 · {failed} 失败";

    private void PresentInterruptedExtraction(List<ArchiveExtractItem> observed, string target, string message)
    {
        var summary = observed.Count == 0 ? "" : ExtractSummary(
            observed.Count(item => item.Outcome == ArchiveExtractOutcome.Extracted),
            observed.Count(item => item.Outcome == ArchiveExtractOutcome.Skipped),
            observed.Count(item => item.Outcome == ArchiveExtractOutcome.Failed));
        PresentExtractResults(observed, summary, target, message);
    }

    private void PresentExtractResults(IReadOnlyList<ArchiveExtractItem> items, string summary, string target, string? message = null)
    {
        _resultsSummary.Text = summary;
        _resultsSummary.Visibility = summary.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        _resultsMessage.Text = message ?? "";
        _resultsMessage.Visibility = message is null ? Visibility.Collapsed : Visibility.Visible;
        ToolTipService.SetToolTip(_resultsHeader, target);
        AutomationProperties.SetHelpText(_resultsHeader, "解压目标：" + target);
        _resultsList.Items.Clear();
        foreach (var item in items) AddExtractResult(item);
        _resultsToggle.Content = "收起";
        _resultsToggle.Visibility = _resultsList.Visibility = items.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
        _resultsHost.Visibility = Visibility.Visible;
    }

    private void AddExtractResult(ArchiveExtractItem item)
    {
        var (iconName, styleKey, label) = item.Outcome switch
        {
            ArchiveExtractOutcome.Extracted => ("folder-open", "FwPrimaryIconStyle", "已解压"),
            ArchiveExtractOutcome.Skipped => ("minus", "FwSecondaryIconStyle", "已跳过"),
            _ => ("alert-triangle", "FwDangerIconStyle", "失败"),
        };
        var icon = new LucideIcon
        {
            IconName = iconName,
            Width = 18,
            Height = 18,
            VerticalAlignment = VerticalAlignment.Top,
            Margin = new Thickness(0, 2, 0, 0),
            Style = (Style)Application.Current.Resources[styleKey],
        };
        var separator = item.FullName.LastIndexOf('/');
        var leaf = separator + 1 < item.FullName.Length ? item.FullName[(separator + 1)..] : item.FullName;
        var name = new TextBlock
        {
            Text = leaf,
            Style = (Style)Application.Current.Resources["FwToolbarTextStyle"],
            TextTrimming = TextTrimming.CharacterEllipsis,
        };
        ToolTipService.SetToolTip(name, item.FullName);
        var outcome = new TextBlock { Text = label, FontSize = 12 };
        outcome.Style = (Style)Application.Current.Resources[
            item.Outcome == ArchiveExtractOutcome.Failed ? "FwDangerTextStyle" : "FwCaptionTextStyle"];
        var text = new StackPanel { Spacing = 2 };
        text.Children.Add(name);
        text.Children.Add(outcome);
        var reason = item.Error ?? (item.Outcome == ArchiveExtractOutcome.Skipped ? "目标已存在，未覆盖。" : null);
        if (!string.IsNullOrEmpty(reason))
        {
            var detail = new TextBlock { Text = reason, TextWrapping = TextWrapping.Wrap };
            detail.Style = (Style)Application.Current.Resources[
                item.Outcome == ArchiveExtractOutcome.Failed ? "FwDangerTextStyle" : "FwCaptionTextStyle"];
            text.Children.Add(detail);
        }
        var grid = new Grid { ColumnSpacing = 10, Padding = new Thickness(4, 4, 8, 4) };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(22) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.Children.Add(icon);
        Grid.SetColumn(text, 1);
        grid.Children.Add(text);
        var row = new ListViewItem { Content = grid, Tag = item, IsEnabled = true };
        AutomationProperties.SetName(row, item.FullName + " " + label);
        _resultsList.Items.Add(row);
    }

    private void UpdateCommands()
    {
        var canBrowse = !_closed && !_busy && !_confirming && _session is not null;
        _extractSelected.IsEnabled = canBrowse && _list.SelectedItems.Count > 0;
        _extractAll.IsEnabled = canBrowse && _session?.Entries.Any(entry => !entry.IsDirectory) == true;
        _rootCrumb.IsEnabled = _parentCrumb.IsEnabled = _ancestorButton.IsEnabled = canBrowse;
        _selectAll.IsEnabled = canBrowse && _list.Items.Count > 0;
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
