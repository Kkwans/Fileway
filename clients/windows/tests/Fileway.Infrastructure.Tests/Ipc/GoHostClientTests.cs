using System.Diagnostics;
using System.Reflection;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Ipc;

[assembly: Xunit.v3.Parallelization(Mode = Xunit.Sdk.ParallelMode.None)]

namespace Fileway.Infrastructure.Tests.Ipc;

public sealed class GoHostClientTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;
    private static JsonElement Parameters(object value) => JsonSerializer.SerializeToElement(value);

    private static async Task WaitForRequestAsync(HarnessFixture fixture, CancellationToken cancellationToken)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(TimeSpan.FromSeconds(3));
        while (!File.Exists(fixture.RequestSeen)) await Task.Delay(10, deadline.Token);
    }

    private static async Task<string> OpenAsync(GoHostClient client, string baseUrl, CancellationToken cancellationToken)
    {
        HostReply reply = await client.CallAsync(new HostCall("open", Parameters(new { baseUrl, network = "direct" })), cancellationToken);
        Assert.True(reply.IsSuccess);
        return reply.Result!.Value.GetString()!;
    }

    [Fact]
    public async Task RealEnginePreservesUnicodeHttpStatusAndOwnsJson()
    {
        await using var fixture = new HttpFixture();
        await using var client = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        Assert.True(client.ProcessId > 0);
        Assert.Equal(1, client.Handshake.CoreProtocol);
        string session = await OpenAsync(client, fixture.BaseUrl + "/nas", Token);
        await client.CallAsync(new HostCall("login", Parameters(new { username = "fixture.user", password = "fixture.password" }), session), Token);
        HostReply reply;
        using (var parameters = JsonDocument.Parse("{\"method\":\"GET\",\"endpoint\":\"/api/resources/%E7%94%B5%E5%BD%B1\"}"))
        {
            Task<HostReply> pending = client.CallAsync(new HostCall("request", parameters.RootElement, session, long.MaxValue), Token);
            parameters.Dispose();
            reply = await pending;
        }
        Assert.True(reply.IsSuccess);
        Assert.Equal(long.MaxValue, reply.Generation);
        Assert.Contains("电影🎬", reply.Result!.Value.GetProperty("body").GetString());
        HostReply denied = await client.CallAsync(new HostCall("request", Parameters(new { method = "GET", endpoint = "/api/forbidden" }), session), Token);
        Assert.True(denied.IsSuccess);
        Assert.Equal(403, denied.Result!.Value.GetProperty("status").GetInt32());
        Assert.Contains("电影🎬", reply.Result.Value.GetProperty("body").GetString());
        Assert.DoesNotContain("fixture.password", new HostCall("login", Parameters(new { password = "fixture.password" })).ToString());
        Assert.DoesNotContain("电影", reply.ToString());
        await client.ShutdownAsync(Token);
        Assert.True((await client.Completion.WaitAsync(TimeSpan.FromSeconds(2), Token)).Expected);
    }

    [Fact]
    public async Task RealEightWorkersQueueAndControlsRemainResponsive()
    {
        await using var fixture = new HttpFixture(_ => null);
        await using var client = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        string session = await OpenAsync(client, fixture.BaseUrl, Token);
        var active = new List<Task<HostReply>>();
        for (int i = 0; i < 8; i++) active.Add(client.CallAsync(new HostCall("request", Parameters(new { method = "GET", endpoint = "/api/resources/active" }), session), Token));
        for (int i = 0; i < 8; i++) await fixture.Seen.Reader.ReadAsync(Token).AsTask().WaitAsync(TimeSpan.FromSeconds(3), Token);
        using var queuedCancellation = CancellationTokenSource.CreateLinkedTokenSource(Token);
        Task<HostReply> canceled = client.CallAsync(new HostCall("request", Parameters(new { method = "GET", endpoint = "/api/resources/canceled" }), session), queuedCancellation.Token);
        var queued = new List<Task<HostReply>>();
        for (int i = 0; i < 63; i++) queued.Add(client.CallAsync(new HostCall("request", Parameters(new { method = "GET", endpoint = "/api/resources/queued" }), session), Token));
        var overflow = await Assert.ThrowsAsync<FilewayException>(() => client.CallAsync(new HostCall("request", Parameters(new { method = "GET", endpoint = "/api/resources/overflow" }), session), Token));
        Assert.Equal(ErrorCode.Busy, overflow.Error.Code);
        // CallAsync only enters the local queue; priority controls can overtake business writes.
        // Prove Host acceptance before asserting that every queued request was canceled by close.
        using var acceptance = CancellationTokenSource.CreateLinkedTokenSource(Token);
        acceptance.CancelAfter(TimeSpan.FromSeconds(3));
        while (true)
        {
            HostReply health = await client.CallAsync(new HostCall("health", Safety: HostCallSafety.Control), acceptance.Token);
            Assert.True(health.IsSuccess);
            JsonElement state = health.Result!.Value;
            Assert.Equal("ready", state.GetProperty("state").GetString());
            if (state.GetProperty("activeBusiness").GetInt32() == 8 && state.GetProperty("queuedBusiness").GetInt32() == 64) break;
            await Task.Delay(10, acceptance.Token);
        }
        queuedCancellation.Cancel();
        try { HostReply canceledReply = await canceled; Assert.Equal("Canceled", canceledReply.Error?.Code); }
        catch (FilewayException error) { Assert.Equal(ErrorCode.Canceled, error.Error.Code); }
        HostReply closed = await client.CallAsync(new HostCall("close_session", Session: session, Safety: HostCallSafety.Control), Token);
        Assert.True(closed.IsSuccess);
        foreach (Task<HostReply> task in active.Concat(queued)) Assert.Equal("Canceled", (await task).Error?.Code);
        Assert.Equal(8, fixture.Count);
        HostReply afterClose = await client.CallAsync(new HostCall("request", Parameters(new { method = "GET", endpoint = "/api/resources/closed" }), session), Token);
        Assert.False(afterClose.IsSuccess);
        Assert.Equal("InvalidRequest", afterClose.Error?.Code);
        Assert.Equal(8, fixture.Count);
    }

    [Fact]
    public async Task LocallyQueuedBusinessMayReachHostAfterPriorityClose()
    {
        await using var fixture = new HttpFixture(_ => null);
        await using var client = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        string session = await OpenAsync(client, fixture.BaseUrl, Token);
        var active = new List<Task<HostReply>>();
        for (int i = 0; i < 8; i++) active.Add(client.CallAsync(new HostCall("request", Parameters(new { method = "GET", endpoint = "/api/resources/active" }), session), Token));
        for (int i = 0; i < 8; i++) await fixture.Seen.Reader.ReadAsync(Token).AsTask().WaitAsync(TimeSpan.FromSeconds(3), Token);

        var local = new List<Task<HostReply>>();
        Task<HostReply> close;
        object gate = typeof(GoHostClient).GetField("_gate", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(client)!;
        lock (gate)
        {
            // Queue uses the same reentrant gate; the writer cannot mark another business frame as writing.
            // It can have prefetched at most one frame before the priority close becomes available.
            for (int i = 0; i < 64; i++) local.Add(client.CallAsync(new HostCall("request", Parameters(new { method = "GET", endpoint = "/api/resources/local" }), session), Token));
            close = client.CallAsync(new HostCall("close_session", Session: session, Safety: HostCallSafety.Control), Token);
        }

        Assert.True((await close).IsSuccess);
        foreach (var request in active) Assert.Equal("Canceled", (await request).Error?.Code);
        HostReply[] replies = await Task.WhenAll(local);
        int late = replies.Count(reply => reply.Error?.Code == "InvalidRequest");
        Assert.InRange(late, 63, 64);
        Assert.Equal(64 - late, replies.Count(reply => reply.Error?.Code == "Canceled"));
        Assert.Equal(8, fixture.Count);
    }

    [Theory]
    [InlineData("wrong-major")]
    [InlineData("wrong-core")]
    [InlineData("wrong-manifest")]
    [InlineData("wrong-architecture")]
    [InlineData("wrong-dirty")]
    public async Task StartupRejectsIncompatibleHandshakeAndCleansChild(string mode)
    {
        await using var harness = new HarnessFixture(mode);
        var error = await Assert.ThrowsAsync<FilewayException>(() => GoHostClient.StartAsync(harness.Options, Token));
        Assert.Equal(ErrorCode.ProtocolMismatch, error.Error.Code);
        Assert.DoesNotContain(harness.DirectoryPath, error.ToString());
        int pid = int.Parse(await File.ReadAllTextAsync(Path.Combine(harness.DirectoryPath, "process.pid"), Token), System.Globalization.CultureInfo.InvariantCulture);
        Assert.Throws<ArgumentException>(() => Process.GetProcessById(pid));
    }

    [Theory]
    [InlineData("bad-utf8")]
    [InlineData("oversized")]
    [InlineData("wrong-id")]
    [InlineData("wrong-generation")]
    [InlineData("duplicate-json")]
    [InlineData("missing-result")]
    [InlineData("error-private-code")]
    public async Task InvalidFrameOrIdentityEndsTheLifetime(string mode)
    {
        await using var harness = new HarnessFixture(mode);
        await using var client = await GoHostClient.StartAsync(harness.Options, Token);
        var error = await Assert.ThrowsAsync<FilewayException>(() => client.CallAsync(new HostCall("open"), Token));
        Assert.Equal(ErrorCode.ProtocolMismatch, error.Error.Code);
        Assert.DoesNotContain("synthetic.private.token", error.ToString());
        Assert.DoesNotContain("synthetic.private.token", JsonSerializer.Serialize(error.Error));
        Assert.False((await client.Completion.WaitAsync(TimeSpan.FromSeconds(3), Token)).Expected);
        Assert.Equal(ErrorCode.HostExited, (await Assert.ThrowsAsync<FilewayException>(() => client.CallAsync(new HostCall("health"), Token))).Error.Code);
    }

    [Fact]
    public async Task InflightCancellationRetainsConfirmedWriteSuccess()
    {
        await using var harness = new HarnessFixture("delayed-success", 250);
        await using var client = await GoHostClient.StartAsync(harness.Options, Token);
        using var cancellation = CancellationTokenSource.CreateLinkedTokenSource(Token);
        Task<HostReply> call = client.CallAsync(new HostCall("request", Parameters(new { method = "POST", endpoint = "/api/resources/write" }), "session", Safety: HostCallSafety.Control), cancellation.Token);
        await WaitForRequestAsync(harness, Token);
        cancellation.Cancel();
        Assert.True((await call.WaitAsync(TimeSpan.FromSeconds(3), Token)).IsSuccess);
    }

    [Fact]
    public async Task DispatchedWriteCanceledReplyIsOutcomeUnknownAndTextIsRedacted()
    {
        await using var harness = new HarnessFixture("cancel-failure");
        await using var client = await GoHostClient.StartAsync(harness.Options, Token);
        using var cancellation = CancellationTokenSource.CreateLinkedTokenSource(Token);
        Task<HostReply> call = client.CallAsync(new HostCall("request", Parameters(new { method = "DELETE", endpoint = "/api/resources/write" }), "session", Safety: HostCallSafety.ReadOnly), cancellation.Token);
        await WaitForRequestAsync(harness, Token);
        cancellation.Cancel();
        var error = await Assert.ThrowsAsync<FilewayException>(() => call);
        Assert.Equal(ErrorCode.OutcomeUnknown, error.Error.Code);
        Assert.DoesNotContain("synthetic private", error.ToString());
    }

    [Fact]
    public async Task LateReplyAfterTimeoutRetainsCorrelationAndHostRemainsHealthy()
    {
        await using var harness = new HarnessFixture("delayed-success", 250);
        await using var client = await GoHostClient.StartAsync(harness.Options, Token);
        var error = await Assert.ThrowsAsync<FilewayException>(() => client.CallAsync(new HostCall("request", Parameters(new { method = "PUT", endpoint = "/api/resources/write" }), "session", Timeout: TimeSpan.FromMilliseconds(80)), Token));
        Assert.Equal(ErrorCode.OutcomeUnknown, error.Error.Code);
        await Task.Delay(350, Token);
        Assert.True((await client.CallAsync(new HostCall("health"), Token)).IsSuccess);
        Assert.False(client.Completion.IsCompleted);
    }

    [Theory]
    [InlineData("POST")]
    [InlineData("PUT")]
    [InlineData("PATCH")]
    [InlineData("DELETE")]
    public async Task KnownHttpWritesCannotBeDowngradedAndAreNeverReplayed(string method)
    {
        await using var fixture = new HttpFixture(_ => null);
        await using var client = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        string session = await OpenAsync(client, fixture.BaseUrl, Token);
        Task<HostReply> call = client.CallAsync(new HostCall("request", Parameters(new { method, endpoint = "/api/resources/write", body = new { action = "fixture" } }), session, Safety: HostCallSafety.Control), Token);
        await fixture.Seen.Reader.ReadAsync(Token).AsTask().WaitAsync(TimeSpan.FromSeconds(3), Token);
        using (Process process = Process.GetProcessById(client.ProcessId)) process.Kill();
        Assert.Equal(ErrorCode.OutcomeUnknown, (await Assert.ThrowsAsync<FilewayException>(() => call)).Error.Code);
        Assert.Equal(1, fixture.Count);
        Assert.Equal(ErrorCode.HostExited, (await Assert.ThrowsAsync<FilewayException>(() => client.CallAsync(new HostCall("health"), Token))).Error.Code);
        Assert.Equal(1, fixture.Count);
    }

    [Theory]
    [InlineData("stdin-backpressure")]
    [InlineData("ignore-shutdown")]
    [InlineData("truncated-response")]
    public async Task ShutdownDeadlineIncludesPipeBackpressure(string mode)
    {
        await using var harness = new HarnessFixture(mode);
        await using var client = await GoHostClient.StartAsync(harness.Options, Token);
        var calls = new List<Task<HostReply>>();
        for (int i = 0; i < 6; i++) calls.Add(client.CallAsync(new HostCall("request", Parameters(new { method = "POST", endpoint = "/api/resources/write", body = new string('x', 500_000) }), "session"), Token));
        var watch = Stopwatch.StartNew();
        await client.ShutdownAsync(Token).WaitAsync(TimeSpan.FromSeconds(2), Token);
        Assert.True(watch.Elapsed < TimeSpan.FromSeconds(2));
        foreach (Task<HostReply> call in calls)
        {
            try { await call; }
            catch (FilewayException error) { Assert.Contains(error.Error.Code, new[] { ErrorCode.OutcomeUnknown, ErrorCode.HostExited }); }
        }
        await client.Completion.WaitAsync(TimeSpan.FromSeconds(2), Token);
    }

    [Fact]
    public async Task MinimalChildEnvironmentExcludesCredentialsDebugAndProxyVariables()
    {
        string[] names = ["FILEWAY_SYNTHETIC_SENTINEL", "GODEBUG", "TS_DEBUG_SYNTHETIC", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY"];
        var previous = names.ToDictionary(name => name, Environment.GetEnvironmentVariable);
        try
        {
            foreach (string name in names) Environment.SetEnvironmentVariable(name, "synthetic-test-sentinel");
            await using var harness = new HarnessFixture("environment");
            await using var client = await GoHostClient.StartAsync(harness.Options, Token);
            HostReply reply = await client.CallAsync(new HostCall("health"), Token);
            string[] actual = reply.Result!.Value.GetProperty("environmentNames").EnumerateArray().Select(value => value.GetString()!).ToArray();
            foreach (string name in names) Assert.DoesNotContain(name, actual, StringComparer.OrdinalIgnoreCase);
            Assert.Contains("SystemRoot", actual, StringComparer.OrdinalIgnoreCase);
        }
        finally { foreach (var pair in previous) Environment.SetEnvironmentVariable(pair.Key, pair.Value); }
    }

    [Fact]
    public async Task KillingTheParentClosesJobAndLeavesNoHostOrphan()
    {
        string executable = HarnessFixture.HarnessExecutable;
        string receipt = Path.Combine(HarnessFixture.OutputRoot, "ipc-fixtures", Guid.NewGuid().ToString("N") + ".pid");
        Directory.CreateDirectory(Path.GetDirectoryName(receipt)!);
        var start = new ProcessStartInfo(executable) { UseShellExecute = false, CreateNoWindow = true };
        start.ArgumentList.Add("parent");
        start.ArgumentList.Add(Path.GetDirectoryName(HarnessFixture.RealOptions.ExecutablePath)!);
        start.ArgumentList.Add(receipt);
        using Process parent = Process.Start(start)!;
        Process? child = null;
        try
        {
            using var deadline = CancellationTokenSource.CreateLinkedTokenSource(Token);
            deadline.CancelAfter(TimeSpan.FromSeconds(10));
            while (!File.Exists(receipt)) await Task.Delay(20, deadline.Token);
            int pid = int.Parse(await File.ReadAllTextAsync(receipt, deadline.Token), System.Globalization.CultureInfo.InvariantCulture);
            child = Process.GetProcessById(pid);
            parent.Kill();
            await parent.WaitForExitAsync(deadline.Token);
            await child.WaitForExitAsync(deadline.Token);
            Assert.True(child.HasExited);
        }
        finally
        {
            if (!parent.HasExited) parent.Kill(true);
            if (child is not null) { if (!child.HasExited) child.Kill(); child.Dispose(); }
            File.Delete(receipt);
        }
    }

    [Fact]
    public async Task RepeatedStartStopReleasesProcessesAndNativeHandles()
    {
        using var process = Process.GetCurrentProcess();
        int handles = process.HandleCount;
        for (int i = 0; i < 8; i++)
        {
            var client = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
            int pid = client.ProcessId;
            await client.DisposeAsync();
            await client.Completion.WaitAsync(TimeSpan.FromSeconds(2), Token);
            Assert.Throws<ArgumentException>(() => Process.GetProcessById(pid));
        }
        process.Refresh();
        Assert.True(process.HandleCount < handles + 32);
    }

    [Fact]
    public async Task StderrIsDrainedWithoutAppearingInErrorsOrResults()
    {
        await using var harness = new HarnessFixture("stderr");
        await using var client = await GoHostClient.StartAsync(harness.Options, Token);
        Assert.True((await client.CallAsync(new HostCall("open"), Token)).IsSuccess);
    }

    [Fact]
    public async Task QueuedCancellationDoesNotInterruptThePrecedingFrame()
    {
        await using var harness = new HarnessFixture("stdin-backpressure");
        await using var client = await GoHostClient.StartAsync(harness.Options, Token);
        Task<HostReply> preceding = client.CallAsync(new HostCall("request", Parameters(new { method = "POST", endpoint = "/api/resources/write", body = new string('x', 500_000) }), "session"), Token);
        using var cancellation = CancellationTokenSource.CreateLinkedTokenSource(Token);
        Task<HostReply> queued = client.CallAsync(new HostCall("request", Parameters(new { method = "POST", endpoint = "/api/resources/queued" }), "session"), cancellation.Token);
        cancellation.Cancel();
        Assert.Equal(ErrorCode.Canceled, (await Assert.ThrowsAsync<FilewayException>(() => queued)).Error.Code);
        await client.ShutdownAsync(Token);
        Assert.Equal(ErrorCode.OutcomeUnknown, (await Assert.ThrowsAsync<FilewayException>(() => preceding)).Error.Code);
    }

    [Fact]
    public async Task PublicControlPendingIsBoundedIndependentlyOfBusinessCalls()
    {
        await using var harness = new HarnessFixture("no-response");
        await using var client = await GoHostClient.StartAsync(harness.Options, Token);
        var controls = new List<Task<HostReply>>();
        for (int i = 0; i < 18; i++) controls.Add(client.CallAsync(new HostCall("close_session", Session: "session", Safety: HostCallSafety.Control), Token));
        Assert.Equal(ErrorCode.Busy, (await Assert.ThrowsAsync<FilewayException>(() => client.CallAsync(new HostCall("health"), Token))).Error.Code);
        Assert.Equal(ErrorCode.Timeout, (await Assert.ThrowsAsync<FilewayException>(() => client.CallAsync(new HostCall("open", Timeout: TimeSpan.FromMilliseconds(80)), Token))).Error.Code);
        await client.ShutdownAsync(Token);
        foreach (Task<HostReply> control in controls) await Assert.ThrowsAsync<FilewayException>(() => control);
    }

    [Fact]
    public async Task ManifestHashMismatchFailsBeforeProcessCreation()
    {
        await using var harness = new HarnessFixture("normal");
        string content = await File.ReadAllTextAsync(harness.Options.ManifestPath, Token);
        using var document = JsonDocument.Parse(content);
        string hash = document.RootElement.GetProperty("sha256").GetString()!;
        await File.WriteAllTextAsync(harness.Options.ManifestPath, content.Replace(hash, new string('0', 64), StringComparison.Ordinal), Token);
        var error = await Assert.ThrowsAsync<FilewayException>(() => GoHostClient.StartAsync(harness.Options, Token));
        Assert.Equal(ErrorCode.ProtocolMismatch, error.Error.Code);
        Assert.False(File.Exists(harness.RequestSeen));
    }

    [Fact]
    public async Task HostErrorTextIsNotExportedAndUntypedErrorsAreNotRetried()
    {
        await using var harness = new HarnessFixture("error-private");
        await using var client = await GoHostClient.StartAsync(harness.Options, Token);
        HostReply reply = await client.CallAsync(new HostCall("open"), Token);
        Assert.False(reply.IsSuccess);
        Assert.Equal("CoreRejected", reply.Error!.Code);
        Assert.False(reply.Error.Retryable);
        Assert.DoesNotContain("fixture.password", reply.Error.ToString());
    }

    [Fact]
    public async Task RealEscapedResponseLimitAndInputBoundaryLeaveHostHealthy()
    {
        await using var fixture = new HttpFixture(_ => new FixtureResponse(200, new byte[3 << 20]));
        await using var client = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        string session = await OpenAsync(client, fixture.BaseUrl, Token);
        HostReply reply = await client.CallAsync(new HostCall("request", Parameters(new { method = "GET", endpoint = "/api/resources/large" }), session), Token);
        Assert.Equal("ResponseTooLarge", reply.Error?.Code);
        Assert.Equal(ErrorCode.InvalidRequest, (await Assert.ThrowsAsync<FilewayException>(() => client.CallAsync(new HostCall("request", Parameters(new { method = "POST", endpoint = "/api/resources/large", body = new string('x', 1 << 20) }), session), Token))).Error.Code);
        Assert.Equal(ErrorCode.InvalidRequest, (await Assert.ThrowsAsync<FilewayException>(() => client.CallAsync(new HostCall("request", Parameters(new { method = "POST", endpoint = "/api/resources/large", bodyBase64 = "" }), session), Token))).Error.Code);
        Assert.Equal(1, fixture.Count);
        Assert.True((await client.CallAsync(new HostCall("health"), Token)).IsSuccess);
    }

    [Fact]
    public async Task RegisterTimeCancellationCannotDoubleReleaseASaturatedWriteSlot()
    {
        await using var harness = new HarnessFixture("pause-then-no-response");
        await using var client = await GoHostClient.StartAsync(harness.Options, Token);
        Task<HostReply> preceding = client.CallAsync(new HostCall("request", Parameters(new { method = "POST", endpoint = "/api/resources/write", body = new string('x', 500_000) }), "session"), Token);
        object gate = typeof(GoHostClient).GetField("_gate", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(client)!;
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(Token);
        deadline.CancelAfter(TimeSpan.FromSeconds(5));
        while (true)
        {
            bool writing;
            lock (gate)
            {
                var pending = (System.Collections.IDictionary)typeof(GoHostClient).GetField("_pending", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(client)!;
                object owner = pending.Values.Cast<object>().Single();
                writing = owner.GetType().GetField("Phase", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(owner)!.ToString() == "Writing";
            }
            if (writing) break;
            await Task.Delay(5, deadline.Token);
        }
        // These canceled owners occupy the physical write queue while their
        // logical pending slots have already been released.
        for (int i = 0; i < 72; i++)
        {
            using var canceled = CancellationTokenSource.CreateLinkedTokenSource(Token);
            Task<HostReply> queued = client.CallAsync(new HostCall("open"), canceled.Token);
            canceled.Cancel();
            await Assert.ThrowsAsync<FilewayException>(() => queued);
        }
        for (int i = 0; i < 16; i++)
        {
            using var canceled = CancellationTokenSource.CreateLinkedTokenSource(Token);
            var returned = new TaskCompletionSource<Task<HostReply>>(TaskCreationOptions.RunContinuationsAsynchronously);
            var thread = new Thread(() =>
            {
                try { returned.SetResult(client.CallAsync(new HostCall("open"), canceled.Token)); }
                catch (Exception error) { returned.SetException(error); }
            }) { IsBackground = true };
            lock (gate)
            {
                thread.Start();
                // Queue has passed its entrance check and is waiting for this
                // gate; cancellation is guaranteed to win before Register.
                Assert.True(SpinWait.SpinUntil(() => (thread.ThreadState & System.Threading.ThreadState.WaitSleepJoin) != 0, TimeSpan.FromSeconds(2)));
                canceled.Cancel();
            }
            Task<HostReply> queued = await returned.Task.WaitAsync(TimeSpan.FromSeconds(2), Token);
            Assert.Equal(ErrorCode.Canceled, (await Assert.ThrowsAsync<FilewayException>(() => queued)).Error.Code);
        }
        await File.WriteAllTextAsync(Path.Combine(harness.DirectoryPath, "resume.marker"), "resume", Token);
        Assert.True((await client.CallAsync(new HostCall("health"), Token)).IsSuccess);
        var accepted = new List<Task<HostReply>>();
        for (int i = 0; i < 71; i++) accepted.Add(client.CallAsync(new HostCall("open"), Token));
        Assert.Equal(ErrorCode.Busy, (await Assert.ThrowsAsync<FilewayException>(() => client.CallAsync(new HostCall("open"), Token))).Error.Code);
        await client.ShutdownAsync(Token);
        await Assert.ThrowsAsync<FilewayException>(() => preceding);
        foreach (Task<HostReply> pending in accepted) await Assert.ThrowsAsync<FilewayException>(() => pending);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task AcceptedPostWithLostOrOversizedResponseIsUnknownAndNotReplayed(bool disconnect)
    {
        await using var fixture = new HttpFixture(_ => new FixtureResponse(disconnect ? 0 : 200, disconnect ? [] : new byte[3 << 20]));
        await using var client = await GoHostClient.StartAsync(HarnessFixture.RealOptions, Token);
        string session = await OpenAsync(client, fixture.BaseUrl, Token);
        var error = await Assert.ThrowsAsync<FilewayException>(() => client.CallAsync(new HostCall("request",
            Parameters(new { method = "POST", endpoint = "/api/resources/write", body = new { mutation = "fixture" } }), session, Safety: HostCallSafety.ReadOnly), Token));
        Assert.Equal(ErrorCode.OutcomeUnknown, error.Error.Code);
        Assert.False(error.Error.Retryable);
        FixtureRequest observed = await fixture.Seen.Reader.ReadAsync(Token);
        Assert.Equal("POST", observed.Method);
        Assert.Contains("fixture", observed.Body);
        Assert.Equal(1, fixture.Count);
        Assert.True((await client.CallAsync(new HostCall("health"), Token)).IsSuccess);
        Assert.Equal(1, fixture.Count);
    }

    [Theory]
    [InlineData("host-failure", "HostFailure")]
    [InlineData("error-private", "CoreRejected")]
    [InlineData("duplicate-request", "DuplicateRequest")]
    public async Task AmbiguousHostFailuresPreserveReadCodesAndConservativelyClassifyWrites(string mode, string code)
    {
        await using var harness = new HarnessFixture(mode);
        await using var client = await GoHostClient.StartAsync(harness.Options, Token);
        Assert.Equal(code, (await client.CallAsync(new HostCall("open"), Token)).Error?.Code);
        var error = await Assert.ThrowsAsync<FilewayException>(() => client.CallAsync(new HostCall("request", Parameters(new { method = "PATCH", endpoint = "/api/resources/write" }), "session", Safety: HostCallSafety.Control), Token));
        Assert.Equal(ErrorCode.OutcomeUnknown, error.Error.Code);
        Assert.DoesNotContain("fixture.password", error.ToString());
    }
}
