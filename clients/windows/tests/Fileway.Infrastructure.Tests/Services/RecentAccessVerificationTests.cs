using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Services;

namespace Fileway.Infrastructure.Tests.Services;

/// <summary>Independent contract regressions; the implementation owner must not weaken these assertions.</summary>
public sealed class RecentAccessVerificationTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;
    private const long ServerTime = 1_792_670_400_123L;
    private static readonly string[] ServerOrder = ["first", "second", "literal", "lost", "legacy-lost"];

    [Fact]
    public async Task ListRetainsServerOrderTimeNamesAndDistinctRawByteSiblings()
    {
        await using var host = new CoreReadHost();
        host.Dispatch = (call, _) => Task.FromResult(CoreReadHost.Http(new object[]
        {
            Row("first", "/同名.txt", "/%FF.txt", true, ServerTime, "服务器名称", true),
            Row("second", "/同名.txt", "/%FE.txt", true, ServerTime + 10),
            Row("literal", "/literal%2F +?.txt", null, null, ServerTime - 1),
            Row("lost", "/old/../lost\uFFFD", null, false, ServerTime - 2),
            Row("legacy-lost", "/lost\uFFFD", null, null, ServerTime - 3),
        }));
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var entries = await services.ListRecentAsync(session.Account, Token);

        Assert.Equal(ServerOrder, entries.Select(row => row.Id));
        Assert.All(entries, row => Assert.Equal(session.Account, row.Account));
        Assert.Equal(ServerTime, entries[0].AccessedAt.ToUnixTimeMilliseconds());
        Assert.Equal(ServerTime + 10, entries[1].AccessedAt.ToUnixTimeMilliseconds());
        Assert.Equal("服务器名称", entries[0].DisplayName);
        Assert.True(entries[0].IsDirectory);
        Assert.Equal("/%FF.txt", entries[0].Resource!.WirePath);
        Assert.Equal("/%FE.txt", entries[1].Resource!.WirePath);
        Assert.Equal(session.Account, entries[0].Resource!.Account);
        Assert.Equal(RecentAccessPathConfidence.ServerWire, entries[0].PathConfidence);
        Assert.Equal("/literal%252F%20%2B%3F.txt", entries[2].Resource!.WirePath);
        Assert.Equal(RecentAccessPathConfidence.LegacyUtf8, entries[2].PathConfidence);
        Assert.Equal("/old/../lost\uFFFD", entries[3].DisplayPath);
        Assert.Null(entries[3].Resource);
        Assert.Null(entries[4].Resource);
        Assert.Equal(RecentAccessPathConfidence.Unverified, entries[3].PathConfidence);
        Assert.Equal(RecentAccessPathConfidence.Unverified, entries[4].PathConfidence);
        var request = Assert.Single(host.Calls, IsRecentRead);
        Assert.Equal("/api/recent?limit=100", CoreReadHost.Endpoint(request));
        Assert.DoesNotContain(host.Calls, IsRecentWrite);
    }

    [Theory]
    [InlineData("missing-wire")]
    [InlineData("wrong-display")]
    [InlineData("invalid-wire")]
    [InlineData("invalid-provenance")]
    [InlineData("missing-time")]
    [InlineData("invalid-time")]
    [InlineData("missing-id")]
    [InlineData("duplicate-id")]
    [InlineData("too-many")]
    public async Task MalformedListCannotInventAnAliasTimestampOrTruncateTheResponse(string scenario)
    {
        var row = Row("one", "/a", "/a", true);
        object[] rows = [row];
        switch (scenario)
        {
            case "missing-wire": row.Remove("wirePath"); break;
            case "wrong-display": row["wirePath"] = "/b"; break;
            case "invalid-wire": row["wirePath"] = "/a%2Fb"; break;
            case "invalid-provenance": row["pathVerified"] = "true"; break;
            case "missing-time": row.Remove("accessedAt"); break;
            case "invalid-time": row["accessedAt"] = long.MaxValue; break;
            case "missing-id": row.Remove("id"); break;
            case "duplicate-id": rows = [row, Row("one", "/b", "/b", true)]; break;
            case "too-many": rows = Enumerable.Range(0, 101).Select(index => (object)Row("id-" + index, "/a", "/a", true)).ToArray(); break;
        }
        await using var host = new CoreReadHost { Dispatch = (_, _) => Task.FromResult(CoreReadHost.Http(rows)) };
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var error = await Assert.ThrowsAsync<FilewayException>(() => services.ListRecentAsync(session.Account, Token));

        Assert.Equal(ErrorCode.ProtocolMismatch, error.Error.Code);
        Assert.Single(host.Calls, IsRecentRead);
        Assert.DoesNotContain(host.Calls, IsRecentWrite);
    }

    [Fact]
    public async Task ExactlyOneHundredEntriesRemainInTheReturnedServerOrder()
    {
        var rows = Enumerable.Range(0, 100).Select(index => Row("id-" + index, "/a", "/a", true, ServerTime + index)).ToArray();
        await using var host = new CoreReadHost { Dispatch = (_, _) => Task.FromResult(CoreReadHost.Http(rows)) };
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var entries = await services.ListRecentAsync(session.Account, Token);

        Assert.Equal(100, entries.Count);
        Assert.Equal("id-0", entries[0].Id);
        Assert.Equal("id-99", entries[99].Id);
        Assert.Equal(ServerTime + 99, entries[99].AccessedAt.ToUnixTimeMilliseconds());
    }

    [Fact]
    public async Task VerifiedLiteralReplacementCharacterRemainsDistinctFromLossyRawBytes()
    {
        await using var host = new CoreReadHost { Dispatch = (_, _) => Task.FromResult(CoreReadHost.Http(new[]
        {
            Row("raw", "/\uFFFD.txt", "/%FF.txt", true),
            Row("unicode", "/\uFFFD.txt", "/%EF%BF%BD.txt", true),
        })) };
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var entries = await services.ListRecentAsync(session.Account, Token);

        Assert.Equal(2, entries.Count);
        Assert.Equal(entries[0].DisplayPath, entries[1].DisplayPath);
        Assert.Equal("/%FF.txt", entries[0].Resource!.WirePath);
        Assert.Equal("/%EF%BF%BD.txt", entries[1].Resource!.WirePath);
        Assert.All(entries, entry => Assert.Equal(RecentAccessPathConfidence.ServerWire, entry.PathConfidence));
    }

    [Fact]
    public async Task ReadingMetadataAndDirectoryDoesNotImplicitlyRecordAnOpen()
    {
        await using var host = new CoreReadHost { Dispatch = (_, _) => Task.FromResult(CoreReadHost.Http(CoreReadHost.Metadata("/fixture", directory: true))) };
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var resource = new RemoteResourceRef(session.Account, "/fixture", "fixture", "/fixture");

        var metadata = await services.GetAsync(resource, Token);
        var directory = await services.ListAsync(resource, new DirectoryQuery(), Token);

        Assert.True(metadata.IsDirectory);
        Assert.Empty(directory.Items);
        Assert.DoesNotContain(host.Calls, IsRecentWrite);
    }

    [Theory]
    [InlineData("/literal%252F%20%2B%3F.txt", "/literal%2F +?.txt", true)]
    [InlineData("/%FF.txt", "/同名.txt", false)]
    public async Task RecordSendsOneWireBodyWithoutFilesystemPermissionOrWireCapability(string wire, string display, bool legacy)
    {
        await using var host = new CoreReadHost { Download = false, Create = false, WireOperations = false };
        host.Dispatch = (_, _) => Task.FromResult(CoreReadHost.Http(Row("record", display, wire, true)));
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var result = await services.RecordOpenedAsync(new RemoteResourceRef(session.Account, wire, "opened", display), Token);

        Assert.False(session.Permissions.Download);
        Assert.False(session.Permissions.Create);
        Assert.False(session.Capabilities.ResourceWireOperations);
        Assert.Equal(RecentAccessRecordOutcome.Confirmed, result.Outcome);
        Assert.Equal("record", result.RecordId);
        Assert.Equal(ServerTime, result.AccessedAt!.Value.ToUnixTimeMilliseconds());
        var write = Assert.Single(host.Calls, IsRecentWrite);
        Assert.Equal(HostCallSafety.Mutation, write.Safety);
        var body = write.Parameters!.Value.GetProperty("body");
        Assert.Equal(wire, body.GetProperty("wirePath").GetString());
        Assert.Equal(legacy, body.TryGetProperty("path", out var path));
        if (legacy) Assert.Equal(display, path.GetString());
        Assert.DoesNotContain(host.Calls, call => call.Operation == "request" && CoreReadHost.Endpoint(call).StartsWith("/api/resources", StringComparison.Ordinal));
    }

    [Fact]
    public async Task RecordAcknowledgementComparesBytesRatherThanWireEscaping()
    {
        await using var host = new CoreReadHost { Dispatch = (_, _) => Task.FromResult(CoreReadHost.Http(Row("record", "/a+", "/a+", true))) };
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var result = await services.RecordOpenedAsync(new RemoteResourceRef(session.Account, "/%61%2B", "a+", "/a+"), Token);

        Assert.Equal(RecentAccessRecordOutcome.Confirmed, result.Outcome);
        Assert.Equal("record", result.RecordId);
        Assert.Single(host.Calls, IsRecentWrite);
    }

    [Theory]
    [InlineData("accepted")]
    [InlineData("empty-success")]
    [InlineData("saved-then-prune-failed")]
    [InlineData("lost-ack")]
    [InlineData("malformed-json")]
    [InlineData("wrong-raw-bytes")]
    [InlineData("unverified")]
    [InlineData("missing-wire")]
    [InlineData("missing-id")]
    [InlineData("missing-time")]
    public async Task UncertainRecordNeverReplaysOrManufacturesConfirmation(string scenario)
    {
        await using var host = new CoreReadHost();
        var row = Row("record", "/同名.txt", "/%FF.txt", true);
        if (scenario == "wrong-raw-bytes") row["wirePath"] = "/%FE.txt";
        if (scenario == "unverified") row["pathVerified"] = false;
        if (scenario == "missing-wire") row.Remove("wirePath");
        if (scenario == "missing-id") row.Remove("id");
        if (scenario == "missing-time") row.Remove("accessedAt");
        host.Dispatch = (_, _) => Task.FromResult(scenario switch
        {
            "accepted" => CoreReadHost.Http(row, 202),
            "empty-success" => CoreReadHost.Http(null, 204, ""),
            "saved-then-prune-failed" => CoreReadHost.Http(null, 500, "private-server-detail"),
            "lost-ack" => throw new IOException("private-transport-detail"),
            "malformed-json" => CoreReadHost.Http(null, raw: "{broken"),
            _ => CoreReadHost.Http(row),
        });
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var result = await services.RecordOpenedAsync(new RemoteResourceRef(session.Account, "/%FF.txt", "同名.txt", "/同名.txt"), Token);

        Assert.Equal(RecentAccessRecordOutcome.OutcomeUnknown, result.Outcome);
        Assert.Null(result.RecordId);
        Assert.Null(result.AccessedAt);
        Assert.Equal(ErrorCode.OutcomeUnknown, result.Error!.Code);
        Assert.DoesNotContain("private", result.Error.Message);
        Assert.Single(host.Calls, IsRecentWrite);
    }

    [Theory]
    [InlineData(400)]
    [InlineData(401)]
    [InlineData(403)]
    [InlineData(404)]
    public async Task DefinitiveRecordRejectionRetainsStatusAndNeverFallsBack(int status)
    {
        await using var host = new CoreReadHost { Dispatch = (_, _) => Task.FromResult(CoreReadHost.Http(null, status, "private-rejection")) };
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var result = await services.RecordOpenedAsync(new RemoteResourceRef(session.Account, "/a", "a", "/a"), Token);

        Assert.Equal(RecentAccessRecordOutcome.Rejected, result.Outcome);
        Assert.Equal(status, result.Error!.HttpStatus);
        Assert.Null(result.RecordId);
        Assert.Null(result.AccessedAt);
        Assert.DoesNotContain("private", result.Error.Message);
        Assert.Single(host.Calls, IsRecentWrite);
        if (status == 401)
        {
            var retired = await Assert.ThrowsAsync<FilewayException>(() => services.ListRecentAsync(session.Account, Token));
            Assert.Equal(ErrorCode.SourceChanged, retired.Error.Code);
            Assert.Single(host.Calls, call => call.Operation == "close_session");
            Assert.DoesNotContain(host.Calls, IsRecentRead);
        }
    }

    [Fact]
    public async Task CanceledBeforeSubmissionReturnsNotSubmittedAndSendsNothing()
    {
        await using var host = new CoreReadHost();
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        using var canceled = CancellationTokenSource.CreateLinkedTokenSource(Token);
        await canceled.CancelAsync();

        var result = await services.RecordOpenedAsync(new RemoteResourceRef(session.Account, "/a", "a", "/a"), canceled.Token);

        Assert.Equal(RecentAccessRecordOutcome.NotSubmitted, result.Outcome);
        Assert.Equal(ErrorCode.Canceled, result.Error!.Code);
        Assert.DoesNotContain(host.Calls, IsRecentWrite);
    }

    [Fact]
    public async Task ListedReferenceCannotFollowTheSameAccountIntoANewSession()
    {
        await using var host = new CoreReadHost { Dispatch = (_, _) => Task.FromResult(CoreReadHost.Http(new[] { Row("one", "/a", "/a", true) })) };
        await using var services = new FilewayClientServices(host);
        var profile = CoreReadHost.Profile();
        var oldSession = await services.ConnectAsync(profile, CoreReadHost.Login, Token);
        var reference = Assert.Single(await services.ListRecentAsync(oldSession.Account, Token)).Resource!;
        await services.DisconnectAsync(oldSession.Account, Token);
        var newSession = await services.ConnectAsync(profile, CoreReadHost.Login, Token);
        Assert.Equal(oldSession.Account, newSession.Account);

        var result = await services.RecordOpenedAsync(reference, Token);

        Assert.Equal(RecentAccessRecordOutcome.NotSubmitted, result.Outcome);
        Assert.Equal(ErrorCode.SourceChanged, result.Error!.Code);
        Assert.DoesNotContain(host.Calls, IsRecentWrite);
    }

    [Fact]
    public async Task LateListFromRetiredSessionCannotReturnReferencesForReconnectedAccount()
    {
        var started = Signal();
        var release = Signal();
        await using var host = new CoreReadHost();
        host.Dispatch = async (_, _) =>
        {
            started.TrySetResult(true);
            await release.Task.WaitAsync(Token);
            return CoreReadHost.Http(new[] { Row("old", "/a", "/a", true) });
        };
        await using var services = new FilewayClientServices(host);
        var profile = CoreReadHost.Profile();
        var oldSession = await services.ConnectAsync(profile, CoreReadHost.Login, Token);
        var pending = services.ListRecentAsync(oldSession.Account, Token);
        await started.Task.WaitAsync(Token);
        await services.DisconnectAsync(oldSession.Account, Token);
        var newSession = await services.ConnectAsync(profile, CoreReadHost.Login, Token);
        Assert.Equal(oldSession.Account, newSession.Account);
        release.TrySetResult(true);

        var error = await Assert.ThrowsAsync<FilewayException>(() => pending);

        Assert.Equal(ErrorCode.SourceChanged, error.Error.Code);
        host.Dispatch = (_, _) => Task.FromResult(CoreReadHost.Http(new[] { Row("new", "/b", "/b", true) }));
        var entry = Assert.Single(await services.ListRecentAsync(newSession.Account, Token));
        Assert.Equal("new", entry.Id);
        Assert.Equal(newSession.Account, entry.Resource!.Account);
    }

    [Fact]
    public async Task LateAcknowledgementAfterLogoutAndReconnectKeepsTheOriginalConfirmation()
    {
        var started = Signal();
        var release = Signal();
        await using var host = new CoreReadHost();
        host.Dispatch = async (_, _) =>
        {
            started.TrySetResult(true);
            await release.Task.WaitAsync(Token);
            return CoreReadHost.Http(Row("old-record", "/a", "/a", true));
        };
        await using var services = new FilewayClientServices(host);
        var profile = CoreReadHost.Profile();
        var oldSession = await services.ConnectAsync(profile, CoreReadHost.Login, Token);
        var pending = services.RecordOpenedAsync(new RemoteResourceRef(oldSession.Account, "/a", "a", "/a"), Token);
        await started.Task.WaitAsync(Token);
        await services.DisconnectAsync(oldSession.Account, Token);
        var newSession = await services.ConnectAsync(profile, CoreReadHost.Login, Token);
        Assert.Equal(oldSession.Account, newSession.Account);
        release.TrySetResult(true);

        var result = await pending;

        Assert.Equal(RecentAccessRecordOutcome.Confirmed, result.Outcome);
        Assert.Equal("old-record", result.RecordId);
        Assert.Equal(ServerTime, result.AccessedAt!.Value.ToUnixTimeMilliseconds());
        Assert.Null(result.Error);
        Assert.Single(host.Calls, IsRecentWrite);
        host.Dispatch = (_, _) => Task.FromResult(CoreReadHost.Http(new[] { Row("new-record", "/b", "/b", true) }));
        Assert.Equal("new-record", Assert.Single(await services.ListRecentAsync(newSession.Account, Token)).Id);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ConfirmedAcknowledgementSurvivesCallerCancellationOrRenewalFailure(bool renewalFailure)
    {
        using var canceled = CancellationTokenSource.CreateLinkedTokenSource(Token);
        await using var host = new CoreReadHost();
        host.Dispatch = async (_, _) =>
        {
            if (renewalFailure)
            {
                host.CurrentToken = CoreReadHost.Jwt("renewed");
                host.UserReadStatus = 503;
            }
            else await canceled.CancelAsync();
            return CoreReadHost.Http(Row("confirmed", "/a", "/a", true));
        };
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var result = await services.RecordOpenedAsync(new RemoteResourceRef(session.Account, "/a", "a", "/a"), canceled.Token);

        Assert.Equal(RecentAccessRecordOutcome.Confirmed, result.Outcome);
        Assert.Equal("confirmed", result.RecordId);
        Assert.Equal(ServerTime, result.AccessedAt!.Value.ToUnixTimeMilliseconds());
        Assert.Null(result.Error);
        Assert.Single(host.Calls, IsRecentWrite);
    }

    private static Dictionary<string, object?> Row(string id, string path, string? wire, bool? verified,
        long accessedAt = ServerTime, string name = "server-name", bool directory = false)
    {
        var row = new Dictionary<string, object?>
        {
            ["id"] = id, ["path"] = path, ["name"] = name, ["isDir"] = directory, ["accessedAt"] = accessedAt,
        };
        if (wire is not null) row["wirePath"] = wire;
        if (verified is not null) row["pathVerified"] = verified;
        return row;
    }

    private static bool IsRecentRead(HostCall call) => call.Operation == "request" &&
        call.Parameters!.Value.GetProperty("method").GetString() == "GET" &&
        CoreReadHost.Endpoint(call).StartsWith("/api/recent", StringComparison.Ordinal);

    private static bool IsRecentWrite(HostCall call) => call.Operation == "request" &&
        call.Parameters!.Value.GetProperty("method").GetString() == "POST" && CoreReadHost.Endpoint(call) == "/api/recent";

    private static TaskCompletionSource<bool> Signal() => new(TaskCreationOptions.RunContinuationsAsynchronously);
}
