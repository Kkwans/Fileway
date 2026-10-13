using System.Reflection;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Transfers;

namespace Fileway.Infrastructure.Tests.Transfers;

public sealed partial class WindowsDownloadFileAccessTests
{
    [Fact]
    public async Task ProgressiveReaderUsesVerifiedWritesBeforeFlushAndNeverReturnsTemporaryEof()
    {
        using var fixture = new NativeFixture();
        await using var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token);
        var readable = Assert.IsAssignableFrom<IReadableDownloadDestination>(destination);
        TransferRunToken run = ReaderRun();
        await using var reader = await readable.AcquireReadLeaseAsync(run, Payload.Length, Token);
        Assert.Equal(run, reader.Run);
        Assert.Equal(Payload.Length, reader.TotalBytes);
        Assert.Equal(0, reader.ReadablePrefixBytes);
        byte[] firstBuffer = new byte[Payload.Length];
        Task<int> first = reader.ReadAsync(0, firstBuffer, Token).AsTask();
        Assert.False(first.IsCompleted);

        await destination.WriteAsync(Payload.AsMemory(0, 3), Token).WaitAsync(TimeSpan.FromSeconds(5), Token);
        Assert.Equal(3, await first.WaitAsync(TimeSpan.FromSeconds(5), Token));
        Assert.Equal(Payload.AsSpan(0, 3).ToArray(), firstBuffer.AsSpan(0, 3).ToArray());
        Assert.Equal(3, reader.ReadablePrefixBytes);
        byte[] tailBuffer = new byte[Payload.Length];
        Task<int> tail = reader.ReadAsync(3, tailBuffer, Token).AsTask();
        Assert.False(tail.IsCompleted);
        await destination.WriteAsync(Payload.AsMemory(3), Token).WaitAsync(TimeSpan.FromSeconds(5), Token);
        Assert.Equal(Payload.Length - 3, await tail.WaitAsync(TimeSpan.FromSeconds(5), Token));
        Assert.Equal(Payload.AsSpan(3).ToArray(), tailBuffer.AsSpan(0, Payload.Length - 3).ToArray());
        Assert.Equal(Payload.Length, reader.ReadablePrefixBytes);
        Assert.Equal(0, await reader.ReadAsync(Payload.Length, firstBuffer, Token));
        Assert.Equal(0, await reader.ReadAsync(0, Memory<byte>.Empty, Token));
        Assert.False(destination.IsPublished);
        Assert.False(File.Exists(fixture.FinalPath));
        AssertSafeDisplay(reader.ToString(), fixture.Root);
    }

    [Fact]
    public async Task CancelingAFuturePrefixReadOrQueuedReadDoesNotCancelItsWriter()
    {
        using var fixture = new NativeFixture();
        await using var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token);
        var readable = Assert.IsAssignableFrom<IReadableDownloadDestination>(destination);
        await using var reader = await readable.AcquireReadLeaseAsync(ReaderRun(), Payload.Length, Token);
        using var firstCancellation = CancellationTokenSource.CreateLinkedTokenSource(Token);
        Task<int> future = reader.ReadAsync(5, new byte[3], firstCancellation.Token).AsTask();
        Assert.False(future.IsCompleted);
        using var queuedCancellation = CancellationTokenSource.CreateLinkedTokenSource(Token);
        Task<int> queued = reader.ReadAsync(0, new byte[3], queuedCancellation.Token).AsTask();
        Assert.False(queued.IsCompleted);
        await queuedCancellation.CancelAsync();
        await AssertCanceledAsync(() => queued.WaitAsync(TimeSpan.FromSeconds(5), Token), queuedCancellation.Token);
        Assert.False(future.IsCompleted);
        await firstCancellation.CancelAsync();
        await AssertCanceledAsync(() => future.WaitAsync(TimeSpan.FromSeconds(5), Token), firstCancellation.Token);
        await destination.WriteAsync(Payload, Token);
        byte[] buffer = new byte[Payload.Length];
        Assert.Equal(Payload.Length, await reader.ReadAsync(0, buffer, Token));
        Assert.Equal(Payload, buffer);
        Assert.Equal(Payload.Length, await destination.FlushToDiskAsync(Token));
    }

    [Fact]
    public async Task CancelingWhileQueuedForOwnerIoIsPromptAndLeavesTheOwnedFileUsable()
    {
        using var fixture = new NativeFixture();
        await using var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token);
        await destination.WriteAsync(Payload, Token);
        var readable = Assert.IsAssignableFrom<IReadableDownloadDestination>(destination);
        await using var reader = await readable.AcquireReadLeaseAsync(ReaderRun(), Payload.Length, Token);
        SemaphoreSlim serial = DestinationSerial(destination);
        await serial.WaitAsync(Token);
        using var canceled = CancellationTokenSource.CreateLinkedTokenSource(Token);
        Task<int> read;
        try
        {
            read = reader.ReadAsync(0, new byte[Payload.Length], canceled.Token).AsTask();
            Assert.False(read.IsCompleted);
            await canceled.CancelAsync();
            await AssertCanceledAsync(() => read.WaitAsync(TimeSpan.FromSeconds(5), Token), canceled.Token);
        }
        finally { serial.Release(); }
        Assert.Equal(Payload.Length, await reader.ReadAsync(0, new byte[Payload.Length], Token));
        Assert.Equal(Payload.Length, await destination.FlushToDiskAsync(Token));
    }

    [Fact]
    public async Task DisposingAReaderWakesItsActiveAndQueuedCallsAndReleasesOnlyItsOwnSlot()
    {
        using var fixture = new NativeFixture();
        await using var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token);
        var readable = Assert.IsAssignableFrom<IReadableDownloadDestination>(destination);
        TransferRunToken run = ReaderRun();
        var reader = await readable.AcquireReadLeaseAsync(run, Payload.Length, Token);
        Task<int> active = reader.ReadAsync(5, new byte[3], Token).AsTask();
        Task<int> queued = reader.ReadAsync(0, new byte[3], Token).AsTask();
        Assert.False(active.IsCompleted);
        Assert.False(queued.IsCompleted);
        await Task.WhenAll(Enumerable.Range(0, 8).Select(_ => reader.DisposeAsync().AsTask()))
            .WaitAsync(TimeSpan.FromSeconds(5), Token);
        await AssertReaderUnknownAsync(() => active, fixture.Root);
        await AssertReaderUnknownAsync(() => queued, fixture.Root);
        await AssertReaderUnknownAsync(() => reader.ReadAsync(0, new byte[1], Token).AsTask(), fixture.Root);
        await using var replacement = await readable.AcquireReadLeaseAsync(run, Payload.Length, Token);
        await destination.WriteAsync(Payload, Token);
        byte[] buffer = new byte[Payload.Length];
        Assert.Equal(Payload.Length, await replacement.ReadAsync(0, buffer, Token));
        Assert.Equal(Payload, buffer);
    }

    [Fact]
    public async Task OwnerRevocationIsSingleFlightWakesEveryReaderAndPrecedesOwnedDeletion()
    {
        using var fixture = new NativeFixture();
        await using var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token);
        var readable = Assert.IsAssignableFrom<IReadableDownloadDestination>(destination);
        TransferRunToken run = ReaderRun();
        var readers = new List<IDownloadReadLease>();
        try
        {
            for (int index = 0; index < 4; index++) readers.Add(await readable.AcquireReadLeaseAsync(run, Payload.Length, Token));
            Task<int>[] reads = readers.Select(reader => reader.ReadAsync(4, new byte[1], Token).AsTask()).ToArray();
            Assert.All(reads, read => Assert.False(read.IsCompleted));
            await Task.WhenAll(Enumerable.Range(0, 8).Select(_ => readable.RevokeReadersAsync(Token)))
                .WaitAsync(TimeSpan.FromSeconds(5), Token);
            foreach (Task<int> read in reads) await AssertReaderUnknownAsync(() => read, fixture.Root);
            await AssertReaderUnknownAsync(() => readable.AcquireReadLeaseAsync(run, Payload.Length, Token), fixture.Root);
            await destination.WriteAsync(Payload, Token);
            await destination.DeleteOwnedPartialAsync(Token);
            Assert.False(File.Exists(destination.Files.PartialPath));
            Assert.Empty(Directory.GetFiles(fixture.DestinationDirectory));
        }
        finally { foreach (var reader in readers) await reader.DisposeAsync(); }
    }

    [Fact]
    public async Task DeleteOwnedPartialRevokesWaitingReadersWithoutNeedingRunnerCooperation()
    {
        using var fixture = new NativeFixture();
        await using var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token);
        var readable = Assert.IsAssignableFrom<IReadableDownloadDestination>(destination);
        await using var reader = await readable.AcquireReadLeaseAsync(ReaderRun(), Payload.Length, Token);
        Task<int> future = reader.ReadAsync(4, new byte[1], Token).AsTask();
        Assert.False(future.IsCompleted);
        await destination.DeleteOwnedPartialAsync(Token).WaitAsync(TimeSpan.FromSeconds(5), Token);
        await AssertReaderUnknownAsync(() => future, fixture.Root);
        Assert.False(File.Exists(destination.Files.PartialPath));
    }

    [Fact]
    public async Task UnpublishedWriterDisposalRevokesReadersAndPreservesTheReleasedPartial()
    {
        using var fixture = new NativeFixture();
        var access = new WindowsDownloadFileAccess();
        await using var destination = await access.CreateFreshAsync(fixture.FinalPath, Token);
        TransferWorkFiles files = destination.Files;
        var readable = Assert.IsAssignableFrom<IReadableDownloadDestination>(destination);
        await using var reader = await readable.AcquireReadLeaseAsync(ReaderRun(), Payload.Length, Token);
        await destination.WriteAsync(Payload.AsMemory(0, 3), Token);
        Task<int> future = reader.ReadAsync(4, new byte[1], Token).AsTask();
        await destination.DisposeAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(5), Token);
        await destination.DisposeAsync();
        await AssertReaderUnknownAsync(() => future, fixture.Root);
        Assert.Equal(Payload.AsSpan(0, 3).ToArray(), await File.ReadAllBytesAsync(files.PartialPath!, Token));
        Assert.False(File.Exists(fixture.FinalPath));
        await using var recovered = await access.OpenOwnedAsync(fixture.FinalPath, files, Token);
        await recovered.DeleteOwnedPartialAsync(Token);
    }

    [Fact]
    public async Task PublishedWriterDisposalRetainsTheSameExclusiveObjectAndParentPinsUntilLastReaderDisposal()
    {
        using var fixture = new NativeFixture();
        var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token);
        var readable = Assert.IsAssignableFrom<IReadableDownloadDestination>(destination);
        TransferRunToken run = ReaderRun();
        var first = await readable.AcquireReadLeaseAsync(run, Payload.Length, Token);
        var second = await readable.AcquireReadLeaseAsync(run, Payload.Length, Token);
        string nativeIdentity = destination.Files.PartialIdentity!;
        try
        {
            await destination.WriteAsync(Payload, Token);
            Assert.Equal(nativeIdentity, await destination.PublishNoReplaceAsync(Token));
            await destination.DisposeAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(5), Token);
            await destination.DisposeAsync();
            Assert.False(File.Exists(destination.Files.PartialPath));
            Assert.True(File.Exists(fixture.FinalPath));
            Assert.Throws<IOException>(() =>
            {
                using var unrelated = new FileStream(fixture.FinalPath, FileMode.Open, FileAccess.Read,
                    FileShare.ReadWrite | FileShare.Delete);
            });
            Assert.Throws<IOException>(() => Directory.Move(fixture.DestinationDirectory, Path.Combine(fixture.Root, "moved-output")));
            await AssertReaderUnknownAsync(() => readable.AcquireReadLeaseAsync(run, Payload.Length, Token), fixture.Root);
            // Rejected writer/admission operations cannot invalidate existing retained readers.
            await AssertReaderUnknownAsync(() => destination.FlushToDiskAsync(Token), fixture.Root);
            byte[] buffer = new byte[Payload.Length];
            Assert.Equal(Payload.Length, await first.ReadAsync(0, buffer, Token));
            Assert.Equal(Payload, buffer);
            Assert.Equal(0, await first.ReadAsync(Payload.Length, buffer, Token));
            await first.DisposeAsync();
            Assert.Throws<IOException>(() => File.Move(fixture.FinalPath, Path.Combine(fixture.DestinationDirectory, "moved.bin")));
            Assert.Equal(Payload.Length, await second.ReadAsync(0, buffer, Token));
        }
        finally
        {
            await first.DisposeAsync();
            await second.DisposeAsync();
            await destination.DisposeAsync();
        }
        Assert.Equal(Payload, await File.ReadAllBytesAsync(fixture.FinalPath, Token));
        Assert.Equal(nativeIdentity, (await SnapshotAsync(fixture, fixture.FinalPath)).NativeFileIdentity);
        string moved = Path.Combine(fixture.Root, "released-output");
        Directory.Move(fixture.DestinationDirectory, moved);
        Directory.Move(moved, fixture.DestinationDirectory);
    }

    [Fact]
    public async Task PublishedWriterDisposalDoesNotWaitForAReaderHoldingTheOwnerIoBarrier()
    {
        using var fixture = new NativeFixture();
        await using var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token);
        var readable = Assert.IsAssignableFrom<IReadableDownloadDestination>(destination);
        await using var reader = await readable.AcquireReadLeaseAsync(ReaderRun(), Payload.Length, Token);
        await destination.WriteAsync(Payload, Token);
        await destination.PublishNoReplaceAsync(Token);
        SemaphoreSlim serial = DestinationSerial(destination);
        await serial.WaitAsync(Token);
        Task<int> read;
        try
        {
            byte[] buffer = new byte[Payload.Length];
            read = reader.ReadAsync(0, buffer, Token).AsTask();
            Assert.False(read.IsCompleted);
            await destination.DisposeAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(5), Token);
            Assert.False(read.IsCompleted);
            Assert.Throws<IOException>(() => File.Delete(fixture.FinalPath));
            Assert.Throws<IOException>(() => Directory.Move(fixture.DestinationDirectory, Path.Combine(fixture.Root, "moved-output")));
        }
        finally { serial.Release(); }
        Assert.Equal(Payload.Length, await read.WaitAsync(TimeSpan.FromSeconds(5), Token));
        Assert.Equal(0, await reader.ReadAsync(Payload.Length, new byte[1], Token));
        await reader.DisposeAsync();
        Assert.Equal(Payload, await File.ReadAllBytesAsync(fixture.FinalPath, Token));
        await destination.DisposeAsync();
    }

    [Fact]
    public async Task ExplicitRevocationAfterPublishedWriterDisposalReleasesPinsWithoutDeletingTheFinal()
    {
        using var fixture = new NativeFixture();
        var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token);
        var readable = Assert.IsAssignableFrom<IReadableDownloadDestination>(destination);
        await using var reader = await readable.AcquireReadLeaseAsync(ReaderRun(), Payload.Length, Token);
        try
        {
            await destination.WriteAsync(Payload, Token);
            await destination.PublishNoReplaceAsync(Token);
            await destination.DisposeAsync();
            await readable.RevokeReadersAsync(Token).WaitAsync(TimeSpan.FromSeconds(5), Token);
            await AssertReaderUnknownAsync(() => reader.ReadAsync(0, new byte[1], Token).AsTask(), fixture.Root);
            Assert.Equal(Payload, await File.ReadAllBytesAsync(fixture.FinalPath, Token));
            string moved = Path.Combine(fixture.Root, "released-output");
            Directory.Move(fixture.DestinationDirectory, moved);
            Directory.Move(moved, fixture.DestinationDirectory);
        }
        finally { await destination.DisposeAsync(); }
    }

    [Fact]
    public async Task ReaderAdmissionBindsOneRunAndTotalAndEnforcesFourSlotsAndStrictReadBounds()
    {
        using var fixture = new NativeFixture();
        await using var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token);
        var readable = Assert.IsAssignableFrom<IReadableDownloadDestination>(destination);
        TransferRunToken run = ReaderRun();
        var readers = new List<IDownloadReadLease>();
        try
        {
            foreach (TransferRunToken invalid in new[] { default(TransferRunToken), run with { Id = Guid.Empty }, run with { Generation = 0 } })
            {
                var error = await Assert.ThrowsAsync<FilewayException>(() => readable.AcquireReadLeaseAsync(invalid, Payload.Length, Token));
                Assert.Equal(ErrorCode.InvalidRequest, error.Error.Code);
                AssertSafeFailure(error, fixture.Root);
            }
            var totalError = await Assert.ThrowsAsync<FilewayException>(() => readable.AcquireReadLeaseAsync(run, -1, Token));
            Assert.Equal(ErrorCode.InvalidRequest, totalError.Error.Code);
            for (int index = 0; index < 4; index++) readers.Add(await readable.AcquireReadLeaseAsync(run, Payload.Length, Token));
            var full = await Assert.ThrowsAsync<FilewayException>(() => readable.AcquireReadLeaseAsync(run, Payload.Length, Token));
            Assert.Equal(ErrorCode.Busy, full.Error.Code);
            foreach (Func<Task> mismatch in new Func<Task>[]
            {
                () => readable.AcquireReadLeaseAsync(run with { Id = Guid.NewGuid() }, Payload.Length, Token),
                () => readable.AcquireReadLeaseAsync(run with { Generation = run.Generation + 1 }, Payload.Length, Token),
                () => readable.AcquireReadLeaseAsync(run, Payload.Length + 1, Token),
            })
            {
                var error = await Assert.ThrowsAsync<FilewayException>(mismatch);
                Assert.Equal(ErrorCode.InvalidRequest, error.Error.Code);
            }
            IDownloadReadLease reader = readers[0];
            foreach ((long offset, int count) in new[] { (-1L, 1), ((long)Payload.Length + 1, 1), (0L, 128 * 1024 + 1) })
            {
                var error = await Assert.ThrowsAsync<FilewayException>(() => reader.ReadAsync(offset, new byte[count], Token).AsTask());
                Assert.Equal(ErrorCode.InvalidRequest, error.Error.Code);
                AssertSafeFailure(error, fixture.Root);
            }
            await readers[3].DisposeAsync();
            await using var replacement = await readable.AcquireReadLeaseAsync(run, Payload.Length, Token);
            await destination.WriteAsync(Payload, Token);
            Assert.Equal(Payload.Length, await replacement.ReadAsync(0, new byte[Payload.Length], Token));
        }
        finally { foreach (var reader in readers) await reader.DisposeAsync(); }
    }

    [Fact]
    public async Task ReadCallsAtTheMaximumBoundUseTheCallerBufferAndDoNotAdvanceWriterPosition()
    {
        using var fixture = new NativeFixture();
        byte[] expected = Enumerable.Range(0, 128 * 1024 + 17).Select(index => (byte)(index * 17 + 5)).ToArray();
        await using var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token);
        var readable = Assert.IsAssignableFrom<IReadableDownloadDestination>(destination);
        await using var reader = await readable.AcquireReadLeaseAsync(ReaderRun(), expected.Length, Token);
        await destination.WriteAsync(expected.AsMemory(0, 128 * 1024), Token);
        byte[] buffer = new byte[128 * 1024];
        Assert.Equal(buffer.Length, await reader.ReadAsync(0, buffer, Token));
        Assert.Equal(expected.AsSpan(0, buffer.Length).ToArray(), buffer);
        await destination.WriteAsync(expected.AsMemory(buffer.Length), Token);
        Assert.Equal(expected.Length, destination.Length);
        Array.Fill(buffer, (byte)0xee);
        Assert.Equal(17, await reader.ReadAsync(128 * 1024, buffer, Token));
        Assert.Equal(expected.AsSpan(128 * 1024).ToArray(), buffer.AsSpan(0, 17).ToArray());
        Assert.All(buffer.Skip(17), value => Assert.Equal((byte)0xee, value));
        Assert.Equal(new DownloadLocalFileHash(expected.Length, Sha256(expected)), await destination.CalculateSha256Async(Token));
    }

    [Fact]
    public async Task IncompletePublicationCannotTurnAPrefixIntoEofAndSuccessfulRenamePreservesReaderBytes()
    {
        using var fixture = new NativeFixture();
        await using var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token);
        var readable = Assert.IsAssignableFrom<IReadableDownloadDestination>(destination);
        await using var reader = await readable.AcquireReadLeaseAsync(ReaderRun(), Payload.Length, Token);
        await destination.WriteAsync(Payload.AsMemory(0, 3), Token);
        byte[] tail = new byte[5];
        Task<int> future = reader.ReadAsync(3, tail, Token).AsTask();
        var incomplete = await Assert.ThrowsAsync<FilewayException>(() => destination.PublishNoReplaceAsync(Token));
        Assert.Equal(ErrorCode.InvalidRequest, incomplete.Error.Code);
        Assert.False(destination.IsPublished);
        Assert.False(future.IsCompleted);
        await destination.WriteAsync(Payload.AsMemory(3), Token);
        await destination.PublishNoReplaceAsync(Token);
        Assert.Equal(5, await future.WaitAsync(TimeSpan.FromSeconds(5), Token));
        Assert.Equal(Payload.AsSpan(3).ToArray(), tail);
        byte[] all = new byte[Payload.Length];
        Assert.Equal(Payload.Length, await reader.ReadAsync(0, all, Token));
        Assert.Equal(Payload, all);
        Assert.Equal(0, await reader.ReadAsync(Payload.Length, all, Token));
    }

    [Fact]
    public async Task EmptyDeclaredDownloadCanPublishAndRetainAReaderAtItsOnlyEof()
    {
        using var fixture = new NativeFixture();
        var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token);
        var readable = Assert.IsAssignableFrom<IReadableDownloadDestination>(destination);
        await using var reader = await readable.AcquireReadLeaseAsync(ReaderRun(), 0, Token);
        try
        {
            Assert.Equal(0, await reader.ReadAsync(0, new byte[1], Token));
            await destination.PublishNoReplaceAsync(Token);
            await destination.DisposeAsync();
            Assert.Equal(0, await reader.ReadAsync(0, new byte[1], Token));
        }
        finally { await destination.DisposeAsync(); }
    }

    [Fact]
    public async Task HistoricalOwnedReopenNeverGrantsAnActiveDownloadReader()
    {
        using var fixture = new NativeFixture();
        var access = new WindowsDownloadFileAccess();
        TransferWorkFiles files;
        await using (var destination = await access.CreateFreshAsync(fixture.FinalPath, Token))
        {
            await destination.WriteAsync(Payload, Token);
            files = destination.Files;
        }
        await using var historical = await access.OpenOwnedAsync(fixture.FinalPath, files, Token);
        var readable = Assert.IsAssignableFrom<IReadableDownloadDestination>(historical);
        await AssertReaderUnknownAsync(() => readable.AcquireReadLeaseAsync(ReaderRun(), Payload.Length, Token), fixture.Root);
        Assert.Equal(new DownloadLocalFileHash(Payload.Length, Sha256(Payload)), await historical.CalculateSha256Async(Token));
        await historical.DeleteOwnedPartialAsync(Token);
    }

    [Fact]
    public async Task ReaderIdentityVerificationFailureWakesOtherPrefixWaitersAndNeverFabricatesReadableGrowth()
    {
        using var fixture = new NativeFixture();
        await using var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token);
        var readable = Assert.IsAssignableFrom<IReadableDownloadDestination>(destination);
        TransferRunToken run = ReaderRun();
        await using var first = await readable.AcquireReadLeaseAsync(run, Payload.Length, Token);
        await using var second = await readable.AcquireReadLeaseAsync(run, Payload.Length, Token);
        await destination.WriteAsync(Payload.AsMemory(0, 3), Token);
        Task<int> future = first.ReadAsync(5, new byte[1], Token).AsTask();
        // Exclusive native ownership prevents an outside replacement. Corrupt only the expected length
        // observation to exercise the same fail-closed verification path without exposing a handle.
        DestinationField(destination, "_length").SetValue(destination, 4L);
        await AssertReaderUnknownAsync(() => second.ReadAsync(0, new byte[1], Token).AsTask(), fixture.Root);
        await AssertReaderUnknownAsync(() => future, fixture.Root);
        Assert.Equal(3, first.ReadablePrefixBytes);
        await AssertReaderUnknownAsync(() => readable.AcquireReadLeaseAsync(run, Payload.Length, Token), fixture.Root);
        Assert.False(File.Exists(fixture.FinalPath));
    }

    [Fact]
    public async Task UncertainPublicationRevokesReaderAccessAndCannotBecomeDeletionAuthority()
    {
        using var fixture = new NativeFixture();
        var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token);
        var readable = Assert.IsAssignableFrom<IReadableDownloadDestination>(destination);
        await using var reader = await readable.AcquireReadLeaseAsync(ReaderRun(), Payload.Length, Token);
        TransferWorkFiles files = destination.Files;
        try
        {
            await destination.WriteAsync(Payload, Token);
            DestinationField(destination, "_publishUncertain").SetValue(destination, true);
            await AssertReaderUnknownAsync(() => reader.ReadAsync(0, new byte[1], Token).AsTask(), fixture.Root);
            await AssertReaderUnknownAsync(() => destination.DeleteOwnedPartialAsync(Token), fixture.Root);
            Assert.True(File.Exists(files.PartialPath));
            Assert.False(File.Exists(fixture.FinalPath));
        }
        finally { await destination.DisposeAsync(); }
        Assert.Equal(Payload, await File.ReadAllBytesAsync(files.PartialPath!, Token));
    }

    [Fact]
    public async Task PreCanceledReaderAdmissionReadAndRevocationHaveNoOwnerSideEffects()
    {
        using var fixture = new NativeFixture();
        await using var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token);
        var readable = Assert.IsAssignableFrom<IReadableDownloadDestination>(destination);
        using var canceled = CancellationTokenSource.CreateLinkedTokenSource(Token);
        await canceled.CancelAsync();
        await AssertCanceledAsync(() => readable.AcquireReadLeaseAsync(default, -1, canceled.Token), canceled.Token);
        await AssertCanceledAsync(() => readable.RevokeReadersAsync(canceled.Token), canceled.Token);
        await using var reader = await readable.AcquireReadLeaseAsync(ReaderRun(), Payload.Length, Token);
        await AssertCanceledAsync(() => reader.ReadAsync(-1, new byte[1], canceled.Token).AsTask(), canceled.Token);
        await destination.WriteAsync(Payload, Token);
        Assert.Equal(Payload.Length, await reader.ReadAsync(0, new byte[Payload.Length], Token));
    }

    [Fact]
    public async Task PrefixStallTimeoutEndsOnlyTheReadAndTheWriterAndLeaseCanContinue()
    {
        using var fixture = new NativeFixture();
        await using var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token);
        var readable = Assert.IsAssignableFrom<IReadableDownloadDestination>(destination);
        await using var reader = await readable.AcquireReadLeaseAsync(ReaderRun(), Payload.Length, Token);
        var timeout = await Assert.ThrowsAsync<FilewayException>(() => reader.ReadAsync(5, new byte[1], Token).AsTask()
            .WaitAsync(TimeSpan.FromSeconds(40), Token));
        Assert.Equal(ErrorCode.Timeout, timeout.Error.Code);
        AssertSafeFailure(timeout, fixture.Root);
        Assert.Equal(0, reader.ReadablePrefixBytes);
        await destination.WriteAsync(Payload, Token);
        Assert.Equal(Payload.Length, await reader.ReadAsync(0, new byte[Payload.Length], Token));
    }

    [Fact]
    public async Task AnyVerifiedPrefixGrowthResetsTheStallBudgetEvenBeforeATailSeekIsReadable()
    {
        using var fixture = new NativeFixture();
        await using var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token);
        var readable = Assert.IsAssignableFrom<IReadableDownloadDestination>(destination);
        await using var reader = await readable.AcquireReadLeaseAsync(ReaderRun(), Payload.Length, Token);
        byte[] tail = new byte[1];
        Task<int> read = reader.ReadAsync(6, tail, Token).AsTask();
        for (int index = 0; index < 3; index++)
        {
            await Task.Delay(TimeSpan.FromSeconds(11), Token);
            Assert.False(read.IsCompleted);
            await destination.WriteAsync(Payload.AsMemory(index, 1), Token);
        }
        Assert.False(read.IsCompleted); // More than 30 seconds elapsed, with progress unrelated to offset 6.
        await destination.WriteAsync(Payload.AsMemory(3), Token);
        Assert.Equal(1, await read.WaitAsync(TimeSpan.FromSeconds(5), Token));
        Assert.Equal(Payload[6], tail[0]);
    }

    [Fact]
    public async Task AnUnconfirmedDrainTimesOutUnknownAndKeepsOwnershipUntilTheActualBarrierReleases()
    {
        using var fixture = new NativeFixture();
        var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token);
        var readable = Assert.IsAssignableFrom<IReadableDownloadDestination>(destination);
        var reader = await readable.AcquireReadLeaseAsync(ReaderRun(), Payload.Length, Token);
        await destination.WriteAsync(Payload, Token);
        TransferWorkFiles files = destination.Files;
        SemaphoreSlim serial = DestinationSerial(destination);
        await serial.WaitAsync(Token);
        Task? disposal = null;
        try
        {
            Task<int> queued = reader.ReadAsync(0, new byte[1], Token).AsTask();
            var unknown = await Assert.ThrowsAsync<FilewayException>(() => readable.RevokeReadersAsync(Token)
                .WaitAsync(TimeSpan.FromSeconds(40), Token));
            Assert.Equal(ErrorCode.OutcomeUnknown, unknown.Error.Code);
            AssertSafeFailure(unknown, fixture.Root);
            await AssertReaderUnknownAsync(() => queued, fixture.Root);
            Assert.True(File.Exists(files.PartialPath));
            Assert.Throws<IOException>(() => File.Delete(files.PartialPath!));
            Assert.Throws<IOException>(() =>
            {
                using var unrelated = new FileStream(files.PartialPath!, FileMode.Open, FileAccess.Read,
                    FileShare.ReadWrite | FileShare.Delete);
            });
            disposal = destination.DisposeAsync().AsTask();
            Assert.False(disposal.IsCompleted);
            using var cancelDelete = CancellationTokenSource.CreateLinkedTokenSource(Token);
            cancelDelete.CancelAfter(TimeSpan.FromMilliseconds(100));
            await AssertCanceledAsync(() => destination.DeleteOwnedPartialAsync(cancelDelete.Token), cancelDelete.Token);
            Assert.True(File.Exists(files.PartialPath));
        }
        finally
        {
            serial.Release();
            if (disposal is not null) await disposal.WaitAsync(TimeSpan.FromSeconds(5), Token);
            await readable.RevokeReadersAsync(Token).WaitAsync(TimeSpan.FromSeconds(5), Token);
            await reader.DisposeAsync();
            await destination.DisposeAsync();
        }
        Assert.Equal(Payload, await File.ReadAllBytesAsync(files.PartialPath!, Token));
        Assert.False(File.Exists(fixture.FinalPath));
    }

    [Fact]
    public async Task ThrowingStopCallbackIsSanitizedAndCannotSkipTheOtherReaderDrain()
    {
        using var fixture = new NativeFixture();
        var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token);
        var readable = Assert.IsAssignableFrom<IReadableDownloadDestination>(destination);
        TransferRunToken run = ReaderRun();
        var first = await readable.AcquireReadLeaseAsync(run, Payload.Length, Token);
        var second = await readable.AcquireReadLeaseAsync(run, Payload.Length, Token);
        using var callback = ReaderStop(first).Token.Register(() => throw new IOException("Synthetic private callback: " + fixture.Root));
        try
        {
            Task<int> left = first.ReadAsync(3, new byte[1], Token).AsTask();
            Task<int> right = second.ReadAsync(3, new byte[1], Token).AsTask();
            await AssertReaderUnknownAsync(() => readable.RevokeReadersAsync(Token), fixture.Root);
            await AssertReaderUnknownAsync(() => left, fixture.Root);
            await AssertReaderUnknownAsync(() => right, fixture.Root);
            await destination.WriteAsync(Payload, Token);
            await destination.PublishNoReplaceAsync(Token);
            await destination.DisposeAsync();
            Assert.Equal(Payload, await File.ReadAllBytesAsync(fixture.FinalPath, Token));
            await AssertReaderUnknownAsync(() => first.DisposeAsync().AsTask(), fixture.Root);
            await second.DisposeAsync();
        }
        finally
        {
            try { await first.DisposeAsync(); }
            catch (FilewayException error) { Assert.Equal(ErrorCode.OutcomeUnknown, error.Error.Code); }
            await second.DisposeAsync();
            try { await destination.DisposeAsync(); }
            catch (FilewayException error) { Assert.Equal(ErrorCode.OutcomeUnknown, error.Error.Code); }
        }
    }

    [Fact]
    public async Task CallerCanceledRevocationDoesNotAcknowledgeABlockedStopCallbackOrReleaseOwnership()
    {
        using var fixture = new NativeFixture();
        await using var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token);
        var readable = Assert.IsAssignableFrom<IReadableDownloadDestination>(destination);
        await using var reader = await readable.AcquireReadLeaseAsync(ReaderRun(), Payload.Length, Token);
        using var release = new ManualResetEventSlim();
        var callbackStarted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        using var callback = ReaderStop(reader).Token.Register(() =>
        {
            callbackStarted.TrySetResult();
            release.Wait(Token);
        });
        Task revocation = readable.RevokeReadersAsync(Token);
        try
        {
            await callbackStarted.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
            Assert.False(revocation.IsCompleted);
            using var canceled = CancellationTokenSource.CreateLinkedTokenSource(Token);
            canceled.CancelAfter(TimeSpan.FromMilliseconds(100));
            await AssertCanceledAsync(() => readable.RevokeReadersAsync(canceled.Token), canceled.Token);
            Assert.False(revocation.IsCompleted);
            Assert.Throws<IOException>(() => File.Delete(destination.Files.PartialPath!));
            Assert.Throws<IOException>(() => Directory.Move(fixture.DestinationDirectory, Path.Combine(fixture.Root, "moved-output")));
        }
        finally { release.Set(); }
        await revocation.WaitAsync(TimeSpan.FromSeconds(5), Token);
        await destination.WriteAsync(Payload, Token);
        await destination.DeleteOwnedPartialAsync(Token);
        Assert.Empty(Directory.GetFiles(fixture.DestinationDirectory));
    }

    private static CancellationTokenSource ReaderStop(IDownloadReadLease reader) => Assert.IsType<CancellationTokenSource>(
        Assert.IsAssignableFrom<FieldInfo>(reader.GetType().GetField("_stop", BindingFlags.Instance | BindingFlags.NonPublic)).GetValue(reader));

    private static TransferRunToken ReaderRun() => new(new(new(Guid.NewGuid(), Guid.NewGuid(), "native-download-reader"),
        Guid.NewGuid()), Guid.NewGuid(), 1);

    private static SemaphoreSlim DestinationSerial(IDownloadDestination destination) =>
        Assert.IsType<SemaphoreSlim>(DestinationField(destination, "_serial").GetValue(destination));

    private static FieldInfo DestinationField(IDownloadDestination destination, string name) =>
        Assert.IsAssignableFrom<FieldInfo>(destination.GetType().GetField(name, BindingFlags.Instance | BindingFlags.NonPublic));

    private static async Task AssertReaderUnknownAsync(Func<Task> operation, string privatePath)
    {
        var unknown = await Assert.ThrowsAsync<FilewayException>(() => operation().WaitAsync(TimeSpan.FromSeconds(5), Token));
        Assert.Equal(ErrorCode.OutcomeUnknown, unknown.Error.Code);
        AssertSafeFailure(unknown, privatePath);
    }
}
