using System.Collections.Concurrent;
using System.Runtime.InteropServices;
using Fileway.Core.Integration;

namespace Fileway.Playback.Native;

/// <summary>
/// One owned mpv instance's read-only view of one already-owned download lease.
/// Register once, cancel before mpv_terminate_destroy, then release only after
/// mpv_terminate_destroy returns. There is deliberately no ordinary Dispose:
/// libmpv keeps protocol callbacks until its entire instance is destroyed.
/// </summary>
internal sealed class MpvDownloadStreamBridge
{
    internal const string Protocol = "fileway-download";
    internal const int MaximumOpenStreams = 8;
    internal const int MaximumReadBytes = 64 * 1024;
    internal const int LoadingFailed = -13;
    internal const int GenericError = -20;

    private readonly object _gate = new();
    private readonly IDownloadReadLease _lease;
    private readonly long _totalBytes;
    private readonly ConcurrentDictionary<nint, StreamState> _streams = new();
    private readonly OpenCallback _open;
    private readonly ReadCallback _read;
    private readonly SeekCallback _seek;
    private readonly SizeCallback _size;
    private readonly CloseCallback _close;
    private readonly CancelCallback _cancel;
    private readonly StreamInfo _callbacks;
    private GCHandle _root;
    private nint _registrationCookie;
    private long _nextCookie;
    private int _stopping;
    private bool _registrationAttempted;
    private Task? _release;

    /// <summary>Successful construction takes ownership of this reader, never its writer.</summary>
    internal MpvDownloadStreamBridge(IDownloadReadLease lease)
    {
        ArgumentNullException.ThrowIfNull(lease);
        _totalBytes = lease.TotalBytes;
        ArgumentOutOfRangeException.ThrowIfNegative(_totalBytes);
        _lease = lease;
        Uri = $"{Protocol}://{Guid.NewGuid():N}";
        _open = Open;
        _read = Read;
        _seek = Seek;
        _size = Size;
        _close = Close;
        _cancel = Cancel;
        _callbacks = new StreamInfo
        {
            Read = Marshal.GetFunctionPointerForDelegate(_read),
            Seek = Marshal.GetFunctionPointerForDelegate(_seek),
            Size = Marshal.GetFunctionPointerForDelegate(_size),
            Close = Marshal.GetFunctionPointerForDelegate(_close),
            Cancel = Marshal.GetFunctionPointerForDelegate(_cancel),
        };
    }

    internal string Uri { get; }

    internal void Register(MpvNative native, nint handle) => Register(handle, native.AddReadOnlyStreamProtocol);

    // The injected registration operation permits real callback ABI tests without
    // loading a Windows DLL. It is not a replacement for native playback acceptance.
    internal void Register(nint handle, Action<nint, string, nint, OpenCallback> register)
    {
        ArgumentNullException.ThrowIfNull(register);
        if (handle == 0) throw new ArgumentException("An owned mpv instance is required.", nameof(handle));
        lock (_gate)
        {
            if (_registrationAttempted || Volatile.Read(ref _stopping) != 0)
                throw new InvalidOperationException("The download protocol can only be registered once.");
            _registrationAttempted = true;
            _root = GCHandle.Alloc(this);
            _registrationCookie = GCHandle.ToIntPtr(_root);
            // Keep the root even if registration throws. The instance owner must
            // destroy mpv and call ReleaseAfterDestroyAsync on this path as well.
            register(handle, Protocol, _registrationCookie, _open);
        }
    }

    /// <summary>Stops admission and wakes readers; does not call libmpv or cancel the download.</summary>
    internal void CancelBeforeDestroy()
    {
        Volatile.Write(ref _stopping, 1);
        foreach (var stream in _streams.Values) stream.Cancel();
    }

    /// <summary>
    /// The caller guarantees mpv_terminate_destroy has returned (or no mpv instance
    /// ever existed). Calling this before native destruction is a lifetime error.
    /// </summary>
    internal Task ReleaseAfterDestroyAsync()
    {
        lock (_gate) return _release ??= ReleaseCoreAsync();
    }

    private async Task ReleaseCoreAsync()
    {
        CancelBeforeDestroy();
        var streams = _streams.Values.ToArray();
        await Task.WhenAll(streams.Select(stream => stream.DisposeAsync().AsTask())).ConfigureAwait(false);
        try { await _lease.DisposeAsync().ConfigureAwait(false); }
        finally
        {
            if (_root.IsAllocated) _root.Free();
            // Keep every delegate alive through the native lifetime and the final
            // managed drain, including exceptional lease disposal.
            GC.KeepAlive(_open);
            GC.KeepAlive(_read);
            GC.KeepAlive(_seek);
            GC.KeepAlive(_size);
            GC.KeepAlive(_close);
            GC.KeepAlive(_cancel);
        }
    }

    private int Open(nint userData, nint uri, nint info)
    {
        StreamState? stream = null;
        try
        {
            if (Volatile.Read(ref _stopping) != 0 || userData != _registrationCookie || info == 0 || !MatchesUri(uri))
                return LoadingFailed;
            lock (_gate)
            {
                if (Volatile.Read(ref _stopping) != 0 || _streams.Count >= MaximumOpenStreams || _nextCookie == nint.MaxValue)
                    return LoadingFailed;
                var cookie = checked((nint)(++_nextCookie));
                stream = new StreamState(this, cookie);
                if (!_streams.TryAdd(cookie, stream)) throw new InvalidOperationException("Duplicate stream cookie.");
                if (Volatile.Read(ref _stopping) != 0)
                {
                    stream.Close();
                    return LoadingFailed;
                }
                var callbacks = _callbacks;
                callbacks.Cookie = cookie;
                Marshal.StructureToPtr(callbacks, info, false);
                return 0;
            }
        }
        catch
        {
            try { stream?.Close(); }
            catch { /* Also contain cleanup failures on the native open boundary. */ }
            return LoadingFailed;
        }
    }

    private bool MatchesUri(nint uri)
    {
        if (uri == 0) return false;
        // This generated URI is ASCII. Never decode an unbounded native string,
        // normalize aliases, interpret a filename, or accept URI suffixes.
        for (int i = 0; i < Uri.Length; i++)
            if (Marshal.ReadByte(uri, i) != Uri[i]) return false;
        return Marshal.ReadByte(uri, Uri.Length) == 0;
    }

    private long Read(nint cookie, nint buffer, ulong length)
    {
        try { return _streams.TryGetValue(cookie, out var stream) ? stream.Read(buffer, length) : -1; }
        catch { return -1; }
    }

    private long Seek(nint cookie, long offset)
    {
        try { return _streams.TryGetValue(cookie, out var stream) ? stream.Seek(offset) : GenericError; }
        catch { return GenericError; }
    }

    private long Size(nint cookie)
    {
        try { return _streams.TryGetValue(cookie, out var stream) ? stream.Size() : GenericError; }
        catch { return GenericError; }
    }

    private void Close(nint cookie)
    {
        try { if (_streams.TryGetValue(cookie, out var stream)) stream.Close(); }
        catch { /* No managed exception may cross an unmanaged callback boundary. */ }
    }

    private void Cancel(nint cookie)
    {
        try { if (_streams.TryGetValue(cookie, out var stream)) stream.Cancel(); }
        catch { /* In particular, cancellation registrations may throw. */ }
    }

    private sealed class StreamState(MpvDownloadStreamBridge owner, nint cookie) : IAsyncDisposable
    {
        private readonly object _ioGate = new();
        private readonly CancellationTokenSource _cancellation = new();
        private readonly TaskCompletionSource _cancelFinished = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private readonly TaskCompletionSource _closedFinished = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private byte[]? _buffer;
        private long _position;
        private int _operations, _canceled, _closed, _finalizing;

        public ValueTask DisposeAsync()
        {
            Close();
            return new ValueTask(_closedFinished.Task);
        }

        internal long Read(nint destination, ulong length)
        {
            if (!Enter()) return -1;
            try
            {
                lock (_ioGate)
                {
                    var cancellationToken = _cancellation.Token;
                    cancellationToken.ThrowIfCancellationRequested();
                    if (length == 0 || _position == owner._totalBytes) return 0;
                    if (destination == 0) return -1;
                    int count = (int)Math.Min(Math.Min(length, MaximumReadBytes), (ulong)(owner._totalBytes - _position));
                    _buffer ??= new byte[MaximumReadBytes];
                    int read = owner._lease.ReadAsync(_position, _buffer.AsMemory(0, count), cancellationToken)
                        .AsTask().GetAwaiter().GetResult();
                    cancellationToken.ThrowIfCancellationRequested();
                    // The lease must wait for future bytes. A broken lease must
                    // fail rather than advertise temporary starvation as EOF.
                    if (read <= 0 || read > count) return -1;
                    Marshal.Copy(_buffer, 0, destination, read);
                    _position += read; // read <= total - position; cannot overflow.
                    return read;
                }
            }
            finally { Exit(); }
        }

        internal long Seek(long offset)
        {
            if (!Enter()) return GenericError;
            try
            {
                lock (_ioGate)
                {
                    _cancellation.Token.ThrowIfCancellationRequested();
                    if (offset < 0 || offset > owner._totalBytes) return GenericError;
                    // Seeking ahead does not read unowned bytes. Its next read
                    // waits on the same owner's verified prefix and cancellation.
                    _position = offset;
                    return offset;
                }
            }
            finally { Exit(); }
        }

        internal long Size()
        {
            if (!Enter()) return GenericError;
            try { return Volatile.Read(ref _canceled) == 0 ? owner._totalBytes : GenericError; }
            finally { Exit(); }
        }

        internal void Cancel()
        {
            if (Interlocked.Exchange(ref _canceled, 1) != 0) return;
            try
            {
                // Cancel() would run arbitrary token registrations synchronously
                // on mpv's separate cancel thread. CancelAsync sets the token now
                // but dispatches those registrations elsewhere, without I/O locks.
                _ = FinishCancellationAsync(_cancellation.CancelAsync());
            }
            catch { _cancelFinished.TrySetResult(); }
        }

        private async Task FinishCancellationAsync(Task cancellation)
        {
            try { await cancellation.ConfigureAwait(false); }
            catch { /* Token callback failures must not fault native callbacks or cleanup. */ }
            finally { _cancelFinished.TrySetResult(); }
        }

        internal void Close()
        {
            Interlocked.Exchange(ref _closed, 1);
            Cancel();
            TryFinishClose();
        }

        private bool Enter()
        {
            if (Volatile.Read(ref _closed) != 0 || Volatile.Read(ref _canceled) != 0) return false;
            Interlocked.Increment(ref _operations);
            if (Volatile.Read(ref _closed) == 0 && Volatile.Read(ref _canceled) == 0) return true;
            Exit();
            return false;
        }

        private void Exit()
        {
            if (Interlocked.Decrement(ref _operations) == 0) TryFinishClose();
        }

        private void TryFinishClose()
        {
            if (Volatile.Read(ref _closed) != 0 && Volatile.Read(ref _operations) == 0 &&
                Interlocked.CompareExchange(ref _finalizing, 1, 0) == 0)
                _ = FinishCloseAsync();
        }

        private async Task FinishCloseAsync()
        {
            await _cancelFinished.Task.ConfigureAwait(false);
            _cancellation.Dispose();
            _buffer = null;
            // Closing states retain their admission slot until reads and token
            // callbacks drain, keeping retained buffers and pending closes bounded.
            owner._streams.TryRemove(cookie, out _);
            _closedFinished.TrySetResult();
        }
    }

    // mpv v0.41.0 include/mpv/stream_cb.h: six pointer-sized fields, in this order.
    [StructLayout(LayoutKind.Sequential)]
    internal struct StreamInfo
    {
        internal nint Cookie, Read, Seek, Size, Close, Cancel;
    }

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    internal delegate int OpenCallback(nint userData, nint uri, nint info);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    internal delegate long ReadCallback(nint cookie, nint buffer, ulong length);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    internal delegate long SeekCallback(nint cookie, long offset);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    internal delegate long SizeCallback(nint cookie);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    internal delegate void CloseCallback(nint cookie);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    internal delegate void CancelCallback(nint cookie);
}
