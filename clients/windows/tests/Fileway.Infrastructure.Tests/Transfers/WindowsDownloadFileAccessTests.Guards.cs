using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Transfers;

namespace Fileway.Infrastructure.Tests.Transfers;

public sealed partial class WindowsDownloadFileAccessTests
{
    private const string ForcedName = "fileway-download-v1-0123456789abcdef0123456789abcdef.partial";

    [Fact]
    public async Task GeneratedPartialNameCollisionNeverOpensTruncatesOrDeletesTheExistingObject()
    {
        using var fixture = new NativeFixture();
        string collisionPath = Path.Combine(fixture.DestinationDirectory, ForcedName);
        await File.WriteAllBytesAsync(collisionPath, StrangerPayload, Token);
        TransferSourceSnapshot stranger = await SnapshotAsync(fixture, collisionPath);
        var access = WithPartialName(() => ForcedName);
        using (var unrelated = new FileStream(collisionPath, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
        {
            var conflict = await Assert.ThrowsAsync<FilewayException>(() => access.CreateFreshAsync(fixture.FinalPath, Token));
            Assert.Equal(ErrorCode.Conflict, conflict.Error.Code);
            AssertSafeFailure(conflict, fixture.Root);
        }
        Assert.Equal(collisionPath, Assert.Single(Directory.GetFiles(fixture.DestinationDirectory)));
        Assert.Equal(StrangerPayload, await File.ReadAllBytesAsync(collisionPath, Token));
        Assert.Equal(stranger, await SnapshotAsync(fixture, collisionPath));
        Assert.False(File.Exists(fixture.FinalPath));
        string moved = Path.Combine(fixture.Root, "released-output");
        Directory.Move(fixture.DestinationDirectory, moved); // Exhausted create retries must release every native guard.
        Directory.Move(moved, fixture.DestinationDirectory);
    }

    [Fact]
    public async Task NameCollisionRetriesCreateOnlyAndTheNextFreshNameLeavesTheStrangerIntact()
    {
        using var fixture = new NativeFixture();
        string collisionPath = Path.Combine(fixture.DestinationDirectory, ForcedName);
        await File.WriteAllBytesAsync(collisionPath, StrangerPayload, Token);
        TransferSourceSnapshot stranger = await SnapshotAsync(fixture, collisionPath);
        const string availableName = "fileway-download-v1-abcdef0123456789abcdef0123456789.partial";
        int calls = 0;
        var access = WithPartialName(() => Interlocked.Increment(ref calls) == 1 ? ForcedName : availableName);
        TransferWorkFiles files;
        await using (var destination = await access.CreateFreshAsync(fixture.FinalPath, Token))
        {
            files = destination.Files;
            Assert.Equal(2, calls);
            Assert.Equal(Path.Combine(fixture.DestinationDirectory, availableName), files.PartialPath);
            Assert.NotEqual(stranger.NativeFileIdentity, files.PartialIdentity);
            await destination.WriteAsync(Payload, Token);
            await destination.FlushToDiskAsync(Token);
            await destination.DeleteOwnedPartialAsync(Token);
        }
        Assert.False(File.Exists(files.PartialPath));
        Assert.Equal(stranger, await SnapshotAsync(fixture, collisionPath));
        Assert.Equal(StrangerPayload, await File.ReadAllBytesAsync(collisionPath, Token));
        Assert.Equal(collisionPath, Assert.Single(Directory.GetFiles(fixture.DestinationDirectory)));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task GeneratedNameCollisionWithDirectoryOrJunctionPreservesTheEntireExistingEntry(bool junction)
    {
        using var fixture = new NativeFixture();
        string collisionPath = Path.Combine(fixture.DestinationDirectory, ForcedName);
        string target = junction ? Path.Combine(fixture.DestinationDirectory, "target") : collisionPath;
        Directory.CreateDirectory(target);
        string marker = Path.Combine(target, "marker.bin");
        await File.WriteAllBytesAsync(marker, StrangerPayload, Token);
        TransferSourceSnapshot stranger = await SnapshotAsync(fixture, marker);
        if (junction)
            await RunFixtureCommandAsync(fixture.DestinationDirectory,
                "mklink /j fileway-download-v1-0123456789abcdef0123456789abcdef.partial target");
        string[] before = Directory.GetFileSystemEntries(fixture.DestinationDirectory).Order(StringComparer.Ordinal).ToArray();
        try
        {
            var conflict = await Assert.ThrowsAsync<FilewayException>(() => WithPartialName(() => ForcedName).CreateFreshAsync(fixture.FinalPath, Token));
            Assert.Equal(ErrorCode.Conflict, conflict.Error.Code);
            AssertSafeFailure(conflict, fixture.Root);
            Assert.Equal(before, Directory.GetFileSystemEntries(fixture.DestinationDirectory).Order(StringComparer.Ordinal).ToArray());
            Assert.Equal(marker, Assert.Single(Directory.GetFiles(target)));
            Assert.Equal(StrangerPayload, await File.ReadAllBytesAsync(marker, Token));
            Assert.Equal(stranger, await SnapshotAsync(fixture, marker));
            Assert.Equal(junction, (File.GetAttributes(collisionPath) & FileAttributes.ReparsePoint) != 0);
            Assert.False(File.Exists(fixture.FinalPath));
        }
        finally
        {
            if (junction) Directory.Delete(collisionPath); // Remove only the fixture junction, never its target.
        }
        string moved = Path.Combine(fixture.Root, "released-output");
        Directory.Move(fixture.DestinationDirectory, moved);
        Directory.Move(moved, fixture.DestinationDirectory);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("private.partial")]
    [InlineData("..\\fileway-download-v1-0123456789abcdef0123456789abcdef.partial")]
    [InlineData("fileway-download-v1-0123456789abcdef0123456789abcde.partial")]
    [InlineData("fileway-download-v1-0123456789abcdef0123456789abcdeF.partial")]
    [InlineData("fileway-download-v1-0123456789abcdef0123456789abcdef.PARTIAL")]
    [InlineData("fileway-download-v1-0123456789abcdef0123456789abcdef.partial:stream")]
    public async Task InvalidGeneratedBasenamesAreRejectedBeforeFileCreationAndReleaseParentHandles(string? name)
    {
        using var fixture = new NativeFixture();
        var access = WithPartialName(() => name!);
        var invalid = await Assert.ThrowsAsync<FilewayException>(() => access.CreateFreshAsync(fixture.FinalPath, Token));
        Assert.Equal(ErrorCode.InvalidRequest, invalid.Error.Code);
        AssertSafeFailure(invalid, fixture.Root);
        Assert.Empty(Directory.GetFileSystemEntries(fixture.DestinationDirectory));
        string moved = Path.Combine(fixture.Root, "released-output");
        Directory.Move(fixture.DestinationDirectory, moved);
        Directory.Delete(moved);
    }

    [Fact]
    public async Task InvalidFinalPathsAndMissingParentsDoNotCreateFilesOrExposePrivateLocations()
    {
        using var fixture = new NativeFixture();
        var access = new WindowsDownloadFileAccess();
        string[] invalidPaths =
        [
            @"relative\private.bin", @"C:private.bin", @"\\.\pipe\private", @"\\?\C:\private.bin",
            @"\\synthetic-invalid-host\share\private.bin",
            Path.Combine(fixture.DestinationDirectory, "..", "private.bin"), fixture.FinalPath + ":private-stream",
            Path.Combine(fixture.DestinationDirectory, "NUL.bin"), Path.Combine(fixture.DestinationDirectory, "COM¹.bin"),
            fixture.FinalPath + ".", fixture.FinalPath + " ", Path.Combine(fixture.DestinationDirectory, "bad?.bin"),
        ];
        foreach (string path in invalidPaths)
        {
            var invalid = await Assert.ThrowsAsync<FilewayException>(() => access.CreateFreshAsync(path, Token));
            Assert.Equal(ErrorCode.InvalidRequest, invalid.Error.Code);
            AssertSafeFailure(invalid, path);
            Assert.Empty(Directory.GetFileSystemEntries(fixture.DestinationDirectory));
        }
        string missingDirectory = Path.Combine(fixture.Root, "missing-parent");
        var unavailable = await Assert.ThrowsAsync<FilewayException>(() => access.CreateFreshAsync(Path.Combine(missingDirectory, "private.bin"), Token));
        Assert.Equal(ErrorCode.LocalStorageUnavailable, unavailable.Error.Code);
        AssertSafeFailure(unavailable, missingDirectory);
        Assert.False(Directory.Exists(missingDirectory));
        Assert.Empty(Directory.GetFileSystemEntries(fixture.DestinationDirectory));
    }

    [Fact]
    public async Task JunctionAncestorsAreRejectedForFreshCreationAndRecoveryWithoutFollowingTheRedirect()
    {
        using var fixture = new NativeFixture();
        string target = Path.Combine(fixture.Root, "target");
        string redirect = Path.Combine(fixture.Root, "redirect");
        Directory.CreateDirectory(target);
        string actualFinal = Path.Combine(target, "download.bin");
        string redirectedFinal = Path.Combine(redirect, "download.bin");
        var access = new WindowsDownloadFileAccess();
        TransferWorkFiles files;
        await using (var owned = await access.CreateFreshAsync(actualFinal, Token))
        {
            files = owned.Files;
            await owned.WriteAsync(Payload, Token);
            await owned.FlushToDiskAsync(Token);
        }
        TransferSourceSnapshot ownedSnapshot = await SnapshotAsync(fixture, files.PartialPath!);
        await RunFixtureCommandAsync(fixture.Root, "mklink /j redirect target");
        try
        {
            var invalid = await Assert.ThrowsAsync<FilewayException>(() => access.CreateFreshAsync(redirectedFinal, Token));
            Assert.Equal(ErrorCode.InvalidRequest, invalid.Error.Code);
            AssertSafeFailure(invalid, redirectedFinal);
            TransferWorkFiles redirectedEvidence = files with { PartialPath = Path.Combine(redirect, Path.GetFileName(files.PartialPath!)) };
            var unknown = await Assert.ThrowsAsync<FilewayException>(() => access.OpenOwnedAsync(redirectedFinal, redirectedEvidence, Token));
            Assert.Equal(ErrorCode.OutcomeUnknown, unknown.Error.Code);
            AssertSafeFailure(unknown, redirect);
            Assert.False(File.Exists(actualFinal));
            Assert.Equal(files.PartialPath, Assert.Single(Directory.GetFiles(target)));
            Assert.Equal(ownedSnapshot, await SnapshotAsync(fixture, files.PartialPath!));
            Assert.Equal(Payload, await File.ReadAllBytesAsync(files.PartialPath!, Token));
        }
        finally { Directory.Delete(redirect); } // Delete only the fixture junction, never its target.
        string moved = Path.Combine(fixture.Root, "released-target");
        Directory.Move(target, moved); // Both rejected operations must release opened ancestors immediately.
    }

    [Fact]
    public async Task CapabilityPinsParentAndAncestorsButAllowsUnrelatedSiblingAtomicRenamesUntilDispose()
    {
        using var fixture = new NativeFixture();
        string localTemporary = Path.Combine(fixture.DestinationDirectory, "marker.tmp");
        string localReady = Path.Combine(fixture.DestinationDirectory, "marker.ready");
        string siblingTemporary = Path.Combine(fixture.Root, "sibling.tmp");
        string siblingReady = Path.Combine(fixture.Root, "sibling.ready");
        string movedOutput = Path.Combine(fixture.Root, "moved-output");
        string movedRoot = fixture.Root + "-moved";
        await File.WriteAllTextAsync(localTemporary, "new-local-marker", Token);
        await File.WriteAllTextAsync(localReady, "old-local-marker", Token);
        await File.WriteAllTextAsync(siblingTemporary, "new-sibling-marker", Token);
        await File.WriteAllTextAsync(siblingReady, "old-sibling-marker", Token);
        TransferWorkFiles files;
        try
        {
            await using (var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(fixture.FinalPath, Token))
            {
                files = destination.Files;
                await destination.WriteAsync(Payload, Token);
                await destination.FlushToDiskAsync(Token);
                File.Move(localTemporary, localReady, overwrite: true);
                File.Move(siblingTemporary, siblingReady, overwrite: true);
                Assert.Equal("new-local-marker", await File.ReadAllTextAsync(localReady, Token));
                Assert.Equal("new-sibling-marker", await File.ReadAllTextAsync(siblingReady, Token));
                Assert.Throws<IOException>(() => Directory.Move(fixture.DestinationDirectory, movedOutput));
                Assert.Throws<IOException>(() => Directory.Move(fixture.Root, movedRoot));
                Assert.True(Directory.Exists(fixture.DestinationDirectory));
                Assert.False(Directory.Exists(movedOutput));
                Assert.False(Directory.Exists(movedRoot));
            }
            Directory.Move(fixture.DestinationDirectory, movedOutput);
            string movedPartial = Path.Combine(movedOutput, Path.GetFileName(files.PartialPath!));
            Assert.Equal(Payload, await File.ReadAllBytesAsync(movedPartial, Token));
            Assert.Equal(files.PartialIdentity, (await SnapshotAsync(fixture, movedPartial)).NativeFileIdentity);
        }
        finally
        {
            // If a protection assertion fails, restore only these owned fixture directories before teardown.
            if (Directory.Exists(movedRoot) && !Directory.Exists(fixture.Root)) Directory.Move(movedRoot, fixture.Root);
            if (Directory.Exists(movedOutput) && !Directory.Exists(fixture.DestinationDirectory))
                Directory.Move(movedOutput, fixture.DestinationDirectory);
        }
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task UnicodeAndOrdinaryLongPathsPreserveExactBytesAndNativeIdentityWithoutExtendedSyntax(bool longPath)
    {
        using var fixture = new NativeFixture();
        string directory = Path.Combine(fixture.DestinationDirectory, "电影🎬-café-данные");
        if (longPath)
            while (directory.Length < 320) directory = Path.Combine(directory, new string('d', 70));
        Directory.CreateDirectory(directory);
        string final = Path.Combine(directory, "résumé-电影🎬.bin");
        if (longPath) Assert.True(final.Length > 260);
        TransferWorkFiles files;
        await using (var destination = await new WindowsDownloadFileAccess().CreateFreshAsync(final, Token))
        {
            files = destination.Files;
            AssertFreshEvidence(final, files);
            Assert.DoesNotContain(@"\\?\", files.PartialPath!, StringComparison.Ordinal);
            AssertSafeDisplay(destination.ToString(), final);
            await destination.WriteAsync(Payload, Token);
            Assert.Equal(Payload.Length, await destination.FlushToDiskAsync(Token));
            Assert.Equal(new DownloadLocalFileHash(Payload.Length, Sha256(Payload)), await destination.CalculateSha256Async(Token));
            Assert.Equal(files.PartialIdentity, await destination.PublishNoReplaceAsync(Token));
        }
        Assert.False(File.Exists(files.PartialPath));
        Assert.Equal(Payload, await File.ReadAllBytesAsync(final, Token));
        Assert.Equal(files.PartialIdentity, (await SnapshotAsync(fixture, final)).NativeFileIdentity);
        Assert.Equal(final, Assert.Single(Directory.GetFiles(directory)));
    }
}
