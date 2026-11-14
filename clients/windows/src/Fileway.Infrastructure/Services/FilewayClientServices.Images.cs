using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices : IThumbnailContentService
{
    private const long MaximumThumbnailBytes = 8L * 1024 * 1024;

    public async Task<IImageContent> OpenThumbnailAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
    {
        var lease = (ResourceLease)await AcquireContentLeaseAsync(resource, thumbnail: true, cancellationToken).ConfigureAwait(false);
        return await ReadImageContentAsync(resource, lease, MaximumThumbnailBytes, thumbnail: true, cancellationToken).ConfigureAwait(false);
    }

    private static FilewayException ImageTooLarge(bool thumbnail) => Safe(ErrorCode.ResponseTooLarge,
        thumbnail ? "缩略图超过 8 MiB 大小限制。" : "图片超过首预览的 64 MiB 大小限制。");
}
