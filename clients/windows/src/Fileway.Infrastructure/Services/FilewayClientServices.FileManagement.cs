using System.Collections.Concurrent;
using System.Text;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices
{
    private readonly ConcurrentDictionary<Guid, FrozenRename> _batchRenamePreviews = new();
    private readonly ConcurrentDictionary<(AccountKey Account, Guid Session, Guid Draft), Guid> _batchRenameDrafts = new();
    private readonly object _batchRenameGate = new();

    public async Task<FileOperationResult> CreateEmptyFileAsync(RemoteResourceRef parent, string name, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(parent);
        string normalized = FileOperationName(name);
        _ = FileOperationWireIdentity(parent.WirePath, allowRoot: true);
        var state = GetResourceState(parent);
        var target = FileManagementChild(parent, Uri.EscapeDataString(normalized), normalized);
        return await FileWriteAsync(state, target, "POST", "/api/resources" + target.WirePath + "?override=false",
            permissions => permissions.Create, async token =>
            {
                var directory = await FileMetadataAsync(state, parent, false, token).ConfigureAwait(false);
                if (directory?.IsDirectory != true) throw Safe(ErrorCode.InvalidRequest, "父目录已不可用，请刷新后重试。");
                if (await FileMetadataAsync(state, target, true, token).ConfigureAwait(false) is not null)
                    throw Safe(ErrorCode.Conflict, "同名项目已存在，请修改名称。", 409);
            }, ConfirmSynchronousFileWrite, OperationKind.CreateEmptyFile,
            [new OperationItem(0, Target: new OperationResourceSnapshot(target))], cancellationToken, new OperationOptions(Name: normalized)).ConfigureAwait(false);
    }

    public async Task<IReadOnlyList<ResourceMetadataResult>> GetMetadataBatchAsync(IReadOnlyList<RemoteResourceRef> resources,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(resources);
        var snapshot = resources.ToArray();
        if (snapshot.Length == 0) return [];
        var state = FileManagementState(snapshot);
        var rows = await ReadMetadataBatchesAsync(state, snapshot, cancellationToken).ConfigureAwait(false);
        EnsureActive(state);
        return rows.Select(row => new ResourceMetadataResult(row.Requested, row.File, row.Error)).ToArray();
    }

    private sealed record DetailedMetadata(RemoteResourceRef Requested, RemoteFile? File, bool? IsSymlink,
        string? Modified, ErrorInfo? Error);

    private async Task<IReadOnlyList<DetailedMetadata>> ReadMetadataBatchesAsync(SessionState state,
        RemoteResourceRef[] resources, CancellationToken cancellationToken, bool gateOwned = false)
    {
        var results = new List<DetailedMetadata>(resources.Length);
        for (int start = 0; start < resources.Length;)
        {
            var chunk = new List<RemoteResourceRef>();
            int bytes = 32;
            while (start + chunk.Count < resources.Length && chunk.Count < 500)
            {
                var resource = resources[start + chunk.Count];
                int next = JsonSerializer.SerializeToUtf8Bytes(resource.WirePath).Length + 1;
                if (bytes + next > 768 * 1024)
                {
                    if (chunk.Count == 0) throw Safe(ErrorCode.ResponseTooLarge, "项目路径过长，无法批量读取。");
                    break;
                }
                chunk.Add(resource);
                bytes += next;
            }
            object parameters = new { method = "POST", endpoint = "/api/resources/batch", body = new { wirePaths = chunk.Select(item => item.WirePath).ToArray() } };
            JsonElement response;
            try
            {
                response = gateOwned
                    ? await CallAsync("request", parameters, state.CoreSession, cancellationToken, HostCallSafety.ReadOnly).ConfigureAwait(false)
                    : await SessionRequestAsync(state, parameters, false, HostCallSafety.ReadOnly, cancellationToken).ConfigureAwait(false);
            }
            catch (FilewayException error) when (error.Error.Code == ErrorCode.OutcomeUnknown)
            { throw InterruptedReadOnlyRequest(error.Error.HttpStatus); }
            RequireExactStatus(response, 200);
            using var document = ParseBody(response);
            results.AddRange(ParseMetadataBatch(document.RootElement, chunk, state));
            start += chunk.Count;
        }
        return results;
    }

    private DetailedMetadata[] ParseMetadataBatch(JsonElement value, List<RemoteResourceRef> requested, SessionState state)
    {
        try
        {
            if (value.ValueKind != JsonValueKind.Array || value.GetArrayLength() != requested.Count) throw InvalidResponse();
            var rows = new DetailedMetadata[requested.Count];
            int index = 0;
            foreach (var row in value.EnumerateArray())
            {
                var source = requested[index];
                string wire = RequiredText(row.GetProperty("wirePath"));
                if (FileOperationWireIdentity(wire, true) != FileOperationWireIdentity(source.WirePath, true)) throw InvalidResponse();
                int status = row.GetProperty("status").GetInt32();
                if (status == 200)
                {
                    var item = row.GetProperty("item");
                    var file = ParseFile(item, state);
                    if (FileOperationWireIdentity(file.Resource.WirePath, true) != FileOperationWireIdentity(source.WirePath, true)) throw InvalidResponse();
                    bool? symlink = item.TryGetProperty("isSymlink", out var flag) ? flag.GetBoolean() : null;
                    rows[index] = new DetailedMetadata(source, file, symlink, OptionalText(item, "modified"), null);
                }
                else
                {
                    if (status is < 400 or > 599) throw InvalidResponse();
                    rows[index] = new DetailedMetadata(source, null, null, null, MetadataHttpError(status));
                }
                index++;
            }
            return rows;
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    public async Task<BatchRenamePreview> PreviewBatchRenameAsync(BatchRenameDraft draft, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(draft);
        ArgumentNullException.ThrowIfNull(draft.Items);
        var sources = draft.Items.ToArray();
        if (draft.Id == Guid.Empty || sources.Length is < 1 or > 500) throw Safe(ErrorCode.InvalidRequest, "批量重命名草稿无效。");
        var state = FileManagementState(sources.Select(item => item.Source).ToArray());
        var key = (state.Session.Account, state.Session.Id, draft.Id);
        Guid tokenId = Guid.NewGuid();
        Guid existingId;
        lock (_batchRenameGate)
        {
            if (_batchRenameDrafts.TryGetValue(key, out existingId) && _batchRenamePreviews.TryGetValue(existingId, out var existing) &&
                existing.Attempted != 0 && existing.LastOutcome is null or OperationOutcome.OutcomeUnknown)
                throw Safe(existing.LastOutcome is null ? ErrorCode.Busy : ErrorCode.OutcomeUnknown,
                    existing.LastOutcome is null ? "此草稿正在执行，请等待结果。" : Message(ErrorCode.OutcomeUnknown));
            _batchRenameDrafts[key] = tokenId;
        }
        var plan = new BatchRenamePreviewItem[sources.Length];
        string? parentIdentity = null;
        for (int index = 0; index < sources.Length; index++)
        {
            var source = sources[index].Source;
            _ = FileOperationWireIdentity(source.WirePath, false);
            string parentWire = source.WirePath[..source.WirePath.LastIndexOf('/')];
            if (parentWire.Length == 0) parentWire = "/";
            string identity = FileOperationWireIdentity(parentWire, true);
            if (parentIdentity is not null && parentIdentity != identity) throw Safe(ErrorCode.InvalidRequest, "批量重命名必须位于同一个文件夹。");
            parentIdentity = identity;
            string name = FileOperationName(sources[index].NewName);
            string parentDisplay = source.DisplayPath[..Math.Max(0, source.DisplayPath.LastIndexOf('/'))];
            var target = BindResource(new RemoteResourceRef(source.Account,
                parentWire.TrimEnd('/') + "/" + Uri.EscapeDataString(name), name, parentDisplay + "/" + name), state);
            plan[index] = new BatchRenamePreviewItem(source, target, false);
        }
        var metadata = await ReadMetadataBatchesAsync(state, plan.Select(item => item.Source).ToArray(), cancellationToken).ConfigureAwait(false);
        JsonElement body = RenameBody(plan, legacy: false);
        bool legacy = false;
        JsonElement response;
        try { response = await ReadRenamePreviewAsync(state, body, cancellationToken).ConfigureAwait(false); }
        catch (FilewayException error) when (error.Error.HttpStatus == 400 && plan.All(item => LegacyFilePath(item.Source.WirePath) is not null && LegacyFilePath(item.Target.WirePath) is not null))
        {
            legacy = true;
            body = RenameBody(plan, legacy: true);
            response = await ReadRenamePreviewAsync(state, body, cancellationToken).ConfigureAwait(false);
        }
        var parsed = ParseRenameResult(response, plan, legacy, execute: false);
        EnsureActive(state);
        bool valid = parsed.Valid && metadata.All(item => item.File is not null);
        ErrorInfo? failure = parsed.Error ?? metadata.FirstOrDefault(item => item.Error is not null)?.Error;
        BatchRenamePreviewRef? executable = null;
        lock (_batchRenameGate)
        {
            if (!_batchRenameDrafts.TryGetValue(key, out var current) || current != tokenId)
            {
                valid = false;
                failure = new ErrorInfo(ErrorCode.SourceChanged, "此草稿已有新的预览，请使用最新结果。");
            }
            if (valid)
            {
                executable = new BatchRenamePreviewRef(state.Session.Account, state.Session.Id, tokenId);
                _batchRenamePreviews[tokenId] = new FrozenRename(state, draft.Id, parsed.Items, metadata.ToArray(), body, legacy);
                if (existingId != Guid.Empty) _batchRenamePreviews.TryRemove(existingId, out _);
            }
        }
        return new BatchRenamePreview(draft.Id, state.Session.Account, state.Session.Id, valid,
            Array.AsReadOnly(parsed.Items.ToArray()), executable, failure);
    }

    public async Task<BatchRenameResult> ExecuteBatchRenameAsync(BatchRenamePreviewRef preview, CancellationToken cancellationToken)
    {
        var state = GetState(preview.Account);
        FrozenRename frozen;
        lock (_batchRenameGate)
        {
            if (preview.SessionId != state.Session.Id || !_batchRenamePreviews.TryGetValue(preview.Id, out var found) ||
                !ReferenceEquals(state, found.State) || !_batchRenameDrafts.TryGetValue((preview.Account, preview.SessionId, found.DraftId), out var latest) || latest != preview.Id)
                throw Safe(ErrorCode.SourceChanged, "重命名预览已失效，请重新预览。");
            frozen = found;
            if (frozen.Attempted != 0)
                throw Safe(frozen.LastOutcome == OperationOutcome.OutcomeUnknown ? ErrorCode.OutcomeUnknown : ErrorCode.Conflict,
                    frozen.LastOutcome == OperationOutcome.OutcomeUnknown ? Message(ErrorCode.OutcomeUnknown) : "此预览已提交，请重新预览后再操作。");
            frozen.Attempted = 1;
        }
        var snapshots = RenameOperationItems(frozen.Plan, frozen.Metadata);
        RenameResult? acknowledged = null;
        var journal = PrepareJournalContext(state, OperationKind.BatchRename, snapshots, response =>
        {
            var parsed = ParseRenameResult(response, frozen.Plan, frozen.Legacy, execute: true);
            if (!parsed.Valid || !parsed.Executed || parsed.Items.Any(item => !item.Valid || item.Status != "completed")) throw InvalidResponse();
            acknowledged = parsed;
            return new OperationAcknowledgement(OperationAckKind.BatchRenameConfirmed,
                Items: snapshots.Select(item => item with { Outcome = FileOperationItemOutcome.Confirmed }).ToArray());
        }, new OperationOptions(BatchId: frozen.DraftId), captureItems: () => snapshots);
        try
        {
            _ = await SessionRequestAsync(state, new { method = "POST", endpoint = "/api/resources/batch-rename", body = new { items = frozen.Body, dryRun = false } },
                false, HostCallSafety.Mutation, cancellationToken, beforeSend: async token =>
                {
                    RequireBatchRenameWrite(state, frozen.Legacy);
                    var current = await ReadMetadataBatchesAsync(state, frozen.Plan.Select(item => item.Source).ToArray(), token, gateOwned: true).ConfigureAwait(false);
                    for (int index = 0; index < current.Count; index++) RequireSameFileVersion(frozen.Metadata[index], current[index]);
                    snapshots = RenameOperationItems(frozen.Plan, current);
                }, authorizeSend: () => RequireBatchRenameWrite(state, frozen.Legacy), journal: journal).ConfigureAwait(false);
            lock (_batchRenameGate) frozen.LastOutcome = OperationOutcome.Confirmed;
            return new BatchRenameResult(OperationOutcome.Confirmed, true, true, acknowledged!.Items, journal.Receipt);
        }
        catch (FilewayException error)
        {
            bool unknown = IsUncertainJournalFailure(error, journal);
            lock (_batchRenameGate) frozen.LastOutcome = unknown ? OperationOutcome.OutcomeUnknown : OperationOutcome.Partial;
            var safe = unknown ? UnknownMutationError(error.Error.HttpStatus) : error.Error;
            return new BatchRenameResult(frozen.LastOutcome.Value, false, false,
                frozen.Plan.Select(item => item with { Valid = false, Error = safe }).ToArray(), journal.Receipt, safe);
        }
    }

    private async Task<JsonElement> ReadRenamePreviewAsync(SessionState state, JsonElement body, CancellationToken cancellationToken)
    {
        try
        {
            return await SessionRequestAsync(state, new { method = "POST", endpoint = "/api/resources/batch-rename", body = new { items = body, dryRun = true } },
                false, HostCallSafety.ReadOnly, cancellationToken).ConfigureAwait(false);
        }
        catch (FilewayException error) when (error.Error.Code == ErrorCode.OutcomeUnknown)
        { throw InterruptedReadOnlyRequest(error.Error.HttpStatus); }
    }

    private static FilewayException InterruptedReadOnlyRequest(int? status) => new(new ErrorInfo(ErrorCode.Unknown,
        "读取或预览已中断，未执行文件操作；可以重新读取或预览。", status, Retryable: true));

    private sealed class FrozenRename(SessionState state, Guid draftId, IReadOnlyList<BatchRenamePreviewItem> plan,
        DetailedMetadata[] metadata, JsonElement body, bool legacy)
    {
        internal SessionState State { get; } = state;
        internal Guid DraftId { get; } = draftId;
        internal IReadOnlyList<BatchRenamePreviewItem> Plan { get; } = Array.AsReadOnly(plan.ToArray());
        internal DetailedMetadata[] Metadata { get; } = metadata;
        internal JsonElement Body { get; } = body;
        internal bool Legacy { get; } = legacy;
        internal int Attempted;
        internal OperationOutcome? LastOutcome;
    }
    private sealed record RenameResult(bool Valid, bool Executed, BatchRenamePreviewItem[] Items, ErrorInfo? Error);

    private static JsonElement RenameBody(IReadOnlyList<BatchRenamePreviewItem> plan, bool legacy) => legacy
        ? JsonSerializer.SerializeToElement(plan.Select(item => new { from = LegacyFilePath(item.Source.WirePath), to = LegacyFilePath(item.Target.WirePath) }).ToArray())
        : JsonSerializer.SerializeToElement(plan.Select(item => new { fromWirePath = item.Source.WirePath, toWirePath = item.Target.WirePath }).ToArray());

    private static RenameResult ParseRenameResult(JsonElement response, IReadOnlyList<BatchRenamePreviewItem> plan, bool legacy, bool execute)
    {
        try
        {
            RequireExactStatus(response, 200);
            using var document = ParseBody(response);
            var root = document.RootElement;
            bool valid = root.GetProperty("valid").GetBoolean();
            bool executed = root.GetProperty("executed").GetBoolean();
            var rows = root.GetProperty("items");
            if (rows.ValueKind != JsonValueKind.Array || rows.GetArrayLength() != plan.Count || !execute && executed) throw InvalidResponse();
            var items = new BatchRenamePreviewItem[plan.Count];
            int index = 0;
            foreach (var row in rows.EnumerateArray())
            {
                var expected = plan[index];
                string? from = OptionalText(row, "fromWirePath");
                string? to = OptionalText(row, "toWirePath");
                if (from is not null || to is not null)
                {
                    if (from is null || to is null || FileOperationWireIdentity(from, false) != FileOperationWireIdentity(expected.Source.WirePath, false) ||
                        FileOperationWireIdentity(to, false) != FileOperationWireIdentity(expected.Target.WirePath, false)) throw InvalidResponse();
                }
                else if (!legacy || OptionalText(row, "from") != LegacyFilePath(expected.Source.WirePath) || OptionalText(row, "to") != LegacyFilePath(expected.Target.WirePath))
                    throw InvalidResponse();
                string status = RequiredText(row.GetProperty("status"));
                if (status is not ("ready" or "error" or "completed") || !execute && status == "completed") throw InvalidResponse();
                items[index++] = expected with { Valid = status != "error", Status = status,
                    Error = status == "error" ? new ErrorInfo(ErrorCode.Conflict, "此项目未通过重命名检查，请刷新并核对名称。") : null };
            }
            if (valid && items.Any(item => !item.Valid || item.Status != (execute ? "completed" : "ready"))) throw InvalidResponse();
            return new RenameResult(valid, executed, items,
                valid ? null : new ErrorInfo(ErrorCode.Conflict, "重命名计划不可执行，请核对逐项结果。"));
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    private static OperationItem[] RenameOperationItems(IReadOnlyList<BatchRenamePreviewItem> plan, IReadOnlyList<DetailedMetadata> metadata) =>
        plan.Select((item, index) => new OperationItem(index,
            metadata[index].File is { } source ? FileSnapshot(source) : new OperationResourceSnapshot(item.Source),
            new OperationResourceSnapshot(item.Target))).ToArray();

    public async Task<ServerTaskSubmissionResult> SubmitTransferAsync(FileTransferRequest request, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(request);
        ArgumentNullException.ThrowIfNull(request.Destination);
        ArgumentNullException.ThrowIfNull(request.Items);
        var selected = request.Items.ToArray();
        if (!Enum.IsDefined(request.Action) || selected.Length is < 1 or > 1000 || selected.Any(item => !Enum.IsDefined(item.Conflict)))
            throw Safe(ErrorCode.InvalidRequest, "文件传输请求无效。");
        var state = FileManagementState(selected.Select(item => item.Source).Prepend(request.Destination).ToArray());
        var submitted = selected.Where(item => item.Conflict != FileConflictPolicy.Skip).ToArray();
        if (submitted.Length == 0) return new ServerTaskSubmissionResult(ServerTaskSubmissionOutcome.NotSubmitted);
        var sources = new HashSet<string>(StringComparer.Ordinal);
        var targets = new HashSet<string>(StringComparer.Ordinal);
        var plan = new TransferPlan[submitted.Length];
        bool requiresWireCapability = LegacyFilePath(request.Destination.WirePath) is null;
        for (int index = 0; index < submitted.Length; index++)
        {
            var item = submitted[index];
            string identity = FileOperationWireIdentity(item.Source.WirePath, false);
            if (!sources.Add(identity)) throw Safe(ErrorCode.InvalidRequest, "所选源项目重复。");
            string wireName = item.Source.WirePath[(item.Source.WirePath.LastIndexOf('/') + 1)..];
            var target = FileManagementChild(request.Destination, wireName, item.Source.DisplayName);
            string targetIdentity = FileOperationWireIdentity(target.WirePath, false);
            bool safeSameDirectoryCopy = request.Action == FileTransferAction.Copy && item.Conflict == FileConflictPolicy.KeepBoth;
            if (targetIdentity == identity && !safeSameDirectoryCopy || WireIsDescendant(item.Source.WirePath, target.WirePath) || !targets.Add(targetIdentity))
                throw Safe(ErrorCode.InvalidRequest, "目标与源相同、位于源内部，或多个项目指向同一个目标。");
            requiresWireCapability |= LegacyFilePath(item.Source.WirePath) is null || LegacyFilePath(target.WirePath) is null;
            var body = new Dictionary<string, object?>
            {
                ["fromWirePath"] = item.Source.WirePath, ["toWirePath"] = target.WirePath,
                ["overwrite"] = item.Conflict == FileConflictPolicy.Replace, ["rename"] = item.Conflict == FileConflictPolicy.KeepBoth,
            };
            // Strict UTF-8 compatibility stays in the same request. A mutation
            // is never retried with another path representation.
            if (LegacyFilePath(item.Source.WirePath) is not null && LegacyFilePath(target.WirePath) is not null)
            {
                body["from"] = "/files" + item.Source.WirePath;
                body["to"] = "/files" + target.WirePath;
            }
            plan[index] = new TransferPlan(item, target, body);
        }
        var snapshots = plan.Select((item, index) => new OperationItem(index,
            new OperationResourceSnapshot(item.Request.Source), new OperationResourceSnapshot(item.Target), item.Request.Conflict)).ToArray();
        ServerTask? accepted = null;
        AccountKey capturedAccount = request.Destination.Account;
        string action = request.Action == FileTransferAction.Copy ? "copy" : "move";
        var journal = PrepareJournalContext(state, request.Action == FileTransferAction.Copy ? OperationKind.Copy : OperationKind.Move,
            snapshots, response =>
            {
                RequireExactStatus(response, 202);
                using var document = ParseBody(response);
                var task = ParseServerTask(document.RootElement, capturedAccount);
                if (task.RawType != "file." + action) throw InvalidResponse();
                accepted = task;
                return new OperationAcknowledgement(OperationAckKind.TaskAccepted, RecordId: task.Reference.Id,
                    Task: task.Reference, Items: snapshots);
            }, captureItems: () => snapshots);
        try
        {
            _ = await SessionRequestAsync(state, new
            {
                method = "POST", endpoint = "/api/resources/transfer",
                body = new { action, items = plan.Select(item => item.Body).ToArray() },
            }, false, HostCallSafety.Mutation, cancellationToken, beforeSend: async token =>
            {
                RequireTransferWrite(state, request.Action, plan, requiresWireCapability);
                using var directoryDocument = ParseBody(await RequestAsync(state.CoreSession,
                    "/api/resources" + request.Destination.WirePath + "?metadata=1", token).ConfigureAwait(false));
                var directory = ParseFile(directoryDocument.RootElement, state);
                if (!directory.IsDirectory || FileOperationWireIdentity(directory.Resource.WirePath, true) !=
                    FileOperationWireIdentity(request.Destination.WirePath, true)) throw InvalidResponse();
                var metadata = await ReadMetadataBatchesAsync(state,
                    plan.Select(item => item.Request.Source).Concat(plan.Select(item => item.Target)).ToArray(), token, gateOwned: true).ConfigureAwait(false);
                var captured = new OperationItem[plan.Length];
                for (int index = 0; index < plan.Length; index++)
                {
                    var source = metadata[index];
                    var destination = metadata[index + plan.Length];
                    if (source.Error is { } sourceError) throw new FilewayException(sourceError);
                    if (source.File is not { } file || source.Modified is null || file.Modified is null) throw InvalidResponse();
                    if (source.IsSymlink is not false)
                        throw Safe(source.IsSymlink is null ? ErrorCode.CapabilityMissing : ErrorCode.InvalidRequest,
                            "无法确认此项目可安全传输；符号链接不受支持。");
                    if (destination.Error is { HttpStatus: not 404 } targetError) throw new FilewayException(targetError);
                    if (destination.File is { } existing)
                    {
                        if (plan[index].Request.Conflict == FileConflictPolicy.Fail)
                            throw Safe(ErrorCode.Conflict, "目标项目已存在，请选择冲突处理方式。", 409);
                        if (plan[index].Request.Conflict == FileConflictPolicy.Replace && existing.IsDirectory != file.IsDirectory)
                            throw Safe(ErrorCode.Conflict, "源与目标类型不同，无法替换。", 409);
                    }
                    plan[index].Body["name"] = file.Resource.DisplayName;
                    plan[index].Body["size"] = file.Size;
                    plan[index].Body["modified"] = source.Modified;
                    plan[index].Body["isDir"] = file.IsDirectory;
                    captured[index] = new OperationItem(index, FileSnapshot(file),
                        destination.File is { } targetFile ? FileSnapshot(targetFile) : new OperationResourceSnapshot(plan[index].Target),
                        plan[index].Request.Conflict);
                }
                snapshots = captured;
            }, authorizeSend: () => RequireTransferWrite(state, request.Action, plan, requiresWireCapability), journal: journal).ConfigureAwait(false);
            // 202 accepts a durable task. It does not confirm copied/moved files.
            return new ServerTaskSubmissionResult(ServerTaskSubmissionOutcome.Accepted, accepted, journal.Receipt);
        }
        catch (FilewayException error)
        {
            bool unknown = IsUncertainJournalFailure(error, journal);
            return new ServerTaskSubmissionResult(unknown ? ServerTaskSubmissionOutcome.OutcomeUnknown : ServerTaskSubmissionOutcome.Rejected,
                Receipt: journal.Receipt, Error: unknown ? UnknownMutationError(error.Error.HttpStatus) : error.Error);
        }
    }

    private sealed record TransferPlan(FileTransferItem Request, RemoteResourceRef Target, Dictionary<string, object?> Body);
    private void RequireBatchRenameWrite(SessionState state, bool legacy)
    {
        EnsureActive(state);
        if (!state.Session.Permissions.Rename) throw Safe(ErrorCode.PermissionDenied, Message(ErrorCode.PermissionDenied));
        if (!legacy && !state.Session.Capabilities.ResourceWireOperations)
            throw Safe(ErrorCode.CapabilityMissing, Message(ErrorCode.CapabilityMissing));
    }
    private void RequireTransferWrite(SessionState state, FileTransferAction action, IReadOnlyList<TransferPlan> plan, bool requiresWireCapability)
    {
        EnsureActive(state);
        var permissions = state.Session.Permissions;
        if (!permissions.Create || action == FileTransferAction.Move && !permissions.Rename ||
            plan.Any(item => item.Request.Conflict == FileConflictPolicy.Replace) && !permissions.Modify)
            throw Safe(ErrorCode.PermissionDenied, Message(ErrorCode.PermissionDenied));
        if (requiresWireCapability && !state.Session.Capabilities.ResourceWireOperations)
            throw Safe(ErrorCode.CapabilityMissing, "服务器尚不支持安全的原始路径文件操作，请升级服务器后重试。");
    }
    private static bool WireIsDescendant(string parentWire, string childWire)
    {
        byte[] parent = Convert.FromHexString(FileOperationWireIdentity(parentWire, true));
        byte[] child = Convert.FromHexString(FileOperationWireIdentity(childWire, true));
        return child.Length > parent.Length && child.AsSpan(0, parent.Length).SequenceEqual(parent) &&
            (parent.Length == 1 || child[parent.Length] == '/');
    }

    private static void RequireSameFileVersion(DetailedMetadata before, DetailedMetadata after)
    {
        if (after.Error is { } failure) throw new FilewayException(failure);
        if (before.File is not { } original || after.File is not { } current || original.IsDirectory != current.IsDirectory ||
            original.Size != current.Size || before.Modified != after.Modified || before.IsSymlink != after.IsSymlink)
            throw Safe(ErrorCode.Conflict, "源项目在预览后已变化，请重新预览。", 409);
    }

    private SessionState FileManagementState(IReadOnlyList<RemoteResourceRef> resources)
    {
        SessionState? state = null;
        foreach (var resource in resources)
        {
            ArgumentNullException.ThrowIfNull(resource);
            _ = FileOperationWireIdentity(resource.WirePath, true);
            var owner = GetResourceState(resource);
            if (state is not null && !ReferenceEquals(state, owner)) throw Safe(ErrorCode.SourceChanged, "所选项目不属于同一账户会话。");
            state = owner;
        }
        return state ?? throw Safe(ErrorCode.InvalidRequest, "请选择项目。");
    }
    private static RemoteResourceRef FileManagementChild(RemoteResourceRef parent, string wireName, string displayName) =>
        new(parent.Account, parent.WirePath.TrimEnd('/') + "/" + wireName, displayName, parent.DisplayPath.TrimEnd('/') + "/" + displayName);
    private static OperationResourceSnapshot FileSnapshot(RemoteFile file) => new(file.Resource, file.IsDirectory, file.Size, file.Modified);
    private static ErrorInfo MetadataHttpError(int status) => status switch
    {
        401 => new(ErrorCode.AuthenticationExpired, Message(ErrorCode.AuthenticationExpired), status),
        403 => new(ErrorCode.PermissionDenied, Message(ErrorCode.PermissionDenied), status),
        404 => new(ErrorCode.Conflict, "项目已不可用，请刷新后重试。", status),
        409 => new(ErrorCode.Conflict, Message(ErrorCode.Conflict), status),
        _ => new(ErrorCode.NetworkUnavailable, Message(ErrorCode.NetworkUnavailable), status),
    };
    private static string? LegacyFilePath(string wire)
    {
        try
        {
            string value = StrictUtf8.GetString(Convert.FromHexString(FileOperationWireIdentity(wire, true)));
            return value.Contains('\uFFFD') ? null : value;
        }
        catch (DecoderFallbackException) { return null; }
    }
}
