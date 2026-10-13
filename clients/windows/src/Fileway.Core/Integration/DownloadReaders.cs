using Fileway.Core.Contracts;

namespace Fileway.Core.Integration;

/// <summary>
/// Read-only access bound to one already-owned download object and execution generation.
/// An application reader may obtain missing bytes through authenticated, observation-checked ranges;
/// those bytes do not advance the owned file's prefix. No path, native handle, or network capability
/// is exposed. Disposal releases this reader only.
/// </summary>
public interface IDownloadReadLease : IAsyncDisposable
{
    TransferRunToken Run { get; }
    long TotalBytes { get; }
    /// <summary>
    /// Current owner-verified completed writes, not a flush/publication/offline-playability guarantee.
    /// Revocation, disposal or source/authorization failure throws a safe exception rather than returning
    /// a historical prefix. Successful publication may remain readable through the same retained object.
    /// </summary>
    long ReadablePrefixBytes { get; }
    /// <summary>
    /// Reads at an absolute offset. Missing bytes may use an authorized remote range or wait without
    /// holding writer/I/O locks when bytes may still arrive. A range read does not establish resume
    /// authority, persisted progress, or an offline playable time range.
    /// Zero denotes declared EOF or an empty buffer, never temporary prefix exhaustion.
    /// A canceled call does not cancel the writer; revoked or irrecoverably ended sources must fail promptly.
    /// </summary>
    ValueTask<int> ReadAsync(long offset, Memory<byte> buffer, CancellationToken cancellationToken);
}

/// <summary>Optional capability of an owned destination; readers use its same retained native object.</summary>
public interface IReadableDownloadDestination
{
    Task<IDownloadReadLease> AcquireReadLeaseAsync(TransferRunToken run, long totalBytes,
        CancellationToken cancellationToken);
    /// <summary>Revokes admission, wakes blocked reads, and drains actual reader I/O before owned cleanup.</summary>
    Task RevokeReadersAsync(CancellationToken cancellationToken);
}

/// <summary>
/// Acquires a reader for an already active task, without starting or replaying it.
/// The application boundary separately authorizes the exact account/session/activation context.
/// </summary>
public interface IFileDownloadReadLeaseProvider
{
    /// <summary>May await accepted owned-file readiness, but never awaits future media bytes before returning.</summary>
    Task<IDownloadReadLease> AcquireReadLeaseAsync(TransferTaskRef task, CancellationToken cancellationToken);
}
