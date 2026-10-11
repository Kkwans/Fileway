using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices
{
    public async Task<IReadOnlyList<TrashItem>> ListTrashAsync(AccountKey account, CancellationToken cancellationToken)
    {
        var state = GetState(account);
        using var document = ParseBody(await RequestAsync(state, "/api/trash", cancellationToken).ConfigureAwait(false));
        var items = ParseOwnTrash(document.RootElement, account);
        EnsureActive(state);
        return items;
    }

    public async Task<TrashRestoreResult> RestoreTrashAsync(TrashItemRef item, FileConflictPolicy conflict, CancellationToken cancellationToken)
    {
        _ = ServiceRecordId(item.Id);
        string strategy = FileConflictWire(conflict);
        var state = GetState(item.Account);
        bool? skipped = null;
        string? displayPath = null;
        string? originalDisplayPath = null;
        var journal = PrepareJournalContext(state, OperationKind.Restore, [], response =>
        {
            RequireExactStatus(response, 200);
            using var document = ParseBody(response);
            string path = RequiredText(document.RootElement.GetProperty("path"));
            bool wasSkipped = document.RootElement.GetProperty("skipped").GetBoolean();
            if (!path.StartsWith('/') || path.Contains('\0') || wasSkipped && conflict != FileConflictPolicy.Skip)
                throw InvalidResponse();
            skipped = wasSkipped;
            displayPath = path;
            return new OperationAcknowledgement(OperationAckKind.TrashRestored, RecordId: item.Id,
                Skipped: wasSkipped, DisplayPath: path);
        }, new OperationOptions(RecordId: item.Id, Conflict: conflict), captureOptions: () =>
            new OperationOptions(RecordId: item.Id, Conflict: conflict, DisplayPath: originalDisplayPath));
        try
        {
            _ = await SessionRequestAsync(state, new
            {
                method = "POST", endpoint = "/api/trash/" + Uri.EscapeDataString(item.Id) + "/restore", body = new { conflict = strategy },
            }, requireDownload: false, HostCallSafety.Mutation, cancellationToken, beforeSend: async token =>
            {
                RequireOwnTrashRestore(state, conflict);
                using var document = ParseBody(await RequestAsync(state.CoreSession, "/api/trash", token).ConfigureAwait(false));
                var own = ParseOwnTrash(document.RootElement, item.Account);
                var selected = own.SingleOrDefault(candidate => candidate.Reference == item);
                if (selected is null) throw Safe(ErrorCode.PermissionDenied, "回收站项目不属于当前账户或已不可用。", 403);
                originalDisplayPath = selected.DisplayPath;
                if (selected.Status is not ("available" or "failed"))
                    throw Safe(ErrorCode.Conflict, "回收站项目当前不可恢复，请刷新后重试。", 409);
            }, authorizeSend: () => RequireOwnTrashRestore(state, conflict), journal: journal).ConfigureAwait(false);
            // No opaque resource is invented from this display-only response.
            // Fresh directory/collection reads observe the server's restoration.
            return new TrashRestoreResult(OperationOutcome.Confirmed, skipped, displayPath, journal.Receipt);
        }
        catch (FilewayException error)
        {
            bool unknown = IsUncertainJournalFailure(error, journal);
            return new TrashRestoreResult(unknown ? OperationOutcome.OutcomeUnknown : OperationOutcome.Partial,
                Receipt: journal.Receipt, Error: unknown ? UnknownMutationError(error.Error.HttpStatus) : error.Error);
        }
    }

    private void RequireOwnTrashRestore(SessionState state, FileConflictPolicy conflict)
    {
        EnsureActive(state);
        if (!state.Session.Permissions.Create || conflict == FileConflictPolicy.Replace && !state.Session.Permissions.Delete)
            throw Safe(ErrorCode.PermissionDenied, Message(ErrorCode.PermissionDenied));
    }

    private static List<TrashItem> ParseOwnTrash(JsonElement value, AccountKey account)
    {
        try
        {
            var items = new List<TrashItem>();
            var ids = new HashSet<string>(StringComparer.Ordinal);
            foreach (var row in value.EnumerateArray())
            {
                string owner = ServiceOwner(row);
                if (owner != account.ServerUserId) continue; // Administrators also receive other users' rows.
                string id = ServiceRecordId(RequiredText(row.GetProperty("id")));
                if (!ids.Add(id)) throw InvalidResponse();
                bool directory = row.GetProperty("isDir").GetBoolean();
                long rawSize = row.GetProperty("size").GetInt64();
                if (rawSize < 0) throw InvalidResponse();
                string? sizeState = OptionalText(row, "sizeState");
                long? size = sizeState == "accurate" || sizeState is null && !directory ? rawSize : null;
                items.Add(new TrashItem(new TrashItemRef(account, id), owner, RequiredText(row.GetProperty("originalPath")),
                    RequiredText(row.GetProperty("status")), directory, size, sizeState,
                    ServiceOptionalDate(row, "deletedAt"), ServiceOptionalDate(row, "undoUntil")));
            }
            return items;
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException or ArgumentOutOfRangeException)
        { throw InvalidResponse(); }
    }

    private static string FileConflictWire(FileConflictPolicy conflict) => conflict switch
    {
        FileConflictPolicy.Fail => "fail", FileConflictPolicy.KeepBoth => "keep-both",
        FileConflictPolicy.Replace => "replace", FileConflictPolicy.Skip => "skip",
        _ => throw Safe(ErrorCode.InvalidRequest, "文件冲突策略无效。"),
    };
}
