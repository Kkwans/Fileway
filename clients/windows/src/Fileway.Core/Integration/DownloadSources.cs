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
}
