using System.Text.Json;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices
{
    public async Task<FileChecksumResult> CalculateChecksumAsync(RemoteResourceRef resource, ChecksumAlgorithm algorithm,
        string? expectedDigest, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(resource);
        (string key, int length) = algorithm switch
        {
            ChecksumAlgorithm.Md5 => ("md5", 32),
            ChecksumAlgorithm.Sha1 => ("sha1", 40),
            ChecksumAlgorithm.Sha256 => ("sha256", 64),
            ChecksumAlgorithm.Sha512 => ("sha512", 128),
            _ => throw Safe(ErrorCode.InvalidRequest, "请选择有效的校验算法。"),
        };
        string? expected = expectedDigest is null ? null : NormalizeChecksumHex(expectedDigest.Trim(), length, fromServer: false);
        _ = FileOperationWireIdentity(resource.WirePath, allowRoot: true);
        var state = GetResourceState(resource);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        await RefreshSessionAsync(state, linked.Token).ConfigureAwait(false);
        RequireDownload(state);
        using var beforeBody = await ResourceDocumentAsync(state, resource, true, linked.Token).ConfigureAwait(false);
        var before = ParseChecksumMetadata(beforeBody.RootElement, resource, state);
        RequireDownload(state);

        // This read releases Operations while Go hashes (its budget is fifteen
        // minutes). Listing/task polling/logout can progress independently.
        var response = await SessionLongReadAsync(state,
            "/api/resources" + resource.WirePath + "?metadata=1&checksum=" + key,
            TimeSpan.FromMinutes(16), linked.Token, requireDownload: true).ConfigureAwait(false);
        if (response.GetProperty("status").GetInt32() != 200) throw InvalidResponse();
        using var hashBody = ParseBody(response);
        var hashed = ParseChecksumMetadata(hashBody.RootElement, resource, state);
        RequireUnchangedChecksumSource(before, hashed);
        string digest;
        try
        {
            digest = NormalizeChecksumHex(RequiredText(hashBody.RootElement.GetProperty("checksums").GetProperty(key)), length, fromServer: true);
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or KeyNotFoundException)
        { throw InvalidResponse(); }

        using var afterBody = await ResourceDocumentAsync(state, resource, true, linked.Token).ConfigureAwait(false);
        var after = ParseChecksumMetadata(afterBody.RootElement, resource, state);
        RequireUnchangedChecksumSource(before, after);
        EnsureActive(state);
        RequireDownload(state);
        return new FileChecksumResult(resource, algorithm, digest, expected is null ? ChecksumComparison.NotRequested :
            StringComparer.Ordinal.Equals(expected, digest) ? ChecksumComparison.Matches : ChecksumComparison.Differs,
            after.File.Size, after.File.Modified);
    }

    private sealed record ChecksumMetadata(RemoteFile File, string Modified);

    private ChecksumMetadata ParseChecksumMetadata(JsonElement row, RemoteResourceRef requested, SessionState state)
    {
        try
        {
            var file = ParseFile(row, state, includeSourceVersion: true);
            if (FileOperationWireIdentity(file.Resource.WirePath, allowRoot: true) !=
                FileOperationWireIdentity(requested.WirePath, allowRoot: true) || file.Modified is null)
                throw InvalidResponse();
            if (file.IsDirectory) throw Safe(ErrorCode.InvalidRequest, "文件夹不支持文件校验和。");
            return new ChecksumMetadata(file, RequiredText(row.GetProperty("modified")));
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    private static void RequireUnchangedChecksumSource(ChecksumMetadata before, ChecksumMetadata after)
    {
        // Preserve the original timestamp text as well as the parsed value so
        // sub-tick server precision is not silently rounded away by .NET.
        if (before.File.Size != after.File.Size || before.Modified != after.Modified)
            throw Safe(ErrorCode.Conflict, "文件在计算期间已变化，请刷新后重试。", 409);
    }

    private static string NormalizeChecksumHex(string value, int length, bool fromServer)
    {
        if (value.Length != length || value.Any(character => character is not (>= '0' and <= '9' or >= 'a' and <= 'f' or >= 'A' and <= 'F')))
        {
            if (fromServer) throw InvalidResponse();
            throw Safe(ErrorCode.InvalidRequest, "预期校验和的长度或十六进制格式无效。");
        }
        return value.ToLowerInvariant();
    }
}
