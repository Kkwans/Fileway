using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Credentials;

internal static class CredentialValidation
{
    internal static void Validate(AccountKey account)
    {
        if (account.ProfileId == Guid.Empty || account.SourceRevision == Guid.Empty || string.IsNullOrEmpty(account.ServerUserId))
            throw Unavailable();
    }

    internal static FilewayException Unavailable() => new(new ErrorInfo(
        ErrorCode.CredentialsUnavailable, "无法访问本地登录凭据，请重新登录或稍后重试。"));
}
