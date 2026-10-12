using Fileway.App;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Tests.Transfers;

public sealed class UploadPresentationPolicyTests
{
    [Fact]
    public void BodyReadsNeverBecomeServerConfirmedProgress()
    {
        var item = Item(UploadStatus.Uploading, confirmed: null, total: 1024, sent: 1024);

        var progress = UploadPresentationPolicy.Progress(item);

        Assert.True(progress.IsIndeterminate);
        Assert.Equal(0, progress.Value);
        Assert.Equal("确认字节尚待核实", progress.ConfirmedText);
        Assert.DoesNotContain("1024", progress.ConfirmedText, StringComparison.Ordinal);
    }

    [Fact]
    public void DeterminateProgressUsesOnlyConfirmedOffset()
    {
        var item = Item(UploadStatus.Uploading, confirmed: 256, total: 1024, sent: 1024);

        var progress = UploadPresentationPolicy.Progress(item);

        Assert.False(progress.IsIndeterminate);
        Assert.Equal(25, progress.Value);
        Assert.Equal("服务器已确认 256 B / 1 KB", progress.ConfirmedText);
    }

    [Fact]
    public void BodyProgressConfirmationCannotOverrideDurableSnapshot()
    {
        var item = Item(UploadStatus.Uploading, confirmed: 256, total: 1024, sent: 1024);
        item = item with { Progress = item.Progress! with { ConfirmedOffset = 1024 } };

        Assert.Equal(25, UploadPresentationPolicy.Progress(item).Value);
        Assert.Contains("256 B", UploadPresentationPolicy.ConfirmedBytesLabel(item), StringComparison.Ordinal);
    }

    [Theory]
    [InlineData(TransferPhase.Preparing)]
    [InlineData(TransferPhase.Running)]
    [InlineData(TransferPhase.Verifying)]
    [InlineData(TransferPhase.Publishing)]
    [InlineData(TransferPhase.Reconciling)]
    public void IdleHistoryProjectionOffersManualResumeForItsRealCheckpoint(TransferPhase phase)
    {
        var item = Item(UploadStatus.NotStarted, phase: phase);

        Assert.Equal(UploadCommand.Resume, UploadPresentationPolicy.PrimaryUploadCommand(item));
        Assert.Equal("恢复上传", UploadPresentationPolicy.PrimaryButtonLabel(item));
        Assert.Equal("尚未开始", UploadPresentationPolicy.UploadItemStatusLabel(item));
        Assert.False(UploadPresentationPolicy.Progress(item).IsIndeterminate);
        Assert.Equal(phase, item.Snapshot.Phase);
    }

    [Theory]
    [InlineData(TransferIntent.Run, "上传结果待核实", "核实结果")]
    [InlineData(TransferIntent.Cancel, "取消结果待核实", "核实取消结果")]
    public void UnknownResultOffersReconciliationWithItsOriginalIntent(TransferIntent intent, string stage, string action)
    {
        var item = Item(UploadStatus.OutcomeUnknown, intent: intent, phase: TransferPhase.OutcomeUnknown);

        Assert.Equal(UploadCommand.Reconcile, UploadPresentationPolicy.PrimaryUploadCommand(item));
        Assert.Equal(stage, UploadPresentationPolicy.UploadItemStatusLabel(item));
        Assert.Equal(action, UploadPresentationPolicy.PrimaryButtonLabel(item));
        Assert.Equal(intent, item.Snapshot.Intent);
        Assert.Equal(UploadPresentationTone.Warning, UploadPresentationPolicy.Tone(item));
    }

    [Fact]
    public void AcceptedCancelIntentDoesNotNeedCreatePermissionToReconcile()
    {
        var item = Item(UploadStatus.OutcomeUnknown, intent: TransferIntent.Cancel);

        Assert.True(UploadPresentationPolicy.CanUsePrimary(item, canCreate: false));
        Assert.Equal(UploadCommand.Reconcile, UploadPresentationPolicy.PrimaryUploadCommand(item));
    }

    [Theory]
    [InlineData(UploadStatus.NotStarted)]
    [InlineData(UploadStatus.Paused)]
    [InlineData(UploadStatus.Failed)]
    [InlineData(UploadStatus.WaitingAuthentication)]
    [InlineData(UploadStatus.WaitingNetwork)]
    [InlineData(UploadStatus.OutcomeUnknown)]
    public void ResumingUploadRequiresCurrentCreatePermission(UploadStatus status)
    {
        var item = Item(status);

        Assert.False(UploadPresentationPolicy.CanUsePrimary(item, canCreate: false));
        Assert.True(UploadPresentationPolicy.CanUsePrimary(item, canCreate: true));
    }

    [Fact]
    public void WaitingNetworkOffersExplicitResumeInsteadOfAnotherPause()
    {
        var item = Item(UploadStatus.WaitingNetwork, phase: TransferPhase.WaitingNetwork);

        Assert.Equal(UploadCommand.Resume, UploadPresentationPolicy.PrimaryUploadCommand(item));
        Assert.Equal("恢复上传", UploadPresentationPolicy.PrimaryButtonLabel(item));
        Assert.False(UploadPresentationPolicy.Progress(item).IsIndeterminate);
    }

    [Fact]
    public void LocalCanceledPhaseDoesNotClaimRemoteDeletion()
    {
        var item = Item(UploadStatus.Canceled, intent: TransferIntent.Cancel, phase: TransferPhase.Canceled,
            confirmed: 256, remoteCanceled: false);

        Assert.Equal("上传已停止，远端未确认清理", UploadPresentationPolicy.UploadItemStatusLabel(item));
        Assert.Equal("上传已停止。远端数据清理尚未确认。", UploadPresentationPolicy.UploadDetailLabel(item));
        Assert.Equal(UploadPresentationTone.Warning, UploadPresentationPolicy.Tone(item));
        Assert.False(UploadPresentationPolicy.CanCancelUpload(item.Status));
    }

    [Fact]
    public void CancelBeforeAnyRemoteWriteDoesNotInventServerAcknowledgement()
    {
        var item = Item(UploadStatus.Canceled, intent: TransferIntent.Cancel, phase: TransferPhase.Canceled,
            confirmed: null, remoteCanceled: false);

        Assert.Equal("上传任务已取消，未发布文件。", UploadPresentationPolicy.UploadDetailLabel(item));
        Assert.DoesNotContain("服务器已确认", UploadPresentationPolicy.UploadDetailLabel(item), StringComparison.Ordinal);
    }

    [Fact]
    public void ServerCancellationIsClaimedOnlyFromConfirmedRemoteEvidence()
    {
        var item = Item(UploadStatus.Canceled, intent: TransferIntent.Cancel, phase: TransferPhase.Canceled,
            confirmed: 256, remoteCanceled: true);

        Assert.Equal("服务器已确认取消。", UploadPresentationPolicy.UploadDetailLabel(item));
        Assert.Equal(UploadPresentationTone.Information, UploadPresentationPolicy.Tone(item));
    }

    [Fact]
    public void PendingCancelRemainsPendingAndCannotBeCanceledAgain()
    {
        var item = Item(UploadStatus.Canceling, intent: TransferIntent.Cancel, phase: TransferPhase.CancelRequested);

        Assert.Equal("正在取消并核实", UploadPresentationPolicy.UploadItemStatusLabel(item));
        Assert.Contains("尚不能认为已取消或已完成", UploadPresentationPolicy.UploadDetailLabel(item), StringComparison.Ordinal);
        Assert.False(UploadPresentationPolicy.CanCancelUpload(item.Status));
        Assert.Null(UploadPresentationPolicy.PrimaryUploadCommand(item));
    }

    [Theory]
    [InlineData(UploadStatus.Completed)]
    [InlineData(UploadStatus.Canceled)]
    [InlineData(UploadStatus.Skipped)]
    public void TerminalItemsHaveNoMutatingControls(UploadStatus status)
    {
        var item = Item(status);

        Assert.Null(UploadPresentationPolicy.PrimaryUploadCommand(item));
        Assert.Empty(UploadPresentationPolicy.PrimaryButtonLabel(item));
        Assert.False(UploadPresentationPolicy.CanUsePrimary(item, canCreate: true));
        Assert.False(UploadPresentationPolicy.CanCancelUpload(status));
        Assert.False(UploadPresentationPolicy.Progress(item).IsIndeterminate);
    }

    [Theory]
    [InlineData(UploadStatus.Conflict)]
    [InlineData(UploadStatus.SourceChanged)]
    public void ConflictsAndChangedSourcesHaveNoBlindResumeOrOverwrite(UploadStatus status)
    {
        var item = Item(status);

        Assert.Null(UploadPresentationPolicy.PrimaryUploadCommand(item));
        Assert.False(UploadPresentationPolicy.CanUsePrimary(item, canCreate: true));
        Assert.Equal(UploadPresentationTone.Warning, UploadPresentationPolicy.Tone(item));
    }

    [Fact]
    public void ZeroLengthCompletionRequiresRemotePublishEvidenceForFullIndicator()
    {
        var item = Item(UploadStatus.Completed, confirmed: 0, total: 0, remotePublished: true);

        Assert.Equal(100, UploadPresentationPolicy.Progress(item).Value);
        Assert.Equal(0, UploadPresentationPolicy.Progress(item with { Snapshot = item.Snapshot with { RemotePublished = false } }).Value);
        Assert.Equal("服务器已确认 0 B / 0 B", UploadPresentationPolicy.Progress(item).ConfirmedText);
    }

    [Fact]
    public void EveryPublicStatusHasAReadableStage()
    {
        foreach (var status in Enum.GetValues<UploadStatus>())
            Assert.False(string.IsNullOrWhiteSpace(UploadPresentationPolicy.UploadStatusLabel(status)));
    }

    [Fact]
    public void CompletedEventRemainsAvailableWhenAnOlderSubmissionReturnArrives()
    {
        var queued = Item(UploadStatus.Queued, phase: TransferPhase.Queued);
        var completed = queued with { Status = UploadStatus.Completed,
            Snapshot = queued.Snapshot with { Phase = TransferPhase.Completed, RemotePublished = true, Revision = 4 } };

        var accepted = UploadPresentationPolicy.PreferUploadItem(completed, queued);

        Assert.Same(completed, accepted);
        Assert.True(accepted.Snapshot.RemotePublished);
        Assert.Equal(UploadStatus.Completed, accepted.Status);
    }

    [Fact]
    public void TerminalPresentationNeverRegressesWithinItsExecutionGeneration()
    {
        var completed = Item(UploadStatus.Completed, phase: TransferPhase.Completed, remotePublished: true);
        var lateRunning = completed with { Status = UploadStatus.Uploading,
            Snapshot = completed.Snapshot with { Phase = TransferPhase.Running, RemotePublished = false } };

        Assert.Same(completed, UploadPresentationPolicy.PreferUploadItem(completed, lateRunning));
    }

    private static UploadItem Item(UploadStatus status, TransferIntent intent = TransferIntent.Run, TransferPhase phase = TransferPhase.Running,
        long? confirmed = null, long? total = 1024, long sent = 0, bool remoteCanceled = false, bool remotePublished = false)
    {
        var account = new AccountKey(Guid.NewGuid(), Guid.NewGuid(), "fixture");
        var task = new TransferTaskRef(account, Guid.NewGuid());
        var context = new UploadContext(account, Guid.NewGuid(), Guid.NewGuid());
        var parent = new RemoteResourceRef(account, "/fixture", "fixture", "/fixture");
        var snapshot = new UploadTaskSnapshot(task, intent, phase, confirmed, total, remotePublished, remoteCanceled,
            TransferUncertainty.None, null, 1, 1);
        var progress = new UploadTaskProgress(new(task, Guid.NewGuid(), 1), sent, confirmed, total ?? 0, TimeSpan.FromSeconds(1));
        return new(context, parent, "fixture.txt", "/fixture", status, snapshot, progress);
    }
}
