using System.Text.Json;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices : ILocalCacheGalleryService
{
    private const int MaximumGalleryBytes = 2 * 1024 * 1024;

    public async Task<LocalCacheGallery> ListCacheImagesAsync(AccountKey account, CancellationToken cancellationToken)
    {
        var state = GetState(account);
        RequireDownload(state);
        var data = await CallAsync("cache_gallery_list", new { cacheOwner = CacheOwnership.Owner(account) },
            null, cancellationToken, timeout: TimeSpan.FromSeconds(8)).ConfigureAwait(false);
        RequireDownload(state);
        var items = data.GetProperty("items");
        if (items.ValueKind != JsonValueKind.Array || items.GetArrayLength() > 120) throw InvalidResponse();
        var images = new List<LocalCacheImage>(items.GetArrayLength());
        foreach (var item in items.EnumerateArray())
        {
            var id = item.GetProperty("id").GetString();
            var bytes = item.GetProperty("bytes").GetInt64();
            if (id is null || id.Length > 180 || bytes is <= 0 or > MaximumGalleryBytes) throw InvalidResponse();
            images.Add(new(id, bytes, item.GetProperty("used").GetDateTimeOffset()));
        }
        return new(images, data.GetProperty("truncated").GetBoolean());
    }

    public async Task<byte[]> ReadCacheImageAsync(AccountKey account, LocalCacheImage image, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(image);
        var state = GetState(account);
        RequireDownload(state);
        var data = await CallAsync("cache_gallery_read", new { cacheOwner = CacheOwnership.Owner(account), cacheItem = image.Id },
            null, cancellationToken, timeout: TimeSpan.FromSeconds(5)).ConfigureAwait(false);
        RequireDownload(state);
        if (data.ValueKind != JsonValueKind.String || !data.TryGetBytesFromBase64(out var bytes) ||
            bytes.Length != image.Bytes || bytes.Length > MaximumGalleryBytes)
            throw InvalidResponse();
        return bytes;
    }
}
