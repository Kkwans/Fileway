using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

/// <summary>
/// Same-directory external subtitle discovery for a playing remote media file. Listing is bounded
/// and read-only; same-stem candidates sort first, then by name. No lease is acquired here.
/// </summary>
public sealed class RemoteSubtitleCatalog(IFileRepository files) : IRemoteSubtitleSource, IRemoteEpisodeSource, IRemoteVideoSequence
{
    private const int Maximum = 50;
    private static readonly string[] Extensions = [".srt", ".ass", ".ssa", ".vtt", ".sub", ".idx"];
    private static readonly string[] VideoExtensions = [".mp4", ".mkv", ".mov", ".webm", ".avi", ".m4v", ".ts", ".m2ts", ".wmv", ".mpg", ".mpeg", ".flv"];
    private static readonly string[] MediaExtensions = [..VideoExtensions,
        ".mp3", ".flac", ".wav", ".aac", ".ogg", ".opus", ".m4a", ".wma", ".ape", ".mka", ".aiff"];

    public async Task<IReadOnlyList<RemoteSubtitleOption>> ListAsync(RemoteResourceRef media,
        CancellationToken cancellationToken)
    {
        var ordered = await ListMediaAsync(media, Extensions, static option => option, cancellationToken).ConfigureAwait(false);
        return ordered
            .Select((option, index) => (option, index))
            .OrderByDescending(entry => entry.option.SameStem)
            .ThenBy(entry => entry.index)
            .Select(entry => new RemoteSubtitleOption(entry.option.Resource, entry.option.Size))
            .ToArray();
    }

    public Task<IReadOnlyList<RemoteMediaOption>> ListEpisodesAsync(RemoteResourceRef media,
        CancellationToken cancellationToken) => ListMediaAsync(media, MediaExtensions,
        option => new RemoteMediaOption(option.Resource, option.Size, option.Resource.WirePath == media.WirePath), cancellationToken);

    public async Task<RemoteResourceRef?> FindNextVideoAsync(RemoteResourceRef current, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(current);
        int slash = current.WirePath.LastIndexOf('/');
        if (slash < 0 || slash == current.WirePath.Length - 1) return null;
        var parent = Parent(current, slash);
        string? cursor = null;
        var seenCursors = new HashSet<string>(StringComparer.Ordinal);
        bool found = false;
        do
        {
            var page = await files.ListAsync(parent, new DirectoryQuery(FileSortField.Name, Descending: false,
                Cursor: cursor), cancellationToken).ConfigureAwait(false);
            foreach (var item in page.Items)
            {
                cancellationToken.ThrowIfCancellationRequested();
                if (item.IsDirectory) continue;
                bool video = item.MediaType?.StartsWith("video/", StringComparison.OrdinalIgnoreCase) == true ||
                    VideoExtensions.Contains(Path.GetExtension(item.Resource.DisplayName), StringComparer.OrdinalIgnoreCase);
                if (item.Resource.WirePath == current.WirePath)
                {
                    if (!video) return null;
                    found = true;
                }
                else if (found && video) return item.Resource;
            }
            cursor = page.NextCursor;
        } while (cursor is not null && seenCursors.Count < 100 && seenCursors.Add(cursor));
        return null;
    }

    private static RemoteResourceRef Parent(RemoteResourceRef media, int slash)
    {
        string wire = slash == 0 ? "/" : media.WirePath[..slash];
        int displaySlash = media.DisplayPath.LastIndexOf('/');
        string display = displaySlash <= 0 ? "/" : media.DisplayPath[..displaySlash];
        string name = display == "/" ? "/" : display[(display.LastIndexOf('/') + 1)..];
        return new RemoteResourceRef(media.Account, wire, name, display);
    }

    private async Task<IReadOnlyList<TResult>> ListMediaAsync<TResult>(RemoteResourceRef media, string[] extensions,
        Func<(RemoteResourceRef Resource, long Size, bool SameStem, string Name), TResult> project,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(media);
        var wire = media.WirePath;
        int slash = wire.LastIndexOf('/');
        if (slash < 1) return [];
        var parent = Parent(media, slash);
        string stem = wire[(slash + 1)..];
        int dot = stem.LastIndexOf('.');
        if (dot > 0) stem = stem[..dot];
        string stemPrefix = stem.ToLowerInvariant();
        var options = new List<(RemoteResourceRef Resource, long Size, bool SameStem, string Name)>();
        string? cursor = null;
        do
        {
            var page = await files.ListAsync(parent, new DirectoryQuery(FileSortField.Name, Descending: false,
                Cursor: cursor), cancellationToken).ConfigureAwait(false);
            foreach (var item in page.Items)
            {
                cancellationToken.ThrowIfCancellationRequested();
                if (item.IsDirectory) continue;
                string name = item.Resource.DisplayName;
                int extension = name.LastIndexOf('.');
                if (extension < 0 || !extensions.Contains(name[extension..].ToLowerInvariant())) continue;
                bool sameStem = name.ToLowerInvariant().StartsWith(stemPrefix, StringComparison.Ordinal);
                options.Add((item.Resource, item.Size, sameStem, name));
                if (options.Count >= Maximum) break;
            }
            cursor = page.NextCursor;
        } while (cursor is not null && options.Count < Maximum);
        return options
            .OrderBy(option => option.Name, StringComparer.OrdinalIgnoreCase)
            .Select(project)
            .ToArray();
    }
}
