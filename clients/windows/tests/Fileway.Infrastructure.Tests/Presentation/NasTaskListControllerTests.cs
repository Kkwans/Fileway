using Fileway.App;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Tests.Presentation;

public sealed class NasTaskListControllerTests
{
    private static readonly AccountKey Account = new(Guid.NewGuid(), Guid.NewGuid(), "task-fixture");
    private static readonly AccountKey OtherAccount = new(Guid.NewGuid(), Guid.NewGuid(), "other-fixture");
    private static readonly DateTimeOffset CreatedAt = new(2024, 2, 3, 4, 5, 6, TimeSpan.FromHours(8));
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Fact]
    public async Task FilterAndPaginationAreOwnAccountFileOnlyAndKeepOriginalRowsAndOpaqueCursor()
    {
        using var fixture = new Fixture();
        var first = Row("first") with
        { SourceDisplayPath = "/display/%FF", OutputDisplayPath = "C:\\display only", ArchivedAt = CreatedAt.AddHours(1) };
        var second = Row("second") with { ArchivedAt = CreatedAt.AddHours(1) };
        var statuses = new[] { ServerTaskStatus.Failed, ServerTaskStatus.Canceled, ServerTaskStatus.Failed };
        fixture.Services.Page = Page([first], "opaque/+%FF cursor", 900);
        await fixture.Controller.ApplyFilterAsync(new(Statuses: statuses, Type: "file.copy", Archived: true,
            Text: "name", From: CreatedAt.AddDays(-1), To: CreatedAt.AddDays(1),
            Category: ServerTaskCategory.Background, Limit: 2, Cursor: "caller cursor must be ignored"), Token);
        statuses[0] = ServerTaskStatus.Running;
        fixture.Services.Page = Page([second], null, 900);
        await fixture.Controller.LoadMoreAsync(Token);

        Assert.Same(first, fixture.Controller.Items[0]);
        Assert.Same(second, fixture.Controller.Items[1]);
        Assert.Equal(900, fixture.Controller.Total);
        Assert.Null(fixture.Controller.NextCursor);
        Assert.False(fixture.Controller.CanLoadMore);
        Assert.Equal(2, fixture.Services.Lists.Count);
        Assert.All(fixture.Services.Lists, call =>
        {
            Assert.Equal(Account, call.Account);
            Assert.Equal(ServerTaskCategory.File, call.Query.Category);
            Assert.Equal([ServerTaskStatus.Failed, ServerTaskStatus.Canceled], call.Query.Statuses);
            Assert.Equal("file.copy", call.Query.Type);
            Assert.Equal("name", call.Query.Text);
            Assert.True(call.Query.Archived);
            Assert.Equal(2, call.Query.Limit);
        });
        Assert.Null(fixture.Services.Lists[0].Query.Cursor);
        Assert.Equal("opaque/+%FF cursor", fixture.Services.Lists[1].Query.Cursor);
        Assert.Empty(fixture.Services.Actions);
        Assert.Empty(fixture.Services.Gets);
    }

    [Theory]
    [InlineData("unknown-status")]
    [InlineData("undefined-status")]
    [InlineData("background-type")]
    [InlineData("zero-limit")]
    [InlineData("over-limit")]
    [InlineData("dates")]
    public async Task InvalidFiltersCannotDispatchOrReplaceTheCurrentFilter(string scenario)
    {
        using var fixture = new Fixture();
        var query = scenario switch
        {
            "unknown-status" => new ServerTaskQuery(Statuses: [ServerTaskStatus.Unknown]),
            "undefined-status" => new ServerTaskQuery(Statuses: [(ServerTaskStatus)999]),
            "background-type" => new ServerTaskQuery(Type: "media.hls"),
            "zero-limit" => new ServerTaskQuery(Limit: 0),
            "over-limit" => new ServerTaskQuery(Limit: 101),
            _ => new ServerTaskQuery(From: CreatedAt.AddDays(1), To: CreatedAt),
        };

        await fixture.Controller.ApplyFilterAsync(query, Token);

        Assert.Empty(fixture.Services.Lists);
        Assert.NotNull(fixture.Controller.Error);
        Assert.Equal(ServerTaskCategory.File, fixture.Controller.Filter.Category);
    }

    [Theory]
    [InlineData("wrong-account")]
    [InlineData("wrong-owner")]
    [InlineData("duplicate")]
    [InlineData("blank-id")]
    [InlineData("negative-progress")]
    [InlineData("oversized")]
    [InlineData("null-page")]
    [InlineData("null-items")]
    [InlineData("null-row")]
    [InlineData("negative-count")]
    [InlineData("negative-category-count")]
    public async Task InvalidPageCannotPartiallyReplacePreviousRows(string scenario)
    {
        using var fixture = new Fixture();
        var known = Row("known");
        fixture.Services.Page = Page([known], "known-cursor");
        await fixture.Controller.RefreshAsync(Token);
        var first = Row("first");
        fixture.Services.Page = scenario switch
        {
            "wrong-account" => Page([first, Row("other") with { Reference = new(OtherAccount, "other") }]),
            "wrong-owner" => Page([first, Row("other") with { OwnerUserId = OtherAccount.ServerUserId }]),
            "duplicate" => Page([first, first with { Title = "different" }]),
            "blank-id" => Page([first, Row(" ")]),
            "negative-progress" => Page([first, Row("bad") with { ProcessedBytes = -1 }]),
            "oversized" => Page(Enumerable.Range(0, 31).Select(index => Row(index.ToString(System.Globalization.CultureInfo.InvariantCulture))).ToArray()),
            "null-page" => null!,
            "null-items" => new(null!, null, 0, new Dictionary<string, long>(), new Dictionary<string, IReadOnlyDictionary<string, long>>()),
            "null-row" => Page([first, null!]),
            "negative-count" => Page([first]) with { Counts = new Dictionary<string, long> { ["all"] = -1 } },
            _ => Page([first]) with
            { CategoryCounts = new Dictionary<string, IReadOnlyDictionary<string, long>> { ["file"] = new Dictionary<string, long> { ["all"] = -1 } } },
        };

        await fixture.Controller.RefreshAsync(Token);

        Assert.Same(known, Assert.Single(fixture.Controller.Items));
        Assert.Equal("known-cursor", fixture.Controller.NextCursor);
        Assert.True(fixture.Controller.HasLoaded);
        Assert.NotNull(fixture.Controller.Error);
        Assert.False(fixture.Controller.IsLoading);
    }

    [Fact]
    public async Task RepeatedCursorRejectsWholePageAndKeepsExplicitRetryAvailable()
    {
        using var fixture = new Fixture();
        var known = Row("known");
        fixture.Services.Page = Page([known], "cursor-one");
        await fixture.Controller.RefreshAsync(Token);
        fixture.Services.Page = Page([Row("uncommitted")], "cursor-one");

        await fixture.Controller.LoadMoreAsync(Token);

        Assert.Same(known, Assert.Single(fixture.Controller.Items));
        Assert.Equal("cursor-one", fixture.Controller.NextCursor);
        Assert.True(fixture.Controller.CanLoadMore);
        Assert.NotNull(fixture.Controller.Error);
        fixture.Services.Page = Page([Row("accepted")], null);
        await fixture.Controller.LoadMoreAsync(Token);
        Assert.Equal(2, fixture.Controller.Items.Count);
        Assert.Null(fixture.Controller.Error);
    }

    [Fact]
    public async Task OverlappingPagesMergeByTaskIdentityAndOldEquivalentRowsCannotAct()
    {
        using var fixture = new Fixture();
        var original = Row("same");
        fixture.Services.Page = Page([original], "next");
        await fixture.Controller.RefreshAsync(Token);
        var current = original with { ProcessedItems = 9 };
        fixture.Services.Page = Page([current, Row("second")]);
        await fixture.Controller.LoadMoreAsync(Token);

        Assert.Same(current, fixture.Controller.Items[0]);
        Assert.Equal(2, fixture.Controller.Items.Count);
        Assert.Null(await fixture.Controller.RetryAsync(original, Token));
        Assert.Null(await fixture.Controller.RetryAsync(current with { }, Token));
        Assert.Empty(fixture.Services.Actions);
    }

    [Theory]
    [InlineData("wrong-account")]
    [InlineData("wrong-owner")]
    [InlineData("wrong-id")]
    public async Task DetailUsesOriginalTaskReferenceAndRejectsMismatchedResponse(string scenario)
    {
        using var fixture = new Fixture();
        var row = Row("original") with { SourceDisplayPath = "file:///display-only", OutputDisplayPath = "/%FF/display" };
        fixture.Services.Page = Page([row]);
        await fixture.Controller.RefreshAsync(Token);
        fixture.Services.Detail = scenario switch
        {
            "wrong-account" => row with { Reference = new(OtherAccount, row.Reference.Id) },
            "wrong-owner" => row with { OwnerUserId = OtherAccount.ServerUserId },
            _ => Row("different"),
        };

        await fixture.Controller.LoadDetailAsync(row, Token);

        Assert.Equal(row.Reference, Assert.Single(fixture.Services.Gets).Task);
        Assert.Same(row, Assert.Single(fixture.Controller.Items));
        Assert.Null(fixture.Controller.Detail);
        Assert.NotNull(fixture.Controller.Error);
        Assert.Empty(fixture.Services.Actions);
    }

    [Fact]
    public async Task DetailReplacementKeepsOriginalServerPathsAndRetiresOldActionObjects()
    {
        using var fixture = new Fixture();
        var row = Row("original");
        fixture.Services.Page = Page([row]);
        await fixture.Controller.RefreshAsync(Token);
        var detail = row with { SourceDisplayPath = "../display/%FF", OutputDisplayPath = "C:\\display only" };
        fixture.Services.Detail = detail;

        await fixture.Controller.LoadDetailAsync(row, Token);

        Assert.Same(detail, fixture.Controller.Detail);
        Assert.Same(detail, Assert.Single(fixture.Controller.Items));
        Assert.False(fixture.Controller.CanAct(row, ServerTaskActionKind.RetryAccepted));
        Assert.True(fixture.Controller.CanAct(detail, ServerTaskActionKind.RetryAccepted));
    }

    [Theory]
    [InlineData("future.file", "failed", ServerTaskStatus.Failed)]
    [InlineData("file.copy", "future", ServerTaskStatus.Unknown)]
    [InlineData("file.copy", "running", ServerTaskStatus.Failed)]
    public async Task UnknownRowsStayVisibleWithoutDangerousActions(string type, string rawStatus, ServerTaskStatus status)
    {
        using var fixture = new Fixture();
        var row = Row("future") with { RawType = type, RawStatus = rawStatus, Status = status };
        fixture.Services.Page = Page([row]);
        await fixture.Controller.RefreshAsync(Token);

        foreach (var action in Enum.GetValues<ServerTaskActionKind>()) Assert.False(fixture.Controller.CanAct(row, action));
        Assert.Null(await fixture.Controller.RetryAsync(row, Token));
        Assert.Null(await fixture.Controller.CancelAsync(row, Token));
        Assert.Same(row, Assert.Single(fixture.Controller.Items));
        Assert.Empty(fixture.Services.Actions);
    }

    [Theory]
    [InlineData("account")]
    [InlineData("reconnect")]
    [InlineData("services")]
    [InlineData("logout")]
    [InlineData("suspend")]
    [InlineData("dispose")]
    public async Task RetiredListCancelsRequestAndCannotPublishLateRowsOrErrors(string scenario)
    {
        using var fixture = new Fixture();
        var pendingPage = Deferred<ServerTaskPage>();
        fixture.Services.ListBehavior = (_, _, _) => pendingPage.Task;
        var pending = fixture.Controller.RefreshAsync(Token);
        var token = Assert.Single(fixture.Services.Lists).Token;

        Retire(fixture, scenario);
        pendingPage.SetResult(Page([Row("late")]));
        await pending;

        Assert.True(token.IsCancellationRequested);
        Assert.False(fixture.Controller.IsLoading);
        Assert.Empty(fixture.Controller.Items);
        Assert.Null(fixture.Controller.Error);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task NewRefreshWinsOverLateSuccessOrFailure(bool fail)
    {
        using var fixture = new Fixture();
        var pendingPage = Deferred<ServerTaskPage>();
        fixture.Services.ListBehavior = (_, _, _) => pendingPage.Task;
        var pending = fixture.Controller.RefreshAsync(Token);
        var oldToken = Assert.Single(fixture.Services.Lists).Token;
        var newest = Row("newest");
        fixture.Services.ListBehavior = null;
        fixture.Services.Page = Page([newest]);
        await fixture.Controller.RefreshAsync(Token);
        if (fail) pendingPage.SetException(new IOException("private path and host"));
        else pendingPage.SetResult(Page([Row("late")]));
        await pending;

        Assert.True(oldToken.IsCancellationRequested);
        Assert.Same(newest, Assert.Single(fixture.Controller.Items));
        Assert.Null(fixture.Controller.Error);
    }

    [Theory]
    [InlineData("account")]
    [InlineData("refresh")]
    [InlineData("suspend")]
    public async Task LateDetailCannotReplaceCurrentListOrSelection(string scenario)
    {
        using var fixture = new Fixture();
        var row = Row("original");
        fixture.Services.Page = Page([row]);
        await fixture.Controller.RefreshAsync(Token);
        var pendingDetail = Deferred<ServerTask>();
        fixture.Services.GetBehavior = (_, _) => pendingDetail.Task;
        var pending = fixture.Controller.LoadDetailAsync(row, Token);
        var oldToken = Assert.Single(fixture.Services.Gets).Token;
        if (scenario == "refresh") await fixture.Controller.RefreshAsync(Token);
        else Retire(fixture, scenario);
        pendingDetail.SetResult(row with { Title = "late" });
        await pending;

        Assert.True(oldToken.IsCancellationRequested);
        Assert.Null(fixture.Controller.Detail);
        Assert.Null(fixture.Controller.Error);
        Assert.False(fixture.Controller.IsLoadingDetail);
    }

    [Fact]
    public async Task RetryAcceptsDistinctNewIdWithoutReplacingOriginalTaskOrClaimingCompletion()
    {
        using var fixture = new Fixture();
        var original = Row("original", ServerTaskStatus.Canceled);
        fixture.Services.Page = Page([original]);
        await fixture.Controller.RefreshAsync(Token);
        var retry = Row("retry", ServerTaskStatus.Queued) with { RetryOf = original.Reference.Id };
        fixture.Services.ActionResult = new(OperationOutcome.Confirmed, ServerTaskActionKind.RetryAccepted, retry);

        var result = await fixture.Controller.RetryAsync(original, Token);

        Assert.Same(fixture.Services.ActionResult, result);
        Assert.Same(original, Assert.Single(fixture.Controller.Items));
        Assert.Same(retry, fixture.Controller.Detail);
        Assert.Equal(ServerTaskStatus.Queued, result!.Task!.Status);
        Assert.Equal("retry", NasTaskPresentationPolicy.RetryTaskId(result));
        Assert.False(fixture.Controller.CanAct(original, ServerTaskActionKind.RetryAccepted));
        Assert.Single(fixture.Services.Actions);
    }

    [Theory]
    [InlineData("same-id")]
    [InlineData("wrong-retry-of")]
    [InlineData("wrong-owner")]
    [InlineData("wrong-account")]
    [InlineData("wrong-kind")]
    [InlineData("wrong-type")]
    [InlineData("null-task")]
    [InlineData("contradictory-error")]
    public async Task InvalidMutationAcknowledgementRemainsUnknownAndRetainsReceipt(string scenario)
    {
        using var fixture = new Fixture();
        var original = Row("original");
        fixture.Services.Page = Page([original]);
        await fixture.Controller.RefreshAsync(Token);
        var retry = Row("retry", ServerTaskStatus.Queued) with { RetryOf = original.Reference.Id };
        retry = scenario switch
        {
            "same-id" => retry with { Reference = original.Reference },
            "wrong-retry-of" => retry with { RetryOf = "other" },
            "wrong-owner" => retry with { OwnerUserId = OtherAccount.ServerUserId },
            "wrong-account" => retry with { Reference = new(OtherAccount, "retry") },
            "wrong-type" => retry with { RawType = "file.move" },
            _ => retry,
        };
        var receipt = new OperationReceipt(Guid.NewGuid(), false);
        fixture.Services.ActionResult = new(OperationOutcome.Confirmed,
            scenario == "wrong-kind" ? ServerTaskActionKind.Archived : ServerTaskActionKind.RetryAccepted,
            scenario == "null-task" ? null : retry, receipt,
            scenario == "contradictory-error" ? new(ErrorCode.OutcomeUnknown, "private") : null);

        var result = await fixture.Controller.RetryAsync(original, Token);

        Assert.Equal(OperationOutcome.OutcomeUnknown, result!.Outcome);
        Assert.Same(receipt, result.Receipt);
        Assert.Same(original, Assert.Single(fixture.Controller.Items));
        Assert.Null(fixture.Controller.Detail);
        Assert.Contains("不要重复提交", fixture.Controller.Error);
        Assert.False(fixture.Controller.CanAct(original, ServerTaskActionKind.RetryAccepted));
        Assert.Null(await fixture.Controller.RetryAsync(original, Token));
        Assert.Single(fixture.Services.Actions);
    }

    [Fact]
    public async Task CancellationAcceptedPreservesRunningStateAndArchiveOnlyCallsTaskRecordService()
    {
        using var fixture = new Fixture();
        var running = Row("running", ServerTaskStatus.Running);
        fixture.Services.Page = Page([running]);
        await fixture.Controller.RefreshAsync(Token);
        var acknowledged = running with { ProcessedItems = 3 };
        fixture.Services.ActionResult = new(OperationOutcome.Confirmed, ServerTaskActionKind.CancellationRequested, acknowledged);

        var result = await fixture.Controller.CancelAsync(running, Token);

        Assert.Equal(ServerTaskStatus.Running, result!.Task!.Status);
        Assert.Same(acknowledged, Assert.Single(fixture.Controller.Items));
        Assert.False(fixture.Controller.CanAct(acknowledged, ServerTaskActionKind.CancellationRequested));
        Assert.Contains("请求已接受", NasTaskPresentationPolicy.ActionText(result));
        var completed = Row("completed", ServerTaskStatus.Completed);
        fixture.Services.Page = Page([completed]);
        await fixture.Controller.RefreshAsync(Token);
        fixture.Services.ActionResult = new(OperationOutcome.Confirmed, ServerTaskActionKind.Archived,
            completed with { ArchivedAt = CreatedAt.AddHours(1) });
        var archived = await fixture.Controller.ArchiveAsync(completed, Token);
        Assert.Equal(ServerTaskActionKind.Archived, fixture.Services.Actions[^1].Kind);
        Assert.Contains("文件未被删除", NasTaskPresentationPolicy.ActionText(archived!));
        Assert.Empty(fixture.Controller.Items);
    }

    [Fact]
    public async Task DoubleClickAndDifferentTaskActionsShareOneBoundedSlot()
    {
        using var fixture = new Fixture();
        var first = Row("first");
        var second = Row("second");
        fixture.Services.Page = Page([first, second]);
        await fixture.Controller.RefreshAsync(Token);
        var completion = Deferred<ServerTaskActionResult>();
        fixture.Services.ActionBehavior = (_, _, _) => completion.Task;
        var pending = fixture.Controller.RetryAsync(first, Token);

        Assert.Null(await fixture.Controller.RetryAsync(first, Token));
        Assert.Null(await fixture.Controller.RetryAsync(second, Token));
        Assert.Null(await fixture.Controller.ArchiveAsync(second, Token));
        Assert.True(fixture.Controller.IsActing);
        Assert.Single(fixture.Services.Actions);
        completion.SetResult(new(OperationOutcome.OutcomeUnknown, ServerTaskActionKind.RetryAccepted));
        await pending;
        Assert.False(fixture.Controller.IsActing);
    }

    [Theory]
    [InlineData("account")]
    [InlineData("logout")]
    [InlineData("suspend")]
    [InlineData("dispose")]
    public async Task RetiredActionReturnsEvidenceWithoutPublishingIntoReplacementView(string scenario)
    {
        using var fixture = new Fixture();
        var row = Row("original");
        fixture.Services.Page = Page([row]);
        await fixture.Controller.RefreshAsync(Token);
        var completion = Deferred<ServerTaskActionResult>();
        fixture.Services.ActionBehavior = (_, _, _) => completion.Task;
        var pending = fixture.Controller.RetryAsync(row, Token);
        var oldToken = Assert.Single(fixture.Services.Actions).Token;
        Retire(fixture, scenario);
        var receipt = new OperationReceipt(Guid.NewGuid(), false);
        var unknown = new ServerTaskActionResult(OperationOutcome.OutcomeUnknown, ServerTaskActionKind.RetryAccepted, Receipt: receipt);
        completion.SetResult(unknown);

        Assert.Same(unknown, await pending);
        Assert.True(oldToken.IsCancellationRequested);
        Assert.Null(fixture.Controller.LastActionResult);
        Assert.Null(fixture.Controller.Error);
        Assert.False(fixture.Controller.IsActing);
        Assert.Single(fixture.Services.Actions);
    }

    [Fact]
    public async Task NewAccountCanReadButCannotDispatchWhileRetiredProviderStillOwnsActionSlot()
    {
        using var fixture = new Fixture();
        var row = Row("original");
        fixture.Services.Page = Page([row]);
        await fixture.Controller.RefreshAsync(Token);
        var completion = Deferred<ServerTaskActionResult>();
        fixture.Services.ActionBehavior = (_, _, _) => completion.Task;
        var pending = fixture.Controller.RetryAsync(row, Token);
        var nextServices = new FakeServices();
        var nextRow = Row("next") with { Reference = new(OtherAccount, "next"), OwnerUserId = OtherAccount.ServerUserId };
        nextServices.Page = Page([nextRow]);
        fixture.Controller.SetContext(nextServices, nextServices, fixture.Session with { Account = OtherAccount, Id = Guid.NewGuid() });

        await fixture.Controller.RefreshAsync(Token);

        Assert.Same(nextRow, Assert.Single(fixture.Controller.Items));
        Assert.Null(await fixture.Controller.RetryAsync(nextRow, Token));
        Assert.Empty(nextServices.Actions);
        completion.SetResult(new(OperationOutcome.OutcomeUnknown, ServerTaskActionKind.RetryAccepted));
        await pending;
        Assert.True(fixture.Controller.CanAct(nextRow, ServerTaskActionKind.RetryAccepted));
        Assert.Null(fixture.Controller.LastActionResult);
    }

    [Fact]
    public async Task CancellationAfterInvocationIsUnknownWhilePreCanceledCallerNeverDispatches()
    {
        using var fixture = new Fixture();
        var row = Row("original");
        fixture.Services.Page = Page([row]);
        await fixture.Controller.RefreshAsync(Token);
        using var canceled = CancellationTokenSource.CreateLinkedTokenSource(Token);
        await canceled.CancelAsync();

        Assert.Null(await fixture.Controller.RetryAsync(row, canceled.Token));
        Assert.Empty(fixture.Services.Actions);
        Assert.Null(fixture.Controller.LastActionResult);
        fixture.Services.ActionBehavior = (_, _, _) => throw new OperationCanceledException("private exception");
        var result = await fixture.Controller.RetryAsync(row, Token);
        Assert.Equal(OperationOutcome.OutcomeUnknown, result!.Outcome);
        Assert.Contains("不要重复提交", fixture.Controller.Error);
        Assert.DoesNotContain("private", fixture.Controller.Error);
        await fixture.Controller.RefreshAsync(Token);
        Assert.False(fixture.Controller.CanAct(row, ServerTaskActionKind.RetryAccepted));
        Assert.Single(fixture.Services.Actions);
    }

    [Fact]
    public async Task SameSessionPermissionRefreshImmediatelyRemovesMoveRetryWithoutRetiringRead()
    {
        using var fixture = new Fixture();
        var row = Row("move") with { RawType = "file.move" };
        var deferred = Deferred<ServerTaskPage>();
        fixture.Services.ListBehavior = (_, _, _) => deferred.Task;
        var pending = fixture.Controller.RefreshAsync(Token);
        var generation = fixture.Controller.Generation;
        var token = Assert.Single(fixture.Services.Lists).Token;
        fixture.Controller.SetContext(fixture.Services, fixture.Services, fixture.Session with
        { Permissions = fixture.Session.Permissions with { Rename = false } });
        deferred.SetResult(Page([row]));
        await pending;

        Assert.Equal(generation, fixture.Controller.Generation);
        Assert.False(token.IsCancellationRequested);
        Assert.Same(row, Assert.Single(fixture.Controller.Items));
        Assert.False(fixture.Controller.CanAct(row, ServerTaskActionKind.RetryAccepted));
        Assert.Null(await fixture.Controller.RetryAsync(row, Token));
        Assert.Empty(fixture.Services.Actions);
    }

    [Fact]
    public async Task UncertainActionAfterLeavingThePageStillBlocksDuplicateSubmissionOnReturn()
    {
        using var fixture = new Fixture();
        var row = Row("original");
        fixture.Services.Page = Page([row]);
        await fixture.Controller.RefreshAsync(Token);
        var completion = Deferred<ServerTaskActionResult>();
        fixture.Services.ActionBehavior = (_, _, _) => completion.Task;
        var pending = fixture.Controller.RetryAsync(row, Token);
        fixture.Controller.SuspendView();
        completion.SetResult(new(OperationOutcome.OutcomeUnknown, ServerTaskActionKind.RetryAccepted,
            Receipt: new(Guid.NewGuid(), false)));
        var result = await pending;
        await fixture.Controller.RefreshAsync(Token);

        Assert.Equal(OperationOutcome.OutcomeUnknown, result!.Outcome);
        Assert.Null(fixture.Controller.LastActionResult);
        Assert.Same(row, Assert.Single(fixture.Controller.Items));
        Assert.False(fixture.Controller.CanAct(row, ServerTaskActionKind.RetryAccepted));
        Assert.Null(await fixture.Controller.RetryAsync(row, Token));
        Assert.Single(fixture.Services.Actions);
    }

    [Fact]
    public async Task ReadFailureKeepsKnownRowsAndDoesNotEchoPrivateProviderText()
    {
        using var fixture = new Fixture();
        var row = Row("original");
        fixture.Services.Page = Page([row]);
        await fixture.Controller.RefreshAsync(Token);
        fixture.Services.ListBehavior = (_, _, _) => throw new FilewayException(new(ErrorCode.PermissionDenied, "private host/path"));

        await fixture.Controller.RefreshAsync(Token);

        Assert.Same(row, Assert.Single(fixture.Controller.Items));
        Assert.Equal("当前账号没有操作权限。", fixture.Controller.Error);
        Assert.DoesNotContain("private", fixture.Controller.Error);
        Assert.Empty(fixture.Services.Actions);
    }

    [Fact]
    public async Task LoadingDisablesOldRowsAndDuplicatePageRequestsAreBounded()
    {
        using var fixture = new Fixture();
        var row = Row("original");
        fixture.Services.Page = Page([row], "next");
        await fixture.Controller.RefreshAsync(Token);
        var completion = Deferred<ServerTaskPage>();
        fixture.Services.ListBehavior = (_, _, _) => completion.Task;
        var pending = fixture.Controller.LoadMoreAsync(Token);

        Assert.False(fixture.Controller.CanLoadMore);
        Assert.Null(await fixture.Controller.RetryAsync(row, Token));
        await fixture.Controller.LoadMoreAsync(Token);
        await fixture.Controller.LoadDetailAsync(row, Token);
        Assert.Equal(2, fixture.Services.Lists.Count);
        Assert.Empty(fixture.Services.Actions);
        Assert.Empty(fixture.Services.Gets);
        completion.SetResult(Page([Row("second")], null, 2));
        await pending;
        Assert.False(fixture.Controller.IsLoading);
        Assert.Equal(2, fixture.Controller.Items.Count);
    }

    [Fact]
    public async Task JournalReconciliationUsesOriginalOperationAndNeverCallsAMutation()
    {
        using var fixture = new Fixture();
        var relevant = Operation(OperationKind.TaskRetry, "original");
        var unrelated = Operation(OperationKind.AddFavorite, "favorite");
        fixture.Services.Operations = new([relevant, unrelated], "operation cursor");
        await fixture.Controller.RefreshOperationsAsync(Token);
        Assert.Same(relevant, Assert.Single(fixture.Controller.Operations));
        Assert.Equal("operation cursor", fixture.Controller.OperationNextCursor);
        Assert.True(fixture.Controller.CanReconcile(relevant));
        fixture.Services.Reconciliation = new(relevant.Intent.Reference, OperationResolution.ObservedTaskState,
            Row("original"), new(ErrorCode.OutcomeUnknown, "private"));

        var result = await fixture.Controller.ReconcileAsync(relevant, Token);

        Assert.Same(fixture.Services.Reconciliation, result);
        Assert.Equal(relevant.Intent.Reference, Assert.Single(fixture.Services.Reconciles).Operation);
        Assert.Contains("仍未知", fixture.Controller.OperationsError);
        Assert.Empty(fixture.Services.Actions);
        Assert.Empty(fixture.Services.Gets);
        Assert.Empty(fixture.Services.Lists);
    }

    [Fact]
    public async Task UnknownJournalActionBlocksRepeatedMutationAndOriginalStateObservationDoesNotReleaseIt()
    {
        using var fixture = new Fixture();
        var row = Row("original");
        var operation = Operation(OperationKind.TaskRetry, row.Reference.Id);
        fixture.Services.Page = Page([row]);
        fixture.Services.Operations = new([operation], null);
        await fixture.Controller.RefreshAsync(Token);
        await fixture.Controller.RefreshOperationsAsync(Token);
        Assert.False(fixture.Controller.CanAct(row, ServerTaskActionKind.RetryAccepted));
        fixture.Services.Reconciliation = new(operation.Intent.Reference, OperationResolution.ObservedTaskState, row,
            new(ErrorCode.OutcomeUnknown, "private"));
        await fixture.Controller.ReconcileAsync(operation, Token);
        Assert.False(fixture.Controller.CanAct(row, ServerTaskActionKind.RetryAccepted));
        Assert.Null(await fixture.Controller.RetryAsync(row, Token));
        fixture.Services.Reconciliation = new(operation.Intent.Reference, OperationResolution.NotSent);
        await fixture.Controller.ReconcileAsync(operation, Token);

        Assert.True(fixture.Controller.CanAct(row, ServerTaskActionKind.RetryAccepted));
        Assert.Empty(fixture.Services.Actions);
        Assert.Contains("未自动重新提交", NasTaskPresentationPolicy.ReconciliationText(fixture.Controller.LastReconciliation!));
    }

    [Fact]
    public async Task ResolvingOneOfMultipleUnknownActionsCannotReleaseTheOther()
    {
        using var fixture = new Fixture();
        var row = Row("original");
        var first = Operation(OperationKind.TaskRetry, row.Reference.Id);
        var second = Operation(OperationKind.TaskArchive, row.Reference.Id);
        fixture.Services.Page = Page([row]);
        fixture.Services.Operations = new([first, second], null);
        await fixture.Controller.RefreshAsync(Token);
        await fixture.Controller.RefreshOperationsAsync(Token);
        fixture.Services.Reconciliation = new(second.Intent.Reference, OperationResolution.NotSent);
        await fixture.Controller.ReconcileAsync(second, Token);

        Assert.False(fixture.Controller.CanAct(row, ServerTaskActionKind.RetryAccepted));
        fixture.Services.Reconciliation = new(first.Intent.Reference, OperationResolution.NotSent);
        await fixture.Controller.ReconcileAsync(first, Token);
        Assert.True(fixture.Controller.CanAct(row, ServerTaskActionKind.RetryAccepted));
    }

    [Theory]
    [InlineData("account")]
    [InlineData("suspend")]
    [InlineData("logout")]
    public async Task RetiredHistoryAndReconciliationCannotPublishInAnotherContext(string scenario)
    {
        using var fixture = new Fixture();
        var record = Operation(OperationKind.Copy);
        fixture.Services.Operations = new([record], null);
        await fixture.Controller.RefreshOperationsAsync(Token);
        var completion = Deferred<OperationReconciliation>();
        fixture.Services.ReconcileBehavior = (_, _) => completion.Task;
        var pending = fixture.Controller.ReconcileAsync(record, Token);
        var token = Assert.Single(fixture.Services.Reconciles).Token;
        Retire(fixture, scenario);
        completion.SetResult(new(record.Intent.Reference, OperationResolution.NotSent));

        Assert.Null(await pending);
        Assert.True(token.IsCancellationRequested);
        Assert.Null(fixture.Controller.LastReconciliation);
        Assert.Null(fixture.Controller.OperationsError);
        Assert.Empty(fixture.Services.Actions);
    }

    [Theory]
    [InlineData("account")]
    [InlineData("suspend")]
    [InlineData("logout")]
    [InlineData("dispose")]
    public async Task LateJournalPageIsCanceledAndDiscardedAfterRetirement(string scenario)
    {
        using var fixture = new Fixture();
        var completion = Deferred<OperationPage>();
        fixture.Services.OperationsBehavior = (_, _, _, _) => completion.Task;
        var pending = fixture.Controller.RefreshOperationsAsync(Token);
        var token = Assert.Single(fixture.Services.OperationLists).Token;
        Retire(fixture, scenario);
        completion.SetResult(new([Operation(OperationKind.TaskRetry, "original")], "late"));
        await pending;

        Assert.True(token.IsCancellationRequested);
        Assert.Empty(fixture.Controller.Operations);
        Assert.Null(fixture.Controller.OperationNextCursor);
        Assert.False(fixture.Controller.HasLoadedOperations);
        Assert.False(fixture.Controller.IsLoadingOperations);
        Assert.Null(fixture.Controller.OperationsError);
    }

    [Theory]
    [InlineData("record-account")]
    [InlineData("resource-account")]
    [InlineData("observed-account")]
    [InlineData("observed-owner")]
    [InlineData("duplicate")]
    public async Task InvalidJournalPageCannotPartiallyReplaceKnownOperationRecords(string scenario)
    {
        using var fixture = new Fixture();
        var known = Operation(OperationKind.Copy);
        fixture.Services.Operations = new([known], null);
        await fixture.Controller.RefreshOperationsAsync(Token);
        var invalid = Operation(OperationKind.Move);
        invalid = scenario switch
        {
            "record-account" => invalid with { Intent = invalid.Intent with { Reference = new(OtherAccount, Guid.NewGuid()) } },
            "resource-account" => invalid with { Intent = invalid.Intent with
            { Items = [new(0, new(new RemoteResourceRef(OtherAccount, "/opaque", "display", "/display only")))] } },
            "observed-account" => invalid with
            { Reconciliation = new(invalid.Intent.Reference, OperationResolution.ObservedTaskState,
                Row("observed") with { Reference = new(OtherAccount, "observed") }) },
            "observed-owner" => invalid with
            { Reconciliation = new(invalid.Intent.Reference, OperationResolution.ObservedTaskState,
                Row("observed") with { OwnerUserId = OtherAccount.ServerUserId }) },
            _ => invalid,
        };
        fixture.Services.Operations = new(scenario == "duplicate" ? [invalid, invalid] : [Operation(OperationKind.Copy), invalid], null);

        await fixture.Controller.RefreshOperationsAsync(Token);

        Assert.Same(known, Assert.Single(fixture.Controller.Operations));
        Assert.NotNull(fixture.Controller.OperationsError);
        Assert.Empty(fixture.Services.Actions);
    }

    [Fact]
    public async Task JournalPaginationRejectsRepeatedCursorAndInvalidOwnershipBeforeCommitting()
    {
        using var fixture = new Fixture();
        var record = Operation(OperationKind.Copy);
        fixture.Services.Operations = new([record], "cursor");
        await fixture.Controller.RefreshOperationsAsync(Token);
        fixture.Services.Operations = new([Operation(OperationKind.Move)], "cursor");
        await fixture.Controller.LoadMoreOperationsAsync(Token);
        Assert.Same(record, Assert.Single(fixture.Controller.Operations));
        Assert.True(fixture.Controller.CanLoadMoreOperations);
        var other = Operation(OperationKind.TaskRetry, "task") with
        { Reconciliation = new(new(Account, Guid.NewGuid()), OperationResolution.NotSent) };
        fixture.Services.Operations = new([other], null);
        await fixture.Controller.LoadMoreOperationsAsync(Token);

        Assert.Same(record, Assert.Single(fixture.Controller.Operations));
        Assert.NotNull(fixture.Controller.OperationsError);
        Assert.Equal("cursor", fixture.Services.OperationLists[^1].Cursor);
        Assert.All(fixture.Services.OperationLists, call => Assert.Equal(Account, call.Account));
    }

    [Fact]
    public async Task ProviderCancellationCallbacksDoNotBlockRetirementOrEscapeAsReadErrors()
    {
        using var fixture = new Fixture();
        var completion = Deferred<ServerTaskPage>();
        var callbackEntered = Deferred();
        var callbackRelease = Deferred();
        CancellationTokenRegistration registration = default;
        fixture.Services.ListBehavior = (_, _, token) =>
        {
            registration = token.Register(() =>
            {
                callbackEntered.SetResult();
                callbackRelease.Task.GetAwaiter().GetResult();
                throw new InvalidOperationException("private cancellation callback");
            });
            return completion.Task;
        };
        var pending = fixture.Controller.RefreshAsync(Token);
        try
        {
            fixture.Controller.SuspendView();
            await callbackEntered.Task.WaitAsync(Token);
            completion.SetResult(Page([Row("late")]));
            await pending.WaitAsync(Token);
            Assert.True(Assert.Single(fixture.Services.Lists).Token.IsCancellationRequested);
            Assert.Empty(fixture.Controller.Items);
            Assert.Null(fixture.Controller.Error);
        }
        finally
        {
            callbackRelease.TrySetResult();
            await registration.DisposeAsync();
        }
    }

    [Fact]
    public async Task FileCountsUseFileCategoryInsteadOfGlobalBackgroundTotals()
    {
        using var fixture = new Fixture();
        var fileCounts = new Dictionary<string, long> { ["all"] = 7, ["active"] = 3 };
        fixture.Services.Page = Page([Row("file")], total: 7) with
        {
            Counts = new Dictionary<string, long> { ["all"] = 9, ["active"] = 5 },
            CategoryCounts = new Dictionary<string, IReadOnlyDictionary<string, long>>
            {
                ["file"] = fileCounts,
                ["background"] = new Dictionary<string, long> { ["all"] = 2, ["active"] = 2 },
            },
        };

        await fixture.Controller.RefreshAsync(Token);
        fileCounts["all"] = 100;

        Assert.Equal(7, fixture.Controller.Total);
        Assert.Equal(7, fixture.Controller.Counts["all"]);
        Assert.Equal(3, fixture.Controller.Counts["active"]);
        Assert.Null(fixture.Controller.Error);
    }

    [Fact]
    public async Task MissingFileCategoryCannotBorrowGlobalCounts()
    {
        using var fixture = new Fixture();
        fixture.Services.Page = Page([Row("file")], total: 7) with
        {
            Counts = new Dictionary<string, long> { ["all"] = 9 },
            CategoryCounts = new Dictionary<string, IReadOnlyDictionary<string, long>>
            { ["background"] = new Dictionary<string, long> { ["all"] = 2 } },
        };

        await fixture.Controller.RefreshAsync(Token);

        Assert.Empty(fixture.Controller.Counts);
        Assert.Equal(7, fixture.Controller.Total);
    }

    [Fact]
    public async Task FirstRefreshCannotAuthorizeMutationBeforeAllJournalPagesFinish()
    {
        using var fixture = new Fixture();
        var row = Row("original");
        var unknown = Operation(OperationKind.TaskRetry, row.Reference.Id);
        var secondPage = Deferred<OperationPage>();
        fixture.Services.Page = Page([row]);
        fixture.Services.OperationsBehavior = (_, cursor, _, _) => cursor is null
            ? Task.FromResult(new OperationPage([Operation(OperationKind.AddFavorite)], "second")) : secondPage.Task;

        var pending = fixture.Controller.RefreshAsync(Token);

        Assert.Same(row, Assert.Single(fixture.Controller.Items));
        Assert.True(fixture.Controller.HasLoaded);
        Assert.True(fixture.Controller.IsVerifyingOperations);
        Assert.False(fixture.Controller.HasVerifiedOperations);
        Assert.False(fixture.Controller.CanAct(row, ServerTaskActionKind.RetryAccepted));
        Assert.Null(await fixture.Controller.RetryAsync(row, Token));
        Assert.Empty(fixture.Services.Actions);
        secondPage.SetResult(new([unknown], null));
        await pending;
        Assert.True(fixture.Controller.HasVerifiedOperations);
        Assert.False(fixture.Controller.IsVerifyingOperations);
        Assert.False(fixture.Controller.CanAct(row, ServerTaskActionKind.RetryAccepted));
        Assert.Null(await fixture.Controller.RetryAsync(row, Token));
        Assert.Empty(fixture.Services.Actions);
        Assert.Equal([null, "second"], fixture.Services.OperationLists.Select(call => call.Cursor));
    }

    [Fact]
    public async Task SameAccountNewSessionMustRecheckDurableUnknownActions()
    {
        using var fixture = new Fixture();
        var row = Row("original");
        fixture.Services.Page = Page([row]);
        await fixture.Controller.RefreshAsync(Token);
        Assert.True(fixture.Controller.CanAct(row, ServerTaskActionKind.RetryAccepted));
        fixture.Services.Operations = new([Operation(OperationKind.TaskRetry, row.Reference.Id)], null);

        fixture.Controller.SetContext(fixture.Services, fixture.Services, fixture.Session with { Id = Guid.NewGuid() });

        Assert.False(fixture.Controller.HasVerifiedOperations);
        Assert.False(fixture.Controller.CanAct(row, ServerTaskActionKind.RetryAccepted));
        await fixture.Controller.RefreshAsync(Token);
        Assert.Same(row, Assert.Single(fixture.Controller.Items));
        Assert.True(fixture.Controller.HasVerifiedOperations);
        Assert.False(fixture.Controller.CanAct(row, ServerTaskActionKind.RetryAccepted));
        Assert.Null(await fixture.Controller.RetryAsync(row, Token));
        Assert.Empty(fixture.Services.Actions);
    }

    [Fact]
    public async Task EachPostRechecksFullJournalWhileHoldingActionSlot()
    {
        using var fixture = new Fixture();
        var row = Row("original");
        fixture.Services.Page = Page([row]);
        await fixture.Controller.RefreshAsync(Token);
        Assert.True(fixture.Controller.CanAct(row, ServerTaskActionKind.RetryAccepted));
        var unknown = Operation(OperationKind.TaskRetry, row.Reference.Id);
        fixture.Services.OperationsBehavior = (_, cursor, _, _) => Task.FromResult(cursor is null
            ? new OperationPage([], "late-page") : new OperationPage([unknown], null));

        var result = await fixture.Controller.RetryAsync(row, Token);

        Assert.Null(result);
        Assert.Null(fixture.Controller.LastActionResult);
        Assert.Empty(fixture.Services.Actions);
        Assert.False(fixture.Controller.CanAct(row, ServerTaskActionKind.RetryAccepted));
        Assert.Contains("不要重复提交", fixture.Controller.Error);
        Assert.Equal([null, null, "late-page"], fixture.Services.OperationLists.Select(call => call.Cursor));
    }

    [Theory]
    [InlineData("missing")]
    [InlineData("first-page")]
    [InlineData("later-page")]
    [InlineData("cursor-loop")]
    [InlineData("unknown-phase")]
    [InlineData("missing-task-id")]
    public async Task IncompleteOrInvalidPersistentHistoryFailsClosed(string scenario)
    {
        using var fixture = new Fixture();
        var row = Row("original");
        fixture.Services.Page = Page([row]);
        if (scenario == "missing") fixture.Controller.SetContext(fixture.Services, null, fixture.Session);
        else fixture.Services.OperationsBehavior = (_, cursor, _, _) => scenario switch
        {
            "first-page" => throw new FilewayException(new(ErrorCode.LocalStorageUnavailable, "private journal location")),
            "later-page" => cursor is null ? Task.FromResult(new OperationPage([], "second")) :
                throw new IOException("private journal location"),
            "cursor-loop" => Task.FromResult(new OperationPage([], "loop")),
            "unknown-phase" => Task.FromResult(new OperationPage(
                [Operation(OperationKind.TaskRetry, row.Reference.Id) with { Phase = (OperationPhase)999 }], null)),
            _ => Task.FromResult(new OperationPage([Operation(OperationKind.TaskRetry)], null)),
        };

        await fixture.Controller.RefreshAsync(Token);

        Assert.Same(row, Assert.Single(fixture.Controller.Items));
        Assert.False(fixture.Controller.HasVerifiedOperations);
        Assert.False(fixture.Controller.CanAct(row, ServerTaskActionKind.RetryAccepted));
        Assert.Null(await fixture.Controller.RetryAsync(row, Token));
        Assert.Empty(fixture.Services.Actions);
        Assert.Null(fixture.Controller.LastActionResult);
        Assert.NotNull(fixture.Controller.OperationsError);
        Assert.DoesNotContain("private", fixture.Controller.OperationsError);
    }

    [Fact]
    public async Task FailedActionPreflightNeverBecomesAnUnknownMutation()
    {
        using var fixture = new Fixture();
        var row = Row("original");
        fixture.Services.Page = Page([row]);
        await fixture.Controller.RefreshAsync(Token);
        fixture.Services.OperationsBehavior = (_, _, _, _) => throw new IOException("private journal location");

        var result = await fixture.Controller.RetryAsync(row, Token);

        Assert.Null(result);
        Assert.Null(fixture.Controller.LastActionResult);
        Assert.Empty(fixture.Services.Actions);
        Assert.False(fixture.Controller.HasVerifiedOperations);
        Assert.False(fixture.Controller.IsActing);
        Assert.NotNull(fixture.Controller.OperationsError);
        Assert.DoesNotContain("private", fixture.Controller.OperationsError);
    }

    [Theory]
    [InlineData("caller")]
    [InlineData("account")]
    [InlineData("reconnect")]
    [InlineData("logout")]
    [InlineData("suspend")]
    [InlineData("dispose")]
    public async Task RetiredNoncooperativeJournalPreflightCannotDispatchOrClaimUnknownMutation(string scenario)
    {
        using var fixture = new Fixture();
        using var caller = CancellationTokenSource.CreateLinkedTokenSource(Token);
        var row = Row("original");
        fixture.Services.Page = Page([row]);
        await fixture.Controller.RefreshAsync(Token);
        var completion = Deferred<OperationPage>();
        fixture.Services.OperationsBehavior = (_, _, _, _) => completion.Task;
        var pending = fixture.Controller.RetryAsync(row, caller.Token);
        var requestToken = fixture.Services.OperationLists[^1].Token;
        Assert.True(fixture.Controller.IsActing);
        Assert.True(fixture.Controller.IsVerifyingOperations);
        Assert.Empty(fixture.Services.Actions);

        if (scenario == "caller") await caller.CancelAsync();
        else Retire(fixture, scenario);
        completion.SetResult(new([], null));

        Assert.Null(await pending);
        Assert.True(requestToken.IsCancellationRequested);
        Assert.Empty(fixture.Services.Actions);
        Assert.Null(fixture.Controller.LastActionResult);
        Assert.False(fixture.Controller.IsActing);
        Assert.False(fixture.Controller.IsVerifyingOperations);
    }

    [Fact]
    public async Task PendingPreflightBoundsDifferentTaskActionsUntilProofCompletes()
    {
        using var fixture = new Fixture();
        var first = Row("first");
        var second = Row("second");
        fixture.Services.Page = Page([first, second]);
        await fixture.Controller.RefreshAsync(Token);
        var completion = Deferred<OperationPage>();
        fixture.Services.OperationsBehavior = (_, _, _, _) => completion.Task;
        fixture.Services.ActionResult = new(OperationOutcome.Confirmed, ServerTaskActionKind.RetryAccepted,
            Row("retry", ServerTaskStatus.Queued) with { RetryOf = first.Reference.Id });
        var pending = fixture.Controller.RetryAsync(first, Token);

        Assert.Null(await fixture.Controller.RetryAsync(first, Token));
        Assert.Null(await fixture.Controller.RetryAsync(second, Token));
        Assert.Empty(fixture.Services.Actions);
        completion.SetResult(new([], null));
        Assert.Equal(OperationOutcome.Confirmed, (await pending)!.Outcome);
        Assert.Equal(first.Reference, Assert.Single(fixture.Services.Actions).Task);
        Assert.False(fixture.Controller.IsActing);
    }

    [Theory]
    [InlineData("reconnect")]
    [InlineData("account")]
    [InlineData("logout")]
    [InlineData("suspend")]
    [InlineData("dispose")]
    public async Task StartedServiceHandoffPreservesConfirmedAcknowledgementAcrossSynchronousRetirement(string scenario)
    {
        using var fixture = new Fixture();
        var original = Row("original");
        fixture.Services.Page = Page([original]);
        await fixture.Controller.RefreshAsync(Token);
        var completion = Deferred<ServerTaskActionResult>();
        fixture.Services.ActionBehavior = (_, _, _) =>
        {
            // A provider may synchronously report session retirement as it starts. Once
            // called, it owns a real request; retirement does not revoke a later observed ACK.
            Retire(fixture, scenario);
            return completion.Task;
        };

        var pending = fixture.Controller.RetryAsync(original, Token);

        Assert.True(Assert.Single(fixture.Services.Actions).Token.IsCancellationRequested);
        Assert.False(fixture.Controller.IsActing);
        Assert.Null(fixture.Controller.LastActionResult);
        Assert.Null(await fixture.Controller.RetryAsync(original, Token));
        var acknowledged = new ServerTaskActionResult(OperationOutcome.Confirmed, ServerTaskActionKind.RetryAccepted,
            Row("retry", ServerTaskStatus.Queued) with { RetryOf = original.Reference.Id }, new(Guid.NewGuid(), true));
        completion.SetResult(acknowledged);

        Assert.Same(acknowledged, await pending);
        Assert.Equal(OperationOutcome.Confirmed, acknowledged.Outcome);
        Assert.Equal("retry", NasTaskPresentationPolicy.RetryTaskId(acknowledged));
        Assert.Null(fixture.Controller.LastActionResult);
        Assert.Null(fixture.Controller.Error);
        Assert.Single(fixture.Services.Actions);
    }

    [Fact]
    public async Task StartedServiceHandoffRetiringAndThrowingCannotBeReportedAsUnsent()
    {
        using var fixture = new Fixture();
        var original = Row("original");
        fixture.Services.Page = Page([original]);
        await fixture.Controller.RefreshAsync(Token);
        fixture.Services.ActionBehavior = (_, _, _) =>
        {
            fixture.Controller.SuspendView();
            throw new IOException("private provider dispatch failure");
        };

        var result = await fixture.Controller.RetryAsync(original, Token);

        Assert.NotNull(result);
        Assert.Equal(OperationOutcome.OutcomeUnknown, result.Outcome);
        Assert.Contains("不要重复提交", NasTaskPresentationPolicy.ActionText(result));
        Assert.DoesNotContain("private", NasTaskPresentationPolicy.ActionText(result));
        Assert.Null(fixture.Controller.LastActionResult);
        Assert.False(fixture.Controller.IsActing);
        Assert.Single(fixture.Services.Actions);
    }

    private static ServerTask Row(string id, ServerTaskStatus status = ServerTaskStatus.Failed) =>
        new(new(Account, id), Account.ServerUserId, "file.copy", status.ToString().ToLowerInvariant(), status,
            "task " + id, CreatedAt, 10, 2, 100, 20, SourceDisplayPath: "/display only", OutputDisplayPath: "/output only");
    private static ServerTaskPage Page(ServerTask[] rows, string? cursor = null, long? total = null) =>
        new(rows, cursor, total ?? rows.Length, new Dictionary<string, long> { ["all"] = total ?? rows.Length },
            new Dictionary<string, IReadOnlyDictionary<string, long>>());
    private static OperationRecord Operation(OperationKind kind, string? taskId = null)
    {
        var operation = new OperationRef(Account, Guid.NewGuid());
        return new(new(operation, Guid.NewGuid(), kind, [], new(RecordId: taskId)),
            OperationPhase.OutcomeUnknown, 1, CreatedAt, CreatedAt);
    }
    private static TaskCompletionSource<T> Deferred<T>() => new(TaskCreationOptions.RunContinuationsAsynchronously);
    private static TaskCompletionSource Deferred() => new(TaskCreationOptions.RunContinuationsAsynchronously);
    private static void Retire(Fixture fixture, string scenario)
    {
        switch (scenario)
        {
            case "suspend": fixture.Controller.SuspendView(); break;
            case "dispose": fixture.Controller.Dispose(); break;
            case "logout": fixture.Controller.SetContext(null, null, null); break;
            case "services":
                var services = new FakeServices(); fixture.Controller.SetContext(services, services, fixture.Session); break;
            default:
                fixture.Controller.SetContext(fixture.Services, fixture.Services, fixture.Session with
                { Id = Guid.NewGuid(), Account = scenario == "account" ? OtherAccount : Account }); break;
        }
    }

    private sealed class Fixture : IDisposable
    {
        internal FakeServices Services { get; } = new();
        internal ConnectionSession Session { get; } = new(Guid.NewGuid(), Account, "fixture", new(true, true, true, true, true, true), new());
        internal NasTaskListController Controller { get; } = new();
        internal Fixture() => Controller.SetContext(Services, Services, Session);
        public void Dispose() => Controller.Dispose();
    }

    private sealed class FakeServices : IServerTaskService, IOperationHistoryService
    {
        internal ServerTaskPage Page { get; set; } = NasTaskListControllerTests.Page([]);
        internal ServerTask Detail { get; set; } = Row("fixture");
        internal OperationPage Operations { get; set; } = new([], null);
        internal ServerTaskActionResult ActionResult { get; set; } = new(OperationOutcome.Partial, ServerTaskActionKind.RetryAccepted);
        internal OperationReconciliation Reconciliation { get; set; } = new(new(Account, Guid.NewGuid()), OperationResolution.NeedsReview);
        internal Func<AccountKey, ServerTaskQuery, CancellationToken, Task<ServerTaskPage>>? ListBehavior { get; set; }
        internal Func<ServerTaskRef, CancellationToken, Task<ServerTask>>? GetBehavior { get; set; }
        internal Func<ServerTaskRef, ServerTaskActionKind, CancellationToken, Task<ServerTaskActionResult>>? ActionBehavior { get; set; }
        internal Func<AccountKey, string?, int, CancellationToken, Task<OperationPage>>? OperationsBehavior { get; set; }
        internal Func<OperationRef, CancellationToken, Task<OperationReconciliation>>? ReconcileBehavior { get; set; }
        internal List<(AccountKey Account, ServerTaskQuery Query, CancellationToken Token)> Lists { get; } = [];
        internal List<(ServerTaskRef Task, CancellationToken Token)> Gets { get; } = [];
        internal List<(ServerTaskRef Task, ServerTaskActionKind Kind, CancellationToken Token)> Actions { get; } = [];
        internal List<(AccountKey Account, string? Cursor, int Limit, CancellationToken Token)> OperationLists { get; } = [];
        internal List<(OperationRef Operation, CancellationToken Token)> Reconciles { get; } = [];
        public Task<ServerTaskPage> ListTasksAsync(AccountKey account, ServerTaskQuery query, CancellationToken cancellationToken)
        { Lists.Add((account, query, cancellationToken)); return ListBehavior?.Invoke(account, query, cancellationToken) ?? Task.FromResult(Page); }
        public Task<ServerTask> GetTaskAsync(ServerTaskRef task, CancellationToken cancellationToken)
        { Gets.Add((task, cancellationToken)); return GetBehavior?.Invoke(task, cancellationToken) ?? Task.FromResult(Detail); }
        public Task<ServerTaskActionResult> CancelTaskAsync(ServerTaskRef task, CancellationToken cancellationToken) =>
            Act(task, ServerTaskActionKind.CancellationRequested, cancellationToken);
        public Task<ServerTaskActionResult> RetryTaskAsync(ServerTaskRef task, CancellationToken cancellationToken) =>
            Act(task, ServerTaskActionKind.RetryAccepted, cancellationToken);
        public Task<ServerTaskActionResult> ArchiveTaskAsync(ServerTaskRef task, CancellationToken cancellationToken) =>
            Act(task, ServerTaskActionKind.Archived, cancellationToken);
        public Task<ServerTaskActionResult> UnarchiveTaskAsync(ServerTaskRef task, CancellationToken cancellationToken) =>
            Act(task, ServerTaskActionKind.Unarchived, cancellationToken);
        private Task<ServerTaskActionResult> Act(ServerTaskRef task, ServerTaskActionKind kind, CancellationToken token)
        { Actions.Add((task, kind, token)); return ActionBehavior?.Invoke(task, kind, token) ?? Task.FromResult(ActionResult); }
        public Task<OperationPage> ListOperationsAsync(AccountKey account, string? cursor, int limit, CancellationToken cancellationToken)
        { OperationLists.Add((account, cursor, limit, cancellationToken)); return OperationsBehavior?.Invoke(account, cursor, limit, cancellationToken) ?? Task.FromResult(Operations); }
        public Task<OperationReconciliation> ReconcileAsync(OperationRef operation, CancellationToken cancellationToken)
        { Reconciles.Add((operation, cancellationToken)); return ReconcileBehavior?.Invoke(operation, cancellationToken) ?? Task.FromResult(Reconciliation); }
    }
}
