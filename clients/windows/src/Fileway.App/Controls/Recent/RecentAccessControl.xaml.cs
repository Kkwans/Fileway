using System.Collections.ObjectModel;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.System;

namespace Fileway.App.Controls.Recent;

/// <summary>A read-only server history view. Selection and refreshing never record an open.</summary>
public sealed partial class RecentAccessControl : UserControl, IDisposable
{
    private readonly RecentAccessListController _controller = new();
    private readonly ObservableCollection<RecentAccessRow> _rows = [];
    private bool _disposed;
    private bool _rendering;
    public event EventHandler? CloseRequested;
    public Func<RemoteFile, Task>? OpenResourceAsync { get; set; }

    public RecentAccessControl()
    {
        InitializeComponent(); Fileway.App.Controls.FloatingNotice.Attach(RecentError); RecentRecords.ItemsSource = _rows;
        Render();
    }

    public void SetContext(IRecentAccessService? service, IFileRepository? files, ConnectionSession? session)
    {
        if (_disposed) return;
        var generation = _controller.Generation;
        _controller.SetContext(service, files, session);
        if (generation != _controller.Generation) Render();
        else UpdateCommands();
    }

    public async Task RefreshAsync()
    {
        if (_disposed || Visibility != Visibility.Visible || _controller.IsLoading) return;
        var pending = _controller.RefreshAsync();
        Render(synchronizeRows: false);
        await pending;
        if (!_disposed && Visibility == Visibility.Visible) Render();
    }

    public void SuspendView()
    {
        if (!_disposed) _controller.SuspendView();
    }

    private void Render(bool synchronizeRows = true)
    {
        if (_disposed) return;
        if (synchronizeRows)
        {
            var selectedId = (RecentRecords.SelectedItem as RecentAccessRow)?.Entry.Id;
            _rendering = true;
            try
            {
                _rows.Clear();
                foreach (var entry in _controller.Items) _rows.Add(new RecentAccessRow(entry));
                if (selectedId is not null) RecentRecords.SelectedItem = _rows.FirstOrDefault(row => row.Entry.Id == selectedId);
            }
            finally { _rendering = false; }
        }
        RecentLoadingRing.IsActive = _controller.IsLoading || _controller.IsOpening;
        RecentLoadingRing.Visibility = RecentLoadingRing.IsActive ? Visibility.Visible : Visibility.Collapsed;
        RecentRefreshButton.IsEnabled = !_controller.IsLoading;
        RecentError.IsOpen = _controller.Error is not null;
        RecentError.Message = _controller.Error;
        RecentErrorPanel.Visibility = _controller.Error is not null ? Visibility.Visible : Visibility.Collapsed;
        RecentHeaders.Visibility = _rows.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
        RecentEmptyState.Visibility = _rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        RecentEmptyState.Title = _controller.IsLoading ? "正在读取最近访问" : _controller.Error is not null ? "记录暂不可用" : "暂无最近访问";
        RecentEmptyState.Description = _controller.Error is not null ? "可以刷新重试，未确认的资源不会被打开。" : "成功打开远端图片或视频后，可刷新查看服务器记录。";
        RecentCount.Text = $"当前账号 · {_rows.Count} 条服务器记录";
        UpdateCommands();
    }

    private void UpdateCommands()
    {
        OpenSelectedButton.Visibility = RecentRecords.SelectedItem is RecentAccessRow ? Visibility.Visible : Visibility.Collapsed;
        OpenSelectedButton.IsEnabled = !_disposed && OpenResourceAsync is not null
            && RecentRecords.SelectedItem is RecentAccessRow row && _controller.CanOpen(row.Entry);
    }

    private async Task OpenSelectedAsync()
    {
        if (_disposed || Visibility != Visibility.Visible || OpenResourceAsync is not { } open
            || RecentRecords.SelectedItem is not RecentAccessRow row || !_controller.CanOpen(row.Entry)) return;
        var pending = _controller.OpenAsync(row.Entry, open);
        Render(synchronizeRows: false);
        await pending;
        if (!_disposed && Visibility == Visibility.Visible) Render(synchronizeRows: false);
    }

    private void Close_Click(object sender, RoutedEventArgs e) => CloseRequested?.Invoke(this, EventArgs.Empty);
    private async void Refresh_Click(object sender, RoutedEventArgs e) => await RefreshAsync();
    private async void Open_Click(object sender, RoutedEventArgs e) => await OpenSelectedAsync();
    private void Record_Changed(object sender, SelectionChangedEventArgs e) { if (!_rendering) UpdateCommands(); }
    private async void Records_PreviewKeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Handled || e.Key != VirtualKey.Enter || e.KeyStatus.WasKeyDown) return;
        foreach (var modifier in new[] { VirtualKey.Control, VirtualKey.Menu, VirtualKey.Shift })
            if (Microsoft.UI.Input.InputKeyboardSource.GetKeyStateForCurrentThread(modifier)
                .HasFlag(Windows.UI.Core.CoreVirtualKeyStates.Down)) return;
        e.Handled = true;
        await OpenSelectedAsync();
    }
    private async void Records_DoubleTapped(object sender, DoubleTappedRoutedEventArgs e)
    {
        var source = e.OriginalSource as DependencyObject;
        while (source is not null && !ReferenceEquals(source, RecentRecords) && source is not SelectorItem) source = VisualTreeHelper.GetParent(source);
        if (source is not SelectorItem item) return;
        RecentRecords.SelectedItem = item.Content;
        await OpenSelectedAsync();
    }
    private void Panel_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        if (sender is Grid grid) grid.Padding = new Thickness(e.NewSize.Width < 500 ? 12 : 20, 12, e.NewSize.Width < 500 ? 12 : 20, 12);
    }
    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true; _controller.Dispose(); _rows.Clear(); OpenResourceAsync = null;
    }
}
