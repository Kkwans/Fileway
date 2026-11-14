using System.Diagnostics;
using System.Reflection;
using Fileway.Core.Contracts;
using Fileway.Infrastructure.Tests.Ipc;
using Fileway.Infrastructure.Transfers;
using Microsoft.Win32.SafeHandles;

namespace Fileway.Infrastructure.Tests.Transfers;

public sealed class WindowsUploadSourceVerificationTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Fact]
    public void RelativeLockCreateStaysWithOpenedDirectoryAfterItsOriginalPathIsReplacedAndRecoversFromSharingFailure()
    {
        using var fixture = new Fixture();
        string moved = Path.Combine(fixture.Root, "anchored-directory");
        var task = Reference();
        string lockName = Assert.IsType<string>(PrivateMethod("LockName").Invoke(null, [task]));
        // This fixture handle deliberately allows rename; the public guard's no-delete policy has its own test.
        using var directory = OpenRenameableFixtureDirectory(fixture.Locks);
        Assert.False(directory.IsInvalid);
        Directory.Move(fixture.Locks, moved);
        Directory.CreateDirectory(fixture.Locks);
        string anchoredFile = Path.Combine(moved, lockName);

        using (var created = OpenRelativeLock(directory, lockName))
        {
            Assert.False(created.IsInvalid);
            Assert.True(File.Exists(anchoredFile));
            Assert.Empty(Directory.GetFileSystemEntries(fixture.Locks));
            var invocation = Assert.Throws<TargetInvocationException>(() =>
            {
                using var rejected = OpenRelativeLock(directory, lockName);
            });
            var failure = Assert.IsType<FilewayException>(invocation.InnerException);
            Assert.Equal(ErrorCode.Busy, failure.Error.Code);
            Assert.Null(failure.InnerException);
            Assert.DoesNotContain(fixture.Root, failure.ToString(), StringComparison.OrdinalIgnoreCase);
            Assert.False(directory.IsClosed);
            Assert.False(directory.IsInvalid);
            Assert.Throws<IOException>(() =>
            {
                using var exclusive = new FileStream(anchoredFile, FileMode.Open, FileAccess.ReadWrite, FileShare.None);
            });
        }

        // FILE_OPEN_IF must reopen the same entry after the failed second acquisition and original close.
        using (var reopened = OpenRelativeLock(directory, lockName)) Assert.False(reopened.IsInvalid);
        Assert.Equal(0, new FileInfo(anchoredFile).Length);
        Assert.Equal(anchoredFile, Assert.Single(Directory.GetFiles(moved)));
        Assert.Empty(Directory.GetFileSystemEntries(fixture.Locks));
        File.Delete(anchoredFile); // Successful cleanup detects a leaked handle from either native call.
    }

    private static SafeFileHandle OpenRenameableFixtureDirectory(string path)
    {
        var contextType = Assert.IsAssignableFrom<Type>(typeof(WindowsUploadFileAccess).GetNestedType("AccessContext", BindingFlags.NonPublic));
        object storageContext = Enum.Parse(contextType, "Storage");
        // Existing product Win32 helper, with read/write/delete sharing only for this owned rename fixture.
        return Assert.IsType<SafeFileHandle>(PrivateMethod("OpenHandle").Invoke(null,
            [path, 0x80000000u, 7u, 3u, 0x02200000u, storageContext]));
    }

    private static SafeFileHandle OpenRelativeLock(SafeFileHandle directory, string lockName) =>
        Assert.IsType<SafeFileHandle>(PrivateMethod("OpenLockHandle").Invoke(null, [directory, lockName]));

    private static MethodInfo PrivateMethod(string name) => Assert.IsAssignableFrom<MethodInfo>(
        typeof(WindowsUploadFileAccess).GetMethod(name, BindingFlags.Static | BindingFlags.NonPublic));

    [Fact]
    public async Task HeldExecutionLockAllowsSiblingAtomicRenamesButPreventsDirectoryReplacementUntilReleased()
    {
        using var fixture = new Fixture();
        var provider = new WindowsUploadFileAccess(fixture.Locks);
        var task = Reference();
        string localTemporary = Path.Combine(fixture.Locks, "marker.tmp");
        string localReady = Path.Combine(fixture.Locks, "marker.ready");
        string siblingTemporary = Path.Combine(fixture.Root, "sibling.tmp");
        string siblingReady = Path.Combine(fixture.Root, "sibling.ready");
        string movedLocks = Path.Combine(fixture.Root, "moved-locks");
        string replacement = Path.Combine(fixture.Root, "replacement-locks");
        string movedRoot = fixture.Root + "-moved";
        Directory.CreateDirectory(replacement);
        await File.WriteAllTextAsync(localTemporary, "new-local-marker", Token);
        await File.WriteAllTextAsync(localReady, "old-local-marker", Token);
        await File.WriteAllTextAsync(siblingTemporary, "new-sibling-marker", Token);
        await File.WriteAllTextAsync(siblingReady, "old-sibling-marker", Token);

        try
        {
            await using (var ownership = await provider.AcquireAsync(task, Token))
            {
                File.Move(localTemporary, localReady, overwrite: true);
                File.Move(siblingTemporary, siblingReady, overwrite: true);
                Assert.Equal("new-local-marker", await File.ReadAllTextAsync(localReady, Token));
                Assert.Equal("new-sibling-marker", await File.ReadAllTextAsync(siblingReady, Token));
                Assert.False(File.Exists(localTemporary));
                Assert.False(File.Exists(siblingTemporary));

                // Replacing the execution directory requires vacating the guarded name first.
                Assert.Throws<IOException>(ReplaceExecutionDirectory);
                Assert.Throws<IOException>(() => Directory.Move(fixture.Root, movedRoot));
                Assert.True(Directory.Exists(fixture.Locks));
                Assert.True(Directory.Exists(replacement));
                Assert.False(Directory.Exists(movedLocks));
                Assert.False(Directory.Exists(movedRoot));
                var busy = await Assert.ThrowsAsync<FilewayException>(() => provider.AcquireAsync(task, Token));
                Assert.Equal(ErrorCode.Busy, busy.Error.Code);
                Assert.Equal(task, ownership.Task);
            }

            // The same public directory replacement succeeds only after the owner released its handles.
            ReplaceExecutionDirectory();
            Assert.False(Directory.Exists(replacement));
            Assert.Equal("new-local-marker", await File.ReadAllTextAsync(Path.Combine(movedLocks, "marker.ready"), Token));
            await using (var movedOwner = await new WindowsUploadFileAccess(movedLocks).AcquireAsync(task, Token))
                Assert.Equal(task, movedOwner.Task);
            await using var replacementOwner = await provider.AcquireAsync(task, Token);
            Assert.Equal(task, replacementOwner.Task);
        }
        finally
        {
            // Even a failed directory-protection assertion leaves cleanup inside the owned output root.
            if (Directory.Exists(movedRoot) && !Directory.Exists(fixture.Root)) Directory.Move(movedRoot, fixture.Root);
        }

        void ReplaceExecutionDirectory()
        {
            Directory.Move(fixture.Locks, movedLocks);
            Directory.Move(replacement, fixture.Locks);
        }
    }

    [Fact]
    public async Task EmptyHardLinkedLockIsRejectedWithoutChangingEitherNameAndCanBeReacquiredAfterUnlink()
    {
        using var fixture = new Fixture();
        var provider = new WindowsUploadFileAccess(fixture.Locks);
        var task = Reference();
        await using (var initial = await provider.AcquireAsync(task, Token)) Assert.Equal(task, initial.Task);
        string lockPath = Assert.Single(Directory.GetFiles(fixture.Locks));
        string original = Path.Combine(fixture.Root, "original.lock");
        string alias = Path.Combine(fixture.Root, "alias.lock");
        File.Move(lockPath, original);
        await CreateFixtureHardLinkAsync(fixture.Root);
        File.Move(alias, lockPath);

        var failure = await Assert.ThrowsAsync<FilewayException>(() => provider.AcquireAsync(task, Token));

        Assert.Equal(ErrorCode.LocalStorageUnavailable, failure.Error.Code);
        Assert.Null(failure.InnerException);
        Assert.DoesNotContain(fixture.Root, failure.ToString(), StringComparison.OrdinalIgnoreCase);
        Assert.Equal(0, new FileInfo(original).Length);
        Assert.Equal(0, new FileInfo(lockPath).Length);
        Assert.Single(Directory.GetFiles(fixture.Locks));
        File.Delete(original); // Only this fixture's alias is removed; rejection must have released all handles.
        await using var reacquired = await provider.AcquireAsync(task, Token);
        Assert.Equal(task, reacquired.Task);
    }

    [Fact]
    public async Task PreCanceledOperationsDoNotValidateInputsOpenSourcesOrCreateLocks()
    {
        using var fixture = new Fixture();
        var provider = new WindowsUploadFileAccess(fixture.Locks);
        using var canceled = CancellationTokenSource.CreateLinkedTokenSource(Token);
        await canceled.CancelAsync();

        var source = await Assert.ThrowsAnyAsync<OperationCanceledException>(() => provider.OpenAsync("invalid-relative-source", canceled.Token));
        var ownership = await Assert.ThrowsAnyAsync<OperationCanceledException>(() => provider.AcquireAsync(default, canceled.Token));

        Assert.Equal(canceled.Token, source.CancellationToken);
        Assert.Equal(canceled.Token, ownership.CancellationToken);
        Assert.Empty(Directory.GetFiles(fixture.Locks));
        Assert.Single(Directory.GetFileSystemEntries(fixture.Root));
        var task = Reference();
        await using var acquired = await provider.AcquireAsync(task, Token);
        Assert.Equal(task, acquired.Task);
    }

    private static TransferTaskRef Reference() => new(new(Guid.NewGuid(), Guid.NewGuid(), "verification-owner"), Guid.NewGuid());

    private static async Task CreateFixtureHardLinkAsync(string directory)
    {
        var start = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "cmd.exe"))
        {
            Arguments = "/d /c mklink /h alias.lock original.lock",
            WorkingDirectory = directory,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        using var process = Process.Start(start) ?? throw new InvalidOperationException("Synthetic hard-link fixture could not start.");
        using var budget = CancellationTokenSource.CreateLinkedTokenSource(Token);
        budget.CancelAfter(TimeSpan.FromSeconds(10));
        try
        {
            var output = process.StandardOutput.ReadToEndAsync(budget.Token);
            var error = process.StandardError.ReadToEndAsync(budget.Token);
            await process.WaitForExitAsync(budget.Token);
            await output;
            await error;
            Assert.Equal(0, process.ExitCode);
        }
        finally
        {
            if (!process.HasExited) process.Kill(entireProcessTree: true);
        }
    }

    private sealed class Fixture : IDisposable
    {
        private readonly string _outputRoot = Path.TrimEndingDirectorySeparator(Path.GetFullPath(HarnessFixture.OutputRoot));
        internal string Root { get; }
        internal string Locks => Path.Combine(Root, "locks");

        internal Fixture()
        {
            Root = Path.Combine(_outputRoot, "upload-independent-fixtures", Guid.NewGuid().ToString("N"));
            RequireOwnedPath();
            for (DirectoryInfo? ancestor = new(_outputRoot); ancestor is not null; ancestor = ancestor.Parent)
            {
                string marker = Path.Combine(ancestor.FullName, ".git");
                if (File.Exists(marker) || Directory.Exists(marker))
                    throw new InvalidOperationException("Synthetic fixtures must be outside Git checkouts.");
            }
            Directory.CreateDirectory(Locks);
        }

        private void RequireOwnedPath()
        {
            if (!Path.GetFullPath(Root).StartsWith(_outputRoot + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("Synthetic fixture escaped its isolated output root.");
        }

        public void Dispose()
        {
            RequireOwnedPath();
            Directory.Delete(Root, recursive: true);
        }
    }
}
