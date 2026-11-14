using System.Text;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Ipc;
using Fileway.Infrastructure.Services;
using Fileway.Infrastructure.Tests.Ipc;

namespace Fileway.Infrastructure.Tests.Services;

public sealed class PlaybackProgressTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;
    private static readonly LoginInput PasswordLogin = new(AuthenticationMode.Password, "fixture.user", "fixture.password");

    private static ConnectionProfile Profile(HttpFixture fixture) => new(Guid.NewGuid(), Guid.NewGuid(), "播放进度测试",
        new Uri(fixture.BaseUrl + "/nas"), NetworkMode.SystemNetwork, new ProxyPolicy(ProxyMode.Direct));

    private static string Jwt() => "fixture." + Convert.ToBase64String(Encoding.UTF8.GetBytes(
        JsonSerializer.Serialize(new { user = new { id = 7 } })))
        .TrimEnd('=').Replace('+', '-').Replace('/', '_') + ".signature";

    private static FixtureResponse Json(object value) => new(200, JsonSerializer.SerializeToUtf8Bytes(value));

    private static FixtureResponse AccountResponse(FixtureRequest request, string resumeMode = "resume", int resumeMinSec = 10) =>
        request.Target switch
        {
            "/nas/api/login" => new FixtureResponse(200, Encoding.UTF8.GetBytes(Jwt()), "text/plain"),
            "/nas/api/users/7" => Json(new
            {
                id = 7,
                username = "播放测试用户",
                perm = new { download = true },
                playerPreferences = new { resumeMode, resumeMinSec },
            }),
            "/nas/api/client-capabilities" => Json(new { authMethod = "json", resourceWireOperations = true }),
            _ => new FixtureResponse(404, []),
        };

    private static List<FixtureRequest> PlaybackRequests(HttpFixture fixture)
    {
        var requests = new List<FixtureRequest>();
        while (fixture.Seen.Reader.TryRead(out var request))
            if (request.Target.StartsWith("/nas/api/media/playback", StringComparison.Ordinal)) requests.Add(request);
        return requests;
    }

    [Fact]
    public async Task ReadWriteAndClearPreserveWireIdentityAndRefreshResumePreferences()
    {
        const string wirePath = "/%E7%94%B5%E5%BD%B1%F0%9F%8E%AC/100%25-%252F-%23+&=.mp4";
        const string displayPath = "/电影🎬/100%-%2F-#+&=.mp4";
        const string target = "/nas/api/media/playback?path=%2F%E7%94%B5%E5%BD%B1%F0%9F%8E%AC%2F100%25-%252F-%23%2B%26%3D.mp4";
        int accountReads = 0;
        await using var fixture = new HttpFixture(request =>
        {
            if (request.Target == "/nas/api/users/7")
                return Interlocked.Increment(ref accountReads) == 1
                    ? AccountResponse(request, "from-start", 5)
                    : AccountResponse(request, "ask", 25);
            if (request.Target == target && request.Method == "GET")
                return Json(new { exists = true, identity = "fixture.media.identity", position = 42.25, duration = 600.5 });
            if (request.Target == "/nas/api/media/playback" && request.Method == "PUT" ||
                request.Target == target && request.Method == "DELETE") return Json(new { });
            return AccountResponse(request);
        });
        await using var host = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(Profile(fixture), PasswordLogin, Token);
        var resource = new RemoteResourceRef(session.Account, wirePath, "100%-%2F-#+&=.mp4", displayPath);

        var bookmark = Assert.IsType<PlaybackBookmark>(await services.ReadAsync(resource, Token));
        Assert.Equal(TimeSpan.FromSeconds(42.25), bookmark.Position);
        Assert.Equal(TimeSpan.FromSeconds(600.5), bookmark.Duration);
        Assert.Equal("fixture.media.identity", bookmark.SourceIdentity);
        Assert.Equal(PlaybackResumeMode.Ask, bookmark.Mode);
        Assert.Equal(TimeSpan.FromSeconds(25), bookmark.MinimumPosition);
        Assert.Equal(2, accountReads);
        await services.WriteAsync(resource, TimeSpan.FromSeconds(81.125), TimeSpan.FromSeconds(610.75), Token);
        await services.ClearAsync(resource, Token);

        Assert.Collection(PlaybackRequests(fixture),
            request =>
            {
                Assert.Equal("GET", request.Method);
                Assert.Equal(target, request.Target);
                Assert.Empty(request.Body);
            },
            request =>
            {
                Assert.Equal("PUT", request.Method);
                Assert.Equal("/nas/api/media/playback", request.Target);
                using var body = JsonDocument.Parse(request.Body);
                Assert.Equal(displayPath, body.RootElement.GetProperty("path").GetString());
                Assert.Equal(81.125, body.RootElement.GetProperty("position").GetDouble());
                Assert.Equal(610.75, body.RootElement.GetProperty("duration").GetDouble());
            },
            request =>
            {
                Assert.Equal("DELETE", request.Method);
                Assert.Equal(target, request.Target);
                Assert.Empty(request.Body);
            });
    }

    [Fact]
    public async Task ReconnectingSameAccountRejectsOldResourceAndAllowsNewResourceToWrite()
    {
        const string target = "/nas/api/media/playback?path=%2Fvideo.mp4";
        await using var fixture = new HttpFixture(request =>
            request.Target == target && request.Method == "GET"
                ? Json(new { exists = true, identity = "fixture.media.identity", position = 12, duration = 100 })
                : request.Target == "/nas/api/media/playback" && request.Method == "PUT"
                    ? Json(new { }) : AccountResponse(request));
        await using var host = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        await using var services = new FilewayClientServices(host);
        var profile = Profile(fixture);
        var first = await services.ConnectAsync(profile, PasswordLogin, Token);
        var oldResource = new RemoteResourceRef(first.Account, "/video.mp4", "video.mp4", "/video.mp4");
        _ = await services.ReadAsync(oldResource, Token);
        var second = await services.ConnectAsync(profile, PasswordLogin, Token);
        Assert.Equal(first.Account, second.Account);
        Assert.NotEqual(first.Id, second.Id);

        int beforeRejectedWrite = fixture.Count;
        var error = await Assert.ThrowsAsync<FilewayException>(() => services.WriteAsync(oldResource,
            TimeSpan.FromSeconds(18), TimeSpan.FromSeconds(100), Token));
        Assert.Equal(ErrorCode.SourceChanged, error.Error.Code);
        Assert.Equal(beforeRejectedWrite, fixture.Count);
        var newResource = new RemoteResourceRef(second.Account, "/video.mp4", "video.mp4", "/video.mp4");
        await services.WriteAsync(newResource, TimeSpan.FromSeconds(19), TimeSpan.FromSeconds(100), Token);

        var requests = PlaybackRequests(fixture);
        Assert.Equal(2, requests.Count);
        var write = Assert.Single(requests, request => request.Method == "PUT");
        Assert.Equal("/nas/api/media/playback", write.Target);
        using var body = JsonDocument.Parse(write.Body);
        Assert.Equal("/video.mp4", body.RootElement.GetProperty("path").GetString());
        Assert.Equal(19, body.RootElement.GetProperty("position").GetDouble());
    }

    [Fact]
    public async Task ReconnectingRejectsListedResourceBeforeAnyProgressReadOrMediaAcquisition()
    {
        await using var fixture = new HttpFixture(request => request.Target switch
        {
            "/nas/api/resources/" => Json(new
            {
                name = "",
                path = "/",
                wirePath = "/",
                isDir = true,
                size = 0,
                items = new[]
                {
                    new { name = "video.mp4", path = "/video.mp4", wirePath = "/video.mp4", isDir = false, size = 100 },
                },
            }),
            "/nas/api/media/playback" when request.Method == "PUT" => Json(new { }),
            _ => AccountResponse(request),
        });
        await using var host = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        await using var services = new FilewayClientServices(host);
        var repository = services;
        var profile = Profile(fixture);
        var first = await services.ConnectAsync(profile, PasswordLogin, Token);
        var oldRoot = await repository.GetRootAsync(first, Token);
        var oldResource = Assert.Single((await repository.ListAsync(oldRoot, new DirectoryQuery(), Token)).Items).Resource;
        var second = await services.ConnectAsync(profile, PasswordLogin, Token);
        Assert.Equal(first.Account, second.Account);

        int beforeRejectedOperations = fixture.Count;
        var writeError = await Assert.ThrowsAsync<FilewayException>(() => services.WriteAsync(oldResource,
            TimeSpan.FromSeconds(18), TimeSpan.FromSeconds(100), Token));
        Assert.Equal(ErrorCode.SourceChanged, writeError.Error.Code);
        Assert.Equal(beforeRejectedOperations, fixture.Count);
        var acquireError = await Assert.ThrowsAsync<FilewayException>(() => services.AcquireAsync(oldResource, Token));
        Assert.Equal(ErrorCode.SourceChanged, acquireError.Error.Code);
        Assert.Equal(beforeRejectedOperations, fixture.Count);

        var newRoot = await repository.GetRootAsync(second, Token);
        var newResource = Assert.Single((await repository.ListAsync(newRoot, new DirectoryQuery(), Token)).Items).Resource;
        Assert.Equal(oldResource, newResource);
        Assert.NotSame(oldResource, newResource);
        await services.WriteAsync(newResource, TimeSpan.FromSeconds(19), TimeSpan.FromSeconds(100), Token);

        var write = Assert.Single(PlaybackRequests(fixture));
        Assert.Equal("PUT", write.Method);
        Assert.Equal("/nas/api/media/playback", write.Target);
        using var body = JsonDocument.Parse(write.Body);
        Assert.Equal("/video.mp4", body.RootElement.GetProperty("path").GetString());
        Assert.Equal(19d, body.RootElement.GetProperty("position").GetDouble());
    }

    [Fact]
    public async Task OpaqueNonUtf8PathCanBeReadButCannotWriteDisplayNameAlias()
    {
        const string target = "/nas/api/media/playback?path=%2F%FF%25%23.mp4";
        await using var fixture = new HttpFixture(request =>
            request.Target == target && request.Method == "GET"
                ? Json(new { exists = true, identity = "fixture.opaque.identity", position = 15, duration = 120 })
                : AccountResponse(request));
        await using var host = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(Profile(fixture), PasswordLogin, Token);
        var resource = new RemoteResourceRef(session.Account, "/%FF%25%23.mp4", "显示文件.mp4", "/显示文件.mp4");

        var bookmark = Assert.IsType<PlaybackBookmark>(await services.ReadAsync(resource, Token));
        Assert.Equal(TimeSpan.FromSeconds(15), bookmark.Position);
        int beforeRejectedWrite = fixture.Count;
        var error = await Assert.ThrowsAsync<FilewayException>(() => services.WriteAsync(resource,
            TimeSpan.FromSeconds(20), TimeSpan.FromSeconds(120), Token));
        Assert.Equal(ErrorCode.CapabilityMissing, error.Error.Code);
        Assert.Equal(beforeRejectedWrite, fixture.Count);
        var read = Assert.Single(PlaybackRequests(fixture));
        Assert.Equal("GET", read.Method);
        Assert.Equal(target, read.Target);
    }
}
