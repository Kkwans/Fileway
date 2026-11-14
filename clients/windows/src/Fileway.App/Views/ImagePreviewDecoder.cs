using System.Security.Cryptography;
using Fileway.Core.Contracts;
using Windows.Graphics.Imaging;

namespace Fileway.App.Views;

internal sealed class ImagePreviewFrame(SoftwareBitmap bitmap, long encodedLength, string? signature,
    ImageAmbientSample? ambient) : IDisposable
{
    public SoftwareBitmap Bitmap { get; } = bitmap;
    public long EncodedLength { get; } = encodedLength;
    public string? Signature { get; } = signature;
    public ImageAmbientSample? Ambient { get; } = ambient;

    public void Dispose() => Bitmap.Dispose();
}

internal static class ImagePreviewDecoder
{
    private const long EncodedByteBudget = 96L * 1024 * 1024;
    private const long PrefetchByteBudget = 32L * 1024 * 1024;
    private const double PixelBudget = 16_000_000;
    private const double LongEdgeBudget = 8192;

    public static async Task<ImagePreviewFrame?> DecodeAsync(IImageContentService service, RemoteResourceRef resource,
        ImagePreviewFrame? cached, bool prefetch, CancellationToken token)
    {
        SoftwareBitmap? bitmap = null;
        try
        {
            ImagePreviewFrame result;
            await using (var content = await service.OpenAsync(resource, token).ConfigureAwait(false))
            {
                token.ThrowIfCancellationRequested();
                var length = content.Length;
                CheckEncodedLength(length);
                if (prefetch && length > PrefetchByteBudget) return null;

                var input = content.Content;
                // The built-in services hand over a fully buffered stream for this lease.
                using var copy = input.GetType() == typeof(MemoryStream) && input.CanSeek && input.Position == 0
                    ? null
                    : await CopyEncodedAsync(input, token).ConfigureAwait(false);
                var encoded = copy ?? input;
                length = encoded.Length;
                CheckEncodedLength(length);
                if (prefetch && length > PrefetchByteBudget) return null;

                string? signature = null;
                if (prefetch || cached is not null)
                {
                    signature = Convert.ToHexString(await SHA256.HashDataAsync(encoded, token).ConfigureAwait(false));
                    encoded.Position = 0;
                }

                token.ThrowIfCancellationRequested();
                if (cached is not null && signature is not null && cached.Signature == signature && cached.EncodedLength == length)
                {
                    result = cached;
                }
                else
                {
                    using var random = encoded.AsRandomAccessStream();
                    var decoder = await BitmapDecoder.CreateAsync(random).AsTask(token).ConfigureAwait(false);
                    var scale = Math.Min(1, Math.Min(
                        Math.Sqrt(PixelBudget / ((double)decoder.PixelWidth * decoder.PixelHeight)),
                        LongEdgeBudget / Math.Max(decoder.PixelWidth, decoder.PixelHeight)));
                    var transform = new BitmapTransform
                    {
                        ScaledWidth = Math.Max(1, (uint)(decoder.PixelWidth * scale)),
                        ScaledHeight = Math.Max(1, (uint)(decoder.PixelHeight * scale)),
                        InterpolationMode = BitmapInterpolationMode.Fant,
                    };
                    token.ThrowIfCancellationRequested();
                    // Await the native result so cancellation cannot hide a bitmap that needs disposal.
                    bitmap = await decoder.GetSoftwareBitmapAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied,
                        transform, ExifOrientationMode.RespectExifOrientation, ColorManagementMode.ColorManageToSRgb)
                        .AsTask().ConfigureAwait(false);
                    var ambient = await ReadAmbientAsync(decoder, token).ConfigureAwait(false);
                    result = new ImagePreviewFrame(bitmap, length, signature, ambient);
                }
            }

            token.ThrowIfCancellationRequested();
            bitmap = null;
            return result;
        }
        finally
        {
            bitmap?.Dispose();
        }
    }

    private static async Task<ImageAmbientSample?> ReadAmbientAsync(BitmapDecoder decoder, CancellationToken token)
    {
        const int width = 48;
        const int height = 32;
        try
        {
            token.ThrowIfCancellationRequested();
            var transform = new BitmapTransform
            {
                ScaledWidth = width,
                ScaledHeight = height,
                InterpolationMode = BitmapInterpolationMode.Fant,
            };
            var data = await decoder.GetPixelDataAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Straight, transform,
                ExifOrientationMode.IgnoreExifOrientation, ColorManagementMode.ColorManageToSRgb).AsTask().ConfigureAwait(false);
            var pixels = data.DetachPixelData();
            return pixels.Length == width * height * 4 ? new ImageAmbientSample(width, height, pixels) : null;
        }
        catch (Exception error) when (error is System.Runtime.InteropServices.COMException or ArgumentException)
        {
            // The sharp photo remains usable when this codec cannot produce a small color sample.
            return null;
        }
    }

    private static async Task<MemoryStream> CopyEncodedAsync(Stream input, CancellationToken token)
    {
        var copy = new MemoryStream();
        try
        {
            var buffer = new byte[64 * 1024];
            int read;
            while ((read = await input.ReadAsync(buffer.AsMemory(), token).ConfigureAwait(false)) != 0)
            {
                CheckEncodedLength(copy.Length + read);
                copy.Write(buffer, 0, read);
            }
            copy.Position = 0;
            return copy;
        }
        catch
        {
            copy.Dispose();
            throw;
        }
    }

    private static void CheckEncodedLength(long length)
    {
        if (length > EncodedByteBudget)
            throw new FilewayException(new ErrorInfo(ErrorCode.ResponseTooLarge, "图片文件超过本次预览的 96 MB 上限。"));
    }
}
