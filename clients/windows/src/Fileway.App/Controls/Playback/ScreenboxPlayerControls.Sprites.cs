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
    private bool _seekPointerOver;
    private long _spriteEpoch;
    private long _spriteRetryAfter;
    private double _previewSeconds, _previewX;

    internal void SetSpriteSource(IVideoSpriteService? sprites, RemoteResourceRef? resource)
    {
        _sprites = sprites; _spriteResource = resource;
        SeekSlider.AddHandler(PointerEnteredEvent, new PointerEventHandler(Sprite_PointerEntered), true);
        SeekSlider.AddHandler(PointerMovedEvent, new PointerEventHandler(Sprite_PointerMoved), true);
        SeekSlider.AddHandler(PointerExitedEvent, new PointerEventHandler(Sprite_PointerExited), true);
        ControlsVisibilityChanged += (_, _) => { if (!ControlsVisible) { _seekPointerOver = false; HideSeekPreview(); } };
    }
    private void Sprite_PointerEntered(object sender, PointerRoutedEventArgs args)
    {
        _seekPointerOver = true;
        Sprite_PointerMoved(sender, args);
        NotifyActivity();
    }
    private void Sprite_PointerMoved(object sender, PointerRoutedEventArgs args)
    {
        if (_disposed || _audioOnly || !SeekSlider.IsEnabled || SeekSlider.ActualWidth <= 0) return;
        var x = Math.Clamp(args.GetCurrentPoint(SeekSlider).Position.X, 0, SeekSlider.ActualWidth);
        ShowSeekPreview(_seeking ? SeekSlider.Value : SeekSlider.Minimum + x / SeekSlider.ActualWidth * (SeekSlider.Maximum - SeekSlider.Minimum), x);
    }
    private void Sprite_PointerExited(object sender, PointerRoutedEventArgs args)
    {
        _seekPointerOver = false;
        if (!_seeking) HideSeekPreview();
        NotifyActivity();
    }
    private void ShowSeekPreview(double seconds, double? x = null)
    {
        if (_disposed || _audioOnly || !double.IsFinite(seconds)) return;
        _previewSeconds = Math.Clamp(seconds, SeekSlider.Minimum, SeekSlider.Maximum);
        _previewX = x ?? (_previewSeconds - SeekSlider.Minimum) / Math.Max(1, SeekSlider.Maximum - SeekSlider.Minimum) * SeekSlider.ActualWidth;
        SeekPreviewTime.Text = Clock(TimeSpan.FromSeconds(_previewSeconds));
        SeekPreviewCard.Visibility = Visibility.Visible;
        PlaceSeekPreview(); RenderSpriteTile();
        if (!_spriteAttempted && Environment.TickCount64 >= _spriteRetryAfter && _sprites is not null && _spriteResource is not null)
        { _spriteAttempted = true; _ = LoadSpriteAsync(); }
    }
    private void PlaceSeekPreview()
    {
        if (_disposed || XamlRoot is null) return;
        var origin = SeekSlider.TransformToVisual(Root).TransformPoint(new Point(0, 0));
        var width = _spriteGeometry is { } geometry ? geometry.Width + 18d : 72d;
        var height = _spriteGeometry is { } image ? image.Height + 44d : 38d;
        SeekPreviewCard.Margin = new Thickness(Math.Clamp(origin.X + _previewX - width / 2, 8, Math.Max(8, ActualWidth - width - 8)), Math.Max(8, origin.Y - height - 10), 0, 0);
        NotifyMaterialBoundsChanged();
    }
    internal Rect? GetSeekPreviewBounds()
    {
        if (_disposed || SeekPreviewCard.Visibility != Visibility.Visible) return null;
        var width = _spriteGeometry is { } geometry ? geometry.Width + 18d : 72d;
        var height = _spriteGeometry is { } image ? image.Height + 44d : 38d;
        return new Rect(SeekPreviewCard.Margin.Left, SeekPreviewCard.Margin.Top,
            Math.Max(width, SeekPreviewCard.ActualWidth), Math.Max(height, SeekPreviewCard.ActualHeight));
    }
    private void HideSeekPreview()
    {
        if (SeekPreviewCard.Visibility != Visibility.Visible) return;
        SeekPreviewCard.Visibility = Visibility.Collapsed;
        NotifyMaterialBoundsChanged();
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
            await using var asset = await _sprites!.OpenVideoSpriteAsync(_spriteResource!, cancellation.Token);
            if (!Current()) return;
            if (asset is null || !asset.Geometry.IsValid) { RetrySpriteLater(); return; }
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
            SpriteViewport.Visibility = Visibility.Visible;
            RenderSpriteTile(); PlaceSeekPreview();
        }
        catch (OperationCanceledException) { }
        catch (Exception) { if (Current()) RetrySpriteLater(); }
        finally { if (ReferenceEquals(_spriteCancellation, cancellation)) _spriteCancellation = null; }
    }
    private void ResetSpritePreview()
    {
        ++_spriteEpoch; _spriteAttempted = false; _spriteRetryAfter = 0; _spriteGeometry = null;
        if (_spriteCancellation is { } cancellation) _ = CancelSpriteAsync(cancellation);
        HideSeekPreview(); SpriteSheetImage.Source = null;
        SpriteSheetImage.Visibility = Visibility.Collapsed;
        SpriteViewport.Visibility = Visibility.Collapsed;
    }
    private void RetrySpriteLater()
    {
        _spriteAttempted = false;
        _spriteRetryAfter = Environment.TickCount64 + 5000;
    }
    private static async Task CancelSpriteAsync(CancellationTokenSource cancellation)
    { try { await cancellation.CancelAsync(); } catch (Exception) { /* Retirement must not interrupt the player. */ } }
}
