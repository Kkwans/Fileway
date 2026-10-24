using Fileway.Core.Contracts;
using Fileway.Playback.Contracts;
using Microsoft.UI.Dispatching;

namespace Fileway.App.MediaSurfaces;

public sealed class PlaybackWindowFactory(DispatcherQueue dispatcher, IVideoSpriteService? sprites = null,
    IRemoteSubtitleSource? remoteSubtitles = null) : IPlaybackSurfaceFactory
{
    public Task<IPlaybackSurface> CreateAsync(IPlaybackSession session, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var completion = new TaskCompletionSource<IPlaybackSurface>(TaskCreationOptions.RunContinuationsAsynchronously);
        void Create()
        {
            if (cancellationToken.IsCancellationRequested) { completion.TrySetCanceled(cancellationToken); return; }
            try { var window = new PlayerWindow(session, sprites, remoteSubtitles); window.Activate(); completion.TrySetResult(window); }
            catch (Exception exception) { completion.TrySetException(exception); }
        }
        if (dispatcher.HasThreadAccess) Create();
        else if (!dispatcher.TryEnqueue(Create)) completion.TrySetException(new InvalidOperationException("播放窗口调度器已关闭。"));
        return completion.Task;
    }
}
