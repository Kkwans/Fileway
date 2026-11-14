using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Credentials;
using Fileway.Infrastructure.Persistence;
using Fileway.Infrastructure.Services;
using Fileway.Infrastructure.Tests.Persistence;

namespace Fileway.Infrastructure.Tests.Services;

public sealed partial class DownloadSourceServiceTests
{
    private const string Wire = "/%FF/literal%252F%20%2B.bin";
    private const string Capability = "http://127.0.0.1:43127/stream/download-capability_1";
    private const string Modified = "2026-10-11T11:00:00.000000001Z";
    private static readonly string[] LeaseFields = ["wirePath"];
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Fact]
    public async Task PreparationReadsAuthenticatedMetadataRatherThanCallerDisplayAliases()
    {
        await using var host = DownloadHost();
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        var resource = Source(session);

        var metadata = await Provider(services).PrepareDownloadAsync(resource, Token);

        Assert.Equal(session.Account, metadata.Resource.Account);
        Assert.Equal(Wire, metadata.Resource.WirePath);
        Assert.Equal("authenticated.bin", metadata.Resource.DisplayName);
        Assert.NotEqual(resource.DisplayPath, metadata.Resource.DisplayPath);
        Assert.Null(metadata.Resource.SourceVersion);
        Assert.False(metadata.IsDirectory);
        Assert.Equal(17, metadata.Size);
        Assert.NotNull(metadata.Modified);
        Assert.Equal(MetadataEndpoint, CoreReadHost.Endpoint(Assert.Single(MetadataCalls(host))));
        Assert.Empty(LeaseCalls(host));
        Assert.Empty(HashCalls(host));
    }

    [Theory]
    [InlineData(0, false)]
    [InlineData(17, false)]
    [InlineData(17, true)]
    public async Task AcquisitionIsUncachedFreshOnlyAndRequiresOnlyDownloadPermission(long size, bool capabilityFlag)
    {
        await using var host = DownloadHost(size);
        host.ConditionalDownloadV1 = capabilityFlag;
        host.Create = host.Modify = host.Delete = false;
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        var request = Request(session, size);

        await using var lease = await Provider(services).AcquireDownloadAsync(request, Token);

        Assert.Equal(capabilityFlag, session.Capabilities.ConditionalDownloadV1);
        Assert.Same(request.Source, lease.Resource);
        Assert.Equal(size, lease.Length);
        Assert.Null(lease.SourceVersion);
        Assert.False(lease.SupportsResume);
        Assert.Equal(new Uri(Capability), lease.Address);
        Assert.DoesNotContain(Capability, lease.ToString());
        Assert.DoesNotContain(Wire, request.ToString());
        var call = Assert.Single(LeaseCalls(host));
        Assert.Equal(HostCallSafety.Control, call.Safety);
        Assert.NotNull(call.Session);
        Assert.Equal(LeaseFields, call.Parameters!.Value.EnumerateObject().Select(field => field.Name).ToArray());
        Assert.Equal(Wire, call.Parameters.Value.GetProperty("wirePath").GetString());
        Assert.Single(MetadataCalls(host));
        Assert.Empty(HashCalls(host));
        Assert.DoesNotContain(host.Calls, call => call.Operation is "cache_configure" or "lease_stats" or "upload_lease");
    }

    [Fact]
    public async Task ConfiguredStorageDoesNotRegisterCacheUseOrCreateTransferOrJournalRecords()
    {
        using var directory = new StorageTestDirectory();
        await using var host = DownloadHost();
        var store = new InMemoryProfileStore();
        var defaults = new ClientStorageSettings(Path.Combine(directory.DirectoryPath, "cache"),
            Path.Combine(directory.DirectoryPath, "downloads"));
        await using var settings = new LocalStorageSettingsService(store, defaults, host);
        await using var services = new FilewayClientServices(host, store, new InMemoryCredentialVault(), settings);
        var session = await Connect(services);

        await using var lease = await Provider(services).AcquireDownloadAsync(Request(session), Token);

        Assert.Equal(LeaseFields, Assert.Single(LeaseCalls(host)).Parameters!.Value.EnumerateObject().Select(field => field.Name).ToArray());
        Assert.Empty(await store.ListCacheRootsAsync(Token));
        Assert.Empty((await store.ListTransfersAsync(session.Account, null, 20, Token)).Items);
        Assert.Empty((await store.ListOperationsAsync(session.Account, null, 20, Token)).Items);
        Assert.False(Directory.Exists(defaults.CacheDirectory));
        Assert.False(Directory.Exists(defaults.DownloadDirectory));
    }

    [Theory]
    [InlineData("prepare", false)]
    [InlineData("acquire", false)]
    [InlineData("checksum", false)]
    [InlineData("prepare", true)]
    [InlineData("acquire", true)]
    [InlineData("checksum", true)]
    public async Task PinnedInputIsRejectedBeforeAnyHostCallEvenWithAdvertisedCapability(string entry, bool capabilityFlag)
    {
        await using var host = DownloadHost();
        host.ConditionalDownloadV1 = capabilityFlag;
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        int before = host.Calls.Count;

        var error = await Assert.ThrowsAsync<FilewayException>(() => Invoke(services, entry,
            Source(session) with { SourceVersion = "private-pinned-version" }));

        Assert.Equal(ErrorCode.CapabilityMissing, error.Error.Code);
        Assert.DoesNotContain("private-pinned-version", error.Message);
        Assert.Equal(before, host.Calls.Count);
    }

    [Theory]
    [InlineData("prepare")]
    [InlineData("acquire")]
    [InlineData("checksum")]
    public async Task EmptyButNonNullPinnedInputIsAlsoRejected(string entry)
    {
        await using var host = DownloadHost();
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        int before = host.Calls.Count;

        var error = await Assert.ThrowsAsync<FilewayException>(() => Invoke(services, entry,
            Source(session) with { SourceVersion = "" }));

        Assert.Equal(ErrorCode.CapabilityMissing, error.Error.Code);
        Assert.Equal(before, host.Calls.Count);
    }

    [Theory]
    [InlineData("prepare")]
    [InlineData("acquire")]
    [InlineData("checksum")]
    public async Task MissingDownloadPermissionPreventsReadingSourceOrCreatingCapability(string entry)
    {
        await using var host = DownloadHost();
        host.Download = false;
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);

        var error = await Assert.ThrowsAsync<FilewayException>(() => Invoke(services, entry, Source(session)));

        Assert.Equal(ErrorCode.PermissionDenied, error.Error.Code);
        Assert.Empty(MetadataCalls(host));
        Assert.Empty(HashCalls(host));
        Assert.Empty(LeaseCalls(host));
    }

    [Theory]
    [InlineData("negative-length")]
    [InlineData("escaped-separator")]
    [InlineData("traversal")]
    [InlineData("directory-suffix")]
    [InlineData("query")]
    [InlineData("bad-escape")]
    public async Task InvalidRequestsFailBeforeHostDispatch(string scenario)
    {
        await using var host = DownloadHost();
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);
        var request = Request(session);
        request = scenario switch
        {
            "negative-length" => request with { Length = -1 },
            "escaped-separator" => request with { Source = request.Source with { WirePath = "/safe%2Ffile" } },
            "traversal" => request with { Source = request.Source with { WirePath = "/%2e%2e/file" } },
            "directory-suffix" => request with { Source = request.Source with { WirePath = "/dir/" } },
            "query" => request with { Source = request.Source with { WirePath = "/file?private=value" } },
            _ => request with { Source = request.Source with { WirePath = "/bad%escape" } },
        };
        int before = host.Calls.Count;

        var error = await Assert.ThrowsAsync<FilewayException>(() => Provider(services).AcquireDownloadAsync(request, Token));

        Assert.Equal(ErrorCode.InvalidRequest, error.Error.Code);
        Assert.Equal(before, host.Calls.Count);
    }

    [Theory]
    [InlineData("prepare", "version", ErrorCode.CapabilityMissing)]
    [InlineData("acquire", "version", ErrorCode.CapabilityMissing)]
    [InlineData("checksum", "version", ErrorCode.CapabilityMissing)]
    [InlineData("prepare", "directory", ErrorCode.InvalidRequest)]
    [InlineData("acquire", "directory", ErrorCode.InvalidRequest)]
    [InlineData("checksum", "directory", ErrorCode.InvalidRequest)]
    [InlineData("acquire", "identity", ErrorCode.ProtocolMismatch)]
    [InlineData("acquire", "size", ErrorCode.Conflict)]
    [InlineData("acquire", "negative-size", ErrorCode.ProtocolMismatch)]
    [InlineData("acquire", "malformed-version", ErrorCode.ProtocolMismatch)]
    public async Task InvalidOrVersionedMetadataCannotProduceAReadableCapability(string entry, string scenario, ErrorCode expected)
    {
        await using var host = DownloadHost();
        host.ConditionalDownloadV1 = true;
        host.Dispatch = (call, _) => Task.FromResult(CoreReadHost.Http(Metadata(
            size: scenario == "size" ? 18 : scenario == "negative-size" ? -1 : 17,
            directory: scenario == "directory", wire: scenario == "identity" ? "/other.bin" : Wire,
            version: scenario == "version" ? "private-server-version" : scenario == "malformed-version" ? 42 : null)));
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);

        var error = await Assert.ThrowsAsync<FilewayException>(() => Invoke(services, entry, Source(session)));

        Assert.Equal(expected, error.Error.Code);
        Assert.DoesNotContain("private-server-version", error.Message);
        Assert.Empty(LeaseCalls(host));
        Assert.Empty(HashCalls(host));
        Assert.DoesNotContain(host.Calls, call => call.Operation == "close_session");
    }

    [Fact]
    public async Task PermissionWithdrawnDuringMetadataPreventsCapabilityDispatch()
    {
        await using var host = DownloadHost();
        var initial = host.Dispatch!;
        host.Dispatch = (call, token) =>
        {
            if (IsMetadata(call))
            {
                host.CurrentToken = CoreReadHost.Jwt("withdrawn");
                host.Download = false;
            }
            return initial(call, token);
        };
        await using var services = new FilewayClientServices(host);
        var session = await Connect(services);

        var error = await Assert.ThrowsAsync<FilewayException>(() => Provider(services).AcquireDownloadAsync(Request(session), Token));

        Assert.Equal(ErrorCode.PermissionDenied, error.Error.Code);
        Assert.Empty(LeaseCalls(host));
    }

    private static CoreReadHost DownloadHost(long size = 17)
    {
        var host = new CoreReadHost();
        host.Dispatch = (call, _) => Task.FromResult<object>(call.Operation switch
        {
            "lease" => Capability,
            "revoke" => new { },
            "request" => CoreReadHost.Http(Metadata(size, checksums: IsHash(call)
                ? new Dictionary<string, string> { ["sha256"] = new string('a', 64) } : null)),
            _ => throw new InvalidOperationException("Unexpected download fixture command."),
        });
        return host;
    }

    private static object Metadata(long size = 17, bool directory = false, string wire = Wire,
        string modified = Modified, object? version = null, object? checksums = null) => new
    {
        wirePath = wire, name = "authenticated.bin", path = "/authenticated/endpoint.bin", isDir = directory,
        size, modified, type = "file", sourceVersion = version, checksums,
    };

    private static FilewayClientServices Provider(FilewayClientServices services) => services;
    private static Task<ConnectionSession> Connect(FilewayClientServices services, ConnectionProfile? profile = null) =>
        services.ConnectAsync(profile ?? CoreReadHost.Profile(), CoreReadHost.Login, Token);
    private static RemoteResourceRef Source(ConnectionSession session) => new(session.Account, Wire, "caller-display.bin", "/caller-display.bin");
    private static DownloadLeaseRequest Request(ConnectionSession session, long size = 17) => new(Source(session), size);
    private static string MetadataEndpoint => "/api/resources" + Wire + "?metadata=1";
    private static bool IsMetadata(HostCall call) => call.Operation == "request" && CoreReadHost.Endpoint(call) == MetadataEndpoint;
    private static bool IsHash(HostCall call) => call.Operation == "request" && CoreReadHost.Endpoint(call).Contains("checksum=", StringComparison.Ordinal);
    private static IEnumerable<HostCall> MetadataCalls(CoreReadHost host) => host.Calls.Where(IsMetadata);
    private static IEnumerable<HostCall> HashCalls(CoreReadHost host) => host.Calls.Where(IsHash);
    private static IEnumerable<HostCall> LeaseCalls(CoreReadHost host) => host.Calls.Where(call => call.Operation == "lease");
    private static Task Invoke(FilewayClientServices services, string entry, RemoteResourceRef source) => entry switch
    {
        "prepare" => Provider(services).PrepareDownloadAsync(source, Token),
        "acquire" => Provider(services).AcquireDownloadAsync(new(source, 17), Token),
        "checksum" => Provider(services).CalculateDownloadChecksumAsync(new(source, 17), Token),
        _ => throw new InvalidOperationException("Unknown download fixture entry point."),
    };
}
