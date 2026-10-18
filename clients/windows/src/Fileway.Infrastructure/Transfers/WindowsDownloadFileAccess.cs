using System.Buffers;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;
using Microsoft.Win32.SafeHandles;

namespace Fileway.Infrastructure.Transfers;

/// <summary>Fresh, same-directory local downloads. Persisted paths alone never grant mutation authority.</summary>
public sealed class WindowsDownloadFileAccess : IDownloadDestinationProvider
{
    public async Task DeletePublishedAsync(string finalPath, TransferWorkFiles files, CancellationToken cancellationToken)
    {
        await using var destination = await OpenOwnedAsync(finalPath, files, cancellationToken).ConfigureAwait(false);
        if (destination is not DownloadDestination owned || !destination.IsPublished) throw Unknown();
        await owned.DeleteOwnedPublishedAsync(cancellationToken).ConfigureAwait(false);
    }

    private const string PartialPrefix = "fileway-download-v1-", PartialSuffix = ".partial";
    private const int CreationAttempts = 16;
    private static readonly SearchValues<char> PartialHex = SearchValues.Create("0123456789abcdef");
    private static readonly SearchValues<char> IdentityHex = SearchValues.Create("0123456789ABCDEF");
    private readonly Func<string> _partialNameFactory;
    private readonly Action? _afterCreated;

    public WindowsDownloadFileAccess() : this(() => PartialPrefix +
        Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(16)) + PartialSuffix) { }

    // A generated-basename seam for collision tests; it never changes create-only native semantics.
    internal WindowsDownloadFileAccess(Func<string> partialNameFactory) : this(partialNameFactory, afterCreated: null) { }

    // Runs only after strict FILE_CREATED proof, to exercise failures before later identity observations.
    internal WindowsDownloadFileAccess(Func<string> partialNameFactory, Action? afterCreated)
    {
        _partialNameFactory = partialNameFactory ?? throw new ArgumentNullException(nameof(partialNameFactory));
        _afterCreated = afterCreated;
    }

    public Task<IDownloadDestination> CreateFreshAsync(string finalPath, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        string final = TransferTaskValidation.LocalFilePath(finalPath);
        RequireLocalPath(final, AccessContext.Fresh);
        DirectoryGuard? directories = null;
        SafeFileHandle? handle = null;
        bool created = false;
        string? partial = null, identity = null;
        try
        {
            directories = GuardDirectories(final, AccessContext.Fresh);
            directories.Revalidate(AccessContext.Fresh);
            cancellationToken.ThrowIfCancellationRequested();
            uint? finalAttributes = ReadRelativeEntryAttributes(directories.TerminalHandle, Path.GetFileName(final));
            directories.Revalidate(AccessContext.Fresh);
            cancellationToken.ThrowIfCancellationRequested();
            // This observation only avoids allocating/transferring an already-conflicting download.
            // A later arrival is still rejected by the mandatory no-replace publication operation.
            if (finalAttributes is not null) throw Conflict();
            for (int attempt = 0; attempt < CreationAttempts; attempt++)
            {
                cancellationToken.ThrowIfCancellationRequested();
                string name = _partialNameFactory();
                if (!IsPartialName(name)) throw Invalid();
                partial = Path.Combine(Path.GetDirectoryName(final)!, name);
                if (StringComparer.OrdinalIgnoreCase.Equals(partial, final)) continue;
                directories.Revalidate(AccessContext.Fresh);
                // FILE_CREATE cannot open, truncate or delete a colliding file, directory or reparse entry.
                handle = OpenRelative(directories.TerminalHandle, name,
                    DownloadFileNative.OwnedAccess, create: true, AccessContext.Fresh);
                if (handle is null) continue;
                created = true;
                _afterCreated?.Invoke();
                var information = Observe(handle, directory: false, AccessContext.Fresh);
                RequireCanonicalPath(handle, partial, AccessContext.Fresh);
                if (information.Length != 0) throw Unknown();
                identity = information.Identity;
                directories.Revalidate(AccessContext.Fresh);
                cancellationToken.ThrowIfCancellationRequested();
                IDownloadDestination destination = new DownloadDestination(final, partial, identity, handle, directories,
                    new TransferWorkFiles(partial, identity), length: 0, published: false, readerAdmission: true);
                handle = null;
                directories = null;
                return Task.FromResult(destination);
            }
            throw Conflict();
        }
        catch (Exception error)
        {
            // FILE_CREATED itself proves this exclusively held object was created by this acquire; no
            // rename/publish has occurred. Cleanup of that object remains safe even if subsequent metadata
            // queries or parent/path observations fail. A generated name or unknown create result is not proof.
            // Unconfirmed cleanup overrides cancellation/ordinary failure; the runner must protect the outcome.
            if (created && handle is not null && !TryDeleteCreated(handle)) throw Unknown();
            if (IsFileFailure(error)) throw ManagedFailure(error, AccessContext.Fresh);
            throw;
        }
        finally
        {
            handle?.Dispose();
            directories?.Dispose();
        }
    }

    public Task<IDownloadDestination> OpenOwnedAsync(string finalPath, TransferWorkFiles files, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        DirectoryGuard? directories = null;
        SafeFileHandle? handle = null;
        try
        {
            string final = TransferTaskValidation.LocalFilePath(finalPath);
            RequireLocalPath(final, AccessContext.Owned);
            ValidateEvidence(final, files);
            string partial = TransferTaskValidation.LocalFilePath(files.PartialPath!);
            string identity = files.PartialIdentity!;
            directories = GuardDirectories(final, AccessContext.Owned);
            handle = OpenRelative(directories.TerminalHandle, Path.GetFileName(partial),
                DownloadFileNative.OwnedAccess, create: false, AccessContext.Owned);
            bool published = false;
            Observation information;
            if (handle is not null)
            {
                // A single-link, exclusive, positively owned partial cannot also be the final entry.
                // Do not open an unrelated final just to gain authority to clean up this partial.
                if (files.PublishedFileIdentity is not null) throw Unknown();
                information = Observe(handle, directory: false, AccessContext.Owned);
                RequireCanonicalPath(handle, partial, AccessContext.Owned);
                if (!StringComparer.Ordinal.Equals(information.Identity, identity)) throw Unknown();
            }
            else
            {
                // The held final gets read authority only. It can never become a deletion capability.
                handle = OpenRelative(directories.TerminalHandle, Path.GetFileName(final),
                    UploadFileNative.FileGenericRead, create: false, AccessContext.Owned);
                if (handle is null) throw Unknown();
                information = Observe(handle, directory: false, AccessContext.Owned);
                RequireCanonicalPath(handle, final, AccessContext.Owned);
                if (!StringComparer.Ordinal.Equals(information.Identity, identity)) throw Unknown();
                // Both names present is ambiguous evidence, even if a newly created partial is a stranger.
                using var ambiguous = OpenRelative(directories.TerminalHandle, Path.GetFileName(partial),
                    UploadFileNative.FileGenericRead, create: false, AccessContext.Owned);
                if (ambiguous is not null) throw Unknown();
                files = files with { PublishedFileIdentity = identity };
                published = true;
            }
            directories.Revalidate(AccessContext.Owned);
            cancellationToken.ThrowIfCancellationRequested();
            IDownloadDestination destination = new DownloadDestination(final, partial, identity, handle, directories,
                files, information.Length, published, readerAdmission: false);
            handle = null;
            directories = null;
            return Task.FromResult(destination);
        }
        catch (FilewayException) { throw Unknown(); }
        catch (Exception error) when (IsFileFailure(error)) { throw Unknown(); }
        finally
        {
            handle?.Dispose();
            directories?.Dispose();
        }
    }

    public override string ToString() => "WindowsDownloadFileAccess (private destinations redacted)";

    private static void ValidateEvidence(string final, TransferWorkFiles? files)
    {
        if (files is null || files.PartialPath is null || !IsIdentity(files.PartialIdentity) ||
            files.OriginalTargetIdentity is not null || files.BackupPath is not null || files.BackupIdentity is not null ||
            files.PublishedFileIdentity is not null && !StringComparer.Ordinal.Equals(files.PublishedFileIdentity, files.PartialIdentity))
            throw Unknown();
        string partial = TransferTaskValidation.LocalFilePath(files.PartialPath);
        if (StringComparer.OrdinalIgnoreCase.Equals(final, partial) || !IsPartialName(Path.GetFileName(partial)) ||
            !StringComparer.OrdinalIgnoreCase.Equals(Path.GetDirectoryName(final), Path.GetDirectoryName(partial)))
            throw Unknown();
    }

    private static bool IsPartialName(string? name) => name is not null &&
        name.Length == PartialPrefix.Length + 32 + PartialSuffix.Length &&
        name.StartsWith(PartialPrefix, StringComparison.Ordinal) && name.EndsWith(PartialSuffix, StringComparison.Ordinal) &&
        name.AsSpan(PartialPrefix.Length, 32).ContainsAnyExcept(PartialHex) == false;

    private static bool IsIdentity(string? identity)
    {
        const string prefix = "win32-fileid128-v1:";
        return identity is not null && identity.Length == prefix.Length + 16 + 1 + 32 &&
            identity.StartsWith(prefix, StringComparison.Ordinal) && identity[prefix.Length + 16] == ':' &&
            identity.AsSpan(prefix.Length, 16).ContainsAnyExcept(IdentityHex) == false &&
            identity.AsSpan(prefix.Length + 17, 32).ContainsAnyExcept(IdentityHex) == false;
    }

    private static SafeFileHandle? OpenRelative(SafeFileHandle directory, string name, uint access, bool create, AccessContext context)
    {
        nint nameBuffer = 0, namePointer = 0;
        bool directoryReferenced = false;
        SafeFileHandle? handle = null;
        try
        {
            nameBuffer = Marshal.StringToHGlobalUni(name);
            ushort nameBytes = checked((ushort)(name.Length * sizeof(char)));
            var nativeName = new UploadFileNative.UnicodeString
            {
                Length = nameBytes, MaximumLength = checked((ushort)(nameBytes + sizeof(char))), Buffer = nameBuffer,
            };
            namePointer = Marshal.AllocHGlobal(Marshal.SizeOf<UploadFileNative.UnicodeString>());
            Marshal.StructureToPtr(nativeName, namePointer, fDeleteOld: false);
            directory.DangerousAddRef(ref directoryReferenced);
            var attributes = new UploadFileNative.ObjectAttributes
            {
                Length = checked((uint)Marshal.SizeOf<UploadFileNative.ObjectAttributes>()),
                RootDirectory = directory.DangerousGetHandle(), ObjectName = namePointer,
                Attributes = UploadFileNative.ObjectCaseInsensitive | UploadFileNative.ObjectDontReparse,
            };
            // Synchronous NT handles make metadata completion unambiguous; RandomAccess schedules async I/O
            // for these handles without giving a FileStream ownership of them or leaving buffered bytes.
            int status = UploadFileNative.NtCreateFile(out handle, access, in attributes, out var completion,
                allocationSize: 0, UploadFileNative.NormalAttributes, share: 0,
                create ? DownloadFileNative.NtCreate : DownloadFileNative.NtOpen,
                UploadFileNative.SynchronousNonAlert | UploadFileNative.NonDirectoryFile |
                UploadFileNative.OpenReparsePoint | DownloadFileNative.NtOpenNoRecall, eaBuffer: 0, eaLength: 0);
            if (status < 0)
            {
                int code = unchecked((int)UploadFileNative.RtlNtStatusToDosError(status));
                handle.Dispose();
                handle = null; // A failed create/open conveys no ownership; release it before a metadata probe.
                if (create && code is 80 or 183 || !create && code is 2 or 3) return null;
                // NT can report AccessDenied when a create-only non-directory request collides with
                // a directory/reparse entry. Do not confuse that with insufficient parent permissions.
                if (create && context == AccessContext.Fresh && code == 5 &&
                    IsExistingDirectoryOrReparseEntry(directory, name)) return null;
                throw NativeFailure(code, context);
            }
            if (status != 0 || !CompletionSucceeded(completion) || handle.IsInvalid ||
                completion.Information != (create ? UploadFileNative.FileCreated : UploadFileNative.FileOpened))
                throw Unknown();
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

    private static bool IsExistingDirectoryOrReparseEntry(SafeFileHandle directory, string name)
    {
        // A collision observation never grants ownership, content, write or delete authority. This
        // basename is one generated ASCII component; the retained parent is already guarded and pinned.
        if (!IsPartialName(name)) return false;
        try
        {
            const uint directoryOrReparse = 0x00000010 | 0x00000400;
            return ReadRelativeEntryAttributes(directory, name) is { } attributes && (attributes & directoryOrReparse) != 0;
        }
        catch (FilewayException) { return false; }
        catch (Exception error) when (IsFileFailure(error)) { return false; }
    }

    private static uint? ReadRelativeEntryAttributes(SafeFileHandle directory, string name)
    {
        // Both callers supply one already-validated basename. Open only that leaf relative to the
        // retained parent, with read-attributes authority; reparse targets and content are never read.
        nint nameBuffer = 0, namePointer = 0;
        bool directoryReferenced = false;
        SafeFileHandle? handle = null;
        try
        {
            nameBuffer = Marshal.StringToHGlobalUni(name);
            ushort nameBytes = checked((ushort)(name.Length * sizeof(char)));
            var nativeName = new UploadFileNative.UnicodeString
            {
                Length = nameBytes, MaximumLength = checked((ushort)(nameBytes + sizeof(char))), Buffer = nameBuffer,
            };
            namePointer = Marshal.AllocHGlobal(Marshal.SizeOf<UploadFileNative.UnicodeString>());
            Marshal.StructureToPtr(nativeName, namePointer, fDeleteOld: false);
            directory.DangerousAddRef(ref directoryReferenced);
            var attributes = new UploadFileNative.ObjectAttributes
            {
                Length = checked((uint)Marshal.SizeOf<UploadFileNative.ObjectAttributes>()),
                RootDirectory = directory.DangerousGetHandle(), ObjectName = namePointer,
                // OBJ_DONT_REPARSE rejects a reparse entry. Here FILE_OPEN_REPARSE_POINT opens the
                // leaf object itself; the single basename cannot introduce intermediate traversal.
                Attributes = UploadFileNative.ObjectCaseInsensitive,
            };
            int status = UploadFileNative.NtCreateFile(out handle, DownloadFileNative.MetadataAccess, in attributes,
                out var completion, allocationSize: 0, UploadFileNative.NormalAttributes, DownloadFileNative.ShareAll,
                DownloadFileNative.NtOpen, UploadFileNative.SynchronousNonAlert |
                UploadFileNative.OpenReparsePoint | DownloadFileNative.NtOpenNoRecall, eaBuffer: 0, eaLength: 0);
            // Only a typed missing leaf proves absence. Path/ACL/sharing/unknown failures must not
            // authorize fresh work, even if their Win32 translation resembles another missing case.
            if (status == unchecked((int)0xC0000034)) return null; // STATUS_OBJECT_NAME_NOT_FOUND
            if (status < 0) throw NativeFailure(unchecked((int)UploadFileNative.RtlNtStatusToDosError(status)), AccessContext.Fresh);
            if (status != 0 || !CompletionSucceeded(completion) || handle.IsInvalid ||
                completion.Information != UploadFileNative.FileOpened || UploadFileNative.GetFileType(handle) != UploadFileNative.DiskType)
                throw Unknown();
            if (!UploadFileNative.GetBasicInformation(handle, 0, out var basic,
                checked((uint)Marshal.SizeOf<UploadFileNative.BasicInformation>())))
                throw NativeFailure(Marshal.GetLastPInvokeError(), AccessContext.Fresh);
            return basic.Attributes;
        }
        finally
        {
            handle?.Dispose();
            if (directoryReferenced) directory.DangerousRelease();
            if (namePointer != 0) Marshal.FreeHGlobal(namePointer);
            if (nameBuffer != 0) Marshal.FreeHGlobal(nameBuffer);
        }
    }

    private static DirectoryGuard GuardDirectories(string final, AccessContext context)
    {
        string directory = Path.GetDirectoryName(final)!;
        string root = Path.GetPathRoot(directory)!;
        var guard = new DirectoryGuard(root);
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

        void Add(string path)
        {
            // Hold real read access so sharing denies ancestor/parent deletion or replacement. Sharing write
            // permits unrelated sibling creation/rename; reparse attributes are rechecked before mutations.
            var handle = UploadFileNative.CreateFileW(NativePath(path), UploadFileNative.GenericRead,
                UploadFileNative.ShareRead | UploadFileNative.ShareWrite, 0, UploadFileNative.OpenExisting,
                UploadFileNative.OpenReparsePoint | UploadFileNative.BackupSemantics | UploadFileNative.OpenNoRecall, 0);
            if (handle.IsInvalid)
            {
                int error = Marshal.GetLastPInvokeError();
                handle.Dispose();
                throw NativeFailure(error, context);
            }
            guard.Add(path, handle);
            var observation = Observe(handle, directory: true, context);
            RequireCanonicalPath(handle, path, context);
            guard.SetLastIdentity(observation.Identity);
        }
    }

    private static Observation Observe(SafeFileHandle handle, bool directory, AccessContext context)
    {
        uint type = UploadFileNative.GetFileType(handle);
        if (type != UploadFileNative.DiskType) throw Unsupported(context);
        if (!UploadFileNative.GetBasicInformation(handle, 0, out var basic,
            checked((uint)Marshal.SizeOf<UploadFileNative.BasicInformation>())))
            throw NativeFailure(Marshal.GetLastPInvokeError(), context);
        if (!UploadFileNative.GetStandardInformation(handle, 1, out var standard,
            checked((uint)Marshal.SizeOf<UploadFileNative.StandardInformation>())))
            throw NativeFailure(Marshal.GetLastPInvokeError(), context);
        const uint unsupported = 0x00000400 | 0x00000040 | 0x00001000 | 0x00040000 | 0x00400000;
        if ((basic.Attributes & unsupported) != 0 || standard.DeletePending != 0 || standard.EndOfFile < 0 ||
            (standard.Directory != 0) != directory || ((basic.Attributes & 0x10) != 0) != directory ||
            !directory && standard.NumberOfLinks != 1) throw Unsupported(context);
        if (!UploadFileNative.GetIdentityInformation(handle, 18, out var identity,
            checked((uint)Marshal.SizeOf<UploadFileNative.IdentityInformation>())))
            throw NativeFailure(Marshal.GetLastPInvokeError(), context);
        if (identity.VolumeSerialNumber == 0 || (identity.FileIdLow == 0 && identity.FileIdHigh == 0))
            throw Unsupported(context);
        string nativeIdentity = string.Create(CultureInfo.InvariantCulture,
            $"win32-fileid128-v1:{identity.VolumeSerialNumber:X16}:{identity.FileIdLow:X16}{identity.FileIdHigh:X16}");
        return new Observation(standard.EndOfFile, nativeIdentity);
    }

    private static void RequireLocalPath(string path, AccessContext context)
    {
        // FILE_TYPE_DISK includes network files. Explicitly reject UNC and mapped network drives too.
        if (path.StartsWith(@"\\", StringComparison.Ordinal) ||
            DownloadFileNative.GetDriveTypeW(Path.GetPathRoot(path)!) != DownloadFileNative.FixedDrive)
            throw Unsupported(context);
    }

    private static string NativePath(string path) => @"\\?\" + path;

    private static void RequireCanonicalPath(SafeFileHandle handle, string expected, AccessContext context)
    {
        ushort[] buffer = new ushort[512];
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
            if (!resolved.StartsWith(@"\\?\", StringComparison.Ordinal) || resolved.StartsWith(@"\\?\UNC\", StringComparison.OrdinalIgnoreCase))
                throw Unsupported(context);
            resolved = resolved[4..];
            if (!StringComparer.OrdinalIgnoreCase.Equals(resolved.TrimEnd('\\'), expected.TrimEnd('\\')))
                throw Unsupported(context);
            return;
        }
        throw Unsupported(context);
    }

    private static bool TryDeleteCreated(SafeFileHandle handle)
    {
        try
        {
            // No more observations or file I/O after requesting FileDispositionInformation.
            return DeleteByHandle(handle) == 0;
        }
        catch (FilewayException) { return false; }
        catch (Exception error) when (IsFileFailure(error)) { return false; }
        finally { handle.Dispose(); }
    }

    private static int DeleteByHandle(SafeFileHandle handle)
    {
        var disposition = new DownloadFileNative.DispositionInformation { DeleteFile = 1 };
        int status = DownloadFileNative.SetDispositionInformation(handle, out var completion, in disposition,
            checked((uint)Marshal.SizeOf<DownloadFileNative.DispositionInformation>()), DownloadFileNative.FileDispositionInformation);
        return status == 0 && CompletionSucceeded(completion) ? 0 : status == 0 ? -1 : status;
    }

    private static bool CompletionSucceeded(UploadFileNative.IoStatusBlock completion) =>
        // IO_STATUS_BLOCK overlays 32-bit NTSTATUS with PVOID; its unused upper pointer bits are not status.
        unchecked((int)completion.StatusOrPointer) == 0;

    private static int RenameByHandle(SafeFileHandle handle, string finalName, out UploadFileNative.IoStatusBlock completion)
    {
        char[] name = finalName.ToCharArray();
        int nameBytes = checked(name.Length * sizeof(char));
        int length = checked(Marshal.SizeOf<DownloadFileNative.RenameInformation>() + nameBytes);
        nint buffer = Marshal.AllocHGlobal(length);
        try
        {
            // Native union/pointer alignment is derived from the actual architecture, never hardcoded x64.
            Marshal.Copy(new byte[length], 0, buffer, length);
            Marshal.WriteIntPtr(buffer, checked((int)Marshal.OffsetOf<DownloadFileNative.RenameInformation>(nameof(DownloadFileNative.RenameInformation.RootDirectory))), 0);
            Marshal.WriteInt32(buffer, checked((int)Marshal.OffsetOf<DownloadFileNative.RenameInformation>(nameof(DownloadFileNative.RenameInformation.FileNameLength))), nameBytes);
            Marshal.Copy(name, 0, buffer + checked((int)Marshal.OffsetOf<DownloadFileNative.RenameInformation>(nameof(DownloadFileNative.RenameInformation.FileName))), name.Length);
            // ReplaceIfExists is FALSE, RootDirectory is NULL and the name is a basename: documented
            // same-directory rename of the held object, without reopening source/target or copying bytes.
            return DownloadFileNative.NtSetInformationFile(handle, out completion, buffer,
                checked((uint)length), DownloadFileNative.FileRenameInformation);
        }
        finally { Marshal.FreeHGlobal(buffer); }
    }

    private enum AccessContext { Fresh, Owned, Operation, Publish }
    private sealed record Observation(long Length, string Identity);

    private static FilewayException NativeFailure(int code, AccessContext context)
    {
        if (context == AccessContext.Owned) return Unknown();
        return code switch
        {
            80 or 183 or 267 when context == AccessContext.Publish => Conflict(),
            5 or 65 or 1314 => Safe(ErrorCode.PermissionDenied, "没有访问本地文件的权限。"),
            32 or 33 => Safe(ErrorCode.Busy, "本地文件或任务正在使用中。"),
            87 or 123 or 161 or 206 or 4390 or 4392 or 4393 or 4394 or 4395 => Unsupported(context),
            _ => Safe(ErrorCode.LocalStorageUnavailable, "本地文件存储暂不可用。"),
        };
    }

    private static bool IsFileFailure(Exception error) => error is IOException or UnauthorizedAccessException or
        ArgumentException or NotSupportedException or ObjectDisposedException or OverflowException;
    private static FilewayException ManagedFailure(Exception error, AccessContext context) => error switch
    {
        ObjectDisposedException => Unknown(),
        UnauthorizedAccessException => NativeFailure(5, context),
        IOException => NativeFailure(error.HResult & 0xffff, context),
        ArgumentException or NotSupportedException or OverflowException => Unsupported(context),
        _ => Safe(ErrorCode.LocalStorageUnavailable, "本地文件存储暂不可用。"),
    };
    private static FilewayException Unsupported(AccessContext context) => context == AccessContext.Fresh ? Invalid() : Unknown();
    private static FilewayException Invalid() => Safe(ErrorCode.InvalidRequest, "请选择可直接写入的普通本地文件位置。");
    private static FilewayException Conflict() => Safe(ErrorCode.Conflict, "目标名称已存在，请选择其他位置。");
    private static FilewayException Unknown() => Safe(ErrorCode.OutcomeUnknown, "无法确认本地下载文件的归属或发布结果，请检查后重试。");
    private static FilewayException Safe(ErrorCode code, string message) => new(new ErrorInfo(code, message));

    private sealed class DirectoryGuard(string root) : IDisposable
    {
        private readonly List<(string Path, SafeFileHandle Handle, string? Identity)> _entries = [];
        internal SafeFileHandle TerminalHandle => _entries[^1].Handle;
        internal void Add(string path, SafeFileHandle handle) => _entries.Add((path, handle, null));
        internal void SetLastIdentity(string identity)
        {
            var entry = _entries[^1];
            _entries[^1] = (entry.Path, entry.Handle, identity);
        }
        internal void Revalidate(AccessContext context)
        {
            RequireLocalPath(root, context);
            foreach (var entry in _entries)
            {
                var current = Observe(entry.Handle, directory: true, context);
                RequireCanonicalPath(entry.Handle, entry.Path, context);
                if (!StringComparer.Ordinal.Equals(current.Identity, entry.Identity)) throw Unknown();
            }
        }
        public void Dispose()
        {
            for (int index = _entries.Count - 1; index >= 0; index--) _entries[index].Handle.Dispose();
            _entries.Clear();
        }
    }

    private sealed class DownloadDestination(string final, string partial, string identity, SafeFileHandle handle,
        DirectoryGuard directories, TransferWorkFiles files, long length, bool published, bool readerAdmission)
        : IDownloadDestination, IReadableDownloadDestination
    {
        private const int MaximumReaders = 4, MaximumReadBytes = 128 * 1024;
        private static readonly TimeSpan ReaderPrefixTimeout = TimeSpan.FromSeconds(30);
        private static readonly TimeSpan ReaderIoTimeout = TimeSpan.FromSeconds(30);
        private static readonly TimeSpan ReaderDrainTimeout = TimeSpan.FromSeconds(30);
        private readonly SemaphoreSlim _serial = new(1, 1);
        private readonly object _readerGate = new();
        private readonly List<DownloadReadLease> _readers = [];
        private TaskCompletionSource _prefixChanged = NewSignal();
        private Task? _revocation, _disposal;
        private TransferRunToken? _readerRun;
        private long _readerTotal, _readablePrefix = length;
        private TransferWorkFiles _files = files;
        private long _length = length;
        private bool _published = published, _publicationVerified = published, _disposed, _deleted, _writeFaulted,
            _publishUncertain, _deleteUncertain;
        private bool _readersRevoked, _readersDrained, _readerFaulted, _handleClosed, _admissionClosed;
        public TransferWorkFiles Files => Volatile.Read(ref _files);
        public bool IsPublished => Volatile.Read(ref _published);
        public long Length => Interlocked.Read(ref _length);

        public async Task<IDownloadReadLease> AcquireReadLeaseAsync(TransferRunToken run, long totalBytes,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            TransferTaskValidation.Reference(run.Task);
            if (run.Id == Guid.Empty || run.Generation <= 0 || totalBytes < 0) throw ReaderInvalid();
            await _serial.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                RequireActive();
                RequireCompleteWrites();
                if (!readerAdmission || _published) throw Unknown();
                try { Verify(compareLength: true); }
                catch { FaultReaders(); throw; }
                cancellationToken.ThrowIfCancellationRequested();
                lock (_readerGate)
                {
                    if (_readersRevoked || _readerFaulted || _handleClosed || _admissionClosed) throw Unknown();
                    if (_readerRun is { } binding && (binding != run || _readerTotal != totalBytes) || _length > totalBytes)
                        throw ReaderInvalid();
                    if (_readers.Count >= MaximumReaders) throw Safe(ErrorCode.Busy, "本地下载读取器数量已达上限。");
                    _readerRun = run;
                    _readerTotal = totalBytes;
                    var lease = new DownloadReadLease(this, run, totalBytes);
                    _readers.Add(lease);
                    return lease;
                }
            }
            catch (Exception error) when (IsFileFailure(error)) { throw Unknown(); }
            finally { _serial.Release(); }
        }

        public async Task RevokeReadersAsync(CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            await AwaitTerminationAsync(StartRevocation(), cancellationToken).ConfigureAwait(false);
        }

        public async Task WriteAsync(ReadOnlyMemory<byte> bytes, CancellationToken cancellationToken)
        {
            await _serial.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                RequireActive();
                if (_published) throw Invalid();
                RequireCompleteWrites();
                Verify(compareLength: true);
                long next = checked(_length + bytes.Length);
                lock (_readerGate)
                    if (_readerRun is not null && next > _readerTotal) throw ReaderInvalid();
                cancellationToken.ThrowIfCancellationRequested();
                try { await RandomAccess.WriteAsync(handle, bytes, _length, cancellationToken).ConfigureAwait(false); }
                catch { _writeFaulted = true; FaultReaders(); throw; }
                Interlocked.Exchange(ref _length, next);
                try { Verify(compareLength: true); }
                catch { _writeFaulted = true; FaultReaders(); throw; }
                // Reading is permitted only after the whole write and its ownership/length check succeed.
                // It is intentionally independent of fsync/checkpoint acknowledgement.
                AdvanceReadablePrefix(next);
            }
            catch (FilewayException error)
            {
                if (error.Error.Code == ErrorCode.OutcomeUnknown && !_disposed) FaultReaders();
                throw;
            }
            catch (Exception error) when (IsFileFailure(error))
            { FaultReaders(); throw ManagedFailure(error, AccessContext.Operation); }
            finally { _serial.Release(); }
        }

        public async Task<long> FlushToDiskAsync(CancellationToken cancellationToken)
        {
            await _serial.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                RequireActive();
                RequireCompleteWrites();
                Verify(compareLength: true);
                cancellationToken.ThrowIfCancellationRequested();
                await Task.Run(() => RandomAccess.FlushToDisk(handle), CancellationToken.None).ConfigureAwait(false);
                Verify(compareLength: true);
                cancellationToken.ThrowIfCancellationRequested();
                return _length;
            }
            catch (FilewayException error)
            {
                if (error.Error.Code == ErrorCode.OutcomeUnknown && !_disposed) FaultReaders();
                throw;
            }
            catch (Exception error) when (IsFileFailure(error))
            { FaultReaders(); throw ManagedFailure(error, AccessContext.Operation); }
            finally { _serial.Release(); }
        }

        public async Task<DownloadLocalFileHash> CalculateSha256Async(CancellationToken cancellationToken)
        {
            await _serial.WaitAsync(cancellationToken).ConfigureAwait(false);
            byte[]? buffer = null;
            try
            {
                RequireActive();
                RequireCompleteWrites();
                Verify(compareLength: true);
                using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
                buffer = ArrayPool<byte>.Shared.Rent(128 * 1024);
                long offset = 0;
                while (offset < _length)
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    int count = checked((int)Math.Min(buffer.Length, _length - offset));
                    int received = await RandomAccess.ReadAsync(handle, buffer.AsMemory(0, count), offset, cancellationToken).ConfigureAwait(false);
                    if (received <= 0) throw Unknown();
                    hash.AppendData(buffer.AsSpan(0, received));
                    offset = checked(offset + received);
                }
                Verify(compareLength: true);
                cancellationToken.ThrowIfCancellationRequested();
                return new DownloadLocalFileHash(offset, Convert.ToHexStringLower(hash.GetHashAndReset()));
            }
            catch (FilewayException error)
            {
                if (error.Error.Code == ErrorCode.OutcomeUnknown && !_disposed) FaultReaders();
                throw;
            }
            catch (Exception error) when (IsFileFailure(error))
            { FaultReaders(); throw ManagedFailure(error, AccessContext.Operation); }
            finally
            {
                if (buffer is not null) ArrayPool<byte>.Shared.Return(buffer, clearArray: true);
                _serial.Release();
            }
        }

        public async Task<string> PublishNoReplaceAsync(CancellationToken cancellationToken)
        {
            await _serial.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                RequireActive();
                RequireCompleteWrites();
                Verify(compareLength: true);
                if (_published) return identity;
                lock (_readerGate)
                    if (_readerRun is not null && _length != _readerTotal) throw ReaderInvalid();
                cancellationToken.ThrowIfCancellationRequested();
                _publishUncertain = true;
                int status = RenameByHandle(handle, Path.GetFileName(final), out var completion);
                if (status == 0)
                {
                    // This flag precedes all fallible observations and is never reversed, including on cancellation.
                    Volatile.Write(ref _published, true);
                    Volatile.Write(ref _files, _files with { PublishedFileIdentity = identity });
                    _publishUncertain = false;
                    try
                    {
                        if (!CompletionSucceeded(completion)) throw Unknown();
                        Verify(compareLength: true);
                    }
                    catch { _publishUncertain = true; FaultReaders(); throw; }
                    lock (_readerGate) _publicationVerified = true;
                    SignalReaders();
                    return identity;
                }
                if (status >= 0) throw Unknown();
                // A failed rename is cleanup-capable only if the held object is still the exact original partial.
                Verify(compareLength: true, allowUncertainPublish: true);
                _publishUncertain = false;
                throw NativeFailure(unchecked((int)UploadFileNative.RtlNtStatusToDosError(status)), AccessContext.Publish);
            }
            catch (FilewayException error)
            {
                if (error.Error.Code == ErrorCode.OutcomeUnknown && !_disposed) FaultReaders();
                throw;
            }
            catch (Exception error) when (IsFileFailure(error))
            { FaultReaders(); throw ManagedFailure(error, AccessContext.Operation); }
            finally { _serial.Release(); }
        }

        public Task DeleteOwnedPartialAsync(CancellationToken cancellationToken) => DeleteOwnedFileAsync(false, cancellationToken);
        internal Task DeleteOwnedPublishedAsync(CancellationToken cancellationToken) => DeleteOwnedFileAsync(true, cancellationToken);
        private async Task DeleteOwnedFileAsync(bool published, CancellationToken cancellationToken)
        {
            // Revoke outside the I/O semaphore so prefix waits and queued native reads can leave.
            // A failed/expired drain never grants deletion or close authority.
            await RevokeReadersAsync(cancellationToken).ConfigureAwait(false);
            await _serial.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                if (_deleted) return;
                RequireActive();
                if (_published != published || _publishUncertain || _deleteUncertain) throw Unknown();
                Verify(compareLength: false);
                cancellationToken.ThrowIfCancellationRequested();
                _deleteUncertain = true;
                if (DeleteByHandle(handle) != 0) throw Unknown();
                _deleted = true;
                // Native disposition permits close only. Never inspect/flush/read/write this handle again.
                CloseHandle();
            }
            catch (Exception error) when (IsFileFailure(error)) { throw ManagedFailure(error, AccessContext.Operation); }
            finally { _serial.Release(); }
        }

        public ValueTask DisposeAsync()
        {
            Task task;
            TaskCompletionSource? completion = null;
            lock (_readerGate)
            {
                if (_disposal is null)
                {
                    completion = NewSignal();
                    _disposal = completion.Task;
                    _admissionClosed = true;
                }
                task = _disposal;
            }
            if (completion is not null) _ = DisposeWriterAsync(completion);
            return new ValueTask(AwaitTerminationAsync(task, CancellationToken.None));
        }

        private async Task DisposeWriterAsync(TaskCompletionSource completion)
        {
            try
            {
                bool retainPublished;
                lock (_readerGate)
                {
                    retainPublished = _publicationVerified && !_publishUncertain && !_handleClosed && _readers.Count > 0 && !_readersDrained;
                    if (retainPublished) _disposed = true;
                }
                if (retainPublished)
                {
                    // Retained readers already own the lifetime. In particular, do not queue behind
                    // a player's in-flight native I/O merely to release the completed writer.
                    SignalReaders();
                    completion.TrySetResult();
                    return;
                }
                await _serial.WaitAsync().ConfigureAwait(false);
                try
                {
                    lock (_readerGate)
                    {
                        _disposed = true;
                        retainPublished = _publicationVerified && !_publishUncertain;
                    }
                    CloseIfReleased();
                }
                finally { _serial.Release(); }
                SignalReaders();
                // A published owner returns immediately, leaving the same object and directory pins with
                // its readers. Unpublished/uncertain sources must actually drain before releasing them.
                if (!retainPublished) await StartRevocation().ConfigureAwait(false);
                completion.TrySetResult();
            }
            catch (Exception) { completion.TrySetException(Unknown()); }
        }

        private Task StartRevocation()
        {
            TaskCompletionSource completion, changed;
            DownloadReadLease[] readers;
            lock (_readerGate)
            {
                if (_revocation is not null) return _revocation;
                completion = NewSignal();
                _revocation = completion.Task;
                _readersRevoked = true;
                readers = [.. _readers];
                changed = ReplaceSignal();
            }
            // Cancellation callbacks and continuations must never run under either owner lock.
            changed.TrySetResult();
            foreach (var reader in readers) reader.RequestStop();
            _ = DrainReadersAsync(readers, completion);
            return completion.Task;
        }

        private async Task DrainReadersAsync(DownloadReadLease[] readers, TaskCompletionSource completion)
        {
            try
            {
                await Task.WhenAll(readers.Select(reader => reader.Drained)).ConfigureAwait(false);
                await _serial.WaitAsync().ConfigureAwait(false);
                try
                {
                    lock (_readerGate) _readersDrained = true;
                    CloseIfReleased();
                }
                finally { _serial.Release(); }
                if (readers.Any(reader => reader.StopFailed)) completion.TrySetException(Unknown());
                else completion.TrySetResult();
            }
            catch (Exception) { completion.TrySetException(Unknown()); }
        }

        private static async Task AwaitTerminationAsync(Task task, CancellationToken cancellationToken)
        {
            try { await task.WaitAsync(ReaderDrainTimeout, cancellationToken).ConfigureAwait(false); }
            catch (TimeoutException) { throw Unknown(); }
            // The actual drain task remains live on timeout/caller cancellation. No cleanup acknowledgement
            // is fabricated: a subsequent attempt can succeed only after native I/O really completes.
        }

        private async Task ReleaseReaderAsync(DownloadReadLease reader)
        {
            await _serial.WaitAsync().ConfigureAwait(false);
            try
            {
                lock (_readerGate) _readers.Remove(reader);
                CloseIfReleased();
            }
            finally { _serial.Release(); }
        }

        private void CloseIfReleased()
        {
            bool close;
            lock (_readerGate) close = _disposed && (_readers.Count == 0 || _readersDrained);
            if (close) CloseHandle();
        }

        private void CloseHandle()
        {
            if (_handleClosed) return;
            lock (_readerGate) _handleClosed = true;
            // Lifetime release never invents deletion authority, including an uncertain rename.
            handle.Dispose();
            directories.Dispose();
        }

        private async ValueTask<int> ReadAsync(DownloadReadLease reader, long offset, Memory<byte> buffer,
            CancellationToken cancellationToken)
        {
            if (offset < 0 || offset > reader.TotalBytes || buffer.Length > MaximumReadBytes) throw ReaderInvalid();
            long lastPrefix = -1, lastProgress = Environment.TickCount64;
            while (true)
            {
                cancellationToken.ThrowIfCancellationRequested();
                Task changed;
                long prefix;
                lock (_readerGate)
                {
                    RequireReadable(reader);
                    prefix = _readablePrefix;
                    changed = _prefixChanged.Task;
                }
                if (offset == reader.TotalBytes || buffer.IsEmpty) return 0;
                if (prefix > offset) break;
                if (prefix > lastPrefix)
                {
                    lastPrefix = prefix;
                    lastProgress = Environment.TickCount64;
                }
                TimeSpan remaining = ReaderPrefixTimeout - TimeSpan.FromMilliseconds(Environment.TickCount64 - lastProgress);
                if (remaining <= TimeSpan.Zero) throw ReaderTimeout();
                try { await changed.WaitAsync(remaining, cancellationToken).ConfigureAwait(false); }
                catch (TimeoutException)
                {
                    lock (_readerGate)
                    {
                        RequireReadable(reader);
                        if (_readablePrefix <= lastPrefix) throw ReaderTimeout();
                    }
                }
                // A signal alone does not renew the budget. Any verified owner-prefix growth does,
                // even when a tail/index seek still cannot be served.
            }

            await _serial.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                lock (_readerGate) RequireReadable(reader);
                RequireCompleteWrites();
                Verify(compareLength: true);
                int count = checked((int)Math.Min(Math.Min(buffer.Length, MaximumReadBytes),
                    Math.Min(reader.TotalBytes - offset, Interlocked.Read(ref _readablePrefix) - offset)));
                if (count <= 0) throw Unknown();
                using var budget = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                budget.CancelAfter(ReaderIoTimeout);
                int received;
                try { received = await RandomAccess.ReadAsync(handle, buffer[..count], offset, budget.Token).ConfigureAwait(false); }
                catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested) { throw ReaderTimeout(); }
                // This synchronous NT handle may not interrupt an in-flight native read. Always await
                // actual completion before releasing the caller's buffer or claiming that a drain ended.
                if (received <= 0 || received > count) throw Unknown();
                Verify(compareLength: true);
                lock (_readerGate) RequireReadable(reader);
                cancellationToken.ThrowIfCancellationRequested();
                if (budget.IsCancellationRequested) throw ReaderTimeout();
                return received;
            }
            catch (FilewayException error)
            {
                if (error.Error.Code == ErrorCode.OutcomeUnknown) FaultReaders();
                throw;
            }
            catch (Exception error) when (IsFileFailure(error)) { FaultReaders(); throw Unknown(); }
            finally { _serial.Release(); }
        }

        // Called under _readerGate; writer disposal is allowed only for the already-published object.
        private void RequireReadable(DownloadReadLease reader)
        {
            if (_readersRevoked || _readerFaulted || _handleClosed || _deleted || _deleteUncertain ||
                _writeFaulted || _disposed && !_published || _disposal is not null && !_publicationVerified ||
                _readerRun != reader.Run || _readerTotal != reader.TotalBytes) throw Unknown();
        }

        private void AdvanceReadablePrefix(long next)
        {
            TaskCompletionSource? changed = null;
            lock (_readerGate)
            {
                if (next > _readablePrefix)
                {
                    Interlocked.Exchange(ref _readablePrefix, next);
                    changed = ReplaceSignal();
                }
            }
            changed?.TrySetResult();
        }

        private void FaultReaders()
        {
            TaskCompletionSource changed;
            lock (_readerGate)
            {
                _readerFaulted = true;
                changed = ReplaceSignal();
            }
            changed.TrySetResult();
        }

        private void SignalReaders()
        {
            TaskCompletionSource changed;
            lock (_readerGate) changed = ReplaceSignal();
            changed.TrySetResult();
        }

        private TaskCompletionSource ReplaceSignal()
        {
            var previous = _prefixChanged;
            _prefixChanged = NewSignal();
            return previous;
        }

        private static TaskCompletionSource NewSignal() => new(TaskCreationOptions.RunContinuationsAsynchronously);
        private static FilewayException ReaderInvalid() => Safe(ErrorCode.InvalidRequest, "本地下载读取请求无效。");
        private static FilewayException ReaderTimeout() => Safe(ErrorCode.Timeout, "本地下载读取等待超时，请稍后重试。");

        private void RequireActive()
        {
            if (_disposed || _deleted || _deleteUncertain || _publishUncertain || _handleClosed) throw Unknown();
        }
        private void RequireCompleteWrites()
        {
            if (_writeFaulted) throw Unknown();
        }
        private void Verify(bool compareLength, bool allowUncertainPublish = false)
        {
            if (!allowUncertainPublish && _publishUncertain || _deleteUncertain) throw Unknown();
            directories.Revalidate(AccessContext.Owned);
            var current = Observe(handle, directory: false, AccessContext.Owned);
            RequireCanonicalPath(handle, _published ? final : partial, AccessContext.Owned);
            if (!StringComparer.Ordinal.Equals(current.Identity, identity) || compareLength && current.Length != _length)
                throw Unknown();
        }
        public override string ToString() => "DownloadDestination (private file ownership redacted)";

        private sealed class DownloadReadLease(DownloadDestination owner, TransferRunToken run, long totalBytes)
            : IDownloadReadLease
        {
            private readonly object _gate = new();
            private readonly SemaphoreSlim _queue = new(1, 1);
            private readonly CancellationTokenSource _stop = new();
            private readonly TaskCompletionSource _drained = NewSignal();
            private Task? _disposal, _stopFlight;
            private int _operations;
            private bool _stopped, _stopIssued, _stopFailed;
            public TransferRunToken Run => run;
            public long TotalBytes => totalBytes;
            public long ReadablePrefixBytes
            {
                get
                {
                    lock (_gate)
                    {
                        if (_stopped) throw Unknown();
                        lock (owner._readerGate)
                        {
                            // The retained count is historical after revocation or this lease's
                            // disposal. Only a currently readable owner can grant this evidence.
                            owner.RequireReadable(this);
                            return owner._readablePrefix;
                        }
                    }
                }
            }
            internal Task Drained => _drained.Task;
            internal bool StopFailed { get { lock (_gate) return _stopFailed; } }

            public async ValueTask<int> ReadAsync(long offset, Memory<byte> buffer, CancellationToken cancellationToken)
            {
                cancellationToken.ThrowIfCancellationRequested();
                if (offset < 0 || offset > totalBytes || buffer.Length > MaximumReadBytes) throw ReaderInvalid();
                lock (_gate)
                {
                    if (_stopped) throw Unknown();
                    _operations++;
                }
                bool entered = false;
                try
                {
                    using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, _stop.Token);
                    // One lease can serve several bridge streams. A future seek at the head can delay
                    // later calls on that lease; cancellation while queued still exits immediately.
                    await _queue.WaitAsync(linked.Token).ConfigureAwait(false);
                    entered = true;
                    return await owner.ReadAsync(this, offset, buffer, linked.Token).ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
                { throw new OperationCanceledException(cancellationToken); }
                catch (OperationCanceledException) { throw Unknown(); }
                finally
                {
                    if (entered) _queue.Release();
                    bool drained;
                    lock (_gate) drained = --_operations == 0 && _stopped && _stopIssued;
                    if (drained) _drained.TrySetResult();
                }
            }

            internal void RequestStop()
            {
                TaskCompletionSource completion;
                lock (_gate)
                {
                    if (_stopFlight is not null) return;
                    _stopped = true;
                    completion = NewSignal();
                    _stopFlight = completion.Task;
                }
                // CancelAsync returns control without running arbitrary registered callbacks under a
                // player/owner lock. A callback failure cannot skip stopping the other owner readers.
                _ = StopReaderAsync(completion);
            }

            private async Task StopReaderAsync(TaskCompletionSource completion)
            {
                try { await _stop.CancelAsync().ConfigureAwait(false); }
                catch (Exception) { lock (_gate) _stopFailed = true; }
                finally
                {
                    bool drained;
                    lock (_gate)
                    {
                        _stopIssued = true;
                        drained = _operations == 0;
                    }
                    completion.TrySetResult();
                    if (drained) _drained.TrySetResult();
                }
            }

            public ValueTask DisposeAsync()
            {
                Task task;
                TaskCompletionSource? completion = null;
                lock (_gate)
                {
                    if (_disposal is null)
                    {
                        completion = NewSignal();
                        _disposal = completion.Task;
                    }
                    task = _disposal;
                }
                if (completion is not null)
                {
                    RequestStop();
                    _ = DisposeReaderAsync(completion);
                }
                return new ValueTask(AwaitTerminationAsync(task, CancellationToken.None));
            }

            private async Task DisposeReaderAsync(TaskCompletionSource completion)
            {
                try
                {
                    await _drained.Task.ConfigureAwait(false);
                    await owner.ReleaseReaderAsync(this).ConfigureAwait(false);
                    _stop.Dispose();
                    if (StopFailed) completion.TrySetException(Unknown());
                    else completion.TrySetResult();
                }
                catch (Exception) { completion.TrySetException(Unknown()); }
            }

            public override string ToString() => "DownloadReadLease (private file ownership redacted)";
        }
    }

}

internal static partial class DownloadFileNative
{
    internal const uint NtOpen = 1, NtCreate = 2, DeleteAccess = 0x00010000, NtOpenNoRecall = 0x00400000;
    internal const uint OwnedAccess = UploadFileNative.FileGenericRead | UploadFileNative.FileGenericWrite | DeleteAccess;
    internal const uint MetadataAccess = 0x00100080, ShareAll = 7; // SYNCHRONIZE | FILE_READ_ATTRIBUTES; READ/WRITE/DELETE sharing.
    internal const uint FixedDrive = 3;
    internal const int FileRenameInformation = 10, FileDispositionInformation = 13;

    [StructLayout(LayoutKind.Sequential)]
    internal struct DispositionInformation { internal byte DeleteFile; }
    [StructLayout(LayoutKind.Sequential)]
    internal struct RenameInformation
    {
        internal uint ReplaceIfExists;
        internal nint RootDirectory;
        internal uint FileNameLength;
        internal ushort FileName;
    }

    [LibraryImport("kernel32.dll", StringMarshalling = StringMarshalling.Utf16)]
    internal static partial uint GetDriveTypeW(string rootPath);
    [LibraryImport("ntdll.dll")]
    internal static partial int NtSetInformationFile(SafeFileHandle handle, out UploadFileNative.IoStatusBlock completion,
        nint information, uint size, int informationClass);
    [LibraryImport("ntdll.dll", EntryPoint = "NtSetInformationFile")]
    internal static partial int SetDispositionInformation(SafeFileHandle handle, out UploadFileNative.IoStatusBlock completion,
        in DispositionInformation information, uint size, int informationClass);
}
