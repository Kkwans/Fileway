using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;

namespace Fileway.App.Views;

/// <summary>A small color sample from the decoded image, separate from the sharp photo.</summary>
internal sealed record ImageAmbientSample(int Width, int Height, byte[] Pixels)
{
    public async Task<BitmapImage> CreateBackdropAsync(bool dark)
    {
        var pixels = BuildBackdrop(dark);
        using var stream = new InMemoryRandomAccessStream();
        var encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.PngEncoderId, stream);
        encoder.SetPixelData(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Ignore, (uint)Width, (uint)Height, 96, 96, pixels);
        await encoder.FlushAsync();
        stream.Seek(0);
        var image = new BitmapImage();
        await image.SetSourceAsync(stream);
        return image;
    }

    private byte[] BuildBackdrop(bool dark)
    {
        var result = new byte[Pixels.Length];
        var mean = AverageColor(0, 0, Width - 1, Height - 1);
        const int radius = 6;
        for (var y = 0; y < Height; y++)
        for (var x = 0; x < Width; x++)
        {
            var local = AverageColor(Math.Max(0, x - radius), Math.Max(0, y - radius),
                Math.Min(Width - 1, x + radius), Math.Min(Height - 1, y + radius));
            var nx = 2d * x / (Width - 1) - 1;
            var ny = 2d * y / (Height - 1) - 1;
            var edge = (nx * nx + ny * ny) / 2;
            var offset = (y * Width + x) * 4;
            result[offset] = Grade(local.Blue, mean.Blue, dark ? 25 : 241, edge, dark);
            result[offset + 1] = Grade(local.Green, mean.Green, dark ? 17 : 235, edge, dark);
            result[offset + 2] = Grade(local.Red, mean.Red, dark ? 12 : 230, edge, dark);
            result[offset + 3] = byte.MaxValue;
        }
        return result;
    }

    private static byte Grade(double local, double mean, int basis, double edge, bool dark)
    {
        var tone = local * 0.55 + mean * 0.45;
        var value = dark ? basis + tone * (0.35 - edge * 0.10) : basis * 0.72 + tone * 0.28 - edge * 4;
        return (byte)Math.Clamp(Math.Round(value), 0, 255);
    }

    private (double Blue, double Green, double Red) AverageColor(int left, int top, int right, int bottom)
    {
        double weight = 0, blue = 0, green = 0, red = 0;
        for (var y = top; y <= bottom; y++)
        for (var x = left; x <= right; x++)
        {
            var offset = (y * Width + x) * 4;
            var b = Pixels[offset];
            var g = Pixels[offset + 1];
            var r = Pixels[offset + 2];
            var brightest = Math.Max(r, Math.Max(g, b));
            var chroma = brightest - Math.Min(r, Math.Min(g, b));
            // Favor the visible colors over transparent pixels and black letterboxing.
            var contribution = Pixels[offset + 3] / 255d * (0.06 + (brightest + 3d * chroma) / 255);
            weight += contribution;
            blue += b * contribution;
            green += g * contribution;
            red += r * contribution;
        }
        return weight > 0 ? (blue / weight, green / weight, red / weight) : (0, 0, 0);
    }
}
