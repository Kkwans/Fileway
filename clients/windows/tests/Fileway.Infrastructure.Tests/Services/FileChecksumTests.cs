using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Services;

namespace Fileway.Infrastructure.Tests.Services;

public sealed class FileChecksumTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;
    private const string Wire = "/%FF/literal%252F%2B.bin";

    [Theory]
    [InlineData(ChecksumAlgorithm.Md5, "md5", 32)]
    [InlineData(ChecksumAlgorithm.Sha1, "sha1", 40)]
    [InlineData(ChecksumAlgorithm.Sha256, "sha256", 64)]
    [InlineData(ChecksumAlgorithm.Sha512, "sha512", 128)]
    public async Task AllAlgorithmsNormalizeHexAndPreserveTheRequestedOpaqueIdentity(ChecksumAlgorithm algorithm,
        string key, int length)
    {
        await using var host = ChecksumHost(key, new string('A', length));
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var resource = Resource(session);

        var result = await services.CalculateChecksumAsync(resource, algorithm, new string('a', length), Token);

        Assert.Same(resource, result.Resource);
        Assert.Equal(algorithm, result.Algorithm);
        Assert.Equal(new string('a', length), result.Digest);
        Assert.Equal(ChecksumComparison.Matches, result.Comparison);
        Assert.Equal(17, result.Size);
        Assert.NotNull(result.Modified);
        var call = Assert.Single(HashCalls(host));
        Assert.Equal(TimeSpan.FromMinutes(16), call.Timeout);
        Assert.Equal(HostCallSafety.ReadOnly, call.Safety);
        Assert.Equal("/api/resources" + Wire + "?metadata=1&checksum=" + key, CoreReadHost.Endpoint(call));
        Assert.Equal("GET", call.Parameters!.Value.GetProperty("method").GetString());
        Assert.False(call.Parameters.Value.TryGetProperty("body", out _));
        Assert.Equal(2, host.Calls.Count(item => item.Operation == "request" &&
            CoreReadHost.Endpoint(item) == "/api/resources" + Wire + "?metadata=1"));
    }

    [Theory]
    [InlineData(ChecksumComparison.NotRequested)]
    [InlineData(ChecksumComparison.Differs)]
    public async Task ComparisonDoesNotPretendThatAnUnrequestedOrDifferentDigestMatches(ChecksumComparison expected)
    {
        await using var host = ChecksumHost();
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var result = await services.CalculateChecksumAsync(Resource(session), ChecksumAlgorithm.Sha256,
            expected == ChecksumComparison.NotRequested ? null : new string('b', 64), Token);

        Assert.Equal(expected, result.Comparison);
    }

    [Theory]
    [InlineData(false, false, ErrorCode.PermissionDenied)]
    [InlineData(true, true, ErrorCode.InvalidRequest)]
    public async Task ContentPermissionAndFileTypeAreCheckedBeforeStartingHash(bool download, bool directory,
        ErrorCode expected)
    {
        await using var host = ChecksumHost(directory: directory);
        host.Download = download;
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var error = await Assert.ThrowsAsync<FilewayException>(() => services.CalculateChecksumAsync(Resource(session),
            ChecksumAlgorithm.Sha256, null, Token));

        Assert.Equal(expected, error.Error.Code);
        Assert.Empty(HashCalls(host));
    }

    [Theory]
    [InlineData("wrong-length")]
    [InlineData("not-hex")]
    [InlineData("missing-algorithm")]
    public async Task MalformedServerDigestIsRejected(string kind)
    {
        await using var host = ChecksumHost();
        var initial = host.Dispatch!;
        host.Dispatch = (call, token) => IsHash(call)
            ? Task.FromResult(CoreReadHost.Http(CoreReadHost.Metadata(Wire, checksums: kind == "missing-algorithm"
                ? new Dictionary<string, string>() : new Dictionary<string, string> { ["sha256"] = kind == "not-hex" ? new string('g', 64) : "abc" })))
            : initial(call, token);
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var error = await Assert.ThrowsAsync<FilewayException>(() => services.CalculateChecksumAsync(Resource(session),
            ChecksumAlgorithm.Sha256, null, Token));

        Assert.Equal(ErrorCode.ProtocolMismatch, error.Error.Code);
        Assert.Single(HashCalls(host));
    }

    [Theory]
    [InlineData("identity", ErrorCode.ProtocolMismatch)]
    [InlineData("size", ErrorCode.Conflict)]
    [InlineData("modified", ErrorCode.Conflict)]
    [InlineData("after-read", ErrorCode.Conflict)]
    public async Task IdentityAndMetadataMustStillMatchBeforeReturningTheDigest(string change, ErrorCode expected)
    {
        int metadataReads = 0;
        await using var host = ChecksumHost();
        host.Dispatch = (call, _) =>
        {
            bool hash = IsHash(call);
            if (!hash) Interlocked.Increment(ref metadataReads);
            bool changed = hash && change != "after-read" || !hash && metadataReads == 2 && change == "after-read";
            return Task.FromResult(CoreReadHost.Http(CoreReadHost.Metadata(
                changed && change == "identity" ? "/%FF/other.bin" : Wire,
                size: changed && change is ("size" or "after-read") ? 18 : 17,
                modified: changed && change == "modified" ? "2026-10-11T11:00:00.000000002Z" : "2026-10-11T11:00:00.000000001Z",
                checksums: hash ? new Dictionary<string, string> { ["sha256"] = new string('a', 64) } : null)));
        };
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var error = await Assert.ThrowsAsync<FilewayException>(() => services.CalculateChecksumAsync(Resource(session),
            ChecksumAlgorithm.Sha256, null, Token));

        Assert.Equal(expected, error.Error.Code);
        Assert.Single(HashCalls(host));
    }

    [Fact]
    public async Task ServerSourceChangedConflictIsNotReplayedOrExposedAsRawServerText()
    {
        await using var host = ChecksumHost();
        var initial = host.Dispatch!;
        host.Dispatch = (call, token) => IsHash(call)
            ? Task.FromResult(CoreReadHost.Http(null, 409, "fixture.private.server.response")) : initial(call, token);
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var error = await Assert.ThrowsAsync<FilewayException>(() => services.CalculateChecksumAsync(Resource(session),
            ChecksumAlgorithm.Sha256, null, Token));

        Assert.Equal(ErrorCode.Conflict, error.Error.Code);
        Assert.Equal(409, error.Error.HttpStatus);
        Assert.DoesNotContain("fixture.private", error.Error.Message);
        Assert.Single(HashCalls(host));
    }

    [Fact]
    public async Task InvalidExpectedDigestFailsBeforeAnyResourceRequest()
    {
        await using var host = ChecksumHost();
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        int before = host.Calls.Count;

        var error = await Assert.ThrowsAsync<FilewayException>(() => services.CalculateChecksumAsync(Resource(session),
            ChecksumAlgorithm.Sha256, "sha256:abc", Token));

        Assert.Equal(ErrorCode.InvalidRequest, error.Error.Code);
        Assert.Equal(before, host.Calls.Count);
    }

    [Fact]
    public async Task BlockedHashDoesNotHoldTheSessionOperationGate()
    {
        var started = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(Token);
        deadline.CancelAfter(TimeSpan.FromSeconds(10));
        await using var host = ChecksumHost();
        var initial = host.Dispatch!;
        host.Dispatch = async (call, token) =>
        {
            if (IsHash(call))
            {
                started.TrySetResult();
                await release.Task.WaitAsync(token);
            }
            if (call.Operation == "request" && CoreReadHost.Endpoint(call) == "/api/resources/")
                return CoreReadHost.Http(CoreReadHost.Metadata("/", directory: true, size: 0));
            return await initial(call, token);
        };
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var root = await services.GetRootAsync(session, Token);
        var hash = services.CalculateChecksumAsync(Resource(session), ChecksumAlgorithm.Sha256, null, deadline.Token);
        await started.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);

        var listing = await services.ListAsync(root, new DirectoryQuery(), Token).WaitAsync(TimeSpan.FromSeconds(5), Token);

        Assert.Empty(listing.Items);
        Assert.False(hash.IsCompleted);
        release.TrySetResult();
        Assert.Equal(new string('a', 64), (await hash.WaitAsync(TimeSpan.FromSeconds(5), Token)).Digest);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task CancelOrSessionReplacementInterruptsTheOriginalLongRead(bool replaceSession)
    {
        var started = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        using var cancellation = CancellationTokenSource.CreateLinkedTokenSource(Token);
        cancellation.CancelAfter(TimeSpan.FromSeconds(10));
        await using var host = ChecksumHost();
        var initial = host.Dispatch!;
        host.Dispatch = async (call, token) =>
        {
            if (!IsHash(call)) return await initial(call, token);
            started.TrySetResult();
            await Task.Delay(Timeout.InfiniteTimeSpan, token);
            throw new InvalidOperationException("Unreachable canceled hash.");
        };
        await using var services = new FilewayClientServices(host);
        var profile = CoreReadHost.Profile();
        var session = await services.ConnectAsync(profile, CoreReadHost.Login, Token);
        var hash = services.CalculateChecksumAsync(Resource(session), ChecksumAlgorithm.Sha256, null, cancellation.Token);
        await started.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);

        if (replaceSession) _ = await services.ConnectAsync(profile, CoreReadHost.Login, Token);
        else cancellation.Cancel();

        var error = await Assert.ThrowsAsync<FilewayException>(() => hash.WaitAsync(TimeSpan.FromSeconds(5), Token));
        Assert.Equal(replaceSession ? ErrorCode.SourceChanged : ErrorCode.Canceled, error.Error.Code);
        Assert.Single(HashCalls(host));
    }

    [Fact]
    public async Task PermissionWithdrawalDuringHashPreventsPublishingTheDigest()
    {
        await using var host = ChecksumHost();
        var initial = host.Dispatch!;
        host.Dispatch = (call, token) =>
        {
            if (IsHash(call))
            {
                host.CurrentToken = CoreReadHost.Jwt("permission-withdrawn");
                host.Download = false;
            }
            return initial(call, token);
        };
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var error = await Assert.ThrowsAsync<FilewayException>(() => services.CalculateChecksumAsync(Resource(session),
            ChecksumAlgorithm.Sha256, null, Token));

        Assert.Equal(ErrorCode.PermissionDenied, error.Error.Code);
        Assert.Single(HashCalls(host));
    }

    private static CoreReadHost ChecksumHost(string key = "sha256", string? digest = null, bool directory = false)
    {
        var host = new CoreReadHost();
        host.Dispatch = (call, _) => Task.FromResult(CoreReadHost.Http(CoreReadHost.Metadata(Wire, directory,
            checksums: IsHash(call) ? new Dictionary<string, string> { [key] = digest ?? new string('a', 64) } : null)));
        return host;
    }
    private static bool IsHash(HostCall call) => call.Operation == "request" && CoreReadHost.Endpoint(call).Contains("checksum=", StringComparison.Ordinal);
    private static IEnumerable<HostCall> HashCalls(CoreReadHost host) => host.Calls.Where(IsHash);
    private static RemoteResourceRef Resource(ConnectionSession session) => new(session.Account, Wire, "literal%2F+.bin", "/范围/literal%2F+.bin");
}
