using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Persistence;

internal static class ProfileStoreValidation
{
    internal static void Validate(AccountKey account)
    {
        if (account.ProfileId == Guid.Empty || account.SourceRevision == Guid.Empty || string.IsNullOrEmpty(account.ServerUserId))
            throw Unavailable();
    }

    internal static void Validate(SavedConnection connection)
    {
        if (connection is null || connection.Profile is null || connection.Profile.Proxy is null ||
            connection.Profile.DisplayName is null || connection.Username is null ||
            !IsSafeAddress(connection.Profile.BaseAddress) ||
            connection.Profile.Proxy.ManualAddress is { } manual && !IsSafeAddress(manual) ||
            !Enum.IsDefined(connection.Profile.Network) || !Enum.IsDefined(connection.Profile.Proxy.Mode) ||
            !Enum.IsDefined(connection.AuthenticationMode))
            throw Unavailable();
        Validate(connection.Account);
    }

    private static bool IsSafeAddress(Uri? address) => address is { IsAbsoluteUri: true } &&
        address.Scheme is "http" or "https" && address.UserInfo.Length == 0 &&
        address.Query.Length == 0 && address.Fragment.Length == 0;

    internal static FilewayException Unavailable() => new(new ErrorInfo(
        ErrorCode.LocalStorageUnavailable, "无法访问本地连接资料，请稍后重试。"));
}
