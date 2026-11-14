using System.Collections.Concurrent;
using System.Globalization;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Services;

namespace Fileway.Infrastructure.Tests.Services;

public sealed class UploadLeaseServiceTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;
    private static readonly ConnectionProfile Profile = new(Guid.NewGuid(), Guid.NewGuid(), "上传测试",
        new Uri("https://fixture.invalid/base"), NetworkMode.SystemNetwork, new ProxyPolicy(ProxyMode.Direct));
    private static readonly LoginInput Login = new(AuthenticationMode.Password, "fixture.user", "fixture.password");
    private static readonly Guid Transfer = Guid.Parse("11111111-2222-3333-4444-555555555555");
    private static readonly Guid Batch = Guid.Parse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static readonly string[] ParameterKeys = ["upload", "wirePath"];
    private static readonly string[] UploadKeys = ["metadata", "overwrite", "protocol", "resume", "size", "transferId"];
    private static readonly string[] MetadataKeys = ["X-Upload-Batch-Bytes", "X-Upload-Batch-ID", "X-Upload-Batch-Items", "X-Upload-Batch-Name", "X-Upload-Folder"];
    private const string Capability = "http://127.0.0.1:32450/stream/fixture_capability-1";
    private const string Opaque = "/%FF/literal%252F%20%2B.txt";

    [Fact]
    public async Task AcquisitionUsesOriginalOpaqueTargetAndExactTusMetadataWithoutDownloadOrWireCapability()
    {
        await using var host = new UploadHost();
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        var target = new RemoteResourceRef(session.Account, Opaque, "显示名", "/不同的显示目录/显示名");
        var request = new UploadLeaseRequest(target, 0, Transfer, Resume: true,
            Batch: new UploadBatchMetadata(Batch, "中文 %2F +", 3, 100, true));

        await using var lease = await services.AcquireUploadAsync(request, Token);

        Assert.False(session.Permissions.Download);
        Assert.False(session.Capabilities.ResourceWireOperations);
        Assert.Same(target, lease.Resource);
        Assert.Equal(Transfer, lease.TransferId);
        Assert.True(lease.IsResume);
        Assert.Equal(0, lease.Length);
        var call = Assert.Single(host.Calls, call => call.Operation == "upload_lease");
        Assert.Equal(HostCallSafety.Control, call.Safety);
        var parameters = call.Parameters!.Value;
        Assert.Equal(ParameterKeys, Keys(parameters));
        Assert.Equal(Opaque, parameters.GetProperty("wirePath").GetString());
        var upload = parameters.GetProperty("upload");
        Assert.Equal(UploadKeys, Keys(upload));
        Assert.Equal("tus", upload.GetProperty("protocol").GetString());
        Assert.Equal(Transfer.ToString("N"), upload.GetProperty("transferId").GetString());
        Assert.Equal(0, upload.GetProperty("size").GetInt64());
        Assert.False(upload.GetProperty("overwrite").GetBoolean());
        Assert.True(upload.GetProperty("resume").GetBoolean());
        var metadata = upload.GetProperty("metadata");
        Assert.Equal(MetadataKeys, Keys(metadata));
        Assert.Equal(Batch.ToString("N"), metadata.GetProperty("X-Upload-Batch-ID").GetString());
        Assert.Equal(Uri.EscapeDataString("中文 %2F +"), metadata.GetProperty("X-Upload-Batch-Name").GetString());
        Assert.Equal("3", metadata.GetProperty("X-Upload-Batch-Items").GetString());
        Assert.Equal("100", metadata.GetProperty("X-Upload-Batch-Bytes").GetString());
        Assert.Equal("true", metadata.GetProperty("X-Upload-Folder").GetString());
        Assert.DoesNotContain(Capability, lease.ToString());
        Assert.DoesNotContain(host.Calls, call => call.Operation == "request" &&
            call.Parameters!.Value.GetProperty("endpoint").GetString()!.StartsWith("/api/resources", StringComparison.Ordinal));
    }

    [Theory]
    [InlineData(false, false, false)]
    [InlineData(true, false, true)]
    public async Task MissingRequiredCreationOrOverwritePermissionRejectsBeforeLease(bool create, bool modify, bool overwrite)
    {
        await using var host = new UploadHost { Create = create, Modify = modify };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);

        var error = await Assert.ThrowsAsync<FilewayException>(() => services.AcquireUploadAsync(Request(session) with { Overwrite = overwrite }, Token));

        Assert.Equal(ErrorCode.PermissionDenied, error.Error.Code);
        Assert.DoesNotContain(host.Calls, call => call.Operation == "upload_lease");
        Assert.DoesNotContain(host.Calls, call => call.Operation == "close_session");
    }

    [Theory]
    [InlineData("root")]
    [InlineData("directory")]
    [InlineData("traversal")]
    [InlineData("negative-length")]
    [InlineData("empty-transfer")]
    [InlineData("empty-batch")]
    [InlineData("zero-items")]
    [InlineData("negative-total")]
    [InlineData("null-name")]
    [InlineData("crlf-name")]
    [InlineData("utf8-limit")]
    [InlineData("invalid-utf16")]
    public async Task InvalidTargetsOrStrongMetadataNeverReachHost(string scenario)
    {
        await using var host = new UploadHost();
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        var batch = new UploadBatchMetadata(Batch, "batch", 1, 1);
        var request = Request(session) with { Batch = batch };
        request = scenario switch
        {
            "root" => request with { Target = new RemoteResourceRef(session.Account, "/", "root", "/") },
            "directory" => request with { Target = new RemoteResourceRef(session.Account, "/dir/", "dir", "/dir") },
            "traversal" => request with { Target = new RemoteResourceRef(session.Account, "/%2e%2e/file", "file", "/file") },
            "negative-length" => request with { Length = -1 },
            "empty-transfer" => request with { TransferId = Guid.Empty },
            "empty-batch" => request with { Batch = batch with { Id = Guid.Empty } },
            "zero-items" => request with { Batch = batch with { ItemCount = 0 } },
            "negative-total" => request with { Batch = batch with { TotalBytes = -1 } },
            "null-name" => request with { Batch = batch with { Name = null! } },
            "crlf-name" => request with { Batch = batch with { Name = "a\r\nb" } },
            "utf8-limit" => request with { Batch = batch with { Name = new string('中', 114) } },
            _ => request with { Batch = batch with { Name = "\uD800" } },
        };

        var error = await Assert.ThrowsAsync<FilewayException>(() => services.AcquireUploadAsync(request, Token));

        Assert.Equal(ErrorCode.InvalidRequest, error.Error.Code);
        Assert.DoesNotContain(host.Calls, call => call.Operation == "upload_lease");
    }

    [Theory]
    [InlineData("https://127.0.0.1:32450/stream/cap")]
    [InlineData("http://localhost:32450/stream/cap")]
    [InlineData("http://127.0.0.1/stream/cap")]
    [InlineData("http://127.0.0.1:80/stream/cap")]
    [InlineData("http://127.0.0.1:0/stream/cap")]
    [InlineData("http://user@127.0.0.1:32450/stream/cap")]
    [InlineData("http://127.0.0.1:32450/stream/cap?x=1")]
    [InlineData("http://127.0.0.1:32450/stream/cap#fragment")]
    [InlineData("http://127.0.0.1:32450/stream/")]
    [InlineData("http://127.0.0.1:32450/stream/cap/extra")]
    [InlineData("http://127.0.0.1:32450/stream/%61")]
    [InlineData("http://127.0.0.1:32450/stream/..")]
    public async Task NonCapabilityAddressesAreRejectedWithoutRevokeToUntrustedUrl(string address)
    {
        await using var host = new UploadHost { Address = address };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);

        var error = await Assert.ThrowsAsync<FilewayException>(() => services.AcquireUploadAsync(Request(session), Token));

        Assert.Equal(ErrorCode.ProtocolMismatch, error.Error.Code);
        Assert.DoesNotContain(address, error.ToString());
        Assert.DoesNotContain(host.Calls, call => call.Operation == "revoke");
    }

    [Fact]
    public async Task StatisticsUseOriginalCoreSessionWithoutRefreshAndNeverInferConfirmationFromCumulativeBytes()
    {
        await using var host = new UploadHost { Statistics = Stats(sent: 30, offset: -1, current: 4, size: 10) };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        await using var lease = await services.AcquireUploadAsync(Request(session), Token);
        host.CurrentToken = Jwt("would-require-refresh");
        host.FailUserRead = true;
        int tokenReads = host.Calls.Count(call => call.Operation == "token");
        int apiReads = host.Calls.Count(call => call.Operation == "request");

        var stats = Assert.IsType<UploadLeaseStatistics>(await lease.ReadUploadStatisticsAsync(Token));

        Assert.Equal(30, stats.SentBytes);
        Assert.Null(stats.ConfirmedOffset);
        Assert.Equal(4, stats.CurrentSent);
        Assert.Equal(10, stats.CurrentSize);
        Assert.Equal(2, stats.RequestId);
        Assert.Equal(TimeSpan.FromMilliseconds(12), stats.Elapsed);
        Assert.False(stats.Active);
        Assert.Equal(tokenReads, host.Calls.Count(call => call.Operation == "token"));
        Assert.Equal(apiReads, host.Calls.Count(call => call.Operation == "request"));
        var acquisition = Assert.Single(host.Calls, call => call.Operation == "upload_lease");
        var poll = Assert.Single(host.Calls, call => call.Operation == "upload_stats");
        Assert.Equal(acquisition.Session, poll.Session);
        Assert.Equal(Capability, poll.Parameters!.Value.GetProperty("url").GetString());
    }

    [Fact]
    public async Task ExplicitDisposalIsIdempotentAndRetirementHasDifferentStatisticsMeaning()
    {
        await using var host = new UploadHost();
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        var disposed = await services.AcquireUploadAsync(Request(session), Token);
        await disposed.DisposeAsync();
        await disposed.DisposeAsync();
        Assert.Null(await disposed.ReadUploadStatisticsAsync(Token));
        var revoke = Assert.Single(host.Calls, call => call.Operation == "revoke");
        Assert.Null(revoke.Session);
        Assert.Equal(HostCallSafety.Control, revoke.Safety);
        Assert.DoesNotContain(host.Calls, call => call.Operation == "upload_stats");

        var retired = await services.AcquireUploadAsync(Request(session), Token);
        await services.DisconnectAsync(session.Account, Token);
        var error = await Assert.ThrowsAsync<FilewayException>(() => retired.ReadUploadStatisticsAsync(Token));
        Assert.Equal(ErrorCode.SourceChanged, error.Error.Code);
        Assert.Equal(2, host.Calls.Count(call => call.Operation == "revoke"));
        await retired.DisposeAsync();
        Assert.Equal(2, host.Calls.Count(call => call.Operation == "revoke"));
        Assert.Null(await retired.ReadUploadStatisticsAsync(Token));
    }

    [Theory]
    [InlineData("lost-create")]
    [InlineData("lost-modify")]
    [InlineData("failed-refresh")]
    [InlineData("canceled")]
    public async Task CapturedCapabilityIsRevokedWhenPostResponseValidationFails(string scenario)
    {
        await using var host = new UploadHost { Modify = true };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        using var cancel = CancellationTokenSource.CreateLinkedTokenSource(Token);
        host.OnUpload = (_, _) =>
        {
            host.CurrentToken = Jwt("rotated");
            if (scenario == "lost-create") host.Create = false;
            if (scenario == "lost-modify") host.Modify = false;
            if (scenario == "failed-refresh") host.FailUserRead = true;
            if (scenario == "canceled") cancel.Cancel();
            return Task.FromResult(Reply(Capability));
        };

        var error = await Assert.ThrowsAsync<FilewayException>(() => services.AcquireUploadAsync(
            Request(session) with { Overwrite = scenario == "lost-modify" }, cancel.Token));

        Assert.Equal(scenario switch
        {
            "canceled" => ErrorCode.Canceled,
            "failed-refresh" => ErrorCode.NetworkUnavailable,
            _ => ErrorCode.PermissionDenied,
        }, error.Error.Code);
        var revoke = Assert.Single(host.Calls, call => call.Operation == "revoke");
        Assert.Equal(Capability, revoke.Parameters!.Value.GetProperty("url").GetString());
        Assert.Null(revoke.Session);
        Assert.Single(host.Calls, call => call.Operation == "upload_lease");
    }

    [Fact]
    public async Task LateReturnedCapabilityAfterReconnectIsRevokedAndCannotAttachToReplacementSession()
    {
        await using var host = new UploadHost();
        await using var services = new FilewayClientServices(host);
        var first = await Connect(services);
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource<HostReply>(TaskCreationOptions.RunContinuationsAsynchronously);
        host.OnUpload = (_, _) => { entered.SetResult(); return release.Task; };
        var original = Request(first);
        var pending = services.AcquireUploadAsync(original, Token);
        await entered.Task.WaitAsync(Token);
        var replacement = await Connect(services);
        release.SetResult(Reply(Capability));

        var error = await Assert.ThrowsAsync<FilewayException>(() => pending);

        Assert.Equal(ErrorCode.SourceChanged, error.Error.Code);
        Assert.Single(host.Calls, call => call.Operation == "revoke");
        Assert.Single(host.Calls, call => call.Operation == "close_session");
        host.OnUpload = null;
        await using var fresh = await services.AcquireUploadAsync(Request(replacement), Token);
        var stale = await Assert.ThrowsAsync<FilewayException>(() => services.AcquireUploadAsync(original, Token));
        Assert.Equal(ErrorCode.SourceChanged, stale.Error.Code);
        Assert.Equal(2, host.Calls.Count(call => call.Operation == "upload_lease"));
    }

    [Fact]
    public async Task ControlTimeoutClosesOriginalSessionAndDoesNotReplayAcquisition()
    {
        await using var host = new UploadHost
        {
            OnUpload = (_, _) => Task.FromException<HostReply>(new FilewayException(new ErrorInfo(ErrorCode.Timeout, "fixture timeout"))),
        };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        var request = Request(session);

        var error = await Assert.ThrowsAsync<FilewayException>(() => services.AcquireUploadAsync(request, Token));

        Assert.Equal(ErrorCode.Timeout, error.Error.Code);
        var acquisition = Assert.Single(host.Calls, call => call.Operation == "upload_lease");
        var close = Assert.Single(host.Calls, call => call.Operation == "close_session");
        Assert.Equal(acquisition.Session, close.Session);
        Assert.DoesNotContain(host.Calls, call => call.Operation == "revoke");
        var stale = await Assert.ThrowsAsync<FilewayException>(() => services.AcquireUploadAsync(request, Token));
        Assert.Equal(ErrorCode.SourceChanged, stale.Error.Code);
    }

    [Fact]
    public async Task PresendCancellationLeavesExistingLeaseActive()
    {
        await using var host = new UploadHost();
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        await using var existing = await services.AcquireUploadAsync(Request(session), Token);
        using var canceled = CancellationTokenSource.CreateLinkedTokenSource(Token);
        await canceled.CancelAsync();

        var error = await Assert.ThrowsAsync<FilewayException>(() => services.AcquireUploadAsync(Request(session), canceled.Token));

        Assert.Equal(ErrorCode.Canceled, error.Error.Code);
        Assert.Single(host.Calls, call => call.Operation == "upload_lease");
        Assert.DoesNotContain(host.Calls, call => call.Operation == "close_session");
        Assert.IsType<UploadLeaseStatistics>(await existing.ReadUploadStatisticsAsync(Token));
    }

    [Fact]
    public async Task ResumePreservesTransferIdentityButNewLeaseStartsWithoutConfirmedOffset()
    {
        await using var host = new UploadHost { Statistics = Stats(offset: 10) };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        var original = Request(session);
        var first = await services.AcquireUploadAsync(original, Token);
        Assert.Equal(10, (await first.ReadUploadStatisticsAsync(Token))!.ConfirmedOffset);
        await first.DisposeAsync();
        host.Statistics = Stats(offset: -1, size: -1);

        await using var resumed = await services.AcquireUploadAsync(original with { Resume = true }, Token);
        var stats = Assert.IsType<UploadLeaseStatistics>(await resumed.ReadUploadStatisticsAsync(Token));

        Assert.Null(stats.ConfirmedOffset);
        Assert.Null(stats.CurrentSize);
        Assert.Equal(Transfer, resumed.TransferId);
        Assert.True(resumed.IsResume);
        var last = host.Calls.Last(call => call.Operation == "upload_lease").Parameters!.Value;
        Assert.Equal(original.Target.WirePath, last.GetProperty("wirePath").GetString());
        Assert.Equal(Transfer.ToString("N"), last.GetProperty("upload").GetProperty("transferId").GetString());
        Assert.Empty(last.GetProperty("upload").GetProperty("metadata").EnumerateObject());
    }

    private static string[] Keys(JsonElement value) => value.EnumerateObject().Select(property => property.Name).Order(StringComparer.Ordinal).ToArray();
    private static UploadLeaseRequest Request(ConnectionSession session) => new(new RemoteResourceRef(session.Account, "/target.bin", "target.bin", "/target.bin"), 10, Transfer);
    private static Task<ConnectionSession> Connect(FilewayClientServices services) => services.ConnectAsync(Profile, Login, Token);
    private static object Stats(long sent = 0, long offset = -1, long current = 0, long size = -1) => new
        { sentBytes = sent, acceptedOffset = offset, currentSent = current, currentSize = size, requestId = 2, elapsedMillis = 12, active = false };
    private static string Jwt(string revision = "initial") => "fixture." + Convert.ToBase64String(JsonSerializer.SerializeToUtf8Bytes(
        new { user = new { id = 7 }, revision })).TrimEnd('=').Replace('+', '-').Replace('/', '_') + ".signature";
    private static HostReply Reply(object response) => new("fixture", 0, true, JsonSerializer.SerializeToElement(response), null);
    private static object Http(object? value, int status = 200, string? raw = null) => new { status, body = raw ?? JsonSerializer.Serialize(value) };

    private sealed class UploadHost : IHostControlClient
    {
        private int _nextSession;
        internal bool Create { get; set; } = true;
        internal bool Modify { get; set; }
        internal bool FailUserRead { get; set; }
        internal string CurrentToken { get; set; } = Jwt();
        internal string Address { get; set; } = Capability;
        internal object Statistics { get; set; } = Stats();
        internal Func<HostCall, CancellationToken, Task<HostReply>>? OnUpload { get; set; }
        internal ConcurrentQueue<HostCall> Calls { get; } = new();
        public HostHandshake Handshake { get; } = new(1, 0, 1, "fixture", "fixture", "fixture", JsonSerializer.SerializeToElement(new { }));
        public Task<HostExit> Completion { get; } = Task.FromResult(new HostExit(true, 0, null));

        public Task<HostReply> CallAsync(HostCall request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Calls.Enqueue(request);
            if (request.Operation == "upload_lease" && OnUpload is not null) return OnUpload(request, cancellationToken);
            object response = request.Operation switch
            {
                "open" => "fixture-session-" + Interlocked.Increment(ref _nextSession).ToString(CultureInfo.InvariantCulture),
                "login" => Http(null, raw: CurrentToken),
                "token" => CurrentToken,
                "upload_lease" => Address,
                "upload_stats" => Statistics,
                "revoke" or "close_session" => new { },
                "request" => Api(request.Parameters!.Value),
                _ => throw new InvalidOperationException("Unexpected fixture command."),
            };
            return Task.FromResult(Reply(response));
        }

        private object Api(JsonElement parameters)
        {
            if (parameters.GetProperty("method").GetString() != "GET") throw new InvalidOperationException("Unexpected remote mutation.");
            return parameters.GetProperty("endpoint").GetString() switch
            {
                "/api/users/7" => FailUserRead ? Http(null, 503) : Http(new
                {
                    id = 7, username = "fixture", perm = new
                    { download = false, create = Create, rename = false, modify = Modify, delete = false, execute = false },
                }),
                "/api/client-capabilities" => Http(new { authMethod = "json", resourceWireOperations = false }),
                _ => throw new InvalidOperationException("Unexpected metadata or content request."),
            };
        }

        public Task ShutdownAsync(CancellationToken cancellationToken) => Task.CompletedTask;
        public ValueTask DisposeAsync() => ValueTask.CompletedTask;
    }
}
