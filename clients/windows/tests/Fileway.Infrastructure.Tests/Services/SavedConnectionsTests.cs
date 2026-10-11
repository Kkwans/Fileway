using System.Collections.Concurrent;
using System.Text;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Credentials;
using Fileway.Infrastructure.Persistence;
using Fileway.Infrastructure.Services;
using Fileway.Infrastructure.Tests.Persistence;

namespace Fileway.Infrastructure.Tests.Services;

public sealed class SavedConnectionsTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;
    private static readonly LoginInput Login = new(AuthenticationMode.Password, "fixture.user", "fixture.password", KeepSignedIn: true);

    [Fact]
    public async Task PasswordRestoreUsesOnlySavedTokenAndVerifiesLiveMetadata()
    {
        using var directory = new StorageTestDirectory();
        var saved = SavedProfile();
        var token = Jwt("initial");
        await new SqliteProfileStore(directory.DatabasePath).SaveAsync(saved, Token);
        await new DpapiCredentialVault(directory.VaultPath).WriteTokenAsync(saved.Account, token, Token);
        await using var host = new ControlledHost();
        await using var services = new FilewayClientServices(host, new SqliteProfileStore(directory.DatabasePath),
            new DpapiCredentialVault(directory.VaultPath));

        var restored = await services.RestoreAsync(saved.Profile.Id, Token);

        Assert.Equal(saved.Account, restored.Account);
        Assert.Equal("服务器核验用户", restored.DisplayName);
        Assert.True(restored.Permissions.Download); // The saved token claims false.
        Assert.False(restored.Permissions.Delete);
        Assert.DoesNotContain(host.Calls, call => call.Operation == "login");
        var open = Assert.Single(host.Calls, call => call.Operation == "open");
        Assert.Equal(token, open.Parameters!.Value.GetProperty("token").GetString());
        Assert.Contains(host.Calls, call => Endpoint(call) == "/api/users/7");
        Assert.Equal("服务器核验用户", Assert.IsType<SavedConnection>(await services.GetAsync(saved.Profile.Id, Token)).Username);
        var bytes = await File.ReadAllBytesAsync(directory.DatabasePath, Token);
        Assert.Equal(-1, bytes.AsSpan().IndexOf(Encoding.UTF8.GetBytes(token)));
        Assert.Equal(-1, bytes.AsSpan().IndexOf(Encoding.UTF8.GetBytes(Login.Password)));
    }

    [Fact]
    public async Task RestoreRejectsChangedServerPrincipalWithoutOverwritingSavedState()
    {
        using var directory = new StorageTestDirectory();
        var saved = SavedProfile();
        var store = new SqliteProfileStore(directory.DatabasePath);
        var vault = new DpapiCredentialVault(directory.VaultPath);
        var token = Jwt("initial");
        await store.SaveAsync(saved, Token);
        await vault.WriteTokenAsync(saved.Account, token, Token);
        await using var host = new ControlledHost { LiveUserId = 8 };
        await using var services = new FilewayClientServices(host, store, vault);

        var failure = await Assert.ThrowsAsync<FilewayException>(() => services.RestoreAsync(saved.Profile.Id, Token));

        Assert.Equal(ErrorCode.AuthenticationExpired, failure.Error.Code);
        Assert.Equal(saved, await store.GetAsync(saved.Profile.Id, Token));
        Assert.Equal(token, await vault.ReadTokenAsync(saved.Account, Token));
        Assert.DoesNotContain(host.Calls, call => call.Operation == "login");
        Assert.Single(host.Calls, call => call.Operation == "close_session");
    }

    [Theory]
    [InlineData(false, ErrorCode.CredentialsUnavailable)]
    [InlineData(true, ErrorCode.Canceled)]
    public async Task FailedLogoutRetiresSessionAndRestartCompletesDurableCleanup(bool cancelAfterIntent, ErrorCode expected)
    {
        using var directory = new StorageTestDirectory();
        using var cancellation = CancellationTokenSource.CreateLinkedTokenSource(Token);
        var backingStore = new SqliteProfileStore(directory.DatabasePath);
        var store = new LogoutIntentStore(backingStore);
        var backingVault = new DpapiCredentialVault(directory.VaultPath);
        var vault = new FaultVault(backingVault);
        var profile = SavedProfile().Profile;
        await using (var host = new ControlledHost())
        await using (var services = new FilewayClientServices(host, store, vault))
        {
            var session = await services.ConnectAsync(profile, Login, Token);
            if (cancelAfterIntent) store.AfterBeginCommitted = cancellation.Cancel;
            else vault.FailDelete = true;

            var failure = await Assert.ThrowsAsync<FilewayException>(() => services.LogoutAsync(session.Account, cancellation.Token));

            Assert.Equal(expected, failure.Error.Code);
            Assert.False(Assert.IsType<SavedConnection>(await backingStore.GetAsync(profile.Id, Token)).KeepSignedIn);
            Assert.Equal(session.Account, Assert.Single(await backingStore.ListPendingLogoutsAsync(Token)));
            Assert.NotNull(await backingVault.ReadTokenAsync(session.Account, Token));
            var stale = await Assert.ThrowsAsync<FilewayException>(() => services.GetRootAsync(session, Token));
            Assert.Equal(ErrorCode.SourceChanged, stale.Error.Code);
            Assert.Contains(host.Calls, call => call.Operation == "close_session");
        }

        await using var restartedHost = new ControlledHost();
        var reopenedStore = new SqliteProfileStore(directory.DatabasePath);
        var reopenedVault = new DpapiCredentialVault(directory.VaultPath);
        await using var restarted = new FilewayClientServices(restartedHost, reopenedStore, reopenedVault);
        var retained = Assert.IsType<SavedConnection>(await restarted.GetAsync(profile.Id, Token));
        Assert.False(retained.KeepSignedIn);
        Assert.Empty(await reopenedStore.ListPendingLogoutsAsync(Token));
        Assert.Null(await reopenedVault.ReadTokenAsync(retained.Account, Token));
        var restore = await Assert.ThrowsAsync<FilewayException>(() => restarted.RestoreAsync(profile.Id, Token));
        Assert.Equal(ErrorCode.AuthenticationExpired, restore.Error.Code);
        Assert.Empty(restartedHost.Calls);
    }

    [Fact]
    public async Task ConcurrentTokenRefreshAndLogoutCannotResurrectSavedCredentials()
    {
        var store = new InMemoryProfileStore();
        var backingVault = new InMemoryCredentialVault();
        var vault = new FaultVault(backingVault);
        await using var host = new ControlledHost();
        await using var services = new FilewayClientServices(host, store, vault);
        var session = await services.ConnectAsync(SavedProfile().Profile, Login, Token);
        vault.PauseNextWrite = true;
        host.CurrentToken = Jwt("renewed");
        var refresh = services.GetRootAsync(session, Token);
        await vault.WriteEntered.Task.WaitAsync(TimeSpan.FromSeconds(10), Token);
        var logout = services.LogoutAsync(session.Account, Token);
        vault.ReleaseWrite.TrySetResult();

        var refreshFailure = await Record.ExceptionAsync(async () => { await refresh; });
        await logout;

        Assert.True(refreshFailure is null or FilewayException { Error.Code: ErrorCode.SourceChanged or ErrorCode.Canceled });
        Assert.Null(await backingVault.ReadTokenAsync(session.Account, Token));
        Assert.False(Assert.IsType<SavedConnection>(await store.GetAsync(session.Account.ProfileId, Token)).KeepSignedIn);
        Assert.Empty(await store.ListPendingLogoutsAsync(Token));
    }

    [Fact]
    public async Task ConfirmedWriteSucceedsOnceWhenPostWriteTokenPersistenceFails()
    {
        var store = new InMemoryProfileStore();
        var backingVault = new InMemoryCredentialVault();
        var vault = new FaultVault(backingVault);
        await using var host = new ControlledHost();
        await using var services = new FilewayClientServices(host, store, vault);
        var session = await services.ConnectAsync(SavedProfile().Profile, Login, Token);
        var issues = new List<ConnectionIssue>();
        services.ConnectionIssue += (_, _) => throw new InvalidOperationException("fixture subscriber failure");
        services.ConnectionIssue += (_, issue) => issues.Add(issue);
        host.RotateAfterMutation = Jwt("after-confirmed-write");
        vault.FailWrite = true;
        var resource = new RemoteResourceRef(session.Account, "/video.mp4", "video.mp4", "/video.mp4");

        await services.WriteAsync(resource, TimeSpan.FromSeconds(42), TimeSpan.FromSeconds(120), Token);

        var mutation = Assert.Single(host.Calls, call => call.Operation == "request"
            && call.Parameters!.Value.GetProperty("method").GetString() == "PUT");
        Assert.Equal(HostCallSafety.Mutation, mutation.Safety);
        var issue = Assert.Single(issues);
        Assert.Equal(session.Account, issue.Account);
        Assert.Equal(session.Id, issue.SessionId);
        Assert.Equal(ErrorCode.CredentialsUnavailable, issue.Error.Code);
        Assert.True(issue.SessionInvalidated);
        Assert.Equal(Jwt("initial"), await backingVault.ReadTokenAsync(session.Account, Token));
        var stale = await Assert.ThrowsAsync<FilewayException>(() => services.GetRootAsync(session, Token));
        Assert.Equal(ErrorCode.SourceChanged, stale.Error.Code);
        Assert.Single(host.Calls, call => call.Operation == "login");
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task FailedSessionCloseUsesHostShutdownAndKeepsLogoutPendingWhenBothFail(bool shutdownFails)
    {
        var store = new InMemoryProfileStore();
        var vault = new InMemoryCredentialVault();
        await using var host = new ControlledHost();
        await using var services = new FilewayClientServices(host, store, vault);
        var session = await services.ConnectAsync(SavedProfile().Profile, Login, Token);
        host.FailClose = true;
        host.FailShutdown = shutdownFails;

        if (shutdownFails)
        {
            var failure = await Assert.ThrowsAsync<FilewayException>(() => services.LogoutAsync(session.Account, Token));
            Assert.Equal(ErrorCode.HostExited, failure.Error.Code);
            Assert.DoesNotContain(ControlledHost.PrivateFailure, failure.ToString());
            Assert.Equal(session.Account, Assert.Single(await store.ListPendingLogoutsAsync(Token)));
            Assert.NotNull(await vault.ReadTokenAsync(session.Account, Token));
            // A cached failed retirement must not later be mistaken for success.
            var repeated = await Assert.ThrowsAsync<FilewayException>(() => services.LogoutAsync(session.Account, Token));
            Assert.Equal(ErrorCode.HostExited, repeated.Error.Code);
            Assert.Equal(session.Account, Assert.Single(await store.ListPendingLogoutsAsync(Token)));
            await Assert.ThrowsAsync<FilewayException>(() => services.DisposeAsync().AsTask());
            host.FailShutdown = false; // Allow the test-owned fake to dispose normally.
        }
        else
        {
            await services.LogoutAsync(session.Account, Token);
            Assert.Empty(await store.ListPendingLogoutsAsync(Token));
            Assert.Null(await vault.ReadTokenAsync(session.Account, Token));
        }
        Assert.Equal(1, host.ShutdownCalls);
        Assert.False(Assert.IsType<SavedConnection>(await store.GetAsync(session.Account.ProfileId, Token)).KeepSignedIn);
    }

    private static SavedConnection SavedProfile() => new(
        new ConnectionProfile(Guid.NewGuid(), Guid.NewGuid(), "保存连接", new Uri("https://fixture.invalid/nas/"),
            NetworkMode.SystemNetwork, new ProxyPolicy(ProxyMode.Direct)),
        AuthenticationMode.Password, "旧资料用户", "7", true, DateTimeOffset.UtcNow.AddDays(-1));

    private static string Jwt(string version) => "fixture." + Convert.ToBase64String(JsonSerializer.SerializeToUtf8Bytes(
        new { user = new { id = 7, username = "token 中的旧名称", perm = new { download = false } }, version }))
        .TrimEnd('=').Replace('+', '-').Replace('/', '_') + ".signature";

    private static string? Endpoint(HostCall call) => call.Operation == "request"
        ? call.Parameters!.Value.GetProperty("endpoint").GetString() : null;

    // This fake models the public Host boundary, not the broker's renewal internals.
    // Rotation is explicitly observable through "token" before a persistence failure.
    private sealed class ControlledHost : IHostControlClient
    {
        internal const string PrivateFailure = "fixture-private-credential-failure";
        private readonly TaskCompletionSource<HostExit> _completion = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private int _shutdownCalls;
        internal ConcurrentQueue<HostCall> Calls { get; } = new();
        internal string CurrentToken { get; set; } = Jwt("initial");
        internal string? RotateAfterMutation { get; set; }
        internal int LiveUserId { get; set; } = 7;
        internal bool FailClose { get; set; }
        internal bool FailShutdown { get; set; }
        internal int ShutdownCalls => Volatile.Read(ref _shutdownCalls);
        public HostHandshake Handshake { get; } = new(1, 0, 1, "fixture", "fixture", "fixture", JsonSerializer.SerializeToElement(new { }));
        public Task<HostExit> Completion => _completion.Task;

        public Task<HostReply> CallAsync(HostCall request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Calls.Enqueue(request);
            object result;
            switch (request.Operation)
            {
                case "open":
                    var supplied = request.Parameters!.Value.GetProperty("token").GetString();
                    if (!string.IsNullOrEmpty(supplied)) CurrentToken = supplied;
                    result = "fixture-session";
                    break;
                case "login":
                    result = Http(CurrentToken);
                    break;
                case "token":
                    result = CurrentToken;
                    break;
                case "request":
                    var parameters = request.Parameters!.Value;
                    var method = parameters.GetProperty("method").GetString();
                    var endpoint = parameters.GetProperty("endpoint").GetString();
                    if (method == "PUT")
                    {
                        result = Http("{}");
                        if (RotateAfterMutation is { } rotated) CurrentToken = rotated;
                    }
                    else if (endpoint == "/api/users/7")
                        result = Http(JsonSerializer.Serialize(new { id = LiveUserId, username = "服务器核验用户",
                            perm = new { download = true, create = false, rename = false, modify = false, delete = false, execute = false } }));
                    else if (endpoint == "/api/client-capabilities")
                        result = Http("{\"authMethod\":\"json\",\"resourceWireOperations\":true}");
                    else throw new InvalidOperationException("Unexpected fixture endpoint.");
                    break;
                case "close_session":
                    if (FailClose) throw new IOException(PrivateFailure);
                    result = new { };
                    break;
                default:
                    throw new InvalidOperationException("Unexpected fixture operation.");
            }
            return Task.FromResult(new HostReply(Guid.NewGuid().ToString("N"), request.Generation, true,
                JsonSerializer.SerializeToElement(result), null));
        }

        private static object Http(string body) => new { status = 200, body };
        public Task ShutdownAsync(CancellationToken cancellationToken)
        {
            Interlocked.Increment(ref _shutdownCalls);
            if (FailShutdown) throw new IOException(PrivateFailure);
            _completion.TrySetResult(new HostExit(true, 0, null));
            return Task.CompletedTask;
        }
        public async ValueTask DisposeAsync() => await ShutdownAsync(CancellationToken.None);
    }

    private sealed class FaultVault(ICredentialVault inner) : ICredentialVault
    {
        internal bool FailWrite { get; set; }
        internal bool FailDelete { get; set; }
        internal bool PauseNextWrite { get; set; }
        internal TaskCompletionSource WriteEntered { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal TaskCompletionSource ReleaseWrite { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);

        public Task<string?> ReadTokenAsync(AccountKey account, CancellationToken cancellationToken) => inner.ReadTokenAsync(account, cancellationToken);
        public async Task WriteTokenAsync(AccountKey account, string token, CancellationToken cancellationToken)
        {
            if (PauseNextWrite)
            {
                PauseNextWrite = false;
                WriteEntered.TrySetResult();
                await ReleaseWrite.Task.WaitAsync(cancellationToken);
            }
            cancellationToken.ThrowIfCancellationRequested();
            if (FailWrite) throw new IOException("fixture credential write failure");
            await inner.WriteTokenAsync(account, token, cancellationToken);
        }
        public Task DeleteTokenAsync(AccountKey account, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return FailDelete ? Task.FromException(new IOException("fixture credential deletion failure"))
                : inner.DeleteTokenAsync(account, cancellationToken);
        }
    }

    private sealed class LogoutIntentStore(IProfileStore inner) : IProfileStore
    {
        internal Action? AfterBeginCommitted { get; set; }
        public Task<IReadOnlyList<SavedConnection>> ListAsync(CancellationToken cancellationToken) => inner.ListAsync(cancellationToken);
        public Task<SavedConnection?> GetAsync(Guid profileId, CancellationToken cancellationToken) => inner.GetAsync(profileId, cancellationToken);
        public Task SaveAsync(SavedConnection connection, CancellationToken cancellationToken) => inner.SaveAsync(connection, cancellationToken);
        public Task ForgetAsync(AccountKey account, CancellationToken cancellationToken) => inner.ForgetAsync(account, cancellationToken);
        public Task<IReadOnlyList<AccountKey>> ListPendingLogoutsAsync(CancellationToken cancellationToken) => inner.ListPendingLogoutsAsync(cancellationToken);
        public async Task BeginLogoutAsync(AccountKey account, CancellationToken cancellationToken)
        {
            await inner.BeginLogoutAsync(account, cancellationToken);
            AfterBeginCommitted?.Invoke();
        }
        public Task CompleteLogoutAsync(AccountKey account, CancellationToken cancellationToken) => inner.CompleteLogoutAsync(account, cancellationToken);
    }
}
