using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;

namespace Fileway.Infrastructure.Ipc;

/// <summary>Creates the child inside its Job, with only its three pipe handles inherited.</summary>
internal sealed class NativeHostProcess : IDisposable
{
    private readonly SafeFileHandle _job;
    private readonly SafeProcessHandle _process;
    private int _disposed;

    private NativeHostProcess(SafeFileHandle job, SafeProcessHandle process, int processId,
        AnonymousPipeServerStream input, AnonymousPipeServerStream output, AnonymousPipeServerStream error)
    {
        _job = job;
        _process = process;
        ProcessId = processId;
        Input = input;
        Output = output;
        Error = error;
        bool held = false;
        _process.DangerousAddRef(ref held);
        nint waitHandle = _process.DangerousGetHandle();
        try { Exit = Task.Run(() => WaitForExit(waitHandle)); }
        catch { if (held) _process.DangerousRelease(); throw; }
    }

    internal int ProcessId { get; }
    internal Stream Input { get; }
    internal Stream Output { get; }
    internal Stream Error { get; }
    internal Task<int?> Exit { get; }

    internal static unsafe NativeHostProcess Start(string executable)
    {
        var input = new AnonymousPipeServerStream(PipeDirection.Out, HandleInheritability.Inheritable);
        var output = new AnonymousPipeServerStream(PipeDirection.In, HandleInheritability.Inheritable);
        var error = new AnonymousPipeServerStream(PipeDirection.In, HandleInheritability.Inheritable);
        SafeFileHandle? job = null;
        SafeProcessHandle? process = null;
        nint attributes = 0;
        bool initialized = false;
        try
        {
            // NULL security attributes make this handle non-inheritable. It is
            // deliberately absent from HANDLE_LIST: the parent owns the last handle.
            job = new SafeFileHandle(NativeMethods.CreateJobObjectW(0, null), true);
            if (job.IsInvalid)
                throw new IOException("Cannot create the Host lifetime boundary.");
            NativeMethods.JobExtendedLimit limits = new();
            limits.Basic.LimitFlags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE.
            if (!NativeMethods.SetInformationJobObject(job, 9, &limits, (uint)sizeof(NativeMethods.JobExtendedLimit)))
                throw new IOException("Cannot configure the Host lifetime boundary.");

            nuint bytes = 0;
            _ = NativeMethods.InitializeProcThreadAttributeList(0, 2, 0, ref bytes);
            if (bytes == 0)
                throw new IOException("Cannot prepare private Host handles.");
            attributes = Marshal.AllocHGlobal(checked((nint)bytes));
            if (!NativeMethods.InitializeProcThreadAttributeList(attributes, 2, 0, ref bytes))
                throw new IOException("Cannot initialize private Host handles.");
            initialized = true;
            nint* inherited = stackalloc nint[3]
            {
                input.ClientSafePipeHandle.DangerousGetHandle(),
                output.ClientSafePipeHandle.DangerousGetHandle(),
                error.ClientSafePipeHandle.DangerousGetHandle(),
            };
            nint jobValue = job.DangerousGetHandle();
            // ProcThreadAttributeValue(JobList=13, thread=false, input=true, additive=false).
            if (!NativeMethods.UpdateProcThreadAttribute(attributes, 0, 0x2000D, &jobValue, (nuint)sizeof(nint), 0, 0) ||
                !NativeMethods.UpdateProcThreadAttribute(attributes, 0, 0x20002, inherited, (nuint)(3 * sizeof(nint)), 0, 0))
                throw new IOException("Cannot bind private Host handles.");
            NativeMethods.StartupInfoEx startup = new();
            startup.Startup.Size = (uint)sizeof(NativeMethods.StartupInfoEx);
            startup.Startup.Flags = 0x100; // STARTF_USESTDHANDLES.
            startup.Startup.StandardInput = inherited[0];
            startup.Startup.StandardOutput = inherited[1];
            startup.Startup.StandardError = inherited[2];
            startup.Attributes = attributes;
            // JOB_LIST assignment happens inside CreateProcess. There is no
            // unowned Process.Start -> AssignProcessToJobObject interval.
            char[] environment = MinimalEnvironment();
            NativeMethods.ProcessInformation information;
            fixed (char* environmentBlock = environment)
            {
                if (!NativeMethods.CreateProcessW(executable, null, 0, 0, true,
                        0x08000000 | 0x00080000 | 0x400, (nint)environmentBlock, Path.GetDirectoryName(executable), ref startup, out information))
                    throw new IOException("Cannot create the supervised Host.");
            }
            process = new SafeProcessHandle(information.Process, true);
            using var thread = new SafeFileHandle(information.Thread, true);
            input.DisposeLocalCopyOfClientHandle();
            output.DisposeLocalCopyOfClientHandle();
            error.DisposeLocalCopyOfClientHandle();
            var result = new NativeHostProcess(job, process, checked((int)information.ProcessId), input, output, error);
            job = null;
            process = null;
            return result;
        }
        catch
        {
            job?.Dispose(); // Also kills a child if a later managed step failed.
            process?.Dispose();
            input.Dispose();
            output.Dispose();
            error.Dispose();
            throw;
        }
        finally
        {
            if (initialized)
                NativeMethods.DeleteProcThreadAttributeList(attributes);
            if (attributes != 0)
                Marshal.FreeHGlobal(attributes);
        }
    }

    private static char[] MinimalEnvironment()
    {
        string windows = Environment.GetFolderPath(Environment.SpecialFolder.Windows);
        string temporary = Path.GetFullPath(Path.GetTempPath());
        var values = new SortedDictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            ["SystemRoot"] = windows, ["WINDIR"] = windows,
            ["PATH"] = Path.Combine(windows, "System32"), ["TEMP"] = temporary, ["TMP"] = temporary,
        };
        // The independent managed test harness needs only its explicitly
        // installed runtime directory. Never inherit the parent's environment.
        foreach (string name in new[] { "DOTNET_ROOT", "DOTNET_ROOT_X64" })
        {
            string? root = Environment.GetEnvironmentVariable(name);
            if (root is not null && Path.IsPathFullyQualified(root) && File.Exists(Path.Combine(root, "dotnet.exe")))
                values[name] = Path.GetFullPath(root);
        }
        var block = new StringBuilder();
        foreach (var pair in values) block.Append(pair.Key).Append('=').Append(pair.Value).Append('\0');
        block.Append('\0');
        return block.ToString().ToCharArray();
    }

    private int? WaitForExit(nint handle)
    {
        try
        {
            if (NativeMethods.WaitForSingleObject(handle, uint.MaxValue) != 0)
                return null;
            return NativeMethods.GetExitCodeProcess(handle, out uint code) ? unchecked((int)code) : null;
        }
        finally
        {
            _process.DangerousRelease();
        }
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0)
            return;
        _job.Dispose(); // Release child endpoints before interrupting blocked parent I/O.
        Input.Dispose();
        Output.Dispose();
        Error.Dispose();
        _process.Dispose();
    }
}

internal static partial class NativeMethods
{
    [StructLayout(LayoutKind.Sequential)]
    internal struct StartupInfo
    {
        internal uint Size;
        internal nint Reserved, Desktop, Title;
        internal uint X, Y, XSize, YSize, XCountChars, YCountChars, FillAttribute, Flags;
        internal ushort ShowWindow, ReservedSize;
        internal nint ReservedBytes, StandardInput, StandardOutput, StandardError;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct StartupInfoEx { internal StartupInfo Startup; internal nint Attributes; }

    [StructLayout(LayoutKind.Sequential)]
    internal struct ProcessInformation { internal nint Process, Thread; internal uint ProcessId, ThreadId; }

    [StructLayout(LayoutKind.Sequential)]
    internal struct JobBasicLimit
    {
        internal long PerProcessUserTime, PerJobUserTime;
        internal uint LimitFlags;
        internal nuint MinimumWorkingSet, MaximumWorkingSet;
        internal uint ActiveProcessLimit;
        internal nuint Affinity;
        internal uint PriorityClass, SchedulingClass;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct IoCounters { internal ulong ReadOperations, WriteOperations, OtherOperations, ReadBytes, WriteBytes, OtherBytes; }

    [StructLayout(LayoutKind.Sequential)]
    internal struct JobExtendedLimit
    {
        internal JobBasicLimit Basic;
        internal IoCounters Io;
        internal nuint ProcessMemory, JobMemory, PeakProcessMemory, PeakJobMemory;
    }

    [LibraryImport("kernel32.dll", EntryPoint = "CreateJobObjectW", StringMarshalling = StringMarshalling.Utf16, SetLastError = true)]
    internal static partial nint CreateJobObjectW(nint attributes, string? name);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static unsafe partial bool SetInformationJobObject(SafeFileHandle job, int informationClass, void* information, uint length);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool InitializeProcThreadAttributeList(nint list, int count, uint flags, ref nuint bytes);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static unsafe partial bool UpdateProcThreadAttribute(nint list, uint flags, nuint attribute, void* value, nuint size, nint previous, nint returned);

    [LibraryImport("kernel32.dll")]
    internal static partial void DeleteProcThreadAttributeList(nint list);

    [LibraryImport("kernel32.dll", EntryPoint = "CreateProcessW", StringMarshalling = StringMarshalling.Utf16, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static unsafe partial bool CreateProcessW(string application, char* commandLine, nint processAttributes,
        nint threadAttributes, [MarshalAs(UnmanagedType.Bool)] bool inheritHandles, uint flags, nint environment,
        string? currentDirectory, ref StartupInfoEx startup, out ProcessInformation information);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    internal static partial uint WaitForSingleObject(nint handle, uint milliseconds);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool GetExitCodeProcess(nint process, out uint code);
}
