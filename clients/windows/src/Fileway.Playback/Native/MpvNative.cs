using System.Runtime.InteropServices;
using System.Text.Json;
using Fileway.Core.Contracts;

namespace Fileway.Playback.Native;

internal sealed class MpvNative : IDisposable
{
    private readonly nint _library;
    private readonly CreateFunction _create;
    private readonly HandleFunction _initialize;
    private readonly DestroyFunction _destroy;
    private readonly OptionFunction _option;
    private readonly CommandFunction _command;
    private readonly ObserveFunction _observe;
    private readonly WaitFunction _wait;
    private readonly WakeFunction _wake;
    private readonly StringFunction _string;
    private readonly FreeFunction _free;
    private readonly AddReadOnlyStreamFunction _addReadOnlyStream;
    private int _disposed;

    internal MpvNative(string runtimeDirectory)
    {
        string directory = Path.GetFullPath(runtimeDirectory);
        string dll = Path.Combine(directory, "libmpv-2.dll");
        if (!Path.IsPathFullyQualified(runtimeDirectory) || !File.Exists(dll))
            throw Error(ErrorCode.CapabilityMissing, "固定播放器运行库不可用。");
        // Only the explicitly selected DLL directory and Windows System32 are
        // searched. No PATH/CWD or user-provided mpv configuration is consulted.
        _library = MpvLibraryMethods.LoadLibraryExW(dll, 0, 0x100 | 0x800);
        if (_library == 0) throw Error(ErrorCode.CapabilityMissing, "播放器运行库或固定依赖未能加载。");
        try
        {
            var version = Export<VersionFunction>("mpv_client_api_version")();
            if ((version >> 16) != 2) throw Error(ErrorCode.CapabilityMissing, "播放器原生 API 版本不兼容。");
            _create = Export<CreateFunction>("mpv_create");
            _initialize = Export<HandleFunction>("mpv_initialize");
            _destroy = Export<DestroyFunction>("mpv_terminate_destroy");
            _option = Export<OptionFunction>("mpv_set_option_string");
            _command = Export<CommandFunction>("mpv_command_async");
            _observe = Export<ObserveFunction>("mpv_observe_property");
            _wait = Export<WaitFunction>("mpv_wait_event");
            _wake = Export<WakeFunction>("mpv_wakeup");
            _string = Export<StringFunction>("mpv_get_property_string");
            _free = Export<FreeFunction>("mpv_free");
            _addReadOnlyStream = Export<AddReadOnlyStreamFunction>("mpv_stream_cb_add_ro");
        }
        catch { _ = MpvLibraryMethods.FreeLibrary(_library); throw; }
    }

    private T Export<T>(string name) where T : Delegate => Marshal.GetDelegateForFunctionPointer<T>(NativeLibrary.GetExport(_library, name));
    internal nint Create() => _create();
    internal void Initialize(nint handle) => Check(_initialize(handle));
    internal void Option(nint handle, string name, string value) => Check(_option(handle, name, value));
    internal void Observe(nint handle, ulong id, string name, int format) => Check(_observe(handle, id, name, format));
    internal void Wake(nint handle) => _wake(handle);
    internal void Destroy(nint handle) => _destroy(handle);
    internal void AddReadOnlyStreamProtocol(nint handle, string protocol, nint userData,
        MpvDownloadStreamBridge.OpenCallback open) => Check(_addReadOnlyStream(handle, protocol, userData, open));

    internal string? String(nint handle, string name)
    {
        nint value = _string(handle, name);
        if (value == 0) return null;
        try { return Marshal.PtrToStringUTF8(value); }
        finally { _free(value); }
    }

    internal unsafe void Command(nint handle, ulong id, string[] arguments)
    {
        nint[] strings = new nint[arguments.Length + 1];
        try
        {
            for (int i = 0; i < arguments.Length; i++) strings[i] = Marshal.StringToCoTaskMemUTF8(arguments[i]);
            fixed (nint* array = strings) Check(_command(handle, id, (nint)array));
        }
        finally { foreach (nint value in strings) if (value != 0) Marshal.FreeCoTaskMem(value); }
    }

    internal MpvEvent Wait(nint handle)
    {
        var native = Marshal.PtrToStructure<EventData>(_wait(handle, 0.1));
        MpvProperty? property = null;
        string[]? messages = null;
        int endReason = -1;
        if (native.Id == 22 && native.Data != 0)
        {
            var data = Marshal.PtrToStructure<PropertyData>(native.Data);
            string name = Marshal.PtrToStringUTF8(data.Name) ?? "";
            object? value = null;
            if (data.Data != 0)
            {
                value = data.Format switch
                {
                    1 => Marshal.PtrToStringUTF8(Marshal.ReadIntPtr(data.Data)),
                    3 => Marshal.ReadInt32(data.Data) != 0,
                    4 => Marshal.ReadInt64(data.Data),
                    5 => Marshal.PtrToStructure<double>(data.Data),
                    6 => JsonSerializer.SerializeToElement(CopyNode(Marshal.PtrToStructure<Node>(data.Data), 0)),
                    _ => null,
                };
            }
            property = new MpvProperty(name, value);
        }
        else if (native.Id == 7 && native.Data != 0) endReason = Marshal.ReadInt32(native.Data);
        else if (native.Id == 16 && native.Data != 0)
        {
            var message = Marshal.PtrToStructure<ClientMessage>(native.Data);
            if (message.Count is >= 0 and <= 16)
            {
                messages = new string[message.Count];
                for (int i = 0; i < messages.Length; i++) messages[i] = Marshal.PtrToStringUTF8(Marshal.ReadIntPtr(message.Arguments, i * nint.Size)) ?? "";
            }
        }
        // Every referenced native value has been copied before the next wait.
        return new MpvEvent(native.Id, native.Error, native.User, property, endReason, messages);
    }

    private static object? CopyNode(Node node, int depth)
    {
        if (depth > 16) return null;
        if (node.Format == 1) return Marshal.PtrToStringUTF8(node.Pointer);
        if (node.Format == 3) return node.Flag != 0;
        if (node.Format == 4) return node.Integer;
        if (node.Format == 5) return node.Number;
        if (node.Format is not (7 or 8) || node.Pointer == 0) return null;
        var list = Marshal.PtrToStructure<NodeList>(node.Pointer);
        if (list.Count is < 0 or > 512) return null;
        if (node.Format == 7)
        {
            var result = new object?[list.Count];
            for (int i = 0; i < list.Count; i++) result[i] = CopyNode(Marshal.PtrToStructure<Node>(list.Values + i * Marshal.SizeOf<Node>()), depth + 1);
            return result;
        }
        var map = new Dictionary<string, object?>(StringComparer.Ordinal);
        for (int i = 0; i < list.Count; i++)
        {
            string key = Marshal.PtrToStringUTF8(Marshal.ReadIntPtr(list.Keys, i * nint.Size)) ?? "";
            map[key] = CopyNode(Marshal.PtrToStructure<Node>(list.Values + i * Marshal.SizeOf<Node>()), depth + 1);
        }
        return map;
    }

    internal static void Check(int result)
    {
        if (result < 0) throw Error(ErrorCode.Unknown, $"播放器原生命令失败（{result}）。");
    }

    internal static FilewayException Error(ErrorCode code, string message) => new(new ErrorInfo(code, message));
    public void Dispose() { if (Interlocked.Exchange(ref _disposed, 1) == 0) _ = MpvLibraryMethods.FreeLibrary(_library); }

    [StructLayout(LayoutKind.Sequential)] private struct EventData { internal int Id, Error; internal ulong User; internal nint Data; }
    [StructLayout(LayoutKind.Sequential)] private struct PropertyData { internal nint Name; internal int Format; internal nint Data; }
    [StructLayout(LayoutKind.Sequential)] private struct ClientMessage { internal int Count; internal nint Arguments; }
    [StructLayout(LayoutKind.Explicit, Size = 16)] private struct Node
    { [FieldOffset(0)] internal nint Pointer; [FieldOffset(0)] internal long Integer; [FieldOffset(0)] internal int Flag; [FieldOffset(0)] internal double Number; [FieldOffset(8)] internal int Format; }
    [StructLayout(LayoutKind.Sequential)] private struct NodeList { internal int Count; internal nint Values, Keys; }

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate uint VersionFunction();
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate nint CreateFunction();
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate int HandleFunction(nint handle);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate void DestroyFunction(nint handle);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate int OptionFunction(nint handle, [MarshalAs(UnmanagedType.LPUTF8Str)] string name, [MarshalAs(UnmanagedType.LPUTF8Str)] string value);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate int CommandFunction(nint handle, ulong id, nint arguments);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate int ObserveFunction(nint handle, ulong id, [MarshalAs(UnmanagedType.LPUTF8Str)] string name, int format);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate nint WaitFunction(nint handle, double timeout);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate void WakeFunction(nint handle);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate nint StringFunction(nint handle, [MarshalAs(UnmanagedType.LPUTF8Str)] string name);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate void FreeFunction(nint value);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate int AddReadOnlyStreamFunction(nint handle,
        [MarshalAs(UnmanagedType.LPUTF8Str)] string protocol, nint userData, MpvDownloadStreamBridge.OpenCallback open);
}

internal sealed record MpvEvent(int Id, int Error, ulong User, MpvProperty? Property, int EndReason, string[]? Messages);
internal sealed record MpvProperty(string Name, object? Value);

internal static partial class MpvLibraryMethods
{
    [LibraryImport("kernel32.dll", EntryPoint = "LoadLibraryExW", StringMarshalling = StringMarshalling.Utf16, SetLastError = true)]
    internal static partial nint LoadLibraryExW(string path, nint reserved, uint flags);
    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)] internal static partial bool FreeLibrary(nint library);
}
