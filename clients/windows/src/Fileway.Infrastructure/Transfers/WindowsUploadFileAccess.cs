using System.Globalization;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;
using Microsoft.Win32.SafeHandles;

namespace Fileway.Infrastructure.Transfers;

/// <summary>Native source observations and crash-released task ownership; the caller supplies an existing private lock directory.</summary>
public sealed class WindowsUploadFileAccess : IUploadSourceProvider, ITransferExecutionLockProvider
{
    private readonly string _executionDirectory;

    public WindowsUploadFileAccess(string privateExecutionDirectory)
    {
        _executionDirectory = TransferTaskValidation.LocalFilePath(privateExecutionDirectory);
    }

    public Task<IUploadSource> OpenAsync(string fullPath, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        string requestedPath = TransferTaskValidation.LocalFilePath(fullPath);
        SafeFileHandle? handle = null;
        try
        {
            using var ancestors = GuardDirectories(requestedPath, includeFinal: false, AccessContext.SourceOpen);
            handle = OpenSourceHandle(requestedPath, AccessContext.SourceOpen);
            var snapshot = ObserveSource(handle, AccessContext.SourceOpen);
            RequireCanonicalPath(handle, requestedPath, AccessContext.SourceOpen);
            cancellationToken.ThrowIfCancellationRequested();
            var content = new FileStream(handle, FileAccess.Read, bufferSize: 4096, isAsync: true);
            IUploadSource source = new UploadSource(requestedPath, handle, content, snapshot);
            handle = null; // FileStream is the sole owner of the source handle from this point.
            return Task.FromResult(source);
        }
        catch (Exception error) when (IsFileFailure(error)) { throw ManagedFailure(error); }
        finally { handle?.Dispose(); }
    }

    public Task<ITransferExecutionLock> AcquireAsync(TransferTaskRef task, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        TransferTaskValidation.Reference(task);
        DirectoryGuard? directories = null;
        SafeFileHandle? handle = null;
        try
        {
            directories = GuardDirectories(_executionDirectory, includeFinal: true, AccessContext.Storage);
            string lockName = LockName(task);
            string lockPath = Path.Combine(_executionDirectory, lockName);
            // Create relative to the verified directory object, never by resolving the absolute path again.
            // NT FILE_OPEN_IF opens/creates without truncation; there is no delete-on-close or path cleanup.
            handle = OpenLockHandle(directories.TerminalHandle, lockName);
            _ = Inspect(directories.TerminalHandle, directory: true, AccessContext.Storage);
            RequireCanonicalPath(directories.TerminalHandle, _executionDirectory, AccessContext.Storage);
            var information = Inspect(handle, directory: false, AccessContext.Storage);
            RequireCanonicalPath(handle, lockPath, AccessContext.Storage);
            if (information.Standard.EndOfFile != 0 || information.Standard.NumberOfLinks != 1)
                throw Safe(ErrorCode.LocalStorageUnavailable, "任务执行锁文件不可用。");
            cancellationToken.ThrowIfCancellationRequested();
            ITransferExecutionLock ownership = new ExecutionLock(task, handle, directories);
            handle = null;
            directories = null; // Keep directories pinned too, so replacing a lock directory cannot create a second owner.
            return Task.FromResult(ownership);
        }
        catch (Exception error) when (IsFileFailure(error)) { throw ManagedFailure(error); }
        finally
        {
            handle?.Dispose();
            directories?.Dispose();
        }
    }

    public override string ToString() => "WindowsUploadFileAccess (private directory redacted)";

    private static string LockName(TransferTaskRef task)
    {
        using var bytes = new MemoryStream();
        using (var writer = new BinaryWriter(bytes, Encoding.UTF8, leaveOpen: true))
        {
            writer.Write("FilewayExecutionLock/v1");
            writer.Write(task.Account.ProfileId.ToByteArray());
            writer.Write(task.Account.SourceRevision.ToByteArray());
            writer.Write(task.Account.ServerUserId); // BinaryWriter length-prefixes the full UTF-8 user identity.
            writer.Write(task.Id.ToByteArray());
        }
        string identity = Convert.ToHexStringLower(SHA256.HashData(bytes.GetBuffer().AsSpan(0, checked((int)bytes.Length))));
        return "fileway-execution-v1-" + identity + ".lock";
    }

    private static SafeFileHandle OpenSourceHandle(string path, AccessContext context) =>
        OpenHandle(path, UploadFileNative.GenericRead, UploadFileNative.ShareRead, UploadFileNative.OpenExisting,
            UploadFileNative.OpenReparsePoint | UploadFileNative.BackupSemantics | UploadFileNative.Overlapped |
            UploadFileNative.SequentialScan | UploadFileNative.OpenNoRecall, context);

    private static SafeFileHandle OpenHandle(string path, uint access, uint share, uint creation, uint flags, AccessContext context)
    {
        var handle = UploadFileNative.CreateFileW(NativePath(path), access, share, 0, creation, flags, 0);
        if (!handle.IsInvalid) return handle;
        int error = Marshal.GetLastPInvokeError();
        handle.Dispose();
        throw NativeFailure(error, context);
    }

    private static SafeFileHandle OpenLockHandle(SafeFileHandle directory, string lockName)
    {
        nint nameBuffer = 0, namePointer = 0;
        bool directoryReferenced = false;
        SafeFileHandle? handle = null;
        try
        {
            // LockName is one generated ASCII basename; it can never introduce a root, separator, ADS or traversal.
            nameBuffer = Marshal.StringToHGlobalUni(lockName);
            ushort nameBytes = checked((ushort)(lockName.Length * sizeof(char)));
            var name = new UploadFileNative.UnicodeString
            {
                Length = nameBytes, MaximumLength = checked((ushort)(nameBytes + sizeof(char))), Buffer = nameBuffer,
            };
            namePointer = Marshal.AllocHGlobal(Marshal.SizeOf<UploadFileNative.UnicodeString>());
            Marshal.StructureToPtr(name, namePointer, fDeleteOld: false);
            directory.DangerousAddRef(ref directoryReferenced);
            var attributes = new UploadFileNative.ObjectAttributes
            {
                Length = checked((uint)Marshal.SizeOf<UploadFileNative.ObjectAttributes>()),
                RootDirectory = directory.DangerousGetHandle(), ObjectName = namePointer,
                Attributes = UploadFileNative.ObjectCaseInsensitive | UploadFileNative.ObjectDontReparse,
            };
            // FILE_GENERIC_* include SYNCHRONIZE, as required by FILE_SYNCHRONOUS_IO_NONALERT.
            int status = UploadFileNative.NtCreateFile(out handle,
                UploadFileNative.FileGenericRead | UploadFileNative.FileGenericWrite, in attributes, out var completion,
                allocationSize: 0, UploadFileNative.NormalAttributes, share: 0, UploadFileNative.NtOpenIf,
                UploadFileNative.SynchronousNonAlert | UploadFileNative.NonDirectoryFile | UploadFileNative.OpenReparsePoint,
                eaBuffer: 0, eaLength: 0);
            if (status < 0)
                throw NativeFailure(unchecked((int)UploadFileNative.RtlNtStatusToDosError(status)), AccessContext.Storage);
            if (handle.IsInvalid || completion.Information is not (UploadFileNative.FileOpened or UploadFileNative.FileCreated))
                throw Safe(ErrorCode.LocalStorageUnavailable, "任务执行锁文件不可用。");
            var opened = handle;
            handle = null;
            return opened;
        }
        finally
        {
            handle?.Dispose();
            if (directoryReferenced) directory.DangerousRelease();
            if (namePointer != 0) Marshal.FreeHGlobal(namePointer);
            if (nameBuffer != 0) Marshal.FreeHGlobal(nameBuffer);
        }
    }

    // Lexical input remains an ordinary path. Extended syntax is used only in a native call.
    private static string NativePath(string path) => path.StartsWith(@"\\", StringComparison.Ordinal)
        ? @"\\?\UNC\" + path[2..] : @"\\?\" + path;

    private static DirectoryGuard GuardDirectories(string path, bool includeFinal, AccessContext context)
    {
        string directory = includeFinal ? path : Path.GetDirectoryName(path)!;
        string root = Path.GetPathRoot(directory)!;
        var guard = new DirectoryGuard();
        try
        {
            Add(root);
            string current = root;
            foreach (string component in directory[root.Length..].Split('\\', StringSplitOptions.RemoveEmptyEntries))
            {
                current = Path.Combine(current, component);
                Add(current);
            }
            return guard;
        }
        catch
        {
            guard.Dispose();
            throw;
        }

        void Add(string directoryPath)
        {
            // GENERIC_READ includes FILE_LIST_DIRECTORY: an attribute-only handle does not enforce sharing.
            // Deny directory rename/delete, but allow FILE_ADD_FILE access needed for unrelated child renames.
            // Sharing cannot prohibit FILE_WRITE_ATTRIBUTES; final path/reparse validation remains necessary.
            var handle = OpenHandle(directoryPath, UploadFileNative.GenericRead, UploadFileNative.ShareRead | UploadFileNative.ShareWrite,
                UploadFileNative.OpenExisting, UploadFileNative.OpenReparsePoint | UploadFileNative.BackupSemantics, context);
            guard.Add(handle);
            _ = Inspect(handle, directory: true, context);
            RequireCanonicalPath(handle, directoryPath, context);
        }
    }

    private static (UploadFileNative.BasicInformation Basic, UploadFileNative.StandardInformation Standard) Inspect(
        SafeFileHandle handle, bool directory, AccessContext context)
    {
        uint type = UploadFileNative.GetFileType(handle);
        if (type != UploadFileNative.DiskType)
        {
            int error = Marshal.GetLastPInvokeError();
            if (type == 0 && error != 0) throw NativeFailure(error, context);
            throw Unsupported(context);
        }
        if (!UploadFileNative.GetBasicInformation(handle, 0, out var basic,
            checked((uint)Marshal.SizeOf<UploadFileNative.BasicInformation>())))
            throw NativeFailure(Marshal.GetLastPInvokeError(), context);
        if (!UploadFileNative.GetStandardInformation(handle, 1, out var standard,
            checked((uint)Marshal.SizeOf<UploadFileNative.StandardInformation>())))
            throw NativeFailure(Marshal.GetLastPInvokeError(), context);
        const uint unsupportedAttributes = 0x00000400 | 0x00000040 | 0x00001000 | 0x00040000 | 0x00400000;
        if ((basic.Attributes & unsupportedAttributes) != 0 || standard.DeletePending != 0 ||
            (standard.Directory != 0) != directory || ((basic.Attributes & 0x10) != 0) != directory || standard.EndOfFile < 0)
            throw Unsupported(context);
        return (basic, standard);
    }

    private static TransferSourceSnapshot ObserveSource(SafeFileHandle handle, AccessContext context)
    {
        var information = Inspect(handle, directory: false, context);
        // FILE_ID_INFO retains all 128 file-ID bits, including on ReFS. Unsupported identity queries fail closed.
        if (!UploadFileNative.GetIdentityInformation(handle, 18, out var identity,
            checked((uint)Marshal.SizeOf<UploadFileNative.IdentityInformation>())))
            throw NativeFailure(Marshal.GetLastPInvokeError(), context);
        DateTimeOffset modified;
        try { modified = new DateTimeOffset(DateTime.FromFileTimeUtc(information.Basic.LastWriteTime)); }
        catch (ArgumentOutOfRangeException) { throw Safe(ErrorCode.LocalStorageUnavailable, "无法读取上传源的文件信息。"); }
        string nativeIdentity = string.Create(CultureInfo.InvariantCulture,
            $"win32-fileid128-v1:{identity.VolumeSerialNumber:X16}:{identity.FileIdLow:X16}{identity.FileIdHigh:X16}");
        return new TransferSourceSnapshot(information.Standard.EndOfFile, modified, NativeFileIdentity: nativeIdentity);
    }

    private static void RequireCanonicalPath(SafeFileHandle handle, string requestedPath, AccessContext context)
    {
        ushort[] buffer = new ushort[512]; // LPWSTR storage without handwritten unsafe code or character marshalling.
        for (int attempt = 0; attempt < 3; attempt++)
        {
            uint written = UploadFileNative.GetFinalPathNameByHandleW(handle, buffer, checked((uint)buffer.Length), 0);
            if (written == 0) throw NativeFailure(Marshal.GetLastPInvokeError(), context);
            if (written >= buffer.Length)
            {
                if (written >= 32768) throw Unsupported(context);
                buffer = new ushort[checked((int)written + 1)];
                continue;
            }
            string resolved = new(MemoryMarshal.Cast<ushort, char>(buffer.AsSpan(0, checked((int)written))));
            if (resolved.StartsWith(@"\\?\UNC\", StringComparison.OrdinalIgnoreCase)) resolved = @"\\" + resolved[8..];
            else if (resolved.StartsWith(@"\\?\", StringComparison.Ordinal)) resolved = resolved[4..];
            else throw Unsupported(context);
            if (!string.Equals(resolved.TrimEnd('\\'), requestedPath.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase))
                throw Unsupported(context);
            return;
        }
        throw Unsupported(context);
    }

    private enum AccessContext { SourceOpen, SourceVerify, Storage }

    private static FilewayException Unsupported(AccessContext context) => context switch
    {
        AccessContext.SourceVerify => SourceChanged(),
        AccessContext.Storage => Safe(ErrorCode.LocalStorageUnavailable, "任务执行目录或锁文件不可用。"),
        _ => Safe(ErrorCode.InvalidRequest, "请选择可直接读取的普通本地文件。"),
    };

    private static FilewayException NativeFailure(int code, AccessContext context) => code switch
    {
        5 or 65 or 1314 => Safe(ErrorCode.PermissionDenied, "没有访问本地文件的权限。"),
        32 or 33 => Safe(ErrorCode.Busy, "本地文件或任务正在使用中。"),
        2 or 3 or 15 when context != AccessContext.Storage => SourceChanged(),
        87 or 123 or 161 or 206 when context == AccessContext.SourceOpen => Unsupported(context),
        4390 or 4392 or 4393 or 4394 => Unsupported(context),
        _ => Safe(ErrorCode.LocalStorageUnavailable, "本地文件存储暂不可用。"),
    };

    private static bool IsFileFailure(Exception error) => error is IOException or UnauthorizedAccessException or
        ArgumentException or NotSupportedException or ObjectDisposedException;

    private static FilewayException ManagedFailure(Exception error) => error switch
    {
        ObjectDisposedException => SourceChanged(),
        UnauthorizedAccessException => Safe(ErrorCode.PermissionDenied, "没有访问本地文件的权限。"),
        IOException => NativeFailure(error.HResult & 0xffff, AccessContext.SourceVerify),
        ArgumentException or NotSupportedException => Safe(ErrorCode.InvalidRequest, "本地文件操作无效。"),
        _ => Safe(ErrorCode.LocalStorageUnavailable, "本地文件存储暂不可用。"),
    };

    private static FilewayException SourceChanged() => Safe(ErrorCode.SourceChanged, "上传源已变化或不可用，请重新选择文件。");
    private static FilewayException Safe(ErrorCode code, string message) => new(new ErrorInfo(code, message));

    private sealed class DirectoryGuard : IDisposable
    {
        private readonly List<SafeFileHandle> _handles = [];
        internal SafeFileHandle TerminalHandle => _handles[^1];
        internal void Add(SafeFileHandle handle) => _handles.Add(handle);
        public void Dispose()
        {
            for (int index = _handles.Count - 1; index >= 0; index--) _handles[index].Dispose();
            _handles.Clear();
        }
    }

    private sealed class ExecutionLock(TransferTaskRef task, SafeFileHandle handle, DirectoryGuard directories) : ITransferExecutionLock
    {
        private readonly object _gate = new();
        private bool _disposed;
        public TransferTaskRef Task { get; } = task;
        public ValueTask DisposeAsync()
        {
            lock (_gate)
            {
                if (_disposed) return ValueTask.CompletedTask;
                _disposed = true;
                handle.Dispose();
                directories.Dispose();
            }
            return ValueTask.CompletedTask;
        }
        public override string ToString() => "TransferExecutionLock (owner redacted)";
    }

    private sealed class UploadSource(string requestedPath, SafeFileHandle handle, FileStream content,
        TransferSourceSnapshot snapshot) : IUploadSource
    {
        private readonly object _gate = new();
        private readonly SafeReadStream _content = new(content);
        private bool _disposed;
        private Task? _dispose;
        public Stream Content => _content;
        public TransferSourceSnapshot Snapshot { get; } = snapshot;

        public Task VerifyAsync(TransferSourceSnapshot expected, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            lock (_gate)
            {
                if (_disposed || expected != Snapshot) throw SourceChanged();
                try
                {
                    using var ancestors = GuardDirectories(requestedPath, includeFinal: false, AccessContext.SourceVerify);
                    if (ObserveSource(handle, AccessContext.SourceVerify) != Snapshot) throw SourceChanged();
                    RequireCanonicalPath(handle, requestedPath, AccessContext.SourceVerify);
                    using var current = OpenSourceHandle(requestedPath, AccessContext.SourceVerify);
                    RequireCanonicalPath(current, requestedPath, AccessContext.SourceVerify);
                    if (ObserveSource(current, AccessContext.SourceVerify) != Snapshot) throw SourceChanged();
                    cancellationToken.ThrowIfCancellationRequested();
                }
                catch (Exception error) when (IsFileFailure(error)) { throw ManagedFailure(error); }
            }
            return Task.CompletedTask;
        }

        public ValueTask DisposeAsync()
        {
            lock (_gate)
            {
                _disposed = true;
                _dispose ??= _content.DisposeAsync().AsTask();
                return new ValueTask(_dispose);
            }
        }
        public override string ToString() => "UploadSource (private source redacted)";
    }

    /// <summary>Keeps native/managed I/O failures free of OS messages, paths and inner exceptions.</summary>
    private sealed class SafeReadStream(FileStream content) : Stream
    {
        private FileStream? _file = content;
        private FileStream File => Volatile.Read(ref _file) ?? throw SourceChanged();
        public override bool CanRead => Volatile.Read(ref _file)?.CanRead ?? false;
        public override bool CanSeek => Volatile.Read(ref _file)?.CanSeek ?? false;
        public override bool CanWrite => false;
        public override long Length => Invoke(file => file.Length);
        public override long Position { get => Invoke(file => file.Position); set => Invoke(file => file.Position = value); }
        public override long Seek(long offset, SeekOrigin origin) => Invoke(file => file.Seek(offset, origin));
        public override int ReadByte() => Invoke(file => file.ReadByte());
        public override int Read(byte[] buffer, int offset, int count) => Invoke(file => file.Read(buffer, offset, count));
        public override int Read(Span<byte> buffer)
        {
            try { return File.Read(buffer); }
            catch (Exception error) when (IsFileFailure(error)) { throw ManagedFailure(error); }
        }
        public override async Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken)
        {
            try
            {
                ArgumentNullException.ThrowIfNull(buffer);
                return await File.ReadAsync(buffer.AsMemory(offset, count), cancellationToken).ConfigureAwait(false);
            }
            catch (Exception error) when (IsFileFailure(error)) { throw ManagedFailure(error); }
        }
        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            try { return await File.ReadAsync(buffer, cancellationToken).ConfigureAwait(false); }
            catch (Exception error) when (IsFileFailure(error)) { throw ManagedFailure(error); }
        }
        public override void Flush() => _ = File;
        public override Task FlushAsync(CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Flush();
            return Task.CompletedTask;
        }
        public override void SetLength(long value) => throw ReadOnly();
        public override void Write(byte[] buffer, int offset, int count) => throw ReadOnly();
        public override void Write(ReadOnlySpan<byte> buffer) => throw ReadOnly();
        public override Task WriteAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken) => throw ReadOnly();
        public override ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken cancellationToken = default) => throw ReadOnly();

        private T Invoke<T>(Func<FileStream, T> action)
        {
            try { return action(File); }
            catch (Exception error) when (IsFileFailure(error)) { throw ManagedFailure(error); }
        }
        protected override void Dispose(bool disposing)
        {
            try { if (disposing) Interlocked.Exchange(ref _file, null)?.Dispose(); }
            catch (Exception error) when (IsFileFailure(error)) { throw ManagedFailure(error); }
            finally { base.Dispose(disposing); }
        }
        public override async ValueTask DisposeAsync()
        {
            var file = Interlocked.Exchange(ref _file, null);
            try { if (file is not null) await file.DisposeAsync().ConfigureAwait(false); }
            catch (Exception error) when (IsFileFailure(error)) { throw ManagedFailure(error); }
            finally
            {
                await base.DisposeAsync().ConfigureAwait(false);
                GC.SuppressFinalize(this);
            }
        }
        private static FilewayException ReadOnly() => Safe(ErrorCode.InvalidRequest, "上传源只支持读取。");
        public override string ToString() => "UploadContent (private source redacted)";
    }
}

internal static partial class UploadFileNative
{
    internal const uint GenericRead = 0x80000000, GenericWrite = 0x40000000;
    internal const uint ShareRead = 1, ShareWrite = 2, OpenExisting = 3, DiskType = 1;
    internal const uint OpenReparsePoint = 0x00200000, BackupSemantics = 0x02000000, Overlapped = 0x40000000,
        SequentialScan = 0x08000000, OpenNoRecall = 0x00100000;
    internal const uint ObjectCaseInsensitive = 0x40, ObjectDontReparse = 0x1000, NtOpenIf = 3;
    internal const uint FileGenericRead = 0x00120089, FileGenericWrite = 0x00120116, NormalAttributes = 0x80;
    internal const uint SynchronousNonAlert = 0x20, NonDirectoryFile = 0x40;
    internal const uint FileOpened = 1, FileCreated = 2;

    [StructLayout(LayoutKind.Sequential)]
    internal struct UnicodeString
    {
        internal ushort Length, MaximumLength;
        internal nint Buffer;
    }
    [StructLayout(LayoutKind.Sequential)]
    internal struct ObjectAttributes
    {
        internal uint Length;
        internal nint RootDirectory, ObjectName;
        internal uint Attributes;
        internal nint SecurityDescriptor, SecurityQualityOfService;
    }
    [StructLayout(LayoutKind.Sequential)]
    internal struct IoStatusBlock
    {
        internal nint StatusOrPointer; // Native NTSTATUS/PVOID union occupies one pointer-sized slot.
        internal nuint Information;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct BasicInformation
    {
        internal long CreationTime, LastAccessTime, LastWriteTime, ChangeTime;
        internal uint Attributes;
    }
    [StructLayout(LayoutKind.Sequential)]
    internal struct StandardInformation
    {
        internal long AllocationSize, EndOfFile;
        internal uint NumberOfLinks;
        internal byte DeletePending, Directory;
    }
    [StructLayout(LayoutKind.Sequential)]
    internal struct IdentityInformation
    {
        internal ulong VolumeSerialNumber, FileIdLow, FileIdHigh;
    }

    [LibraryImport("kernel32.dll", EntryPoint = "CreateFileW", StringMarshalling = StringMarshalling.Utf16, SetLastError = true)]
    internal static partial SafeFileHandle CreateFileW(string name, uint access, uint share, nint securityAttributes,
        uint creation, uint flags, nint template);
    [LibraryImport("ntdll.dll")]
    internal static partial int NtCreateFile(out SafeFileHandle handle, uint access, in ObjectAttributes attributes,
        out IoStatusBlock completion, nint allocationSize, uint fileAttributes, uint share, uint disposition,
        uint options, nint eaBuffer, uint eaLength);
    [LibraryImport("ntdll.dll")]
    internal static partial uint RtlNtStatusToDosError(int status);
    [LibraryImport("kernel32.dll", SetLastError = true)]
    internal static partial uint GetFileType(SafeFileHandle handle);
    [LibraryImport("kernel32.dll", EntryPoint = "GetFileInformationByHandleEx", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool GetBasicInformation(SafeFileHandle handle, int informationClass,
        out BasicInformation information, uint size);
    [LibraryImport("kernel32.dll", EntryPoint = "GetFileInformationByHandleEx", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool GetStandardInformation(SafeFileHandle handle, int informationClass,
        out StandardInformation information, uint size);
    [LibraryImport("kernel32.dll", EntryPoint = "GetFileInformationByHandleEx", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool GetIdentityInformation(SafeFileHandle handle, int informationClass,
        out IdentityInformation information, uint size);
    [LibraryImport("kernel32.dll", SetLastError = true)]
    internal static partial uint GetFinalPathNameByHandleW(SafeFileHandle handle, [Out] ushort[] path, uint capacity, uint flags);
}
