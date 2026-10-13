using Fileway.App;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Tests.Transfers;

public sealed class TransferCenterPresentationPolicyTests
{
    [Fact]
    public void NewDirectionCanLoadItsFirstPageWithoutClaimingAnyHistoryTotal()
    {
        var state = UploadState(Context());

        Assert.Empty(state.Items);
        Assert.False(state.HasLoaded);
        Assert.False(state.IsLoading);
        Assert.True(state.CanLoadMore);
        Assert.Null(state.NextCursor);
        Assert.Null(state.Error);
    }

    [Fact]
    public void RepeatedLoadClicksDoNotStartCompetingRequests()
    {
        var state = UploadState(Context());

        Assert.True(state.TryBeginLoad(out var generation, out var cursor));
        Assert.Null(cursor);
        Assert.True(state.IsLoading);
        Assert.False(state.CanLoadMore);
        Assert.False(state.TryBeginLoad(out var repeatedGeneration, out _));
        Assert.Equal(generation, repeatedGeneration);
        Assert.Equal(generation, state.Generation);
    }

    [Fact]
    public void EmptyPageWithoutCursorExhaustsOnlyThatDirection()
    {
        var state = UploadState(Context());
        Assert.True(state.TryBeginLoad(out var generation, out _));

        Assert.True(state.CompleteLoad(generation, [], null));

        Assert.Empty(state.Items);
        Assert.True(state.HasLoaded);
        Assert.False(state.IsLoading);
        Assert.False(state.CanLoadMore);
        Assert.False(state.TryBeginLoad(out _, out _));
    }

    [Fact]
    public void EmptyFilteredPageStillFollowsItsOpaqueNextCursor()
    {
        var current = Context();
        var state = UploadState(current);
        Assert.True(state.TryBeginLoad(out var generation, out _));

        Assert.True(state.CompleteLoad(generation, [Upload(Context())], "next"));

        Assert.Empty(state.Items);
        Assert.True(state.HasLoaded);
        Assert.True(state.CanLoadMore);
        Assert.True(state.TryBeginLoad(out _, out var cursor));
        Assert.Equal("next", cursor);
    }

    [Theory]
    [InlineData("account")]
    [InlineData("session")]
    [InlineData("activation")]
    [InlineData("task-account")]
    [InlineData("remote-account")]
    public void PageAndEventRowsMustPassTheExactContextPredicate(string mismatch)
    {
        var context = Context();
        var state = UploadState(context);
        var item = Upload(context);
        item = mismatch switch
        {
            "account" => item with { Context = context with { Account = Context().Account } },
            "session" => item with { Context = context with { SessionId = Guid.NewGuid() } },
            "activation" => item with { Context = context with { ActivationId = Guid.NewGuid() } },
            "task-account" => item with { Snapshot = item.Snapshot with { Task = item.Snapshot.Task with { Account = Context().Account } } },
            _ => item with { Parent = item.Parent with { Account = Context().Account } },
        };

        Assert.False(state.Upsert(item));
        Assert.True(state.TryBeginLoad(out var generation, out _));
        Assert.True(state.CompleteLoad(generation, [item], null));

        Assert.Empty(state.Items);
    }

    [Fact]
    public void CallerCanValidateAgainstItsCurrentActivationAtMergeTime()
    {
        var current = Context();
        var old = Upload(current);
        var state = new TransferCenterPageState<UploadItem>(item => item.Snapshot.Task,
            UploadPresentationPolicy.PreferUploadItem, item => item.Context == current);
        Assert.True(state.TryBeginLoad(out var generation, out _));
        current = current with { ActivationId = Guid.NewGuid() };

        Assert.True(state.CompleteLoad(generation, [old], "next"));
        Assert.False(state.Upsert(old));
        Assert.Empty(state.Items);
        Assert.Equal("next", state.NextCursor);
    }

    [Fact]
    public void DuplicateRowsMergeAcrossPagesWithoutReorderingFirstObservedTasks()
    {
        var context = Context();
        var state = UploadState(context);
        var first = Upload(context);
        var second = Upload(context);
        var third = Upload(context);
        var updated = first with { Name = "updated.txt", Snapshot = first.Snapshot with { Revision = 3 } };
        Assert.True(state.TryBeginLoad(out var firstGeneration, out _));
        Assert.True(state.CompleteLoad(firstGeneration, [first, second], "next"));
        Assert.True(state.TryBeginLoad(out var nextGeneration, out var cursor));
        Assert.Equal("next", cursor);

        Assert.True(state.CompleteLoad(nextGeneration, [second, third, updated, first], null));

        Assert.Equal(new[] { updated, second, third }, state.Items);
        Assert.False(state.CanLoadMore);
    }

    [Fact]
    public void IdentityIncludesTheAccountInsteadOfOnlyTheTaskGuid()
    {
        var context = Context();
        var first = Upload(context);
        var otherContext = Context();
        var other = Upload(otherContext, first.Snapshot.Task.Id);
        var state = new TransferCenterPageState<UploadItem>(item => item.Snapshot.Task,
            UploadPresentationPolicy.PreferUploadItem);

        Assert.True(state.Upsert(first));
        Assert.True(state.Upsert(other));

        Assert.Equal(new[] { first, other }, state.Items);
    }

    [Fact]
    public void LiveEventAndLaterPageReturnDoNotDuplicateOrRegressAnUpload()
    {
        var context = Context();
        var state = UploadState(context);
        var queued = Upload(context);
        var completed = queued with { Status = UploadStatus.Completed,
            Snapshot = queued.Snapshot with { Revision = 7, Phase = TransferPhase.Completed, RemotePublished = true } };
        Assert.True(state.TryBeginLoad(out var generation, out _));
        Assert.True(state.Upsert(completed));

        Assert.True(state.CompleteLoad(generation, [queued], null));

        Assert.Same(completed, Assert.Single(state.Items));
    }

    [Fact]
    public void DuplicatePreferenceUsesGenerationBeforeRevisionAndKeepsTerminalTies()
    {
        var context = Context();
        var state = UploadState(context);
        var completed = Upload(context) with { Status = UploadStatus.Completed };
        var newerRun = completed with { Status = UploadStatus.Uploading,
            Snapshot = completed.Snapshot with { Generation = 2, Revision = 1 } };
        var olderRun = newerRun with { Snapshot = newerRun.Snapshot with { Generation = 1, Revision = 99 } };
        Assert.True(state.Upsert(completed));
        Assert.True(state.Upsert(newerRun));
        Assert.True(state.Upsert(olderRun));
        Assert.Same(newerRun, Assert.Single(state.Items));
        var terminal = newerRun with { Status = UploadStatus.Completed };
        Assert.True(state.Upsert(terminal));

        Assert.True(state.Upsert(newerRun));

        Assert.Same(terminal, Assert.Single(state.Items));
    }

    [Fact]
    public void DownloadRowsUseTheirOwnEffectiveTerminalPreference()
    {
        var context = Context();
        var downloadContext = new DownloadContext(context.Account, context.SessionId, context.ActivationId);
        var state = DownloadState(downloadContext);
        var queued = Download(downloadContext);
        var completed = queued with { Status = DownloadStatus.Completed,
            Snapshot = queued.Snapshot with { LocalPublished = true, Phase = TransferPhase.Completed, Revision = 5 } };
        Assert.True(state.Upsert(completed));
        Assert.True(state.TryBeginLoad(out var generation, out _));

        Assert.True(state.CompleteLoad(generation,
            [queued with { Snapshot = queued.Snapshot with { Revision = 5 } }], null));

        Assert.Same(completed, Assert.Single(state.Items));
    }

    [Fact]
    public void UploadAndDownloadPagingAndErrorsAreIndependent()
    {
        var context = Context();
        var downloadContext = new DownloadContext(context.Account, context.SessionId, context.ActivationId);
        var uploads = UploadState(context);
        var downloads = DownloadState(downloadContext);
        var upload = Upload(context);
        var download = Download(downloadContext, upload.Snapshot.Task.Id);
        Assert.True(uploads.TryBeginLoad(out var uploadGeneration, out _));
        Assert.True(downloads.TryBeginLoad(out var downloadGeneration, out _));
        Assert.True(uploads.CompleteLoad(uploadGeneration, [upload], "upload-next"));
        Assert.True(downloads.Upsert(download));

        Assert.True(downloads.FailLoad(downloadGeneration, "download page unavailable"));

        Assert.Same(upload, Assert.Single(uploads.Items));
        Assert.Same(download, Assert.Single(downloads.Items));
        Assert.Equal("upload-next", uploads.NextCursor);
        Assert.Null(uploads.Error);
        Assert.False(downloads.HasLoaded);
        Assert.Null(downloads.NextCursor);
        Assert.Equal("download page unavailable", downloads.Error);
        downloads.Reset();
        Assert.Same(upload, Assert.Single(uploads.Items));
        Assert.Equal("upload-next", uploads.NextCursor);
    }

    [Fact]
    public void RepeatedCurrentCursorFailsBeforeRowsOrCursorAreCommitted()
    {
        var context = Context();
        var state = UploadState(context);
        var first = Upload(context);
        var rejected = Upload(context);
        Assert.True(state.TryBeginLoad(out var firstGeneration, out _));
        Assert.True(state.CompleteLoad(firstGeneration, [first], "next"));
        Assert.True(state.TryBeginLoad(out var generation, out var cursor));
        Assert.Equal("next", cursor);

        var error = Assert.Throws<FilewayException>(() => state.CompleteLoad(generation, [rejected], "next"));

        Assert.Equal(ErrorCode.ProtocolMismatch, error.Error.Code);
        Assert.Same(first, Assert.Single(state.Items));
        Assert.Equal("next", state.NextCursor);
        Assert.True(state.IsLoading);
        Assert.True(state.FailLoad(generation, error.Error.Message));
        Assert.Equal(error.Error.Message, state.Error);
        Assert.True(state.TryBeginLoad(out _, out var retryCursor));
        Assert.Equal("next", retryCursor);
    }

    [Fact]
    public void APreviouslyReturnedCursorCycleIsRejectedBeforeCommit()
    {
        var context = Context();
        var state = UploadState(context);
        Assert.True(state.TryBeginLoad(out var firstGeneration, out _));
        Assert.True(state.CompleteLoad(firstGeneration, [], "first"));
        Assert.True(state.TryBeginLoad(out var secondGeneration, out _));
        Assert.True(state.CompleteLoad(secondGeneration, [], "second"));
        Assert.True(state.TryBeginLoad(out var thirdGeneration, out _));

        Assert.Throws<FilewayException>(() => state.CompleteLoad(thirdGeneration, [Upload(context)], "first"));

        Assert.Empty(state.Items);
        Assert.Equal("second", state.NextCursor);
    }

    [Fact]
    public void OpaqueCursorsAreComparedOrdinallyWithoutTrimmingOrNormalizing()
    {
        var state = UploadState(Context());
        foreach (var cursor in new[] { "next", "NEXT", " next ", "" })
        {
            Assert.True(state.TryBeginLoad(out var generation, out _));
            Assert.True(state.CompleteLoad(generation, [], cursor));
            Assert.Equal(cursor, state.NextCursor);
            Assert.True(state.CanLoadMore);
        }
    }

    [Fact]
    public void AFailedPagePreservesRowsAndCursorForAnExplicitRetry()
    {
        var context = Context();
        var state = UploadState(context);
        var first = Upload(context);
        Assert.True(state.TryBeginLoad(out var firstGeneration, out _));
        Assert.True(state.CompleteLoad(firstGeneration, [first], "next"));
        Assert.True(state.TryBeginLoad(out var generation, out _));

        Assert.True(state.FailLoad(generation, "page unavailable"));

        Assert.Same(first, Assert.Single(state.Items));
        Assert.True(state.HasLoaded);
        Assert.False(state.IsLoading);
        Assert.True(state.CanLoadMore);
        Assert.Equal("next", state.NextCursor);
        Assert.Equal("page unavailable", state.Error);
        Assert.True(state.TryBeginLoad(out var retryGeneration, out var retryCursor));
        Assert.True(retryGeneration > generation);
        Assert.Equal("next", retryCursor);
        Assert.Null(state.Error);
    }

    [Fact]
    public void AFailedFirstPageDoesNotPretendHistoryIsExhausted()
    {
        var state = UploadState(Context());
        Assert.True(state.TryBeginLoad(out var generation, out _));

        Assert.True(state.FailLoad(generation, "first page unavailable"));

        Assert.False(state.HasLoaded);
        Assert.True(state.CanLoadMore);
        Assert.Null(state.NextCursor);
    }

    [Fact]
    public void ASettledRequestCannotOverwriteOrFailItsNewerRetry()
    {
        var context = Context();
        var state = UploadState(context);
        Assert.True(state.TryBeginLoad(out var failedGeneration, out _));
        Assert.True(state.FailLoad(failedGeneration, "page unavailable"));
        Assert.True(state.TryBeginLoad(out var retryGeneration, out _));

        Assert.False(state.CompleteLoad(failedGeneration, [Upload(context)], "stale"));
        Assert.False(state.FailLoad(failedGeneration, "late failure"));

        Assert.Equal(retryGeneration, state.Generation);
        Assert.True(state.IsLoading);
        Assert.Empty(state.Items);
        Assert.Null(state.NextCursor);
        Assert.Null(state.Error);
    }

    [Fact]
    public void ACompletedRequestCannotApplyTwiceOrBecomeAnError()
    {
        var context = Context();
        var state = UploadState(context);
        var first = Upload(context);
        Assert.True(state.TryBeginLoad(out var generation, out _));
        Assert.True(state.CompleteLoad(generation, [first], "next"));

        Assert.False(state.CompleteLoad(generation, [Upload(context)], "next"));
        Assert.False(state.FailLoad(generation, "late failure"));

        Assert.Same(first, Assert.Single(state.Items));
        Assert.Null(state.Error);
        Assert.Equal("next", state.NextCursor);
    }

    [Fact]
    public void RefreshPreservesLiveRowsButFencesOldSuccessAndFailure()
    {
        var context = Context();
        var state = UploadState(context);
        var live = Upload(context);
        Assert.True(state.Upsert(live));
        Assert.True(state.TryBeginLoad(out var oldGeneration, out _));

        state.BeginRefresh();

        Assert.Same(live, Assert.Single(state.Items));
        Assert.False(state.HasLoaded);
        Assert.False(state.IsLoading);
        Assert.True(state.CanLoadMore);
        Assert.Null(state.NextCursor);
        Assert.False(state.CompleteLoad(oldGeneration, [Upload(context)], "stale"));
        Assert.False(state.FailLoad(oldGeneration, "stale failure"));
        Assert.True(state.TryBeginLoad(out var generation, out var cursor));
        Assert.Null(cursor);
        Assert.True(generation > oldGeneration);
    }

    [Fact]
    public void RefreshStartsANewCursorTraversalAndKeepsNewerLiveEvidence()
    {
        var context = Context();
        var state = UploadState(context);
        var old = Upload(context);
        var updated = old with { Snapshot = old.Snapshot with { Revision = 8 } };
        Assert.True(state.TryBeginLoad(out var firstGeneration, out _));
        Assert.True(state.CompleteLoad(firstGeneration, [old], "next"));
        Assert.True(state.Upsert(updated));
        state.BeginRefresh();
        Assert.True(state.TryBeginLoad(out var generation, out _));

        Assert.True(state.CompleteLoad(generation, [old], "next"));

        Assert.Same(updated, Assert.Single(state.Items));
        Assert.Equal("next", state.NextCursor);
        Assert.True(state.HasLoaded);
    }

    [Fact]
    public void ResetDropsRowsPagingAndErrorsAndRetiresTheOldRequest()
    {
        var context = Context();
        var state = UploadState(context);
        Assert.True(state.Upsert(Upload(context)));
        Assert.True(state.TryBeginLoad(out var generation, out _));
        Assert.True(state.FailLoad(generation, "unavailable"));
        Assert.True(state.TryBeginLoad(out var oldGeneration, out _));

        state.Reset();

        Assert.Empty(state.Items);
        Assert.False(state.HasLoaded);
        Assert.False(state.IsLoading);
        Assert.True(state.CanLoadMore);
        Assert.Null(state.NextCursor);
        Assert.Null(state.Error);
        Assert.False(state.CompleteLoad(oldGeneration, [Upload(context)], "stale"));
        Assert.False(state.FailLoad(oldGeneration, "stale failure"));
    }

    [Fact]
    public void LiveUpsertDoesNotConsumePagesOrClearTheirError()
    {
        var context = Context();
        var state = UploadState(context);
        Assert.True(state.TryBeginLoad(out var generation, out _));
        Assert.True(state.FailLoad(generation, "unavailable"));

        Assert.True(state.Upsert(Upload(context)));

        Assert.Single(state.Items);
        Assert.False(state.HasLoaded);
        Assert.True(state.CanLoadMore);
        Assert.Null(state.NextCursor);
        Assert.Equal("unavailable", state.Error);
    }

    [Fact]
    public void ItemsExposeAStableReadOnlyViewAcrossUpdatesAndReset()
    {
        var context = Context();
        var state = UploadState(context);
        var view = state.Items;
        var item = Upload(context);

        Assert.True(state.Upsert(item));

        Assert.Same(view, state.Items);
        Assert.Same(item, Assert.Single(view));
        Assert.Throws<NotSupportedException>(() => ((IList<UploadItem>)view).Add(Upload(context)));
        state.Reset();
        Assert.Same(view, state.Items);
        Assert.Empty(view);
    }

    [Fact]
    public void AnInvalidPageCannotLeavePartiallyMergedRowsOrAnAdvancedCursor()
    {
        var context = Context();
        var state = UploadState(context);
        var original = Upload(context);
        Assert.True(state.Upsert(original));
        Assert.True(state.TryBeginLoad(out var generation, out _));

        Assert.Throws<InvalidOperationException>(() => state.CompleteLoad(generation, BrokenPage(), "next"));

        Assert.Same(original, Assert.Single(state.Items));
        Assert.Null(state.NextCursor);
        Assert.False(state.HasLoaded);
        Assert.True(state.IsLoading);

        IEnumerable<UploadItem> BrokenPage()
        {
            yield return Upload(context);
            throw new InvalidOperationException("page interrupted");
        }
    }

    private static TransferCenterPageState<UploadItem> UploadState(UploadContext context) => new(
        item => item.Snapshot.Task, UploadPresentationPolicy.PreferUploadItem,
        item => item.Context == context && item.Snapshot.Task.Account == context.Account && item.Parent.Account == context.Account);

    private static TransferCenterPageState<DownloadItem> DownloadState(DownloadContext context) => new(
        item => item.Snapshot.Task, DownloadPresentationPolicy.Prefer,
        item => item.Context == context && item.Snapshot.Task.Account == context.Account && item.Source.Account == context.Account);

    private static UploadContext Context() => new(
        new(Guid.NewGuid(), Guid.NewGuid(), "fixture"), Guid.NewGuid(), Guid.NewGuid());

    private static UploadItem Upload(UploadContext context, Guid? id = null)
    {
        var task = new TransferTaskRef(context.Account, id ?? Guid.NewGuid());
        var parent = new RemoteResourceRef(context.Account, "/fixture", "fixture", "/fixture");
        var snapshot = new Fileway.Core.Integration.UploadTaskSnapshot(task, TransferIntent.Run, TransferPhase.Queued,
            0, 1024, false, false, TransferUncertainty.None, null, 1, 1);
        return new(context, parent, "fixture.txt", "/fixture", UploadStatus.Queued, snapshot);
    }

    private static DownloadItem Download(DownloadContext context, Guid? id = null)
    {
        var task = new TransferTaskRef(context.Account, id ?? Guid.NewGuid());
        var source = new RemoteResourceRef(context.Account, "/fixture.txt", "fixture.txt", "/fixture.txt");
        var snapshot = new DownloadTaskSnapshot(task, TransferIntent.Run, TransferPhase.Queued,
            0, 1024, false, TransferUncertainty.None, null, 1, 1);
        return new(context, source, "fixture.txt", "C:\\fixture\\fixture.txt", DownloadStatus.Queued, snapshot);
    }
}
