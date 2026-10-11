using System.Text;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Infrastructure.Credentials;
using Fileway.Infrastructure.Ipc;
using Fileway.Infrastructure.Persistence;
using Fileway.Infrastructure.Services;
using Fileway.Infrastructure.Tests.Ipc;

namespace Fileway.Infrastructure.Tests.Services;

public sealed class FileOperationsTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;
    private static readonly LoginInput Login = new(AuthenticationMode.Password, "fixture.user", "fixture.password");
    private const string OpaqueParent = "/%B5%E7%D3%B0%252F";
    private const string NewName = "新目录%+&#";
    private const string NewNameWire = "%E6%96%B0%E7%9B%AE%E5%BD%95%25%2B%26%23";
    private static readonly IReadOnlyDictionary<string, string> RenewHeader = new Dictionary<string, string> { ["X-Renew-Token"] = "true" };

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task MkdirPreservesOpaqueParentAndRejectsKnownConflictBeforeSending(bool exists)
    {
        const string targetWire = OpaqueParent + "/" + NewNameWire;
        await using var fixture = new HttpFixture(request => request.Target switch
        {
            "/nas/api/resources" + OpaqueParent + "?metadata=1" => Json(FileMetadata("父目录", OpaqueParent, isDirectory: true)),
            "/nas/api/resources" + targetWire + "?metadata=1" => exists
                ? Json(FileMetadata(NewName, targetWire, isDirectory: true)) : new FixtureResponse(404, []),
            "/nas/api/resources" + targetWire + "/?override=false" when request.Method == "POST" => Json(new { }),
            _ => AccountResponse(request),
        });
        await using var host = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(Profile(fixture), Login, Token);
        var parent = new RemoteResourceRef(session.Account, OpaqueParent, "父目录", "/父目录");

        if (exists)
        {
            var failure = await Assert.ThrowsAsync<FilewayException>(() => services.CreateDirectoryAsync(parent, NewName, Token));
            Assert.Equal(ErrorCode.Conflict, failure.Error.Code);
            Assert.Empty(Mutations(fixture));
        }
        else
        {
            var result = await services.CreateDirectoryAsync(parent, NewName, Token);
            // A 200 proves directory existence, not exclusive creation against a racing creator.
            Assert.Equal(OperationOutcome.Confirmed, result.Outcome);
            var item = Assert.Single(Assert.IsAssignableFrom<IReadOnlyList<FileOperationItemResult>>(result.Items));
            Assert.Equal(targetWire, item.Resource.WirePath);
            Assert.Equal(FileOperationItemOutcome.Confirmed, item.Outcome);
            var mutation = Assert.Single(Mutations(fixture));
            Assert.Equal("POST", mutation.Method);
            Assert.Equal("/nas/api/resources" + targetWire + "/?override=false", mutation.Target);
            Assert.Empty(mutation.Body);
        }
    }

    [Fact]
    public async Task RenameQueryEncodesWireDestinationAsASeparateLayerAndNeverEnablesOverwrite()
    {
        const string sourceWire = OpaqueParent + "/old%25.png";
        const string targetWire = OpaqueParent + "/" + NewNameWire;
        await using var fixture = new HttpFixture(request => request.Method == "PATCH"
            ? Json(new { }) : request.Target == "/nas/api/resources" + sourceWire + "?metadata=1"
                ? Json(FileMetadata("旧名称.png", sourceWire)) : AccountResponse(request));
        await using var host = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(Profile(fixture), Login, Token);
        var source = new RemoteResourceRef(session.Account, sourceWire, "旧名称.png", "/父目录/旧名称.png");

        var result = await services.RenameAsync(source, NewName, Token);

        Assert.Equal(OperationOutcome.Confirmed, result.Outcome);
        var mutation = Assert.Single(Mutations(fixture));
        Assert.Equal("PATCH", mutation.Method);
        var parts = mutation.Target.Split('?', 2);
        Assert.Equal("/nas/api/resources" + sourceWire, parts[0]);
        var query = ParseQuery(parts[1]);
        Assert.Equal("rename", query["action"]);
        Assert.Equal(targetWire, query["destinationWirePath"]);
        Assert.Equal("false", query["override"]);
        Assert.Equal("false", query["rename"]);
        Assert.Contains("destinationWirePath=%2F%25B5%25E7%25D3%25B0%25252F%2F%25E6", parts[1]);
        Assert.Empty(mutation.Body);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task TrashReportsEveryRequestedItemAfterPartialFailureOrCancellation(bool cancelAfterFirst)
    {
        using var cancellation = CancellationTokenSource.CreateLinkedTokenSource(Token);
        await using var fixture = new HttpFixture(request => request.Method == "DELETE"
            ? request.Target == "/nas/api/resources/first?mode=trash"
                ? TrashAvailable(cancelAfterFirst ? RenewHeader : null)
                : new FixtureResponse(403, Encoding.UTF8.GetBytes("fixture.private.response"))
            : AccountResponse(request));
        await using var host = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(Profile(fixture), Login, Token);
        if (cancelAfterFirst) services.SessionUpdated += (_, _) => cancellation.Cancel();
        RemoteResourceRef[] resources = [Resource(session, "/first"), Resource(session, "/second"), Resource(session, "/third")];

        var result = await services.TrashAsync(resources, cancellation.Token);

        Assert.Equal(OperationOutcome.Partial, result.Outcome);
        var items = Assert.IsAssignableFrom<IReadOnlyList<FileOperationItemResult>>(result.Items);
        Assert.Equal(resources, items.Select(item => item.Resource));
        Assert.Equal(FileOperationItemOutcome.Confirmed, items[0].Outcome);
        Assert.Equal(cancelAfterFirst ? FileOperationItemOutcome.NotAttempted : FileOperationItemOutcome.Failed, items[1].Outcome);
        Assert.Equal(cancelAfterFirst ? ErrorCode.Canceled : ErrorCode.PermissionDenied, items[1].Error!.Code);
        Assert.Equal(FileOperationItemOutcome.NotAttempted, items[2].Outcome);
        var mutations = Mutations(fixture);
        Assert.Equal(cancelAfterFirst ? 1 : 2, mutations.Count);
        Assert.All(mutations, request =>
        {
            Assert.Equal("DELETE", request.Method);
            Assert.EndsWith("?mode=trash", request.Target);
        });
        Assert.DoesNotContain(mutations, request => request.Target.Contains("/third", StringComparison.Ordinal));
        Assert.DoesNotContain("fixture.private.response", result.Error!.Message);
    }

    [Theory]
    [InlineData(200, OperationOutcome.Confirmed, FileOperationItemOutcome.Confirmed)]
    [InlineData(202, OperationOutcome.OutcomeUnknown, FileOperationItemOutcome.OutcomeUnknown)]
    [InlineData(500, OperationOutcome.OutcomeUnknown, FileOperationItemOutcome.OutcomeUnknown)]
    [InlineData(0, OperationOutcome.OutcomeUnknown, FileOperationItemOutcome.OutcomeUnknown)]
    [InlineData(403, OperationOutcome.Partial, FileOperationItemOutcome.Failed)]
    public async Task TrashRequiresSynchronousConfirmationAndNeverReplaysAmbiguousWrites(int status,
        OperationOutcome expected, FileOperationItemOutcome expectedItem)
    {
        await using var fixture = new HttpFixture(request => request.Method == "DELETE"
            ? new FixtureResponse(status, JsonSerializer.SerializeToUtf8Bytes(new { id = "fixture-trash", userId = 7, status = "available" }))
            : AccountResponse(request));
        await using var host = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(Profile(fixture), Login, Token);

        var result = await services.TrashAsync([Resource(session, "/selected")], Token);

        Assert.Equal(expected, result.Outcome);
        var item = Assert.Single(Assert.IsAssignableFrom<IReadOnlyList<FileOperationItemResult>>(result.Items));
        Assert.Equal(expectedItem, item.Outcome);
        if (expected == OperationOutcome.OutcomeUnknown) Assert.Equal(ErrorCode.OutcomeUnknown, item.Error!.Code);
        if (status == 403) Assert.Equal(ErrorCode.PermissionDenied, item.Error!.Code);
        Assert.Single(Mutations(fixture));
    }

    [Theory]
    [InlineData(false, ErrorCode.PermissionDenied)]
    [InlineData(true, ErrorCode.CapabilityMissing)]
    public async Task MetadataRenewalMustRecheckPermissionsAndCapabilitiesBeforeSendingRename(bool removeCapability, ErrorCode expected)
    {
        int renewed = 0;
        await using var fixture = new HttpFixture(request =>
        {
            if (request.Target == "/nas/api/renew")
            {
                Interlocked.Exchange(ref renewed, 1);
                return new FixtureResponse(200, Encoding.UTF8.GetBytes(Jwt("renewed")), "text/plain");
            }
            if (request.Target == "/nas/api/resources/selected?metadata=1")
                return Json(FileMetadata("selected", "/selected"), RenewHeader);
            if (request.Method == "PATCH") return Json(new { });
            return AccountResponse(request, rename: removeCapability || Volatile.Read(ref renewed) == 0,
                wireOperations: !removeCapability || Volatile.Read(ref renewed) == 0);
        });
        await using var host = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(Profile(fixture), Login, Token);

        var failure = await Assert.ThrowsAsync<FilewayException>(() => services.RenameAsync(Resource(session, "/selected"), "new-name", Token));

        Assert.Equal(expected, failure.Error.Code);
        Assert.Equal(1, Volatile.Read(ref renewed));
        Assert.Empty(Mutations(fixture));
    }

    [Theory]
    [InlineData(false, ErrorCode.LocalStorageUnavailable)]
    [InlineData(true, ErrorCode.NetworkUnavailable)]
    public async Task ConfirmedMkdirSurvivesPostWriteProfileOrRefreshFailureWithoutReplay(bool failRefresh, ErrorCode expectedIssue)
    {
        var store = new FailingSaveStore();
        int renewed = 0;
        await using var fixture = new HttpFixture(request =>
        {
            if (request.Target == "/nas/api/renew")
            {
                Interlocked.Exchange(ref renewed, 1);
                store.FailSave = !failRefresh;
                return new FixtureResponse(200, Encoding.UTF8.GetBytes(Jwt("renewed")), "text/plain");
            }
            if (request.Target == "/nas/api/users/7" && failRefresh && Volatile.Read(ref renewed) != 0)
                return new FixtureResponse(503, Encoding.UTF8.GetBytes("fixture.private.response"));
            if (request.Target == "/nas/api/resources/?metadata=1") return Json(FileMetadata("", "/", isDirectory: true));
            if (request.Target == "/nas/api/resources/new-folder?metadata=1") return new FixtureResponse(404, []);
            if (request.Method == "POST" && request.Target == "/nas/api/resources/new-folder/?override=false")
                return Json(new { }, RenewHeader);
            return AccountResponse(request);
        });
        await using var host = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        await using var services = new FilewayClientServices(host, store, new InMemoryCredentialVault());
        var session = await services.ConnectAsync(Profile(fixture), Login, Token);
        var issues = new List<ConnectionIssue>();
        services.ConnectionIssue += (_, issue) => issues.Add(issue);

        var result = await services.CreateDirectoryAsync(Resource(session, "/"), "new-folder", Token);

        Assert.Equal(OperationOutcome.Confirmed, result.Outcome);
        Assert.Null(result.Error);
        Assert.Equal(FileOperationItemOutcome.Confirmed, Assert.Single(result.Items!).Outcome);
        Assert.Single(Mutations(fixture));
        var issue = Assert.Single(issues);
        Assert.Equal(expectedIssue, issue.Error.Code);
        Assert.Equal(session.Id, issue.SessionId);
        Assert.True(issue.SessionInvalidated);
        Assert.DoesNotContain("fixture.private.response", issue.Error.Message);
    }

    private static ConnectionProfile Profile(HttpFixture fixture) => new(Guid.NewGuid(), Guid.NewGuid(), "文件操作测试",
        new Uri(fixture.BaseUrl + "/nas"), NetworkMode.SystemNetwork, new ProxyPolicy(ProxyMode.Direct));

    private static RemoteResourceRef Resource(ConnectionSession session, string wirePath) => new(session.Account,
        wirePath, wirePath == "/" ? "根目录" : wirePath.TrimStart('/'), wirePath);

    private static string Jwt(string version = "initial") => "fixture." + Convert.ToBase64String(JsonSerializer.SerializeToUtf8Bytes(
        new { user = new { id = 7 }, version })).TrimEnd('=').Replace('+', '-').Replace('/', '_') + ".signature";

    private static FixtureResponse Json(object value, IReadOnlyDictionary<string, string>? headers = null) =>
        new(200, JsonSerializer.SerializeToUtf8Bytes(value), Headers: headers);

    private static FixtureResponse TrashAvailable(IReadOnlyDictionary<string, string>? headers = null) =>
        Json(new { id = "fixture-trash", userId = 7, status = "available" }, headers);

    private static object FileMetadata(string name, string wirePath, bool isDirectory = false) => new
    {
        name, path = "/" + name, wirePath, isDir = isDirectory, size = 0,
        modified = "2026-10-11T11:00:00Z", type = isDirectory ? "" : "file", extension = "",
    };

    private static FixtureResponse AccountResponse(FixtureRequest request, bool rename = true, bool wireOperations = true) => request.Target switch
    {
        "/nas/api/login" => new FixtureResponse(200, Encoding.UTF8.GetBytes(Jwt()), "text/plain"),
        "/nas/api/renew" => new FixtureResponse(200, Encoding.UTF8.GetBytes(Jwt("renewed")), "text/plain"),
        "/nas/api/users/7" => Json(new { id = 7, username = "操作用户", perm = new { download = true, create = true,
            rename, modify = true, delete = true, execute = false } }),
        "/nas/api/client-capabilities" => Json(new { authMethod = "json", resourceWireOperations = wireOperations }),
        _ => new FixtureResponse(404, []),
    };

    private static List<FixtureRequest> Mutations(HttpFixture fixture)
    {
        var mutations = new List<FixtureRequest>();
        while (fixture.Seen.Reader.TryRead(out var request))
            if ((request.Method is "POST" or "PATCH" or "DELETE") && request.Target.StartsWith("/nas/api/resources", StringComparison.Ordinal))
                mutations.Add(request);
        return mutations;
    }

    private static Dictionary<string, string> ParseQuery(string query) => query.Split('&').Select(part => part.Split('=', 2))
        .ToDictionary(part => part[0], part => Uri.UnescapeDataString(part[1]), StringComparer.Ordinal);

    private sealed class FailingSaveStore : IProfileStore, IOperationJournalStore
    {
        private readonly InMemoryProfileStore _inner = new();
        internal bool FailSave { get; set; }
        public Task<IReadOnlyList<SavedConnection>> ListAsync(CancellationToken cancellationToken) => _inner.ListAsync(cancellationToken);
        public Task<SavedConnection?> GetAsync(Guid profileId, CancellationToken cancellationToken) => _inner.GetAsync(profileId, cancellationToken);
        public Task SaveAsync(SavedConnection connection, CancellationToken cancellationToken) => FailSave
            ? Task.FromException(new IOException("fixture.private.response")) : _inner.SaveAsync(connection, cancellationToken);
        public Task ForgetAsync(AccountKey account, CancellationToken cancellationToken) => _inner.ForgetAsync(account, cancellationToken);
        public Task<IReadOnlyList<AccountKey>> ListPendingLogoutsAsync(CancellationToken cancellationToken) => _inner.ListPendingLogoutsAsync(cancellationToken);
        public Task BeginLogoutAsync(AccountKey account, CancellationToken cancellationToken) => _inner.BeginLogoutAsync(account, cancellationToken);
        public Task CompleteLogoutAsync(AccountKey account, CancellationToken cancellationToken) => _inner.CompleteLogoutAsync(account, cancellationToken);
        public bool IsPersistent => _inner.IsPersistent;
        public Task<bool> HasUnresolvedOperationsAsync(AccountKey account, CancellationToken cancellationToken) => _inner.HasUnresolvedOperationsAsync(account, cancellationToken);
        public Task<OperationRecord> CreateIntentAsync(OperationIntent intent, CancellationToken cancellationToken) => _inner.CreateIntentAsync(intent, cancellationToken);
        public Task<OperationRecord> MarkDispatchAsync(OperationRef operation, long revision, CancellationToken cancellationToken) => _inner.MarkDispatchAsync(operation, revision, cancellationToken);
        public Task<OperationRecord> RecordAckAsync(OperationRef operation, long revision, OperationAcknowledgement acknowledgement, CancellationToken cancellationToken) => _inner.RecordAckAsync(operation, revision, acknowledgement, cancellationToken);
        public Task<OperationRecord> RecordRejectedOrUnknownAsync(OperationRef operation, long revision, OperationPhase phase, ErrorInfo? error, CancellationToken cancellationToken) => _inner.RecordRejectedOrUnknownAsync(operation, revision, phase, error, cancellationToken);
        public Task<OperationPage> ListOperationsAsync(AccountKey account, string? cursor, int limit, CancellationToken cancellationToken) => _inner.ListOperationsAsync(account, cursor, limit, cancellationToken);
        public Task<OperationRecord?> GetOperationAsync(OperationRef operation, CancellationToken cancellationToken) => _inner.GetOperationAsync(operation, cancellationToken);
        public Task<OperationRecord> SaveReconciliationAsync(OperationRef operation, long revision, OperationReconciliation reconciliation, CancellationToken cancellationToken) => _inner.SaveReconciliationAsync(operation, revision, reconciliation, cancellationToken);
    }
}
