using System.Security.Cryptography;
using System.Text.Json;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Services;

internal static class CacheOwnership
{
    private static readonly object ProbeGate = new();
    private static readonly Dictionary<string, Task<bool>> RootProbes = new(StringComparer.OrdinalIgnoreCase);
    private static readonly SemaphoreSlim ProbeSlots = new(4, 4);

    internal static async Task RequireRegisteredRootAvailableAsync(string cacheRoot, CancellationToken cancellationToken)
    {
        // A missing managed child is empty, but a missing application root may be an offline volume.
        // Never convert that ambiguity into a successful logout that forgets ownership.
        Task<bool> probe;
        lock (ProbeGate)
        {
            if (!RootProbes.TryGetValue(cacheRoot, out probe!))
                RootProbes.Add(cacheRoot, probe = ProbeRootAsync(cacheRoot));
        }
        bool available;
        try { available = await probe.WaitAsync(TimeSpan.FromMilliseconds(500), cancellationToken).ConfigureAwait(false); }
        catch (TimeoutException) { available = false; }
        catch (OperationCanceledException) { throw new FilewayException(new ErrorInfo(ErrorCode.Canceled, "操作已取消。")); }
        finally
        {
            lock (ProbeGate)
                if (probe.IsCompleted && RootProbes.TryGetValue(cacheRoot, out var current) && ReferenceEquals(probe, current))
                    RootProbes.Remove(cacheRoot);
        }
        if (!available) throw new FilewayException(new ErrorInfo(ErrorCode.LocalStorageUnavailable,
            "已登记的本地缓存位置暂不可用，请稍后重试。"));
    }

    private static async Task<bool> ProbeRootAsync(string cacheRoot)
    {
        await ProbeSlots.WaitAsync().ConfigureAwait(false);
        try
        {
            return await Task.Run(() =>
            {
                try { return (File.GetAttributes(cacheRoot) & FileAttributes.Directory) != 0; }
                catch (Exception error) when (error is DirectoryNotFoundException or FileNotFoundException)
                {
                    try
                    {
                        // Only a ready local volume proves that an intentionally removed folder is empty.
                        var drive = new DriveInfo(Path.GetPathRoot(cacheRoot)!);
                        return (drive.DriveType is DriveType.Fixed or DriveType.Removable) && drive.IsReady;
                    }
                    catch (Exception) { return false; }
                }
                catch (Exception) { return false; }
            }).ConfigureAwait(false);
        }
        finally { ProbeSlots.Release(); }
    }

    internal static string Owner(AccountKey account) => Identity(new
    {
        profileId = account.ProfileId, sourceRevision = account.SourceRevision, serverUserId = account.ServerUserId,
    });

    internal static string Identity<T>(T value) =>
        Convert.ToHexString(SHA256.HashData(JsonSerializer.SerializeToUtf8Bytes(value))).ToLowerInvariant();
}
