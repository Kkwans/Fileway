using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Transfers;

namespace Fileway.Infrastructure.Tests.Transfers;

public sealed partial class WindowsDownloadFileAccessTests
{
    [Fact]
    public async Task DisposedPartialReopensOnlyWithItsOriginalIdentityAndOwnedDeletionIsIdempotent()
    {
        using var fixture = new NativeFixture();
        var access = new WindowsDownloadFileAccess();
        var first = await access.CreateFreshAsync(fixture.FinalPath, Token);
        TransferWorkFiles files = first.Files;
        try
        {
            await first.WriteAsync(Payload, Token);
            await first.FlushToDiskAsync(Token);
        }
        finally
        {
            await first.DisposeAsync();
            await first.DisposeAsync();
        }
        Assert.Equal(files.PartialIdentity, (await SnapshotAsync(fixture, files.PartialPath!)).NativeFileIdentity);

        await using (var reopened = await access.OpenOwnedAsync(fixture.FinalPath, files, Token))
        {
            Assert.False(reopened.IsPublished);
            Assert.Equal(files, reopened.Files);
            Assert.Equal(Payload.Length, reopened.Length);
            Assert.Equal(new DownloadLocalFileHash(Payload.Length, Sha256(Payload)), await reopened.CalculateSha256Async(Token));
            Assert.Throws<IOException>(() => File.Delete(files.PartialPath!));
            await reopened.DeleteOwnedPartialAsync(Token);
            await reopened.DeleteOwnedPartialAsync(Token);
            Assert.False(File.Exists(files.PartialPath));
        }
        Assert.Empty(Directory.GetFileSystemEntries(fixture.DestinationDirectory));
        var missing = await Assert.ThrowsAsync<FilewayException>(() => access.OpenOwnedAsync(fixture.FinalPath, files, Token));
        Assert.Equal(ErrorCode.OutcomeUnknown, missing.Error.Code);
        AssertSafeFailure(missing, fixture.Root);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task PublishedFinalReopensByOriginalIdentityEvenWhenItsPublishReceiptWasNotPersisted(bool persistPublication)
    {
        using var fixture = new NativeFixture();
        var access = new WindowsDownloadFileAccess();
        TransferWorkFiles evidence;
        string identity;
        await using (var first = await access.CreateFreshAsync(fixture.FinalPath, Token))
        {
            await first.WriteAsync(Payload, Token);
            await first.FlushToDiskAsync(Token);
            evidence = first.Files;
            identity = await first.PublishNoReplaceAsync(Token);
            if (persistPublication) evidence = first.Files;
        }
        Assert.Equal(evidence.PartialIdentity, identity);
        Assert.False(File.Exists(evidence.PartialPath));

        await using (var reopened = await access.OpenOwnedAsync(fixture.FinalPath, evidence, Token))
        {
            Assert.True(reopened.IsPublished);
            Assert.Equal(evidence with { PublishedFileIdentity = identity }, reopened.Files);
            Assert.Equal(Payload.Length, reopened.Length);
            Assert.Equal(new DownloadLocalFileHash(Payload.Length, Sha256(Payload)), await reopened.CalculateSha256Async(Token));
            var immutable = await Assert.ThrowsAsync<FilewayException>(() => reopened.WriteAsync(StrangerPayload, Token));
            Assert.Equal(ErrorCode.InvalidRequest, immutable.Error.Code);
            var protectedFinal = await Assert.ThrowsAsync<FilewayException>(() => reopened.DeleteOwnedPartialAsync(Token));
            Assert.Equal(ErrorCode.OutcomeUnknown, protectedFinal.Error.Code);
            AssertSafeFailure(protectedFinal, fixture.Root);
        }
        Assert.Equal(Payload, await File.ReadAllBytesAsync(fixture.FinalPath, Token));
        Assert.Equal(identity, (await SnapshotAsync(fixture, fixture.FinalPath)).NativeFileIdentity);
        Assert.Equal(fixture.FinalPath, Assert.Single(Directory.GetFiles(fixture.DestinationDirectory)));
    }

    [Theory]
    [InlineData("file")]
    [InlineData("directory")]
    [InlineData("junction")]
    public async Task ValidPartialCanBeReopenedAndCleanedUpAlongsideAnUnrelatedFinal(string targetKind)
    {
        using var fixture = new NativeFixture();
        var access = new WindowsDownloadFileAccess();
        TransferWorkFiles files;
        await using (var first = await access.CreateFreshAsync(fixture.FinalPath, Token))
        {
            files = first.Files;
            await first.WriteAsync(Payload, Token);
            await first.FlushToDiskAsync(Token);
        }
        string strangerPath;
        if (targetKind == "file")
        {
            strangerPath = fixture.FinalPath;
            await File.WriteAllBytesAsync(strangerPath, StrangerPayload, Token);
        }
        else
        {
            string directory = targetKind == "junction" ? Path.Combine(fixture.DestinationDirectory, "target") : fixture.FinalPath;
            Directory.CreateDirectory(directory);
            strangerPath = Path.Combine(directory, "marker.bin");
            await File.WriteAllBytesAsync(strangerPath, StrangerPayload, Token);
            if (targetKind == "junction")
                await RunFixtureCommandAsync(fixture.DestinationDirectory, "mklink /j download.bin target");
        }
        TransferSourceSnapshot stranger = await SnapshotAsync(fixture, strangerPath);
        try
        {
            using (var unrelated = targetKind == "file"
                ? new FileStream(fixture.FinalPath, FileMode.Open, FileAccess.ReadWrite, FileShare.None) : null)
            await using (var reopened = await access.OpenOwnedAsync(fixture.FinalPath, files, Token))
            {
                Assert.False(reopened.IsPublished);
                Assert.Equal(files, reopened.Files);
                Assert.Equal(new DownloadLocalFileHash(Payload.Length, Sha256(Payload)), await reopened.CalculateSha256Async(Token));
                await reopened.DeleteOwnedPartialAsync(Token);
                await reopened.DeleteOwnedPartialAsync(Token);
            }
            Assert.False(File.Exists(files.PartialPath));
            Assert.Equal(StrangerPayload, await File.ReadAllBytesAsync(strangerPath, Token));
            Assert.Equal(stranger, await SnapshotAsync(fixture, strangerPath));
            if (targetKind == "junction")
                Assert.True((File.GetAttributes(fixture.FinalPath) & FileAttributes.ReparsePoint) != 0);
        }
        finally
        {
            if (targetKind == "junction") Directory.Delete(fixture.FinalPath);
        }
    }

    [Fact]
    public async Task EqualBytesAndTimestampNeverAuthorizeReopeningAReplacedPartial()
    {
        using var fixture = new NativeFixture();
        var access = new WindowsDownloadFileAccess();
        TransferWorkFiles files;
        await using (var first = await access.CreateFreshAsync(fixture.FinalPath, Token))
        {
            files = first.Files;
            await first.WriteAsync(Payload, Token);
            await first.FlushToDiskAsync(Token);
        }
        TransferSourceSnapshot owned = await SnapshotAsync(fixture, files.PartialPath!);
        string retained = Path.Combine(fixture.Root, "retained-owned.bin");
        File.Move(files.PartialPath!, retained);
        await File.WriteAllBytesAsync(files.PartialPath!, Payload, Token);
        File.SetLastWriteTimeUtc(files.PartialPath!, owned.Modified!.Value.UtcDateTime);
        TransferSourceSnapshot stranger = await SnapshotAsync(fixture, files.PartialPath!);
        Assert.Equal(owned.Length, stranger.Length);
        Assert.Equal(owned.Modified, stranger.Modified);
        Assert.NotEqual(owned.NativeFileIdentity, stranger.NativeFileIdentity);

        var ambiguous = await Assert.ThrowsAsync<FilewayException>(() => access.OpenOwnedAsync(fixture.FinalPath, files, Token));

        Assert.Equal(ErrorCode.OutcomeUnknown, ambiguous.Error.Code);
        AssertSafeFailure(ambiguous, fixture.Root);
        Assert.Equal(stranger, await SnapshotAsync(fixture, files.PartialPath!));
        Assert.Equal(Payload, await File.ReadAllBytesAsync(files.PartialPath!, Token));
        Assert.Equal(owned, await SnapshotAsync(fixture, retained));
        Assert.Equal(Payload, await File.ReadAllBytesAsync(retained, Token));
        Assert.False(File.Exists(fixture.FinalPath));
    }

    [Fact]
    public async Task MissingPartialAndEqualUnrelatedFinalRemainOutcomeUnknownWithoutAdoptingTheFinal()
    {
        using var fixture = new NativeFixture();
        var access = new WindowsDownloadFileAccess();
        TransferWorkFiles files;
        await using (var first = await access.CreateFreshAsync(fixture.FinalPath, Token))
        {
            files = first.Files;
            await first.WriteAsync(Payload, Token);
            await first.FlushToDiskAsync(Token);
        }
        TransferSourceSnapshot owned = await SnapshotAsync(fixture, files.PartialPath!);
        string retained = Path.Combine(fixture.Root, "retained-owned.bin");
        File.Move(files.PartialPath!, retained);
        await File.WriteAllBytesAsync(fixture.FinalPath, Payload, Token);
        File.SetLastWriteTimeUtc(fixture.FinalPath, owned.Modified!.Value.UtcDateTime);
        TransferSourceSnapshot stranger = await SnapshotAsync(fixture, fixture.FinalPath);
        Assert.Equal(owned.Length, stranger.Length);
        Assert.Equal(owned.Modified, stranger.Modified);
        Assert.NotEqual(files.PartialIdentity, stranger.NativeFileIdentity);

        var ambiguous = await Assert.ThrowsAsync<FilewayException>(() => access.OpenOwnedAsync(fixture.FinalPath, files, Token));

        Assert.Equal(ErrorCode.OutcomeUnknown, ambiguous.Error.Code);
        AssertSafeFailure(ambiguous, fixture.Root);
        Assert.Equal(stranger, await SnapshotAsync(fixture, fixture.FinalPath));
        Assert.Equal(Payload, await File.ReadAllBytesAsync(fixture.FinalPath, Token));
        Assert.Equal(owned, await SnapshotAsync(fixture, retained));
        Assert.False(File.Exists(files.PartialPath));
    }

    [Fact]
    public async Task RecreatedPartialAlongsideAnOwnedPublishedFinalIsAmbiguousAndBothNamesArePreserved()
    {
        using var fixture = new NativeFixture();
        var access = new WindowsDownloadFileAccess();
        TransferWorkFiles beforePublish;
        TransferWorkFiles afterPublish;
        await using (var first = await access.CreateFreshAsync(fixture.FinalPath, Token))
        {
            await first.WriteAsync(Payload, Token);
            await first.FlushToDiskAsync(Token);
            beforePublish = first.Files;
            await first.PublishNoReplaceAsync(Token);
            afterPublish = first.Files;
        }
        await File.WriteAllBytesAsync(beforePublish.PartialPath!, StrangerPayload, Token);
        TransferSourceSnapshot final = await SnapshotAsync(fixture, fixture.FinalPath);
        TransferSourceSnapshot stranger = await SnapshotAsync(fixture, beforePublish.PartialPath!);
        Assert.Equal(beforePublish.PartialIdentity, final.NativeFileIdentity);
        Assert.NotEqual(beforePublish.PartialIdentity, stranger.NativeFileIdentity);
        foreach (TransferWorkFiles evidence in new[] { beforePublish, afterPublish })
        {
            var ambiguous = await Assert.ThrowsAsync<FilewayException>(() => access.OpenOwnedAsync(fixture.FinalPath, evidence, Token));
            Assert.Equal(ErrorCode.OutcomeUnknown, ambiguous.Error.Code);
            AssertSafeFailure(ambiguous, fixture.Root);
        }
        Assert.Equal(final, await SnapshotAsync(fixture, fixture.FinalPath));
        Assert.Equal(stranger, await SnapshotAsync(fixture, beforePublish.PartialPath!));
        Assert.Equal(Payload, await File.ReadAllBytesAsync(fixture.FinalPath, Token));
        Assert.Equal(StrangerPayload, await File.ReadAllBytesAsync(beforePublish.PartialPath!, Token));
        Assert.Equal(2, Directory.GetFiles(fixture.DestinationDirectory).Length);
    }

    [Fact]
    public async Task MissingEvidenceDoesNotCreateEitherNameAndReleasesAllGuardHandles()
    {
        using var fixture = new NativeFixture();
        var access = new WindowsDownloadFileAccess();
        TransferWorkFiles files;
        await using (var first = await access.CreateFreshAsync(fixture.FinalPath, Token)) files = first.Files;
        File.Delete(files.PartialPath!); // Remove only the positively known synthetic fixture after releasing its capability.

        var unknown = await Assert.ThrowsAsync<FilewayException>(() => access.OpenOwnedAsync(fixture.FinalPath, files, Token));

        Assert.Equal(ErrorCode.OutcomeUnknown, unknown.Error.Code);
        AssertSafeFailure(unknown, fixture.Root);
        Assert.Empty(Directory.GetFileSystemEntries(fixture.DestinationDirectory));
        string moved = Path.Combine(fixture.Root, "released-output");
        Directory.Move(fixture.DestinationDirectory, moved);
        Directory.Delete(moved); // A failed recovery must not pin any parent or partially opened native file.
    }

    [Fact]
    public async Task MalformedOrInconsistentPersistedEvidenceNeverCreatesRenamesOrDeletesFiles()
    {
        using var fixture = new NativeFixture();
        var access = new WindowsDownloadFileAccess();
        TransferWorkFiles files;
        await using (var first = await access.CreateFreshAsync(fixture.FinalPath, Token))
        {
            files = first.Files;
            await first.WriteAsync(Payload, Token);
            await first.FlushToDiskAsync(Token);
        }
        TransferSourceSnapshot owned = await SnapshotAsync(fixture, files.PartialPath!);
        TransferWorkFiles[] invalid =
        [
            new(), files with { PartialPath = null }, files with { PartialIdentity = null },
            files with { PartialIdentity = " " }, files with { PartialIdentity = files.PartialIdentity + "-other" },
            files with { PartialPath = "relative\\private.partial" }, files with { PartialPath = fixture.FinalPath },
            files with { PartialPath = Path.Combine(fixture.Root, Path.GetFileName(files.PartialPath!)) },
            files with { PartialPath = Path.Combine(fixture.DestinationDirectory, "private.partial") },
            files with { PublishedFileIdentity = files.PartialIdentity },
            files with { PublishedFileIdentity = files.PartialIdentity + "-other" },
            files with { OriginalTargetIdentity = files.PartialIdentity },
            files with { BackupPath = Path.Combine(fixture.DestinationDirectory, "private-backup.bin") },
            files with { BackupIdentity = files.PartialIdentity },
        ];
        foreach (TransferWorkFiles evidence in invalid)
        {
            var unknown = await Assert.ThrowsAsync<FilewayException>(() => access.OpenOwnedAsync(fixture.FinalPath, evidence, Token));
            Assert.Equal(ErrorCode.OutcomeUnknown, unknown.Error.Code);
            AssertSafeFailure(unknown, fixture.Root);
            Assert.Equal(files.PartialPath, Assert.Single(Directory.GetFiles(fixture.DestinationDirectory)));
        }
        Assert.Equal(owned, await SnapshotAsync(fixture, files.PartialPath!));
        Assert.Equal(Payload, await File.ReadAllBytesAsync(files.PartialPath!, Token));
        await using var reopened = await access.OpenOwnedAsync(fixture.FinalPath, files, Token);
        await reopened.DeleteOwnedPartialAsync(Token);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task MatchingNativeIdentityWithMultipleHardLinksIsRejectedUntilTheExtraFixtureLinkIsRemoved(bool published)
    {
        using var fixture = new NativeFixture();
        var access = new WindowsDownloadFileAccess();
        TransferWorkFiles files;
        await using (var first = await access.CreateFreshAsync(fixture.FinalPath, Token))
        {
            await first.WriteAsync(Payload, Token);
            await first.FlushToDiskAsync(Token);
            if (published) await first.PublishNoReplaceAsync(Token);
            files = first.Files;
        }
        string ownedPath = published ? fixture.FinalPath : files.PartialPath!;
        string original = Path.Combine(fixture.Root, "original.bin");
        string alias = Path.Combine(fixture.Root, "alias.bin");
        File.Move(ownedPath, original);
        await RunFixtureCommandAsync(fixture.Root, "mklink /h alias.bin original.bin");
        File.Move(alias, ownedPath);
        TransferSourceSnapshot linked = await SnapshotAsync(fixture, ownedPath);
        Assert.Equal(files.PartialIdentity, linked.NativeFileIdentity);
        Assert.Equal(linked, await SnapshotAsync(fixture, original));

        var unknown = await Assert.ThrowsAsync<FilewayException>(() => access.OpenOwnedAsync(fixture.FinalPath, files, Token));

        Assert.Equal(ErrorCode.OutcomeUnknown, unknown.Error.Code);
        AssertSafeFailure(unknown, fixture.Root);
        Assert.Equal(linked, await SnapshotAsync(fixture, ownedPath));
        Assert.Equal(linked, await SnapshotAsync(fixture, original));
        Assert.Equal(Payload, await File.ReadAllBytesAsync(ownedPath, Token));
        Assert.Equal(Payload, await File.ReadAllBytesAsync(original, Token));
        File.Delete(original); // Removing this owned fixture link also proves the rejected open leaked no file handle.
        await using (var recovered = await access.OpenOwnedAsync(fixture.FinalPath, files, Token))
        {
            Assert.Equal(published, recovered.IsPublished);
            Assert.Equal(new DownloadLocalFileHash(Payload.Length, Sha256(Payload)), await recovered.CalculateSha256Async(Token));
            if (published)
            {
                var protectedFinal = await Assert.ThrowsAsync<FilewayException>(() => recovered.DeleteOwnedPartialAsync(Token));
                Assert.Equal(ErrorCode.OutcomeUnknown, protectedFinal.Error.Code);
            }
            else await recovered.DeleteOwnedPartialAsync(Token);
        }
        Assert.Equal(published, File.Exists(ownedPath));
        if (published) Assert.Equal(files.PartialIdentity, (await SnapshotAsync(fixture, ownedPath)).NativeFileIdentity);
    }
}
