using System.Collections.Concurrent;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Tests.Services;

public sealed partial class UploadApplicationServiceTests
{
    [Fact]
    public async Task QueueEventsAreProjectedAndRemainBoundToTheirOriginalDestination()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var submitted = await fixture.Service.SubmitAsync(fixture.Submission(), Token);
        var context = fixture.Context;
        var progress = new UploadTaskProgress(new(submitted.Snapshot.Task, Guid.NewGuid(), 1), 9, 4, 12, TimeSpan.FromSeconds(2));
        var signal = new TaskCompletionSource<UploadItem>(TaskCreationOptions.RunContinuationsAsynchronously);
        fixture.Service.TaskChanged += (_, item) =>
        {
            if (item.Snapshot.Phase == TransferPhase.Running) signal.TrySetResult(item);
        };
        var running = submitted.Snapshot with { Phase = TransferPhase.Running, ConfirmedOffset = 4, TotalBytes = 12, Generation = 1 };

        fixture.Coordinator.Emit(new(new(context.Account, context.SessionId, context.ActivationId), running, progress));
        var item = await signal.Task.WaitAsync(GateTimeout, Token);

        Assert.Equal(context, item.Context);
        Assert.Equal(submitted.Parent, item.Parent);
        Assert.Equal(submitted.Name, item.Name);
        Assert.Equal(submitted.DestinationDisplayPath, item.DestinationDisplayPath);
        Assert.Equal(UploadStatus.Uploading, item.Status);
        Assert.Equal(running, item.Snapshot);
        Assert.Equal(progress, item.Progress);
        Assert.DoesNotContain(PrivateLocalPath, item.ToString(), StringComparison.Ordinal);
        AssertNoExecutionProperties();
    }

    [Fact]
    public async Task RetiredAndForeignQueueEventsCannotReachTheNewEpoch()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var oldContext = fixture.Context;
        var task = await fixture.CreateStoredAsync();
        await fixture.ConnectAsync();
        var current = fixture.Context;
        var observed = new ConcurrentQueue<UploadItem>();
        var signal = new TaskCompletionSource<UploadItem>(TaskCreationOptions.RunContinuationsAsynchronously);
        fixture.Service.TaskChanged += (_, item) => { observed.Enqueue(item); signal.TrySetResult(item); };
        var snapshot = Snapshot(task);

        fixture.Coordinator.Emit(new(new(oldContext.Account, oldContext.SessionId, oldContext.ActivationId), snapshot));
        fixture.Coordinator.Emit(new(new(current.Account, current.SessionId, Guid.NewGuid()), snapshot));
        fixture.Coordinator.Emit(new(new(current.Account, current.SessionId, current.ActivationId), snapshot with
            { Task = snapshot.Task with { Account = current.Account with { ServerUserId = "other" } } }));
        fixture.Coordinator.Emit(new(new(current.Account, current.SessionId, current.ActivationId), snapshot));
        var item = await signal.Task.WaitAsync(GateTimeout, Token);

        Assert.Equal(current, item.Context);
        Assert.Single(observed);
        Assert.Equal(task.Plan.Reference, item.Snapshot.Task);
    }

    [Fact]
    public async Task ThrowingObserverDoesNotBlockLaterQueueObserversOrCommands()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var task = await fixture.CreateStoredAsync();
        var context = fixture.Context;
        var signal = new TaskCompletionSource<UploadItem>(TaskCreationOptions.RunContinuationsAsynchronously);
        fixture.Service.TaskChanged += (_, _) => throw new InvalidOperationException("throwing presentation fixture");
        fixture.Service.TaskChanged += (_, item) => signal.TrySetResult(item);

        fixture.Coordinator.Emit(new(new(context.Account, context.SessionId, context.ActivationId), Snapshot(task)));
        var item = await signal.Task.WaitAsync(GateTimeout, Token);
        var paused = await fixture.Service.PauseAsync(context, task.Plan.Reference, Token);

        Assert.Equal(task.Plan.Reference, item.Snapshot.Task);
        Assert.Equal(task.Plan.Reference, paused.Snapshot.Task);
    }

    [Fact]
    public async Task ConnectionEventsAreSafelyForwardedAndUpdatedPermissionAppliesToSubmit()
    {
        await using var fixture = new Fixture();
        var session = await fixture.ConnectAsync();
        var context = fixture.Context;
        var sessions = new ConcurrentQueue<ConnectionSession>();
        var issues = new ConcurrentQueue<ConnectionIssue>();
        fixture.Service.SessionUpdated += (_, _) => throw new InvalidOperationException("throwing connection observer fixture");
        fixture.Service.SessionUpdated += (_, update) => sessions.Enqueue(update);
        fixture.Service.ConnectionIssue += (_, _) => throw new InvalidOperationException("throwing issue observer fixture");
        fixture.Service.ConnectionIssue += (_, issue) => issues.Enqueue(issue);
        var updated = session with { Permissions = session.Permissions with { Create = false } };
        var issue = new ConnectionIssue(context.Account, context.SessionId,
            new(ErrorCode.NetworkUnavailable, "Network temporarily unavailable.", Retryable: true), false);

        fixture.Connection.EmitSession(updated);
        fixture.Connection.EmitIssue(issue);

        Assert.Equal(updated, Assert.Single(sessions));
        Assert.Equal(issue.Account, Assert.Single(issues).Account);
        Assert.Equal(context, fixture.Service.CurrentContext);
        await AssertSafeFailureAsync(ErrorCode.PermissionDenied, () => fixture.Service.SubmitAsync(fixture.Submission(), Token));
        Assert.Empty(fixture.Coordinator.Enqueued);
    }

    [Fact]
    public async Task PrivateCoordinatorErrorMessageIsNeverReturnedOrForwarded()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var task = await fixture.CreateStoredAsync();
        var context = fixture.Context;
        var signal = new TaskCompletionSource<UploadItem>(TaskCreationOptions.RunContinuationsAsynchronously);
        fixture.Service.TaskChanged += (_, item) => signal.TrySetResult(item);
        var raw = Snapshot(task) with
        {
            Phase = TransferPhase.Failed,
            Error = new(ErrorCode.NetworkUnavailable, "Private " + PrivateLocalPath + " https://private.invalid/?capability=secret", Retryable: true),
        };

        fixture.Coordinator.Emit(new(new(context.Account, context.SessionId, context.ActivationId), raw));
        var item = await signal.Task.WaitAsync(GateTimeout, Token);

        Assert.Equal(UploadStatus.Failed, item.Status);
        Assert.NotNull(item.Snapshot.Error);
        Assert.Equal(ErrorCode.NetworkUnavailable, item.Snapshot.Error.Code);
        Assert.DoesNotContain(PrivateLocalPath, item.Snapshot.Error.Message, StringComparison.Ordinal);
        Assert.DoesNotContain("private.invalid", item.Snapshot.Error.Message, StringComparison.Ordinal);
        Assert.DoesNotContain("capability=secret", item.Snapshot.Error.Message, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData(TransferPhase.Queued, UploadStatus.Queued)]
    [InlineData(TransferPhase.Preparing, UploadStatus.Preparing)]
    [InlineData(TransferPhase.Running, UploadStatus.Uploading)]
    [InlineData(TransferPhase.Paused, UploadStatus.Paused)]
    [InlineData(TransferPhase.WaitingNetwork, UploadStatus.WaitingNetwork)]
    [InlineData(TransferPhase.WaitingAuthentication, UploadStatus.WaitingAuthentication)]
    [InlineData(TransferPhase.Conflict, UploadStatus.Conflict)]
    [InlineData(TransferPhase.SourceChanged, UploadStatus.SourceChanged)]
    [InlineData(TransferPhase.Verifying, UploadStatus.Verifying)]
    [InlineData(TransferPhase.Publishing, UploadStatus.Publishing)]
    [InlineData(TransferPhase.Reconciling, UploadStatus.Reconciling)]
    [InlineData(TransferPhase.Completed, UploadStatus.Completed)]
    [InlineData(TransferPhase.CancelRequested, UploadStatus.Canceling)]
    [InlineData(TransferPhase.Canceled, UploadStatus.Canceled)]
    [InlineData(TransferPhase.Failed, UploadStatus.Failed)]
    [InlineData(TransferPhase.OutcomeUnknown, UploadStatus.OutcomeUnknown)]
    public async Task CurrentQueueEvidenceMapsToThePublicStatusWithoutStartingMoreWork(TransferPhase phase, UploadStatus status)
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var submitted = await fixture.Service.SubmitAsync(fixture.Submission(), Token);
        var context = fixture.Context;
        var signal = new TaskCompletionSource<UploadItem>(TaskCreationOptions.RunContinuationsAsynchronously);
        fixture.Service.TaskChanged += (_, item) =>
        {
            if (item.Snapshot.Phase == phase && item.Snapshot.Generation == (phase == TransferPhase.Queued ? 0 : 1)) signal.TrySetResult(item);
        };

        var snapshot = submitted.Snapshot with
        {
            Phase = phase,
            Generation = phase == TransferPhase.Queued ? 0 : 1,
            Revision = phase == TransferPhase.Queued ? 0 : 2,
            ConfirmedOffset = phase == TransferPhase.Queued ? null : phase is TransferPhase.Verifying or TransferPhase.Publishing or TransferPhase.Completed ? 8 : 0,
            TotalBytes = phase == TransferPhase.Queued ? null : 8,
            RemotePublished = phase == TransferPhase.Completed,
            RemoteCanceled = phase == TransferPhase.Canceled,
            Uncertainty = phase == TransferPhase.OutcomeUnknown ? TransferUncertainty.RemoteWrite : TransferUncertainty.None,
            Intent = phase switch
            {
                TransferPhase.CancelRequested or TransferPhase.Canceled => TransferIntent.Cancel,
                TransferPhase.Paused => TransferIntent.Pause,
                _ => TransferIntent.Run,
            },
        };
        fixture.Coordinator.Emit(new(new(context.Account, context.SessionId, context.ActivationId), snapshot));
        var item = await signal.Task.WaitAsync(GateTimeout, Token);

        Assert.Equal(status, item.Status);
        Assert.Equal(phase, item.Snapshot.Phase);
        Assert.Single(fixture.Coordinator.Enqueued);
        Assert.DoesNotContain("resume", fixture.Coordinator.Calls);
    }

    [Theory]
    [InlineData(TransferPhase.Running, false)]
    [InlineData(TransferPhase.Running, true)]
    [InlineData(TransferPhase.Reconciling, false)]
    [InlineData(TransferPhase.Reconciling, true)]
    public async Task ProtectedCurrentExecutionShowsProgressButStoppingOrFailureRetainsUnknownRemoteEvidence(
        TransferPhase activePhase, bool stopWithError)
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var submitted = await fixture.Service.SubmitAsync(fixture.Submission(), Token);
        var context = fixture.Context;
        var executing = new TaskCompletionSource<UploadItem>(TaskCreationOptions.RunContinuationsAsynchronously);
        var stopped = new TaskCompletionSource<UploadItem>(TaskCreationOptions.RunContinuationsAsynchronously);
        fixture.Service.TaskChanged += (_, item) =>
        {
            if (item.Snapshot.Revision == 2) executing.TrySetResult(item);
            if (item.Snapshot.Revision == 3) stopped.TrySetResult(item);
        };
        var active = submitted.Snapshot with
        {
            Phase = activePhase, Generation = 1, Revision = 2, ConfirmedOffset = 0, TotalBytes = 8,
            Uncertainty = TransferUncertainty.RemoteWrite,
        };
        var progress = new UploadTaskProgress(new(submitted.Snapshot.Task, Guid.NewGuid(), 1), 4, 0, 8, TimeSpan.FromSeconds(1));
        var activation = new UploadActivationToken(context.Account, context.SessionId, context.ActivationId);

        fixture.Coordinator.Emit(new(activation, active, progress));
        var running = await executing.Task.WaitAsync(GateTimeout, Token);
        Assert.Equal(activePhase == TransferPhase.Running ? UploadStatus.Uploading : UploadStatus.Reconciling, running.Status);
        Assert.Equal(TransferUncertainty.RemoteWrite, running.Snapshot.Uncertainty);
        Assert.Equal(progress, running.Progress);
        Assert.False(running.Snapshot.RemotePublished);

        var interrupted = active with
        {
            Revision = 3,
            Phase = stopWithError ? activePhase : TransferPhase.OutcomeUnknown,
            Intent = stopWithError ? TransferIntent.Run : TransferIntent.Pause,
            Error = stopWithError ? new(ErrorCode.NetworkUnavailable, "Protected fixture request did not receive an acknowledgement.") : null,
        };
        fixture.Coordinator.Emit(new(activation, interrupted));
        var unknown = await stopped.Task.WaitAsync(GateTimeout, Token);

        Assert.Equal(UploadStatus.OutcomeUnknown, unknown.Status);
        Assert.Equal(TransferUncertainty.RemoteWrite, unknown.Snapshot.Uncertainty);
        Assert.Equal(0, unknown.Snapshot.ConfirmedOffset);
        Assert.False(unknown.Snapshot.RemotePublished);
        Assert.Single(fixture.Coordinator.Enqueued);
        Assert.DoesNotContain("resume", fixture.Coordinator.Calls);
    }

    [Fact]
    public async Task NewGenerationClearsOldProgressAndLateSnapshotsCannotRegressItsFinalEvidence()
    {
        await using var fixture = new Fixture();
        await fixture.ConnectAsync();
        var submitted = await fixture.Service.SubmitAsync(fixture.Submission(), Token);
        var context = fixture.Context;
        var activation = new UploadActivationToken(context.Account, context.SessionId, context.ActivationId);
        var first = new TaskCompletionSource<UploadItem>(TaskCreationOptions.RunContinuationsAsynchronously);
        var replacement = new TaskCompletionSource<UploadItem>(TaskCreationOptions.RunContinuationsAsynchronously);
        var afterOld = new TaskCompletionSource<UploadItem>(TaskCreationOptions.RunContinuationsAsynchronously);
        var completed = new TaskCompletionSource<UploadItem>(TaskCreationOptions.RunContinuationsAsynchronously);
        fixture.Service.TaskChanged += (_, item) =>
        {
            if (item.Snapshot.Generation == 1 && item.Snapshot.Revision == 2) first.TrySetResult(item);
            if (item.Snapshot.Generation == 2 && item.Snapshot.Revision == 3) replacement.TrySetResult(item);
            if (item.Snapshot.Generation == 2 && item.Snapshot.Revision == 4) afterOld.TrySetResult(item);
            if (item.Snapshot.Generation == 2 && item.Snapshot.Revision == 5) completed.TrySetResult(item);
        };
        var firstSnapshot = submitted.Snapshot with
            { Phase = TransferPhase.Running, Generation = 1, Revision = 2, ConfirmedOffset = 0, TotalBytes = 8 };
        var oldProgress = new UploadTaskProgress(new(submitted.Snapshot.Task, Guid.NewGuid(), 1), 4, 0, 8, TimeSpan.FromSeconds(1));
        fixture.Coordinator.Emit(new(activation, firstSnapshot, oldProgress));
        Assert.Equal(oldProgress, (await first.Task.WaitAsync(GateTimeout, Token)).Progress);

        var secondSnapshot = firstSnapshot with { Generation = 2, Revision = 3 };
        fixture.Coordinator.Emit(new(activation, secondSnapshot));
        var newGeneration = await replacement.Task.WaitAsync(GateTimeout, Token);
        Assert.Null(newGeneration.Progress);
        Assert.Equal(2, newGeneration.Snapshot.Generation);

        fixture.Coordinator.Emit(new(activation, firstSnapshot with
            { Phase = TransferPhase.Completed, Revision = 99, ConfirmedOffset = 8, RemotePublished = true }, oldProgress));
        fixture.Coordinator.Emit(new(activation, secondSnapshot with { Revision = 4 }, oldProgress));
        var retained = await afterOld.Task.WaitAsync(GateTimeout, Token);
        Assert.Equal(UploadStatus.Uploading, retained.Status);
        Assert.Equal(2, retained.Snapshot.Generation);
        Assert.False(retained.Snapshot.RemotePublished);
        Assert.Null(retained.Progress);

        var final = secondSnapshot with { Phase = TransferPhase.Completed, Revision = 5, ConfirmedOffset = 8, RemotePublished = true };
        var terminalBodyProgress = new UploadTaskProgress(new(submitted.Snapshot.Task, Guid.NewGuid(), 2), 8, 8, 8, TimeSpan.FromSeconds(2));
        fixture.Coordinator.Emit(new(activation, final, terminalBodyProgress));
        var published = await completed.Task.WaitAsync(GateTimeout, Token);
        Assert.Equal(UploadStatus.Completed, published.Status);
        Assert.Null(published.Progress);

        fixture.Coordinator.Emit(new(activation, secondSnapshot with { Revision = 4 }, terminalBodyProgress));
        var listed = Assert.Single((await fixture.Service.ListAsync(context, null, 50, Token)).Items);
        Assert.Equal(final, listed.Snapshot);
        Assert.Equal(UploadStatus.Completed, listed.Status);
        Assert.Null(listed.Progress);
    }
}
