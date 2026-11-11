using System.Net.Http.Headers;
using System.Text.Json;
using System.Text.RegularExpressions;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Services;

/// <summary>Public release metadata only. No NAS session, cookies, download, or installer execution.</summary>
public sealed partial class GitHubAppUpdateService : IAppUpdateService
{
    private const string Project = "https://github.com/Kkwans/Fileway";
    private static readonly Uri Catalog = new("https://api.github.com/repos/Kkwans/Fileway/releases?per_page=100");
    private static readonly HttpClient Http = new(new HttpClientHandler { AllowAutoRedirect = false, UseCookies = false })
    {
        Timeout = Timeout.InfiniteTimeSpan,
        MaxResponseContentBufferSize = 4 * 1024 * 1024,
    };

    public async Task<WindowsRelease?> GetLatestWindowsReleaseAsync(CancellationToken cancellationToken)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(10));
        using var request = new HttpRequestMessage(HttpMethod.Get, Catalog);
        request.Headers.UserAgent.ParseAdd("Fileway-Windows");
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
        request.Headers.Add("X-GitHub-Api-Version", "2022-11-28");
        try
        {
            using var response = await Http.SendAsync(request, timeout.Token).ConfigureAwait(false);
            response.EnsureSuccessStatusCode();
            await using var body = await response.Content.ReadAsStreamAsync(timeout.Token).ConfigureAwait(false);
            using var document = await JsonDocument.ParseAsync(body, cancellationToken: timeout.Token).ConfigureAwait(false);
            if (document.RootElement.ValueKind != JsonValueKind.Array)
                throw new JsonException("Unexpected public release catalog.");
            WindowsRelease? latest = null;
            foreach (var row in document.RootElement.EnumerateArray())
            {
                var candidate = ParseRelease(row);
                if (candidate is not null && (latest is null || candidate.Version > latest.Version ||
                    candidate.Version == latest.Version && latest.Preview && !candidate.Preview))
                    latest = candidate;
            }
            return latest;
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            throw new FilewayException(new(ErrorCode.Timeout, "检查更新超时。请检查网络后重试。", Retryable: true));
        }
        catch (HttpRequestException)
        {
            throw new FilewayException(new(ErrorCode.NetworkUnavailable, "暂时无法读取 GitHub 发布目录，请稍后重试。", Retryable: true));
        }
        catch (JsonException)
        {
            throw new FilewayException(new(ErrorCode.ProtocolMismatch, "暂时无法识别 GitHub 发布目录，请稍后重试。", Retryable: true));
        }
    }

    private static WindowsRelease? ParseRelease(JsonElement row)
    {
        if (row.ValueKind != JsonValueKind.Object || !row.TryGetProperty("draft", out var draft) ||
            draft.ValueKind != JsonValueKind.False || !row.TryGetProperty("assets", out var assets) ||
            assets.ValueKind != JsonValueKind.Array)
            return null;
        var tag = Text(row, "tag_name");
        if (tag is null) return null;
        var match = Tag().Match(tag);
        if (!match.Success || !Version.TryParse(match.Groups["version"].Value, out var version)) return null;
        foreach (var asset in assets.EnumerateArray())
        {
            var name = Text(asset, "name");
            if (name is null || !Installer().IsMatch(name) || Installer().Match(name).Groups["version"].Value != version.ToString() ||
                Text(asset, "state") != "uploaded" || !asset.TryGetProperty("size", out var size) || size.ValueKind != JsonValueKind.Number ||
                !size.TryGetInt64(out var length) || length <= 0)
                continue;
            // Do not use an untrusted html_url or offer a link to a direct executable.
            var expectedAsset = $"{Project}/releases/download/{tag}/{name}";
            if (!string.Equals(Text(asset, "browser_download_url"), expectedAsset, StringComparison.OrdinalIgnoreCase))
                continue;
            bool preview = match.Groups["preview"].Success || name.Contains("-preview", StringComparison.OrdinalIgnoreCase) ||
                row.TryGetProperty("prerelease", out var prerelease) && prerelease.ValueKind == JsonValueKind.True;
            return new(version, preview, new Uri($"{Project}/releases/tag/{tag}"));
        }
        return null;
    }

    private static string? Text(JsonElement element, string property) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(property, out var value) &&
        value.ValueKind == JsonValueKind.String ? value.GetString() : null;

    [GeneratedRegex(@"^windows-(?<preview>preview-)?(?<version>[0-9]+\.[0-9]+\.[0-9]+)(?:-[0-9a-f]{7,40})?\z", RegexOptions.CultureInvariant)]
    private static partial Regex Tag();

    [GeneratedRegex(@"^Fileway-(?<version>[0-9]+\.[0-9]+\.[0-9]+)(?:-preview\.[0-9]+)?-win-x64(?:-unsigned(?:-preview)?)?-setup\.exe\z", RegexOptions.CultureInvariant | RegexOptions.IgnoreCase)]
    private static partial Regex Installer();
}
