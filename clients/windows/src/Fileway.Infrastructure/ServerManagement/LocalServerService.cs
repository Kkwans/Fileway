using System.ComponentModel;
using System.Globalization;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using Fileway.Core.Contracts;
using Microsoft.Win32.SafeHandles;

namespace Fileway.Infrastructure.ServerManagement;

/// <summary>
/// Owns one managed loopback server: integrity, lifecycle, boot registration, watchdog and logs.
/// A process is only ever signalled when its image path and start time match the recorded deployment,
/// so a foreign listener on the same port can never be stopped by mistake.
/// </summary>
public sealed class LocalServerService : ILocalServerManagementService, IAsyncDisposable
{
    private const int MaximumAutomaticRestarts = 5;
    private const string ProcessName = "fileway-server";
    private const string PauseMessage = "连续 5 次自动重启都没有成功，已暂停自动重启。请查看日志后手动重试。";

    private static readonly TimeSpan GracefulStopWindow = TimeSpan.FromSeconds(15);
    private static readonly TimeSpan StartDeadline = TimeSpan.FromSeconds(25);
    private static readonly TimeSpan WatchInterval = TimeSpan.FromSeconds(5);
    private static readonly TimeSpan DetectCacheWindow = TimeSpan.FromSeconds(15);

    private static readonly HttpClient Probe = new(new SocketsHttpHandler
    {
        UseCookies = false,
        AllowAutoRedirect = false,
        ConnectTimeout = TimeSpan.FromSeconds(2),
        Proxy = null,
    })
    {
        Timeout = TimeSpan.FromSeconds(3),
    };

    /// <summary>Startup polls must notice a refused bind quickly instead of waiting out a full request timeout.</summary>
    private static readonly HttpClient QuickProbe = new(new SocketsHttpHandler
    {
        UseCookies = false,
        AllowAutoRedirect = false,
        ConnectTimeout = TimeSpan.FromMilliseconds(600),
        Proxy = null,
    })
    {
        Timeout = TimeSpan.FromMilliseconds(900),
    };

    private readonly LocalServerLayout _layout;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly CancellationTokenSource _lifetime = new();
    private readonly object _integrityLock = new();
    private readonly Task _watchdog;

    private SafeProcessHandle? _owned;
    private LocalServerStatus? _published;
    private bool _expectRunning;
    private bool _userStopping;
    private bool _stopInFlight;
    private int _consecutiveFailures;
    private IReadOnlyList<LocalServerInstance> _detected = Array.Empty<LocalServerInstance>();
    private DateTime _detectedAt = DateTime.MinValue;
    private string? _verifiedHash;
    private DateTime _verifiedStamp;
    private long _verifiedLength;
    private int _disposed;

    private LocalServerService(LocalServerLayout layout)
    {
        _layout = layout;
        _watchdog = Task.Run(() => WatchAsync(_lifetime.Token));
    }

    public event EventHandler<LocalServerStatus>? StatusChanged;

    public static LocalServerService CreateForCurrentInstallation() =>
        new(new LocalServerLayout(AppContext.BaseDirectory, LocalServerLayout.DefaultDataRoot()));

    /// <summary>Isolated verification and diagnostics use the resolved root instead of the private client directory.</summary>
    public string DataDirectory => _layout.DataRoot;

    /// <summary>The port default is fixed; a busy machine is reported as such instead of quietly relocating.</summary>
    public LocalServerDefaults GetDefaults() => new(LocalServerLayout.DefaultPort, true, _layout.FilesRoot,
        ServerInstaller.GenerateUsername(), ServerInstaller.DefaultPassword);

    public string GeneratePassword() => ServerInstaller.GeneratePassword();

    public async Task<LocalServerStatus> ReadStatusAsync(CancellationToken cancellationToken)
    {
        var document = _layout.ReadDocument();
        if (DateTime.UtcNow - _detectedAt > DetectCacheWindow)
            await RefreshDetectionAsync(document, cancellationToken).ConfigureAwait(false);
        return await ReadCoreAsync(document, cancellationToken).ConfigureAwait(false);
    }

    private async Task<IReadOnlyList<LocalServerInstance>> RefreshDetectionAsync(
        LocalServerDocument? document, CancellationToken cancellationToken)
    {
        _detectedAt = DateTime.UtcNow;
        try
        {
            _detected = await ServerDiscovery.DetectAsync(document?.Port ?? LocalServerLayout.DefaultPort,
                document?.ProcessId, QuickProbe, cancellationToken).ConfigureAwait(false);
        }
        catch (Exception error) when (error is not OperationCanceledException)
        {
            _detected = Array.Empty<LocalServerInstance>();
        }
        return _detected;
    }

    private static void EnsurePortAvailable(int port)
    {
        try
        {
            if (IPGlobalProperties.GetIPGlobalProperties().GetActiveTcpListeners().Any(endpoint => endpoint.Port == port))
                throw Conflict($"端口 {port.ToString(CultureInfo.InvariantCulture)} 已被占用，请先停止占用它的程序或修改端口。");
        }
        catch (NetworkInformationException) { /* The server will report a bind failure if inspection is unavailable. */ }
    }

    private void EnsureDatabaseAvailable()
    {
        if (!File.Exists(_layout.DatabasePath))
            throw Conflict("本地服务端数据库未找到，请恢复原数据文件后重试。");
        try
        {
            using var file = new FileStream(_layout.DatabasePath, FileMode.Open, FileAccess.ReadWrite,
                FileShare.ReadWrite | FileShare.Delete);
            var position = new DatabaseLockPosition { Offset = uint.MaxValue, OffsetHigh = uint.MaxValue };
            if (!LockFileEx(file.SafeFileHandle, 3, 0, 1, 0, ref position))
            {
                int code = Marshal.GetLastWin32Error();
                if (code == 33) throw new IOException("Database lock is held.");
                throw new Win32Exception(code);
            }
            if (!UnlockFileEx(file.SafeFileHandle, 0, 1, 0, ref position))
                throw new Win32Exception(Marshal.GetLastWin32Error());
        }
        catch (IOException)
        {
            throw Conflict("本地服务端数据正在被其他进程使用，请先停止使用同一数据的服务端。");
        }
        catch (UnauthorizedAccessException)
        {
            throw new FilewayException(new ErrorInfo(ErrorCode.LocalStorageUnavailable,
                "无法访问本地服务端数据，请检查数据目录权限。", Retryable: true));
        }
        catch (Win32Exception)
        {
            throw new FilewayException(new ErrorInfo(ErrorCode.LocalStorageUnavailable,
                "无法检查本地服务端数据状态，请检查数据目录权限。", Retryable: true));
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct DatabaseLockPosition
    {
        internal nint Internal;
        internal nint InternalHigh;
        internal uint Offset;
        internal uint OffsetHigh;
        internal nint Event;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool LockFileEx(SafeFileHandle file, uint flags, uint reserved,
        uint bytesLow, uint bytesHigh, ref DatabaseLockPosition position);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool UnlockFileEx(SafeFileHandle file, uint reserved,
        uint bytesLow, uint bytesHigh, ref DatabaseLockPosition position);

    private async Task<LocalServerStatus> ReadCoreAsync(LocalServerDocument? document, CancellationToken cancellationToken)
    {
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, _lifetime.Token);
        _ = _layout.TryReadReceipt(out ServerBuildReceipt? receipt);
        if (document is not { Deployed: true }) return Undeployed(receipt, document);

        if (!IsIntegrityVerified(document, receipt))
            return Status(document, LocalServerState.ComponentMissing, receipt, null);

        bool serving = await ProbeAsync(document, linked.Token).ConfigureAwait(false);
        SafeProcessHandle? opened = null;
        try
        {
            bool ours;
            opened = TryHandle(document, out ours);
            bool ownsPort = ours && opened is not null &&
                ServerDiscovery.OwnsListeningPort(ServerProcess.ProcessIdOf(opened), document.Port);
            if (!ownsPort && serving && TryAdopt(document))
            {
                opened?.Dispose();
                opened = TryHandle(document, out ours);
                ownsPort = ours && opened is not null &&
                    ServerDiscovery.OwnsListeningPort(ServerProcess.ProcessIdOf(opened), document.Port);
            }

            if (serving)
                return Status(document, ownsPort ? LocalServerState.Running : LocalServerState.OccupiedByOther,
                    receipt, ownsPort ? opened : null);
            if (ours && opened is not null && !ServerProcess.HasExited(opened))
            {
                // An alive-but-silent process is starting only when no stop was requested for it.
                bool windingDown = _stopInFlight || document.StoppedByUser;
                return Status(document, windingDown ? LocalServerState.Stopping : LocalServerState.Starting, receipt, opened);
            }

            if (document.ProcessId is int tracked) RecordExit(document, tracked);
            // A stop the user asked for is never reported as a crash, even when it needed force.
            LocalServerState state = document.StoppedByUser || document.LastExitCode is null or 0
                ? LocalServerState.Stopped
                : LocalServerState.Failed;
            return Status(document, state, receipt, null);
        }
        finally { opened?.Dispose(); }
    }

    public async Task<LocalServerStatus> DeployAndStartAsync(LocalServerDeploymentRequest request, CancellationToken cancellationToken)
    {
        ServerInstaller.Validate(request);
        await EnterAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var existing = _layout.ReadDocument();
            if (existing is { Deployed: true })
                throw Conflict("本地服务端已经部署过。要更换端口或共享范围，请先停止服务再重新部署。");
            if (!_layout.TryReadReceipt(out ServerBuildReceipt? receipt)) throw ComponentMissing();
            // Checking before anything is written keeps a blocked deployment from leaving a half-created database.
            EnsurePortAvailable(request.Port);

            var document = LocalServerLayout.NewDocument(_layout.ExecutablePath, receipt.Sha256);
            document.Port = request.Port;
            document.Address = request.Address;
            document.MountsWholeComputer = request.MountsWholeComputer;
            document.FilesRoot = request.MountsWholeComputer ? _layout.FilesRoot : Path.GetFullPath(request.FilesRoot);
            document.AdminUsername = request.AdminUsername;
            document.DeployedAt = DateTimeOffset.Now;
            document.AutoRestart = existing?.AutoRestart ?? false;
            await ServerInstaller.PrepareAsync(_layout, document, request.AdminPassword, cancellationToken).ConfigureAwait(false);
            _layout.WriteAdminSecret(request.AdminPassword);
            _layout.WriteDocument(document);
            return await StartCoreAsync(document, receipt, cancellationToken).ConfigureAwait(false);
        }
        finally { _gate.Release(); }
    }

    public Task<LocalServerStatus> StartAsync(CancellationToken cancellationToken) => MutateAsync(StartInnerAsync, cancellationToken);

    public Task<LocalServerStatus> RetryStartAsync(CancellationToken cancellationToken) => MutateAsync(async token =>
    {
        var document = RequireDeployed();
        document.LastError = null;
        document.LastExitCode = null;
        document.RestartAttempts = 0;
        _consecutiveFailures = 0;
        _userStopping = false;
        _layout.WriteDocument(document);
        return await StartInnerAsync(token).ConfigureAwait(false);
    }, cancellationToken);

    public Task<LocalServerStatus> StopAsync(CancellationToken cancellationToken) => MutateAsync(StopInnerAsync, cancellationToken);

    public Task<LocalServerStatus> SetAutoStartAsync(bool enabled, CancellationToken cancellationToken) => MutateAsync(async token =>
    {
        var document = RequireDeployed();
        if (!enabled)
        {
            StartupRegistration.Remove();
            document.AutoStart = false;
            _layout.WriteDocument(document);
            return await ReadStatusAsync(token).ConfigureAwait(false);
        }

        string command = StartupRegistration.Command(_layout.ExecutablePath, _layout.LaunchArguments(document));
        try
        {
            StartupRegistration.Apply(command);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            throw new FilewayException(new ErrorInfo(ErrorCode.LocalStorageUnavailable,
                "无法写入当前用户的开机启动项，可能被安全软件或组策略限制。", Retryable: true), error);
        }
        if (!StartupRegistration.IsRegistered(command))
            throw new FilewayException(new ErrorInfo(ErrorCode.LocalStorageUnavailable,
                "开机启动项写入后读到的内容不一致，本次登录仍不会自动启动。"));
        document.AutoStart = true;
        _layout.WriteDocument(document);
        return await ReadStatusAsync(token).ConfigureAwait(false);
    }, cancellationToken);

    public Task<LocalServerStatus> SetAutoRestartAsync(bool enabled, CancellationToken cancellationToken) => MutateAsync(async token =>
    {
        var document = RequireDeployed();
        document.AutoRestart = enabled;
        if (enabled)
        {
            _consecutiveFailures = 0;
            document.RestartAttempts = 0;
            if (string.Equals(document.LastError, PauseMessage, StringComparison.Ordinal)) document.LastError = null;
        }
        _layout.WriteDocument(document);
        return await ReadStatusAsync(token).ConfigureAwait(false);
    }, cancellationToken);

    public Task<LocalServerStatus> ReconfigureAsync(LocalServerReconfigureRequest request, CancellationToken cancellationToken) => MutateAsync(async token =>
    {
        ServerInstaller.ValidateEndpoint(request.Port, request.Address);
        ServerInstaller.ValidateAccount(request.AdminUsername, request.AdminPassword);
        if (!request.MountsWholeComputer &&
            (string.IsNullOrWhiteSpace(request.FilesRoot) || !Path.IsPathFullyQualified(request.FilesRoot)))
            throw new FilewayException(new ErrorInfo(ErrorCode.InvalidRequest, "限定共享范围时需要一个完整的文件夹路径。"));
        var document = RequireDeployed();
        if (!_layout.TryReadReceipt(out ServerBuildReceipt? receipt)) throw ComponentMissing();
        if (request.Port != document.Port) EnsurePortAvailable(request.Port);
        var current = await ReadCoreAsync(document, token).ConfigureAwait(false);
        if (current.State is LocalServerState.Starting or LocalServerState.Stopping)
            throw Conflict("服务端正在启动或停止，请等状态稳定后再修改部署设置。");
        bool wasRunning = current.State == LocalServerState.Running;
        if (wasRunning)
        {
            var stopped = await StopInnerAsync(token).ConfigureAwait(false);
            if (stopped.State == LocalServerState.Stopping)
            {
                var pending = RequireDeployed();
                using var process = TryHandle(pending, out bool ours);
                if (ours && process is not null &&
                    !await ServerProcess.WaitForExitAsync(process, TimeSpan.FromSeconds(10), token).ConfigureAwait(false))
                    throw Conflict("服务端正在停止，请稍后重试保存。");
            }
        }
        document = RequireDeployed();
        try
        {
            if (request.AdminUsername is not null || request.AdminPassword is not null)
                await ServerInstaller.UpdateAdminAsync(_layout, document.AdminUsername, request.AdminUsername, request.AdminPassword, token).ConfigureAwait(false);
        }
        catch
        {
            // A failed account edit must not leave a stopped deployment behind.
            if (wasRunning)
            {
                try { await StartCoreAsync(document, receipt, token).ConfigureAwait(false); }
                catch (Exception restartError) when (restartError is FilewayException or OperationCanceledException) { }
            }
            throw;
        }
        document.Port = request.Port;
        document.Address = request.Address;
        document.MountsWholeComputer = request.MountsWholeComputer;
        document.FilesRoot = request.MountsWholeComputer ? _layout.FilesRoot : Path.GetFullPath(request.FilesRoot);
        if (request.AdminUsername is not null) document.AdminUsername = request.AdminUsername;
        document.LastError = null;
        _layout.WriteDocument(document);
        if (request.AdminPassword is not null) _layout.WriteAdminSecret(request.AdminPassword);
        SyncStartupRegistration(document);
        return await StartCoreAsync(document, receipt, token).ConfigureAwait(false);
    }, cancellationToken);

    public async Task<LocalServerCredentialState> CheckCredentialAsync(CancellationToken cancellationToken)
    {
        var document = _layout.ReadDocument();
        if (document is not { Deployed: true }) return LocalServerCredentialState.Unknown;
        string? password = await Task.Run(_layout.ReadAdminSecret, cancellationToken).ConfigureAwait(false);
        if (password is null) return LocalServerCredentialState.Unknown;
        if (!await ProbeAsync(document, cancellationToken).ConfigureAwait(false)) return LocalServerCredentialState.Unknown;
        try
        {
            using var content = new StringContent(
                JsonSerializer.Serialize(new { username = document.AdminUsername, password }),
                Encoding.UTF8, "application/json");
            using var response = await Probe.PostAsync(
                $"http://127.0.0.1:{document.Port.ToString(CultureInfo.InvariantCulture)}/api/login",
                content, cancellationToken).ConfigureAwait(false);
            return response.StatusCode == HttpStatusCode.OK ? LocalServerCredentialState.Valid
                : response.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden ? LocalServerCredentialState.Stale
                : LocalServerCredentialState.Unknown;
        }
        catch (Exception error) when (error is HttpRequestException or TaskCanceledException or OperationCanceledException or SocketException)
        {
            return LocalServerCredentialState.Unknown;
        }
    }

    public Task<IReadOnlyList<string>> GetLanEndpointsAsync(CancellationToken cancellationToken)
    {
        var document = _layout.ReadDocument();
        if (document is not { Deployed: true } ||
            !IPAddress.TryParse(document.Address, out var bound) || !bound.Equals(IPAddress.Any))
            return Task.FromResult<IReadOnlyList<string>>(Array.Empty<string>());
        var endpoints = new List<string>();
        foreach (var adapter in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (adapter.OperationalStatus != OperationalStatus.Up || adapter.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            foreach (var unicast in adapter.GetIPProperties().UnicastAddresses)
            {
                var address = unicast.Address;
                if (address.AddressFamily != AddressFamily.InterNetwork || !IsPrivateNetwork(address)) continue;
                endpoints.Add($"http://{address}:{document.Port.ToString(CultureInfo.InvariantCulture)}");
            }
        }
        return Task.FromResult<IReadOnlyList<string>>(endpoints);
    }

    private static bool IsPrivateNetwork(IPAddress address)
    {
        var bytes = address.GetAddressBytes();
        return bytes[0] == 10 || (bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31) || (bytes[0] == 192 && bytes[1] == 168);
    }

    public async Task<LocalServerLogPage> ReadLogsAsync(LocalServerFilter filter, int maxLines, CancellationToken cancellationToken)
    {
        var captured = filter;
        return await Task.Run(() => ServerLogReader.Read(_layout, captured, maxLines), cancellationToken).ConfigureAwait(false);
    }

    public async Task<string?> GetConnectableEndpointAsync(CancellationToken cancellationToken)
    {
        var status = await ReadStatusAsync(cancellationToken).ConfigureAwait(false);
        return status.State == LocalServerState.Running ? status.Endpoint : null;
    }

    /// <summary>The deployment account, revealed deliberately from the page rather than from a hidden file.</summary>
    public async Task<LocalServerCredential?> ReadCredentialAsync(CancellationToken cancellationToken)
    {
        var document = _layout.ReadDocument();
        if (document is not { Deployed: true }) return null;
        string? password = await Task.Run(_layout.ReadAdminSecret, cancellationToken).ConfigureAwait(false);
        return password is null ? null : new LocalServerCredential(document.AdminUsername, password);
    }

    public async ValueTask DisposeAsync()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0) return;
        _lifetime.Cancel();
        try { await _watchdog.ConfigureAwait(false); }
        catch (Exception error) when (error is not OperationCanceledException) { /* The watchdog exits with the token. */ }
        _owned?.Dispose();
        _owned = null;
        _lifetime.Dispose();
        _gate.Dispose();
    }

    private Task<LocalServerStatus> StartInnerAsync(CancellationToken token)
    {
        var document = RequireDeployed();
        if (!_layout.TryReadReceipt(out ServerBuildReceipt? receipt)) throw ComponentMissing();
        return StartCoreAsync(document, receipt, token);
    }

    /// <summary>Launches, then waits for /health instead of a window that never appears.</summary>
    private async Task<LocalServerStatus> StartCoreAsync(LocalServerDocument document, ServerBuildReceipt receipt, CancellationToken cancellationToken)
    {
        if (!IsIntegrityVerified(document, receipt)) throw ComponentMissing();
        if (await ProbeAsync(document, cancellationToken).ConfigureAwait(false))
        {
            using var running = TryHandle(document, out bool ours);
            bool ownsPort = ours && running is not null &&
                ServerDiscovery.OwnsListeningPort(ServerProcess.ProcessIdOf(running), document.Port);
            if (ownsPort || TryAdopt(document))
                return await ReadStatusAsync(cancellationToken).ConfigureAwait(false);
            throw Conflict("这个端口上已经有另一个服务端在响应，但它不是本客户端部署的那个进程。为避免误停别人的服务，请先停止它或更换端口。");
        }

        using (var existing = TryHandle(document, out bool ours))
        {
            if (ours && existing is not null && !ServerProcess.HasExited(existing))
            {
                if (!document.StoppedByUser) return await ReadStatusAsync(cancellationToken).ConfigureAwait(false);
                if (!await ServerProcess.WaitForExitAsync(existing, TimeSpan.FromSeconds(10), cancellationToken).ConfigureAwait(false))
                    throw Conflict("服务端正在停止，请稍后重试。");
            }
        }
        EnsurePortAvailable(document.Port);
        EnsureDatabaseAvailable();

        _userStopping = false;
        SafeProcessHandle handle;
        try
        {
            handle = ServerProcess.Start(_layout.ExecutablePath, _layout.LaunchArguments(document), _layout.DataRoot,
                _layout.DiagnosticsLogPath, Path.Combine(_layout.LogRoot, "output.log"));
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            document.LastError = error.Message;
            _layout.WriteDocument(document);
            throw new FilewayException(new ErrorInfo(ErrorCode.LocalStorageUnavailable, error.Message, Retryable: true), error);
        }

        _owned?.Dispose();
        _owned = handle;
        document.ProcessId = ServerProcess.ProcessIdOf(handle);
        document.ExecutablePath = _layout.ExecutablePath;
        document.ProcessStartedAt = DateTimeOffset.Now;
        document.LastError = null;
        document.LastExitCode = null;
        document.LastStopSignal = null;
        document.StoppedByUser = false;
        _layout.WriteDocument(document);

        DateTime deadline = DateTime.UtcNow + StartDeadline;
        while (DateTime.UtcNow < deadline)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (await ProbeAsync(document, cancellationToken, QuickProbe).ConfigureAwait(false))
            {
                _expectRunning = true;
                _consecutiveFailures = 0;
                document.RestartAttempts = 0;
                SyncStartupRegistration(document);
                _layout.WriteDocument(document);
                Publish(document, LocalServerState.Running, receipt, handle);
                InvalidateDetection();
                return await ReadStatusAsync(cancellationToken).ConfigureAwait(false);
            }
            if (ServerProcess.HasExited(handle)) break;
            await Task.Delay(300, cancellationToken).ConfigureAwait(false);
        }

        int? exitCode = ServerProcess.ExitCode(handle);
        if (exitCode is null)
        {
            document.LastError = "服务端启动时间较长，可继续等待或停止后查看日志。";
            _layout.WriteDocument(document);
            Publish(document, LocalServerState.Starting, receipt, handle);
            return await ReadStatusAsync(cancellationToken).ConfigureAwait(false);
        }
        document.LastExitCode = exitCode;
        document.LastError = LaunchFailureMessage(document, detail: ServerLogReader.DescribeFailure(_layout.DiagnosticsLogPath));
        _expectRunning = false;
        _layout.WriteDocument(document);
        Publish(document, LocalServerState.Failed, receipt, null);
        throw new FilewayException(new ErrorInfo(ErrorCode.HostExited, document.LastError!, Retryable: true));
    }

    private async Task<LocalServerStatus> StopInnerAsync(CancellationToken cancellationToken)
    {
        var document = RequireDeployed();
        if (document.ProcessId is null && !await ProbeAsync(document, cancellationToken).ConfigureAwait(false))
            return await ReadStatusAsync(cancellationToken).ConfigureAwait(false);

        SafeProcessHandle? opened = TryHandle(document, out bool ours);
        if (opened is null && TryAdopt(document)) opened = TryHandle(document, out ours);
        bool borrowed = opened is not null && !ReferenceEquals(opened, _owned);
        try
        {
            if (opened is null || !ours)
                throw Conflict("没有发现由本客户端部署的服务端进程。端口上应答的可能是其他程序，为避免误停已经拒绝操作。");

            _userStopping = true;
            _expectRunning = false;
            _stopInFlight = true;
            ServerStopOutcome outcome;
            string signal;
            try
            {
                (outcome, signal) = await ServerProcess.StopAsync(opened,
                    () => ProbeAsync(document, cancellationToken), GracefulStopWindow,
                    _layout.StopEventName, cancellationToken).ConfigureAwait(false);
            }
            finally { _stopInFlight = false; }

            document.LastStopSignal = signal;
            document.StoppedByUser = true;
            document.LastExitCode = ServerProcess.ExitCode(opened);
            // A draining process keeps its recorded pid so the supervision loop can clear it later.
            if (outcome is ServerStopOutcome.Graceful or ServerStopOutcome.Forced or ServerStopOutcome.AlreadyExited)
            {
                document.ProcessId = null;
                document.ProcessStartedAt = null;
                _owned?.Dispose();
                _owned = null;
            }
            document.LastError = outcome == ServerStopOutcome.Forced
                ? "服务端未能正常停止，已结束服务进程。"
                : null;
            _layout.WriteDocument(document);
            if (outcome == ServerStopOutcome.Unknown)
                throw new FilewayException(new ErrorInfo(ErrorCode.OutcomeUnknown, "停止结果无法确认，请刷新状态后再决定下一步。", Retryable: true));
            InvalidateDetection();
            return await ReadStatusAsync(cancellationToken).ConfigureAwait(false);
        }
        finally { if (borrowed) opened?.Dispose(); }
    }

    private async Task<LocalServerStatus> MutateAsync(Func<CancellationToken, Task<LocalServerStatus>> operation, CancellationToken cancellationToken)
    {
        await EnterAsync(cancellationToken).ConfigureAwait(false);
        try { return await operation(cancellationToken).ConfigureAwait(false); }
        finally { _gate.Release(); }
    }

    private async Task WatchAsync(CancellationToken cancellationToken)
    {
        using var timer = new PeriodicTimer(WatchInterval);
        while (true)
        {
            try
            {
                if (!await timer.WaitForNextTickAsync(cancellationToken).ConfigureAwait(false)) return;
                var status = await ReadStatusAsync(cancellationToken).ConfigureAwait(false);
                Raise(status);
                await ReactAsync(status, cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) { return; }
            catch (Exception error) when (error is not OperationCanceledException)
            {
                _ = error; // Background supervision must never surface as an application crash.
            }
        }
    }

    private async Task ReactAsync(LocalServerStatus status, CancellationToken cancellationToken)
    {
        if (status.State == LocalServerState.Running)
        {
            _expectRunning = true;
            _consecutiveFailures = 0;
            return;
        }
        if (_userStopping || !_expectRunning) return;
        var document = _layout.ReadDocument();
        if (document is not { AutoRestart: true }) { _expectRunning = false; return; }
        if (_consecutiveFailures >= MaximumAutomaticRestarts) return;

        _consecutiveFailures++;
        bool givingUp = _consecutiveFailures >= MaximumAutomaticRestarts;
        document.RestartAttempts = _consecutiveFailures;
        document.LastError = givingUp ? PauseMessage
            : $"服务端意外退出，正在自动重启（第 {_consecutiveFailures.ToString(CultureInfo.InvariantCulture)} 次）。";
        _layout.WriteDocument(document);
        _expectRunning = !givingUp;
        _ = _layout.TryReadReceipt(out ServerBuildReceipt? receipt);
        Raise(Status(document, LocalServerState.Stopped, receipt, null));
        if (givingUp) return;

        await Task.Delay(Backoff(_consecutiveFailures), cancellationToken).ConfigureAwait(false);
        try { await MutateAsync(StartInnerAsync, cancellationToken).ConfigureAwait(false); }
        catch (FilewayException) { _expectRunning = false; }
    }

    private static TimeSpan Backoff(int attempt) => TimeSpan.FromSeconds(Math.Min(60, 2 << Math.Clamp(attempt - 1, 0, 5)));

    private SafeProcessHandle? TryHandle(LocalServerDocument document, out bool ours)
    {
        ours = false;
        if (document.ProcessId is not int recorded || recorded <= 0) return null;
        if (_owned is { } owned)
        {
            var copy = ServerProcess.TryDuplicate(owned);
            if (copy is not null)
            {
                ours = Matches(copy, document);
                if (ours) return copy;
                copy.Dispose();
            }
        }
        SafeProcessHandle? opened = ServerProcess.TryOpen(recorded);
        if (opened is null) return null;
        ours = Matches(opened, document);
        if (!ours) opened.Dispose();
        return ours ? opened : null;
    }

    /// <summary>
    /// Claims a server that is already serving on the managed port when its image path is this
    /// installation's own executable. Exactly one such process may exist, and never a foreign binary.
    /// </summary>
    private bool TryAdopt(LocalServerDocument document)
    {
        var candidates = new List<(int Id, DateTimeOffset Started)>();
        foreach (System.Diagnostics.Process candidate in System.Diagnostics.Process.GetProcessesByName(ProcessName))
        {
            using (candidate)
            {
                try
                {
                    if (!IsOurImage(candidate.MainModule?.FileName, document)) continue;
                    if (candidate.StartTime < document.DeployedAt?.LocalDateTime) continue;
                    if (!ServerDiscovery.OwnsListeningPort(candidate.Id, document.Port)) continue;
                    candidates.Add((candidate.Id, candidate.StartTime));
                }
                catch (Exception error) when (error is InvalidOperationException or Win32Exception or NotSupportedException)
                {
                    // A process we cannot inspect is by definition not provably ours.
                }
            }
        }
        if (candidates.Count != 1) return false;
        // Merge into a fresh read: a concurrent reconfigure may have changed port/bind since
        // this document was read; writing the whole stale object would revert those edits.
        var fresh = _layout.ReadDocument() ?? document;
        fresh.ProcessId = candidates[0].Id;
        fresh.ProcessStartedAt = candidates[0].Started;
        document.ProcessId = fresh.ProcessId;
        document.ProcessStartedAt = fresh.ProcessStartedAt;
        _expectRunning = true;
        _layout.WriteDocument(fresh);
        return true;
    }

    private void RecordExit(LocalServerDocument document, int trackedProcessId)
    {
        if (document.LastExitCode is not null) return;
        System.Diagnostics.Process? process = null;
        try
        {
            process = System.Diagnostics.Process.GetProcessById(trackedProcessId);
            if (!IsOurImage(process.MainModule?.FileName, document)) return;
            var fresh = _layout.ReadDocument() ?? document;
            fresh.LastExitCode = process.ExitCode;
            if (process.ExitCode != 0) fresh.LastError = ServerLogReader.DescribeFailure(_layout.DiagnosticsLogPath);
            document.LastExitCode = fresh.LastExitCode;
            document.LastError = fresh.LastError;
            _layout.WriteDocument(fresh);
        }
        catch (Exception error) when (error is ArgumentException or InvalidOperationException or Win32Exception)
        {
            _ = error; // A vanished process keeps the previous recorded outcome rather than inventing one.
        }
        finally { process?.Dispose(); }
    }

    private bool IsIntegrityVerified(LocalServerDocument document, ServerBuildReceipt? receipt)
    {
        if (receipt is null) return false;
        lock (_integrityLock)
        {
            // The on-disk bundle must match the receipt that ships with it; that is the tamper test.
            if (!_layout.VerifyExecutable(receipt.Sha256, ref _verifiedStamp, ref _verifiedLength, ref _verifiedHash)) return false;
        }
        // An upgrade replaces the bundled server. The deployment keeps its database and adopts the new
        // binary; refusing here would brick every existing deployment the moment the app updates.
        if (!string.Equals(document.ExecutableSha256, receipt.Sha256, StringComparison.OrdinalIgnoreCase))
        {
            var fresh = _layout.ReadDocument() ?? document;
            fresh.ExecutableSha256 = receipt.Sha256;
            document.ExecutableSha256 = fresh.ExecutableSha256;
            _layout.WriteDocument(fresh);
        }
        return true;
    }

    /// <summary>Either the image recorded at deploy time or the one this installation ships counts as ours.</summary>
    private bool IsOurImage(string? imagePath, LocalServerDocument document) =>
        !string.IsNullOrEmpty(imagePath) &&
        (string.Equals(imagePath, document.ExecutablePath, StringComparison.OrdinalIgnoreCase) ||
         string.Equals(imagePath, _layout.ExecutablePath, StringComparison.OrdinalIgnoreCase));

    private void SyncStartupRegistration(LocalServerDocument document)
    {
        if (!document.AutoStart) return;
        string command = StartupRegistration.Command(_layout.ExecutablePath, _layout.LaunchArguments(document));
        if (StartupRegistration.IsRegistered(command)) return;
        try
        {
            StartupRegistration.Apply(command);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            _ = error;
            document.AutoStart = false;
            document.LastError = "开机启动项与本部署不一致且写入失败，本次登录不会自动启动服务端。";
        }
    }

    /// <summary>A launch or stop changes what is listening, so the next read must re-scan rather than reuse the cache.</summary>
    private void InvalidateDetection() => _detectedAt = DateTime.MinValue;

    private LocalServerStatus Undeployed(ServerBuildReceipt? receipt, LocalServerDocument? document) => new(
        LocalServerState.NotDeployed, null, null, null, null, null, receipt?.Version, false,
        document?.AutoRestart ?? false, 0, null, null, _detected);

    private LocalServerStatus Status(LocalServerDocument document, LocalServerState state, ServerBuildReceipt? receipt, SafeProcessHandle? handle)
    {
        DateTimeOffset? startedAt = document.ProcessStartedAt;
        bool ours = handle is not null && (ReferenceEquals(handle, _owned) || Matches(handle, document));
        TimeSpan? uptime = state == LocalServerState.Running && startedAt is not null ? DateTimeOffset.Now - startedAt.Value : null;
        var deployment = new LocalServerDeployment(document.Port, document.Address, document.MountsWholeComputer,
            document.MountsWholeComputer ? LocalServerLayout.ComputerRootLabel : document.FilesRoot, _layout.DataRoot,
            _layout.LogRoot, document.AdminUsername, document.DeployedAt ?? DateTimeOffset.Now);
        ErrorInfo? error = string.IsNullOrEmpty(document.LastError)
            ? null
            : new ErrorInfo(document.LastExitCode is null or 0 ? ErrorCode.Unknown : ErrorCode.HostExited, document.LastError!, Retryable: true);
        return new LocalServerStatus(state, deployment, Endpoint(document), ours ? document.ProcessId : null,
            startedAt, uptime, receipt?.Version, MatchesStartup(document), document.AutoRestart,
            document.RestartAttempts, document.LastExitCode, error, _detected);
    }

    /// <summary>Credentials for a one-click sign-in; only handed out while the managed instance is healthy.</summary>
    public async Task<LocalServerSignIn?> GetSignInAsync(CancellationToken cancellationToken)
    {
        var document = _layout.ReadDocument();
        if (document is not { Deployed: true }) return null;
        string? password = await Task.Run(_layout.ReadAdminSecret, cancellationToken).ConfigureAwait(false);
        if (string.IsNullOrEmpty(password)) return null;
        return new LocalServerSignIn(Endpoint(document), document.AdminUsername, password);
    }

    public async Task<bool> OpenWebUiAsync(int port, CancellationToken cancellationToken)
    {
        if (port is < 1 or > 65535) return false;
        var uri = new Uri($"http://127.0.0.1:{port.ToString(System.Globalization.CultureInfo.InvariantCulture)}/");
        try
        {
            using var response = await Probe.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead, cancellationToken).ConfigureAwait(false);
            if (response.StatusCode != HttpStatusCode.OK) return false;
            return await Windows.System.Launcher.LaunchUriAsync(uri).AsTask(cancellationToken).ConfigureAwait(false);
        }
        catch (Exception error) when (error is HttpRequestException or TaskCanceledException or OperationCanceledException or InvalidOperationException)
        {
            return false;
        }
    }

    private bool MatchesStartup(LocalServerDocument document) => document.AutoStart &&
        StartupRegistration.IsRegistered(StartupRegistration.Command(_layout.ExecutablePath, _layout.LaunchArguments(document)));

    private void Raise(LocalServerStatus status)
    {
        if (_published is not null && Signature(_published) == Signature(status)) return;
        _published = status;
        StatusChanged?.Invoke(this, status);
    }

    private void Publish(LocalServerDocument document, LocalServerState state, ServerBuildReceipt? receipt, SafeProcessHandle? handle) =>
        Raise(Status(document, state, receipt, handle));

    private static string Endpoint(LocalServerDocument document) =>
        $"http://{ProbeHost(document.Address)}:{document.Port.ToString(CultureInfo.InvariantCulture)}";

    private static string Signature(LocalServerStatus status) =>
        string.Join('|', status.State, status.ProcessId, status.LastExitCode, status.RestartAttempts,
            status.AutoStartEnabled, status.AutoRestartEnabled, status.LastError?.Message,
            string.Join(',', status.Detected.Select(instance => instance.Port)));

    private static Task<bool> ProbeAsync(LocalServerDocument document, CancellationToken cancellationToken, HttpClient? client = null) =>
        ProbeAsync(ProbeHost(document.Address), document.Port, cancellationToken, client);

    /// <summary>A wildcard bind is not a connectable client address; health/sign-in always use loopback.</summary>
    private static string ProbeHost(string address) =>
        IPAddress.TryParse(address, out var ip) && ip.Equals(IPAddress.Any) ? "127.0.0.1" : address;

    private static async Task<bool> ProbeAsync(string address, int port, CancellationToken cancellationToken, HttpClient? client = null)
    {
        HttpClient http = client ?? Probe;
        try
        {
            using var response = await http.GetAsync($"http://{address}:{port.ToString(CultureInfo.InvariantCulture)}/health",
                HttpCompletionOption.ResponseHeadersRead, cancellationToken).ConfigureAwait(false);
            if (response.StatusCode != HttpStatusCode.OK) return false;
            string body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            return body.Contains("OK", StringComparison.OrdinalIgnoreCase);
        }
        catch (Exception error) when (error is HttpRequestException or TaskCanceledException or OperationCanceledException or SocketException)
        {
            return false;
        }
    }

    /// <summary>PID reuse is rejected by comparing the recorded start time and image path.</summary>
    private bool Matches(SafeProcessHandle handle, LocalServerDocument document)
    {
        if (document.ProcessId is not int recorded || recorded <= 0) return false;
        try
        {
            using var process = System.Diagnostics.Process.GetProcessById(recorded);
            if (ServerProcess.ProcessIdOf(handle) != process.Id) return false;
            if (document.ProcessStartedAt is DateTimeOffset start &&
                Math.Abs((process.StartTime - start.LocalDateTime).TotalSeconds) > 10) return false;
            return IsOurImage(process.MainModule?.FileName, document);
        }
        catch (Exception error) when (error is ArgumentException or InvalidOperationException or NotSupportedException or Win32Exception)
        {
            return false;
        }
    }

    private static string LaunchFailureMessage(LocalServerDocument document, string detail)
    {
        string reason = detail.Contains("bind:", StringComparison.OrdinalIgnoreCase) || detail.Contains("socket address", StringComparison.OrdinalIgnoreCase)
            ? $"端口 {document.Port.ToString(CultureInfo.InvariantCulture)} 已被其他程序占用"
            : detail.Contains("timeout", StringComparison.OrdinalIgnoreCase)
                ? "这个数据目录已经被另一个服务端实例锁定"
                : "服务端启动失败";
        return reason + "，请查看本地服务端日志。";
    }

    private LocalServerDocument RequireDeployed() =>
        _layout.ReadDocument() is { Deployed: true } document ? document : throw Conflict("本地服务端还没有部署。");

    private static FilewayException Conflict(string message) => new(new ErrorInfo(ErrorCode.Conflict, message, Retryable: true));

    private static FilewayException ComponentMissing() => new(new ErrorInfo(ErrorCode.CapabilityMissing,
        "安装包里的服务端组件缺失或校验不通过，请重新安装栖卷。"));

    private Task EnterAsync(CancellationToken cancellationToken) => _gate.WaitAsync(cancellationToken);
}
