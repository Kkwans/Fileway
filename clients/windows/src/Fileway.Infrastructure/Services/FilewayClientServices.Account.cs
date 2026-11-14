using System.Globalization;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices : IAccountSecurityService
{
    private static readonly string[] PasswordOnlyWhich = ["password"];
    public bool SupportsPasswordChange(AccountKey account)
    {
        var state = GetState(account);
        lock (_gate)
        {
            EnsureActive(state);
            return string.Equals(state.Session.Capabilities.AuthMethod, "json", StringComparison.OrdinalIgnoreCase);
        }
    }

    public async Task<PasswordChangeResult> ChangePasswordAsync(AccountKey account, string currentPassword,
        string newPassword, CancellationToken cancellationToken)
    {
        var state = GetState(account);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        string replacement = newPassword ?? "";
        try
        {
            lock (_gate)
            {
                EnsureActive(state);
                if (!string.Equals(state.Session.Capabilities.AuthMethod, "json", StringComparison.OrdinalIgnoreCase))
                    throw Safe(ErrorCode.CapabilityMissing, "当前服务器不使用密码认证，无法修改密码。");
            }
            // The numeric id comes from our own signed token, not from any listing call.
            string userId = ReadTokenUserId(state.VerifiedToken);
            string username = state.Session.DisplayName;
            var response = await SessionRequestAsync(state, new
            {
                method = "PUT",
                endpoint = "/api/users/" + Uri.EscapeDataString(userId),
                body = new
                {
                    what = "user",
                    which = PasswordOnlyWhich,
                    current_password = currentPassword ?? "",
                    data = new Dictionary<string, object?>
                    {
                        ["id"] = ulong.Parse(userId, CultureInfo.InvariantCulture),
                        ["username"] = username,
                        ["password"] = replacement,
                    },
                },
            }, requireDownload: false, HostCallSafety.Mutation, linked.Token, operation: "request").ConfigureAwait(false);
            RequireExactStatus(response, 200);
            // The server mutation is already confirmed. A vault failure is a separate warning,
            // never a fabricated failure of the password change or permission to replay it.
            bool localFailure = false;
            await _persistence.WaitAsync(CancellationToken.None).ConfigureAwait(false);
            try
            {
                var saved = await StorageAsync(() => _profiles.GetAsync(account.ProfileId, CancellationToken.None)).ConfigureAwait(false);
                var pending = await StorageAsync(() => _profiles.ListPendingLogoutsAsync(CancellationToken.None)).ConfigureAwait(false);
                if (saved?.Account == account && saved.KeepSignedIn && saved.AuthenticationMode == AuthenticationMode.Password && !pending.Contains(account))
                {
                    EnsureActive(state);
                    if (_passwords is null) localFailure = true;
                    else await CredentialsAsync(() => _passwords.WritePasswordAsync(account, replacement, CancellationToken.None)).ConfigureAwait(false);
                }
            }
            catch (FilewayException) { localFailure = true; }
            finally { _persistence.Release(); }
            return new PasswordChangeResult(state.Session.DisplayName, localFailure);
        }
        catch (FilewayException error) when (error.Error.HttpStatus == 400)
        { throw Safe(ErrorCode.PermissionDenied, "当前密码不正确，密码未修改。", 400); }
        catch (OperationCanceledException) { throw SessionCanceled(state, cancellationToken); }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }
}
