using System.Globalization;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Services;

namespace Fileway.Infrastructure.Tests.Services;

public sealed class RecentAccessServiceTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;
    private static readonly string[] ServerOrder = ["second", "first", "legacy", "unknown", "lost", "root"];
    private static readonly ConnectionProfile Profile = new(Guid.NewGuid(), Guid.NewGuid(), "最近访问测试",
        new Uri("https://fixture.invalid/base"), NetworkMode.SystemNetwork, new ProxyPolicy(ProxyMode.Direct));

    [Fact]
    public async Task ListPreservesServerOrderTimeOwnershipAndDistinctWireIdentities()
    {
        await using var host = new RecentHost
        {
            Entries = new object[]
            {
                Entry("second", "/中文.txt", "/%D6%D0%CE%C4.txt", true, 1_700_000_000_123),
                Entry("first", "/中文.txt", "/%E4%B8%AD%E6%96%87.txt", true, 1_700_000_000_456),
                Entry("legacy", "/literal%2F +?.txt", null, null, 1_700_000_000_789),
                Entry("unknown", "/legacy/../lost\uFFFD", null, false),
                Entry("lost", "/lost\uFFFD", null, null),
                Entry("root", "/", "/", true, isDirectory: true, name: "我的文件"),
            },
        };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);

        var entries = await services.ListRecentAsync(session.Account, Token);

        Assert.Equal(ServerOrder, entries.Select(entry => entry.Id));
        Assert.All(entries, entry => Assert.Equal(session.Account, entry.Account));
        Assert.Equal(1_700_000_000_123, entries[0].AccessedAt.ToUnixTimeMilliseconds());
        Assert.Equal(TimeSpan.Zero, entries[0].AccessedAt.Offset);
        Assert.Equal("/%D6%D0%CE%C4.txt", entries[0].Resource!.WirePath);
        Assert.Equal("/%E4%B8%AD%E6%96%87.txt", entries[1].Resource!.WirePath);
        Assert.Equal(RecentAccessPathConfidence.ServerWire, entries[0].PathConfidence);
        Assert.Equal("/literal%252F%20%2B%3F.txt", entries[2].Resource!.WirePath);
        Assert.Equal(RecentAccessPathConfidence.LegacyUtf8, entries[2].PathConfidence);
        Assert.Null(entries[3].Resource);
        Assert.Null(entries[4].Resource);
        Assert.Equal(RecentAccessPathConfidence.Unverified, entries[3].PathConfidence);
        Assert.Equal(RecentAccessPathConfidence.Unverified, entries[4].PathConfidence);
        Assert.Equal("/legacy/../lost\uFFFD", entries[3].DisplayPath);
        Assert.True(entries[5].IsDirectory);
        Assert.Equal("我的文件", entries[5].DisplayName);
        Assert.Single(host.Reads, endpoint => endpoint == "/api/recent?limit=100");
        Assert.Empty(host.Writes);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(100)]
    public async Task ListAcceptsTheRetainedServerBoundary(int count)
    {
        await using var host = new RecentHost { Entries = Enumerable.Range(0, count).Select(index => Entry(index.ToString(CultureInfo.InvariantCulture), "/a", "/a", true)).ToArray() };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);

        Assert.Equal(count, (await services.ListRecentAsync(session.Account, Token)).Count);
    }

    [Theory]
    [InlineData("over-limit")]
    [InlineData("not-array")]
    [InlineData("missing-id")]
    [InlineData("blank-id")]
    [InlineData("duplicate-id")]
    [InlineData("missing-time")]
    [InlineData("fraction-time")]
    [InlineData("overflow-time")]
    [InlineData("missing-directory")]
    [InlineData("invalid-name")]
    [InlineData("missing-wire")]
    [InlineData("wrong-display")]
    [InlineData("invalid-wire")]
    [InlineData("invalid-provenance")]
    [InlineData("invalid-legacy")]
    public async Task InvalidListDataNeverInventsIdentityOrTime(string scenario)
    {
        var row = Entry("id", "/a", "/a", true);
        object entries = new[] { row };
        switch (scenario)
        {
            case "over-limit": entries = Enumerable.Range(0, 101).Select(index => Entry(index.ToString(CultureInfo.InvariantCulture), "/a", "/a", true)).ToArray(); break;
            case "not-array": entries = row; break;
            case "missing-id": row.Remove("id"); break;
            case "blank-id": row["id"] = " \t"; break;
            case "duplicate-id": entries = new[] { row, row }; break;
            case "missing-time": row.Remove("accessedAt"); break;
            case "fraction-time": row["accessedAt"] = 1.5; break;
            case "overflow-time": row["accessedAt"] = long.MaxValue; break;
            case "missing-directory": row.Remove("isDir"); break;
            case "invalid-name": row["name"] = 5; break;
            case "missing-wire": row.Remove("wirePath"); break;
            case "wrong-display": row["wirePath"] = "/b"; break;
            case "invalid-wire": row["wirePath"] = "/bad%2Fpath"; break;
            case "invalid-provenance": row["pathVerified"] = "true"; break;
            case "invalid-legacy": row.Remove("wirePath"); row.Remove("pathVerified"); row["path"] = "/a/../b"; break;
        }
        await using var host = new RecentHost { Entries = entries };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);

        var error = await Assert.ThrowsAsync<FilewayException>(() => services.ListRecentAsync(session.Account, Token));

        Assert.Equal(ErrorCode.ProtocolMismatch, error.Error.Code);
        Assert.Empty(host.Writes);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task RecordUsesOneWireRequestWithOnlyStrictlyRestorableLiteralPath(bool opaque)
    {
        string wire = opaque ? "/%D6%D0%CE%C4.txt" : "/literal%252F%20%2B%3F.txt";
        string display = opaque ? "/中文.txt" : "/literal%2F +?.txt";
        await using var host = new RecentHost { WireOperations = false };
        host.OnWrite = (_, _) => Task.FromResult(Http(Entry(" authoritative id ", display, wire, true, 1_700_000_000_123)));
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);

        var result = await services.RecordOpenedAsync(new RemoteResourceRef(session.Account, wire, "name", display), Token);

        Assert.False(session.Permissions.Download);
        Assert.False(session.Permissions.Create);
        Assert.False(session.Capabilities.ResourceWireOperations);
        Assert.Equal(RecentAccessRecordOutcome.Confirmed, result.Outcome);
        Assert.Equal(" authoritative id ", result.RecordId);
        Assert.Equal(1_700_000_000_123, result.AccessedAt!.Value.ToUnixTimeMilliseconds());
        Assert.Null(result.Error);
        var write = Assert.Single(host.Writes);
        Assert.Equal(HostCallSafety.Mutation, write.Safety);
        Assert.Equal("POST", write.Parameters!.Value.GetProperty("method").GetString());
        Assert.Equal("/api/recent", write.Parameters.Value.GetProperty("endpoint").GetString());
        var body = write.Parameters.Value.GetProperty("body");
        Assert.Equal(wire, body.GetProperty("wirePath").GetString());
        Assert.Equal(!opaque, body.TryGetProperty("path", out var literal));
        if (!opaque) Assert.Equal(display, literal.GetString());
        Assert.Equal(opaque ? 1 : 2, body.EnumerateObject().Count());
        Assert.DoesNotContain(host.Reads, endpoint => endpoint.StartsWith("/api/resources", StringComparison.Ordinal));
    }

    [Fact]
    public async Task EquivalentWireBytesConfirmWithoutComparingEscapingSpelling()
    {
        await using var host = new RecentHost { OnWrite = (_, _) => Task.FromResult(Http(Entry("saved", "/a+", "/a+", true))) };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);

        var result = await services.RecordOpenedAsync(new RemoteResourceRef(session.Account, "/%61%2B", "a+", "/a+"), Token);

        Assert.Equal(RecentAccessRecordOutcome.Confirmed, result.Outcome);
        Assert.Single(host.Writes);
    }

    [Theory]
    [InlineData(400)]
    [InlineData(401)]
    [InlineData(403)]
    [InlineData(404)]
    [InlineData(409)]
    public async Task ExplicitRejectionReturnsItsOwnSafeWarningWithoutFallback(int status)
    {
        await using var host = new RecentHost { OnWrite = (_, _) => Task.FromResult(Http(null, status, "private-source-error")) };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);

        var result = await services.RecordOpenedAsync(new RemoteResourceRef(session.Account, "/a", "a", "/a"), Token);

        Assert.Equal(RecentAccessRecordOutcome.Rejected, result.Outcome);
        Assert.Equal(status, result.Error!.HttpStatus);
        Assert.Null(result.RecordId);
        Assert.Null(result.AccessedAt);
        Assert.False(result.Error.Retryable);
        Assert.DoesNotContain("private", result.ToString());
        Assert.Single(host.Writes);
        if (status == 401)
        {
            var retired = await Assert.ThrowsAsync<FilewayException>(() => services.GetRootAsync(session, Token));
            Assert.Equal(ErrorCode.SourceChanged, retired.Error.Code);
        }
        else Assert.Equal(session.Account, (await services.GetRootAsync(session, Token)).Account);
    }

    [Theory]
    [InlineData("server-500")]
    [InlineData("server-503")]
    [InlineData("server-408")]
    [InlineData("server-504")]
    [InlineData("accepted")]
    [InlineData("no-content")]
    [InlineData("invalid-json")]
    [InlineData("wrong-wire")]
    [InlineData("unverified")]
    [InlineData("missing-id")]
    [InlineData("missing-time")]
    [InlineData("host-lost")]
    [InlineData("canceled")]
    public async Task UncertainRecordNeverReplaysOrInventsAck(string scenario)
    {
        await using var host = new RecentHost();
        host.OnWrite = (_, _) =>
        {
            var row = Entry("saved", "/a", "/a", true);
            object response;
            if (scenario.StartsWith("server-", StringComparison.Ordinal)) response = Http(null,
                scenario switch { "server-500" => 500, "server-503" => 503, "server-408" => 408, _ => 504 }, "private-source-error");
            else if (scenario == "accepted") response = Http(row, 202);
            else if (scenario == "no-content") response = Http(null, 204, "");
            else if (scenario == "invalid-json") response = Http(null, raw: "{private-broken");
            else if (scenario == "host-lost") throw new IOException("private-transport-error");
            else if (scenario == "canceled") throw new OperationCanceledException();
            else
            {
                if (scenario == "wrong-wire") { row["wirePath"] = "/b"; row["path"] = "/b"; }
                if (scenario == "unverified") row["pathVerified"] = false;
                if (scenario == "missing-id") row.Remove("id");
                if (scenario == "missing-time") row.Remove("accessedAt");
                response = Http(row);
            }
            return Task.FromResult(response);
        };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);

        var result = await services.RecordOpenedAsync(new RemoteResourceRef(session.Account, "/a", "a", "/a"), Token);

        Assert.Equal(RecentAccessRecordOutcome.OutcomeUnknown, result.Outcome);
        Assert.Equal(ErrorCode.OutcomeUnknown, result.Error!.Code);
        Assert.Null(result.RecordId);
        Assert.Null(result.AccessedAt);
        Assert.False(result.Error.Retryable);
        Assert.Contains("不要重复", result.Error.Message);
        Assert.DoesNotContain("private", result.ToString());
        Assert.Single(host.Writes);
    }

    [Fact]
    public async Task PreSendCancellationAndInvalidPathReturnNotSubmitted()
    {
        await using var host = new RecentHost();
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        using var canceled = CancellationTokenSource.CreateLinkedTokenSource(Token);
        await canceled.CancelAsync();

        var canceledResult = await services.RecordOpenedAsync(new RemoteResourceRef(session.Account, "/a", "a", "/a"), canceled.Token);
        var invalidResult = await services.RecordOpenedAsync(new RemoteResourceRef(session.Account, "/a", "a", "/b"), Token);

        Assert.Equal(RecentAccessRecordOutcome.NotSubmitted, canceledResult.Outcome);
        Assert.Equal(ErrorCode.Canceled, canceledResult.Error!.Code);
        Assert.Equal(RecentAccessRecordOutcome.NotSubmitted, invalidResult.Outcome);
        Assert.Empty(host.Writes);
    }

    [Fact]
    public async Task ListReferencesCannotFollowTheSameAccountIntoAReplacementSession()
    {
        await using var host = new RecentHost { Entries = new[] { Entry("id", "/a", "/a", true) } };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        var resource = Assert.Single(await services.ListRecentAsync(session.Account, Token)).Resource!;
        await services.DisconnectAsync(session.Account, Token);
        var replacement = await Connect(services);
        Assert.Equal(session.Account, replacement.Account);
        Assert.NotEqual(session.Id, replacement.Id);

        var result = await services.RecordOpenedAsync(resource, Token);

        Assert.Equal(RecentAccessRecordOutcome.NotSubmitted, result.Outcome);
        Assert.Equal(ErrorCode.SourceChanged, result.Error!.Code);
        Assert.Empty(host.Writes);
    }

    [Fact]
    public async Task ConfirmedOpenRecorderCannotRebindToReplacementAccountSession()
    {
        await using var host = new RecentHost { Entries = new[] { Entry("id", "/a", "/a", true) } };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        var resource = Assert.Single(await services.ListRecentAsync(session.Account, Token)).Resource!;
        var recording = new RecentOpenRecording(services, resource, 1);
        await services.DisconnectAsync(session.Account, Token);
        var replacement = await Connect(services);
        Assert.Equal(session.Account, replacement.Account);
        Assert.NotEqual(session.Id, replacement.Id);

        await recording.ConfirmOpenedAsync(1, Token);
        await recording.ConfirmOpenedAsync(1, Token);

        Assert.Empty(host.Writes);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ConfirmedAckSurvivesCallerCancellationOrPostWriteRenewalFailure(bool renewalFailure)
    {
        await using var host = new RecentHost();
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        using var caller = CancellationTokenSource.CreateLinkedTokenSource(Token);
        var issues = new List<ConnectionIssue>();
        services.ConnectionIssue += (_, issue) => issues.Add(issue);
        host.OnWrite = async (_, _) =>
        {
            if (renewalFailure) { host.CurrentToken = Jwt("rotated"); host.FailUserRead = true; }
            else await caller.CancelAsync();
            return Http(Entry("saved", "/a", "/a", true, 1_700_000_000_123));
        };

        var result = await services.RecordOpenedAsync(new RemoteResourceRef(session.Account, "/a", "a", "/a"), caller.Token);

        Assert.Equal(RecentAccessRecordOutcome.Confirmed, result.Outcome);
        Assert.Equal("saved", result.RecordId);
        Assert.Equal(1_700_000_000_123, result.AccessedAt!.Value.ToUnixTimeMilliseconds());
        Assert.Null(result.Error);
        Assert.Single(host.Writes);
        if (renewalFailure) Assert.True(Assert.Single(issues).SessionInvalidated);
        else Assert.Empty(issues);
    }

    [Fact]
    public async Task LateAckAfterDisconnectAndReconnectKeepsOnlyTheOriginalRecordingReceipt()
    {
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        await using var host = new RecentHost();
        host.OnWrite = async (_, _) =>
        {
            entered.SetResult();
            await release.Task.WaitAsync(Token);
            return Http(Entry("old-session-ack", "/a", "/a", true));
        };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        var pending = services.RecordOpenedAsync(new RemoteResourceRef(session.Account, "/a", "a", "/a"), Token);
        await entered.Task.WaitAsync(Token);
        await services.DisconnectAsync(session.Account, Token);
        var replacement = await Connect(services);
        release.SetResult();

        var result = await pending;

        Assert.Equal(RecentAccessRecordOutcome.Confirmed, result.Outcome);
        Assert.Equal("old-session-ack", result.RecordId);
        Assert.Null(result.Error);
        Assert.Equal(replacement.Account, (await services.GetRootAsync(replacement, Token)).Account);
        Assert.Single(host.Writes);
    }

    [Fact]
    public async Task LateListCannotAttachHistoryToAReplacementSession()
    {
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        await using var host = new RecentHost();
        host.OnRecentRead = async _ =>
        {
            entered.SetResult();
            await release.Task.WaitAsync(Token);
            return Http(new[] { Entry("late", "/a", "/a", true) });
        };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        var pending = services.ListRecentAsync(session.Account, Token);
        await entered.Task.WaitAsync(Token);
        await services.DisconnectAsync(session.Account, Token);
        var replacement = await Connect(services);
        release.SetResult();

        var error = await Assert.ThrowsAsync<FilewayException>(() => pending);

        Assert.Equal(ErrorCode.SourceChanged, error.Error.Code);
        Assert.Equal(replacement.Account, (await services.GetRootAsync(replacement, Token)).Account);
        Assert.Empty(host.Writes);
    }

    private static Task<ConnectionSession> Connect(FilewayClientServices services) => services.ConnectAsync(Profile,
        new LoginInput(AuthenticationMode.Password, "fixture.user", "fixture.password"), Token);

    private static Dictionary<string, object?> Entry(string id, string path, string? wire, bool? verified,
        long milliseconds = 1000, bool isDirectory = false, string name = "name")
    {
        var row = new Dictionary<string, object?> { ["id"] = id, ["path"] = path, ["name"] = name,
            ["isDir"] = isDirectory, ["accessedAt"] = milliseconds };
        if (wire is not null) row["wirePath"] = wire;
        if (verified is not null) row["pathVerified"] = verified;
        return row;
    }

    private static object Http(object? value, int status = 200, string? raw = null) => new { status, body = raw ?? JsonSerializer.Serialize(value) };
    private static string Jwt(string revision = "initial") => "fixture." + Convert.ToBase64String(JsonSerializer.SerializeToUtf8Bytes(
        new { user = new { id = 7 }, revision })).TrimEnd('=').Replace('+', '-').Replace('/', '_') + ".signature";

    private sealed class RecentHost : IHostControlClient
    {
        internal object Entries { get; set; } = Array.Empty<object>();
        internal bool WireOperations { get; set; }
        internal bool FailUserRead { get; set; }
        internal string CurrentToken { get; set; } = Jwt();
        internal Func<HostCall, CancellationToken, Task<object>>? OnWrite { get; set; }
        internal Func<CancellationToken, Task<object>>? OnRecentRead { get; set; }
        internal List<HostCall> Writes { get; } = [];
        internal List<string> Reads { get; } = [];
        public HostHandshake Handshake { get; } = new(1, 0, 1, "fixture", "fixture", "fixture", JsonSerializer.SerializeToElement(new { }));
        public Task<HostExit> Completion { get; } = Task.FromResult(new HostExit(true, 0, null));

        public async Task<HostReply> CallAsync(HostCall request, CancellationToken cancellationToken)
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
                    response = await (OnWrite?.Invoke(request, cancellationToken) ?? throw new InvalidOperationException("Unexpected mutation."));
                }
                else
                {
                    Reads.Add(endpoint);
                    response = endpoint switch
                    {
                        "/api/users/7" => FailUserRead ? Http(null, 503) : Http(new { id = 7, username = "fixture",
                            perm = new { download = false, create = false, rename = false, modify = false, delete = false, execute = false } }),
                        "/api/client-capabilities" => Http(new { authMethod = "json", resourceWireOperations = WireOperations }),
                        "/api/recent?limit=100" => OnRecentRead is null ? Http(Entries) : await OnRecentRead(cancellationToken),
                        _ => Http(null, 404),
                    };
                }
            }
            else throw new InvalidOperationException("Unexpected fixture command.");
            return new HostReply("fixture", 0, true, JsonSerializer.SerializeToElement(response), null);
        }

        public Task ShutdownAsync(CancellationToken cancellationToken) => Task.CompletedTask;
        public ValueTask DisposeAsync() => ValueTask.CompletedTask;
    }
}
