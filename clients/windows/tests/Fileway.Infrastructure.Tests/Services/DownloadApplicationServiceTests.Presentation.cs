using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Tests.Services;

public sealed partial class DownloadApplicationServiceTests
{
    [Fact]
    public async Task PlaybackPresentationIsOrderedWithoutInventingDurableCheckpointChanges()
    {
        await using var fixture = new PlaybackFixture();
        var task = await fixture.StartAsync();
        var first = await fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token);
        await first.Reader.DisposeAsync();
        var second = await fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token);
        await second.Reader.DisposeAsync();

        Assert.True(first.Item.PresentationRevision > 0);
        Assert.True(second.Item.PresentationRevision > first.Item.PresentationRevision);
        Assert.Equal(first.Item.Snapshot, second.Item.Snapshot);
    }

    [Fact]
    public async Task PendingCancellationHasNewPresentationOrderAtTheSameDurableRevision()
    {
        await using var fixture = new PlaybackFixture();
        var task = await fixture.StartAsync();
        var source = await fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token);
        await source.Reader.DisposeAsync();
        var release = Signal();
        fixture.Runner.Inner.AfterCancelIntent = async (_, _) => await release.Task;
        var canceling = new TaskCompletionSource<DownloadItem>(TaskCreationOptions.RunContinuationsAsynchronously);
        fixture.Downloads.TaskChanged += (_, item) =>
        {
            if (item.Status == DownloadStatus.Canceling) canceling.TrySetResult(item);
        };
        var cancel = fixture.Downloads.CancelAsync(fixture.Context, task, Token);
        try
        {
            var pending = await canceling.Task.WaitAsync(Timeout, Token);
            Assert.Equal(source.Item.Snapshot.Revision, pending.Snapshot.Revision);
            Assert.True(pending.PresentationRevision > source.Item.PresentationRevision);
        }
        finally { release.TrySetResult(); await cancel; }
    }
}
