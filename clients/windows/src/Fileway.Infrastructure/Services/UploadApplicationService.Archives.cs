using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Transfers;

namespace Fileway.Infrastructure.Services;

public sealed partial class UploadApplicationService : IArchiveDownloadService
{
    private ArchiveDownloadJob? _archiveJob;
    private readonly Dictionary<Binding, List<CancellationTokenSource>> _archiveStops = [];

    /// <summary>
    /// One-shot server-side ZIP export into the effective download directory. It holds no durable
    /// task, never resumes, and is drained/canceled with the owning binding like every download.
    /// </summary>
    public async Task<string> DownloadArchiveAsync(ArchiveDownloadRequest request, IProgress<long>? progress,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(request);
        await EnterAsync(cancellationToken).ConfigureAwait(false);
        ArchiveDownloadJob job;
        CancellationTokenSource stop;
        Binding binding;
        try
        {
            binding = RequireDownloadContext(request.Context);
            RequireDownloadPermission(binding);
            if (request.Directory is null || request.Directory.Account != request.Context.Account ||
                request.Directory.SourceVersion is not null || request.ItemWirePaths is null ||
                request.ItemWirePaths.Count is 0 or > 1000 ||
                string.IsNullOrWhiteSpace(request.SuggestedName)) throw DownloadSafe(ErrorCode.InvalidRequest);
            lock (_gate)
            {
                ObjectDisposedException.ThrowIf(_disposed, this);
                RequireCurrent(binding);
                if (binding.DownloadDraining) throw DownloadSafe(ErrorCode.Busy);
                if (_archiveJob is null) _archiveJob = new(_downloads!.Sources, new WindowsDownloadFileAccess());
                job = _archiveJob;
                // Drain and permission-withdraw cancel this source; there is no binding-lifetime token.
                stop = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                if (!_archiveStops.TryGetValue(binding, out var stops))
                {
                    stops = [];
                    _archiveStops[binding] = stops;
                }
                stops.Add(stop);
            }
        }
        finally { _commands.Release(); }

        ArchiveDownloadJob? registered = job;
        try
        {
            var settings = await StorageAsync(token => _downloads!.StorageSettings.ReadAsync(token), stop.Token).ConfigureAwait(false);
            RequireCurrent(binding);
            if (string.IsNullOrWhiteSpace(settings.Active.DownloadDirectory)) throw DownloadSafe(ErrorCode.LocalStorageUnavailable);
            string leaf = FilewayClientServices.MapWindowsLeafName(request.SuggestedName);
            if (!leaf.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)) leaf += ".zip";
            string target = settings.Active.DownloadDirectory.TrimEnd('\\') + "\\" + leaf;
            // No store target reservation exists for a synthetic archive; the fresh partial rules
            // still refuse to open or overwrite an existing final name.
            if (System.IO.File.Exists(target)) throw DownloadSafe(ErrorCode.Conflict);
            void OnBytes(object? sender, long bytes) => progress?.Report(bytes);
            job.BytesReceived += OnBytes;
            try { return await job.RunAsync(request.Directory, request.ItemWirePaths, target, stop.Token).ConfigureAwait(false); }
            finally { job.BytesReceived -= OnBytes; }
        }
        finally
        {
            lock (_gate)
            {
                if (_archiveStops.TryGetValue(binding, out var stops))
                {
                    stops.Remove(stop);
                    if (stops.Count == 0) _archiveStops.Remove(binding);
                }
            }
            stop.Dispose();
        }
    }

    private void CancelArchiveWorkLocked(Binding binding)
    {
        if (!_archiveStops.TryGetValue(binding, out var stops)) return;
        foreach (var stop in stops)
            try { _ = stop.CancelAsync(); }
            catch (Exception) { /* Drain still waits for the export completion. */ }
    }

    private Task DrainArchiveWorkAsync(Binding binding)
    {
        Task[] pending;
        lock (_gate)
        {
            if (!_archiveStops.TryGetValue(binding, out var stops)) return Task.CompletedTask;
            pending = [.. stops.Select(stop => stop.CancelAsync())];
        }
        return Task.WhenAll(pending);
    }
}
