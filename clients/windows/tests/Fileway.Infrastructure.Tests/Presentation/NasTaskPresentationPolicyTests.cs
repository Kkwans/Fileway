using Fileway.App;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Tests.Presentation;

public sealed class NasTaskPresentationPolicyTests
{
    private static readonly AccountKey Account = new(Guid.NewGuid(), Guid.NewGuid(), "task-fixture");
    private static readonly PermissionSet AllPermissions = new(true, true, true, true, true, true);

    [Theory]
    [InlineData("file.copy", ServerTaskStatus.Canceled, true, false, true)]
    [InlineData("file.copy", ServerTaskStatus.Failed, true, false, true)]
    [InlineData("file.copy", ServerTaskStatus.Interrupted, true, false, true)]
    [InlineData("file.copy", ServerTaskStatus.Failed, false, true, false)]
    [InlineData("file.move", ServerTaskStatus.Canceled, true, true, true)]
    [InlineData("file.move", ServerTaskStatus.Failed, true, false, false)]
    [InlineData("file.move", ServerTaskStatus.Interrupted, false, true, false)]
    [InlineData("file.copy", ServerTaskStatus.Completed, true, true, false)]
    [InlineData("file.copy", ServerTaskStatus.Running, true, true, false)]
    public void RetryUsesServerStatesAndCurrentCreateRenamePermissions(string type, ServerTaskStatus status,
        bool create, bool rename, bool expected)
    {
        var task = TaskRow(type, status);
        var permissions = AllPermissions with { Create = create, Rename = rename };

        Assert.Equal(expected, NasTaskPresentationPolicy.CanAct(task, permissions, ServerTaskActionKind.RetryAccepted));
    }

    [Theory]
    [InlineData("future.file", "failed", ServerTaskStatus.Failed)]
    [InlineData("media.hls", "failed", ServerTaskStatus.Failed)]
    [InlineData("file.copy", "future-status", ServerTaskStatus.Unknown)]
    [InlineData("file.copy", "running", ServerTaskStatus.Failed)]
    [InlineData("file.copy", "failed", (ServerTaskStatus)999)]
    public void UnknownTypeOrStatusCannotOfferAnyMutation(string type, string rawStatus, ServerTaskStatus status)
    {
        var task = TaskRow(type, status) with { RawStatus = rawStatus };

        foreach (var action in Enum.GetValues<ServerTaskActionKind>())
            Assert.False(NasTaskPresentationPolicy.CanAct(task, AllPermissions, action));
    }

    [Fact]
    public void ArchivedTasksOnlyOfferUnarchiveAndRecordActionDoesNotRequireFileDeletePermission()
    {
        var permissions = new PermissionSet(false, false, false, false, false, false);
        var task = TaskRow("file.copy", ServerTaskStatus.Canceled);

        Assert.True(NasTaskPresentationPolicy.CanAct(task, permissions, ServerTaskActionKind.Archived));
        var archived = task with { ArchivedAt = DateTimeOffset.UtcNow };
        Assert.True(NasTaskPresentationPolicy.CanAct(archived, permissions, ServerTaskActionKind.Unarchived));
        Assert.False(NasTaskPresentationPolicy.CanAct(archived, AllPermissions, ServerTaskActionKind.Archived));
        Assert.False(NasTaskPresentationPolicy.CanAct(archived, AllPermissions, ServerTaskActionKind.RetryAccepted));
    }

    [Fact]
    public void CancelAcceptanceDoesNotClaimTheTaskWasCanceledOrCompleted()
    {
        var task = TaskRow("file.copy", ServerTaskStatus.Running);
        var result = new ServerTaskActionResult(OperationOutcome.Confirmed,
            ServerTaskActionKind.CancellationRequested, task);

        Assert.Equal("正在执行", NasTaskPresentationPolicy.StatusText(task));
        Assert.Contains("请求已接受", NasTaskPresentationPolicy.ActionText(result));
        Assert.DoesNotContain("已取消", NasTaskPresentationPolicy.ActionText(result));
        Assert.Null(NasTaskPresentationPolicy.RetryTaskId(result));
    }

    [Fact]
    public void RetryPresentationExposesNewTaskIdAndArchiveExplainsItKeepsFiles()
    {
        var original = TaskRow("file.move", ServerTaskStatus.Failed);
        var retry = original with { Reference = new(Account, "new-task"), RetryOf = original.Reference.Id };
        var result = new ServerTaskActionResult(OperationOutcome.Confirmed, ServerTaskActionKind.RetryAccepted, retry);

        Assert.Equal("new-task", NasTaskPresentationPolicy.RetryTaskId(result));
        Assert.Contains("新任务", NasTaskPresentationPolicy.ActionText(result));
        Assert.Contains("文件未被删除", NasTaskPresentationPolicy.ActionText(
            new(OperationOutcome.Confirmed, ServerTaskActionKind.Archived, original)));
        Assert.Null(NasTaskPresentationPolicy.RetryTaskId(result with { Outcome = OperationOutcome.OutcomeUnknown }));
    }

    [Fact]
    public void ProgressDoesNotTurnProcessedCountsOrZeroTotalIntoCompletion()
    {
        var task = TaskRow("file.copy", ServerTaskStatus.Running) with
        { TotalItems = 0, ProcessedItems = 4, TotalBytes = 0, ProcessedBytes = 17 };

        Assert.Null(NasTaskPresentationPolicy.ProgressFraction(task));
        Assert.Contains("已处理", NasTaskPresentationPolicy.ProgressText(task));
        Assert.Equal("正在执行", NasTaskPresentationPolicy.StatusText(task));
        Assert.Equal(1d, NasTaskPresentationPolicy.ProgressFraction(task with { TotalBytes = 10, ProcessedBytes = 17 }));
        Assert.Equal(0.25d, NasTaskPresentationPolicy.ProgressFraction(task with { TotalItems = 8, ProcessedItems = 2 }));
    }

    [Fact]
    public void ObservedOriginalTaskWithUnknownErrorDoesNotResolveAnUnknownRetry()
    {
        var operation = new OperationRef(Account, Guid.NewGuid());
        var record = new OperationRecord(new(operation, Guid.NewGuid(), OperationKind.TaskRetry, [], new(RecordId: "original")),
            OperationPhase.OutcomeUnknown, 1, DateTimeOffset.UtcNow, DateTimeOffset.UtcNow);
        var observed = new OperationReconciliation(operation, OperationResolution.ObservedTaskState,
            TaskRow("file.copy", ServerTaskStatus.Failed), new(ErrorCode.OutcomeUnknown, "private fixture"));

        Assert.True(NasTaskPresentationPolicy.NeedsReconciliation(record with { Reconciliation = observed }));
        Assert.False(NasTaskPresentationPolicy.IsResolved(observed));
        Assert.Contains("仍未知", NasTaskPresentationPolicy.ReconciliationText(observed));
        Assert.DoesNotContain("private", NasTaskPresentationPolicy.ReconciliationText(observed));
        Assert.False(NasTaskPresentationPolicy.NeedsReconciliation(record with
        { Reconciliation = new(operation, OperationResolution.NotSent) }));
    }

    [Fact]
    public void ErrorAndUnknownResultTextNeverEchoProviderDetailsOrSuggestMutationReplay()
    {
        var result = new ServerTaskActionResult(OperationOutcome.OutcomeUnknown, ServerTaskActionKind.RetryAccepted,
            Error: new(ErrorCode.NetworkUnavailable, "private host and path"));

        Assert.Contains("不要重复提交", NasTaskPresentationPolicy.ActionText(result));
        Assert.DoesNotContain("private", NasTaskPresentationPolicy.ActionText(result));
        Assert.Equal("当前账号没有操作权限。", NasTaskPresentationPolicy.SafeError(new(ErrorCode.Unknown, "private", 403), "fallback"));
        Assert.Equal("fallback", NasTaskPresentationPolicy.SafeError(new(ErrorCode.Unknown, "private"), "fallback"));
    }

    [Fact]
    public void ConfirmedAcknowledgementWithFailedPersistenceKeepsSuccessAndReportsJournalWarning()
    {
        var result = new ServerTaskActionResult(OperationOutcome.Confirmed, ServerTaskActionKind.CancellationRequested,
            TaskRow("file.copy", ServerTaskStatus.Running), new(Guid.NewGuid(), false));

        Assert.Contains("请求已接受", NasTaskPresentationPolicy.ActionText(result));
        Assert.Contains("本地确认记录未保存", NasTaskPresentationPolicy.ActionText(result));
        Assert.DoesNotContain("结果未知", NasTaskPresentationPolicy.ActionText(result));
    }

    private static ServerTask TaskRow(string type, ServerTaskStatus status) => new(new(Account, "original"), Account.ServerUserId,
        type, status.ToString().ToLowerInvariant(), status, "fixture task", DateTimeOffset.UtcNow, 8, 2, 100, 25);
}
