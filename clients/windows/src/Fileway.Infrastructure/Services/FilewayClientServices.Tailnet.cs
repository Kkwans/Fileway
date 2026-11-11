using System.Net;
using System.Security;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices : IEmbeddedTailnetService
{
    private readonly string? _tailnetDirectory;
    private const int TailnetIdentityBytes = 8;
    private const int TailnetKeyBytes = 32;
    private static readonly byte[] TailnetKeyPurpose = "Fileway.Windows.EmbeddedTailnet.v1"u8.ToArray();

    public async Task<EmbeddedTailnetStatus> ReadTailnetAsync(CancellationToken cancellationToken) =>
        await ReadTailnetStateAsync(cancellationToken).ConfigureAwait(false);

    public async Task<EmbeddedTailnetStatus> StartTailnetAsync(CancellationToken cancellationToken)
    {
        await _connections.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            lock (_gate) ObjectDisposedException.ThrowIf(_disposed, this);
            return await StartTailnetCoreAsync(cancellationToken).ConfigureAwait(false);
        }
        finally { _connections.Release(); }
    }

    public Task<EmbeddedTailnetStatus> StopTailnetAsync(CancellationToken cancellationToken) =>
        EndTailnetAsync(logout: false, cancellationToken);

    public Task<EmbeddedTailnetStatus> LogoutTailnetAsync(CancellationToken cancellationToken) =>
        EndTailnetAsync(logout: true, cancellationToken);

    private async Task<EmbeddedTailnetStatus> EndTailnetAsync(bool logout, CancellationToken cancellationToken)
    {
        await _connections.WaitAsync(cancellationToken).ConfigureAwait(false);
        var retired = new List<SessionState>();
        try
        {
            SessionState[] states;
            lock (_gate)
            {
                ObjectDisposedException.ThrowIf(_disposed, this);
                states = _sessions.Values.Where(state => state.Saved.Profile.Network == NetworkMode.EmbeddedTailnet).ToArray();
            }
            foreach (var state in states)
            {
                try { if (await RetireAsync(state).ConfigureAwait(false)) retired.Add(state); }
                catch (Exception) { retired.Add(state); throw; }
            }
            var current = await ReadTailnetStateAsync(cancellationToken).ConfigureAwait(false);
            if (current.State is EmbeddedTailnetState.Unconfigured or EmbeddedTailnetState.Configured)
                return current;
            await CallAsync(logout ? "network_logout" : "network_stop", new { }, null, cancellationToken,
                HostCallSafety.Mutation, TimeSpan.FromSeconds(15)).ConfigureAwait(false);
            return await ReadTailnetStateAsync(cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _connections.Release();
            foreach (var state in retired)
                PublishConnectionIssue(state, new ErrorInfo(ErrorCode.SourceChanged,
                    "内嵌 Tailscale 会话已关闭，服务器连接已断开。"));
        }
    }

    private async Task<EmbeddedTailnetStatus> ReadTailnetStateAsync(CancellationToken cancellationToken)
    {
        var response = await CallAsync("network_status", new { }, null, cancellationToken, HostCallSafety.ReadOnly,
            TimeSpan.FromSeconds(12)).ConfigureAwait(false);
        return ParseTailnetStatus(response);
    }

    private async Task<EmbeddedTailnetStatus> StartTailnetCoreAsync(CancellationToken cancellationToken)
    {
        var directory = _tailnetDirectory ??
            throw Safe(ErrorCode.CapabilityMissing, "此安装未提供内嵌 Tailscale 状态存储。");
        var current = await ReadTailnetStateAsync(cancellationToken).ConfigureAwait(false);
        if (current.State == EmbeddedTailnetState.Running) return current;
        if (current.State == EmbeddedTailnetState.Unconfigured)
        {
            byte[] identity = await Task.Run(() => ReadOrCreateTailnetIdentity(directory), cancellationToken).ConfigureAwait(false);
            var key = identity.AsSpan(TailnetIdentityBytes, TailnetKeyBytes).ToArray();
            try
            {
                var hostname = "fileway-win-" + Convert.ToHexStringLower(identity.AsSpan(0, TailnetIdentityBytes));
                await CallAsync("network_configure", new { stateDir = directory, hostname, storageKey = key },
                    null, cancellationToken, HostCallSafety.Control, TimeSpan.FromSeconds(10)).ConfigureAwait(false);
            }
            finally
            {
                CryptographicOperations.ZeroMemory(key);
                CryptographicOperations.ZeroMemory(identity);
            }
        }
        if (current.State is EmbeddedTailnetState.NeedsApproval or EmbeddedTailnetState.Starting ||
            current.State == EmbeddedTailnetState.NeedsLogin && current.ApprovalUri is not null)
        {
            // Re-use the current authorization attempt instead of replacing it.
            return current;
        }
        var response = await CallAsync("network_start", new { }, null, cancellationToken, HostCallSafety.Control,
            TimeSpan.FromSeconds(20)).ConfigureAwait(false);
        return ParseTailnetStatus(response);
    }

    private async Task<string> OpenCoreSessionAsync(ConnectionProfile profile, string token, CancellationToken cancellationToken)
    {
        string route = "direct";
        if (profile.Network == NetworkMode.EmbeddedTailnet)
        {
            var status = await StartTailnetCoreAsync(cancellationToken).ConfigureAwait(false);
            if (status.State != EmbeddedTailnetState.Running)
                throw Safe(ErrorCode.CapabilityMissing, status.State == EmbeddedTailnetState.NeedsApproval
                    ? "内嵌 Tailscale 正在等待设备批准。批准后重试。"
                    : "内嵌 Tailscale 尚未就绪。请在连接方式下查看状态并完成登录。");
            route = "tailnet";
        }
        return RequiredText(await CallAsync("open", new { baseUrl = profile.BaseAddress.AbsoluteUri, network = route, token },
            null, cancellationToken).ConfigureAwait(false));
    }

    private static EmbeddedTailnetStatus ParseTailnetStatus(JsonElement response)
    {
        if (response.ValueKind != JsonValueKind.Object) throw InvalidResponse();
        var phase = OptionalText(response, "state") switch
        {
            "Unconfigured" => EmbeddedTailnetState.Unconfigured,
            "Configured" or "NoState" => EmbeddedTailnetState.Configured,
            "Stopped" or "Closed" => EmbeddedTailnetState.Stopped,
            "Starting" => EmbeddedTailnetState.Starting,
            "NeedsLogin" => EmbeddedTailnetState.NeedsLogin,
            "NeedsMachineAuth" => EmbeddedTailnetState.NeedsApproval,
            "Running" => EmbeddedTailnetState.Running,
            _ => EmbeddedTailnetState.Unavailable,
        };
        Uri? approval = null;
        if (phase == EmbeddedTailnetState.NeedsLogin &&
            Uri.TryCreate(OptionalText(response, "authUrl"), UriKind.Absolute, out var candidate) &&
            candidate.Scheme == Uri.UriSchemeHttps && candidate.IdnHost.Equals("login.tailscale.com", StringComparison.OrdinalIgnoreCase) &&
            candidate.IsDefaultPort && candidate.UserInfo.Length == 0 && candidate.Fragment.Length == 0 &&
            candidate.AbsolutePath.StartsWith("/a/", StringComparison.Ordinal))
            approval = candidate;
        var addresses = new List<string>();
        if (response.TryGetProperty("ips", out var ips) && ips.ValueKind == JsonValueKind.Array)
        {
            foreach (var value in ips.EnumerateArray())
                if (value.ValueKind == JsonValueKind.String && IPAddress.TryParse(value.GetString(), out var address))
                    addresses.Add(address.ToString());
        }
        return new(phase, Flag(response, "authenticated"), approval, addresses, Flag(response, "acceptSubnets"));
    }

    private static byte[] ReadOrCreateTailnetIdentity(string directory)
    {
        string? temporary = null;
        byte[]? identity = null;
        try
        {
            var info = new DirectoryInfo(directory);
            using var user = WindowsIdentity.GetCurrent();
            var owner = user.User ?? throw TailnetStorageUnavailable();
            var acl = new DirectorySecurity();
            acl.SetOwner(owner);
            acl.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);
            acl.AddAccessRule(new FileSystemAccessRule(owner, FileSystemRights.FullControl,
                InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
                PropagationFlags.None, AccessControlType.Allow));
            info.Create(acl);
            info.Refresh();
            if ((info.Attributes & FileAttributes.ReparsePoint) != 0 ||
                !owner.Equals(info.GetAccessControl().GetOwner(typeof(SecurityIdentifier))))
                throw TailnetStorageUnavailable();
            info.SetAccessControl(acl);

            string path = Path.Combine(directory, "identity.dpapi");
            string state = Path.Combine(directory, "node.state");
            if (File.Exists(state) && (File.GetAttributes(state) & FileAttributes.ReparsePoint) != 0)
                throw TailnetStorageUnavailable();
            if (File.Exists(path))
            {
                if ((File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0)
                    throw TailnetStorageUnavailable();
                using var file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
                if (file.Length is <= 0 or > 4096) throw TailnetStorageUnavailable();
                var protectedData = new byte[checked((int)file.Length)];
                file.ReadExactly(protectedData);
                identity = ProtectedData.Unprotect(protectedData, TailnetKeyPurpose, DataProtectionScope.CurrentUser);
                if (identity.Length != TailnetIdentityBytes + TailnetKeyBytes) throw TailnetStorageUnavailable();
            }
            else
            {
                // An encrypted state with a missing key must never be silently
                // replaced by another identity.
                if (File.Exists(state)) throw TailnetStorageUnavailable();
                identity = RandomNumberGenerator.GetBytes(TailnetIdentityBytes + TailnetKeyBytes);
                var protectedData = ProtectedData.Protect(identity, TailnetKeyPurpose, DataProtectionScope.CurrentUser);
                temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
                using (var file = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
                {
                    file.Write(protectedData);
                    file.Flush(flushToDisk: true);
                }
                File.Move(temporary, path);
                temporary = null;
            }
            var result = identity;
            identity = null;
            return result;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or CryptographicException or SecurityException)
        { throw TailnetStorageUnavailable(); }
        finally
        {
            if (identity is not null) CryptographicOperations.ZeroMemory(identity);
            if (temporary is not null)
            {
                try { File.Delete(temporary); }
                catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }
            }
        }
    }

    private static FilewayException TailnetStorageUnavailable() => Safe(ErrorCode.LocalStorageUnavailable,
        "无法读取内嵌 Tailscale 的本机身份。已保留原有状态，请检查存储权限后重试。");
}
