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

    public async Task<FileChecksumResult> CalculateConditionalDownloadChecksumAsync(ConditionalDownloadLeaseRequest request,
        CancellationToken cancellationToken)
    {
        ValidateConditionalDownloadRequest(request);
        var state = GetResourceState(request.Source);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        try
        {
            using var beforeBody = await DownloadMetadataDocumentAsync(state, request.Source, linked.Token).ConfigureAwait(false);
            var before = ParseConditionalChecksumMetadata(beforeBody.RootElement, request, state);
            RequireConditionalDownload(state);

            // The checksum endpoint itself is not version-conditioned server-side; the pinned
            // version is enforced by comparing every surrounding observation instead.
            var response = await SessionLongReadAsync(state,
                "/api/resources" + request.Source.WirePath + "?metadata=1&checksum=sha256",
                TimeSpan.FromMinutes(16), linked.Token, requireDownload: true).ConfigureAwait(false);
            RequireExactStatus(response, 200);
            using var hashBody = ParseBody(response);
            var hashed = ParseConditionalChecksumMetadata(hashBody.RootElement, request, state);
            RequireUnchangedConditionalSource(before, hashed);
            string digest = NormalizeChecksumHex(RequiredText(hashBody.RootElement.GetProperty("checksums").GetProperty("sha256")),
                64, fromServer: true);

            using var afterBody = await DownloadMetadataDocumentAsync(state, request.Source, linked.Token).ConfigureAwait(false);
            var after = ParseConditionalChecksumMetadata(afterBody.RootElement, request, state);
            RequireUnchangedConditionalSource(before, after);
            lock (_gate)
            {
                linked.Token.ThrowIfCancellationRequested();
                RequireConditionalDownload(state);
                // The digest is attributed to the pinned version only, so a resume consumer
                // can treat it as evidence about its own checkpoint, not a fresh observation.
                return new FileChecksumResult(request.Source with { SourceVersion = request.SourceVersion },
                    ChecksumAlgorithm.Sha256, digest, ChecksumComparison.NotRequested, after.File.Size, after.File.Modified);
            }
        }
        catch (OperationCanceledException) { throw SessionCanceled(state, cancellationToken); }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    private ChecksumMetadata ParseConditionalChecksumMetadata(JsonElement row, ConditionalDownloadLeaseRequest request,
        SessionState state)
    {
        try
        {
            var file = ParseConditionalDownloadFile(row, state, request.Source);
            if (file.Modified is null) throw InvalidResponse();
            string modified = RequiredText(row.GetProperty("modified"));
            if (file.Size != request.Length) throw Safe(ErrorCode.Conflict, "下载源长度已变化，请刷新后重试。", 409);
            // The pinned version already binds size and modification time, so a mismatch means
            // the digest belongs to a different source and must not extend the checkpoint.
            if (!StringComparer.Ordinal.Equals(file.Resource.SourceVersion, request.SourceVersion))
                throw Safe(ErrorCode.SourceChanged, "下载源已变化，续传已停止。", 412);
            return new ChecksumMetadata(file, modified);
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    private static void RequireUnchangedConditionalSource(ChecksumMetadata before, ChecksumMetadata after)
    {
        if (before.File.Size != after.File.Size || before.Modified != after.Modified ||
            !StringComparer.Ordinal.Equals(before.File.Resource.SourceVersion, after.File.Resource.SourceVersion))
            throw Safe(ErrorCode.Conflict, "文件在计算期间已变化，请刷新后重试。", 409);
    }

    private const int MaximumTreeEnumerationEntries = 1000, MaximumTreeEnumerationQueue = 5000;

    public async Task<Core.Contracts.DownloadTreeEnumeration> EnumerateDownloadTreeAsync(RemoteResourceRef directory,
        int maximumEntries, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(directory);
        ValidateConditionalDownloadSource(directory);
        if (maximumEntries is < 1 or > MaximumTreeEnumerationEntries)
            throw Safe(ErrorCode.InvalidRequest, Message(ErrorCode.InvalidRequest));
        var state = GetResourceState(directory);
        var files = new List<Core.Contracts.DownloadTreeEntry>();
        var failures = new List<string>();
        var used = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var pending = new Queue<(RemoteResourceRef Resource, string RelativePath)>();
        pending.Enqueue((directory, ""));
        bool truncated = false;
        while (pending.Count != 0 && !truncated)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var (parent, relative) = pending.Dequeue();
            string? cursor = null;
            try
            {
                do
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    var page = await ListAsync(parent, new DirectoryQuery(FileSortField.Name, Descending: false,
                        Cursor: cursor), cancellationToken).ConfigureAwait(false);
                    foreach (var item in page.Items)
                    {
                        cancellationToken.ThrowIfCancellationRequested();
                        string childRelative = relative.Length == 0 ? item.Resource.DisplayName : relative + "/" + item.Resource.DisplayName;
                        if (item.IsDirectory)
                        {
                            // An unbounded empty-directory tree must not grow the queue without limit.
                            if (files.Count >= maximumEntries || pending.Count >= MaximumTreeEnumerationQueue) { truncated = true; break; }
                            pending.Enqueue((item.Resource, childRelative));
                        }
                        else
                        {
                            if (files.Count >= maximumEntries) { truncated = true; break; }
                            files.Add(new(item.Resource, childRelative, MapTreeRelativePath(childRelative, used)));
                        }
                    }
                    cursor = page.NextCursor;
                } while (cursor is not null && !truncated);
            }
            catch (OperationCanceledException) { throw SessionCanceled(state, cancellationToken); }
            // Session-wide failures fail the whole enumeration; only per-directory errors are
            // collected so sibling subtrees can still be previewed.
            catch (FilewayException error) when (error.Error.Code is ErrorCode.AuthenticationExpired or
                ErrorCode.CredentialsUnavailable or ErrorCode.PermissionDenied or ErrorCode.Canceled)
            { throw; }
            catch (FilewayException) { failures.Add(relative.Length == 0 ? "/" : relative); }
            catch (Exception error) when (error is IOException or InvalidOperationException or JsonException or FormatException or KeyNotFoundException)
            { failures.Add(relative.Length == 0 ? "/" : relative); }
        }
        return new(files, failures, truncated);
    }

    /// <summary>Maps one remote path segment to a Windows-safe leaf, mirroring TransferTaskValidation.LocalFilePath.</summary>
    internal static string MapWindowsLeafName(string name)
    {
        if (string.IsNullOrEmpty(name)) return "_";
        System.Text.StringBuilder? mapped = null;
        for (int index = 0; index < name.Length; index++)
        {
            char value = name[index];
            bool illegal = value is '<' or '>' or ':' or '"' or '|' or '?' or '*' or '/' or '\\' || char.IsControl(value);
            if (illegal && mapped is null) mapped = new(name, 0, index, name.Length);
            if (illegal) mapped!.Append('_');
            else mapped?.Append(value);
        }
        string leaf = mapped?.ToString() ?? name;
        if (leaf.Length == 0) leaf = "_";
        leaf = leaf.TrimEnd(' ', '.');
        if (leaf.Length == 0) leaf = "_";
        if (ReservedWindowsLeafName(leaf.Split('.')[0])) leaf += "_";
        if (leaf.Length > 255) leaf = leaf[..255].TrimEnd(' ', '.');
        return leaf.Length == 0 ? "_" : leaf;
    }

    private static bool ReservedWindowsLeafName(string stem) =>
        stem.Equals("CON", StringComparison.OrdinalIgnoreCase) || stem.Equals("PRN", StringComparison.OrdinalIgnoreCase) ||
        stem.Equals("AUX", StringComparison.OrdinalIgnoreCase) || stem.Equals("NUL", StringComparison.OrdinalIgnoreCase) ||
        stem.Equals("CONIN$", StringComparison.OrdinalIgnoreCase) || stem.Equals("CONOUT$", StringComparison.OrdinalIgnoreCase) ||
        stem.Length == 4 && (stem.StartsWith("COM", StringComparison.OrdinalIgnoreCase) || stem.StartsWith("LPT", StringComparison.OrdinalIgnoreCase)) &&
            stem[3] is >= '1' and <= '9' or '¹' or '²' or '³';

    private static string MapTreeRelativePath(string relative, HashSet<string> used)
    {
        string[] segments = relative.Split('/');
        var mapped = new string[segments.Length];
        for (int index = 0; index < segments.Length; index++) mapped[index] = MapWindowsLeafName(segments[index]);
        string joined = string.Join('\\', mapped);
        string candidate = joined;
        int suffix = 2;
        // Two distinct remote names may sanitize to the same Windows path; keep every target unique
        // instead of silently merging two different files into one destination.
        while (!used.Add(candidate))
        {
            if (suffix > 999) throw Safe(ErrorCode.InvalidRequest, "目录中可映射的唯一名字已用尽。");
            candidate = joined + " (" + suffix++ + ")";
        }
        return candidate;
    }

    public async Task<IDownloadLease> AcquireArchiveLeaseAsync(ArchiveDownloadLeaseRequest request,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(request);
        ValidateConditionalDownloadSource(request.Directory);
        if (request.ItemWirePaths is null || request.ItemWirePaths.Count is 0 or > 1000 ||
            request.ItemWirePaths.Any(wire => string.IsNullOrEmpty(wire)))
            throw Safe(ErrorCode.InvalidRequest, Message(ErrorCode.InvalidRequest));
        foreach (var wire in request.ItemWirePaths) _ = FileOperationWireIdentity(wire, allowRoot: false);
        var state = GetResourceState(request.Directory);
        RequireDownload(state);
        FreshDownloadLease? captured = null;
        bool attempted = false;
        try
        {
            _ = await SessionRequestAsync(state, new
            {
                wirePath = request.Directory.WirePath,
                archiveFiles = request.ItemWirePaths,
                archiveFormat = "zip",
            }, requireDownload: true, HostCallSafety.Control, cancellationToken, operation: "lease",
            beforeSend: async token =>
            {
                // Fail fast when the owning directory moved; entry-level permission and
                // existence are enforced by the server while it streams the archive.
                var observed = await GetAsync(request.Directory, token).ConfigureAwait(false);
                if (!observed.IsDirectory || observed.Resource.WirePath != request.Directory.WirePath)
                    throw Safe(ErrorCode.SourceChanged, "归档来源已变化，请刷新后重试。", 412);
            },
            authorizeSend: () => { RequireDownload(state); attempted = true; },
            confirmResponse: response =>
            {
                // Length is unknown for a synthetic archive; the streaming consumer must not
                // treat it as a pinned observation or a resumable checkpoint.
                var inner = new ResourceLease(this, state, request.Directory, UploadAddress(response));
                captured = new FreshDownloadLease(this, inner, 0);
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
                await RetireDownloadOwnerAsync(state, error.Error).ConfigureAwait(false);
            throw;
        }
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
