using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Persistence;

/// <summary>Local metadata and durable logout intent. Never stores credentials.</summary>
public interface IProfileStore
{
    Task<IReadOnlyList<SavedConnection>> ListAsync(CancellationToken cancellationToken);
    Task<SavedConnection?> GetAsync(Guid profileId, CancellationToken cancellationToken);
    Task SaveAsync(SavedConnection connection, CancellationToken cancellationToken);
    Task ForgetAsync(AccountKey account, CancellationToken cancellationToken);
    Task<IReadOnlyList<AccountKey>> ListPendingLogoutsAsync(CancellationToken cancellationToken);
    Task BeginLogoutAsync(AccountKey account, CancellationToken cancellationToken);
    Task CompleteLogoutAsync(AccountKey account, CancellationToken cancellationToken);
}
