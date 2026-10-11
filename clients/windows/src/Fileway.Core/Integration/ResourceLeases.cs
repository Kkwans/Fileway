using Fileway.Core.Contracts;

namespace Fileway.Core.Integration;

/// <summary>Infrastructure-only capability. Its address must never enter UI snapshots, history or logs.</summary>
public interface IResourceLease : IAsyncDisposable
{
    RemoteResourceRef Resource { get; }
    Uri Address { get; }
}

public interface IResourceLeaseProvider
{
    Task<IResourceLease> AcquireAsync(RemoteResourceRef resource, CancellationToken cancellationToken);
}

/// <summary>Optional disk-backed acquisition for the primary media stream; ordinary leases remain available.</summary>
public interface ICachedResourceLeaseProvider
{
    Task<IResourceLease> AcquireCachedAsync(RemoteResourceRef resource, CancellationToken cancellationToken);
}

public sealed record ResourceLeaseStatistics(
    long UpstreamBytes,
    long CacheReadBytes,
    long DeliveredBytes,
    TimeSpan Elapsed);

/// <summary>Optional, owner-bound statistics. Capability URLs and credentials never enter a snapshot.</summary>
public interface IResourceLeaseStatisticsSource
{
    Task<ResourceLeaseStatistics?> ReadStatisticsAsync(CancellationToken cancellationToken);
}
