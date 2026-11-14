using System.Collections.Concurrent;
using System.Runtime.CompilerServices;
using System.Threading.Channels;
using Fileway.App;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Tests.Search;

public sealed class SearchResultsControllerTests
{
    private static readonly AccountKey Account = new(Guid.NewGuid(), Guid.NewGuid(), "search-fixture");
    private static readonly AccountKey OtherAccount = new(Guid.NewGuid(), Guid.NewGuid(), "other-fixture");
    private static CancellationToken Token => TestContext.Current.CancellationToken;
    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(10);

    [Theory]
    [InlineData(SearchScope.Current)]
    [InlineData(SearchScope.Recursive)]
    public async Task ExplicitSearchKeepsExactBaseQueryAndScopeWithoutDownloadPermissionOrWrites(SearchScope scope)
    {
        using var fixture = new Fixture();
        var hit = Hit("literal%2F+.txt", "/scope/literal%252F%2B.txt");
        fixture.Services.Updates = [new([hit], 1, new(SearchEndReason.Completed, 1))];
        const string query = "  exact + % query  ";

        await fixture.Controller.SearchAsync(query, scope, Token);

        var call = Assert.Single(fixture.Services.Searches);
        Assert.Same(fixture.Directory, call.Request.Base);
        Assert.Equal(query, call.Request.Query);
        Assert.Equal(scope, call.Request.Scope);
        Assert.Same(hit, Assert.Single(fixture.Controller.Items));
        Assert.Equal(1, fixture.Controller.ReceivedCount);
        Assert.Equal(SearchEndReason.Completed, fixture.Controller.End!.Reason);
        Assert.False(fixture.Controller.IsSearching);
        Assert.Null(fixture.Controller.Error);
        Assert.Empty(fixture.Services.Gets);
        Assert.Equal(0, fixture.Services.Writes);
        Assert.Equal(1, fixture.Services.IteratorDisposals);
    }

    [Fact]
    public async Task StreamingPublishesOriginalRowsAndImmutablePartialSnapshots()
    {
        using var fixture = new Fixture();
        var script = fixture.Script();
        var first = Hit("same.txt", "/scope/%D6%D0%CE%C4.txt");
        var second = Hit("same.txt", "/scope/%E4%B8%AD%E6%96%87.txt");
        var firstSeen = Signal();
        fixture.Controller.Changed += (_, _) => { if (fixture.Controller.Items.Count == 1) firstSeen.TrySetResult(); };
        var pending = fixture.Controller.SearchAsync("query", SearchScope.Recursive, Token);
        await script.Entered.Task.WaitAsync(Timeout, Token);
        script.Send(new([first], 1));
        await firstSeen.Task.WaitAsync(Timeout, Token);
        var partial = fixture.Controller.Items;
        Assert.True(fixture.Controller.IsSearching);
        Assert.Null(fixture.Controller.End);
        Assert.True(fixture.Controller.CanOpen(first));
        script.Send(new([second], 2, new(SearchEndReason.Completed, 2)));
        await pending.WaitAsync(Timeout, Token); // Terminal disposes without another channel poll.

        Assert.Single(partial);
        Assert.Same(first, partial[0]);
        Assert.Equal(2, fixture.Controller.Items.Count);
        Assert.Same(first, fixture.Controller.Items[0]);
        Assert.Same(second, fixture.Controller.Items[1]);
        Assert.NotEqual(first.Resource!.WirePath, second.Resource!.WirePath);
        await script.Disposed.Task.WaitAsync(Timeout, Token);
    }

    [Theory]
    [InlineData(SearchEndReason.Completed)]
    [InlineData(SearchEndReason.LimitReached)]
    [InlineData(SearchEndReason.ServerTimeout)]
    [InlineData(SearchEndReason.Canceled)]
    [InlineData(SearchEndReason.Failed)]
    [InlineData(SearchEndReason.SessionChanged)]
    public async Task ExactTerminalReasonAndServerCountAreRetainedWithoutLeakingProviderMessages(SearchEndReason reason)
    {
        using var fixture = new Fixture();
        var hit = Hit();
        var error = reason is SearchEndReason.Failed or SearchEndReason.SessionChanged
            ? new ErrorInfo(reason == SearchEndReason.SessionChanged ? ErrorCode.SourceChanged : ErrorCode.NetworkUnavailable,
                "private source credential") : null;
        fixture.Services.Updates = [new([hit], 1, new(reason, 77, error))];

        await fixture.Controller.SearchAsync("query", SearchScope.Current, Token);

        Assert.Same(hit, Assert.Single(fixture.Controller.Items));
        Assert.Equal(reason, fixture.Controller.End!.Reason);
        Assert.Equal(77, fixture.Controller.End.ServerCount);
        Assert.False(fixture.Controller.IsSearching);
        Assert.DoesNotContain("private", fixture.Controller.End.Error?.Message ?? "");
        Assert.DoesNotContain("private", fixture.Controller.Error ?? "");
        Assert.Equal(reason != SearchEndReason.SessionChanged, fixture.Controller.CanOpen(hit));
    }

    [Fact]
    public async Task ExplicitCancelKeepsPartialRowsAndRejectsLateNoncooperativeBatches()
    {
        using var fixture = new Fixture();
        var script = fixture.Script();
        var known = Hit("known");
        var seen = Signal();
        fixture.Controller.Changed += (_, _) => { if (fixture.Controller.Items.Count == 1) seen.TrySetResult(); };
        var pending = fixture.Controller.SearchAsync("first", SearchScope.Current, Token);
        script.Send(new([known], 1));
        await seen.Task.WaitAsync(Timeout, Token);

        fixture.Controller.Cancel();

        Assert.True(Assert.Single(fixture.Services.Searches).Token.IsCancellationRequested);
        Assert.False(fixture.Controller.IsSearching);
        Assert.Equal(SearchEndReason.Canceled, fixture.Controller.End!.Reason);
        Assert.Same(known, Assert.Single(fixture.Controller.Items));
        Assert.True(fixture.Controller.CanOpen(known));
        Assert.Null(fixture.Controller.Error);
        script.Send(new([Hit("late")], 2, new(SearchEndReason.Completed, 2)));
        script.Complete();
        await pending.WaitAsync(Timeout, Token);
        Assert.Same(known, Assert.Single(fixture.Controller.Items));
        Assert.Equal(SearchEndReason.Canceled, fixture.Controller.End!.Reason);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task NewQueryWinsOverOldLateSuccessOrError(bool oldFails)
    {
        using var fixture = new Fixture();
        var old = fixture.Script();
        var first = fixture.Controller.SearchAsync("first", SearchScope.Current, Token);
        await old.Entered.Task.WaitAsync(Timeout, Token);
        var newest = Hit("newest");
        fixture.Services.SearchBehavior = null;
        fixture.Services.Updates = [new([newest], 1, new(SearchEndReason.LimitReached, 1))];
        await fixture.Controller.SearchAsync("newest", SearchScope.Recursive, Token);
        if (oldFails) old.Complete(new IOException("private failure"));
        else { old.Send(new([Hit("old")], 1, new(SearchEndReason.Completed, 1))); old.Complete(); }
        await first.WaitAsync(Timeout, Token);

        Assert.Same(newest, Assert.Single(fixture.Controller.Items));
        Assert.Equal(SearchEndReason.LimitReached, fixture.Controller.End!.Reason);
        Assert.Null(fixture.Controller.Error);
        Assert.True(fixture.Services.Searches.First().Token.IsCancellationRequested);
    }

    [Theory]
    [InlineData("account")]
    [InlineData("session")]
    [InlineData("base")]
    [InlineData("search-service")]
    [InlineData("file-service")]
    [InlineData("disconnect")]
    public async Task ContextReplacementRetiresOldSearchAndClearsItsRows(string change)
    {
        using var fixture = new Fixture();
        var script = fixture.Script();
        var seen = Signal();
        fixture.Controller.Changed += (_, _) => { if (fixture.Controller.Items.Count != 0) seen.TrySetResult(); };
        var pending = fixture.Controller.SearchAsync("query", SearchScope.Current, Token);
        script.Send(new([Hit("known")], 1));
        await seen.Task.WaitAsync(Timeout, Token);
        long generation = fixture.Controller.Generation;

        ReplaceContext(fixture, change);

        Assert.True(fixture.Controller.Generation > generation);
        Assert.Empty(fixture.Controller.Items);
        Assert.Equal(0, fixture.Controller.ReceivedCount);
        Assert.False(fixture.Controller.IsSearching);
        Assert.True(Assert.Single(fixture.Services.Searches).Token.IsCancellationRequested);
        script.Send(new([Hit("late")], 2, new(SearchEndReason.Completed, 2)));
        script.Complete();
        await pending.WaitAsync(Timeout, Token);
        Assert.Empty(fixture.Controller.Items);
        Assert.Null(fixture.Controller.End);
        Assert.Null(fixture.Controller.Error);
    }

    [Theory]
    [InlineData("blank")]
    [InlineData("oversized")]
    [InlineData("surrogate")]
    [InlineData("scope")]
    public async Task InvalidQueryRetiresOldWorkWithoutCallingSearch(string change)
    {
        using var fixture = new Fixture();
        string query = change switch
        {
            "blank" => " \t ", "oversized" => new string('é', 4097),
            "surrogate" => new string((char)0xD800, 1), _ => "valid",
        };
        await fixture.Controller.SearchAsync(query, change == "scope" ? (SearchScope)999 : SearchScope.Current, Token);
        Assert.Empty(fixture.Services.Searches);
        Assert.Empty(fixture.Controller.Items);
        Assert.Equal(SearchEndReason.Failed, fixture.Controller.End!.Reason);
        Assert.Equal(ErrorCode.InvalidRequest, fixture.Controller.End.Error!.Code);
        Assert.False(fixture.Controller.IsSearching);
    }

    [Fact]
    public async Task ExactUtf8QueryBoundaryIsAcceptedAndNoContextStartsNothing()
    {
        using var fixture = new Fixture();
        await fixture.Controller.SearchAsync(new string('é', 4096), SearchScope.Current, Token);
        Assert.Single(fixture.Services.Searches);
        fixture.Controller.SetContext(fixture.Services, fixture.Services, fixture.Session,
            fixture.Directory with { Account = OtherAccount });
        await fixture.Controller.SearchAsync("query", SearchScope.Current, Token);
        Assert.Single(fixture.Services.Searches);
        Assert.Empty(fixture.Controller.Items);
    }

    [Theory]
    [InlineData("null-update")]
    [InlineData("null-list")]
    [InlineData("null-hit")]
    [InlineData("foreign")]
    [InlineData("negative-size")]
    [InlineData("count-regression")]
    [InlineData("count-short")]
    [InlineData("terminal-count")]
    [InlineData("end-kind")]
    [InlineData("server-count")]
    [InlineData("batch-limit")]
    public async Task InvalidBatchCannotPartiallyPublishRowsOrEraseEarlierEvidence(string scenario)
    {
        using var fixture = new Fixture();
        var known = Hit("known");
        var next = Hit("next");
        SearchUpdate bad = scenario switch
        {
            "null-update" => null!, "null-list" => new(null!, 2), "null-hit" => new([next, null!], 3),
            "foreign" => new([next, Hit("foreign") with { Resource = next.Resource! with { Account = OtherAccount } }], 3),
            "negative-size" => new([next, Hit("negative") with { Size = -1 }], 3),
            "count-regression" => new([], 0), "count-short" => new([next], 1),
            "terminal-count" => new([next], 3, new(SearchEndReason.Completed)),
            "end-kind" => new([next], 2, new((SearchEndReason)999)),
            "server-count" => new([next], 2, new(SearchEndReason.Completed, -1)),
            _ => new(Enumerable.Range(0, 33).Select(index => Hit(index.ToString(System.Globalization.CultureInfo.InvariantCulture))).ToArray(), 34),
        };
        fixture.Services.Updates = [new([known], 1), bad];
        await fixture.Controller.SearchAsync("query", SearchScope.Current, Token);
        Assert.Same(known, Assert.Single(fixture.Controller.Items));
        Assert.Equal(1, fixture.Controller.ReceivedCount);
        Assert.Equal(SearchEndReason.Failed, fixture.Controller.End!.Reason);
        Assert.Equal(ErrorCode.ProtocolMismatch, fixture.Controller.End.Error!.Code);
        Assert.Equal(0, fixture.Services.Writes);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task FailedStreamKeepsPartialResultsAndSafeError(bool typed)
    {
        using var fixture = new Fixture();
        var known = Hit();
        fixture.Services.SearchBehavior = (_, token) => FailAfter(known, typed, ErrorCode.NetworkUnavailable, token);
        await fixture.Controller.SearchAsync("query", SearchScope.Recursive, Token);
        Assert.Same(known, Assert.Single(fixture.Controller.Items));
        Assert.Equal(SearchEndReason.Failed, fixture.Controller.End!.Reason);
        Assert.NotNull(fixture.Controller.Error);
        Assert.DoesNotContain("private", fixture.Controller.Error);
        Assert.False(fixture.Controller.IsSearching);
    }

    [Theory]
    [InlineData(ErrorCode.NetworkUnavailable, SearchEndReason.Failed)]
    [InlineData(ErrorCode.Canceled, SearchEndReason.Canceled)]
    [InlineData(ErrorCode.SourceChanged, SearchEndReason.SessionChanged)]
    public async Task TypedProviderFailureKeepsItsExplicitReasonAndSafePartialResults(ErrorCode code, SearchEndReason expected)
    {
        using var fixture = new Fixture();
        var known = Hit();
        fixture.Services.SearchBehavior = (_, token) => FailAfter(known, true, code, token);
        await fixture.Controller.SearchAsync("query", SearchScope.Current, Token);
        Assert.Same(known, Assert.Single(fixture.Controller.Items));
        Assert.Equal(expected, fixture.Controller.End!.Reason);
        Assert.Equal(code, fixture.Controller.End.Error!.Code);
        Assert.DoesNotContain("private", fixture.Controller.End.Error.Message);
    }

    [Fact]
    public async Task StreamWithoutTerminalNeverFabricatesCompletion()
    {
        using var fixture = new Fixture();
        fixture.Services.Updates = [new([Hit()], 1)];
        await fixture.Controller.SearchAsync("query", SearchScope.Current, Token);
        Assert.Equal(SearchEndReason.Failed, fixture.Controller.End!.Reason);
        Assert.Equal(ErrorCode.ProtocolMismatch, fixture.Controller.End.Error!.Code);
        Assert.Single(fixture.Controller.Items);
    }

    [Fact]
    public async Task ServiceCancellationHasAnExplicitSafeCanceledTerminal()
    {
        using var fixture = new Fixture();
        fixture.Services.Updates = [new([Hit()], 1, new(SearchEndReason.Canceled, 1,
            new(ErrorCode.Canceled, "private source handle")))];
        await fixture.Controller.SearchAsync("query", SearchScope.Current, Token);
        Assert.Equal(SearchEndReason.Canceled, fixture.Controller.End!.Reason);
        Assert.Contains("取消", fixture.Controller.End.Error!.Message);
        Assert.DoesNotContain("private", fixture.Controller.End.Error.Message);
        Assert.Single(fixture.Controller.Items);
    }

    [Theory]
    [InlineData("display-only")]
    [InlineData("missing-resource")]
    [InlineData("unknown-confidence")]
    [InlineData("forged-row")]
    public async Task OnlyOriginalTrustedRowsCanRequestMetadata(string scenario)
    {
        using var fixture = new Fixture();
        var original = Hit() with
        {
            Resource = scenario == "missing-resource" ? null : Hit().Resource,
            PathConfidence = scenario == "display-only" ? SearchPathConfidence.DisplayOnly :
                scenario == "unknown-confidence" ? (SearchPathConfidence)999 : SearchPathConfidence.ServerWire,
        };
        fixture.Services.Updates = [new([original], 1, new(SearchEndReason.Completed))];
        await fixture.Controller.SearchAsync("query", SearchScope.Current, Token);
        var candidate = scenario == "forged-row" ? original with { } : original;
        int opens = 0;
        await fixture.Controller.OpenAsync(candidate, _ => { opens++; return Task.CompletedTask; }, Token);
        Assert.False(fixture.Controller.CanOpen(candidate));
        Assert.Same(original, Assert.Single(fixture.Controller.Items));
        Assert.Empty(fixture.Services.Gets);
        Assert.Equal(0, opens);
    }

    [Theory]
    [InlineData(SearchPathConfidence.ServerWire)]
    [InlineData(SearchPathConfidence.LegacyUtf8)]
    public async Task OpenRevalidatesOriginalResourceAndHandsOffActualCurrentMetadata(SearchPathConfidence confidence)
    {
        using var fixture = new Fixture();
        var original = Hit("same", "/scope/%FF+%2B") with { PathConfidence = confidence };
        fixture.Services.Updates = [new([original], 1, new(SearchEndReason.Completed))];
        var actual = new RemoteFile(original.Resource! with { DisplayName = "current", SourceVersion = "current-version" },
            original.IsDirectory, 77, DateTimeOffset.UtcNow);
        fixture.Services.GetBehavior = (_, _) => Task.FromResult(actual);
        await fixture.Controller.SearchAsync("query", SearchScope.Current, Token);
        var opened = new List<RemoteFile>();
        await fixture.Controller.OpenAsync(original, file => { opened.Add(file); return Task.CompletedTask; }, Token);
        Assert.Same(original.Resource, Assert.Single(fixture.Services.Gets).Resource);
        Assert.Same(actual, Assert.Single(opened));
        Assert.False(fixture.Controller.IsOpening);
        Assert.Null(fixture.Controller.Error);
        Assert.Equal(0, fixture.Services.Writes);
    }

    [Theory]
    [InlineData("account")]
    [InlineData("wire-case")]
    [InlineData("wire-escaping")]
    [InlineData("type")]
    [InlineData("negative-size")]
    [InlineData("null")]
    public async Task MetadataIdentityAndTypeMustMatchBeforeHandoff(string scenario)
    {
        using var fixture = new Fixture();
        var hit = Hit("selected", "/scope/%6a");
        fixture.Services.Updates = [new([hit], 1, new(SearchEndReason.Completed))];
        var resource = hit.Resource! with
        {
            Account = scenario == "account" ? OtherAccount : Account,
            WirePath = scenario == "wire-case" ? "/scope/%6A" : scenario == "wire-escaping" ? "/scope/j" : hit.Resource!.WirePath,
        };
        fixture.Services.GetBehavior = (_, _) => Task.FromResult(scenario == "null" ? null! :
            new RemoteFile(resource, scenario == "type", scenario == "negative-size" ? -1 : 1, null));
        await fixture.Controller.SearchAsync("query", SearchScope.Current, Token);
        int opens = 0;
        await fixture.Controller.OpenAsync(hit, _ => { opens++; return Task.CompletedTask; }, Token);
        Assert.Equal(0, opens);
        Assert.NotNull(fixture.Controller.Error);
        Assert.False(fixture.Controller.IsOpening);
    }

    [Theory]
    [InlineData("account")]
    [InlineData("session")]
    [InlineData("base")]
    [InlineData("query")]
    [InlineData("cancel")]
    [InlineData("suspend")]
    [InlineData("dispose")]
    public async Task LateMetadataCannotOpenAfterAnyRelevantRetirement(string change)
    {
        using var fixture = new Fixture();
        var hit = Hit();
        fixture.Services.Updates = [new([hit], 1, new(SearchEndReason.Completed))];
        await fixture.Controller.SearchAsync("query", SearchScope.Current, Token);
        var metadata = Deferred<RemoteFile>();
        fixture.Services.GetBehavior = (_, _) => metadata.Task;
        int opens = 0;
        var pending = fixture.Controller.OpenAsync(hit, _ => { opens++; return Task.CompletedTask; }, Token);
        if (change == "query") await fixture.Controller.SearchAsync("next", SearchScope.Recursive, Token);
        else if (change == "cancel") fixture.Controller.Cancel();
        else if (change == "suspend") fixture.Controller.SuspendView();
        else if (change == "dispose") fixture.Controller.Dispose();
        else ReplaceContext(fixture, change);
        Assert.True(Assert.Single(fixture.Services.Gets).Token.IsCancellationRequested);
        metadata.SetResult(new(hit.Resource!, false, 1, null));
        await pending.WaitAsync(Timeout, Token);
        Assert.Equal(0, opens);
        Assert.False(fixture.Controller.IsOpening);
        Assert.Null(fixture.Controller.Error);
    }

    [Fact]
    public async Task OpeningPartialResultStopsSearchAndRetirementDoesNotCloseStartedHandoff()
    {
        using var fixture = new Fixture();
        var script = fixture.Script();
        var hit = Hit();
        var seen = Signal();
        fixture.Controller.Changed += (_, _) => { if (fixture.Controller.Items.Count > 0) seen.TrySetResult(); };
        var searching = fixture.Controller.SearchAsync("query", SearchScope.Recursive, Token);
        script.Send(new([hit], 1));
        await seen.Task.WaitAsync(Timeout, Token);
        var started = Signal();
        var release = Signal();
        int opens = 0;
        var opening = fixture.Controller.OpenAsync(hit, _ => { opens++; started.TrySetResult(); return release.Task; }, Token);
        await started.Task.WaitAsync(Timeout, Token);
        try
        {
            Assert.Equal(SearchEndReason.Canceled, fixture.Controller.End!.Reason);
            Assert.True(Assert.Single(fixture.Services.Searches).Token.IsCancellationRequested);
            Assert.False(fixture.Controller.IsSearching);
            fixture.Controller.SuspendView();
            Assert.False(opening.IsCompleted);
        }
        finally { release.TrySetResult(); script.Complete(); }
        await opening.WaitAsync(Timeout, Token);
        await searching.WaitAsync(Timeout, Token);
        Assert.Equal(1, opens);
        Assert.Null(fixture.Controller.Error);
    }

    [Theory]
    [InlineData(403, "权限")]
    [InlineData(404, "不存在")]
    public async Task MetadataErrorsRemainSafeAndDistinguishMissingFromForbidden(int status, string message)
    {
        using var fixture = new Fixture();
        var hit = Hit();
        fixture.Services.Updates = [new([hit], 1, new(SearchEndReason.Completed))];
        fixture.Services.GetBehavior = (_, _) => throw new FilewayException(new(ErrorCode.SourceChanged, "private endpoint", status));
        await fixture.Controller.SearchAsync("query", SearchScope.Current, Token);
        await fixture.Controller.OpenAsync(hit, _ => throw new InvalidOperationException("must not open"), Token);
        Assert.Contains(message, fixture.Controller.Error);
        Assert.DoesNotContain("private", fixture.Controller.Error);
    }

    [Fact]
    public async Task PreCanceledCallDoesNotStartProviderAndCallerCancellationKeepsPartialRows()
    {
        using var fixture = new Fixture();
        using var canceled = CancellationTokenSource.CreateLinkedTokenSource(Token);
        await canceled.CancelAsync();
        await fixture.Controller.SearchAsync("query", SearchScope.Current, canceled.Token);
        Assert.Empty(fixture.Services.Searches);
        Assert.Equal(SearchEndReason.Canceled, fixture.Controller.End!.Reason);
        Assert.Null(fixture.Controller.Error);

        using var caller = CancellationTokenSource.CreateLinkedTokenSource(Token);
        var script = fixture.Script(honorCancellation: true);
        var seen = Signal();
        fixture.Controller.Changed += (_, _) => { if (fixture.Controller.Items.Count > 0) seen.TrySetResult(); };
        var pending = fixture.Controller.SearchAsync("query", SearchScope.Current, caller.Token);
        script.Send(new([Hit()], 1));
        await seen.Task.WaitAsync(Timeout, Token);
        await caller.CancelAsync();
        await pending.WaitAsync(Timeout, Token);
        Assert.Single(fixture.Controller.Items);
        Assert.Equal(SearchEndReason.Canceled, fixture.Controller.End!.Reason);
        Assert.False(fixture.Controller.IsSearching);
        Assert.Null(fixture.Controller.Error);
    }

    [Fact]
    public async Task BlockingCancellationCallbackCannotBlockCancelOrActualRequestCompletion()
    {
        using var fixture = new Fixture();
        var script = fixture.Script();
        var pending = fixture.Controller.SearchAsync("query", SearchScope.Current, Token);
        await script.Entered.Task.WaitAsync(Timeout, Token);
        var entered = Signal();
        var release = Signal();
        var cancellation = Assert.Single(fixture.Services.Searches).Token;
        using var registration = cancellation.Register(() => { entered.TrySetResult(); release.Task.GetAwaiter().GetResult(); });
        try
        {
            fixture.Controller.Cancel();
            await entered.Task.WaitAsync(Timeout, Token);
            Assert.True(cancellation.IsCancellationRequested);
            script.Complete();
            await pending.WaitAsync(Timeout, Token);
            Assert.False(fixture.Controller.IsSearching);
            Assert.Equal(SearchEndReason.Canceled, fixture.Controller.End!.Reason);
        }
        finally { release.TrySetResult(); }
    }

    [Fact]
    public async Task NotificationsAreOutsideStateGateAndSubscriberFailuresCannotOwnSearch()
    {
        using var fixture = new Fixture();
        Exception? gateFailure = null;
        int notifications = 0;
        fixture.Controller.Changed += (_, _) => throw new IOException("private subscriber failure");
        fixture.Controller.Changed += (_, _) =>
        {
            notifications++;
            try { Task.Run(() => fixture.Controller.Items.Count).WaitAsync(Timeout, Token).GetAwaiter().GetResult(); }
            catch (Exception error) { gateFailure = error; }
        };
        await fixture.Controller.SearchAsync("query", SearchScope.Current, Token);
        Assert.True(notifications >= 2);
        Assert.Null(gateFailure);
        Assert.Null(fixture.Controller.Error);
        Assert.Equal(SearchEndReason.Completed, fixture.Controller.End!.Reason);
    }

    [Theory]
    [InlineData("suspend")]
    [InlineData("context")]
    [InlineData("query")]
    public async Task ReentrantHandoffDefersNotificationsUntilItsOuterGateIsReleased(string retirement)
    {
        using var fixture = new Fixture();
        var hit = Hit();
        fixture.Services.Updates = [new([hit], 1, new(SearchEndReason.Completed))];
        await fixture.Controller.SearchAsync("query", SearchScope.Current, Token);
        var release = Signal();
        var published = Signal();
        bool callbackActive = false, callbackStarted = false, notifiedInsideCallback = false;
        Exception? readFailure = null;
        Task? replacement = null;
        fixture.Controller.Changed += (_, _) =>
        {
            notifiedInsideCallback |= callbackActive;
            try { Task.Run(() => fixture.Controller.Items.Count).WaitAsync(Timeout, Token).GetAwaiter().GetResult(); }
            catch (Exception error) { readFailure = error; }
            if (callbackStarted && !callbackActive) published.TrySetResult();
        };
        var opening = fixture.Controller.OpenAsync(hit, _ =>
        {
            callbackStarted = callbackActive = true;
            try
            {
                if (retirement == "suspend") fixture.Controller.SuspendView();
                else if (retirement == "context") fixture.Controller.SetContext(null, null, null, null);
                else replacement = fixture.Controller.SearchAsync("replacement", SearchScope.Recursive, Token);
            }
            finally { callbackActive = false; }
            return release.Task;
        }, Token);
        try
        {
            await published.Task.WaitAsync(Timeout, Token);
            Assert.False(opening.IsCompleted); // Notification flush does not wait for media completion.
            Assert.False(notifiedInsideCallback);
            Assert.Null(readFailure);
        }
        finally
        {
            release.TrySetResult();
            await opening.WaitAsync(Timeout, Token);
            if (replacement is not null) await replacement.WaitAsync(Timeout, Token);
        }
        Assert.Null(fixture.Controller.Error);
    }

    [Fact]
    public async Task ThrowingReentrantHandoffStillFlushesRetirementOutsideItsOuterGate()
    {
        using var fixture = new Fixture();
        var hit = Hit();
        fixture.Services.Updates = [new([hit], 1, new(SearchEndReason.Completed))];
        await fixture.Controller.SearchAsync("query", SearchScope.Current, Token);
        bool callbackActive = false, callbackStarted = false, notifiedInsideCallback = false;
        int retirementNotifications = 0;
        Exception? readFailure = null;
        fixture.Controller.Changed += (_, _) =>
        {
            notifiedInsideCallback |= callbackActive;
            try { Task.Run(() => fixture.Controller.Items.Count).WaitAsync(Timeout, Token).GetAwaiter().GetResult(); }
            catch (Exception error) { readFailure = error; }
            if (callbackStarted && !callbackActive) retirementNotifications++;
        };
        await fixture.Controller.OpenAsync(hit, _ =>
        {
            callbackStarted = callbackActive = true;
            try { fixture.Controller.SuspendView(); }
            finally { callbackActive = false; }
            throw new IOException("private handoff failure");
        }, Token);
        Assert.False(notifiedInsideCallback);
        Assert.Null(readFailure);
        Assert.True(retirementNotifications >= 1);
        Assert.False(fixture.Controller.IsOpening);
        Assert.Null(fixture.Controller.Error);
    }

    private static async IAsyncEnumerable<SearchUpdate> FailAfter(SearchHit hit, bool typed, ErrorCode code,
        [EnumeratorCancellation] CancellationToken cancellationToken)
    {
        await Task.CompletedTask;
        cancellationToken.ThrowIfCancellationRequested();
        yield return new([hit], 1);
        if (typed) throw new FilewayException(new(code, "private host"));
        throw new IOException("private host");
    }
    private static SearchHit Hit(string name = "file.txt", string? wire = null) => new(name, name, false, 1, null, null,
        new(Account, wire ?? "/scope/" + name, name, "/display/" + name), SearchPathConfidence.ServerWire);
    private static TaskCompletionSource Signal() => new(TaskCreationOptions.RunContinuationsAsynchronously);
    private static TaskCompletionSource<T> Deferred<T>() => new(TaskCreationOptions.RunContinuationsAsynchronously);
    private static void ReplaceContext(Fixture fixture, string change)
    {
        if (change == "disconnect") { fixture.Controller.SetContext(null, null, null, null); return; }
        var session = change == "account" ? fixture.Session with { Id = Guid.NewGuid(), Account = OtherAccount } :
            change == "session" ? fixture.Session with { Id = Guid.NewGuid() } : fixture.Session;
        var directory = change == "base" ? fixture.Directory with { WirePath = "/other" } :
            change == "account" ? fixture.Directory with { Account = OtherAccount } : fixture.Directory;
        fixture.Controller.SetContext(change == "search-service" ? new Services() : fixture.Services,
            change == "file-service" ? new Services() : fixture.Services, session, directory);
    }

    private sealed class Fixture : IDisposable
    {
        private readonly List<ControlledSearch> _scripts = [];
        internal Services Services { get; } = new();
        internal ConnectionSession Session { get; } = new(Guid.NewGuid(), Account, "fixture", new(false, false, false, false, false, false), new());
        internal RemoteResourceRef Directory { get; } = new(Account, "/scope", "scope", "/display scope");
        internal SearchResultsController Controller { get; } = new();
        internal Fixture() => Controller.SetContext(Services, Services, Session, Directory);
        internal ControlledSearch Script(bool honorCancellation = false)
        {
            var script = new ControlledSearch(honorCancellation); _scripts.Add(script);
            Services.SearchBehavior = (_, token) => script.Read(token); return script;
        }
        public void Dispose() { Controller.Dispose(); foreach (var script in _scripts) script.Complete(); }
    }

    private sealed class ControlledSearch(bool honorCancellation)
    {
        private readonly Channel<SearchUpdate> _updates = Channel.CreateUnbounded<SearchUpdate>();
        internal TaskCompletionSource Entered { get; } = Signal();
        internal TaskCompletionSource Disposed { get; } = Signal();
        internal void Send(SearchUpdate update) => Assert.True(_updates.Writer.TryWrite(update));
        internal void Complete(Exception? error = null) => _updates.Writer.TryComplete(error);
        internal async IAsyncEnumerable<SearchUpdate> Read([EnumeratorCancellation] CancellationToken cancellationToken)
        {
            Entered.TrySetResult();
            try
            {
                // Noncooperative mode deliberately delivers after cancellation to exercise return fences.
                await foreach (var update in _updates.Reader.ReadAllAsync(honorCancellation ? cancellationToken : CancellationToken.None))
                    yield return update;
            }
            finally { Disposed.TrySetResult(); }
        }
    }

    private sealed class Services : IServerSearchService, IFileRepository
    {
        internal ConcurrentQueue<(SearchRequest Request, CancellationToken Token)> Searches { get; } = new();
        internal ConcurrentQueue<(RemoteResourceRef Resource, CancellationToken Token)> Gets { get; } = new();
        internal IReadOnlyList<SearchUpdate> Updates { get; set; } = [new([], 0, new(SearchEndReason.Completed, 0))];
        internal Func<SearchRequest, CancellationToken, IAsyncEnumerable<SearchUpdate>>? SearchBehavior { get; set; }
        internal Func<RemoteResourceRef, CancellationToken, Task<RemoteFile>>? GetBehavior { get; set; }
        internal int IteratorDisposals, Writes;
        public IAsyncEnumerable<SearchUpdate> SearchAsync(SearchRequest request, CancellationToken cancellationToken)
        {
            Searches.Enqueue((request, cancellationToken));
            return SearchBehavior?.Invoke(request, cancellationToken) ?? EnumerateAsync(Updates, cancellationToken);
        }
        private async IAsyncEnumerable<SearchUpdate> EnumerateAsync(IReadOnlyList<SearchUpdate> updates,
            [EnumeratorCancellation] CancellationToken cancellationToken)
        {
            await Task.CompletedTask;
            try { foreach (var update in updates) { cancellationToken.ThrowIfCancellationRequested(); yield return update; } }
            finally { Interlocked.Increment(ref IteratorDisposals); }
        }
        public Task<RemoteFile> GetAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
        {
            Gets.Enqueue((resource, cancellationToken));
            return GetBehavior?.Invoke(resource, cancellationToken) ?? Task.FromResult(new RemoteFile(resource, false, 1, null));
        }
        public Task<RemoteResourceRef> GetRootAsync(ConnectionSession session, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<DirectoryPage> ListAsync(RemoteResourceRef directory, DirectoryQuery query, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<FileOperationResult> CreateDirectoryAsync(RemoteResourceRef parent, string name, CancellationToken cancellationToken)
        { Interlocked.Increment(ref Writes); throw new NotSupportedException(); }
        public Task<FileOperationResult> RenameAsync(RemoteResourceRef resource, string name, CancellationToken cancellationToken)
        { Interlocked.Increment(ref Writes); throw new NotSupportedException(); }
        public Task<FileOperationResult> TrashAsync(IReadOnlyList<RemoteResourceRef> resources, CancellationToken cancellationToken)
        { Interlocked.Increment(ref Writes); throw new NotSupportedException(); }
    }
}
