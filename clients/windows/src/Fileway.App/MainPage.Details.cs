using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;

namespace Fileway.App;

public sealed partial class MainPage
{
    private async void DetailsLocation_Click(object sender, RoutedEventArgs e)
    {
        if (_disposed || _connecting || _session is not { } session || ActiveFiles.SelectedItem is not FileRow row) return;
        var wire = row.File.Resource.WirePath;
        var display = row.File.Resource.DisplayPath;
        var cut = wire.LastIndexOf('/');
        var parentWire = cut <= 0 ? "/" : wire[..cut];
        var displayCut = display.LastIndexOf('/');
        var parentDisplay = displayCut <= 0 ? "/" : display[..displayCut];
        var parentName = parentDisplay == "/" ? "我的文件" : parentDisplay[(parentDisplay.LastIndexOf('/') + 1)..];
        SetDetailsVisible(false);
        await NavigateToResourceAsync(new RemoteResourceRef(session.Account, parentWire, parentName, parentDisplay));
    }

    private void DetailsChecksum_Click(object sender, RoutedEventArgs e) => FileChecksum_Click(sender, e);

    private async void DetailsRefreshMetadata_Click(object sender, RoutedEventArgs e)
    {
        if (_disposed || _connecting || _session is not { } session || _files is null || ActiveFiles.SelectedItem is not FileRow row) return;
        var source = row.File.Resource;
        var generation = _generation;
        try
        {
            var metadata = await _files.GetAsync(source, _lifetime.Token);
            if (_disposed || _generation != generation || _session?.Id != session.Id) return;
            var index = _rows.IndexOf(row);
            if (index >= 0) _rows[index] = new FileRow(metadata, _browserPreferences);
            UpdateSelection();
            SetNotice("元数据已刷新", metadata.Resource.DisplayName, Microsoft.UI.Xaml.Controls.InfoBarSeverity.Success);
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (_generation == generation) SetNotice("元数据刷新失败", ErrorMessage(error), Microsoft.UI.Xaml.Controls.InfoBarSeverity.Warning);
        }
    }
}