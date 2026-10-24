namespace Fileway.Core.Contracts;

/// <summary>
/// Decoded read-only text content of one remote file, bounded at open time. The encoding name is
/// observational (BOM/strict UTF-8/GB18030 fallback); it never claims the author's true intent.
/// </summary>
public sealed record TextContent(RemoteResourceRef Resource, long DeclaredLength, string Text, string EncodingName)
{
    public override string ToString() => "TextContent (private text redacted)";
    /// <summary>Conditional-write evidence; null means view-only (stale read or unavailable observation).</summary>
    public TextEditExpectation? Edit { get; init; }
}

/// <summary>
/// Conditional-write evidence for one text file: the exact server observation this view was
/// loaded from. Null disables editing until the file is reloaded.
/// </summary>
public sealed record TextEditExpectation(long Size, string ModifiedRaw)
{
    public override string ToString() => "TextEditExpectation (private observation redacted)";
}

/// <summary>
/// Read-only remote text viewing. Opening never starts a transfer or writes anything; the decoded
/// budget bounds memory and the caller presents the text as-is.
/// </summary>
public interface ITextContentService
{
    Task<TextContent> OpenAsync(RemoteResourceRef resource, CancellationToken cancellationToken);

    /// <summary>
    /// Conditional text save: the server stages the bytes and publishes only if its current
    /// observation still equals <paramref name="expected"/>. A changed source throws
    /// SourceChanged instead of overwriting; the result carries the fresh observation for
    /// further edits. The text is written as UTF-8 (BOM preserved only for BOM sources).
    /// </summary>
    Task<TextEditExpectation> SaveAsync(RemoteResourceRef resource, TextEditExpectation expected,
        string newText, CancellationToken cancellationToken);
}
