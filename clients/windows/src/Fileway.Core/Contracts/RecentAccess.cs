namespace Fileway.Core.Contracts;

public enum RecentAccessPathConfidence { ServerWire, LegacyUtf8, Unverified }

/// <summary>Server-owned path history, not an immutable file snapshot or playback position.</summary>
public sealed record RecentAccessEntry(AccountKey Account, string Id, string DisplayName, string DisplayPath,
    bool IsDirectory, DateTimeOffset AccessedAt, RemoteResourceRef? Resource, RecentAccessPathConfidence PathConfidence)
{
    public override string ToString() => "RecentAccessEntry (history redacted)";
}

public enum RecentAccessRecordOutcome { Confirmed, Rejected, OutcomeUnknown, NotSubmitted }

/// <summary>A secondary recording result; it must not reverse an already successful open.</summary>
public sealed record RecentAccessRecordResult(RecentAccessRecordOutcome Outcome, string? RecordId = null,
    DateTimeOffset? AccessedAt = null, ErrorInfo? Error = null);

public interface IRecentAccessService
{
    /// <summary>At most the server's retained 100 entries; no fabricated cursor, total or local timestamp.</summary>
    Task<IReadOnlyList<RecentAccessEntry>> ListRecentAsync(AccountKey account, CancellationToken cancellationToken);

    /// <summary>Call only after a real remote resource has opened successfully; never for prefetch or selection.</summary>
    Task<RecentAccessRecordResult> RecordOpenedAsync(RemoteResourceRef resource, CancellationToken cancellationToken);
}
