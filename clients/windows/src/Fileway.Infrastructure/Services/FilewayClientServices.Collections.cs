using System.Text;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices
{
    public async Task<ResourceCollectionsSnapshot> GetCollectionsAsync(AccountKey account, CancellationToken cancellationToken)
    {
        var state = GetState(account);
        // Opening favorites can claim legacy groups for the current administrator.
        using var favorites = ParseBody(await RequestAsync(state, "/api/favorites", cancellationToken).ConfigureAwait(false));
        using var groups = ParseBody(await RequestAsync(state, "/api/favorites/groups", cancellationToken).ConfigureAwait(false));
        using var tags = ParseBody(await RequestAsync(state, "/api/tags", cancellationToken).ConfigureAwait(false));
        try
        {
            var favoriteRows = favorites.RootElement.EnumerateArray().Select(row => ParseFavorite(row, state))
                .OrderBy(row => row.Order).ThenBy(row => row.Id, StringComparer.Ordinal).ToArray();
            var groupRows = groups.RootElement.EnumerateArray().Select(row => ParseFavoriteGroup(row, account))
                .OrderBy(row => row.Order).ThenBy(row => row.Id, StringComparer.Ordinal).ToArray();
            var tagRows = tags.RootElement.EnumerateArray().Select(row => ParseCollectionTag(row, state)).ToArray();
            RequireDistinctCollectionIds(favoriteRows.Select(row => row.Id));
            RequireDistinctCollectionIds(groupRows.Select(row => row.Id));
            RequireDistinctCollectionIds(tagRows.Select(row => row.Id));
            EnsureActive(state);
            return new ResourceCollectionsSnapshot(account, favoriteRows, groupRows, tagRows);
        }
        catch (Exception error) when (CollectionFormatError(error)) { throw InvalidResponse(); }
    }

    public Task<CollectionWriteResult> AddFavoriteAsync(RemoteResourceRef resource, string name, string? groupId, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(resource);
        var state = GetResourceState(resource);
        string label = CollectionName(name);
        string group = CollectionGroupId(groupId);
        var body = CollectionPathBody(resource);
        body["name"] = label;
        body["groupId"] = group;
        return CollectionWriteAsync(state, "POST", "/api/favorites", body, response =>
        {
            var saved = ParseFavorite(response, state);
            if (saved.Name != label || (saved.GroupId ?? "") != group || saved.Reference.Resource is not { } acknowledged ||
                CollectionWireIdentity(acknowledged.WirePath) != CollectionWireIdentity(resource.WirePath)) throw InvalidResponse();
            return saved.Id;
        }, cancellationToken, resource, group);
    }

    public Task<CollectionWriteResult> UpdateFavoriteAsync(AccountKey account, string id, string name, string? groupId, CancellationToken cancellationToken)
    {
        var state = GetState(account);
        string label = CollectionName(name);
        string group = CollectionGroupId(groupId);
        string endpoint = "/api/favorites/" + CollectionId(id);
        return CollectionWriteAsync(state, "PUT", endpoint, new { name = label, groupId = group }, response =>
        {
            var saved = ParseFavorite(response, state);
            if (saved.Id != id || saved.Name != label || (saved.GroupId ?? "") != group) throw InvalidResponse();
            return saved.Id;
        }, cancellationToken, groupId: group);
    }

    public Task<CollectionWriteResult> RemoveFavoriteAsync(AccountKey account, string id, CancellationToken cancellationToken) =>
        CollectionWriteAsync(GetState(account), "DELETE", "/api/favorites/" + CollectionId(id), null, _ => id,
            cancellationToken, jsonAcknowledgement: false);

    public Task<CollectionWriteResult> CreateFavoriteGroupAsync(AccountKey account, string name, string? color, CancellationToken cancellationToken) =>
        SaveFavoriteGroupAsync(account, null, name, color, cancellationToken);

    public Task<CollectionWriteResult> UpdateFavoriteGroupAsync(AccountKey account, string id, string name, string? color, CancellationToken cancellationToken) =>
        SaveFavoriteGroupAsync(account, id, name, color, cancellationToken);

    private Task<CollectionWriteResult> SaveFavoriteGroupAsync(AccountKey account, string? id, string name, string? color, CancellationToken cancellationToken)
    {
        string label = CollectionName(name);
        string shade = CollectionColor(color ?? "", allowEmpty: true);
        string endpoint = "/api/favorites/groups" + (id is null ? "" : "/" + CollectionId(id));
        return CollectionWriteAsync(GetState(account), id is null ? "POST" : "PUT", endpoint,
            new { name = label, color = shade }, response =>
            {
                var saved = ParseFavoriteGroup(response, account);
                if (id is not null && saved.Id != id || saved.Name != label || (saved.Color ?? "") != shade) throw InvalidResponse();
                return saved.Id;
            }, cancellationToken);
    }

    public Task<CollectionWriteResult> RemoveFavoriteGroupAsync(AccountKey account, string id, CancellationToken cancellationToken) =>
        CollectionWriteAsync(GetState(account), "DELETE", "/api/favorites/groups/" + CollectionId(id), null, _ => id,
            cancellationToken, jsonAcknowledgement: false);

    public Task<CollectionWriteResult> ReorderFavoritesAsync(AccountKey account, IReadOnlyList<string> ids, CancellationToken cancellationToken) =>
        ReorderCollectionAsync(account, "/api/favorites/reorder", ids, cancellationToken);

    public Task<CollectionWriteResult> ReorderFavoriteGroupsAsync(AccountKey account, IReadOnlyList<string> ids, CancellationToken cancellationToken) =>
        ReorderCollectionAsync(account, "/api/favorites/groups/reorder", ids, cancellationToken);

    private Task<CollectionWriteResult> ReorderCollectionAsync(AccountKey account, string endpoint, IReadOnlyList<string> ids, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(ids);
        var snapshot = ids.ToArray();
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (string id in snapshot)
        {
            _ = CollectionId(id);
            if (!seen.Add(id)) throw Safe(ErrorCode.InvalidRequest, "排序中包含重复记录，请刷新后重试。");
        }
        return CollectionWriteAsync(GetState(account), "PUT", endpoint, new { ids = snapshot }, _ => null,
            cancellationToken, jsonAcknowledgement: false);
    }

    public Task<CollectionWriteResult> CreateTagAsync(AccountKey account, string name, string color, CancellationToken cancellationToken) =>
        SaveCollectionTagAsync(account, null, name, color, cancellationToken);

    public Task<CollectionWriteResult> UpdateTagAsync(AccountKey account, string id, string name, string color, CancellationToken cancellationToken) =>
        SaveCollectionTagAsync(account, id, name, color, cancellationToken);

    private Task<CollectionWriteResult> SaveCollectionTagAsync(AccountKey account, string? id, string name, string color, CancellationToken cancellationToken)
    {
        var state = GetState(account);
        string label = CollectionName(name);
        string shade = CollectionColor(color, allowEmpty: false);
        string endpoint = "/api/tags" + (id is null ? "" : "/" + CollectionId(id));
        return CollectionWriteAsync(state, id is null ? "POST" : "PUT", endpoint, new { name = label, color = shade }, response =>
        {
            var saved = ParseCollectionTag(response, state);
            if (id is not null && saved.Id != id || saved.Name != label || saved.Color != shade) throw InvalidResponse();
            return saved.Id;
        }, cancellationToken);
    }

    public Task<CollectionWriteResult> RemoveTagAsync(AccountKey account, string id, CancellationToken cancellationToken) =>
        CollectionWriteAsync(GetState(account), "DELETE", "/api/tags/" + CollectionId(id), null, _ => id,
            cancellationToken, jsonAcknowledgement: false);

    public Task<CollectionWriteResult> AddTagResourceAsync(string tagId, RemoteResourceRef resource, CancellationToken cancellationToken) =>
        ChangeTagResourceAsync(tagId, resource, adding: true, cancellationToken);

    public Task<CollectionWriteResult> RemoveTagResourceAsync(string tagId, RemoteResourceRef resource, CancellationToken cancellationToken) =>
        ChangeTagResourceAsync(tagId, resource, adding: false, cancellationToken);

    private Task<CollectionWriteResult> ChangeTagResourceAsync(string tagId, RemoteResourceRef resource, bool adding, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(resource);
        var state = GetResourceState(resource);
        string endpoint = "/api/tags/" + CollectionId(tagId) + "/paths";
        var body = CollectionPathBody(resource);
        string identity = CollectionWireIdentity(resource.WirePath);
        return CollectionWriteAsync(state, adding ? "POST" : "DELETE", endpoint, body, response =>
        {
            var saved = ParseCollectionTag(response, state);
            bool present = saved.Resources.Any(reference => reference.Resource is { } item && CollectionWireIdentity(item.WirePath) == identity);
            if (saved.Id != tagId || present != adding) throw InvalidResponse();
            return saved.Id;
        }, cancellationToken, resource);
    }

    private async Task<CollectionWriteResult> CollectionWriteAsync(SessionState state, string method, string endpoint, object? body,
        Func<JsonElement, string?> acknowledge, CancellationToken cancellationToken, RemoteResourceRef? pathResource = null,
        string? groupId = null, bool jsonAcknowledgement = true)
    {
        bool sending = false;
        string? recordId = null;
        bool compatible = pathResource is null || CollectionLegacyPath(pathResource.WirePath) is not null;
        void Authorize()
        {
            EnsureActive(state);
            // The metadata endpoints do not require filesystem CRUD permissions.
            // Strict UTF-8 can carry the old literal path in the original request;
            // opaque writes require a server known to implement wire operations.
            if (!compatible && !state.Session.Capabilities.ResourceWireOperations)
                throw Safe(ErrorCode.CapabilityMissing, "服务器尚不支持此原始路径的收藏或标签操作，请升级服务器后重试。");
        }
        try
        {
            await SessionRequestAsync(state, new { method, endpoint, body }, requireDownload: false, HostCallSafety.Mutation,
                cancellationToken, beforeSend: string.IsNullOrEmpty(groupId) ? null : async token =>
                {
                    Authorize();
                    using var groups = ParseBody(await RequestAsync(state.CoreSession, "/api/favorites/groups", token).ConfigureAwait(false));
                    try
                    {
                        if (!groups.RootElement.EnumerateArray().Any(row => RequiredText(row.GetProperty("id")) == groupId))
                            throw Safe(ErrorCode.Conflict, "所选分组已不可用，请刷新后重新选择。");
                    }
                    catch (Exception error) when (CollectionFormatError(error)) { throw InvalidResponse(); }
                }, confirmResponse: response =>
                {
                    ConfirmSynchronousFileWrite(response);
                    try
                    {
                        if (jsonAcknowledgement)
                        {
                            using var document = ParseBody(response);
                            recordId = acknowledge(document.RootElement);
                        }
                        else recordId = acknowledge(default);
                    }
                    catch (Exception error) when (error is FilewayException || CollectionFormatError(error))
                    { throw Safe(ErrorCode.OutcomeUnknown, "服务器尚未确认收藏或标签结果，请刷新核实后再操作。"); }
                }, authorizeSend: () => { Authorize(); sending = true; }).ConfigureAwait(false);
            return new CollectionWriteResult(OperationOutcome.Confirmed, recordId);
        }
        catch (FilewayException error) when (error.Error.Code == ErrorCode.OutcomeUnknown || sending &&
            (error.Error.HttpStatus is >= 500 or 408 || error.Error.HttpStatus is null &&
                error.Error.Code is (ErrorCode.NetworkUnavailable or ErrorCode.Timeout or ErrorCode.ResponseTooLarge or ErrorCode.ProtocolMismatch or ErrorCode.HostExited)))
        {
            return new CollectionWriteResult(OperationOutcome.OutcomeUnknown, Error: new ErrorInfo(ErrorCode.OutcomeUnknown,
                "收藏或标签操作结果尚未确认，请刷新核实；不要直接重复提交。", error.Error.HttpStatus));
        }
    }

    private ResourceFavorite ParseFavorite(JsonElement row, SessionState state) => new(state.Session.Account,
        RequiredText(row.GetProperty("id")), CollectionText(row, "name"), CollectionOptionalText(row, "groupId"),
        row.GetProperty("order").GetInt32(), CollectionTimestamp(row, "addedAt"), ParseCollectionResource(row, state));

    private static FavoriteGroup ParseFavoriteGroup(JsonElement row, AccountKey account) => new(account,
        RequiredText(row.GetProperty("id")), CollectionText(row, "name"), CollectionOptionalText(row, "color"), row.GetProperty("order").GetInt32());

    private ResourceTag ParseCollectionTag(JsonElement row, SessionState state)
    {
        var references = new List<CollectionResource>();
        var seen = new HashSet<string>(StringComparer.Ordinal);
        if (row.TryGetProperty("pathRefs", out var refs))
        {
            foreach (var reference in refs.EnumerateArray())
            {
                // pathRefs is authoritative and includes mandatory provenance.
                _ = reference.GetProperty("pathVerified").GetBoolean();
                Add(ParseCollectionResource(reference, state));
            }
        }
        else
        {
            foreach (var path in row.GetProperty("paths").EnumerateArray())
                Add(ParseCollectionResource(RequiredText(path), null, null, state));
        }
        return new ResourceTag(state.Session.Account, RequiredText(row.GetProperty("id")), CollectionText(row, "name"),
            CollectionText(row, "color"), CollectionTimestamp(row, "createdAt"), references.ToArray());

        void Add(CollectionResource reference)
        {
            if (reference.Resource is null || seen.Add(CollectionWireIdentity(reference.Resource.WirePath))) references.Add(reference);
        }
    }

    private CollectionResource ParseCollectionResource(JsonElement row, SessionState state)
    {
        bool? verified = row.TryGetProperty("pathVerified", out var provenance) ? provenance.GetBoolean() : null;
        return ParseCollectionResource(RequiredText(row.GetProperty("path")), CollectionOptionalText(row, "wirePath"), verified, state);
    }

    private CollectionResource ParseCollectionResource(string displayPath, string? wirePath, bool? verified, SessionState state)
    {
        // Historical display text may itself be noncanonical. It is still a
        // visible metadata row; only a trusted identity needs path validation.
        if (verified == false) return new CollectionResource(displayPath, null);
        if (string.IsNullOrEmpty(wirePath) && verified is null && displayPath.Contains('\uFFFD'))
            return new CollectionResource(displayPath, null);
        string path = CollectionDisplayPath(displayPath);
        if (string.IsNullOrEmpty(wirePath))
        {
            if (verified == true) throw InvalidResponse();
            wirePath = string.Join('/', path.Split('/').Select(Uri.EscapeDataString));
        }
        _ = CollectionWireIdentity(wirePath);
        string? decoded = CollectionLegacyPath(wirePath);
        if (decoded is not null && decoded != path) throw InvalidResponse();
        string name = path == "/" ? "根目录" : path[(path.LastIndexOf('/') + 1)..];
        return new CollectionResource(displayPath, BindResource(new RemoteResourceRef(state.Session.Account, wirePath, name, path), state));
    }

    private static Dictionary<string, string> CollectionPathBody(RemoteResourceRef resource)
    {
        _ = CollectionWireIdentity(resource.WirePath);
        string display = CollectionDisplayPath(resource.DisplayPath);
        string? legacy = CollectionLegacyPath(resource.WirePath);
        if (legacy is not null && legacy != display) throw Safe(ErrorCode.InvalidRequest, "显示路径与原始路径不一致，请刷新后重新选择。");
        var body = new Dictionary<string, string> { ["wirePath"] = resource.WirePath };
        if (legacy is not null) body["path"] = legacy;
        return body;
    }

    private static string CollectionWireIdentity(string wirePath)
    {
        if (string.IsNullOrEmpty(wirePath) || wirePath.Any(character => character < 0x21 || character > 0x7e))
            throw InvalidResponse();
        try { return FileOperationWireIdentity(wirePath, allowRoot: true); }
        catch (FilewayException) { throw InvalidResponse(); }
    }

    private static string? CollectionLegacyPath(string wirePath)
    {
        byte[] bytes = Convert.FromHexString(CollectionWireIdentity(wirePath));
        try { return StrictUtf8.GetString(bytes); }
        catch (DecoderFallbackException) { return null; }
    }

    private static string CollectionDisplayPath(string value)
    {
        if (string.IsNullOrEmpty(value) || value[0] != '/' || value.Contains('\0')) throw InvalidResponse();
        try { _ = StrictUtf8.GetByteCount(value); }
        catch (EncoderFallbackException) { throw InvalidResponse(); }
        string path = value == "/" ? value : value.TrimEnd('/');
        if (path.Length == 0) path = "/";
        if (path != "/" && path.Split('/').Skip(1).Any(segment => segment.Length == 0 || segment is "." or "..")) throw InvalidResponse();
        return path;
    }

    private static string CollectionName(string value)
    {
        if (string.IsNullOrWhiteSpace(value) || value.Contains('\0')) throw Safe(ErrorCode.InvalidRequest, "请输入有效名称。");
        try { _ = StrictUtf8.GetByteCount(value); }
        catch (EncoderFallbackException) { throw Safe(ErrorCode.InvalidRequest, "名称包含无效字符。"); }
        return value.Trim();
    }

    private static string CollectionColor(string value, bool allowEmpty)
    {
        if (value is null || value.Contains('\0') || !allowEmpty && string.IsNullOrWhiteSpace(value))
            throw Safe(ErrorCode.InvalidRequest, "请选择有效颜色。");
        // Existing colors remain server-owned data. Presets are a UI convenience,
        // not a new unique-color constraint or an implicit migration.
        return value.Trim();
    }

    private static string CollectionGroupId(string? id)
    {
        if (string.IsNullOrEmpty(id)) return "";
        _ = CollectionId(id);
        return id;
    }

    private static string CollectionId(string id)
    {
        if (string.IsNullOrWhiteSpace(id) || id.Contains('\0')) throw Safe(ErrorCode.InvalidRequest, "记录标识无效，请刷新后重新选择。");
        try { _ = StrictUtf8.GetByteCount(id); }
        catch (EncoderFallbackException) { throw Safe(ErrorCode.InvalidRequest, "记录标识无效，请刷新后重新选择。"); }
        return Uri.EscapeDataString(id);
    }

    private static string CollectionText(JsonElement row, string property) =>
        row.GetProperty(property).ValueKind == JsonValueKind.String ? row.GetProperty(property).GetString()! : throw InvalidResponse();

    private static string? CollectionOptionalText(JsonElement row, string property) => !row.TryGetProperty(property, out var value)
        ? null : value.ValueKind == JsonValueKind.String ? value.GetString() : throw InvalidResponse();

    private static DateTimeOffset? CollectionTimestamp(JsonElement row, string property) => row.TryGetProperty(property, out var value)
        ? DateTimeOffset.FromUnixTimeMilliseconds(value.GetInt64()) : null;

    private static void RequireDistinctCollectionIds(IEnumerable<string> ids)
    {
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (string id in ids) if (!seen.Add(id)) throw InvalidResponse();
    }

    private static bool CollectionFormatError(Exception error) => error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException or ArgumentOutOfRangeException;
}
