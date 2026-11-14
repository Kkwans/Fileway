using System.Diagnostics;
using System.Reflection;
using System.Security.Cryptography;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Tests.Ipc;
using Fileway.Infrastructure.Transfers;

namespace Fileway.Infrastructure.Tests.Transfers;

public sealed partial class WindowsDownloadFileAccessTests
{
    private static readonly byte[] Payload = [0, 1, 2, 3, 0xff, 0xfe, 0xfd, 0xfc];
    private static readonly byte[] StrangerPayload = [0x91, 0x82, 0x73, 0x64, 0x55, 0x46, 0x37, 0x28];
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Fact]
    public async Task FreshPartialIsAnExclusiveSameDirectoryCapabilityAndDisposeNeverDeletesIt()
    {
        using var fixture = new NativeFixture();
        var access = new WindowsDownloadFileAccess();
        var destination = await access.CreateFreshAsync(fixture.FinalPath, Token);
        TransferWorkFiles files = destination.Files;
        try
        {
            AssertFreshEvidence(fixture.FinalPath, files);
            Assert.False(destination.IsPublished);
            Assert.Equal(0, destination.Length);
            Assert.False(File.Exists(fixture.FinalPath));
            Assert.Equal(files.PartialPath, Assert.Single(Directory.GetFiles(fixture.DestinationDirectory)));
            AssertSafeDisplay(access.ToString(), fixture.Root);
            AssertSafeDisplay(destination.ToString(), files.PartialPath!);
            AssertSafeDisplay(files.ToString(), files.PartialPath!);
            AssertSafeDisplay(files.ToString(), files.PartialIdentity!);

            foreach (FileAccess mode in new[] { FileAccess.Read, FileAccess.Write, FileAccess.ReadWrite })
            {
                Assert.Throws<IOException>(() =>
                {
                    using var unrelated = new FileStream(files.PartialPath!, FileMode.Open, mode, FileShare.ReadWrite | FileShare.Delete);
                });
            }
            Assert.Throws<IOException>(() => File.Delete(files.PartialPath!));
            Assert.Throws<IOException>(() => File.Move(files.PartialPath!, fixture.FinalPath));
            var occupied = await Assert.ThrowsAsync<FilewayException>(() => access.OpenOwnedAsync(fixture.FinalPath, files, Token));
            Assert.Equal(ErrorCode.OutcomeUnknown, occupied.Error.Code);
            AssertSafeFailure(occupied, fixture.Root);
            await destination.WriteAsync(Payload, Token);
            Assert.Equal(Payload.Length, await destination.FlushToDiskAsync(Token));
        }
        finally
        {
            await destination.DisposeAsync();
            await destination.DisposeAsync();
        }

        Assert.True(File.Exists(files.PartialPath));
        Assert.False(File.Exists(fixture.FinalPath));
        Assert.Equal(Payload, await File.ReadAllBytesAsync(files.PartialPath!, Token));
        Assert.Equal(files.PartialIdentity, (await SnapshotAsync(fixture, files.PartialPath!)).NativeFileIdentity);
        using (var exclusive = new FileStream(files.PartialPath!, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
            Assert.Equal(Payload.Length, exclusive.Length);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(524305)]
    public async Task FlushAndHeldFileHashAreExactForEmptyAndMultiBufferPayloadsAndPublishKeepsNativeIdentity(int length)
    {
        using var fixture = new NativeFixture();
        byte[] bytes = Enumerable.Range(0, length).Select(index => (byte)(index * 31 + 7)).ToArray();
        var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token);
        TransferWorkFiles initial = destination.Files;
        string publishedIdentity;
        try
        {
            await destination.WriteAsync(ReadOnlyMemory<byte>.Empty, Token);
            int[] sizes = [1, 4093, 65537, 131071];
            int offset = 0;
            for (int index = 0; offset < bytes.Length; index++)
            {
                int count = Math.Min(sizes[index % sizes.Length], bytes.Length - offset);
                await destination.WriteAsync(bytes.AsMemory(offset, count), Token);
                offset += count;
                Assert.Equal(offset, destination.Length);
            }
            Assert.Equal(bytes.Length, await destination.FlushToDiskAsync(Token));
            Assert.Equal(new DownloadLocalFileHash(bytes.Length, Sha256(bytes)), await destination.CalculateSha256Async(Token));
            Assert.Equal(new DownloadLocalFileHash(bytes.Length, Sha256(bytes)), await destination.CalculateSha256Async(Token));
            Assert.Equal(bytes.Length, destination.Length);

            publishedIdentity = await destination.PublishNoReplaceAsync(Token);
            Assert.Equal(initial.PartialIdentity, publishedIdentity);
            Assert.Equal(initial with { PublishedFileIdentity = publishedIdentity }, destination.Files);
            Assert.True(destination.IsPublished);
            Assert.False(File.Exists(initial.PartialPath));
            Assert.True(File.Exists(fixture.FinalPath));
            Assert.Equal(new DownloadLocalFileHash(bytes.Length, Sha256(bytes)), await destination.CalculateSha256Async(Token));
            var immutable = await Assert.ThrowsAsync<FilewayException>(() => destination.WriteAsync(Payload, Token));
            Assert.Equal(ErrorCode.InvalidRequest, immutable.Error.Code);
            var protectedFinal = await Assert.ThrowsAsync<FilewayException>(() => destination.DeleteOwnedPartialAsync(Token));
            Assert.Equal(ErrorCode.OutcomeUnknown, protectedFinal.Error.Code);
            AssertSafeFailure(protectedFinal, fixture.Root);
        }
        finally { await destination.DisposeAsync(); }

        Assert.Equal(bytes, await File.ReadAllBytesAsync(fixture.FinalPath, Token));
        Assert.Equal(publishedIdentity, (await SnapshotAsync(fixture, fixture.FinalPath)).NativeFileIdentity);
        Assert.Equal(fixture.FinalPath, Assert.Single(Directory.GetFiles(fixture.DestinationDirectory)));
    }

    [Fact]
    public async Task HashingDoesNotChangeTheSequentialWritePosition()
    {
        using var fixture = new NativeFixture();
        TransferWorkFiles files;
        await using (var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token))
        {
            files = destination.Files;
            await destination.WriteAsync(Payload, Token);
            Assert.Equal(new DownloadLocalFileHash(Payload.Length, Sha256(Payload)), await destination.CalculateSha256Async(Token));
            await destination.WriteAsync(StrangerPayload, Token);
            byte[] expected = [.. Payload, .. StrangerPayload];
            Assert.Equal(expected.Length, await destination.FlushToDiskAsync(Token));
            Assert.Equal(new DownloadLocalFileHash(expected.Length, Sha256(expected)), await destination.CalculateSha256Async(Token));
        }
        Assert.Equal(new byte[] { 0, 1, 2, 3, 0xff, 0xfe, 0xfd, 0xfc, 0x91, 0x82, 0x73, 0x64, 0x55, 0x46, 0x37, 0x28 },
            await File.ReadAllBytesAsync(files.PartialPath!, Token));
    }

    [Fact]
    public async Task FileArrivingAfterFreshCreationIsPreservedByAuthoritativePublicationAndOwnedCleanup()
    {
        using var fixture = new NativeFixture();
        TransferSourceSnapshot stranger;
        TransferWorkFiles files;
        await using (var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token))
        {
            files = destination.Files;
            await File.WriteAllBytesAsync(fixture.FinalPath, StrangerPayload, Token);
            stranger = await SnapshotAsync(fixture, fixture.FinalPath);
            // The foreign final arrives after preflight and remains exclusively held during publication.
            using var unrelated = new FileStream(fixture.FinalPath, FileMode.Open, FileAccess.ReadWrite, FileShare.None);
            await destination.WriteAsync(Payload, Token);
            await destination.FlushToDiskAsync(Token);
            var conflict = await Assert.ThrowsAsync<FilewayException>(() => destination.PublishNoReplaceAsync(Token));
            Assert.Equal(ErrorCode.Conflict, conflict.Error.Code);
            AssertSafeFailure(conflict, fixture.Root);
            Assert.False(destination.IsPublished);
            Assert.Equal(files, destination.Files);
            Assert.Equal(new DownloadLocalFileHash(Payload.Length, Sha256(Payload)), await destination.CalculateSha256Async(Token));
            await destination.DeleteOwnedPartialAsync(Token);
            await destination.DeleteOwnedPartialAsync(Token);
        }
        Assert.False(File.Exists(files.PartialPath));
        Assert.Equal(StrangerPayload, await File.ReadAllBytesAsync(fixture.FinalPath, Token));
        Assert.Equal(stranger, await SnapshotAsync(fixture, fixture.FinalPath));
    }

    [Fact]
    public async Task DirectoryArrivingAfterFreshCreationIsPreservedByFailedPublicationAndOwnedCleanup()
    {
        using var fixture = new NativeFixture();
        string marker = Path.Combine(fixture.FinalPath, "marker.bin");
        TransferSourceSnapshot stranger;
        await using (var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token))
        {
            Directory.CreateDirectory(fixture.FinalPath);
            await File.WriteAllBytesAsync(marker, StrangerPayload, Token);
            stranger = await SnapshotAsync(fixture, marker);
            await destination.WriteAsync(Payload, Token);
            await destination.FlushToDiskAsync(Token);
            var conflict = await Assert.ThrowsAsync<FilewayException>(() => destination.PublishNoReplaceAsync(Token));
            Assert.Equal(ErrorCode.Conflict, conflict.Error.Code);
            Assert.False(destination.IsPublished);
            await destination.DeleteOwnedPartialAsync(Token);
        }
        Assert.True(Directory.Exists(fixture.FinalPath));
        Assert.Equal(marker, Assert.Single(Directory.GetFiles(fixture.FinalPath)));
        Assert.Equal(StrangerPayload, await File.ReadAllBytesAsync(marker, Token));
        Assert.Equal(stranger, await SnapshotAsync(fixture, marker));
    }

    [Fact]
    public async Task JunctionArrivingAfterFreshCreationPreservesItsTargetDuringPublicationAndOwnedCleanup()
    {
        using var fixture = new NativeFixture();
        string target = Path.Combine(fixture.DestinationDirectory, "target");
        Directory.CreateDirectory(target);
        string marker = Path.Combine(target, "marker.bin");
        await File.WriteAllBytesAsync(marker, StrangerPayload, Token);
        TransferSourceSnapshot stranger = await SnapshotAsync(fixture, marker);
        try
        {
            await using (var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token))
            {
                await RunFixtureCommandAsync(fixture.DestinationDirectory, "mklink /j download.bin target");
                await destination.WriteAsync(Payload, Token);
                await destination.FlushToDiskAsync(Token);
                var conflict = await Assert.ThrowsAsync<FilewayException>(() => destination.PublishNoReplaceAsync(Token));
                Assert.Equal(ErrorCode.Conflict, conflict.Error.Code);
                Assert.False(destination.IsPublished);
                await destination.DeleteOwnedPartialAsync(Token);
            }
            Assert.True((File.GetAttributes(fixture.FinalPath) & FileAttributes.ReparsePoint) != 0);
            Assert.Equal(marker, Assert.Single(Directory.GetFiles(target)));
            Assert.Equal(StrangerPayload, await File.ReadAllBytesAsync(marker, Token));
            Assert.Equal(stranger, await SnapshotAsync(fixture, marker));
        }
        finally { Directory.Delete(fixture.FinalPath); } // Delete only the fixture junction, never its target.
    }

    [Fact]
    public async Task TargetCreatedAfterFreshPartialWinsWithoutChangingEitherFile()
    {
        using var fixture = new NativeFixture();
        TransferWorkFiles files;
        TransferSourceSnapshot stranger;
        await using (var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token))
        {
            files = destination.Files;
            await destination.WriteAsync(Payload, Token);
            await destination.FlushToDiskAsync(Token);
            await File.WriteAllBytesAsync(fixture.FinalPath, StrangerPayload, Token);
            stranger = await SnapshotAsync(fixture, fixture.FinalPath);
            var conflict = await Assert.ThrowsAsync<FilewayException>(() => destination.PublishNoReplaceAsync(Token));
            Assert.Equal(ErrorCode.Conflict, conflict.Error.Code);
            Assert.False(destination.IsPublished);
            Assert.Equal(files, destination.Files);
            Assert.Equal(new DownloadLocalFileHash(Payload.Length, Sha256(Payload)), await destination.CalculateSha256Async(Token));
        }
        Assert.Equal(Payload, await File.ReadAllBytesAsync(files.PartialPath!, Token));
        Assert.Equal(files.PartialIdentity, (await SnapshotAsync(fixture, files.PartialPath!)).NativeFileIdentity);
        Assert.Equal(StrangerPayload, await File.ReadAllBytesAsync(fixture.FinalPath, Token));
        Assert.Equal(stranger, await SnapshotAsync(fixture, fixture.FinalPath));
    }

    [Fact]
    public async Task TwoPublishersHaveExactlyOneWinnerAndFinalIdentityBelongsToThatWinner()
    {
        using var fixture = new NativeFixture();
        var access = new WindowsDownloadFileAccess();
        await using var first = await access.CreateFreshAsync(fixture.FinalPath, Token);
        await using var second = await access.CreateFreshAsync(fixture.FinalPath, Token);
        TransferWorkFiles firstFiles = first.Files;
        TransferWorkFiles secondFiles = second.Files;
        PublishAttempt winner;
        try
        {
            Assert.NotEqual(firstFiles.PartialPath, secondFiles.PartialPath);
            Assert.NotEqual(firstFiles.PartialIdentity, secondFiles.PartialIdentity);
            await first.WriteAsync(Payload, Token);
            await second.WriteAsync(StrangerPayload, Token);
            await first.FlushToDiskAsync(Token);
            await second.FlushToDiskAsync(Token);
            var start = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            Task<PublishAttempt> left = Task.Run(async () => { await start.Task; return await PublishAsync(first); }, Token);
            Task<PublishAttempt> right = Task.Run(async () => { await start.Task; return await PublishAsync(second); }, Token);
            start.SetResult();
            PublishAttempt[] attempts = await Task.WhenAll(left, right);
            winner = Assert.Single(attempts, attempt => attempt.Identity is not null);
            PublishAttempt loser = Assert.Single(attempts, attempt => attempt.Failure is not null);
            Assert.Null(winner.Failure);
            Assert.Equal(ErrorCode.Conflict, loser.Failure!.Error.Code);
            Assert.True(winner.Destination.IsPublished);
            Assert.False(loser.Destination.IsPublished);
            Assert.Equal(winner.Destination.Files.PartialIdentity, winner.Identity);
            Assert.Equal(winner.Identity, winner.Destination.Files.PublishedFileIdentity);
            await loser.Destination.DeleteOwnedPartialAsync(Token);
            var protectedFinal = await Assert.ThrowsAsync<FilewayException>(() => winner.Destination.DeleteOwnedPartialAsync(Token));
            Assert.Equal(ErrorCode.OutcomeUnknown, protectedFinal.Error.Code);
        }
        finally
        {
            await first.DisposeAsync();
            await second.DisposeAsync();
        }
        Assert.Equal(ReferenceEquals(winner.Destination, first) ? Payload : StrangerPayload,
            await File.ReadAllBytesAsync(fixture.FinalPath, Token));
        Assert.Equal(winner.Identity, (await SnapshotAsync(fixture, fixture.FinalPath)).NativeFileIdentity);
        Assert.Equal(fixture.FinalPath, Assert.Single(Directory.GetFiles(fixture.DestinationDirectory)));
        Assert.False(File.Exists(firstFiles.PartialPath));
        Assert.False(File.Exists(secondFiles.PartialPath));
    }

    private static async Task<PublishAttempt> PublishAsync(IDownloadDestination destination)
    {
        try { return new(destination, await destination.PublishNoReplaceAsync(Token), null); }
        catch (FilewayException failure) { return new(destination, null, failure); }
    }

    private sealed record PublishAttempt(IDownloadDestination Destination, string? Identity, FilewayException? Failure);

    private static string Sha256(byte[] bytes) => Convert.ToHexStringLower(SHA256.HashData(bytes));

    private static async Task<TransferSourceSnapshot> SnapshotAsync(NativeFixture fixture, string path)
    {
        await using var source = await new WindowsUploadFileAccess(fixture.ExecutionDirectory).OpenAsync(path, Token);
        return source.Snapshot;
    }

    private static void AssertFreshEvidence(string finalPath, TransferWorkFiles files)
    {
        Assert.NotNull(files.PartialPath);
        Assert.NotEqual(finalPath, files.PartialPath);
        Assert.Equal(Path.GetDirectoryName(finalPath), Path.GetDirectoryName(files.PartialPath));
        Assert.Matches("^fileway-download-v1-[0-9a-f]{32}\\.partial$", Path.GetFileName(files.PartialPath));
        Assert.False(string.IsNullOrWhiteSpace(files.PartialIdentity));
        Assert.Null(files.PublishedFileIdentity);
        Assert.Null(files.OriginalTargetIdentity);
        Assert.Null(files.BackupPath);
        Assert.Null(files.BackupIdentity);
    }

    private static WindowsDownloadFileAccess WithPartialName(Func<string> factory)
    {
        var constructor = Assert.IsAssignableFrom<ConstructorInfo>(typeof(WindowsDownloadFileAccess).GetConstructor(
            BindingFlags.Instance | BindingFlags.NonPublic, binder: null, types: [typeof(Func<string>)], modifiers: null));
        return Assert.IsType<WindowsDownloadFileAccess>(constructor.Invoke([factory]));
    }

    private static WindowsDownloadFileAccess WithCreatedHook(Func<string> factory, Action afterCreated)
    {
        var constructor = Assert.IsAssignableFrom<ConstructorInfo>(typeof(WindowsDownloadFileAccess).GetConstructor(
            BindingFlags.Instance | BindingFlags.NonPublic, binder: null, types: [typeof(Func<string>), typeof(Action)], modifiers: null));
        return Assert.IsType<WindowsDownloadFileAccess>(constructor.Invoke([factory, afterCreated]));
    }

    private static void AssertSafeFailure(FilewayException failure, string privateValue)
    {
        Assert.Null(failure.InnerException);
        AssertSafeDisplay(failure.Message, privateValue);
        AssertSafeDisplay(failure.ToString(), privateValue);
        Assert.DoesNotContain(@"\\?\", failure.Message, StringComparison.Ordinal);
    }

    private static void AssertSafeDisplay(string? display, string privateValue)
    {
        Assert.NotNull(display);
        Assert.DoesNotContain(privateValue, display, StringComparison.OrdinalIgnoreCase);
    }

    private static async Task RunFixtureCommandAsync(string directory, string command)
    {
        // Commands contain only fixed relative fixture names, never private or externally supplied path text.
        var start = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "cmd.exe"))
        {
            Arguments = "/d /c " + command, WorkingDirectory = directory, UseShellExecute = false,
            CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true,
        };
        using var process = Process.Start(start) ?? throw new InvalidOperationException("Synthetic fixture process could not start.");
        using var limit = CancellationTokenSource.CreateLinkedTokenSource(Token);
        limit.CancelAfter(TimeSpan.FromSeconds(10));
        try
        {
            var output = process.StandardOutput.ReadToEndAsync(limit.Token);
            var error = process.StandardError.ReadToEndAsync(limit.Token);
            await process.WaitForExitAsync(limit.Token);
            await output;
            await error;
            Assert.Equal(0, process.ExitCode);
        }
        finally
        {
            if (!process.HasExited) process.Kill(entireProcessTree: true);
        }
    }

    private sealed class NativeFixture : IDisposable
    {
        private readonly string _outputRoot;
        internal string Root { get; }
        internal string DestinationDirectory => Path.Combine(Root, "output");
        internal string FinalPath => Path.Combine(DestinationDirectory, "download.bin");
        internal string ExecutionDirectory => Path.Combine(Root, "locks");

        internal NativeFixture()
        {
            if (!OperatingSystem.IsWindows())
                throw new InvalidOperationException("Native download tests require an actual Windows filesystem.");
            _outputRoot = Path.TrimEndingDirectorySeparator(Path.GetFullPath(HarnessFixture.OutputRoot));
            Root = Path.Combine(_outputRoot, "download-native-fixtures", Guid.NewGuid().ToString("N"));
            RequireOwnedPath();
            for (DirectoryInfo? ancestor = new(_outputRoot); ancestor is not null; ancestor = ancestor.Parent)
            {
                string marker = Path.Combine(ancestor.FullName, ".git");
                if (File.Exists(marker) || Directory.Exists(marker))
                    throw new InvalidOperationException("Synthetic fixtures must be outside Git checkouts.");
            }
            Directory.CreateDirectory(DestinationDirectory);
            Directory.CreateDirectory(ExecutionDirectory);
        }

        private void RequireOwnedPath()
        {
            if (!Path.GetFullPath(Root).StartsWith(_outputRoot + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("Synthetic fixture escaped its isolated output root.");
        }

        public void Dispose()
        {
            RequireOwnedPath();
            Directory.Delete(Root, recursive: true); // Immediate cleanup also detects leaked file or ancestor handles.
        }
    }
}
