using System.Collections.Concurrent;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Credentials;
using Fileway.Infrastructure.Ipc;
using Fileway.Infrastructure.Persistence;
using Fileway.Infrastructure.Services;
using Fileway.Infrastructure.Tests.Ipc;
using Fileway.Infrastructure.Tests.Persistence;

namespace Fileway.Infrastructure.Tests.Services;

public sealed class ImageCacheAndRootsTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;
    private static readonly LoginInput Login = new(AuthenticationMode.Password, "fixture.user", "fixture.password", KeepSignedIn: true);
    private const string WirePath = "/%FF%252F.png";
    private const string PreviewTarget = "/nas/api/preview/thumb" + WirePath + "?fit=contain";
    private const string RawTarget = "/nas/api/raw" + WirePath;
    private static readonly byte[] Png = Convert.FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1cAAAAASUVORK5CYII=");
    private static readonly byte[] Jpeg = Convert.FromBase64String(
        "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////2wBDAf//////////////////////////////////////////////////////////////////////////////////////wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAf/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIQAxAAAAF//8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABBQJ//8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAgBAwEBPwF//8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAgBAgEBPwF//8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQAGPwJ//8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPyF//9oADAMBAAIAAwAAABD/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAEDAQE/EB//xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAECAQE/EB//xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAE/EB//2Q==");

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task RegistryFailureStreamsTheSameRawOrContainRepresentationWithoutCacheOwnership(bool thumbnail)
    {
        byte[] payload = thumbnail ? Png : Jpeg;
        string mime = thumbnail ? "image/png" : "image/jpeg";
        await using var fixture = new HttpFixture(request => Response(request, payload, mime));
        using var directory = new StorageTestDirectory();
        await using var realHost = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        var host = new ObservedHost(realHost);
        var store = new InMemoryProfileStore();
        await using var settings = new LocalStorageSettingsService(store, Settings(directory, "cache"), host);
        await using var services = new FilewayClientServices(host, new FailingRegistry(store), new InMemoryCredentialVault(), settings);
        var session = await services.ConnectAsync(Profile(fixture), Login, Token);

        await using var image = thumbnail
            ? await services.OpenThumbnailAsync(Resource(session), Token)
            : await services.OpenAsync(Resource(session), Token);

        Assert.Equal(payload.Length, image.Length);
        Assert.Equal(mime, image.ContentType);
        byte[] actual = new byte[payload.Length];
        await image.Content.ReadExactlyAsync(actual, Token);
        Assert.Equal(payload, actual);
        var acquired = Assert.Single(host.Calls, call => call.Operation is "lease" or "preview");
        Assert.Equal(thumbnail ? "preview" : "lease", acquired.Operation);
        Assert.False(acquired.Parameters!.Value.TryGetProperty("cacheKey", out _));
        Assert.False(acquired.Parameters.Value.TryGetProperty("cacheOwner", out _));
        if (thumbnail) Assert.Equal("contain", acquired.Parameters.Value.GetProperty("scope").GetString());
        Assert.Empty(await store.ListCacheRootsAsync(Token));
        var requests = Drain(fixture);
        Assert.Contains(requests, request => request.Target == (thumbnail ? PreviewTarget : RawTarget));
        Assert.DoesNotContain(requests, request => request.Target == (thumbnail ? RawTarget : PreviewTarget));
    }

    [Theory]
    [InlineData(501, "image/png", ErrorCode.NetworkUnavailable)]
    [InlineData(200, "image/gif", ErrorCode.CapabilityMissing)]
    public async Task UnsupportedPreviewNeverRequestsTheOriginal(int status, string mime, ErrorCode expected)
    {
        await using var fixture = new HttpFixture(request => request.Target == PreviewTarget
            ? new FixtureResponse(status, Png, mime) : Response(request, Png));
        using var directory = new StorageTestDirectory();
        await using var host = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        var store = new InMemoryProfileStore();
        await using var settings = new LocalStorageSettingsService(store, Settings(directory, "cache"), host);
        await using var services = new FilewayClientServices(host, store, new InMemoryCredentialVault(), settings);
        var session = await services.ConnectAsync(Profile(fixture), Login, Token);

        var failure = await Assert.ThrowsAsync<FilewayException>(() => services.OpenThumbnailAsync(Resource(session), Token));

        Assert.Equal(expected, failure.Error.Code);
        var requests = Drain(fixture);
        Assert.Contains(requests, request => request.Target == PreviewTarget);
        Assert.DoesNotContain(requests, request => request.Target == RawTarget);
    }

    [Theory]
    [InlineData(true, 0)]
    [InlineData(true, 1)]
    [InlineData(false, 1)]
    public async Task EncodedImageLimitsAcceptEightMiBPreviewAndRejectTheNextByte(bool thumbnail, int excess)
    {
        int maximum = (thumbnail ? 8 : 64) * 1024 * 1024;
        // PNG contents followed by padding exercise encoded HTTP size, independent of decoded dimensions.
        byte[] payload = new byte[maximum + excess];
        Png.CopyTo(payload, 0);
        await using var fixture = new HttpFixture(request => Response(request, payload));
        using var directory = new StorageTestDirectory();
        await using var host = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        var store = new InMemoryProfileStore();
        await using var settings = new LocalStorageSettingsService(store, Settings(directory, "cache") with { MaximumCacheBytes = 0 }, host);
        await using var services = new FilewayClientServices(host, store, new InMemoryCredentialVault(), settings);
        var session = await services.ConnectAsync(Profile(fixture), Login, Token);
        if (excess == 0)
        {
            await using var image = await services.OpenThumbnailAsync(Resource(session), Token);
            Assert.Equal(maximum, image.Length);
            Assert.Equal(0x89, image.Content.ReadByte());
        }
        else
        {
            var failure = await Assert.ThrowsAsync<FilewayException>(() => thumbnail
                ? services.OpenThumbnailAsync(Resource(session), Token) : services.OpenAsync(Resource(session), Token));
            Assert.Equal(ErrorCode.ResponseTooLarge, failure.Error.Code);
        }
        Assert.DoesNotContain(Drain(fixture), request => request.Target == (thumbnail ? RawTarget : PreviewTarget));
    }

    [Fact]
    public async Task InitializationSeedsKnownAndPendingOwnersBeforeAnyAcquisitionAndClearPreservesOtherData()
    {
        await using var fixture = new HttpFixture(request => Response(request, Png));
        using var directory = new StorageTestDirectory();
        var defaults = Settings(directory, "cache");
        var store = new InMemoryProfileStore();
        var profile = Profile(fixture);
        var saved = Saved(profile);
        var pending = saved.Account with { ProfileId = Guid.NewGuid() };
        var unregistered = saved.Account with { ServerUserId = "99" };
        await store.SaveAsync(saved, Token);
        await store.BeginLogoutAsync(pending, Token);
        string savedBlock = SeedOldBlock(defaults.CacheDirectory, saved.Account, "saved");
        string pendingBlock = SeedOldBlock(defaults.CacheDirectory, pending, "pending");
        string unregisteredBlock = SeedOldBlock(defaults.CacheDirectory, unregistered, "unknown-owner");
        string unknown = Path.Combine(Path.GetDirectoryName(savedBlock)!, "personal-notes.txt");
        await File.WriteAllTextAsync(unknown, "preserve unknown file", Token);
        Directory.CreateDirectory(defaults.DownloadDirectory);
        string download = Path.Combine(defaults.DownloadDirectory, "fileway-chunk-" + Hash("download") + "-0-4");
        await File.WriteAllTextAsync(download, "preserve download", Token);
        await using var realHost = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        var host = new ObservedHost(realHost);
        await using var settings = new LocalStorageSettingsService(store, defaults, host);

        await settings.InitializeCacheAsync(Token);

        Assert.DoesNotContain(host.Calls, call => call.Operation is "lease" or "preview");
        var roots = await store.ListCacheRootsAsync(Token);
        Assert.Equal(2, roots.Count);
        Assert.Contains(new RegisteredCacheRoot(saved.Account, defaults.CacheDirectory), roots);
        Assert.Contains(new RegisteredCacheRoot(pending, defaults.CacheDirectory), roots);
        await settings.ClearInactiveCacheAsync(Token);
        Assert.False(File.Exists(savedBlock));
        Assert.False(File.Exists(pendingBlock));
        Assert.True(File.Exists(unregisteredBlock));
        Assert.Equal("preserve unknown file", await File.ReadAllTextAsync(unknown, Token));
        Assert.Equal("preserve download", await File.ReadAllTextAsync(download, Token));
        Assert.Equal(roots.Count, (await store.ListCacheRootsAsync(Token)).Count);
        Assert.Equal(pending, Assert.Single(await store.ListPendingLogoutsAsync(Token)));
        Assert.All(host.Calls.Where(call => call.Operation == "cache_cleanup_owner"),
            call => Assert.True(call.Parameters!.Value.GetProperty("inactiveOnly").GetBoolean()));

        await using var services = new FilewayClientServices(host, store, new InMemoryCredentialVault(), settings);
        var session = await services.ConnectAsync(profile, Login, Token);
        await using var active = await services.AcquireCachedAsync(Resource(session), Token);
        Assert.Equal(Png, await ReadLeaseAsync(active));
        string activeBlock = Assert.Single(Directory.GetFiles(OwnerDirectory(defaults.CacheDirectory, saved.Account), "fileway-chunk-*", SearchOption.AllDirectories));
        var usage = await settings.ClearInactiveCacheAsync(Token);
        Assert.True(File.Exists(activeBlock));
        Assert.True(usage.ProtectedBlockCount > 0);
        await active.DisposeAsync();
        await settings.ClearInactiveCacheAsync(Token);
        Assert.False(File.Exists(activeBlock));
        Assert.True(File.Exists(unregisteredBlock));
        Assert.True(File.Exists(unknown));
        Assert.True(File.Exists(download));
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task RestartedTwoRootLogoutRetainsFailedOldOwnerWhileSameProfileChangesUserOrRevision(bool changeUser)
    {
        int userId = 7;
        await using var fixture = new HttpFixture(request => Response(request, Png, userId: Volatile.Read(ref userId)));
        using var directory = new StorageTestDirectory();
        var firstSettings = Settings(directory, "first-root");
        var nextSettings = Settings(directory, "second-root");
        var profile = Profile(fixture);
        var vault = new InMemoryCredentialVault();
        AccountKey oldAccount;
        string oldOwner;
        await using (var firstHost = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token))
        {
            var store = new SqliteProfileStore(directory.DatabasePath);
            await using var settings = new LocalStorageSettingsService(store, firstSettings, firstHost);
            await using var services = new FilewayClientServices(firstHost, store, vault, settings);
            var session = await services.ConnectAsync(profile, Login, Token);
            oldAccount = session.Account;
            oldOwner = Owner(oldAccount);
            await using var oldImage = await services.OpenAsync(Resource(session), Token);
            Assert.NotNull(await vault.ReadTokenAsync(oldAccount, Token));
            Assert.True((await settings.SaveAsync(nextSettings, Token)).RestartRequired);
        }
        var reopened = new SqliteProfileStore(directory.DatabasePath);
        await using var realHost = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        var host = new ObservedHost(realHost) { FailedCleanupRoot = firstSettings.CacheDirectory };
        await using var restarted = new LocalStorageSettingsService(reopened, firstSettings, host);
        await using var client = new FilewayClientServices(host, reopened, vault, restarted);
        Assert.Equal(nextSettings, (await restarted.ReadAsync(Token)).Active);
        var restored = await client.RestoreAsync(profile.Id, Token);
        await using var secondImage = await client.OpenAsync(Resource(restored), Token);
        Assert.Equal(2, (await reopened.ListCacheRootsAsync(Token)).Count(root => root.Account == oldAccount));

        if (changeUser) Volatile.Write(ref userId, 8);
        var replacementProfile = changeUser ? profile : profile with { SourceRevision = Guid.NewGuid() };
        var replacement = await client.ConnectAsync(replacementProfile, Login, Token);
        Assert.Equal(oldAccount.ProfileId, replacement.Account.ProfileId);
        Assert.NotEqual(oldAccount, replacement.Account);
        Assert.NotEqual(oldOwner, Owner(replacement.Account));
        Assert.Null(await vault.ReadTokenAsync(oldAccount, Token));
        Assert.NotNull(await vault.ReadTokenAsync(replacement.Account, Token));
        Assert.Contains(oldAccount, await reopened.ListPendingLogoutsAsync(Token));
        var oldRoots = (await reopened.ListCacheRootsAsync(Token)).Where(root => root.Account == oldAccount).ToArray();
        Assert.Equal(firstSettings.CacheDirectory, Assert.Single(oldRoots).CacheRoot);
        Assert.NotEmpty(Directory.GetFiles(OwnerDirectory(firstSettings.CacheDirectory, oldAccount), "fileway-chunk-*", SearchOption.AllDirectories));
        Assert.Empty(Directory.GetFiles(OwnerDirectory(nextSettings.CacheDirectory, oldAccount), "fileway-chunk-*", SearchOption.AllDirectories));

        await using var newImage = await client.OpenAsync(Resource(replacement), Token);
        Assert.Contains(new RegisteredCacheRoot(replacement.Account, nextSettings.CacheDirectory), await reopened.ListCacheRootsAsync(Token));
        await client.LogoutAsync(replacement.Account, Token);
        Assert.Null(await vault.ReadTokenAsync(replacement.Account, Token));
        Assert.DoesNotContain(await reopened.ListCacheRootsAsync(Token), root => root.Account == replacement.Account);
        Assert.Contains(new RegisteredCacheRoot(oldAccount, firstSettings.CacheDirectory), await reopened.ListCacheRootsAsync(Token));
        Assert.Equal(oldAccount, Assert.Single(await reopened.ListPendingLogoutsAsync(Token)));
        var current = Assert.IsType<SavedConnection>(await client.GetAsync(profile.Id, Token));
        Assert.Equal(replacement.Account, current.Account);
        Assert.Null(await vault.ReadTokenAsync(oldAccount, Token));
    }

    [Fact]
    public async Task NoAuthenticationPendingAccountCannotRestoreWhileItsCacheCleanupRemainsUnavailable()
    {
        await using var fixture = new HttpFixture(request => Response(request, Png));
        using var directory = new StorageTestDirectory();
        var defaults = Settings(directory, "cache");
        var store = new InMemoryProfileStore();
        var saved = Saved(Profile(fixture)) with { AuthenticationMode = AuthenticationMode.NoAuthentication, KeepSignedIn = false };
        await store.SaveAsync(saved, Token);
        await store.BeginLogoutAsync(saved.Account, Token);
        await using var realHost = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        var host = new ObservedHost(realHost) { FailedCleanupRoot = defaults.CacheDirectory };
        await using var settings = new LocalStorageSettingsService(store, defaults, host);
        await settings.InitializeCacheAsync(Token);
        await using var services = new FilewayClientServices(host, store, new InMemoryCredentialVault(), settings);

        var failure = await Assert.ThrowsAsync<FilewayException>(() => services.RestoreAsync(saved.Profile.Id, Token));

        Assert.Equal(ErrorCode.AuthenticationExpired, failure.Error.Code);
        Assert.DoesNotContain(host.Calls, call => call.Operation is "open" or "login" or "lease" or "preview");
        Assert.Equal(0, fixture.Count);
        Assert.Equal(saved.Account, Assert.Single(await store.ListPendingLogoutsAsync(Token)));
        Assert.Equal(new RegisteredCacheRoot(saved.Account, defaults.CacheDirectory), Assert.Single(await store.ListCacheRootsAsync(Token)));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task SlowManagementRpcAllowsCachedRegistrationAndDisposeWaitsForThatRpc(bool clear)
    {
        await using var fixture = new HttpFixture(request => Response(request, Png));
        using var directory = new StorageTestDirectory();
        await using var realHost = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        var host = new ObservedHost(realHost);
        var store = new InMemoryProfileStore();
        await using var settings = new LocalStorageSettingsService(store, Settings(directory, "cache"), host);
        await using var services = new FilewayClientServices(host, store, new InMemoryCredentialVault(), settings);
        var session = await services.ConnectAsync(Profile(fixture), Login, Token);
        await settings.InitializeCacheAsync(Token);
        await using var initial = await services.AcquireCachedAsync(Resource(session), Token);
        host.BlockOperation = clear ? "cache_cleanup_owner" : "cache_stats";
        Task<LocalCacheUsage> management = clear ? settings.ClearInactiveCacheAsync(Token) : settings.ReadCacheUsageAsync(Token);
        Task? disposal = null;
        try
        {
            await host.ManagementEntered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
            await using var concurrent = await services.AcquireCachedAsync(Resource(session), Token).WaitAsync(TimeSpan.FromSeconds(5), Token);
            Assert.Contains(await store.ListCacheRootsAsync(Token), root => root.Account == session.Account);
            Assert.False(management.IsCompleted);
            disposal = settings.DisposeAsync().AsTask();
            Assert.False(disposal.IsCompleted);
            await Assert.ThrowsAsync<ObjectDisposedException>(() => settings.ReadAsync(Token));
        }
        finally { host.ReleaseManagement.TrySetResult(); }

        await management.WaitAsync(TimeSpan.FromSeconds(5), Token);
        await disposal!.WaitAsync(TimeSpan.FromSeconds(5), Token);
    }

    private static ClientStorageSettings Settings(StorageTestDirectory directory, string name) =>
        new(Path.Combine(directory.DirectoryPath, name), Path.Combine(directory.DirectoryPath, "downloads"));
    private static ConnectionProfile Profile(HttpFixture fixture) => new(Guid.NewGuid(), Guid.NewGuid(), "图片及缓存根测试",
        new Uri(fixture.BaseUrl + "/nas"), NetworkMode.SystemNetwork, new ProxyPolicy(ProxyMode.Direct));
    private static SavedConnection Saved(ConnectionProfile profile) => new(profile, AuthenticationMode.Password, "fixture.user", "7", true, DateTimeOffset.UtcNow);
    private static RemoteResourceRef Resource(ConnectionSession session) => new(session.Account, WirePath, "图片.png", "/图片.png");
    private static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value))).ToLowerInvariant();
    private static string Owner(AccountKey account) => Hash(JsonSerializer.Serialize(new
    { profileId = account.ProfileId, sourceRevision = account.SourceRevision, serverUserId = account.ServerUserId }));
    private static string OwnerDirectory(string root, AccountKey account) => Path.Combine(root, "fileway-cache-v1", Hash(Owner(account)));

    private static string SeedOldBlock(string root, AccountKey account, string group)
    {
        // A representative pre-ledger managed block; personal/unknown names are deliberately different.
        string location = Path.Combine(OwnerDirectory(root, account), Hash(group));
        Directory.CreateDirectory(location);
        string path = Path.Combine(location, "fileway-chunk-" + Hash("legacy-source") + "-0-4");
        File.WriteAllBytes(path, [1, 2, 3, 4]);
        return path;
    }

    private static async Task<byte[]> ReadLeaseAsync(IResourceLease lease)
    {
        using var http = new HttpClient(new SocketsHttpHandler { UseProxy = false, AllowAutoRedirect = false });
        return await http.GetByteArrayAsync(lease.Address, Token);
    }

    private static List<FixtureRequest> Drain(HttpFixture fixture)
    {
        var requests = new List<FixtureRequest>();
        while (fixture.Seen.Reader.TryRead(out var request)) requests.Add(request);
        return requests;
    }

    private static FixtureResponse Response(FixtureRequest request, byte[] payload, string mime = "image/png", int userId = 7)
    {
        if (request.Target == "/nas/api/login")
        {
            string encoded = Convert.ToBase64String(JsonSerializer.SerializeToUtf8Bytes(new { user = new { id = userId } }))
                .TrimEnd('=').Replace('+', '-').Replace('/', '_');
            return new FixtureResponse(200, Encoding.UTF8.GetBytes("fixture." + encoded + ".signature"), "text/plain");
        }
        if (request.Target == "/nas/api/users/" + userId.ToString(CultureInfo.InvariantCulture))
            return new FixtureResponse(200, JsonSerializer.SerializeToUtf8Bytes(new { id = userId, username = "fixture.user", perm = new { download = true } }));
        if (request.Target == "/nas/api/client-capabilities")
            return new FixtureResponse(200, JsonSerializer.SerializeToUtf8Bytes(new { authMethod = "json", resourceWireOperations = true }));
        if (request.Target == "/nas/api/resources" + WirePath + "?metadata=1")
            return new FixtureResponse(200, JsonSerializer.SerializeToUtf8Bytes(new { name = "图片.png", path = "/图片.png", wirePath = WirePath,
                isDir = false, size = payload.Length, modified = "2026-10-11T01:02:03Z" }));
        if (request.Target is RawTarget or PreviewTarget)
        {
            var headers = new Dictionary<string, string> { ["Accept-Ranges"] = "bytes", ["ETag"] = "\"image-v1\"" };
            if (request.Headers.TryGetValue("Range", out string? range))
            {
                string[] bounds = range["bytes=".Length..].Split('-');
                int first = int.Parse(bounds[0], CultureInfo.InvariantCulture);
                int last = int.Parse(bounds[1], CultureInfo.InvariantCulture);
                headers["Content-Range"] = $"bytes {first}-{last}/{payload.Length}";
                return new FixtureResponse(206, payload[first..(last + 1)], mime, headers);
            }
            return new FixtureResponse(200, payload, mime, headers);
        }
        return new FixtureResponse(404, []);
    }

    private sealed class ObservedHost(IHostControlClient inner) : IHostControlClient
    {
        private int _blocked;
        internal ConcurrentQueue<HostCall> Calls { get; } = new();
        internal string? FailedCleanupRoot { get; set; }
        internal string? BlockOperation { get; set; }
        internal TaskCompletionSource ManagementEntered { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal TaskCompletionSource ReleaseManagement { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public HostHandshake Handshake => inner.Handshake;
        public Task<HostExit> Completion => inner.Completion;

        public async Task<HostReply> CallAsync(HostCall request, CancellationToken cancellationToken)
        {
            Calls.Enqueue(request);
            if (request.Operation == "cache_cleanup_owner" && FailedCleanupRoot is { } failed &&
                request.Parameters!.Value.GetProperty("cacheRoot").GetString() == failed)
                throw new IOException("fixture cache volume unavailable");
            if (request.Operation == BlockOperation && Interlocked.Exchange(ref _blocked, 1) == 0)
            {
                ManagementEntered.TrySetResult();
                await ReleaseManagement.Task.WaitAsync(TimeSpan.FromSeconds(10), cancellationToken);
            }
            return await inner.CallAsync(request, cancellationToken);
        }

        public Task ShutdownAsync(CancellationToken cancellationToken) => inner.ShutdownAsync(cancellationToken);
        public ValueTask DisposeAsync() => ValueTask.CompletedTask;
    }

    private sealed class FailingRegistry(InMemoryProfileStore inner) : IProfileStore, ICacheRootStore
    {
        public Task<IReadOnlyList<SavedConnection>> ListAsync(CancellationToken cancellationToken) => inner.ListAsync(cancellationToken);
        public Task<SavedConnection?> GetAsync(Guid profileId, CancellationToken cancellationToken) => inner.GetAsync(profileId, cancellationToken);
        public Task SaveAsync(SavedConnection connection, CancellationToken cancellationToken) => inner.SaveAsync(connection, cancellationToken);
        public Task ForgetAsync(AccountKey account, CancellationToken cancellationToken) => inner.ForgetAsync(account, cancellationToken);
        public Task<IReadOnlyList<AccountKey>> ListPendingLogoutsAsync(CancellationToken cancellationToken) => inner.ListPendingLogoutsAsync(cancellationToken);
        public Task BeginLogoutAsync(AccountKey account, CancellationToken cancellationToken) => inner.BeginLogoutAsync(account, cancellationToken);
        public Task CompleteLogoutAsync(AccountKey account, CancellationToken cancellationToken) => inner.CompleteLogoutAsync(account, cancellationToken);
        public Task<IReadOnlyList<RegisteredCacheRoot>> ListCacheRootsAsync(CancellationToken cancellationToken) => inner.ListCacheRootsAsync(cancellationToken);
        public Task RegisterCacheRootAsync(AccountKey account, string cacheRoot, CancellationToken cancellationToken) => throw new IOException("fixture registry unavailable");
        public Task RegisterCacheRootForCleanupAsync(AccountKey account, string cacheRoot, CancellationToken cancellationToken) => inner.RegisterCacheRootForCleanupAsync(account, cacheRoot, cancellationToken);
        public Task RemoveCacheRootAsync(AccountKey account, string cacheRoot, CancellationToken cancellationToken) => inner.RemoveCacheRootAsync(account, cacheRoot, cancellationToken);
    }
}
