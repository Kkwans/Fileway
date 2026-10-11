using System.Diagnostics;
using Fileway.Core.Contracts;
using Fileway.Infrastructure.Tests.Ipc;
using Fileway.Infrastructure.Transfers;

namespace Fileway.Infrastructure.Tests.Transfers;

public sealed class WindowsUploadFileAccessTests
{
    private static readonly byte[] Payload = [0, 1, 2, 3, 0xff, 0xfe, 0xfd, 0xfc];
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Fact]
    public async Task SourceAllowsCompatibleReadersAndSeekingButDeniesWritesDeletesAndRenames()
    {
        using var fixture = new NativeFixture();
        await File.WriteAllBytesAsync(fixture.SourcePath, Payload, Token);
        var access = new WindowsUploadFileAccess(fixture.ExecutionDirectory);
        await using var source = await access.OpenAsync(fixture.SourcePath, Token);
        Assert.True(source.Content.CanRead);
        Assert.True(source.Content.CanSeek);
        Assert.False(source.Content.CanWrite);
        Assert.Equal(Payload.Length, source.Snapshot.Length);
        Assert.NotNull(source.Snapshot.Modified);
        Assert.Equal(TimeSpan.Zero, source.Snapshot.Modified.Value.Offset);
        Assert.Equal(File.GetLastWriteTimeUtc(fixture.SourcePath), source.Snapshot.Modified.Value.UtcDateTime);
        Assert.False(string.IsNullOrEmpty(source.Snapshot.NativeFileIdentity));
        AssertSafeDisplay(source.ToString(), fixture.SourcePath);
        AssertSafeDisplay(source.Content.ToString(), fixture.SourcePath);
        AssertSafeDisplay(source.Snapshot.ToString(), fixture.SourcePath);
        AssertSafeDisplay(access.ToString(), fixture.ExecutionDirectory);

        using (var compatible = new FileStream(fixture.SourcePath, FileMode.Open, FileAccess.Read, FileShare.Read))
        {
            byte[] copy = new byte[Payload.Length];
            await compatible.ReadExactlyAsync(copy, Token);
            Assert.Equal(Payload, copy);
        }
        Assert.Throws<IOException>(() =>
        {
            using var writer = new FileStream(fixture.SourcePath, FileMode.Open, FileAccess.Write, FileShare.ReadWrite | FileShare.Delete);
        });
        Assert.Throws<IOException>(() => File.Delete(fixture.SourcePath));
        Assert.Throws<IOException>(() => File.Move(fixture.SourcePath, fixture.SourcePath + ".moved"));
        source.Content.Seek(Payload.Length - 2, SeekOrigin.Begin);
        byte[] tail = new byte[2];
        await source.Content.ReadExactlyAsync(tail, Token);
        Assert.Equal(Payload.AsSpan(Payload.Length - 2).ToArray(), tail);
        await source.VerifyAsync(source.Snapshot, Token);
    }

    [Fact]
    public async Task NativeIdentityDistinguishesEqualFilesAndFollowsTheFileAcrossHardLinks()
    {
        using var fixture = new NativeFixture();
        string original = Path.Combine(fixture.Root, "original.bin");
        string equal = Path.Combine(fixture.Root, "equal.bin");
        string alias = Path.Combine(fixture.Root, "alias.bin");
        await File.WriteAllBytesAsync(original, Payload, Token);
        await File.WriteAllBytesAsync(equal, Payload, Token);
        File.SetLastWriteTimeUtc(equal, File.GetLastWriteTimeUtc(original));
        await RunFixtureCommandAsync(fixture.Root, "mklink /h alias.bin original.bin");
        var access = new WindowsUploadFileAccess(fixture.ExecutionDirectory);
        await using var first = await access.OpenAsync(original, Token);
        await using var second = await access.OpenAsync(equal, Token);
        await using var linked = await access.OpenAsync(alias, Token);
        Assert.Equal(first.Snapshot.Length, second.Snapshot.Length);
        Assert.Equal(first.Snapshot.Modified, second.Snapshot.Modified);
        Assert.NotEqual(first.Snapshot.NativeFileIdentity, second.Snapshot.NativeFileIdentity);
        Assert.Equal(first.Snapshot, linked.Snapshot);
        var failure = await Assert.ThrowsAsync<FilewayException>(() => first.VerifyAsync(second.Snapshot, Token));
        Assert.Equal(ErrorCode.SourceChanged, failure.Error.Code);
        await first.VerifyAsync(linked.Snapshot, Token);
    }

    [Fact]
    public async Task VerifyRejectsWrongCheckpointLengthAndChangedHandleMetadata()
    {
        using var fixture = new NativeFixture();
        await File.WriteAllBytesAsync(fixture.SourcePath, Payload, Token);
        var access = new WindowsUploadFileAccess(fixture.ExecutionDirectory);
        await using (var source = await access.OpenAsync(fixture.SourcePath, Token))
        {
            var wrongLength = await Assert.ThrowsAsync<FilewayException>(() =>
                source.VerifyAsync(source.Snapshot with { Length = source.Snapshot.Length + 1 }, Token));
            Assert.Equal(ErrorCode.SourceChanged, wrongLength.Error.Code);
            File.SetLastWriteTimeUtc(fixture.SourcePath, source.Snapshot.Modified!.Value.UtcDateTime.AddMinutes(1));
            var changed = await Assert.ThrowsAsync<FilewayException>(() => source.VerifyAsync(source.Snapshot, Token));
            Assert.Equal(ErrorCode.SourceChanged, changed.Error.Code);
            AssertSafeFailure(changed, fixture.SourcePath);
        }

    }

    [Fact]
    public async Task RejectedSourcesLeaveNoPinnedFileOrDirectoryHandlesAndExposeOnlySafeErrors()
    {
        using var fixture = new NativeFixture();
        var access = new WindowsUploadFileAccess(fixture.ExecutionDirectory);
        string missing = Path.Combine(fixture.SourceDirectory, "private-missing.bin");
        var missingFailure = await Assert.ThrowsAsync<FilewayException>(() => access.OpenAsync(missing, Token));
        Assert.Equal(ErrorCode.SourceChanged, missingFailure.Error.Code);
        AssertSafeFailure(missingFailure, missing);
        var directoryFailure = await Assert.ThrowsAsync<FilewayException>(() => access.OpenAsync(fixture.SourceDirectory, Token));
        Assert.Equal(ErrorCode.InvalidRequest, directoryFailure.Error.Code);
        AssertSafeFailure(directoryFailure, fixture.SourceDirectory);

        string[] invalidPaths =
        [
            @"relative\private.bin", @"C:private.bin", @"\\.\pipe\private", @"\\?\C:\private.bin",
            Path.Combine(fixture.SourceDirectory, "..", "private.bin"), fixture.SourcePath + ":private-stream",
            Path.Combine(fixture.SourceDirectory, "NUL.bin"), Path.Combine(fixture.SourceDirectory, "COM¹.bin"),
            fixture.SourcePath + ".", fixture.SourcePath + " ", Path.Combine(fixture.SourceDirectory, "bad?.bin"),
        ];
        foreach (string path in invalidPaths)
        {
            var invalid = await Assert.ThrowsAsync<FilewayException>(() => access.OpenAsync(path, Token));
            Assert.Equal(ErrorCode.InvalidRequest, invalid.Error.Code);
            AssertSafeFailure(invalid, path);
        }
        await File.WriteAllBytesAsync(fixture.SourcePath, Payload, Token);
        using (var writer = new FileStream(fixture.SourcePath, FileMode.Open, FileAccess.ReadWrite, FileShare.ReadWrite | FileShare.Delete))
        {
            var busy = await Assert.ThrowsAsync<FilewayException>(() => access.OpenAsync(fixture.SourcePath, Token));
            Assert.Equal(ErrorCode.Busy, busy.Error.Code);
            AssertSafeFailure(busy, fixture.SourcePath);
            Assert.Equal(Payload.Length, writer.Length);
        }
        File.Delete(fixture.SourcePath);
        string moved = Path.Combine(fixture.Root, "unpinned-source");
        Directory.Move(fixture.SourceDirectory, moved);
        Directory.Delete(moved); // Failing after a native directory/file open must not leave either handle owned.
    }

    [Fact]
    public async Task DisposingSourceAndContentRepeatedlyReleasesTheReadHandle()
    {
        using var fixture = new NativeFixture();
        await File.WriteAllBytesAsync(fixture.SourcePath, Payload, Token);
        var source = await new WindowsUploadFileAccess(fixture.ExecutionDirectory).OpenAsync(fixture.SourcePath, Token);
        try
        {
            await source.Content.DisposeAsync();
            await source.DisposeAsync();
            await source.DisposeAsync();
            var closed = await Assert.ThrowsAsync<FilewayException>(() => source.VerifyAsync(source.Snapshot, Token));
            Assert.Equal(ErrorCode.SourceChanged, closed.Error.Code);
            AssertSafeFailure(closed, fixture.SourcePath);
            using (var exclusive = new FileStream(fixture.SourcePath, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
                Assert.Equal(Payload.Length, exclusive.Length);
            File.Delete(fixture.SourcePath);
        }
        finally { await source.DisposeAsync(); }
    }

    [Fact]
    public async Task LocksAreExclusiveAcrossInstancesAndIncludeEveryAccountFieldAndTheTaskId()
    {
        using var fixture = new NativeFixture();
        var firstProvider = new WindowsUploadFileAccess(fixture.ExecutionDirectory);
        var secondProvider = new WindowsUploadFileAccess(fixture.ExecutionDirectory);
        var task = Reference();
        await using var first = await firstProvider.AcquireAsync(task, Token);
        Assert.Equal(task, first.Task);
        AssertSafeDisplay(first.ToString(), task.Account.ServerUserId);
        var busy = await Assert.ThrowsAsync<FilewayException>(() => secondProvider.AcquireAsync(task, Token));
        Assert.Equal(ErrorCode.Busy, busy.Error.Code);
        AssertSafeFailure(busy, fixture.ExecutionDirectory);
        TransferTaskRef[] distinctOwners =
        [
            task with { Account = task.Account with { ProfileId = Guid.NewGuid() } },
            task with { Account = task.Account with { SourceRevision = Guid.NewGuid() } },
            task with { Account = task.Account with { ServerUserId = task.Account.ServerUserId + "-other" } },
            task with { Id = Guid.NewGuid() },
        ];
        foreach (var owner in distinctOwners)
        {
            await using var independent = await secondProvider.AcquireAsync(owner, Token);
            Assert.Equal(owner, independent.Task);
        }
        await first.DisposeAsync();
        await first.DisposeAsync();
        await using (var replacement = await secondProvider.AcquireAsync(task, Token))
            Assert.Equal(task, replacement.Task);
        foreach (string path in Directory.GetFiles(fixture.ExecutionDirectory)) Assert.Equal(0, new FileInfo(path).Length);
    }

    [Fact]
    public async Task LocksRejectInvalidOwnersAndPreserveNonemptyExistingContent()
    {
        using var fixture = new NativeFixture();
        var provider = new WindowsUploadFileAccess(fixture.ExecutionDirectory);
        var task = Reference();
        TransferTaskRef[] invalidOwners =
        [
            task with { Id = Guid.Empty }, task with { Account = task.Account with { ProfileId = Guid.Empty } },
            task with { Account = task.Account with { SourceRevision = Guid.Empty } },
            task with { Account = task.Account with { ServerUserId = " " } },
            task with { Account = task.Account with { ServerUserId = "private\0identity" } },
        ];
        foreach (var owner in invalidOwners)
        {
            var invalid = await Assert.ThrowsAsync<FilewayException>(() => provider.AcquireAsync(owner, Token));
            Assert.Equal(ErrorCode.InvalidRequest, invalid.Error.Code);
        }
        Assert.Empty(Directory.GetFiles(fixture.ExecutionDirectory));
        await using (var ownership = await provider.AcquireAsync(task, Token)) Assert.Equal(task, ownership.Task);
        string lockPath = Assert.Single(Directory.GetFiles(fixture.ExecutionDirectory));
        await File.WriteAllBytesAsync(lockPath, Payload, Token);
        var occupied = await Assert.ThrowsAsync<FilewayException>(() => provider.AcquireAsync(task, Token));
        Assert.Equal(ErrorCode.LocalStorageUnavailable, occupied.Error.Code);
        AssertSafeFailure(occupied, lockPath);
        Assert.Equal(Payload, await File.ReadAllBytesAsync(lockPath, Token));
        Assert.Single(Directory.GetFiles(fixture.ExecutionDirectory));
        File.Delete(lockPath); // A rejected existing file is closed, and cleanup is limited to this owned fixture.
    }

    [Fact]
    public async Task LockPinsItsDirectoryUntilReleasedAndMissingDirectoriesAreNotCreated()
    {
        using var fixture = new NativeFixture();
        string moved = Path.Combine(fixture.Root, "moved-locks");
        var provider = new WindowsUploadFileAccess(fixture.ExecutionDirectory);
        var task = Reference();
        await using (var ownership = await provider.AcquireAsync(task, Token))
        {
            Assert.Throws<IOException>(() => Directory.Move(fixture.ExecutionDirectory, moved));
            Assert.Equal(task, ownership.Task);
        }
        Directory.Move(fixture.ExecutionDirectory, moved);
        var missing = await Assert.ThrowsAsync<FilewayException>(() => provider.AcquireAsync(task, Token));
        Assert.Equal(ErrorCode.LocalStorageUnavailable, missing.Error.Code);
        Assert.False(Directory.Exists(fixture.ExecutionDirectory));
        await using var reacquired = await new WindowsUploadFileAccess(moved).AcquireAsync(task, Token);
        Assert.Equal(task, reacquired.Task);
    }

    [Fact]
    public async Task JunctionSourcesAndLockDirectoriesAreRejectedWithoutFollowingTheRedirect()
    {
        using var fixture = new NativeFixture();
        string target = Path.Combine(fixture.Root, "target");
        string redirect = Path.Combine(fixture.Root, "redirect");
        Directory.CreateDirectory(target);
        string targetFile = Path.Combine(target, "source.bin");
        await File.WriteAllBytesAsync(targetFile, Payload, Token);
        await RunFixtureCommandAsync(fixture.Root, "mklink /j redirect target");
        try
        {
            var access = new WindowsUploadFileAccess(fixture.ExecutionDirectory);
            string redirectedSource = Path.Combine(redirect, "source.bin");
            var sourceFailure = await Assert.ThrowsAsync<FilewayException>(() => access.OpenAsync(redirectedSource, Token));
            Assert.Equal(ErrorCode.InvalidRequest, sourceFailure.Error.Code);
            AssertSafeFailure(sourceFailure, redirectedSource);
            var finalReparse = await Assert.ThrowsAsync<FilewayException>(() => access.OpenAsync(redirect, Token));
            Assert.Equal(ErrorCode.InvalidRequest, finalReparse.Error.Code);
            var redirectedLocks = new WindowsUploadFileAccess(redirect);
            var lockFailure = await Assert.ThrowsAsync<FilewayException>(() => redirectedLocks.AcquireAsync(Reference(), Token));
            Assert.Equal(ErrorCode.LocalStorageUnavailable, lockFailure.Error.Code);
            AssertSafeFailure(lockFailure, redirect);
            Assert.Single(Directory.GetFiles(target));
            Assert.Equal(Payload, await File.ReadAllBytesAsync(targetFile, Token));
        }
        finally { Directory.Delete(redirect); } // Remove only the fixture junction, not its target.
    }

    [Fact]
    public async Task OrdinaryLongPathsWorkForSourcesAndTaskLocksWithoutExposingExtendedSyntax()
    {
        using var fixture = new NativeFixture();
        string directory = fixture.SourceDirectory;
        while (directory.Length < 300) directory = Path.Combine(directory, new string('d', 70));
        Directory.CreateDirectory(directory);
        string sourcePath = Path.Combine(directory, "long-source.bin");
        string locks = Path.Combine(directory, "private-locks");
        Directory.CreateDirectory(locks);
        await File.WriteAllBytesAsync(sourcePath, Payload, Token);
        Assert.True(sourcePath.Length > 260);
        var access = new WindowsUploadFileAccess(locks);
        await using (var source = await access.OpenAsync(sourcePath, Token))
        {
            byte[] read = new byte[Payload.Length];
            await source.Content.ReadExactlyAsync(read, Token);
            Assert.Equal(Payload, read);
            await source.VerifyAsync(source.Snapshot, Token);
            AssertSafeDisplay(source.ToString(), sourcePath);
            Assert.DoesNotContain(@"\\?\", source.ToString(), StringComparison.Ordinal);
        }
        var task = Reference();
        await using (var ownership = await access.AcquireAsync(task, Token))
        {
            var busy = await Assert.ThrowsAsync<FilewayException>(() => new WindowsUploadFileAccess(locks).AcquireAsync(task, Token));
            Assert.Equal(ErrorCode.Busy, busy.Error.Code);
            AssertSafeFailure(busy, locks);
            Assert.Equal(task, ownership.Task);
        }
        await using var replacement = await access.AcquireAsync(task, Token);
        Assert.Equal(task, replacement.Task);
    }

    private static TransferTaskRef Reference() => new(new AccountKey(Guid.NewGuid(), Guid.NewGuid(), "synthetic-private-owner"), Guid.NewGuid());

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
        // Commands contain only fixed relative fixture names; no private/externally supplied path enters cmd syntax.
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
        internal string SourceDirectory { get; }
        internal string SourcePath => Path.Combine(SourceDirectory, "source.bin");
        internal string ExecutionDirectory { get; }

        internal NativeFixture()
        {
            _outputRoot = Path.TrimEndingDirectorySeparator(Path.GetFullPath(HarnessFixture.OutputRoot));
            Root = Path.Combine(_outputRoot, "upload-native-fixtures", Guid.NewGuid().ToString("N"));
            SourceDirectory = Path.Combine(Root, "source");
            ExecutionDirectory = Path.Combine(Root, "locks");
            RequireOwnedPath();
            for (DirectoryInfo? ancestor = new(_outputRoot); ancestor is not null; ancestor = ancestor.Parent)
            {
                string gitMarker = Path.Combine(ancestor.FullName, ".git");
                if (File.Exists(gitMarker) || Directory.Exists(gitMarker))
                    throw new InvalidOperationException("Synthetic fixtures must be outside Git checkouts.");
            }
            Directory.CreateDirectory(SourceDirectory);
            Directory.CreateDirectory(ExecutionDirectory);
        }

        private void RequireOwnedPath()
        {
            if (!Path.GetFullPath(Root).StartsWith(_outputRoot + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("Synthetic fixture must stay within the isolated output root.");
        }

        public void Dispose()
        {
            RequireOwnedPath();
            Directory.Delete(Root, recursive: true); // Immediate cleanup also detects leaked source/ancestor/lock handles.
        }
    }
}
