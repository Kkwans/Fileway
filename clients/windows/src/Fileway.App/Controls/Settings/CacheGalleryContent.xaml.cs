using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Graphics.Imaging;

namespace Fileway.App.Controls.Settings;

public sealed partial class CacheGalleryContent : UserControl, IDisposable
{
    private const int PageSize = 6;
    private readonly ILocalCacheGalleryService _service;
    private readonly AccountKey _account;
    private readonly Func<bool> _isCurrent;
    private readonly CancellationTokenSource _cancellation;
    private readonly CancellationToken _token;
    private LocalCacheGallery? _gallery;
    private int _page;
    private bool _busy;
    private string _summary = string.Empty;
    private Button? _viewed;

    public CacheGalleryContent(ILocalCacheGalleryService service, AccountKey account, Func<bool> isCurrent, CancellationToken lifetime)
    {
        _service = service;
        _account = account;
        _isCurrent = isCurrent;
        _cancellation = CancellationTokenSource.CreateLinkedTokenSource(lifetime);
        _token = _cancellation.Token;
        InitializeComponent();
        Loaded += OnLoaded;
    }

    private bool IsCurrent => !_token.IsCancellationRequested && _isCurrent();

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        Loaded -= OnLoaded;
        await LoadAsync(refresh: true, page: 0);
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e) => await LoadAsync(refresh: true, page: 0);
    private async void Previous_Click(object sender, RoutedEventArgs e) => await LoadAsync(refresh: false, page: _page - 1);
    private async void Next_Click(object sender, RoutedEventArgs e) => await LoadAsync(refresh: false, page: _page + 1);

    private async Task LoadAsync(bool refresh, int page)
    {
        if (_busy || !IsCurrent) return;
        _busy = true;
        RefreshButton.IsEnabled = PreviousButton.IsEnabled = NextButton.IsEnabled = false;
        Progress.Visibility = Visibility.Visible;
        Progress.IsIndeterminate = true;
        StatusText.Text = "正在读取本机缓存…";
        Tiles.Children.Clear();
        Tiles.Visibility = Visibility.Collapsed;
        try
        {
            if (refresh || _gallery is null)
                _gallery = await _service.ListCacheImagesAsync(_account, _token);
            if (!IsCurrent) return;
            var gallery = _gallery;
            int pages = Math.Max(1, (gallery.Images.Count + PageSize - 1) / PageSize);
            _page = Math.Clamp(page, 0, pages - 1);
            int index = 0, missing = 0;
            foreach (var item in gallery.Images.Skip(_page * PageSize).Take(PageSize))
            {
                try
                {
                    var bytes = await _service.ReadCacheImageAsync(_account, item, _token);
                    if (!IsCurrent) return;
                    var bitmap = await DecodeAsync(bytes, _token);
                    if (!IsCurrent) return;
                    AddTile(item, bitmap, index++);
                }
                catch (Exception)
                {
                    if (!IsCurrent) return;
                    missing++;
                }
            }
            _summary = gallery.Images.Count == 0
                ? "还没有可浏览的缩略图。浏览文件时成功载入的图片缩略图会出现在这里。"
                : gallery.Truncated
                    ? $"最近 {gallery.Images.Count} 张 · 第 {_page + 1} / {pages} 页"
                    : $"{gallery.Images.Count} 张缩略图 · 第 {_page + 1} / {pages} 页";
            if (missing > 0) _summary += $"。本页 {missing} 张已清理或无法读取，请刷新。";
            StatusText.Text = _summary;
            Tiles.Visibility = index > 0 ? Visibility.Visible : Visibility.Collapsed;
            Pager.Visibility = pages > 1 ? Visibility.Visible : Visibility.Collapsed;
            PreviousButton.IsEnabled = _page > 0;
            NextButton.IsEnabled = _page + 1 < pages;
            RefreshButton.Content = "刷新";
        }
        catch (Exception)
        {
            if (!IsCurrent) return;
            _gallery = null;
            Pager.Visibility = Visibility.Collapsed;
            StatusText.Text = "本机缓存暂不可读取，请检查缓存目录后重试。";
            RefreshButton.Content = "重试";
        }
        finally
        {
            _busy = false;
            if (IsCurrent)
            {
                Progress.IsIndeterminate = false;
                Progress.Visibility = Visibility.Collapsed;
                RefreshButton.IsEnabled = true;
                foreach (var tile in Tiles.Children.OfType<Button>()) tile.IsEnabled = true;
            }
        }
    }

    private static async Task<BitmapImage> DecodeAsync(byte[] bytes, CancellationToken token)
    {
        using var memory = new MemoryStream(bytes, writable: false);
        using var stream = memory.AsRandomAccessStream();
        var decoder = await BitmapDecoder.CreateAsync(stream).AsTask(token);
        if (decoder.PixelWidth is 0 or > 1024 || decoder.PixelHeight is 0 or > 1024 ||
            decoder.DecoderInformation.CodecId != BitmapDecoder.JpegDecoderId &&
            decoder.DecoderInformation.CodecId != BitmapDecoder.PngDecoderId)
            throw new InvalidDataException("Unexpected cache thumbnail.");
        stream.Seek(0);
        var bitmap = new BitmapImage();
        await bitmap.SetSourceAsync(stream).AsTask(token);
        return bitmap;
    }

    private void AddTile(LocalCacheImage item, BitmapImage bitmap, int index)
    {
        int ordinal = _page * PageSize + index + 1;
        var caption = $"缓存缩略图 {ordinal}，{item.LastUsed.ToLocalTime():g}，{DownloadPresentationPolicy.FormatBytes(item.Bytes)}";
        var button = new Button
        {
            Style = (Style)Application.Current.Resources["FwSecondaryButtonStyle"],
            HorizontalAlignment = HorizontalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
            Height = 84,
            IsEnabled = false,
            Padding = new Thickness(4),
            Content = new Image { Source = bitmap, Stretch = Stretch.Uniform, Height = 72 },
        };
        AutomationProperties.SetName(button, caption);
        AutomationProperties.SetAutomationId(button, $"CacheImage{index + 1}");
        ToolTipService.SetToolTip(button, caption);
        button.Click += (_, _) =>
        {
            if (!IsCurrent) return;
            _viewed = button;
            PreviewImage.Source = bitmap;
            PreviewSurface.Visibility = BackButton.Visibility = Visibility.Visible;
            Tiles.Visibility = GalleryActions.Visibility = Visibility.Collapsed;
            StatusText.Text = caption;
            BackButton.Focus(FocusState.Programmatic);
        };
        Grid.SetRow(button, index / 3);
        Grid.SetColumn(button, index % 3);
        Tiles.Children.Add(button);
    }

    private void Back_Click(object sender, RoutedEventArgs e)
    {
        PreviewImage.Source = null;
        PreviewSurface.Visibility = BackButton.Visibility = Visibility.Collapsed;
        Tiles.Visibility = GalleryActions.Visibility = Visibility.Visible;
        StatusText.Text = _summary;
        _viewed?.Focus(FocusState.Programmatic);
        _viewed = null;
    }

    public void Dispose()
    {
        _cancellation.Cancel();
        _cancellation.Dispose();
    }
}
