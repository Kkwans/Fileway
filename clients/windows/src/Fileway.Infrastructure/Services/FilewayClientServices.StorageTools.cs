using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices : IStorageAnalysisService
{
    async Task<ServerTaskRef> IStorageAnalysisService.StartAsync(RemoteResourceRef directory, CancellationToken cancellationToken)
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
                endpoint = "/api/analysis/storage",
                body = new { paths = new[] { directory.DisplayPath } },
            }, requireDownload: true, HostCallSafety.Control, cancellationToken, operation: "request",
            beforeSend: async token =>
            {
                // Scope fidelity: the server path this task analyzes must still be the directory
                // the user chose, re-observed right before submission.
                var current = await GetAsync(directory, token).ConfigureAwait(false);
                if (!current.IsDirectory || current.Resource.WirePath != directory.WirePath)
                    throw Safe(ErrorCode.SourceChanged, "分析范围已变化，请重新选择。", 412);
            }).ConfigureAwait(false);
            RequireExactStatus(response, 200);
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

    async Task<ServerTask> IStorageAnalysisService.GetTaskAsync(ServerTaskRef task, CancellationToken cancellationToken) =>
        await GetTaskAsync(task, cancellationToken).ConfigureAwait(false);

    async Task<StorageAnalysisReport?> IStorageAnalysisService.GetReportAsync(ServerTaskRef task, CancellationToken cancellationToken)
    {
        _ = ServiceRecordId(task.Id);
        var state = GetState(task.Account);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        try
        {
            var response = await RequestAsync(state, "/api/analysis/storage/" + Uri.EscapeDataString(task.Id), linked.Token).ConfigureAwait(false);
            int status = response.GetProperty("status").GetInt32();
            if (status is 409 or 404)
                return null; // Still running (409) or unknown/not-yet-published (404): not an error.
            RequireExactStatus(response, 200);
            using var body = ParseBody(response);
            EnsureActive(state);
            return ParseStorageReport(body.RootElement);
        }
        catch (OperationCanceledException) { throw SessionCanceled(state, cancellationToken); }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    private static StorageAnalysisReport ParseStorageReport(JsonElement row)
    {
        try
        {
            var scopes = new List<StorageAnalysisScope>();
            foreach (var item in row.GetProperty("scopes").EnumerateArray())
                scopes.Add(new(
                    RequiredText(item.GetProperty("path")),
                    item.GetProperty("isDir").GetBoolean(),
                    item.GetProperty("files").GetInt64(),
                    item.GetProperty("directories").GetInt64(),
                    item.GetProperty("bytes").GetInt64()));
            var files = new List<StorageAnalysisLargestFile>();
            foreach (var item in row.GetProperty("largestFiles").EnumerateArray())
            {
                DateTimeOffset? modified = null;
                if (item.TryGetProperty("modified", out var raw) && raw.ValueKind == JsonValueKind.Number)
                    modified = DateTimeOffset.FromUnixTimeMilliseconds(raw.GetInt64());
                files.Add(new(RequiredText(item.GetProperty("path")), item.GetProperty("size").GetInt64(), modified));
            }
            var directories = new List<StorageAnalysisLargestDirectory>();
            foreach (var item in row.GetProperty("largestDirectories").EnumerateArray())
                directories.Add(new(RequiredText(item.GetProperty("path")), item.GetProperty("files").GetInt64(), item.GetProperty("bytes").GetInt64()));
            var report = new StorageAnalysisReport(scopes,
                row.GetProperty("scannedFiles").GetInt64(),
                row.GetProperty("scannedDirectories").GetInt64(),
                row.GetProperty("scannedBytes").GetInt64(),
                files, directories,
                row.GetProperty("skippedCount").GetInt32(),
                row.GetProperty("truncated").GetBoolean());
            if (report.ScannedFiles < 0 || report.ScannedBytes < 0) throw InvalidResponse();
            return report;
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException or ArgumentOutOfRangeException)
        { throw InvalidResponse(); }
    }
}
