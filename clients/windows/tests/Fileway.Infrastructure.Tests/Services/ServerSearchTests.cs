using System.Collections.Concurrent;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Services;

namespace Fileway.Infrastructure.Tests.Services;

public sealed class ServerSearchTests
{
    private static readonly string[] ExpectedOpaqueWirePaths = ["/%FF/item%FE.bin", "/%ff/item%FF.bin"];
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Fact]
    public async Task VerifiedOpaqueHitsPreserveTwoIdentitiesWithTheSameDisplayAndDoNotRequireDownload()
    {
        var polls = new ConcurrentQueue<object>([
            Batch([Hit("item�.bin", "/%FF/item%FE.bin"), Hit("item�.bin", "/%ff/item%FF.bin")], 2),
            Batch([], 2, "finished", "completed", 2),
        ]);
        await using var host = SearchHost(polls);
        host.Download = false;
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var request = new SearchRequest(Base(session), "电影🎬 & + # type:bin", SearchScope.Current);

        var updates = await CollectAsync(services.SearchAsync(request, Token));

        var hits = updates.SelectMany(update => update.Items).ToArray();
        Assert.Equal(2, hits.Length);
        Assert.Equal(hits[0].RelativeDisplayPath, hits[1].RelativeDisplayPath);
        Assert.Equal(ExpectedOpaqueWirePaths, hits.Select(hit => hit.Resource!.WirePath));
        Assert.All(hits, hit => Assert.Equal(SearchPathConfidence.ServerWire, hit.PathConfidence));
        Assert.All(hits, hit => Assert.Equal(session.Account, hit.Resource!.Account));
        var end = Assert.Single(updates, update => update.End is not null);
        Assert.Equal(SearchEndReason.Completed, end.End!.Reason);
        Assert.Equal(2, end.ReceivedCount);
        var started = Assert.Single(host.Calls, call => call.Operation == "search_start");
        Assert.Equal("/%FF", started.Parameters!.Value.GetProperty("wirePath").GetString());
        Assert.Equal(request.Query, started.Parameters.Value.GetProperty("query").GetString());
        Assert.Equal("current", started.Parameters.Value.GetProperty("scope").GetString());
        Assert.Equal(started.Session, Assert.Single(host.Calls, call => call.Operation == "search_cancel").Session);
    }

    [Theory]
    [InlineData(false, "literal%2F+.bin", null, false, SearchPathConfidence.LegacyUtf8, "/%FF/literal%252F%2B.bin")]
    [InlineData(false, "item�.bin", null, false, SearchPathConfidence.DisplayOnly, null)]
    [InlineData(true, "child.bin", null, false, SearchPathConfidence.DisplayOnly, null)]
    [InlineData(true, "child.bin", "/other/child.bin", true, SearchPathConfidence.DisplayOnly, null)]
    [InlineData(true, "child.bin", "/%FF/other.bin", true, SearchPathConfidence.DisplayOnly, null)]
    [InlineData(false, "child.bin", "/%FF/child.bin", false, SearchPathConfidence.DisplayOnly, null)]
    [InlineData(true, "child.bin", "/%FF/child%2F.bin", true, SearchPathConfidence.DisplayOnly, null)]
    [InlineData(true, "child.bin", "/%FF/%2e%2e/child.bin", true, SearchPathConfidence.DisplayOnly, null)]
    public async Task WireClaimsNeverDowngradeToGuessedDisplayPaths(bool capability, string relative, string? wire,
        bool verified, SearchPathConfidence confidence, string? expectedWire)
    {
        var polls = new ConcurrentQueue<object>([Batch([Hit(relative, wire, verified)], 1, "finished", "completed", 1)]);
        await using var host = SearchHost(polls);
        host.SearchWirePathV1 = capability;
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var updates = await CollectAsync(services.SearchAsync(new SearchRequest(Base(session), "item"), Token));

        var hit = Assert.Single(updates.SelectMany(update => update.Items));
        Assert.Equal(confidence, hit.PathConfidence);
        Assert.Equal(expectedWire, hit.Resource?.WirePath);
        Assert.Equal(relative, hit.RelativeDisplayPath);
    }

    [Theory]
    [InlineData("completed", SearchEndReason.Completed, 1)]
    [InlineData("limit", SearchEndReason.LimitReached, 1)]
    [InlineData("timeout", SearchEndReason.ServerTimeout, 2)]
    [InlineData("canceled", SearchEndReason.Canceled, 2)]
    public async Task PartialResultsAndActualReceivedCountSurviveEveryServerTerminal(string reason,
        SearchEndReason expected, long serverCount)
    {
        var polls = new ConcurrentQueue<object>([
            Batch([Hit("literal%2F+.bin", "/%FF/literal%252F%2B.bin")], 1),
            Batch([], 1, "finished", reason, serverCount),
        ]);
        await using var host = SearchHost(polls);
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var updates = await CollectAsync(services.SearchAsync(new SearchRequest(Base(session), "item", SearchScope.Recursive), Token));

        Assert.Single(updates.SelectMany(update => update.Items));
        var terminal = Assert.Single(updates, update => update.End is not null);
        Assert.Equal(expected, terminal.End!.Reason);
        Assert.Equal(serverCount, terminal.End.ServerCount);
        Assert.Equal(1, terminal.ReceivedCount);
    }

    [Theory]
    [InlineData("finished", null, ErrorCode.ProtocolMismatch)]
    [InlineData("failed", 401, ErrorCode.AuthenticationExpired)]
    [InlineData("failed", 403, ErrorCode.PermissionDenied)]
    [InlineData("failed", 200, ErrorCode.ProtocolMismatch)]
    public async Task FailedOrMissingSummaryCannotBeReportedAsCompleted(string state, int? status, ErrorCode expected)
    {
        var polls = new ConcurrentQueue<object>([
            Batch([Hit("child.bin", "/%FF/child.bin")], 1),
            new { items = Array.Empty<object>(), count = 1, done = true, state, httpStatus = status,
                failure = "fixture.private.server.path" },
        ]);
        await using var host = SearchHost(polls);
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var updates = await CollectAsync(services.SearchAsync(new SearchRequest(Base(session), "child"), Token));

        Assert.Single(updates.SelectMany(update => update.Items));
        var terminal = Assert.Single(updates, update => update.End is not null);
        Assert.Equal(SearchEndReason.Failed, terminal.End!.Reason);
        Assert.Equal(expected, terminal.End.Error!.Code);
        Assert.DoesNotContain("fixture.private", terminal.End.Error.Message);
        Assert.Single(host.Calls, call => call.Operation == "search_cancel");
    }

    [Fact]
    public async Task CancelWhilePollingPreservesEarlierHitsAndReleasesTheOriginalHandle()
    {
        using var cancellation = CancellationTokenSource.CreateLinkedTokenSource(Token);
        var polling = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        int polls = 0;
        await using var host = SearchHost(new ConcurrentQueue<object>());
        var initial = host.Dispatch!;
        host.Dispatch = async (call, token) =>
        {
            if (call.Operation != "search_poll") return await initial(call, token);
            if (Interlocked.Increment(ref polls) == 1) return Batch([Hit("child.bin", "/%FF/child.bin")], 1);
            polling.TrySetResult();
            await Task.Delay(Timeout.InfiniteTimeSpan, token);
            throw new InvalidOperationException("Unreachable canceled poll.");
        };
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        await using var iterator = services.SearchAsync(new SearchRequest(Base(session), "child"), cancellation.Token).GetAsyncEnumerator(cancellation.Token);
        Assert.True(await iterator.MoveNextAsync());
        Assert.Single(iterator.Current.Items);

        var next = iterator.MoveNextAsync().AsTask();
        await polling.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        cancellation.Cancel();

        Assert.True(await next.WaitAsync(TimeSpan.FromSeconds(5), Token));
        Assert.Equal(SearchEndReason.Canceled, iterator.Current.End!.Reason);
        Assert.Equal(1, iterator.Current.ReceivedCount);
        Assert.False(await iterator.MoveNextAsync());
        Assert.Single(host.Calls, call => call.Operation == "search_cancel");
    }

    [Fact]
    public async Task DisposingTheIteratorCancelsWithoutWaitingForAnotherPoll()
    {
        await using var host = SearchHost(new ConcurrentQueue<object>([Batch([Hit("child.bin", "/%FF/child.bin")], 1)]));
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);
        var iterator = services.SearchAsync(new SearchRequest(Base(session), "child"), Token).GetAsyncEnumerator(Token);
        Assert.True(await iterator.MoveNextAsync());

        await iterator.DisposeAsync();

        var cancel = Assert.Single(host.Calls, call => call.Operation == "search_cancel");
        Assert.Equal(TimeSpan.FromSeconds(2), cancel.Timeout);
        Assert.Single(host.Calls, call => call.Operation == "search_poll");
    }

    [Fact]
    public async Task ReconnectionRejectsTheOldIteratorAndItsResources()
    {
        await using var host = SearchHost(new ConcurrentQueue<object>([Batch([Hit("child.bin", "/%FF/child.bin")], 1)]));
        await using var services = new FilewayClientServices(host);
        var profile = CoreReadHost.Profile();
        var oldSession = await services.ConnectAsync(profile, CoreReadHost.Login, Token);
        await using var iterator = services.SearchAsync(new SearchRequest(Base(oldSession), "child"), Token).GetAsyncEnumerator(Token);
        Assert.True(await iterator.MoveNextAsync());
        var oldResource = Assert.Single(iterator.Current.Items).Resource!;
        var firstStart = Assert.Single(host.Calls, call => call.Operation == "search_start");

        var replacement = await services.ConnectAsync(profile, CoreReadHost.Login, Token);

        Assert.NotEqual(oldSession.Id, replacement.Id);
        Assert.True(await iterator.MoveNextAsync());
        Assert.Equal(SearchEndReason.SessionChanged, iterator.Current.End!.Reason);
        Assert.Empty(iterator.Current.Items);
        Assert.False(await iterator.MoveNextAsync());
        var failure = await Assert.ThrowsAsync<FilewayException>(() => services.GetAsync(oldResource, Token));
        Assert.Equal(ErrorCode.SourceChanged, failure.Error.Code);
        Assert.Equal(firstStart.Session, Assert.Single(host.Calls, call => call.Operation == "search_cancel").Session);
    }

    [Fact]
    public async Task StartedHandleIsReleasedWhenThePostStartPrincipalRefreshFails()
    {
        await using var host = SearchHost(new ConcurrentQueue<object>());
        var initial = host.Dispatch!;
        host.Dispatch = (call, token) =>
        {
            if (call.Operation == "search_start")
            {
                host.CurrentToken = CoreReadHost.Jwt("changed");
                host.UserReadStatus = 503;
            }
            return initial(call, token);
        };
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var updates = await CollectAsync(services.SearchAsync(new SearchRequest(Base(session), "child"), Token));

        Assert.Single(host.Calls, call => call.Operation == "search_start");
        Assert.Single(host.Calls, call => call.Operation == "search_cancel");
        Assert.DoesNotContain(host.Calls, call => call.Operation == "search_poll");
        var end = Assert.Single(updates).End!;
        Assert.Equal(SearchEndReason.Failed, end.Reason);
        Assert.Equal(ErrorCode.NetworkUnavailable, end.Error!.Code);
    }

    [Theory]
    [InlineData("search_start")]
    [InlineData("search_poll")]
    public async Task DirectHostAuthenticationFailureKeepsItsCauseAfterTheHelperRetiresTheSession(string operation)
    {
        await using var host = SearchHost(new ConcurrentQueue<object>());
        host.ErrorForCall = call => call.Operation == operation
            ? new HostError("Unauthorized", "fixture.private.server.response", false, 401) : null;
        await using var services = new FilewayClientServices(host);
        var session = await services.ConnectAsync(CoreReadHost.Profile(), CoreReadHost.Login, Token);

        var updates = await CollectAsync(services.SearchAsync(new SearchRequest(Base(session), "child"), Token));

        var end = Assert.Single(updates).End!;
        Assert.Equal(SearchEndReason.Failed, end.Reason);
        Assert.Equal(ErrorCode.AuthenticationExpired, end.Error!.Code);
        Assert.DoesNotContain("fixture.private", end.Error.Message);
    }

    private static CoreReadHost SearchHost(ConcurrentQueue<object> polls)
    {
        var host = new CoreReadHost();
        host.Dispatch = (call, _) => Task.FromResult(call.Operation switch
        {
            "search_start" => (object)"fixture-search",
            "search_poll" => polls.TryDequeue(out var batch) ? batch : Batch([], 0),
            "search_cancel" => new { },
            "request" => CoreReadHost.Http(CoreReadHost.Metadata(CoreReadHost.Endpoint(call)["/api/resources".Length..].Split('?')[0], directory: true)),
            _ => throw new InvalidOperationException("Unexpected fixture command."),
        });
        return host;
    }

    private static object Hit(string relative, string? wire, bool verified = true) => new
    {
        path = relative, name = relative.Split('/')[^1], dir = false, size = 17,
        modified = "2026-10-11T11:00:00Z", riskLevel = "normal", wirePath = wire, pathVerified = verified,
    };
    private static object Batch(object[] items, long count, string state = "running", string? reason = null, long? serverCount = null) => new
    {
        items, count, state, done = state != "running", summary = reason is null ? null : new { reason, count = serverCount ?? count },
    };
    private static RemoteResourceRef Base(ConnectionSession session) => new(session.Account, "/%FF", "范围", "/范围");
    private static async Task<List<SearchUpdate>> CollectAsync(IAsyncEnumerable<SearchUpdate> stream)
    {
        var updates = new List<SearchUpdate>();
        await foreach (var update in stream) updates.Add(update);
        return updates;
    }
}
