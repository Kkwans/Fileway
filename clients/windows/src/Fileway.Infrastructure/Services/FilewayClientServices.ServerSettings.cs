using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices : IServerSettingsService
{
    private const string ServerSettingsEndpoint = "/api/settings";
    private enum SettingKind { Flag, Text, Number, Duration, Lines }
    private sealed record SettingWire(string Path, SettingKind Kind);
    private static readonly Dictionary<ServerSettingField, SettingWire> SettingWires = new()
    {
        [ServerSettingField.Signup] = new("signup", SettingKind.Flag),
        [ServerSettingField.HideLoginButton] = new("hideLoginButton", SettingKind.Flag),
        [ServerSettingField.CreateUserDir] = new("createUserDir", SettingKind.Flag),
        [ServerSettingField.UserHomeBasePath] = new("userHomeBasePath", SettingKind.Text),
        [ServerSettingField.MinimumPasswordLength] = new("minimumPasswordLength", SettingKind.Number),
        [ServerSettingField.SessionMinutes] = new("tokenExpirationTime", SettingKind.Duration),
        [ServerSettingField.DefaultScope] = new("defaults.scope", SettingKind.Text),
        [ServerSettingField.DefaultLocale] = new("defaults.locale", SettingKind.Text),
        [ServerSettingField.DefaultView] = new("defaults.viewMode", SettingKind.Text),
        [ServerSettingField.DefaultSingleClick] = new("defaults.singleClick", SettingKind.Flag),
        [ServerSettingField.DefaultRedirect] = new("defaults.redirectAfterCopyMove", SettingKind.Flag),
        [ServerSettingField.DefaultDateFormat] = new("defaults.dateFormat", SettingKind.Flag),
        [ServerSettingField.DefaultHideDotfiles] = new("defaults.hideDotfiles", SettingKind.Flag),
        [ServerSettingField.DefaultEditorTheme] = new("defaults.aceEditorTheme", SettingKind.Text),
        [ServerSettingField.DefaultSortBy] = new("defaults.sorting.by", SettingKind.Text),
        [ServerSettingField.DefaultSortAscending] = new("defaults.sorting.asc", SettingKind.Flag),
        [ServerSettingField.DefaultAdmin] = new("defaults.perm.admin", SettingKind.Flag),
        [ServerSettingField.DefaultDownload] = new("defaults.perm.download", SettingKind.Flag),
        [ServerSettingField.DefaultShare] = new("defaults.perm.share", SettingKind.Flag),
        [ServerSettingField.DefaultCreate] = new("defaults.perm.create", SettingKind.Flag),
        [ServerSettingField.DefaultRename] = new("defaults.perm.rename", SettingKind.Flag),
        [ServerSettingField.DefaultModify] = new("defaults.perm.modify", SettingKind.Flag),
        [ServerSettingField.DefaultDelete] = new("defaults.perm.delete", SettingKind.Flag),
        [ServerSettingField.DefaultExecute] = new("defaults.perm.execute", SettingKind.Flag),
        [ServerSettingField.BrandName] = new("branding.name", SettingKind.Text),
        [ServerSettingField.BrandFiles] = new("branding.files", SettingKind.Text),
        [ServerSettingField.BrandTheme] = new("branding.theme", SettingKind.Text),
        [ServerSettingField.BrandColor] = new("branding.color", SettingKind.Text),
        [ServerSettingField.BrandDisableExternal] = new("branding.disableExternal", SettingKind.Flag),
        [ServerSettingField.BrandHideUsage] = new("branding.disableUsedPercentage", SettingKind.Flag),
        [ServerSettingField.UploadChunk] = new("tus.chunkSize", SettingKind.Number),
        [ServerSettingField.UploadRetries] = new("tus.retryCount", SettingKind.Number),
        [ServerSettingField.DefaultCommands] = new("defaults.commands", SettingKind.Lines),
        [ServerSettingField.Shell] = new("shell", SettingKind.Lines),
        [ServerSettingField.BeforeCopy] = new("commands.before_copy", SettingKind.Lines),
        [ServerSettingField.AfterCopy] = new("commands.after_copy", SettingKind.Lines),
        [ServerSettingField.BeforeDelete] = new("commands.before_delete", SettingKind.Lines),
        [ServerSettingField.AfterDelete] = new("commands.after_delete", SettingKind.Lines),
        [ServerSettingField.BeforeRename] = new("commands.before_rename", SettingKind.Lines),
        [ServerSettingField.AfterRename] = new("commands.after_rename", SettingKind.Lines),
        [ServerSettingField.BeforeSave] = new("commands.before_save", SettingKind.Lines),
        [ServerSettingField.AfterSave] = new("commands.after_save", SettingKind.Lines),
        [ServerSettingField.BeforeUpload] = new("commands.before_upload", SettingKind.Lines),
        [ServerSettingField.AfterUpload] = new("commands.after_upload", SettingKind.Lines),
    };

    public async Task<ServerSettingsSnapshot> ReadServerSettingsAsync(AccountKey account, CancellationToken cancellationToken)
    {
        var state = GetState(account);
        RequireServerAdmin(state);
        var response = await RequestAsync(state, ServerSettingsEndpoint, cancellationToken).ConfigureAwait(false);
        RequireServerAdmin(state);
        return ReadServerSettingsSnapshot(ReadServerSettingsBody(response), state);
    }

    public async Task<ServerSettingsSnapshot> SaveServerSettingsAsync(AccountKey account, ServerSettingsSnapshot snapshot,
        ServerSettingsEdit edit, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(snapshot);
        ArgumentNullException.ThrowIfNull(edit);
        var state = GetState(account);
        RequireServerAdmin(state);
        if (state.Session.Id != snapshot.SessionId)
            throw Safe(ErrorCode.SourceChanged, "账户连接已更换，请重新读取服务器设置。");
        if (edit.Values.Count == 0 && edit.Rules is null) return snapshot;

        var payload = new JsonObject();
        bool dispatching = false;
        try
        {
            await SessionRequestAsync(state, new { method = "PUT", endpoint = ServerSettingsEndpoint, body = payload },
                requireDownload: false, HostCallSafety.Mutation, cancellationToken,
                beforeSend: async token =>
                {
                    RequireServerAdmin(state);
                    // Read the complete current snapshot inside the session write gate.
                    // This server has no conditional PUT, so reject known conflicts before dispatch.
                    var latest = ReadServerSettingsBody(await RequestAsync(state.CoreSession, ServerSettingsEndpoint, token).ConfigureAwait(false));
                    if (ServerSettingsRevision(latest) != snapshot.Revision)
                        throw Safe(ErrorCode.Conflict, "服务器设置已在其他位置修改，请重新读取后再编辑。");
                    foreach (var (name, value) in latest) payload.Add(name, value?.DeepClone());
                    foreach (var (field, value) in edit.Values)
                    {
                        if (!SettingWires.TryGetValue(field, out var wire)) throw InvalidServerSetting();
                        SetSetting(payload, wire.Path, ConvertSetting(field, wire.Kind, value));
                    }
                    if (ReadSetting(payload, SettingWires[ServerSettingField.DefaultShare].Path)?.GetValue<bool>() == true &&
                        ReadSetting(payload, SettingWires[ServerSettingField.DefaultDownload].Path)?.GetValue<bool>() != true)
                        throw Safe(ErrorCode.InvalidRequest, "默认分享权限需要同时启用下载权限。");
                    if (edit.Rules is not null) payload["rules"] = WriteServerRules(edit.Rules, latest["rules"]);
                },
                authorizeSend: () =>
                {
                    RequireServerAdmin(state);
                    dispatching = true;
                },
                confirmResponse: response => RequireExactStatus(response, 200)).ConfigureAwait(false);
        }
        catch (FilewayException error) when (dispatching &&
            (error.Error.HttpStatus is not (>= 400 and < 500) || error.Error.Code == ErrorCode.OutcomeUnknown))
        {
            // An uncertain write is never replayed. One bounded, independent read
            // can confirm the requested values even after the caller's request timed out.
            using var recovery = CancellationTokenSource.CreateLinkedTokenSource(state.LifetimeToken);
            recovery.CancelAfter(TimeSpan.FromSeconds(6));
            try
            {
                var response = await RequestAsync(state, ServerSettingsEndpoint, recovery.Token).ConfigureAwait(false);
                RequireServerAdmin(state);
                var actual = ReadServerSettingsBody(response);
                if (edit.Values.Keys.All(key => JsonNode.DeepEquals(ReadSetting(actual, SettingWires[key].Path),
                        ReadSetting(payload, SettingWires[key].Path))) &&
                    (edit.Rules is null || ReadServerRules(actual["rules"]).SequenceEqual(edit.Rules)))
                    return ReadServerSettingsSnapshot(actual, state);
            }
            catch (Exception) { /* Preserve the unknown outcome; the UI requires a fresh read. */ }
            throw Safe(ErrorCode.OutcomeUnknown, "保存结果尚未确认。请重新读取服务器设置核对，应用不会重复提交。");
        }
        // A confirmed PUT is already complete even when a subsequent GET is unavailable.
        return ReadServerSettingsSnapshot(payload, state);
    }

    private void RequireServerAdmin(SessionState state)
    {
        EnsureActive(state);
        if (!state.Session.Permissions.Admin)
            throw Safe(ErrorCode.PermissionDenied, "当前账户没有管理服务器的权限。");
    }

    private static JsonObject ReadServerSettingsBody(JsonElement response)
    {
        RequireExactStatus(response, 200);
        try { return JsonNode.Parse(HttpBody(response)) as JsonObject ?? throw InvalidResponse(); }
        catch (JsonException) { throw InvalidResponse(); }
    }

    private static string ServerSettingsRevision(JsonObject body) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(CanonicalSetting(body)!.ToJsonString())));

    private static JsonNode? CanonicalSetting(JsonNode? node) => node switch
    {
        JsonObject obj => new JsonObject(obj.OrderBy(item => item.Key, StringComparer.Ordinal)
            .Select(item => KeyValuePair.Create(item.Key, CanonicalSetting(item.Value)))),
        JsonArray array => new JsonArray(array.Select(CanonicalSetting).ToArray()),
        _ => node?.DeepClone(),
    };

    private static ServerSettingsSnapshot ReadServerSettingsSnapshot(JsonObject body, SessionState state)
    {
        try
        {
            var values = new Dictionary<ServerSettingField, string>();
            foreach (var (field, wire) in SettingWires)
            {
                var value = ReadSetting(body, wire.Path);
                values.Add(field, wire.Kind switch
                {
                    SettingKind.Flag => (value?.GetValue<bool>() ?? false).ToString(),
                    SettingKind.Number => value?.GetValue<ulong>().ToString(CultureInfo.InvariantCulture) ?? "",
                    SettingKind.Duration => ReadSessionMinutes(value?.GetValue<string>() ?? "").ToString("0.########", CultureInfo.InvariantCulture),
                    SettingKind.Lines => string.Join('\n', ReadServerStrings(value)),
                    _ => value?.GetValue<string>() ?? "",
                });
            }
            return new(state.Session.Id, ServerSettingsRevision(body), body["authMethod"]?.GetValue<string>() ?? "",
                state.Session.Capabilities.EnableExec, values, ReadServerRules(body["rules"]));
        }
        catch (Exception error) when (error is InvalidOperationException or FormatException or JsonException or OverflowException)
        { throw InvalidResponse(); }
    }

    private static JsonNode? ReadSetting(JsonObject source, string path)
    {
        JsonNode? value = source;
        foreach (var key in path.Split('.'))
            value = value is JsonObject obj ? obj[key] : null;
        return value;
    }

    private static void SetSetting(JsonObject source, string path, JsonNode value)
    {
        var keys = path.Split('.');
        var section = source;
        foreach (var key in keys.AsSpan(0, keys.Length - 1))
        {
            if (section[key] is null) section[key] = new JsonObject();
            section = section[key] as JsonObject ?? throw InvalidResponse();
        }
        section[keys[^1]] = value;
    }

    private static JsonNode ConvertSetting(ServerSettingField field, SettingKind kind, string value)
    {
        if (value.Length > 32_768 || value.Contains('\0')) throw InvalidServerSetting();
        switch (kind)
        {
            case SettingKind.Flag when bool.TryParse(value, out bool flag):
                return JsonValue.Create(flag);
            case SettingKind.Text when value.Length <= 4096:
                return JsonValue.Create(value);
            case SettingKind.Lines:
                return JsonSerializer.SerializeToNode(SplitServerLines(value))!;
            case SettingKind.Number when ulong.TryParse(value, NumberStyles.None, CultureInfo.InvariantCulture, out ulong number):
                if (field == ServerSettingField.MinimumPasswordLength && (number == 0 || number > int.MaxValue) ||
                    field == ServerSettingField.UploadRetries && number > ushort.MaxValue) throw InvalidServerSetting();
                return JsonValue.Create(number);
            case SettingKind.Duration when decimal.TryParse(value, NumberStyles.AllowDecimalPoint, CultureInfo.InvariantCulture, out decimal minutes)
                && minutes is >= 10 and <= 43200:
                return JsonValue.Create(minutes.ToString("0.########", CultureInfo.InvariantCulture) + "m");
            default:
                throw InvalidServerSetting();
        }
    }

    private static decimal ReadSessionMinutes(string duration)
    {
        if (duration.StartsWith('+')) duration = duration[1..];
        decimal total = 0;
        int offset = 0;
        foreach (Match match in GoDurationParts().Matches(duration))
        {
            if (match.Index != offset ||
                !decimal.TryParse(match.Groups[1].Value, NumberStyles.AllowDecimalPoint, CultureInfo.InvariantCulture, out decimal number))
                throw InvalidResponse();
            offset += match.Length;
            total += number * (match.Groups[2].Value switch
            {
                "h" => 60m, "m" => 1m, "s" => 1m / 60m, "ms" => 1m / 60_000m,
                "us" or "µs" or "μs" => 1m / 60_000_000m, _ => 1m / 60_000_000_000m,
            });
        }
        if (offset != duration.Length || total is < 10 or > 43200) throw InvalidResponse();
        return total;
    }

    [GeneratedRegex(@"([0-9]+(?:\.[0-9]*)?|\.[0-9]+)(ns|us|µs|μs|ms|s|m|h)", RegexOptions.CultureInvariant)]
    private static partial Regex GoDurationParts();

    private static string[] SplitServerLines(string value) =>
        value.ReplaceLineEndings("\n").Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries);

    private static List<string> ReadServerStrings(JsonNode? node) => node is null ? [] :
        (node as JsonArray ?? throw InvalidResponse()).Select(value => value?.GetValue<string>() ?? throw InvalidResponse()).ToList();

    private static List<ServerPathRule> ReadServerRules(JsonNode? node)
    {
        if (node is null) return [];
        var rules = node as JsonArray ?? throw InvalidResponse();
        var result = new List<ServerPathRule>(rules.Count);
        foreach (var rule in rules) result.Add(ReadServerRule(rule));
        return result;
    }

    private static ServerPathRule ReadServerRule(JsonNode? node)
    {
        if (node is not JsonObject rule) throw InvalidResponse();
        bool regex = rule["regex"]?.GetValue<bool>() ?? false;
        return new(rule["allow"]?.GetValue<bool>() ?? false, regex,
            (regex ? ReadSetting(rule, "regexp.raw") : rule["path"])?.GetValue<string>() ?? "");
    }

    private static JsonArray WriteServerRules(IReadOnlyList<ServerPathRule> rules, JsonNode? previous = null)
    {
        if (rules.Count > 1000) throw InvalidServerSetting();
        var original = new Dictionary<ServerPathRule, Queue<JsonNode>>();
        if (previous is JsonArray entries)
        {
            foreach (var entry in entries)
            {
                var rule = ReadServerRule(entry);
                if (!original.TryGetValue(rule, out var queue)) original.Add(rule, queue = new());
                queue.Enqueue(entry!);
            }
        }
        var data = new JsonArray();
        foreach (var rule in rules)
        {
            if (original.TryGetValue(rule, out var queue) && queue.TryDequeue(out var retained))
            {
                data.Add(retained.DeepClone());
                continue;
            }
            if (rule.Match.Length is 0 or > 4096 || rule.Match.Contains('\0') ||
                !rule.RegularExpression && !rule.Match.StartsWith('/')) throw InvalidServerSetting();
            data.Add(new JsonObject
            {
                ["allow"] = rule.Allow, ["regex"] = rule.RegularExpression,
                ["path"] = rule.RegularExpression ? "" : rule.Match,
                ["regexp"] = rule.RegularExpression ? new JsonObject { ["raw"] = rule.Match } : null,
            });
        }
        return data;
    }

    private static FilewayException InvalidServerSetting() =>
        Safe(ErrorCode.InvalidRequest, "请检查数字范围、会话期限与路径规则。路径规则应以 / 开头，正则由服务器校验。");
}
