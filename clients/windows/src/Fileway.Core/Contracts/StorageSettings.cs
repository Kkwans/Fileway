namespace Fileway.Core.Contracts;

/// <summary>Per-device locations and one total cache data budget. Downloaded files are not cache.</summary>
public sealed record ClientStorageSettings(
    string CacheDirectory,
    string DownloadDirectory,
    long MaximumCacheBytes = ClientStorageSettings.DefaultMaximumCacheBytes)
{
    public const long DefaultMaximumCacheBytes = 5_000_000_000;
    public const long MaximumSupportedCacheBytes = 1L << 40;
    public override string ToString() => "ClientStorageSettings (private locations redacted)";
}

/// <summary>Directory changes are pending until restart; effective values must be shown honestly.</summary>
public sealed record StorageSettingsState(ClientStorageSettings Active, ClientStorageSettings Pending, bool RestartRequired)
{
    public override string ToString() => "StorageSettingsState (private locations redacted)";
}

public interface ILocalStorageSettingsService
{
    Task<StorageSettingsState> ReadAsync(CancellationToken cancellationToken);
    Task<StorageSettingsState> SaveAsync(ClientStorageSettings settings, CancellationToken cancellationToken);
}
