using System.Diagnostics;
using System.Net;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.ServerManagement;

internal static partial class ServerDiscovery
{
    private const uint AfInet = 2;
    private const uint TcpTableOwnerPidAll = 5;

    /// <summary>SNMP TCP states: 1 is closed and 2 is listening, so filtering on 1 would drop every listener.</summary>
    private const int TcpStateListen = 2;
    private const int InsufficientBuffer = 122;

    private static readonly string[] ServerProcessNames = ["fileway-server", "filebrowser", "fileway"];

    [StructLayout(LayoutKind.Sequential)]
    private struct TcpRowOwnerPid
    {
        internal uint State;
        internal uint LocalAddress;
        internal uint LocalPort;
        internal uint RemoteAddress;
        internal uint RemotePort;
        internal uint OwningProcess;
    }

    /// <summary>
    /// Finds Fileway servers already listening on this machine. Process names are only used to bound the
    /// search; the answer to /health is what proves a port really is a Fileway server, so an older
    /// deployment named filebrowser.exe is still found.
    /// </summary>
    internal static async Task<IReadOnlyList<LocalServerInstance>> DetectAsync(int managedPort, int? managedProcessId, HttpClient http, CancellationToken cancellationToken)
    {
        var candidates = new Dictionary<int, LocalServerInstance>();
        try
        {
            foreach ((int port, int owner) in ListeningEndpoints())
            {
                cancellationToken.ThrowIfCancellationRequested();
                if (!TryDescribe(owner, out string name)) continue;
                if (!IsServerName(name) && port != managedPort) continue;
                candidates[port] = new LocalServerInstance(port, owner, name,
                    ManagedByThisClient: port == managedPort && owner == managedProcessId, ServingWebUi: false);
            }
        }
        catch (Exception error) when (error is not OperationCanceledException)
        {
            // A broken table read must degrade to “nothing detected”, never hide the rest of the page.
            return Array.Empty<LocalServerInstance>();
        }

        var results = new List<LocalServerInstance>(candidates.Count);
        foreach (LocalServerInstance candidate in candidates.Values.OrderBy(instance => instance.Port))
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (!await IsFilewayAsync(http, candidate.Port, cancellationToken).ConfigureAwait(false)) continue;
            bool web = await HasWebUiAsync(http, candidate.Port, cancellationToken).ConfigureAwait(false);
            results.Add(candidate with
            {
                ServingWebUi = web,
                ManagedByThisClient = candidate.ManagedByThisClient || (candidate.Port == managedPort && candidate.ProcessId == managedProcessId),
            });
        }
        return results;
    }

    private static bool IsServerName(string processName)
    {
        foreach (string server in ServerProcessNames)
            if (string.Equals(processName, server, StringComparison.OrdinalIgnoreCase)) return true;
        return false;
    }

    private static IEnumerable<(int Port, int ProcessId)> ListeningEndpoints()
    {
        uint length = 0;
        // The size probe reports ERROR_INSUFFICIENT_BUFFER as its return value; SetLastError is not reliable
        // here because other marshalling clears it before it can be read.
        uint sizeResult = GetExtendedTcpTable(nint.Zero, ref length, false, AfInet, TcpTableOwnerPidAll, 0);
        if (sizeResult != 0 && sizeResult != InsufficientBuffer) yield break;
        if (length == 0) yield break;
        nint buffer = Marshal.AllocHGlobal(checked((int)length));
        try
        {
            if (GetExtendedTcpTable(buffer, ref length, false, AfInet, TcpTableOwnerPidAll, 0) != 0) yield break;
            int count = Marshal.ReadInt32(buffer);
            int rowSize = Marshal.SizeOf<TcpRowOwnerPid>();
            for (int index = 0; index < count; index++)
            {
                TcpRowOwnerPid row = Marshal.PtrToStructure<TcpRowOwnerPid>(buffer + 4 + index * rowSize);
                if (row.State != TcpStateListen) continue;
                bool loopbackOrAny = row.LocalAddress == 0 || (row.LocalAddress & 0xFF) == 127;
                if (!loopbackOrAny) continue;
                uint swapped = ((row.LocalPort & 0xFF00) >> 8) | ((row.LocalPort & 0x00FF) << 8);
                if (swapped is > 0 and < 65536) yield return ((int)swapped, (int)row.OwningProcess);
            }
        }
        finally { Marshal.FreeHGlobal(buffer); }
    }

    private static bool TryDescribe(int processId, out string processName)
    {
        processName = string.Empty;
        if (processId <= 0) return false;
        try
        {
            using var process = Process.GetProcessById(processId);
            processName = process.ProcessName;
            return true;
        }
        catch (Exception error) when (error is not OperationCanceledException)
        {
            // The owner exited between the table snapshot and the lookup, or belongs to another session.
            _ = error;
            return false;
        }
    }

    private static async Task<bool> IsFilewayAsync(HttpClient http, int port, CancellationToken cancellationToken)
    {
        try
        {
            using var response = await http.GetAsync($"http://127.0.0.1:{port.ToString(System.Globalization.CultureInfo.InvariantCulture)}/health",
                HttpCompletionOption.ResponseContentRead, cancellationToken).ConfigureAwait(false);
            if (response.StatusCode != HttpStatusCode.OK) return false;
            string body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            return body.Contains("\"status\"", StringComparison.OrdinalIgnoreCase) && body.Contains("OK", StringComparison.OrdinalIgnoreCase);
        }
        catch (Exception error) when (error is HttpRequestException or TaskCanceledException or OperationCanceledException)
        {
            return false;
        }
    }

    private static async Task<bool> HasWebUiAsync(HttpClient http, int port, CancellationToken cancellationToken)
    {
        try
        {
            using var response = await http.GetAsync($"http://127.0.0.1:{port.ToString(System.Globalization.CultureInfo.InvariantCulture)}/",
                HttpCompletionOption.ResponseHeadersRead, cancellationToken).ConfigureAwait(false);
            if (response.StatusCode != HttpStatusCode.OK) return false;
            string type = response.Content.Headers.ContentType?.MediaType ?? string.Empty;
            return type.Contains("html", StringComparison.OrdinalIgnoreCase);
        }
        catch (Exception error) when (error is HttpRequestException or TaskCanceledException or OperationCanceledException)
        {
            return false;
        }
    }

    [LibraryImport("iphlpapi.dll", SetLastError = true)]
    private static partial uint GetExtendedTcpTable(nint table, ref uint tableSize, [MarshalAs(UnmanagedType.Bool)] bool order,
        uint addressFamily, uint tableClass, uint reserved);
}
