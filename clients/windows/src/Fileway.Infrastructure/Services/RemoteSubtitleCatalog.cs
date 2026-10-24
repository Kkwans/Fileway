using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

/// <summary>
/// Same-directory external subtitle discovery for a playing remote media file. Listing is bounded
/// and read-only; same-stem candidates sort first, then by name. No lease is acquired here.
/// </summary>
public sealed class RemoteSubtitleCatalog(IFileRepository files) : IRemoteSubtitleSource
{
    private const int Maximum = 50;
    private static readonly string[] Extensions = [".srt", ".ass", ".ssa", ".vtt", ".sub", ".idx"];

    public async Task<IReadOnlyList<RemoteSubtitleOption>> ListAsync(RemoteResourceRef media,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(media);
        var wire = media.WirePath;
        int slash = wire.LastIndexOf('/');
        if (slash < 1) return [];
        string wireParent = wire[..slash];
        string displayParent = media.DisplayPath is { Length: > 1 } display && display.Contains('/')
            ? display[..display.LastIndexOf('/')] : "";
        string displayParentName = displayParent.Length == 0 ? "/" :
            displayParent[(displayParent.LastIndexOf('/') + 1)..];
        if (displayParentName.Length == 0) displayParentName = "/";
        var parent = new RemoteResourceRef(media.Account, wireParent, displayParentName,
            displayParent.Length == 0 ? "/" : displayParent);
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
                if (item.IsDirectory || item.Resource.WirePath == media.WirePath) continue;
                string name = item.Resource.DisplayName;
                int extension = name.LastIndexOf('.');
                if (extension < 0 || !Extensions.Contains(name[extension..].ToLowerInvariant())) continue;
                bool sameStem = name.ToLowerInvariant().StartsWith(stemPrefix, StringComparison.Ordinal);
                options.Add((item.Resource, item.Size, sameStem, name));
                if (options.Count >= Maximum) break;
            }
            cursor = page.NextCursor;
        } while (cursor is not null && options.Count < Maximum);
        return options
            .OrderByDescending(option => option.SameStem)
            .ThenBy(option => option.Name, StringComparer.OrdinalIgnoreCase)
            .Select(option => new RemoteSubtitleOption(option.Resource, option.Size))
            .ToArray();
    }
}
