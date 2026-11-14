using System.Globalization;
using System.Net;
using System.Net.Sockets;
using System.Text;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Tests.Services;

public sealed partial class DownloadApplicationServiceTests
{
    private static readonly byte[] RangePlaybackBytes = "abcdefgh"u8.ToArray();
    private static readonly DateTimeOffset RangePlaybackModified =
        new DateTimeOffset(2026, 10, 13, 1, 2, 3, TimeSpan.Zero).AddTicks(1_234_567);

    [Fact]
    public async Task PlaybackRangesPreferOwnedPrefixReuseReadAheadAndNeverBecomeSavedProgress()
    {
        await using var origin = new PlaybackRangeOrigin(RangePlaybackModified);
        var remote = new PlaybackRangeSources(origin.Address, RangePlaybackModified);
        await using var fixture = new PlaybackFixture(remote);
        var task = await fixture.StartAsync();
        ObserveRangePlaybackSource(fixture);
        int localReads = 0;
        fixture.Runner.Transform = reader =>
        {
            reader.Prefix = 4;
            reader.ReadOperation = (offset, buffer, _) =>
            {
                localReads++;
                Assert.True(offset < reader.Prefix);
                int count = (int)Math.Min(buffer.Length, reader.Prefix - offset);
                RangePlaybackBytes.AsMemory((int)offset, count).CopyTo(buffer);
                return ValueTask.FromResult(count);
            };
        };
        var source = await fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token);
        try
        {
            var local = new byte[4];
            Assert.Equal(2, await source.Reader.ReadAsync(2, local, Token));
            Assert.Equal("cd", Encoding.ASCII.GetString(local, 0, 2));
            Assert.Equal(0, remote.Acquisitions);

            var firstMissing = new byte[1];
            Assert.Equal(1, await source.Reader.ReadAsync(4, firstMissing, Token));
            Assert.Equal((byte)'e', firstMissing[0]);
            var sequential = new byte[3];
            Assert.Equal(3, await source.Reader.ReadAsync(5, sequential, Token));
            Assert.Equal("fgh", Encoding.ASCII.GetString(sequential));
            Assert.Equal(1, origin.Requests);
            Assert.Equal("bytes=4-7", origin.Range);
            Assert.Equal(RangePlaybackModified.ToUniversalTime().ToString("R", CultureInfo.InvariantCulture), origin.IfRange);
            Assert.Equal(3, remote.Preparations); // Preview plus before/after one network fill.
            Assert.Equal(1, remote.Acquisitions);
            Assert.Equal(1, remote.Releases);
            Assert.Equal(4, source.Reader.ReadablePrefixBytes);

            // Newly saved local bytes immediately take precedence over a still-cached network span.
            fixture.Runner.LastReader!.Prefix = 6;
            Assert.Equal(1, await source.Reader.ReadAsync(5, firstMissing, Token));
            Assert.Equal((byte)'f', firstMissing[0]);
            Assert.Equal(2, localReads);
            Assert.Equal(1, origin.Requests);
            var current = Assert.Single((await fixture.Downloads.ListAsync(fixture.Context, null, 50, Token)).Items);
            Assert.Equal(source.Item.Snapshot.ConfirmedOffset, current.Snapshot.ConfirmedOffset);
            Assert.Null(current.Progress);
            Assert.False(current.Snapshot.LocalPublished);
            Assert.Equal(0, fixture.Runner.Inner.Cancels);
        }
        finally { await source.Reader.DisposeAsync(); }
    }

    [Fact]
    public async Task MissingModificationObservationKeepsOriginalOwnedPrefixWait()
    {
        await using var origin = new PlaybackRangeOrigin(RangePlaybackModified);
        var remote = new PlaybackRangeSources(origin.Address, null);
        await using var fixture = new PlaybackFixture(remote);
        var task = await fixture.StartAsync();
        var entered = Signal();
        var continueLocal = fixture.Gate();
        fixture.Runner.Transform = reader =>
        {
            reader.Prefix = 4;
            reader.ReadOperation = async (offset, buffer, cancellationToken) =>
            {
                entered.TrySetResult();
                await continueLocal.Task.WaitAsync(cancellationToken);
                RangePlaybackBytes.AsMemory((int)offset, buffer.Length).CopyTo(buffer);
                return buffer.Length;
            };
        };
        var source = await fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token);
        try
        {
            var bytes = new byte[2];
            var reading = source.Reader.ReadAsync(4, bytes, Token).AsTask();
            await entered.Task.WaitAsync(Timeout, Token);
            Assert.False(reading.IsCompleted);
            Assert.Equal(0, remote.Acquisitions);
            Assert.Equal(1, remote.Preparations);
            fixture.Runner.LastReader!.Prefix = 6;
            continueLocal.TrySetResult();
            Assert.Equal(2, await reading.WaitAsync(Timeout, Token));
            Assert.Equal("ef", Encoding.ASCII.GetString(bytes));
            Assert.Equal(0, origin.Requests);
        }
        finally { continueLocal.TrySetResult(); await source.Reader.DisposeAsync(); }
    }

    [Fact]
    public async Task FullySavedPublishedReaderUsesOriginalOwnerWithoutRemoteProbe()
    {
        await using var origin = new PlaybackRangeOrigin(RangePlaybackModified);
        var remote = new PlaybackRangeSources(origin.Address, RangePlaybackModified);
        await using var fixture = new PlaybackFixture(remote);
        var task = await fixture.StartAsync();
        ObserveRangePlaybackSource(fixture);
        fixture.Runner.Transform = reader =>
        {
            reader.Prefix = RangePlaybackBytes.Length;
            reader.ReadOperation = (offset, buffer, _) =>
            {
                RangePlaybackBytes.AsMemory((int)offset, buffer.Length).CopyTo(buffer);
                return ValueTask.FromResult(buffer.Length);
            };
        };
        var source = await fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token);
        try
        {
            await fixture.Runner.Inner.CompleteAsync(task, Token);
            await fixture.Downloads.ListAsync(fixture.Context, null, 50, Token);
            var bytes = new byte[8];
            Assert.Equal(8, await source.Reader.ReadAsync(0, bytes, Token));
            Assert.Equal(RangePlaybackBytes, bytes);
            Assert.Equal(8, source.Reader.ReadablePrefixBytes);
            Assert.Equal(1, fixture.Runner.Acquisitions);
            Assert.Equal(0, fixture.Runner.LastReader!.Disposals);
            Assert.Equal(1, remote.Preparations);
            Assert.Equal(0, remote.Acquisitions);
            Assert.Equal(0, origin.Requests);
        }
        finally { await source.Reader.DisposeAsync(); }
    }

    [Fact]
    public async Task ChangedRangeSourceStopsPlaybackWithoutCancelingWriterOrAdvertisingOldPrefix()
    {
        await using var origin = new PlaybackRangeOrigin(RangePlaybackModified);
        var remote = new PlaybackRangeSources(origin.Address, RangePlaybackModified)
        {
            Metadata = (count, source) => new(source, false, 8,
                count >= 3 ? RangePlaybackModified.AddTicks(1) : RangePlaybackModified),
        };
        await using var fixture = new PlaybackFixture(remote);
        var task = await fixture.StartAsync();
        ObserveRangePlaybackSource(fixture);
        fixture.Runner.Transform = reader => reader.Prefix = 4;
        var source = await fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token);
        try
        {
            var bytes = new byte[] { 91, 92 };
            await FailureAsync(ErrorCode.SourceChanged, () => source.Reader.ReadAsync(4, bytes, Token).AsTask());
            Assert.Equal(new byte[] { 91, 92 }, bytes);
            Assert.Equal(ErrorCode.SourceChanged,
                Assert.Throws<FilewayException>(() => source.Reader.ReadablePrefixBytes).Error.Code);
            Assert.Equal(0, fixture.Runner.Inner.Cancels);
            Assert.Equal(4, fixture.Runner.LastReader!.Prefix);
            var current = Assert.Single((await fixture.Downloads.ListAsync(fixture.Context, null, 50, Token)).Items);
            Assert.Equal(DownloadStatus.Downloading, current.Status);
            Assert.Null(current.Snapshot.Error);
            Assert.Equal(source.Item.Snapshot.ConfirmedOffset, current.Snapshot.ConfirmedOffset);
        }
        finally { await source.Reader.DisposeAsync(); }
    }

    [Fact]
    public async Task CancelWakesBlockingRangeAndDrainsWithoutFinalNativeReaderDisposal()
    {
        await using var origin = new PlaybackRangeOrigin(RangePlaybackModified, blockBody: true);
        var remote = new PlaybackRangeSources(origin.Address, RangePlaybackModified);
        await using var fixture = new PlaybackFixture(remote);
        var task = await fixture.StartAsync();
        ObserveRangePlaybackSource(fixture);
        fixture.Runner.Transform = reader => reader.Prefix = 4;
        var source = await fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token);
        try
        {
            var reading = source.Reader.ReadAsync(4, new byte[1], Token).AsTask();
            await origin.BodyWaiting.Task.WaitAsync(Timeout, Token);
            var canceled = await fixture.Downloads.CancelAsync(fixture.Context, task, Token).WaitAsync(Timeout, Token);
            await origin.PeerClosed.Task.WaitAsync(Timeout, Token);
            await FailureAsync(ErrorCode.SourceChanged, () => reading.WaitAsync(Timeout, Token));
            Assert.Equal(DownloadStatus.Canceled, canceled.Status);
            Assert.Equal(1, remote.Releases);
            Assert.Equal(0, fixture.Runner.LastReader!.Disposals);
            Assert.Equal(1, fixture.Runner.Inner.Cancels);
        }
        finally { await source.Reader.DisposeAsync(); }
        Assert.Equal(1, fixture.Runner.LastReader!.Disposals);
    }

    [Theory]
    [InlineData("logout")]
    [InlineData("permission")]
    [InlineData("dispose")]
    public async Task BindingRetirementWakesBlockingRangeWhileNativeStillOwnsReader(string retirement)
    {
        await using var origin = new PlaybackRangeOrigin(RangePlaybackModified, blockBody: true);
        var remote = new PlaybackRangeSources(origin.Address, RangePlaybackModified);
        await using var fixture = new PlaybackFixture(remote);
        var task = await fixture.StartAsync();
        var context = fixture.Context;
        ObserveRangePlaybackSource(fixture);
        fixture.Runner.Transform = reader => reader.Prefix = 4;
        var source = await fixture.Playback.AcquirePlaybackAsync(context, task, Token);
        try
        {
            var reading = source.Reader.ReadAsync(4, new byte[1], Token).AsTask();
            await origin.BodyWaiting.Task.WaitAsync(Timeout, Token);
            if (retirement == "permission") fixture.Connection.EmitSession(fixture.Connection.Active! with
                { Permissions = fixture.Connection.Active.Permissions with { Download = false } });
            else if (retirement == "logout") await fixture.Bridge.LogoutAsync(context.Account, Token).WaitAsync(Timeout, Token);
            else await fixture.Bridge.DisposeAsync().AsTask().WaitAsync(Timeout, Token);
            await origin.PeerClosed.Task.WaitAsync(Timeout, Token);
            await FailureAsync(retirement == "permission" ? ErrorCode.PermissionDenied : ErrorCode.SourceChanged,
                () => reading.WaitAsync(Timeout, Token));
            Assert.Equal(1, remote.Releases);
            Assert.Equal(0, fixture.Runner.LastReader!.Disposals);
        }
        finally { await source.Reader.DisposeAsync(); }
    }

    [Fact]
    public async Task CancelAndPermissionLogoutWaitForLateRangeLeaseReleaseWithoutNativeDestructionCycle()
    {
        await using var origin = new PlaybackRangeOrigin(RangePlaybackModified);
        var remote = new PlaybackRangeSources(origin.Address, RangePlaybackModified);
        await using var fixture = new PlaybackFixture(remote);
        var acquireRelease = fixture.Gate();
        var disposeRelease = fixture.Gate();
        remote.AcquireRelease = acquireRelease.Task;
        remote.DisposeRelease = disposeRelease.Task;
        var task = await fixture.StartAsync();
        var context = fixture.Context;
        ObserveRangePlaybackSource(fixture);
        fixture.Runner.Transform = reader => reader.Prefix = 4;
        var source = await fixture.Playback.AcquirePlaybackAsync(context, task, Token);
        Task? canceling = null, loggingOut = null;
        try
        {
            var reading = source.Reader.ReadAsync(4, new byte[1], Token).AsTask();
            await remote.AcquireEntered.Task.WaitAsync(Timeout, Token);
            canceling = fixture.Downloads.CancelAsync(context, task, Token);
            await fixture.Runner.CancelReturned.Task.WaitAsync(Timeout, Token);
            await remote.AcquisitionCanceled.Task.WaitAsync(Timeout, Token);
            Assert.False(canceling.IsCompleted); // Writer settled; real range acquisition still owns its result.
            fixture.Connection.EmitSession(fixture.Connection.Active! with
                { Permissions = fixture.Connection.Active.Permissions with { Download = false } });
            loggingOut = fixture.Bridge.LogoutAsync(context.Account, Token);
            acquireRelease.TrySetResult();
            await remote.ReleaseEntered.Task.WaitAsync(Timeout, Token);
            Assert.False(canceling.IsCompleted);
            Assert.False(loggingOut.IsCompleted);
            Assert.Equal(0, fixture.Runner.LastReader!.Disposals);
            disposeRelease.TrySetResult();
            await canceling.WaitAsync(Timeout, Token);
            await loggingOut.WaitAsync(Timeout, Token);
            var failure = await Assert.ThrowsAsync<FilewayException>(() => reading.WaitAsync(Timeout, Token));
            Assert.Contains(failure.Error.Code, new[] { ErrorCode.PermissionDenied, ErrorCode.SourceChanged });
            Assert.Equal(1, remote.Releases);
            Assert.Equal(0, origin.Requests);
            Assert.Equal(1, fixture.Runner.Inner.Cancels);
            Assert.Equal(0, fixture.Runner.LastReader!.Disposals);
        }
        finally
        {
            acquireRelease.TrySetResult();
            disposeRelease.TrySetResult();
            // Teardown may release the native-held reader after the decisive assertion. A
            // regression that incorrectly waits for this final disposal must fail, not hang cleanup.
            await source.Reader.DisposeAsync();
            if (canceling is not null) await canceling.WaitAsync(Timeout, Token);
            if (loggingOut is not null) await loggingOut.WaitAsync(Timeout, Token);
        }
    }

    [Fact]
    public async Task NativeHeldPlaybackReadersHaveABoundedTotalRangeBudget()
    {
        await using var fixture = new PlaybackFixture();
        var task = await fixture.StartAsync();
        var readers = new List<IDownloadReadLease>();
        try
        {
            for (int index = 0; index < 4; index++)
                readers.Add((await fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token)).Reader);
            await FailureAsync(ErrorCode.Busy, () => fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token));
            Assert.Equal(4, fixture.Runner.Acquisitions);
            await readers[0].DisposeAsync();
            readers.Add((await fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token)).Reader);
            Assert.Equal(5, fixture.Runner.Acquisitions);
        }
        finally { foreach (var reader in readers) await reader.DisposeAsync(); }
    }

    [Fact]
    public async Task CancelCannotAcknowledgeAnUnconfirmedRangeLeaseRelease()
    {
        await using var origin = new PlaybackRangeOrigin(RangePlaybackModified, blockBody: true);
        var remote = new PlaybackRangeSources(origin.Address, RangePlaybackModified) { ReleaseFails = true };
        await using var fixture = new PlaybackFixture(remote);
        var task = await fixture.StartAsync();
        ObserveRangePlaybackSource(fixture);
        fixture.Runner.Transform = reader => reader.Prefix = 4;
        var source = await fixture.Playback.AcquirePlaybackAsync(fixture.Context, task, Token);
        try
        {
            var reading = source.Reader.ReadAsync(4, new byte[1], Token).AsTask();
            await origin.BodyWaiting.Task.WaitAsync(Timeout, Token);
            await FailureAsync(ErrorCode.OutcomeUnknown,
                () => fixture.Downloads.CancelAsync(fixture.Context, task, Token).WaitAsync(Timeout, Token));
            _ = await Assert.ThrowsAsync<FilewayException>(() => reading.WaitAsync(Timeout, Token));
            Assert.Equal(1, remote.Releases);
            Assert.Equal(0, fixture.Runner.LastReader!.Disposals);
        }
        finally
        {
            await FailureAsync(ErrorCode.OutcomeUnknown, () => source.Reader.DisposeAsync().AsTask());
            await FailureAsync(ErrorCode.OutcomeUnknown, () => source.Reader.DisposeAsync().AsTask());
        }
        Assert.Equal(1, remote.Releases);
        Assert.Equal(1, fixture.Runner.LastReader!.Disposals);
    }

    private static void ObserveRangePlaybackSource(PlaybackFixture fixture) =>
        fixture.Store.Transform = record => record with
        {
            Checkpoint = record.Checkpoint with { Source = record.Checkpoint.Source! with { Modified = RangePlaybackModified } },
        };

    private sealed class PlaybackRangeSources(Uri address, DateTimeOffset? modified) : IDownloadSourceProvider
    {
        internal int Preparations, Acquisitions, Releases;
        internal Func<int, RemoteResourceRef, RemoteFile>? Metadata { get; init; }
        internal Task? AcquireRelease { get; set; }
        internal Task? DisposeRelease { get; set; }
        internal bool ReleaseFails { get; init; }
        internal TaskCompletionSource AcquireEntered { get; } = Signal();
        internal TaskCompletionSource AcquisitionCanceled { get; } = Signal();
        internal TaskCompletionSource ReleaseEntered { get; } = Signal();

        public Task<RemoteFile> PrepareDownloadAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            int count = Interlocked.Increment(ref Preparations);
            return Task.FromResult(Metadata?.Invoke(count, resource) ?? new(resource, false, 8, modified));
        }

        public async Task<IDownloadLease> AcquireDownloadAsync(DownloadLeaseRequest request, CancellationToken cancellationToken)
        {
            Interlocked.Increment(ref Acquisitions);
            using var registration = cancellationToken.UnsafeRegister(static state =>
                ((TaskCompletionSource)state!).TrySetResult(), AcquisitionCanceled);
            AcquireEntered.TrySetResult();
            if (AcquireRelease is { } wait) await wait; // Deliberately returns a late owned lease after cancellation.
            return new PlaybackRangeLease(this, request.Source, request.Length, address);
        }

        public Task<FileChecksumResult> CalculateDownloadChecksumAsync(DownloadLeaseRequest request,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        private sealed class PlaybackRangeLease(PlaybackRangeSources owner, RemoteResourceRef resource,
            long length, Uri address) : IDownloadLease
        {
            public RemoteResourceRef Resource => resource;
            public Uri Address => address;
            public long Length => length;
            public string? SourceVersion => null;
            public bool SupportsResume => false;
            public async ValueTask DisposeAsync()
            {
                Interlocked.Increment(ref owner.Releases);
                owner.ReleaseEntered.TrySetResult();
                if (owner.DisposeRelease is { } wait) await wait;
                if (owner.ReleaseFails) throw new IOException("Synthetic lease release failure.");
            }
        }
    }

    private sealed class PlaybackRangeOrigin : IAsyncDisposable
    {
        private readonly TcpListener _listener = new(IPAddress.Loopback, 0);
        private readonly CancellationTokenSource _stop = new();
        private readonly DateTimeOffset _modified;
        private readonly bool _blockBody;
        private readonly Task _server;
        internal int Requests;
        internal string? Range { get; private set; }
        internal string? IfRange { get; private set; }
        internal TaskCompletionSource BodyWaiting { get; } = Signal();
        internal TaskCompletionSource PeerClosed { get; } = Signal();
        internal Uri Address { get; }

        internal PlaybackRangeOrigin(DateTimeOffset modified, bool blockBody = false)
        {
            _modified = modified;
            _blockBody = blockBody;
            _listener.Start();
            Address = new Uri($"http://127.0.0.1:{((IPEndPoint)_listener.LocalEndpoint).Port}/stream/range_fixture");
            _server = ServeAsync();
        }

        private async Task ServeAsync()
        {
            try
            {
                while (!_stop.IsCancellationRequested)
                {
                    using var client = await _listener.AcceptTcpClientAsync(_stop.Token);
                    using var stream = client.GetStream();
                    using var reader = new StreamReader(stream, Encoding.ASCII, false, 1024, leaveOpen: true);
                    string first = await reader.ReadLineAsync(_stop.Token) ?? throw new IOException();
                    Assert.StartsWith("GET /stream/range_fixture HTTP/", first, StringComparison.Ordinal);
                    var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                    while (await reader.ReadLineAsync(_stop.Token) is { Length: > 0 } line)
                    {
                        int separator = line.IndexOf(':');
                        headers.Add(line[..separator], line[(separator + 1)..].Trim());
                    }
                    Range = headers["Range"];
                    IfRange = headers["If-Range"];
                    string[] bounds = Range["bytes=".Length..].Split('-');
                    int start = int.Parse(bounds[0], CultureInfo.InvariantCulture);
                    int end = int.Parse(bounds[1], CultureInfo.InvariantCulture);
                    Interlocked.Increment(ref Requests);
                    string response = $"HTTP/1.1 206 Partial Content\r\nContent-Range: bytes {start}-{end}/8\r\n" +
                        $"Content-Length: {end - start + 1}\r\nLast-Modified: {_modified.ToUniversalTime():R}\r\nConnection: close\r\n\r\n";
                    await stream.WriteAsync(Encoding.ASCII.GetBytes(response), _stop.Token);
                    if (_blockBody)
                    {
                        BodyWaiting.TrySetResult();
                        try { _ = await stream.ReadAsync(new byte[1], _stop.Token); }
                        catch (IOException) { }
                        finally { PeerClosed.TrySetResult(); }
                    }
                    else await stream.WriteAsync(RangePlaybackBytes.AsMemory(start, end - start + 1), _stop.Token);
                }
            }
            catch (OperationCanceledException) when (_stop.IsCancellationRequested) { }
            catch (SocketException) when (_stop.IsCancellationRequested) { }
            catch (ObjectDisposedException) when (_stop.IsCancellationRequested) { }
        }

        public async ValueTask DisposeAsync()
        {
            await _stop.CancelAsync();
            _listener.Stop();
            await _server.WaitAsync(Timeout, Token);
            _stop.Dispose();
        }
    }
}
