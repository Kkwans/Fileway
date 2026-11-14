using System.Buffers;
using System.Globalization;
using System.Text;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices : IUploadLeaseProvider
{
    private static readonly SearchValues<char> UploadCapabilityCharacters =
        SearchValues.Create("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~");

    public async Task<IUploadLease> AcquireUploadAsync(UploadLeaseRequest request, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(request);
        ArgumentNullException.ThrowIfNull(request.Target);
        if (request.Length < 0 || request.TransferId == Guid.Empty) throw InvalidUploadRequest();
        _ = FileOperationWireIdentity(request.Target.WirePath, allowRoot: false);
        var metadata = UploadMetadata(request.Batch);
        var state = GetResourceState(request.Target);
        UploadLease? captured = null;
        bool attempted = false;
        try
        {
            _ = await SessionRequestAsync(state, new
            {
                wirePath = request.Target.WirePath,
                upload = new
                {
                    protocol = "tus", size = request.Length,
                    transferId = request.TransferId.ToString("N"),
                    overwrite = request.Overwrite, resume = request.Resume, metadata,
                },
            }, requireDownload: false, HostCallSafety.Control, cancellationToken, operation: "upload_lease",
                authorizeSend: () =>
                {
                    RequireUpload(state, request.Overwrite);
                    attempted = true;
                }, confirmResponse: response =>
                {
                    var inner = new ResourceLease(this, state, request.Target, UploadAddress(response));
                    captured = new UploadLease(this, inner, request);
                    lock (_gate)
                    {
                        EnsureActive(state);
                        state.Leases.Add(inner);
                    }
                }).ConfigureAwait(false);
            lock (_gate)
            {
                cancellationToken.ThrowIfCancellationRequested();
                RequireUpload(state, request.Overwrite);
                return captured ?? throw InvalidResponse();
            }
        }
        catch (OperationCanceledException)
        {
            if (captured is not null) await captured.DisposeAsync().ConfigureAwait(false);
            throw SessionCanceled(state, cancellationToken);
        }
        catch (FilewayException error)
        {
            if (captured is not null) await captured.DisposeAsync().ConfigureAwait(false);
            // GoHostClient supplies the finite 30-second default call deadline.
            // A timed-out control response may hide a capability: closing only
            // this original owner also closes any unobserved late lease.
            if (attempted && captured is null && error.Error.Code == ErrorCode.Timeout)
            {
                try { _ = await RetireAsync(state).ConfigureAwait(false); }
                catch (FilewayException cleanupError)
                {
                    PublishConnectionIssue(state, cleanupError.Error);
                    throw;
                }
                // Notify the outer application authority after retirement. Its handler fences admission
                // and schedules drain; it must never await the runner from this acquisition stack.
                PublishConnectionIssue(state, error.Error);
            }
            throw;
        }
    }

    private void RequireUpload(SessionState state, bool overwrite)
    {
        EnsureActive(state);
        if (!state.Session.Permissions.Create || overwrite && !state.Session.Permissions.Modify)
            throw Safe(ErrorCode.PermissionDenied, Message(ErrorCode.PermissionDenied));
    }

    private static Dictionary<string, string> UploadMetadata(UploadBatchMetadata? batch)
    {
        Dictionary<string, string> metadata = [];
        if (batch is null) return metadata;
        if (batch.Id == Guid.Empty || batch.Name is null || batch.ItemCount <= 0 || batch.TotalBytes < 0)
            throw InvalidUploadRequest();
        try
        {
            // Reject invalid text before escaping: EscapeDataString otherwise
            // substitutes invalid UTF-16 and would lose the supplied identity.
            _ = StrictUtf8.GetByteCount(batch.Name);
            if (batch.Name.Contains('\r') || batch.Name.Contains('\n')) throw InvalidUploadRequest();
            metadata.Add("X-Upload-Batch-ID", batch.Id.ToString("N"));
            metadata.Add("X-Upload-Batch-Name", Uri.EscapeDataString(batch.Name));
            metadata.Add("X-Upload-Batch-Items", batch.ItemCount.ToString(CultureInfo.InvariantCulture));
            metadata.Add("X-Upload-Batch-Bytes", batch.TotalBytes.ToString(CultureInfo.InvariantCulture));
            metadata.Add("X-Upload-Folder", batch.IsFolder ? "true" : "false");
            foreach (string value in metadata.Values)
                if (StrictUtf8.GetByteCount(value) > 1024 || value.Contains('\r') || value.Contains('\n'))
                    throw InvalidUploadRequest();
        }
        catch (EncoderFallbackException) { throw InvalidUploadRequest(); }
        return metadata;
    }

    private static FilewayException InvalidUploadRequest() => Safe(ErrorCode.InvalidRequest, Message(ErrorCode.InvalidRequest));

    private static Uri UploadAddress(JsonElement response)
    {
        string address = RequiredText(response);
        const string origin = "http://127.0.0.1:";
        if (!address.StartsWith(origin, StringComparison.Ordinal)) throw InvalidResponse();
        int pathStart = address.IndexOf('/', origin.Length);
        if (pathStart < 0 || !int.TryParse(address.AsSpan(origin.Length, pathStart - origin.Length),
            NumberStyles.None, CultureInfo.InvariantCulture, out int port) || port is <= 0 or > 65535 or 80)
            throw InvalidResponse();
        string path = address[pathStart..];
        const string prefix = "/stream/";
        if (!path.StartsWith(prefix, StringComparison.Ordinal) || path.Length == prefix.Length ||
            path.AsSpan(prefix.Length).ContainsAnyExcept(UploadCapabilityCharacters))
            throw InvalidResponse();
        if (!Uri.TryCreate(address, UriKind.Absolute, out var uri) || uri.Scheme != Uri.UriSchemeHttp ||
            uri.Host != "127.0.0.1" || uri.Port != port || uri.IsDefaultPort || uri.UserInfo.Length != 0 ||
            uri.Query.Length != 0 || uri.Fragment.Length != 0 || uri.AbsolutePath != path)
            throw InvalidResponse();
        return uri;
    }

    private sealed class UploadLease(FilewayClientServices owner, ResourceLease inner, UploadLeaseRequest request) : IUploadLease
    {
        private int _disposed;
        public RemoteResourceRef Resource => inner.Resource;
        public Uri Address => inner.Address;
        public Guid TransferId { get; } = request.TransferId;
        public long Length { get; } = request.Length;
        public bool IsResume { get; } = request.Resume;
        public override string ToString() => "UploadLease (address redacted)";

        public async Task<UploadLeaseStatistics?> ReadUploadStatisticsAsync(CancellationToken cancellationToken)
        {
            lock (owner._gate) if (!CheckOwner()) return null;
            using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, inner.State.LifetimeToken);
            try
            {
                var result = await owner.CallAsync("upload_stats", new { url = Address.AbsoluteUri }, inner.State.CoreSession,
                    linked.Token).ConfigureAwait(false);
                lock (owner._gate) if (!CheckOwner()) return null;
                long sent = result.GetProperty("sentBytes").GetInt64();
                long offset = result.GetProperty("acceptedOffset").GetInt64();
                long current = result.GetProperty("currentSent").GetInt64();
                long size = result.GetProperty("currentSize").GetInt64();
                long id = result.GetProperty("requestId").GetInt64();
                long elapsed = result.GetProperty("elapsedMillis").GetInt64();
                bool active = result.GetProperty("active").GetBoolean();
                if (sent < 0 || offset < -1 || offset > Length || current < 0 || current > Length ||
                    size < -1 || size > Length || size >= 0 && current > size || id < 0 || elapsed < 0 ||
                    elapsed > TimeSpan.MaxValue.Ticks / TimeSpan.TicksPerMillisecond) throw InvalidResponse();
                lock (owner._gate)
                {
                    if (!CheckOwner()) return null;
                    cancellationToken.ThrowIfCancellationRequested();
                    return new UploadLeaseStatistics(sent, offset == -1 ? null : offset, current, size == -1 ? null : size,
                        id, TimeSpan.FromTicks(elapsed * TimeSpan.TicksPerMillisecond), active);
                }
            }
            catch (Exception) when (Volatile.Read(ref _disposed) != 0) { return null; }
            catch (Exception) when (inner.State.LifetimeToken.IsCancellationRequested)
            { throw Safe(ErrorCode.SourceChanged, Message(ErrorCode.SourceChanged)); }
            catch (OperationCanceledException) { throw Safe(ErrorCode.Canceled, Message(ErrorCode.Canceled)); }
            catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException or OverflowException)
            { throw InvalidResponse(); }
        }

        // Caller holds the owner's gate. Retirement disposes the tracked inner
        // lease, while explicit wrapper disposal alone gives null statistics.
        private bool CheckOwner()
        {
            if (_disposed != 0) return false;
            owner.EnsureActive(inner.State);
            if (!inner.State.Leases.Contains(inner)) throw Safe(ErrorCode.SourceChanged, Message(ErrorCode.SourceChanged));
            return true;
        }

        public ValueTask DisposeAsync()
        {
            lock (owner._gate)
            {
                if (Interlocked.Exchange(ref _disposed, 1) != 0) return ValueTask.CompletedTask;
            }
            return inner.DisposeAsync();
        }
    }
}
