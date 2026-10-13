using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;
using Fileway.Infrastructure.Tests.Persistence;
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
    [InlineData(ErrorCode.Conflict)]
    [InlineData(ErrorCode.PermissionDenied)]
    [InlineData(ErrorCode.Busy)]
    public async Task FreshDestinationFailureCannotAcquireRemoteCapabilityOrMutateFileCleanup(ErrorCode code)
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Destinations.CreateFailure = new FilewayException(new(code,
            "private create detail: " + fixture.Plan.LocalPath));
        int createAttempts = 0;
        int bodiesCreated = 0;
        fixture.Destinations.BeforeCreate = _ => Interlocked.Increment(ref createAttempts);
        fixture.Http.ContentFactory = () =>
        {
            Interlocked.Increment(ref bodiesCreated);
            return new ByteArrayContent(Data);
        };
        TransferWorkFiles untouchedFiles = fixture.Destinations.File.Files;

        var result = await fixture.Runner.RunAsync(fixture.Task, Token);

        Assert.Equal(code == ErrorCode.Conflict ? TransferPhase.Conflict : TransferPhase.Failed, result.Checkpoint.Phase);
        Assert.Equal(code, result.Checkpoint.Error!.Code);
        AssertSafe(result.Checkpoint.Error, fixture);
        Assert.Null(result.Run);
        Assert.Null(result.Checkpoint.Files);
        Assert.Null(result.Checkpoint.LocalSha256);
        Assert.Null(result.Checkpoint.ServerSha256);
        Assert.Null(result.Checkpoint.ConfirmedOffset);
        Assert.Equal(TransferUncertainty.None, result.Checkpoint.Uncertainty);
        Assert.Equal(result, await fixture.ReadAsync());
        Assert.Equal(1, fixture.Sources.PrepareCalls);
        Assert.Equal(1, createAttempts);
        Assert.Equal(0, fixture.Destinations.CreateCalls);
        Assert.Equal(0, fixture.Destinations.OpenCalls);
        Assert.Empty(fixture.Sources.Acquired);
        Assert.Empty(fixture.Sources.Issued);
        Assert.Empty(fixture.Sources.Checksums);
        Assert.Empty(fixture.Http.Requests);
        Assert.Equal(0, bodiesCreated);
        Assert.Equal(untouchedFiles, fixture.Destinations.File.Files);
        Assert.Empty(fixture.Destinations.File.Bytes);
        Assert.Empty(fixture.Destinations.File.Events);
        Assert.Equal(0, fixture.Destinations.File.PublishCalls);
        Assert.Equal(0, fixture.Destinations.File.DeleteAttempts);
        Assert.Equal(0, fixture.Destinations.File.DisposeCalls);
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

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task HandlerDisposeFailureSettlesSharedCompletionAfterIdleOrActiveDrain(bool active)
    {
        // Do not await fixture disposal in a finally: the original regression leaves that exact
        // shared task permanently pending. The bounded assertion still lets the worker drain.
        var fixture = await Fixture.CreateAsync();
        fixture.Http.DisposeFailure = new InvalidOperationException("private handler disposal detail: " + fixture.Plan.LocalPath);
        BlockingReadStream? body = null;
        Task<TransferTaskRecord>? running = null;
        if (active)
        {
            body = fixture.BlockBody();
            running = fixture.Runner.RunAsync(fixture.Task, Token);
            await body.Entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        }
        fixture.Http.BeforeDispose = fixture.AssertDrained;

        var first = fixture.Runner.DisposeAsync().AsTask();
        var concurrent = fixture.Runner.DisposeAsync().AsTask();
        Assert.Same(first, concurrent);
        var error = await Assert.ThrowsAsync<FilewayException>(() => first.WaitAsync(TimeSpan.FromSeconds(5), Token));
        AssertDisposeSafe(error, fixture, ErrorCode.Unknown);
        Assert.Same(error, await Assert.ThrowsAsync<FilewayException>(() => concurrent.WaitAsync(TimeSpan.FromSeconds(5), Token)));
        var repeated = fixture.Runner.DisposeAsync().AsTask();
        Assert.Same(first, repeated);
        Assert.Same(error, await Assert.ThrowsAsync<FilewayException>(() => repeated.WaitAsync(TimeSpan.FromSeconds(5), Token)));
        Assert.Equal(1, fixture.Http.DisposeCalls);
        fixture.AssertDrained();
        if (active)
        {
            var canceled = await running!.WaitAsync(TimeSpan.FromSeconds(5), Token);
            Assert.Equal(TransferIntent.Cancel, canceled.Intent);
            Assert.Equal(TransferPhase.Canceled, canceled.Checkpoint.Phase);
            Assert.Null(canceled.Run);
            Assert.True(body!.CancellationObserved);
            Assert.True(body.Disposed);
            Assert.Equal(1, fixture.Destinations.File.DeleteCalls);
            Assert.Equal(0, fixture.Destinations.File.PublishCalls);
        }
        else
        {
            var queued = await fixture.ReadAsync();
            Assert.Equal(TransferIntent.Run, queued.Intent);
            Assert.Equal(TransferPhase.Queued, queued.Checkpoint.Phase);
            Assert.Equal(0, fixture.Sources.PrepareCalls);
            Assert.Equal(0, fixture.Destinations.CreateCalls);
            Assert.Empty(fixture.Http.Requests);
        }
        await Assert.ThrowsAsync<ObjectDisposedException>(() => fixture.Runner.RunAsync(fixture.Task, Token));
    }

    [Theory]
    [InlineData("intent", ErrorCode.LocalStorageUnavailable)]
    [InlineData("callback", ErrorCode.Unknown)]
    public async Task HandlerDisposeFailureCannotReplaceEarlierDrainFailure(string failure, ErrorCode expected)
    {
        var fixture = await Fixture.CreateAsync();
        var body = fixture.BlockBody(checkDurableCancel: failure != "intent");
        body.ThrowOnCancellation = failure == "callback";
        fixture.Http.DisposeFailure = new FilewayException(new(ErrorCode.HostExited, "private handler disposal detail"));
        fixture.Http.BeforeDispose = fixture.AssertDrained;
        var running = fixture.Runner.RunAsync(fixture.Task, Token);
        await body.Entered.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        if (failure == "intent")
            fixture.Store.BeforeIntent = (_, _, _, _) => Task.FromException(new FilewayException(new(ErrorCode.LocalStorageUnavailable, "private intent storage detail")));

        var first = fixture.Runner.DisposeAsync().AsTask();
        var concurrent = fixture.Runner.DisposeAsync().AsTask();
        Assert.Same(first, concurrent);
        var error = await Assert.ThrowsAsync<FilewayException>(() => first.WaitAsync(TimeSpan.FromSeconds(5), Token));
        AssertDisposeSafe(error, fixture, expected);
        Assert.Same(error, await Assert.ThrowsAsync<FilewayException>(() => concurrent.WaitAsync(TimeSpan.FromSeconds(5), Token)));
        var repeated = fixture.Runner.DisposeAsync().AsTask();
        Assert.Same(first, repeated);
        Assert.Same(error, await Assert.ThrowsAsync<FilewayException>(() => repeated.WaitAsync(TimeSpan.FromSeconds(5), Token)));
        Assert.Equal(1, fixture.Http.DisposeCalls);
        Assert.True(body.CancellationObserved);
        Assert.True(body.Disposed);
        Assert.Equal(0, fixture.Destinations.File.PublishCalls);
        fixture.AssertDrained();
        if (failure == "intent")
        {
            var runError = await Assert.ThrowsAsync<FilewayException>(() => running.WaitAsync(TimeSpan.FromSeconds(5), Token));
            AssertDisposeSafe(runError, fixture, ErrorCode.LocalStorageUnavailable);
            Assert.True(await fixture.Store.HasProtectedTransfersAsync(fixture.Task.Account, Token));
        }
        else
        {
            var canceled = await running.WaitAsync(TimeSpan.FromSeconds(5), Token);
            Assert.Equal(TransferIntent.Cancel, canceled.Intent);
            Assert.Equal(TransferPhase.Canceled, canceled.Checkpoint.Phase);
            Assert.Null(canceled.Run);
            Assert.Equal(1, fixture.Destinations.File.DeleteCalls);
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

    [Theory]
    [InlineData(false, false)]
    [InlineData(false, true)]
    [InlineData(true, false)]
    [InlineData(true, true)]
    public async Task LateCreatedPartialCleanupFailurePreservesExactOwnedEvidenceAfterDurableCancel(bool outcomeUnknown, bool stop)
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Destinations.File.DeleteFailure = outcomeUnknown
            ? new FilewayException(new(ErrorCode.OutcomeUnknown, "private rejected delete detail: " + fixture.Plan.LocalPath))
            : new IOException("private rejected delete detail: " + fixture.Plan.LocalPath);
        fixture.Store.BeforeSave = async (_, _, checkpoint, release, _) =>
        {
            if (checkpoint.Phase != TransferPhase.OutcomeUnknown) return;
            var durable = await fixture.ReadAsync();
            Assert.NotNull(durable.Run);
            Assert.Equal(fixture.Destinations.File.Files, durable.Checkpoint.Files);
            Assert.Equal(TransferUncertainty.LocalPublish, durable.Checkpoint.Uncertainty);
            Assert.True(release);
        };

        var pending = await ReleaseLateCreateAfterCancelAsync(fixture, stop);
        await pending.Control.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var unknown = await pending.Running.WaitAsync(TimeSpan.FromSeconds(5), Token);

        await AssertRejectedCreateUnknownAsync(fixture, unknown, pending.Claimed);
        Assert.Equal(unknown, await fixture.ReadAsync());
        var replay = await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.RunAsync(fixture.Task, Token));
        Assert.Equal(ErrorCode.InvalidRequest, replay.Error.Code);
        Assert.Equal(unknown, await fixture.ReadAsync());
        Assert.Equal(1, fixture.Destinations.CreateCalls);
        Assert.Equal(1, fixture.Destinations.File.DeleteAttempts);
        Assert.Equal(1, fixture.Destinations.File.DisposeCalls);
        fixture.AssertDrained();
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task LateCreatedPartialCleanupFailureAfterCallerCancellationPersistsCancelWithExactOwnedUnknown(bool outcomeUnknown)
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Destinations.File.DeleteFailure = outcomeUnknown
            ? new FilewayException(new(ErrorCode.OutcomeUnknown, "private rejected delete detail: " + fixture.Plan.LocalPath))
            : new IOException("private rejected delete detail: " + fixture.Plan.LocalPath);
        int evidenceBeforeCancel = 0;
        int unknownAfterCancel = 0;
        fixture.Store.BeforeSave = async (_, _, checkpoint, release, _) =>
        {
            if (checkpoint.Files is null) return;
            var durable = await fixture.ReadAsync();
            if (checkpoint.Phase == TransferPhase.OutcomeUnknown)
            {
                Assert.Equal(TransferIntent.Cancel, durable.Intent);
                Assert.Equal(fixture.Destinations.File.Files, durable.Checkpoint.Files);
                Assert.Equal(TransferUncertainty.LocalPublish, durable.Checkpoint.Uncertainty);
                Assert.True(release);
                Interlocked.Increment(ref unknownAfterCancel);
            }
            else
            {
                Assert.Equal(TransferIntent.Run, durable.Intent);
                Assert.NotNull(durable.Run);
                Assert.Null(durable.Checkpoint.Files);
                Assert.Equal(TransferUncertainty.None, durable.Checkpoint.Uncertainty);
                Assert.Equal(fixture.Destinations.File.Files, checkpoint.Files);
                Assert.Equal(TransferUncertainty.LocalPublish, checkpoint.Uncertainty);
                Assert.False(release);
                Interlocked.Increment(ref evidenceBeforeCancel);
            }
        };

        var pending = await ReleaseLateCreateAfterCancelAsync(fixture, stop: false, callerCancellation: true);
        await pending.Control.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var unknown = await pending.Running.WaitAsync(TimeSpan.FromSeconds(5), Token);

        Assert.Equal(TransferIntent.Run, pending.CanceledBeforeRelease.Intent);
        await AssertRejectedCreateUnknownAsync(fixture, unknown, pending.Claimed);
        Assert.Equal(unknown, await fixture.ReadAsync());
        Assert.Equal(1, evidenceBeforeCancel);
        Assert.Equal(1, unknownAfterCancel);
        Assert.Equal(1, fixture.Destinations.File.DeleteAttempts);
        Assert.Equal(1, fixture.Destinations.File.DisposeCalls);
        fixture.AssertDrained();
    }

    [Theory]
    [InlineData("same-final")]
    [InlineData("cross-parent")]
    [InlineData("blank-identity")]
    public async Task LateCreatedInvalidPartialEvidenceCannotAuthorizeDeletionOrBecomeDurableOwnedFiles(string invalid)
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Destinations.File.Files = invalid switch
        {
            "same-final" => fixture.Destinations.File.Files with { PartialPath = fixture.Plan.LocalPath },
            "cross-parent" => fixture.Destinations.File.Files with { PartialPath = @"C:\private-other-parent\fixture.owned-partial" },
            "blank-identity" => fixture.Destinations.File.Files with { PartialIdentity = " " },
            _ => throw new InvalidOperationException(),
        };

        var pending = await ReleaseLateCreateAfterCancelAsync(fixture, stop: true);
        await pending.Control.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var unknown = await pending.Running.WaitAsync(TimeSpan.FromSeconds(5), Token);

        Assert.Equal(TransferIntent.Cancel, unknown.Intent);
        Assert.Equal(TransferPhase.OutcomeUnknown, unknown.Checkpoint.Phase);
        Assert.Equal(TransferUncertainty.LocalPublish, unknown.Checkpoint.Uncertainty);
        Assert.Null(unknown.Run);
        Assert.Null(unknown.Checkpoint.Files);
        Assert.Equal(pending.Claimed.Generation, unknown.Generation);
        Assert.Equal(pending.Claimed.Checkpoint.Source, unknown.Checkpoint.Source);
        Assert.Equal(unknown, await fixture.ReadAsync());
        Assert.True(await fixture.Store.HasProtectedTransfersAsync(fixture.Task.Account, Token));
        AssertSafe(unknown.Checkpoint.Error!, fixture);
        Assert.Equal(1, fixture.Destinations.CreateCalls);
        Assert.Equal(0, fixture.Destinations.OpenCalls);
        Assert.Equal(0, fixture.Destinations.File.DeleteAttempts);
        Assert.Equal(0, fixture.Destinations.File.DeleteCalls);
        Assert.Equal(1, fixture.Destinations.File.DisposeCalls);
        Assert.Equal(0, fixture.Destinations.File.PublishCalls);
        Assert.Empty(fixture.Http.Requests);
        Assert.Empty(fixture.Sources.Acquired);
        Assert.Empty(fixture.Sources.Checksums);
        fixture.AssertDrained();
    }

    [Fact]
    public async Task ReopenedSqliteRetainsLateCreatedPartialEvidenceAndReconcilesOnlyMatchingLocalIdentity()
    {
        using var directory = new StorageTestDirectory();
        await using var fixture = await Fixture.CreateAsync(store: new SqliteProfileStore(directory.DatabasePath));
        fixture.Destinations.File.DeleteFailure = new IOException("private rejected delete detail: " + fixture.Plan.LocalPath);
        var pending = await ReleaseLateCreateAfterCancelAsync(fixture, stop: true);
        await pending.Control.WaitAsync(TimeSpan.FromSeconds(5), Token);
        var unknown = await pending.Running.WaitAsync(TimeSpan.FromSeconds(5), Token);
        await AssertRejectedCreateUnknownAsync(fixture, unknown, pending.Claimed);
        fixture.AssertDrained();
        await fixture.Runner.DisposeAsync();

        var reopened = new SqliteProfileStore(directory.DatabasePath);
        var durable = Assert.IsType<TransferTaskRecord>(await reopened.GetTransferAsync(fixture.Task, Token));
        Assert.Equal(unknown, durable);
        Assert.Equal(unknown.Generation, durable.Generation);
        Assert.Equal(unknown.Checkpoint.Source, durable.Checkpoint.Source);
        Assert.Equal(unknown.Checkpoint.Files, durable.Checkpoint.Files);
        Assert.Null(durable.Run);
        Assert.True(await reopened.HasProtectedTransfersAsync(fixture.Task.Account, Token));
        var localHttp = new DownloadHandler(fixture, Data);
        await using var restarted = new FileDownloadRunner(reopened, fixture.Sources, fixture.Destinations,
            fixture.Locks, localHttp, new() { BufferSizeBytes = 4 });
        fixture.Destinations.File.DeleteFailure = null;
        fixture.Destinations.MutateOpenedFiles = files => files with { PartialIdentity = "fixture-volume:replaced" };

        var mismatched = await restarted.ReconcileAsync(fixture.Task, Token);

        Assert.Equal(TransferPhase.OutcomeUnknown, mismatched.Checkpoint.Phase);
        Assert.Equal(TransferUncertainty.LocalPublish, mismatched.Checkpoint.Uncertainty);
        Assert.Equal(durable.Checkpoint.Files, mismatched.Checkpoint.Files);
        Assert.Equal(1, fixture.Destinations.File.DeleteAttempts);
        Assert.Equal(0, fixture.Destinations.File.DeleteCalls);
        Assert.True(await reopened.HasProtectedTransfersAsync(fixture.Task.Account, Token));
        fixture.AssertDrained();
        fixture.Destinations.File.Files = durable.Checkpoint.Files!;
        fixture.Destinations.MutateOpenedFiles = null;

        var reconciled = await restarted.ReconcileAsync(fixture.Task, Token);

        Assert.Equal(TransferIntent.Cancel, reconciled.Intent);
        Assert.Equal(TransferPhase.Canceled, reconciled.Checkpoint.Phase);
        Assert.Equal(TransferUncertainty.None, reconciled.Checkpoint.Uncertainty);
        Assert.Null(reconciled.Run);
        Assert.Equal(durable.Checkpoint.Source, reconciled.Checkpoint.Source);
        Assert.Equal(durable.Checkpoint.Files, reconciled.Checkpoint.Files);
        Assert.True(reconciled.Generation > durable.Generation);
        Assert.Equal(reconciled, await new SqliteProfileStore(directory.DatabasePath).GetTransferAsync(fixture.Task, Token));
        Assert.False(await reopened.HasProtectedTransfersAsync(fixture.Task.Account, Token));
        Assert.Equal(1, fixture.Destinations.CreateCalls);
        Assert.Equal(2, fixture.Destinations.OpenCalls);
        Assert.Equal(2, fixture.Destinations.File.DeleteAttempts);
        Assert.Equal(1, fixture.Destinations.File.DeleteCalls);
        Assert.Equal(0, fixture.Destinations.File.PublishCalls);
        Assert.Equal(1, fixture.Sources.PrepareCalls);
        Assert.Empty(fixture.Sources.Acquired);
        Assert.Empty(fixture.Sources.Checksums);
        Assert.Empty(fixture.Http.Requests);
        Assert.Empty(localHttp.Requests);
        fixture.AssertDrained();
    }

    [Fact]
    public async Task LateCreatedPartialDeleteAndDisposeFailuresPreserveOwnedUnknownAndReportSafeReleaseFailure()
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Destinations.File.DeleteFailure = new IOException("private rejected delete detail: " + fixture.Plan.LocalPath);
        fixture.Destinations.File.DisposeFailure = new FilewayException(new(ErrorCode.HostExited,
            "private destination release detail: " + fixture.Plan.LocalPath));

        var pending = await ReleaseLateCreateAfterCancelAsync(fixture, stop: true);
        var drainError = await Assert.ThrowsAsync<FilewayException>(() => pending.Control.WaitAsync(TimeSpan.FromSeconds(5), Token));
        var runError = await Assert.ThrowsAsync<FilewayException>(() => pending.Running.WaitAsync(TimeSpan.FromSeconds(5), Token));

        AssertDisposeSafe(drainError, fixture, ErrorCode.HostExited);
        AssertDisposeSafe(runError, fixture, ErrorCode.HostExited);
        Assert.DoesNotContain("private destination release detail", drainError.ToString(), StringComparison.Ordinal);
        Assert.DoesNotContain("private destination release detail", runError.ToString(), StringComparison.Ordinal);
        await AssertRejectedCreateUnknownAsync(fixture, await fixture.ReadAsync(), pending.Claimed);
        Assert.Equal(1, fixture.Destinations.File.DeleteAttempts);
        Assert.Equal(1, fixture.Destinations.File.DisposeCalls);
        fixture.AssertDrained();
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task LateCreatedPartialEvidenceSaveFailureRetainsProtectedActiveRunWithoutRetryOrReplacement(bool releaseFails)
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Destinations.File.DeleteFailure = new IOException("private rejected delete detail: " + fixture.Plan.LocalPath);
        if (releaseFails) fixture.Destinations.File.DisposeFailure = new FilewayException(new(ErrorCode.HostExited,
            "private destination release detail: " + fixture.Plan.LocalPath));
        int evidenceSaveAttempts = 0;
        fixture.Store.BeforeSave = (_, _, checkpoint, _, _) =>
        {
            if (checkpoint.Files is null) return Task.CompletedTask;
            Interlocked.Increment(ref evidenceSaveAttempts);
            return Task.FromException(new FilewayException(new(ErrorCode.LocalStorageUnavailable,
                "private evidence storage detail: " + fixture.Plan.LocalPath)));
        };

        var pending = await ReleaseLateCreateAfterCancelAsync(fixture, stop: true);
        var drainError = await Assert.ThrowsAsync<FilewayException>(() => pending.Control.WaitAsync(TimeSpan.FromSeconds(5), Token));
        var runError = await Assert.ThrowsAsync<FilewayException>(() => pending.Running.WaitAsync(TimeSpan.FromSeconds(5), Token));

        AssertDisposeSafe(drainError, fixture, ErrorCode.LocalStorageUnavailable);
        AssertDisposeSafe(runError, fixture, ErrorCode.LocalStorageUnavailable);
        Assert.DoesNotContain("private evidence storage detail", runError.ToString(), StringComparison.Ordinal);
        Assert.DoesNotContain("private destination release detail", runError.ToString(), StringComparison.Ordinal);
        var durable = await fixture.ReadAsync();
        Assert.Equal(pending.CanceledBeforeRelease, durable);
        Assert.Equal(TransferIntent.Cancel, durable.Intent);
        Assert.Equal(pending.Claimed.Run, durable.Run);
        Assert.NotNull(durable.Run);
        Assert.Equal(pending.Claimed.Generation, durable.Generation);
        Assert.Equal(pending.Claimed.Checkpoint.Source, durable.Checkpoint.Source);
        Assert.Null(durable.Checkpoint.Files);
        Assert.True(await fixture.Store.HasProtectedTransfersAsync(fixture.Task.Account, Token));
        Assert.Equal(1, evidenceSaveAttempts);
        var replay = await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.RunAsync(fixture.Task, Token));
        Assert.Equal(ErrorCode.InvalidRequest, replay.Error.Code);
        Assert.Equal(durable, await fixture.ReadAsync());
        Assert.Equal(1, fixture.Destinations.CreateCalls);
        Assert.Equal(1, fixture.Destinations.File.DeleteAttempts);
        Assert.Equal(0, fixture.Destinations.File.DeleteCalls);
        Assert.Equal(1, fixture.Destinations.File.DisposeCalls);
        Assert.Equal(0, fixture.Destinations.File.PublishCalls);
        Assert.Empty(fixture.Http.Requests);
        Assert.Empty(fixture.Sources.Acquired);
        Assert.Empty(fixture.Sources.Checksums);
        fixture.AssertDrained();
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task LateCreatedPartialEvidenceSaveConflictThenReadFailureRetainsExactProtectedRunAndStorageFailure(bool releaseFails)
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Destinations.File.DeleteFailure = new IOException("private rejected delete detail: " + fixture.Plan.LocalPath);
        if (releaseFails) fixture.Destinations.File.DisposeFailure = new FilewayException(new(ErrorCode.HostExited,
            "private destination release detail: " + fixture.Plan.LocalPath));
        int evidenceSaveAttempts = 0;
        int releaseSaveAttempts = 0;
        int failNextRead = 0;
        int readFailures = 0;
        fixture.Store.BeforeRead = (_, _) =>
        {
            if (Interlocked.CompareExchange(ref failNextRead, 0, 1) != 1) return Task.CompletedTask;
            Interlocked.Increment(ref readFailures);
            return Task.FromException(new FilewayException(new(ErrorCode.LocalStorageUnavailable,
                "private evidence read detail: " + fixture.Plan.LocalPath)));
        };
        fixture.Store.BeforeSave = (_, _, checkpoint, release, _) =>
        {
            if (release) Interlocked.Increment(ref releaseSaveAttempts);
            if (checkpoint.Files is null) return Task.CompletedTask;
            Interlocked.Increment(ref evidenceSaveAttempts);
            Assert.Equal(0, Interlocked.Exchange(ref failNextRead, 1));
            return Task.FromException(new FilewayException(new(ErrorCode.Conflict,
                "private evidence conflict detail: " + fixture.Plan.LocalPath)));
        };

        var pending = await ReleaseLateCreateAfterCancelAsync(fixture, stop: true);
        var drainError = await Assert.ThrowsAsync<FilewayException>(() => pending.Control.WaitAsync(TimeSpan.FromSeconds(5), Token));
        var runError = await Assert.ThrowsAsync<FilewayException>(() => pending.Running.WaitAsync(TimeSpan.FromSeconds(5), Token));

        AssertDisposeSafe(drainError, fixture, ErrorCode.LocalStorageUnavailable);
        AssertDisposeSafe(runError, fixture, ErrorCode.LocalStorageUnavailable);
        Assert.DoesNotContain("private evidence read detail", runError.ToString(), StringComparison.Ordinal);
        Assert.DoesNotContain("private evidence conflict detail", runError.ToString(), StringComparison.Ordinal);
        Assert.DoesNotContain("private destination release detail", runError.ToString(), StringComparison.Ordinal);
        Assert.Equal(1, evidenceSaveAttempts);
        Assert.Equal(1, readFailures);
        Assert.Equal(0, Volatile.Read(ref failNextRead));
        Assert.Equal(0, releaseSaveAttempts);
        var durable = await fixture.ReadAsync();
        Assert.Equal(pending.CanceledBeforeRelease, durable);
        Assert.Equal(TransferIntent.Cancel, durable.Intent);
        Assert.Equal(pending.Claimed.Run, durable.Run);
        Assert.NotNull(durable.Run);
        Assert.Equal(pending.Claimed.Generation, durable.Generation);
        Assert.Equal(pending.Claimed.Checkpoint.Source, durable.Checkpoint.Source);
        Assert.Null(durable.Checkpoint.Files);
        Assert.True(await fixture.Store.HasProtectedTransfersAsync(fixture.Task.Account, Token));
        var replay = await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.RunAsync(fixture.Task, Token));
        Assert.Equal(ErrorCode.InvalidRequest, replay.Error.Code);
        Assert.Equal(durable, await fixture.ReadAsync());
        Assert.Equal(1, fixture.Destinations.CreateCalls);
        Assert.Equal(0, fixture.Destinations.OpenCalls);
        Assert.Equal(1, fixture.Destinations.File.DeleteAttempts);
        Assert.Equal(0, fixture.Destinations.File.DeleteCalls);
        Assert.Equal(1, fixture.Destinations.File.DisposeCalls);
        Assert.Equal(0, fixture.Destinations.File.PublishCalls);
        Assert.Empty(fixture.Http.Requests);
        Assert.Empty(fixture.Sources.Acquired);
        Assert.Empty(fixture.Sources.Checksums);
        fixture.AssertDrained();
    }

    [Theory]
    [InlineData("stop")]
    [InlineData("cancel")]
    [InlineData("dispose")]
    [InlineData("caller")]
    public async Task LateAcquiredLeaseReleaseFailureRemainsObservableAfterDurableCancellationAndPartialCleanup(string action)
    {
        var fixture = await Fixture.CreateAsync();
        fixture.Sources.MutateLease = lease => lease.DisposeFailure = new FilewayException(new(ErrorCode.HostExited,
            "private rejected lease release detail: " + fixture.Plan.LocalPath + " " + lease.Address));
        if (action == "dispose") fixture.Http.BeforeDispose = fixture.AssertDrained;

        var pending = await ReleaseLateLeaseAfterCancellationAsync(fixture, action);
        FilewayException? controlError = null;
        if (action == "caller")
            await pending.Control.WaitAsync(TimeSpan.FromSeconds(5), Token);
        else
        {
            controlError = await Assert.ThrowsAsync<FilewayException>(() => pending.Control.WaitAsync(TimeSpan.FromSeconds(5), Token));
            AssertRejectedLeaseReleaseSafe(controlError, fixture, ErrorCode.HostExited);
            fixture.AssertDrained();
        }
        var runError = await Assert.ThrowsAsync<FilewayException>(() => pending.Running.WaitAsync(TimeSpan.FromSeconds(5), Token));
        AssertRejectedLeaseReleaseSafe(runError, fixture, ErrorCode.HostExited);
        await AssertRejectedLeaseCanceledAsync(fixture, pending.Claimed);
        if (action == "dispose")
        {
            var repeated = fixture.Runner.DisposeAsync().AsTask();
            Assert.Same(pending.Control, repeated);
            Assert.Same(controlError, await Assert.ThrowsAsync<FilewayException>(() => repeated.WaitAsync(TimeSpan.FromSeconds(5), Token)));
            Assert.Equal(1, fixture.Http.DisposeCalls);
        }
        else await fixture.DisposeAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(5), Token);
    }

    [Fact]
    public async Task LateAcquiredLeaseInterruptedReleaseReportsUnknownWithoutRetryingCancellationOrStartingGet()
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Sources.MutateLease = lease => lease.DisposeFailure = new OperationCanceledException(
            "private rejected lease release detail: " + fixture.Plan.LocalPath + " " + lease.Address, Token);

        var pending = await ReleaseLateLeaseAfterCancellationAsync(fixture, "stop");
        var drainError = await Assert.ThrowsAsync<FilewayException>(() => pending.Control.WaitAsync(TimeSpan.FromSeconds(5), Token));
        AssertRejectedLeaseReleaseSafe(drainError, fixture, ErrorCode.Unknown);
        fixture.AssertDrained();
        var runError = await Assert.ThrowsAsync<FilewayException>(() => pending.Running.WaitAsync(TimeSpan.FromSeconds(5), Token));
        AssertRejectedLeaseReleaseSafe(runError, fixture, ErrorCode.Unknown);
        await AssertRejectedLeaseCanceledAsync(fixture, pending.Claimed);
        var durable = await fixture.ReadAsync();
        Assert.Equal(durable, await fixture.Runner.RunAsync(fixture.Task, Token));
        AssertRejectedLeaseDrained(fixture, deleted: true);
    }

    [Fact]
    public async Task LateAcquiredLeaseReleaseAndPartialDeleteFailuresPreserveExactProtectedLocalUnknown()
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Sources.MutateLease = lease => lease.DisposeFailure = new FilewayException(new(ErrorCode.HostExited,
            "private rejected lease release detail: " + fixture.Plan.LocalPath + " " + lease.Address));
        fixture.Destinations.File.DeleteFailure = new IOException("private rejected lease delete detail: " + fixture.Plan.LocalPath);

        var pending = await ReleaseLateLeaseAfterCancellationAsync(fixture, "stop");
        var drainError = await Assert.ThrowsAsync<FilewayException>(() => pending.Control.WaitAsync(TimeSpan.FromSeconds(5), Token));
        AssertRejectedLeaseReleaseSafe(drainError, fixture, ErrorCode.HostExited);
        fixture.AssertDrained();
        var runError = await Assert.ThrowsAsync<FilewayException>(() => pending.Running.WaitAsync(TimeSpan.FromSeconds(5), Token));
        AssertRejectedLeaseReleaseSafe(runError, fixture, ErrorCode.HostExited);
        var durable = await fixture.ReadAsync();
        Assert.Equal(TransferIntent.Cancel, durable.Intent);
        Assert.Equal(TransferPhase.OutcomeUnknown, durable.Checkpoint.Phase);
        Assert.Equal(TransferUncertainty.LocalPublish, durable.Checkpoint.Uncertainty);
        Assert.Null(durable.Run);
        Assert.Equal(pending.Claimed.Generation, durable.Generation);
        Assert.Equal(pending.Claimed.Checkpoint.Source, durable.Checkpoint.Source);
        Assert.Equal(pending.Claimed.Checkpoint.Files, durable.Checkpoint.Files);
        Assert.Equal(fixture.Destinations.File.Files, durable.Checkpoint.Files);
        Assert.Equal(0, durable.Checkpoint.ConfirmedOffset);
        Assert.Null(durable.Checkpoint.Files!.PublishedFileIdentity);
        Assert.Equal(ErrorCode.LocalStorageUnavailable, durable.Checkpoint.Error!.Code);
        AssertSafe(durable.Checkpoint.Error, fixture);
        Assert.DoesNotContain("private rejected lease delete detail", durable.Checkpoint.Error.Message, StringComparison.Ordinal);
        Assert.True(await fixture.Store.HasProtectedTransfersAsync(fixture.Task.Account, Token));
        AssertRejectedLeaseDrained(fixture, deleted: false);
    }

    [Fact]
    public async Task LateAcquiredLeaseReleaseAndCanceledCheckpointSaveFailuresRetainExactProtectedActiveRunAndStoragePriority()
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Sources.MutateLease = lease => lease.DisposeFailure = new FilewayException(new(ErrorCode.HostExited,
            "private rejected lease release detail: " + fixture.Plan.LocalPath + " " + lease.Address));
        int terminalSaveAttempts = 0;
        fixture.Store.BeforeSave = (_, _, checkpoint, release, _) =>
        {
            if (checkpoint.Phase != TransferPhase.Canceled || !release) return Task.CompletedTask;
            Interlocked.Increment(ref terminalSaveAttempts);
            Assert.Equal(fixture.Destinations.File.Files, checkpoint.Files);
            Assert.Equal(TransferUncertainty.None, checkpoint.Uncertainty);
            Assert.Null(checkpoint.Error);
            return Task.FromException(new FilewayException(new(ErrorCode.LocalStorageUnavailable,
                "private rejected lease terminal storage detail: " + fixture.Plan.LocalPath)));
        };

        var pending = await ReleaseLateLeaseAfterCancellationAsync(fixture, "stop");
        var drainError = await Assert.ThrowsAsync<FilewayException>(() => pending.Control.WaitAsync(TimeSpan.FromSeconds(5), Token));
        AssertRejectedLeaseReleaseSafe(drainError, fixture, ErrorCode.LocalStorageUnavailable);
        fixture.AssertDrained();
        var runError = await Assert.ThrowsAsync<FilewayException>(() => pending.Running.WaitAsync(TimeSpan.FromSeconds(5), Token));
        AssertRejectedLeaseReleaseSafe(runError, fixture, ErrorCode.LocalStorageUnavailable);
        Assert.DoesNotContain("private rejected lease terminal storage detail", runError.ToString(), StringComparison.Ordinal);
        var durable = await fixture.ReadAsync();
        Assert.Equal(pending.CanceledBeforeRelease, durable);
        Assert.Equal(TransferIntent.Cancel, durable.Intent);
        Assert.Equal(TransferPhase.Running, durable.Checkpoint.Phase);
        Assert.Equal(TransferUncertainty.None, durable.Checkpoint.Uncertainty);
        Assert.Equal(pending.Claimed.Run, durable.Run);
        Assert.NotNull(durable.Run);
        Assert.Equal(pending.Claimed.Generation, durable.Generation);
        Assert.Equal(pending.Claimed.Checkpoint.Source, durable.Checkpoint.Source);
        Assert.Equal(pending.Claimed.Checkpoint.Files, durable.Checkpoint.Files);
        Assert.Equal(fixture.Destinations.File.Files, durable.Checkpoint.Files);
        Assert.True(await fixture.Store.HasProtectedTransfersAsync(fixture.Task.Account, Token));
        Assert.Equal(1, terminalSaveAttempts);
        var replay = await Assert.ThrowsAsync<FilewayException>(() => fixture.Runner.RunAsync(fixture.Task, Token));
        Assert.Equal(ErrorCode.InvalidRequest, replay.Error.Code);
        Assert.Equal(durable, await fixture.ReadAsync());
        AssertRejectedLeaseDrained(fixture, deleted: true);
    }

    private static async Task<(Task<TransferTaskRecord> Running, Task Control, TransferTaskRecord Claimed,
        TransferTaskRecord CanceledBeforeRelease)> ReleaseLateLeaseAfterCancellationAsync(Fixture fixture, string action)
    {
        using var release = new ManualResetEventSlim();
        using var caller = action == "caller" ? CancellationTokenSource.CreateLinkedTokenSource(Token) : null;
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var exited = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var cancelPersisted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var stopObserved = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        fixture.Sources.BeforeAcquire = cancellationToken =>
        {
            using var registration = cancellationToken.Register(() => stopObserved.TrySetResult());
            entered.TrySetResult();
            try
            {
                // Ignore cancellation until the test confirms that this actual provider token stopped.
                if (!release.Wait(TimeSpan.FromSeconds(10), CancellationToken.None))
                    throw new TimeoutException("bounded rejected lease wait expired");
                Assert.True(cancellationToken.IsCancellationRequested);
            }
            finally { exited.TrySetResult(); }
        };
        fixture.Store.AfterIntent = record =>
        {
            if (record.Intent == TransferIntent.Cancel) cancelPersisted.TrySetResult();
        };
        var running = fixture.Runner.RunAsync(fixture.Task, caller?.Token ?? Token);
        Task? control = null;
        TransferTaskRecord? claimed = null;
        TransferTaskRecord? canceled = null;
        try
        {
            await entered.Task.WaitAsync(TimeSpan.FromSeconds(3), Token);
            claimed = await fixture.ReadAsync();
            Assert.NotNull(claimed.Run);
            Assert.Equal(TransferPhase.Running, claimed.Checkpoint.Phase);
            Assert.Equal(fixture.Destinations.File.Files, claimed.Checkpoint.Files);
            Assert.Single(fixture.Sources.Acquired);
            Assert.Empty(fixture.Sources.Issued);
            control = action switch
            {
                "stop" => fixture.Runner.StopAndDrainAsync(Token),
                "cancel" => fixture.Runner.CancelAsync(fixture.Task, Token),
                "dispose" => fixture.Runner.DisposeAsync().AsTask(),
                "caller" => caller!.CancelAsync(),
                _ => throw new InvalidOperationException(),
            };
            if (action == "dispose") Assert.Same(control, fixture.Runner.DisposeAsync().AsTask());
            if (action != "caller") await cancelPersisted.Task.WaitAsync(TimeSpan.FromSeconds(3), Token);
            await stopObserved.Task.WaitAsync(TimeSpan.FromSeconds(3), Token);
            canceled = await fixture.ReadAsync();
            Assert.Equal(action == "caller" ? TransferIntent.Run : TransferIntent.Cancel, canceled.Intent);
            Assert.Equal(claimed.Run, canceled.Run);
            Assert.Equal(claimed.Checkpoint, canceled.Checkpoint);
            if (action != "caller") Assert.False(control.IsCompleted);
            Assert.False(running.IsCompleted);
            Assert.Empty(fixture.Sources.Issued);
            Assert.Empty(fixture.Http.Requests);
        }
        finally
        {
            release.Set();
            if (entered.Task.IsCompleted) await exited.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        }
        return (running, control!, claimed!, canceled!);
    }

    private static async Task AssertRejectedLeaseCanceledAsync(Fixture fixture, TransferTaskRecord claimed)
    {
        var durable = await fixture.ReadAsync();
        Assert.Equal(TransferIntent.Cancel, durable.Intent);
        Assert.Equal(TransferPhase.Canceled, durable.Checkpoint.Phase);
        Assert.Equal(TransferUncertainty.None, durable.Checkpoint.Uncertainty);
        Assert.Null(durable.Checkpoint.Error);
        Assert.Null(durable.Run);
        Assert.Equal(claimed.Generation, durable.Generation);
        Assert.Equal(claimed.Checkpoint.Source, durable.Checkpoint.Source);
        Assert.Equal(claimed.Checkpoint.Files, durable.Checkpoint.Files);
        Assert.Null(durable.Checkpoint.Files!.PublishedFileIdentity);
        Assert.Equal(0, durable.Checkpoint.ConfirmedOffset);
        Assert.False(await fixture.Store.HasProtectedTransfersAsync(fixture.Task.Account, Token));
        AssertRejectedLeaseDrained(fixture, deleted: true);
    }

    private static void AssertRejectedLeaseDrained(Fixture fixture, bool deleted)
    {
        var lease = Assert.Single(fixture.Sources.Issued);
        Assert.True(lease.Disposed);
        Assert.Equal(1, lease.DisposeCalls);
        Assert.Single(fixture.Sources.Acquired);
        Assert.Equal(1, fixture.Sources.PrepareCalls);
        Assert.Empty(fixture.Sources.Checksums);
        Assert.Empty(fixture.Http.Requests);
        Assert.Equal(1, fixture.Destinations.CreateCalls);
        Assert.Equal(0, fixture.Destinations.OpenCalls);
        Assert.Equal(1, fixture.Destinations.File.DeleteAttempts);
        Assert.Equal(deleted ? 1 : 0, fixture.Destinations.File.DeleteCalls);
        Assert.Equal(1, fixture.Destinations.File.DisposeCalls);
        Assert.Equal(0, fixture.Destinations.File.PublishCalls);
        Assert.Empty(fixture.Destinations.File.Bytes);
        fixture.AssertDrained();
    }

    private static void AssertRejectedLeaseReleaseSafe(FilewayException error, Fixture fixture, ErrorCode expected)
    {
        AssertDisposeSafe(error, fixture, expected);
        Assert.DoesNotContain("private rejected lease release detail", error.ToString(), StringComparison.Ordinal);
        Assert.DoesNotContain("private-download-capability", error.ToString(), StringComparison.Ordinal);
    }

    private static async Task<(Task<TransferTaskRecord> Running, Task Control, TransferTaskRecord Claimed,
        TransferTaskRecord CanceledBeforeRelease)> ReleaseLateCreateAfterCancelAsync(Fixture fixture, bool stop,
        bool callerCancellation = false)
    {
        using var release = new ManualResetEventSlim();
        using var caller = callerCancellation ? CancellationTokenSource.CreateLinkedTokenSource(Token) : null;
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var exited = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var cancelPersisted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var stopObserved = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        fixture.Destinations.BeforeCreate = cancellationToken =>
        {
            using var registration = cancellationToken.Register(() => stopObserved.TrySetResult());
            entered.TrySetResult();
            try
            {
                if (!release.Wait(TimeSpan.FromSeconds(10), CancellationToken.None))
                    throw new TimeoutException("bounded rejected create wait expired");
            }
            finally { exited.TrySetResult(); }
        };
        fixture.Store.AfterIntent = record =>
        {
            if (record.Intent == TransferIntent.Cancel) cancelPersisted.TrySetResult();
        };
        var running = fixture.Runner.RunAsync(fixture.Task, caller?.Token ?? Token);
        Task? control = null;
        TransferTaskRecord? claimed = null;
        TransferTaskRecord? canceled = null;
        try
        {
            await entered.Task.WaitAsync(TimeSpan.FromSeconds(3), Token);
            claimed = await fixture.ReadAsync();
            Assert.NotNull(claimed.Run);
            Assert.Null(claimed.Checkpoint.Files);
            control = callerCancellation ? caller!.CancelAsync()
                : stop ? fixture.Runner.StopAndDrainAsync(Token) : fixture.Runner.CancelAsync(fixture.Task, Token);
            if (!callerCancellation) await cancelPersisted.Task.WaitAsync(TimeSpan.FromSeconds(3), Token);
            await stopObserved.Task.WaitAsync(TimeSpan.FromSeconds(3), Token);
            canceled = await fixture.ReadAsync();
            Assert.Equal(callerCancellation ? TransferIntent.Run : TransferIntent.Cancel, canceled.Intent);
            Assert.Equal(claimed.Run, canceled.Run);
            if (!callerCancellation) Assert.False(control.IsCompleted);
            Assert.False(running.IsCompleted);
        }
        finally
        {
            release.Set();
            if (entered.Task.IsCompleted) await exited.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
        }
        return (running, control!, claimed!, canceled!);
    }

    private static async Task AssertRejectedCreateUnknownAsync(Fixture fixture, TransferTaskRecord unknown,
        TransferTaskRecord claimed)
    {
        Assert.Equal(TransferIntent.Cancel, unknown.Intent);
        Assert.Equal(TransferPhase.OutcomeUnknown, unknown.Checkpoint.Phase);
        Assert.Equal(TransferUncertainty.LocalPublish, unknown.Checkpoint.Uncertainty);
        Assert.Null(unknown.Run);
        Assert.Equal(claimed.Generation, unknown.Generation);
        Assert.Equal(claimed.Checkpoint.Source, unknown.Checkpoint.Source);
        Assert.Equal(new TransferSourceSnapshot(Data.LongLength, Modified), unknown.Checkpoint.Source);
        var files = Assert.IsType<TransferWorkFiles>(unknown.Checkpoint.Files);
        string partialPath = Assert.IsType<string>(files.PartialPath);
        Assert.Equal(fixture.Destinations.File.Files, files);
        Assert.Equal(fixture.Plan.LocalPath + ".owned-partial", partialPath);
        Assert.Equal("fixture-volume:partial-1", files.PartialIdentity);
        Assert.Equal(fixture.Plan.LocalPath[..fixture.Plan.LocalPath.LastIndexOf('\\')],
            partialPath[..partialPath.LastIndexOf('\\')]);
        Assert.Null(files.PublishedFileIdentity);
        Assert.True(await fixture.Store.HasProtectedTransfersAsync(fixture.Task.Account, Token));
        AssertSafe(unknown.Checkpoint.Error!, fixture);
        Assert.Equal(1, fixture.Destinations.CreateCalls);
        Assert.Equal(0, fixture.Destinations.OpenCalls);
        Assert.Equal(0, fixture.Destinations.File.DeleteCalls);
        Assert.Equal(0, fixture.Destinations.File.PublishCalls);
        Assert.Equal(1, fixture.Sources.PrepareCalls);
        Assert.Empty(fixture.Sources.Acquired);
        Assert.Empty(fixture.Sources.Checksums);
        Assert.Empty(fixture.Http.Requests);
        fixture.AssertDrained();
    }

    private static void AssertDisposeSafe(FilewayException error, Fixture fixture, ErrorCode expected)
    {
        Assert.Equal(expected, error.Error.Code);
        Assert.Null(error.InnerException);
        AssertSafe(error.Error, fixture);
        Assert.DoesNotContain("private handler disposal detail", error.ToString(), StringComparison.Ordinal);
        Assert.DoesNotContain("private callback failure", error.ToString(), StringComparison.Ordinal);
        Assert.DoesNotContain("private intent storage detail", error.ToString(), StringComparison.Ordinal);
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
            Func<TransferPlan, TransferPlan>? mutatePlan = null, ITransferTaskStore? store = null)
        {
            var inner = store ?? new InMemoryProfileStore();
            var saved = new SavedConnection(new(Guid.NewGuid(), Guid.NewGuid(), "download runner fixture", new("https://fixture.invalid/"),
                NetworkMode.SystemNetwork, new(ProxyMode.Direct)), AuthenticationMode.Password, "fixture", "7", false, DateTimeOffset.UtcNow);
            await ((IProfileStore)inner).SaveAsync(saved, Token);
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

    private sealed class FaultStore(ITransferTaskStore inner) : ITransferTaskStore
    {
        internal ITransferTaskStore Inner { get; } = inner;
        internal Func<TransferRunToken, long, TransferCheckpoint, bool, CancellationToken, Task>? BeforeSave { get; set; }
        internal Func<TransferTaskRef, long, TransferIntent, CancellationToken, Task>? BeforeIntent { get; set; }
        internal Action<TransferTaskRecord>? AfterIntent { get; set; }
        internal Func<TransferTaskRef, CancellationToken, Task>? BeforeRead { get; set; }
        internal bool IgnoreReadCancellation { get; set; }
        public bool IsPersistent => Inner.IsPersistent;
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
        internal Action<CancellationToken>? BeforeAcquire { get; set; }
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
            BeforeAcquire?.Invoke(cancellationToken);
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
        internal Exception? DisposeFailure { get; set; }
        internal int DisposeCalls;
        public RemoteResourceRef Resource { get; set; } = request.Source;
        public Uri Address { get; set; } = new("http://127.0.0.1:32450/stream/private-download-capability");
        public long Length { get; set; } = request.Length;
        public string? SourceVersion { get; set; }
        public bool SupportsResume { get; set; }
        public ValueTask DisposeAsync()
        {
            Interlocked.Increment(ref DisposeCalls);
            Disposed = true;
            if (DisposeFailure is { } failure) return ValueTask.FromException(failure);
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
        internal Func<TransferWorkFiles, TransferWorkFiles>? MutateOpenedFiles { get; set; }
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
            if (MutateOpenedFiles is { } mutate) File.Files = mutate(File.Files);
            return Task.FromResult<IDownloadDestination>(File);
        }
    }

    private sealed class FakeDestination(string finalPath) : IDownloadDestination
    {
        private readonly List<byte> _bytes = [];
        internal byte[] Bytes => _bytes.ToArray();
        internal bool Disposed { get; set; }
        internal int DisposeCalls;
        internal int DeleteAttempts;
        internal int DeleteCalls;
        internal int PublishCalls;
        internal ConcurrentQueue<string> Events { get; } = new();
        internal string? FailOperation { get; set; }
        internal Exception? DeleteFailure { get; set; }
        internal Exception? DisposeFailure { get; set; }
        internal Func<DownloadLocalFileHash, DownloadLocalFileHash>? MutateHash { get; set; }
        internal Func<CancellationToken, Task>? BeforePublish { get; set; }
        internal Func<CancellationToken, Task>? AfterPublish { get; set; }
        internal bool PublishUnknown { get; set; }
        internal bool PublishDespiteUnknown { get; set; }
        public TransferWorkFiles Files { get; internal set; } = new(finalPath + ".owned-partial", "fixture-volume:partial-1");
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
            Interlocked.Increment(ref DeleteAttempts);
            Check("delete", cancellationToken);
            Assert.False(IsPublished);
            if (DeleteFailure is { } failure) throw failure;
            Interlocked.Increment(ref DeleteCalls);
            return Task.CompletedTask;
        }
        private void Check(string operation, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Assert.False(Disposed);
            if (FailOperation == operation) throw new IOException(finalPath);
        }
        public ValueTask DisposeAsync()
        {
            Interlocked.Increment(ref DisposeCalls);
            Disposed = true;
            return DisposeFailure is { } failure ? ValueTask.FromException(failure) : ValueTask.CompletedTask;
        }
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
        internal Exception? DisposeFailure { get; set; }
        internal Action? BeforeDispose { get; set; }
        internal int DisposeCalls;
        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                Interlocked.Increment(ref DisposeCalls);
                BeforeDispose?.Invoke();
                if (DisposeFailure is { } failure) throw failure;
            }
            base.Dispose(disposing);
        }
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
