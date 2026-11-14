using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Credentials;

/// <summary>Account-bound password retention, separate from the token namespace.</summary>
public interface IPasswordVault
{
    Task<string?> ReadPasswordAsync(AccountKey account, CancellationToken cancellationToken);
    Task WritePasswordAsync(AccountKey account, string password, CancellationToken cancellationToken);
    Task DeletePasswordAsync(AccountKey account, CancellationToken cancellationToken);
}
