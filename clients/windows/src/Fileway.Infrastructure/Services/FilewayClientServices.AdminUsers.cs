using System.Globalization;
using System.Text.Json;
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
        Flag(perm, "modify"), Flag(perm, "delete"), Flag(perm, "execute"));

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
                ParsePermissions(row.GetProperty("perm")));
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    /// <summary>
    /// Server user preference defaults: the server deserializes a full record, so create/update
    /// never drop fields Windows does not yet manage.
    /// </summary>
    private static object AdminUserData(string username, string password, bool isAdmin, PermissionSet permissions,
        bool lockPassword, ulong id = 0) => new
    {
        id,
        username,
        password,
        scope = "",
        locale = "",
        viewMode = "mosaic",
        singleClick = false,
        hideDotfiles = false,
        dateFormat = false,
        redirectAfterCopyMove = false,
        lockPassword,
        perm = new Dictionary<string, bool>
        {
            ["admin"] = isAdmin,
            ["execute"] = permissions.Execute,
            ["create"] = permissions.Create,
            ["rename"] = permissions.Rename,
            ["modify"] = permissions.Modify,
            ["delete"] = permissions.Delete,
            ["share"] = false,
            ["download"] = permissions.Download,
        },
        commands = Array.Empty<string>(),
        rules = Array.Empty<object>(),
    };

    async Task<IReadOnlyList<AdminUser>> IAdminUserService.ListAsync(AccountKey account, CancellationToken cancellationToken)
    {
        var state = GetState(account);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        try
        {
            var response = await RequestAsync(state, "/api/users", linked.Token).ConfigureAwait(false);
            RequireExactStatus(response, 200);
            using var body = ParseBody(response);
            EnsureActive(state);
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
            }, requireDownload: false, HostCallSafety.Mutation, cancellationToken, operation: "request").ConfigureAwait(false);
            RequireExactStatus(response, 201);
            using var created = ParseBody(response);
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
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        try
        {
            var which = new List<string>();
            if (update.Password is not null) which.Add("password");
            if (update.IsAdmin is not null || update.Permissions is not null) which.Add("perm");
            if (update.LockPassword is not null) which.Add("lockPassword");
            if (which.Count == 0) throw Safe(ErrorCode.InvalidRequest, Message(ErrorCode.InvalidRequest));
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
                        lockPassword: update.LockPassword ?? false, id: update.Id),
                },
            }, requireDownload: false, HostCallSafety.Mutation, linked.Token, operation: "request").ConfigureAwait(false);
            RequireExactStatus(response, 200);
        }
        catch (OperationCanceledException) { throw SessionCanceled(state, cancellationToken); }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    async Task IAdminUserService.DeleteAsync(AccountKey account, ulong userId, string adminPassword, CancellationToken cancellationToken)
    {
        var state = GetState(account);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        try
        {
            var response = await SessionRequestAsync(state, new
            {
                method = "DELETE",
                endpoint = "/api/users/" + userId.ToString(CultureInfo.InvariantCulture),
                body = new { current_password = adminPassword ?? "" },
            }, requireDownload: false, HostCallSafety.Mutation, linked.Token, operation: "request").ConfigureAwait(false);
            RequireExactStatus(response, 200);
        }
        catch (OperationCanceledException) { throw SessionCanceled(state, cancellationToken); }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }
}
