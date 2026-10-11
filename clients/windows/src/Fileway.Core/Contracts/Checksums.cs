namespace Fileway.Core.Contracts;

public enum ChecksumAlgorithm { Md5, Sha1, Sha256, Sha512 }
public enum ChecksumComparison { NotRequested, Matches, Differs }
public sealed record FileChecksumResult(RemoteResourceRef Resource, ChecksumAlgorithm Algorithm,
    string Digest, ChecksumComparison Comparison, long Size, DateTimeOffset? Modified)
{
    public override string ToString() => "FileChecksumResult (resource redacted)";
}
public interface IFileChecksumService
{
    Task<FileChecksumResult> CalculateChecksumAsync(RemoteResourceRef resource, ChecksumAlgorithm algorithm,
        string? expectedDigest, CancellationToken cancellationToken);
}
