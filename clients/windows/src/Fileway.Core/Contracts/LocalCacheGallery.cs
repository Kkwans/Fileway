namespace Fileway.Core.Contracts;

public sealed record LocalCacheImage(string Id, long Bytes, DateTimeOffset LastUsed)
{
    public override string ToString() => "LocalCacheImage (cache identifier redacted)";
}

public sealed record LocalCacheGallery(IReadOnlyList<LocalCacheImage> Images, bool Truncated);

public interface ILocalCacheGalleryService
{
    Task<LocalCacheGallery> ListCacheImagesAsync(AccountKey account, CancellationToken cancellationToken);
    Task<byte[]> ReadCacheImageAsync(AccountKey account, LocalCacheImage image, CancellationToken cancellationToken);
}
