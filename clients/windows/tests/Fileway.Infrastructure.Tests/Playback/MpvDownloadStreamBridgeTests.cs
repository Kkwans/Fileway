using System.Runtime.InteropServices;
using System.Runtime.CompilerServices;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Playback.Native;

namespace Fileway.Infrastructure.Tests.Playback;

public sealed class MpvDownloadStreamBridgeTests
{
    private static readonly TimeSpan Deadline = TimeSpan.FromSeconds(10);

    [Fact]
    public async Task NativeLayoutAndCallbacksPreserveAllSixPointersAnd64BitValues()
    {
        Assert.Equal(6 * nint.Size, Marshal.SizeOf<MpvDownloadStreamBridge.StreamInfo>());
        string[] names = ["Cookie", "Read", "Seek", "Size", "Close", "Cancel"];
        for (int i = 0; i < names.Length; i++)
            Assert.Equal((nint)(i * nint.Size), Marshal.OffsetOf<MpvDownloadStreamBridge.StreamInfo>(names[i]));

        var lease = new TestLease(long.MaxValue, long.MaxValue);
        await using var fixture = new NativeFixture(lease);
        var stream = fixture.Open();
        Assert.Equal(long.MaxValue, Native.Size(stream));
        long offset = (1L << 34) + 17;
        Assert.Equal(offset, Native.Seek(stream, offset));
        using var buffer = new NativeBuffer();
        Assert.Equal(MpvDownloadStreamBridge.MaximumReadBytes, Native.Read(stream, buffer.Address, 1UL << 32));
        Assert.Equal(offset, lease.LastOffset);
        Assert.Equal(MpvDownloadStreamBridge.MaximumReadBytes, lease.MaximumRequested);
        Assert.Equal(TestLease.ByteAt(offset), buffer.ReadByte());
        Assert.Equal(MpvDownloadStreamBridge.MaximumReadBytes, Native.Read(stream, buffer.Address, ulong.MaxValue));
        Assert.Equal(offset + MpvDownloadStreamBridge.MaximumReadBytes, lease.LastOffset);
        Assert.Equal(long.MaxValue - 2, Native.Seek(stream, long.MaxValue - 2));
        Assert.Equal(2, Native.Read(stream, buffer.Address, ulong.MaxValue));
        Assert.Equal(0, Native.Read(stream, buffer.Address, ulong.MaxValue));
        Assert.Equal(long.MaxValue, Native.Seek(stream, long.MaxValue));
        Assert.Equal(MpvDownloadStreamBridge.GenericError, Native.Seek(stream, -1));
    }

    [Fact]
    public async Task OnlyExactGeneratedUriAndRegistrationCookieCanOpen()
    {
        await using var fixture = new NativeFixture(new TestLease(10, 10));
        await using var other = new NativeFixture(new TestLease(10, 10));
        string uri = fixture.Bridge.Uri;
        Assert.StartsWith(MpvDownloadStreamBridge.Protocol + "://", uri);
        string[] rejected = [uri + "/", uri + "?x=1", uri + "#fragment", uri.ToUpperInvariant(),
            uri.Replace("://", ":/", StringComparison.Ordinal), "file:///private/partial", "", uri[..^1]];
        foreach (string candidate in rejected)
            Assert.Equal(MpvDownloadStreamBridge.LoadingFailed, fixture.TryOpen(candidate).Result);
        Assert.Equal(MpvDownloadStreamBridge.LoadingFailed, fixture.TryOpen(other.Bridge.Uri).Result);
        Assert.Equal(MpvDownloadStreamBridge.LoadingFailed, fixture.TryOpen(uri, fixture.UserData + 1).Result);
        Assert.Equal(MpvDownloadStreamBridge.LoadingFailed, Native.Open(fixture.OpenPointer, fixture.UserData, 0, 0));
        Assert.NotEqual(0, fixture.Open().Cookie);
    }

    [Fact]
    public async Task PrefixExhaustionWaitsAndResumesWithoutTemporaryEof()
    {
        var lease = new TestLease(100, 3);
        await using var fixture = new NativeFixture(lease);
        var stream = fixture.Open();
        using var buffer = new NativeBuffer();
        Assert.Equal(3, Native.Read(stream, buffer.Address, 10));
        var waiting = Task.Run(() => Native.Read(stream, buffer.Address, 10));
        await lease.Waiting.Task.WaitAsync(Deadline, TestContext.Current.CancellationToken);
        Assert.False(waiting.IsCompleted);
        lease.Advance(13);
        Assert.Equal(10, await waiting.WaitAsync(Deadline, TestContext.Current.CancellationToken));
        Assert.Equal(TestLease.ByteAt(3), buffer.ReadByte());
    }

    [Fact]
    public async Task SeekAheadWaitsAtTheRequestedOffsetAndRejectsOutOfRangeOffsets()
    {
        var lease = new TestLease(100, 4);
        await using var fixture = new NativeFixture(lease);
        var stream = fixture.Open();
        Assert.Equal(80, Native.Seek(stream, 80));
        Assert.Equal(MpvDownloadStreamBridge.GenericError, Native.Seek(stream, 101));
        Assert.Equal(MpvDownloadStreamBridge.GenericError, Native.Seek(stream, long.MaxValue));
        using var buffer = new NativeBuffer();
        var waiting = Task.Run(() => Native.Read(stream, buffer.Address, 7));
        await lease.Waiting.Task.WaitAsync(Deadline, TestContext.Current.CancellationToken);
        lease.Advance(87);
        Assert.Equal(7, await waiting.WaitAsync(Deadline, TestContext.Current.CancellationToken));
        Assert.Equal(80, lease.LastOffset);
        Assert.Equal(TestLease.ByteAt(80), buffer.ReadByte());
    }

    [Fact]
    public async Task StreamsHaveIndependentPositionsAndCancelDoesNotDisposeSharedLease()
    {
        var lease = new TestLease(100, 100);
        await using var fixture = new NativeFixture(lease);
        var first = fixture.Open();
        var second = fixture.Open();
        using var buffer = new NativeBuffer();
        Assert.Equal(5, Native.Read(first, buffer.Address, 5));
        Assert.Equal(3, Native.Read(second, buffer.Address, 3));
        Assert.Equal(0, lease.LastOffset);
        Native.Cancel(first);
        Assert.Equal(-1, Native.Read(first, buffer.Address, 1));
        Assert.Equal(MpvDownloadStreamBridge.GenericError, Native.Seek(first, 0));
        Assert.Equal(MpvDownloadStreamBridge.GenericError, Native.Size(first));
        Assert.Equal(2, Native.Read(second, buffer.Address, 2));
        Assert.Equal(3, lease.LastOffset);
        Native.Close(first);
        Assert.Equal(0, lease.DisposeCount);
        Assert.Equal(100, Native.Size(second));
    }

    [Fact]
    public async Task NativeCancelWakesAPrefixWaitAndNeverReturnsEof()
    {
        var lease = new TestLease(100, 0);
        await using var fixture = new NativeFixture(lease);
        var stream = fixture.Open();
        using var buffer = new NativeBuffer();
        var waiting = Task.Run(() => Native.Read(stream, buffer.Address, 1));
        await lease.Waiting.Task.WaitAsync(Deadline, TestContext.Current.CancellationToken);
        Native.Cancel(stream);
        Assert.Equal(-1, await waiting.WaitAsync(Deadline, TestContext.Current.CancellationToken));
        Assert.Equal(0, lease.DisposeCount);
    }

    [Fact]
    public async Task CancelAndCloseNeverBlockOnArbitraryCancellationRegistrations()
    {
        var entered = Signal();
        var release = Signal();
        var started = Signal();
        var lease = new TestLease(100, 0)
        {
            ReadOverride = async (_, _, token) =>
            {
                using var registration = token.Register(() =>
                {
                    entered.TrySetResult();
                    release.Task.GetAwaiter().GetResult();
                    throw new InvalidOperationException("Deliberate token callback failure.");
                });
                started.TrySetResult();
                await Task.Delay(Timeout.InfiniteTimeSpan, token);
                return 0;
            },
        };
        await using var fixture = new NativeFixture(lease);
        var stream = fixture.Open();
        using var buffer = new NativeBuffer();
        var waiting = Task.Run(() => Native.Read(stream, buffer.Address, 1));
        try
        {
            await started.Task.WaitAsync(Deadline, TestContext.Current.CancellationToken);
            await Task.Run(() => Native.Cancel(stream), TestContext.Current.CancellationToken).WaitAsync(Deadline, TestContext.Current.CancellationToken);
            await entered.Task.WaitAsync(Deadline, TestContext.Current.CancellationToken);
            await Task.Run(() => Native.Close(stream), TestContext.Current.CancellationToken).WaitAsync(Deadline, TestContext.Current.CancellationToken);
            Assert.Equal(0, lease.DisposeCount);
            fixture.Bridge.CancelBeforeDestroy();
            Task disposing = fixture.Bridge.ReleaseAfterDestroyAsync();
            Assert.False(disposing.IsCompleted);
            release.TrySetResult();
            Assert.Equal(-1, await waiting.WaitAsync(Deadline, TestContext.Current.CancellationToken));
            await disposing.WaitAsync(Deadline, TestContext.Current.CancellationToken);
            Assert.Equal(1, lease.DisposeCount);
        }
        finally { release.TrySetResult(); }
    }

    [Fact]
    public async Task CloseRacingReadAndSeekDrainsBeforeLeaseRelease()
    {
        var entered = Signal();
        var release = Signal();
        var lease = new TestLease(100, 0)
        {
            ReadOverride = async (_, _, _) =>
            {
                entered.TrySetResult();
                await release.Task; // Model in-flight owner I/O which has not drained yet.
                return 1;
            },
        };
        await using var fixture = new NativeFixture(lease);
        var stream = fixture.Open();
        using var buffer = new NativeBuffer();
        var read = Task.Run(() => Native.Read(stream, buffer.Address, 1));
        try
        {
            await entered.Task.WaitAsync(Deadline, TestContext.Current.CancellationToken);
            var seek = Task.Run(() => Native.Seek(stream, 10));
            Native.Close(stream);
            Native.Close(stream);
            Native.Cancel(stream);
            Task disposing = fixture.Bridge.ReleaseAfterDestroyAsync();
            Assert.False(disposing.IsCompleted);
            Assert.Equal(0, lease.DisposeCount);
            release.TrySetResult();
            Assert.Equal(-1, await read.WaitAsync(Deadline, TestContext.Current.CancellationToken));
            Assert.Equal(MpvDownloadStreamBridge.GenericError, await seek.WaitAsync(Deadline, TestContext.Current.CancellationToken));
            await disposing.WaitAsync(Deadline, TestContext.Current.CancellationToken);
            Assert.Equal(1, lease.DisposeCount);
        }
        finally { release.TrySetResult(); }
    }

    [Fact]
    public async Task OpenCountAndPendingCloseResourcesStayBounded()
    {
        var entered = Signal();
        var release = Signal();
        var lease = new TestLease(100, 0)
        {
            ReadOverride = async (_, _, _) => { entered.TrySetResult(); await release.Task; return 1; },
        };
        await using var fixture = new NativeFixture(lease);
        var streams = Enumerable.Range(0, MpvDownloadStreamBridge.MaximumOpenStreams).Select(_ => fixture.Open()).ToArray();
        Assert.Equal(MpvDownloadStreamBridge.LoadingFailed, fixture.TryOpen(fixture.Bridge.Uri).Result);
        using var buffer = new NativeBuffer();
        var read = Task.Run(() => Native.Read(streams[0], buffer.Address, 1));
        try
        {
            await entered.Task.WaitAsync(Deadline, TestContext.Current.CancellationToken);
            Native.Close(streams[0]);
            Assert.Equal(MpvDownloadStreamBridge.LoadingFailed, fixture.TryOpen(fixture.Bridge.Uri).Result);
            release.TrySetResult();
            Assert.Equal(-1, await read.WaitAsync(Deadline, TestContext.Current.CancellationToken));
            // Cancellation continuations can still be draining when read returns.
            var reopened = await OpenWhenDrainedAsync(fixture);
            Assert.NotEqual(streams[0].Cookie, reopened.Cookie);
            Assert.Equal(-1, Native.Read(streams[0], buffer.Address, 1));
        }
        finally { release.TrySetResult(); }
    }

    [Theory]
    [InlineData(0)]
    [InlineData(-1)]
    [InlineData(65_537)]
    public async Task InvalidLeaseReadResultBecomesErrorInsteadOfEofOrBufferOverrun(int readResult)
    {
        var lease = new TestLease(100_000, 100_000)
        { ReadOverride = (_, _, _) => ValueTask.FromResult(readResult) };
        await using var fixture = new NativeFixture(lease);
        var stream = fixture.Open();
        using var buffer = new NativeBuffer();
        Assert.Equal(-1, Native.Read(stream, buffer.Address, ulong.MaxValue));
        Assert.Equal(0xA5, buffer.ReadByte());
    }

    [Fact]
    public async Task LeaseExceptionsAreContainedAtTheUnmanagedBoundary()
    {
        var lease = new TestLease(100, 100)
        { ReadOverride = (_, _, _) => throw new IOException("Revoked download ownership.") };
        await using var fixture = new NativeFixture(lease);
        var stream = fixture.Open();
        using var buffer = new NativeBuffer();
        Assert.Equal(-1, Native.Read(stream, buffer.Address, 1));
        lease.ReadOverride = (_, _, _) => ValueTask.FromException<int>(new ObjectDisposedException("reader"));
        Assert.Equal(-1, Native.Read(stream, buffer.Address, 1));
        Assert.Equal(0, lease.LastOffset);
    }

    [Fact]
    public async Task NullBuffersAndZeroLengthReadsDoNotConsumeBytes()
    {
        var lease = new TestLease(4, 4);
        await using var fixture = new NativeFixture(lease);
        var stream = fixture.Open();
        Assert.Equal(-1, Native.Read(stream, 0, 1));
        Assert.Equal(0, Native.Read(stream, 0, 0));
        using var buffer = new NativeBuffer();
        Assert.Equal(4, Native.Read(stream, buffer.Address, 4));
        Assert.Equal(0, lease.LastOffset);
        Assert.Equal(0, Native.Read(stream, buffer.Address, 1));
        Assert.Equal(0xA5, buffer.ReadByte(4));
    }

    [Fact]
    public async Task EmptyDeclaredObjectHasRealEofWithoutCallingTheLease()
    {
        var lease = new TestLease(0, 0)
        { ReadOverride = (_, _, _) => throw new InvalidOperationException("No read expected at EOF.") };
        await using var fixture = new NativeFixture(lease);
        var stream = fixture.Open();
        Assert.Equal(0, Native.Size(stream));
        Assert.Equal(0, Native.Seek(stream, 0));
        Assert.Equal(0, Native.Read(stream, 0, ulong.MaxValue));
    }

    [Fact]
    public async Task PreDestroyCancellationStopsAdmissionButRetainsLeaseUntilPostDestroy()
    {
        var lease = new TestLease(10, 0);
        await using var fixture = new NativeFixture(lease);
        var stream = fixture.Open();
        using var buffer = new NativeBuffer();
        var read = Task.Run(() => Native.Read(stream, buffer.Address, 1));
        await lease.Waiting.Task.WaitAsync(Deadline, TestContext.Current.CancellationToken);
        fixture.Bridge.CancelBeforeDestroy();
        Assert.Equal(-1, await read.WaitAsync(Deadline, TestContext.Current.CancellationToken));
        Assert.Equal(0, lease.DisposeCount);
        Assert.Equal(MpvDownloadStreamBridge.LoadingFailed, fixture.TryOpen(fixture.Bridge.Uri).Result);
        Assert.Throws<InvalidOperationException>(() => fixture.Bridge.Register(1, (_, _, _, _) => { }));
        Task first = fixture.Bridge.ReleaseAfterDestroyAsync();
        Assert.Same(first, fixture.Bridge.ReleaseAfterDestroyAsync());
        await first.WaitAsync(Deadline, TestContext.Current.CancellationToken);
        Assert.Equal(1, lease.DisposeCount);
    }

    [Fact]
    public async Task RegistrationFailureStillHasAnExplicitPostDestroyReleasePath()
    {
        var lease = new TestLease(10, 10);
        var bridge = new MpvDownloadStreamBridge(lease);
        Assert.Throws<InvalidOperationException>(() => bridge.Register(1, (_, _, _, _) =>
            throw new InvalidOperationException("mpv protocol registration failed.")));
        Assert.Throws<InvalidOperationException>(() => bridge.Register(1, (_, _, _, _) => { }));
        bridge.CancelBeforeDestroy();
        Assert.Equal(0, lease.DisposeCount);
        await bridge.ReleaseAfterDestroyAsync();
        Assert.Equal(1, lease.DisposeCount);
    }

    [Fact]
    public async Task CollectionCannotInvalidateRegisteredCallbackThunks()
    {
        await using var fixture = new NativeFixture(new TestLease(100, 100));
        var stream = fixture.Open();
        GC.Collect();
        GC.WaitForPendingFinalizers();
        GC.Collect();
        using var buffer = new NativeBuffer();
        Assert.Equal(1, Native.Read(stream, buffer.Address, 1));
        Assert.Equal(100, Native.Size(stream));
        Native.Close(stream);
    }

    [Fact]
    public async Task RegistrationRootsTheBridgeEvenWithoutAnExternalManagedOwner()
    {
        var registration = CreateUnrootedRegistration();
        GC.Collect();
        GC.WaitForPendingFinalizers();
        GC.Collect();
        Assert.True(registration.Bridge.TryGetTarget(out var bridge));
        try
        {
            var opened = TryOpen(registration.OpenPointer, registration.UserData, registration.Uri);
            Assert.Equal(0, opened.Result);
            using var buffer = new NativeBuffer();
            Assert.Equal(1, Native.Read(opened.Stream, buffer.Address, 1));
            Native.Close(opened.Stream);
        }
        finally
        {
            bridge.CancelBeforeDestroy();
            await bridge.ReleaseAfterDestroyAsync();
        }
    }

    [Fact]
    public async Task UnknownAndClosedCookiesAreRejectedWithoutNativeExceptions()
    {
        await using var fixture = new NativeFixture(new TestLease(10, 10));
        var stream = fixture.Open();
        var unknown = stream;
        unknown.Cookie = nint.MaxValue;
        using var buffer = new NativeBuffer();
        Assert.Equal(-1, Native.Read(unknown, buffer.Address, 1));
        Assert.Equal(MpvDownloadStreamBridge.GenericError, Native.Seek(unknown, 0));
        Assert.Equal(MpvDownloadStreamBridge.GenericError, Native.Size(unknown));
        Native.Cancel(unknown);
        Native.Close(unknown);
        Native.Close(stream);
        Assert.Equal(-1, Native.Read(stream, buffer.Address, 1));
        Assert.Equal(MpvDownloadStreamBridge.GenericError, Native.Seek(stream, 0));
        Assert.Equal(MpvDownloadStreamBridge.GenericError, Native.Size(stream));
        Native.Cancel(stream);
        Native.Close(stream);
    }

    [Fact]
    public async Task ConcurrentOpenAndPreDestroyCancellationCannotLeaveLiveReaders()
    {
        var lease = new TestLease(100, 100);
        await using var fixture = new NativeFixture(lease);
        var openings = Enumerable.Range(0, 32).Select(_ => Task.Run(() => fixture.TryOpen(fixture.Bridge.Uri))).ToArray();
        fixture.Bridge.CancelBeforeDestroy();
        var results = await Task.WhenAll(openings);
        using var buffer = new NativeBuffer();
        foreach (var result in results.Where(result => result.Result == 0))
            Assert.Equal(-1, Native.Read(result.Stream, buffer.Address, 1));
        await fixture.Bridge.ReleaseAfterDestroyAsync();
        Assert.Equal(1, lease.DisposeCount);
    }

    private static async Task<MpvDownloadStreamBridge.StreamInfo> OpenWhenDrainedAsync(NativeFixture fixture)
    {
        using var timeout = new CancellationTokenSource(Deadline);
        while (true)
        {
            var result = fixture.TryOpen(fixture.Bridge.Uri);
            if (result.Result == 0) return result.Stream;
            await Task.Delay(1, timeout.Token);
        }
    }

    private static TaskCompletionSource Signal() => new(TaskCreationOptions.RunContinuationsAsynchronously);

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static UnrootedRegistration CreateUnrootedRegistration()
    {
        var bridge = new MpvDownloadStreamBridge(new TestLease(10, 10));
        nint userData = 0, pointer = 0;
        bridge.Register(1, (_, _, data, open) =>
        {
            userData = data;
            pointer = Marshal.GetFunctionPointerForDelegate(open);
        });
        return new UnrootedRegistration(bridge.Uri, userData, pointer, new WeakReference<MpvDownloadStreamBridge>(bridge));
    }

    private sealed record UnrootedRegistration(string Uri, nint UserData, nint OpenPointer,
        WeakReference<MpvDownloadStreamBridge> Bridge);

    private static (int Result, MpvDownloadStreamBridge.StreamInfo Stream) TryOpen(nint pointer, nint userData, string uri)
    {
        nint text = Marshal.StringToCoTaskMemUTF8(uri);
        nint info = Marshal.AllocHGlobal(Marshal.SizeOf<MpvDownloadStreamBridge.StreamInfo>());
        try
        {
            Marshal.StructureToPtr(default(MpvDownloadStreamBridge.StreamInfo), info, false);
            int result = Native.Open(pointer, userData, text, info);
            return (result, Marshal.PtrToStructure<MpvDownloadStreamBridge.StreamInfo>(info));
        }
        finally { Marshal.FreeHGlobal(info); Marshal.FreeCoTaskMem(text); }
    }

    private sealed class NativeFixture : IAsyncDisposable
    {
        internal NativeFixture(TestLease lease)
        {
            Bridge = new MpvDownloadStreamBridge(lease);
            Bridge.Register(17, (handle, protocol, data, open) =>
            {
                Assert.Equal((nint)17, handle);
                Assert.Equal(MpvDownloadStreamBridge.Protocol, protocol);
                UserData = data;
                OpenPointer = Marshal.GetFunctionPointerForDelegate(open);
            });
        }

        internal MpvDownloadStreamBridge Bridge { get; }
        internal nint UserData { get; private set; }
        internal nint OpenPointer { get; private set; }

        internal MpvDownloadStreamBridge.StreamInfo Open()
        {
            var result = TryOpen(Bridge.Uri);
            Assert.Equal(0, result.Result);
            return result.Stream;
        }

        internal (int Result, MpvDownloadStreamBridge.StreamInfo Stream) TryOpen(string uri, nint? userData = null) =>
            MpvDownloadStreamBridgeTests.TryOpen(OpenPointer, userData ?? UserData, uri);

        public async ValueTask DisposeAsync()
        {
            Bridge.CancelBeforeDestroy();
            // Tests own every simulated native invocation and await them before
            // this point, standing in for mpv_terminate_destroy's callback drain.
            await Bridge.ReleaseAfterDestroyAsync().WaitAsync(Deadline, TestContext.Current.CancellationToken);
        }
    }

    private sealed class NativeBuffer : IDisposable
    {
        internal NativeBuffer()
        {
            Address = Marshal.AllocHGlobal(MpvDownloadStreamBridge.MaximumReadBytes + 16);
            byte[] initial = new byte[MpvDownloadStreamBridge.MaximumReadBytes + 16];
            Array.Fill(initial, (byte)0xA5);
            Marshal.Copy(initial, 0, Address, initial.Length);
        }

        internal nint Address { get; }
        internal byte ReadByte(int offset = 0) => Marshal.ReadByte(Address, offset);
        public void Dispose() => Marshal.FreeHGlobal(Address);
    }

    // These calls execute the unmanaged Cdecl thunks. Do not replace them with
    // GetDelegateForFunctionPointer: that can return the original managed delegate
    // and silently skip the ABI transition this suite is intended to exercise.
    private static unsafe class Native
    {
        internal static int Open(nint callback, nint data, nint uri, nint info) =>
            ((delegate* unmanaged[Cdecl]<nint, nint, nint, int>)callback)(data, uri, info);
        internal static long Read(MpvDownloadStreamBridge.StreamInfo stream, nint buffer, ulong count) =>
            ((delegate* unmanaged[Cdecl]<nint, nint, ulong, long>)stream.Read)(stream.Cookie, buffer, count);
        internal static long Seek(MpvDownloadStreamBridge.StreamInfo stream, long offset) =>
            ((delegate* unmanaged[Cdecl]<nint, long, long>)stream.Seek)(stream.Cookie, offset);
        internal static long Size(MpvDownloadStreamBridge.StreamInfo stream) =>
            ((delegate* unmanaged[Cdecl]<nint, long>)stream.Size)(stream.Cookie);
        internal static void Close(MpvDownloadStreamBridge.StreamInfo stream) =>
            ((delegate* unmanaged[Cdecl]<nint, void>)stream.Close)(stream.Cookie);
        internal static void Cancel(MpvDownloadStreamBridge.StreamInfo stream) =>
            ((delegate* unmanaged[Cdecl]<nint, void>)stream.Cancel)(stream.Cookie);
    }

    private sealed class TestLease(long totalBytes, long prefixBytes) : IDownloadReadLease
    {
        private readonly object _gate = new();
        private TaskCompletionSource _changed = Signal();
        private long _prefix = prefixBytes;
        private int _disposed;

        public TransferRunToken Run => default;
        public long TotalBytes => totalBytes;
        public long ReadablePrefixBytes { get { lock (_gate) return _prefix; } }
        internal int DisposeCount => Volatile.Read(ref _disposed);
        internal long LastOffset { get; private set; }
        internal int MaximumRequested { get; private set; }
        internal TaskCompletionSource Waiting { get; } = Signal();
        internal Func<long, Memory<byte>, CancellationToken, ValueTask<int>>? ReadOverride { get; set; }

        public async ValueTask<int> ReadAsync(long offset, Memory<byte> buffer, CancellationToken cancellationToken)
        {
            if (ReadOverride is { } read) return await read(offset, buffer, cancellationToken);
            while (true)
            {
                cancellationToken.ThrowIfCancellationRequested();
                Task changed;
                lock (_gate)
                {
                    LastOffset = offset;
                    MaximumRequested = Math.Max(MaximumRequested, buffer.Length);
                    if (offset >= totalBytes) return 0;
                    if (offset < _prefix)
                    {
                        int count = (int)Math.Min(buffer.Length, _prefix - offset);
                        for (int i = 0; i < count; i++) buffer.Span[i] = ByteAt(offset + i);
                        return count;
                    }
                    changed = _changed.Task;
                    Waiting.TrySetResult();
                }
                await changed.WaitAsync(cancellationToken);
            }
        }

        internal void Advance(long prefix)
        {
            lock (_gate)
            {
                _prefix = prefix;
                _changed.TrySetResult();
                _changed = Signal();
            }
        }

        internal static byte ByteAt(long offset) => (byte)(offset % 251);
        public ValueTask DisposeAsync() { Interlocked.Increment(ref _disposed); return ValueTask.CompletedTask; }
    }
}
