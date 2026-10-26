using System.Collections.Concurrent;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Tests.Services;

public sealed partial class DownloadApplicationServiceTests
{
    [Theory]
    [InlineData("disconnect")]
    [InlineData("logout")]
    [InlineData("connect")]
    [InlineData("dispose")]
    public async Task RetirementFencesBothContextsAndStartsBothDrainsBeforeDelegating(string operation)
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var oldContext = fixture.Context;
        var preview = await fixture.PreviewAsync();
        await fixture.Downloads.SubmitAsync(preview, Token);
        await fixture.Runner.Started.Task.WaitAsync(Timeout, Token);
        fixture.Upload.HoldStop = true;
        fixture.Runner.HoldStop = true;
        using var caller = CancellationTokenSource.CreateLinkedTokenSource(Token);
        Task retiring = operation switch
        {
            "disconnect" => fixture.Bridge.DisconnectAsync(oldContext.Account, caller.Token),
            "logout" => fixture.Bridge.LogoutAsync(oldContext.Account, caller.Token),
            "connect" => fixture.Bridge.ConnectAsync(fixture.Connection.Profile, new(AuthenticationMode.NoAuthentication), caller.Token),
            _ => fixture.Bridge.DisposeAsync().AsTask(),
        };
        await Task.WhenAll(fixture.Upload.StopEntered.Task, fixture.Runner.StopEntered.Task).WaitAsync(Timeout, Token);
        Assert.Null(fixture.Bridge.CurrentContext);
        Assert.Null(fixture.Downloads.CurrentContext);
        Assert.False(retiring.IsCompleted);
        Assert.Equal(["connect"], fixture.Connection.Calls);
        if (operation is "disconnect" or "logout") await caller.CancelAsync();
        fixture.Upload.StopRelease.TrySetResult();
        Assert.False(retiring.IsCompleted);
        Assert.Equal(["connect"], fixture.Connection.Calls);
        fixture.Runner.StopRelease.TrySetResult();
        await retiring.WaitAsync(Timeout, Token);
        Assert.Equal(TransferPhase.Canceled, (await fixture.ReadAsync(new(oldContext.Account, preview.Draft.Id))).Checkpoint.Phase);
        if (operation == "connect")
        {
            Assert.NotEqual(oldContext, fixture.Context);
            Assert.Equal(["connect", "disconnect", "connect"], fixture.Connection.Calls);
            await FailureAsync(ErrorCode.SourceChanged, () => fixture.Downloads.SubmitAsync(preview, Token));
        }
        else if (operation == "dispose")
        {
            Assert.Equal(1, fixture.Upload.Disposals);
            Assert.Equal(1, fixture.Runner.Disposals);
            Assert.Equal(0, fixture.Connection.Disposals);
        }
        else Assert.Equal(["connect", operation], fixture.Connection.Calls);
    }

    [Fact]
    public async Task InvalidationDuringPersistenceLeavesDurableHistoryButCannotStartOldAuthority()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var context = fixture.Context;
        var preview = await fixture.PreviewAsync();
        fixture.Store.BeforeCreate = (_, _) => { fixture.Connection.Invalidate(); return Task.CompletedTask; };
        await FailureAsync(ErrorCode.SourceChanged, () => fixture.Downloads.SubmitAsync(preview, Token));
        Assert.Null(fixture.Downloads.CurrentContext);
        Assert.Null(fixture.Bridge.CurrentContext);
        Assert.Empty(fixture.Runner.Runs);
        var durable = await fixture.ReadAsync(new(context.Account, preview.Draft.Id));
        Assert.Equal(TransferPhase.Queued, durable.Checkpoint.Phase);
        await fixture.ConnectAsync().WaitAsync(Timeout, Token);
        var item = Assert.Single((await fixture.Downloads.ListAsync(fixture.Context, null, 50, Token)).Items);
        Assert.Equal(DownloadStatus.NotStarted, item.Status);
        Assert.Empty(fixture.Runner.Runs);
    }

    [Fact]
    public async Task PendingRunnerInitialReadIsCanceledByFenceEvenWhenRunnerDrainSeesNoRegisteredExecution()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var context = fixture.Context;
        var entered = Signal(); var release = fixture.Gate();
        CancellationToken executionToken = default;
        fixture.Runner.BeforeInitialRead = async (_, cancellationToken) =>
        { executionToken = cancellationToken; entered.TrySetResult(); await release.Task.WaitAsync(Token); };
        await fixture.Downloads.SubmitAsync(await fixture.PreviewAsync(), Token);
        await entered.Task.WaitAsync(Timeout, Token);
        var disconnecting = fixture.Bridge.DisconnectAsync(context.Account, Token);
        await Task.WhenAll(fixture.Upload.StopEntered.Task, fixture.Runner.StopEntered.Task).WaitAsync(Timeout, Token);
        Assert.True(executionToken.IsCancellationRequested);
        Assert.Empty(fixture.Runner.Runs);
        Assert.False(disconnecting.IsCompleted);
        Assert.Equal(["connect"], fixture.Connection.Calls);
        release.TrySetResult();
        await disconnecting.WaitAsync(Timeout, Token);
        Assert.Empty(fixture.Runner.Runs);
        Assert.Equal(["connect", "disconnect"], fixture.Connection.Calls);
    }

    [Fact]
    public async Task UnknownCancelAcknowledgementReadsDurableIntentAndNeverRetriesItOrRestartsDownload()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var item = await fixture.Downloads.SubmitAsync(await fixture.PreviewAsync(), Token);
        await fixture.Runner.Started.Task.WaitAsync(Timeout, Token);
        fixture.Runner.AfterCancelIntent = (_, _) => throw new FilewayException(new(ErrorCode.OutcomeUnknown, "private canceled acknowledgement"));
        try
        {
            await FailureAsync(ErrorCode.OutcomeUnknown, () => fixture.Downloads.CancelAsync(fixture.Context, item.Snapshot.Task, Token));
            var uncertain = Assert.Single((await fixture.Downloads.ListAsync(fixture.Context, null, 50, Token)).Items);
            Assert.Equal(TransferIntent.Cancel, uncertain.Snapshot.Intent);
            Assert.Equal(DownloadStatus.OutcomeUnknown, uncertain.Status);
            Assert.True(uncertain.Snapshot.CanReconcileLocally);
            Assert.Equal(1, fixture.Runner.Cancels);
            Assert.Single(fixture.Runner.Runs);
            Assert.Equal(1, fixture.Sources.Preparations);
        }
        finally { fixture.Runner.AfterCancelIntent = null; }
    }

    [Fact]
    public async Task InvalidationFromRunnerStackFencesImmediatelyButDoesNotAwaitItsOwnCompletion()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var preview = await fixture.PreviewAsync();
        var callbackReturned = Signal(); var release = fixture.Gate(); var admitted = fixture.Gate();
        fixture.Runner.BeforeRun = async (_, cancellationToken) =>
        {
            await admitted.Task.WaitAsync(Token);
            fixture.Connection.Invalidate();
            Assert.Null(fixture.Downloads.CurrentContext);
            Assert.True(cancellationToken.IsCancellationRequested);
            callbackReturned.TrySetResult();
            await release.Task.WaitAsync(Token);
        };
        await fixture.Downloads.SubmitAsync(preview, Token);
        admitted.TrySetResult();
        await callbackReturned.Task.WaitAsync(Timeout, Token);
        await Task.WhenAll(fixture.Upload.StopEntered.Task, fixture.Runner.StopEntered.Task).WaitAsync(Timeout, Token);
        var reconnecting = fixture.ConnectAsync();
        Assert.False(reconnecting.IsCompleted);
        release.TrySetResult();
        await reconnecting.WaitAsync(Timeout, Token);
        Assert.Single(fixture.Runner.Runs);
    }

    [Fact]
    public async Task SynchronousRunnerCompletionIsPublishedAfterQueuedAdmission()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        fixture.Runner.CompleteBeforeReturn = true;
        var completed = new TaskCompletionSource<DownloadItem>(TaskCreationOptions.RunContinuationsAsynchronously);
        fixture.Downloads.TaskChanged += (_, changed) =>
        {
            if (changed.Status == DownloadStatus.Completed) completed.TrySetResult(changed);
        };
        var preview = await fixture.PreviewAsync();
        var admitted = await fixture.Downloads.SubmitAsync(preview, Token);
        var item = await completed.Task.WaitAsync(Timeout, Token);
        Assert.Equal(admitted.Snapshot.Task, item.Snapshot.Task);
        Assert.Equal(DownloadStatus.Completed, item.Status);
        Assert.True(item.Snapshot.LocalPublished);
        Assert.Equal(8, item.Snapshot.ConfirmedOffset);
        Assert.Null(item.Progress);
        Assert.False(item.Snapshot.CanReconcileLocally);
        var duplicate = await fixture.Downloads.SubmitAsync(preview, Token);
        Assert.Equal(item.Snapshot, duplicate.Snapshot);
        Assert.Single(fixture.Runner.Runs);
    }

    [Fact]
    public async Task LocalCancelAndReconcileRemainAvailableAfterDownloadPermissionRemovalWithoutNetworkPreparation()
    {
        await using var fixture = new Fixture();
        var session = await fixture.ConnectAsync();
        var preview = await fixture.PreviewAsync();
        var item = await fixture.Downloads.SubmitAsync(preview, Token);
        await fixture.Runner.Started.Task.WaitAsync(Timeout, Token);
        fixture.Runner.HoldStop = true;
        fixture.Connection.EmitSession(session with { Permissions = session.Permissions with { Download = false } });
        await fixture.Runner.StopEntered.Task.WaitAsync(Timeout, Token);
        await FailureAsync(ErrorCode.PermissionDenied, () => fixture.PreviewAsync());
        await FailureAsync(ErrorCode.PermissionDenied, () => fixture.Downloads.SubmitAsync(preview, Token));
        var canceled = await fixture.Downloads.CancelAsync(fixture.Context, item.Snapshot.Task, Token);
        Assert.Equal(DownloadStatus.Canceled, canceled.Status);
        var reconciled = await fixture.Downloads.ReconcileLocalAsync(fixture.Context, item.Snapshot.Task, Token);
        Assert.Equal(DownloadStatus.Canceled, reconciled.Status);
        Assert.Equal(1, fixture.Runner.Reconciles);
        Assert.Equal(1, fixture.Sources.Preparations);
        Assert.Single(fixture.Runner.Runs);
    }

    [Fact]
    public async Task PermissionWithdrawalCancelsActiveDownloadWithoutRetiringUploadOrSessionAndBlocksReadmissionUntilDrain()
    {
        await using var fixture = new Fixture();
        var session = await fixture.ConnectAsync();
        var context = fixture.Context;
        var uploadContext = fixture.Bridge.CurrentContext;
        var item = await fixture.Downloads.SubmitAsync(await fixture.PreviewAsync(), Token);
        await fixture.Runner.Started.Task.WaitAsync(Timeout, Token);
        fixture.Runner.HoldStop = true;
        fixture.Connection.EmitSession(session with { Permissions = session.Permissions with { Download = false } });
        await fixture.Runner.StopEntered.Task.WaitAsync(Timeout, Token);
        Assert.Equal(context, fixture.Downloads.CurrentContext);
        Assert.Equal(uploadContext, fixture.Bridge.CurrentContext);
        Assert.False(fixture.Upload.StopEntered.Task.IsCompleted);
        Assert.Equal(["connect"], fixture.Connection.Calls);
        Assert.Equal(DownloadStatus.Canceling, Assert.Single((await fixture.Downloads.ListAsync(context, null, 50, Token)).Items).Status);
        fixture.Connection.EmitSession(session);
        var next = await fixture.PreviewAsync();
        await FailureAsync(ErrorCode.Busy, () => fixture.Downloads.SubmitAsync(next, Token));
        Assert.Equal(1, fixture.Store.Creates);
        var canceledEvent = new TaskCompletionSource<DownloadItem>(TaskCreationOptions.RunContinuationsAsynchronously);
        fixture.Downloads.TaskChanged += (_, changed) => { if (changed.Status == DownloadStatus.Canceled) canceledEvent.TrySetResult(changed); };
        fixture.Runner.StopRelease.TrySetResult();
        var canceled = await canceledEvent.Task.WaitAsync(Timeout, Token);
        Assert.Equal(item.Snapshot.Task, canceled.Snapshot.Task);
        Assert.Equal(DownloadStatus.Canceled, canceled.Status);
        Assert.Equal(context, fixture.Downloads.CurrentContext);
        Assert.False(fixture.Upload.StopEntered.Task.IsCompleted);
    }

    [Fact]
    public async Task ReceivedActivityIsRunBoundMonotonicAndNeverConfirmsFlushedOrPublishedBytes()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var preview = await fixture.PreviewAsync();
        var item = await fixture.Downloads.SubmitAsync(preview, Token);
        await fixture.Runner.Started.Task.WaitAsync(Timeout, Token);
        var record = await fixture.ReadAsync(item.Snapshot.Task);
        var run = record.Run!.Value;
        var events = new ConcurrentQueue<DownloadItem>();
        fixture.Downloads.TaskChanged += (_, changed) => { events.Enqueue(changed); throw new InvalidOperationException("private subscriber"); };
        fixture.Runner.Emit(new(run, 7, 0, 8, TimeSpan.FromSeconds(2)));
        var received = Assert.Single((await fixture.Downloads.ListAsync(fixture.Context, null, 50, Token)).Items);
        Assert.Equal(7, received.Progress?.ReceivedBytes);
        Assert.Equal(0, received.Snapshot.ConfirmedOffset);
        Assert.False(received.Snapshot.LocalPublished);
        Assert.Equal(DownloadStatus.Downloading, received.Status);
        fixture.Runner.Emit(new(run, 2, 0, 8, TimeSpan.FromSeconds(1)));
        fixture.Runner.Emit(new(run with { Id = Guid.NewGuid() }, 8, 8, 8, TimeSpan.FromSeconds(3)));
        fixture.Runner.Emit(new(run with { Generation = run.Generation - 1 }, 8, 8, 8, TimeSpan.FromSeconds(3)));
        fixture.Runner.Emit(new(run, 8, 8, 9, TimeSpan.FromSeconds(3)));
        fixture.Runner.Emit(new(run, 9, 0, 8, TimeSpan.FromSeconds(3)));
        var safe = Assert.Single((await fixture.Downloads.ListAsync(fixture.Context, null, 50, Token)).Items);
        Assert.Equal(7, safe.Progress?.ReceivedBytes);
        Assert.Equal(0, safe.Snapshot.ConfirmedOffset);
        fixture.Runner.Emit(new(run, 8, 0, 8, TimeSpan.FromSeconds(4)));
        var fullBody = Assert.Single((await fixture.Downloads.ListAsync(fixture.Context, null, 50, Token)).Items);
        Assert.Equal(8, fullBody.Progress?.ReceivedBytes);
        Assert.Equal(DownloadStatus.Downloading, fullBody.Status);
        Assert.False(fullBody.Snapshot.LocalPublished);
        await fixture.Runner.CompleteAsync(item.Snapshot.Task, Token);
        var completed = Assert.Single((await fixture.Downloads.ListAsync(fixture.Context, null, 50, Token)).Items);
        Assert.Equal(DownloadStatus.Completed, completed.Status);
        Assert.True(completed.Snapshot.LocalPublished);
        Assert.Null(completed.Progress);
        fixture.Runner.Emit(new(run, 1, 0, 8, TimeSpan.FromSeconds(5)));
        Assert.Equal(completed.Snapshot, Assert.Single((await fixture.Downloads.ListAsync(fixture.Context, null, 50, Token)).Items).Snapshot);
        Assert.DoesNotContain(events, changed => changed.Snapshot.LocalPublished && changed.Status != DownloadStatus.Completed);
    }
}
