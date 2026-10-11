using System.Collections.Concurrent;
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

public sealed class MediaCacheTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;
    private static readonly LoginInput Login = new(AuthenticationMode.Password, "fixture.user", "fixture.password");
    private static readonly byte[] Media = Enumerable.Range(0, 16).Select(value => (byte)value).ToArray();
    private const string WirePath = "/%FF%252F.mp4";

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task CacheIdentityUsesAuthenticatedMetadataAndAccountRatherThanCallerAliases(bool hasModificationTime)
    {
        await using var fixture = new HttpFixture(request => Response(request, hasModificationTime));
        using var directory = new StorageTestDirectory();
        await using var realHost = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        var host = new RecordingHost(realHost);
        var store = new InMemoryProfileStore();
        await using var settings = new LocalStorageSettingsService(store, Settings(directory), host);
        await using var services = new FilewayClientServices(host, store, new InMemoryCredentialVault(), settings);
        var profile = Profile(fixture);
        var first = await services.ConnectAsync(profile, Login, Token);
        await using var one = await services.AcquireCachedAsync(Resource(first, "caller-version-one"), Token);
        await using var two = await services.AcquireCachedAsync(new RemoteResourceRef(first.Account, WirePath, "另一个显示名", "/另一个显示名", "caller-version-two"), Token);

        var firstCalls = LeaseCalls(host);
        Assert.Equal(Key(firstCalls[0]), Key(firstCalls[1]));
        Assert.Equal(Owner(firstCalls[0]), Owner(firstCalls[1]));
        Assert.Matches("^[a-f0-9]{64}$", Key(firstCalls[0]));
        Assert.Matches("^[a-f0-9]{64}$", Owner(firstCalls[0]));
        Assert.Equal(WirePath, firstCalls[0].Parameters!.Value.GetProperty("wirePath").GetString());

        var reconnected = await services.ConnectAsync(profile, Login, Token);
        await using var three = await services.AcquireCachedAsync(Resource(reconnected), Token);
        var reconnectedCall = LeaseCalls(host)[2];
        Assert.Equal(Owner(firstCalls[0]), Owner(reconnectedCall));
        if (hasModificationTime) Assert.Equal(Key(firstCalls[0]), Key(reconnectedCall));
        else Assert.NotEqual(Key(firstCalls[0]), Key(reconnectedCall));

        var otherAccount = await services.ConnectAsync(profile with { Id = Guid.NewGuid() }, Login, Token);
        await using var four = await services.AcquireCachedAsync(Resource(otherAccount), Token);
        var otherCall = LeaseCalls(host)[3];
        Assert.NotEqual(Owner(firstCalls[0]), Owner(otherCall));
        Assert.NotEqual(Key(firstCalls[0]), Key(otherCall));
        Assert.All(LeaseCalls(host), call => Assert.NotNull(call.Session));
    }

    [Fact]
    public async Task CachedReadsAndStatisticsAreLeaseBoundAndDoNotCrossAccounts()
    {
        await using var fixture = new HttpFixture(request => Response(request));
        using var directory = new StorageTestDirectory();
        await using var realHost = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        var host = new RecordingHost(realHost);
        var store = new InMemoryProfileStore();
        await using var settings = new LocalStorageSettingsService(store, Settings(directory), host);
        await using var services = new FilewayClientServices(host, store, new InMemoryCredentialVault(), settings);
        var profile = Profile(fixture);
        var first = await services.ConnectAsync(profile, Login, Token);
        await using var cold = await services.AcquireCachedAsync(Resource(first), Token);
        Assert.Equal(Media, await ReadMediaAsync(cold));
        var coldStats = await CompletedStatisticsAsync(cold);
        Assert.Equal(Media.Length, coldStats.UpstreamBytes);
        Assert.Equal(0, coldStats.CacheReadBytes);

        await using var warm = await services.AcquireCachedAsync(Resource(first), Token);
        var beforeRead = Assert.IsType<ResourceLeaseStatistics>(await Statistics(warm).ReadStatisticsAsync(Token));
        Assert.Equal(0, beforeRead.UpstreamBytes);
        Assert.Equal(0, beforeRead.CacheReadBytes);
        Assert.Equal(0, beforeRead.DeliveredBytes);
        Assert.Equal(Media, await ReadMediaAsync(warm));
        var warmStats = await CompletedStatisticsAsync(warm);
        Assert.Equal(0, warmStats.UpstreamBytes);
        Assert.Equal(Media.Length, warmStats.CacheReadBytes);
        Assert.True(warmStats.Elapsed >= TimeSpan.Zero);

        var second = await services.ConnectAsync(profile with { Id = Guid.NewGuid() }, Login, Token);
        int statsCallsBeforeRetiredRead = host.Calls.Count(call => call.Operation == "lease_stats");
        Assert.Null(await Statistics(warm).ReadStatisticsAsync(Token));
        Assert.Equal(statsCallsBeforeRetiredRead, host.Calls.Count(call => call.Operation == "lease_stats"));
        await using var isolated = await services.AcquireCachedAsync(Resource(second), Token);
        Assert.Equal(Media, await ReadMediaAsync(isolated));
        var isolatedStats = await CompletedStatisticsAsync(isolated);
        Assert.Equal(Media.Length, isolatedStats.UpstreamBytes);
        Assert.Equal(0, isolatedStats.CacheReadBytes);

        var isolatedCall = LeaseCalls(host)[2];
        Assert.All(host.Calls.Where(call => call.Operation == "lease_stats" &&
            call.Parameters!.Value.GetProperty("url").GetString() == isolated.Address.AbsoluteUri),
            call => Assert.Equal(isolatedCall.Session, call.Session));
        await isolated.DisposeAsync();
        Assert.Null(await Statistics(isolated).ReadStatisticsAsync(Token));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task UnavailableCacheFallsBackToOrdinaryLeasesAndWarnsOnlyOnce(bool configuredService)
    {
        await using var fixture = new HttpFixture(request => Response(request));
        using var directory = new StorageTestDirectory();
        await using var realHost = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        var host = new RecordingHost(realHost) { FailConfiguration = true };
        var store = new InMemoryProfileStore();
        await using var settings = new LocalStorageSettingsService(store, Settings(directory), host);
        await using var services = new FilewayClientServices(host, store, new InMemoryCredentialVault(), configuredService ? settings : null);
        var issues = new List<ConnectionIssue>();
        services.ConnectionIssue += (_, issue) => issues.Add(issue);
        var session = await services.ConnectAsync(Profile(fixture), Login, Token);
        await using var first = await services.AcquireCachedAsync(Resource(session), Token);
        await using var second = await services.AcquireCachedAsync(Resource(session), Token);

        Assert.Equal(Media, await ReadMediaAsync(first));
        var warning = Assert.Single(issues);
        Assert.Equal(ErrorCode.LocalStorageUnavailable, warning.Error.Code);
        Assert.DoesNotContain(RecordingHost.PrivateFailure, warning.Error.Message);
        Assert.All(LeaseCalls(host), call =>
        {
            Assert.False(call.Parameters!.Value.TryGetProperty("cacheKey", out _));
            Assert.False(call.Parameters.Value.TryGetProperty("cacheOwner", out _));
        });
        Assert.False(Directory.Exists(Path.Combine(Settings(directory).CacheDirectory, "fileway-cache-v1")));
        var statistics = await CompletedStatisticsAsync(first);
        Assert.Equal(Media.Length, statistics.UpstreamBytes);
        Assert.Equal(0, statistics.CacheReadBytes);
    }

    [Fact]
    public async Task ConfirmedCapacitySaveSurvivesHostFailureAndRetriesBeforeNextCachedLease()
    {
        await using var fixture = new HttpFixture(request => Response(request));
        using var directory = new StorageTestDirectory();
        await using var realHost = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        var host = new RecordingHost(realHost);
        var store = new InMemoryProfileStore();
        var defaults = Settings(directory);
        await using var settings = new LocalStorageSettingsService(store, defaults, host);
        await using var services = new FilewayClientServices(host, store, new InMemoryCredentialVault(), settings);
        var session = await services.ConnectAsync(Profile(fixture), Login, Token);
        await using var initial = await services.AcquireCachedAsync(Resource(session), Token);
        host.FailConfiguration = true;

        var saved = await settings.SaveAsync(defaults with { MaximumCacheBytes = 0 }, Token);

        Assert.Equal(defaults, saved.Active);
        Assert.Equal(0, saved.Pending.MaximumCacheBytes);
        Assert.True(saved.RestartRequired);
        Assert.Equal(saved.Pending, await store.ReadStorageSettingsAsync(Token));
        host.FailConfiguration = false;
        await using var retried = await services.AcquireCachedAsync(Resource(session), Token);
        var active = await settings.ReadAsync(Token);
        Assert.Equal(saved.Pending, active.Active);
        Assert.Equal(active.Active, active.Pending);
        Assert.False(active.RestartRequired);
        Assert.Equal(0, host.Calls.Last(call => call.Operation == "cache_configure").Parameters!.Value
            .GetProperty("cacheConfig").GetProperty("maxBytes").GetInt64());
        Assert.Equal(Media, await ReadMediaAsync(retried));
        Assert.Equal(0, (await CompletedStatisticsAsync(retried)).CacheReadBytes);
    }

    [Fact]
    public async Task CacheConfigurationCancellationDoesNotSilentlyAcquireAnUncachedLease()
    {
        await using var fixture = new HttpFixture(request => Response(request));
        using var directory = new StorageTestDirectory();
        await using var realHost = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        var host = new RecordingHost(realHost) { CancelConfiguration = true };
        var store = new InMemoryProfileStore();
        await using var settings = new LocalStorageSettingsService(store, Settings(directory), host);
        await using var services = new FilewayClientServices(host, store, new InMemoryCredentialVault(), settings);
        var issues = new List<ConnectionIssue>();
        services.ConnectionIssue += (_, issue) => issues.Add(issue);
        var session = await services.ConnectAsync(Profile(fixture), Login, Token);

        var error = await Assert.ThrowsAsync<FilewayException>(() => services.AcquireCachedAsync(Resource(session), Token));

        Assert.Equal(ErrorCode.Canceled, error.Error.Code);
        Assert.Empty(LeaseCalls(host));
        Assert.Empty(issues);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task FailedOwnerCleanupKeepsLogoutIntentWithoutBlockingOtherProfilesOrResurrectingCredentials(bool forget)
    {
        await using var fixture = new HttpFixture(request => Response(request));
        using var directory = new StorageTestDirectory();
        await using var realHost = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        var host = new RecordingHost(realHost);
        var store = new InMemoryProfileStore();
        var vault = new InMemoryCredentialVault();
        await using var settings = new LocalStorageSettingsService(store, Settings(directory), host);
        await using var services = new FilewayClientServices(host, store, vault, settings);
        var persistentLogin = Login with { KeepSignedIn = true };
        var healthyProfile = Profile(fixture);
        var healthy = await services.ConnectAsync(healthyProfile, persistentLogin, Token);
        var targetProfile = Profile(fixture);
        var target = await services.ConnectAsync(targetProfile, persistentLogin, Token);
        Assert.NotNull(await vault.ReadTokenAsync(healthy.Account, Token));
        Assert.NotNull(await vault.ReadTokenAsync(target.Account, Token));
        await using var lease = await services.AcquireCachedAsync(Resource(target), Token);
        host.FailedCleanupOwner = Owner(Assert.Single(LeaseCalls(host)));

        var failure = await Assert.ThrowsAsync<FilewayException>(() => forget
            ? services.ForgetAsync(targetProfile.Id, Token)
            : services.LogoutAsync(target.Account, Token));

        Assert.Equal(ErrorCode.LocalStorageUnavailable, failure.Error.Code);
        Assert.DoesNotContain(RecordingHost.PrivateFailure, failure.Error.Message);
        Assert.Null(await vault.ReadTokenAsync(target.Account, Token));
        Assert.Equal(target.Account, Assert.Single(await store.ListPendingLogoutsAsync(Token)));
        Assert.False(Assert.IsType<SavedConnection>(await store.GetAsync(targetProfile.Id, Token)).KeepSignedIn);
        Assert.Contains(await services.ListAsync(Token), saved => saved.Account == healthy.Account);
        Assert.Equal(healthy.Account, Assert.IsType<SavedConnection>(await services.GetAsync(healthyProfile.Id, Token)).Account);
        var other = await services.ConnectAsync(Profile(fixture), persistentLogin, Token);
        Assert.NotEqual(target.Account, other.Account);
        Assert.Equal(healthy.Account, (await services.RestoreAsync(healthyProfile.Id, Token)).Account);

        int opensBeforeRejectedRestore = host.Calls.Count(call => call.Operation == "open");
        var restoreFailure = await Assert.ThrowsAsync<FilewayException>(() => services.RestoreAsync(targetProfile.Id, Token));
        Assert.Equal(ErrorCode.AuthenticationExpired, restoreFailure.Error.Code);
        Assert.Equal(opensBeforeRejectedRestore, host.Calls.Count(call => call.Operation == "open"));
        var reconnectFailure = await Assert.ThrowsAsync<FilewayException>(() => services.ConnectAsync(targetProfile, persistentLogin, Token));
        Assert.Equal(ErrorCode.LocalStorageUnavailable, reconnectFailure.Error.Code);
        Assert.Null(await vault.ReadTokenAsync(target.Account, Token));
        Assert.Equal(target.Account, Assert.Single(await store.ListPendingLogoutsAsync(Token)));
        Assert.False(Assert.IsType<SavedConnection>(await store.GetAsync(targetProfile.Id, Token)).KeepSignedIn);

        host.FailedCleanupOwner = null;
        _ = await services.ListAsync(Token);
        Assert.Empty(await store.ListPendingLogoutsAsync(Token));
        Assert.Null(await vault.ReadTokenAsync(target.Account, Token));
    }

    [Fact]
    public async Task PendingRecoveryContinuesToLaterAccountsAfterFirstOwnerCacheCleanupFails()
    {
        await using var fixture = new HttpFixture(request => Response(request));
        using var directory = new StorageTestDirectory();
        await using var realHost = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        var host = new RecordingHost(realHost);
        var store = new SqliteProfileStore(directory.DatabasePath);
        var vault = new InMemoryCredentialVault();
        await using var settings = new LocalStorageSettingsService(store, Settings(directory), host);
        await using var services = new FilewayClientServices(host, store, vault, settings);
        var persistentLogin = Login with { KeepSignedIn = true };
        var firstProfile = Profile(fixture) with { Id = Guid.Parse("00000000-0000-0000-0000-000000000001") };
        var secondProfile = Profile(fixture) with { Id = Guid.Parse("00000000-0000-0000-0000-000000000002") };
        var first = await services.ConnectAsync(firstProfile, persistentLogin, Token);
        await using var firstLease = await services.AcquireCachedAsync(Resource(first), Token);
        var second = await services.ConnectAsync(secondProfile, persistentLogin, Token);
        await using var secondLease = await services.AcquireCachedAsync(Resource(second), Token);
        Assert.NotNull(await vault.ReadTokenAsync(first.Account, Token));
        Assert.NotNull(await vault.ReadTokenAsync(second.Account, Token));
        var calls = LeaseCalls(host);
        string firstOwner = Owner(calls[0]);
        string secondOwner = Owner(calls[1]);
        host.FailedCleanupOwner = firstOwner;
        await store.BeginLogoutAsync(first.Account, Token);
        await store.BeginLogoutAsync(second.Account, Token);
        Assert.Equal(new[] { first.Account, second.Account }, await store.ListPendingLogoutsAsync(Token));

        var saved = await services.ListAsync(Token);

        Assert.Equal(2, saved.Count);
        Assert.All(saved, connection => Assert.False(connection.KeepSignedIn));
        Assert.Null(await vault.ReadTokenAsync(first.Account, Token));
        Assert.Null(await vault.ReadTokenAsync(second.Account, Token));
        Assert.Equal(first.Account, Assert.Single(await store.ListPendingLogoutsAsync(Token)));
        Assert.Collection(host.Calls.Where(call => call.Operation == "cache_cleanup_owner"),
            call => Assert.Equal(firstOwner, call.Parameters!.Value.GetProperty("cacheOwner").GetString()),
            call => Assert.Equal(secondOwner, call.Parameters!.Value.GetProperty("cacheOwner").GetString()));

        host.FailedCleanupOwner = null;
        _ = await services.ListAsync(Token);
        Assert.Empty(await store.ListPendingLogoutsAsync(Token));
    }

    private static ClientStorageSettings Settings(StorageTestDirectory directory) =>
        new(Path.Combine(directory.DirectoryPath, "cache"), Path.Combine(directory.DirectoryPath, "downloads"));

    private static ConnectionProfile Profile(HttpFixture fixture) => new(Guid.NewGuid(), Guid.NewGuid(), "缓存测试",
        new Uri(fixture.BaseUrl + "/nas"), NetworkMode.SystemNetwork, new ProxyPolicy(ProxyMode.Direct));

    private static RemoteResourceRef Resource(ConnectionSession session, string? version = null) =>
        new(session.Account, WirePath, "视频.mp4", "/视频.mp4", version);

    private static IResourceLeaseStatisticsSource Statistics(IResourceLease lease) => Assert.IsAssignableFrom<IResourceLeaseStatisticsSource>(lease);
    private static HostCall[] LeaseCalls(RecordingHost host) => host.Calls.Where(call => call.Operation == "lease").ToArray();
    private static string Key(HostCall call) => call.Parameters!.Value.GetProperty("cacheKey").GetString()!;
    private static string Owner(HostCall call) => call.Parameters!.Value.GetProperty("cacheOwner").GetString()!;

    private static async Task<byte[]> ReadMediaAsync(IResourceLease lease)
    {
        using var http = new HttpClient(new SocketsHttpHandler { UseProxy = false, AllowAutoRedirect = false });
        return await http.GetByteArrayAsync(lease.Address, Token);
    }

    private static async Task<ResourceLeaseStatistics> CompletedStatisticsAsync(IResourceLease lease)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(Token);
        timeout.CancelAfter(TimeSpan.FromSeconds(5));
        while (true)
        {
            var statistics = Assert.IsType<ResourceLeaseStatistics>(await Statistics(lease).ReadStatisticsAsync(timeout.Token));
            if (statistics.DeliveredBytes == Media.Length) return statistics;
            await Task.Delay(10, timeout.Token);
        }
    }

    private static FixtureResponse Response(FixtureRequest request, bool hasModificationTime = true)
    {
        if (request.Target == "/nas/api/login")
        {
            string payload = Convert.ToBase64String(Encoding.UTF8.GetBytes("{\"user\":{\"id\":7}}"))
                .TrimEnd('=').Replace('+', '-').Replace('/', '_');
            return new FixtureResponse(200, Encoding.UTF8.GetBytes("fixture." + payload + ".signature"), "text/plain");
        }
        if (request.Target == "/nas/api/users/7")
            return Json(new { id = 7, username = "缓存测试用户", perm = new { download = true } });
        if (request.Target == "/nas/api/client-capabilities")
            return Json(new { authMethod = "json", resourceWireOperations = true });
        if (request.Target == "/nas/api/resources" + WirePath + "?metadata=1")
            return Json(new { name = "视频.mp4", path = "/视频.mp4", wirePath = WirePath, isDir = false, size = Media.Length,
                modified = hasModificationTime ? "2026-10-11T01:02:03Z" : null });
        if (request.Target == "/nas/api/raw" + WirePath)
        {
            var headers = new Dictionary<string, string> { ["Accept-Ranges"] = "bytes", ["ETag"] = "\"fixture-media-v1\"" };
            if (request.Headers.TryGetValue("Range", out string? range))
            {
                Assert.Equal("bytes=0-15", range);
                headers["Content-Range"] = "bytes 0-15/16";
                return new FixtureResponse(206, Media, "video/mp4", headers);
            }
            return new FixtureResponse(200, Media, "video/mp4", headers);
        }
        return new FixtureResponse(404, []);
    }

    private static FixtureResponse Json(object value) => new(200, JsonSerializer.SerializeToUtf8Bytes(value));

    // Observe the public Host boundary while keeping broker/cache behavior real.
    private sealed class RecordingHost(IHostControlClient inner) : IHostControlClient
    {
        internal const string PrivateFailure = "fixture-private-cache-path-failure";
        internal ConcurrentQueue<HostCall> Calls { get; } = new();
        internal bool FailConfiguration { get; set; }
        internal bool CancelConfiguration { get; set; }
        internal string? FailedCleanupOwner { get; set; }
        public HostHandshake Handshake => inner.Handshake;
        public Task<HostExit> Completion => inner.Completion;

        public Task<HostReply> CallAsync(HostCall request, CancellationToken cancellationToken)
        {
            Calls.Enqueue(request);
            if (request.Operation == "cache_configure")
            {
                if (CancelConfiguration) throw new OperationCanceledException(cancellationToken);
                if (FailConfiguration) throw new IOException(PrivateFailure);
            }
            if (request.Operation == "cache_cleanup_owner" && FailedCleanupOwner is { } failedOwner &&
                request.Parameters!.Value.GetProperty("cacheOwner").GetString() == failedOwner)
                throw new IOException(PrivateFailure);
            return inner.CallAsync(request, cancellationToken);
        }

        public Task ShutdownAsync(CancellationToken cancellationToken) => inner.ShutdownAsync(cancellationToken);
        public ValueTask DisposeAsync() => ValueTask.CompletedTask;
    }
}
