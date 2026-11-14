using System.Text.Json;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices : IDirectoryStatisticsService
{
    public async Task<DirectoryStatistics> ReadDirectoryStatisticsAsync(RemoteResourceRef directory, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(directory);
        _ = FileOperationWireIdentity(directory.WirePath, allowRoot: true);
        var state = GetResourceState(directory);
        // Confirm the selected root is visible through normal resource policy.
        if (!(await GetAsync(directory, cancellationToken).ConfigureAwait(false)).IsDirectory)
            throw Safe(ErrorCode.InvalidRequest, "只能统计文件夹。");
        var response = await SessionLongReadAsync(state, "/api/resources/recursive" + directory.WirePath,
            TimeSpan.FromSeconds(45), cancellationToken).ConfigureAwait(false);
        RequireExactStatus(response, 200);
        try
        {
            using var body = ParseBody(response);
            long files = 0, directories = 0, bytes = 0;
            foreach (var row in body.RootElement.EnumerateArray())
            {
                cancellationToken.ThrowIfCancellationRequested();
                if (row.GetProperty("isDir").GetBoolean()) directories++;
                else
                {
                    long size = row.GetProperty("size").GetInt64();
                    if (size < 0) throw InvalidResponse();
                    files++;
                    bytes = checked(bytes + size);
                }
            }
            EnsureActive(state);
            return new(files, directories, bytes);
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException or OverflowException)
        { throw InvalidResponse(); }
    }
}
