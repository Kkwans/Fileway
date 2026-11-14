using Fileway.App;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Tests.Transfers;

public sealed class DownloadPlaybackEntryTests
{
    [Theory]
    [InlineData("video.mp4")]
    [InlineData("VIDEO.MKV")]
    [InlineData("movie.webm")]
    [InlineData("clip.ts")]
    public void ActiveVideoCanOfferPlayback(string name)
    {
        var item = Item(name);
        Assert.True(DownloadPresentationPolicy.CanPlay(item));
        Assert.True(DownloadPresentationPolicy.CanPlay(item with
        {
            Status = DownloadStatus.Verifying,
            Snapshot = item.Snapshot with { Phase = TransferPhase.Verifying },
        }));
    }

    [Theory]
    [InlineData("notes.txt")]
    [InlineData("photo.png")]
    [InlineData("movie.mp4.txt")]
    [InlineData("")]
    public void RenamedDestinationDoesNotMakeSourceAVideo(string name)
    {
        Assert.False(DownloadPresentationPolicy.CanPlay(Item(name) with { Name = "video.mp4" }));
    }

    [Theory]
    [InlineData(DownloadStatus.Preparing, TransferPhase.Preparing)]
    [InlineData(DownloadStatus.Queued, TransferPhase.Queued)]
    [InlineData(DownloadStatus.Publishing, TransferPhase.Publishing)]
    [InlineData(DownloadStatus.Completed, TransferPhase.Completed)]
    [InlineData(DownloadStatus.Failed, TransferPhase.Failed)]
    [InlineData(DownloadStatus.Downloading, TransferPhase.Preparing)]
    [InlineData(DownloadStatus.Verifying, TransferPhase.Running)]
    public void OnlyMatchingActivePhasesOfferPlayback(DownloadStatus status, TransferPhase phase)
    {
        var item = Item("video.mp4");
        Assert.False(DownloadPresentationPolicy.CanPlay(item with { Status = status, Snapshot = item.Snapshot with { Phase = phase } }));
    }

    [Fact]
    public void InvalidAuthorityOrEvidenceDoesNotOfferPlayback()
    {
        var item = Item("video.mp4");
        var other = new AccountKey(Guid.NewGuid(), Guid.NewGuid(), "other");
        var rejected = new[]
        {
            item with { Context = item.Context with { SessionId = Guid.Empty } },
            item with { Context = item.Context with { ActivationId = Guid.Empty } },
            item with { Source = item.Source with { Account = other } },
            item with { Snapshot = item.Snapshot with { Task = new(other, Guid.NewGuid()) } },
            item with { Snapshot = item.Snapshot with { Generation = 0 } },
            item with { Snapshot = item.Snapshot with { Intent = TransferIntent.Cancel } },
            item with { Snapshot = item.Snapshot with { TotalBytes = 0 } },
            item with { Snapshot = item.Snapshot with { TotalBytes = null } },
            item with { Snapshot = item.Snapshot with { Error = new(ErrorCode.Unknown, "failed") } },
        };
        foreach (var invalid in rejected) Assert.False(DownloadPresentationPolicy.CanPlay(invalid));
    }

    private static DownloadItem Item(string name)
    {
        var account = new AccountKey(Guid.NewGuid(), Guid.NewGuid(), "fixture");
        var task = new TransferTaskRef(account, Guid.NewGuid());
        var context = new DownloadContext(account, Guid.NewGuid(), Guid.NewGuid());
        var source = new RemoteResourceRef(account, "/opaque", name, "fixture");
        var snapshot = new DownloadTaskSnapshot(task, TransferIntent.Run, TransferPhase.Running, 0, 1024,
            false, TransferUncertainty.None, null, 1, 1);
        return new(context, source, name, "fixture", DownloadStatus.Downloading, snapshot);
    }
}
