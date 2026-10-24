namespace Fileway.Core.Contracts;

/// <summary>
/// Decoded read-only text content of one remote file, bounded at open time. The encoding name is
/// observational (BOM/strict UTF-8/GB18030 fallback); it never claims the author's true intent.
/// </summary>
public sealed record TextContent(RemoteResourceRef Resource, long DeclaredLength, string Text, string EncodingName)
{
    public override string ToString() => "TextContent (private text redacted)";
}

/// <summary>
/// Read-only remote text viewing. Opening never starts a transfer or writes anything; the decoded
/// budget bounds memory and the caller presents the text as-is.
/// </summary>
public interface ITextContentService
{
    Task<TextContent> OpenAsync(RemoteResourceRef resource, CancellationToken cancellationToken);
}
