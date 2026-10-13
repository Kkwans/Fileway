using Fileway.App;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Tests.Transfers;

public sealed class DownloadPresentationPolicyTests
{
    [Fact]
    public void DownloadOnlyPermissionCanPrepareWithoutAnyCreatePermission()
    {
        var permissions = new PermissionSet(true, false, false, false, false, false);

        Assert.True(DownloadPresentationPolicy.CanPrepare(permissions, currentContext: true, browserReady: true,
            loading: false, busy: false, selectedCount: 1, selectedIsDirectory: false));
    }

    [Theory]
    [InlineData(false, true, false, false, 1, false)]
    [InlineData(true, false, false, false, 1, false)]
    [InlineData(true, true, true, false, 1, false)]
    [InlineData(true, true, false, true, 1, false)]
    [InlineData(true, true, false, false, 0, false)]
    [InlineData(true, true, false, false, 2, false)]
    [InlineData(true, true, false, false, 1, true)]
    public void PreparationRequiresLiveSingleFileSelection(bool context, bool ready, bool loading, bool busy, int count, bool directory)
    {
        Assert.False(DownloadPresentationPolicy.CanPrepare(new(true, false, false, false, false, false), context,
            ready, loading, busy, count, directory));
    }

    [Fact]
    public void MissingDownloadPermissionCannotBeReplacedByCreatePermission()
    {
        Assert.False(DownloadPresentationPolicy.CanPrepare(new(false, true, true, true, true, true), true, true, false, false, 1, false));
    }

    [Fact]
    public void ReceivedActivityAdvancesWhileDurableFlushRemainsZero()
    {
        var item = Item(DownloadStatus.Downloading, confirmed: 0, received: 512);

        var progress = DownloadPresentationPolicy.Progress(item);

        Assert.False(progress.IsIndeterminate);
        Assert.Equal(50, progress.Value);
        Assert.Equal("已接收 512 B / 1 KB（尚未完成校验与保存）", progress.ActivityText);
        Assert.Equal("磁盘已确认写入 0 B / 1 KB", progress.FlushedText);
        Assert.Equal(DownloadStatus.Downloading, DownloadPresentationPolicy.EffectiveStatus(item));
        Assert.Equal(DownloadPresentationTone.Information, DownloadPresentationPolicy.Tone(item));
    }

    [Fact]
    public void BodyProgressConfirmationCannotOverwriteDurableFlushEvidence()
    {
        var item = Item(DownloadStatus.Downloading, confirmed: 0, received: 1024);
        item = item with { Progress = item.Progress! with { ConfirmedOffset = 1024 } };

        Assert.Equal("磁盘已确认写入 0 B / 1 KB", DownloadPresentationPolicy.Progress(item).FlushedText);
        Assert.Equal(DownloadStatus.Downloading, DownloadPresentationPolicy.EffectiveStatus(item));
    }

    [Theory]
    [InlineData(-1)]
    [InlineData(1025)]
    public void OutOfRangeReceivedEventsAreRejectedRatherThanDisplayed(long received)
    {
        var item = Item(DownloadStatus.Downloading, confirmed: 128, received: received);

        var progress = DownloadPresentationPolicy.Progress(item);

        Assert.Equal(12.5, progress.Value);
        Assert.Contains("已接收 128 B", progress.ActivityText, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("task")]
    [InlineData("generation")]
    [InlineData("total")]
    public void BodyActivityMustMatchThePinnedTaskExecutionAndLength(string mismatch)
    {
        var item = Item(DownloadStatus.Downloading, confirmed: 0, received: 1024);
        var body = item.Progress!;
        body = mismatch switch
        {
            "task" => body with { Run = body.Run with { Task = new(body.Run.Task.Account, Guid.NewGuid()) } },
            "generation" => body with { Run = body.Run with { Generation = body.Run.Generation + 1 } },
            _ => body with { TotalBytes = body.TotalBytes + 1 },
        };

        var progress = DownloadPresentationPolicy.Progress(item with { Progress = body });

        Assert.Equal(0, progress.Value);
        Assert.Contains("已接收 0 B", progress.ActivityText, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData(DownloadStatus.Verifying, "正在校验")]
    [InlineData(DownloadStatus.Publishing, "正在保存文件")]
    public void FullReceptionDoesNotBecomeCompletedBeforeVerificationAndPublish(DownloadStatus status, string stage)
    {
        var item = Item(status, confirmed: 1024, received: 1024);

        Assert.Equal(100, DownloadPresentationPolicy.Progress(item).Value);
        Assert.Equal(stage, DownloadPresentationPolicy.Stage(item));
        Assert.Contains("尚未", DownloadPresentationPolicy.Progress(item).ActivityText, StringComparison.Ordinal);
        Assert.Equal(DownloadPresentationTone.Information, DownloadPresentationPolicy.Tone(item));
    }

    [Theory]
    [InlineData(false, TransferUncertainty.None)]
    [InlineData(true, TransferUncertainty.LocalPublish)]
    public void CompletionRequiresConfirmedLocalPublicationAndNoUncertainty(bool published, TransferUncertainty uncertainty)
    {
        var item = Item(DownloadStatus.Completed, published: published, reconcile: true);
        item = item with { Snapshot = item.Snapshot with { Uncertainty = uncertainty } };

        Assert.Equal(DownloadStatus.OutcomeUnknown, DownloadPresentationPolicy.EffectiveStatus(item));
        Assert.Equal(DownloadPresentationTone.Warning, DownloadPresentationPolicy.Tone(item));
        Assert.Equal("核实本地结果", DownloadPresentationPolicy.PrimaryLabel(item));
        Assert.False(DownloadPresentationPolicy.CanCancel(item));
    }

    [Fact]
    public void UncertainCancellationCannotClaimCompletedCleanup()
    {
        var item = Item(DownloadStatus.Canceled, intent: TransferIntent.Cancel, reconcile: true);
        item = item with { Snapshot = item.Snapshot with { Uncertainty = TransferUncertainty.LocalPublish } };

        Assert.Equal(DownloadStatus.OutcomeUnknown, DownloadPresentationPolicy.EffectiveStatus(item));
        Assert.Equal("取消结果待本地核实", DownloadPresentationPolicy.Stage(item));
        Assert.Equal("核实取消结果", DownloadPresentationPolicy.PrimaryLabel(item));
        Assert.DoesNotContain("已确认清理", DownloadPresentationPolicy.Detail(item), StringComparison.Ordinal);
    }

    [Theory]
    [InlineData(DownloadStatus.NotStarted)]
    [InlineData(DownloadStatus.Stopped)]
    [InlineData(DownloadStatus.WaitingNetwork)]
    [InlineData(DownloadStatus.WaitingAuthentication)]
    [InlineData(DownloadStatus.Failed)]
    [InlineData(DownloadStatus.OutcomeUnknown)]
    public void IdleEvidenceOffersOnlyLocalReconciliationWhenAuthorized(DownloadStatus status)
    {
        var item = Item(status, reconcile: true);

        Assert.True(DownloadPresentationPolicy.CanReconcile(item));
        Assert.Equal("核实本地结果", DownloadPresentationPolicy.PrimaryLabel(item));
        Assert.DoesNotContain("恢复", DownloadPresentationPolicy.PrimaryLabel(item), StringComparison.Ordinal);
        Assert.DoesNotContain("暂停", DownloadPresentationPolicy.PrimaryLabel(item), StringComparison.Ordinal);
        Assert.False(DownloadPresentationPolicy.CanReconcile(item with { Snapshot = item.Snapshot with { CanReconcileLocally = false } }));
    }

    [Theory]
    [InlineData(DownloadStatus.Queued)]
    [InlineData(DownloadStatus.Preparing)]
    [InlineData(DownloadStatus.Downloading)]
    [InlineData(DownloadStatus.Verifying)]
    [InlineData(DownloadStatus.Publishing)]
    [InlineData(DownloadStatus.Reconciling)]
    [InlineData(DownloadStatus.Canceling)]
    public void ActiveWorkDoesNotOfferCompetingLocalReconciliation(DownloadStatus status)
    {
        var item = Item(status, reconcile: true);

        Assert.False(DownloadPresentationPolicy.CanReconcile(item));
        Assert.Empty(DownloadPresentationPolicy.PrimaryLabel(item));
    }

    [Theory]
    [InlineData(DownloadStatus.Completed)]
    [InlineData(DownloadStatus.Canceled)]
    public void ReliableTerminalEvidenceHasNoMutatingControls(DownloadStatus status)
    {
        var item = Item(status, published: status == DownloadStatus.Completed, reconcile: true);

        Assert.False(DownloadPresentationPolicy.CanCancel(item));
        Assert.False(DownloadPresentationPolicy.CanReconcile(item));
        Assert.Empty(DownloadPresentationPolicy.PrimaryLabel(item));
    }

    [Fact]
    public void PendingCancellationIsNotPresentedAsSuccessfulCleanup()
    {
        var item = Item(DownloadStatus.Canceling, intent: TransferIntent.Cancel);

        Assert.Equal("正在取消并清理", DownloadPresentationPolicy.Stage(item));
        Assert.Contains("尚不能认为取消已完成", DownloadPresentationPolicy.Detail(item), StringComparison.Ordinal);
        Assert.False(DownloadPresentationPolicy.CanCancel(item));
    }

    [Fact]
    public void FinishedCancellationReportsOnlyOwnedUnpublishedPartialCleanup()
    {
        var item = Item(DownloadStatus.Canceled, intent: TransferIntent.Cancel);

        Assert.Contains("本任务拥有的未发布临时文件", DownloadPresentationPolicy.Detail(item), StringComparison.Ordinal);
        Assert.DoesNotContain("服务器", DownloadPresentationPolicy.Detail(item), StringComparison.Ordinal);
    }

    [Fact]
    public void ProgressDialogCloseStatementDoesNotPromiseMainWindowExitKeepsDownloading()
    {
        Assert.Contains("关闭此进度对话框不会取消下载", DownloadPresentationPolicy.Detail(Item(DownloadStatus.Downloading)), StringComparison.Ordinal);
    }

    [Fact]
    public void CompletionWinsAgainstAnOlderSubmitResponseOrLateActiveEvent()
    {
        var queued = Item(DownloadStatus.Queued);
        var completed = queued with { Status = DownloadStatus.Completed,
            Snapshot = queued.Snapshot with { Phase = TransferPhase.Completed, LocalPublished = true, Revision = 5 } };

        Assert.Same(completed, DownloadPresentationPolicy.Prefer(completed, queued));
        var late = queued with { Snapshot = queued.Snapshot with { Revision = 5 } };
        Assert.Same(completed, DownloadPresentationPolicy.Prefer(completed, late));
    }

    [Fact]
    public void AllPublicStagesHaveLabelsWithoutResumeOrPausePromises()
    {
        foreach (var status in Enum.GetValues<DownloadStatus>())
        {
            var stage = DownloadPresentationPolicy.Stage(Item(status, published: status == DownloadStatus.Completed));
            Assert.False(string.IsNullOrWhiteSpace(stage));
            Assert.DoesNotContain("恢复", stage, StringComparison.Ordinal);
            Assert.DoesNotContain("暂停", stage, StringComparison.Ordinal);
        }
    }

    private static DownloadItem Item(DownloadStatus status, long? confirmed = 0, long received = 0, long total = 1024,
        bool published = false, bool reconcile = false, TransferIntent intent = TransferIntent.Run)
    {
        var account = new AccountKey(Guid.NewGuid(), Guid.NewGuid(), "fixture");
        var task = new TransferTaskRef(account, Guid.NewGuid());
        var context = new DownloadContext(account, Guid.NewGuid(), Guid.NewGuid());
        var source = new RemoteResourceRef(account, "/fixture.txt", "fixture.txt", "/fixture.txt");
        var snapshot = new DownloadTaskSnapshot(task, intent, TransferPhase.Running, confirmed, total, published,
            TransferUncertainty.None, null, 1, 1, reconcile);
        var progress = new DownloadTaskProgress(new(task, Guid.NewGuid(), 1), received, confirmed, total, TimeSpan.FromSeconds(1));
        return new(context, source, "fixture.txt", "C:\\fixture\\fixture.txt", status, snapshot, progress);
    }
}
