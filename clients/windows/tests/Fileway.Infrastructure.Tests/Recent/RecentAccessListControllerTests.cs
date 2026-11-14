using System.Globalization;
using Fileway.App;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Tests.Recent;

public sealed class RecentAccessListControllerTests
{
    private static readonly AccountKey Account = new(Guid.NewGuid(), Guid.NewGuid(), "recent-fixture");
    private static readonly AccountKey OtherAccount = new(Guid.NewGuid(), Guid.NewGuid(), "other-fixture");
    private static readonly DateTimeOffset OriginalTime = new(2024, 2, 3, 4, 5, 6, TimeSpan.FromHours(8));
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Fact]
    public void RowPreservesOriginalEntryTimestampAndDistinctWireIdentity()
    {
        var first = Entry("first", "/%D6%D0%CE%C4.txt") with { DisplayPath = "/中文.txt" };
        var second = Entry("second", "/%E4%B8%AD%E6%96%87.txt") with { DisplayPath = first.DisplayPath };
        DateTimeOffset? received = null;
        var row = new RecentAccessRow(first, time => { received = time; return "formatted original"; });
        var other = new RecentAccessRow(second);

        Assert.Same(first, row.Entry);
        Assert.Equal(first.DisplayName, row.Name);
        Assert.Equal(first.DisplayPath, row.DisplayPath);
        Assert.Equal(first.AccessedAt, received);
        Assert.Equal(first.AccessedAt.Offset, received!.Value.Offset);
        Assert.Equal("formatted original", row.AccessedTime);
        Assert.Contains(first.Resource!.WirePath, row.IdentityHint);
        Assert.NotEqual(row.IdentityHint, other.IdentityHint);
        Assert.True(row.CanOpen);
        Assert.Contains(row.Name, row.AccessibleName);
        Assert.Contains(row.AccessedTime, row.AccessibleName);
        Assert.Contains(row.IdentityHint, row.AccessibleName);
    }

    [Fact]
    public void RowFormatterFailureUsesOriginalInstantInUtc()
    {
        var entry = Entry("id");
        var row = new RecentAccessRow(entry, _ => throw new InvalidOperationException("private fixture"));

        Assert.Equal(entry.AccessedAt.ToUniversalTime().ToString("u", CultureInfo.InvariantCulture), row.AccessedTime);
        Assert.Contains("2024-02-02 20:05:06Z", row.AccessedTime);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(100)]
    public async Task RetainedBoundaryHasNoWritesOrFabricatedRows(int count)
    {
        using var fixture = new Fixture();
        fixture.Services.Rows = Enumerable.Range(0, count).Select(index => Entry(index.ToString(CultureInfo.InvariantCulture))).ToArray();

        await fixture.Controller.RefreshAsync(Token);

        Assert.Equal(count, fixture.Controller.Items.Count);
        Assert.True(fixture.Controller.HasLoaded);
        Assert.False(fixture.Controller.IsLoading);
        Assert.Null(fixture.Controller.Error);
        Assert.Equal(Account, Assert.Single(fixture.Services.Lists).Account);
        Assert.Empty(fixture.Services.Gets);
        Assert.Equal(0, fixture.Services.Writes);
        Assert.Equal(0, fixture.Services.Records);
    }

    [Fact]
    public async Task ServerOrderTimesAndOriginalReferencesAreKeptExactly()
    {
        using var fixture = new Fixture();
        var first = Entry("second", "/%D6%D0%CE%C4.txt") with { DisplayPath = "/中文.txt" };
        var second = Entry("first", "/%E4%B8%AD%E6%96%87.txt") with { DisplayPath = first.DisplayPath, AccessedAt = OriginalTime.AddDays(7) };
        fixture.Services.Rows = [first, second];

        await fixture.Controller.RefreshAsync(Token);

        Assert.Same(first, fixture.Controller.Items[0]);
        Assert.Same(second, fixture.Controller.Items[1]);
        Assert.Equal(OriginalTime, fixture.Controller.Items[0].AccessedAt);
        Assert.Equal(OriginalTime.Offset, fixture.Controller.Items[0].AccessedAt.Offset);
        Assert.Same(first.Resource, fixture.Controller.Items[0].Resource);
        Assert.NotEqual(first.Resource!.WirePath, second.Resource!.WirePath);
    }

    [Theory]
    [InlineData("101")]
    [InlineData("duplicate")]
    [InlineData("wrong-account")]
    [InlineData("wrong-resource-account")]
    [InlineData("null-list")]
    [InlineData("null-row")]
    [InlineData("blank-id")]
    public async Task InvalidListCannotPartiallyReplaceKnownRows(string scenario)
    {
        using var fixture = new Fixture();
        var known = Entry("known");
        fixture.Services.Rows = [known];
        await fixture.Controller.RefreshAsync(Token);
        var first = Entry("first");
        fixture.Services.Rows = scenario switch
        {
            "101" => Enumerable.Range(0, 101).Select(index => Entry(index.ToString(CultureInfo.InvariantCulture))).ToArray(),
            "duplicate" => [first, first with { DisplayName = "different" }],
            "wrong-account" => [first, Entry("wrong") with { Account = OtherAccount }],
            "wrong-resource-account" => [first, Entry("wrong") with { Resource = first.Resource! with { Account = OtherAccount } }],
            "null-list" => null!,
            "null-row" => [first, null!],
            _ => [first, Entry(" ")],
        };

        await fixture.Controller.RefreshAsync(Token);

        Assert.Same(known, Assert.Single(fixture.Controller.Items));
        Assert.NotNull(fixture.Controller.Error);
        Assert.False(fixture.Controller.IsLoading);
        Assert.True(fixture.Controller.HasLoaded);
        Assert.Equal(0, fixture.Services.Records);
        Assert.Equal(0, fixture.Services.Writes);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ListErrorsAreSafeAndKeepSameContextRows(bool typed)
    {
        using var fixture = new Fixture();
        var known = Entry("known");
        fixture.Services.Rows = [known];
        await fixture.Controller.RefreshAsync(Token);
        fixture.Services.ListBehavior = (_, _) => throw Failure(typed);

        await fixture.Controller.RefreshAsync(Token);

        Assert.Same(known, Assert.Single(fixture.Controller.Items));
        Assert.NotNull(fixture.Controller.Error);
        Assert.DoesNotContain("private", fixture.Controller.Error);
        Assert.False(fixture.Controller.IsLoading);
    }

    [Theory]
    [InlineData("null")]
    [InlineData("unverified")]
    [InlineData("unknown")]
    public async Task UntrustedRowsRemainVisibleAndCannotRequestMetadata(string scenario)
    {
        using var fixture = new Fixture();
        var entry = Entry("untrusted") with
        {
            Resource = scenario == "null" ? null : Entry("untrusted").Resource,
            PathConfidence = scenario == "unknown" ? (RecentAccessPathConfidence)999 : RecentAccessPathConfidence.Unverified,
        };
        fixture.Services.Rows = [entry];
        await fixture.Controller.RefreshAsync(Token);
        int callbacks = 0;

        await fixture.Controller.OpenAsync(entry, _ => { ++callbacks; return Task.CompletedTask; }, Token);

        Assert.Same(entry, Assert.Single(fixture.Controller.Items));
        Assert.False(new RecentAccessRow(entry).CanOpen);
        Assert.False(fixture.Controller.CanOpen(entry));
        Assert.Empty(fixture.Services.Gets);
        Assert.Equal(0, callbacks);
        Assert.Equal(0, fixture.Services.Records);
    }

    [Theory]
    [InlineData(RecentAccessPathConfidence.ServerWire)]
    [InlineData(RecentAccessPathConfidence.LegacyUtf8)]
    public async Task ExplicitOpenResolvesOnceUsingOriginalResourceAndReturnedMetadata(RecentAccessPathConfidence confidence)
    {
        using var fixture = new Fixture();
        var entry = Entry("selected", "/opaque%FF+%2B") with { PathConfidence = confidence };
        fixture.Services.Rows = [entry];
        var metadata = new RemoteFile(entry.Resource! with { DisplayName = "current name", SourceVersion = "new version" }, true, 72, OriginalTime.AddDays(1));
        fixture.Services.GetBehavior = (_, _) => Task.FromResult(metadata);
        await fixture.Controller.RefreshAsync(Token);
        var callbacks = new List<RemoteFile>();

        await fixture.Controller.OpenAsync(entry, file => { callbacks.Add(file); return Task.CompletedTask; }, Token);

        Assert.True(new RecentAccessRow(entry).CanOpen);
        Assert.Same(entry.Resource, Assert.Single(fixture.Services.Gets).Resource);
        Assert.Same(metadata, Assert.Single(callbacks));
        Assert.False(fixture.Controller.IsOpening);
        Assert.Null(fixture.Controller.Error);
        Assert.Equal(0, fixture.Services.Records);
        Assert.Equal(0, fixture.Services.Writes);
    }

    [Theory]
    [InlineData("account")]
    [InlineData("wire-case")]
    [InlineData("wire-escaping")]
    [InlineData("null")]
    public async Task MetadataMustMatchExactAccountAndOrdinalWireBeforeCallback(string scenario)
    {
        using var fixture = new Fixture();
        var entry = Entry("selected", "/%6a");
        fixture.Services.Rows = [entry];
        var resource = entry.Resource! with
        {
            Account = scenario == "account" ? OtherAccount : Account,
            WirePath = scenario == "wire-case" ? "/%6A" : scenario == "wire-escaping" ? "/j" : entry.Resource.WirePath,
        };
        fixture.Services.GetBehavior = (_, _) => Task.FromResult(scenario == "null" ? null! : new RemoteFile(resource, false, 12, null));
        await fixture.Controller.RefreshAsync(Token);
        int callbacks = 0;

        await fixture.Controller.OpenAsync(entry, _ => { ++callbacks; return Task.CompletedTask; }, Token);

        Assert.Single(fixture.Services.Gets);
        Assert.Equal(0, callbacks);
        Assert.NotNull(fixture.Controller.Error);
        Assert.False(fixture.Controller.IsOpening);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task MetadataErrorsAreSafeAndNeverOpen(bool typed)
    {
        using var fixture = new Fixture();
        var entry = Entry("selected");
        fixture.Services.Rows = [entry];
        fixture.Services.GetBehavior = (_, _) => throw Failure(typed);
        await fixture.Controller.RefreshAsync(Token);
        int callbacks = 0;

        await fixture.Controller.OpenAsync(entry, _ => { ++callbacks; return Task.CompletedTask; }, Token);

        Assert.Equal(0, callbacks);
        Assert.False(fixture.Controller.IsOpening);
        Assert.NotNull(fixture.Controller.Error);
        Assert.DoesNotContain("private", fixture.Controller.Error);
    }

    [Theory]
    [InlineData(403, "权限")]
    [InlineData(404, "不存在")]
    public async Task MetadataHttpErrorDistinguishesMissingFromForbidden(int status, string expected)
    {
        using var fixture = new Fixture();
        var entry = Entry("selected");
        fixture.Services.Rows = [entry];
        fixture.Services.GetBehavior = (_, _) => throw new FilewayException(new(ErrorCode.SourceChanged, "private endpoint", status));
        await fixture.Controller.RefreshAsync(Token);

        await fixture.Controller.OpenAsync(entry, _ => Task.CompletedTask, Token);

        Assert.Contains(expected, fixture.Controller.Error);
        Assert.DoesNotContain("private", fixture.Controller.Error);
    }

    [Theory]
    [InlineData("account")]
    [InlineData("reconnect")]
    [InlineData("services")]
    [InlineData("disconnect")]
    public async Task ContextReplacementCancelsLateListAndClearsPreviousRows(string scenario)
    {
        using var fixture = new Fixture();
        fixture.Services.Rows = [Entry("known")];
        await fixture.Controller.RefreshAsync(Token);
        var deferred = Deferred<IReadOnlyList<RecentAccessEntry>>();
        fixture.Services.ListBehavior = (_, _) => deferred.Task;
        var pending = fixture.Controller.RefreshAsync(Token);
        var token = fixture.Services.Lists[^1].Token;
        var generation = fixture.Controller.Generation;

        ReplaceContext(fixture, scenario);
        Assert.Empty(fixture.Controller.Items);
        Assert.False(fixture.Controller.IsLoading);
        Assert.True(token.IsCancellationRequested);
        Assert.True(fixture.Controller.Generation > generation);
        deferred.SetResult([Entry("late")]);
        await pending;

        Assert.Empty(fixture.Controller.Items);
        Assert.Null(fixture.Controller.Error);
        Assert.False(fixture.Controller.HasLoaded);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task NewRefreshWinsOverLateListSuccessOrError(bool oldFails)
    {
        using var fixture = new Fixture();
        var deferred = Deferred<IReadOnlyList<RecentAccessEntry>>();
        fixture.Services.ListBehavior = (_, _) => deferred.Task;
        var pending = fixture.Controller.RefreshAsync(Token);
        var oldToken = Assert.Single(fixture.Services.Lists).Token;
        var newest = Entry("newest");
        fixture.Services.ListBehavior = null;
        fixture.Services.Rows = [newest];

        await fixture.Controller.RefreshAsync(Token);
        if (oldFails) deferred.SetException(Failure(true));
        else deferred.SetResult([Entry("late")]);
        await pending;

        Assert.True(oldToken.IsCancellationRequested);
        Assert.Same(newest, Assert.Single(fixture.Controller.Items));
        Assert.Null(fixture.Controller.Error);
        Assert.False(fixture.Controller.IsLoading);
    }

    [Theory]
    [InlineData("account")]
    [InlineData("reconnect")]
    [InlineData("refresh")]
    [InlineData("suspend")]
    [InlineData("dispose")]
    public async Task LateMetadataCannotOpenAfterContextRefreshOrViewRetirement(string scenario)
    {
        using var fixture = new Fixture();
        var entry = Entry("selected");
        fixture.Services.Rows = [entry];
        await fixture.Controller.RefreshAsync(Token);
        var deferred = Deferred<RemoteFile>();
        fixture.Services.GetBehavior = (_, _) => deferred.Task;
        int callbacks = 0;
        var pending = fixture.Controller.OpenAsync(entry, _ => { ++callbacks; return Task.CompletedTask; }, Token);
        var token = Assert.Single(fixture.Services.Gets).Token;
        Assert.True(fixture.Controller.IsOpening);

        if (scenario == "refresh") await fixture.Controller.RefreshAsync(Token);
        else if (scenario == "suspend") fixture.Controller.SuspendView();
        else if (scenario == "dispose") fixture.Controller.Dispose();
        else ReplaceContext(fixture, scenario);
        deferred.SetResult(new RemoteFile(entry.Resource!, false, 32, null));
        await pending;

        Assert.True(token.IsCancellationRequested);
        Assert.Equal(0, callbacks);
        Assert.False(fixture.Controller.IsOpening);
        Assert.Null(fixture.Controller.Error);
        Assert.Equal(0, fixture.Services.Records);
    }

    [Fact]
    public async Task SuspendCancelsLateListErrorAndReactivationRequiresRefresh()
    {
        using var fixture = new Fixture();
        var known = Entry("known");
        fixture.Services.Rows = [known];
        await fixture.Controller.RefreshAsync(Token);
        var deferred = Deferred<IReadOnlyList<RecentAccessEntry>>();
        fixture.Services.ListBehavior = (_, _) => deferred.Task;
        var pending = fixture.Controller.RefreshAsync(Token);
        var token = fixture.Services.Lists[^1].Token;

        fixture.Controller.SuspendView();
        deferred.SetException(Failure(true));
        await pending;

        Assert.True(token.IsCancellationRequested);
        Assert.False(fixture.Controller.CanOpen(known));
        Assert.Same(known, Assert.Single(fixture.Controller.Items));
        Assert.Null(fixture.Controller.Error);
        fixture.Controller.SetContext(fixture.Services, fixture.Services, fixture.Session);
        Assert.False(fixture.Controller.CanOpen(known));
        fixture.Services.ListBehavior = null;
        await fixture.Controller.RefreshAsync(Token);
        Assert.True(fixture.Controller.CanOpen(known));
    }

    [Fact]
    public async Task SameSessionMetadataUpdateDoesNotRetireCurrentList()
    {
        using var fixture = new Fixture();
        var deferred = Deferred<IReadOnlyList<RecentAccessEntry>>();
        fixture.Services.ListBehavior = (_, _) => deferred.Task;
        var pending = fixture.Controller.RefreshAsync(Token);
        var generation = fixture.Controller.Generation;
        var token = Assert.Single(fixture.Services.Lists).Token;

        fixture.Controller.SetContext(fixture.Services, fixture.Services, fixture.Session with { DisplayName = "updated" });
        deferred.SetResult([Entry("current")]);
        await pending;

        Assert.Equal(generation, fixture.Controller.Generation);
        Assert.False(token.IsCancellationRequested);
        Assert.Single(fixture.Controller.Items);
    }

    [Fact]
    public async Task RefreshDisallowsOldOrEquivalentRowObjectsAfterReplacement()
    {
        using var fixture = new Fixture();
        var old = Entry("same");
        fixture.Services.Rows = [old];
        await fixture.Controller.RefreshAsync(Token);
        var current = old with { };
        fixture.Services.Rows = [current];
        await fixture.Controller.RefreshAsync(Token);

        await fixture.Controller.OpenAsync(old, _ => Task.CompletedTask, Token);
        await fixture.Controller.OpenAsync(current with { }, _ => Task.CompletedTask, Token);

        Assert.False(fixture.Controller.CanOpen(old));
        Assert.True(fixture.Controller.CanOpen(current));
        Assert.Empty(fixture.Services.Gets);
    }

    [Fact]
    public async Task RefreshLoadingDisablesKnownRowsAndDoubleOpenIsBounded()
    {
        using var fixture = new Fixture();
        var entry = Entry("selected");
        fixture.Services.Rows = [entry];
        await fixture.Controller.RefreshAsync(Token);
        var list = Deferred<IReadOnlyList<RecentAccessEntry>>();
        var get = Deferred<RemoteFile>();
        fixture.Services.ListBehavior = (_, _) => list.Task;
        fixture.Services.GetBehavior = (_, _) => get.Task;
        var refresh = fixture.Controller.RefreshAsync(Token);
        Assert.True(fixture.Controller.IsLoading);
        Assert.False(fixture.Controller.CanOpen(entry));
        await fixture.Controller.OpenAsync(entry, _ => Task.CompletedTask, Token);
        Assert.Empty(fixture.Services.Gets);
        list.SetResult([entry]);
        await refresh;
        Assert.False(fixture.Controller.IsLoading);
        Assert.True(fixture.Controller.CanOpen(entry));
        int callbacks = 0;
        var open = fixture.Controller.OpenAsync(entry, _ => { ++callbacks; return Task.CompletedTask; }, Token);
        await fixture.Controller.OpenAsync(entry, _ => { ++callbacks; return Task.CompletedTask; }, Token);
        Assert.True(fixture.Controller.IsOpening);
        Assert.False(fixture.Controller.IsLoading);
        Assert.Single(fixture.Services.Gets);

        get.SetResult(new RemoteFile(entry.Resource!, false, 1, null));
        await open;
        Assert.Equal(1, callbacks);
        Assert.False(fixture.Controller.IsOpening);
    }

    [Fact]
    public async Task ThrowingProviderCancellationCannotEscapeRetirementOrPublishLateRows()
    {
        using var fixture = new Fixture();
        var deferred = Deferred<IReadOnlyList<RecentAccessEntry>>();
        var callback = Deferred();
        CancellationTokenRegistration registration = default;
        fixture.Services.ListBehavior = (_, token) =>
        {
            registration = token.Register(() =>
            {
                callback.SetResult();
                throw new InvalidOperationException("private cancellation callback");
            });
            return deferred.Task;
        };
        var pending = fixture.Controller.RefreshAsync(Token);

        fixture.Controller.SuspendView();
        await callback.Task.WaitAsync(Token);
        deferred.SetResult([Entry("late")]);
        await pending;
        await registration.DisposeAsync();

        Assert.Empty(fixture.Controller.Items);
        Assert.False(fixture.Controller.IsLoading);
        Assert.Null(fixture.Controller.Error);
    }

    [Fact]
    public async Task BlockingProviderCancellationDoesNotBlockRetirementOrRequestCompletion()
    {
        using var fixture = new Fixture();
        var deferred = Deferred<RemoteFile>();
        var entered = Deferred();
        var release = Deferred();
        var entry = Entry("selected");
        fixture.Services.Rows = [entry];
        await fixture.Controller.RefreshAsync(Token);
        CancellationTokenRegistration registration = default;
        fixture.Services.GetBehavior = (_, token) =>
        {
            registration = token.Register(() =>
            {
                entered.SetResult();
                release.Task.GetAwaiter().GetResult();
            });
            return deferred.Task;
        };
        int callbacks = 0;
        var pending = fixture.Controller.OpenAsync(entry, _ => { ++callbacks; return Task.CompletedTask; }, Token);
        try
        {
            // CancelAsync signals the token immediately; the provider callback is dispatched separately.
            fixture.Controller.SetContext(null, null, null);
            await entered.Task.WaitAsync(Token);
            deferred.SetResult(new RemoteFile(entry.Resource!, false, 1, null));
            await pending.WaitAsync(Token);
            Assert.True(Assert.Single(fixture.Services.Gets).Token.IsCancellationRequested);
            Assert.Equal(0, callbacks);
            Assert.False(fixture.Controller.IsOpening);
            Assert.Null(fixture.Controller.Error);
        }
        finally
        {
            release.TrySetResult();
            await registration.DisposeAsync();
        }
    }

    [Fact]
    public async Task SuspendingAfterCallbackStartsDoesNotCancelAlreadyOpenedMedia()
    {
        using var fixture = new Fixture();
        var entry = Entry("selected");
        fixture.Services.Rows = [entry];
        await fixture.Controller.RefreshAsync(Token);
        var opened = Deferred();
        var release = Deferred();
        int callbacks = 0;
        var pending = fixture.Controller.OpenAsync(entry, _ =>
        {
            ++callbacks;
            opened.SetResult();
            return release.Task;
        }, Token);
        await opened.Task.WaitAsync(Token);

        fixture.Controller.SuspendView();
        Assert.False(pending.IsCompleted);
        release.SetResult();
        await pending;

        Assert.Equal(1, callbacks);
        Assert.Null(fixture.Controller.Error);
        Assert.Equal(0, fixture.Services.Records);
        Assert.Equal(0, fixture.Services.Writes);
    }

    [Fact]
    public async Task CallerCancellationRetiresReadWithoutPresentingError()
    {
        using var fixture = new Fixture();
        using var canceled = CancellationTokenSource.CreateLinkedTokenSource(Token);
        await canceled.CancelAsync();

        await fixture.Controller.RefreshAsync(canceled.Token);

        Assert.Empty(fixture.Services.Lists);
        Assert.False(fixture.Controller.IsLoading);
        Assert.Null(fixture.Controller.Error);
    }

    private static Exception Failure(bool typed) => typed
        ? new FilewayException(new(ErrorCode.NetworkUnavailable, "private path and host"))
        : new IOException("private path and host");

    private static RecentAccessEntry Entry(string id, string wire = "/fixture") => new(Account, id, "display " + id,
        "/display only", false, OriginalTime, new RemoteResourceRef(Account, wire, "resource " + id, "/resource display", "version"),
        RecentAccessPathConfidence.ServerWire);

    private static void ReplaceContext(Fixture fixture, string scenario)
    {
        var session = scenario == "account" ? fixture.Session with { Account = OtherAccount, Id = Guid.NewGuid() }
            : fixture.Session with { Id = Guid.NewGuid() };
        if (scenario == "disconnect") fixture.Controller.SetContext(null, null, null);
        else if (scenario == "services")
        {
            var services = new FakeServices();
            fixture.Controller.SetContext(services, services, fixture.Session);
        }
        else fixture.Controller.SetContext(fixture.Services, fixture.Services, session);
    }

    private static TaskCompletionSource<T> Deferred<T>() => new(TaskCreationOptions.RunContinuationsAsynchronously);
    private static TaskCompletionSource Deferred() => new(TaskCreationOptions.RunContinuationsAsynchronously);

    private sealed class Fixture : IDisposable
    {
        internal FakeServices Services { get; } = new();
        internal ConnectionSession Session { get; } = new(Guid.NewGuid(), Account, "fixture", new(false, false, false, false, false, false), new());
        internal RecentAccessListController Controller { get; } = new();

        internal Fixture() => Controller.SetContext(Services, Services, Session);
        public void Dispose() => Controller.Dispose();
    }

    private sealed class FakeServices : IRecentAccessService, IFileRepository
    {
        internal IReadOnlyList<RecentAccessEntry> Rows { get; set; } = Array.Empty<RecentAccessEntry>();
        internal Func<AccountKey, CancellationToken, Task<IReadOnlyList<RecentAccessEntry>>>? ListBehavior { get; set; }
        internal Func<RemoteResourceRef, CancellationToken, Task<RemoteFile>>? GetBehavior { get; set; }
        internal List<(AccountKey Account, CancellationToken Token)> Lists { get; } = [];
        internal List<(RemoteResourceRef Resource, CancellationToken Token)> Gets { get; } = [];
        internal int Records { get; private set; }
        internal int Writes { get; private set; }

        public Task<IReadOnlyList<RecentAccessEntry>> ListRecentAsync(AccountKey account, CancellationToken cancellationToken)
        {
            Lists.Add((account, cancellationToken));
            return ListBehavior?.Invoke(account, cancellationToken) ?? Task.FromResult(Rows);
        }

        public Task<RemoteFile> GetAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
        {
            Gets.Add((resource, cancellationToken));
            return GetBehavior?.Invoke(resource, cancellationToken) ?? Task.FromResult(new RemoteFile(resource, false, 1, null));
        }

        public Task<RecentAccessRecordResult> RecordOpenedAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
        { ++Records; throw new InvalidOperationException("Recent pane must not record history."); }
        public Task<RemoteResourceRef> GetRootAsync(ConnectionSession session, CancellationToken cancellationToken) => throw new InvalidOperationException("Unexpected root read.");
        public Task<DirectoryPage> ListAsync(RemoteResourceRef directory, DirectoryQuery query, CancellationToken cancellationToken) => throw new InvalidOperationException("Unexpected directory read.");
        public Task<FileOperationResult> CreateDirectoryAsync(RemoteResourceRef parent, string name, CancellationToken cancellationToken) => UnexpectedWrite();
        public Task<FileOperationResult> RenameAsync(RemoteResourceRef resource, string name, CancellationToken cancellationToken) => UnexpectedWrite();
        public Task<FileOperationResult> TrashAsync(IReadOnlyList<RemoteResourceRef> resources, CancellationToken cancellationToken) => UnexpectedWrite();
        private Task<FileOperationResult> UnexpectedWrite() { ++Writes; throw new InvalidOperationException("Recent pane must not mutate files."); }
    }
}
