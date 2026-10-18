using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices : IDownloadSourceProvider, IConditionalDownloadSourceProvider
{
    public bool CanUseConditionalDownload(RemoteResourceRef resource)
    {
        ValidateConditionalDownloadSource(resource);
        var state = GetResourceState(resource);
        lock (_gate)
        {
            EnsureActive(state);
            return state.Session.Capabilities.ConditionalDownloadV1;
        }
    }

    public async Task<RemoteFile> PrepareConditionalDownloadAsync(RemoteResourceRef resource,
        CancellationToken cancellationToken)
    {
        ValidateConditionalDownloadSource(resource);
        var state = GetResourceState(resource);
        RequireConditionalDownload(state);
        try
        {
            using var document = await DownloadMetadataDocumentAsync(state, resource, cancellationToken).ConfigureAwait(false);
            var file = ParseConditionalDownloadFile(document.RootElement, state, resource);
            cancellationToken.ThrowIfCancellationRequested();
            RequireDownload(state);
            return file;
        }
        catch (OperationCanceledException) { throw SessionCanceled(state, cancellationToken); }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    public async Task<IDownloadLease> AcquireConditionalDownloadAsync(ConditionalDownloadLeaseRequest request,
        CancellationToken cancellationToken)
    {
        ValidateConditionalDownloadRequest(request);
        var state = GetResourceState(request.Source);
        RequireConditionalDownload(state);
        FreshDownloadLease? captured = null;
        bool attempted = false;
        try
        {
            _ = await SessionRequestAsync(state, new
            {
                wirePath = request.Source.WirePath,
                sourceVersion = request.SourceVersion,
            }, requireDownload: true, HostCallSafety.Control, cancellationToken, operation: "lease", beforeSend: async token =>
            {
                var metadata = await ReadConditionalDownloadMetadataAsync(state, request.Source, token,
                    operationGateOwned: true).ConfigureAwait(false);
                RequireDownloadLength(metadata.Size, request.Length);
                if (!StringComparer.Ordinal.Equals(metadata.Resource.SourceVersion, request.SourceVersion))
                    throw Safe(ErrorCode.SourceChanged, "下载播放源已变化，请重新打开。", 412);
            }, authorizeSend: () =>
            {
                RequireConditionalDownload(state);
                attempted = true;
            }, confirmResponse: response =>
            {
                var inner = new ResourceLease(this, state, request.Source, UploadAddress(response));
                captured = new FreshDownloadLease(this, inner, request.Length, request.SourceVersion);
                lock (_gate)
                {
                    EnsureActive(state);
                    state.Leases.Add(inner);
                }
            }).ConfigureAwait(false);
            lock (_gate)
            {
                cancellationToken.ThrowIfCancellationRequested();
                RequireConditionalDownload(state);
                return captured ?? throw InvalidResponse();
            }
        }
        catch (OperationCanceledException)
        {
            var failure = SessionCanceled(state, cancellationToken);
            if (captured is not null) await captured.DisposeAsync().ConfigureAwait(false);
            else if (attempted) await RetireDownloadOwnerAsync(state, failure.Error).ConfigureAwait(false);
            throw failure;
        }
        catch (FilewayException error)
        {
            if (captured is not null) await captured.DisposeAsync().ConfigureAwait(false);
            else if (attempted && error.Error.Code is ErrorCode.Canceled or ErrorCode.Timeout or ErrorCode.ProtocolMismatch or
                ErrorCode.NetworkUnavailable or ErrorCode.HostExited or ErrorCode.OutcomeUnknown or ErrorCode.ResponseTooLarge)
                await RetireDownloadOwnerAsync(state, error.Error).ConfigureAwait(false);
            throw;
        }
    }

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
            else if (attempted) await RetireDownloadOwnerAsync(state, failure.Error).ConfigureAwait(false);
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
                await RetireDownloadOwnerAsync(state, error.Error).ConfigureAwait(false);
            }
            throw;
        }
    }

    private async Task RetireDownloadOwnerAsync(SessionState state, ErrorInfo error)
    {
        try { _ = await RetireAsync(state).ConfigureAwait(false); }
        catch (FilewayException cleanupError)
        {
            error = cleanupError.Error;
            throw;
        }
        finally
        {
            // Fence the original application's authority without waiting for its runner from this acquisition stack.
            PublishConnectionIssue(state, error);
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

    private async Task<RemoteFile> ReadConditionalDownloadMetadataAsync(SessionState state, RemoteResourceRef resource,
        CancellationToken cancellationToken, bool operationGateOwned = false)
    {
        try
        {
            using var document = await DownloadMetadataDocumentAsync(state, resource, cancellationToken,
                operationGateOwned).ConfigureAwait(false);
            var file = ParseConditionalDownloadFile(document.RootElement, state, resource);
            cancellationToken.ThrowIfCancellationRequested();
            RequireConditionalDownload(state);
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

    private static void ValidateConditionalDownloadSource(RemoteResourceRef resource)
    {
        ArgumentNullException.ThrowIfNull(resource);
        if (resource.SourceVersion is not null) throw Safe(ErrorCode.CapabilityMissing, Message(ErrorCode.CapabilityMissing));
        _ = FileOperationWireIdentity(resource.WirePath, allowRoot: true);
    }

    private static void ValidateConditionalDownloadRequest(ConditionalDownloadLeaseRequest request)
    {
        ArgumentNullException.ThrowIfNull(request);
        if (request.Length < 0 || string.IsNullOrEmpty(request.SourceVersion) || request.SourceVersion.Length > 4096 ||
            request.SourceVersion.Any(char.IsControl)) throw Safe(ErrorCode.InvalidRequest, Message(ErrorCode.InvalidRequest));
        ArgumentNullException.ThrowIfNull(request.Source);
        _ = FileOperationWireIdentity(request.Source.WirePath, allowRoot: true);
        if (!StringComparer.Ordinal.Equals(request.Source.SourceVersion, request.SourceVersion))
            throw Safe(ErrorCode.SourceChanged, "下载播放源已变化，请重新打开。", 412);
    }

    private void RequireConditionalDownload(SessionState state)
    {
        RequireDownload(state);
        lock (_gate)
        {
            EnsureActive(state);
            if (!state.Session.Capabilities.ConditionalDownloadV1)
                throw Safe(ErrorCode.CapabilityMissing, Message(ErrorCode.CapabilityMissing));
        }
    }

    private RemoteFile ParseConditionalDownloadFile(JsonElement row, SessionState state, RemoteResourceRef requested)
    {
        var file = ParseFile(row, state);
        if (file.IsDirectory || FileOperationWireIdentity(file.Resource.WirePath, allowRoot: true) !=
            FileOperationWireIdentity(requested.WirePath, allowRoot: true)) throw InvalidResponse();
        string version = RequiredConditionalSourceVersion(row);
        return file with { Resource = file.Resource with { SourceVersion = version } };
    }

    private static string RequiredConditionalSourceVersion(JsonElement row)
    {
        if (!row.TryGetProperty("sourceVersion", out var value) || value.ValueKind != JsonValueKind.String ||
            value.GetString() is not { Length: > 0 } version || version.Length > 4096 || version.Any(char.IsControl))
            throw InvalidResponse();
        return version;
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

    private sealed class FreshDownloadLease(FilewayClientServices owner, ResourceLease inner, long length,
        string? sourceVersion = null) : IDownloadLease
    {
        private readonly object _disposalGate = new();
        private Task? _disposal;
        public RemoteResourceRef Resource => inner.Resource;
        public Uri Address => inner.Address;
        public long Length { get; } = length;
        public string? SourceVersion { get; } = sourceVersion;
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
                await owner.RetireDownloadOwnerAsync(inner.State,
                    new(ErrorCode.OutcomeUnknown, Message(ErrorCode.OutcomeUnknown))).ConfigureAwait(false);
        }
    }
}
