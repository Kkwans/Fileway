using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Transfers;

namespace Fileway.Infrastructure.Tests.Transfers;

public sealed partial class WindowsDownloadFileAccessTests
{
    [Fact]
    public async Task PreCanceledProviderAndCapabilityOperationsHaveNoFilesystemSideEffects()
    {
        using var fixture = new NativeFixture();
        var access = new WindowsDownloadFileAccess();
        using var canceled = CancellationTokenSource.CreateLinkedTokenSource(Token);
        await canceled.CancelAsync();
        await AssertCanceledAsync(() => access.CreateFreshAsync("invalid-relative-final", canceled.Token), canceled.Token);
        await AssertCanceledAsync(() => access.OpenOwnedAsync("invalid-relative-final", new(), canceled.Token), canceled.Token);
        Assert.Empty(Directory.GetFileSystemEntries(fixture.DestinationDirectory));

        await using var destination = await access.CreateFreshAsync(fixture.FinalPath, Token);
        TransferWorkFiles files = destination.Files;
        await AssertCanceledAsync(() => destination.WriteAsync(Payload, canceled.Token), canceled.Token);
        await AssertCanceledAsync(() => destination.FlushToDiskAsync(canceled.Token), canceled.Token);
        await AssertCanceledAsync(() => destination.CalculateSha256Async(canceled.Token), canceled.Token);
        await AssertCanceledAsync(() => destination.PublishNoReplaceAsync(canceled.Token), canceled.Token);
        await AssertCanceledAsync(() => destination.DeleteOwnedPartialAsync(canceled.Token), canceled.Token);
        Assert.Equal(0, destination.Length);
        Assert.False(destination.IsPublished);
        Assert.Equal(files, destination.Files);
        Assert.True(File.Exists(files.PartialPath));
        Assert.False(File.Exists(fixture.FinalPath));
        await destination.WriteAsync(Payload, Token);
        Assert.Equal(Payload.Length, await destination.FlushToDiskAsync(Token));
        await destination.DeleteOwnedPartialAsync(Token);
        Assert.Empty(Directory.GetFileSystemEntries(fixture.DestinationDirectory));
    }

    [Fact]
    public async Task CancellationDuringFreshCreationRemovesOnlyTheNewlyVerifiedOwnedPartial()
    {
        using var fixture = new NativeFixture();
        await File.WriteAllBytesAsync(fixture.FinalPath, StrangerPayload, Token);
        TransferSourceSnapshot stranger = await SnapshotAsync(fixture, fixture.FinalPath);
        using var canceled = CancellationTokenSource.CreateLinkedTokenSource(Token);
        var access = WithPartialName(() => { canceled.Cancel(); return ForcedName; });

        await AssertCanceledAsync(() => access.CreateFreshAsync(fixture.FinalPath, canceled.Token), canceled.Token);

        Assert.Equal(fixture.FinalPath, Assert.Single(Directory.GetFiles(fixture.DestinationDirectory)));
        Assert.False(File.Exists(Path.Combine(fixture.DestinationDirectory, ForcedName)));
        Assert.Equal(StrangerPayload, await File.ReadAllBytesAsync(fixture.FinalPath, Token));
        Assert.Equal(stranger, await SnapshotAsync(fixture, fixture.FinalPath));
        string moved = Path.Combine(fixture.Root, "released-output");
        Directory.Move(fixture.DestinationDirectory, moved);
        Directory.Move(moved, fixture.DestinationDirectory);
    }

    [Fact]
    public async Task FailureImmediatelyAfterNativeCreateReleasesAndDeletesOnlyThatCreatedObjectWithoutIdentityEvidence()
    {
        using var fixture = new NativeFixture();
        string sentinel = Path.Combine(fixture.DestinationDirectory, "sentinel.bin");
        string partial = Path.Combine(fixture.DestinationDirectory, ForcedName);
        await File.WriteAllBytesAsync(sentinel, StrangerPayload, Token);
        TransferSourceSnapshot stranger = await SnapshotAsync(fixture, sentinel);
        bool createdWasObserved = false;
        var access = WithCreatedHook(() => ForcedName, () =>
        {
            createdWasObserved = File.Exists(partial);
            throw new IOException("Synthetic private acquisition failure: " + fixture.Root);
        });

        var unavailable = await Assert.ThrowsAsync<FilewayException>(() => access.CreateFreshAsync(fixture.FinalPath, Token));

        Assert.True(createdWasObserved);
        Assert.Equal(ErrorCode.LocalStorageUnavailable, unavailable.Error.Code);
        AssertSafeFailure(unavailable, fixture.Root);
        Assert.False(File.Exists(partial));
        Assert.False(File.Exists(fixture.FinalPath));
        Assert.Equal(sentinel, Assert.Single(Directory.GetFiles(fixture.DestinationDirectory)));
        Assert.Equal(StrangerPayload, await File.ReadAllBytesAsync(sentinel, Token));
        Assert.Equal(stranger, await SnapshotAsync(fixture, sentinel));
        string moved = Path.Combine(fixture.Root, "released-output");
        Directory.Move(fixture.DestinationDirectory, moved);
        Directory.Move(moved, fixture.DestinationDirectory);
    }

    [Fact]
    public async Task CancellationAfterPublicationCannotDeleteOrRevertTheFinalFile()
    {
        using var fixture = new NativeFixture();
        var access = new WindowsDownloadFileAccess();
        TransferWorkFiles files;
        string identity;
        await using (var destination = await access.CreateFreshAsync(fixture.FinalPath, Token))
        {
            await destination.WriteAsync(Payload, Token);
            await destination.FlushToDiskAsync(Token);
            identity = await destination.PublishNoReplaceAsync(Token);
            files = destination.Files;
            using var canceled = CancellationTokenSource.CreateLinkedTokenSource(Token);
            await canceled.CancelAsync();
            await AssertCanceledAsync(() => destination.WriteAsync(StrangerPayload, canceled.Token), canceled.Token);
            await AssertCanceledAsync(() => destination.FlushToDiskAsync(canceled.Token), canceled.Token);
            await AssertCanceledAsync(() => destination.CalculateSha256Async(canceled.Token), canceled.Token);
            await AssertCanceledAsync(() => destination.PublishNoReplaceAsync(canceled.Token), canceled.Token);
            await AssertCanceledAsync(() => destination.DeleteOwnedPartialAsync(canceled.Token), canceled.Token);
            await AssertCanceledAsync(() => access.OpenOwnedAsync(fixture.FinalPath, files, canceled.Token), canceled.Token);
            Assert.True(destination.IsPublished);
            Assert.Equal(identity, destination.Files.PublishedFileIdentity);
            Assert.Equal(files, destination.Files);
            Assert.Equal(Payload.Length, destination.Length);
            var protectedFinal = await Assert.ThrowsAsync<FilewayException>(() => destination.DeleteOwnedPartialAsync(Token));
            Assert.Equal(ErrorCode.OutcomeUnknown, protectedFinal.Error.Code);
        }
        Assert.False(File.Exists(files.PartialPath));
        Assert.Equal(Payload, await File.ReadAllBytesAsync(fixture.FinalPath, Token));
        Assert.Equal(identity, (await SnapshotAsync(fixture, fixture.FinalPath)).NativeFileIdentity);
        Assert.Equal(fixture.FinalPath, Assert.Single(Directory.GetFiles(fixture.DestinationDirectory)));
    }

    [Fact]
    public async Task DisposedCapabilityCannotMutateANewOwnerAndCompletedDeletionNeverDeletesAReusedName()
    {
        using var fixture = new NativeFixture();
        var access = new WindowsDownloadFileAccess();
        var stale = await access.CreateFreshAsync(fixture.FinalPath, Token);
        TransferWorkFiles files = stale.Files;
        try
        {
            await stale.WriteAsync(Payload, Token);
            await stale.FlushToDiskAsync(Token);
        }
        finally { await stale.DisposeAsync(); }
        var current = await access.OpenOwnedAsync(fixture.FinalPath, files, Token);
        try
        {
            Func<Task>[] staleOperations =
            [
                () => stale.WriteAsync(StrangerPayload, Token), () => stale.FlushToDiskAsync(Token),
                () => stale.CalculateSha256Async(Token), () => stale.PublishNoReplaceAsync(Token),
                () => stale.DeleteOwnedPartialAsync(Token),
            ];
            foreach (Func<Task> operation in staleOperations)
            {
                var closed = await Assert.ThrowsAsync<FilewayException>(operation);
                Assert.Equal(ErrorCode.OutcomeUnknown, closed.Error.Code);
                AssertSafeFailure(closed, fixture.Root);
            }
            Assert.Equal(files, current.Files);
            Assert.Equal(new DownloadLocalFileHash(Payload.Length, Sha256(Payload)), await current.CalculateSha256Async(Token));
            await current.DeleteOwnedPartialAsync(Token);
            await current.DeleteOwnedPartialAsync(Token);
        }
        finally
        {
            await current.DisposeAsync();
            await current.DisposeAsync();
            await stale.DisposeAsync();
        }
        await File.WriteAllBytesAsync(files.PartialPath!, StrangerPayload, Token);
        TransferSourceSnapshot stranger = await SnapshotAsync(fixture, files.PartialPath!);
        await current.DeleteOwnedPartialAsync(Token); // A completed deletion is a no-op even after this name is reused.
        var noAuthority = await Assert.ThrowsAsync<FilewayException>(() => stale.DeleteOwnedPartialAsync(Token));
        Assert.Equal(ErrorCode.OutcomeUnknown, noAuthority.Error.Code);
        Assert.Equal(stranger, await SnapshotAsync(fixture, files.PartialPath!));
        Assert.Equal(StrangerPayload, await File.ReadAllBytesAsync(files.PartialPath!, Token));
        Assert.False(File.Exists(fixture.FinalPath));
    }

    [Fact]
    public async Task ConcurrentWritesFlushAndHashSerializeAtWholeWriteBoundariesWithoutByteCorruption()
    {
        using var fixture = new NativeFixture();
        byte[] block = Enumerable.Range(0, 16384).Select(index => (byte)(index * 13 + 11)).ToArray();
        const int count = 24;
        byte[] expected = Enumerable.Range(0, count).SelectMany(_ => block).ToArray();
        var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token);
        TransferWorkFiles files = destination.Files;
        try
        {
            var start = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            Task[] writes = Enumerable.Range(0, count).Select(_ => Task.Run(async () =>
            {
                await start.Task;
                await destination.WriteAsync(block, Token);
            }, Token)).ToArray();
            Task<long> flush = Task.Run(async () => { await start.Task; return await destination.FlushToDiskAsync(Token); }, Token);
            Task<DownloadLocalFileHash> hash = Task.Run(async () => { await start.Task; return await destination.CalculateSha256Async(Token); }, Token);
            start.SetResult();
            await Task.WhenAll(writes.Append(flush).Append(hash));
            long flushed = await flush;
            DownloadLocalFileHash observed = await hash;
            Assert.InRange(flushed, 0, expected.Length);
            Assert.Equal(0, flushed % block.Length);
            Assert.InRange(observed.Length, 0, expected.Length);
            Assert.Equal(0, observed.Length % block.Length);
            Assert.Equal(Sha256(expected.AsSpan(0, checked((int)observed.Length)).ToArray()), observed.Sha256);
            Assert.Equal(expected.Length, destination.Length);
            Assert.Equal(expected.Length, await destination.FlushToDiskAsync(Token));
            Assert.Equal(new DownloadLocalFileHash(expected.Length, Sha256(expected)), await destination.CalculateSha256Async(Token));
        }
        finally
        {
            await Task.WhenAll(Enumerable.Range(0, 8).Select(_ => destination.DisposeAsync().AsTask()));
        }
        Assert.Equal(expected, await File.ReadAllBytesAsync(files.PartialPath!, Token));
        Assert.Equal(files.PartialIdentity, (await SnapshotAsync(fixture, files.PartialPath!)).NativeFileIdentity);
        await using var reopened = await new WindowsDownloadFileAccess().OpenOwnedAsync(fixture.FinalPath, files, Token);
        await reopened.DeleteOwnedPartialAsync(Token);
    }

    [Fact]
    public async Task ConcurrentDisposeDrainsAcquiredNativeIoAndUnacquiredOperationsFailWithoutChangingTheFile()
    {
        using var fixture = new NativeFixture();
        byte[] block = Enumerable.Range(0, 32768).Select(index => (byte)(index * 17 + 3)).ToArray();
        var access = new WindowsDownloadFileAccess();
        var destination = await access.CreateFreshAsync(fixture.FinalPath, Token);
        TransferWorkFiles files = destination.Files;
        try
        {
            await destination.WriteAsync(block, Token);
            var start = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            Task<bool>[] writes = Enumerable.Range(0, 24).Select(_ => Task.Run(async () =>
            {
                await start.Task;
                return await CompleteOrClosedAsync(() => destination.WriteAsync(block, Token));
            }, Token)).ToArray();
            DownloadLocalFileHash? observedHash = null;
            long? observedFlush = null;
            Task<bool> flush = Task.Run(async () =>
            {
                await start.Task;
                return await CompleteOrClosedAsync(async () => { observedFlush = await destination.FlushToDiskAsync(Token); });
            }, Token);
            Task<bool> hash = Task.Run(async () =>
            {
                await start.Task;
                return await CompleteOrClosedAsync(async () => { observedHash = await destination.CalculateSha256Async(Token); });
            }, Token);
            Task disposal = Task.Run(async () => { await start.Task; await destination.DisposeAsync(); }, Token);
            start.SetResult();
            await Task.WhenAll(writes.Cast<Task>().Append(flush).Append(hash).Append(disposal));
            bool[] completed = await Task.WhenAll(writes);
            int completedWrites = 1 + completed.Count(succeeded => succeeded);
            byte[] expected = Enumerable.Range(0, completedWrites).SelectMany(_ => block).ToArray();
            Assert.Equal(expected, await File.ReadAllBytesAsync(files.PartialPath!, Token));
            Assert.Equal(expected.Length, destination.Length);
            if (observedFlush is { } flushed)
            {
                Assert.InRange(flushed, block.Length, expected.Length);
                Assert.Equal(0, flushed % block.Length);
            }
            if (observedHash is { } observed)
            {
                Assert.InRange(observed.Length, block.Length, expected.Length);
                Assert.Equal(0, observed.Length % block.Length);
                Assert.Equal(Sha256(expected.AsSpan(0, checked((int)observed.Length)).ToArray()), observed.Sha256);
            }
            await using var recovered = await access.OpenOwnedAsync(fixture.FinalPath, files, Token);
            Assert.Equal(new DownloadLocalFileHash(expected.Length, Sha256(expected)), await recovered.CalculateSha256Async(Token));
            await recovered.DeleteOwnedPartialAsync(Token);
        }
        finally { await destination.DisposeAsync(); }
        Assert.Empty(Directory.GetFileSystemEntries(fixture.DestinationDirectory));
    }

    private static async Task AssertCanceledAsync(Func<Task> operation, CancellationToken canceled)
    {
        var failure = await Assert.ThrowsAnyAsync<OperationCanceledException>(operation);
        Assert.Equal(canceled, failure.CancellationToken);
    }

    private static async Task<bool> CompleteOrClosedAsync(Func<Task> operation)
    {
        try { await operation(); return true; }
        catch (FilewayException failure)
        {
            Assert.Equal(ErrorCode.OutcomeUnknown, failure.Error.Code);
            Assert.Null(failure.InnerException);
            return false;
        }
    }
}
