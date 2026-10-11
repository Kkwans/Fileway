using System.Text;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices
{
    public async Task<FileOperationResult> CreateDirectoryAsync(RemoteResourceRef parent, string name, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(parent);
        string normalizedName = FileOperationName(name);
        _ = FileOperationWireIdentity(parent.WirePath, allowRoot: true);
        var state = GetResourceState(parent);
        string targetWire = (parent.WirePath == "/" ? "" : parent.WirePath) + "/" + Uri.EscapeDataString(normalizedName);
        var target = new RemoteResourceRef(parent.Account, targetWire, normalizedName,
            (parent.DisplayPath == "/" ? "" : parent.DisplayPath) + "/" + normalizedName);
        return await FileWriteAsync(state, target, "POST", "/api/resources" + targetWire + "/?override=false",
            permissions => permissions.Create,
            async token =>
            {
                var directory = await FileMetadataAsync(state, parent, false, token).ConfigureAwait(false);
                if (directory?.IsDirectory != true) throw Safe(ErrorCode.InvalidRequest, "父目录已不可用，请刷新后重试。");
                if (await FileMetadataAsync(state, target, true, token).ConfigureAwait(false) is not null)
                    throw Safe(ErrorCode.Conflict, "同名文件或文件夹已存在，请修改名称。", 409);
                // The existing server uses MkdirAll. This precheck prevents a
                // known conflict; a concurrent creator can still win the race.
                // A 200 acknowledges that the target directory now exists.
            }, ConfirmSynchronousFileWrite, cancellationToken).ConfigureAwait(false);
    }

    public async Task<FileOperationResult> RenameAsync(RemoteResourceRef resource, string name, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(resource);
        string normalizedName = FileOperationName(name);
        _ = FileOperationWireIdentity(resource.WirePath, allowRoot: false);
        var state = GetResourceState(resource);
        // Only the new UTF-8 name is encoded. The server-issued parent bytes
        // remain opaque, including non-UTF-8 names and literal percent signs.
        string targetWire = resource.WirePath[..resource.WirePath.LastIndexOf('/')] + "/" + Uri.EscapeDataString(normalizedName);
        string endpoint = "/api/resources" + resource.WirePath + "?action=rename&destinationWirePath=" +
            Uri.EscapeDataString(targetWire) + "&override=false&rename=false";
        return await FileWriteAsync(state, resource, "PATCH", endpoint, permissions => permissions.Rename, async token =>
        {
            var source = await FileMetadataAsync(state, resource, false, token).ConfigureAwait(false);
            if (StringComparer.Ordinal.Equals(source!.Resource.DisplayName, normalizedName))
                throw Safe(ErrorCode.InvalidRequest, "名称没有变化。");
        }, ConfirmSynchronousFileWrite, cancellationToken).ConfigureAwait(false);
    }

    public async Task<FileOperationResult> TrashAsync(IReadOnlyList<RemoteResourceRef> resources, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(resources);
        var snapshot = resources.ToArray();
        if (snapshot.Length == 0) throw Safe(ErrorCode.InvalidRequest, "请选择要移入回收站的项目。");
        var identities = new HashSet<string>(StringComparer.Ordinal);
        SessionState? state = null;
        foreach (var resource in snapshot)
        {
            ArgumentNullException.ThrowIfNull(resource);
            string identity = FileOperationWireIdentity(resource.WirePath, allowRoot: false);
            if (!identities.Add(identity)) throw Safe(ErrorCode.InvalidRequest, "选择中包含重复项目，请刷新后重试。");
            var owner = GetResourceState(resource);
            if (state is not null && !ReferenceEquals(state, owner))
                throw Safe(ErrorCode.SourceChanged, "所选项目不属于同一账户会话，请刷新后重试。");
            state = owner;
        }

        var results = new List<FileOperationItemResult>(snapshot.Length);
        ErrorInfo? stopped = null;
        bool unknown = false;
        foreach (var resource in snapshot)
        {
            if (stopped is not null)
            {
                results.Add(new FileOperationItemResult(resource, FileOperationItemOutcome.NotAttempted));
                continue;
            }
            try
            {
                var result = await FileWriteAsync(state!, resource, "DELETE", "/api/resources" + resource.WirePath + "?mode=trash",
                    permissions => permissions.Delete, _ => Task.CompletedTask,
                    ConfirmTrashWrite, cancellationToken).ConfigureAwait(false);
                if (result.Outcome == OperationOutcome.Confirmed)
                    results.Add(new FileOperationItemResult(resource, FileOperationItemOutcome.Confirmed));
                else
                {
                    unknown = true;
                    stopped = result.Error ?? new ErrorInfo(ErrorCode.OutcomeUnknown, Message(ErrorCode.OutcomeUnknown));
                    results.Add(new FileOperationItemResult(resource, FileOperationItemOutcome.OutcomeUnknown, stopped));
                }
            }
            catch (FilewayException error)
            {
                stopped = error.Error;
                results.Add(new FileOperationItemResult(resource,
                    error.Error.Code is ErrorCode.Canceled or ErrorCode.SourceChanged
                        ? FileOperationItemOutcome.NotAttempted : FileOperationItemOutcome.Failed, stopped));
            }
        }
        return new FileOperationResult(unknown ? OperationOutcome.OutcomeUnknown : stopped is null
            ? OperationOutcome.Confirmed : OperationOutcome.Partial, Error: stopped, Items: results.ToArray());
    }

    private async Task<FileOperationResult> FileWriteAsync(SessionState state, RemoteResourceRef resource,
        string method, string endpoint, Func<PermissionSet, bool> permitted,
        Func<CancellationToken, Task> prepare, Action<JsonElement> confirm,
        CancellationToken cancellationToken)
    {
        bool sending = false;
        try
        {
            _ = await SessionRequestAsync(state, new { method, endpoint }, requireDownload: false,
                HostCallSafety.Mutation, cancellationToken, beforeSend: async token =>
                {
                    RequireFileWrite(state, permitted(state.Session.Permissions));
                    await prepare(token).ConfigureAwait(false);
                    token.ThrowIfCancellationRequested();
                    EnsureActive(state);
                }, confirmResponse: confirm, authorizeSend: () =>
                {
                    RequireFileWrite(state, permitted(state.Session.Permissions));
                    sending = true;
                }).ConfigureAwait(false);
            return new FileOperationResult(OperationOutcome.Confirmed,
                Items: [new FileOperationItemResult(resource, FileOperationItemOutcome.Confirmed)]);
        }
        catch (FilewayException error) when (error.Error.Code == ErrorCode.OutcomeUnknown || sending &&
            (error.Error.HttpStatus is >= 500 or 408 || error.Error.HttpStatus is null &&
                error.Error.Code is (ErrorCode.NetworkUnavailable or ErrorCode.Timeout or ErrorCode.ResponseTooLarge
                    or ErrorCode.ProtocolMismatch or ErrorCode.HostExited)))
        {
            var unknown = new ErrorInfo(ErrorCode.OutcomeUnknown, Message(ErrorCode.OutcomeUnknown), error.Error.HttpStatus);
            return new FileOperationResult(OperationOutcome.OutcomeUnknown, Error: unknown,
                Items: [new FileOperationItemResult(resource, FileOperationItemOutcome.OutcomeUnknown, unknown)]);
        }
    }

    private void RequireFileWrite(SessionState state, bool permitted)
    {
        EnsureActive(state);
        if (!state.Session.Capabilities.ResourceWireOperations)
            throw Safe(ErrorCode.CapabilityMissing, "服务器尚不支持安全的原始路径文件操作，请升级服务器后重试。");
        if (!permitted) throw Safe(ErrorCode.PermissionDenied, Message(ErrorCode.PermissionDenied));
    }

    // Called only while SessionRequestAsync owns Operations. Re-entering
    // GetAsync or the session RequestAsync overload here would deadlock.
    private async Task<RemoteFile?> FileMetadataAsync(SessionState state, RemoteResourceRef resource,
        bool allowMissing, CancellationToken cancellationToken)
    {
        try
        {
            using var document = ParseBody(await RequestAsync(state.CoreSession,
                "/api/resources" + resource.WirePath + "?metadata=1", cancellationToken).ConfigureAwait(false));
            var file = ParseFile(document.RootElement, state);
            if (FileOperationWireIdentity(file.Resource.WirePath, allowRoot: true) !=
                FileOperationWireIdentity(resource.WirePath, allowRoot: true)) throw InvalidResponse();
            return file;
        }
        catch (FilewayException error) when (allowMissing && error.Error.HttpStatus == 404) { return null; }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    private static void ConfirmSynchronousFileWrite(JsonElement response)
    {
        // These endpoints complete synchronously with 200. A generic 2xx,
        // particularly 202, is not proof that the mutation completed.
        if (response.GetProperty("status").GetInt32() != 200)
            throw Safe(ErrorCode.OutcomeUnknown, Message(ErrorCode.OutcomeUnknown));
    }

    private static void ConfirmTrashWrite(JsonElement response)
    {
        ConfirmSynchronousFileWrite(response);
        try
        {
            using var document = ParseBody(response);
            var item = document.RootElement;
            _ = RequiredText(item.GetProperty("id"));
            if (RequiredText(item.GetProperty("status")) != "available") throw InvalidResponse();
        }
        catch (Exception error) when (error is FilewayException or JsonException or InvalidOperationException or KeyNotFoundException)
        { throw Safe(ErrorCode.OutcomeUnknown, Message(ErrorCode.OutcomeUnknown)); }
    }

    private static string FileOperationName(string name)
    {
        if (name is null) throw Safe(ErrorCode.InvalidRequest, "请输入名称。");
        string trimmed = name.Trim();
        if (trimmed.Length == 0 || trimmed is "." or ".." || trimmed.Contains('/') || trimmed.Contains('\0'))
            throw Safe(ErrorCode.InvalidRequest, "名称不能为空、包含 / 或空字符，也不能是 . 或 ..。");
        try { _ = StrictUtf8.GetByteCount(trimmed); }
        catch (EncoderFallbackException) { throw Safe(ErrorCode.InvalidRequest, "名称包含无效字符。"); }
        return trimmed;
    }

    private static string FileOperationWireIdentity(string wirePath, bool allowRoot)
    {
        if (string.IsNullOrEmpty(wirePath) || wirePath[0] != '/' || wirePath.Any(character => character is '?' or '#' or '\\' || char.IsControl(character)) ||
            wirePath != "/" && (wirePath.EndsWith('/') || wirePath.Contains("//", StringComparison.Ordinal)))
            throw Safe(ErrorCode.InvalidRequest, "项目原始路径无效，请刷新后重试。");
        if (wirePath == "/")
        {
            if (!allowRoot) throw Safe(ErrorCode.InvalidRequest, "不能修改文件系统根目录。");
            return "2F";
        }
        try
        {
            using var identity = new MemoryStream();
            foreach (string segment in wirePath.Split('/').Skip(1))
            {
                byte[] encoded = StrictUtf8.GetBytes(segment);
                byte[] decoded = new byte[encoded.Length];
                int length = 0;
                for (int index = 0; index < encoded.Length; index++)
                {
                    byte value = encoded[index];
                    if (value == '%')
                    {
                        if (index + 2 >= encoded.Length || Hex(encoded[index + 1]) < 0 || Hex(encoded[index + 2]) < 0)
                            throw Safe(ErrorCode.InvalidRequest, "项目原始路径编码无效，请刷新后重试。");
                        value = (byte)((Hex(encoded[index + 1]) << 4) | Hex(encoded[index + 2]));
                        index += 2;
                    }
                    if (value == 0 || value == '/') throw Safe(ErrorCode.InvalidRequest, "项目原始路径包含无效分隔符。");
                    decoded[length++] = value;
                }
                if (length == 1 && decoded[0] == '.' || length == 2 && decoded[0] == '.' && decoded[1] == '.')
                    throw Safe(ErrorCode.InvalidRequest, "项目原始路径包含遍历，请刷新后重试。");
                identity.WriteByte((byte)'/');
                identity.Write(decoded, 0, length);
            }
            return Convert.ToHexString(identity.ToArray());
        }
        catch (EncoderFallbackException) { throw Safe(ErrorCode.InvalidRequest, "项目原始路径包含无效字符。"); }
    }
}
