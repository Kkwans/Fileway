using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;
using Fileway.Infrastructure.Transfers;

namespace Fileway.Infrastructure.Tests.Transfers;

public sealed class FileDownloadRunnerTests
{
    private static readonly byte[] Data = [3, 1, 4, 1, 5, 9, 2, 6, 5];
    private static readonly DateTimeOffset Modified = new(2026, 10, 12, 0, 0, 0, TimeSpan.Zero);
    private static CancellationToken Token => TestContext.Current.CancellationToken;
    private static string Hash(byte[] bytes) => Convert.ToHexStringLower(SHA256.HashData(bytes));

    [Fact]
    public async Task ExactFreshBytesAreFlushedAndRereadBeforeChecksumAndDurablePublish()
    {
        await using var fixture = await Fixture.CreateAsync();
        var progress = new ConcurrentQueue<DownloadTaskProgress>();
        fixture.Runner.ProgressChanged += (_, value) => { progress.Enqueue(value); throw new InvalidOperationException("subscriber fixture"); };
        fixture.Sources.BeforeChecksum = async _ =>
        {
            var durable = await fixture.ReadAsync();
            Assert.Equal(TransferPhase.Verifying, durable.Checkpoint.Phase);
            Assert.Equal(Data.LongLength, durable.Checkpoint.ConfirmedOffset);
            Assert.Equal(Hash(Data), durable.Checkpoint.LocalSha256);
            Assert.Equal(["flush", "hash"], fixture.Destinations.File.Events.Where(value => value is "flush" or "hash"));
        };
        fixture.Destinations.File.BeforePublish = async _ =>
        {
            var durable = await fixture.ReadAsync();
            Assert.Equal(TransferPhase.Publishing, durable.Checkpoint.Phase);
            Assert.Equal(TransferUncertainty.LocalPublish, durable.Checkpoint.Uncertainty);
            Assert.Equal(Hash(Data), durable.Checkpoint.LocalSha256);
            Assert.Equal(Hash(Data), durable.Checkpoint.ServerSha256);
        };

        var result = await fixture.Runner.RunAsync(fixture.Task, Token);

        Assert.Equal(TransferPhase.Completed, result.Checkpoint.Phase);
        Assert.Equal(Data, fixture.Destinations.File.Bytes);
        Assert.Equal(Data.LongLength, result.Checkpoint.ConfirmedOffset);
        Assert.Equal("fixture-volume:partial-1", result.Checkpoint.Files!.PublishedFileIdentity);
        Assert.Equal(TransferUncertainty.None, result.Checkpoint.Uncertainty);
        Assert.Null(result.Run);
        Assert.Single(fixture.Http.Requests);
        Assert.Single(fixture.Sources.Acquired);
        Assert.Single(fixture.Sources.Checksums);
        Assert.Equal(1, fixture.Destinations.File.PublishCalls);
        Assert.Equal(0, fixture.Destinations.File.DeleteCalls);
        Assert.All(progress, value => Assert.InRange(value.ReceivedBytes, 0, Data.LongLength));
        fixture.AssertDrained();
    }

    [Fact]
    public async Task Empty200StillRequiresFlushLocalHashServerHashAndOnePublication()
    {
        await using var fixture = await Fixture.CreateAsync(data: []);

        var result = await fixture.Runner.RunAsync(fixture.Task, Token);

        Assert.Equal(TransferPhase.Completed, result.Checkpoint.Phase);
        Assert.Equal(0, result.Checkpoint.ConfirmedOffset);
        Assert.Equal(Hash([]), result.Checkpoint.LocalSha256);
        Assert.Equal(Hash([]), result.Checkpoint.ServerSha256);
        Assert.Equal(["flush", "hash", "publish"], fixture.Destinations.File.Events);
        fixture.AssertDrained();
    }

    [Theory]
    [InlineData("206")]
    [InlineData("redirect")]
    [InlineData("short")]
    [InlineData("long")]
    [InlineData("content-length")]
    [InlineData("content-range")]
    [InlineData("unparsed-content-range")]
    [InlineData("gzip")]
    [InlineData("unknown-fileway-version-extension")]
    public async Task InvalidFreshResponseNeverPublishesAndDeletesOnlyOwnedPartial(string scenario)
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Http.Mutate = response =>
        {
            switch (scenario)
            {
                case "206": response.StatusCode = HttpStatusCode.PartialContent; break;
                case "redirect": response.StatusCode = HttpStatusCode.Redirect; response.Headers.Location = new("http://127.0.0.1:1/other"); break;
                case "short": response.Content = new ByteArrayContent(Data[..^1]); response.Content.Headers.ContentLength = null; break;
                case "long": response.Content = new ByteArrayContent([.. Data, 7]); response.Content.Headers.ContentLength = null; break;
                case "content-length": response.Content.Headers.ContentLength = Data.LongLength + 1; break;
                case "content-range": response.Content.Headers.ContentRange = new ContentRangeHeaderValue(0, Data.Length - 1, Data.Length); break;
                case "unparsed-content-range": response.Content.Headers.TryAddWithoutValidation("Content-Range", "private-malformed-range"); break;
                case "gzip": response.Content.Headers.ContentEncoding.Add("gzip"); break;
                // Defensive rejection of an unknown version extension does not imply a pinned-source contract.
                case "unknown-fileway-version-extension": response.Headers.Add("X-Fileway-Source-Version", "private-version"); break;
            }
        };

        var result = await fixture.Runner.RunAsync(fixture.Task, Token);

        Assert.NotEqual(TransferPhase.Completed, result.Checkpoint.Phase);
        Assert.NotEqual(TransferPhase.Paused, result.Checkpoint.Phase);
        Assert.Equal(0, fixture.Destinations.File.PublishCalls);
        Assert.Equal(1, fixture.Destinations.File.DeleteCalls);
        Assert.Empty(fixture.Sources.Checksums);
        Assert.InRange(fixture.Destinations.File.Length, 0, Data.LongLength);
        AssertSafe(result.Checkpoint.Error!, fixture);
        fixture.AssertDrained();
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task NoContentLengthAndIdentityEncodingAreValidFreshReads(bool identityEncoding)
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Http.Mutate = response =>
        {
            response.Content.Headers.ContentLength = null;
            if (identityEncoding) response.Content.Headers.ContentEncoding.Add("identity");
        };

        var result = await fixture.Runner.RunAsync(fixture.Task, Token);

        Assert.Equal(TransferPhase.Completed, result.Checkpoint.Phase);
        Assert.Equal(Data, fixture.Destinations.File.Bytes);
    }

    [Fact]
    public async Task StandardEtagAndAdvertisedResumeDoNotTurnFreshGetIntoConditionalOrRangeRead()
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Sources.MutateLease = lease => lease.SupportsResume = true;
        fixture.Http.Mutate = response => response.Headers.ETag = new EntityTagHeaderValue("\"ordinary-http-etag\"");

        var result = await fixture.Runner.RunAsync(fixture.Task, Token);

        Assert.Equal(TransferPhase.Completed, result.Checkpoint.Phase);
        Assert.Single(fixture.Http.Requests);
        Assert.Equal(Data, fixture.Destinations.File.Bytes);
    }

    [Theory]
    [InlineData("identity")]
    [InlineData("length")]
    [InlineData("version")]
    [InlineData("resource-version")]
    [InlineData("non-loopback")]
    public async Task InvalidLeaseFailsBeforeHttpWithoutUsingResumeCapability(string scenario)
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Sources.MutateLease = lease =>
        {
            lease.SupportsResume = true;
            switch (scenario)
            {
                case "identity": lease.Resource = lease.Resource with { WirePath = "/private-other.bin" }; break;
                case "length": lease.Length++; break;
                case "version": lease.SourceVersion = "private-version"; break;
                case "resource-version": lease.Resource = lease.Resource with { SourceVersion = "private-version" }; break;
                case "non-loopback": lease.Address = new("https://private.example.invalid/download"); break;
            }
        };

        var result = await fixture.Runner.RunAsync(fixture.Task, Token);

        Assert.NotEqual(TransferPhase.Completed, result.Checkpoint.Phase);
        Assert.Empty(fixture.Http.Requests);
        Assert.Equal(0, fixture.Destinations.File.PublishCalls);
        Assert.Equal(1, fixture.Destinations.File.DeleteCalls);
        AssertSafe(result.Checkpoint.Error!, fixture);
        fixture.AssertDrained();
    }

    [Theory]
    [InlineData("version")]
    [InlineData("identity")]
    [InlineData("directory")]
    [InlineData("negative-length")]
    public async Task InvalidPreparedMetadataCannotCreateAnyOwnedFile(string scenario)
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Sources.MutateMetadata = file => scenario switch
        {
            "version" => file with { Resource = file.Resource with { SourceVersion = "private-version" } },
            "identity" => file with { Resource = file.Resource with { WirePath = "/private-other.bin" } },
            "directory" => file with { IsDirectory = true },
            "negative-length" => file with { Size = -1 },
            _ => throw new InvalidOperationException(),
        };

        var result = await fixture.Runner.RunAsync(fixture.Task, Token);

        Assert.NotEqual(TransferPhase.Completed, result.Checkpoint.Phase);
        Assert.Equal(0, fixture.Destinations.CreateCalls);
        Assert.Empty(fixture.Sources.Acquired);
        Assert.Empty(fixture.Http.Requests);
        AssertSafe(result.Checkpoint.Error!, fixture);
        fixture.AssertDrained();
    }

    [Theory]
    [InlineData("version")]
    [InlineData("automatic-resume")]
    [InlineData("replace")]
    [InlineData("upload")]
    public async Task UnsupportedPlanIsRejectedWithoutClaimingOrOpeningAnything(string scenario)
    {
        await using var fixture = await Fixture.CreateAsync(mutatePlan: plan => scenario switch
        {
            "version" => plan with { Remote = plan.Remote with { SourceVersion = "private-version" } },
            "automatic-resume" => plan with { AutomaticResume = true },
            "replace" => plan with { Conflict = FileConflictPolicy.Replace },
            "upload" => plan with { Direction = TransferDirection.Upload },
            _ => throw new InvalidOperationException(),
        });
        var original = await fixture.ReadAsync();

        await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.RunAsync(fixture.Task, Token));

        Assert.Equal(original, await fixture.ReadAsync());
        Assert.False(fixture.Locks.Held);
        Assert.Equal(0, fixture.Sources.PrepareCalls);
        Assert.Equal(0, fixture.Destinations.CreateCalls);
        Assert.Empty(fixture.Http.Requests);
    }

    [Theory]
    [InlineData("hash")]
    [InlineData("bad-hash")]
    [InlineData("algorithm")]
    [InlineData("identity")]
    [InlineData("length")]
    [InlineData("modified")]
    [InlineData("version")]
    public async Task ChecksumMustMatchPinnedIdentityAlgorithmLengthModifiedAndLocalReread(string scenario)
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Sources.MutateChecksum = checksum => scenario switch
        {
            "hash" => checksum with { Digest = new string('a', 64) },
            "bad-hash" => checksum with { Digest = new string('g', 64) },
            "algorithm" => checksum with { Algorithm = ChecksumAlgorithm.Md5 },
            "identity" => checksum with { Resource = checksum.Resource with { WirePath = "/private-other.bin" } },
            "length" => checksum with { Size = checksum.Size + 1 },
            "modified" => checksum with { Modified = Modified.AddTicks(1) },
            "version" => checksum with { Resource = checksum.Resource with { SourceVersion = "private-version" } },
            _ => throw new InvalidOperationException(),
        };

        var result = await fixture.Runner.RunAsync(fixture.Task, Token);

        Assert.NotEqual(TransferPhase.Completed, result.Checkpoint.Phase);
        Assert.Equal(Data.LongLength, result.Checkpoint.ConfirmedOffset);
        Assert.Equal(0, fixture.Destinations.File.PublishCalls);
        Assert.Equal(1, fixture.Destinations.File.DeleteCalls);
        AssertSafe(result.Checkpoint.Error!, fixture);
        fixture.AssertDrained();
    }

    [Fact]
    public async Task LocalRereadCorruptionFailsEvenWhenReceivedBytesMatchServer()
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Destinations.File.MutateHash = hash => hash with { Sha256 = new string('a', 64) };

        var result = await fixture.Runner.RunAsync(fixture.Task, Token);

        Assert.NotEqual(TransferPhase.Completed, result.Checkpoint.Phase);
        Assert.Equal(0, fixture.Destinations.File.PublishCalls);
        Assert.Equal(1, fixture.Destinations.File.DeleteCalls);
        fixture.AssertDrained();
    }

    [Fact]
    public async Task DurableCancelPrecedesStoppingBodyAndDrainsBeforeReturning()
    {
        await using var fixture = await Fixture.CreateAsync();
        var body = fixture.BlockBody();
        var running = fixture.Runner.RunAsync(fixture.Task, Token);
        await body.Entered.Task.WaitAsync(Token);

        var canceled = await fixture.Runner.CancelAsync(fixture.Task, Token);

        Assert.Equal(TransferIntent.Cancel, canceled.Intent);
        Assert.Equal(TransferPhase.Canceled, canceled.Checkpoint.Phase);
        Assert.Equal(canceled, await running);
        Assert.True(body.CancellationObserved);
        Assert.True(body.Disposed);
        Assert.Equal(1, fixture.Destinations.File.DeleteCalls);
        Assert.Equal(0, fixture.Destinations.File.PublishCalls);
        fixture.AssertDrained();
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task StopAndDisposePersistCancelAndDrainAllActiveResources(bool dispose)
    {
        await using var fixture = await Fixture.CreateAsync();
        var body = fixture.BlockBody();
        var running = fixture.Runner.RunAsync(fixture.Task, Token);
        await body.Entered.Task.WaitAsync(Token);

        if (dispose) await fixture.Runner.DisposeAsync();
        else await fixture.Runner.StopAndDrainAsync(Token);

        var stopped = await running;
        Assert.Equal(TransferIntent.Cancel, stopped.Intent);
        Assert.Equal(TransferPhase.Canceled, stopped.Checkpoint.Phase);
        Assert.True(body.Disposed);
        fixture.AssertDrained();
    }

    [Fact]
    public async Task StopAndDrainLeavesRunnerReusableForAnotherExplicitQueuedTask()
    {
        await using var fixture = await Fixture.CreateAsync();
        var body = fixture.BlockBody();
        var running = fixture.Runner.RunAsync(fixture.Task, Token);
        await body.Entered.Task.WaitAsync(Token);
        await fixture.Runner.StopAndDrainAsync(Token).WaitAsync(TimeSpan.FromSeconds(5), Token);
        Assert.Equal(TransferPhase.Canceled, (await running).Checkpoint.Phase);
        fixture.AssertDrained();
        var nextPlan = fixture.Plan with { Reference = new(fixture.Task.Account, Guid.NewGuid()) };
        await fixture.Store.CreateTransferAsync(nextPlan, Token);
        fixture.HttpTask = nextPlan.Reference;
        fixture.Http.ContentFactory = null;

        var completed = await fixture.Runner.RunAsync(nextPlan.Reference, Token);

        Assert.Equal(TransferPhase.Completed, completed.Checkpoint.Phase);
        Assert.Equal(Data, fixture.Destinations.File.Bytes);
        Assert.Equal(2, fixture.Http.Requests.Count);
        Assert.Equal(2, fixture.Destinations.CreateCalls);
        fixture.AssertDrained();
    }

    [Fact]
    public async Task QueuedCancelUsesNeitherSourceDestinationNorHttp()
    {
        await using var fixture = await Fixture.CreateAsync();

        var result = await fixture.Runner.CancelAsync(fixture.Task, Token);

        Assert.Equal(TransferPhase.Canceled, result.Checkpoint.Phase);
        Assert.Equal(0, fixture.Sources.PrepareCalls);
        Assert.Equal(0, fixture.Destinations.CreateCalls);
        Assert.Empty(fixture.Sources.Acquired);
        Assert.Empty(fixture.Http.Requests);
    }

    [Fact]
    public async Task OsLockBusyCannotClaimOrOpenAnySource()
    {
        await using var fixture = await Fixture.CreateAsync();
        await using var held = await fixture.Locks.AcquireAsync(fixture.Task, Token);
        var original = await fixture.ReadAsync();

        var error = await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.RunAsync(fixture.Task, Token));

        Assert.Equal(ErrorCode.Busy, error.Error.Code);
        Assert.Equal(original, await fixture.ReadAsync());
        Assert.Equal(0, fixture.Sources.PrepareCalls);
        Assert.Equal(0, fixture.Destinations.CreateCalls);
    }

    [Fact]
    public async Task StoreGlobalThreeRunBudgetRemainsAuthoritativeAndBusyReleasesOsLock()
    {
        await using var fixture = await Fixture.CreateAsync();
        for (int index = 0; index < 3; index++)
        {
            var plan = fixture.Plan with { Reference = new(fixture.Task.Account, Guid.NewGuid()),
                LocalPath = @"C:\synthetic-download\budget-" + index + ".bin" };
            var queued = await fixture.Store.CreateTransferAsync(plan, Token);
            await fixture.Store.ClaimTransferAsync(plan.Reference, queued.Revision, Guid.NewGuid(), recovery: false, Token);
        }
        var original = await fixture.ReadAsync();

        var error = await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.RunAsync(fixture.Task, Token));

        Assert.Equal(ErrorCode.Busy, error.Error.Code);
        Assert.Equal(original, await fixture.ReadAsync());
        Assert.False(fixture.Locks.Held);
        Assert.Equal(0, fixture.Sources.PrepareCalls);
        Assert.Equal(0, fixture.Destinations.CreateCalls);
    }

    [Fact]
    public async Task OneRunnerRejectsConcurrentExecutionWithoutStartingSecondGet()
    {
        await using var fixture = await Fixture.CreateAsync();
        var body = fixture.BlockBody();
        var running = fixture.Runner.RunAsync(fixture.Task, Token);
        await body.Entered.Task.WaitAsync(Token);
        var otherPlan = fixture.Plan with { Reference = new(fixture.Task.Account, Guid.NewGuid()) };
        await fixture.Store.CreateTransferAsync(otherPlan, Token);

        var error = await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.RunAsync(otherPlan.Reference, Token));

        Assert.Equal(ErrorCode.Busy, error.Error.Code);
        Assert.Single(fixture.Http.Requests);
        await fixture.Runner.CancelAsync(fixture.Task, Token);
        await running;
        fixture.AssertDrained();
    }

    [Fact]
    public async Task CancelIntentCasRaceAfterActualRenamePreservesCompletedEvidence()
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Destinations.File.AfterPublish = async _ =>
        {
            var record = await fixture.ReadAsync();
            await fixture.Store.Inner.SetTransferIntentAsync(fixture.Task, record.Revision, TransferIntent.Cancel, Token);
        };

        var result = await fixture.Runner.RunAsync(fixture.Task, Token);

        Assert.Equal(TransferIntent.Cancel, result.Intent);
        Assert.Equal(TransferPhase.Completed, result.Checkpoint.Phase);
        Assert.NotNull(result.Checkpoint.Files!.PublishedFileIdentity);
        Assert.Equal(1, fixture.Destinations.File.PublishCalls);
        Assert.Equal(0, fixture.Destinations.File.DeleteCalls);
        fixture.AssertDrained();
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task UnknownPublicationIsNeverReplayedAndReconcilesOnlyOwnedLocalEvidence(bool actuallyPublished)
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Destinations.File.PublishUnknown = true;
        fixture.Destinations.File.PublishDespiteUnknown = actuallyPublished;

        var unknown = await fixture.Runner.RunAsync(fixture.Task, Token);

        Assert.Equal(TransferPhase.OutcomeUnknown, unknown.Checkpoint.Phase);
        Assert.Equal(TransferUncertainty.LocalPublish, unknown.Checkpoint.Uncertainty);
        Assert.Equal(0, fixture.Destinations.File.DeleteCalls);
        Assert.True(await fixture.Store.HasProtectedTransfersAsync(fixture.Task.Account, Token));
        var reconciled = await fixture.Runner.ReconcileAsync(fixture.Task, Token);
        Assert.Equal(actuallyPublished ? TransferPhase.Completed : TransferPhase.Failed, reconciled.Checkpoint.Phase);
        Assert.Equal(TransferUncertainty.None, reconciled.Checkpoint.Uncertainty);
        Assert.Equal(1, fixture.Sources.PrepareCalls);
        Assert.Single(fixture.Sources.Acquired);
        Assert.Single(fixture.Sources.Checksums);
        Assert.Single(fixture.Http.Requests);
        Assert.Equal(1, fixture.Destinations.CreateCalls);
        Assert.Equal(1, fixture.Destinations.OpenCalls);
        Assert.Equal(1, fixture.Destinations.File.PublishCalls);
        Assert.Equal(actuallyPublished ? 0 : 1, fixture.Destinations.File.DeleteCalls);
        fixture.AssertDrained();
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ReconcileMissingOrMismatchedOwnedEvidenceStaysProtectedAndDoesNotDelete(bool missing)
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Destinations.File.PublishUnknown = true;
        fixture.Destinations.File.PublishDespiteUnknown = true;
        await fixture.Runner.RunAsync(fixture.Task, Token);
        if (missing) fixture.Destinations.OpenFailure = new FilewayException(new(ErrorCode.SourceChanged, "private missing file"));
        else fixture.Destinations.File.MutateHash = hash => hash with { Sha256 = new string('a', 64) };

        var result = await fixture.Runner.ReconcileAsync(fixture.Task, Token);

        Assert.Equal(TransferPhase.OutcomeUnknown, result.Checkpoint.Phase);
        Assert.Equal(TransferUncertainty.LocalPublish, result.Checkpoint.Uncertainty);
        Assert.Equal(0, fixture.Destinations.File.DeleteCalls);
        Assert.Equal(1, fixture.Destinations.File.PublishCalls);
        Assert.True(await fixture.Store.HasProtectedTransfersAsync(fixture.Task.Account, Token));
        fixture.AssertDrained();
    }

    [Fact]
    public async Task CancelUnknownButActuallyPublishedDiscoversCompletedWithoutRemoteWork()
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Destinations.File.PublishUnknown = true;
        fixture.Destinations.File.PublishDespiteUnknown = true;
        await fixture.Runner.RunAsync(fixture.Task, Token);

        var result = await fixture.Runner.CancelAsync(fixture.Task, Token);

        Assert.Equal(TransferIntent.Cancel, result.Intent);
        Assert.Equal(TransferPhase.Completed, result.Checkpoint.Phase);
        Assert.Equal(TransferUncertainty.None, result.Checkpoint.Uncertainty);
        Assert.Equal(0, fixture.Destinations.File.DeleteCalls);
        Assert.Equal(1, fixture.Destinations.File.PublishCalls);
        Assert.Equal(1, fixture.Sources.PrepareCalls);
        Assert.Single(fixture.Sources.Acquired);
        Assert.Single(fixture.Sources.Checksums);
        Assert.Single(fixture.Http.Requests);
        fixture.AssertDrained();
    }

    [Fact]
    public async Task FailureToPersistPublishIntentNeverRenamesAndRetainsProtectedEvidence()
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Store.BeforeSave = (_, _, checkpoint, _, _) => checkpoint.Phase == TransferPhase.Publishing
            ? Task.FromException(new FilewayException(new(ErrorCode.LocalStorageUnavailable, "fixture storage failure"))) : Task.CompletedTask;

        var error = await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.RunAsync(fixture.Task, Token));

        Assert.Equal(ErrorCode.LocalStorageUnavailable, error.Error.Code);
        Assert.Equal(0, fixture.Destinations.File.PublishCalls);
        var durable = await fixture.ReadAsync();
        Assert.NotNull(durable.Run);
        Assert.NotNull(durable.Checkpoint.Files);
        Assert.True(await fixture.Store.HasProtectedTransfersAsync(fixture.Task.Account, Token));
        fixture.AssertDrained();
    }

    [Theory]
    [InlineData("write")]
    [InlineData("flush")]
    [InlineData("hash")]
    public async Task NativeOperationFailureDrainsAndNeverPublishes(string operation)
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Destinations.File.FailOperation = operation;

        var result = await fixture.Runner.RunAsync(fixture.Task, Token);

        Assert.Equal(TransferPhase.Failed, result.Checkpoint.Phase);
        Assert.Equal(0, fixture.Destinations.File.PublishCalls);
        Assert.Equal(1, fixture.Destinations.File.DeleteCalls);
        AssertSafe(result.Checkpoint.Error!, fixture);
        fixture.AssertDrained();
    }

    [Fact]
    public async Task ServerChecksumUsesItsOwnBudgetRatherThanShortControlCallTimeout()
    {
        await using var fixture = await Fixture.CreateAsync(options: new() { BufferSizeBytes = 4, ControlCallTimeout = TimeSpan.FromMilliseconds(30) });
        fixture.Sources.BeforeChecksum = cancellationToken => Task.Delay(TimeSpan.FromMilliseconds(160), cancellationToken);

        var result = await fixture.Runner.RunAsync(fixture.Task, Token);

        Assert.Equal(TransferPhase.Completed, result.Checkpoint.Phase);
        fixture.AssertDrained();
    }

    [Fact]
    public async Task LeaseDisposeFailureStillReleasesDestinationAndExecutionLockWithoutUndoingCompletedFile()
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Sources.MutateLease = lease => lease.FailDispose = true;

        await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.RunAsync(fixture.Task, Token).WaitAsync(TimeSpan.FromSeconds(5), Token));

        var durable = await fixture.ReadAsync();
        Assert.Equal(TransferPhase.Completed, durable.Checkpoint.Phase);
        Assert.Equal(0, fixture.Destinations.File.DeleteCalls);
        fixture.AssertDrained();
        await fixture.Runner.StopAndDrainAsync(Token).WaitAsync(TimeSpan.FromSeconds(5), Token);
    }

    [Fact]
    public async Task CleanupFailureDuringDrainReturnsProtectedUnknownWithoutLeakingOrDeadlocking()
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Destinations.File.FailOperation = "delete";
        var body = fixture.BlockBody();
        var running = fixture.Runner.RunAsync(fixture.Task, Token);
        await body.Entered.Task.WaitAsync(Token);

        await fixture.Runner.StopAndDrainAsync(Token).WaitAsync(TimeSpan.FromSeconds(5), Token);

        var stopped = await running;
        Assert.Equal(TransferIntent.Cancel, stopped.Intent);
        Assert.Equal(TransferPhase.OutcomeUnknown, stopped.Checkpoint.Phase);
        Assert.Equal(TransferUncertainty.LocalPublish, stopped.Checkpoint.Uncertainty);
        Assert.True(await fixture.Store.HasProtectedTransfersAsync(fixture.Task.Account, Token));
        Assert.True(body.Disposed);
        Assert.Equal(0, fixture.Destinations.File.PublishCalls);
        fixture.AssertDrained();
    }

    [Fact]
    public async Task CancelIntentStorageFailureCannotPreventStopAndDrainFromReleasingActiveResources()
    {
        await using var fixture = await Fixture.CreateAsync();
        var body = fixture.BlockBody(checkDurableCancel: false);
        var running = fixture.Runner.RunAsync(fixture.Task, Token);
        await body.Entered.Task.WaitAsync(Token);
        fixture.Store.BeforeIntent = (_, _, _, _) => Task.FromException(new FilewayException(new(ErrorCode.LocalStorageUnavailable, "fixture intent storage failure")));

        var error = await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.StopAndDrainAsync(Token).WaitAsync(TimeSpan.FromSeconds(5), Token));

        Assert.Equal(ErrorCode.LocalStorageUnavailable, error.Error.Code);
        await Assert.ThrowsAsync<FilewayException>(() => running);
        Assert.True(body.CancellationObserved);
        Assert.True(body.Disposed);
        Assert.Equal(0, fixture.Destinations.File.PublishCalls);
        Assert.True(await fixture.Store.HasProtectedTransfersAsync(fixture.Task.Account, Token));
        fixture.AssertDrained();
    }

    [Theory]
    [InlineData("lock")]
    [InlineData("prepare")]
    [InlineData("create")]
    public async Task SynchronouslyBlockedProviderCannotBlockRunCallerOrDurableCancelAndMustDrain(string stage)
    {
        await using var fixture = await Fixture.CreateAsync();
        using var release = new ManualResetEventSlim();
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var cancelPersisted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        Action<CancellationToken> block = _ =>
        {
            entered.TrySetResult();
            if (!release.Wait(TimeSpan.FromSeconds(10), CancellationToken.None)) throw new TimeoutException("bounded synchronous fixture wait expired");
        };
        switch (stage)
        {
            case "lock": fixture.Locks.BeforeAcquire = block; break;
            case "prepare": fixture.Sources.BeforePrepare = block; break;
            case "create": fixture.Destinations.BeforeCreate = block; break;
        }
        fixture.Store.AfterIntent = record =>
        {
            if (record.Intent == TransferIntent.Cancel) cancelPersisted.TrySetResult();
        };
        // The outer task distinguishes returning RunAsync from waiting for its asynchronous result.
        var invocation = Task.Factory.StartNew(() => fixture.Runner.RunAsync(fixture.Task, Token), Token,
            TaskCreationOptions.DenyChildAttach, TaskScheduler.Default);
        Task<TransferTaskRecord>? running = null;
        Task? draining = null;
        try
        {
            running = await invocation.WaitAsync(TimeSpan.FromSeconds(3), Token);
            await entered.Task.WaitAsync(TimeSpan.FromSeconds(3), Token);
            Assert.False(running.IsCompleted);
            draining = fixture.Runner.StopAndDrainAsync(Token);
            await cancelPersisted.Task.WaitAsync(TimeSpan.FromSeconds(3), Token);
            Assert.Equal(TransferIntent.Cancel, (await fixture.ReadAsync()).Intent);
            Assert.False(draining.IsCompleted);
        }
        finally { release.Set(); }

        await draining!.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var canceled = await running!.WaitAsync(TimeSpan.FromSeconds(5), Token);
        Assert.Equal(TransferPhase.Canceled, canceled.Checkpoint.Phase);
        Assert.Equal(TransferIntent.Cancel, canceled.Intent);
        Assert.Null(canceled.Run);
        Assert.Empty(fixture.Http.Requests);
        if (stage == "create") Assert.Equal(1, fixture.Destinations.File.DeleteCalls);
        fixture.AssertDrained();
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task DelayedPreAdmissionReadCannotStartAfterNoopDrainOrCallerCancellation(bool cancelCaller)
    {
        await using var fixture = await Fixture.CreateAsync();
        using var caller = CancellationTokenSource.CreateLinkedTokenSource(Token);
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var readReturned = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        int held = 0;
        fixture.Store.IgnoreReadCancellation = true;
        fixture.Store.BeforeRead = async (task, _) =>
        {
            if (task == fixture.Task && Interlocked.CompareExchange(ref held, 1, 0) == 0)
            {
                entered.TrySetResult();
                await release.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
                readReturned.TrySetResult();
            }
        };
        var original = await fixture.Store.Inner.GetTransferAsync(fixture.Task, Token);
        var running = fixture.Runner.RunAsync(fixture.Task, caller.Token);
        try
        {
            await entered.Task.WaitAsync(TimeSpan.FromSeconds(3), Token);
            if (cancelCaller) await caller.CancelAsync();
            else await fixture.Runner.StopAndDrainAsync(Token).WaitAsync(TimeSpan.FromSeconds(3), Token);
        }
        finally { release.TrySetResult(); }

        if (cancelCaller)
            await Assert.ThrowsAnyAsync<OperationCanceledException>(() => running.WaitAsync(TimeSpan.FromSeconds(5), Token));
        else
        {
            var error = await Assert.ThrowsAsync<FilewayException>(() => running.WaitAsync(TimeSpan.FromSeconds(5), Token));
            Assert.Equal(ErrorCode.SourceChanged, error.Error.Code);
        }
        await readReturned.Task.WaitAsync(TimeSpan.FromSeconds(3), Token);
        Assert.Equal(original, await fixture.ReadAsync());
        Assert.Equal(0, fixture.Sources.PrepareCalls);
        Assert.Equal(0, fixture.Destinations.CreateCalls);
        Assert.Empty(fixture.Sources.Acquired);
        Assert.Empty(fixture.Http.Requests);
        fixture.AssertDrained();
    }

    [Theory]
    [InlineData("stop")]
    [InlineData("cancel")]
    [InlineData("dispose")]
    public async Task ThrowingCancellationCallbackStillDrainsAndReportsSafeFailure(string action)
    {
        var fixture = await Fixture.CreateAsync();
        try
        {
            var body = fixture.BlockBody();
            body.ThrowOnCancellation = true;
            var running = fixture.Runner.RunAsync(fixture.Task, Token);
            await body.Entered.Task.WaitAsync(TimeSpan.FromSeconds(3), Token);

            Task stopping = action switch
            {
                "stop" => fixture.Runner.StopAndDrainAsync(Token),
                "cancel" => fixture.Runner.CancelAsync(fixture.Task, Token),
                "dispose" => fixture.Runner.DisposeAsync().AsTask(),
                _ => throw new InvalidOperationException(),
            };
            var error = await Assert.ThrowsAsync<FilewayException>(() => stopping.WaitAsync(TimeSpan.FromSeconds(5), Token));

            AssertSafe(error.Error, fixture);
            Assert.DoesNotContain("private callback failure", error.Message, StringComparison.Ordinal);
            var canceled = await running.WaitAsync(TimeSpan.FromSeconds(5), Token);
            Assert.Equal(TransferIntent.Cancel, canceled.Intent);
            Assert.Equal(TransferPhase.Canceled, canceled.Checkpoint.Phase);
            Assert.Null(canceled.Run);
            Assert.True(body.CancellationObserved);
            Assert.True(body.Disposed);
            Assert.Equal(1, fixture.Destinations.File.DeleteCalls);
            Assert.Equal(0, fixture.Destinations.File.PublishCalls);
            fixture.AssertDrained();
        }
        finally
        {
            if (action == "dispose")
                await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.DisposeAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(5), Token));
            else await fixture.DisposeAsync();
        }
    }

    [Fact]
    public async Task UnknownOwnedCreateResultRemainsProtectedAfterDurableCancelInsteadOfBecomingCanceled()
    {
        await using var fixture = await Fixture.CreateAsync();
        using var release = new ManualResetEventSlim();
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var cancelPersisted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        fixture.Destinations.BeforeCreate = _ =>
        {
            entered.TrySetResult();
            if (!release.Wait(TimeSpan.FromSeconds(10), CancellationToken.None)) throw new TimeoutException("bounded create wait expired");
        };
        fixture.Destinations.CreateFailure = new FilewayException(new(ErrorCode.OutcomeUnknown, "private create result unknown"));
        fixture.Store.AfterIntent = record =>
        {
            if (record.Intent == TransferIntent.Cancel) cancelPersisted.TrySetResult();
        };
        var running = fixture.Runner.RunAsync(fixture.Task, Token);
        Task? draining = null;
        try
        {
            await entered.Task.WaitAsync(TimeSpan.FromSeconds(3), Token);
            draining = fixture.Runner.StopAndDrainAsync(Token);
            await cancelPersisted.Task.WaitAsync(TimeSpan.FromSeconds(3), Token);
            Assert.False(draining.IsCompleted);
        }
        finally { release.Set(); }

        await draining!.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var unknown = await running.WaitAsync(TimeSpan.FromSeconds(5), Token);
        Assert.Equal(TransferIntent.Cancel, unknown.Intent);
        Assert.Equal(TransferPhase.OutcomeUnknown, unknown.Checkpoint.Phase);
        Assert.Equal(TransferUncertainty.LocalPublish, unknown.Checkpoint.Uncertainty);
        Assert.True(await fixture.Store.HasProtectedTransfersAsync(fixture.Task.Account, Token));
        Assert.Empty(fixture.Http.Requests);
        Assert.Empty(fixture.Sources.Acquired);
        fixture.AssertDrained();
    }

    private static void AssertSafe(ErrorInfo error, Fixture fixture)
    {
        Assert.DoesNotContain(fixture.Plan.LocalPath, error.Message, StringComparison.Ordinal);
        Assert.DoesNotContain("private-version", error.Message, StringComparison.Ordinal);
        Assert.DoesNotContain("private.example", error.Message, StringComparison.Ordinal);
        Assert.DoesNotContain("127.0.0.1", error.Message, StringComparison.Ordinal);
    }

    private sealed class Fixture : IAsyncDisposable
    {
        private Fixture(TransferPlan plan, FaultStore store, byte[] bytes, FileDownloadRunnerOptions options)
        {
            Plan = plan;
            HttpTask = plan.Reference;
            Store = store;
            Sources = new(this, bytes);
            Destinations = new(plan.LocalPath);
            Http = new(this, bytes);
            Runner = new(store, Sources, Destinations, Locks, Http, options);
        }
        internal TransferPlan Plan { get; }
        internal TransferTaskRef Task => Plan.Reference;
        internal TransferTaskRef HttpTask { get; set; }
        internal FaultStore Store { get; }
        internal FakeSources Sources { get; }
        internal FakeDestinations Destinations { get; }
        internal FakeLocks Locks { get; } = new();
        internal DownloadHandler Http { get; }
        internal FileDownloadRunner Runner { get; }
        internal static async Task<Fixture> CreateAsync(byte[]? data = null, FileDownloadRunnerOptions? options = null,
            Func<TransferPlan, TransferPlan>? mutatePlan = null)
        {
            var inner = new InMemoryProfileStore();
            var saved = new SavedConnection(new(Guid.NewGuid(), Guid.NewGuid(), "download runner fixture", new("https://fixture.invalid/"),
                NetworkMode.SystemNetwork, new(ProxyMode.Direct)), AuthenticationMode.Password, "fixture", "7", false, DateTimeOffset.UtcNow);
            await inner.SaveAsync(saved, Token);
            var plan = new TransferPlan(new(saved.Account, Guid.NewGuid()), TransferDirection.Download,
                new(saved.Account, "/fixture.bin", "fixture.bin", "/fixture.bin"), @"C:\synthetic-download\fixture.bin");
            plan = mutatePlan?.Invoke(plan) ?? plan;
            await inner.CreateTransferAsync(plan, Token);
            return new(plan, new(inner), data ?? Data, options ?? new() { BufferSizeBytes = 4 });
        }
        internal async Task<TransferTaskRecord> ReadAsync() => Assert.IsType<TransferTaskRecord>(await Store.GetTransferAsync(Task, Token));
        internal BlockingReadStream BlockBody(bool checkDurableCancel = true)
        {
            var stream = new BlockingReadStream(async () =>
            {
                if (checkDurableCancel) Assert.Equal(TransferIntent.Cancel, (await ReadAsync()).Intent);
            });
            Http.ContentFactory = () => new StreamContent(stream);
            return stream;
        }
        internal void AssertDrained()
        {
            Assert.All(Sources.Issued, lease => Assert.True(lease.Disposed));
            if (Destinations.CreateCalls != 0) Assert.True(Destinations.File.Disposed);
            Assert.False(Locks.Held);
        }
        public ValueTask DisposeAsync() => Runner.DisposeAsync();
    }

    private sealed class FaultStore(InMemoryProfileStore inner) : ITransferTaskStore
    {
        internal InMemoryProfileStore Inner { get; } = inner;
        internal Func<TransferRunToken, long, TransferCheckpoint, bool, CancellationToken, Task>? BeforeSave { get; set; }
        internal Func<TransferTaskRef, long, TransferIntent, CancellationToken, Task>? BeforeIntent { get; set; }
        internal Action<TransferTaskRecord>? AfterIntent { get; set; }
        internal Func<TransferTaskRef, CancellationToken, Task>? BeforeRead { get; set; }
        internal bool IgnoreReadCancellation { get; set; }
        public bool IsPersistent => false;
        public Task<TransferTaskRecord> CreateTransferAsync(TransferPlan plan, CancellationToken cancellationToken) => Inner.CreateTransferAsync(plan, cancellationToken);
        public async Task<TransferTaskRecord?> GetTransferAsync(TransferTaskRef task, CancellationToken cancellationToken)
        {
            if (BeforeRead is { } before) await before(task, cancellationToken);
            return await Inner.GetTransferAsync(task, IgnoreReadCancellation ? CancellationToken.None : cancellationToken);
        }
        public Task<TransferTaskPage> ListTransfersAsync(AccountKey account, string? cursor, int limit, CancellationToken cancellationToken) => Inner.ListTransfersAsync(account, cursor, limit, cancellationToken);
        public Task<bool> HasProtectedTransfersAsync(AccountKey account, CancellationToken cancellationToken) => Inner.HasProtectedTransfersAsync(account, cancellationToken);
        public async Task<TransferTaskRecord> SetTransferIntentAsync(TransferTaskRef task, long expectedRevision, TransferIntent intent, CancellationToken cancellationToken)
        {
            if (BeforeIntent is { } before) await before(task, expectedRevision, intent, cancellationToken);
            var record = await Inner.SetTransferIntentAsync(task, expectedRevision, intent, cancellationToken);
            AfterIntent?.Invoke(record);
            return record;
        }
        public Task<TransferTaskRecord> ClaimTransferAsync(TransferTaskRef task, long expectedRevision, Guid runId, bool recovery, CancellationToken cancellationToken) => Inner.ClaimTransferAsync(task, expectedRevision, runId, recovery, cancellationToken);
        public async Task<TransferTaskRecord> SaveTransferCheckpointAsync(TransferRunToken run, long expectedRevision, TransferCheckpoint checkpoint, bool releaseRun, CancellationToken cancellationToken)
        {
            if (BeforeSave is { } before) await before(run, expectedRevision, checkpoint, releaseRun, cancellationToken);
            return await Inner.SaveTransferCheckpointAsync(run, expectedRevision, checkpoint, releaseRun, cancellationToken);
        }
    }

    private sealed class FakeSources(Fixture fixture, byte[] bytes) : IDownloadSourceProvider
    {
        internal int PrepareCalls;
        internal ConcurrentQueue<DownloadLeaseRequest> Acquired { get; } = new();
        internal ConcurrentQueue<DownloadLeaseRequest> Checksums { get; } = new();
        internal ConcurrentQueue<FakeLease> Issued { get; } = new();
        internal Action<FakeLease>? MutateLease { get; set; }
        internal Func<RemoteFile, RemoteFile>? MutateMetadata { get; set; }
        internal Func<FileChecksumResult, FileChecksumResult>? MutateChecksum { get; set; }
        internal Func<CancellationToken, Task>? BeforeChecksum { get; set; }
        internal Action<CancellationToken>? BeforePrepare { get; set; }
        public Task<RemoteFile> PrepareDownloadAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Interlocked.Increment(ref PrepareCalls);
            BeforePrepare?.Invoke(cancellationToken);
            var file = new RemoteFile(resource, false, bytes.LongLength, Modified);
            return Task.FromResult(MutateMetadata?.Invoke(file) ?? file);
        }
        public Task<IDownloadLease> AcquireDownloadAsync(DownloadLeaseRequest request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Acquired.Enqueue(request);
            Assert.Equal(fixture.Plan.Remote, request.Source);
            Assert.Equal(bytes.LongLength, request.Length);
            Assert.Null(request.Source.SourceVersion);
            var lease = new FakeLease(request);
            MutateLease?.Invoke(lease);
            Issued.Enqueue(lease);
            return Task.FromResult<IDownloadLease>(lease);
        }
        public async Task<FileChecksumResult> CalculateDownloadChecksumAsync(DownloadLeaseRequest request, CancellationToken cancellationToken)
        {
            Checksums.Enqueue(request);
            if (BeforeChecksum is { } before) await before(cancellationToken);
            var checksum = new FileChecksumResult(request.Source, ChecksumAlgorithm.Sha256, Hash(bytes), ChecksumComparison.NotRequested, bytes.LongLength, Modified);
            return MutateChecksum?.Invoke(checksum) ?? checksum;
        }
    }

    private sealed class FakeLease(DownloadLeaseRequest request) : IDownloadLease
    {
        internal bool Disposed { get; private set; }
        internal bool FailDispose { get; set; }
        public RemoteResourceRef Resource { get; set; } = request.Source;
        public Uri Address { get; set; } = new("http://127.0.0.1:32450/stream/private-download-capability");
        public long Length { get; set; } = request.Length;
        public string? SourceVersion { get; set; }
        public bool SupportsResume { get; set; }
        public ValueTask DisposeAsync()
        {
            Disposed = true;
            return FailDispose ? ValueTask.FromException(new FilewayException(new(ErrorCode.HostExited, "fixture revoke failure"))) : ValueTask.CompletedTask;
        }
    }

    private sealed class FakeDestinations(string finalPath) : IDownloadDestinationProvider
    {
        internal FakeDestination File { get; private set; } = new(finalPath);
        internal int CreateCalls;
        internal int OpenCalls;
        internal Exception? OpenFailure { get; set; }
        internal Exception? CreateFailure { get; set; }
        internal Action<CancellationToken>? BeforeCreate { get; set; }
        public Task<IDownloadDestination> CreateFreshAsync(string path, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Assert.Equal(finalPath, path);
            BeforeCreate?.Invoke(cancellationToken);
            if (CreateFailure is { } failure) throw failure;
            if (Interlocked.Increment(ref CreateCalls) > 1) File = new(finalPath);
            return Task.FromResult<IDownloadDestination>(File);
        }
        public Task<IDownloadDestination> OpenOwnedAsync(string path, TransferWorkFiles files, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Assert.Equal(finalPath, path);
            Assert.Equal(File.Files.PartialIdentity, files.PartialIdentity);
            Interlocked.Increment(ref OpenCalls);
            if (OpenFailure is { } failure) throw failure;
            File.Disposed = false;
            return Task.FromResult<IDownloadDestination>(File);
        }
    }

    private sealed class FakeDestination(string finalPath) : IDownloadDestination
    {
        private readonly List<byte> _bytes = [];
        internal byte[] Bytes => _bytes.ToArray();
        internal bool Disposed { get; set; }
        internal int DeleteCalls;
        internal int PublishCalls;
        internal ConcurrentQueue<string> Events { get; } = new();
        internal string? FailOperation { get; set; }
        internal Func<DownloadLocalFileHash, DownloadLocalFileHash>? MutateHash { get; set; }
        internal Func<CancellationToken, Task>? BeforePublish { get; set; }
        internal Func<CancellationToken, Task>? AfterPublish { get; set; }
        internal bool PublishUnknown { get; set; }
        internal bool PublishDespiteUnknown { get; set; }
        public TransferWorkFiles Files { get; private set; } = new(finalPath + ".owned-partial", "fixture-volume:partial-1");
        public bool IsPublished { get; private set; }
        public long Length => _bytes.Count;
        public Task WriteAsync(ReadOnlyMemory<byte> bytes, CancellationToken cancellationToken)
        {
            Check("write", cancellationToken);
            Assert.False(IsPublished);
            Events.Enqueue("write");
            _bytes.AddRange(bytes.ToArray());
            return Task.CompletedTask;
        }
        public Task<long> FlushToDiskAsync(CancellationToken cancellationToken)
        {
            Check("flush", cancellationToken);
            Events.Enqueue("flush");
            return Task.FromResult(Length);
        }
        public Task<DownloadLocalFileHash> CalculateSha256Async(CancellationToken cancellationToken)
        {
            Check("hash", cancellationToken);
            Events.Enqueue("hash");
            var hash = new DownloadLocalFileHash(Length, Hash(Bytes));
            return Task.FromResult(MutateHash?.Invoke(hash) ?? hash);
        }
        public async Task<string> PublishNoReplaceAsync(CancellationToken cancellationToken)
        {
            Check("publish", cancellationToken);
            Assert.False(IsPublished);
            Interlocked.Increment(ref PublishCalls);
            Events.Enqueue("publish");
            if (BeforePublish is { } before) await before(cancellationToken);
            if (!PublishUnknown || PublishDespiteUnknown)
            {
                IsPublished = true;
                Files = Files with { PublishedFileIdentity = Files.PartialIdentity };
            }
            if (AfterPublish is { } after) await after(cancellationToken);
            if (PublishUnknown) throw new IOException("private rename result unknown");
            return Files.PublishedFileIdentity!;
        }
        public Task DeleteOwnedPartialAsync(CancellationToken cancellationToken)
        {
            Check("delete", cancellationToken);
            Assert.False(IsPublished);
            Interlocked.Increment(ref DeleteCalls);
            return Task.CompletedTask;
        }
        private void Check(string operation, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Assert.False(Disposed);
            if (FailOperation == operation) throw new IOException(finalPath);
        }
        public ValueTask DisposeAsync() { Disposed = true; return ValueTask.CompletedTask; }
    }

    private sealed class FakeLocks : ITransferExecutionLockProvider
    {
        private int _held;
        internal bool Held => Volatile.Read(ref _held) != 0;
        internal Action<CancellationToken>? BeforeAcquire { get; set; }
        public Task<ITransferExecutionLock> AcquireAsync(TransferTaskRef task, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            BeforeAcquire?.Invoke(cancellationToken);
            if (Interlocked.CompareExchange(ref _held, 1, 0) != 0) throw new FilewayException(new(ErrorCode.Busy, "fixture lock busy"));
            return Task.FromResult<ITransferExecutionLock>(new FakeLock(task, () => Interlocked.Exchange(ref _held, 0)));
        }
    }

    private sealed class FakeLock(TransferTaskRef task, Action release) : ITransferExecutionLock
    {
        private int _disposed;
        public TransferTaskRef Task => task;
        public ValueTask DisposeAsync() { if (Interlocked.Exchange(ref _disposed, 1) == 0) release(); return ValueTask.CompletedTask; }
    }

    private sealed class DownloadHandler(Fixture fixture, byte[] bytes) : HttpMessageHandler
    {
        internal ConcurrentQueue<string> Requests { get; } = new();
        internal Action<HttpResponseMessage>? Mutate { get; set; }
        internal Func<HttpContent>? ContentFactory { get; set; }
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Assert.Equal(HttpMethod.Get, request.Method);
            Assert.Null(request.Headers.Range);
            Assert.Empty(request.Headers.IfMatch);
            Assert.Empty(request.Headers.IfNoneMatch);
            Assert.Null(request.Headers.IfRange);
            Assert.Null(request.Headers.IfUnmodifiedSince);
            Assert.Null(request.Content);
            var durable = await fixture.Store.GetTransferAsync(fixture.HttpTask, cancellationToken);
            Assert.NotNull(durable);
            Assert.NotNull(durable.Run);
            Assert.Equal(TransferPhase.Running, durable.Checkpoint.Phase);
            Assert.Equal(0, durable.Checkpoint.ConfirmedOffset);
            Assert.NotNull(durable.Checkpoint.Files);
            Assert.Equal(new TransferSourceSnapshot(bytes.LongLength, Modified), durable.Checkpoint.Source);
            Requests.Enqueue(request.Method.Method);
            var response = new HttpResponseMessage(HttpStatusCode.OK) { Content = ContentFactory?.Invoke() ?? new ByteArrayContent(bytes) };
            Mutate?.Invoke(response);
            return response;
        }
    }

    private sealed class BlockingReadStream(Func<Task> checkCancellation) : Stream
    {
        internal TaskCompletionSource Entered { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal bool CancellationObserved { get; private set; }
        internal bool Disposed { get; private set; }
        internal bool ThrowOnCancellation { get; set; }
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            // Register the cooperative delay first so the later throwing callback runs before its
            // cancellation continuation can dispose that callback registration.
            var delay = Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
            using var registration = ThrowOnCancellation
                ? cancellationToken.Register(() => throw new InvalidOperationException("private callback failure"))
                : default;
            Entered.TrySetResult();
            try { await delay; }
            catch (OperationCanceledException) { await checkCancellation(); CancellationObserved = true; throw; }
            throw new InvalidOperationException("unreachable fixture");
        }
        protected override void Dispose(bool disposing) { Disposed = true; base.Dispose(disposing); }
        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override void Flush() => throw new NotSupportedException();
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }
}
