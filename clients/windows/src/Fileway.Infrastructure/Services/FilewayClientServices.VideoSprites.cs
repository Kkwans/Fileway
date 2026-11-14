using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices : IVideoSpriteService
{
    public async Task<VideoSpriteAsset?> OpenVideoSpriteAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
    {
        var state = GetResourceState(resource);
        using var budget = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        budget.CancelAfter(TimeSpan.FromMinutes(3));
        var token = budget.Token;
        var original = await GetAsync(resource, token).ConfigureAwait(false);
        if (original.IsDirectory || resource.SourceVersion is { } expected && original.Resource.SourceVersion != expected)
            throw Safe(ErrorCode.SourceChanged, "视频来源已变更，预览不可用。");
        VideoSpriteGeometry? geometry = null;
        while (geometry is null)
        {
            using var document = ParseBody(await RequestAsync(state, MediaEndpoint(resource, "sprite"), token, requireDownload: true).ConfigureAwait(false));
            var value = document.RootElement;
            var status = RequiredText(value.GetProperty("state"));
            if (status == "preparing") { await Task.Delay(TimeSpan.FromSeconds(2), token).ConfigureAwait(false); continue; }
            if (status != "ready") return null;
            geometry = new(value.GetProperty("number").GetInt32(), value.GetProperty("column").GetInt32(),
                value.GetProperty("width").GetInt32(), value.GetProperty("height").GetInt32(), value.GetProperty("interval").GetDouble());
            if (!geometry.IsValid) throw InvalidResponse();
        }
        // Ignore the returned URL. Broker the fixed same-service asset, as Android does.
        var lease = (ResourceLease)await AcquireLeaseAsync(state, resource, new { endpoint = MediaEndpoint(resource, "sprite.jpg") }, token, "asset").ConfigureAwait(false);
        var image = await ReadImageContentAsync(resource, lease, MaximumThumbnailBytes, thumbnail: true, token).ConfigureAwait(false);
        try
        {
            var current = await GetAsync(resource, token).ConfigureAwait(false);
            EnsureActive(state);
            if (current.Size != original.Size || current.Modified != original.Modified || current.Resource.SourceVersion != original.Resource.SourceVersion)
                throw Safe(ErrorCode.SourceChanged, "视频来源已变更，预览不可用。");
            return new(geometry, image);
        }
        catch { await image.DisposeAsync().ConfigureAwait(false); throw; }
    }
}
