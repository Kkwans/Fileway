using Fileway.Core.Contracts;

namespace Fileway.Playback.Contracts;

public interface IPlaybackSurface : IAsyncDisposable
{
    nint ParentHwnd { get; }
    ValueTask DetachAsync(CancellationToken cancellationToken);
}

public interface IPlaybackSurfaceFactory
{
    Task<IPlaybackSurface> CreateAsync(IPlaybackSession session, CancellationToken cancellationToken);
}

/// <summary>Native host window behavior which an embedded renderer cannot own.</summary>
public interface IPlaybackWindowControl
{
    event EventHandler<bool>? FullscreenChanged;
    ValueTask SetFullscreenAsync(bool fullscreen, CancellationToken cancellationToken);
    ValueTask ShowControlsAsync(CancellationToken cancellationToken);
}
