using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices : IDownloadSourceProvider
{
    public async Task<RemoteFile> PrepareDownloadAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
    {
        ValidateFreshDownloadSource(resource);
        var state = GetResourceState(resource);
        return await ReadFreshDownloadMetadataAsync(state, resource, cancellationToken).ConfigureAwait(false);
    }

    public async Task<IDownloadLease> AcquireDownloadAsync(DownloadLeaseRequest request, CancellationToken cancellationToken)
    {
        ValidateFreshDownloadRequest(request);
        var state = GetResourceState(request.Source);
        FreshDownloadLease? captured = null;
        bool attempted = false;
        try
        {
            _ = await SessionRequestAsync(state, new { wirePath = request.Source.WirePath }, requireDownload: true,
                HostCallSafety.Control, cancellationToken, operation: "lease", beforeSend: async token =>
                {
                    // Operations is already held. Do not re-enter the session
                    // gate while authenticating the file's current metadata.
                    var metadata = await ReadFreshDownloadMetadataAsync(state, request.Source, token,
                        operationGateOwned: true).ConfigureAwait(false);
                    RequireDownloadLength(metadata.Size, request.Length);
                }, authorizeSend: () =>
                {
                    RequireDownload(state);
                    attempted = true;
                }, confirmResponse: response =>
                {
                    // The strict validator is shared with upload capabilities;
                    // both use the same private loopback /stream/{id} origin.
                    var inner = new ResourceLease(this, state, request.Source, UploadAddress(response));
                    captured = new FreshDownloadLease(this, inner, request.Length);
                    lock (_gate)
                    {
                        EnsureActive(state);
                        state.Leases.Add(inner);
                    }
                }).ConfigureAwait(false);
            lock (_gate)
            {
                cancellationToken.ThrowIfCancellationRequested();
                RequireDownload(state);
                return captured ?? throw InvalidResponse();
            }
        }
        catch (OperationCanceledException)
        {
            var failure = SessionCanceled(state, cancellationToken);
            if (captured is not null) await captured.DisposeAsync().ConfigureAwait(false);
            else if (attempted) await RetireAsync(state).ConfigureAwait(false);
            throw failure;
        }
        catch (FilewayException error)
        {
            if (captured is not null) await captured.DisposeAsync().ConfigureAwait(false);
            else if (attempted && error.Error.Code is ErrorCode.Canceled or ErrorCode.Timeout or ErrorCode.ProtocolMismatch or
                ErrorCode.NetworkUnavailable or ErrorCode.HostExited or ErrorCode.OutcomeUnknown or ErrorCode.ResponseTooLarge)
            {
                // An unseen or malformed control response can hide a live
                // capability. Closing its original session retires it without
                // replaying acquisition or affecting a replacement session.
                await RetireAsync(state).ConfigureAwait(false);
            }
            throw;
        }
    }

    public async Task<FileChecksumResult> CalculateDownloadChecksumAsync(DownloadLeaseRequest request,
        CancellationToken cancellationToken)
    {
        ValidateFreshDownloadRequest(request);
        var state = GetResourceState(request.Source);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        try
        {
            using var beforeBody = await DownloadMetadataDocumentAsync(state, request.Source, linked.Token).ConfigureAwait(false);
            var before = ParseFreshDownloadChecksumMetadata(beforeBody.RootElement, request, state);
            RequireDownload(state);

            // Reuse the existing long-read/checksum facilities, but inspect all
            // raw responses before ParseFile can discard an unknown version.
            var response = await SessionLongReadAsync(state,
                "/api/resources" + request.Source.WirePath + "?metadata=1&checksum=sha256",
                TimeSpan.FromMinutes(16), linked.Token, requireDownload: true).ConfigureAwait(false);
            RequireExactStatus(response, 200);
            using var hashBody = ParseBody(response);
            var hashed = ParseFreshDownloadChecksumMetadata(hashBody.RootElement, request, state);
            RequireUnchangedChecksumSource(before, hashed);
            string digest = NormalizeChecksumHex(RequiredText(hashBody.RootElement.GetProperty("checksums").GetProperty("sha256")),
                64, fromServer: true);

            using var afterBody = await DownloadMetadataDocumentAsync(state, request.Source, linked.Token).ConfigureAwait(false);
            var after = ParseFreshDownloadChecksumMetadata(afterBody.RootElement, request, state);
            RequireUnchangedChecksumSource(before, after);
            lock (_gate)
            {
                linked.Token.ThrowIfCancellationRequested();
                RequireDownload(state);
                // This validates a fresh source observation, not an immutable
                // historical version or permission to resume an old prefix.
                return new FileChecksumResult(request.Source, ChecksumAlgorithm.Sha256, digest,
                    ChecksumComparison.NotRequested, after.File.Size, after.File.Modified);
            }
        }
        catch (OperationCanceledException) { throw SessionCanceled(state, cancellationToken); }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    private async Task<RemoteFile> ReadFreshDownloadMetadataAsync(SessionState state, RemoteResourceRef resource,
        CancellationToken cancellationToken, bool operationGateOwned = false)
    {
        try
        {
            using var document = await DownloadMetadataDocumentAsync(state, resource, cancellationToken,
                operationGateOwned).ConfigureAwait(false);
            RequireUnversionedDownloadResponse(document.RootElement);
            var file = ParseFile(document.RootElement, state);
            if (FileOperationWireIdentity(file.Resource.WirePath, allowRoot: true) !=
                FileOperationWireIdentity(resource.WirePath, allowRoot: true)) throw InvalidResponse();
            if (file.IsDirectory) throw Safe(ErrorCode.InvalidRequest, "文件夹不能作为单文件下载源。");
            cancellationToken.ThrowIfCancellationRequested();
            RequireDownload(state);
            return file;
        }
        catch (OperationCanceledException) { throw SessionCanceled(state, cancellationToken); }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    private async Task<JsonDocument> DownloadMetadataDocumentAsync(SessionState state, RemoteResourceRef resource,
        CancellationToken cancellationToken, bool operationGateOwned = false)
    {
        string endpoint = "/api/resources" + resource.WirePath + "?metadata=1";
        var response = operationGateOwned
            ? await RequestAsync(state.CoreSession, endpoint, cancellationToken).ConfigureAwait(false)
            : await RequestAsync(state, endpoint, cancellationToken, requireDownload: true).ConfigureAwait(false);
        RequireExactStatus(response, 200);
        return ParseBody(response);
    }

    private ChecksumMetadata ParseFreshDownloadChecksumMetadata(JsonElement row, DownloadLeaseRequest request, SessionState state)
    {
        RequireUnversionedDownloadResponse(row);
        var metadata = ParseChecksumMetadata(row, request.Source, state);
        RequireDownloadLength(metadata.File.Size, request.Length);
        return metadata;
    }

    private static void ValidateFreshDownloadRequest(DownloadLeaseRequest request)
    {
        ArgumentNullException.ThrowIfNull(request);
        ValidateFreshDownloadSource(request.Source);
        if (request.Length < 0) throw Safe(ErrorCode.InvalidRequest, Message(ErrorCode.InvalidRequest));
    }

    private static void ValidateFreshDownloadSource(RemoteResourceRef resource)
    {
        ArgumentNullException.ThrowIfNull(resource);
        // A capability flag alone cannot supply the missing conditional raw
        // and checksum protocol. Never downgrade a caller's pinned version.
        if (resource.SourceVersion is not null) throw Safe(ErrorCode.CapabilityMissing, Message(ErrorCode.CapabilityMissing));
        _ = FileOperationWireIdentity(resource.WirePath, allowRoot: true);
    }

    private static void RequireUnversionedDownloadResponse(JsonElement row)
    {
        if (!row.TryGetProperty("sourceVersion", out var version) || version.ValueKind == JsonValueKind.Null) return;
        if (version.ValueKind != JsonValueKind.String) throw InvalidResponse();
        throw Safe(ErrorCode.CapabilityMissing, Message(ErrorCode.CapabilityMissing));
    }

    private static void RequireDownloadLength(long observed, long expected)
    {
        if (observed != expected) throw Safe(ErrorCode.Conflict, "下载源长度已变化，请刷新后重试。", 409);
    }

    private sealed class FreshDownloadLease(FilewayClientServices owner, ResourceLease inner, long length) : IDownloadLease
    {
        private readonly object _disposalGate = new();
        private Task? _disposal;
        public RemoteResourceRef Resource => inner.Resource;
        public Uri Address => inner.Address;
        public long Length { get; } = length;
        public string? SourceVersion => null;
        public bool SupportsResume => false;
        public override string ToString() => "FreshDownloadLease (address redacted)";

        public ValueTask DisposeAsync()
        {
            lock (_disposalGate) return new ValueTask(_disposal ??= DisposeCoreAsync());
        }

        private async Task DisposeCoreAsync()
        {
            // All inner consumers share one revoke result, including account
            // retirement. Fallback belongs here, outside its inner release, so
            // a failed revoke during logout cannot form a Retirement wait cycle.
            if (!await inner.TryDisposeConfirmedAsync().ConfigureAwait(false))
                await owner.RetireAsync(inner.State).ConfigureAwait(false);
        }
    }
}
