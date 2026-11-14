namespace Fileway.Core.Contracts;

public enum SearchScope { Current, Recursive }
public enum SearchPathConfidence { ServerWire, LegacyUtf8, DisplayOnly }
public enum SearchEndReason { Completed, LimitReached, ServerTimeout, Canceled, Failed, SessionChanged }

public sealed record SearchRequest(RemoteResourceRef Base, string Query, SearchScope Scope = SearchScope.Current)
{
    public override string ToString() => "SearchRequest (query and source redacted)";
}

public sealed record SearchHit(string RelativeDisplayPath, string DisplayName, bool IsDirectory, long Size,
    DateTimeOffset? Modified, string? RiskLevel, RemoteResourceRef? Resource, SearchPathConfidence PathConfidence)
{
    public override string ToString() => "SearchHit (resource redacted)";
}

public sealed record SearchEnd(SearchEndReason Reason, long? ServerCount = null, ErrorInfo? Error = null);
public sealed record SearchUpdate(IReadOnlyList<SearchHit> Items, long ReceivedCount, SearchEnd? End = null);

public interface IServerSearchService
{
    IAsyncEnumerable<SearchUpdate> SearchAsync(SearchRequest request, CancellationToken cancellationToken);
}
