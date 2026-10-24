using System.Text;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices : ITextContentService
{
    internal const long MaximumTextBytes = 4 * 1024 * 1024;

    async Task<TextContent> ITextContentService.OpenAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(resource);
        var lease = (ResourceLease)await AcquireCachedAsync(resource, cancellationToken).ConfigureAwait(false);
        HttpResponseMessage? response = null;
        byte[]? bytes = null;
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, lease.State.LifetimeToken);
        try
        {
            response = await _images.GetAsync(lease.Address, HttpCompletionOption.ResponseHeadersRead, linked.Token).ConfigureAwait(false);
            CheckStatus((int)response.StatusCode);
            if (response.Content.Headers.ContentLength > MaximumTextBytes)
                throw Safe(ErrorCode.ResponseTooLarge, "文本文件超过本次预览的 4 MB 上限。");
            using var input = await response.Content.ReadAsStreamAsync(linked.Token).ConfigureAwait(false);
            var buffer = new MemoryStream();
            byte[] chunk = new byte[81920];
            while (true)
            {
                int count = await input.ReadAsync(chunk, linked.Token).ConfigureAwait(false);
                if (count == 0) break;
                if (buffer.Length + count > MaximumTextBytes)
                    throw Safe(ErrorCode.ResponseTooLarge, "文本文件超过本次预览的 4 MB 上限。");
                buffer.Write(chunk, 0, count);
            }
            bytes = buffer.ToArray();
            await RefreshSessionAsync(lease.State, linked.Token).ConfigureAwait(false);
            RequireDownload(lease.State);
            var (text, encodingName) = DecodeText(bytes);
            await lease.DisposeAsync().ConfigureAwait(false);
            return new TextContent(resource, response.Content.Headers.ContentLength ?? bytes.LongLength, text, encodingName);
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
            throw Safe(ErrorCode.NetworkUnavailable, "无法读取文本内容，请检查连接后重试。");
        }
        finally { response?.Dispose(); _ = bytes; }
    }

    internal static (string Text, string EncodingName) DecodeText(byte[] bytes)
    {
        if (bytes.Length == 0) return ("", "UTF-8");
        // BOM first: it is the only explicit author intent we can honor.
        if (bytes.Length >= 3 && bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF)
            return (new UTF8Encoding(false).GetString(bytes, 3, bytes.Length - 3), "UTF-8 BOM");
        if (bytes.Length >= 2 && bytes[0] == 0xFF && bytes[1] == 0xFE)
            return (Encoding.Unicode.GetString(bytes, 2, bytes.Length - 2), "UTF-16 LE BOM");
        if (bytes.Length >= 2 && bytes[0] == 0xFE && bytes[1] == 0xFF)
            return (Encoding.BigEndianUnicode.GetString(bytes, 2, bytes.Length - 2), "UTF-16 BE BOM");
        // Strict UTF-8 accepts the overwhelming majority of modern text.
        try { return (new UTF8Encoding(false, true).GetString(bytes), "UTF-8"); }
        catch (DecoderFallbackException) { }
        // GB18030 covers the remaining legacy Chinese text; decoding is total, so this is honest
        // only as a labeled fallback rather than a claimed true encoding.
        return (Encoding.GetEncoding("GB18030").GetString(bytes), "GB18030（回退）");
    }
}
