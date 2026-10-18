using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

/// <summary>
/// Observation-checked, memory-only playback reads through fresh authenticated capabilities.
/// This neither owns the download file nor establishes immutable content or resume support.
/// </summary>
internal sealed class DownloadPlaybackRangeReader : IAsyncDisposable
{
    internal const int PrefetchBytes = 1024 * 1024;
    private static readonly UTF8Encoding StrictUtf8 = new(false, true);
    private readonly object _gate = new();
    private readonly SemaphoreSlim _serial = new(1, 1);
    private readonly IDownloadSourceProvider _sources;
    private readonly RemoteResourceRef _source;
    private readonly TransferSourceSnapshot _observed;
    private readonly byte[] _wireIdentity;
    private readonly DateTimeOffset? _modified;
    private readonly DateTimeOffset? _httpModified;
    private readonly Action _requireAvailable;
    private readonly CancellationToken _authorityStop;
    private readonly CancellationScope _lifetime;
    private readonly HttpClient _http;
    private readonly TimeSpan _operationTimeout;
    private TaskCompletionSource? _disposeCompletion;
    private TaskCompletionSource? _drained;
    private int _operations;
    private ErrorInfo? _terminalError;
    private ErrorInfo? _releaseError;
    private byte[]? _cache;
    private long _cacheOffset;
    private int _cacheLength;

    internal DownloadPlaybackRangeReader(IDownloadSourceProvider sources, RemoteResourceRef source,
        TransferSourceSnapshot observed, Action requireAvailable, CancellationToken authorityStop,
        HttpMessageHandler? handler = null)
        : this(sources, source, observed, requireAvailable, DownloadPlaybackRangeReaderOptions.Default,
            handler, authorityStop) { }

    internal DownloadPlaybackRangeReader(IDownloadSourceProvider sources, RemoteResourceRef source,
        TransferSourceSnapshot observed, Action requireAvailable, DownloadPlaybackRangeReaderOptions options,
        HttpMessageHandler? handler, CancellationToken authorityStop)
    {
        ArgumentNullException.ThrowIfNull(sources);
        ArgumentNullException.ThrowIfNull(source);
        ArgumentNullException.ThrowIfNull(observed);
        ArgumentNullException.ThrowIfNull(requireAvailable);
        ArgumentNullException.ThrowIfNull(options);
        if (observed.SourceVersion is null && observed.Modified is null)
            throw Safe(ErrorCode.CapabilityMissing);
        if (observed.Length < 0 || options.OperationTimeout <= TimeSpan.Zero ||
            options.OperationTimeout > TimeSpan.FromMinutes(5)) throw Safe(ErrorCode.InvalidRequest);
        _sources = sources;
        _source = source;
        _observed = observed;
        _wireIdentity = WireBytes(source.WirePath);
        _modified = observed.Modified?.ToUniversalTime();
        _httpModified = _modified is { } modified
            ? new(modified.Ticks - modified.Ticks % TimeSpan.TicksPerSecond, TimeSpan.Zero) : null;
        _requireAvailable = requireAvailable;
        _authorityStop = authorityStop;
        _operationTimeout = options.OperationTimeout;
        _http = new HttpClient(SafeHandler(handler), disposeHandler: true) { Timeout = Timeout.InfiniteTimeSpan };
        _lifetime = new(null, authorityStop);
    }

    internal async ValueTask<int> ReadAsync(long offset, Memory<byte> buffer, CancellationToken cancellationToken)
    {
        if (offset < 0 || offset > _observed.Length) throw Safe(ErrorCode.InvalidRequest);
        lock (_gate)
        {
            RequireAdmission();
            _operations++;
        }
        bool serialOwned = false;
        try
        {
            await using var operation = new CancellationScope(null, cancellationToken, _lifetime.Token);
            RequireAvailable(operation.Token);
            await _serial.WaitAsync(operation.Token).ConfigureAwait(false);
            serialOwned = true;
            RequireAvailable(operation.Token);
            if (buffer.IsEmpty || offset == _observed.Length) return 0;
            if (_cacheLength == 0 || offset < _cacheOffset || offset - _cacheOffset >= _cacheLength)
            {
                // Serial reads reuse one allocation; unverified fills never remain valid cache entries.
                _cacheLength = 0;
                int filled = await FillAsync(offset, operation.Token).ConfigureAwait(false);
                RequireAvailable(operation.Token);
                _cacheOffset = offset;
                _cacheLength = filled;
            }

            // The final authority/cancellation fence precedes any caller-memory publication.
            RequireAvailable(operation.Token);
            lock (_gate)
            {
                RequireAdmission();
                operation.Token.ThrowIfCancellationRequested();
                _authorityStop.ThrowIfCancellationRequested();
                int skip = checked((int)(offset - _cacheOffset));
                int count = Math.Min(buffer.Length, _cacheLength - skip);
                (_cache ?? throw Safe(ErrorCode.ProtocolMismatch)).AsMemory(skip, count).CopyTo(buffer);
                return count;
            }
        }
        catch (Exception error)
        {
            var safe = Normalize(error);
            if (safe.Error.Code is ErrorCode.SourceChanged or ErrorCode.ProtocolMismatch or ErrorCode.CapabilityMissing or
                ErrorCode.AuthenticationExpired or ErrorCode.PermissionDenied or ErrorCode.Conflict or ErrorCode.OutcomeUnknown)
            {
                lock (_gate)
                {
                    _terminalError ??= safe.Error;
                    _cache = null;
                    _cacheLength = 0;
                }
            }
            throw safe;
        }
        finally
        {
            if (serialOwned) _serial.Release();
            lock (_gate)
            {
                _operations--;
                if (_operations == 0) _drained?.TrySetResult();
            }
        }
    }

    private async Task<int> FillAsync(long offset, CancellationToken token)
    {
        await ObserveAsync(token).ConfigureAwait(false);
        IDownloadLease? lease = null;
        FilewayException? failure = null;
        int length = 0;
        try
        {
            await using (var control = new CancellationScope(_operationTimeout, token))
            {
                try
                {
                    RequireAvailable(token);
                    // Capture before checking cancellation: even a noncooperative late result is owned.
                    lease = await _sources.AcquireDownloadAsync(new(_source, _observed.Length, _observed.SourceVersion), control.Token).ConfigureAwait(false);
                    RequireAvailable(token);
                    control.ThrowIfCanceled();
                }
                catch (Exception error) { throw StageFailure(error, control, token); }
            }
            RequireLease(lease);
            if (lease is null) throw Safe(ErrorCode.ProtocolMismatch);
            length = checked((int)Math.Min(PrefetchBytes, _observed.Length - offset));
            long end = offset + length - 1;
            using var request = new HttpRequestMessage(HttpMethod.Get, lease.Address);
            request.Headers.Range = new(offset, end);
            if (_httpModified is { } httpModified) request.Headers.IfRange = new(httpModified);
            request.Headers.AcceptEncoding.Add(new("identity"));
            request.Headers.CacheControl = new() { NoCache = true, NoStore = true };
            using var response = await SendAsync(request, token).ConfigureAwait(false);
            RequireResponse(response, offset, end, length);
            _cache ??= new byte[PrefetchBytes];
            await ReceiveAsync(response, _cache.AsMemory(0, length), token).ConfigureAwait(false);
            await ObserveAsync(token).ConfigureAwait(false);
            RequireAvailable(token);
        }
        catch (Exception error) { failure = Normalize(error); }
        finally
        {
            // Never invoke reader disposal from a read stack, or abandon a late capability.
            if (lease is not null)
            {
                try { await lease.DisposeAsync().ConfigureAwait(false); }
                catch (Exception)
                {
                    lock (_gate)
                    {
                        _releaseError ??= Safe(ErrorCode.OutcomeUnknown).Error;
                        failure = new FilewayException(_releaseError);
                    }
                }
            }
        }
        if (failure is not null) throw failure;
        return length;
    }

    private async Task ObserveAsync(CancellationToken token)
    {
        await using var control = new CancellationScope(_operationTimeout, token);
        try
        {
            RequireAvailable(token);
            var file = await _sources.PrepareDownloadAsync(_source, control.Token).ConfigureAwait(false);
            RequireAvailable(token);
            control.ThrowIfCanceled();
            if (file is null || file.Resource is null) throw Safe(ErrorCode.ProtocolMismatch);
            if (!SameSource(file.Resource) || file.IsDirectory || file.Size != _observed.Length ||
                !SameVersion(file.Resource.SourceVersion, file.Modified)) throw Safe(ErrorCode.SourceChanged);
        }
        catch (Exception error) { throw StageFailure(error, control, token); }
    }

    private async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token)
    {
        HttpResponseMessage? response = null;
        var headers = new CancellationScope(_operationTimeout, token);
        try
        {
            await using (headers)
            {
                RequireAvailable(token);
                response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, headers.Token).ConfigureAwait(false);
                RequireAvailable(token);
                headers.ThrowIfCanceled();
                return response;
            }
        }
        catch (Exception error)
        {
            response?.Dispose();
            throw StageFailure(error, headers, token);
        }
    }

    private async Task ReceiveAsync(HttpResponseMessage response, Memory<byte> bytes, CancellationToken token)
    {
        await using var bodyBudget = new CancellationScope(_operationTimeout, token);
        Stream? body = null;
        FilewayException? failure = null;
        try
        {
            RequireAvailable(token);
            body = await response.Content.ReadAsStreamAsync(bodyBudget.Token).ConfigureAwait(false);
            RequireAvailable(token);
            bodyBudget.ThrowIfCanceled();
            int received = 0;
            while (received < bytes.Length)
            {
                int count = await body.ReadAsync(bytes[received..], bodyBudget.Token).ConfigureAwait(false);
                RequireAvailable(token);
                bodyBudget.ThrowIfCanceled();
                if (count <= 0 || count > bytes.Length - received) throw Safe(ErrorCode.ProtocolMismatch);
                received += count;
            }
            byte[] probe = new byte[1];
            int extra = await body.ReadAsync(probe, bodyBudget.Token).ConfigureAwait(false);
            RequireAvailable(token);
            bodyBudget.ThrowIfCanceled();
            if (extra != 0) throw Safe(ErrorCode.ProtocolMismatch);
        }
        catch (Exception error) { failure = StageFailure(error, bodyBudget, token); }
        finally
        {
            if (body is not null)
            {
                try { await body.DisposeAsync().ConfigureAwait(false); }
                catch (Exception) { failure = Safe(ErrorCode.OutcomeUnknown); }
            }
        }
        if (failure is not null) throw failure;
    }

    private void RequireLease(IDownloadLease? lease)
    {
        if (lease is null || lease.Resource is null) throw Safe(ErrorCode.ProtocolMismatch);
        if (lease.SupportsResume || !StringComparer.Ordinal.Equals(lease.SourceVersion, _observed.SourceVersion) ||
            !StringComparer.Ordinal.Equals(lease.Resource.SourceVersion, _observed.SourceVersion))
            throw Safe(ErrorCode.SourceChanged);
        if (!SameSource(lease.Resource)) throw Safe(ErrorCode.ProtocolMismatch);
        if (lease.Length != _observed.Length) throw Safe(ErrorCode.SourceChanged);
        RequireAddress(lease.Address);
    }

    private bool SameSource(RemoteResourceRef resource) => resource.Account == _source.Account &&
        _wireIdentity.AsSpan().SequenceEqual(WireBytes(resource.WirePath));

    private bool SameVersion(string? sourceVersion, DateTimeOffset? modified) =>
        _observed.SourceVersion is { } expected
            ? StringComparer.Ordinal.Equals(sourceVersion, expected)
            : modified?.ToUniversalTime() == _modified;

    // Match the existing transfer protocol's opaque byte identity without Unicode replacement decoding.
    private static byte[] WireBytes(string wire)
    {
        try
        {
            if (wire is null) throw Safe(ErrorCode.ProtocolMismatch);
            byte[] encoded = StrictUtf8.GetBytes(wire);
            using var decoded = new MemoryStream(encoded.Length);
            for (int index = 0; index < encoded.Length; index++)
            {
                byte value = encoded[index];
                if (value == '%')
                {
                    if (index + 2 >= encoded.Length || !char.IsAsciiHexDigit((char)encoded[index + 1]) ||
                        !char.IsAsciiHexDigit((char)encoded[index + 2])) throw Safe(ErrorCode.ProtocolMismatch);
                    value = checked((byte)int.Parse(Encoding.ASCII.GetString(encoded, index + 1, 2), NumberStyles.HexNumber,
                        CultureInfo.InvariantCulture));
                    index += 2;
                }
                decoded.WriteByte(value);
            }
            return decoded.ToArray();
        }
        catch (EncoderFallbackException) { throw Safe(ErrorCode.ProtocolMismatch); }
    }

    private static void RequireAddress(Uri address)
    {
        const string origin = "http://127.0.0.1:";
        const string path = "/stream/";
        if (address is null || !address.IsAbsoluteUri || address.Scheme != Uri.UriSchemeHttp ||
            address.Host != "127.0.0.1" || address.IsDefaultPort || address.Port <= 0 || address.UserInfo.Length != 0 ||
            address.Query.Length != 0 || address.Fragment.Length != 0) throw Safe(ErrorCode.ProtocolMismatch);
        // Validate the original spelling too: Uri canonicalizes numeric hosts and dot segments.
        string raw = address.OriginalString;
        if (!raw.StartsWith(origin, StringComparison.Ordinal)) throw Safe(ErrorCode.ProtocolMismatch);
        int slash = raw.IndexOf('/', origin.Length);
        if (slash < 0 || !int.TryParse(raw.AsSpan(origin.Length, slash - origin.Length), NumberStyles.None,
            CultureInfo.InvariantCulture, out int port) || port != address.Port ||
            !raw.AsSpan(slash).StartsWith(path, StringComparison.Ordinal)) throw Safe(ErrorCode.ProtocolMismatch);
        var capability = raw.AsSpan(slash + path.Length);
        if (capability.IsEmpty) throw Safe(ErrorCode.ProtocolMismatch);
        foreach (char character in capability)
            if (!char.IsAsciiLetterOrDigit(character) && character is not '-' and not '_') throw Safe(ErrorCode.ProtocolMismatch);
    }

    private void RequireResponse(HttpResponseMessage response, long start, long end, int length)
    {
        if (response.StatusCode != HttpStatusCode.PartialContent)
            throw Safe(response.StatusCode switch
            {
                HttpStatusCode.Unauthorized => ErrorCode.AuthenticationExpired,
                HttpStatusCode.Forbidden => ErrorCode.PermissionDenied,
                HttpStatusCode.NotFound or HttpStatusCode.OK or HttpStatusCode.RequestedRangeNotSatisfiable => ErrorCode.SourceChanged,
                _ => ErrorCode.ProtocolMismatch,
            }, (int)response.StatusCode);
        if (response.Headers.Contains("X-Fileway-Source-Version") || response.Content.Headers.Contains("X-Fileway-Source-Version"))
            throw Safe(ErrorCode.CapabilityMissing);
        if (response.Headers.Contains("Transfer-Encoding")) throw Safe(ErrorCode.ProtocolMismatch);
        var content = response.Content.Headers;
        string range = SingleHeader(content, "Content-Range");
        if (!ContentRangeHeaderValue.TryParse(range, out var parsed) ||
            !parsed.Unit.Equals("bytes", StringComparison.OrdinalIgnoreCase) || !parsed.HasRange || !parsed.HasLength ||
            parsed.From != start || parsed.To != end || parsed.Length != _observed.Length) throw Safe(ErrorCode.ProtocolMismatch);
        if (!long.TryParse(SingleHeader(content, "Content-Length").AsSpan().Trim(), NumberStyles.None,
            CultureInfo.InvariantCulture, out long declared) || declared != length) throw Safe(ErrorCode.ProtocolMismatch);
        if (content.Contains("Content-Encoding") &&
            !SingleHeader(content, "Content-Encoding").Trim().Equals("identity", StringComparison.OrdinalIgnoreCase))
            throw Safe(ErrorCode.ProtocolMismatch);
        if (content.Contains("Content-Type") &&
            (!MediaTypeHeaderValue.TryParse(SingleHeader(content, "Content-Type"), out var type) || type.MediaType is null ||
                type.MediaType.StartsWith("multipart/", StringComparison.OrdinalIgnoreCase))) throw Safe(ErrorCode.ProtocolMismatch);
        if (!DateTimeOffset.TryParseExact(SingleHeader(content, "Last-Modified"), "r", CultureInfo.InvariantCulture,
            DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out var modified)) throw Safe(ErrorCode.ProtocolMismatch);
        if (modified != _httpModified) throw Safe(ErrorCode.SourceChanged);
    }

    private static string SingleHeader(HttpHeaders headers, string name)
    {
        if (!headers.NonValidated.TryGetValues(name, out var values)) throw Safe(ErrorCode.ProtocolMismatch);
        string[] raw = values.ToArray();
        if (raw.Length != 1 || string.IsNullOrWhiteSpace(raw[0])) throw Safe(ErrorCode.ProtocolMismatch);
        return raw[0];
    }

    private void RequireAvailable(CancellationToken token)
    {
        token.ThrowIfCancellationRequested();
        _authorityStop.ThrowIfCancellationRequested();
        lock (_gate) RequireAdmission();
        _requireAvailable();
        token.ThrowIfCancellationRequested();
        _authorityStop.ThrowIfCancellationRequested();
        lock (_gate) RequireAdmission();
    }

    private void RequireAdmission()
    {
        if (_disposeCompletion is not null) throw Safe(ErrorCode.SourceChanged);
        if (_terminalError is not null) throw new FilewayException(_terminalError);
    }

    private static FilewayException StageFailure(Exception error, CancellationScope budget, CancellationToken token)
    {
        if (token.IsCancellationRequested) return Safe(ErrorCode.Canceled);
        if (budget.TimedOut) return Safe(ErrorCode.Timeout);
        return Normalize(error);
    }

    private static FilewayException Normalize(Exception error) => error switch
    {
        FilewayException fileway => Safe(fileway.Error.Code, fileway.Error.HttpStatus),
        OperationCanceledException => Safe(ErrorCode.Canceled),
        HttpRequestException http => Safe(http.StatusCode switch
        {
            HttpStatusCode.Unauthorized => ErrorCode.AuthenticationExpired,
            HttpStatusCode.Forbidden => ErrorCode.PermissionDenied,
            HttpStatusCode.NotFound => ErrorCode.SourceChanged,
            _ => ErrorCode.NetworkUnavailable,
        }, http.StatusCode is { } status ? (int)status : null),
        IOException => Safe(ErrorCode.NetworkUnavailable),
        ObjectDisposedException => Safe(ErrorCode.SourceChanged),
        _ => Safe(ErrorCode.ProtocolMismatch),
    };

    private static FilewayException Safe(ErrorCode code, int? status = null) => new(new(code, code switch
    {
        ErrorCode.AuthenticationExpired => "下载播放的认证已失效。",
        ErrorCode.PermissionDenied => "已无权读取下载播放源。",
        ErrorCode.SourceChanged => "下载播放源或执行权限已变化。",
        ErrorCode.CapabilityMissing => "下载播放源不支持所需的读取能力。",
        ErrorCode.Canceled => "下载播放读取已取消。",
        ErrorCode.Timeout => "下载播放读取超时。",
        ErrorCode.NetworkUnavailable => "下载播放读取连接不可用。",
        ErrorCode.InvalidRequest => "下载播放读取参数无效。",
        ErrorCode.OutcomeUnknown => "下载播放读取能力未能确认释放。",
        ErrorCode.Busy => "下载播放读取尚未排空。",
        _ => "下载播放读取响应无效。",
    }, status));

    private static HttpMessageHandler SafeHandler(HttpMessageHandler? handler)
    {
        handler ??= new HttpClientHandler();
        if (handler is HttpClientHandler client)
        {
            client.AllowAutoRedirect = false;
            client.UseProxy = false;
            client.UseCookies = false;
            client.AutomaticDecompression = DecompressionMethods.None;
        }
        else if (handler is SocketsHttpHandler sockets)
        {
            sockets.AllowAutoRedirect = false;
            sockets.UseProxy = false;
            sockets.UseCookies = false;
            sockets.AutomaticDecompression = DecompressionMethods.None;
        }
        return handler;
    }

    public ValueTask DisposeAsync()
    {
        TaskCompletionSource completion;
        lock (_gate)
        {
            if (_disposeCompletion is not null) return new(_disposeCompletion.Task);
            _disposeCompletion = completion = new(TaskCreationOptions.RunContinuationsAsynchronously);
        }
        _ = DisposeCoreAsync(completion);
        return new(completion.Task);
    }

    // The service calls this after its read drain, without awaiting playback/native disposal.
    internal void RequireReleased()
    {
        lock (_gate)
        {
            if (_operations != 0) throw Safe(ErrorCode.Busy);
            if (_releaseError is not null) throw new FilewayException(_releaseError);
        }
    }

    private async Task DisposeCoreAsync(TaskCompletionSource completion)
    {
        FilewayException? failure = null;
        try { await _lifetime.CancelAsync().ConfigureAwait(false); }
        catch (Exception) { failure = Safe(ErrorCode.OutcomeUnknown); }
        Task drained;
        lock (_gate)
            drained = _operations == 0 ? Task.CompletedTask : (_drained ??= new(TaskCreationOptions.RunContinuationsAsynchronously)).Task;
        await drained.ConfigureAwait(false);
        lock (_gate)
            if (_releaseError is not null) failure = new FilewayException(_releaseError);
        try { await _lifetime.DisposeAsync().ConfigureAwait(false); }
        catch (Exception) { failure ??= Safe(ErrorCode.OutcomeUnknown); }
        try { _http.Dispose(); }
        catch (Exception) { failure ??= Safe(ErrorCode.OutcomeUnknown); }
        _cache = null;
        _cacheLength = 0;
        _serial.Dispose();
        if (failure is null) completion.TrySetResult();
        else completion.TrySetException(failure);
    }

    /// <summary>Tracks actual cancellation callbacks rather than disposing a linked CTS during their execution.</summary>
    private sealed class CancellationScope : IAsyncDisposable
    {
        private readonly object _gate = new();
        private readonly CancellationTokenSource _source = new();
        private readonly CancellationTokenRegistration _first;
        private readonly CancellationTokenRegistration _second;
        private readonly Timer? _timer;
        private TaskCompletionSource? _cancellation;
        private int _timedOut;

        internal CancellationScope(TimeSpan? timeout, CancellationToken first, CancellationToken second = default)
        {
            _first = first.UnsafeRegister(static value => _ = ((CancellationScope)value!).CancelAsync(), this);
            _second = second.UnsafeRegister(static value => _ = ((CancellationScope)value!).CancelAsync(), this);
            if (timeout is { } due)
                _timer = new(static value =>
                {
                    var scope = (CancellationScope)value!;
                    Interlocked.Exchange(ref scope._timedOut, 1);
                    _ = scope.CancelAsync();
                }, this, due, Timeout.InfiniteTimeSpan);
        }

        internal CancellationToken Token => _source.Token;
        internal bool TimedOut => Volatile.Read(ref _timedOut) != 0;
        internal void ThrowIfCanceled() => Token.ThrowIfCancellationRequested();

        internal Task CancelAsync()
        {
            TaskCompletionSource completion;
            lock (_gate)
            {
                if (_cancellation is not null) return _cancellation.Task;
                _cancellation = completion = new(TaskCreationOptions.RunContinuationsAsynchronously);
            }
            _ = CancelCoreAsync(completion);
            return completion.Task;
        }

        private async Task CancelCoreAsync(TaskCompletionSource completion)
        {
            try
            {
                await _source.CancelAsync().ConfigureAwait(false);
                completion.TrySetResult();
            }
            catch (Exception) { completion.TrySetException(Safe(ErrorCode.OutcomeUnknown)); }
        }

        public async ValueTask DisposeAsync()
        {
            await _first.DisposeAsync().ConfigureAwait(false);
            await _second.DisposeAsync().ConfigureAwait(false);
            if (_timer is not null) await _timer.DisposeAsync().ConfigureAwait(false);
            Task? cancellation;
            lock (_gate) cancellation = _cancellation?.Task;
            try
            {
                if (cancellation is not null) await cancellation.ConfigureAwait(false);
            }
            finally { _source.Dispose(); }
        }
    }
}

internal sealed record DownloadPlaybackRangeReaderOptions(TimeSpan OperationTimeout)
{
    internal static DownloadPlaybackRangeReaderOptions Default { get; } = new(TimeSpan.FromSeconds(30));
}
