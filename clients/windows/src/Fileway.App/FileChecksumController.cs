using Fileway.Core.Contracts;

namespace Fileway.App;

/// <summary>Explicit, read-only checksum state for one original resource and verified session.</summary>
internal sealed class FileChecksumController : IDisposable
{
    private readonly object _gate = new();
    private IFileChecksumService? _service;
    private RemoteResourceRef? _source;
    private ConnectionSession? _session;
    private RequestCancellation? _request;
    private long _generation;
    private long _epoch;
    private bool _disposed;
    private bool _calculating;
    private FileChecksumResult? _result;
    private string? _error;

    internal bool IsCalculating { get { lock (_gate) return _calculating; } }
    internal FileChecksumResult? Result { get { lock (_gate) return _result; } }
    internal string? Error { get { lock (_gate) return _error; } }

    internal void SetContext(IFileChecksumService? service, RemoteResourceRef? source, ConnectionSession? session)
    {
        RequestCancellation? request;
        lock (_gate)
        {
            if (_disposed) return;
            if (ReferenceEquals(_service, service) && ReferenceEquals(_source, source) &&
                _session?.Id == session?.Id && _session?.Account == session?.Account &&
                _session?.Permissions.Download == session?.Permissions.Download)
            { _session = session; return; }
            _service = service; _source = source; _session = session;
            ++_generation; ++_epoch;
            request = _request; _request = null;
            _calculating = false; _result = null; _error = null;
        }
        request?.Cancel();
    }

    internal static bool TryExpectedDigest(ChecksumAlgorithm algorithm, string? input, out string? expected)
    {
        expected = null;
        int length = DigestLength(algorithm);
        if (length == 0) return false;
        string? value = input?.Trim();
        if (string.IsNullOrEmpty(value)) return true;
        if (!IsDigest(value, length)) return false;
        expected = value.ToLowerInvariant();
        return true;
    }

    internal async Task CalculateAsync(ChecksumAlgorithm algorithm, string? expectedDigest,
        CancellationToken cancellationToken = default)
    {
        RequestContext context;
        RequestCancellation request;
        RequestCancellation? previous;
        long epoch;
        string? expected;
        lock (_gate)
        {
            if (_disposed || _service is null || _source is null || _session is null) return;
            previous = _request; _request = null;
            epoch = ++_epoch; _result = null; _error = null; _calculating = false;
            if (_source.Account != _session.Account || !_session.Permissions.Download)
                _error = "当前账号没有访问权限。";
            else if (!TryExpectedDigest(algorithm, expectedDigest, out _))
                _error = "预期校验和的长度或十六进制格式无效。";
            if (_error is not null)
            {
                context = default; request = null!; expected = null;
            }
            else
            {
                _ = TryExpectedDigest(algorithm, expectedDigest, out expected);
                context = new(_service, _source, _session.Account, _session.Id, _generation);
                request = new(cancellationToken); _request = request; _calculating = true;
            }
        }
        previous?.Cancel();
        if (request is null) return;
        try
        {
            request.Token.ThrowIfCancellationRequested();
            var result = await context.Service.CalculateChecksumAsync(context.Source, algorithm, expected, request.Token).ConfigureAwait(false);
            lock (_gate)
            {
                if (!IsCurrent(context, epoch, request) || request.Token.IsCancellationRequested) return;
                ValidateResult(result, context.Source, algorithm, expected);
                _result = result;
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            lock (_gate)
                if (IsCurrent(context, epoch, request) && !request.Token.IsCancellationRequested) _error = ReadError(error);
        }
        finally
        {
            lock (_gate)
                if (IsCurrent(context, epoch, request)) { _request = null; _calculating = false; }
            request.Dispose();
        }
    }

    internal void Cancel()
    {
        RequestCancellation? request;
        lock (_gate)
        {
            if (_disposed) return;
            ++_epoch; request = _request; _request = null;
            _calculating = false; _result = null; _error = null;
        }
        request?.Cancel();
    }

    public void Dispose()
    {
        RequestCancellation? request;
        lock (_gate)
        {
            if (_disposed) return;
            _disposed = true; ++_generation; ++_epoch;
            request = _request; _request = null;
            _service = null; _source = null; _session = null;
            _calculating = false; _result = null; _error = null;
        }
        request?.Cancel();
    }

    private bool IsCurrent(RequestContext context, long epoch, RequestCancellation request) => !_disposed &&
        _generation == context.Generation && _epoch == epoch && ReferenceEquals(_request, request) &&
        ReferenceEquals(_service, context.Service) && ReferenceEquals(_source, context.Source) &&
        _session?.Id == context.SessionId && _session.Account == context.Account && _session.Permissions.Download;

    private static int DigestLength(ChecksumAlgorithm algorithm) => algorithm switch
    { ChecksumAlgorithm.Md5 => 32, ChecksumAlgorithm.Sha1 => 40, ChecksumAlgorithm.Sha256 => 64, ChecksumAlgorithm.Sha512 => 128, _ => 0 };
    private static bool IsDigest(string value, int length) => value.Length == length && value.All(char.IsAsciiHexDigit);
    private static void ValidateResult(FileChecksumResult result, RemoteResourceRef source, ChecksumAlgorithm algorithm, string? expected)
    {
        if (result is null || result.Resource is null || result.Resource.Account != source.Account ||
            !string.Equals(result.Resource.WirePath, source.WirePath, StringComparison.Ordinal) ||
            !string.Equals(result.Resource.SourceVersion, source.SourceVersion, StringComparison.Ordinal) ||
            result.Algorithm != algorithm || result.Digest is null || !IsDigest(result.Digest, DigestLength(algorithm)) || result.Size < 0 ||
            result.Comparison != (expected is null ? ChecksumComparison.NotRequested :
                string.Equals(result.Digest, expected, StringComparison.OrdinalIgnoreCase) ? ChecksumComparison.Matches : ChecksumComparison.Differs))
            throw new FilewayException(new(ErrorCode.ProtocolMismatch, "文件校验结果与原请求不一致，请重试。"));
    }
    private static string ReadError(Exception error) => error is FilewayException known ? known.Error.HttpStatus switch
    {
        403 => "当前账号没有访问权限。", 404 => "原文件已不存在，请刷新后重试。",
        _ => known.Error.Code switch
        {
            ErrorCode.Conflict or ErrorCode.SourceChanged => "文件在计算期间已变化，请刷新后重试。",
            ErrorCode.AuthenticationExpired or ErrorCode.CredentialsUnavailable => "登录已过期，请重新连接。",
            ErrorCode.PermissionDenied => "当前账号没有访问权限。",
            ErrorCode.NetworkUnavailable => "网络暂不可用，请重试。", ErrorCode.Timeout => "计算请求超时，请重试。",
            ErrorCode.HostExited => "连接服务已退出，请重新连接。",
            ErrorCode.ProtocolMismatch => "文件校验结果与原请求不一致，请重试。",
            _ => "暂时无法计算校验和，请重试。",
        },
    } : "暂时无法计算校验和，请重试。";

    private sealed class RequestCancellation : IDisposable
    {
        private readonly object _gate = new();
        private readonly CancellationTokenSource _source;
        private Task? _cancellation;
        private bool _disposed;
        internal RequestCancellation(CancellationToken caller)
        { _source = CancellationTokenSource.CreateLinkedTokenSource(caller); Token = _source.Token; }
        internal CancellationToken Token { get; }
        internal void Cancel()
        {
            Task cancellation;
            lock (_gate) { if (_disposed) return; cancellation = _cancellation ??= _source.CancelAsync(); }
            _ = ObserveAsync(cancellation);
        }
        public void Dispose()
        {
            Task? cancellation;
            lock (_gate)
            {
                if (_disposed) return;
                _disposed = true; cancellation = _cancellation;
                if (cancellation is null || cancellation.IsCompleted) { _source.Dispose(); return; }
            }
            _ = DisposeAsync(_source, cancellation);
        }
        private static async Task ObserveAsync(Task cancellation)
        { try { await cancellation.ConfigureAwait(false); } catch (Exception) { } }
        private static async Task DisposeAsync(CancellationTokenSource source, Task cancellation)
        { await ObserveAsync(cancellation).ConfigureAwait(false); source.Dispose(); }
    }
    private readonly record struct RequestContext(IFileChecksumService Service, RemoteResourceRef Source, AccountKey Account, Guid SessionId, long Generation);
}
