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

    [Theory]
    [InlineData("movie.mkv")]
    [InlineData("中文片名.mp4")]
    [InlineData("emoji-😀.jpg")]
    [InlineData("literal%2F+name.txt")]
    [InlineData(".hidden")]
    [InlineData("COM0.txt")]
    [InlineData("COM10.txt")]
    [InlineData("LPT0")]
    [InlineData(" leading.txt")]
    public void SaveAsAcceptsSafeLiteralLocalLeaves(string name)
    {
        Assert.True(DownloadPresentationPolicy.IsSafeLocalName(name));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData(" ")]
    [InlineData(".")]
    [InlineData("..")]
    [InlineData("file.")]
    [InlineData("file ")]
    [InlineData("a/b")]
    [InlineData("a\\b")]
    [InlineData("C:\\file.txt")]
    [InlineData("../file")]
    [InlineData("a<b")]
    [InlineData("a>b")]
    [InlineData("a:b")]
    [InlineData("a\"b")]
    [InlineData("a|b")]
    [InlineData("a?b")]
    [InlineData("a*b")]
    [InlineData("a\0b")]
    [InlineData("a\tb")]
    [InlineData("a\nb")]
    [InlineData("a\u007fb")]
    [InlineData("a\u0085b")]
    [InlineData("CON")]
    [InlineData("con.txt")]
    [InlineData("PRN.log")]
    [InlineData("aux")]
    [InlineData("NUL.backup.tar")]
    [InlineData("CONIN$.txt")]
    [InlineData("conout$")]
    [InlineData("COM1.txt")]
    [InlineData("com9")]
    [InlineData("LPT1")]
    [InlineData("lpt9.txt")]
    [InlineData("COM¹.txt")]
    [InlineData("COM²")]
    [InlineData("COM³")]
    [InlineData("LPT¹")]
    [InlineData("LPT².txt")]
    [InlineData("LPT³")]
    public void SaveAsRejectsUnsafeLocalLeaves(string? name)
    {
        Assert.False(DownloadPresentationPolicy.IsSafeLocalName(name));
    }

    [Fact]
    public void SaveAsLengthUsesUtf16CharactersAndStrictUtf8WithoutRenaming()
    {
        Assert.True(DownloadPresentationPolicy.IsSafeLocalName(new string('中', 255)));
        Assert.False(DownloadPresentationPolicy.IsSafeLocalName(new string('中', 256)));
        Assert.True(DownloadPresentationPolicy.IsSafeLocalName(string.Concat(Enumerable.Repeat("😀", 127)) + "a"));
        Assert.False(DownloadPresentationPolicy.IsSafeLocalName(string.Concat(Enumerable.Repeat("😀", 128))));
        Assert.False(DownloadPresentationPolicy.IsSafeLocalName("broken\ud800.txt"));
        Assert.False(DownloadPresentationPolicy.IsSafeLocalName("broken\udc00.txt"));
    }

    [Fact]
    public void SaveAsPreviewUsesExactTokenNameAndOriginalSourceThenConfirmsExactObjectOnce()
    {
        var state = SaveAs(out var context, out var source, "literal%2F+name.txt");
        Assert.Equal("literal%2F+name.txt", state.Name);
        Assert.Null(state.Preview);
        Assert.False(state.IsPreparing);
        Assert.False(state.IsSubmitted);
        Assert.False(state.IsClosed);
        Assert.True(state.TryBeginPrepare(out var preparation));
        Assert.Equal(state.Name, preparation.Name);
        Assert.True(state.IsPreparing);
        Assert.False(state.TryBeginPrepare(out _));
        var preview = Preview(context, source, preparation.Name);

        Assert.True(state.AcceptPreview(preparation, preview));
        Assert.Same(preview, state.Preview);
        Assert.False(state.AcceptPreview(preparation, preview with { Draft = new(Guid.NewGuid()) }));
        Assert.Same(preview, state.Preview);
        Assert.False(state.TryConfirm(out _));
        state.FinishPrepare(preparation);
        Assert.False(state.IsPreparing);
        Assert.True(state.TryConfirm(out var accepted));
        Assert.Same(preview, accepted);
        Assert.Same(source, accepted.Source);
        Assert.True(state.IsSubmitted);
        Assert.False(state.TryConfirm(out _));
        Assert.False(state.TryBeginPrepare(out _));
        Assert.False(state.EditName("different.txt"));
        Assert.Equal(preparation.Name, state.Name);
    }

    [Fact]
    public void SaveAsEditAfterPreviewRequiresAnotherBackendPreparation()
    {
        var state = SaveAs(out var context, out var source);
        Assert.True(state.TryBeginPrepare(out var original));
        var preview = Preview(context, source, original.Name);
        Assert.True(state.AcceptPreview(original, preview));
        state.FinishPrepare(original);

        Assert.True(state.EditName("edited.mkv"));
        Assert.Null(state.Preview);
        Assert.False(state.TryConfirm(out _));
        Assert.True(state.TryBeginPrepare(out var edited));
        Assert.True(edited.Revision > original.Revision);
        Assert.Equal("edited.mkv", edited.Name);
        var updated = Preview(context, source, edited.Name);
        Assert.True(state.AcceptPreview(edited, updated));
        state.FinishPrepare(edited);
        Assert.True(state.TryConfirm(out var accepted));
        Assert.Same(updated, accepted);
    }

    [Fact]
    public void SaveAsEditDuringPreparationRejectsOldResultAndKeepsPendingExclusive()
    {
        var state = SaveAs(out var context, out var source);
        Assert.True(state.TryBeginPrepare(out var pending));

        Assert.True(state.EditName("new-name.mp4"));
        Assert.True(state.IsPreparing);
        Assert.Equal("fixture.txt", pending.Name);
        Assert.False(state.TryBeginPrepare(out _));
        Assert.False(state.AcceptPreview(pending, Preview(context, source, pending.Name)));
        Assert.Null(state.Preview);
        Assert.False(state.TryConfirm(out _));
        state.FinishPrepare(pending);
        Assert.True(state.TryBeginPrepare(out var newest));
        Assert.Equal("new-name.mp4", newest.Name);
        Assert.True(newest.Revision > pending.Revision);
    }

    [Fact]
    public void SaveAsEditBackToSameNameStillFencesOldPreparation()
    {
        var state = SaveAs(out var context, out var source);
        Assert.True(state.TryBeginPrepare(out var pending));
        state.EditName("temporary.txt");
        state.EditName(pending.Name);

        Assert.False(state.AcceptPreview(pending, Preview(context, source, pending.Name)));
        state.FinishPrepare(pending);
        Assert.True(state.TryBeginPrepare(out var current));
        Assert.Equal(pending.Name, current.Name);
        Assert.True(current.Revision > pending.Revision);
    }

    [Theory]
    [InlineData("account")]
    [InlineData("session")]
    [InlineData("activation")]
    [InlineData("source-account")]
    [InlineData("source-wire")]
    [InlineData("source-wire-case")]
    [InlineData("source-wire-escaping")]
    [InlineData("source-version")]
    [InlineData("local-name")]
    [InlineData("local-name-case")]
    [InlineData("draft")]
    [InlineData("negative-size")]
    [InlineData("destination")]
    [InlineData("null-source")]
    public void SaveAsRejectsMismatchedOrIncompleteBackendPreview(string mismatch)
    {
        var state = SaveAs(out var context, out var source);
        Assert.True(state.TryBeginPrepare(out var preparation));
        var preview = Preview(context, source, preparation.Name);
        preview = mismatch switch
        {
            "account" => preview with { Context = context with { Account = OtherAccount() } },
            "session" => preview with { Context = context with { SessionId = Guid.NewGuid() } },
            "activation" => preview with { Context = context with { ActivationId = Guid.NewGuid() } },
            "source-account" => preview with { Source = source with { Account = OtherAccount() } },
            "source-wire" => preview with { Source = source with { WirePath = "/different.txt" } },
            "source-wire-case" => preview with { Source = source with { WirePath = "/fixture%2B.txt" } },
            "source-wire-escaping" => preview with { Source = source with { WirePath = "/fixture+.txt" } },
            "source-version" => preview with { Source = source with { SourceVersion = "injected version" } },
            "local-name" => preview with { Name = "different.txt" },
            "local-name-case" => preview with { Name = preparation.Name.ToUpperInvariant() },
            "draft" => preview with { Draft = new(Guid.Empty) },
            "negative-size" => preview with { TotalBytes = -1 },
            "destination" => preview with { DestinationDisplayPath = " \t" },
            _ => preview with { Source = null! },
        };

        Assert.False(state.AcceptPreview(preparation, preview));
        state.FinishPrepare(preparation);
        Assert.Null(state.Preview);
        Assert.False(state.TryConfirm(out _));
        Assert.False(state.IsSubmitted);
    }

    [Fact]
    public void SaveAsAcceptsFreshSourceDisplayMetadataAndActualBackendDestination()
    {
        var state = SaveAs(out var context, out var source);
        Assert.True(state.TryBeginPrepare(out var preparation));
        var preview = Preview(context, source, preparation.Name) with
        {
            Source = source with { DisplayName = "fresh remote display", DisplayPath = "/updated display" },
            DestinationDisplayPath = "\\\\server\\effective-share\\actual-local-name.txt",
            TotalBytes = 0,
        };

        Assert.True(state.AcceptPreview(preparation, preview));
        state.FinishPrepare(preparation);
        Assert.True(state.TryConfirm(out var accepted));
        Assert.Same(preview, accepted);
        Assert.Equal(preview.DestinationDisplayPath, accepted.DestinationDisplayPath);
        Assert.Equal(source.WirePath, accepted.Source.WirePath);
    }

    [Theory]
    [InlineData("before-prepare")]
    [InlineData("pending")]
    [InlineData("preview")]
    public void SaveAsCloseNeverSubmitsAndRejectsLatePreviewOrConfirm(string phase)
    {
        var state = SaveAs(out var context, out var source);
        DownloadSaveAsPreparation? pending = null;
        if (phase != "before-prepare")
        {
            Assert.True(state.TryBeginPrepare(out pending));
            if (phase == "preview")
            {
                Assert.True(state.AcceptPreview(pending, Preview(context, source, pending.Name)));
                state.FinishPrepare(pending);
            }
        }

        state.Close();
        state.Close();

        Assert.True(state.IsClosed);
        Assert.False(state.IsSubmitted);
        Assert.False(state.IsPreparing);
        Assert.Null(state.Preview);
        Assert.False(state.TryConfirm(out _));
        Assert.False(state.TryBeginPrepare(out _));
        Assert.False(state.EditName("after-close.txt"));
        if (pending is not null)
        {
            Assert.False(state.AcceptPreview(pending, Preview(context, source, pending.Name)));
            state.FinishPrepare(pending);
        }
        Assert.False(state.IsSubmitted);
    }

    [Fact]
    public void SaveAsStaleFinishCannotReleaseAnotherPreparationAndForeignTokenCannotAccept()
    {
        var state = SaveAs(out var context, out var source);
        Assert.True(state.TryBeginPrepare(out var first));
        state.FinishPrepare(first);
        Assert.True(state.TryBeginPrepare(out var current));
        state.FinishPrepare(first);
        Assert.True(state.IsPreparing);
        var forged = current with { };
        Assert.False(state.AcceptPreview(forged, Preview(context, source, current.Name)));
        state.FinishPrepare(forged);
        Assert.True(state.IsPreparing);
        Assert.False(state.AcceptPreview(first, Preview(context, source, first.Name)));
        Assert.True(state.AcceptPreview(current, Preview(context, source, current.Name)));
        state.FinishPrepare(current);
        Assert.False(state.IsPreparing);
    }

    [Fact]
    public void SaveAsForeignStateTokenAndFinishedTokenCannotAcceptPreview()
    {
        var state = SaveAs(out var context, out var source);
        var foreign = new DownloadSaveAsState(context, source, state.Name);
        Assert.True(state.TryBeginPrepare(out var current));
        Assert.True(foreign.TryBeginPrepare(out var other));
        Assert.Equal(current.Name, other.Name);
        Assert.Equal(current.Revision, other.Revision);

        Assert.False(state.AcceptPreview(other, Preview(context, source, other.Name)));
        state.FinishPrepare(current);
        Assert.False(state.AcceptPreview(current, Preview(context, source, current.Name)));
        Assert.False(state.TryConfirm(out _));
    }

    [Theory]
    [InlineData("CON.txt")]
    [InlineData("../escape.txt")]
    [InlineData("name ")]
    public void SaveAsUnsafeEditsNeverBeginBackendPreparation(string name)
    {
        var state = SaveAs(out _, out _);
        Assert.True(state.EditName(name));
        Assert.Equal(name, state.Name);
        Assert.False(state.TryBeginPrepare(out _));
        Assert.False(state.IsPreparing);
        Assert.False(state.TryConfirm(out _));
    }

    [Fact]
    public void SaveAsSourceAccountMustMatchTheOriginalContext()
    {
        _ = SaveAs(out var context, out var source);
        var state = new DownloadSaveAsState(context, source with { Account = OtherAccount() }, "safe.txt");

        Assert.False(state.TryBeginPrepare(out _));
        Assert.False(state.TryConfirm(out _));
    }

    private static DownloadSaveAsState SaveAs(out DownloadContext context, out RemoteResourceRef source, string name = "fixture.txt")
    {
        var account = OtherAccount();
        context = new(account, Guid.NewGuid(), Guid.NewGuid());
        source = new(account, "/fixture%2b.txt", "remote-original.txt", "/original remote display");
        return new(context, source, name);
    }

    private static AccountKey OtherAccount() => new(Guid.NewGuid(), Guid.NewGuid(), "save-as-fixture");

    private static DownloadPreview Preview(DownloadContext context, RemoteResourceRef source, string name) =>
        new(context, new(Guid.NewGuid()), source, name, "D:\\effective\\" + name, 1024);

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
