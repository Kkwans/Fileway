namespace Fileway.Core.Contracts;

public sealed record VideoSpriteGeometry(int Number, int Columns, int Width, int Height, double IntervalSeconds)
{
    public bool IsValid => Number is >= 1 and <= 100 && Columns >= 1 && Columns <= Math.Min(Number, 10) &&
        Width is >= 1 and <= 160 && Height is >= 1 and <= 90 && double.IsFinite(IntervalSeconds) && IntervalSeconds > 0;
    public int SheetWidth => checked(Columns * Width);
    public int SheetHeight => checked((Number + Columns - 1) / Columns * Height);
    public (int X, int Y) TileAt(TimeSpan position)
    {
        if (!IsValid) throw new InvalidOperationException("预览图尺寸无效。");
        var index = (int)Math.Clamp(Math.Floor(Math.Max(0, position.TotalSeconds) / IntervalSeconds), 0, Number - 1);
        return (index % Columns * Width, index / Columns * Height);
    }
}
public sealed record VideoSpriteAsset(VideoSpriteGeometry Geometry, IImageContent Sheet) : IAsyncDisposable
{
    public ValueTask DisposeAsync() => Sheet.DisposeAsync();
    public override string ToString() => "VideoSpriteAsset (source redacted)";
}
public interface IVideoSpriteService
{
    Task<VideoSpriteAsset?> OpenVideoSpriteAsync(RemoteResourceRef resource, CancellationToken cancellationToken);
}
public interface IPlaybackResourceSource { RemoteResourceRef? Resource { get; } }
