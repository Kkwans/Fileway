using Fileway.Core.Contracts;

namespace Fileway.Core.Integration;

/// <summary>Pinned source and length. SourceVersion is opaque; null permits only a fresh, unconditioned read.</summary>
public sealed record DownloadLeaseRequest(RemoteResourceRef Source, long Length)
{
    public override string ToString() => "DownloadLeaseRequest (private source redacted)";
}

/// <summary>
/// Uncached, read-only capability. The address stays in Infrastructure; a non-null version and actual
/// conditional support are required for resume. The consumer must still validate Range and version headers.
/// </summary>
public interface IDownloadLease : IResourceLease
{
    long Length { get; }
    string? SourceVersion { get; }
    bool SupportsResume { get; }
}

public interface IDownloadSourceProvider
{
    /// <summary>Authenticated, permission-checked file metadata; opt-in version lookup avoids listing-wide native probes.</summary>
    Task<RemoteFile> PrepareDownloadAsync(RemoteResourceRef resource, CancellationToken cancellationToken);

    /// <summary>Creates only a local read capability. It never writes a local target or starts a download.</summary>
    Task<IDownloadLease> AcquireDownloadAsync(DownloadLeaseRequest request, CancellationToken cancellationToken);

    /// <summary>SHA-256 for the pinned source, conditioned on SourceVersion when available; no task/journal mutation.</summary>
    Task<FileChecksumResult> CalculateDownloadChecksumAsync(DownloadLeaseRequest request, CancellationToken cancellationToken);

    /// <summary>
    /// Acquires a one-shot ZIP export lease for explicitly selected entries under one directory.
    /// The consumer must still validate the response shape; no durable checkpoint authority exists.
    /// </summary>
    Task<IDownloadLease> AcquireArchiveLeaseAsync(ArchiveDownloadLeaseRequest request,
        CancellationToken cancellationToken) => Task.FromException<IDownloadLease>(Missing());

    /// <summary>
    /// Bounded breadth-first enumeration of one directory through the authenticated listing path.
    /// It reports per-directory failures and truncation instead of guessing; it never starts a transfer.
    /// </summary>
    Task<DownloadTreeEnumeration> EnumerateDownloadTreeAsync(RemoteResourceRef directory, int maximumEntries,
        CancellationToken cancellationToken) => Task.FromException<DownloadTreeEnumeration>(Missing());

    private static FilewayException Missing() => new(new(ErrorCode.CapabilityMissing,
        "当前仅支持全新单文件下载，此版本能力尚不受支持。"));
}

/// <summary>
/// A server-side archive export streams one synthetic file; its length is unknown upfront and it
/// is never resumed or checksummed against a pinned source observation.
/// </summary>
public sealed record ArchiveDownloadLeaseRequest(RemoteResourceRef Directory, IReadOnlyList<string> ItemWirePaths)
{
    public override string ToString() => "ArchiveDownloadLeaseRequest (private locations redacted)";
}

/// <summary>
/// Optional playback-only source capability. It carries an authenticated opaque source version
/// for conditional Range reads; it does not grant transfer resume or durable checkpoint authority.
/// </summary>
public sealed record ConditionalDownloadLeaseRequest(RemoteResourceRef Source, long Length, string SourceVersion)
{
    public override string ToString() => "ConditionalDownloadLeaseRequest (private source redacted)";
}

/// <summary>
/// Additive conditional-read surface. Existing download providers remain fresh-only when they do not
/// implement this interface, so old servers and test doubles keep their original contract.
/// </summary>
public interface IConditionalDownloadSourceProvider
{
    bool CanUseConditionalDownload(RemoteResourceRef resource);
    Task<RemoteFile> PrepareConditionalDownloadAsync(RemoteResourceRef resource, CancellationToken cancellationToken);
    Task<IDownloadLease> AcquireConditionalDownloadAsync(ConditionalDownloadLeaseRequest request,
        CancellationToken cancellationToken);
    /// <summary>
    /// SHA-256 bound to the pinned source version: every observation in the exchange must report the
    /// same version, so a changed source is rejected instead of hashing mismatched content.
    /// </summary>
    Task<FileChecksumResult> CalculateConditionalDownloadChecksumAsync(ConditionalDownloadLeaseRequest request,
        CancellationToken cancellationToken) => Task.FromException<FileChecksumResult>(Missing());

    private static FilewayException Missing() => new(new(ErrorCode.CapabilityMissing,
        "当前仅支持全新单文件下载，此版本能力尚不受支持。"));
}
