using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices : IFileBytesService
{
    async Task<FileBytes> IFileBytesService.OpenAsync(RemoteResourceRef resource, long maximumBytes,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(resource);
        if (maximumBytes is < 1 or > 256L * 1024 * 1024) throw Safe(ErrorCode.InvalidRequest, Message(ErrorCode.InvalidRequest));
        var lease = (ResourceLease)await AcquireCachedAsync(resource, cancellationToken).ConfigureAwait(false);
        HttpResponseMessage? response = null;
        MemoryStream? content = null;
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, lease.State.LifetimeToken);
        try
        {
            response = await _images.GetAsync(lease.Address, HttpCompletionOption.ResponseHeadersRead, linked.Token).ConfigureAwait(false);
            CheckStatus((int)response.StatusCode);
            if (response.Content.Headers.ContentLength > maximumBytes)
                throw Safe(ErrorCode.ResponseTooLarge, $"文件超过本次预览的 {maximumBytes / (1024 * 1024)} MB 上限。");
            content = new MemoryStream();
            await using var input = await response.Content.ReadAsStreamAsync(linked.Token).ConfigureAwait(false);
            byte[] buffer = new byte[81920];
            while (true)
            {
                int count = await input.ReadAsync(buffer, linked.Token).ConfigureAwait(false);
                if (count == 0) break;
                if (content.Length + count > maximumBytes)
                    throw Safe(ErrorCode.ResponseTooLarge, $"文件超过本次预览的 {maximumBytes / (1024 * 1024)} MB 上限。");
                await content.WriteAsync(buffer.AsMemory(0, count), linked.Token).ConfigureAwait(false);
            }
            await RefreshSessionAsync(lease.State, linked.Token).ConfigureAwait(false);
            RequireDownload(lease.State);
            var bytes = content.ToArray();
            await lease.DisposeAsync().ConfigureAwait(false);
            return new FileBytes(resource, response.Content.Headers.ContentLength ?? bytes.LongLength, bytes);
        }
        catch (FilewayException) { await lease.DisposeAsync().ConfigureAwait(false); throw; }
        catch (OperationCanceledException)
        {
            await lease.DisposeAsync().ConfigureAwait(false);
            var code = cancellationToken.IsCancellationRequested ? ErrorCode.Canceled :
                lease.State.LifetimeToken.IsCancellationRequested ? ErrorCode.SourceChanged : ErrorCode.Timeout;
            throw Safe(code, Message(code));
        }
        catch (Exception error) when (error is HttpRequestException or IOException or ObjectDisposedException)
        {
            await lease.DisposeAsync().ConfigureAwait(false);
            throw Safe(ErrorCode.NetworkUnavailable, "无法读取文件内容，请检查连接后重试。");
        }
        finally { response?.Dispose(); content?.Dispose(); }
    }
}
