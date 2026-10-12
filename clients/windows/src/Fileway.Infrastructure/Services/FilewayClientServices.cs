using System.Diagnostics;
using System.Globalization;
using System.Runtime.CompilerServices;
using System.Text;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Credentials;
using Fileway.Infrastructure.Persistence;

namespace Fileway.Infrastructure.Services;

/// <summary>Typed, account-bound application services over the supervised Go Host.</summary>
public sealed partial class FilewayClientServices : IConnectionService, IConnectionCatalog, IFileRepository, IImageContentService, IResourceCollectionsService, IBrowserPreferencesService,
    IServerSearchService, IFileManagementService, IServerTaskService, ITrashService, IFileChecksumService, IOperationHistoryService,
    IResourceLeaseProvider, IPlaybackProgressStore, IRecentAccessService, IAsyncDisposable
{
    private const long MaximumImageBytes = 64L * 1024 * 1024;
    private static readonly UTF8Encoding StrictUtf8 = new(false, true);
    private readonly IHostControlClient _host;
    private readonly IProfileStore _profiles;
    private readonly ICredentialVault _credentials;
    private readonly object _gate = new();
    private readonly SemaphoreSlim _connections = new(1, 1);
    private readonly SemaphoreSlim _persistence = new(1, 1);
    private readonly Dictionary<AccountKey, SessionState> _sessions = [];
    private readonly ConditionalWeakTable<RemoteResourceRef, SessionState> _playbackOwners = new();
    private readonly HttpClient _images = new(new SocketsHttpHandler { UseProxy = false, AllowAutoRedirect = false });
    private bool _disposed;

    private sealed class SessionState
    {
        internal SessionState(string coreSession, VerifiedConnection verified, SavedConnection saved)
        {
            CoreSession = coreSession;
            Session = verified.Session;
            VerifiedToken = verified.Token;
            Saved = saved;
            LifetimeToken = Lifetime.Token;
            Preferences = verified.Preferences;
        }
        internal string CoreSession { get; }
        internal ConnectionSession Session { get; set; }
        internal SavedConnection Saved { get; set; }
        internal string VerifiedToken { get; set; }
        internal CancellationTokenSource Lifetime { get; } = new();
        internal CancellationToken LifetimeToken { get; }
        internal HashSet<ResourceLease> Leases { get; } = [];
        internal SemaphoreSlim Operations { get; } = new(1, 1);
        internal SemaphoreSlim Refresh { get; } = new(1, 1);
        internal TaskCompletionSource<ErrorInfo?> Retirement { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal ResumePreferences Preferences { get; set; }
        internal bool Active { get; set; } = true;
        internal int CacheWarningPublished;
    }

    private readonly record struct ResumePreferences(PlaybackResumeMode Mode, TimeSpan MinimumPosition);

    private sealed class VerifiedConnection(ConnectionSession session, ResumePreferences preferences, string token)
    {
        internal ConnectionSession Session { get; } = session;
        internal ResumePreferences Preferences { get; } = preferences;
        internal string Token { get; } = token;
        public override string ToString() => "VerifiedConnection (credentials redacted)";
    }

    public event EventHandler<ConnectionSession>? SessionUpdated;
    public event EventHandler<ConnectionIssue>? ConnectionIssue;

    public FilewayClientServices(IHostControlClient host) : this(host, new InMemoryProfileStore(), new InMemoryCredentialVault()) { }

    public FilewayClientServices(IHostControlClient host, IProfileStore profiles, ICredentialVault credentials,
        LocalStorageSettingsService? storageSettings = null)
    {
        _host = host ?? throw new ArgumentNullException(nameof(host));
        _profiles = profiles ?? throw new ArgumentNullException(nameof(profiles));
        _credentials = credentials ?? throw new ArgumentNullException(nameof(credentials));
        _storageSettings = storageSettings;
    }

    public async Task<ConnectionSession> ConnectAsync(ConnectionProfile profile, LoginInput login, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(profile);
        ArgumentNullException.ThrowIfNull(login);
        await _connections.WaitAsync(cancellationToken).ConfigureAwait(false);
        string? coreSession = null;
        try
        {
            lock (_gate) ObjectDisposedException.ThrowIf(_disposed, this);
            await CompletePendingLogoutsAsync(cancellationToken).ConfigureAwait(false);
            ValidateProfile(profile, login);
            var previous = await StorageAsync(() => _profiles.GetAsync(profile.Id, cancellationToken)).ConfigureAwait(false);
            if (previous is not null && previous.Profile.SourceRevision == profile.SourceRevision &&
                (!StringComparer.Ordinal.Equals(previous.Profile.BaseAddress.AbsoluteUri, profile.BaseAddress.AbsoluteUri) ||
                    previous.Profile.Network != profile.Network || previous.Profile.Proxy != profile.Proxy))
                throw Safe(ErrorCode.SourceChanged, "服务器地址或安全路由已变化，请使用新的连接来源。");
            await RetireAllAsync().ConfigureAwait(false);
            var opened = await CallAsync("open", new { baseUrl = profile.BaseAddress.AbsoluteUri, network = "direct", token = "" }, null, cancellationToken).ConfigureAwait(false);
            coreSession = RequiredText(opened);
            var authenticated = await CallAsync("login", new
            {
                username = login.Mode == AuthenticationMode.Password ? login.Username : "",
                password = login.Mode == AuthenticationMode.Password ? login.Password : "",
            }, coreSession, cancellationToken).ConfigureAwait(false);
            string token = HttpBody(authenticated).Trim();
            string userId = ReadTokenUserId(token);
            var verified = await VerifyConnectionAsync(coreSession, profile, login.Mode, userId, Guid.NewGuid(), token,
                cancellationToken).ConfigureAwait(false);
            var saved = new SavedConnection(profile, login.Mode, verified.Session.DisplayName, userId,
                login.KeepSignedIn, DateTimeOffset.UtcNow);
            await _persistence.WaitAsync(cancellationToken).ConfigureAwait(false);
            try { await SaveConnectionAsync(saved, verified.Token, cancellationToken).ConfigureAwait(false); }
            finally { _persistence.Release(); }
            cancellationToken.ThrowIfCancellationRequested();
            lock (_gate)
            {
                ObjectDisposedException.ThrowIf(_disposed, this);
                _sessions.Add(saved.Account, new SessionState(coreSession, verified, saved));
            }
            coreSession = null;
            return verified.Session;
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        {
            throw InvalidResponse();
        }
        finally
        {
            try { if (coreSession is not null) await CloseCoreAsync(coreSession).ConfigureAwait(false); }
            finally { _connections.Release(); }
        }
    }

    public Task DisconnectAsync(AccountKey account, CancellationToken cancellationToken) => RetireAccountAsync(account, cancellationToken);

    public async Task<ConnectionSession> RestoreAsync(Guid profileId, CancellationToken cancellationToken)
    {
        await _connections.WaitAsync(cancellationToken).ConfigureAwait(false);
        string? coreSession = null;
        try
        {
            lock (_gate) ObjectDisposedException.ThrowIf(_disposed, this);
            await CompletePendingLogoutsAsync(cancellationToken).ConfigureAwait(false);
            var saved = await StorageAsync(() => _profiles.GetAsync(profileId, cancellationToken)).ConfigureAwait(false)
                ?? throw Safe(ErrorCode.AuthenticationExpired, Message(ErrorCode.AuthenticationExpired));
            var pending = await StorageAsync(() => _profiles.ListPendingLogoutsAsync(cancellationToken)).ConfigureAwait(false);
            if (pending.Contains(saved.Account)) throw Safe(ErrorCode.AuthenticationExpired, Message(ErrorCode.AuthenticationExpired));
            ValidateProfile(saved.Profile, new LoginInput(saved.AuthenticationMode, saved.Username), restoring: true);
            string token = "";
            if (saved.AuthenticationMode == AuthenticationMode.Password)
            {
                if (!saved.KeepSignedIn) throw Safe(ErrorCode.AuthenticationExpired, Message(ErrorCode.AuthenticationExpired));
                token = await CredentialsAsync(() => _credentials.ReadTokenAsync(saved.Account, cancellationToken)).ConfigureAwait(false) ?? "";
                if (string.IsNullOrWhiteSpace(token)) throw Safe(ErrorCode.AuthenticationExpired, Message(ErrorCode.AuthenticationExpired));
                RequireTokenAccount(token, saved.ServerUserId);
            }
            await RetireAllAsync().ConfigureAwait(false);
            coreSession = RequiredText(await CallAsync("open", new
            {
                baseUrl = saved.Profile.BaseAddress.AbsoluteUri, network = "direct", token,
            }, null, cancellationToken).ConfigureAwait(false));
            if (saved.AuthenticationMode == AuthenticationMode.NoAuthentication)
                token = HttpBody(await CallAsync("login", new { username = "", password = "" }, coreSession,
                    cancellationToken).ConfigureAwait(false)).Trim();
            var verified = await VerifyConnectionAsync(coreSession, saved.Profile, saved.AuthenticationMode,
                saved.ServerUserId, Guid.NewGuid(), token, cancellationToken).ConfigureAwait(false);
            saved = saved with { Username = verified.Session.DisplayName, LastConnectedAt = DateTimeOffset.UtcNow };
            await _persistence.WaitAsync(cancellationToken).ConfigureAwait(false);
            try { await SaveConnectionAsync(saved, verified.Token, cancellationToken).ConfigureAwait(false); }
            finally { _persistence.Release(); }
            cancellationToken.ThrowIfCancellationRequested();
            lock (_gate)
            {
                ObjectDisposedException.ThrowIf(_disposed, this);
                _sessions.Add(saved.Account, new SessionState(coreSession, verified, saved));
            }
            coreSession = null;
            return verified.Session;
        }
        finally
        {
            try { if (coreSession is not null) await CloseCoreAsync(coreSession).ConfigureAwait(false); }
            finally { _connections.Release(); }
        }
    }

    public async Task<IReadOnlyList<SavedConnection>> ListAsync(CancellationToken cancellationToken)
    {
        await _connections.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            lock (_gate) ObjectDisposedException.ThrowIf(_disposed, this);
            await CompletePendingLogoutsAsync(cancellationToken).ConfigureAwait(false);
            return await StorageAsync(() => _profiles.ListAsync(cancellationToken)).ConfigureAwait(false);
        }
        finally { _connections.Release(); }
    }

    public async Task<SavedConnection?> GetAsync(Guid profileId, CancellationToken cancellationToken)
    {
        await _connections.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            lock (_gate) ObjectDisposedException.ThrowIf(_disposed, this);
            await CompletePendingLogoutsAsync(cancellationToken).ConfigureAwait(false);
            return await StorageAsync(() => _profiles.GetAsync(profileId, cancellationToken)).ConfigureAwait(false);
        }
        finally { _connections.Release(); }
    }

    public async Task LogoutAsync(AccountKey account, CancellationToken cancellationToken)
    {
        await _connections.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            lock (_gate) ObjectDisposedException.ThrowIf(_disposed, this);
            await _persistence.WaitAsync(cancellationToken).ConfigureAwait(false);
            try { await LogoutCoreAsync(account, cancellationToken).ConfigureAwait(false); }
            finally { _persistence.Release(); }
        }
        finally { _connections.Release(); }
    }

    public async Task ForgetAsync(Guid profileId, CancellationToken cancellationToken)
    {
        await _connections.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            lock (_gate) ObjectDisposedException.ThrowIf(_disposed, this);
            await CompletePendingLogoutsAsync(cancellationToken).ConfigureAwait(false);
            await _persistence.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                var saved = await StorageAsync(() => _profiles.GetAsync(profileId, cancellationToken)).ConfigureAwait(false);
                if (saved is null) return;
                await EnsureOperationHistoryResolvedAsync(saved.Account, cancellationToken).ConfigureAwait(false);
                await LogoutCoreAsync(saved.Account, cancellationToken).ConfigureAwait(false);
                await StorageAsync(() => _profiles.ForgetAsync(saved.Account, cancellationToken)).ConfigureAwait(false);
            }
            finally { _persistence.Release(); }
        }
        finally { _connections.Release(); }
    }

    public async Task<RemoteResourceRef> GetRootAsync(ConnectionSession session, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(session);
        cancellationToken.ThrowIfCancellationRequested();
        var state = GetState(session.Account);
        if (state.Session.Id != session.Id) throw Safe(ErrorCode.SourceChanged, "此账户会话已更换，请重新打开目录。");
        await RefreshSessionAsync(state, cancellationToken).ConfigureAwait(false);
        return BindResource(new RemoteResourceRef(session.Account, "/", "根目录", "/"), state);
    }

    public async Task<DirectoryPage> ListAsync(RemoteResourceRef directory, DirectoryQuery query, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(directory);
        ArgumentNullException.ThrowIfNull(query);
        if (query.Cursor is not null) throw Safe(ErrorCode.CapabilityMissing, "首预览尚不支持分页目录。");
        var state = GetResourceState(directory);
        using var document = await ResourceDocumentAsync(state, directory, false, cancellationToken).ConfigureAwait(false);
        try
        {
            var root = document.RootElement;
            var parsedDirectory = ParseFile(root, state);
            if (!parsedDirectory.IsDirectory || FileOperationWireIdentity(parsedDirectory.Resource.WirePath, allowRoot: true)
                != FileOperationWireIdentity(directory.WirePath, allowRoot: true)) throw InvalidResponse();
            var items = root.GetProperty("items").EnumerateArray().Select(item => ParseFile(item, state)).ToArray();
            Array.Sort(items, (left, right) => CompareFiles(left, right, query));
            EnsureActive(state);
            return new DirectoryPage(parsedDirectory.Resource, items, null, null);
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    public async Task<RemoteFile> GetAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(resource);
        var state = GetResourceState(resource);
        using var document = await ResourceDocumentAsync(state, resource, true, cancellationToken).ConfigureAwait(false);
        try
        {
            var file = ParseFile(document.RootElement, state);
            if (FileOperationWireIdentity(file.Resource.WirePath, allowRoot: true)
                != FileOperationWireIdentity(resource.WirePath, allowRoot: true)) throw InvalidResponse();
            EnsureActive(state);
            return file;
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    public async Task<PlaybackBookmark?> ReadAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(resource);
        ValidateWirePath(resource.WirePath);
        var state = await GetPlaybackStateAsync(resource, cancellationToken).ConfigureAwait(false);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        try
        {
            // Web refreshes account policy on every open and falls back to its
            // last verified account preferences when that read is unavailable.
            try
            {
                string preferenceToken = state.VerifiedToken;
                using var userBody = ParseBody(await RequestAsync(state,
                    "/api/users/" + state.Session.Account.ServerUserId, linked.Token, requireDownload: true).ConfigureAwait(false));
                if (ReadUserId(userBody.RootElement) != state.Session.Account.ServerUserId) throw InvalidResponse();
                var preferences = ParseResumePreferences(userBody.RootElement);
                lock (_gate)
                {
                    EnsureActive(state);
                    if (StringComparer.Ordinal.Equals(preferenceToken, state.VerifiedToken)) state.Preferences = preferences;
                }
            }
            catch (FilewayException error) when (!linked.IsCancellationRequested)
            {
                Trace.TraceWarning("Fileway playback preference refresh failed ({0}); using verified account policy.", error.Error.Code);
            }

            using var document = ParseBody(await RequestAsync(state,
                PlaybackEndpoint(resource), linked.Token, requireDownload: true).ConfigureAwait(false));
            var value = document.RootElement;
            bool exists = value.GetProperty("exists").GetBoolean();
            string identity = RequiredText(value.GetProperty("identity"));
            TimeSpan position = MediaTime(value.GetProperty("position"));
            TimeSpan duration = MediaTime(value.GetProperty("duration"));
            lock (_gate)
            {
                EnsureActive(state);
                return exists ? new PlaybackBookmark(position, duration > TimeSpan.Zero ? duration : null,
                    identity, state.Preferences.Mode, state.Preferences.MinimumPosition) : null;
            }
        }
        catch (FilewayException error) when (error.Error.Code == ErrorCode.Canceled && state.LifetimeToken.IsCancellationRequested)
        { throw Safe(ErrorCode.SourceChanged, Message(ErrorCode.SourceChanged)); }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException or OverflowException)
        { throw InvalidResponse(); }
    }

    public async Task WriteAsync(RemoteResourceRef resource, TimeSpan confirmedPosition, TimeSpan? duration, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(resource);
        if (confirmedPosition < TimeSpan.Zero || duration < TimeSpan.Zero)
            throw Safe(ErrorCode.InvalidRequest, "播放位置和时长不能为负数。");
        var state = GetResourceState(resource);
        string path = PlaybackJsonPath(resource);
        _ = await SessionRequestAsync(state, new
        {
            method = "PUT", endpoint = "/api/media/playback",
            // The bridge forwards body as json.RawMessage. Keep its single JSON
            // encoding here; confirmed mutations are never replayed.
            body = new { path, position = confirmedPosition.TotalSeconds, duration = duration?.TotalSeconds ?? 0 },
        }, requireDownload: true, HostCallSafety.Mutation, cancellationToken).ConfigureAwait(false);
    }

    public async Task ClearAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(resource);
        var state = GetResourceState(resource);
        string endpoint = PlaybackEndpoint(resource);
        _ = await SessionRequestAsync(state, new { method = "DELETE", endpoint },
            requireDownload: true, HostCallSafety.Mutation, cancellationToken).ConfigureAwait(false);
    }

    public async Task<IResourceLease> AcquireAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(resource);
        ValidateWirePath(resource.WirePath);
        var state = GetResourceState(resource);
        return await AcquireLeaseAsync(state, resource, new { wirePath = resource.WirePath }, cancellationToken).ConfigureAwait(false);
    }

    public async Task<IImageContent> OpenAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
    {
        var lease = (ResourceLease)await AcquireCachedAsync(resource, cancellationToken).ConfigureAwait(false);
        return await ReadImageContentAsync(resource, lease, MaximumImageBytes, thumbnail: false, cancellationToken).ConfigureAwait(false);
    }

    private async Task<IImageContent> ReadImageContentAsync(RemoteResourceRef resource, ResourceLease lease,
        long maximumBytes, bool thumbnail, CancellationToken cancellationToken)
    {
        HttpResponseMessage? response = null;
        MemoryStream? content = null;
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, lease.State.LifetimeToken);
        try
        {
            response = await _images.GetAsync(lease.Address, HttpCompletionOption.ResponseHeadersRead, linked.Token).ConfigureAwait(false);
            CheckStatus((int)response.StatusCode);
            string? mediaType = response.Content.Headers.ContentType?.MediaType;
            if (thumbnail && !StringComparer.OrdinalIgnoreCase.Equals(mediaType, "image/jpeg") &&
                !StringComparer.OrdinalIgnoreCase.Equals(mediaType, "image/png"))
                throw Safe(ErrorCode.CapabilityMissing, "服务器未提供 JPEG 或 PNG 缩略图。");
            if (response.Content.Headers.ContentLength > maximumBytes) throw ImageTooLarge(thumbnail);
            content = new MemoryStream();
            await using var input = await response.Content.ReadAsStreamAsync(linked.Token).ConfigureAwait(false);
            byte[] buffer = new byte[81920];
            while (true)
            {
                int count = await input.ReadAsync(buffer, linked.Token).ConfigureAwait(false);
                if (count == 0) break;
                if (content.Length + count > maximumBytes) throw ImageTooLarge(thumbnail);
                await content.WriteAsync(buffer.AsMemory(0, count), linked.Token).ConfigureAwait(false);
            }
            content.Position = 0;
            await RefreshSessionAsync(lease.State, linked.Token).ConfigureAwait(false);
            RequireDownload(lease.State);
            var image = new ImageContent(resource, content, response, lease);
            lease.Attach(image);
            EnsureActive(lease.State);
            return image;
        }
        catch (FilewayException) { content?.Dispose(); response?.Dispose(); await lease.DisposeAsync().ConfigureAwait(false); throw; }
        catch (OperationCanceledException)
        {
            content?.Dispose(); response?.Dispose(); await lease.DisposeAsync().ConfigureAwait(false);
            var code = cancellationToken.IsCancellationRequested ? ErrorCode.Canceled :
                lease.State.LifetimeToken.IsCancellationRequested ? ErrorCode.SourceChanged : ErrorCode.Timeout;
            throw Safe(code, Message(code));
        }
        catch (Exception error) when (error is HttpRequestException or IOException or ObjectDisposedException)
        { content?.Dispose(); response?.Dispose(); await lease.DisposeAsync().ConfigureAwait(false); throw Safe(ErrorCode.NetworkUnavailable, "无法读取图片内容，请检查连接后重试。"); }
    }

    public async ValueTask DisposeAsync()
    {
        await _connections.WaitAsync().ConfigureAwait(false);
        try
        {
            lock (_gate) { if (_disposed) return; _disposed = true; }
            await RetireAllAsync().ConfigureAwait(false);
            _images.Dispose();
        }
        finally { _connections.Release(); }
    }

    private async Task<VerifiedConnection> VerifyConnectionAsync(string coreSession, ConnectionProfile profile,
        AuthenticationMode mode, string userId, Guid sessionId, string token, CancellationToken cancellationToken)
    {
        try
        {
            // Verification reads can themselves renew a token. Accept metadata
            // only when the token is stable across the entire verification.
            for (int attempt = 0; attempt < 3; attempt++)
            {
                RequireTokenAccount(token, userId);
                using var userBody = ParseBody(await RequestAsync(coreSession, "/api/users/" + userId,
                    cancellationToken).ConfigureAwait(false));
                var user = userBody.RootElement;
                if (ReadUserId(user) != userId) throw Safe(ErrorCode.AuthenticationExpired, Message(ErrorCode.AuthenticationExpired));
                using var capabilityBody = ParseBody(await RequestAsync(coreSession, "/api/client-capabilities",
                    cancellationToken).ConfigureAwait(false));
                var capabilities = capabilityBody.RootElement;
                if (RequiredText(capabilities.GetProperty("authMethod")) !=
                    (mode == AuthenticationMode.NoAuthentication ? "noauth" : "json"))
                    throw Safe(ErrorCode.CapabilityMissing, "服务器认证方式与保存的登录方式不一致，请确认后重新连接。");
                string currentToken = await ReadCoreTokenAsync(coreSession, cancellationToken).ConfigureAwait(false);
                RequireTokenAccount(currentToken, userId);
                if (!StringComparer.Ordinal.Equals(token, currentToken))
                {
                    token = currentToken;
                    continue;
                }
                var permissions = user.GetProperty("perm");
                var session = new ConnectionSession(sessionId, new AccountKey(profile.Id, profile.SourceRevision, userId),
                    OptionalText(user, "username") ?? profile.DisplayName,
                    new PermissionSet(Flag(permissions, "download"), Flag(permissions, "create"), Flag(permissions, "rename"),
                        Flag(permissions, "modify"), Flag(permissions, "delete"), Flag(permissions, "execute")),
                    new ServerCapabilities(Flag(capabilities, "resourcePaginationV1"), Flag(capabilities, "conditionalDownloadV1"),
                        Flag(capabilities, "searchWirePathV1"), Flag(capabilities, "resourceWireOperations")));
                return new VerifiedConnection(session, ParseResumePreferences(user), currentToken);
            }
            throw Safe(ErrorCode.AuthenticationExpired, "登录状态在核验期间持续变化，请重新连接。");
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    private async Task<string> ReadCoreTokenAsync(string coreSession, CancellationToken cancellationToken) =>
        RequiredText(await CallAsync("token", new { }, coreSession, cancellationToken).ConfigureAwait(false)).Trim();

    private static void RequireTokenAccount(string token, string userId)
    {
        if (ReadTokenUserId(token) != userId)
            throw Safe(ErrorCode.AuthenticationExpired, "登录状态与所选账户不一致，请重新登录。");
    }

    // Callers hold _persistence. Every token write shares this gate with the
    // durable logout intent, so an in-flight refresh cannot resurrect a logout.
    private async Task SaveConnectionAsync(SavedConnection saved, string token, CancellationToken cancellationToken)
    {
        var pending = await StorageAsync(() => _profiles.ListPendingLogoutsAsync(cancellationToken)).ConfigureAwait(false);
        if (pending.Contains(saved.Account))
            throw Safe(ErrorCode.LocalStorageUnavailable, "此账户仍有待清理的本地缓存，请完成注销清理后重新登录。");
        var previous = await StorageAsync(() => _profiles.GetAsync(saved.Profile.Id, cancellationToken)).ConfigureAwait(false);
        if (previous is not null && previous.Account != saved.Account)
        {
            await EnsureOperationHistoryResolvedAsync(previous.Account, cancellationToken).ConfigureAwait(false);
            await StorageAsync(() => _profiles.BeginLogoutAsync(previous.Account, cancellationToken)).ConfigureAwait(false);
            // A disconnected old cache root belongs to the old full AccountKey.
            // Once its session and vault token are retired, keep its cleanup intent
            // without blocking a newly verified principal using this profile slot.
            if (!await CompleteLogoutCleanupAsync(previous.Account, cancellationToken).ConfigureAwait(false))
                Trace.TraceWarning("Fileway retained previous account cache cleanup while replacing a profile account.");
        }
        if (saved.KeepSignedIn)
            await CredentialsAsync(() => _credentials.WriteTokenAsync(saved.Account, token, cancellationToken)).ConfigureAwait(false);
        else
            await CredentialsAsync(() => _credentials.DeleteTokenAsync(saved.Account, cancellationToken)).ConfigureAwait(false);
        await StorageAsync(() => _profiles.SaveAsync(saved, cancellationToken)).ConfigureAwait(false);
    }

    private async Task CompletePendingLogoutsAsync(CancellationToken cancellationToken)
    {
        await _persistence.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var pending = await StorageAsync(() => _profiles.ListPendingLogoutsAsync(cancellationToken)).ConfigureAwait(false);
            using var recoveryBudget = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            recoveryBudget.CancelAfter(TimeSpan.FromSeconds(2));
            foreach (var account in pending)
            {
                if (recoveryBudget.IsCancellationRequested) { cancellationToken.ThrowIfCancellationRequested(); break; }
                try
                {
                    if (await CompleteLogoutCleanupAsync(account, cancellationToken, recoveryBudget.Token).ConfigureAwait(false)) continue;
                }
                catch (FilewayException error) when (error.Error.Code == ErrorCode.Canceled && !cancellationToken.IsCancellationRequested) { break; }
                catch (FilewayException error) when (error.Error.Code != ErrorCode.Canceled)
                {
                    // Durable intent prevents this account from restoring; other accounts can still proceed.
                }
                Trace.TraceWarning("Fileway retained pending account cleanup; that account cannot restore credentials.");
            }
        }
        finally { _persistence.Release(); }
    }

    private async Task LogoutCoreAsync(AccountKey account, CancellationToken cancellationToken)
    {
        await StorageAsync(() => _profiles.BeginLogoutAsync(account, cancellationToken)).ConfigureAwait(false);
        // Intent has committed. Caller cancellation must not leave this process
        // using a logged-out token; deletion may still leave durable pending work.
        if (!await CompleteLogoutCleanupAsync(account, cancellationToken).ConfigureAwait(false))
            throw Safe(ErrorCode.LocalStorageUnavailable, "此账户的登录凭据已删除，本地缓存尚未清理完成，请稍后重试。");
    }

    private async Task RefreshSessionAsync(SessionState state, CancellationToken cancellationToken)
    {
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        ConnectionSession? updated = null;
        FilewayException? failure = null;
        bool entered = false;
        try
        {
            await state.Operations.WaitAsync(linked.Token).ConfigureAwait(false);
            entered = true;
            updated = await RefreshSessionCoreAsync(state, linked.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) { failure = SessionCanceled(state, cancellationToken); }
        catch (FilewayException error) { failure = error; }
        finally { if (entered) state.Operations.Release(); }
        if (failure is not null)
        {
            if (failure.Error.Code != ErrorCode.Canceled && await RetireAsync(state).ConfigureAwait(false))
                PublishConnectionIssue(state, failure.Error);
            throw failure;
        }
        PublishSessionUpdated(state, updated);
    }

    private async Task<ConnectionSession?> RefreshSessionCoreAsync(SessionState state, CancellationToken cancellationToken)
    {
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        bool entered = false;
        try
        {
            await state.Refresh.WaitAsync(linked.Token).ConfigureAwait(false);
            entered = true;
            EnsureActive(state);
            string token = await ReadCoreTokenAsync(state.CoreSession, linked.Token).ConfigureAwait(false);
            if (StringComparer.Ordinal.Equals(token, state.VerifiedToken)) return null;
            var verified = await VerifyConnectionAsync(state.CoreSession, state.Saved.Profile, state.Saved.AuthenticationMode,
                state.Session.Account.ServerUserId, state.Session.Id, token, linked.Token).ConfigureAwait(false);
            await _persistence.WaitAsync(linked.Token).ConfigureAwait(false);
            try
            {
                EnsureActive(state);
                var current = await StorageAsync(() => _profiles.GetAsync(state.Session.Account.ProfileId, linked.Token)).ConfigureAwait(false);
                if (current is null || current.Account != state.Session.Account)
                    throw Safe(ErrorCode.SourceChanged, Message(ErrorCode.SourceChanged));
                var saved = current with { Username = verified.Session.DisplayName };
                EnsureActive(state);
                await SaveConnectionAsync(saved, verified.Token, linked.Token).ConfigureAwait(false);
                lock (_gate)
                {
                    EnsureActive(state);
                    state.Saved = saved;
                    state.VerifiedToken = verified.Token;
                    state.Preferences = verified.Preferences;
                    state.Session = verified.Session;
                }
                return verified.Session;
            }
            finally { _persistence.Release(); }
        }
        catch (OperationCanceledException)
        {
            var code = state.LifetimeToken.IsCancellationRequested ? ErrorCode.SourceChanged : ErrorCode.Canceled;
            throw Safe(code, Message(code));
        }
        finally { if (entered) state.Refresh.Release(); }
    }

    private static FilewayException SessionCanceled(SessionState state, CancellationToken cancellationToken)
    {
        var code = state.LifetimeToken.IsCancellationRequested ? ErrorCode.SourceChanged :
            cancellationToken.IsCancellationRequested ? ErrorCode.Canceled : ErrorCode.Timeout;
        return Safe(code, Message(code));
    }

    private void PublishSessionUpdated(SessionState state, ConnectionSession? updated)
    {
        if (updated is null) return;
        lock (_gate) if (!state.Active || _disposed || !ReferenceEquals(state.Session, updated)) return;
        PublishEvent(SessionUpdated, updated);
    }

    private void PublishConnectionIssue(SessionState state, ErrorInfo error) =>
        PublishEvent(ConnectionIssue, new ConnectionIssue(state.Session.Account, state.Session.Id,
            new ErrorInfo(error.Code, Message(error.Code), error.HttpStatus, error.Retryable), true));

    private void PublishEvent<T>(EventHandler<T>? handlers, T value)
    {
        if (handlers is null) return;
        foreach (EventHandler<T> handler in handlers.GetInvocationList())
        {
            try { handler(this, value); }
            catch (Exception) { Trace.TraceWarning("Fileway connection event handler failed ({0}).", ErrorCode.Unknown); }
        }
    }

    private static Task<T> StorageAsync<T>(Func<Task<T>> operation) => LocalAsync(operation, ErrorCode.LocalStorageUnavailable);
    private static Task<T> CredentialsAsync<T>(Func<Task<T>> operation) => LocalAsync(operation, ErrorCode.CredentialsUnavailable);
    private static Task StorageAsync(Func<Task> operation) => LocalAsync(operation, ErrorCode.LocalStorageUnavailable);
    private static Task CredentialsAsync(Func<Task> operation) => LocalAsync(operation, ErrorCode.CredentialsUnavailable);

    private static async Task LocalAsync(Func<Task> operation, ErrorCode code)
    {
        await LocalAsync(async () => { await operation().ConfigureAwait(false); return true; }, code).ConfigureAwait(false);
    }

    private static async Task<T> LocalAsync<T>(Func<Task<T>> operation, ErrorCode code)
    {
        try { return await operation().ConfigureAwait(false); }
        catch (OperationCanceledException) { throw Safe(ErrorCode.Canceled, Message(ErrorCode.Canceled)); }
        catch (FilewayException error) when (error.Error.Code == ErrorCode.Canceled)
        { throw Safe(ErrorCode.Canceled, Message(ErrorCode.Canceled)); }
        catch (Exception) { throw Safe(code, Message(code)); }
    }

    private static void ValidateProfile(ConnectionProfile profile, LoginInput login, bool restoring = false)
    {
        if (profile.Id == Guid.Empty || profile.SourceRevision == Guid.Empty || !profile.BaseAddress.IsAbsoluteUri ||
            profile.BaseAddress.Scheme is not ("http" or "https") || profile.BaseAddress.UserInfo.Length != 0 ||
            profile.BaseAddress.Query.Length != 0 || profile.BaseAddress.Fragment.Length != 0)
            throw Safe(ErrorCode.InvalidRequest, "服务器地址无效，请输入 HTTP 或 HTTPS 地址。");
        if (profile.Network is not (NetworkMode.SystemNetwork or NetworkMode.SystemTailnet))
            throw Safe(ErrorCode.CapabilityMissing, "首预览仅支持系统网络或系统 Tailscale 连接。");
        if (profile.Proxy.Mode != ProxyMode.Direct)
            throw Safe(ErrorCode.CapabilityMissing, "首预览尚未接入系统或手动代理，请明确选择直接连接。");
        if (login.Mode is not (AuthenticationMode.Password or AuthenticationMode.NoAuthentication))
            throw Safe(ErrorCode.InvalidRequest, "请选择受支持的登录方式。");
        if (!restoring && login.Mode == AuthenticationMode.Password && (string.IsNullOrWhiteSpace(login.Username) || string.IsNullOrEmpty(login.Password)))
            throw Safe(ErrorCode.InvalidRequest, "请输入用户名和密码。");
    }

    private SessionState GetState(AccountKey account)
    {
        lock (_gate)
        {
            if (!_disposed && _sessions.TryGetValue(account, out var state) && state.Active) return state;
        }
        throw Safe(ErrorCode.SourceChanged, "账户连接已失效，请重新连接。");
    }

    private async Task<SessionState> GetPlaybackStateAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
    {
        var state = GetResourceState(resource);
        await RefreshSessionAsync(state, cancellationToken).ConfigureAwait(false);
        RequireDownload(state);
        return state;
    }

    private void RequireDownload(SessionState state)
    {
        EnsureActive(state);
        if (!state.Session.Permissions.Download) throw Safe(ErrorCode.PermissionDenied, Message(ErrorCode.PermissionDenied));
    }

    private SessionState GetResourceState(RemoteResourceRef resource)
    {
        lock (_gate)
        {
            // Repository references are bound when created. For a newly
            // constructed external reference there is no creation lifetime to
            // infer, so only its currently authorized account can be used.
            if (!_playbackOwners.TryGetValue(resource, out var state))
            {
                state = GetState(resource.Account);
                _playbackOwners.Add(resource, state);
            }
            EnsureActive(state);
            return state;
        }
    }

    private RemoteResourceRef BindResource(RemoteResourceRef resource, SessionState state)
    {
        lock (_gate)
        {
            EnsureActive(state);
            _playbackOwners.Add(resource, state);
            return resource;
        }
    }

    private void EnsureActive(SessionState state)
    {
        lock (_gate) if (!state.Active || _disposed) throw Safe(ErrorCode.SourceChanged, "账户连接已更换，请刷新后重试。");
    }

    private async Task RetireAccountAsync(AccountKey account, CancellationToken cancellationToken)
    {
        await _connections.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await RetireExactAccountAsync(account).ConfigureAwait(false);
        }
        finally { _connections.Release(); }
    }

    private async Task RetireAllAsync()
    {
        SessionState[] states;
        lock (_gate) states = [.. _sessions.Values];
        foreach (var state in states) await RetireAsync(state).ConfigureAwait(false);
    }

    private async Task RetireExactAccountAsync(AccountKey account)
    {
        SessionState? state;
        lock (_gate) _sessions.TryGetValue(account, out state);
        if (state is not null) await RetireAsync(state).ConfigureAwait(false);
    }

    private async Task<bool> RetireAsync(SessionState state)
    {
        ResourceLease[]? leases = null;
        lock (_gate)
        {
            if (state.Active)
            {
                state.Active = false;
                leases = [.. state.Leases];
            }
        }
        if (leases is null)
        {
            var cleanupError = await state.Retirement.Task.ConfigureAwait(false);
            if (cleanupError is not null) throw new FilewayException(cleanupError);
            return false;
        }
        FilewayException? failure = null;
        try
        {
            try { await state.Lifetime.CancelAsync().ConfigureAwait(false); }
            catch (Exception) { Trace.TraceWarning("Fileway session cancellation failed ({0}).", ErrorCode.Unknown); }
            foreach (var lease in leases)
            {
                try { await lease.DisposeAsync().ConfigureAwait(false); }
                catch (Exception) { Trace.TraceWarning("Fileway lease retirement failed ({0}).", ErrorCode.Unknown); }
            }
            await CloseCoreAsync(state.CoreSession).ConfigureAwait(false);
        }
        catch (Exception)
        {
            failure = CleanupFailed();
            throw failure;
        }
        finally
        {
            state.Lifetime.Dispose();
            lock (_gate)
            {
                if (failure is null && _sessions.TryGetValue(state.Session.Account, out var current) && ReferenceEquals(current, state))
                    _sessions.Remove(state.Session.Account);
            }
            // Keep failed, inactive states reachable so later logout/cleanup
            // callers also fail instead of clearing durable logout intent.
            state.Retirement.TrySetResult(failure?.Error);
        }
        return true;
    }

    private async Task CloseCoreAsync(string coreSession)
    {
        try { await CallAsync("close_session", new { }, coreSession, CancellationToken.None).ConfigureAwait(false); }
        catch (Exception)
        {
            try { await _host.ShutdownAsync(CancellationToken.None).ConfigureAwait(false); }
            catch (Exception) { throw CleanupFailed(); }
        }
    }

    private async Task<JsonDocument> ResourceDocumentAsync(SessionState state, RemoteResourceRef resource, bool metadata, CancellationToken cancellationToken)
    {
        ValidateWirePath(resource.WirePath);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        var response = await RequestAsync(state, "/api/resources" + resource.WirePath + (metadata ? "?metadata=1" : ""), linked.Token).ConfigureAwait(false);
        EnsureActive(state);
        return ParseBody(response);
    }

    private Task<JsonElement> RequestAsync(string session, string endpoint, CancellationToken cancellationToken) =>
        CallAsync("request", new { method = "GET", endpoint }, session, cancellationToken);

    private Task<JsonElement> RequestAsync(SessionState state, string endpoint, CancellationToken cancellationToken,
        bool requireDownload = false) => SessionRequestAsync(state, new { method = "GET", endpoint },
            requireDownload, HostCallSafety.ReadOnly, cancellationToken);

    private async Task<JsonElement> SessionRequestAsync(SessionState state, object parameters, bool requireDownload,
        HostCallSafety safety, CancellationToken cancellationToken, string operation = "request",
        Func<CancellationToken, Task>? beforeSend = null, Action<JsonElement>? confirmResponse = null,
        Action? authorizeSend = null, JournalContext? journal = null)
    {
        if (journal is not null) ValidateJournalRequest(operation, parameters, safety);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        bool entered = false;
        bool confirmed = false;
        bool checkingSession = true;
        bool invalidate = false;
        JsonElement response = default;
        ConnectionSession? updated = null;
        FilewayException? failure = null;
        try
        {
            // This gate covers all service requests, including their permission
            // checks. Lease-side renewal is independently bound by the broker.
            await state.Operations.WaitAsync(linked.Token).ConfigureAwait(false);
            entered = true;
            updated = await RefreshSessionCoreAsync(state, linked.Token).ConfigureAwait(false);
            EnsureActive(state);
            if (requireDownload) RequireDownload(state);
            checkingSession = false;
            if (beforeSend is not null)
            {
                await beforeSend(linked.Token).ConfigureAwait(false);
                // Metadata preflight can renew the token. Re-verify that
                // principal's permissions/capabilities before sending a write;
                // never replay the preflight or re-enter Operations here.
                checkingSession = true;
                updated = await RefreshSessionCoreAsync(state, linked.Token).ConfigureAwait(false) ?? updated;
                EnsureActive(state);
                checkingSession = false;
            }
            if (journal is not null) await MarkJournalDispatchAsync(state, journal, linked.Token).ConfigureAwait(false);
            authorizeSend?.Invoke();
            linked.Token.ThrowIfCancellationRequested();
            EnsureActive(state);
            if (journal is not null) journal.CallStarted = true;
            response = await CallAsync(operation, parameters, state.CoreSession, linked.Token, safety).ConfigureAwait(false);
            if (operation == "request") _ = HttpBody(response);
            confirmResponse?.Invoke(response);
            var acknowledgement = journal is null ? null : CaptureJournalAcknowledgement(journal, response);
            confirmed = true;
            if (journal is not null && acknowledgement is not null)
                await RecordJournalAcknowledgementAsync(journal, acknowledgement).ConfigureAwait(false);
            checkingSession = true;
            updated = await RefreshSessionCoreAsync(state, safety == HostCallSafety.Mutation ? state.LifetimeToken : linked.Token)
                .ConfigureAwait(false) ?? updated;
            if (safety != HostCallSafety.Mutation) EnsureActive(state);
        }
        catch (OperationCanceledException)
        {
            failure = SessionCanceled(state, cancellationToken);
            invalidate = failure.Error.Code != ErrorCode.Canceled;
        }
        catch (FilewayException error)
        {
            failure = error;
            invalidate = checkingSession && error.Error.Code != ErrorCode.Canceled || error.Error.Code == ErrorCode.AuthenticationExpired;
        }
        finally
        {
            if (entered) state.Operations.Release();
            if (journal is not null && failure is null) _activeJournalOperations.TryRemove(journal.Intent.Reference.Id, out _);
        }

        // No event callback runs under a session/persistence mutex. A subscriber can
        // safely start another operation without changing the confirmed result.
        if (failure is not null)
        {
            try
            {
                if (journal is not null && !confirmed) await RecordJournalFailureAsync(journal, failure.Error).ConfigureAwait(false);
            }
            finally
            {
                if (journal is not null) _activeJournalOperations.TryRemove(journal.Intent.Reference.Id, out _);
            }
            if (invalidate || confirmed && safety == HostCallSafety.Mutation)
            {
                try
                {
                    if (await RetireAsync(state).ConfigureAwait(false)) PublishConnectionIssue(state, failure.Error);
                }
                catch (FilewayException cleanupError) when (confirmed && safety == HostCallSafety.Mutation)
                {
                    PublishConnectionIssue(state, cleanupError.Error);
                    Trace.TraceWarning("Fileway confirmed operation session cleanup failed ({0}).", cleanupError.Error.Code);
                }
            }
            if (!confirmed || safety != HostCallSafety.Mutation) throw failure;
            Trace.TraceWarning("Fileway confirmed operation session refresh failed ({0}).", failure.Error.Code);
            return response;
        }
        PublishSessionUpdated(state, updated);
        return response;
    }

    private async Task<JsonElement> CallAsync(string operation, object parameters, string? session, CancellationToken cancellationToken,
        HostCallSafety? safety = null, TimeSpan? timeout = null)
    {
        try
        {
            var reply = await _host.CallAsync(new HostCall(operation, JsonSerializer.SerializeToElement(parameters), session,
                Safety: safety ?? (operation is "open" or "close_session" or "revoke" ? HostCallSafety.Control : HostCallSafety.ReadOnly),
                Timeout: timeout), cancellationToken).ConfigureAwait(false);
            if (!reply.IsSuccess)
            {
                if (reply.Error?.HttpStatus is { } status) CheckStatus(status);
                var code = reply.Error?.Code switch
                {
                    "Canceled" => ErrorCode.Canceled, "Busy" => ErrorCode.Busy, "ResponseTooLarge" => ErrorCode.ResponseTooLarge,
                    "ProtocolMismatch" or "HandshakeRequired" => ErrorCode.ProtocolMismatch,
                    "HostClosing" or "HostFailure" => ErrorCode.HostExited, "InvalidRequest" => ErrorCode.InvalidRequest,
                    "OutcomeUnknown" => ErrorCode.OutcomeUnknown,
                    _ => ErrorCode.NetworkUnavailable,
                };
                throw Safe(code, Message(code));
            }
            return reply.Result ?? throw InvalidResponse();
        }
        catch (FilewayException error) { throw new FilewayException(error.Error with { Message = Message(error.Error.Code) }); }
        catch (OperationCanceledException) { throw Safe(ErrorCode.Canceled, "操作已取消。"); }
        catch (Exception)
        {
            var code = safety == HostCallSafety.Mutation ? ErrorCode.OutcomeUnknown : ErrorCode.NetworkUnavailable;
            throw Safe(code, Message(code));
        }
    }

    private static string HttpBody(JsonElement response)
    {
        try { CheckStatus(response.GetProperty("status").GetInt32()); return response.GetProperty("body").GetString() ?? throw InvalidResponse(); }
        catch (Exception error) when (error is JsonException or InvalidOperationException or KeyNotFoundException) { throw InvalidResponse(); }
    }

    private static JsonDocument ParseBody(JsonElement response)
    {
        try { return JsonDocument.Parse(HttpBody(response)); }
        catch (JsonException) { throw InvalidResponse(); }
    }

    private static string ReadTokenUserId(string token)
    {
        try
        {
            var parts = token.Trim().Split('.');
            if (parts.Length != 3 || parts[1].Length > 256 * 1024) throw InvalidResponse();
            string payload = parts[1].Replace('-', '+').Replace('_', '/');
            payload = payload.PadRight((payload.Length + 3) / 4 * 4, '=');
            using var document = JsonDocument.Parse(Convert.FromBase64String(payload));
            return ReadUserId(document.RootElement.GetProperty("user"));
        }
        catch (Exception error) when (error is FormatException or JsonException or InvalidOperationException or KeyNotFoundException) { throw InvalidResponse(); }
    }

    private static string ReadUserId(JsonElement user)
    {
        if (!user.GetProperty("id").TryGetUInt64(out ulong id) || id == 0) throw InvalidResponse();
        return id.ToString(CultureInfo.InvariantCulture);
    }

    private static ResumePreferences ParseResumePreferences(JsonElement user)
    {
        PlaybackResumeMode mode = PlaybackResumeMode.Resume;
        double minimum = 10;
        if (user.TryGetProperty("playerPreferences", out var preferences) && preferences.ValueKind == JsonValueKind.Object)
        {
            mode = OptionalText(preferences, "resumeMode")?.Trim().ToLowerInvariant() switch
            {
                "from-start" or "start" or "restart" or "none" => PlaybackResumeMode.FromStart,
                "ask" or "prompt" or "choose" => PlaybackResumeMode.Ask,
                _ => PlaybackResumeMode.Resume,
            };
            if (preferences.TryGetProperty("resumeMinSec", out var raw) && raw.ValueKind == JsonValueKind.Number &&
                raw.TryGetDouble(out double seconds) && double.IsFinite(seconds) && seconds > 0)
                minimum = Math.Clamp(Math.Floor(seconds + 0.5), 5, 600);
        }
        return new ResumePreferences(mode, TimeSpan.FromSeconds(minimum));
    }

    private static TimeSpan MediaTime(JsonElement value)
    {
        double seconds = value.GetDouble();
        if (!double.IsFinite(seconds) || seconds < 0 || seconds >= TimeSpan.MaxValue.TotalSeconds) throw InvalidResponse();
        return TimeSpan.FromSeconds(seconds);
    }

    private static string PlaybackEndpoint(RemoteResourceRef resource)
    {
        ValidateWirePath(resource.WirePath);
        // Query.Get decodes once. Preserve escaped filename bytes, including
        // literal percent signs, and escape only query delimiters as Web does.
        string query = resource.WirePath.Replace("/", "%2F", StringComparison.Ordinal)
            .Replace("+", "%2B", StringComparison.Ordinal).Replace("&", "%26", StringComparison.Ordinal)
            .Replace("=", "%3D", StringComparison.Ordinal);
        return "/api/media/playback?path=" + query;
    }

    private static string PlaybackJsonPath(RemoteResourceRef resource)
    {
        ValidateWirePath(resource.WirePath);
        try
        {
            byte[] encoded = StrictUtf8.GetBytes(resource.WirePath);
            byte[] decoded = new byte[encoded.Length];
            int length = 0;
            for (int index = 0; index < encoded.Length; index++)
            {
                byte current = encoded[index];
                if (current == '%')
                {
                    if (index + 2 >= encoded.Length) throw InvalidResponse();
                    int high = Hex(encoded[index + 1]);
                    int low = Hex(encoded[index + 2]);
                    if (high < 0 || low < 0) throw InvalidResponse();
                    current = (byte)((high << 4) | low);
                    index += 2;
                }
                decoded[length++] = current;
            }
            string path = StrictUtf8.GetString(decoded, 0, length);
            if (path.Equals(resource.DisplayPath, StringComparison.Ordinal)) return path;
        }
        catch (Exception error) when (error is EncoderFallbackException or DecoderFallbackException) { }
        throw Safe(ErrorCode.CapabilityMissing, "服务器尚不支持此原始路径的续播保存；仍可直接播放或下载原文件。");
    }

    private static int Hex(byte value) => value switch
    {
        >= (byte)'0' and <= (byte)'9' => value - '0',
        >= (byte)'A' and <= (byte)'F' => value - 'A' + 10,
        >= (byte)'a' and <= (byte)'f' => value - 'a' + 10,
        _ => -1,
    };

    private RemoteFile ParseFile(JsonElement item, SessionState state)
    {
        string wirePath = RequiredText(item.GetProperty("wirePath"));
        ValidateWirePath(wirePath);
        string name = OptionalText(item, "name") is { } actualName && (actualName.Length > 0 || wirePath == "/")
            ? actualName.Length == 0 ? "根目录" : actualName : throw InvalidResponse();
        string displayPath = RequiredText(item.GetProperty("path"));
        bool isDirectory = item.GetProperty("isDir").GetBoolean();
        long size = item.GetProperty("size").GetInt64();
        if (size < 0) throw InvalidResponse();
        DateTimeOffset? modified = null;
        if (OptionalText(item, "modified") is { } date && DateTimeOffset.TryParse(date, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var parsed)) modified = parsed;
        return new RemoteFile(BindResource(new RemoteResourceRef(state.Session.Account, wirePath, name, displayPath), state),
            isDirectory, size, modified, OptionalText(item, "type"));
    }

    private static int CompareFiles(RemoteFile left, RemoteFile right, DirectoryQuery query)
    {
        if (left.IsDirectory != right.IsDirectory) return left.IsDirectory ? -1 : 1;
        int order = query.SortBy switch
        {
            FileSortField.Size => left.Size.CompareTo(right.Size), FileSortField.Modified => Nullable.Compare(left.Modified, right.Modified),
            FileSortField.Type => StringComparer.OrdinalIgnoreCase.Compare(left.MediaType, right.MediaType),
            _ => StringComparer.OrdinalIgnoreCase.Compare(left.Resource.DisplayName, right.Resource.DisplayName),
        };
        if (order == 0) order = StringComparer.Ordinal.Compare(left.Resource.WirePath, right.Resource.WirePath);
        return query.Descending ? -Math.Sign(order) : order;
    }

    private static void ValidateWirePath(string wirePath)
    {
        if (string.IsNullOrEmpty(wirePath) || wirePath[0] != '/' || wirePath.Any(character => character is '?' or '#' or '\\' || char.IsControl(character)))
            throw InvalidResponse();
    }

    private static bool Flag(JsonElement value, string name) => value.TryGetProperty(name, out var flag) && flag.ValueKind == JsonValueKind.True;
    private static string? OptionalText(JsonElement value, string name) => value.TryGetProperty(name, out var text) && text.ValueKind == JsonValueKind.String ? text.GetString() : null;
    private static string RequiredText(JsonElement value) => value.ValueKind == JsonValueKind.String && value.GetString() is { Length: > 0 } text ? text : throw InvalidResponse();
    private static FilewayException Safe(ErrorCode code, string message, int? status = null) => new(new ErrorInfo(code, message, status));
    private static FilewayException CleanupFailed() => Safe(ErrorCode.HostExited, "无法确认连接与媒体已关闭，请关闭并重新打开客户端。");
    private static FilewayException InvalidResponse() => Safe(ErrorCode.ProtocolMismatch, "服务器返回的数据格式不受支持，请刷新或检查服务器版本。");
    private static void CheckStatus(int status)
    {
        if (status is >= 200 and < 300) return;
        var code = status switch { 401 => ErrorCode.AuthenticationExpired, 403 => ErrorCode.PermissionDenied, 409 => ErrorCode.Conflict, 408 or 504 => ErrorCode.Timeout, _ => ErrorCode.NetworkUnavailable };
        throw Safe(code, Message(code), status);
    }

    private static string Message(ErrorCode code) => code switch
    {
        ErrorCode.AuthenticationExpired => "登录已失效，请重新登录。", ErrorCode.PermissionDenied => "没有权限执行此操作，请检查账户权限。",
        ErrorCode.Canceled => "操作已取消。", ErrorCode.Busy => "请求较多，请稍后重试。", ErrorCode.ResponseTooLarge => "服务器响应超过大小限制。",
        ErrorCode.ProtocolMismatch => "服务器返回的数据格式不受支持。", ErrorCode.HostExited => "连接服务已退出，请重新连接。",
        ErrorCode.InvalidRequest => "请求参数无效。", ErrorCode.Timeout => "连接超时，请稍后重试。", ErrorCode.Conflict => "服务器内容已变化，请刷新后重试。",
        ErrorCode.SourceChanged => "账户连接已更换，请重新连接。", ErrorCode.CapabilityMissing => "当前连接不支持此功能。",
        ErrorCode.CredentialsUnavailable => "无法访问此 Windows 用户的登录凭据，请重新登录或稍后重试。",
        ErrorCode.LocalStorageUnavailable => "无法访问本地连接资料，请稍后重试。",
        ErrorCode.OutcomeUnknown => "操作结果尚未确认；请先核对服务器状态，不要直接重试。",
        _ => "无法完成服务器请求，请检查网络或服务器后重试。",
    };

    private sealed class ResourceLease(FilewayClientServices owner, SessionState state, RemoteResourceRef resource, Uri address) : IResourceLease, IResourceLeaseStatisticsSource
    {
        private int _disposed;
        private Task<bool>? _release;
        private ImageContent? _image;
        internal SessionState State { get; } = state;
        public RemoteResourceRef Resource { get; } = resource;
        public Uri Address { get; } = address;
        public override string ToString() => "ResourceLease (address redacted)";

        public async Task<ResourceLeaseStatistics?> ReadStatisticsAsync(CancellationToken cancellationToken)
        {
            lock (owner._gate)
            {
                if (_disposed != 0) return null;
                owner.EnsureActive(State);
                if (!State.Leases.Contains(this)) throw Safe(ErrorCode.SourceChanged, Message(ErrorCode.SourceChanged));
            }
            using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, State.LifetimeToken);
            try
            {
                var result = await owner.CallAsync("lease_stats", new { url = Address.AbsoluteUri }, State.CoreSession,
                    linked.Token).ConfigureAwait(false);
                long upstream = result.GetProperty("upstreamBytes").GetInt64();
                long cached = result.GetProperty("cacheReadBytes").GetInt64();
                long delivered = result.GetProperty("deliveredBytes").GetInt64();
                long elapsed = result.GetProperty("elapsedMillis").GetInt64();
                if (upstream < 0 || cached < 0 || delivered < 0 || elapsed < 0 || elapsed > TimeSpan.MaxValue.Ticks / TimeSpan.TicksPerMillisecond)
                    throw InvalidResponse();
                lock (owner._gate)
                {
                    if (_disposed != 0) return null;
                    owner.EnsureActive(State);
                }
                if (result.TryGetProperty("cacheWriteFailures", out var failures))
                {
                    long count = failures.GetInt64();
                    if (count < 0) throw InvalidResponse();
                    if (count > 0) owner.PublishCacheUnavailable(State);
                }
                return new ResourceLeaseStatistics(upstream, cached, delivered, TimeSpan.FromTicks(elapsed * TimeSpan.TicksPerMillisecond));
            }
            catch (FilewayException) when (Volatile.Read(ref _disposed) != 0) { return null; }
            catch (FilewayException) when (State.LifetimeToken.IsCancellationRequested)
            { throw Safe(ErrorCode.SourceChanged, Message(ErrorCode.SourceChanged)); }
            catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException or OverflowException)
            { throw InvalidResponse(); }
        }

        internal void Attach(ImageContent image)
        {
            lock (owner._gate)
            {
                if (_disposed != 0) { image.CloseContent(); throw Safe(ErrorCode.SourceChanged, "图片所属账户连接已关闭。"); }
                _image = image;
            }
        }

        public async ValueTask DisposeAsync()
        {
            _ = await TryDisposeConfirmedAsync().ConfigureAwait(false);
        }

        /// <summary>Shared release result; false means session closure is needed to retire unobserved authority.</summary>
        internal Task<bool> TryDisposeConfirmedAsync()
        {
            TaskCompletionSource<bool> completion;
            lock (owner._gate)
            {
                if (_release is not null) return _release;
                Interlocked.Exchange(ref _disposed, 1);
                State.Leases.Remove(this);
                _image?.CloseContent();
                _image = null;
                completion = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
                _release = completion.Task;
            }
            // Dispatch outside the owner's gate. Retirement awaits this inner
            // release, so it must never wait for Retirement or call RetireAsync.
            _ = CompleteReleaseAsync(completion);
            return completion.Task;
        }

        private async Task CompleteReleaseAsync(TaskCompletionSource<bool> completion)
        {
            try
            {
                await owner.CallAsync("revoke", new { url = Address.AbsoluteUri }, null, CancellationToken.None).ConfigureAwait(false);
                completion.TrySetResult(true);
            }
            catch (FilewayException) { completion.TrySetResult(false); }
            catch (Exception error) { completion.TrySetException(error); }
        }
    }

    private sealed class ImageContent(RemoteResourceRef resource, MemoryStream content, HttpResponseMessage response, ResourceLease lease) : IImageContent
    {
        public RemoteResourceRef Resource { get; } = resource;
        public Stream Content { get; } = content;
        public string? ContentType { get; } = response.Content.Headers.ContentType?.MediaType;
        public long Length { get; } = content.Length;
        public override string ToString() => "ImageContent (source redacted)";
        internal void CloseContent() { content.Dispose(); response.Dispose(); }
        public ValueTask DisposeAsync() => lease.DisposeAsync();
    }
}
