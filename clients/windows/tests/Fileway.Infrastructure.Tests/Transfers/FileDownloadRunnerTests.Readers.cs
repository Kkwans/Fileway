using System.Collections.Concurrent;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Tests.Transfers;

public sealed partial class FileDownloadRunnerTests
{
    [Fact]
    public async Task ReaderAcquisitionCannotStartAQueuedOrHistoricalCompletedTask()
    {
        await using var fixture = await Fixture.CreateAsync();
        var error = await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.AcquireReadLeaseAsync(fixture.Task, Token));
        Assert.Equal(ErrorCode.SourceChanged, error.Error.Code);
        Assert.Equal(0, fixture.Sources.PrepareCalls);
        Assert.Equal(0, fixture.Destinations.CreateCalls);
        Assert.Empty(fixture.Http.Requests);

        var completed = await fixture.Runner.RunAsync(fixture.Task, Token);
        Assert.Equal(TransferPhase.Completed, completed.Checkpoint.Phase);
        error = await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.AcquireReadLeaseAsync(fixture.Task, Token));
        Assert.Equal(ErrorCode.SourceChanged, error.Error.Code);
        Assert.Single(fixture.Http.Requests);
        Assert.Equal(0, fixture.Destinations.OpenCalls);
    }

    [Fact]
    public async Task ReaderReadinessRequiresThePersistedSourceAndAcceptedRunningFiles()
    {
        await using var fixture = await Fixture.CreateAsync();
        var readable = EnableReaders(fixture);
        var saveEntered = NewReaderSignal();
        var saveRelease = NewReaderSignal();
        using var barrier = new ReaderBarrierRelease(saveRelease);
        fixture.Store.BeforeSave = async (_, _, checkpoint, _, cancellationToken) =>
        {
            if (checkpoint.Phase != TransferPhase.Running) return;
            saveEntered.TrySetResult();
            await saveRelease.Task.WaitAsync(cancellationToken);
        };
        using var body = fixture.BlockBody();
        readable.BeforeAcquire = async _ =>
        {
            var durable = await fixture.ReadAsync();
            Assert.NotNull(durable.Run);
            Assert.Equal(TransferPhase.Running, durable.Checkpoint.Phase);
            Assert.Equal(new TransferSourceSnapshot(Data.LongLength, Modified), durable.Checkpoint.Source);
            Assert.Equal(fixture.Destinations.File.Files, durable.Checkpoint.Files);
            Assert.Equal(0, durable.Checkpoint.ConfirmedOffset);
        };
        var running = fixture.Runner.RunAsync(fixture.Task, Token);
        await saveEntered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var acquiring = fixture.Runner.AcquireReadLeaseAsync(fixture.Task, Token);
        Assert.False(acquiring.IsCompleted);
        Assert.Equal(0, readable.AcquireCalls);
        Assert.Empty(fixture.Sources.Issued);
        Assert.Empty(fixture.Http.Requests);
        saveRelease.TrySetResult();
        await using var reader = await acquiring.WaitAsync(TimeSpan.FromSeconds(5), Token);
        Assert.Equal(0, reader.ReadablePrefixBytes);
        Assert.Equal(Data.LongLength, reader.TotalBytes);
        Assert.Equal((await fixture.ReadAsync()).Run, reader.Run);
        await body.Entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        await fixture.Runner.CancelAsync(fixture.Task, Token);
        Assert.Equal(TransferPhase.Canceled, (await running).Checkpoint.Phase);
    }

    [Fact]
    public async Task CancelingPendingReadinessOnlyCancelsThatAcquisition()
    {
        await using var fixture = await Fixture.CreateAsync();
        var readable = EnableReaders(fixture);
        var entered = NewReaderSignal();
        var release = NewReaderSignal();
        using var barrier = new ReaderBarrierRelease(release);
        fixture.Store.BeforeSave = async (_, _, checkpoint, _, cancellationToken) =>
        {
            if (checkpoint.Phase != TransferPhase.Running) return;
            entered.TrySetResult();
            await release.Task.WaitAsync(cancellationToken);
        };
        var running = fixture.Runner.RunAsync(fixture.Task, Token);
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        using var caller = CancellationTokenSource.CreateLinkedTokenSource(Token);
        var acquiring = fixture.Runner.AcquireReadLeaseAsync(fixture.Task, caller.Token);
        await caller.CancelAsync();
        var error = await Assert.ThrowsAsync<FilewayException>(() => acquiring.WaitAsync(TimeSpan.FromSeconds(5), Token));
        Assert.Equal(ErrorCode.Canceled, error.Error.Code);
        Assert.Equal(TransferIntent.Run, (await fixture.ReadAsync()).Intent);
        Assert.Equal(0, readable.AcquireCalls);
        release.TrySetResult();
        Assert.Equal(TransferPhase.Completed, (await running.WaitAsync(TimeSpan.FromSeconds(5), Token)).Checkpoint.Phase);
        Assert.Single(fixture.Http.Requests);
    }

    [Fact]
    public async Task DestinationWithoutOptionalReadersStillCompletesItsOriginalDownload()
    {
        await using var fixture = await Fixture.CreateAsync();
        using var body = new ReaderControlledBody(Data);
        fixture.Http.ContentFactory = () => new System.Net.Http.StreamContent(body);
        var running = fixture.Runner.RunAsync(fixture.Task, Token);
        await body.Entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var error = await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.AcquireReadLeaseAsync(fixture.Task, Token));
        Assert.Equal(ErrorCode.CapabilityMissing, error.Error.Code);
        Assert.Equal(TransferIntent.Run, (await fixture.ReadAsync()).Intent);
        body.Release.TrySetResult();
        Assert.Equal(TransferPhase.Completed, (await running.WaitAsync(TimeSpan.FromSeconds(5), Token)).Checkpoint.Phase);
    }

    [Theory]
    [InlineData("task")]
    [InlineData("account")]
    public async Task ReaderAcquisitionRejectsAnUnrelatedTaskWithoutAdditionalIo(string mismatch)
    {
        await using var fixture = await Fixture.CreateAsync();
        var readable = EnableReaders(fixture);
        using var body = fixture.BlockBody();
        var running = fixture.Runner.RunAsync(fixture.Task, Token);
        await body.Entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var other = mismatch == "task" ? fixture.Task with { Id = Guid.NewGuid() }
            : fixture.Task with { Account = fixture.Task.Account with { ProfileId = Guid.NewGuid() } };
        var error = await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.AcquireReadLeaseAsync(other, Token));
        Assert.Equal(ErrorCode.SourceChanged, error.Error.Code);
        Assert.Equal(0, readable.AcquireCalls);
        Assert.Single(fixture.Http.Requests);
        await fixture.Runner.CancelAsync(fixture.Task, Token);
        await running;
    }

    [Fact]
    public async Task FourPendingAdmissionsConsumeTheSameBudgetAsLiveReaders()
    {
        await using var fixture = await Fixture.CreateAsync();
        var readable = EnableReaders(fixture);
        var entered = NewReaderSignal();
        var release = NewReaderSignal();
        using var barrier = new ReaderBarrierRelease(release);
        fixture.Store.BeforeSave = async (_, _, checkpoint, _, cancellationToken) =>
        {
            if (checkpoint.Phase != TransferPhase.Running) return;
            entered.TrySetResult();
            await release.Task.WaitAsync(cancellationToken);
        };
        using var body = fixture.BlockBody();
        var running = fixture.Runner.RunAsync(fixture.Task, Token);
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var pending = Enumerable.Range(0, 4).Select(_ => fixture.Runner.AcquireReadLeaseAsync(fixture.Task, Token)).ToArray();
        var fifth = await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.AcquireReadLeaseAsync(fixture.Task, Token));
        Assert.Equal(ErrorCode.Busy, fifth.Error.Code);
        Assert.Equal(0, readable.AcquireCalls);
        release.TrySetResult();
        var readers = await Task.WhenAll(pending).WaitAsync(TimeSpan.FromSeconds(5), Token);
        try
        {
            Assert.Equal(4, readable.AcquireCalls);
            fifth = await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.AcquireReadLeaseAsync(fixture.Task, Token));
            Assert.Equal(ErrorCode.Busy, fifth.Error.Code);
            await readers[0].DisposeAsync();
            await using var replacement = await fixture.Runner.AcquireReadLeaseAsync(fixture.Task, Token);
            Assert.Equal(5, readable.AcquireCalls);
        }
        finally { foreach (var reader in readers) await reader.DisposeAsync(); }
        await fixture.Runner.CancelAsync(fixture.Task, Token);
        await running;
    }

    [Theory]
    [InlineData("run")]
    [InlineData("length")]
    [InlineData("negative-prefix")]
    [InlineData("excess-prefix")]
    public async Task UntrustedReaderEvidenceIsReleasedAndNeverReturned(string invalid)
    {
        await using var fixture = await Fixture.CreateAsync();
        var readable = EnableReaders(fixture);
        readable.MutateReader = reader =>
        {
            switch (invalid)
            {
                case "run": reader.Run = reader.Run with { Id = Guid.NewGuid() }; break;
                case "length": reader.TotalBytes++; break;
                case "negative-prefix": reader.PrefixOverride = -1; break;
                case "excess-prefix": reader.PrefixOverride = Data.LongLength + 1; break;
            }
        };
        using var body = fixture.BlockBody();
        var running = fixture.Runner.RunAsync(fixture.Task, Token);
        await body.Entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var error = await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.AcquireReadLeaseAsync(fixture.Task, Token));
        Assert.Equal(ErrorCode.ProtocolMismatch, error.Error.Code);
        Assert.True(Assert.Single(readable.Readers).Disposed);
        Assert.Equal(1, Assert.Single(readable.Readers).DisposeCalls);
        Assert.Equal(TransferIntent.Run, (await fixture.ReadAsync()).Intent);
        Assert.Single(fixture.Http.Requests);
        await fixture.Runner.CancelAsync(fixture.Task, Token);
        await running;
    }

    [Fact]
    public async Task LateCanceledReaderFactoryIsReleasedBeforeAcquisitionAcknowledgement()
    {
        await using var fixture = await Fixture.CreateAsync();
        var readable = EnableReaders(fixture);
        var entered = NewReaderSignal();
        var release = NewReaderSignal();
        var disposing = NewReaderSignal();
        var disposed = NewReaderSignal();
        using var barrier = new ReaderBarrierRelease(release, disposed);
        readable.IgnoreAcquireCancellation = true;
        readable.BeforeAcquire = async _ => { entered.TrySetResult(); await release.Task; };
        readable.MutateReader = reader => reader.BeforeDispose = async () =>
        { disposing.TrySetResult(); await disposed.Task; };
        using var body = fixture.BlockBody();
        var running = fixture.Runner.RunAsync(fixture.Task, Token);
        await body.Entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        using var caller = CancellationTokenSource.CreateLinkedTokenSource(Token);
        var acquiring = fixture.Runner.AcquireReadLeaseAsync(fixture.Task, caller.Token);
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        await caller.CancelAsync();
        Assert.False(acquiring.IsCompleted);
        release.TrySetResult();
        await disposing.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        Assert.False(acquiring.IsCompleted);
        Assert.Equal(TransferIntent.Run, (await fixture.ReadAsync()).Intent);
        disposed.TrySetResult();
        var error = await Assert.ThrowsAsync<FilewayException>(() => acquiring.WaitAsync(TimeSpan.FromSeconds(5), Token));
        Assert.Equal(ErrorCode.Canceled, error.Error.Code);
        Assert.Equal(1, Assert.Single(readable.Readers).DisposeCalls);
        await fixture.Runner.CancelAsync(fixture.Task, Token);
        await running;
    }

    [Fact]
    public async Task StopWaitsForLateAdmissionReleaseBeforeDeletingOwnedPartial()
    {
        await using var fixture = await Fixture.CreateAsync();
        var readable = EnableReaders(fixture);
        var entered = NewReaderSignal();
        var release = NewReaderSignal();
        var disposing = NewReaderSignal();
        var disposeRelease = NewReaderSignal();
        using var barrier = new ReaderBarrierRelease(release, disposeRelease);
        readable.IgnoreAcquireCancellation = true;
        readable.BeforeAcquire = async _ => { entered.TrySetResult(); await release.Task; };
        readable.MutateReader = reader => reader.BeforeDispose = async () =>
        { disposing.TrySetResult(); await disposeRelease.Task; };
        using var body = fixture.BlockBody();
        var running = fixture.Runner.RunAsync(fixture.Task, Token);
        await body.Entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var acquiring = fixture.Runner.AcquireReadLeaseAsync(fixture.Task, Token);
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var drain = fixture.Runner.StopAndDrainAsync(Token);
        Assert.False(drain.IsCompleted);
        Assert.Equal(0, fixture.Destinations.File.DeleteCalls);
        release.TrySetResult();
        await disposing.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        Assert.False(drain.IsCompleted);
        Assert.Equal(0, fixture.Destinations.File.DeleteCalls);
        disposeRelease.TrySetResult();
        await Assert.ThrowsAsync<FilewayException>(() => acquiring.WaitAsync(TimeSpan.FromSeconds(5), Token));
        await drain.WaitAsync(TimeSpan.FromSeconds(5), Token);
        Assert.Equal(TransferPhase.Canceled, (await running).Checkpoint.Phase);
        Assert.Equal(1, fixture.Destinations.File.DeleteCalls);
        Assert.Equal(1, readable.RevokeCalls);
    }

    [Fact]
    public async Task ReceivedReadableBytesAreAvailableBeforeFlushAndWriterCompletionDoesNotWaitForPlayer()
    {
        await using var fixture = await Fixture.CreateAsync();
        var readable = EnableReaders(fixture);
        var entered = NewReaderSignal();
        var release = NewReaderSignal();
        using var barrier = new ReaderBarrierRelease(release);
        fixture.Sources.BeforeChecksum = async cancellationToken =>
        { entered.TrySetResult(); await release.Task.WaitAsync(cancellationToken); };
        // Acquire while the body is deliberately held, then let the accepted owner receive all bytes.
        var body = new ReaderControlledBody(Data) { HoldAfterFirstRead = true };
        fixture.Http.ContentFactory = () => new System.Net.Http.StreamContent(body);
        var running = fixture.Runner.RunAsync(fixture.Task, Token);
        await body.Entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        await using var reader = await fixture.Runner.AcquireReadLeaseAsync(fixture.Task, Token);
        var buffer = new byte[Data.Length];
        var waiting = reader.ReadAsync(0, buffer, Token).AsTask();
        Assert.False(waiting.IsCompleted);
        body.Release.TrySetResult();
        int received = await waiting.WaitAsync(TimeSpan.FromSeconds(5), Token);
        Assert.InRange(received, 1, Data.Length);
        Assert.Equal(Data[..received], buffer[..received]);
        await body.NextEntered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        Assert.Equal(received, reader.ReadablePrefixBytes);
        Assert.Equal(0, (await fixture.ReadAsync()).Checkpoint.ConfirmedOffset);
        Assert.DoesNotContain("flush", fixture.Destinations.File.Events);
        var future = reader.ReadAsync(received, buffer, Token).AsTask();
        Assert.False(future.IsCompleted);
        body.NextRelease.TrySetResult();
        Assert.InRange(await future.WaitAsync(TimeSpan.FromSeconds(5), Token), 1, Data.Length - received);
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        Assert.Equal(Data.LongLength, reader.ReadablePrefixBytes);
        Assert.False(readable.WriterDisposed);
        release.TrySetResult();
        Assert.Equal(TransferPhase.Completed, (await running.WaitAsync(TimeSpan.FromSeconds(5), Token)).Checkpoint.Phase);
        Assert.True(readable.WriterDisposed);
        Assert.False(Assert.Single(readable.Readers).Disposed);
        Assert.Equal(0, readable.RevokeCalls);
        Assert.Equal(Data.Length, await reader.ReadAsync(0, buffer, Token));
        Assert.Equal(Data, buffer);
        Assert.Equal(0, await reader.ReadAsync(Data.Length, buffer, Token));
        var historical = await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.AcquireReadLeaseAsync(fixture.Task, Token));
        Assert.Equal(ErrorCode.SourceChanged, historical.Error.Code);
        Assert.Single(fixture.Http.Requests);
    }

    [Fact]
    public async Task IndividualReaderDisposeAndCanceledReadLeaveTheDownloadRunning()
    {
        await using var fixture = await Fixture.CreateAsync();
        var readable = EnableReaders(fixture);
        using var body = fixture.BlockBody();
        var running = fixture.Runner.RunAsync(fixture.Task, Token);
        await body.Entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var reader = await fixture.Runner.AcquireReadLeaseAsync(fixture.Task, Token);
        using var caller = CancellationTokenSource.CreateLinkedTokenSource(Token);
        var waiting = reader.ReadAsync(0, new byte[4], caller.Token).AsTask();
        await caller.CancelAsync();
        var error = await Assert.ThrowsAsync<FilewayException>(() => waiting.WaitAsync(TimeSpan.FromSeconds(5), Token));
        Assert.Equal(ErrorCode.Canceled, error.Error.Code);
        await reader.DisposeAsync();
        await reader.DisposeAsync();
        Assert.Equal(1, Assert.Single(readable.Readers).DisposeCalls);
        Assert.Equal(TransferIntent.Run, (await fixture.ReadAsync()).Intent);
        Assert.False(running.IsCompleted);
        Assert.Equal(0, readable.RevokeCalls);
        await fixture.Runner.CancelAsync(fixture.Task, Token);
        await running;
    }

    [Fact]
    public async Task ConcurrentReaderDisposeSharesCompletionAndLeavesTheRunnerGateAvailable()
    {
        await using var fixture = await Fixture.CreateAsync();
        var readable = EnableReaders(fixture);
        using var body = fixture.BlockBody();
        var running = fixture.Runner.RunAsync(fixture.Task, Token);
        await body.Entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var reader = await fixture.Runner.AcquireReadLeaseAsync(fixture.Task, Token);
        var entered = NewReaderSignal();
        var release = NewReaderSignal();
        using var barrier = new ReaderBarrierRelease(release);
        Assert.Single(readable.Readers).BeforeDispose = async () => { entered.TrySetResult(); await release.Task; };
        var first = reader.DisposeAsync().AsTask();
        var second = reader.DisposeAsync().AsTask();
        Assert.Same(first, second);
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        await using var unrelatedReader = await fixture.Runner.AcquireReadLeaseAsync(fixture.Task, Token)
            .WaitAsync(TimeSpan.FromSeconds(5), Token);
        Assert.False(first.IsCompleted);
        Assert.Equal(TransferIntent.Run, (await fixture.ReadAsync()).Intent);
        release.TrySetResult();
        await first.WaitAsync(TimeSpan.FromSeconds(5), Token);
        Assert.Equal(1, readable.Readers.First().DisposeCalls);
        Assert.Equal(0, readable.RevokeCalls);
        await fixture.Runner.CancelAsync(fixture.Task, Token);
        await running;
    }

    [Fact]
    public async Task ReaderDisposeFailureRevokesOnlyReadCapabilityAndPreservesWriterIntent()
    {
        await using var fixture = await Fixture.CreateAsync();
        var readable = EnableReaders(fixture);
        using var body = new ReaderControlledBody(Data);
        fixture.Http.ContentFactory = () => new System.Net.Http.StreamContent(body);
        var running = fixture.Runner.RunAsync(fixture.Task, Token);
        await body.Entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var reader = await fixture.Runner.AcquireReadLeaseAsync(fixture.Task, Token);
        Assert.Single(readable.Readers).BeforeDispose = () => throw new IOException("private reader disposal: " + fixture.Plan.LocalPath);
        var error = await Assert.ThrowsAsync<FilewayException>(() => reader.DisposeAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(5), Token));
        Assert.Equal(ErrorCode.LocalStorageUnavailable, error.Error.Code);
        AssertSafe(error.Error, fixture);
        Assert.DoesNotContain("private reader disposal", error.ToString(), StringComparison.Ordinal);
        Assert.Null(error.InnerException);
        Assert.Equal(1, readable.RevokeCalls);
        Assert.Equal(TransferIntent.Run, (await fixture.ReadAsync()).Intent);
        Assert.False(running.IsCompleted);
        body.Release.TrySetResult();
        Assert.Equal(TransferPhase.Completed, (await running.WaitAsync(TimeSpan.FromSeconds(5), Token)).Checkpoint.Phase);
        Assert.Equal(0, fixture.Destinations.File.DeleteCalls);
        Assert.Single(fixture.Http.Requests);
    }

    [Fact]
    public async Task ActiveReaderIoMustDrainBeforeCancellationDeletesOwnedFile()
    {
        await using var fixture = await Fixture.CreateAsync();
        var readable = EnableReaders(fixture);
        using var body = fixture.BlockBody();
        var running = fixture.Runner.RunAsync(fixture.Task, Token);
        await body.Entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        await using var reader = await fixture.Runner.AcquireReadLeaseAsync(fixture.Task, Token);
        var ioEntered = NewReaderSignal();
        var ioRelease = NewReaderSignal();
        using var barrier = new ReaderBarrierRelease(ioRelease);
        Assert.Single(readable.Readers).BeforeRead = async _ => { ioEntered.TrySetResult(); await ioRelease.Task; };
        var read = reader.ReadAsync(0, new byte[4], Token).AsTask();
        await ioEntered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var canceled = fixture.Runner.CancelAsync(fixture.Task, Token);
        await readable.RevocationEntered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        Assert.False(canceled.IsCompleted);
        Assert.False(running.IsCompleted);
        Assert.False(fixture.Destinations.File.Disposed);
        Assert.Equal(0, fixture.Destinations.File.DeleteCalls);
        ioRelease.TrySetResult();
        await Assert.ThrowsAsync<FilewayException>(() => read.WaitAsync(TimeSpan.FromSeconds(5), Token));
        Assert.Equal(TransferPhase.Canceled, (await canceled.WaitAsync(TimeSpan.FromSeconds(5), Token)).Checkpoint.Phase);
        await running;
        Assert.Equal(1, fixture.Destinations.File.DeleteCalls);
        Assert.Equal(1, readable.RevokeCalls);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task IdleStopOrDisposeRevokesReadersRetainedAfterSuccessfulPublication(bool dispose)
    {
        await using var fixture = await Fixture.CreateAsync();
        var readable = EnableReaders(fixture);
        var body = new ReaderControlledBody(Data);
        fixture.Http.ContentFactory = () => new System.Net.Http.StreamContent(body);
        var running = fixture.Runner.RunAsync(fixture.Task, Token);
        await body.Entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        await using var reader = await fixture.Runner.AcquireReadLeaseAsync(fixture.Task, Token);
        body.Release.TrySetResult();
        var completed = await running.WaitAsync(TimeSpan.FromSeconds(5), Token);
        Assert.Equal(TransferPhase.Completed, completed.Checkpoint.Phase);
        Assert.Equal(0, readable.RevokeCalls);
        Task control = dispose ? fixture.Runner.DisposeAsync().AsTask() : fixture.Runner.StopAndDrainAsync(Token);
        await control.WaitAsync(TimeSpan.FromSeconds(5), Token);
        Assert.Equal(1, readable.RevokeCalls);
        var error = await Assert.ThrowsAsync<FilewayException>(() => reader.ReadAsync(0, new byte[4], Token).AsTask());
        Assert.Equal(ErrorCode.SourceChanged, error.Error.Code);
        Assert.Equal(completed, await fixture.ReadAsync());
        Assert.Equal(0, fixture.Destinations.File.DeleteCalls);
        Assert.Single(fixture.Http.Requests);
    }

    [Theory]
    [InlineData("negative")]
    [InlineData("beyond")]
    [InlineData("large")]
    [InlineData("temporary-eof")]
    public async Task ReaderBoundaryRejectsInvalidRequestsOrAnUnprovenTemporaryEof(string invalid)
    {
        await using var fixture = await Fixture.CreateAsync();
        var readable = EnableReaders(fixture);
        using var body = fixture.BlockBody();
        var running = fixture.Runner.RunAsync(fixture.Task, Token);
        await body.Entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        await using var reader = await fixture.Runner.AcquireReadLeaseAsync(fixture.Task, Token);
        if (invalid == "temporary-eof") Assert.Single(readable.Readers).ReturnTemporaryEof = true;
        var error = await Assert.ThrowsAsync<FilewayException>(() => reader.ReadAsync(invalid switch
        { "negative" => -1, "beyond" => Data.LongLength + 1, _ => 0 },
            new byte[invalid == "large" ? 128 * 1024 + 1 : 4], Token).AsTask());
        Assert.Equal(invalid == "temporary-eof" ? ErrorCode.ProtocolMismatch : ErrorCode.InvalidRequest, error.Error.Code);
        Assert.Equal(TransferIntent.Run, (await fixture.ReadAsync()).Intent);
        Assert.False(running.IsCompleted);
        await fixture.Runner.CancelAsync(fixture.Task, Token);
        await running;
    }

    [Fact]
    public async Task RevokeFailureProtectsOwnedPartialAndNormalizesPrivateDetails()
    {
        var fixture = await Fixture.CreateAsync();
        var readable = EnableReaders(fixture);
        readable.RevokeFailure = new IOException("private reader revoke: " + fixture.Plan.LocalPath);
        using var body = fixture.BlockBody();
        var running = fixture.Runner.RunAsync(fixture.Task, Token);
        await body.Entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var reader = await fixture.Runner.AcquireReadLeaseAsync(fixture.Task, Token);
        var cancel = fixture.Runner.CancelAsync(fixture.Task, Token);
        var error = await Assert.ThrowsAsync<FilewayException>(() => cancel.WaitAsync(TimeSpan.FromSeconds(5), Token));
        Assert.Equal(ErrorCode.LocalStorageUnavailable, error.Error.Code);
        AssertSafe(error.Error, fixture);
        Assert.DoesNotContain("private reader revoke", error.ToString(), StringComparison.Ordinal);
        await Assert.ThrowsAsync<FilewayException>(() => running.WaitAsync(TimeSpan.FromSeconds(5), Token));
        var durable = await fixture.ReadAsync();
        Assert.Equal(TransferPhase.OutcomeUnknown, durable.Checkpoint.Phase);
        Assert.Equal(TransferIntent.Cancel, durable.Intent);
        Assert.Equal(fixture.Destinations.File.Files, durable.Checkpoint.Files);
        Assert.True(await fixture.Store.HasProtectedTransfersAsync(fixture.Task.Account, Token));
        Assert.Equal(0, fixture.Destinations.File.DeleteCalls);
        Assert.Equal(1, readable.RevokeCalls);
        await reader.DisposeAsync();
        await Assert.ThrowsAsync<FilewayException>(() => fixture.DisposeAsync().AsTask());
    }

    [Fact]
    public async Task ANewExplicitDrainReawaitsTheSameUnconfirmedReadFlightWithoutFileReplay()
    {
        await using var fixture = await Fixture.CreateAsync();
        var readable = EnableReaders(fixture);
        readable.UnknownWhileDraining = true;
        using var body = fixture.BlockBody();
        var running = fixture.Runner.RunAsync(fixture.Task, Token);
        await body.Entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var reader = await fixture.Runner.AcquireReadLeaseAsync(fixture.Task, Token);
        var entered = NewReaderSignal();
        var release = NewReaderSignal();
        using var barrier = new ReaderBarrierRelease(release);
        Assert.Single(readable.Readers).BeforeRead = async _ => { entered.TrySetResult(); await release.Task; };
        var reading = reader.ReadAsync(0, new byte[4], Token).AsTask();
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var first = await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.StopAndDrainAsync(Token)
            .WaitAsync(TimeSpan.FromSeconds(5), Token));
        Assert.Equal(ErrorCode.OutcomeUnknown, first.Error.Code);
        await Assert.ThrowsAsync<FilewayException>(() => running.WaitAsync(TimeSpan.FromSeconds(5), Token));
        await readable.RevocationEntered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        Assert.Equal(1, readable.RevokeCalls);
        Assert.Equal(1, readable.RevokeWaits);
        Assert.False(readable.ActualRevocation.IsCompleted);
        Assert.False(reading.IsCompleted);
        Assert.Equal(0, fixture.Destinations.File.DeleteCalls);
        var protectedRecord = await fixture.ReadAsync();
        Assert.Equal(TransferPhase.OutcomeUnknown, protectedRecord.Checkpoint.Phase);
        Assert.Equal(TransferIntent.Cancel, protectedRecord.Intent);
        Assert.Equal(fixture.Destinations.File.Files, protectedRecord.Checkpoint.Files);

        var stillUnknown = await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.StopAndDrainAsync(Token)
            .WaitAsync(TimeSpan.FromSeconds(5), Token));
        Assert.Equal(ErrorCode.OutcomeUnknown, stillUnknown.Error.Code);
        Assert.Equal(1, readable.RevokeCalls);
        Assert.Equal(2, readable.RevokeWaits);
        Assert.Equal(protectedRecord, await fixture.ReadAsync());
        Assert.Equal(0, fixture.Destinations.File.DeleteCalls);
        release.TrySetResult();
        await Assert.ThrowsAsync<FilewayException>(() => reading.WaitAsync(TimeSpan.FromSeconds(5), Token));
        await readable.ActualRevocation.WaitAsync(TimeSpan.FromSeconds(5), Token);
        await fixture.Runner.StopAndDrainAsync(Token).WaitAsync(TimeSpan.FromSeconds(5), Token);
        Assert.Equal(1, readable.RevokeCalls);
        Assert.Equal(3, readable.RevokeWaits);
        Assert.Equal(protectedRecord, await fixture.ReadAsync());
        Assert.Equal(0, fixture.Destinations.File.DeleteCalls);
        var canceled = await fixture.Runner.ReconcileAsync(fixture.Task, Token);
        Assert.Equal(TransferPhase.Canceled, canceled.Checkpoint.Phase);
        Assert.Equal(1, fixture.Destinations.File.DeleteCalls);
        Assert.Single(fixture.Http.Requests);
        Assert.Equal(1, fixture.Destinations.CreateCalls);
        await reader.DisposeAsync();
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task UnconfirmedLateCapturesKeepTheGlobalBudgetUntilTheirOwnerDrainIsConfirmed(bool confirm)
    {
        var fixture = await Fixture.CreateAsync();
        var readable = EnableReaders(fixture);
        readable.IgnoreAcquireCancellation = true;
        readable.UnknownWhileDraining = true;
        var allFactoriesEntered = NewReaderSignal();
        var factoryRelease = NewReaderSignal();
        var actualDrainRelease = NewReaderSignal();
        int enteredFactories = 0;
        readable.BeforeAcquire = async _ =>
        {
            if (Interlocked.Increment(ref enteredFactories) == 4) allFactoriesEntered.TrySetResult();
            await factoryRelease.Task;
        };
        readable.BeforeActualRevoke = () => actualDrainRelease.Task;
        readable.MutateReader = reader => reader.BeforeDispose = () =>
            throw new FilewayException(new(ErrorCode.OutcomeUnknown, "private late reader release"));
        using var barrier = new ReaderBarrierRelease(factoryRelease, actualDrainRelease);
        using var body = fixture.BlockBody();
        Task<TransferTaskRecord>? nextRunning = null;
        TransferTaskRef? nextTask = null;
        try
        {
            var firstRunning = fixture.Runner.RunAsync(fixture.Task, Token);
            await body.Entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
            using var caller = CancellationTokenSource.CreateLinkedTokenSource(Token);
            var acquiring = Enumerable.Range(0, 4).Select(_ => fixture.Runner.AcquireReadLeaseAsync(fixture.Task, caller.Token)).ToArray();
            await allFactoriesEntered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
            await caller.CancelAsync();
            factoryRelease.TrySetResult();
            foreach (var pending in acquiring)
            {
                var error = await Assert.ThrowsAsync<FilewayException>(() => pending.WaitAsync(TimeSpan.FromSeconds(5), Token));
                Assert.Equal(ErrorCode.OutcomeUnknown, error.Error.Code);
                Assert.DoesNotContain("private late reader release", error.ToString(), StringComparison.Ordinal);
            }
            Assert.Equal(4, readable.Readers.Count);
            var oldFile = fixture.Destinations.File;
            await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.CancelAsync(fixture.Task, Token)
                .WaitAsync(TimeSpan.FromSeconds(5), Token));
            await Assert.ThrowsAsync<FilewayException>(() => firstRunning.WaitAsync(TimeSpan.FromSeconds(5), Token));
            var protectedRecord = await fixture.ReadAsync();
            Assert.Equal(TransferPhase.OutcomeUnknown, protectedRecord.Checkpoint.Phase);
            Assert.Equal(0, oldFile.DeleteCalls);
            Assert.False(readable.ActualRevocation.IsCompleted);
            if (confirm)
            {
                actualDrainRelease.TrySetResult();
                await readable.ActualRevocation.WaitAsync(TimeSpan.FromSeconds(5), Token);
                await fixture.Runner.StopAndDrainAsync(Token).WaitAsync(TimeSpan.FromSeconds(5), Token);
                Assert.Equal(protectedRecord, await fixture.ReadAsync());
                Assert.Equal(0, oldFile.DeleteCalls);
            }
            var nextPlan = fixture.Plan with { Reference = fixture.Task with { Id = Guid.NewGuid() },
                LocalPath = @"C:\synthetic-download\next.bin" };
            await fixture.Store.CreateTransferAsync(nextPlan, Token);
            nextTask = nextPlan.Reference;
            fixture.HttpTask = nextPlan.Reference;
            fixture.Destinations.FreshOverride = null;
            fixture.Destinations.FreshFactory = file => new ReadableFixtureDestination(file);
            fixture.Destinations.AlternateFinalPath = nextPlan.LocalPath;
            using var nextBody = new ReaderControlledBody(Data);
            fixture.Http.ContentFactory = () => new System.Net.Http.StreamContent(nextBody);
            nextRunning = fixture.Runner.RunAsync(nextPlan.Reference, Token);
            await nextBody.Entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
            if (confirm)
            {
                await using var reader = await fixture.Runner.AcquireReadLeaseAsync(nextPlan.Reference, Token)
                    .WaitAsync(TimeSpan.FromSeconds(5), Token);
                Assert.Equal(nextPlan.Reference, reader.Run.Task);
                Assert.Equal(TransferIntent.Run, (await fixture.Store.GetTransferAsync(nextPlan.Reference, Token))!.Intent);
            }
            else
            {
                var fifth = await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.AcquireReadLeaseAsync(nextPlan.Reference, Token));
                Assert.Equal(ErrorCode.Busy, fifth.Error.Code);
                Assert.False(readable.ActualRevocation.IsCompleted);
            }
            Assert.Equal(0, oldFile.DeleteCalls);
            Assert.Equal(protectedRecord, await fixture.ReadAsync());
            Assert.Equal(2, fixture.Http.Requests.Count);
        }
        finally
        {
            factoryRelease.TrySetResult();
            actualDrainRelease.TrySetResult();
            if (nextTask is { } task)
            {
                try { await fixture.Runner.CancelAsync(task, Token).WaitAsync(TimeSpan.FromSeconds(5), Token); }
                catch (FilewayException) { }
                if (nextRunning is not null)
                    try { await nextRunning.WaitAsync(TimeSpan.FromSeconds(5), Token); }
                    catch (FilewayException) { }
            }
            try { await fixture.DisposeAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(5), Token); }
            catch (FilewayException) { }
        }
    }

    private static TaskCompletionSource NewReaderSignal() => new(TaskCreationOptions.RunContinuationsAsynchronously);

    private sealed class ReaderBarrierRelease(params TaskCompletionSource[] signals) : IDisposable
    {
        public void Dispose() { foreach (var signal in signals) signal.TrySetResult(); }
    }

    private static ReadableFixtureDestination EnableReaders(Fixture fixture)
    {
        var readable = new ReadableFixtureDestination(fixture.Destinations.File);
        fixture.Destinations.FreshOverride = readable;
        return readable;
    }

    private sealed class ReadableFixtureDestination(FakeDestination inner) : IDownloadDestination, IReadableDownloadDestination
    {
        private readonly object _gate = new();
        private readonly List<byte> _bytes = [];
        private TaskCompletionSource _changed = NewReaderSignal();
        private Task? _revocation;
        private bool _revoked;
        internal ConcurrentQueue<FixtureReadLease> Readers { get; } = new();
        internal TaskCompletionSource RevocationEntered { get; } = NewReaderSignal();
        internal Func<CancellationToken, Task>? BeforeAcquire { get; set; }
        internal Action<FixtureReadLease>? MutateReader { get; set; }
        internal bool IgnoreAcquireCancellation { get; set; }
        internal Exception? RevokeFailure { get; set; }
        internal Func<Task>? BeforeActualRevoke { get; set; }
        internal bool UnknownWhileDraining { get; set; }
        internal int AcquireCalls, RevokeCalls, RevokeWaits;
        internal Task ActualRevocation { get { lock (_gate) return _revocation ?? Task.CompletedTask; } }
        internal bool WriterDisposed { get; private set; }
        public TransferWorkFiles Files => inner.Files;
        public bool IsPublished => inner.IsPublished;
        public long Length => inner.Length;
        internal long Prefix { get { lock (_gate) return _bytes.Count; } }

        public async Task WriteAsync(ReadOnlyMemory<byte> bytes, CancellationToken cancellationToken)
        {
            await inner.WriteAsync(bytes, cancellationToken);
            lock (_gate)
            {
                _bytes.AddRange(bytes.ToArray());
                var previous = _changed;
                _changed = NewReaderSignal();
                previous.TrySetResult();
            }
        }
        public Task<long> FlushToDiskAsync(CancellationToken cancellationToken) => inner.FlushToDiskAsync(cancellationToken);
        public Task<DownloadLocalFileHash> CalculateSha256Async(CancellationToken cancellationToken) => inner.CalculateSha256Async(cancellationToken);
        public Task<string> PublishNoReplaceAsync(CancellationToken cancellationToken) => inner.PublishNoReplaceAsync(cancellationToken);
        public Task DeleteOwnedPartialAsync(CancellationToken cancellationToken)
        {
            Assert.True(_revoked);
            Assert.All(Readers, reader => Assert.Equal(0, reader.ActiveReads));
            return inner.DeleteOwnedPartialAsync(cancellationToken);
        }
        public ValueTask DisposeAsync() { WriterDisposed = true; return inner.DisposeAsync(); }
        public async Task<IDownloadReadLease> AcquireReadLeaseAsync(TransferRunToken run, long totalBytes, CancellationToken cancellationToken)
        {
            Interlocked.Increment(ref AcquireCalls);
            if (BeforeAcquire is { } before) await before(cancellationToken);
            if (!IgnoreAcquireCancellation) cancellationToken.ThrowIfCancellationRequested();
            var reader = new FixtureReadLease(this, run, totalBytes);
            MutateReader?.Invoke(reader);
            Readers.Enqueue(reader);
            return reader;
        }
        public Task RevokeReadersAsync(CancellationToken cancellationToken)
        {
            lock (_gate)
            {
                Interlocked.Increment(ref RevokeWaits);
                if (_revocation is null)
                {
                    _revoked = true;
                    _changed.TrySetResult();
                    _revocation = Task.Run(async () =>
                    {
                        Interlocked.Increment(ref RevokeCalls);
                        RevocationEntered.TrySetResult();
                        foreach (var reader in Readers) await reader.RevokeAsync();
                        if (BeforeActualRevoke is { } before) await before();
                        if (RevokeFailure is { } failure) throw failure;
                    }, CancellationToken.None);
                }
                // Models an elapsed wait budget, while retaining one actual reader-I/O drain.
                if (UnknownWhileDraining && !_revocation.IsCompleted)
                    return Task.FromException(new FilewayException(new(ErrorCode.OutcomeUnknown, "fixture reader drain still running")));
                return _revocation;
            }
        }
        internal async ValueTask<int> ReadAsync(long offset, Memory<byte> buffer, CancellationToken cancellationToken)
        {
            while (true)
            {
                Task changed;
                lock (_gate)
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    if (_revoked) throw new FilewayException(new(ErrorCode.SourceChanged, "fixture revoked"));
                    if (offset < _bytes.Count)
                    {
                        int count = checked((int)Math.Min(buffer.Length, _bytes.Count - offset));
                        _bytes.GetRange(checked((int)offset), count).ToArray().AsMemory().CopyTo(buffer);
                        return count;
                    }
                    changed = _changed.Task;
                }
                await changed.WaitAsync(cancellationToken);
            }
        }
    }

    private sealed class FixtureReadLease(ReadableFixtureDestination owner, TransferRunToken run, long totalBytes) : IDownloadReadLease
    {
        private readonly SemaphoreSlim _serial = new(1, 1);
        private readonly CancellationTokenSource _stop = new();
        private readonly object _gate = new();
        private Task? _dispose;
        internal Func<CancellationToken, Task>? BeforeRead { get; set; }
        internal Func<Task>? BeforeDispose { get; set; }
        internal long? PrefixOverride { get; set; }
        internal bool ReturnTemporaryEof { get; set; }
        internal bool Disposed { get; private set; }
        internal int DisposeCalls, ActiveReads;
        public TransferRunToken Run { get; set; } = run;
        public long TotalBytes { get; set; } = totalBytes;
        public long ReadablePrefixBytes => PrefixOverride ?? owner.Prefix;
        public async ValueTask<int> ReadAsync(long offset, Memory<byte> buffer, CancellationToken cancellationToken)
        {
            using var stopped = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, _stop.Token);
            await _serial.WaitAsync(stopped.Token);
            try
            {
                Interlocked.Increment(ref ActiveReads);
                if (BeforeRead is { } before) await before(stopped.Token);
                stopped.Token.ThrowIfCancellationRequested();
                if (buffer.Length == 0 || offset == TotalBytes || ReturnTemporaryEof) return 0;
                return await owner.ReadAsync(offset, buffer, stopped.Token);
            }
            finally { Interlocked.Decrement(ref ActiveReads); _serial.Release(); }
        }
        internal async Task RevokeAsync()
        {
            await _stop.CancelAsync();
            await _serial.WaitAsync(CancellationToken.None);
            _serial.Release();
        }
        public ValueTask DisposeAsync()
        {
            lock (_gate)
            {
                _dispose ??= Task.Run(async () =>
                {
                    Interlocked.Increment(ref DisposeCalls);
                    if (BeforeDispose is { } before) await before();
                    await RevokeAsync();
                    Disposed = true;
                }, CancellationToken.None);
                return new(_dispose);
            }
        }
    }

    private sealed class ReaderControlledBody(byte[] bytes) : Stream
    {
        private int _offset;
        internal TaskCompletionSource Entered { get; } = NewReaderSignal();
        internal TaskCompletionSource Release { get; } = NewReaderSignal();
        internal TaskCompletionSource NextEntered { get; } = NewReaderSignal();
        internal TaskCompletionSource NextRelease { get; } = NewReaderSignal();
        internal bool HoldAfterFirstRead { get; set; }
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => bytes.Length;
        public override long Position { get => _offset; set => throw new NotSupportedException(); }
        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            Entered.TrySetResult();
            await Release.Task.WaitAsync(cancellationToken);
            if (HoldAfterFirstRead && _offset > 0 && _offset < bytes.Length)
            {
                NextEntered.TrySetResult();
                await NextRelease.Task.WaitAsync(cancellationToken);
            }
            int count = Math.Min(buffer.Length, bytes.Length - _offset);
            bytes.AsMemory(_offset, count).CopyTo(buffer);
            _offset += count;
            return count;
        }
        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override void Flush() => throw new NotSupportedException();
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }
}
