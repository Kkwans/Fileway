namespace Fileway.Core.Contracts;

public enum BrowserViewMode
{
    Mosaic,
    CompactGrid,
    WindowsIcons,
    Details,
    CompactList,
}

public enum BrowserIconSize
{
    Small,
    Medium,
    Large,
    ExtraLarge,
}

/// <summary>Filters the currently loaded directory; it is not a recursive server search.</summary>
public enum BrowserFileTypeFilter
{
    All,
    Image,
    Audio,
    Video,
    Pdf,
    Markdown,
    Config,
    Code,
}

/// <summary>Account/directory-owned local presentation. The two icon modes retain independent sizes.</summary>
public sealed record BrowserPreferences(
    BrowserViewMode ViewMode = BrowserViewMode.Mosaic,
    BrowserIconSize CompactGridSize = BrowserIconSize.Medium,
    BrowserIconSize WindowsIconSize = BrowserIconSize.Medium,
    BrowserFileTypeFilter TypeFilter = BrowserFileTypeFilter.All,
    FileSortField SortBy = FileSortField.Name,
    bool Descending = false);

public interface IBrowserPreferencesService
{
    Task<BrowserPreferences> ReadBrowserPreferencesAsync(RemoteResourceRef directory, CancellationToken cancellationToken);
    Task SaveBrowserPreferencesAsync(RemoteResourceRef directory, BrowserPreferences preferences, CancellationToken cancellationToken);
}
