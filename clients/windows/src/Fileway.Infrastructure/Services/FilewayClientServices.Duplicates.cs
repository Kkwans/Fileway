using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices : IDuplicateAnalysisService
{
    async Task<ServerTaskRef> IDuplicateAnalysisService.StartScanAsync(RemoteResourceRef directory, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(directory);
        if (directory.SourceVersion is not null) throw Safe(ErrorCode.InvalidRequest, Message(ErrorCode.InvalidRequest));
        var state = GetResourceState(directory);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        try
        {
            RequireDownload(state);
            var response = await SessionRequestAsync(state, new
            {
                method = "POST",
                endpoint = "/api/analysis/duplicates",
                body = new { paths = new[] { directory.DisplayPath } },
            }, requireDownload: true, HostCallSafety.Control, cancellationToken, operation: "request",
            beforeSend: async token =>
            {
                var current = await FileMetadataAsync(state, directory, allowMissing: false, token).ConfigureAwait(false);
                if (current is not { IsDirectory: true } || current.Resource.WirePath != directory.WirePath)
                    throw Safe(ErrorCode.SourceChanged, "扫描范围已变化，请重新选择。", 412);
            }).ConfigureAwait(false);
            RequireExactStatus(response, 202);
            using var body = ParseBody(response);
            string id = RequiredText(body.RootElement.GetProperty("id"));
            lock (_gate)
            {
                linked.Token.ThrowIfCancellationRequested();
                RequireDownload(state);
                return new ServerTaskRef(state.Session.Account, id);
            }
        }
        catch (OperationCanceledException) { throw SessionCanceled(state, cancellationToken); }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    async Task<ServerTask> IDuplicateAnalysisService.GetTaskAsync(ServerTaskRef task, CancellationToken cancellationToken) =>
        await GetTaskAsync(task, cancellationToken).ConfigureAwait(false);

    async Task<DuplicateReport?> IDuplicateAnalysisService.GetReportAsync(ServerTaskRef task, CancellationToken cancellationToken)
    {
        _ = ServiceRecordId(task.Id);
        var state = GetState(task.Account);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        try
        {
            var response = await RequestAsync(state, "/api/analysis/" + Uri.EscapeDataString(task.Id), linked.Token).ConfigureAwait(false);
            int status = response.GetProperty("status").GetInt32();
            if (status is 409 or 404) return null;
            RequireExactStatus(response, 200);
            using var body = ParseBody(response);
            EnsureActive(state);
            return ParseDuplicateReport(body.RootElement);
        }
        catch (FilewayException error) when (error.Error.HttpStatus is 404 or 409)
        { EnsureActive(state); return null; }
        catch (OperationCanceledException) { throw SessionCanceled(state, cancellationToken); }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    private static DuplicateReport ParseDuplicateReport(JsonElement row)
    {
        try
        {
            var scopes = row.GetProperty("scopes").EnumerateArray().Select(item => RequiredText(item)).ToArray();
            var groups = new List<DuplicateGroup>();
            foreach (var group in row.GetProperty("groups").EnumerateArray())
            {
                var files = new List<DuplicateFileInfo>();
                foreach (var file in group.GetProperty("files").EnumerateArray())
                {
                    DateTimeOffset? modified = null;
                    if (file.TryGetProperty("modified", out var raw) && raw.ValueKind == JsonValueKind.Number)
                        modified = DateTimeOffset.FromUnixTimeMilliseconds(raw.GetInt64());
                    files.Add(new(RequiredText(file.GetProperty("path")), file.GetProperty("size").GetInt64(), modified,
                        file.TryGetProperty("identity", out _)));
                }
                groups.Add(new(
                    RequiredText(group.GetProperty("sha256")),
                    group.GetProperty("size").GetInt64(),
                    group.GetProperty("totalFiles").GetInt32(),
                    group.GetProperty("reclaimableBytes").GetInt64(),
                    files,
                    OptionalText(group, "suggestedKeepPath"),
                    OptionalText(group, "keepReason")));
            }
            var report = new DuplicateReport(scopes,
                row.GetProperty("scannedFiles").GetInt64(),
                row.GetProperty("scannedBytes").GetInt64(),
                row.GetProperty("candidateFiles").GetInt64(),
                row.GetProperty("duplicateGroups").GetInt32(),
                row.GetProperty("duplicateFiles").GetInt32(),
                row.GetProperty("reclaimableBytes").GetInt64(),
                groups,
                row.GetProperty("skippedCount").GetInt32(),
                row.GetProperty("truncated").GetBoolean());
            if (report.ScannedFiles < 0 || report.ReclaimableBytes < 0) throw InvalidResponse();
            return report;
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException or ArgumentOutOfRangeException)
        { throw InvalidResponse(); }
    }

    async Task<ServerTaskRef> IDuplicateAnalysisService.StartCleanupAsync(AccountKey account, string reportId,
        IReadOnlyList<DuplicateCleanupSelection> selections, CancellationToken cancellationToken)
    {
        if (string.IsNullOrEmpty(reportId) || selections is null || selections.Count == 0)
            throw Safe(ErrorCode.InvalidRequest, Message(ErrorCode.InvalidRequest));
        var state = GetState(account);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        try
        {
            RequireDownload(state);
            var response = await SessionRequestAsync(state, new
            {
                method = "POST",
                endpoint = "/api/analysis/duplicates/" + Uri.EscapeDataString(reportId) + "/cleanup",
                body = new { groups = selections.Select(selection => new { sha256 = selection.Sha256, keepPath = selection.KeepPath }).ToArray() },
            }, requireDownload: true, HostCallSafety.Mutation, linked.Token, operation: "request").ConfigureAwait(false);
            if (response.GetProperty("status").GetInt32() is not (200 or 202))
                RequireExactStatus(response, 200);
            using var body = ParseBody(response);
            string id = RequiredText(body.RootElement.GetProperty("id"));
            lock (_gate)
            {
                linked.Token.ThrowIfCancellationRequested();
                return new ServerTaskRef(account, id);
            }
        }
        catch (OperationCanceledException) { throw SessionCanceled(state, cancellationToken); }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    async Task<ServerTask?> IDuplicateAnalysisService.GetCleanupForReportAsync(AccountKey account, string reportId,
        CancellationToken cancellationToken)
    {
        var state = GetState(account);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        try
        {
            var response = await RequestAsync(state, "/api/analysis/duplicates/" + Uri.EscapeDataString(reportId) + "/cleanup", linked.Token).ConfigureAwait(false);
            int status = response.GetProperty("status").GetInt32();
            if (status == 404) return null;
            RequireExactStatus(response, 200);
            using var body = ParseBody(response);
            EnsureActive(state);
            return ParseServerTask(body.RootElement, state.Session.Account);
        }
        catch (FilewayException error) when (error.Error.HttpStatus == 404)
        { EnsureActive(state); return null; }
        catch (OperationCanceledException) { throw SessionCanceled(state, cancellationToken); }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    async Task<DuplicateCleanupResult?> IDuplicateAnalysisService.GetCleanupResultAsync(ServerTaskRef cleanupTask,
        CancellationToken cancellationToken)
    {
        _ = ServiceRecordId(cleanupTask.Id);
        var state = GetState(cleanupTask.Account);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        try
        {
            var response = await RequestAsync(state, "/api/analysis/duplicates/cleanup/" + Uri.EscapeDataString(cleanupTask.Id), linked.Token).ConfigureAwait(false);
            int status = response.GetProperty("status").GetInt32();
            if (status is 404 or 409) return null;
            RequireExactStatus(response, 200);
            using var body = ParseBody(response);
            EnsureActive(state);
            var row = body.RootElement;
            var groups = new List<(string, string, IReadOnlyList<DuplicateCleanupFileResult>)>();
            foreach (var group in row.GetProperty("groups").EnumerateArray())
            {
                var files = new List<DuplicateCleanupFileResult>();
                foreach (var file in group.GetProperty("files").EnumerateArray())
                    files.Add(new(RequiredText(file.GetProperty("path")), RequiredText(file.GetProperty("status")),
                        OptionalText(file, "trashId"), OptionalText(file, "reason")));
                groups.Add((RequiredText(group.GetProperty("sha256")), RequiredText(group.GetProperty("keepPath")), files));
            }
            return new DuplicateCleanupResult(RequiredText(row.GetProperty("reportId")), groups);
        }
        catch (FilewayException error) when (error.Error.HttpStatus is 404 or 409)
        { EnsureActive(state); return null; }
        catch (OperationCanceledException) { throw SessionCanceled(state, cancellationToken); }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }
}
