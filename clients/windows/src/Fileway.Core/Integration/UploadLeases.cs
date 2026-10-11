using Fileway.Core.Contracts;

namespace Fileway.Core.Integration;

/// <summary>Optional existing server batch tracking, never a filename or authentication header.</summary>
public sealed record UploadBatchMetadata(Guid Id, string Name, long ItemCount, long TotalBytes, bool IsFolder = false)
{
    public override string ToString() => "UploadBatchMetadata (private name redacted)";
}

/// <summary>Immutable upload authority. Durable tasks retain these values, never the local lease address.</summary>
public sealed record UploadLeaseRequest(RemoteResourceRef Target, long Length, Guid TransferId,
    bool Overwrite = false, bool Resume = false, UploadBatchMetadata? Batch = null)
{
    public override string ToString() => "UploadLeaseRequest (target redacted)";
}

/// <summary>Body reads are not remote acknowledgements; null offset means the server has not confirmed it.</summary>
public sealed record UploadLeaseStatistics(long SentBytes, long? ConfirmedOffset, long CurrentSent,
    long? CurrentSize, long RequestId, TimeSpan Elapsed, bool Active);

/// <summary>
/// Infrastructure-only TUS capability. Its address must never enter UI state, logs or persistent tasks.
/// Disposing pauses local access; it does not cancel or delete a remote upload. Statistics do not prove completion.
/// </summary>
public interface IUploadLease : IResourceLease
{
    Guid TransferId { get; }
    long Length { get; }
    bool IsResume { get; }
    /// <summary>Returns null after explicit disposal; a retired source session rejects access.</summary>
    Task<UploadLeaseStatistics?> ReadUploadStatisticsAsync(CancellationToken cancellationToken);
}

public interface IUploadLeaseProvider
{
    /// <summary>Creates a local capability only. It does not send POST/PATCH/DELETE or replay a mutation.</summary>
    Task<IUploadLease> AcquireUploadAsync(UploadLeaseRequest request, CancellationToken cancellationToken);
}
