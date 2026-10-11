using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices
{
    public async Task<IReadOnlyList<RecentAccessEntry>> ListRecentAsync(AccountKey account, CancellationToken cancellationToken)
    {
        var state = GetState(account);
        using var document = ParseBody(await RequestAsync(state, "/api/recent?limit=100", cancellationToken).ConfigureAwait(false));
        try
        {
            if (document.RootElement.GetArrayLength() > 100) throw InvalidResponse();
            var entries = document.RootElement.EnumerateArray().Select(row => ParseRecentAcknowledgement(row, account)).ToArray();
            RequireDistinctCollectionIds(entries.Select(entry => entry.Id));
            EnsureActive(state);
            // Preserve the server's ordering and timestamps. A history row is
            // not proof that the current path still denotes the original file.
            var bound = entries.Select(entry => entry.Resource is { } resource
                ? entry with { Resource = BindResource(resource, state) } : entry).ToArray();
            EnsureActive(state);
            return bound;
        }
        catch (Exception error) when (CollectionFormatError(error)) { throw InvalidResponse(); }
    }

    public async Task<RecentAccessRecordResult> RecordOpenedAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(resource);
        bool mayHaveSubmitted = false;
        RecentAccessRecordResult? acknowledged = null;
        try
        {
            var state = GetResourceState(resource);
            var body = CollectionPathBody(resource);
            string identity = CollectionWireIdentity(resource.WirePath);
            await SessionRequestAsync(state, new { method = "POST", endpoint = "/api/recent", body },
                requireDownload: false, HostCallSafety.Mutation, cancellationToken,
                authorizeSend: () =>
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    EnsureActive(state);
                    // A cancellation/retirement can still race with the host
                    // invocation after this point; conservatively keep unknown.
                    mayHaveSubmitted = true;
                }, confirmResponse: response =>
                {
                    try
                    {
                        if (response.GetProperty("status").GetInt32() != 200) throw InvalidResponse();
                        using var document = ParseBody(response);
                        var saved = ParseRecentAcknowledgement(document.RootElement, resource.Account);
                        if (saved.Resource is not { } target || CollectionWireIdentity(target.WirePath) != identity)
                            throw InvalidResponse();
                        // Parse purely: a late ACK must not bind a new resource
                        // into a retired or replacement account session.
                        acknowledged = new RecentAccessRecordResult(RecentAccessRecordOutcome.Confirmed, saved.Id, saved.AccessedAt);
                    }
                    catch (Exception error) when (error is FilewayException || CollectionFormatError(error))
                    { throw Safe(ErrorCode.OutcomeUnknown, "最近访问记录结果尚未确认，请刷新核实；不要重复提交。"); }
                }).ConfigureAwait(false);
            return acknowledged!;
        }
        catch (OperationCanceledException)
        {
            return acknowledged ?? RecentRecordingFailure(mayHaveSubmitted, new ErrorInfo(ErrorCode.Canceled, Message(ErrorCode.Canceled)));
        }
        catch (FilewayException error)
        {
            return acknowledged ?? RecentRecordingFailure(mayHaveSubmitted, error.Error);
        }
    }

    private static RecentAccessRecordResult RecentRecordingFailure(bool mayHaveSubmitted, ErrorInfo error)
    {
        if (!mayHaveSubmitted)
            return new RecentAccessRecordResult(RecentAccessRecordOutcome.NotSubmitted, Error: error with
            { Message = "最近访问记录尚未提交。" + Message(error.Code), Retryable = false });
        if (error.HttpStatus is >= 400 and < 500 and not 408)
            return new RecentAccessRecordResult(RecentAccessRecordOutcome.Rejected, Error: error with
            { Message = "服务器拒绝了本次最近访问记录。" + Message(error.Code), Retryable = false });
        return new RecentAccessRecordResult(RecentAccessRecordOutcome.OutcomeUnknown, Error: new ErrorInfo(ErrorCode.OutcomeUnknown,
            "最近访问记录结果尚未确认，请刷新核实；不要重复提交。", error.HttpStatus));
    }

    private static RecentAccessEntry ParseRecentAcknowledgement(JsonElement row, AccountKey account)
    {
        string id = RequiredText(row.GetProperty("id"));
        try { _ = CollectionId(id); }
        catch (FilewayException) { throw InvalidResponse(); }
        var reference = ParseCollectionResourceAcknowledgement(row, account);
        string? wire = CollectionOptionalText(row, "wirePath");
        var confidence = reference.Resource is null ? RecentAccessPathConfidence.Unverified
            : string.IsNullOrEmpty(wire) ? RecentAccessPathConfidence.LegacyUtf8 : RecentAccessPathConfidence.ServerWire;
        return new RecentAccessEntry(account, id, CollectionText(row, "name"), reference.DisplayPath,
            row.GetProperty("isDir").GetBoolean(), DateTimeOffset.FromUnixTimeMilliseconds(row.GetProperty("accessedAt").GetInt64()),
            reference.Resource, confidence);
    }
}
