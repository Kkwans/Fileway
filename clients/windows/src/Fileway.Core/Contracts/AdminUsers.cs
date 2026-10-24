namespace Fileway.Core.Contracts;

/// <summary>One server user as returned by the admin user API (no password is ever returned).</summary>
public sealed record AdminUser(
    ulong Id, string Username, bool IsAdmin, bool LockPassword, bool HideDotfiles, bool SingleClick,
    PermissionSet Permissions)
{
    public override string ToString() => "AdminUser (server user redacted)";
}

/// <summary>New-user payload for creation; preferences take server-compatible defaults.</summary>
public sealed record AdminUserCreate(string Username, string Password, bool IsAdmin, PermissionSet Permissions);

/// <summary>Selected admin edits for one user; only the named fields are written.</summary>
public sealed record AdminUserUpdate(ulong Id, string? Password, bool? IsAdmin, bool? LockPassword, PermissionSet? Permissions);

/// <summary>
/// Administrator user management over the server's own user API. Every mutating call carries the
/// admin's current password as the server requires; the server remains the permission authority.
/// </summary>
public interface IAdminUserService
{
    bool IsAvailable(AccountKey account);

    Task<IReadOnlyList<AdminUser>> ListAsync(AccountKey account, CancellationToken cancellationToken);

    Task<ulong> CreateAsync(AccountKey account, AdminUserCreate create, string adminPassword, CancellationToken cancellationToken);

    Task UpdateAsync(AccountKey account, AdminUserUpdate update, string adminPassword, CancellationToken cancellationToken);

    Task DeleteAsync(AccountKey account, ulong userId, string adminPassword, CancellationToken cancellationToken);
}
