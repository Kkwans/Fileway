using Fileway.Core.Contracts;

namespace Fileway.App.Views;

/// <summary>View-only text content already read from an archive entry; no server access and no edit expectation.</summary>
internal sealed class ArchiveTextContentService(TextContent content) : ITextContentService
{
    public Task<TextContent> OpenAsync(RemoteResourceRef resource, CancellationToken cancellationToken) =>
        Task.FromResult(content with { Resource = resource });

    public Task<TextEditExpectation> SaveAsync(RemoteResourceRef resource, TextEditExpectation expected,
        string newText, CancellationToken cancellationToken) =>
        throw new NotSupportedException("Archive previews are read-only.");
}

internal sealed class ArchiveImageContent(RemoteResourceRef resource, byte[] bytes) : IImageContent
{
    private readonly MemoryStream _stream = new(bytes, writable: false);

    public RemoteResourceRef Resource { get; } = resource;
    public Stream Content => _stream;
    public string? ContentType => null;
    public long Length => bytes.LongLength;

    public ValueTask DisposeAsync()
    {
        _stream.Dispose();
        return ValueTask.CompletedTask;
    }
}

internal sealed class ArchiveImageContentService(byte[] bytes) : IImageContentService
{
    public Task<IImageContent> OpenAsync(RemoteResourceRef resource, CancellationToken cancellationToken) =>
        Task.FromResult<IImageContent>(new ArchiveImageContent(resource, bytes));
}
