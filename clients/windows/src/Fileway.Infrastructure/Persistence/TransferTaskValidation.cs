using System.Buffers;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Persistence;

/// <summary>Pure metadata validation; no transfer body or native filesystem operation belongs here.</summary>
internal static class TransferTaskValidation
{
    internal const int MaximumPayloadBytes = 1_048_576;
    private static readonly UTF8Encoding StrictUtf8 = new(false, true);
    private static readonly SearchValues<char> InvalidFileCharacters = SearchValues.Create("<>:\"|?*");
    private static readonly JsonSerializerOptions Json = new()
    {
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
        RespectRequiredConstructorParameters = true,
        RespectNullableAnnotations = true,
        MaxDepth = 16,
    };

    internal static void Account(AccountKey account)
    {
        if (account.ProfileId == Guid.Empty || account.SourceRevision == Guid.Empty) throw Invalid();
        Text(account.ServerUserId, 1024, required: true);
    }

    internal static void Reference(TransferTaskRef task)
    {
        Account(task.Account);
        if (task.Id == Guid.Empty) throw Invalid();
    }

    internal static TransferPlan Plan(TransferPlan plan)
    {
        if (plan is null) throw Invalid();
        Reference(plan.Reference);
        if (!Enum.IsDefined(plan.Direction) || !Enum.IsDefined(plan.Conflict) || plan.Remote is null ||
            plan.Remote.Account != plan.Reference.Account || plan.BatchId == Guid.Empty || plan.Ordinal < 0) throw Invalid();
        Text(plan.Remote.DisplayName, 16384, allowControls: true, required: true);
        Text(plan.Remote.DisplayPath, 65536, allowControls: true, required: true);
        Text(plan.Remote.SourceVersion, 4096);
        _ = WireIdentity(plan.Remote.WirePath);
        _ = LocalFilePath(plan.LocalPath);
        return plan;
    }

    internal static void RouteIdentity(SavedConnection previous, SavedConnection replacement)
    {
        if (previous.Profile.SourceRevision == replacement.Profile.SourceRevision &&
            (!StringComparer.Ordinal.Equals(previous.Profile.BaseAddress.AbsoluteUri, replacement.Profile.BaseAddress.AbsoluteUri) ||
                previous.Profile.Network != replacement.Profile.Network || previous.Profile.Proxy != replacement.Profile.Proxy))
            throw RouteChanged();
    }

    internal static string RouteKey(ConnectionProfile profile)
    {
        using var bytes = new MemoryStream();
        using (var writer = new BinaryWriter(bytes, StrictUtf8, leaveOpen: true))
        {
            // Versioned UTF-8 strings have BinaryWriter's 7-bit byte-length prefix; integers are little-endian Int32.
            // Length prefixes keep every field unambiguous, including an absent manual proxy address.
            writer.Write("FilewayTransferRoute/v1");
            writer.Write(profile.BaseAddress.AbsoluteUri);
            writer.Write((int)profile.Network);
            writer.Write((int)profile.Proxy.Mode);
            writer.Write(profile.Proxy.ManualAddress?.AbsoluteUri ?? "");
        }
        return Convert.ToHexStringLower(SHA256.HashData(bytes.ToArray()));
    }

    internal static bool IsRouteKey(string value) => value.Length == 64 && value.All(character => character is >= '0' and <= '9' or >= 'a' and <= 'f');

    internal static TransferTaskRecord Create(TransferPlan plan)
    {
        Plan(plan);
        var now = DateTimeOffset.UtcNow;
        return new(plan, TransferIntent.Run, new(TransferPhase.Queued), 0, 0, null, now, now);
    }

    internal static void ExpectedRevision(long expectedRevision)
    {
        if (expectedRevision < 0) throw Conflict();
    }

    internal static void Revision(TransferTaskRecord current, long expectedRevision)
    {
        ExpectedRevision(expectedRevision);
        if (current.Revision != expectedRevision) throw Conflict();
    }

    internal static TransferTaskRecord Intent(TransferTaskRecord current, long expectedRevision, TransferIntent intent)
    {
        Revision(current, expectedRevision);
        if (!Enum.IsDefined(intent)) throw Invalid();
        if (IsTerminal(current.Checkpoint.Phase)) return current;
        if (current.Intent == TransferIntent.Cancel && intent != TransferIntent.Cancel) throw Conflict();
        return current with { Intent = intent, Revision = checked(current.Revision + 1), UpdatedAt = Now(current) };
    }

    internal static bool IsUnstartedDownload(TransferTaskRecord record) => record.Plan.Direction == TransferDirection.Download &&
        record.Generation == 0 && record.Run is null && record.Checkpoint == new TransferCheckpoint(TransferPhase.Queued);

    internal static TransferTaskRecord CancelUnstartedDownload(TransferTaskRecord current, long expectedRevision, Guid cancellationRunId, Guid? lastRunId)
    {
        Revision(current, expectedRevision);
        if (!IsUnstartedDownload(current) || current.Intent != TransferIntent.Cancel || lastRunId is not null) throw Conflict();
        // A real, task-locked local cancellation owns this control generation. Neither
        // the transient claim nor its budget reservation is published as an active writer.
        var claimed = Claim(current, expectedRevision, cancellationRunId, lastRunId, recovery: true);
        return Progress(claimed, claimed.Run!.Value, claimed.Revision, new(TransferPhase.Canceled), releaseRun: true);
    }

    internal static TransferTaskRecord Claim(TransferTaskRecord current, long expectedRevision, Guid runId,
        Guid? lastRunId, bool recovery)
    {
        Revision(current, expectedRevision);
        if (runId == Guid.Empty) throw Invalid();
        if (runId == lastRunId || IsTerminal(current.Checkpoint.Phase)) throw Conflict();
        bool normalPhase = IsNormalClaimPhase(current.Checkpoint.Phase);
        bool eligible = recovery
            ? current.Run is not null || current.Checkpoint.Uncertainty != TransferUncertainty.None ||
                current.Checkpoint.Phase is TransferPhase.Reconciling or TransferPhase.OutcomeUnknown || normalPhase ||
                current.Intent == TransferIntent.Cancel && current.Checkpoint.Phase is TransferPhase.Conflict or TransferPhase.SourceChanged
            : current.Intent == TransferIntent.Run && current.Run is null &&
                current.Checkpoint.Uncertainty == TransferUncertainty.None && normalPhase;
        if (!eligible) throw Conflict();
        long generation = checked(current.Generation + 1);
        return current with
        {
            Revision = checked(current.Revision + 1), Generation = generation,
            Run = new(current.Plan.Reference, runId, generation), UpdatedAt = Now(current),
            Checkpoint = current.Checkpoint with { Phase = recovery ? TransferPhase.Reconciling : TransferPhase.Preparing },
        };
    }

    internal static TransferTaskRecord Progress(TransferTaskRecord current, TransferRunToken run, long expectedRevision,
        TransferCheckpoint checkpoint, bool releaseRun)
    {
        Revision(current, expectedRevision);
        Reference(run.Task);
        if (run.Id == Guid.Empty || run.Generation <= 0) throw Invalid();
        if (current.Run != run || current.Generation != run.Generation) throw Conflict();
        var next = Checkpoint(current.Plan, checkpoint);
        if (!AllowedPhase(current.Checkpoint.Phase, next.Phase, current.Plan.Direction) ||
            releaseRun != IsReleased(next.Phase) || !releaseRun && !IsActive(next.Phase)) throw Conflict();
        if (next.Phase is TransferPhase.CancelRequested or TransferPhase.Canceled && current.Intent != TransferIntent.Cancel)
            throw Conflict();
        if (next.Phase == TransferPhase.Skipped && (current.Plan.Conflict != FileConflictPolicy.Skip ||
            Published(current.Checkpoint) || Published(next) || current.Checkpoint.Uncertainty != TransferUncertainty.None ||
            next.Uncertainty != TransferUncertainty.None)) throw Conflict();
        PreserveEvidence(current, next);
        return current with
        {
            Checkpoint = next, Run = releaseRun ? null : run,
            Revision = checked(current.Revision + 1), UpdatedAt = Now(current),
        };
    }

    internal static bool IsProtected(TransferTaskRecord record) => record.Run is not null || record.Checkpoint.Uncertainty != TransferUncertainty.None;

    internal static string TargetKey(TransferPlan plan) => plan.Direction == TransferDirection.Download
        ? "D:" + LocalFilePath(plan.LocalPath).ToUpperInvariant()
        : string.Join(':', "U", plan.Reference.Account.ProfileId.ToString("N"), plan.Reference.Account.SourceRevision.ToString("N"),
            Convert.ToHexString(StrictUtf8.GetBytes(plan.Reference.Account.ServerUserId)), WireIdentity(plan.Remote.WirePath));

    internal static (long Ticks, Guid Id)? Cursor(string? cursor, AccountKey account, int limit)
    {
        Account(account);
        if (limit is < 1 or > 200) throw Invalid();
        if (cursor is null) return null;
        if (cursor.Length > 8192) throw Invalid();
        string[] values = cursor.Split(':');
        if (values.Length != 7 || values[0] != "t1" || !Guid.TryParseExact(values[1], "N", out var profile) ||
            !Guid.TryParseExact(values[2], "N", out var revision) ||
            !long.TryParse(values[4], NumberStyles.None, CultureInfo.InvariantCulture, out long ticks) ||
            !Guid.TryParseExact(values[5], "N", out var id) || values[6] != "1") throw Invalid();
        string user;
        try { user = StrictUtf8.GetString(Convert.FromHexString(values[3])); }
        catch (Exception error) when (error is FormatException or DecoderFallbackException) { throw Invalid(); }
        if (profile != account.ProfileId || revision != account.SourceRevision || user != account.ServerUserId ||
            ticks < DateTimeOffset.MinValue.UtcTicks || ticks > DateTimeOffset.MaxValue.UtcTicks || id == Guid.Empty) throw Invalid();
        return (ticks, id);
    }

    internal static string NextCursor(TransferTaskRecord record) => string.Join(':', "t1",
        record.Plan.Reference.Account.ProfileId.ToString("N"), record.Plan.Reference.Account.SourceRevision.ToString("N"),
        Convert.ToHexString(StrictUtf8.GetBytes(record.Plan.Reference.Account.ServerUserId)),
        record.CreatedAt.UtcTicks.ToString(CultureInfo.InvariantCulture), record.Plan.Reference.Id.ToString("N"), "1");

    internal static string Encode(TransferTaskRecord record)
    {
        string value = JsonSerializer.Serialize(record, Json);
        if (StrictUtf8.GetByteCount(value) > MaximumPayloadBytes) throw Invalid();
        return value;
    }

    internal static TransferTaskRecord Decode(string value, Guid? lastRunId)
    {
        try
        {
            if (value.Length > MaximumPayloadBytes || StrictUtf8.GetByteCount(value) > MaximumPayloadBytes) throw Invalid();
            using var document = JsonDocument.Parse(value, new JsonDocumentOptions { MaxDepth = 16 });
            if (document.RootElement.ValueKind != JsonValueKind.Object) throw Invalid();
            UniqueJsonMembers(document.RootElement);
            var record = JsonSerializer.Deserialize<TransferTaskRecord>(value, Json) ?? throw Invalid();
            Plan(record.Plan);
            if (record.Checkpoint is null || !Enum.IsDefined(record.Intent) || record.Revision < 0 || record.Generation < 0 ||
                record.Revision < record.Generation || record.CreatedAt.Offset != TimeSpan.Zero || record.UpdatedAt.Offset != TimeSpan.Zero ||
                record.UpdatedAt < record.CreatedAt || (lastRunId is null) != (record.Generation == 0) || lastRunId == Guid.Empty)
                throw Invalid();
            var checkpoint = Checkpoint(record.Plan, record.Checkpoint);
            if (checkpoint != record.Checkpoint || (record.Run is not null) != IsActive(checkpoint.Phase) ||
                record.Run is { } run && (run.Task != record.Plan.Reference || run.Id != lastRunId || run.Generation != record.Generation || run.Generation <= 0) ||
                (record.Generation == 0) != (checkpoint.Phase == TransferPhase.Queued) ||
                checkpoint.Source?.Modified is { } modified && modified.Offset != TimeSpan.Zero ||
                checkpoint.Phase is TransferPhase.CancelRequested or TransferPhase.Canceled && record.Intent != TransferIntent.Cancel ||
                checkpoint.Phase == TransferPhase.Skipped && record.Plan.Conflict != FileConflictPolicy.Skip) throw Invalid();
            return record;
        }
        catch (Exception error) when (error is JsonException or FilewayException or ArgumentException or InvalidOperationException or
            FormatException or OverflowException)
        { throw ProfileStoreValidation.Unavailable(); }
    }

    private static TransferCheckpoint Checkpoint(TransferPlan plan, TransferCheckpoint checkpoint)
    {
        if (checkpoint is null || !Enum.IsDefined(checkpoint.Phase) || (checkpoint.Uncertainty & ~(TransferUncertainty.RemoteWrite | TransferUncertainty.LocalPublish)) != 0 ||
            checkpoint.RemotePublished && checkpoint.RemoteCanceled ||
            plan.Direction == TransferDirection.Upload && (checkpoint.Files is not null || (checkpoint.Uncertainty & TransferUncertainty.LocalPublish) != 0) ||
            plan.Direction == TransferDirection.Download && (checkpoint.RemotePublished || checkpoint.RemoteCanceled || (checkpoint.Uncertainty & TransferUncertainty.RemoteWrite) != 0))
            throw Invalid();
        TransferSourceSnapshot? source = checkpoint.Source;
        if (source is not null)
        {
            if (source.Length < 0 || plan.Direction == TransferDirection.Upload && source.SourceVersion is not null) throw Invalid();
            Text(source.SourceVersion, 4096); Text(source.NativeFileIdentity, 4096);
            source = source with { Modified = source.Modified?.ToUniversalTime() };
        }
        if (checkpoint.ConfirmedOffset is { } offset && (source is null || offset < 0 || offset > source.Length)) throw Invalid();
        if (checkpoint.Phase is TransferPhase.Running or TransferPhase.Verifying or TransferPhase.Publishing or TransferPhase.Completed)
        {
            if (source is null || checkpoint.ConfirmedOffset is null ||
                plan.Direction == TransferDirection.Upload && source.NativeFileIdentity is null) throw Invalid();
            if (checkpoint.Phase != TransferPhase.Running && checkpoint.ConfirmedOffset != source.Length) throw Invalid();
        }
        ValidateFiles(plan, checkpoint.Files);
        string? localHash = Hash(checkpoint.LocalSha256), serverHash = Hash(checkpoint.ServerSha256);
        if (checkpoint.Phase == TransferPhase.Queued && (source is not null || checkpoint.ConfirmedOffset is not null || checkpoint.Files is not null ||
            localHash is not null || serverHash is not null || checkpoint.RemotePublished || checkpoint.RemoteCanceled ||
            checkpoint.Uncertainty != TransferUncertainty.None || checkpoint.Error is not null)) throw Invalid();
        if (checkpoint.Phase == TransferPhase.OutcomeUnknown && checkpoint.Uncertainty == TransferUncertainty.None) throw Invalid();
        if (checkpoint.Phase is TransferPhase.Completed or TransferPhase.Canceled or TransferPhase.Skipped &&
            checkpoint.Uncertainty != TransferUncertainty.None) throw Invalid();
        if (checkpoint.Phase == TransferPhase.Completed && (
            plan.Direction == TransferDirection.Upload && (!checkpoint.RemotePublished || checkpoint.RemoteCanceled) ||
            plan.Direction == TransferDirection.Download && (localHash is null || localHash != serverHash || checkpoint.Files?.PublishedFileIdentity is null))) throw Invalid();
        if (checkpoint.Phase is TransferPhase.Canceled or TransferPhase.Skipped && Published(checkpoint)) throw Invalid();
        return checkpoint with { Source = source, LocalSha256 = localHash, ServerSha256 = serverHash, Error = OperationJournalValidation.SafeError(checkpoint.Error) };
    }

    private static void PreserveEvidence(TransferTaskRecord current, TransferCheckpoint next)
    {
        var previous = current.Checkpoint;
        if (previous.Source is not null && previous.Source != next.Source ||
            previous.ConfirmedOffset is not null && (next.ConfirmedOffset is null || next.ConfirmedOffset < previous.ConfirmedOffset && previous.Phase != TransferPhase.Reconciling) ||
            previous.RemotePublished && !next.RemotePublished || previous.RemoteCanceled && !next.RemoteCanceled) throw Conflict();
        Pin(previous.LocalSha256, next.LocalSha256); Pin(previous.ServerSha256, next.ServerSha256);
        if (previous.Files is not { } files) return;
        var newFiles = next.Files;
        if (newFiles is null)
        {
            if (files != new TransferWorkFiles()) throw Conflict();
            return;
        }
        bool partialChanged = files.PartialPath is not null && files.PartialPath != newFiles.PartialPath ||
            files.PartialIdentity is not null && files.PartialIdentity != newFiles.PartialIdentity;
        if (partialChanged)
        {
            if (previous.Phase != TransferPhase.Reconciling || next.ConfirmedOffset != 0 || next.Source != previous.Source ||
                newFiles.PartialPath is null || (previous.Uncertainty & TransferUncertainty.LocalPublish) != 0 ||
                (next.Uncertainty & TransferUncertainty.LocalPublish) != 0 || files.BackupPath is not null || newFiles.BackupPath is not null ||
                files.BackupIdentity is not null || newFiles.BackupIdentity is not null || files.PublishedFileIdentity is not null || newFiles.PublishedFileIdentity is not null)
                throw Conflict();
        }
        Pin(files.OriginalTargetIdentity, newFiles.OriginalTargetIdentity);
        Pin(files.BackupPath, newFiles.BackupPath); Pin(files.BackupIdentity, newFiles.BackupIdentity);
        Pin(files.PublishedFileIdentity, newFiles.PublishedFileIdentity);
    }

    private static void ValidateFiles(TransferPlan plan, TransferWorkFiles? files)
    {
        if (files is null) return;
        if (plan.Direction != TransferDirection.Download) throw Invalid();
        Text(files.PartialIdentity, 4096); Text(files.OriginalTargetIdentity, 4096);
        Text(files.BackupIdentity, 4096); Text(files.PublishedFileIdentity, 4096);
        if (files.PartialIdentity is not null && files.PartialPath is null || files.BackupIdentity is not null && files.BackupPath is null) throw Invalid();
        string final = LocalFilePath(plan.LocalPath);
        string? partial = files.PartialPath is null ? null : LocalFilePath(files.PartialPath);
        string? backup = files.BackupPath is null ? null : LocalFilePath(files.BackupPath);
        string parent = final[..final.LastIndexOf('\\')];
        if (partial is not null && (StringComparer.OrdinalIgnoreCase.Equals(partial, final) ||
                !StringComparer.OrdinalIgnoreCase.Equals(parent, partial[..partial.LastIndexOf('\\')])) ||
            backup is not null && (StringComparer.OrdinalIgnoreCase.Equals(backup, final) || StringComparer.OrdinalIgnoreCase.Equals(backup, partial) ||
                !StringComparer.OrdinalIgnoreCase.Equals(parent, backup[..backup.LastIndexOf('\\')]))) throw Invalid();
    }

    private static void Pin(string? previous, string? next)
    {
        if (previous is not null && previous != next) throw Conflict();
    }

    private static string? Hash(string? value)
    {
        if (value is null) return null;
        if (value.Length != 64 || value.Any(character => !char.IsAsciiHexDigit(character))) throw Invalid();
        return value.ToLowerInvariant();
    }

    // Lexical Win32 paths only: this makes no claim about reparses, hardlinks or native handle ownership.
    internal static string LocalFilePath(string value)
    {
        Text(value, 32767, required: true);
        string normalized = value.Replace('/', '\\');
        int start;
        bool unc = normalized.StartsWith("\\\\", StringComparison.Ordinal);
        if (unc) start = 2;
        else if (normalized.Length >= 4 && char.IsAsciiLetter(normalized[0]) && normalized[1] == ':' && normalized[2] == '\\') start = 3;
        else throw Invalid();
        string[] components = normalized[start..].Split('\\');
        if (unc && components.Length < 3) throw Invalid();
        foreach (string component in components)
        {
            if (component.Length is 0 or > 255 || component is "." or ".." || component.EndsWith('.') || component.EndsWith(' ') ||
                component.AsSpan().ContainsAny(InvalidFileCharacters) || Reserved(component)) throw Invalid();
        }
        return unc ? normalized : char.ToUpperInvariant(normalized[0]) + normalized[1..];
    }

    private static bool Reserved(string component)
    {
        string name = component.Split('.')[0];
        if (name.Equals("CON", StringComparison.OrdinalIgnoreCase) || name.Equals("PRN", StringComparison.OrdinalIgnoreCase) ||
            name.Equals("AUX", StringComparison.OrdinalIgnoreCase) || name.Equals("NUL", StringComparison.OrdinalIgnoreCase) ||
            name.Equals("CONIN$", StringComparison.OrdinalIgnoreCase) || name.Equals("CONOUT$", StringComparison.OrdinalIgnoreCase)) return true;
        return name.Length == 4 && (name.StartsWith("COM", StringComparison.OrdinalIgnoreCase) || name.StartsWith("LPT", StringComparison.OrdinalIgnoreCase)) &&
            (name[3] is >= '1' and <= '9' or '¹' or '²' or '³');
    }

    private static string WireIdentity(string value)
    {
        Text(value, 65536, required: true);
        if (value.Length < 2 || value[0] != '/' || value.Any(character => character is '?' or '#' or '\\') ||
            value.EndsWith('/') || value.Contains("//", StringComparison.Ordinal)) throw Invalid();
        using var identity = new MemoryStream();
        foreach (string segment in value[1..].Split('/'))
        {
            byte[] encoded = StrictUtf8.GetBytes(segment);
            byte[] decoded = new byte[encoded.Length];
            int count = 0;
            for (int index = 0; index < encoded.Length; index++)
            {
                byte item = encoded[index];
                if (item == '%')
                {
                    if (index + 2 >= encoded.Length || !char.IsAsciiHexDigit((char)encoded[index + 1]) || !char.IsAsciiHexDigit((char)encoded[index + 2])) throw Invalid();
                    item = (byte)((Hex(encoded[index + 1]) << 4) | Hex(encoded[index + 2]));
                    index += 2;
                }
                if (item == 0 || item == '/') throw Invalid();
                decoded[count++] = item;
            }
            if (count == 1 && decoded[0] == '.' || count == 2 && decoded[0] == '.' && decoded[1] == '.') throw Invalid();
            identity.WriteByte((byte)'/');
            identity.Write(decoded, 0, count);
        }
        return Convert.ToHexString(identity.ToArray());
    }

    private static int Hex(byte value) => value is >= (byte)'0' and <= (byte)'9' ? value - '0' :
        value is >= (byte)'A' and <= (byte)'F' ? value - 'A' + 10 : value - 'a' + 10;

    private static void Text(string? value, int maximum, bool required = false, bool allowControls = false)
    {
        if (required && string.IsNullOrWhiteSpace(value) || value is not null &&
            (value.Length > maximum || value.Contains('\0') || !allowControls && (string.IsNullOrWhiteSpace(value) || value.Any(char.IsControl)))) throw Invalid();
        if (value is not null)
        {
            try { _ = StrictUtf8.GetByteCount(value); }
            catch (EncoderFallbackException) { throw Invalid(); }
        }
    }

    private static void UniqueJsonMembers(JsonElement element)
    {
        if (element.ValueKind == JsonValueKind.Object)
        {
            var names = new HashSet<string>(StringComparer.Ordinal);
            foreach (var property in element.EnumerateObject())
            {
                if (!names.Add(property.Name)) throw Invalid();
                UniqueJsonMembers(property.Value);
            }
        }
        else if (element.ValueKind == JsonValueKind.Array)
            foreach (var item in element.EnumerateArray()) UniqueJsonMembers(item);
    }

    private static DateTimeOffset Now(TransferTaskRecord current)
    {
        var now = DateTimeOffset.UtcNow;
        return now > current.UpdatedAt ? now : current.UpdatedAt;
    }
    private static bool Published(TransferCheckpoint checkpoint) => checkpoint.RemotePublished || checkpoint.Files?.PublishedFileIdentity is not null;
    private static bool IsNormalClaimPhase(TransferPhase phase) => phase is TransferPhase.Queued or TransferPhase.Paused or
        TransferPhase.WaitingNetwork or TransferPhase.WaitingAuthentication or TransferPhase.Failed;
    private static bool IsTerminal(TransferPhase phase) => phase is TransferPhase.Completed or TransferPhase.Canceled or TransferPhase.Skipped;
    private static bool IsActive(TransferPhase phase) => phase is TransferPhase.Preparing or TransferPhase.Running or TransferPhase.Verifying or
        TransferPhase.Publishing or TransferPhase.Reconciling or TransferPhase.CancelRequested;
    private static bool IsReleased(TransferPhase phase) => phase is TransferPhase.Paused or TransferPhase.WaitingNetwork or TransferPhase.WaitingAuthentication or
        TransferPhase.Conflict or TransferPhase.SourceChanged or TransferPhase.Completed or TransferPhase.Canceled or TransferPhase.Failed or TransferPhase.OutcomeUnknown or TransferPhase.Skipped;

    private static bool AllowedPhase(TransferPhase current, TransferPhase next, TransferDirection direction) => current switch
    {
        TransferPhase.Preparing => next is TransferPhase.Preparing or TransferPhase.Running or TransferPhase.Verifying or TransferPhase.Paused or
            TransferPhase.WaitingNetwork or TransferPhase.WaitingAuthentication or TransferPhase.Conflict or TransferPhase.SourceChanged or TransferPhase.Failed or
            TransferPhase.Reconciling or TransferPhase.CancelRequested or TransferPhase.Canceled or TransferPhase.Skipped or TransferPhase.OutcomeUnknown,
        TransferPhase.Running or TransferPhase.Verifying => next is TransferPhase.Verifying or TransferPhase.Publishing or TransferPhase.Paused or
            TransferPhase.WaitingNetwork or TransferPhase.WaitingAuthentication or TransferPhase.Conflict or TransferPhase.SourceChanged or TransferPhase.Failed or
            TransferPhase.Reconciling or TransferPhase.CancelRequested or TransferPhase.Canceled or TransferPhase.OutcomeUnknown ||
            current == TransferPhase.Running && next == TransferPhase.Running || direction == TransferDirection.Upload && next == TransferPhase.Completed,
        TransferPhase.Publishing => next is TransferPhase.Publishing or TransferPhase.Completed or TransferPhase.Conflict or TransferPhase.Failed or TransferPhase.Reconciling or TransferPhase.OutcomeUnknown,
        TransferPhase.CancelRequested => next is TransferPhase.CancelRequested or TransferPhase.Canceled or TransferPhase.Completed or TransferPhase.Failed or
            TransferPhase.Reconciling or TransferPhase.OutcomeUnknown or TransferPhase.WaitingNetwork or TransferPhase.WaitingAuthentication,
        TransferPhase.Reconciling => next is TransferPhase.Reconciling or TransferPhase.Preparing or TransferPhase.Running or TransferPhase.Verifying or TransferPhase.Publishing or
            TransferPhase.Paused or TransferPhase.WaitingNetwork or TransferPhase.WaitingAuthentication or TransferPhase.Conflict or TransferPhase.SourceChanged or
            TransferPhase.Failed or TransferPhase.CancelRequested or TransferPhase.Completed or TransferPhase.Canceled or TransferPhase.OutcomeUnknown or TransferPhase.Skipped,
        _ => false,
    };

    internal static FilewayException Invalid() => new(new(ErrorCode.InvalidRequest, "本地传输记录参数无效。"));
    internal static FilewayException Conflict() => new(new(ErrorCode.Conflict, "本地传输记录已更新或当前状态不允许此操作，请重新读取。"));
    internal static FilewayException ProtectedConflict() => new(new(ErrorCode.Conflict, "此连接还有正在执行或待核实的传输，请先停止并核实。"));
    internal static FilewayException RouteChanged() => new(new(ErrorCode.SourceChanged, "此连接来源已改变，请创建新的来源版本。"));
    internal static FilewayException Capacity() => new(new(ErrorCode.Busy, "正在执行的传输已达上限，请稍后重试。"));
}
