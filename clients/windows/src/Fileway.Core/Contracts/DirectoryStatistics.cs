namespace Fileway.Core.Contracts;

/// <summary>Visible descendants, excluding the queried directory itself.</summary>
public sealed record DirectoryStatistics(long Files, long Directories, long Bytes);

public interface IDirectoryStatisticsService
{
    Task<DirectoryStatistics> ReadDirectoryStatisticsAsync(RemoteResourceRef directory, CancellationToken cancellationToken);
}
