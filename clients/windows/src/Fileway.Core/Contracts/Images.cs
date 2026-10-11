namespace Fileway.Core.Contracts;

public interface IImageContent : IAsyncDisposable
{
    RemoteResourceRef Resource { get; }
    Stream Content { get; }
    string? ContentType { get; }
    long Length { get; }
}

public interface IImageContentService
{
    Task<IImageContent> OpenAsync(RemoteResourceRef resource, CancellationToken cancellationToken);
}

/// <summary>Bounded server-generated contain previews; unsupported media never falls back to the full original.</summary>
public interface IThumbnailContentService
{
    Task<IImageContent> OpenThumbnailAsync(RemoteResourceRef resource, CancellationToken cancellationToken);
}
