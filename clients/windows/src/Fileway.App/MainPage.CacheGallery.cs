using Fileway.App.Controls;
using Fileway.App.Controls.Settings;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App;

public sealed partial class MainPage
{
    private ContentDialog? _cacheGalleryDialog;
    private Func<bool>? _cacheGalleryCurrent;

    private void UpdateCacheGalleryContext()
    {
        if (_cacheGalleryCurrent?.Invoke() == false)
            _cacheGalleryDialog?.Hide();
    }

    private async void CacheGallery_Click(object sender, RoutedEventArgs args)
    {
        if (_disposed || _browserDialog is not null) return;
        if (_session is not { } session || !session.Permissions.Download || _files is not ILocalCacheGalleryService service)
        {
            SetNotice("缓存图库", "连接有下载权限的账户后，即可查看该账户保存在本机的缩略图。", InfoBarSeverity.Informational);
            return;
        }
        bool IsCurrent() => !_disposed && _session is { } current && current.Id == session.Id && current.Permissions.Download;
        using var gallery = new CacheGalleryContent(service, session.Account, IsCurrent, _lifetime.Token);
        var dialog = new ContentDialog
        {
            Title = "缓存图库",
            Content = new ScrollViewer
            {
                Content = gallery, MaxHeight = DialogOrdering.ContentMaxHeight(this, 460),
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            },
            CloseButtonText = "关闭",
            PrimaryButtonText = "管理缓存…",
        };
        _cacheGalleryDialog = dialog;
        _cacheGalleryCurrent = IsCurrent;
        ContentDialogResult result;
        try { result = await ShowBrowserDialogAsync(dialog); }
        finally { _cacheGalleryDialog = null; _cacheGalleryCurrent = null; }
        if (result == ContentDialogResult.Primary && IsCurrent())
            StorageSettings_Click(this, new RoutedEventArgs());
    }
}
