using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices : IAdminUserService
{
    public bool IsAdminAvailable(AccountKey account)
    {
        var state = GetState(account);
        lock (_gate)
        {
            EnsureActive(state);
            return state.Session.Permissions.Admin;
        }
    }

    bool IAdminUserService.IsAvailable(AccountKey account) => IsAdminAvailable(account);

    private static PermissionSet ParsePermissions(JsonElement perm) => new(
        Flag(perm, "download"), Flag(perm, "create"), Flag(perm, "rename"),
        Flag(perm, "modify"), Flag(perm, "delete"), Flag(perm, "execute"), Flag(perm, "admin"), Flag(perm, "share"));

    private static AdminUser ParseAdminUser(JsonElement row)
    {
        try
        {
            return new AdminUser(
                row.GetProperty("id").GetUInt64(),
                RequiredText(row.GetProperty("username")),
                Flag(row.GetProperty("perm"), "admin"),
                row.TryGetProperty("lockPassword", out var locked) && locked.GetBoolean(),
                row.TryGetProperty("hideDotfiles", out var hidden) && hidden.GetBoolean(),
                row.TryGetProperty("singleClick", out var click) && click.GetBoolean(),
                ParsePermissions(row.GetProperty("perm")),
                OptionalText(row, "scope") ?? "",
                row.TryGetProperty("commands", out var commands) ? ReadServerStrings(JsonNode.Parse(commands.GetRawText())) : [],
                row.TryGetProperty("rules", out var rules) ? ReadServerRules(JsonNode.Parse(rules.GetRawText())) : []);
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    /// <summary>
    /// Server user preference defaults: the server deserializes a full record, so create/update
    /// never drop fields Windows does not yet manage.
    /// </summary>
    private static object AdminUserData(string username, string password, bool isAdmin, PermissionSet permissions,
        bool lockPassword, ulong id = 0, Dictionary<string, bool>? permissionData = null, string? scope = null,
        IReadOnlyList<string>? allowedCommands = null, JsonArray? pathRules = null) => new
    {
        id,
        username,
        password,
        scope = scope ?? "",
        locale = "",
        viewMode = "mosaic",
        singleClick = false,
        hideDotfiles = false,
        dateFormat = false,
        redirectAfterCopyMove = false,
        lockPassword,
        perm = permissionData ?? new Dictionary<string, bool>
        {
            ["admin"] = isAdmin,
            ["execute"] = permissions.Execute,
            ["create"] = permissions.Create,
            ["rename"] = permissions.Rename,
            ["modify"] = permissions.Modify,
            ["delete"] = permissions.Delete,
            ["share"] = permissions.Share,
            ["download"] = permissions.Download,
        },
        commands = allowedCommands ?? Array.Empty<string>(),
        rules = pathRules ?? new JsonArray(),
    };

    async Task<IReadOnlyList<AdminUser>> IAdminUserService.ListAsync(AccountKey account, CancellationToken cancellationToken)
    {
        var state = GetState(account);
        RequireServerAdmin(state);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        try
        {
            var response = await RequestAsync(state, "/api/users", linked.Token).ConfigureAwait(false);
            RequireExactStatus(response, 200);
            using var body = ParseBody(response);
            RequireServerAdmin(state);
            var users = new List<AdminUser>();
            foreach (var row in body.RootElement.EnumerateArray()) users.Add(ParseAdminUser(row));
            return users;
        }
        catch (OperationCanceledException) { throw SessionCanceled(state, cancellationToken); }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    async Task<ulong> IAdminUserService.CreateAsync(AccountKey account, AdminUserCreate create, string adminPassword,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(create);
        var state = GetState(account);
        RequireServerAdmin(state);
        if (create.Permissions.Share && !create.Permissions.Download)
            throw Safe(ErrorCode.InvalidRequest, "分享权限需要同时启用下载权限。");
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        try
        {
            var response = await SessionRequestAsync(state, new
            {
                method = "POST",
                endpoint = "/api/users",
                body = new
                {
                    what = "user",
                    which = Array.Empty<string>(),
                    current_password = adminPassword ?? "",
                    data = AdminUserData(create.Username, create.Password, create.IsAdmin, create.Permissions, lockPassword: false),
                },
            }, requireDownload: false, HostCallSafety.Mutation, cancellationToken, operation: "request",
                authorizeSend: () => RequireServerAdmin(state), checkKnownRejection: CheckAdminPasswordRejection).ConfigureAwait(false);
            RequireExactStatus(response, 201);
            // The Location header is not proxied; resolve the new id from the username listing.
            var users = await ((IAdminUserService)this).ListAsync(account, linked.Token).ConfigureAwait(false);
            lock (_gate)
            {
                linked.Token.ThrowIfCancellationRequested();
                EnsureActive(state);
            }
            return users.FirstOrDefault(user => string.Equals(user.Username, create.Username, StringComparison.Ordinal))?.Id
                ?? throw Safe(ErrorCode.OutcomeUnknown, "用户已创建但未能确认其编号，请刷新后查看。");
        }
        catch (OperationCanceledException) { throw SessionCanceled(state, cancellationToken); }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    async Task IAdminUserService.UpdateAsync(AccountKey account, AdminUserUpdate update, string adminPassword,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(update);
        var state = GetState(account);
        RequireServerAdmin(state);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        try
        {
            var which = new List<string>();
            if (update.Password is not null) which.Add("password");
            if (update.IsAdmin is not null || update.Permissions is not null || update.SharePermission is not null) which.Add("perm");
            if (update.LockPassword is not null) which.Add("lockPassword");
            if (update.Scope is { } scope)
            {
                if (scope.Length > 4096 || scope.Contains('\0')) throw InvalidServerSetting();
                which.Add("scope");
            }
            if (update.Commands is { } commands)
            {
                if (commands.Count > 256 || commands.Any(command => command.Length > 4096 || command.Contains('\0')))
                    throw InvalidServerSetting();
                which.Add("commands");
            }
            if (update.Rules is not null) which.Add("rules");
            if (which.Count == 0) throw Safe(ErrorCode.InvalidRequest, Message(ErrorCode.InvalidRequest));
            Dictionary<string, bool>? permissionData = which.Contains("perm") ? new(StringComparer.Ordinal) : null;
            JsonArray? rulesData = update.Rules is not null ? new() : null;
            Func<CancellationToken, Task>? beforeSend = permissionData is not null || rulesData is not null || update.ExpectedPolicy is not null
                ? token => ReadAdminPolicyUpdateAsync(state, update, permissionData, rulesData, token) : null;
            // which fields are sensitive for JSON-auth servers: the admin's current password is required.
            var response = await SessionRequestAsync(state, new
            {
                method = "PUT",
                endpoint = "/api/users/" + update.Id.ToString(CultureInfo.InvariantCulture),
                body = new
                {
                    what = "user",
                    which = which.ToArray(),
                    current_password = adminPassword ?? "",
                    data = AdminUserData(username: "", password: update.Password ?? "", isAdmin: update.IsAdmin ?? false,
                        permissions: update.Permissions ?? new PermissionSet(true, false, false, false, false, false),
                        lockPassword: update.LockPassword ?? false, id: update.Id, permissionData: permissionData,
                        scope: update.Scope, allowedCommands: update.Commands, pathRules: rulesData),
                },
            }, requireDownload: false, HostCallSafety.Mutation, linked.Token, operation: "request", beforeSend: beforeSend,
                authorizeSend: () => RequireServerAdmin(state), checkKnownRejection: CheckAdminPasswordRejection).ConfigureAwait(false);
            RequireExactStatus(response, 200);
        }
        catch (OperationCanceledException) { throw SessionCanceled(state, cancellationToken); }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    // Called under SessionRequestAsync. The server's `which=perm` replaces the
    // whole permission object, so preserve every current flag not explicitly edited.
    private async Task ReadAdminPolicyUpdateAsync(SessionState state, AdminUserUpdate update,
        Dictionary<string, bool>? permissions, JsonArray? rules, CancellationToken cancellationToken)
    {
        RequireServerAdmin(state);
        var response = await RequestAsync(state.CoreSession,
            "/api/users/" + update.Id.ToString(CultureInfo.InvariantCulture), cancellationToken).ConfigureAwait(false);
        RequireExactStatus(response, 200);
        using var body = ParseBody(response);
        var row = body.RootElement;
        if (!row.GetProperty("id").TryGetUInt64(out ulong id) || id != update.Id) throw InvalidResponse();
        if (update.ExpectedPolicy is { } expected)
        {
            var current = ParseAdminUser(row);
            if (expected.Id != current.Id || expected.Username != current.Username ||
                permissions is not null && expected.Permissions != current.Permissions ||
                update.Scope is not null && expected.Scope != current.Scope ||
                update.Commands is not null && !(expected.Commands ?? []).SequenceEqual(current.Commands ?? []) ||
                rules is not null && !(expected.Rules ?? []).SequenceEqual(current.Rules ?? []))
                throw Safe(ErrorCode.Conflict, "此用户的策略已在其他位置修改，请重新读取后编辑。");
        }
        if (rules is not null && update.Rules is not null)
        {
            var original = row.TryGetProperty("rules", out var raw) ? JsonNode.Parse(raw.GetRawText()) : null;
            foreach (var rule in WriteServerRules(update.Rules, original)) rules.Add(rule?.DeepClone());
        }
        if (permissions is null) return;
        foreach (var flag in row.GetProperty("perm").EnumerateObject())
        {
            if (flag.Value.ValueKind is not (JsonValueKind.True or JsonValueKind.False) ||
                !permissions.TryAdd(flag.Name, flag.Value.GetBoolean())) throw InvalidResponse();
        }
        if (update.IsAdmin is { } admin) permissions["admin"] = admin;
        if (update.SharePermission is { } share) permissions["share"] = share;
        if (update.Permissions is { } requested)
        {
            permissions["download"] = requested.Download;
            permissions["create"] = requested.Create;
            permissions["rename"] = requested.Rename;
            permissions["modify"] = requested.Modify;
            permissions["delete"] = requested.Delete;
            permissions["execute"] = requested.Execute;
        }
        if (permissions.GetValueOrDefault("share") && !permissions.GetValueOrDefault("download"))
            throw Safe(ErrorCode.InvalidRequest, "分享权限需要同时启用下载权限。");
    }

    async Task IAdminUserService.DeleteAsync(AccountKey account, ulong userId, string adminPassword, CancellationToken cancellationToken)
    {
        var state = GetState(account);
        RequireServerAdmin(state);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        try
        {
            var response = await SessionRequestAsync(state, new
            {
                method = "DELETE",
                endpoint = "/api/users/" + userId.ToString(CultureInfo.InvariantCulture),
                body = new { current_password = adminPassword ?? "" },
            }, requireDownload: false, HostCallSafety.Mutation, linked.Token, operation: "request",
                authorizeSend: () => RequireServerAdmin(state), checkKnownRejection: CheckAdminPasswordRejection).ConfigureAwait(false);
            RequireExactStatus(response, 200);
        }
        catch (OperationCanceledException) { throw SessionCanceled(state, cancellationToken); }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    private static void CheckAdminPasswordRejection(JsonElement response)
    {
        if (response.ValueKind == JsonValueKind.Object &&
            response.TryGetProperty("status", out var status) && status.ValueKind == JsonValueKind.Number &&
            status.TryGetInt32(out int code) && code == 400 &&
            response.TryGetProperty("body", out var body) && body.ValueKind == JsonValueKind.String &&
            body.GetString()?.Trim() is "400 当前密码不正确" or "当前密码不正确")
            throw Safe(ErrorCode.InvalidRequest, "当前管理员密码不正确，请重新输入。", 400);
    }
}
