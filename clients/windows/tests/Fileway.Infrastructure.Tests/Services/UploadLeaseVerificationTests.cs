using System.Collections.Concurrent;
using System.Globalization;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Services;

namespace Fileway.Infrastructure.Tests.Services;

/// <summary>Independent boundary and lifetime checks; never sends an upload HTTP body.</summary>
public sealed class UploadLeaseVerificationTests
{
    private const string Capability = "http://127.0.0.1:43127/stream/upload-capability_1";
    private const string MediaCapability = "http://127.0.0.1:43127/stream/media-capability";
    private static readonly Guid TransferId = Guid.Parse("edfc6b85-845a-4f79-991e-46e19b31d408");
    private static readonly Guid BatchId = Guid.Parse("177292f1-3c5d-41b4-84f3-b30fdf84b9da");
    private static readonly string[] LeaseFields = ["upload", "wirePath"];
    private static readonly string[] UploadFields = ["metadata", "overwrite", "protocol", "resume", "size", "transferId"];
    private static readonly string[] MetadataFields = ["X-Upload-Batch-Bytes", "X-Upload-Batch-ID", "X-Upload-Batch-Items", "X-Upload-Batch-Name", "X-Upload-Folder"];
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task CreationUsesOnlyRequiredPermissionsAndPreservesOpaqueWireAndFiveBatchFields(bool overwrite)
    {
        await using var host = new UploadHost();
        host.Connection.Download = false;
        host.Connection.Delete = false;
        host.Connection.WireOperations = false;
        host.Connection.Modify = overwrite;
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var target = new RemoteResourceRef(session.Account, "/%ff/literal%252F%20%2B%3F.txt", "同名.txt", "/显示/同名.txt");
        var request = new UploadLeaseRequest(target, 10, TransferId, overwrite, Batch: new(BatchId, "%+中 &", 2, 19, true));

        await using var lease = await Provider(services).AcquireUploadAsync(request, Token);

        Assert.False(session.Permissions.Download);
        Assert.False(session.Capabilities.ResourceWireOperations);
        Assert.Same(target, lease.Resource);
        Assert.Equal(TransferId, lease.TransferId);
        Assert.Equal(10, lease.Length);
        Assert.False(lease.IsResume);
        var call = Assert.Single(host.Calls, call => call.Operation == "upload_lease");
        Assert.Equal(HostCallSafety.Control, call.Safety);
        Assert.NotNull(call.Session);
        var parameters = call.Parameters!.Value;
        Assert.Equal(LeaseFields, Names(parameters));
        Assert.Equal(target.WirePath, parameters.GetProperty("wirePath").GetString());
        var upload = parameters.GetProperty("upload");
        Assert.Equal(UploadFields, Names(upload));
        Assert.Equal("tus", upload.GetProperty("protocol").GetString());
        Assert.Equal(10, upload.GetProperty("size").GetInt64());
        Assert.Equal(TransferId.ToString("N", CultureInfo.InvariantCulture), upload.GetProperty("transferId").GetString());
        Assert.Equal(overwrite, upload.GetProperty("overwrite").GetBoolean());
        Assert.False(upload.GetProperty("resume").GetBoolean());
        var metadata = upload.GetProperty("metadata");
        Assert.Equal(MetadataFields, Names(metadata));
        Assert.Equal(BatchId, Guid.Parse(metadata.GetProperty("X-Upload-Batch-ID").GetString()!));
        Assert.Equal("%25%2B%E4%B8%AD%20%26", metadata.GetProperty("X-Upload-Batch-Name").GetString());
        Assert.Equal("2", metadata.GetProperty("X-Upload-Batch-Items").GetString());
        Assert.Equal("19", metadata.GetProperty("X-Upload-Batch-Bytes").GetString());
        Assert.Equal("true", metadata.GetProperty("X-Upload-Folder").GetString());
        AssertControlOnly(host);
    }

    [Theory]
    [InlineData(false, false, true)]
    [InlineData(true, true, false)]
    public async Task MissingCreateOrOverwriteModifyCannotAcquireACapability(bool create, bool overwrite, bool modify)
    {
        await using var host = new UploadHost();
        host.Connection.Create = create;
        host.Connection.Modify = modify;
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var error = await Assert.ThrowsAsync<FilewayException>(() => Provider(services).AcquireUploadAsync(Request(session, overwrite: overwrite), Token));

        Assert.Equal(ErrorCode.PermissionDenied, error.Error.Code);
        Assert.DoesNotContain(host.Calls, call => call.Operation == "upload_lease");
        AssertControlOnly(host);
    }

    [Theory]
    [InlineData("negative-length")]
    [InlineData("escaped-separator")]
    [InlineData("batch-crlf")]
    [InlineData("escaped-utf8-over-limit")]
    public async Task InvalidInputNeverReachesUploadControl(string scenario)
    {
        await using var host = new UploadHost();
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var request = Request(session);
        request = scenario switch
        {
            "negative-length" => request with { Length = -1 },
            "escaped-separator" => request with { Target = Target(session, "/safe%2Ffile") },
            "batch-crlf" => request with { Batch = new(BatchId, "batch\r\nprivate-detail", 1, 0) },
            "escaped-utf8-over-limit" => request with { Batch = new(BatchId, new string('中', 114), 1, 0) },
            _ => throw new InvalidOperationException("Unknown verification scenario."),
        };

        var error = await Assert.ThrowsAsync<FilewayException>(() => Provider(services).AcquireUploadAsync(request, Token));

        Assert.DoesNotContain("private-detail", error.Message);
        Assert.DoesNotContain(host.Calls, call => call.Operation == "upload_lease");
        Assert.DoesNotContain(host.Calls, call => call.Operation == "close_session");
        AssertControlOnly(host);
    }

    [Theory]
    [InlineData(1024, true)]
    [InlineData(1025, false)]
    public async Task BatchNameLimitAppliesToTheEscapedWireValue(int length, bool allowed)
    {
        await using var host = new UploadHost();
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var request = Request(session) with { Batch = new(BatchId, new string('a', length), 1, 0) };

        if (allowed)
        {
            await using var lease = await Provider(services).AcquireUploadAsync(request, Token);
            var call = Assert.Single(host.Calls, call => call.Operation == "upload_lease");
            Assert.Equal(length, call.Parameters!.Value.GetProperty("upload").GetProperty("metadata").GetProperty("X-Upload-Batch-Name").GetString()!.Length);
        }
        else
        {
            await Assert.ThrowsAsync<FilewayException>(() => Provider(services).AcquireUploadAsync(request, Token));
            Assert.DoesNotContain(host.Calls, call => call.Operation == "upload_lease");
        }
    }

    [Theory]
    [InlineData("http://localhost:43127/stream/cap")]
    [InlineData("http://user@127.0.0.1:43127/stream/cap")]
    [InlineData("http://127.0.0.1:43127/stream/cap%2Fextra")]
    [InlineData("http://127.0.0.1:43127/stream/cap+extra")]
    [InlineData("http://127.0.0.1:43127/stream/../cap")]
    public async Task MalformedCapabilityCannotEscapeTheLoopbackSingleComponentBoundary(string address)
    {
        await using var host = new UploadHost { Upload = (call, _) => Task.FromResult(Success(call, address)) };
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var error = await Assert.ThrowsAsync<FilewayException>(() => Provider(services).AcquireUploadAsync(Request(session), Token));

        Assert.Equal(ErrorCode.ProtocolMismatch, error.Error.Code);
        Assert.DoesNotContain(address, error.Message);
        Assert.Single(host.Calls, call => call.Operation == "upload_lease");
        AssertControlOnly(host);
    }

    [Fact]
    public async Task StatisticsKeepOriginalCoreSessionAndUnknownConfirmationWithoutRefreshingEachTick()
    {
        await using var host = new UploadHost();
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        await using var lease = await Provider(services).AcquireUploadAsync(Request(session) with { Resume = true }, Token);
        var creation = Assert.Single(host.Calls, call => call.Operation == "upload_lease");
        int tokenCalls = host.Calls.Count(call => call.Operation == "token");
        int httpCalls = host.Calls.Count(call => call.Operation == "request");
        host.Connection.CurrentToken = CoreReadHost.Jwt("changed-after-acquisition");
        host.Connection.UserReadStatus = 503;

        var unknown = await lease.ReadUploadStatisticsAsync(Token);
        host.Statistics = (call, _) => Task.FromResult(Success(call, Stats(sent: 23, accepted: 10, current: 10, size: 10, requestId: 2, elapsed: 17, active: false)));
        var acknowledged = await lease.ReadUploadStatisticsAsync(Token);

        Assert.True(lease.IsResume);
        Assert.True(creation.Parameters!.Value.GetProperty("upload").GetProperty("resume").GetBoolean());
        Assert.Equal(TransferId.ToString("N", CultureInfo.InvariantCulture), creation.Parameters!.Value.GetProperty("upload").GetProperty("transferId").GetString());
        Assert.NotNull(unknown);
        Assert.Null(unknown.ConfirmedOffset);
        Assert.Null(unknown.CurrentSize);
        Assert.Equal(0, unknown.SentBytes);
        Assert.Equal(new UploadLeaseStatistics(23, 10, 10, 10, 2, TimeSpan.FromMilliseconds(17), false), acknowledged);
        Assert.Equal(tokenCalls, host.Calls.Count(call => call.Operation == "token"));
        Assert.Equal(httpCalls, host.Calls.Count(call => call.Operation == "request"));
        Assert.All(host.Calls.Where(call => call.Operation == "upload_stats"), call =>
        {
            Assert.Equal(creation.Session, call.Session);
            Assert.Equal(Capability, call.Parameters!.Value.GetProperty("url").GetString());
            Assert.Equal(HostCallSafety.ReadOnly, call.Safety);
        });
        AssertControlOnly(host);
    }

    [Theory]
    [InlineData("sentBytes", -1L)]
    [InlineData("acceptedOffset", -2L)]
    [InlineData("acceptedOffset", 11L)]
    [InlineData("currentSent", 11L)]
    [InlineData("currentSize", -2L)]
    [InlineData("currentSize", 11L)]
    [InlineData("requestId", -1L)]
    [InlineData("elapsedMillis", long.MaxValue)]
    public async Task InvalidStatisticsCannotBecomeClampedOrFabricatedProgress(string field, long value)
    {
        await using var host = new UploadHost();
        var statistics = Stats();
        statistics[field] = value;
        host.Statistics = (call, _) => Task.FromResult(Success(call, statistics));
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        await using var lease = await Provider(services).AcquireUploadAsync(Request(session), Token);

        var error = await Assert.ThrowsAsync<FilewayException>(() => lease.ReadUploadStatisticsAsync(Token));

        Assert.Equal(ErrorCode.ProtocolMismatch, error.Error.Code);
        Assert.Single(host.Calls, call => call.Operation == "upload_stats");
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task StatisticsRejectWrongBooleanOrCurrentSentBeyondKnownRequestSize(bool wrongBoolean)
    {
        await using var host = new UploadHost();
        var statistics = Stats(current: 4, size: 3);
        if (wrongBoolean) { statistics["currentSent"] = 0L; statistics["active"] = "true"; }
        host.Statistics = (call, _) => Task.FromResult(Success(call, statistics));
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        await using var lease = await Provider(services).AcquireUploadAsync(Request(session), Token);

        var error = await Assert.ThrowsAsync<FilewayException>(() => lease.ReadUploadStatisticsAsync(Token));

        Assert.Equal(ErrorCode.ProtocolMismatch, error.Error.Code);
    }

    [Fact]
    public async Task ExplicitDisposeRevokesOnceWithoutSessionAndReturnsNullWithoutPolling()
    {
        await using var host = new UploadHost();
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var lease = await Provider(services).AcquireUploadAsync(Request(session), Token);

        await Task.WhenAll(lease.DisposeAsync().AsTask(), lease.DisposeAsync().AsTask());
        Assert.Null(await lease.ReadUploadStatisticsAsync(Token));
        await lease.DisposeAsync();

        var revoke = Assert.Single(host.Calls, call => call.Operation == "revoke");
        Assert.Null(revoke.Session);
        Assert.Equal(HostCallSafety.Control, revoke.Safety);
        Assert.Equal(Capability, revoke.Parameters!.Value.GetProperty("url").GetString());
        Assert.DoesNotContain(host.Calls, call => call.Operation is "upload_stats" or "close_session");
        AssertControlOnly(host);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task StatisticsResponseCannotOutliveExplicitDisposalOrSourceRetirement(bool retire)
    {
        var started = Signal();
        var release = Signal();
        await using var host = new UploadHost
        {
            Statistics = async (call, _) => { started.TrySetResult(true); await release.Task.WaitAsync(Token); return Success(call, Stats()); },
        };
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var lease = await Provider(services).AcquireUploadAsync(Request(session), Token);
        var pending = lease.ReadUploadStatisticsAsync(Token);
        await started.Task.WaitAsync(Token);
        if (retire) await services.DisconnectAsync(session.Account, Token);
        else await lease.DisposeAsync();
        release.TrySetResult(true);

        if (retire)
        {
            var error = await Assert.ThrowsAsync<FilewayException>(() => pending);
            Assert.Equal(ErrorCode.SourceChanged, error.Error.Code);
            var subsequent = await Assert.ThrowsAsync<FilewayException>(() => lease.ReadUploadStatisticsAsync(Token));
            Assert.Equal(ErrorCode.SourceChanged, subsequent.Error.Code);
        }
        else Assert.Null(await pending);
        Assert.Single(host.Calls, call => call.Operation == "revoke");
    }

    [Fact]
    public async Task CanceledBeforeSendPreservesOtherLeasesAndTheCurrentSession()
    {
        await using var host = new UploadHost();
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        await using var media = await services.AcquireAsync(Target(session, "/media"), Token);
        using var canceled = CancellationTokenSource.CreateLinkedTokenSource(Token);
        await canceled.CancelAsync();

        var error = await Assert.ThrowsAsync<FilewayException>(() => Provider(services).AcquireUploadAsync(Request(session), canceled.Token));

        Assert.Equal(ErrorCode.Canceled, error.Error.Code);
        Assert.DoesNotContain(host.Calls, call => call.Operation is "upload_lease" or "revoke" or "close_session");
        Assert.Equal("/", (await services.GetRootAsync(session, Token)).WirePath);
    }

    [Theory]
    [InlineData("cancel", ErrorCode.Canceled)]
    [InlineData("refresh-failure", ErrorCode.NetworkUnavailable)]
    [InlineData("create-revoked", ErrorCode.PermissionDenied)]
    [InlineData("modify-revoked", ErrorCode.PermissionDenied)]
    public async Task ObservedCapabilityIsCleanedWhenCancellationRefreshOrAuthorizationFails(string scenario, ErrorCode expected)
    {
        using var canceled = CancellationTokenSource.CreateLinkedTokenSource(Token);
        await using var host = new UploadHost();
        host.Upload = async (call, _) =>
        {
            if (scenario == "cancel") await canceled.CancelAsync();
            else
            {
                host.Connection.CurrentToken = CoreReadHost.Jwt("renewed");
                if (scenario == "refresh-failure") host.Connection.UserReadStatus = 503;
                if (scenario == "create-revoked") host.Connection.Create = false;
                if (scenario == "modify-revoked") host.Connection.Modify = false;
            }
            return Success(call, Capability);
        };
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var error = await Assert.ThrowsAsync<FilewayException>(() => Provider(services).AcquireUploadAsync(Request(session, overwrite: scenario == "modify-revoked"), canceled.Token));

        Assert.Equal(expected, error.Error.Code);
        var revoke = Assert.Single(host.Calls, call => call.Operation == "revoke");
        Assert.Equal(Capability, revoke.Parameters!.Value.GetProperty("url").GetString());
        Assert.Null(revoke.Session);
        Assert.Single(host.Calls, call => call.Operation == "upload_lease");
        Assert.DoesNotContain(host.Calls, call => call.Operation == "upload_stats");
        AssertControlOnly(host);
    }

    [Fact]
    public async Task UnobservedControlTimeoutRetiresOriginalSessionAndItsOtherTrackedLease()
    {
        await using var host = new UploadHost { Upload = (_, _) => Task.FromException<HostReply>(ControlTimeout()) };
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        await using var media = await services.AcquireAsync(Target(session, "/media"), Token);

        var error = await Assert.ThrowsAsync<FilewayException>(() => Provider(services).AcquireUploadAsync(Request(session), Token));

        Assert.Equal(ErrorCode.Timeout, error.Error.Code);
        var upload = Assert.Single(host.Calls, call => call.Operation == "upload_lease");
        var close = Assert.Single(host.Calls, call => call.Operation == "close_session");
        Assert.Equal(upload.Session, close.Session);
        var revoke = Assert.Single(host.Calls, call => call.Operation == "revoke");
        Assert.Equal(MediaCapability, revoke.Parameters!.Value.GetProperty("url").GetString());
        Assert.Null(revoke.Session);
        var retired = await Assert.ThrowsAsync<FilewayException>(() => services.GetRootAsync(session, Token));
        Assert.Equal(ErrorCode.SourceChanged, retired.Error.Code);
        AssertControlOnly(host);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task LateCapabilityOrTimeoutCannotRetireOrAttachToReplacementSession(bool timeout)
    {
        var started = Signal();
        var release = Signal();
        await using var host = new UploadHost
        {
            Upload = async (call, _) =>
            {
                started.TrySetResult(true);
                await release.Task.WaitAsync(Token);
                if (timeout) throw ControlTimeout();
                return Success(call, Capability);
            },
        };
        await using var services = new FilewayClientServices(host);
        var profile = CoreReadHost.Profile();
        var original = await services.ConnectAsync(profile, CoreReadHost.Login, Token);
        var request = Request(original);
        var pending = Provider(services).AcquireUploadAsync(request, Token);
        await started.Task.WaitAsync(Token);
        await services.DisconnectAsync(original.Account, Token);
        var replacement = await services.ConnectAsync(profile, CoreReadHost.Login, Token);
        Assert.Equal(original.Account, replacement.Account);
        release.TrySetResult(true);

        var error = await Assert.ThrowsAsync<FilewayException>(() => pending);

        Assert.Equal(timeout ? ErrorCode.Timeout : ErrorCode.SourceChanged, error.Error.Code);
        var originalCall = Assert.Single(host.Calls, call => call.Operation == "upload_lease");
        var close = Assert.Single(host.Calls, call => call.Operation == "close_session");
        Assert.Equal(originalCall.Session, close.Session);
        Assert.Equal("/", (await services.GetRootAsync(replacement, Token)).WirePath);
        if (timeout) Assert.DoesNotContain(host.Calls, call => call.Operation == "revoke");
        else Assert.Equal(Capability, Assert.Single(host.Calls, call => call.Operation == "revoke").Parameters!.Value.GetProperty("url").GetString());
        var oldReference = await Assert.ThrowsAsync<FilewayException>(() => Provider(services).AcquireUploadAsync(request, Token));
        Assert.Equal(ErrorCode.SourceChanged, oldReference.Error.Code);
        Assert.Single(host.Calls, call => call.Operation == "upload_lease");
        AssertControlOnly(host);
    }

    private static IUploadLeaseProvider Provider(FilewayClientServices services) => Assert.IsAssignableFrom<IUploadLeaseProvider>(services);
    private static RemoteResourceRef Target(ConnectionSession session, string wire = "/upload") => new(session.Account, wire, "显示名称", "/显示路径");
    private static UploadLeaseRequest Request(ConnectionSession session, bool overwrite = false) => new(Target(session), 10, TransferId, overwrite);
    private static string[] Names(JsonElement value) => value.EnumerateObject().Select(property => property.Name).Order(StringComparer.Ordinal).ToArray();
    private static TaskCompletionSource<bool> Signal() => new(TaskCreationOptions.RunContinuationsAsynchronously);
    private static HostReply Success(HostCall call, object value) => new("verification", call.Generation, true, JsonSerializer.SerializeToElement(value), null);
    private static FilewayException ControlTimeout() => new(new ErrorInfo(ErrorCode.Timeout, "private-timeout-detail"));
    private static Dictionary<string, object> Stats(long sent = 0, long accepted = -1, long current = 0, long size = -1,
        long requestId = 0, long elapsed = 0, bool active = false) => new()
        {
            ["sentBytes"] = sent, ["acceptedOffset"] = accepted, ["currentSent"] = current,
            ["currentSize"] = size, ["requestId"] = requestId, ["elapsedMillis"] = elapsed, ["active"] = active,
        };

    private static void AssertControlOnly(UploadHost host)
    {
        Assert.DoesNotContain(host.Calls, call => call.Safety == HostCallSafety.Mutation);
        Assert.DoesNotContain(host.Calls, call => call.Operation == "request" &&
            CoreReadHost.Endpoint(call) is not ("/api/users/7" or "/api/client-capabilities"));
        Assert.All(host.Calls.Where(call => call.Operation == "upload_lease"), call => Assert.Equal(HostCallSafety.Control, call.Safety));
    }

    private sealed class UploadHost : IHostControlClient
    {
        internal CoreReadHost Connection { get; } = new();
        internal ConcurrentQueue<HostCall> Calls { get; } = new();
        internal Func<HostCall, CancellationToken, Task<HostReply>>? Upload { get; set; }
        internal Func<HostCall, CancellationToken, Task<HostReply>>? Statistics { get; set; }
        public HostHandshake Handshake => Connection.Handshake;
        public Task<HostExit> Completion => Connection.Completion;

        public Task<HostReply> CallAsync(HostCall request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Calls.Enqueue(request);
            return request.Operation switch
            {
                "upload_lease" => Upload?.Invoke(request, cancellationToken) ?? Task.FromResult(Success(request, Capability)),
                "upload_stats" => Statistics?.Invoke(request, cancellationToken) ?? Task.FromResult(Success(request, Stats())),
                "lease" => Task.FromResult(Success(request, MediaCapability)),
                "revoke" => Task.FromResult(Success(request, new { })),
                _ => Connection.CallAsync(request, cancellationToken),
            };
        }

        public Task ShutdownAsync(CancellationToken cancellationToken) => Connection.ShutdownAsync(cancellationToken);
        public ValueTask DisposeAsync() => Connection.DisposeAsync();
    }
}
