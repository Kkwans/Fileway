namespace Fileway.Core.Contracts;

/// <summary>Immutable ownership; changing the endpoint or security route creates a new source revision.</summary>
public readonly record struct AccountKey(Guid ProfileId, Guid SourceRevision, string ServerUserId);

/// <summary>The wire path is opaque. Local path normalization must never be applied to it.</summary>
public sealed record RemoteResourceRef(
    AccountKey Account,
    string WirePath,
    string DisplayName,
    string DisplayPath,
    string? SourceVersion = null);

public enum NetworkMode
{
    SystemNetwork,
    SystemTailnet,
    EmbeddedTailnet,
}

public enum ProxyMode
{
    System,
    Direct,
    Manual,
}

public sealed record ProxyPolicy(ProxyMode Mode, Uri? ManualAddress = null);

public sealed record ConnectionProfile(
    Guid Id,
    Guid SourceRevision,
    string DisplayName,
    Uri BaseAddress,
    NetworkMode Network,
    ProxyPolicy Proxy);

public sealed record PermissionSet(
    bool Download,
    bool Create,
    bool Rename,
    bool Modify,
    bool Delete,
    bool Execute,
    bool Admin = false);

public sealed record ServerCapabilities(
    bool ResourcePaginationV1 = false,
    bool ConditionalDownloadV1 = false,
    bool SearchWirePathV1 = false,
    bool ResourceWireOperations = false,
    /// <summary>Server auth mode ("json"/"noauth"/...); empty on servers that predate the field.</summary>
    string AuthMethod = "",
    int MinimumPasswordLength = 0);

public sealed record ConnectionSession(
    Guid Id,
    AccountKey Account,
    string DisplayName,
    PermissionSet Permissions,
    ServerCapabilities Capabilities);
