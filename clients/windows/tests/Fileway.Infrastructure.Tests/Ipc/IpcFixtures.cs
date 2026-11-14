using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Tests.Ipc;

internal sealed class HarnessFixture : IAsyncDisposable
{
    private static string RequiredPath(string name, bool file)
    {
        string value = Environment.GetEnvironmentVariable(name) ?? throw new InvalidOperationException("Required isolated test fixture is missing: " + name);
        if (!Path.IsPathFullyQualified(value) || (file ? !File.Exists(value) : !Directory.Exists(value)))
            throw new InvalidOperationException("Required isolated test fixture is unavailable: " + name);
        return value;
    }
    internal static string OutputRoot => RequiredPath("FILEWAY_TEST_OUTPUT_ROOT", false);
    internal static string HarnessExecutable => RequiredPath("FILEWAY_TEST_HARNESS_EXE", true);
    internal string DirectoryPath { get; }
    internal HostClientOptions Options { get; }
    internal string RequestSeen => Path.Combine(DirectoryPath, "request-seen.marker");

    internal HarnessFixture(string mode, int delayMilliseconds = 150)
    {
        DirectoryPath = Path.Combine(OutputRoot, "ipc-fixtures", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(DirectoryPath);
        string harness = Path.GetDirectoryName(HarnessExecutable)!;
        foreach (string file in Directory.GetFiles(harness)) File.Copy(file, Path.Combine(DirectoryPath, Path.GetFileName(file)));
        string executable = Path.Combine(DirectoryPath, "fileway-host.exe");
        File.Copy(HarnessExecutable, executable);
        File.WriteAllText(Path.Combine(DirectoryPath, "harness-mode.json"), JsonSerializer.Serialize(new { mode, delayMilliseconds }));
        byte[] hash = SHA256.HashData(File.ReadAllBytes(executable));
        File.WriteAllText(Path.Combine(DirectoryPath, "host-build.json"), JsonSerializer.Serialize(new
        {
            hostVersion = "harness", buildCommit = "unknown", workingTreeDirty = false, architecture = "win-x64",
            goVersion = "go version go1.26.6 windows/amd64", sha256 = Convert.ToHexString(hash).ToLowerInvariant(),
        }));
        Options = new HostClientOptions(executable, Path.Combine(DirectoryPath, "host-build.json"), TimeSpan.FromSeconds(5), TimeSpan.FromMilliseconds(500));
    }

    internal static HostClientOptions RealOptions => new(RequiredPath("FILEWAY_TEST_HOST_EXE", true),
        RequiredPath("FILEWAY_TEST_HOST_MANIFEST", true), TimeSpan.FromSeconds(10), TimeSpan.FromSeconds(5));

    public async ValueTask DisposeAsync()
    {
        for (int attempt = 0; ; attempt++)
        {
            try { Directory.Delete(DirectoryPath, true); return; }
            catch (Exception error) when (attempt < 10 && error is IOException or UnauthorizedAccessException)
            { await Task.Delay(50, TestContext.Current.CancellationToken); }
        }
    }
}

internal sealed record FixtureRequest(string Method, string Target, string Body, IReadOnlyDictionary<string, string> Headers);
internal sealed record FixtureResponse(int Status, byte[] Body, string ContentType = "application/json",
    IReadOnlyDictionary<string, string>? Headers = null);

internal sealed class HttpFixture : IAsyncDisposable
{
    private readonly TcpListener _listener = new(IPAddress.Loopback, 0);
    private readonly CancellationTokenSource _stop = new();
    private readonly ConcurrentBag<Task> _connections = [];
    private readonly Func<FixtureRequest, FixtureResponse?> _response;
    private readonly Task _accept;
    private int _count;
    internal Channel<FixtureRequest> Seen { get; } = Channel.CreateUnbounded<FixtureRequest>();
    internal int Count => Volatile.Read(ref _count);
    internal string BaseUrl { get; }

    internal HttpFixture(Func<FixtureRequest, FixtureResponse?>? response = null)
    {
        _response = response ?? (request => new FixtureResponse(request.Target.EndsWith("/forbidden", StringComparison.Ordinal) ? 403 : 200,
            Encoding.UTF8.GetBytes(request.Target == "/nas/api/login" ? "fixture.owned.token" : "{\"name\":\"电影🎬\"}")));
        _listener.Start();
        BaseUrl = $"http://127.0.0.1:{((IPEndPoint)_listener.LocalEndpoint).Port}";
        _accept = AcceptAsync();
    }

    private async Task AcceptAsync()
    {
        try
        {
            while (!_stop.IsCancellationRequested)
            {
                TcpClient client = await _listener.AcceptTcpClientAsync(_stop.Token);
                _connections.Add(HandleAsync(client));
            }
        }
        catch (OperationCanceledException) { }
        catch (SocketException) when (_stop.IsCancellationRequested) { }
    }

    private async Task HandleAsync(TcpClient client)
    {
        using (client)
        {
            try
            {
                NetworkStream stream = client.GetStream();
                string[] first = (await LineAsync(stream, _stop.Token)).Split(' ');
                var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                string line;
                while ((line = await LineAsync(stream, _stop.Token)).Length != 0)
                {
                    int colon = line.IndexOf(':');
                    headers.Add(line[..colon], line[(colon + 1)..].Trim());
                }
                int length = headers.TryGetValue("Content-Length", out string? text) ? int.Parse(text, System.Globalization.CultureInfo.InvariantCulture) : 0;
                byte[] body = new byte[length];
                await stream.ReadExactlyAsync(body, _stop.Token);
                var request = new FixtureRequest(first[0], first[1], Encoding.UTF8.GetString(body), headers);
                Interlocked.Increment(ref _count);
                await Seen.Writer.WriteAsync(request, _stop.Token);
                var response = _response(request);
                if (response?.Status == 0) return; // The fixture recorded the write, then lost its response.
                if (response is null)
                {
                    byte[] probe = new byte[1];
                    _ = await stream.ReadAsync(probe, _stop.Token); // Core cancellation closes the request connection.
                    return;
                }
                string extraHeaders = response.Headers is null ? "" : string.Concat(response.Headers.Select(header => $"{header.Key}: {header.Value}\r\n"));
                byte[] head = Encoding.ASCII.GetBytes($"HTTP/1.1 {response.Status} Fixture\r\nContent-Type: {response.ContentType}\r\nContent-Length: {response.Body.Length}\r\n{extraHeaders}Connection: close\r\n\r\n");
                await stream.WriteAsync(head, _stop.Token);
                await stream.WriteAsync(response.Body, _stop.Token);
            }
            catch (OperationCanceledException) when (_stop.IsCancellationRequested) { }
            catch (IOException) { }
        }
    }

    private static async Task<string> LineAsync(Stream stream, CancellationToken cancellationToken)
    {
        using var line = new MemoryStream();
        byte[] one = new byte[1];
        while (line.Length < 16384)
        {
            await stream.ReadExactlyAsync(one, cancellationToken);
            if (one[0] == '\n') return Encoding.ASCII.GetString(line.ToArray()).TrimEnd('\r');
            line.WriteByte(one[0]);
        }
        throw new IOException("Fixture header limit exceeded.");
    }

    public async ValueTask DisposeAsync()
    {
        _stop.Cancel();
        _listener.Stop();
        await _accept;
        await Task.WhenAll(_connections).WaitAsync(TimeSpan.FromSeconds(3), TestContext.Current.CancellationToken);
        _stop.Dispose();
    }
}
