using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Credentials;

/// <summary>Account-bound token storage. Passwords must never be written here.</summary>
public interface ICredentialVault
{
    Task<string?> ReadTokenAsync(AccountKey account, CancellationToken cancellationToken);
    Task WriteTokenAsync(AccountKey account, string token, CancellationToken cancellationToken);
    Task DeleteTokenAsync(AccountKey account, CancellationToken cancellationToken);
}
