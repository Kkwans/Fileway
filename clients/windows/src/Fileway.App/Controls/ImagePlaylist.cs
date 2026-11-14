using Fileway.App.Branding;
using Fileway.Core.Contracts;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Markup;
using Microsoft.UI.Xaml.Media;

namespace Fileway.App.Controls;

/// <summary>The viewer's ordered, virtualized strip of image previews.</summary>
internal sealed class ImagePlaylist : UserControl, IDisposable
{
    private const int MaximumThumbnailLoads = 3;
    private readonly ListView _list;
    private readonly Dictionary<SelectorItem, Tile> _tiles = [];
    private readonly long _visibilityCallback;
    private List<Entry> _entries = [];
    internal sealed record Thumbnail(ImageSource Source, double AspectRatio);
    private const double PlaceholderAspectRatio = 1.38;
    private Func<RemoteResourceRef, CancellationToken, Task<Thumbnail?>>? _loadThumbnail;
    private ScrollViewer? _scroll;
    private int _currentIndex = -1;
    private int _firstVisible = -1;
    private int _lastVisible = -1;
    private int _activeLoads;
    private int _thumbnailHeight = PresentationPreferences.DefaultImageThumbnailHeight;
    private double _totalAspectRatio;
    private bool _settingSelection;
    private bool _scrollToSelection;
    private bool _waitingForTemplate;
    private bool _refreshQueued;
    private bool _disposed;

    internal event EventHandler<int>? ImageSelected;

    internal ImagePlaylist()
    {
        Visibility = Visibility.Collapsed;
        IsTabStop = false;
        HorizontalContentAlignment = HorizontalAlignment.Stretch;
        VerticalContentAlignment = VerticalAlignment.Stretch;
        _list = (ListView)XamlReader.Load("""
            <ListView xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
                      xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
                      Padding="3,2" SelectionMode="Single" SingleSelectionFollowsFocus="True"
                      ScrollViewer.HorizontalScrollMode="Enabled" ScrollViewer.HorizontalScrollBarVisibility="Hidden"
                      ScrollViewer.VerticalScrollMode="Disabled" ScrollViewer.VerticalScrollBarVisibility="Hidden">
              <ListView.Resources>
                <ResourceDictionary>
                  <ResourceDictionary.ThemeDictionaries>
                    <ResourceDictionary x:Key="Default">
                      <SolidColorBrush x:Key="ListViewItemBackgroundSelected" Color="Transparent" />
                    </ResourceDictionary>
                    <ResourceDictionary x:Key="HighContrast">
                      <StaticResource x:Key="ListViewItemBackgroundSelected" ResourceKey="SystemColorHighlightColorBrush" />
                    </ResourceDictionary>
                  </ResourceDictionary.ThemeDictionaries>
                </ResourceDictionary>
              </ListView.Resources>
              <ListView.ItemsPanel>
                <ItemsPanelTemplate><ItemsStackPanel Orientation="Horizontal" /></ItemsPanelTemplate>
              </ListView.ItemsPanel>
              <ListView.ItemContainerStyle>
                <Style TargetType="ListViewItem">
                  <Setter Property="Margin" Value="2,0" />
                  <Setter Property="Padding" Value="0" />
                  <Setter Property="MinWidth" Value="0" />
                  <Setter Property="CornerRadius" Value="6" />
                  <Setter Property="HorizontalContentAlignment" Value="Stretch" />
                  <Setter Property="VerticalContentAlignment" Value="Stretch" />
                  <Setter Property="UseSystemFocusVisuals" Value="True" />
                </Style>
              </ListView.ItemContainerStyle>
              <ListView.ItemTemplate>
                <DataTemplate>
                  <Grid HorizontalAlignment="Center" VerticalAlignment="Center" CornerRadius="6"
                        IsHitTestVisible="False" AutomationProperties.AccessibilityView="Raw">
                    <Border CornerRadius="6">
                      <BitmapIcon x:Name="Fallback" UriSource="ms-appx:///Assets/Lucide/light/image.png" Width="19" Height="19"
                                  Foreground="{ThemeResource FwTextSecondaryBrush}" />
                    </Border>
                    <Image x:Name="Preview" Stretch="Fill" HorizontalAlignment="Stretch" VerticalAlignment="Stretch" />
                    <Border x:Name="Selection" BorderBrush="{ThemeResource FwPrimaryButtonBackgroundBrush}"
                            BorderThickness="2" CornerRadius="6" Visibility="Collapsed" />
                  </Grid>
                </DataTemplate>
              </ListView.ItemTemplate>
            </ListView>
            """);
        AutomationProperties.SetAutomationId(_list, "ImagePlaylist");
        AutomationProperties.SetName(_list, AppBranding.IsChinese ? "图片列表" : "Image playlist");
        _list.ContainerContentChanging += OnContainerContentChanging;
        _list.SelectionChanged += OnSelectionChanged;
        _list.Loaded += OnLoaded;
        _list.Unloaded += OnUnloaded;
        _list.LayoutUpdated += OnLayoutUpdated;
        Content = _list;
        UpdateListSize();
        _visibilityCallback = RegisterPropertyChangedCallback(VisibilityProperty, OnVisibilityChanged);
    }

    internal void SetThumbnailHeight(int height)
    {
        if (_thumbnailHeight == height) return;
        _thumbnailHeight = height;
        UpdateListSize();
        foreach (var tile in _tiles.Values) SizeTile(tile);
        _scrollToSelection = _currentIndex >= 0;
        QueueRefresh();
    }

    private void UpdateListSize()
    {
        _list.Height = _thumbnailHeight + 8;
        MaxWidth = Math.Min(720, 12 + _totalAspectRatio * _thumbnailHeight + _entries.Count * 8);
    }

    private void SizeTile(Tile tile)
    {
        var width = Math.Max(1, _thumbnailHeight * tile.Entry.AspectRatio);
        tile.Container.Width = width;
        tile.Container.Height = _thumbnailHeight;
        if (tile.Template is not { } template) return;
        template.Width = width;
        template.Height = _thumbnailHeight;
    }

    internal void SetItems(IReadOnlyList<RemoteResourceRef> resources,
        Func<RemoteResourceRef, CancellationToken, Task<Thumbnail?>>? loadThumbnail = null)
    {
        if (_disposed) return;
        ClearTiles();
        _loadThumbnail = loadThumbnail;
        _entries = resources.Select((resource, index) => new Entry(resource, index)).ToList();
        _totalAspectRatio = _entries.Count * PlaceholderAspectRatio;
        UpdateListSize();
        _currentIndex = -1;
        _scrollToSelection = false;
        _settingSelection = true;
        try { _list.ItemsSource = _entries; }
        finally { _settingSelection = false; }
        Visibility = _entries.Count > 1 ? Visibility.Visible : Visibility.Collapsed;
        QueueRefresh();
    }

    internal void SetCurrent(int index)
    {
        if (_disposed || (uint)index >= (uint)_entries.Count) return;
        _currentIndex = index;
        UpdateSelection();
        _scrollToSelection = true;
        QueueRefresh();
    }

    private void UpdateSelection()
    {
        _settingSelection = true;
        try { _list.SelectedIndex = _currentIndex; }
        finally { _settingSelection = false; }
        foreach (var tile in _tiles.Values) ShowSelection(tile);
    }

    private void OnSelectionChanged(object sender, SelectionChangedEventArgs args)
    {
        if (_settingSelection || _disposed) return;
        var index = _list.SelectedIndex;
        if (index < 0)
        {
            if (_currentIndex >= 0) UpdateSelection();
            return;
        }
        if (index == _currentIndex) return;
        _currentIndex = index;
        foreach (var tile in _tiles.Values) ShowSelection(tile);
        ImageSelected?.Invoke(this, index);
    }

    private void OnContainerContentChanging(ListViewBase sender, ContainerContentChangingEventArgs args)
    {
        var container = args.ItemContainer;
        if (!args.InRecycleQueue && _tiles.TryGetValue(container, out var existing) && ReferenceEquals(existing.Entry, args.Item))
            return;
        if (_tiles.Remove(container, out var oldTile)) ResetTile(oldTile);
        if (_disposed || args.InRecycleQueue || args.Item is not Entry entry ||
            (uint)entry.Index >= (uint)_entries.Count || !ReferenceEquals(_entries[entry.Index], entry)) return;
        var name = string.IsNullOrWhiteSpace(entry.Resource.DisplayName) ? entry.Resource.DisplayPath : entry.Resource.DisplayName;
        AutomationProperties.SetName(container, name);
        ToolTipService.SetToolTip(container, name);
        var tile = new Tile(container, entry);
        _tiles.Add(container, tile);
        SizeTile(tile);
        QueueRefresh();
    }

    private void OnLoaded(object sender, RoutedEventArgs args) => QueueRefresh();

    private void OnUnloaded(object sender, RoutedEventArgs args)
    {
        DetachScrollViewer();
        StopThumbnailLoads();
    }

    private void OnVisibilityChanged(DependencyObject sender, DependencyProperty property)
    {
        if (Visibility == Visibility.Collapsed) StopThumbnailLoads();
        else QueueRefresh();
    }

    private void OnLayoutUpdated(object? sender, object args)
    {
        if (_list.ItemsPanelRoot is not ItemsStackPanel panel) return;
        if (_scroll is null || _waitingForTemplate || _scrollToSelection ||
            panel.FirstVisibleIndex != _firstVisible || panel.LastVisibleIndex != _lastVisible)
            QueueRefresh();
    }

    private void OnViewChanged(object? sender, ScrollViewerViewChangedEventArgs args) => QueueRefresh();

    private void QueueRefresh()
    {
        if (_disposed || _refreshQueued || !_list.IsLoaded || Visibility != Visibility.Visible) return;
        _refreshQueued = DispatcherQueue.TryEnqueue(DispatcherQueuePriority.Low, () =>
        {
            _refreshQueued = false;
            if (!_disposed && _list.IsLoaded && Visibility == Visibility.Visible) Refresh();
        });
    }

    private void Refresh()
    {
        if (_list.ItemsPanelRoot is not ItemsStackPanel panel || _list.ActualWidth <= 0) return;
        if (_scroll is null && FindScrollViewer(_list) is { } scroll)
        {
            _scroll = scroll;
            _scroll.ViewChanged += OnViewChanged;
        }
        if (_scrollToSelection && _currentIndex >= 0)
        {
            _scrollToSelection = false;
            _list.ScrollIntoView(_entries[_currentIndex]);
        }
        _firstVisible = panel.FirstVisibleIndex;
        _lastVisible = panel.LastVisibleIndex;
        _waitingForTemplate = false;
        foreach (var tile in _tiles.Values)
        {
            if (!IsVisible(tile, panel))
            {
                CancelThumbnail(tile);
                continue;
            }
            if (tile.Preview is null)
            {
                if (tile.Container.ContentTemplateRoot is not FrameworkElement template ||
                    template.FindName("Preview") is not Image preview ||
                    template.FindName("Selection") is not Border selection ||
                    template.FindName("Fallback") is not BitmapIcon fallback)
                {
                    _waitingForTemplate = true;
                    continue;
                }
                tile.Preview = preview;
                tile.Template = template;
                tile.Selection = selection;
                tile.Fallback = fallback;
                SizeTile(tile);
                ShowSelection(tile);
            }
            if (_loadThumbnail is not { } load || tile.Attempted || _activeLoads >= MaximumThumbnailLoads) continue;
            tile.Attempted = true;
            var request = new CancellationTokenSource();
            tile.Request = request;
            ++_activeLoads;
            _ = LoadThumbnailAsync(tile, request, load);
        }
    }

    private async Task LoadThumbnailAsync(Tile tile, CancellationTokenSource request,
        Func<RemoteResourceRef, CancellationToken, Task<Thumbnail?>> load)
    {
        try
        {
            var source = await load(tile.Entry.Resource, request.Token);
            if (source is null || request.IsCancellationRequested || !ReferenceEquals(tile.Request, request)) return;
            if (_list.ItemsPanelRoot is not ItemsStackPanel panel || !IsVisible(tile, panel))
            {
                tile.Attempted = false;
                return;
            }
            _totalAspectRatio += source.AspectRatio - tile.Entry.AspectRatio;
            tile.Entry.AspectRatio = source.AspectRatio;
            SizeTile(tile);
            UpdateListSize();
            tile.Preview!.Source = source.Source;
            tile.Fallback!.Visibility = Visibility.Collapsed;
        }
        catch (OperationCanceledException) { }
        catch (Exception) { /* A failed thumbnail leaves the image fallback visible. */ }
        finally
        {
            if (ReferenceEquals(tile.Request, request)) tile.Request = null;
            request.Dispose();
            --_activeLoads;
            QueueRefresh();
        }
    }

    private bool IsVisible(Tile tile, ItemsStackPanel panel) => _list.IsLoaded && Visibility == Visibility.Visible &&
        panel.FirstVisibleIndex >= 0 && tile.Entry.Index >= panel.FirstVisibleIndex && tile.Entry.Index <= panel.LastVisibleIndex &&
        ReferenceEquals(tile.Container.Content, tile.Entry);

    private void ShowSelection(Tile tile)
    {
        if (tile.Selection is { } selection)
            selection.Visibility = tile.Entry.Index == _currentIndex ? Visibility.Visible : Visibility.Collapsed;
    }

    private static void CancelThumbnail(Tile tile)
    {
        if (tile.Request is not { } request) return;
        tile.Request = null;
        tile.Attempted = false;
        request.Cancel();
    }

    private void StopThumbnailLoads()
    {
        foreach (var tile in _tiles.Values) CancelThumbnail(tile);
    }

    private static void ResetTile(Tile tile)
    {
        CancelThumbnail(tile);
        if (tile.Preview is { } preview) preview.Source = null;
        if (tile.Fallback is { } fallback) fallback.Visibility = Visibility.Visible;
        if (tile.Selection is { } selection) selection.Visibility = Visibility.Collapsed;
        AutomationProperties.SetName(tile.Container, string.Empty);
        ToolTipService.SetToolTip(tile.Container, null);
    }

    private void ClearTiles()
    {
        foreach (var tile in _tiles.Values) ResetTile(tile);
        _tiles.Clear();
    }

    private static ScrollViewer? FindScrollViewer(DependencyObject parent)
    {
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(parent); index++)
        {
            var child = VisualTreeHelper.GetChild(parent, index);
            if (child is ScrollViewer scroll) return scroll;
            if (FindScrollViewer(child) is { } descendant) return descendant;
        }
        return null;
    }

    private void DetachScrollViewer()
    {
        if (_scroll is null) return;
        _scroll.ViewChanged -= OnViewChanged;
        _scroll = null;
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        ClearTiles();
        DetachScrollViewer();
        UnregisterPropertyChangedCallback(VisibilityProperty, _visibilityCallback);
        _list.ContainerContentChanging -= OnContainerContentChanging;
        _list.SelectionChanged -= OnSelectionChanged;
        _list.Loaded -= OnLoaded;
        _list.Unloaded -= OnUnloaded;
        _list.LayoutUpdated -= OnLayoutUpdated;
        _list.ItemsSource = null;
        _entries.Clear();
        _loadThumbnail = null;
    }

    private sealed class Entry(RemoteResourceRef resource, int index)
    {
        public RemoteResourceRef Resource { get; } = resource;
        public int Index { get; } = index;
        public double AspectRatio { get; set; } = PlaceholderAspectRatio;
    }

    private sealed class Tile(SelectorItem container, Entry entry)
    {
        public SelectorItem Container { get; } = container;
        public Entry Entry { get; } = entry;
        public FrameworkElement? Template { get; set; }
        public Image? Preview { get; set; }
        public Border? Selection { get; set; }
        public BitmapIcon? Fallback { get; set; }
        public bool Attempted { get; set; }
        public CancellationTokenSource? Request { get; set; }
    }
}
