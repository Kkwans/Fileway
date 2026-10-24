using System.Globalization;
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
        var state = GetResourceState(resource);
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
            return new TextContent(resource, response.Content.Headers.ContentLength ?? bytes.LongLength, text, encodingName)
            {
                Edit = await ExpectationAsync(state, resource, bytes.LongLength, linked.Token).ConfigureAwait(false),
            };
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

    /// <summary>Fetches the conditional-write expectation; null when the read is already stale.</summary>
    private async Task<TextEditExpectation?> ExpectationAsync(SessionState state, RemoteResourceRef resource,
        long observedBytes, CancellationToken cancellationToken)
    {
        try
        {
            using var document = await DownloadMetadataDocumentAsync(state, resource, cancellationToken).ConfigureAwait(false);
            long size = document.RootElement.GetProperty("size").GetInt64();
            string modified = RequiredText(document.RootElement.GetProperty("modified"));
            if (size != observedBytes) return null; // Content changed during the read; keep view-only.
            return new TextEditExpectation(size, modified);
        }
        catch (Exception error) when (error is FilewayException { Error.Code: ErrorCode.SourceChanged or ErrorCode.InvalidRequest }
            or System.Text.Json.JsonException or KeyNotFoundException or InvalidOperationException or FormatException)
        { return null; }
    }

    async Task<TextEditExpectation> ITextContentService.SaveAsync(RemoteResourceRef resource, TextEditExpectation expected,
        string newText, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(resource);
        ArgumentNullException.ThrowIfNull(expected);
        ArgumentNullException.ThrowIfNull(newText);
        var state = GetResourceState(resource);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        try
        {
            byte[] bytes = Encoding.UTF8.GetBytes(newText);
            if (bytes.LongLength > MaximumTextBytes)
                throw Safe(ErrorCode.ResponseTooLarge, "文本超过本次编辑的 4 MB 上限。");
            string endpoint = "/api/resources" + resource.WirePath +
                "?conditional=true&expectedSize=" + expected.Size.ToString(CultureInfo.InvariantCulture) +
                "&expectedModified=" + Uri.EscapeDataString(expected.ModifiedRaw);
            var response = await SessionRequestAsync(state, new
            {
                method = "PUT",
                endpoint,
                bodyBase64 = Convert.ToBase64String(bytes),
            }, requireDownload: true, HostCallSafety.Mutation, linked.Token, operation: "request",
            beforeSend: async token =>
            {
                RequireModify(state);
            }).ConfigureAwait(false);
            RequireExactStatus(response, 200);
            // Refresh the observation so the next save is conditioned on the file we just wrote.
            var next = await ExpectationAsync(state, resource, bytes.LongLength, linked.Token).ConfigureAwait(false)
                ?? throw Safe(ErrorCode.SourceChanged, "保存已确认，但无法取得最新文件状态；请重新打开后再编辑。");
            return next;
        }
        catch (FilewayException error) when (error.Error.HttpStatus == 409)
        { throw Safe(ErrorCode.SourceChanged, "文件已在服务器上变化；本次修改未写入，请重新打开后再编辑。", 409); }
        catch (OperationCanceledException) { throw SessionCanceled(state, cancellationToken); }
    }

    private void RequireModify(SessionState state)
    {
        lock (_gate)
        {
            EnsureActive(state);
            if (!state.Session.Permissions.Modify) throw Safe(ErrorCode.PermissionDenied, Message(ErrorCode.PermissionDenied));
        }
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
