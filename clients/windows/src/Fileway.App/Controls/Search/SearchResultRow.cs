using System.Globalization;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;

namespace Fileway.App.Controls.Search;

/// <summary>A display projection which retains the controller's exact, original search hit.</summary>
public sealed class SearchResultRow
{
    public SearchResultRow(SearchHit hit)
    {
        ArgumentNullException.ThrowIfNull(hit);
        Hit = hit;
        var kind = hit.IsDirectory ? "文件夹" : DownloadPresentationPolicy.FormatBytes(hit.Size);
        Summary = hit.Modified is { } modified ? kind + " · " + FormatModified(modified) : kind;
    }

    public SearchHit Hit { get; }
    public string Name => Hit.DisplayName;
    public string DisplayPath => Hit.RelativeDisplayPath;
    public string Glyph => Hit.IsDirectory ? "\uE8B7" : "\uE7C3";
    public string IconName => Hit.IsDirectory ? "folder" : "file";
    public string Summary { get; }
    public bool HasIdentity => Hit.PathConfidence is SearchPathConfidence.ServerWire or SearchPathConfidence.LegacyUtf8
        && Hit.Resource is { WirePath.Length: > 0 };
    public string IdentityHint => HasIdentity ? "" : "仅展示 · 资源路径待确认，无法打开";
    public Visibility IdentityVisibility => HasIdentity ? Visibility.Collapsed : Visibility.Visible;
    public string AccessibleName => $"{Name}，{DisplayPath}，{Summary}，{(HasIdentity ? "打开前核对当前资源" : IdentityHint)}";
    public override string ToString() => "SearchResultRow (resource redacted)";

    private static string FormatModified(DateTimeOffset modified)
    {
        try { return modified.ToLocalTime().ToString("g", CultureInfo.CurrentCulture); }
        catch (Exception) { return modified.ToUniversalTime().ToString("u", CultureInfo.InvariantCulture); }
    }
}
