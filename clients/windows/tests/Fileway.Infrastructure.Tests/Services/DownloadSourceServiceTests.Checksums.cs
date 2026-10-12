using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Services;

namespace Fileway.Infrastructure.Tests.Services;

public sealed partial class DownloadSourceServiceTests
{
    [Fact]
    public async Task FreshChecksumRetainsExistingSha256BudgetAndThreeMetadataObservations()
    {
        await using var host = DownloadHost();
        host.Dispatch = (call, _) => Task.FromResult(CoreReadHost.Http(Metadata(checksums: IsHash(call)
            ? new Dictionary<string, string> { ["sha256"] = new string('A', 64) } : null)));
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        var request = Request(session);

        var result = await Provider(services).CalculateDownloadChecksumAsync(request, Token);

        Assert.Same(request.Source, result.Resource);
        Assert.Equal(ChecksumAlgorithm.Sha256, result.Algorithm);
        Assert.Equal(new string('a', 64), result.Digest);
        Assert.Equal(ChecksumComparison.NotRequested, result.Comparison);
        Assert.Equal(request.Length, result.Size);
        Assert.NotNull(result.Modified);
        Assert.Equal(2, MetadataCalls(host).Count());
        var hash = Assert.Single(HashCalls(host));
        Assert.Equal("/api/resources" + Wire + "?metadata=1&checksum=sha256", CoreReadHost.Endpoint(hash));
        Assert.Equal(TimeSpan.FromMinutes(16), hash.Timeout);
        Assert.Equal(HostCallSafety.ReadOnly, hash.Safety);
        Assert.Equal("GET", hash.Parameters!.Value.GetProperty("method").GetString());
        Assert.False(hash.Parameters.Value.TryGetProperty("body", out _));
        Assert.Empty(LeaseCalls(host));
    }

    [Theory]
    [InlineData("hash-version", ErrorCode.CapabilityMissing)]
    [InlineData("after-version", ErrorCode.CapabilityMissing)]
    [InlineData("hash-size", ErrorCode.Conflict)]
    [InlineData("after-size", ErrorCode.Conflict)]
    [InlineData("modified-nanosecond", ErrorCode.Conflict)]
    [InlineData("hash-identity", ErrorCode.ProtocolMismatch)]
    [InlineData("bad-digest", ErrorCode.ProtocolMismatch)]
    public async Task HashAndFinalMetadataMustRemainUnversionedAndConsistent(string scenario, ErrorCode expected)
    {
        int metadataReads = 0;
        await using var host = DownloadHost();
        host.ConditionalDownloadV1 = true;
        host.Dispatch = (call, _) =>
        {
            bool hash = IsHash(call);
            if (!hash) metadataReads++;
            bool after = !hash && metadataReads == 2;
            bool changedSize = hash && scenario == "hash-size" || after && scenario == "after-size";
            bool version = hash && scenario == "hash-version" || after && scenario == "after-version";
            return Task.FromResult(CoreReadHost.Http(Metadata(size: changedSize ? 18 : 17,
                wire: hash && scenario == "hash-identity" ? "/other.bin" : Wire,
                modified: hash && scenario == "modified-nanosecond" ? "2026-10-11T11:00:00.000000002Z" : Modified,
                version: version ? "private-observed-version" : null,
                checksums: hash ? new Dictionary<string, string> { ["sha256"] = new string(scenario == "bad-digest" ? 'g' : 'a', 64) } : null)));
        };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);

        var error = await Assert.ThrowsAsync<FilewayException>(() => Provider(services).CalculateDownloadChecksumAsync(Request(session), Token));

        Assert.Equal(expected, error.Error.Code);
        Assert.DoesNotContain("private-observed-version", error.Message);
        Assert.Single(HashCalls(host));
        Assert.Empty(LeaseCalls(host));
    }

    [Fact]
    public async Task ServerChecksumConflictIsPreservedWithoutReplayOrPrivateResponseText()
    {
        await using var host = DownloadHost();
        var initial = host.Dispatch!;
        host.Dispatch = (call, token) => IsHash(call)
            ? Task.FromResult(CoreReadHost.Http(null, 409, "fixture.private.changed-source")) : initial(call, token);
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);

        var error = await Assert.ThrowsAsync<FilewayException>(() => Provider(services).CalculateDownloadChecksumAsync(Request(session), Token));

        Assert.Equal(ErrorCode.Conflict, error.Error.Code);
        Assert.Equal(409, error.Error.HttpStatus);
        Assert.DoesNotContain("fixture.private", error.Message);
        Assert.Single(HashCalls(host));
    }

    [Fact]
    public async Task LongHashDoesNotHoldTheSessionOperationGate()
    {
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(Token);
        deadline.CancelAfter(TimeSpan.FromSeconds(10));
        await using var host = DownloadHost();
        var initial = host.Dispatch!;
        host.Dispatch = async (call, token) =>
        {
            if (IsHash(call))
            {
                entered.TrySetResult();
                await release.Task.WaitAsync(token);
            }
            return await initial(call, token);
        };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        var hash = Provider(services).CalculateDownloadChecksumAsync(Request(session), deadline.Token);
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);

        var metadata = await Provider(services).PrepareDownloadAsync(Source(session), Token).WaitAsync(TimeSpan.FromSeconds(5), Token);

        Assert.Equal(17, metadata.Size);
        Assert.False(hash.IsCompleted);
        release.TrySetResult();
        Assert.Equal(new string('a', 64), (await hash.WaitAsync(TimeSpan.FromSeconds(5), Token)).Digest);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task CancelOrReconnectInterruptsOriginalLongHash(bool reconnect)
    {
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        using var cancellation = CancellationTokenSource.CreateLinkedTokenSource(Token);
        cancellation.CancelAfter(TimeSpan.FromSeconds(10));
        await using var host = DownloadHost();
        var initial = host.Dispatch!;
        host.Dispatch = async (call, token) =>
        {
            if (!IsHash(call)) return await initial(call, token);
            entered.TrySetResult();
            await Task.Delay(Timeout.InfiniteTimeSpan, token);
            throw new InvalidOperationException("Canceled checksum must not return a digest.");
        };
        await using var services = new FilewayClientServices(host);
        var profile = CoreReadHost.Profile();
        var session = await Connect(services, profile);
        var hash = Provider(services).CalculateDownloadChecksumAsync(Request(session), cancellation.Token);
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);

        if (reconnect) _ = await Connect(services, profile);
        else cancellation.Cancel();

        var error = await Assert.ThrowsAsync<FilewayException>(() => hash.WaitAsync(TimeSpan.FromSeconds(5), Token));
        Assert.Equal(reconnect ? ErrorCode.SourceChanged : ErrorCode.Canceled, error.Error.Code);
        Assert.Single(HashCalls(host));
    }

    [Fact]
    public async Task PermissionWithdrawalDuringHashPreventsDigestPublication()
    {
        await using var host = DownloadHost();
        var initial = host.Dispatch!;
        host.Dispatch = (call, token) =>
        {
            if (IsHash(call))
            {
                host.CurrentToken = CoreReadHost.Jwt("download-withdrawn");
                host.Download = false;
            }
            return initial(call, token);
        };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);

        var error = await Assert.ThrowsAsync<FilewayException>(() => Provider(services).CalculateDownloadChecksumAsync(Request(session), Token));

        Assert.Equal(ErrorCode.PermissionDenied, error.Error.Code);
        Assert.Single(HashCalls(host));
    }
}
