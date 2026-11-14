using System.Text;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Ipc;
using Fileway.Infrastructure.Services;
using Fileway.Infrastructure.Tests.Ipc;

namespace Fileway.Infrastructure.Tests.Services;

public sealed class FilewayClientServicesTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;
    private const string OpaquePath = "/%B5%E7%D3%B0%252F.jpg";
    private static readonly LoginInput PasswordLogin = new(AuthenticationMode.Password, "fixture.user", "fixture.password");

    private static ConnectionProfile Profile(HttpFixture fixture) => new(Guid.NewGuid(), Guid.NewGuid(), "测试连接",
        new Uri(fixture.BaseUrl + "/nas"), NetworkMode.SystemNetwork, new ProxyPolicy(ProxyMode.Direct));

    private static string Jwt(int userId = 7) => "fixture." + Convert.ToBase64String(Encoding.UTF8.GetBytes(
        JsonSerializer.Serialize(new { user = new { id = userId, username = "stale token name", perm = new { download = false } } })))
        .TrimEnd('=').Replace('+', '-').Replace('/', '_') + ".signature";

    private static FixtureResponse Json(object value) => new(200, JsonSerializer.SerializeToUtf8Bytes(value));
    private static object File(string name, string wirePath, bool isDir = false, long size = 5) => new
    { name, path = "/" + name, wirePath, isDir, size, modified = "2026-10-10T11:00:00Z", type = isDir ? "" : "image", extension = ".jpg" };

    private static FixtureResponse DefaultResponse(FixtureRequest request, string authMethod = "json", bool download = true)
    {
        return request.Target switch
        {
            "/nas/api/login" => new FixtureResponse(200, Encoding.UTF8.GetBytes(Jwt()), "text/plain"),
            "/nas/api/users/7" => Json(new { id = 7, username = "真实用户", perm = new { download, create = true, rename = false, modify = false, delete = false, execute = false } }),
            "/nas/api/client-capabilities" => Json(new { authMethod, resourceWireOperations = true }),
            "/nas/api/resources/" => Json(new { name = "", path = "/", wirePath = "/", isDir = true, size = 0, items = new[]
                { File("乙.jpg", OpaquePath, size: 10), File("甲.jpg", "/a.jpg", size: 2), File("目录", "/folder", true) } }),
            "/nas/api/resources" + OpaquePath + "?metadata=1" => Json(File("乙.jpg", OpaquePath, size: 10)),
            "/nas/api/raw" + OpaquePath => new FixtureResponse(200, [1, 2, 3, 4, 5], "image/jpeg"),
            _ => new FixtureResponse(404, Encoding.UTF8.GetBytes("private response must not escape")),
        };
    }

    [Fact]
    public async Task LoginUsesRealUserPermissionsAndPreservesOpaqueResourceIdentity()
    {
        await using var fixture = new HttpFixture(request => DefaultResponse(request));
        await using var host = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        await using var services = new FilewayClientServices(host);
        var profile = Profile(fixture);
        var session = await services.ConnectAsync(profile, PasswordLogin, Token);
        Assert.Equal(new AccountKey(profile.Id, profile.SourceRevision, "7"), session.Account);
        Assert.Equal("真实用户", session.DisplayName);
        Assert.True(session.Permissions.Download);
        Assert.True(session.Permissions.Create);
        Assert.False(session.Capabilities.ResourcePaginationV1);
        Assert.True(session.Capabilities.ResourceWireOperations);
        var root = await services.GetRootAsync(session, Token);
        Assert.Equal("/", root.WirePath);
        var page = await services.ListAsync(root, new DirectoryQuery(FileSortField.Size), Token);
        Assert.True(page.Items[0].IsDirectory);
        Assert.Equal(2, page.Items[1].Size);
        var selected = page.Items[2].Resource;
        Assert.Equal(OpaquePath, selected.WirePath);
        Assert.Equal(10, (await services.GetAsync(selected, Token)).Size);
        var requests = new List<FixtureRequest>();
        while (fixture.Seen.Reader.TryRead(out var request)) requests.Add(request);
        Assert.Contains(requests, request => request.Target == "/nas/api/resources" + OpaquePath + "?metadata=1");
        var login = Assert.Single(requests, request => request.Target == "/nas/api/login");
        using var body = JsonDocument.Parse(login.Body);
        Assert.Equal("fixture.user", body.RootElement.GetProperty("username").GetString());
        Assert.Equal("fixture.password", body.RootElement.GetProperty("password").GetString());
        Assert.All(requests.Where(request => request.Method == "GET"), request => Assert.Equal(Jwt(), request.Headers["X-Auth"]));
    }

    [Fact]
    public async Task ExplicitNoAuthenticationUsesEmptyCredentialsAndRequiresMatchingPolicy()
    {
        await using var fixture = new HttpFixture(request => DefaultResponse(request, "noauth"));
        await using var host = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        await using var services = new FilewayClientServices(host);
        var profile = Profile(fixture);
        var session = await services.ConnectAsync(profile, new LoginInput(AuthenticationMode.NoAuthentication, "ignored", "ignored"), Token);
        Assert.Equal("7", session.Account.ServerUserId);
        Assert.True(fixture.Seen.Reader.TryRead(out var request));
        using var body = JsonDocument.Parse(request!.Body);
        Assert.Equal("", body.RootElement.GetProperty("username").GetString());
        Assert.Equal("", body.RootElement.GetProperty("password").GetString());
        var error = await Assert.ThrowsAsync<FilewayException>(() => services.ConnectAsync(profile, PasswordLogin, Token));
        Assert.Equal(ErrorCode.CapabilityMissing, error.Error.Code);
    }

    [Theory]
    [InlineData(401, ErrorCode.AuthenticationExpired)]
    [InlineData(403, ErrorCode.PermissionDenied)]
    public async Task HttpFailuresAreTypedAndDoNotExposeServerBody(int status, ErrorCode expected)
    {
        await using var fixture = new HttpFixture(request => request.Target == "/nas/api/resources/"
            ? new FixtureResponse(status, Encoding.UTF8.GetBytes("secret fixture.password http://private.invalid")) : DefaultResponse(request));
        await using var host = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(Profile(fixture), PasswordLogin, Token);
        var root = await services.GetRootAsync(session, Token);
        var error = await Assert.ThrowsAsync<FilewayException>(() => services.ListAsync(root, new DirectoryQuery(), Token));
        Assert.Equal(expected, error.Error.Code);
        Assert.Equal(status, error.Error.HttpStatus);
        Assert.DoesNotContain("secret", error.ToString());
        Assert.DoesNotContain("fixture.password", error.ToString());
        Assert.DoesNotContain("http://", error.ToString());
    }

    [Fact]
    public async Task MissingWirePathIsRejectedRatherThanReconstructedFromDisplayName()
    {
        await using var fixture = new HttpFixture(request => request.Target == "/nas/api/resources/"
            ? Json(new { name = "/", path = "/", wirePath = "/", isDir = true, size = 0, items = new[] { new { name = "旧文件", path = "/旧文件", isDir = false, size = 2 } } }) : DefaultResponse(request));
        await using var host = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(Profile(fixture), PasswordLogin, Token);
        var root = await services.GetRootAsync(session, Token);
        var error = await Assert.ThrowsAsync<FilewayException>(() => services.ListAsync(root, new DirectoryQuery(), Token));
        Assert.Equal(ErrorCode.ProtocolMismatch, error.Error.Code);
    }

    [Fact]
    public async Task ImageContentIsOwnedAndLogoutRevokesMediaAndClosesImageStream()
    {
        await using var fixture = new HttpFixture(request => DefaultResponse(request));
        await using var host = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(Profile(fixture), PasswordLogin, Token);
        var resource = new RemoteResourceRef(session.Account, OpaquePath, "图片", "/图片");
        await using var image = await services.OpenAsync(resource, Token);
        Assert.Equal(5, image.Length);
        Assert.Equal("image/jpeg", image.ContentType);
        Assert.Equal(1, image.Content.ReadByte());
        await using var media = await services.AcquireAsync(resource, Token);
        Assert.Equal("127.0.0.1", media.Address.Host);
        Assert.DoesNotContain(media.Address.AbsoluteUri, media.ToString()!);
        await services.LogoutAsync(session.Account, Token);
        Assert.False(image.Content.CanRead);
        using var http = new HttpClient(new SocketsHttpHandler { UseProxy = false, AllowAutoRedirect = false });
        using var retired = await http.GetAsync(media.Address, Token);
        Assert.False(retired.IsSuccessStatusCode);
        var error = await Assert.ThrowsAsync<FilewayException>(() => services.AcquireAsync(resource, Token));
        Assert.Equal(ErrorCode.SourceChanged, error.Error.Code);
    }

    [Fact]
    public async Task SwitchingAccountsRetiresOldResourceOwnersAndDownloadPermissionIsEnforced()
    {
        await using var fixture = new HttpFixture(request => DefaultResponse(request, download: false));
        await using var host = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        await using var services = new FilewayClientServices(host);
        var first = await services.ConnectAsync(Profile(fixture), PasswordLogin, Token);
        var oldRoot = await services.GetRootAsync(first, Token);
        var denied = await Assert.ThrowsAsync<FilewayException>(() => services.AcquireAsync(oldRoot, Token));
        Assert.Equal(ErrorCode.PermissionDenied, denied.Error.Code);
        _ = await services.ConnectAsync(Profile(fixture), PasswordLogin, Token);
        var stale = await Assert.ThrowsAsync<FilewayException>(() => services.ListAsync(oldRoot, new DirectoryQuery(), Token));
        Assert.Equal(ErrorCode.SourceChanged, stale.Error.Code);
    }

    [Theory]
    [InlineData(NetworkMode.EmbeddedTailnet, ProxyMode.Direct)]
    [InlineData(NetworkMode.SystemNetwork, ProxyMode.System)]
    [InlineData(NetworkMode.SystemNetwork, ProxyMode.Manual)]
    public async Task UnsupportedNetworkPoliciesFailBeforeAnyServerRequest(NetworkMode network, ProxyMode proxy)
    {
        await using var fixture = new HttpFixture(request => DefaultResponse(request));
        await using var host = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        await using var services = new FilewayClientServices(host);
        var profile = Profile(fixture) with { Network = network, Proxy = new ProxyPolicy(proxy) };
        var error = await Assert.ThrowsAsync<FilewayException>(() => services.ConnectAsync(profile, PasswordLogin, Token));
        Assert.Equal(ErrorCode.CapabilityMissing, error.Error.Code);
        Assert.Equal(0, fixture.Count);
    }

    [Fact]
    public async Task OversizedImageIsRejectedAndDoesNotEscapeTheLease()
    {
        byte[] oversized = new byte[64 * 1024 * 1024 + 1];
        await using var fixture = new HttpFixture(request => request.Target == "/nas/api/raw" + OpaquePath
            ? new FixtureResponse(200, oversized, "image/jpeg") : DefaultResponse(request));
        await using var host = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(Profile(fixture), PasswordLogin, Token);
        var resource = new RemoteResourceRef(session.Account, OpaquePath, "图片", "/图片");
        var error = await Assert.ThrowsAsync<FilewayException>(() => services.OpenAsync(resource, Token));
        Assert.Equal(ErrorCode.ResponseTooLarge, error.Error.Code);
    }
}
