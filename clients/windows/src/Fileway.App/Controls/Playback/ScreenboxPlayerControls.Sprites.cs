using System.Runtime.InteropServices.WindowsRuntime;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Foundation;
using Windows.Graphics.Imaging;

namespace Fileway.App.Controls.Playback;

public sealed partial class ScreenboxPlayerControls
{
    private IVideoSpriteService? _sprites;
    private RemoteResourceRef? _spriteResource;
    private VideoSpriteGeometry? _spriteGeometry;
    private CancellationTokenSource? _spriteCancellation;
    private bool _spriteAttempted;
    private long _spriteEpoch;
    private double _previewSeconds, _previewX;

    internal void SetSpriteSource(IVideoSpriteService? sprites, RemoteResourceRef? resource)
    {
        _sprites = sprites; _spriteResource = resource;
        SeekSlider.PointerMoved += Sprite_PointerMoved;
        SeekSlider.PointerExited += Sprite_PointerExited;
        ControlsVisibilityChanged += (_, _) => { if (!ControlsVisible) SeekPreviewCard.Visibility = Visibility.Collapsed; };
    }
    private void Sprite_PointerMoved(object sender, PointerRoutedEventArgs args)
    {
        if (_disposed || !SeekSlider.IsEnabled || SeekSlider.ActualWidth <= 0) return;
        var x = Math.Clamp(args.GetCurrentPoint(SeekSlider).Position.X, 0, SeekSlider.ActualWidth);
        ShowSeekPreview(_seeking ? SeekSlider.Value : SeekSlider.Minimum + x / SeekSlider.ActualWidth * (SeekSlider.Maximum - SeekSlider.Minimum), x);
    }
    private void Sprite_PointerExited(object sender, PointerRoutedEventArgs args)
    { if (!_seeking) SeekPreviewCard.Visibility = Visibility.Collapsed; }
    private void ShowSeekPreview(double seconds, double? x = null)
    {
        if (_disposed || !double.IsFinite(seconds)) return;
        _previewSeconds = Math.Clamp(seconds, SeekSlider.Minimum, SeekSlider.Maximum);
        _previewX = x ?? (_previewSeconds - SeekSlider.Minimum) / Math.Max(1, SeekSlider.Maximum - SeekSlider.Minimum) * SeekSlider.ActualWidth;
        SeekPreviewTime.Text = Clock(TimeSpan.FromSeconds(_previewSeconds));
        SeekPreviewCard.Visibility = Visibility.Visible;
        PlaceSeekPreview(); RenderSpriteTile();
        if (!_spriteAttempted && _sprites is not null && _spriteResource is not null)
        { _spriteAttempted = true; _ = LoadSpriteAsync(); }
    }
    private void PlaceSeekPreview()
    {
        if (_disposed || XamlRoot is null) return;
        var origin = SeekSlider.TransformToVisual(Root).TransformPoint(new Point(0, 0));
        var width = (_spriteGeometry?.Width ?? 160) + 16d;
        var height = (_spriteGeometry?.Height ?? 90) + 48d;
        SeekPreviewCard.Margin = new Thickness(Math.Clamp(origin.X + _previewX - width / 2, 8, Math.Max(8, ActualWidth - width - 8)), Math.Max(8, origin.Y - height - 10), 0, 0);
    }
    private void RenderSpriteTile()
    {
        if (_spriteGeometry is not { } geometry) return;
        var (x, y) = geometry.TileAt(TimeSpan.FromSeconds(_previewSeconds));
        Canvas.SetLeft(SpriteSheetImage, -x); Canvas.SetTop(SpriteSheetImage, -y);
    }
    private async Task LoadSpriteAsync()
    {
        var epoch = _spriteEpoch;
        var generation = _snapshot.Generation;
        using var cancellation = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
        _spriteCancellation = cancellation;
        bool Current() => !_disposed && epoch == _spriteEpoch && _snapshot.Generation == generation && !cancellation.IsCancellationRequested;
        try
        {
            await Task.Delay(180, cancellation.Token);
            if (!Current()) return;
            if (SeekPreviewCard.Visibility != Visibility.Visible) { _spriteAttempted = false; return; }
            SpriteStatus.Text = "正在生成预览…";
            await using var asset = await _sprites!.OpenVideoSpriteAsync(_spriteResource!, cancellation.Token);
            if (!Current()) return;
            if (asset is null || !asset.Geometry.IsValid) { SpriteStatus.Text = "预览暂不可用"; return; }
            using var random = asset.Sheet.Content.AsRandomAccessStream();
            var decoder = await BitmapDecoder.CreateAsync(random).AsTask(cancellation.Token);
            var geometry = asset.Geometry;
            if (decoder.PixelWidth != geometry.SheetWidth || decoder.PixelHeight != geometry.SheetHeight ||
                decoder.DecoderInformation.CodecId != BitmapDecoder.JpegDecoderId && decoder.DecoderInformation.CodecId != BitmapDecoder.PngDecoderId)
                throw new InvalidDataException("Preview geometry mismatch.");
            using var bitmap = await decoder.GetSoftwareBitmapAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied).AsTask(cancellation.Token);
            if (!Current()) return;
            var source = new SoftwareBitmapSource(); await source.SetBitmapAsync(bitmap);
            if (!Current()) return;
            _spriteGeometry = geometry;
            SpriteViewport.Width = geometry.Width; SpriteViewport.Height = geometry.Height;
            SpriteViewport.Clip = new RectangleGeometry { Rect = new Rect(0, 0, geometry.Width, geometry.Height) };
            SpriteSheetImage.Width = geometry.SheetWidth; SpriteSheetImage.Height = geometry.SheetHeight;
            SpriteSheetImage.Source = source; SpriteSheetImage.Visibility = Visibility.Visible;
            SpriteStatus.Text = ""; RenderSpriteTile(); PlaceSeekPreview();
        }
        catch (OperationCanceledException) { }
        catch (Exception) { if (Current()) SpriteStatus.Text = "预览暂不可用"; }
        finally { if (ReferenceEquals(_spriteCancellation, cancellation)) _spriteCancellation = null; }
    }
    private void ResetSpritePreview()
    {
        ++_spriteEpoch; _spriteAttempted = false; _spriteGeometry = null;
        if (_spriteCancellation is { } cancellation) _ = CancelSpriteAsync(cancellation);
        SeekPreviewCard.Visibility = Visibility.Collapsed; SpriteSheetImage.Source = null;
        SpriteSheetImage.Visibility = Visibility.Collapsed;
    }
    private static async Task CancelSpriteAsync(CancellationTokenSource cancellation)
    { try { await cancellation.CancelAsync(); } catch (Exception) { /* Retirement must not interrupt the player. */ } }
}
