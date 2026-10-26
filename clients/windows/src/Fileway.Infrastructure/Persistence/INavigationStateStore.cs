using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Persistence;

public interface INavigationStateStore
{
    Task<string?> ReadLastDirectoryAsync(AccountKey account, CancellationToken cancellationToken);
    Task WriteLastDirectoryAsync(AccountKey account, string wirePath, CancellationToken cancellationToken);
}
