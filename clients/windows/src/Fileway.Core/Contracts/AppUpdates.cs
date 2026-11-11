namespace Fileway.Core.Contracts;

public sealed record WindowsRelease(Version Version, bool Preview, Uri ReleasePage);

public interface IAppUpdateService
{
    Task<WindowsRelease?> GetLatestWindowsReleaseAsync(CancellationToken cancellationToken);
}
