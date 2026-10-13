using System.Buffers.Binary;
using System.Text.Json;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Ipc;

namespace Fileway.HostTestHarness;

internal static class Program
{
    private static readonly SemaphoreSlim OutputGate = new(1, 1);
    private static readonly Dictionary<string, JsonElement> Waiting = new(StringComparer.Ordinal);
    private static readonly Stream Input = Console.OpenStandardInput();
    private static readonly Stream Output = Console.OpenStandardOutput();

    private static async Task Main(string[] arguments)
    {
        if (arguments.Length == 3 && arguments[0] == "parent")
        {
            string directory = arguments[1];
            await using var client = await GoHostClient.StartAsync(new HostClientOptions(Path.Combine(directory, "fileway-host.exe"), Path.Combine(directory, "host-build.json")), CancellationToken.None);
            await PidReceipt.PublishAsync(arguments[2], client.ProcessId, CancellationToken.None);
            await Task.Delay(Timeout.InfiniteTimeSpan);
            return;
        }
        using var configuration = JsonDocument.Parse(await File.ReadAllTextAsync(Path.Combine(AppContext.BaseDirectory, "harness-mode.json")));
        string mode = configuration.RootElement.GetProperty("mode").GetString()!;
        int delay = configuration.RootElement.TryGetProperty("delayMilliseconds", out var configuredDelay) ? configuredDelay.GetInt32() : 150;
        await PidReceipt.PublishAsync(Path.Combine(AppContext.BaseDirectory, "process.pid"), Environment.ProcessId, CancellationToken.None);
        while (true)
        {
            JsonElement request;
            try { request = await ReadAsync(); }
            catch (EndOfStreamException) { return; }
            string operation = request.GetProperty("op").GetString()!;
            if (operation == "init")
            {
                await ReplyAsync(request, new
                {
                    protocolMajor = 1, protocolMinor = 0, coreProtocol = mode == "wrong-core" ? 2 : 1,
                    hostVersion = "harness", buildCommit = mode == "wrong-manifest" ? "incorrect" : "unknown", goVersion = "go1.26.6",
                    module = new { path = "github.com/Kkwans/Fileway/clients/windows/host", version = "test" },
                    dependencies = new[] { new { path = "github.com/Kkwans/nas-file-browser-client/core", version = "v0.0.0" } },
                    buildSettings = new Dictionary<string, string> { ["GOOS"] = "windows", ["GOARCH"] = mode == "wrong-architecture" ? "arm64" : "amd64", ["vcs.modified"] = mode == "wrong-dirty" ? "true" : "false" },
                    capabilities = new { framing = "uint32-le-utf8-json", maxFrameBytes = HostProtocol.MaxFrameBytes, maxCoreCommandBytes = HostProtocol.MaxCoreCommandBytes, rawBody = false },
                }, major: mode == "wrong-major" ? 2 : 1);
                if (mode is "never-read" or "stdin-backpressure") await Task.Delay(Timeout.InfiniteTimeSpan);
                if (mode == "pause-then-no-response")
                    while (!File.Exists(Path.Combine(AppContext.BaseDirectory, "resume.marker"))) await Task.Delay(10);
                continue;
            }
            if (operation == "shutdown")
            {
                if (mode is "ignore-shutdown" or "truncated-response") continue;
                await ReplyAsync(request, null);
                return;
            }
            if (operation == "cancel")
            {
                if (mode == "cancel-failure")
                {
                    string target = request.GetProperty("parameters").GetProperty("targetRequestId").GetString()!;
                    if (Waiting.Remove(target, out var original)) await CanceledAsync(original);
                }
                await ReplyAsync(request, null);
                continue;
            }
            if (operation == "health")
            {
                if (mode == "environment")
                {
                    string[] names = Environment.GetEnvironmentVariables().Keys.Cast<string>().Order(StringComparer.Ordinal).ToArray();
                    await ReplyAsync(request, new { environmentNames = names });
                }
                else await ReplyAsync(request, new { state = "ready" });
                continue;
            }
            switch (mode)
            {
                case "bad-utf8":
                    await Output.WriteAsync(new byte[] { 1, 0, 0, 0, 0xff });
                    break;
                case "oversized":
                    byte[] oversized = new byte[4];
                    BinaryPrimitives.WriteUInt32LittleEndian(oversized, HostProtocol.MaxFrameBytes + 1);
                    await Output.WriteAsync(oversized);
                    break;
                case "truncated-response":
                    await Output.WriteAsync(new byte[] { 0, 0, 0, 1, (byte)'{' });
                    break;
                case "wrong-id": await ReplyAsync(request, null, requestId: "unrelated"); break;
                case "wrong-generation": await ReplyAsync(request, null, generation: request.GetProperty("generation").GetInt64() + 1); break;
                case "duplicate-json":
                    await WriteRawAsync(JsonSerializer.SerializeToUtf8Bytes(new { protocolMajor = 1, protocolMinor = 0,
                        requestId = request.GetProperty("requestId").GetString(), generation = request.GetProperty("generation").GetInt64(), ok = true, result = 1 })
                        .AsSpan().ToArray(), duplicateResult: true);
                    break;
                case "missing-result":
                    await WriteAsync(new { protocolMajor = 1, protocolMinor = 0, requestId = request.GetProperty("requestId").GetString(), generation = request.GetProperty("generation").GetInt64(), ok = true });
                    break;
                case "error-private":
                case "error-private-code":
                case "host-failure":
                case "duplicate-request":
                    await WriteAsync(new { protocolMajor = 1, protocolMinor = 0, requestId = request.GetProperty("requestId").GetString(), generation = request.GetProperty("generation").GetInt64(), ok = false,
                        error = "fixture.password", errorInfo = new { code = mode switch { "error-private-code" => "synthetic.private.token-http://private.invalid/stream/synthetic", "host-failure" => "HostFailure", "duplicate-request" => "DuplicateRequest", _ => "CoreRejected" }, message = "fixture.password", retryable = true } });
                    break;
                case "delayed-success":
                    _ = Task.Run(async () => { await Task.Delay(delay); await ReplyAsync(request, new { confirmed = true }); });
                    break;
                case "cancel-failure": Waiting.Add(request.GetProperty("requestId").GetString()!, request); break;
                case "no-response":
                case "pause-then-no-response": break;
                case "exit": Environment.Exit(7); break;
                case "stderr":
                    await Console.OpenStandardError().WriteAsync(new byte[2 << 20]);
                    await ReplyAsync(request, new { confirmed = true });
                    break;
                default: await ReplyAsync(request, new { confirmed = true }); break;
            }
            await File.WriteAllTextAsync(Path.Combine(AppContext.BaseDirectory, "request-seen.marker"), "received");
        }
    }

    private static async Task<JsonElement> ReadAsync()
    {
        byte[] header = new byte[4];
        await Input.ReadExactlyAsync(header);
        uint length = BinaryPrimitives.ReadUInt32LittleEndian(header);
        if (length is 0 or > HostProtocol.MaxFrameBytes) throw new InvalidDataException();
        byte[] payload = new byte[(int)length];
        await Input.ReadExactlyAsync(payload);
        using var document = JsonDocument.Parse(payload);
        return document.RootElement.Clone();
    }

    private static Task ReplyAsync(JsonElement request, object? result, int major = 1, string? requestId = null, long? generation = null) =>
        WriteAsync(new { protocolMajor = major, protocolMinor = 0, requestId = requestId ?? request.GetProperty("requestId").GetString(), generation = generation ?? request.GetProperty("generation").GetInt64(), ok = true, result });

    private static Task CanceledAsync(JsonElement request) => WriteAsync(new
    {
        protocolMajor = 1, protocolMinor = 0, requestId = request.GetProperty("requestId").GetString(), generation = request.GetProperty("generation").GetInt64(), ok = false,
        error = "synthetic private diagnostic", errorInfo = new { code = "Canceled", message = "synthetic private diagnostic", retryable = false },
    });

    private static async Task WriteAsync(object envelope)
    {
        byte[] payload = JsonSerializer.SerializeToUtf8Bytes(envelope);
        await WriteRawAsync(payload);
    }

    private static async Task WriteRawAsync(byte[] payload, bool duplicateResult = false)
    {
        if (duplicateResult)
            payload = System.Text.Encoding.UTF8.GetBytes(System.Text.Encoding.UTF8.GetString(payload)[..^1] + ",\"result\":2}");
        byte[] frame = new byte[payload.Length + 4];
        BinaryPrimitives.WriteUInt32LittleEndian(frame, (uint)payload.Length);
        payload.CopyTo(frame, 4);
        await OutputGate.WaitAsync();
        try { await Output.WriteAsync(frame); }
        finally { OutputGate.Release(); }
    }
}
