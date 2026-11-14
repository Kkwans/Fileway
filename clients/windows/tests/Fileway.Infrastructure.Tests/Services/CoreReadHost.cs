using System.Collections.Concurrent;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Tests.Services;

/// <summary>In-memory Host boundary for the new search/checksum service contracts.</summary>
internal sealed class CoreReadHost : IHostControlClient
{
    private int _sessions;
    internal bool Download { get; set; } = true;
    internal bool Create { get; set; } = true;
    internal bool Rename { get; set; } = true;
    internal bool Modify { get; set; } = true;
    internal bool Delete { get; set; } = true;
    internal bool WireOperations { get; set; } = true;
    internal bool SearchWirePathV1 { get; set; } = true;
    internal bool ConditionalDownloadV1 { get; set; }
    internal int UserReadStatus { get; set; } = 200;
    internal string CurrentToken { get; set; } = Jwt();
    internal ConcurrentQueue<HostCall> Calls { get; } = new();
    internal Func<HostCall, HostError?>? ErrorForCall { get; set; }
    internal Func<HostCall, CancellationToken, Task<object>>? Dispatch { get; set; }
    public HostHandshake Handshake { get; } = new(1, 0, 1, "fixture", "fixture", "fixture", JsonSerializer.SerializeToElement(new { }));
    public Task<HostExit> Completion { get; } = Task.FromResult(new HostExit(true, 0, null));

    public async Task<HostReply> CallAsync(HostCall request, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        Calls.Enqueue(request);
        if (ErrorForCall?.Invoke(request) is { } failure)
            return new HostReply("fixture", request.Generation, false, null, failure);
        object result;
        if (request.Operation == "open") result = "fixture-session-" + Interlocked.Increment(ref _sessions);
        else if (request.Operation == "login") result = Http(null, raw: CurrentToken);
        else if (request.Operation == "token") result = CurrentToken;
        else if (request.Operation == "close_session") result = new { };
        else if (request.Operation == "request" && Endpoint(request) == "/api/users/7") result = Http(new
        {
            id = 7, username = "fixture", perm = new { download = Download, create = Create, rename = Rename,
                modify = Modify, delete = Delete, execute = false, admin = true },
        }, UserReadStatus);
        else if (request.Operation == "request" && Endpoint(request) == "/api/client-capabilities")
            result = Http(new { authMethod = "json", searchWirePathV1 = SearchWirePathV1, resourceWireOperations = WireOperations,
                conditionalDownloadV1 = ConditionalDownloadV1 });
        else result = await (Dispatch?.Invoke(request, cancellationToken) ??
            Task.FromException<object>(new InvalidOperationException("Unexpected fixture command.")));
        return new HostReply("fixture", request.Generation, true, JsonSerializer.SerializeToElement(result), null);
    }

    internal static string Endpoint(HostCall call) => call.Parameters!.Value.GetProperty("endpoint").GetString()!;
    internal static object Http(object? value, int status = 200, string? raw = null) => new { status, body = raw ?? JsonSerializer.Serialize(value) };
    internal static object Metadata(string wire, bool directory = false, long size = 17,
        string modified = "2026-10-11T11:00:00.000000001Z", object? checksums = null, bool isSymlink = false) => new
    {
        wirePath = wire, name = wire == "/" ? "" : "fixture", path = wire == "/" ? "/" : "/fixture",
        isDir = directory, size, modified, type = directory ? "" : "file", checksums, isSymlink,
        items = Array.Empty<object>(),
    };
    internal static string Jwt(string revision = "initial") => "fixture." + Convert.ToBase64String(JsonSerializer.SerializeToUtf8Bytes(
        new { user = new { id = 7 }, revision })).TrimEnd('=').Replace('+', '-').Replace('/', '_') + ".signature";
    internal static ConnectionProfile Profile() => new(Guid.NewGuid(), Guid.NewGuid(), "核心读取测试", new Uri("https://fileway.invalid/nas"),
        NetworkMode.SystemNetwork, new ProxyPolicy(ProxyMode.Direct));
    internal static LoginInput Login { get; } = new(AuthenticationMode.Password, "fixture.user", "fixture.password");
    internal static object TaskRow(string id = "fixture-task", int owner = 7, string type = "file.copy", string status = "running",
        string? retryOf = null, long? archivedAt = null) => new
    {
        id, userId = owner, type, status, title = "fixture task", createdAt = 1_792_670_400_000L,
        totalItems = 2, processedItems = 1, totalBytes = 100, processedBytes = 50, retryOf, archivedAt,
        sourcePath = "/display-only-source", outputPath = "/display-only-output",
    };
    public Task ShutdownAsync(CancellationToken cancellationToken) => Task.CompletedTask;
    public ValueTask DisposeAsync() => ValueTask.CompletedTask;
}
