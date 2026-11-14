using System.Collections;
using System.Collections.Concurrent;
using System.Reflection;
using System.Text.Json;
using System.Threading.Channels;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;
using Fileway.Infrastructure.Transfers;

namespace Fileway.Infrastructure.Tests.Transfers;

public sealed class UploadCoordinatorTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Fact]
    public async Task ConstructionAndActivationDoNotDiscoverRunOrAutomaticallyResumeStoredTasks()
    {
        await using var fixture = await Fixture.CreateAsync();
        _ = await fixture.CreateTaskAsync(automaticResume: true);
        Assert.Empty(fixture.Runner.Calls);
        Assert.Equal(0, fixture.Store.ListReads);
        Assert.Equal(0, fixture.Store.Reads);
        var next = await fixture.Queue.ActivateAsync(fixture.Session with { Id = Guid.NewGuid() }, Token);
        Assert.NotEqual(fixture.Activation.Id, next.Id);
        Assert.Empty(fixture.Runner.Calls);
        Assert.Equal(0, fixture.Store.ListReads);
        Assert.Equal(0, fixture.Store.Reads);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(4)]
    public async Task ExecutionCapacityRejectsValuesOutsideStoreBound(int capacity)
    {
        await using var fixture = await Fixture.CreateAsync();
        var failure = Assert.Throws<FilewayException>(() => new UploadCoordinator(fixture.Store, fixture.Runner,
            new UploadCoordinatorOptions { MaximumConcurrentUploads = capacity }));
        Assert.Equal(ErrorCode.InvalidRequest, failure.Error.Code);
    }

    [Fact]
    public async Task ExplicitAdmissionIsFifoDeduplicatedAndLimitedToThreeExecutions()
    {
        await using var fixture = await Fixture.CreateAsync();
        var tasks = await fixture.CreateTasksAsync(5);
        foreach (var task in tasks) await fixture.Queue.EnqueueAsync(fixture.Activation, task, Token);
        await fixture.Queue.EnqueueAsync(fixture.Activation, tasks[0], Token);
        var first = await fixture.Runner.NextAsync();
        var second = await fixture.Runner.NextAsync();
        var third = await fixture.Runner.NextAsync();
        Assert.Equal(tasks.Take(3), new[] { first.Task, second.Task, third.Task });
        Assert.Equal(3, fixture.Runner.Calls.Count);
        first.Release.TrySetResult();
        var fourth = await fixture.Runner.NextAsync();
        Assert.Equal(tasks[3], fourth.Task);
        second.Release.TrySetResult();
        var fifth = await fixture.Runner.NextAsync();
        Assert.Equal(tasks[4], fifth.Task);
        Assert.Equal(3, fixture.Runner.MaximumActive);
        await fixture.Queue.StopAndDrainAsync(fixture.Activation, Token);
        Assert.Equal(0, fixture.Runner.Active);
        Assert.Equal(5, fixture.Runner.Calls.Count);
    }

    [Fact]
    public async Task CanonicalTargetOccupancyDoesNotStarveAnUnrelatedPendingTask()
    {
        await using var fixture = await Fixture.CreateAsync(capacity: 2);
        var first = await fixture.CreateTaskAsync("/raw-%FE.bin");
        var same = await fixture.CreateTaskAsync("/raw-%fe.bin");
        var other = await fixture.CreateTaskAsync("/other.bin");
        await fixture.Queue.EnqueueAsync(fixture.Activation, first, Token);
        await fixture.Queue.EnqueueAsync(fixture.Activation, same, Token);
        await fixture.Queue.EnqueueAsync(fixture.Activation, other, Token);
        var a = await fixture.Runner.NextAsync();
        var b = await fixture.Runner.NextAsync();
        Assert.Equal(new[] { first, other }, new[] { a.Task, b.Task });
        a.Release.TrySetResult();
        Assert.Equal(same, (await fixture.Runner.NextAsync()).Task);
        Assert.Equal(2, fixture.Runner.MaximumActive);
    }

    [Fact]
    public async Task PausingPendingTaskPersistsIntentAndExplicitResumeAloneReadmitsIt()
    {
        await using var fixture = await Fixture.CreateAsync(capacity: 1);
        var tasks = await fixture.CreateTasksAsync(2);
        await fixture.Queue.EnqueueAsync(fixture.Activation, tasks[0], Token);
        var running = await fixture.Runner.NextAsync();
        await fixture.Queue.EnqueueAsync(fixture.Activation, tasks[1], Token);
        var paused = await fixture.Queue.PauseAsync(fixture.Activation, tasks[1], Token);
        Assert.Equal(TransferIntent.Pause, paused.Intent);
        Assert.Equal(TransferPhase.Queued, paused.Phase);
        var failure = await Assert.ThrowsAsync<FilewayException>(() => fixture.Queue.EnqueueAsync(fixture.Activation, tasks[1], Token));
        Assert.Equal(ErrorCode.Conflict, failure.Error.Code);
        running.Release.TrySetResult();
        await running.Released.Task.WaitAsync(TimeSpan.FromSeconds(10), Token);
        Assert.Single(fixture.Runner.Calls);
        await fixture.Queue.ResumeAsync(fixture.Activation, tasks[1], Token);
        var resumed = await fixture.Runner.NextAsync();
        Assert.Equal(tasks[1], resumed.Task);
        Assert.False(resumed.Recovery);
    }

    [Fact]
    public async Task PauseCommitsIntentBeforeCancellationAndWaitsForLateActualCompletion()
    {
        await using var fixture = await Fixture.CreateAsync();
        var task = await fixture.CreateTaskAsync();
        fixture.Runner.DeferStop.Add(task);
        await fixture.Queue.EnqueueAsync(fixture.Activation, task, Token);
        var running = await fixture.Runner.NextAsync();
        running.PublishOnRelease = true;
        var pause = fixture.Queue.PauseAsync(fixture.Activation, task, Token);
        await running.StopObserved.Task.WaitAsync(TimeSpan.FromSeconds(10), Token);
        Assert.Equal(TransferIntent.Pause, (await fixture.ReadAsync(task)).Intent);
        Assert.False(pause.IsCompleted);
        running.Release.TrySetResult();
        var result = await pause;
        Assert.Equal(TransferPhase.Completed, result.Phase);
        Assert.True(result.RemotePublished);
        Assert.Equal(0, fixture.Runner.DeleteCalls);
        Assert.Equal(0, fixture.Runner.Active);
    }

    [Fact]
    public async Task LateAcknowledgementRetriesOnlyMetadataAfterIntentRevisionChanges()
    {
        await using var fixture = await Fixture.CreateAsync();
        var task = await fixture.CreateTaskAsync();
        await fixture.Queue.EnqueueAsync(fixture.Activation, task, Token);
        var call = await fixture.Runner.NextAsync();
        int acknowledgementWrites = 0;
        fixture.Store.BeforeCheckpoint = async (run, revision, checkpoint, cancellationToken) =>
        {
            if (checkpoint.Phase != TransferPhase.Completed) return;
            if (Interlocked.Increment(ref acknowledgementWrites) != 1) return;
            // Force the exact teardown/control race: intent wins CAS after the ACK owner read.
            await fixture.Store.Inner.SetTransferIntentAsync(run.Task, revision, TransferIntent.Pause, cancellationToken);
        };
        call.Release.TrySetResult();
        var completed = await fixture.WaitUpdateAsync(item => item.Task.Phase == TransferPhase.Completed);
        Assert.Equal(TransferIntent.Pause, completed.Task.Intent);
        Assert.True(completed.Task.RemotePublished);
        Assert.Equal(2, acknowledgementWrites);
        Assert.Single(fixture.Runner.Calls);
        Assert.Equal(1, fixture.Runner.Attempts);
        Assert.Equal(0, fixture.Runner.DeleteCalls);
    }

    [Fact]
    public async Task CancellationReconciliationSharesNormalExecutionSlotsAndIsDeduplicated()
    {
        await using var fixture = await Fixture.CreateAsync(capacity: 2);
        var tasks = await fixture.CreateTasksAsync(3);
        await fixture.SeedPartialAsync(tasks[2]);
        await fixture.Queue.EnqueueAsync(fixture.Activation, tasks[0], Token);
        await fixture.Queue.EnqueueAsync(fixture.Activation, tasks[1], Token);
        var first = await fixture.Runner.NextAsync();
        _ = await fixture.Runner.NextAsync();
        await fixture.Queue.EnqueueAsync(fixture.Activation, tasks[2], Token);
        var cancel = fixture.Queue.CancelAsync(fixture.Activation, tasks[2], Token);
        var duplicate = fixture.Queue.CancelAsync(fixture.Activation, tasks[2], Token);
        Assert.Equal(TransferIntent.Cancel, (await fixture.ReadAsync(tasks[2])).Intent);
        Assert.False(cancel.IsCompleted);
        Assert.Equal(2, fixture.Runner.Calls.Count);
        first.Release.TrySetResult();
        var cleanup = await fixture.Runner.NextAsync();
        Assert.True(cleanup.Cancel);
        Assert.Equal(tasks[2], cleanup.Task);
        cleanup.Release.TrySetResult();
        Assert.Equal(TransferPhase.Canceled, (await cancel).Phase);
        Assert.Equal(TransferPhase.Canceled, (await duplicate).Phase);
        Assert.Equal(1, fixture.Runner.DeleteCalls);
        Assert.Equal(2, fixture.Runner.MaximumActive);
        Assert.Equal(3, fixture.Runner.Calls.Count);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task ExplicitAdmissionSelectsRecoveryForOldRunOrReleasedUncertainty(bool active)
    {
        await using var fixture = await Fixture.CreateAsync();
        var task = await fixture.CreateTaskAsync();
        await fixture.SeedPartialAsync(task, active, uncertain: true);
        await fixture.Queue.EnqueueAsync(fixture.Activation, task, Token);
        var call = await fixture.Runner.NextAsync();
        Assert.True(call.Recovery);
        Assert.Equal(task, call.Task);
        Assert.Equal(2, (await fixture.ReadAsync(task)).Generation);
    }

    [Fact]
    public async Task StopClosesAdmissionBeforeDrainAndCallerCancellationCannotAbandonLateAck()
    {
        await using var fixture = await Fixture.CreateAsync(capacity: 1);
        var tasks = await fixture.CreateTasksAsync(2);
        fixture.Runner.DeferStop.Add(tasks[0]);
        await fixture.Queue.EnqueueAsync(fixture.Activation, tasks[0], Token);
        var call = await fixture.Runner.NextAsync();
        call.PublishOnRelease = true;
        await fixture.Queue.EnqueueAsync(fixture.Activation, tasks[1], Token);
        using var stopCaller = new CancellationTokenSource();
        var stop = fixture.Queue.StopAndDrainAsync(fixture.Activation, stopCaller.Token);
        await call.StopObserved.Task.WaitAsync(TimeSpan.FromSeconds(10), Token);
        await stopCaller.CancelAsync();
        Assert.False(stop.IsCompleted);
        var staleAdmission = fixture.Queue.EnqueueAsync(fixture.Activation, tasks[1], Token);
        call.Release.TrySetResult();
        await stop;
        var failure = await Assert.ThrowsAsync<FilewayException>(() => staleAdmission);
        Assert.Equal(ErrorCode.SourceChanged, failure.Error.Code);
        Assert.Single(fixture.Runner.Calls);
        Assert.Equal(TransferPhase.Completed, (await fixture.ReadAsync(tasks[0])).Checkpoint.Phase);
        Assert.Equal(TransferIntent.Run, (await fixture.ReadAsync(tasks[1])).Intent);
        Assert.Equal(0, fixture.Runner.DeleteCalls);
    }

    [Fact]
    public async Task StorageFailureDuringStopStillDrainsEveryWriterAndKeepsProtectedEvidence()
    {
        await using var fixture = await Fixture.CreateAsync();
        var tasks = await fixture.CreateTasksAsync(3);
        foreach (var task in tasks) await fixture.Queue.EnqueueAsync(fixture.Activation, task, Token);
        var calls = new[] { await fixture.Runner.NextAsync(), await fixture.Runner.NextAsync(), await fixture.Runner.NextAsync() };
        fixture.Store.BeforeIntent = (_, _, _) => throw new IOException("private-path-and-secret fixture");
        var failure = await Assert.ThrowsAsync<FilewayException>(() => fixture.Queue.StopAndDrainAsync(fixture.Activation, Token));
        Assert.Equal(ErrorCode.LocalStorageUnavailable, failure.Error.Code);
        Assert.DoesNotContain("private-path", failure.ToString(), StringComparison.Ordinal);
        Assert.All(calls, call => Assert.True(call.Released.Task.IsCompleted));
        Assert.Equal(0, fixture.Runner.Active);
        Assert.True(await fixture.Store.Inner.HasProtectedTransfersAsync(fixture.Session.Account, Token));
        Assert.Equal(0, fixture.Runner.DeleteCalls);
        fixture.Store.BeforeIntent = null;
    }

    [Fact]
    public async Task ThrowingStopCallbackDoesNotPreventOtherWritersFromDraining()
    {
        await using var fixture = await Fixture.CreateAsync();
        var tasks = await fixture.CreateTasksAsync(3);
        fixture.Runner.ThrowOnStop.Add(tasks[0]);
        foreach (var task in tasks) await fixture.Queue.EnqueueAsync(fixture.Activation, task, Token);
        var calls = new[] { await fixture.Runner.NextAsync(), await fixture.Runner.NextAsync(), await fixture.Runner.NextAsync() };
        var failure = await Assert.ThrowsAsync<FilewayException>(() => fixture.Queue.StopAndDrainAsync(fixture.Activation, Token));
        Assert.Equal(ErrorCode.Unknown, failure.Error.Code);
        Assert.All(calls, call => Assert.True(call.Released.Task.IsCompleted));
        Assert.Equal(0, fixture.Runner.Active);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task PauseOrCancelDrainsTerminalAckCleanupBeforeReturning(bool cancel)
    {
        await using var fixture = await Fixture.CreateAsync();
        var task = await fixture.CreateTaskAsync();
        fixture.Runner.HoldCleanup.Add(task);
        await fixture.Queue.EnqueueAsync(fixture.Activation, task, Token);
        var call = await fixture.Runner.NextAsync();
        call.Release.TrySetResult();
        await call.TerminalSaved.Task.WaitAsync(TimeSpan.FromSeconds(10), Token);
        var control = cancel ? fixture.Queue.CancelAsync(fixture.Activation, task, Token)
            : fixture.Queue.PauseAsync(fixture.Activation, task, Token);
        await call.StopObserved.Task.WaitAsync(TimeSpan.FromSeconds(10), Token);
        Assert.False(control.IsCompleted);
        Assert.Equal(TransferIntent.Run, (await fixture.ReadAsync(task)).Intent);
        call.CleanupRelease.TrySetResult();
        Assert.Equal(TransferPhase.Completed, (await control).Phase);
        Assert.Equal(0, fixture.Runner.Active);
        Assert.Equal(0, fixture.Runner.DeleteCalls);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ThrowingPauseOrCancelCallbackStillWaitsForHandleRelease(bool cancel)
    {
        await using var fixture = await Fixture.CreateAsync();
        var task = await fixture.CreateTaskAsync();
        fixture.Runner.DeferStop.Add(task);
        fixture.Runner.ThrowOnStop.Add(task);
        await fixture.Queue.EnqueueAsync(fixture.Activation, task, Token);
        var call = await fixture.Runner.NextAsync();
        var control = cancel ? fixture.Queue.CancelAsync(fixture.Activation, task, Token)
            : fixture.Queue.PauseAsync(fixture.Activation, task, Token);
        await call.StopObserved.Task.WaitAsync(TimeSpan.FromSeconds(10), Token);
        await Assert.ThrowsAsync<TimeoutException>(() => control.WaitAsync(TimeSpan.FromMilliseconds(100), Token));
        call.Release.TrySetResult();
        var failure = await Assert.ThrowsAsync<FilewayException>(() => control);
        Assert.Equal(ErrorCode.Unknown, failure.Error.Code);
        Assert.True(call.Released.Task.IsCompleted);
        Assert.Equal(0, fixture.Runner.DeleteCalls);
    }

    [Fact]
    public async Task EveryCommandRejectsStaleActivationAndOtherAccountBeforeStorageOrRunnerAccess()
    {
        await using var fixture = await Fixture.CreateAsync();
        var task = await fixture.CreateTaskAsync();
        var old = fixture.Activation;
        fixture.Activation = await fixture.Queue.ActivateAsync(fixture.Session with { Id = Guid.NewGuid() }, Token);
        int reads = fixture.Store.Reads;
        foreach (var command in Commands(fixture.Queue, old, task))
        {
            var failure = await Assert.ThrowsAsync<FilewayException>(command);
            Assert.Equal(ErrorCode.SourceChanged, failure.Error.Code);
        }
        var stop = await Assert.ThrowsAsync<FilewayException>(() => fixture.Queue.StopAndDrainAsync(old, Token));
        Assert.Equal(ErrorCode.SourceChanged, stop.Error.Code);
        var wrong = task with { Account = task.Account with { ServerUserId = "another-user" } };
        foreach (var command in Commands(fixture.Queue, fixture.Activation, wrong))
        {
            var failure = await Assert.ThrowsAsync<FilewayException>(command);
            Assert.Equal(ErrorCode.InvalidRequest, failure.Error.Code);
        }
        Assert.Equal(reads, fixture.Store.Reads);
        Assert.Empty(fixture.Runner.Calls);
    }

    [Fact]
    public async Task SwitchingActivationWaitsForOldReleaseAndDoesNotCarryItsPendingTasks()
    {
        await using var fixture = await Fixture.CreateAsync(capacity: 1);
        var tasks = await fixture.CreateTasksAsync(2);
        fixture.Runner.DeferStop.Add(tasks[0]);
        await fixture.Queue.EnqueueAsync(fixture.Activation, tasks[0], Token);
        var call = await fixture.Runner.NextAsync();
        await fixture.Queue.EnqueueAsync(fixture.Activation, tasks[1], Token);
        var switchSession = fixture.Queue.ActivateAsync(fixture.Session with { Id = Guid.NewGuid() }, Token);
        await call.StopObserved.Task.WaitAsync(TimeSpan.FromSeconds(10), Token);
        Assert.False(switchSession.IsCompleted);
        call.Release.TrySetResult();
        fixture.Activation = await switchSession;
        Assert.Single(fixture.Runner.Calls);
        await fixture.Queue.EnqueueAsync(fixture.Activation, tasks[1], Token);
        Assert.Equal(tasks[1], (await fixture.Runner.NextAsync()).Task);
    }

    [Fact]
    public async Task CancelIntentIsOneWayAndStopNeverPerformsRemoteCleanup()
    {
        await using var fixture = await Fixture.CreateAsync();
        var task = await fixture.CreateTaskAsync();
        await fixture.SeedPartialAsync(task);
        var cancel = fixture.Queue.CancelAsync(fixture.Activation, task, Token);
        var cleanup = await fixture.Runner.NextAsync();
        Assert.True(cleanup.Cancel);
        await fixture.Queue.StopAndDrainAsync(fixture.Activation, Token);
        Assert.Equal(TransferIntent.Cancel, (await cancel).Intent);
        Assert.Equal(0, fixture.Runner.DeleteCalls);
        fixture.Activation = await fixture.Queue.ActivateAsync(fixture.Session, Token);
        var failure = await Assert.ThrowsAsync<FilewayException>(() => fixture.Queue.ResumeAsync(fixture.Activation, task, Token));
        Assert.Equal(ErrorCode.Conflict, failure.Error.Code);
    }

    [Fact]
    public async Task BusyOrConflictAdmissionReportsSafeErrorWithoutLoopingOrReplaying()
    {
        await using var fixture = await Fixture.CreateAsync();
        var task = await fixture.CreateTaskAsync();
        fixture.Runner.BeforeRun = _ => throw new FilewayException(new(ErrorCode.Busy, "private capability URL fixture"));
        await fixture.Queue.EnqueueAsync(fixture.Activation, task, Token);
        var update = await fixture.WaitUpdateAsync(item => item.Task.Error?.Code == ErrorCode.Busy);
        Assert.Equal(TransferPhase.Queued, update.Task.Phase);
        Assert.DoesNotContain("private capability", JsonSerializer.Serialize(update), StringComparison.Ordinal);
        Assert.Equal(1, fixture.Runner.Attempts);
        Assert.Empty(fixture.Runner.Calls);
        Assert.Equal(0, (await fixture.ReadAsync(task)).Generation);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task UnreadableStoreAfterRunnerFailurePreservesNewestObservedEvidenceAndReportsError(bool terminal)
    {
        await using var fixture = await Fixture.CreateAsync();
        var task = await fixture.CreateTaskAsync();
        if (terminal) fixture.Runner.HoldCleanup.Add(task);
        await fixture.Queue.EnqueueAsync(fixture.Activation, task, Token);
        var call = await fixture.Runner.NextAsync();
        UploadQueueUpdate known;
        if (terminal)
        {
            call.Release.TrySetResult();
            await call.TerminalSaved.Task.WaitAsync(TimeSpan.FromSeconds(10), Token);
            await fixture.Queue.EnqueueAsync(fixture.Activation, task, Token);
            known = await fixture.WaitUpdateAsync(item => item.Task.Phase == TransferPhase.Completed);
            call.FailCleanup = true;
        }
        else
        {
            var record = await fixture.ReadAsync(task);
            fixture.Runner.Emit(new(record.Run!.Value, 2, 0, 4, TimeSpan.FromSeconds(1)));
            known = await fixture.WaitUpdateAsync(item => item.Progress is not null);
        }
        fixture.Store.AfterRead = (_, _, _) => throw new IOException("private source path and capability fixture");
        try
        {
            if (terminal) call.CleanupRelease.TrySetResult();
            else call.Release.TrySetResult();
            var failure = await fixture.WaitUpdateAsync(item => item.Task.Error?.Code == ErrorCode.LocalStorageUnavailable);
            Assert.Equal(known.Task.Revision, failure.Task.Revision);
            Assert.Equal(known.Task.Generation, failure.Task.Generation);
            Assert.Equal(known.Task.Phase, failure.Task.Phase);
            Assert.Equal(terminal, failure.Task.RemotePublished);
            Assert.Null(failure.Progress);
            Assert.DoesNotContain("private source", JsonSerializer.Serialize(failure), StringComparison.Ordinal);
            Assert.Equal(0, fixture.Runner.Active);
            Assert.Equal(!terminal, await fixture.Store.Inner.HasProtectedTransfersAsync(fixture.Session.Account, Token));
        }
        finally { fixture.Store.AfterRead = null; }
    }

    [Fact]
    public async Task SafeProgressRejectsOldRunAndSubscriberExceptionsDoNotFailExecution()
    {
        await using var fixture = await Fixture.CreateAsync();
        var task = await fixture.CreateTaskAsync();
        fixture.Queue.TaskChanged += (_, _) => throw new InvalidOperationException("subscriber fixture");
        await fixture.Queue.EnqueueAsync(fixture.Activation, task, Token);
        var call = await fixture.Runner.NextAsync();
        var record = await fixture.ReadAsync(task);
        fixture.Runner.Emit(new(record.Run!.Value with { Id = Guid.NewGuid() }, 99, 0, 4, TimeSpan.FromSeconds(1)));
        fixture.Runner.Emit(new(record.Run.Value, 2, 0, 4, TimeSpan.FromSeconds(2)));
        var update = await fixture.WaitUpdateAsync(item => item.Progress is { SentBytes: 2 });
        Assert.Equal(record.Run, update.Progress!.Run);
        var serialized = JsonSerializer.Serialize(update);
        Assert.DoesNotContain("synthetic-private-source", serialized, StringComparison.Ordinal);
        Assert.DoesNotContain("private-wire", serialized, StringComparison.Ordinal);
        Assert.DoesNotContain("source-identity", serialized, StringComparison.Ordinal);
        call.Release.TrySetResult();
        var completed = await fixture.WaitUpdateAsync(item => item.Task.Phase == TransferPhase.Completed);
        Assert.Null(completed.Progress);
        Assert.True(completed.Task.RemotePublished);
    }

    [Fact]
    public async Task DelayedProgressReadCannotRegressAlreadyPublishedCompletion()
    {
        await using var fixture = await Fixture.CreateAsync();
        var task = await fixture.CreateTaskAsync();
        await fixture.Queue.EnqueueAsync(fixture.Activation, task, Token);
        var call = await fixture.Runner.NextAsync();
        var running = await fixture.ReadAsync(task);
        var entered = fixture.CreateGate();
        var release = fixture.CreateGate();
        var terminalEntered = fixture.CreateGate();
        using var releasePublisher = new ManualResetEventSlim();
        fixture.Queue.TaskChanged += (_, update) =>
        {
            if (update.Task.Task != task || update.Task.Phase != TransferPhase.Completed) return;
            terminalEntered.TrySetResult();
            _ = releasePublisher.Wait(TimeSpan.FromSeconds(10), Token);
        };
        fixture.Store.AfterRead = async (_, record, _) =>
        {
            if (record?.Run != running.Run || Interlocked.CompareExchange(ref fixture.ProgressReadDelayed, 1, 0) != 0) return record;
            entered.TrySetResult();
            await release.Task;
            return record;
        };
        // Invoke the same metadata-read path with its returned Task so the test can await its exact
        // continuation. The public event intentionally has no completion task for presentation work.
        var progressRead = StartProgressRead(fixture.Queue, task, new(running.Run!.Value, 2, 0, 4, TimeSpan.FromSeconds(1)));
        try
        {
            await entered.Task.WaitAsync(TimeSpan.FromSeconds(10), Token);
            call.Release.TrySetResult();
            await terminalEntered.Task.WaitAsync(TimeSpan.FromSeconds(10), Token);
            release.TrySetResult();
            await progressRead.WaitAsync(TimeSpan.FromSeconds(10), Token);
            var marker = await fixture.CreateTaskAsync();
            await fixture.Queue.EnqueueAsync(fixture.Activation, marker, Token);
            // The single publisher is held at the terminal callback. A faulty delayed read would
            // enqueue its old row ahead of this different task's marker; it cannot be overwritten
            // by another terminal update for the original task.
            releasePublisher.Set();
            _ = await fixture.WaitUpdateAsync(item => item.Task.Task == marker);
            Assert.DoesNotContain(fixture.ObservedUpdates, item => item.Progress?.Run == running.Run);
        }
        finally { release.TrySetResult(); releasePublisher.Set(); }
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ControlDuringDispatchedMetadataReadCannotStartNormalUpload(bool cancel)
    {
        await using var fixture = await Fixture.CreateAsync();
        var task = await fixture.CreateTaskAsync();
        var entered = fixture.CreateGate();
        var readCanceled = fixture.CreateGate();
        var release = fixture.CreateGate();
        var returned = fixture.CreateGate();
        int reads = 0;
        fixture.Store.AfterRead = async (reference, record, cancellationToken) =>
        {
            // The admission read is first. Hold the dispatched Work's read before any runner call.
            if (reference != task || Interlocked.Increment(ref reads) != 2) return record;
            using var registration = cancellationToken.Register(() => readCanceled.TrySetResult());
            entered.TrySetResult();
            await release.Task;
            returned.TrySetResult();
            return record;
        };
        await fixture.Queue.EnqueueAsync(fixture.Activation, task, Token);
        try
        {
            await entered.Task.WaitAsync(TimeSpan.FromSeconds(10), Token);
            var control = cancel ? fixture.Queue.CancelAsync(fixture.Activation, task, Token)
                : fixture.Queue.PauseAsync(fixture.Activation, task, Token);
            await readCanceled.Task.WaitAsync(TimeSpan.FromSeconds(10), Token);
            Assert.Equal(cancel ? TransferIntent.Cancel : TransferIntent.Pause, (await fixture.ReadAsync(task)).Intent);
            if (cancel)
            {
                var cleanup = await fixture.Runner.NextAsync();
                Assert.True(cleanup.Cancel);
                cleanup.Release.TrySetResult();
            }
            _ = await control.WaitAsync(TimeSpan.FromSeconds(10), Token);
            release.TrySetResult();
            await returned.Task.WaitAsync(TimeSpan.FromSeconds(10), Token);
            Assert.DoesNotContain(fixture.Runner.Calls, item => !item.Cancel);
            if (!cancel) Assert.Equal(0, (await fixture.ReadAsync(task)).Generation);
        }
        finally { release.TrySetResult(); }
    }

    [Fact]
    public async Task DisposingTwiceDrainsOnceAndRejectsFurtherCommands()
    {
        var fixture = await Fixture.CreateAsync();
        try
        {
            var task = await fixture.CreateTaskAsync();
            await fixture.Queue.EnqueueAsync(fixture.Activation, task, Token);
            _ = await fixture.Runner.NextAsync();
            var first = fixture.Queue.DisposeAsync().AsTask();
            var second = fixture.Queue.DisposeAsync().AsTask();
            Assert.Same(first, second);
            await first;
            Assert.Equal(1, fixture.Runner.Disposals);
            Assert.Equal(0, fixture.Runner.Active);
            await Assert.ThrowsAsync<ObjectDisposedException>(() => fixture.Queue.EnqueueAsync(fixture.Activation, task, Token));
        }
        finally { await fixture.DisposeAsync(); }
    }

    private static IEnumerable<Func<Task>> Commands(UploadCoordinator queue, UploadActivationToken activation, TransferTaskRef task)
    {
        yield return async () => { _ = await queue.EnqueueAsync(activation, task, Token); };
        yield return async () => { _ = await queue.ResumeAsync(activation, task, Token); };
        yield return async () => { _ = await queue.PauseAsync(activation, task, Token); };
        yield return async () => { _ = await queue.CancelAsync(activation, task, Token); };
    }

    private static TaskCompletionSource NewSignal() => new(TaskCreationOptions.RunContinuationsAsynchronously);

    private static Task StartProgressRead(UploadCoordinator queue, TransferTaskRef task, UploadTaskProgress progress)
    {
        const BindingFlags flags = BindingFlags.Instance | BindingFlags.NonPublic;
        var table = Assert.IsAssignableFrom<IDictionary>(typeof(UploadCoordinator).GetField("_work", flags)!.GetValue(queue));
        var work = table[task] ?? throw new InvalidOperationException("Expected an active queue work item.");
        work.GetType().GetProperty("PendingProgress", flags)!.SetValue(work, progress);
        work.GetType().GetProperty("ReadingProgress", flags)!.SetValue(work, true);
        return Assert.IsAssignableFrom<Task>(typeof(UploadCoordinator).GetMethod("ReadProgressAsync", flags)!.Invoke(queue, [work]));
    }

    private sealed class Fixture : IAsyncDisposable
    {
        private readonly Channel<UploadQueueUpdate> _updates = Channel.CreateUnbounded<UploadQueueUpdate>();
        private readonly List<TaskCompletionSource> _cleanupGates = [];
        internal ObservedStore Store { get; }
        internal FakeRunner Runner { get; }
        internal UploadCoordinator Queue { get; }
        internal ConnectionSession Session { get; }
        internal UploadActivationToken Activation { get; set; }
        internal ConcurrentQueue<UploadQueueUpdate> ObservedUpdates { get; } = new();
        internal int ProgressReadDelayed;

        internal TaskCompletionSource CreateGate()
        {
            var gate = NewSignal();
            _cleanupGates.Add(gate);
            return gate;
        }

        private Fixture(ObservedStore store, ConnectionSession session, int capacity)
        {
            Store = store;
            Session = session;
            Runner = new(store);
            Queue = new(store, Runner, new UploadCoordinatorOptions { MaximumConcurrentUploads = capacity });
            Queue.TaskChanged += (_, update) => { ObservedUpdates.Enqueue(update); _updates.Writer.TryWrite(update); };
        }

        internal static async Task<Fixture> CreateAsync(int capacity = 3)
        {
            var store = new ObservedStore(new InMemoryProfileStore());
            var saved = new SavedConnection(new(Guid.NewGuid(), Guid.NewGuid(), "queue fixture", new("https://fixture.invalid/"),
                NetworkMode.SystemNetwork, new(ProxyMode.Direct)), AuthenticationMode.Password, "fixture", "7", false, DateTimeOffset.UtcNow);
            await store.Inner.SaveAsync(saved, Token);
            var session = new ConnectionSession(Guid.NewGuid(), saved.Account, "fixture", new(true, true, true, true, true, true), new());
            var fixture = new Fixture(store, session, capacity);
            fixture.Activation = await fixture.Queue.ActivateAsync(session, Token);
            return fixture;
        }

        internal async Task<TransferTaskRef> CreateTaskAsync(string? wire = null, bool automaticResume = false,
            TransferDirection direction = TransferDirection.Upload)
        {
            var task = new TransferTaskRef(Session.Account, Guid.NewGuid());
            await Store.Inner.CreateTransferAsync(new(task, direction,
                new(Session.Account, wire ?? "/private-wire-" + task.Id.ToString("N") + ".bin", "fixture.bin", "/fixture.bin"),
                @"C:\synthetic-private-source\" + task.Id.ToString("N") + ".bin", AutomaticResume: automaticResume), Token);
            return task;
        }

        internal async Task<TransferTaskRef[]> CreateTasksAsync(int count)
        {
            var tasks = new TransferTaskRef[count];
            for (int index = 0; index < count; index++) tasks[index] = await CreateTaskAsync();
            return tasks;
        }

        internal async Task<TransferTaskRecord> ReadAsync(TransferTaskRef task) =>
            Assert.IsType<TransferTaskRecord>(await Store.Inner.GetTransferAsync(task, Token));

        internal async Task SeedPartialAsync(TransferTaskRef task, bool active = false, bool uncertain = false)
        {
            var record = await ReadAsync(task);
            record = await Store.Inner.ClaimTransferAsync(task, record.Revision, Guid.NewGuid(), false, Token);
            var checkpoint = new TransferCheckpoint(active ? TransferPhase.Running : uncertain ? TransferPhase.OutcomeUnknown : TransferPhase.Paused,
                FakeRunner.Source, 2, Uncertainty: uncertain ? TransferUncertainty.RemoteWrite : TransferUncertainty.None);
            await Store.Inner.SaveTransferCheckpointAsync(record.Run!.Value, record.Revision, checkpoint, !active, Token);
        }

        internal async Task<UploadQueueUpdate> WaitUpdateAsync(Func<UploadQueueUpdate, bool> predicate)
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(Token);
            timeout.CancelAfter(TimeSpan.FromSeconds(10));
            while (true)
            {
                var update = await _updates.Reader.ReadAsync(timeout.Token);
                if (predicate(update)) return update;
            }
        }

        public ValueTask DisposeAsync()
        {
            foreach (var gate in _cleanupGates) gate.TrySetResult();
            foreach (var call in Runner.Calls) { call.Release.TrySetResult(); call.CleanupRelease.TrySetResult(); }
            return Queue.DisposeAsync();
        }
    }

    private sealed class ObservedStore(InMemoryProfileStore inner) : ITransferTaskStore
    {
        internal InMemoryProfileStore Inner { get; } = inner;
        internal Func<TransferTaskRef, TransferTaskRecord?, CancellationToken, Task<TransferTaskRecord?>>? AfterRead { get; set; }
        internal Func<TransferTaskRef, TransferIntent, CancellationToken, Task>? BeforeIntent { get; set; }
        internal Func<TransferRunToken, long, TransferCheckpoint, CancellationToken, Task>? BeforeCheckpoint { get; set; }
        internal int Reads;
        internal int ListReads;
        public bool IsPersistent => false;
        public Task<TransferTaskRecord> CreateTransferAsync(TransferPlan plan, CancellationToken cancellationToken) => Inner.CreateTransferAsync(plan, cancellationToken);
        public async Task<TransferTaskRecord?> GetTransferAsync(TransferTaskRef task, CancellationToken cancellationToken)
        {
            Interlocked.Increment(ref Reads);
            var record = await Inner.GetTransferAsync(task, cancellationToken);
            return AfterRead is { } after ? await after(task, record, cancellationToken) : record;
        }
        public Task<TransferTaskPage> ListTransfersAsync(AccountKey account, string? cursor, int limit, CancellationToken cancellationToken)
        { Interlocked.Increment(ref ListReads); return Inner.ListTransfersAsync(account, cursor, limit, cancellationToken); }
        public Task<bool> HasProtectedTransfersAsync(AccountKey account, CancellationToken cancellationToken) => Inner.HasProtectedTransfersAsync(account, cancellationToken);
        public async Task<TransferTaskRecord> SetTransferIntentAsync(TransferTaskRef task, long expectedRevision, TransferIntent intent, CancellationToken cancellationToken)
        {
            if (BeforeIntent is { } before) await before(task, intent, cancellationToken);
            return await Inner.SetTransferIntentAsync(task, expectedRevision, intent, cancellationToken);
        }
        public Task<TransferTaskRecord> ClaimTransferAsync(TransferTaskRef task, long expectedRevision, Guid runId, bool recovery, CancellationToken cancellationToken) =>
            Inner.ClaimTransferAsync(task, expectedRevision, runId, recovery, cancellationToken);
        public async Task<TransferTaskRecord> SaveTransferCheckpointAsync(TransferRunToken run, long expectedRevision, TransferCheckpoint checkpoint,
            bool releaseRun, CancellationToken cancellationToken)
        {
            if (BeforeCheckpoint is { } before) await before(run, expectedRevision, checkpoint, cancellationToken);
            return await Inner.SaveTransferCheckpointAsync(run, expectedRevision, checkpoint, releaseRun, cancellationToken);
        }
    }

    private sealed class Invocation(TransferTaskRef task, bool recovery, bool cancel)
    {
        internal TransferTaskRef Task { get; } = task;
        internal bool Recovery { get; } = recovery;
        internal bool Cancel { get; } = cancel;
        internal TaskCompletionSource Release { get; } = NewSignal();
        internal TaskCompletionSource StopObserved { get; } = NewSignal();
        internal TaskCompletionSource Released { get; } = NewSignal();
        internal TaskCompletionSource TerminalSaved { get; } = NewSignal();
        internal TaskCompletionSource CleanupRelease { get; } = NewSignal();
        internal bool PublishOnRelease { get; set; }
        internal bool FailCleanup { get; set; }
    }

    private sealed class FakeRunner(ObservedStore store) : IFileUploadRunner
    {
        private readonly Channel<Invocation> _calls = Channel.CreateUnbounded<Invocation>();
        internal static TransferSourceSnapshot Source { get; } = new(4, NativeFileIdentity: "source-identity");
        internal ConcurrentQueue<Invocation> Calls { get; } = new();
        internal HashSet<TransferTaskRef> DeferStop { get; } = [];
        internal HashSet<TransferTaskRef> ThrowOnStop { get; } = [];
        internal HashSet<TransferTaskRef> HoldCleanup { get; } = [];
        internal Action<TransferTaskRef>? BeforeRun { get; set; }
        internal int Active;
        internal int MaximumActive;
        internal int DeleteCalls;
        internal int Disposals;
        internal int Attempts;
        public event EventHandler<UploadTaskProgress>? ProgressChanged;
        internal void Emit(UploadTaskProgress progress) => ProgressChanged?.Invoke(this, progress);
        internal async Task<Invocation> NextAsync() => await _calls.Reader.ReadAsync(Token).AsTask().WaitAsync(TimeSpan.FromSeconds(10), Token);
        public Task<TransferTaskRecord> RunAsync(TransferTaskRef task, bool recovery, CancellationToken cancellationToken) =>
            ExecuteAsync(task, recovery, false, cancellationToken);
        public Task<TransferTaskRecord> CancelAsync(TransferTaskRef task, CancellationToken cancellationToken) =>
            ExecuteAsync(task, true, true, cancellationToken);
        public Task<TransferTaskRecord> PauseAsync(TransferTaskRef task, CancellationToken cancellationToken) =>
            throw new InvalidOperationException("Coordinator uses its own intent and local execution cancellation.");

        private async Task<TransferTaskRecord> ExecuteAsync(TransferTaskRef task, bool recovery, bool cancel, CancellationToken cancellationToken)
        {
            Interlocked.Increment(ref Attempts);
            BeforeRun?.Invoke(task);
            var call = new Invocation(task, recovery, cancel);
            int active = Interlocked.Increment(ref Active);
            int observed;
            do { observed = Volatile.Read(ref MaximumActive); }
            while (active > observed && Interlocked.CompareExchange(ref MaximumActive, active, observed) != observed);
            using var stop = cancellationToken.Register(() =>
            {
                call.StopObserved.TrySetResult();
                if (ThrowOnStop.Contains(task)) throw new InvalidOperationException("throwing stop fixture");
            });
            try
            {
                var record = Assert.IsType<TransferTaskRecord>(await store.GetTransferAsync(task, cancellationToken));
                record = await ClaimAsync(record, recovery, cancellationToken);
                if (record.Run is null || Terminal(record)) return record;
                var source = record.Checkpoint.Source ?? Source;
                record = await SaveAsync(record,
                    record.Checkpoint with { Phase = TransferPhase.Running, Source = source,
                        ConfirmedOffset = record.Checkpoint.ConfirmedOffset ?? 0, Uncertainty = TransferUncertainty.None }, false, cancellationToken);
                Calls.Enqueue(call);
                _calls.Writer.TryWrite(call);
                if (DeferStop.Contains(task)) await call.Release.Task;
                else _ = await Task.WhenAny(call.Release.Task, call.StopObserved.Task);
                record = Assert.IsType<TransferTaskRecord>(await store.GetTransferAsync(task, CancellationToken.None));
                if (cancellationToken.IsCancellationRequested && !call.PublishOnRelease)
                {
                    if (record.Intent == TransferIntent.Run)
                        record = await PauseIntentAsync(record);
                    return await SaveAsync(record,
                        record.Checkpoint with { Phase = TransferPhase.Paused }, true, CancellationToken.None);
                }
                TransferCheckpoint completed;
                if (cancel && !call.PublishOnRelease)
                {
                    Interlocked.Increment(ref DeleteCalls);
                    completed = record.Checkpoint with { Phase = TransferPhase.Canceled, RemoteCanceled = true };
                }
                else completed = record.Checkpoint with { Phase = TransferPhase.Completed, ConfirmedOffset = source.Length, RemotePublished = true };
                record = await SaveAsync(record, completed, true, CancellationToken.None);
                call.TerminalSaved.TrySetResult();
                if (HoldCleanup.Contains(task)) await call.CleanupRelease.Task;
                if (call.FailCleanup) throw new IOException("private cleanup path fixture");
                return record;
            }
            finally
            {
                Interlocked.Decrement(ref Active);
                call.Released.TrySetResult();
            }
        }

        private async Task<TransferTaskRecord> ClaimAsync(TransferTaskRecord record, bool recovery, CancellationToken cancellationToken)
        {
            var plan = record.Plan;
            var runId = Guid.NewGuid();
            for (int attempt = 0; attempt < 16; attempt++)
            {
                if (Terminal(record) || !recovery && record.Intent != TransferIntent.Run) return record;
                try { return await store.ClaimTransferAsync(plan.Reference, record.Revision, runId, recovery, cancellationToken); }
                catch (FilewayException error) when (error.Error.Code == ErrorCode.Conflict)
                {
                    var fresh = Assert.IsType<TransferTaskRecord>(await store.GetTransferAsync(plan.Reference, cancellationToken));
                    if (fresh.Revision == record.Revision || fresh.Plan != plan) throw;
                    record = fresh;
                }
            }
            throw new FilewayException(new(ErrorCode.Conflict, "Fixture metadata claim remained contested."));
        }

        private async Task<TransferTaskRecord> SaveAsync(TransferTaskRecord record, TransferCheckpoint checkpoint,
            bool release, CancellationToken cancellationToken)
        {
            var run = record.Run ?? throw new InvalidOperationException("Expected the fixture's original owned run.");
            for (int attempt = 0; attempt < 16; attempt++)
            {
                try { return await store.SaveTransferCheckpointAsync(run, record.Revision, checkpoint, release, cancellationToken); }
                catch (FilewayException error) when (error.Error.Code == ErrorCode.Conflict)
                {
                    var fresh = Assert.IsType<TransferTaskRecord>(await store.GetTransferAsync(run.Task, cancellationToken));
                    if (fresh.Run != run || fresh.Revision == record.Revision || fresh.Plan != record.Plan) throw;
                    record = fresh;
                }
            }
            throw new FilewayException(new(ErrorCode.Conflict, "Fixture acknowledgement metadata remained contested."));
        }

        private async Task<TransferTaskRecord> PauseIntentAsync(TransferTaskRecord record)
        {
            for (int attempt = 0; attempt < 16; attempt++)
            {
                if (Terminal(record) || record.Intent != TransferIntent.Run) return record;
                try { return await store.SetTransferIntentAsync(record.Plan.Reference, record.Revision, TransferIntent.Pause, CancellationToken.None); }
                catch (FilewayException error) when (error.Error.Code == ErrorCode.Conflict)
                { record = Assert.IsType<TransferTaskRecord>(await store.GetTransferAsync(record.Plan.Reference, CancellationToken.None)); }
            }
            throw new FilewayException(new(ErrorCode.Conflict, "Fixture pause metadata remained contested."));
        }

        private static bool Terminal(TransferTaskRecord record) => record.Checkpoint.Phase is
            TransferPhase.Completed or TransferPhase.Canceled or TransferPhase.Skipped;

        public ValueTask DisposeAsync() { Interlocked.Increment(ref Disposals); return ValueTask.CompletedTask; }
    }
}
