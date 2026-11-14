using System.ComponentModel;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;

namespace Fileway.Infrastructure.ServerManagement;

internal enum ServerStopOutcome
{
    Graceful,

    /// <summary>The listener is closed but the process is still unwinding; it must not be killed.</summary>
    Draining,
    Forced,
    AlreadyExited,
    Unknown,
}

/// <summary>Launches and terminates the managed server without leaking handles or windows.</summary>
internal static partial class ServerProcess
{
    internal const uint StillActive = 259;

    private const uint CreateNewConsole = 0x00000010;
    private const uint CreateUnicodeEnvironment = 0x00000400;
    private const uint StartupInfoUseShowWindow = 0x00000001;
    private const uint StartupInfoUseStandardHandles = 0x00000100;
    private const ushort ShowWindowHidden = 0;
    private const uint GenericRead = 0x80000000;
    private const uint FileAppendData = 0x00000004;
    private const uint ShareReadWriteDelete = 0x00000007;
    private const uint OpenAlways = 4;
    private const uint FileAttributeNormal = 0x80;
    private const uint ControlBreak = 1;
    private const uint WaitObjectSignaled = 0;
    private const uint WaitTimeout = 0x00000102;
    private const uint ProcessTerminateRights = 0x0001;
    private const uint ProcessQueryRights = 0x1000;

    private static readonly object ConsoleGate = new();

    [StructLayout(LayoutKind.Sequential)]
    private struct SecurityAttributes
    {
        internal uint Length;
        internal nint Descriptor;
        internal int InheritHandle;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct StartupInfo
    {
        internal uint Size;
        internal nint Reserved, Desktop, Title;
        internal uint X, Y, XSize, YSize, XCountChars, YCountChars, FillAttribute, Flags;
        internal ushort ShowWindow, ReservedSize;
        internal nint ReservedBytes, StandardInput, StandardOutput, StandardError;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ProcessInformation
    {
        internal nint Process, Thread;
        internal uint ProcessId, ThreadId;
    }

    internal static unsafe SafeProcessHandle Start(string executable, IReadOnlyList<string> arguments, string workingDirectory,
        string diagnosticsPath, string outputPath)
    {
        SecurityAttributes inheritable = new() { Length = (uint)Marshal.SizeOf<SecurityAttributes>(), InheritHandle = 1 };
        using var diagnostics = OpenAppendHandle(diagnosticsPath, inheritable);
        using var output = OpenAppendHandle(outputPath, inheritable);
        StartupInfo startup = new()
        {
            Size = (uint)Marshal.SizeOf<StartupInfo>(),
            Flags = StartupInfoUseShowWindow | StartupInfoUseStandardHandles,
            ShowWindow = ShowWindowHidden,
            StandardError = diagnostics.DangerousGetHandle(),
            StandardOutput = output.DangerousGetHandle(),
        };
        char[] environment = BuildEnvironmentBlock();
        ProcessInformation information;
        fixed (char* block = environment)
        {
            bool created = CreateProcessW(executable, CommandLine(executable, arguments), nint.Zero, nint.Zero, true,
                CreateNewConsole | CreateUnicodeEnvironment, (nint)block, workingDirectory, ref startup, out information);
            if (!created)
                throw new IOException($"Cannot create the local server process (Win32 error {Marshal.GetLastWin32Error()}).");
        }
        using var thread = new SafeFileHandle(information.Thread, true);
        GC.KeepAlive(diagnostics);
        GC.KeepAlive(output);
        return new SafeProcessHandle(information.Process, true);
    }

    /// <summary>Reopens a previously launched instance; used after a client restart or a boot-started server.</summary>
    internal static SafeProcessHandle? TryOpen(int processId)
    {
        nint handle = OpenProcess(ProcessTerminateRights | ProcessQueryRights, false, (uint)processId);
        return handle == nint.Zero || handle == -1 ? null : new SafeProcessHandle(handle, true);
    }

    /// <summary>
    /// Asks the child's console to shut down. A server that stopped accepting connections has
    /// already served the user's intent, so the process is never killed for taking longer to unwind.
    /// </summary>
    internal static async Task<(ServerStopOutcome Outcome, string Signal)> StopAsync(
        SafeProcessHandle handle, Func<Task<bool>> isServing, TimeSpan gracefulWindow, CancellationToken cancellationToken)
    {
        int processId = ProcessIdOf(handle);
        if (HasExited(handle)) return (ServerStopOutcome.AlreadyExited, "already exited");
        // The Go server traps SIGINT, and CTRL_BREAK is the only event addressable to one process id.
        System.Diagnostics.Stopwatch watch = System.Diagnostics.Stopwatch.StartNew();
        string signal = Signal(processId);
        string delivered = signal.Length == 0
            ? "停止事件在 " + watch.ElapsedMilliseconds.ToString(CultureInfo.InvariantCulture) + "ms 内投递"
            : signal;
        if (signal.Length != 0) return await ForceAsync(handle, delivered, cancellationToken).ConfigureAwait(false);

        watch.Restart();
        DateTime deadline = DateTime.UtcNow + gracefulWindow;
        while (DateTime.UtcNow < deadline)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (HasExited(handle))
                return (ServerStopOutcome.Graceful, delivered + "，进程在 " + watch.ElapsedMilliseconds.ToString(CultureInfo.InvariantCulture) + "ms 内退出");
            if (!await isServing().ConfigureAwait(false))
            {
                // Give the process a short grace period to finish, then leave it to unwind on its own.
                if (await WaitForExitAsync(handle, TimeSpan.FromSeconds(3), cancellationToken).ConfigureAwait(false))
                    return (ServerStopOutcome.Graceful, delivered + "，优雅停机完成");
                return (ServerStopOutcome.Draining, delivered + "，已停止监听，进程仍在收尾");
            }
            await Task.Delay(250, cancellationToken).ConfigureAwait(false);
        }
        return await ForceAsync(handle, delivered + "，超时未响应", cancellationToken).ConfigureAwait(false);
    }

    private static async Task<(ServerStopOutcome, string)> ForceAsync(SafeProcessHandle handle, string prefix, CancellationToken cancellationToken)
    {
        bool terminated = TerminateProcess(handle, 1);
        bool exited = terminated && await WaitForExitAsync(handle, TimeSpan.FromSeconds(5), cancellationToken).ConfigureAwait(false);
        if (exited) return (ServerStopOutcome.Forced, prefix + "，已结束进程");
        return terminated ? (ServerStopOutcome.Unknown, prefix + "，结束进程后仍未观察到退出")
            : (ServerStopOutcome.Unknown, prefix + "，结束进程失败，Win32 " + Marshal.GetLastWin32Error().ToString(CultureInfo.InvariantCulture));
    }

    internal static int ProcessIdOf(SafeProcessHandle handle) => NativeGetProcessId(handle.DangerousGetHandle());

    internal static bool HasExited(SafeProcessHandle handle) => !GetExitCodeProcess(handle, out uint code) || code != StillActive;

    internal static int? ExitCode(SafeProcessHandle handle)
    {
        if (!GetExitCodeProcess(handle, out uint code) || code == StillActive) return null;
        return unchecked((int)code);
    }

    internal static async Task<bool> WaitForExitAsync(SafeProcessHandle handle, TimeSpan window, CancellationToken cancellationToken)
    {
        DateTime deadline = DateTime.UtcNow + window;
        while (true)
        {
            cancellationToken.ThrowIfCancellationRequested();
            int remaining = (int)(deadline - DateTime.UtcNow).TotalMilliseconds;
            if (remaining <= 0) return HasExited(handle);
            uint wait = WaitForSingleObject(handle.DangerousGetHandle(), (uint)remaining);
            if (wait == WaitObjectSignaled) return true;
            if (wait != WaitTimeout) return HasExited(handle);
            await Task.Delay(100, cancellationToken).ConfigureAwait(false);
        }
    }

    /// <summary>An empty result means the event was delivered to the child's console.</summary>
    private static string Signal(int processId)
    {
        lock (ConsoleGate)
        {
            // A GUI parent owns no console; detaching first makes the attach unambiguous.
            _ = FreeConsole();
            if (!AttachConsole((uint)processId))
                return "无法接入服务端控制台，Win32 " + Marshal.GetLastWin32Error().ToString(CultureInfo.InvariantCulture);
            try
            {
                // CTRL_BREAK is the only event that can be addressed to a single process id, so the
                // client itself is never a recipient. Go maps it to SIGINT, which the server traps.
                bool sent = GenerateConsoleCtrlEvent(ControlBreak, (uint)processId);
                return sent ? string.Empty : "无法投递停止事件，Win32 " + Marshal.GetLastWin32Error().ToString(CultureInfo.InvariantCulture);
            }
            finally { _ = FreeConsole(); }
        }
    }

    private static SafeFileHandle OpenAppendHandle(string path, SecurityAttributes attributes)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        nint handle = CreateFileW(path, FileAppendData | GenericRead, ShareReadWriteDelete, ref attributes, OpenAlways, FileAttributeNormal, nint.Zero);
        if (handle == nint.Zero || handle == -1)
            throw new IOException($"Cannot open the local server log file (Win32 error {Marshal.GetLastWin32Error()}).");
        return new SafeFileHandle(handle, true);
    }

    private static string CommandLine(string executable, IReadOnlyList<string> arguments)
    {
        var builder = new StringBuilder(Quoted(executable));
        foreach (string argument in arguments) builder.Append(' ').Append(Quoted(argument));
        return builder.ToString();
    }

    private static string Quoted(string value)
    {
        if (value.Length > 0 && !value.Contains(' ', StringComparison.Ordinal) && !value.Contains('"', StringComparison.Ordinal))
            return value;
        // Windows argv rules: backslashes preceding a quote double, the quote itself escapes.
        var builder = new StringBuilder(value.Length + 4).Append('"');
        int backslashes = 0;
        foreach (char character in value)
        {
            if (character == '\\') { backslashes++; continue; }
            if (character == '"') builder.Append('\\', backslashes * 2 + 1).Append('"');
            else builder.Append('\\', backslashes).Append(character);
            backslashes = 0;
        }
        builder.Append('\\', backslashes * 2).Append('"');
        return builder.ToString();
    }

    private static char[] BuildEnvironmentBlock()
    {
        var values = new SortedDictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (System.Collections.DictionaryEntry entry in Environment.GetEnvironmentVariables())
            values[(string)entry.Key!] = entry.Value?.ToString() ?? string.Empty;
        string applicationDirectory = AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar);
        values["PATH"] = values.TryGetValue("PATH", out string? existing) && !string.IsNullOrEmpty(existing)
            ? applicationDirectory + Path.PathSeparator + existing
            : applicationDirectory;
        var block = new StringBuilder();
        foreach (var pair in values) block.Append(pair.Key).Append('=').Append(pair.Value).Append('\0');
        block.Append('\0');
        return block.ToString().ToCharArray();
    }

    [LibraryImport("kernel32.dll", EntryPoint = "CreateProcessW", StringMarshalling = StringMarshalling.Utf16, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static unsafe partial bool CreateProcessW(string application, string commandLine, nint processAttributes,
        nint threadAttributes, [MarshalAs(UnmanagedType.Bool)] bool inheritHandles, uint flags, nint environment,
        string? currentDirectory, ref StartupInfo startup, out ProcessInformation information);

    [LibraryImport("kernel32.dll", SetLastError = true, StringMarshalling = StringMarshalling.Utf16)]
    private static partial nint CreateFileW(string name, uint access, uint share, ref SecurityAttributes attributes,
        uint disposition, uint flags, nint template);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool AttachConsole(uint processId);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool FreeConsole();

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool GenerateConsoleCtrlEvent(uint controlEvent, uint processGroupId);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool TerminateProcess(SafeProcessHandle process, uint exitCode);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    private static partial nint OpenProcess(uint access, [MarshalAs(UnmanagedType.Bool)] bool inherit, uint processId);

    [LibraryImport("kernel32.dll", EntryPoint = "GetProcessId")]
    private static partial int NativeGetProcessId(nint process);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    private static partial uint WaitForSingleObject(nint handle, uint milliseconds);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool GetExitCodeProcess(SafeProcessHandle process, out uint code);
}
