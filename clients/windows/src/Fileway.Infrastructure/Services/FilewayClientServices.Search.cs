using System.Globalization;
using System.Runtime.CompilerServices;
using System.Text;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices
{
    public async IAsyncEnumerable<SearchUpdate> SearchAsync(SearchRequest request,
        [EnumeratorCancellation] CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(request);
        ArgumentNullException.ThrowIfNull(request.Base);
        SessionState? state = null;
        CancellationTokenSource? linked = null;
        string? handle = null;
        long received = 0;
        long delivered = 0;
        try
        {
            SearchEnd? end = null;
            try
            {
                ValidateSearchRequest(request);
                state = GetResourceState(request.Base);
                linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
                var directory = await GetAsync(request.Base, linked.Token).ConfigureAwait(false);
                if (!directory.IsDirectory) throw Safe(ErrorCode.InvalidRequest, "搜索范围必须是文件夹。");
                _ = await SessionRequestAsync(state, new
                {
                    wirePath = request.Base.WirePath,
                    query = request.Query,
                    scope = request.Scope == SearchScope.Current ? "current" : "recursive",
                }, requireDownload: false, HostCallSafety.ReadOnly, linked.Token, operation: "search_start",
                    // Capture before the post-call principal refresh. Even a
                    // subsequently rejected start must release its owned handle.
                    confirmResponse: response => handle = RequiredText(response)).ConfigureAwait(false);
            }
            catch (Exception error) when (IsSearchFailure(error))
            {
                end = SearchFailure(error, state, cancellationToken);
            }
            if (end is not null)
            {
                yield return new SearchUpdate([], received, end);
                yield break;
            }

            bool firstPoll = true;
            while (true)
            {
                SearchPollResult? batch = null;
                try
                {
                    if (!firstPoll) await Task.Delay(100, linked!.Token).ConfigureAwait(false);
                    firstPoll = false;
                    var response = await SessionRequestAsync(state!, new { search = handle }, requireDownload: false,
                        HostCallSafety.ReadOnly, linked!.Token, operation: "search_poll").ConfigureAwait(false);
                    batch = ParseSearchPoll(response, request, state!, received, delivered);
                    EnsureActive(state!);
                    received = batch.Count;
                    delivered += batch.Items.Count;
                    end = batch.End;
                }
                catch (Exception error) when (IsSearchFailure(error))
                {
                    batch = null; // A replaced session cannot publish this batch.
                    end = SearchFailure(error, state, cancellationToken);
                }
                if (end?.Error is { Code: ErrorCode.AuthenticationExpired } authenticationError)
                {
                    try
                    {
                        if (await RetireAsync(state!).ConfigureAwait(false)) PublishConnectionIssue(state!, authenticationError);
                    }
                    catch (FilewayException) { /* The terminal remains the original safe authentication failure. */ }
                }
                if (batch?.Items.Count > 0 || end is not null)
                    yield return new SearchUpdate(batch?.Items ?? [], received, end);
                if (end is not null) yield break;
            }
        }
        finally
        {
            if (handle is not null && state is not null)
            {
                // Cleanup uses the immutable original session, including after
                // cancellation/replacement. It never binds to a fresh login.
                using var cleanup = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                try
                {
                    _ = await CallAsync("search_cancel", new { search = handle }, state.CoreSession,
                        cleanup.Token, HostCallSafety.Control, TimeSpan.FromSeconds(2)).ConfigureAwait(false);
                }
                catch (FilewayException) { /* Session close also cancels all of its searches. */ }
            }
            linked?.Dispose();
        }
    }

    private static void ValidateSearchRequest(SearchRequest request)
    {
        _ = FileOperationWireIdentity(request.Base.WirePath, allowRoot: true);
        if (!Enum.IsDefined(request.Scope) || string.IsNullOrWhiteSpace(request.Query))
            throw Safe(ErrorCode.InvalidRequest, "请输入搜索条件并选择有效范围。");
        try
        {
            if (StrictUtf8.GetByteCount(request.Query) > 8192)
                throw Safe(ErrorCode.InvalidRequest, "搜索条件过长。");
        }
        catch (EncoderFallbackException) { throw Safe(ErrorCode.InvalidRequest, "搜索条件包含无效字符。"); }
    }

    private sealed record SearchPollResult(IReadOnlyList<SearchHit> Items, long Count, SearchEnd? End);

    private SearchPollResult ParseSearchPoll(JsonElement response, SearchRequest request, SessionState state,
        long previousCount, long delivered)
    {
        try
        {
            var rows = response.GetProperty("items");
            if (rows.ValueKind != JsonValueKind.Array || rows.GetArrayLength() > 32) throw InvalidResponse();
            long count = response.GetProperty("count").GetInt64();
            bool done = response.GetProperty("done").GetBoolean();
            string status = RequiredText(response.GetProperty("state"));
            if (count < previousCount || count < delivered + rows.GetArrayLength() ||
                status is not ("running" or "finished" or "failed" or "canceled") || done && status == "running")
                throw InvalidResponse();
            var items = rows.EnumerateArray().Select(row => ParseSearchHit(row, request, state)).ToArray();
            if (!done) return new SearchPollResult(items, count, null);
            if (count != delivered + items.Length) throw InvalidResponse();

            long? serverCount = null;
            string? reason = null;
            if (response.TryGetProperty("summary", out var summary) && summary.ValueKind != JsonValueKind.Null)
            {
                reason = RequiredText(summary.GetProperty("reason"));
                serverCount = summary.GetProperty("count").GetInt64();
                if (serverCount < count || reason is ("completed" or "limit") && serverCount != count)
                    throw InvalidResponse();
            }
            if (status == "canceled") return new SearchPollResult(items, count, new SearchEnd(SearchEndReason.Canceled, serverCount));
            if (status == "failed")
            {
                int? httpStatus = response.TryGetProperty("httpStatus", out var http) ? http.GetInt32() : null;
                if (httpStatus is < 100 or > 599) throw InvalidResponse();
                ErrorCode code = httpStatus switch
                {
                    401 => ErrorCode.AuthenticationExpired,
                    403 => ErrorCode.PermissionDenied,
                    408 or 504 => ErrorCode.Timeout,
                    null or 200 => ErrorCode.ProtocolMismatch,
                    _ => ErrorCode.NetworkUnavailable,
                };
                return new SearchPollResult(items, count, new SearchEnd(SearchEndReason.Failed, serverCount,
                    new ErrorInfo(code, Message(code), httpStatus)));
            }
            SearchEndReason terminal = reason switch
            {
                "completed" => SearchEndReason.Completed,
                "limit" => SearchEndReason.LimitReached,
                "timeout" => SearchEndReason.ServerTimeout,
                "canceled" => SearchEndReason.Canceled,
                _ => throw InvalidResponse(),
            };
            return new SearchPollResult(items, count, new SearchEnd(terminal, serverCount));
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException or OverflowException)
        { throw InvalidResponse(); }
    }

    private SearchHit ParseSearchHit(JsonElement row, SearchRequest request, SessionState state)
    {
        string relative = RequiredText(row.GetProperty("path"));
        string name = RequiredText(row.GetProperty("name"));
        string[] parts = relative.Split('/');
        if (relative.StartsWith('/') || relative.Contains('\0') || parts.Any(part => part is "" or "." or "..") ||
            !StringComparer.Ordinal.Equals(name, parts[^1]) || request.Scope == SearchScope.Current && parts.Length != 1)
            throw InvalidResponse();
        bool directory = row.GetProperty("dir").GetBoolean();
        long size = row.GetProperty("size").GetInt64();
        if (size < 0) throw InvalidResponse();
        DateTimeOffset? modified = null;
        if (OptionalText(row, "modified") is { Length: > 0 } date)
        {
            if (!DateTimeOffset.TryParse(date, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var parsed))
                throw InvalidResponse();
            modified = parsed;
        }

        RemoteResourceRef? resource = null;
        var confidence = SearchPathConfidence.DisplayOnly;
        bool wirePresent = row.TryGetProperty("wirePath", out var wireValue) && wireValue.ValueKind != JsonValueKind.Null;
        bool verified = Flag(row, "pathVerified");
        if (wirePresent || verified || state.Session.Capabilities.SearchWirePathV1)
        {
            if (verified && wireValue.ValueKind == JsonValueKind.String && wireValue.GetString() is { Length: > 0 } wire &&
                ValidSearchWireChild(request.Base.WirePath, wire, parts, request.Scope))
            {
                resource = BindResource(new RemoteResourceRef(request.Base.Account, wire, name,
                    request.Base.DisplayPath.TrimEnd('/') + "/" + relative), state);
                confidence = SearchPathConfidence.ServerWire;
            }
        }
        else
        {
            try
            {
                // A legacy path is literal UTF-8 metadata, never an encoded URI.
                // U+FFFD cannot establish whether JSON lost filesystem bytes.
                if (!relative.Contains('\uFFFD'))
                {
                    foreach (string part in parts) _ = StrictUtf8.GetByteCount(part);
                    string wire = request.Base.WirePath.TrimEnd('/') + "/" + string.Join('/', parts.Select(Uri.EscapeDataString));
                    if (ValidSearchWireChild(request.Base.WirePath, wire, parts, request.Scope))
                    {
                        resource = BindResource(new RemoteResourceRef(request.Base.Account, wire, name,
                            request.Base.DisplayPath.TrimEnd('/') + "/" + relative), state);
                        confidence = SearchPathConfidence.LegacyUtf8;
                    }
                }
            }
            catch (EncoderFallbackException) { /* Display-only metadata is still useful. */ }
        }
        return new SearchHit(relative, name, directory, size, modified, OptionalText(row, "riskLevel"), resource, confidence);
    }

    private static bool ValidSearchWireChild(string baseWire, string wire, string[] displayParts, SearchScope scope)
    {
        try
        {
            byte[] parent = Convert.FromHexString(FileOperationWireIdentity(baseWire, allowRoot: true));
            byte[] child = Convert.FromHexString(FileOperationWireIdentity(wire, allowRoot: false));
            int start;
            if (parent.Length == 1) start = 1;
            else
            {
                if (child.Length <= parent.Length || !child.AsSpan(0, parent.Length).SequenceEqual(parent) || child[parent.Length] != '/')
                    return false;
                start = parent.Length + 1;
            }
            if (start >= child.Length) return false;
            int partIndex = 0;
            while (start < child.Length)
            {
                int end = Array.IndexOf(child, (byte)'/', start);
                if (end < 0) end = child.Length;
                if (partIndex >= displayParts.Length) return false;
                try
                {
                    if (!StringComparer.Ordinal.Equals(StrictUtf8.GetString(child, start, end - start), displayParts[partIndex]))
                        return false;
                }
                catch (DecoderFallbackException) { /* The server wire preserves bytes that its display cannot. */ }
                partIndex++;
                start = end + 1;
            }
            return partIndex == displayParts.Length && (scope != SearchScope.Current || partIndex == 1);
        }
        catch (FilewayException) { return false; }
    }

    private static bool IsSearchFailure(Exception error) => error is FilewayException or OperationCanceledException or
        JsonException or InvalidOperationException or FormatException or KeyNotFoundException;

    private static SearchEnd SearchFailure(Exception error, SessionState? state, CancellationToken cancellationToken)
    {
        // A helper may retire the session because it already observed 401 or a
        // renewal failure. That retirement must not erase the original cause.
        ErrorInfo safe;
        if (error is FilewayException fileway)
            safe = fileway.Error.Code == ErrorCode.Canceled && state?.LifetimeToken.IsCancellationRequested == true
                ? new ErrorInfo(ErrorCode.SourceChanged, Message(ErrorCode.SourceChanged)) : fileway.Error;
        else if (error is OperationCanceledException)
        {
            var code = state?.LifetimeToken.IsCancellationRequested == true ? ErrorCode.SourceChanged :
                cancellationToken.IsCancellationRequested ? ErrorCode.Canceled : ErrorCode.Timeout;
            safe = new ErrorInfo(code, Message(code));
        }
        else safe = InvalidResponse().Error;
        return new SearchEnd(safe.Code == ErrorCode.SourceChanged ? SearchEndReason.SessionChanged :
            safe.Code == ErrorCode.Canceled ? SearchEndReason.Canceled : SearchEndReason.Failed, Error: safe);
    }
}
