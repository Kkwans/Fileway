using Fileway.App.MediaSurfaces;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Tests.Playback;

public sealed class PlaybackDownloadPresentationTests
{
    [Fact]
    public void AbsentDownloadHasNoPresentation()
    {
        Assert.Null(PlaybackDownloadPresentation.Create(null));
    }

    [Fact]
    public void RunningDownloadSeparatesReceptionDurableFlushAndOwnedReadablePrefix()
    {
        var view = Present(State(received: 512, confirmed: 64, readable: 384));

        Assert.Equal("下载中", view.StatusText);
        Assert.Equal("接收 512 B / 1 KiB (50%) · 落盘 64 B · 当前可读前缀 384 B", view.BytesText);
        Assert.Equal(512, view.ReceivedBytes);
        Assert.Equal(64, view.ConfirmedBytes);
        Assert.Equal(384, view.ReadablePrefixBytes);
        Assert.False(view.IsIndeterminate);
        Assert.Equal(50, view.ProgressValue);
        Assert.True(view.ShowProgress);
        Assert.False(view.IsComplete);
        Assert.Contains("不代表可播放时长", view.HelpText, StringComparison.Ordinal);
        Assert.Contains("暂停播放不会暂停下载", view.HelpText, StringComparison.Ordinal);
        Assert.Contains("预加载速度由播放器单独报告", view.HelpText, StringComparison.Ordinal);
    }

    [Fact]
    public void BodyProgressCannotInventDurableConfirmationOrReadablePrefix()
    {
        var state = State(confirmed: null, readable: null);
        state = state with { Item = state.Item with { Progress = state.Item.Progress! with { ConfirmedOffset = 1024 } } };

        var view = Present(state);

        Assert.Equal(512, view.ReceivedBytes);
        Assert.Null(view.ConfirmedBytes);
        Assert.Null(view.ReadablePrefixBytes);
        Assert.Contains("落盘 — · 当前可读前缀 不可用", view.BytesText, StringComparison.Ordinal);
        Assert.Contains("停止或撤销后为不可用", view.HelpText, StringComparison.Ordinal);
    }

    [Fact]
    public void DurableAndReadableBytesDoNotStandInForUnobservedReceivedBytes()
    {
        var state = State(confirmed: 256, readable: 512);
        state = state with { Item = state.Item with { Progress = null } };

        var view = Present(state);

        Assert.Null(view.ReceivedBytes);
        Assert.Equal(256, view.ConfirmedBytes);
        Assert.Equal(512, view.ReadablePrefixBytes);
        Assert.True(view.IsIndeterminate);
        Assert.StartsWith("接收 — / 1 KiB", view.BytesText, StringComparison.Ordinal);
    }

    [Fact]
    public void FullReceivedBodyHonestlyShowsOneHundredPercentWithoutClaimingCompletion()
    {
        var view = Present(State(received: 1024, confirmed: 1024, readable: 1024));

        Assert.Equal("下载中", view.StatusText);
        Assert.False(view.IsComplete);
        Assert.False(view.IsIndeterminate);
        Assert.Equal(100, view.ProgressValue);
        Assert.Contains("(100%)", view.BytesText, StringComparison.Ordinal);
        Assert.Contains("尚未确认校验与保存完成", view.HelpText, StringComparison.Ordinal);
    }

    [Fact]
    public void ReceivedRatioIsNotArtificiallyCappedNearCompletion()
    {
        var view = Present(State(total: 1000, received: 995));

        Assert.Equal(99.5, view.ProgressValue);
        Assert.Contains("(99.5%)", view.BytesText, StringComparison.Ordinal);
        Assert.False(view.IsComplete);
    }

    [Fact]
    public void TextDoesNotRoundAnIncompleteLongSizedReceptionToOneHundredPercent()
    {
        var view = Present(State(total: long.MaxValue, received: long.MaxValue - 1));

        Assert.Contains("(99.9%)", view.BytesText, StringComparison.Ordinal);
        Assert.DoesNotContain("(100%)", view.BytesText, StringComparison.Ordinal);
        Assert.False(view.IsComplete);
    }

    [Theory]
    [InlineData(DownloadStatus.Verifying, TransferPhase.Verifying, "校验中")]
    [InlineData(DownloadStatus.Publishing, TransferPhase.Publishing, "保存中")]
    [InlineData(DownloadStatus.Reconciling, TransferPhase.Reconciling, "核实中")]
    public void FinalizationIsIndeterminateUntilPublicationIsConfirmed(DownloadStatus status, TransferPhase phase, string label)
    {
        var view = Present(State(status: status, phase: phase, received: 1024, confirmed: 1024, readable: 1024));

        Assert.Equal(label, view.StatusText);
        Assert.True(view.IsIndeterminate);
        Assert.True(view.ShowProgress);
        Assert.False(view.IsComplete);
        Assert.Contains("(100%)", view.BytesText, StringComparison.Ordinal);
        Assert.Equal(1024, view.ReceivedBytes);
    }

    [Fact]
    public void ConfirmedPublishedCompletionCanIndependentlyShowReceptionAndCompletion()
    {
        var view = Present(State(status: DownloadStatus.Completed, phase: TransferPhase.Completed,
            received: 1024, confirmed: 1024, readable: 1024, published: true));

        Assert.Equal("已下载并保存", view.StatusText);
        Assert.True(view.IsComplete);
        Assert.False(view.IsIndeterminate);
        Assert.True(view.ShowProgress);
        Assert.Equal(100, view.ProgressValue);
        Assert.Contains("文件已校验并保存", view.HelpText, StringComparison.Ordinal);
    }

    [Fact]
    public void UnavailableCurrentReaderPreservesHistoricalReceptionFlushAndPublicationFacts()
    {
        var view = Present(State(status: DownloadStatus.Completed, phase: TransferPhase.Completed,
            received: 1024, confirmed: 1024, readable: null, published: true));

        Assert.True(view.IsComplete);
        Assert.Equal(1024, view.ReceivedBytes);
        Assert.Equal(1024, view.ConfirmedBytes);
        Assert.Null(view.ReadablePrefixBytes);
        Assert.Contains("当前可读前缀 不可用", view.BytesText, StringComparison.Ordinal);
        Assert.Contains("文件已校验并保存", view.HelpText, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData(false, TransferPhase.Completed, TransferUncertainty.None)]
    [InlineData(true, TransferPhase.Publishing, TransferUncertainty.None)]
    [InlineData(true, TransferPhase.Running, TransferUncertainty.None)]
    [InlineData(true, TransferPhase.Completed, TransferUncertainty.LocalPublish)]
    [InlineData(true, TransferPhase.Completed, TransferUncertainty.RemoteWrite)]
    public void ACompletedLabelAloneIsInsufficient(bool published, TransferPhase phase, TransferUncertainty uncertainty)
    {
        var state = State(status: DownloadStatus.Completed, phase: phase, received: 1024, confirmed: 1024, published: published);
        state = state with { Item = state.Item with { Snapshot = state.Item.Snapshot with { Uncertainty = uncertainty } } };

        var view = Present(state);

        Assert.Equal("保存结果待核实", view.StatusText);
        Assert.False(view.IsComplete);
        Assert.Contains("(100%)", view.BytesText, StringComparison.Ordinal);
        Assert.DoesNotContain("文件已校验并保存", view.HelpText, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData(null)]
    [InlineData(-1L)]
    public void UnknownOrInvalidTotalIsIndeterminateNeverZeroPercent(long? total)
    {
        var state = State();
        state = state with { Item = state.Item with { Snapshot = state.Item.Snapshot with { TotalBytes = total } } };

        var view = Present(state);

        Assert.Null(view.TotalBytes);
        Assert.Null(view.ReceivedBytes);
        Assert.True(view.IsIndeterminate);
        Assert.True(view.ShowProgress);
        Assert.Contains("总量未知", view.BytesText, StringComparison.Ordinal);
        Assert.DoesNotContain('%', view.BytesText);
    }

    [Fact]
    public void EmptyUnpublishedFileStillNeedsVerificationAndPublication()
    {
        var state = State(total: 0, received: 0, confirmed: 0, readable: 0);
        var pending = Present(state);
        var complete = Present(state with { Item = state.Item with
        {
            Status = DownloadStatus.Completed,
            Snapshot = state.Item.Snapshot with { Phase = TransferPhase.Completed, LocalPublished = true },
        } });

        Assert.Equal(0, pending.TotalBytes);
        Assert.True(pending.IsIndeterminate);
        Assert.False(pending.IsComplete);
        Assert.True(complete.IsIndeterminate);
        Assert.False(complete.ShowProgress);
        Assert.DoesNotContain('%', complete.BytesText);
        Assert.True(complete.IsComplete);
    }

    [Fact]
    public void ObservedZeroReceivedBytesWithKnownPositiveTotalIsLegitimateZero()
    {
        var view = Present(State(received: 0, confirmed: 0, readable: 0));

        Assert.Equal(0, view.ReceivedBytes);
        Assert.False(view.IsIndeterminate);
        Assert.Equal(0, view.ProgressValue);
    }

    [Theory]
    [InlineData("task")]
    [InlineData("account")]
    [InlineData("older-generation")]
    [InlineData("newer-generation")]
    [InlineData("total")]
    [InlineData("elapsed")]
    public void MismatchedOrStaleBodyEvidenceIsIgnored(string mismatch)
    {
        var state = State();
        var progress = state.Item.Progress!;
        progress = mismatch switch
        {
            "task" => progress with { Run = progress.Run with { Task = progress.Run.Task with { Id = Guid.NewGuid() } } },
            "account" => progress with { Run = progress.Run with { Task = new(new(Guid.NewGuid(), Guid.NewGuid(), "other"), progress.Run.Task.Id) } },
            "older-generation" => progress with { Run = progress.Run with { Generation = progress.Run.Generation - 1 } },
            "newer-generation" => progress with { Run = progress.Run with { Generation = progress.Run.Generation + 1 } },
            "total" => progress with { TotalBytes = progress.TotalBytes + 1 },
            _ => progress with { Elapsed = TimeSpan.FromTicks(-1) },
        };

        var view = Present(state with { Item = state.Item with { Progress = progress } });

        Assert.Null(view.ReceivedBytes);
        Assert.True(view.IsIndeterminate);
        Assert.Equal(state.Item.Snapshot.ConfirmedOffset, view.ConfirmedBytes);
        Assert.Equal(state.ReadablePrefixBytes, view.ReadablePrefixBytes);
    }

    [Theory]
    [InlineData(-1L)]
    [InlineData(1025L)]
    [InlineData(long.MaxValue)]
    public void InvalidByteEvidenceIsUnknownInsteadOfClampedOrInferred(long bytes)
    {
        var view = Present(State(received: bytes, confirmed: bytes, readable: bytes));

        Assert.Null(view.ReceivedBytes);
        Assert.Null(view.ConfirmedBytes);
        Assert.Null(view.ReadablePrefixBytes);
        Assert.True(view.IsIndeterminate);
        Assert.Equal("接收 — / 1 KiB · 落盘 — · 当前可读前缀 不可用", view.BytesText);
    }

    [Fact]
    public void LongSizedByteCountsDoNotOverflowOrCreateCompletion()
    {
        var view = Present(State(total: long.MaxValue, received: long.MaxValue / 2,
            confirmed: long.MaxValue / 4, readable: long.MaxValue / 3));

        Assert.Equal(long.MaxValue, view.TotalBytes);
        Assert.True(double.IsFinite(view.ProgressValue));
        Assert.InRange(view.ProgressValue, 49.99, 50.01);
        Assert.False(view.IsIndeterminate);
        Assert.False(view.IsComplete);
        Assert.Contains("EiB", view.BytesText, StringComparison.Ordinal);
    }

    [Fact]
    public void FailedDownloadKeepsByteEvidenceWithoutInventingCompletionOrRetry()
    {
        var state = State(status: DownloadStatus.Failed, phase: TransferPhase.Failed, received: 1024);
        var view = Present(state);

        Assert.Equal("下载失败", view.StatusText);
        Assert.Equal(1024, view.ReceivedBytes);
        Assert.False(view.IsComplete);
        Assert.Equal(100, view.ProgressValue);
        Assert.DoesNotContain("重试", view.HelpText, StringComparison.Ordinal);
        Assert.DoesNotContain("已下载并保存", view.HelpText, StringComparison.Ordinal);
    }

    [Fact]
    public void UnknownFailedDownloadDoesNotAnimateAnActiveProgressIndicator()
    {
        var state = State(status: DownloadStatus.Failed, phase: TransferPhase.Failed);
        state = state with { Item = state.Item with { Progress = null, Snapshot = state.Item.Snapshot with { TotalBytes = null } } };

        var view = Present(state);

        Assert.True(view.IsIndeterminate);
        Assert.False(view.ShowProgress);
        Assert.Equal("下载失败", view.StatusText);
    }

    [Fact]
    public void PublishedCompletionDoesNotInventUnobservedReceptionOrLength()
    {
        var state = State(status: DownloadStatus.Completed, phase: TransferPhase.Completed, published: true);
        state = state with { Item = state.Item with { Progress = null, Snapshot = state.Item.Snapshot with { TotalBytes = null } } };

        var view = Present(state);

        Assert.True(view.IsComplete);
        Assert.Equal("已下载并保存", view.StatusText);
        Assert.Null(view.ReceivedBytes);
        Assert.Null(view.TotalBytes);
        Assert.True(view.IsIndeterminate);
        Assert.False(view.ShowProgress);
        Assert.DoesNotContain('%', view.BytesText);
    }

    [Fact]
    public void UncertainCancellationIsNeverPresentedAsConfirmedCleanup()
    {
        var state = State(status: DownloadStatus.Canceled, phase: TransferPhase.Canceled);
        state = state with { Item = state.Item with { Snapshot = state.Item.Snapshot with
        {
            Intent = TransferIntent.Cancel,
            Uncertainty = TransferUncertainty.LocalPublish,
        } } };

        var view = Present(state);

        Assert.Equal("取消结果待核实", view.StatusText);
        Assert.False(view.IsComplete);
        Assert.DoesNotContain("下载已取消", view.HelpText, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData(DownloadStatus.NotStarted, "下载未开始")]
    [InlineData(DownloadStatus.Queued, "等待下载")]
    [InlineData(DownloadStatus.Preparing, "准备下载")]
    [InlineData(DownloadStatus.Canceling, "取消中")]
    [InlineData(DownloadStatus.Stopped, "下载已停止")]
    [InlineData(DownloadStatus.WaitingNetwork, "网络不可用")]
    [InlineData(DownloadStatus.WaitingAuthentication, "需重新登录")]
    [InlineData(DownloadStatus.Conflict, "同名冲突")]
    [InlineData(DownloadStatus.SourceChanged, "源文件已变化")]
    [InlineData(DownloadStatus.OutcomeUnknown, "保存结果待核实")]
    [InlineData(DownloadStatus.Canceled, "下载已取消")]
    public void NonPlaybackStatusesRemainDownloadStatuses(DownloadStatus status, string text)
    {
        var view = Present(State(status: status));

        Assert.Equal(text, view.StatusText);
        Assert.False(view.IsComplete);
    }

    private static PlaybackDownloadView Present(PlaybackDownloadState state) => Assert.IsType<PlaybackDownloadView>(PlaybackDownloadPresentation.Create(state));

    private static PlaybackDownloadState State(DownloadStatus status = DownloadStatus.Downloading,
        TransferPhase phase = TransferPhase.Running, long received = 512, long? confirmed = 128,
        long? readable = 256, long total = 1024, bool published = false)
    {
        var account = new AccountKey(Guid.NewGuid(), Guid.NewGuid(), "playback-download-fixture");
        var task = new TransferTaskRef(account, Guid.NewGuid());
        var context = new DownloadContext(account, Guid.NewGuid(), Guid.NewGuid());
        var source = new RemoteResourceRef(account, "/fixture.mkv", "fixture.mkv", "/fixture.mkv");
        var snapshot = new DownloadTaskSnapshot(task, TransferIntent.Run, phase, confirmed, total, published,
            TransferUncertainty.None, null, 9, 3);
        var progress = new DownloadTaskProgress(new(task, Guid.NewGuid(), snapshot.Generation), received,
            confirmed, total, TimeSpan.FromSeconds(1));
        return new(new(context, source, "fixture.mkv", "C:\\fixture\\fixture.mkv", status, snapshot, progress), readable);
    }
}
