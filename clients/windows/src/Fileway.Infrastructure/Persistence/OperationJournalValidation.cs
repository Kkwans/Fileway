using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Persistence;

/// <summary>Only typed business records cross the storage boundary; external error text never does.</summary>
internal static class OperationJournalValidation
{
    private static readonly JsonSerializerOptions Json = new()
    {
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
        MaxDepth = 32,
    };
    private static readonly UTF8Encoding StrictUtf8 = new(false, true);

    internal static void Account(AccountKey account)
    {
        ProfileStoreValidation.Validate(account);
        Text(account.ServerUserId, 1024, required: true);
    }

    internal static void Reference(OperationRef operation)
    {
        Account(operation.Account);
        if (operation.Id == Guid.Empty) throw Invalid();
    }

    internal static OperationIntent Intent(OperationIntent intent)
    {
        if (intent is null) throw Invalid();
        Reference(intent.Reference);
        if (intent.OriginSessionId == Guid.Empty || !Enum.IsDefined(intent.Kind)) throw Invalid();
        var items = Items(intent.Items, intent.Reference.Account);
        OperationOptions? options = intent.Options;
        if (options is not null)
        {
            Text(options.RecordId, 4096); Text(options.Name, 16384, allowControls: true); Text(options.GroupId, 4096); Text(options.Color, 256);
            Text(options.DisplayPath, 65536, allowControls: true);
            if (options.Conflict is { } conflict && !Enum.IsDefined(conflict) || options.BatchId == Guid.Empty) throw Invalid();
            if (options.RecordIds is { } ids)
            {
                if (ids.Count > 10000) throw Invalid();
                foreach (string id in ids) Text(id, 4096, required: true);
                options = options with { RecordIds = ids.ToArray() };
            }
        }
        return intent with { Items = items.OrderBy(item => item.Ordinal).ToArray(), Options = options };
    }

    internal static OperationAcknowledgement Ack(OperationAcknowledgement acknowledgement, OperationIntent intent)
    {
        AccountKey account = intent.Reference.Account;
        if (acknowledgement is null || !Enum.IsDefined(acknowledgement.Kind) || !Enum.IsDefined(acknowledgement.Outcome) ||
            acknowledgement.Outcome == OperationOutcome.OutcomeUnknown ||
            acknowledgement.Kind is OperationAckKind.TaskAccepted or OperationAckKind.RetryAccepted && acknowledgement.Task is null) throw Invalid();
        var expected = intent.Kind switch
        {
            OperationKind.CreateEmptyFile or OperationKind.CreateDirectory or OperationKind.Rename or OperationKind.Trash => OperationAckKind.FileWriteConfirmed,
            OperationKind.BatchRename => OperationAckKind.BatchRenameConfirmed,
            OperationKind.Copy or OperationKind.Move => OperationAckKind.TaskAccepted,
            OperationKind.Restore => OperationAckKind.TrashRestored,
            OperationKind.TaskCancel => OperationAckKind.CancellationRequested,
            OperationKind.TaskRetry => OperationAckKind.RetryAccepted,
            OperationKind.TaskArchive => OperationAckKind.Archived,
            OperationKind.TaskUnarchive => OperationAckKind.Unarchived,
            _ => OperationAckKind.MetadataConfirmed,
        };
        if (acknowledgement.Kind != expected) throw Invalid();
        Text(acknowledgement.RecordId, 4096); Text(acknowledgement.DisplayPath, 65536, allowControls: true);
        if (acknowledgement.Task is { } task) TaskReference(task, account);
        if (intent.Kind is OperationKind.TaskCancel or OperationKind.TaskRetry or OperationKind.TaskArchive or OperationKind.TaskUnarchive)
        {
            Text(intent.Options?.RecordId, 4096, required: true);
            if (acknowledgement.Task is not { } taskRef ||
                (intent.Kind == OperationKind.TaskRetry ? taskRef.Id == intent.Options!.RecordId : taskRef.Id != intent.Options!.RecordId)) throw Invalid();
        }
        var items = acknowledgement.Items is null ? null : Items(acknowledgement.Items, account);
        if (items?.Any(item => item.Outcome == FileOperationItemOutcome.OutcomeUnknown) == true ||
            acknowledgement.Outcome == OperationOutcome.Partial &&
                (items is null || items.Length == 0 || items.Any(item => item.Outcome is null))) throw Invalid();
        return acknowledgement with { Items = items };
    }

    internal static OperationReconciliation Reconciliation(OperationReconciliation reconciliation, OperationRecord current)
    {
        var operation = current.Intent.Reference;
        if (reconciliation is null || reconciliation.Reference != operation || !Enum.IsDefined(reconciliation.Resolution)) throw Invalid();
        if (reconciliation.Resolution == OperationResolution.NotSent && current.Phase is not (OperationPhase.Intent or OperationPhase.NotSent) ||
            reconciliation.Resolution == OperationResolution.Confirmed &&
                (current.Phase != OperationPhase.Acknowledged || current.Acknowledgement is null)) throw Invalid();
        ServerTask? task = reconciliation.Task;
        if (task is not null)
        {
            TaskReference(task.Reference, operation.Account);
            if (task.OwnerUserId != operation.Account.ServerUserId || !Enum.IsDefined(task.Status) ||
                task.TotalItems < 0 || task.ProcessedItems < 0 || task.TotalBytes < 0 || task.ProcessedBytes < 0) throw Invalid();
            Text(task.RawType, 4096, true); Text(task.RawStatus, 4096, true); Text(task.Title, 16384, true, allowControls: true);
            Text(task.RetryOf, 4096); Text(task.SourceDisplayPath, 65536, allowControls: true); Text(task.OutputDisplayPath, 65536, allowControls: true);
            task = task with { Error = SafeError(task.Error) };
        }
        return reconciliation with { Task = task, Error = SafeError(reconciliation.Error) };
    }

    internal static ErrorInfo? SafeError(ErrorInfo? error)
    {
        if (error is null) return null;
        if (!Enum.IsDefined(error.Code) || error.HttpStatus is < 100 or > 599) throw Invalid();
        // Message is intentionally reconstructed from a fixed allowlist, never an exception/server string.
        return new(error.Code, error.Code == ErrorCode.Canceled ? "操作已取消。" :
            error.Code == ErrorCode.OutcomeUnknown ? "操作结果尚未确认，请重新核实。" : "操作未完成，请查看状态后重试。",
            error.HttpStatus, error.Retryable);
    }

    internal static OperationRecord Transition(OperationRecord current, long expectedRevision, OperationPhase phase,
        OperationAcknowledgement? acknowledgement = null, ErrorInfo? error = null)
    {
        Revision(current, expectedRevision);
        if (!Enum.IsDefined(phase)) throw Invalid();
        if (current.Phase == OperationPhase.Acknowledged)
        {
            if (phase == OperationPhase.Acknowledged) return current;
            throw Conflict();
        }
        bool allowed = phase switch
        {
            OperationPhase.DispatchMarked => current.Phase == OperationPhase.Intent,
            // Only the current dispatch helper may prove CallAsync has never been entered after its marker.
            // Read-only reconciliation cannot manufacture that evidence.
            OperationPhase.NotSent => current.Phase is OperationPhase.Intent or OperationPhase.DispatchMarked,
            OperationPhase.Acknowledged => current.Phase is OperationPhase.DispatchMarked or OperationPhase.OutcomeUnknown,
            OperationPhase.Rejected => current.Phase == OperationPhase.DispatchMarked,
            OperationPhase.OutcomeUnknown => current.Phase is OperationPhase.DispatchMarked or OperationPhase.OutcomeUnknown,
            _ => false,
        };
        if (!allowed) throw Conflict();
        return current with
        {
            Phase = phase, Revision = checked(current.Revision + 1), UpdatedAt = DateTimeOffset.UtcNow,
            Acknowledgement = acknowledgement, Error = SafeError(error), Reconciliation = null,
        };
    }

    internal static void Revision(OperationRecord current, long expectedRevision)
    {
        if (expectedRevision < 0 || current.Revision != expectedRevision) throw Conflict();
    }

    internal static (long Ticks, Guid Id)? Cursor(string? cursor, AccountKey account, int limit)
    {
        Account(account);
        if (limit is < 1 or > 200) throw Invalid();
        if (cursor is null) return null;
        string[] values = cursor.Split(':');
        if (values.Length != 6 || !Guid.TryParseExact(values[0], "N", out var profile) ||
            !Guid.TryParseExact(values[1], "N", out var revision) ||
            !long.TryParse(values[3], NumberStyles.None, CultureInfo.InvariantCulture, out long ticks) ||
            !Guid.TryParseExact(values[4], "N", out var id) || values[5] != "1") throw Invalid();
        string user;
        try { user = StrictUtf8.GetString(Convert.FromHexString(values[2])); }
        catch (Exception error) when (error is FormatException or DecoderFallbackException) { throw Invalid(); }
        if (profile != account.ProfileId || revision != account.SourceRevision || user != account.ServerUserId ||
            ticks < DateTimeOffset.MinValue.UtcTicks || ticks > DateTimeOffset.MaxValue.UtcTicks || id == Guid.Empty) throw Invalid();
        return (ticks, id);
    }

    internal static string NextCursor(OperationRecord record) => string.Join(':',
        record.Intent.Reference.Account.ProfileId.ToString("N"), record.Intent.Reference.Account.SourceRevision.ToString("N"),
        Convert.ToHexString(StrictUtf8.GetBytes(record.Intent.Reference.Account.ServerUserId)),
        record.CreatedAt.UtcTicks.ToString(CultureInfo.InvariantCulture), record.Intent.Reference.Id.ToString("N"), "1");

    internal static string Encode<T>(T value) => JsonSerializer.Serialize(value, Json);
    internal static T Decode<T>(string value)
    {
        try { return JsonSerializer.Deserialize<T>(value, Json) ?? throw Invalid(); }
        catch (JsonException) { throw ProfileStoreValidation.Unavailable(); }
    }

    internal static OperationRecord Copy(OperationRecord record) => Decode<OperationRecord>(Encode(record));

    private static OperationItem[] Items(IReadOnlyList<OperationItem> items, AccountKey account)
    {
        if (items is null || items.Count > 10000) throw Invalid();
        var ordinals = new HashSet<int>();
        var copy = new List<OperationItem>(items.Count);
        foreach (var item in items)
        {
            if (item is null || item.Ordinal < 0 || !ordinals.Add(item.Ordinal) ||
                item.Conflict is { } conflict && !Enum.IsDefined(conflict) ||
                item.Outcome is { } outcome && !Enum.IsDefined(outcome)) throw Invalid();
            Snapshot(item.Source, account); Snapshot(item.Target, account);
            copy.Add(item with { Error = SafeError(item.Error) });
        }
        return copy.ToArray();
    }

    private static void Snapshot(OperationResourceSnapshot? snapshot, AccountKey account)
    {
        if (snapshot is null) return;
        if (snapshot.Resource is not { } resource || resource.Account != account || snapshot.Size < 0 ||
            resource.DisplayName is null || resource.DisplayPath is null) throw Invalid();
        Text(resource.DisplayName, 16384, allowControls: true); Text(resource.DisplayPath, 65536, allowControls: true); Text(resource.SourceVersion, 4096);
        string wire = resource.WirePath;
        if (string.IsNullOrEmpty(wire) || wire.Length > 65536 || wire[0] != '/' ||
            wire.Any(character => character is '?' or '#' or '\\' || char.IsControl(character))) throw Invalid();
        if (wire == "/") return;
        foreach (string segment in wire[1..].Split('/'))
        {
            if (segment.Length == 0) throw Invalid();
            byte[] encoded;
            try { encoded = StrictUtf8.GetBytes(segment); }
            catch (EncoderFallbackException) { throw Invalid(); }
            var decoded = new List<byte>();
            for (int index = 0; index < encoded.Length; index++)
            {
                byte value = encoded[index];
                if (value == '%')
                {
                    if (index + 2 >= encoded.Length || !char.IsAsciiHexDigit((char)encoded[index + 1]) || !char.IsAsciiHexDigit((char)encoded[index + 2])) throw Invalid();
                    value = Convert.ToByte(Encoding.ASCII.GetString(encoded, index + 1, 2), 16);
                    index += 2;
                }
                if (value == 0 || value == '/') throw Invalid();
                decoded.Add(value);
            }
            if (decoded.SequenceEqual(new byte[] { (byte)'.' }) || decoded.SequenceEqual(new byte[] { (byte)'.', (byte)'.' })) throw Invalid();
        }
    }

    private static void TaskReference(ServerTaskRef task, AccountKey account)
    {
        if (task.Account != account) throw Invalid();
        Text(task.Id, 4096, true);
    }

    private static void Text(string? value, int maximum, bool required = false, bool allowControls = false)
    {
        if (required && string.IsNullOrWhiteSpace(value) || value is not null &&
            (value.Length > maximum || value.Contains('\0') || !allowControls && value.Any(char.IsControl))) throw Invalid();
    }

    internal static FilewayException Invalid() => new(new(ErrorCode.InvalidRequest, "本地操作记录参数无效。"));
    internal static FilewayException Conflict() => new(new(ErrorCode.Conflict, "本地操作记录已更新，请重新读取。"));
    internal static FilewayException UnresolvedConflict() => new(new(ErrorCode.Conflict, "此连接还有待核实操作，请先核实，或新建独立连接"));
    internal static bool IsUnresolved(OperationRecord record) =>
        record.Phase is OperationPhase.Intent or OperationPhase.DispatchMarked or OperationPhase.OutcomeUnknown &&
        record.Reconciliation?.Resolution is not (OperationResolution.NotSent or OperationResolution.Confirmed);
}
