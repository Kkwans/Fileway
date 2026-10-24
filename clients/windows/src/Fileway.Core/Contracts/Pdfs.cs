namespace Fileway.Core.Contracts;

/// <summary>Bounded raw bytes of one remote file, read fully into memory at open time.</summary>
public sealed record FileBytes(RemoteResourceRef Resource, long DeclaredLength, byte[] Bytes)
{
    public override string ToString() => "FileBytes (private bytes redacted)";
}

/// <summary>
/// Read-only byte fetch for client-side parsers (PDF rendering and similar). The budget bounds
/// memory; no partial reads, no write authority.
/// </summary>
public interface IFileBytesService
{
    Task<FileBytes> OpenAsync(RemoteResourceRef resource, long maximumBytes, CancellationToken cancellationToken);
}
