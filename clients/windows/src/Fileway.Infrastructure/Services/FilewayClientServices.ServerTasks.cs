using System.Globalization;
using System.Text;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices
{
    public async Task<ServerTaskPage> ListTasksAsync(AccountKey account, ServerTaskQuery query, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(query);
        var state = GetState(account);
        string endpoint = TaskListEndpoint(account, query);
        using var document = ParseBody(await RequestAsync(state, endpoint, cancellationToken).ConfigureAwait(false));
        try
        {
            var body = document.RootElement;
            var items = body.GetProperty("items").EnumerateArray().Select(row => ParseServerTask(row, account)).ToArray();
            long total = body.GetProperty("total").GetInt64();
            if (total < items.Length || items.Select(item => item.Reference.Id).Distinct(StringComparer.Ordinal).Count() != items.Length)
                throw InvalidResponse();
            EnsureActive(state);
            return new ServerTaskPage(items, OptionalText(body, "nextCursor"), total,
                ParseTaskCounts(body.GetProperty("counts")), ParseTaskCategoryCounts(body.GetProperty("categoryCounts")));
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException or ArgumentOutOfRangeException)
        { throw InvalidResponse(); }
    }

    public async Task<ServerTask> GetTaskAsync(ServerTaskRef task, CancellationToken cancellationToken)
    {
        _ = ServiceRecordId(task.Id);
        var state = GetState(task.Account);
        using var document = ParseBody(await RequestAsync(state, TaskEndpoint(task.Id), cancellationToken).ConfigureAwait(false));
        var result = ParseServerTask(document.RootElement, task.Account);
        if (result.Reference != task) throw InvalidResponse();
        EnsureActive(state);
        return result;
    }

    public Task<ServerTaskActionResult> CancelTaskAsync(ServerTaskRef task, CancellationToken cancellationToken) =>
        ServerTaskActionAsync(task, ServerTaskActionKind.CancellationRequested, cancellationToken);
    public Task<ServerTaskActionResult> RetryTaskAsync(ServerTaskRef task, CancellationToken cancellationToken) =>
        ServerTaskActionAsync(task, ServerTaskActionKind.RetryAccepted, cancellationToken);
    public Task<ServerTaskActionResult> ArchiveTaskAsync(ServerTaskRef task, CancellationToken cancellationToken) =>
        ServerTaskActionAsync(task, ServerTaskActionKind.Archived, cancellationToken);
    public Task<ServerTaskActionResult> UnarchiveTaskAsync(ServerTaskRef task, CancellationToken cancellationToken) =>
        ServerTaskActionAsync(task, ServerTaskActionKind.Unarchived, cancellationToken);

    private async Task<ServerTaskActionResult> ServerTaskActionAsync(ServerTaskRef task, ServerTaskActionKind action,
        CancellationToken cancellationToken)
    {
        _ = ServiceRecordId(task.Id);
        var state = GetState(task.Account);
        (string verb, OperationKind operation, OperationAckKind ack, int status) = action switch
        {
            ServerTaskActionKind.CancellationRequested => ("cancel", OperationKind.TaskCancel, OperationAckKind.CancellationRequested, 202),
            ServerTaskActionKind.RetryAccepted => ("retry", OperationKind.TaskRetry, OperationAckKind.RetryAccepted, 202),
            ServerTaskActionKind.Archived => ("archive", OperationKind.TaskArchive, OperationAckKind.Archived, 200),
            ServerTaskActionKind.Unarchived => ("unarchive", OperationKind.TaskUnarchive, OperationAckKind.Unarchived, 200),
            _ => throw Safe(ErrorCode.InvalidRequest, Message(ErrorCode.InvalidRequest)),
        };
        AccountKey capturedAccount = task.Account;
        ServerTask? original = null;
        ServerTask? acknowledged = null;
        var journal = PrepareJournalContext(state, operation, [], response =>
        {
            RequireExactStatus(response, status);
            using var document = ParseBody(response);
            var current = ParseServerTask(document.RootElement, capturedAccount);
            if (original is null || current.RawType != original.RawType) throw InvalidResponse();
            if (action == ServerTaskActionKind.RetryAccepted)
            {
                if (current.Reference.Id == task.Id || current.RetryOf != task.Id) throw InvalidResponse();
            }
            else if (current.Reference != task) throw InvalidResponse();
            if (action == ServerTaskActionKind.Archived && current.ArchivedAt is null ||
                action == ServerTaskActionKind.Unarchived && current.ArchivedAt is not null) throw InvalidResponse();
            acknowledged = current;
            return new OperationAcknowledgement(ack, RecordId: current.Reference.Id, Task: current.Reference);
        }, new OperationOptions(RecordId: task.Id));
        try
        {
            _ = await SessionRequestAsync(state, new { method = "POST", endpoint = TaskEndpoint(task.Id) + "/" + verb },
                requireDownload: false, HostCallSafety.Mutation, cancellationToken, beforeSend: async token =>
                {
                    using var document = ParseBody(await RequestAsync(state.CoreSession, TaskEndpoint(task.Id), token).ConfigureAwait(false));
                    original = ParseServerTask(document.RootElement, capturedAccount);
                    if (original.Reference != task) throw InvalidResponse();
                }, journal: journal).ConfigureAwait(false);
            return new ServerTaskActionResult(OperationOutcome.Confirmed, action, acknowledged, journal.Receipt);
        }
        catch (FilewayException error)
        {
            bool unknown = IsUncertainJournalFailure(error, journal);
            return new ServerTaskActionResult(unknown ? OperationOutcome.OutcomeUnknown : OperationOutcome.Partial,
                action, Receipt: journal.Receipt, Error: unknown ? UnknownMutationError(error.Error.HttpStatus) : error.Error);
        }
    }

    private static string TaskListEndpoint(AccountKey account, ServerTaskQuery query)
    {
        if (query.Limit is < 1 or > 100 || query.From > query.To || query.Category is { } category && !Enum.IsDefined(category))
            throw Safe(ErrorCode.InvalidRequest, "任务筛选条件无效。");
        var values = new List<KeyValuePair<string, string>>
        {
            new("user", account.ServerUserId), new("archived", query.Archived ? "true" : "false"),
            new("limit", query.Limit.ToString(CultureInfo.InvariantCulture)),
        };
        if (query.Statuses is { Count: > 0 } statuses)
        {
            string[] names = statuses.Select(status => status switch
            {
                ServerTaskStatus.Queued => "queued", ServerTaskStatus.Running => "running", ServerTaskStatus.Completed => "completed",
                ServerTaskStatus.Failed => "failed", ServerTaskStatus.Canceled => "canceled", ServerTaskStatus.Interrupted => "interrupted",
                _ => throw Safe(ErrorCode.InvalidRequest, "未知任务状态不能作为筛选条件。"),
            }).Distinct(StringComparer.Ordinal).ToArray();
            values.Add(new("status", string.Join(',', names)));
        }
        if (query.Type is not null) values.Add(new("type", query.Type));
        if (query.Text is not null) values.Add(new("text", query.Text));
        if (query.Cursor is not null) values.Add(new("cursor", query.Cursor));
        if (query.From is { } from) values.Add(new("from", from.ToUnixTimeMilliseconds().ToString(CultureInfo.InvariantCulture)));
        if (query.To is { } to) values.Add(new("to", to.ToUnixTimeMilliseconds().ToString(CultureInfo.InvariantCulture)));
        if (query.Category is { } selected) values.Add(new("category", selected == ServerTaskCategory.File ? "file" : "background"));
        return "/api/tasks?" + string.Join('&', values.Select(pair => Uri.EscapeDataString(pair.Key) + "=" + Uri.EscapeDataString(pair.Value)));
    }

    // Pure parser: an observed mutation ACK must remain valid after logout.
    // It never binds resources or consults a mutable active session.
    private static ServerTask ParseServerTask(JsonElement row, AccountKey account)
    {
        try
        {
            string owner = ServiceOwner(row);
            if (owner != account.ServerUserId) throw Safe(ErrorCode.PermissionDenied, "此任务不属于当前账户。", 403);
            string id = ServiceRecordId(RequiredText(row.GetProperty("id")));
            string type = RequiredText(row.GetProperty("type"));
            string status = RequiredText(row.GetProperty("status"));
            long totalItems = row.GetProperty("totalItems").GetInt64();
            long processedItems = row.GetProperty("processedItems").GetInt64();
            long totalBytes = row.GetProperty("totalBytes").GetInt64();
            long processedBytes = row.GetProperty("processedBytes").GetInt64();
            if (totalItems < 0 || processedItems < 0 || totalBytes < 0 || processedBytes < 0) throw InvalidResponse();
            return new ServerTask(new ServerTaskRef(account, id), owner, type, status, status switch
            {
                "queued" => ServerTaskStatus.Queued, "running" => ServerTaskStatus.Running,
                "completed" => ServerTaskStatus.Completed, "failed" => ServerTaskStatus.Failed,
                "canceled" => ServerTaskStatus.Canceled, "interrupted" => ServerTaskStatus.Interrupted,
                _ => ServerTaskStatus.Unknown,
            }, OptionalText(row, "title") ?? "", ServiceUnixDate(row.GetProperty("createdAt")),
                totalItems, processedItems, totalBytes, processedBytes,
                ServiceOptionalDate(row, "startedAt"), ServiceOptionalDate(row, "finishedAt"),
                ServiceOptionalDate(row, "undoUntil"), ServiceOptionalDate(row, "archivedAt"), OptionalText(row, "retryOf"),
                OptionalText(row, "sourcePath"), OptionalText(row, "outputPath"),
                OptionalText(row, "error") is { Length: > 0 } ? new ErrorInfo(ErrorCode.Unknown, "服务端任务出现错误，请核实任务状态。") : null);
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException or ArgumentOutOfRangeException)
        { throw InvalidResponse(); }
    }

    private static Dictionary<string, long> ParseTaskCounts(JsonElement value)
    {
        var counts = new Dictionary<string, long>(StringComparer.Ordinal);
        foreach (var property in value.EnumerateObject())
        {
            long count = property.Value.GetInt64();
            if (count < 0 || !counts.TryAdd(property.Name, count)) throw InvalidResponse();
        }
        return counts;
    }

    private static Dictionary<string, IReadOnlyDictionary<string, long>> ParseTaskCategoryCounts(JsonElement value)
    {
        var categories = new Dictionary<string, IReadOnlyDictionary<string, long>>(StringComparer.Ordinal);
        foreach (var property in value.EnumerateObject())
        {
            if (!categories.TryAdd(property.Name, ParseTaskCounts(property.Value))) throw InvalidResponse();
        }
        return categories;
    }

    private static string TaskEndpoint(string id) => "/api/tasks/" + Uri.EscapeDataString(ServiceRecordId(id));
    private static string ServiceRecordId(string id)
    {
        if (string.IsNullOrWhiteSpace(id) || id.Length > 1024 || id.Any(character => character is '/' or '\\' or '\0' || char.IsControl(character)))
            throw Safe(ErrorCode.InvalidRequest, "记录标识无效。");
        try { _ = StrictUtf8.GetByteCount(id); }
        catch (EncoderFallbackException) { throw Safe(ErrorCode.InvalidRequest, "记录标识包含无效字符。"); }
        return id;
    }
    private static string ServiceOwner(JsonElement row)
    {
        if (!row.GetProperty("userId").TryGetUInt64(out ulong id) || id == 0) throw InvalidResponse();
        return id.ToString(CultureInfo.InvariantCulture);
    }
    private static DateTimeOffset ServiceUnixDate(JsonElement value) => DateTimeOffset.FromUnixTimeMilliseconds(value.GetInt64());
    private static DateTimeOffset? ServiceOptionalDate(JsonElement row, string name) => row.TryGetProperty(name, out var value) &&
        value.ValueKind != JsonValueKind.Null && value.GetInt64() != 0 ? ServiceUnixDate(value) : null;
    private static void RequireExactStatus(JsonElement response, int expected)
    {
        if (response.GetProperty("status").GetInt32() != expected) throw InvalidResponse();
    }
    private static bool IsUncertainJournalFailure(FilewayException error, JournalContext journal) =>
        error.Error.Code == ErrorCode.OutcomeUnknown || journal.Record?.Phase == OperationPhase.OutcomeUnknown ||
        journal.CallStarted && error.Error.HttpStatus is not (>= 400 and < 500 and not 408);
    private static ErrorInfo UnknownMutationError(int? status = null) => new(ErrorCode.OutcomeUnknown, Message(ErrorCode.OutcomeUnknown), status);
}
