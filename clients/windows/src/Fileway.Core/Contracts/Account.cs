namespace Fileway.Core.Contracts;

/// <summary>Outcome of one self-service password change.</summary>
public sealed record PasswordChangeResult(string Username, bool LocalCredentialUpdateFailed = false);

/// <summary>
/// Self-service account security for password-auth servers: changes the signed-in user's own
/// password via the server's user API. The current password is always required by the server;
/// no direct user enumeration, creation or deletion passes through here.
/// </summary>
public interface IAccountSecurityService
{
    /// <summary>True when the server authenticates with passwords (json auth), making change meaningful.</summary>
    bool SupportsPasswordChange(AccountKey account);

    Task<PasswordChangeResult> ChangePasswordAsync(AccountKey account, string currentPassword, string newPassword,
        CancellationToken cancellationToken);
}
