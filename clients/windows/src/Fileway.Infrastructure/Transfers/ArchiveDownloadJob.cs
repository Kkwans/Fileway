using System.Net;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Transfers;

/// <summary>Bounded budgets for one archive export stream. Idle means no received bytes, not total duration.</summary>
public sealed record ArchiveDownloadJobOptions
{
    public TimeSpan RequestIdleTimeout { get; init; } = TimeSpan.FromSeconds(30);
    public int BufferSizeBytes { get; init; } = 2 * 1024 * 1024;
}

/// <summary>
/// One-shot server-side archive export streamed to a local file. A synthetic archive has no pinned
/// length, digest or version: it creates no durable task, offers no resume, and a failure deletes its
/// own unconfirmed partial instead of pretending a checkpoint exists.
/// </summary>
public sealed class ArchiveDownloadJob : IAsyncDisposable
{
    private readonly IDownloadSourceProvider _sources;
    private readonly IDownloadDestinationProvider _destinations;
    private readonly ArchiveDownloadJobOptions _options;
    private readonly HttpClient _http;
    private readonly object _gate = new();
    private Task? _active;
    private CancellationTokenSource? _stop;
    private bool _disposed;

    public event EventHandler<long>? BytesReceived;

    public ArchiveDownloadJob(IDownloadSourceProvider sources, IDownloadDestinationProvider destinations,
        HttpMessageHandler? handler = null, ArchiveDownloadJobOptions? options = null)
    {
        ArgumentNullException.ThrowIfNull(sources);
        ArgumentNullException.ThrowIfNull(destinations);
        _options = options ?? new();
        if (_options.BufferSizeBytes is < 1 or > 2 * 1024 * 1024 ||
            _options.RequestIdleTimeout <= TimeSpan.Zero || _options.RequestIdleTimeout > TimeSpan.FromHours(1))
            throw new FilewayException(new(ErrorCode.InvalidRequest, "归档导出参数无效。"));
        _sources = sources;
        _destinations = destinations;
        _http = new(handler ?? new SocketsHttpHandler
        {
            AllowAutoRedirect = false, UseProxy = false, UseCookies = false,
            AutomaticDecompression = System.Net.DecompressionMethods.None, ConnectTimeout = TimeSpan.FromSeconds(30),
        }, disposeHandler: true) { Timeout = Timeout.InfiniteTimeSpan };
    }

    /// <summary>Returns the published local file identity; exactly one export runs at a time.</summary>
    public Task<string> RunAsync(RemoteResourceRef directory, IReadOnlyList<string> itemWirePaths, string targetPath,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(directory);
        ArgumentNullException.ThrowIfNull(itemWirePaths);
        lock (_gate)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            if (_active is { IsCompleted: false }) throw Busy();
            _stop = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            _active = Task.Run(() => ExportAsync(directory, itemWirePaths, targetPath), CancellationToken.None);
            return (Task<string>)_active!;
        }
    }

    private async Task<string> ExportAsync(RemoteResourceRef directory, IReadOnlyList<string> itemWirePaths, string targetPath)
    {
        IDownloadLease? lease = null;
        IDownloadDestination? destination = null;
        var stop = _stop!.Token;
        try
        {
            using var acquire = CancellationTokenSource.CreateLinkedTokenSource(stop);
            acquire.CancelAfter(TimeSpan.FromSeconds(30));
            lease = await _sources.AcquireArchiveLeaseAsync(new(directory, itemWirePaths), acquire.Token).ConfigureAwait(false);
            if (lease.SourceVersion is not null) throw Protocol();
            RequireAddress(lease.Address);
            destination = await _destinations.CreateFreshAsync(targetPath, stop).ConfigureAwait(false);
            long received = await StreamAsync(lease, destination, stop).ConfigureAwait(false);
            if (received <= 0 || destination.Length != received) throw Protocol();
            await destination.FlushToDiskAsync(stop).ConfigureAwait(false);
            string identity = await destination.PublishNoReplaceAsync(stop).ConfigureAwait(false);
            return identity;
        }
        catch (Exception error)
        {
            var failure = Normalize(error);
            // Nothing was confirmed: the created partial is this execution's own object, so a
            // failed export cleans it up instead of leaving an unverifiable archive behind.
            if (destination is not null && !destination.IsPublished)
                try { await destination.DeleteOwnedPartialAsync(CancellationToken.None).ConfigureAwait(false); }
                catch (Exception)
                {
                    // An unremovable created partial must not be reported as a clean failure.
                    throw new FilewayException(new(ErrorCode.OutcomeUnknown, "归档导出失败，临时文件未能清理，请手动核实。"));
                }
            throw failure;
        }
        finally
        {
            try { if (lease is not null) await lease.DisposeAsync().ConfigureAwait(false); }
            finally { if (destination is not null) await destination.DisposeAsync().ConfigureAwait(false); }
        }
    }

    private async Task<long> StreamAsync(IDownloadLease lease, IDownloadDestination destination, CancellationToken stop)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, lease.Address);
        using var headersBudget = CancellationTokenSource.CreateLinkedTokenSource(stop);
        headersBudget.CancelAfter(_options.RequestIdleTimeout);
        HttpResponseMessage response;
        try { response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, headersBudget.Token).ConfigureAwait(false); }
        catch (OperationCanceledException) { throw stop.IsCancellationRequested ? Canceled() : IdleTimeout(); }
        using (response)
        {
            if (response.StatusCode != HttpStatusCode.OK)
                throw response.StatusCode switch
                {
                    HttpStatusCode.Unauthorized => Auth(),
                    HttpStatusCode.Forbidden => Permission(),
                    HttpStatusCode.NotFound => Changed(),
                    _ => Protocol(),
                };
            if (response.Content.Headers.Contains("Content-Range") ||
                response.Headers.Contains("X-Fileway-Source-Version") ||
                response.Content.Headers.ContentEncoding.Any(value => !value.Equals("identity", StringComparison.OrdinalIgnoreCase)))
                throw Protocol();
            // A length, when present, is informational; a synthetic archive may stream without one.
            await using var body = await response.Content.ReadAsStreamAsync(stop).ConfigureAwait(false);
            byte[] buffer = new byte[_options.BufferSizeBytes];
            long received = 0;
            while (true)
            {
                stop.ThrowIfCancellationRequested();
                int count;
                using (var idle = CancellationTokenSource.CreateLinkedTokenSource(stop))
                {
                    idle.CancelAfter(_options.RequestIdleTimeout);
                    try { count = await body.ReadAsync(buffer.AsMemory(), idle.Token).ConfigureAwait(false); }
                    catch (OperationCanceledException) { throw stop.IsCancellationRequested ? Canceled() : IdleTimeout(); }
                    catch (IOException) { throw Network(); }
                }
                if (count == 0) return received;
                stop.ThrowIfCancellationRequested();
                await destination.WriteAsync(buffer.AsMemory(0, count), stop).ConfigureAwait(false);
                received = checked(received + count);
                BytesReceived?.Invoke(this, received);
            }
        }
    }

    private static void RequireAddress(Uri address)
    {
        const string prefix = "/stream/";
        if (address is null || !address.IsAbsoluteUri || address.Scheme != Uri.UriSchemeHttp || address.Host != "127.0.0.1" ||
            address.IsDefaultPort || address.Port <= 0 || address.UserInfo.Length != 0 || address.Query.Length != 0 || address.Fragment.Length != 0 ||
            !address.AbsolutePath.StartsWith(prefix, StringComparison.Ordinal) || address.AbsolutePath.Length == prefix.Length)
            throw Protocol();
    }

    private static FilewayException Normalize(Exception error) => error switch
    {
        FilewayException known => known,
        OperationCanceledException => Canceled(),
        HttpRequestException => Network(),
        IOException or UnauthorizedAccessException => new(new ErrorInfo(ErrorCode.LocalStorageUnavailable, "本地归档保存不可用。")),
        ObjectDisposedException => Changed(),
        _ => new(new ErrorInfo(ErrorCode.Unknown, "归档导出未能完成。")),
    };

    private static FilewayException Busy() => new(new(ErrorCode.Busy, "已有归档导出正在执行。"));
    private static FilewayException Canceled() => new(new(ErrorCode.Canceled, "归档导出已停止。"));
    private static FilewayException IdleTimeout() => new(new(ErrorCode.Timeout, "归档导出长时间没有进展。"));
    private static FilewayException Network() => new(new(ErrorCode.NetworkUnavailable, "归档导出连接已中断。"));
    private static FilewayException Auth() => new(new(ErrorCode.AuthenticationExpired, "归档导出需要重新登录。"));
    private static FilewayException Permission() => new(new(ErrorCode.PermissionDenied, "没有导出所选项目的权限。"));
    private static FilewayException Changed() => new(new(ErrorCode.SourceChanged, "归档来源已变化，请刷新后重试。"));
    private static FilewayException Protocol() => new(new(ErrorCode.ProtocolMismatch, "归档导出响应无效，未保存文件。"));

    public async ValueTask DisposeAsync()
    {
        Task? active;
        CancellationTokenSource? stop;
        lock (_gate)
        {
            if (_disposed) return;
            _disposed = true;
            active = _active;
            stop = _stop;
            _active = null;
            _stop = null;
        }
        try { if (stop is not null) await stop.CancelAsync().ConfigureAwait(false); }
        catch (Exception) { /* Draining still waits for the export below. */ }
        if (active is not null)
            try { await active.ConfigureAwait(false); }
            catch (Exception) { /* Drain owns the outcome; the caller's command reports its own failure. */ }
        try { _http.Dispose(); }
        catch (Exception) { }
        stop?.Dispose();
    }
}
