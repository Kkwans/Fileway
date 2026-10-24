namespace Fileway.Core.Contracts;

/// <summary>One entry of an opened remote ZIP archive; index is stable for the session lifetime.</summary>
public sealed record ArchiveEntryInfo(int Index, string FullName, long Size, long CompressedSize, bool IsDirectory);

/// <summary>Per-entry extraction outcome; Skipped means an existing target was left untouched.</summary>
public enum ArchiveExtractOutcome { Extracted, Skipped, Failed }

public sealed record ArchiveExtractItem(int Index, string FullName, ArchiveExtractOutcome Outcome, string? Error = null)
{
    public override string ToString() => "ArchiveExtractItem (private paths redacted)";
}

public sealed record ArchiveExtractResult(int Extracted, int Skipped, int Failed, IReadOnlyList<ArchiveExtractItem> Items)
{
    public override string ToString() => "ArchiveExtractResult (private paths redacted)";
}

/// <summary>
/// One opened remote ZIP archive session: read-only listing and selective extraction. The archive
/// is bounded at open time; extraction targets are chosen by the caller and never overwrite.
/// </summary>
public interface IArchiveBrowseSession : IAsyncDisposable
{
    RemoteResourceRef Archive { get; }
    IReadOnlyList<ArchiveEntryInfo> Entries { get; }
    long TotalUncompressedBytes { get; }

    /// <summary>
    /// Extracts the selected entry indexes into targetDirectory (created if missing). Zip-slip
    /// paths, oversized entries and existing targets are reported per entry, never overwritten.
    /// </summary>
    Task<ArchiveExtractResult> ExtractAsync(IReadOnlyList<int> indexes, string targetDirectory,
        CancellationToken cancellationToken);
}

/// <summary>
/// Read-only browsing of remote ZIP archives. Archives beyond the open budget are refused with an
/// explicit error rather than partially listed; tar families stay with the plain download path.
/// </summary>
public interface IArchiveBrowseService
{
    Task<IArchiveBrowseSession> OpenAsync(RemoteResourceRef archive, CancellationToken cancellationToken);
}
