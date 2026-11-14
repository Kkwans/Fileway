using System.Text;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Ipc;
using Fileway.Infrastructure.Services;
using Fileway.Infrastructure.Tests.Ipc;

namespace Fileway.Infrastructure.Tests.Services;

public sealed class CollectionsServiceTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;
    private const string Opaque = "/%D6%D0%CE%C4.txt";
    private const string Display = "/中文.txt";
    private static readonly string[] FavoriteOrder = ["favorite"];
    private static readonly string[] GroupOrder = ["group"];
    private static readonly ConnectionProfile Profile = new(Guid.NewGuid(), Guid.NewGuid(), "收藏测试",
        new Uri("https://fixture.invalid/base"), NetworkMode.SystemNetwork, new ProxyPolicy(ProxyMode.Direct));

    [Fact]
    public async Task RealHostCollectionNavigationAcceptsEquivalentWireBytesForMetadataAndDirectoryListing()
    {
        const string requestedWire = "/a%2B";
        const string serverWire = "/a+";
        await using var fixture = new HttpFixture(request => request.Target switch
        {
            "/nas/api/login" => new FixtureResponse(200, Encoding.UTF8.GetBytes(Jwt()), "text/plain"),
            "/nas/api/users/7" => FixtureJson(new { id = 7, username = "fixture", perm = new
                { download = false, create = false, rename = false, modify = false, delete = false, execute = false } }),
            "/nas/api/client-capabilities" => FixtureJson(new { authMethod = "json", resourceWireOperations = true }),
            "/nas/api/favorites" => FixtureJson(new[] { Favorite("plus-directory", serverWire, requestedWire, true) }),
            "/nas/api/favorites/groups" or "/nas/api/tags" => FixtureJson(Array.Empty<object>()),
            "/nas/api/resources/a%2B?metadata=1" => FixtureJson(new
                { name = "a+", path = serverWire, wirePath = serverWire, isDir = true, size = 0 }),
            "/nas/api/resources/a%2B" => FixtureJson(new
                { name = "a+", path = serverWire, wirePath = serverWire, isDir = true, size = 0, items = Array.Empty<object>() }),
            _ => new FixtureResponse(404, []),
        });
        await using var host = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        await using var services = new FilewayClientServices(host);
        var profile = new ConnectionProfile(Guid.NewGuid(), Guid.NewGuid(), "真实Host收藏跳转",
            new Uri(fixture.BaseUrl + "/nas"), NetworkMode.SystemNetwork, new ProxyPolicy(ProxyMode.Direct));
        var session = await services.ConnectAsync(profile, new LoginInput(AuthenticationMode.Password, "fixture.user", "fixture.password"), Token);
        var reference = Assert.Single((await services.GetCollectionsAsync(session.Account, Token)).Favorites).Reference.Resource!;
        Assert.Equal(requestedWire, reference.WirePath);

        var metadata = await services.GetAsync(reference, Token);
        var directory = await services.ListAsync(reference, new DirectoryQuery(), Token);

        Assert.True(metadata.IsDirectory);
        Assert.Equal(serverWire, metadata.Resource.WirePath);
        Assert.Equal(serverWire, directory.Directory.WirePath);
        Assert.Equal(session.Account, directory.Directory.Account);
        Assert.Empty(directory.Items);
        var requests = new List<FixtureRequest>();
        while (fixture.Seen.Reader.TryRead(out var request)) requests.Add(request);
        Assert.Contains(requests, request => request.Method == "GET" && request.Target == "/nas/api/resources/a%2B?metadata=1");
        Assert.Contains(requests, request => request.Method == "GET" && request.Target == "/nas/api/resources/a%2B");
        Assert.DoesNotContain(requests, request => request.Target.Contains("%252B", StringComparison.Ordinal));

        static FixtureResponse FixtureJson(object value) => new(200, JsonSerializer.SerializeToUtf8Bytes(value));
    }

    [Fact]
    public async Task SnapshotKeepsOpaqueAndUnknownSiblingsAndLiteralLegacyPercentIdentity()
    {
        await using var host = new CollectionHost();
        host.Favorites = new object[]
        {
            Favorite("unknown", Display, null, false), Favorite("opaque", Display, Opaque, true),
            Favorite("legacy", "/literal%2F +?.txt", null, null),
            Favorite("orphan", "/a", "/a", true, "removed-group"),
            Favorite("historical", "/legacy/../lost\uFFFD", null, false),
        };
        host.Tags = new[] { Tag("tag", new object[]
        {
            Reference(Display, null, false), Reference(Display, Opaque, true),
            Reference("/a", "/a", true), Reference("/a", "/%61", true),
        }) };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);

        var snapshot = await services.GetCollectionsAsync(session.Account, Token);

        Assert.Equal(session.Account, snapshot.Account);
        Assert.Equal(5, snapshot.Favorites.Count);
        Assert.Null(snapshot.Favorites.Single(row => row.Id == "unknown").Reference.Resource);
        Assert.Equal(Opaque, snapshot.Favorites.Single(row => row.Id == "opaque").Reference.Resource!.WirePath);
        Assert.Equal("/literal%252F%20%2B%3F.txt", snapshot.Favorites.Single(row => row.Id == "legacy").Reference.Resource!.WirePath);
        Assert.Equal("removed-group", snapshot.Favorites.Single(row => row.Id == "orphan").GroupId);
        Assert.Equal("/legacy/../lost\uFFFD", snapshot.Favorites.Single(row => row.Id == "historical").Reference.DisplayPath);
        Assert.Null(snapshot.Favorites.Single(row => row.Id == "historical").Reference.Resource);
        Assert.Equal(3, Assert.Single(snapshot.Tags).Resources.Count);
        Assert.Null(snapshot.Tags[0].Resources[0].Resource);
        Assert.Equal(Opaque, snapshot.Tags[0].Resources[1].Resource!.WirePath);
        Assert.All(snapshot.Favorites, row => Assert.Equal(session.Account, row.Account));
        Assert.Empty(host.Writes);
    }

    [Theory]
    [InlineData("missing-wire")]
    [InlineData("wrong-display")]
    [InlineData("invalid-wire")]
    [InlineData("invalid-provenance")]
    [InlineData("invalid-refs")]
    public async Task InvalidAuthoritativeIdentityDoesNotFallBackToDisplay(string scenario)
    {
        await using var host = new CollectionHost();
        host.Favorites = new[] { Favorite("one", "/a", "/a", true) };
        host.Tags = new[] { Tag("tag", new[] { Reference("/a", "/a", true) }) };
        if (scenario == "missing-wire") host.Favorites = new[] { Favorite("one", "/a", null, true) };
        if (scenario == "wrong-display") host.Favorites = new[] { Favorite("one", "/a", "/b", true) };
        if (scenario == "invalid-wire") host.Favorites = new[] { Favorite("one", "/a", "/bad%2Fpath", true) };
        if (scenario == "invalid-provenance") host.Tags = new[] { Tag("tag", new[] { new { path = "/a", wirePath = "/a", pathVerified = "true" } }) };
        if (scenario == "invalid-refs") host.Tags = new[] { Tag("tag", null) };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);

        var error = await Assert.ThrowsAsync<FilewayException>(() => services.GetCollectionsAsync(session.Account, Token));

        Assert.Equal(ErrorCode.ProtocolMismatch, error.Error.Code);
        Assert.Empty(host.Writes);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task MetadataIgnoresFilesystemPermissionsAndUsesOneCorrectJsonBody(bool opaque)
    {
        await using var host = new CollectionHost { WireOperations = opaque };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        string wire = opaque ? Opaque : "/literal%252F%20%2B%3F.txt";
        string display = opaque ? Display : "/literal%2F +?.txt";
        var resource = new RemoteResourceRef(session.Account, wire, "书签", display);
        host.OnWrite = _ => Http(Favorite("created", display, wire, true, name: "书签"));

        var result = await services.AddFavoriteAsync(resource, " 书签 ", null, Token);

        Assert.False(session.Permissions.Create);
        Assert.False(session.Permissions.Delete);
        Assert.False(session.Permissions.Modify);
        Assert.False(session.Permissions.Download);
        Assert.Equal(OperationOutcome.Confirmed, result.Outcome);
        Assert.Equal("created", result.RecordId);
        var request = Assert.Single(host.Writes);
        Assert.Equal(HostCallSafety.Mutation, request.Safety);
        var body = request.Parameters!.Value.GetProperty("body");
        Assert.Equal(JsonValueKind.Object, body.ValueKind);
        Assert.Equal(wire, body.GetProperty("wirePath").GetString());
        Assert.Equal(!opaque, body.TryGetProperty("path", out var compatible));
        if (!opaque) Assert.Equal(display, compatible.GetString());
        Assert.Equal("", body.GetProperty("groupId").GetString());
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task OpaquePathMutationsRequireCapabilityButOwnedIdManagementRemainsOpen(bool removing)
    {
        await using var host = new CollectionHost { WireOperations = false };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        var resource = new RemoteResourceRef(session.Account, Opaque, "中文.txt", Display);

        var failure = await Assert.ThrowsAsync<FilewayException>(() => removing
            ? services.RemoveTagResourceAsync("tag", resource, Token) : services.AddTagResourceAsync("tag", resource, Token));
        Assert.Equal(ErrorCode.CapabilityMissing, failure.Error.Code);
        Assert.Empty(host.Writes);
        host.OnWrite = _ => Http(null, raw: "");
        var result = await services.RemoveFavoriteAsync(session.Account, "owned", Token);
        Assert.Equal(OperationOutcome.Confirmed, result.Outcome);
        Assert.Single(host.Writes);
    }

    [Theory]
    [InlineData(403)]
    [InlineData(404)]
    public async Task DeniedOrMissingResourcesCanStillUnlinkOwnedMetadata(int inaccessibleStatus)
    {
        await using var host = new CollectionHost();
        host.OnRead = endpoint => endpoint.StartsWith("/api/resources", StringComparison.Ordinal)
            ? Http(null, inaccessibleStatus, "private-source") : null;
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        var resource = new RemoteResourceRef(session.Account, "/a", "a", "/a");
        var inaccessible = await Assert.ThrowsAsync<FilewayException>(() => services.GetAsync(resource, Token));
        Assert.Equal(inaccessibleStatus, inaccessible.Error.HttpStatus);
        host.OnWrite = _ => Http(Tag("tag", Array.Empty<object>()));

        var result = await services.RemoveTagResourceAsync("tag", resource, Token);

        Assert.Equal(OperationOutcome.Confirmed, result.Outcome);
        var request = Assert.Single(host.Writes);
        Assert.Equal("DELETE", request.Parameters!.Value.GetProperty("method").GetString());
        Assert.Equal("/api/tags/tag/paths", request.Parameters.Value.GetProperty("endpoint").GetString());
        Assert.DoesNotContain("private-source", result.ToString());
    }

    [Theory]
    [InlineData("wrong-identity")]
    [InlineData("invalid-json")]
    [InlineData("server-error")]
    [InlineData("accepted")]
    [InlineData("host-lost")]
    public async Task UncertainWritesNeverReplayOrInventRecordId(string scenario)
    {
        await using var host = new CollectionHost();
        host.OnWrite = _ => scenario switch
        {
            "wrong-identity" => Http(Favorite("created", "/b", "/b", true, name: "a")),
            "invalid-json" => Http(null, raw: "{broken"),
            "server-error" => Http(null, 503, "private-write-error"),
            "accepted" => Http(null, 202, ""),
            _ => throw new IOException("private-transport-error"),
        };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);

        var result = await services.AddFavoriteAsync(new RemoteResourceRef(session.Account, "/a", "a", "/a"), "a", null, Token);

        Assert.Equal(OperationOutcome.OutcomeUnknown, result.Outcome);
        Assert.Null(result.RecordId);
        Assert.Equal(ErrorCode.OutcomeUnknown, result.Error!.Code);
        Assert.Contains("刷新", result.Error.Message);
        Assert.DoesNotContain("private", result.ToString());
        Assert.Single(host.Writes);
    }

    [Fact]
    public async Task ConfirmedMetadataAckSurvivesPostWriteSessionRefreshFailure()
    {
        await using var host = new CollectionHost();
        host.OnWrite = _ =>
        {
            host.CurrentToken = Jwt("rotated");
            host.FailUserRead = true;
            return Http(new { id = "created-group", name = "组", color = "#3A9BD9", order = 0 });
        };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        var issues = new List<ConnectionIssue>();
        services.ConnectionIssue += (_, issue) => issues.Add(issue);

        var result = await services.CreateFavoriteGroupAsync(session.Account, "组", "#3A9BD9", Token);

        Assert.Equal(OperationOutcome.Confirmed, result.Outcome);
        Assert.Equal("created-group", result.RecordId);
        Assert.Null(result.Error);
        Assert.Single(host.Writes);
        Assert.True(Assert.Single(issues).SessionInvalidated);
    }

    [Fact]
    public async Task CollectionReferencesCannotFollowAnAccountReconnectAndMissingGroupsAreRejectedBeforeWrite()
    {
        await using var host = new CollectionHost();
        host.Favorites = new[] { Favorite("one", "/a", "/a", true) };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        var resource = Assert.Single((await services.GetCollectionsAsync(session.Account, Token)).Favorites).Reference.Resource!;
        var missing = await Assert.ThrowsAsync<FilewayException>(() => services.AddFavoriteAsync(resource, "a", "missing", Token));
        Assert.Equal(ErrorCode.Conflict, missing.Error.Code);
        Assert.Empty(host.Writes);
        await services.DisconnectAsync(session.Account, Token);
        _ = await Connect(services);

        var retired = await Assert.ThrowsAsync<FilewayException>(() => services.AddFavoriteAsync(resource, "a", null, Token));

        Assert.Equal(ErrorCode.SourceChanged, retired.Error.Code);
        Assert.Empty(host.Writes);
    }

    [Fact]
    public async Task LegacyTagReferencesAndDuplicateColorsRemainServerData()
    {
        await using var host = new CollectionHost();
        host.Tags = new object[]
        {
            new { id = "one", name = "one", color = "#3A9BD9", paths = new[] { "/literal%2F", "/lost\uFFFD" } },
            new { id = "two", name = "two", color = "#3A9BD9", paths = Array.Empty<string>() },
        };
        host.OnWrite = _ => Http(Tag("third", Array.Empty<object>(), "#3A9BD9", "third"));
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);

        var snapshot = await services.GetCollectionsAsync(session.Account, Token);
        var result = await services.CreateTagAsync(session.Account, "third", "#3A9BD9", Token);

        Assert.Equal(2, snapshot.Tags.Count);
        Assert.Equal("/literal%252F", snapshot.Tags[0].Resources[0].Resource!.WirePath);
        Assert.Null(snapshot.Tags[0].Resources[1].Resource);
        Assert.Equal(OperationOutcome.Confirmed, result.Outcome);
        Assert.Equal("third", result.RecordId);
    }

    [Fact]
    public async Task MetadataManagementUsesServerIdsAndExplicitUngroupingWithoutFilesystemAccess()
    {
        await using var host = new CollectionHost();
        host.Groups = new[] { new { id = "group", name = "group", color = "", order = 0 } };
        host.OnWrite = request =>
        {
            var parameters = request.Parameters!.Value;
            string endpoint = parameters.GetProperty("endpoint").GetString()!;
            string method = parameters.GetProperty("method").GetString()!;
            var body = parameters.GetProperty("body");
            if (endpoint == "/api/favorites/favorite" && method == "PUT")
                return Http(Favorite("favorite", Display, null, false, body.GetProperty("groupId").GetString()!, body.GetProperty("name").GetString()!));
            if (endpoint == "/api/favorites/groups/group" && method == "PUT")
                return Http(new { id = "group", name = body.GetProperty("name").GetString(), color = body.GetProperty("color").GetString(), order = 0 });
            if (endpoint == "/api/tags/tag" && method == "PUT")
                return Http(Tag("tag", Array.Empty<object>(), body.GetProperty("color").GetString()!, body.GetProperty("name").GetString()!));
            if (endpoint == "/api/tags/tag/paths" && method == "POST")
                return Http(Tag("tag", new[] { Reference("/a", "/a", true) }));
            return Http(null, raw: "");
        };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        var resource = new RemoteResourceRef(session.Account, "/a", "a", "/a");

        Assert.Equal(OperationOutcome.Confirmed, (await services.UpdateFavoriteAsync(session.Account, "favorite", "改名", "group", Token)).Outcome);
        Assert.Equal(OperationOutcome.Confirmed, (await services.UpdateFavoriteAsync(session.Account, "favorite", "改名", null, Token)).Outcome);
        Assert.Equal(OperationOutcome.Confirmed, (await services.UpdateFavoriteGroupAsync(session.Account, "group", "分组", null, Token)).Outcome);
        Assert.Equal(OperationOutcome.Confirmed, (await services.UpdateTagAsync(session.Account, "tag", "标签", "#123456", Token)).Outcome);
        Assert.Equal(OperationOutcome.Confirmed, (await services.AddTagResourceAsync("tag", resource, Token)).Outcome);
        Assert.Equal(OperationOutcome.Confirmed, (await services.ReorderFavoritesAsync(session.Account, FavoriteOrder, Token)).Outcome);
        Assert.Equal(OperationOutcome.Confirmed, (await services.ReorderFavoriteGroupsAsync(session.Account, GroupOrder, Token)).Outcome);
        Assert.Equal(OperationOutcome.Confirmed, (await services.RemoveFavoriteGroupAsync(session.Account, "group", Token)).Outcome);
        Assert.Equal(OperationOutcome.Confirmed, (await services.RemoveTagAsync(session.Account, "tag", Token)).Outcome);

        Assert.Equal(9, host.Writes.Count);
        Assert.All(host.Writes, request => Assert.Equal(HostCallSafety.Mutation, request.Safety));
        Assert.Equal("", host.Writes[1].Parameters!.Value.GetProperty("body").GetProperty("groupId").GetString());
        Assert.Equal("", host.Writes[2].Parameters!.Value.GetProperty("body").GetProperty("color").GetString());
        var reorder = host.Writes[5].Parameters!.Value;
        Assert.Equal("PUT", reorder.GetProperty("method").GetString());
        Assert.Equal("/api/favorites/reorder", reorder.GetProperty("endpoint").GetString());
        Assert.Equal("favorite", Assert.Single(reorder.GetProperty("body").GetProperty("ids").EnumerateArray()).GetString());
        Assert.Equal("DELETE", host.Writes[7].Parameters!.Value.GetProperty("method").GetString());
    }

    [Theory]
    [InlineData(400, ErrorCode.NetworkUnavailable)]
    [InlineData(403, ErrorCode.PermissionDenied)]
    [InlineData(404, ErrorCode.NetworkUnavailable)]
    [InlineData(409, ErrorCode.Conflict)]
    public async Task DefinitiveRejectionsRetainStatusAndDoNotBecomeUnknown(int status, ErrorCode code)
    {
        await using var host = new CollectionHost { OnWrite = _ => Http(null, status, "private-source") };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);

        var error = await Assert.ThrowsAsync<FilewayException>(() => services.RemoveTagAsync(session.Account, "tag", Token));

        Assert.Equal(code, error.Error.Code);
        Assert.Equal(status, error.Error.HttpStatus);
        Assert.DoesNotContain("private-source", error.ToString());
        Assert.Single(host.Writes);
    }

    private static Task<ConnectionSession> Connect(FilewayClientServices services) => services.ConnectAsync(Profile,
        new LoginInput(AuthenticationMode.Password, "fixture.user", "fixture.password"), Token);

    private static Dictionary<string, object?> Favorite(string id, string path, string? wire, bool? verified, string group = "", string name = "name")
    {
        var row = new Dictionary<string, object?> { ["id"] = id, ["path"] = path, ["name"] = name, ["groupId"] = group, ["order"] = 0, ["addedAt"] = 1000L };
        if (wire is not null) row["wirePath"] = wire;
        if (verified is not null) row["pathVerified"] = verified;
        return row;
    }

    private static Dictionary<string, object?> Reference(string path, string? wire, bool verified)
    {
        var row = new Dictionary<string, object?> { ["path"] = path, ["pathVerified"] = verified };
        if (wire is not null) row["wirePath"] = wire;
        return row;
    }

    private static object Tag(string id, object? refs, string color = "#3A9BD9", string name = "tag") =>
        new { id, name, color, paths = Array.Empty<string>(), pathRefs = refs, createdAt = 1000L };
    private static object Http(object? value, int status = 200, string? raw = null) => new { status, body = raw ?? JsonSerializer.Serialize(value) };
    private static string Jwt(string revision = "initial") => "fixture." + Convert.ToBase64String(JsonSerializer.SerializeToUtf8Bytes(
        new { user = new { id = 7 }, revision })).TrimEnd('=').Replace('+', '-').Replace('/', '_') + ".signature";

    private sealed class CollectionHost : IHostControlClient
    {
        internal object Favorites { get; set; } = Array.Empty<object>();
        internal object Groups { get; set; } = Array.Empty<object>();
        internal object Tags { get; set; } = Array.Empty<object>();
        internal bool WireOperations { get; set; } = true;
        internal bool FailUserRead { get; set; }
        internal string CurrentToken { get; set; } = Jwt();
        internal Func<HostCall, object>? OnWrite { get; set; }
        internal Func<string, object?>? OnRead { get; set; }
        internal List<HostCall> Writes { get; } = [];
        public HostHandshake Handshake { get; } = new(1, 0, 1, "fixture", "fixture", "fixture", JsonSerializer.SerializeToElement(new { }));
        public Task<HostExit> Completion { get; } = Task.FromResult(new HostExit(true, 0, null));

        public Task<HostReply> CallAsync(HostCall request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            object response;
            if (request.Operation == "open") response = "fixture-session";
            else if (request.Operation == "login") response = Http(null, raw: CurrentToken);
            else if (request.Operation == "token") response = CurrentToken;
            else if (request.Operation == "close_session") response = new { };
            else if (request.Operation == "request")
            {
                var parameters = request.Parameters!.Value;
                string endpoint = parameters.GetProperty("endpoint").GetString()!;
                if (parameters.GetProperty("method").GetString() != "GET")
                {
                    Writes.Add(request);
                    response = OnWrite?.Invoke(request) ?? throw new InvalidOperationException("Unexpected mutation.");
                }
                else if (endpoint == "/api/users/7") response = FailUserRead ? Http(null, 503) : Http(new
                { id = 7, username = "fixture", perm = new { download = false, create = false, rename = false, modify = false, delete = false, execute = false } });
                else if (endpoint == "/api/client-capabilities") response = Http(new { authMethod = "json", resourceWireOperations = WireOperations });
                else response = OnRead?.Invoke(endpoint) ?? (endpoint switch
                {
                    "/api/favorites" => Http(Favorites), "/api/favorites/groups" => Http(Groups), "/api/tags" => Http(Tags),
                    _ => Http(null, 404),
                });
            }
            else throw new InvalidOperationException("Unexpected fixture command.");
            return Task.FromResult(new HostReply("fixture", 0, true, JsonSerializer.SerializeToElement(response), null));
        }

        public Task ShutdownAsync(CancellationToken cancellationToken) => Task.CompletedTask;
        public ValueTask DisposeAsync() => ValueTask.CompletedTask;
    }
}
