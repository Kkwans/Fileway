using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Persistence;

/// <summary>Device preferences in the same database and migration boundary as saved connections.</summary>
public interface IStorageSettingsStore
{
    Task<ClientStorageSettings?> ReadStorageSettingsAsync(CancellationToken cancellationToken);
    Task SaveStorageSettingsAsync(ClientStorageSettings settings, CancellationToken cancellationToken);
}
