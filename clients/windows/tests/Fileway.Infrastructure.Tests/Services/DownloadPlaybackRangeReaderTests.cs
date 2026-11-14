using System.Collections.Concurrent;
using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Services;

namespace Fileway.Infrastructure.Tests.Services;

public sealed class DownloadPlaybackRangeReaderTests
{
    private static readonly DateTimeOffset Modified = new DateTimeOffset(2026, 10, 13, 1, 2, 3, TimeSpan.Zero).AddTicks(1234567);
    private static readonly DateTimeOffset HttpModified = new(Modified.Ticks - Modified.Ticks % TimeSpan.TicksPerSecond, TimeSpan.Zero);
    private static readonly TimeSpan TestTimeout = TimeSpan.FromSeconds(10);
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Fact]
    public async Task Exact206UsesObservedHttpDateAndBoundedPrefetchAcrossSmallReads()
    {
        await using var fixture = new Fixture(2L * DownloadPlaybackRangeReader.PrefetchBytes + 17);
        byte[] buffer = new byte[64 * 1024];

        Assert.Equal(buffer.Length, await fixture.Reader.ReadAsync(0, buffer, Token));
        Assert.Equal(Pattern(0, buffer.Length), buffer);
        for (int read = 1; read < 16; read++)
        {
            long offset = (long)read * buffer.Length;
            Assert.Equal(buffer.Length, await fixture.Reader.ReadAsync(offset, buffer, Token));
            Assert.Equal(Pattern(offset, buffer.Length), buffer);
        }

        var request = Assert.Single(fixture.Http.Requests);
        Assert.Equal("bytes=0-1048575", request.Range);
        Assert.Equal(HttpModified.ToString("r", CultureInfo.InvariantCulture), request.IfRange);
        Assert.Equal("identity", request.Encoding);
        Assert.Equal(new Uri(Lease.Capability), request.Address);
        Assert.Equal(2, fixture.Sources.Observations);
        Assert.Single(fixture.Sources.Leases);
        Assert.Equal(1, fixture.Sources.Leases.Single().Disposals);
        Assert.Equal(0, fixture.Sources.Checksums);

        long next = DownloadPlaybackRangeReader.PrefetchBytes;
        Assert.Equal(buffer.Length, await fixture.Reader.ReadAsync(next, buffer, Token));
        Assert.Equal(Pattern(next, buffer.Length), buffer);
        Assert.Equal(2, fixture.Http.Requests.Count);
        Assert.Equal(4, fixture.Sources.Observations);
    }

    [Fact]
    public async Task TailNearLongMaxValueUsesExactRangeWithoutOverflowAndEofDoesNotFetch()
    {
        await using var fixture = new Fixture(long.MaxValue);
        byte[] buffer = Enumerable.Repeat((byte)0xa5, 20).ToArray();
        long offset = long.MaxValue - 9;

        Assert.Equal(9, await fixture.Reader.ReadAsync(offset, buffer, Token));
        Assert.Equal(Pattern(offset, 9), buffer[..9]);
        Assert.All(buffer[9..], value => Assert.Equal(0xa5, value));
        Assert.Equal($"bytes={offset}-{long.MaxValue - 1}", Assert.Single(fixture.Http.Requests).Range);
        Assert.Equal(0, await fixture.Reader.ReadAsync(long.MaxValue, buffer, Token));
        Assert.Equal(0, await fixture.Reader.ReadAsync(0, Memory<byte>.Empty, Token));
        Assert.Single(fixture.Http.Requests);
        Assert.Equal(2, fixture.Sources.Observations);
    }

    [Fact]
    public async Task EmptySourceAndBuffersStillRequireAuthorityWithoutIssuingRequests()
    {
        await using var fixture = new Fixture(0);
        Assert.Equal(0, await fixture.Reader.ReadAsync(0, new byte[1], Token));
        Assert.Equal(0, await fixture.Reader.ReadAsync(0, Memory<byte>.Empty, Token));
        fixture.AuthorityError = ErrorCode.PermissionDenied;
        await FailureAsync(ErrorCode.PermissionDenied, () => fixture.Reader.ReadAsync(0, Memory<byte>.Empty, Token).AsTask());
        Assert.Empty(fixture.Http.Requests);
        Assert.Equal(0, fixture.Sources.Observations);
    }

    [Theory]
    [InlineData(-1)]
    [InlineData(8)]
    public async Task OutOfBoundsOffsetFailsBeforeSourceAccess(long offset)
    {
        await using var fixture = new Fixture(7);
        await FailureAsync(ErrorCode.InvalidRequest, () => fixture.Reader.ReadAsync(offset, new byte[1], Token).AsTask());
        Assert.Equal(0, fixture.Sources.Observations);
        Assert.Empty(fixture.Http.Requests);
    }

    [Theory]
    [InlineData("modified")]
    [InlineData("source-version")]
    [InlineData("empty-source-version")]
    [InlineData("snapshot-version")]
    public void MissingObservationOrAnyVersionCannotBeDowngradedIntoRangeCapability(string field)
    {
        var sources = new Sources(17);
        var snapshot = new TransferSourceSnapshot(17, field == "modified" ? null : Modified,
            field == "snapshot-version" ? "private-version" : null);
        var source = sources.Source with { SourceVersion = field switch
        {
            "source-version" => "private-version",
            "empty-source-version" => "",
            _ => null,
        } };
        var error = Assert.Throws<FilewayException>(() => new DownloadPlaybackRangeReader(sources, source, snapshot,
            () => { }, Token));
        Assert.Equal(ErrorCode.CapabilityMissing, error.Error.Code);
        AssertSafe(error);
        Assert.Equal(0, sources.Observations);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task TransportPolicyDisablesRedirectProxyCookiesAndAutomaticDecompression(bool sockets)
    {
        var sources = new Sources(17);
        HttpMessageHandler handler = sockets
            ? new SocketsHttpHandler { AllowAutoRedirect = true, UseCookies = true, UseProxy = true, AutomaticDecompression = DecompressionMethods.All }
            : new HttpClientHandler { AllowAutoRedirect = true, UseCookies = true, UseProxy = true, AutomaticDecompression = DecompressionMethods.All };
        await using var reader = new DownloadPlaybackRangeReader(sources, sources.Source, new(17, Modified), () => { }, Token, handler);
        if (handler is SocketsHttpHandler socketHandler)
        {
            Assert.False(socketHandler.AllowAutoRedirect);
            Assert.False(socketHandler.UseProxy);
            Assert.False(socketHandler.UseCookies);
            Assert.Equal(DecompressionMethods.None, socketHandler.AutomaticDecompression);
        }
        else if (handler is HttpClientHandler client)
        {
            Assert.False(client.AllowAutoRedirect);
            Assert.False(client.UseProxy);
            Assert.False(client.UseCookies);
            Assert.Equal(DecompressionMethods.None, client.AutomaticDecompression);
        }
    }

    [Theory]
    [InlineData(200, ErrorCode.SourceChanged)]
    [InlineData(416, ErrorCode.SourceChanged)]
    [InlineData(401, ErrorCode.AuthenticationExpired)]
    [InlineData(403, ErrorCode.PermissionDenied)]
    [InlineData(404, ErrorCode.SourceChanged)]
    [InlineData(302, ErrorCode.ProtocolMismatch)]
    [InlineData(500, ErrorCode.ProtocolMismatch)]
    public async Task Non206ResponseHasSafeDistinctFailureAndNeverPublishes(int status, ErrorCode expected)
    {
        await using var fixture = new Fixture(17);
        fixture.Http.Mutate = response => response.StatusCode = (HttpStatusCode)status;
        byte[] buffer = Sentinel();
        var error = await FailureAsync(expected, () => fixture.Reader.ReadAsync(0, buffer, Token).AsTask());
        Assert.Equal(status, error.Error.HttpStatus);
        AssertUntouched(buffer);
        Assert.Equal(1, Assert.Single(fixture.Sources.Leases).Disposals);
        Assert.Equal(1, fixture.Sources.Observations);
    }

    [Theory]
    [InlineData("missing-range")]
    [InlineData("malformed-range")]
    [InlineData("duplicate-range")]
    [InlineData("range-unit")]
    [InlineData("range-start")]
    [InlineData("range-end")]
    [InlineData("range-total")]
    [InlineData("range-unknown-total")]
    [InlineData("missing-length")]
    [InlineData("malformed-length")]
    [InlineData("duplicate-length")]
    [InlineData("list-length")]
    [InlineData("wrong-length")]
    [InlineData("gzip")]
    [InlineData("duplicate-identity")]
    [InlineData("multipart")]
    [InlineData("malformed-content-type")]
    [InlineData("duplicate-content-type")]
    [InlineData("missing-modified")]
    [InlineData("malformed-modified")]
    [InlineData("duplicate-modified")]
    [InlineData("chunked")]
    [InlineData("short-body")]
    [InlineData("long-body")]
    public async Task InvalidRangeHeadersOrBodyAreRejectedBeforeAnyBytesReachCaller(string scenario)
    {
        await using var fixture = new Fixture(17);
        fixture.Http.Mutate = response =>
        {
            var headers = response.Content.Headers;
            switch (scenario)
            {
                case "missing-range": headers.Remove("Content-Range"); break;
                case "malformed-range": Raw(headers, "Content-Range", "private-malformed-range"); break;
                case "duplicate-range": Raw(headers, "Content-Range", "bytes 0-16/17", "bytes 0-16/17"); break;
                case "range-unit": Raw(headers, "Content-Range", "items 0-16/17"); break;
                case "range-start": Raw(headers, "Content-Range", "bytes 1-16/17"); break;
                case "range-end": Raw(headers, "Content-Range", "bytes 0-15/17"); break;
                case "range-total": Raw(headers, "Content-Range", "bytes 0-16/18"); break;
                case "range-unknown-total": Raw(headers, "Content-Range", "bytes 0-16/*"); break;
                case "missing-length": headers.Remove("Content-Length"); break;
                case "malformed-length": Raw(headers, "Content-Length", "private-invalid-length"); break;
                case "duplicate-length": Raw(headers, "Content-Length", "17", "17"); break;
                case "list-length": Raw(headers, "Content-Length", "17, 17"); break;
                case "wrong-length": headers.ContentLength = 18; break;
                case "gzip": headers.ContentEncoding.Add("gzip"); break;
                case "duplicate-identity": Raw(headers, "Content-Encoding", "identity", "identity"); break;
                case "multipart": headers.ContentType = new("multipart/byteranges"); break;
                case "malformed-content-type": Raw(headers, "Content-Type", "private invalid/type"); break;
                case "duplicate-content-type": Raw(headers, "Content-Type", "application/octet-stream", "application/octet-stream"); break;
                case "missing-modified": headers.Remove("Last-Modified"); break;
                case "malformed-modified": Raw(headers, "Last-Modified", "private-invalid-date"); break;
                case "duplicate-modified": Raw(headers, "Last-Modified", HttpModified.ToString("r"), HttpModified.ToString("r")); break;
                case "chunked": response.Headers.TransferEncodingChunked = true; break;
                case "short-body": ReplaceBody(response, Pattern(0, 16)); break;
                case "long-body": ReplaceBody(response, Pattern(0, 18)); break;
            }
        };
        byte[] buffer = Sentinel();
        await FailureAsync(ErrorCode.ProtocolMismatch, () => fixture.Reader.ReadAsync(0, buffer, Token).AsTask());
        AssertUntouched(buffer);
        Assert.Equal(1, Assert.Single(fixture.Sources.Leases).Disposals);
        Assert.Equal(1, fixture.Sources.Observations);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task AbsentOrSingleIdentityEncodingIsAccepted(bool explicitIdentity)
    {
        await using var fixture = new Fixture(17);
        if (explicitIdentity) fixture.Http.Mutate = response => response.Content.Headers.ContentEncoding.Add("identity");
        byte[] buffer = new byte[17];
        Assert.Equal(17, await fixture.Reader.ReadAsync(0, buffer, Token));
        Assert.Equal(Pattern(0, 17), buffer);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task UnsupportedVersionExtensionIsNotTreatedAsSourcePinning(bool contentHeader)
    {
        await using var fixture = new Fixture(17);
        fixture.Http.Mutate = response => Raw(contentHeader ? response.Content.Headers : response.Headers,
            "X-Fileway-Source-Version", "private-version");
        byte[] buffer = Sentinel();
        await FailureAsync(ErrorCode.CapabilityMissing, () => fixture.Reader.ReadAsync(0, buffer, Token).AsTask());
        AssertUntouched(buffer);
    }

    [Fact]
    public async Task ChangedLastModifiedRejectsResponseAsSourceChange()
    {
        await using var fixture = new Fixture(17);
        fixture.Http.Mutate = response => response.Content.Headers.LastModified = HttpModified.AddSeconds(1);
        byte[] buffer = Sentinel();
        await FailureAsync(ErrorCode.SourceChanged, () => fixture.Reader.ReadAsync(0, buffer, Token).AsTask());
        AssertUntouched(buffer);
    }

    [Theory]
    [InlineData("account")]
    [InlineData("revision")]
    [InlineData("user")]
    [InlineData("opaque-path")]
    [InlineData("length")]
    [InlineData("full-modified")]
    [InlineData("missing-modified")]
    [InlineData("directory")]
    [InlineData("version")]
    public async Task ChangedMetadataMakesReaderTerminalAndCannotRevivePreviouslyCachedBytes(string field)
    {
        await using var fixture = new Fixture(DownloadPlaybackRangeReader.PrefetchBytes + 17L);
        Assert.Equal(4, await fixture.Reader.ReadAsync(0, new byte[4], Token));
        fixture.Sources.Observe = (_, _) => Task.FromResult(Change(fixture.Sources.Metadata, field));
        byte[] buffer = Sentinel();
        ErrorCode expected = field == "version" ? ErrorCode.CapabilityMissing : ErrorCode.SourceChanged;

        await FailureAsync(expected, () => fixture.Reader.ReadAsync(DownloadPlaybackRangeReader.PrefetchBytes, buffer, Token).AsTask());
        await FailureAsync(expected, () => fixture.Reader.ReadAsync(0, buffer, Token).AsTask());
        AssertUntouched(buffer);
        Assert.Single(fixture.Http.Requests);
        Assert.Equal(3, fixture.Sources.Observations);
    }

    [Theory]
    [InlineData("full-modified")]
    [InlineData("opaque-path")]
    [InlineData("length")]
    public async Task PostBodyMetadataMustMatchFullObservationBeforePublication(string field)
    {
        await using var fixture = new Fixture(17);
        fixture.Sources.Observe = (call, _) => Task.FromResult(call == 2 ? Change(fixture.Sources.Metadata, field) : fixture.Sources.Metadata);
        byte[] buffer = Sentinel();
        await FailureAsync(ErrorCode.SourceChanged, () => fixture.Reader.ReadAsync(0, buffer, Token).AsTask());
        AssertUntouched(buffer);
        Assert.Equal(2, fixture.Sources.Observations);
        Assert.Equal(1, Assert.Single(fixture.Sources.Leases).Disposals);
    }

    [Theory]
    [InlineData("/private/%ff.bin", "/private/%FF.bin", true)]
    [InlineData("/private/%ff.bin", "/private/%fe.bin", false)]
    [InlineData("/private/a%252Fb.bin", "/private/a%2Fb.bin", false)]
    [InlineData("/private/a%25.bin", "/private/a%2525.bin", false)]
    [InlineData("/private/%E4%BD%A0.bin", "/private/你.bin", true)]
    public async Task OpaqueWireIdentityComparesBytesWithoutLossyUnicodeOrDoubleDecoding(string source, string observed, bool equal)
    {
        await using var fixture = new Fixture(17, wire: source);
        fixture.Sources.Observe = (_, _) => Task.FromResult(fixture.Sources.Metadata with
        {
            Resource = fixture.Sources.Source with { WirePath = observed, DisplayName = "another alias" },
            Modified = Modified.ToOffset(TimeSpan.FromHours(8)),
        });
        fixture.Sources.MutateLease = lease => lease.Resource = lease.Resource with { WirePath = observed };
        byte[] buffer = Sentinel();
        if (equal)
        {
            Assert.Equal(17, await fixture.Reader.ReadAsync(0, buffer, Token));
            Assert.Equal(Pattern(0, 17), buffer);
        }
        else
        {
            await FailureAsync(ErrorCode.SourceChanged, () => fixture.Reader.ReadAsync(0, buffer, Token).AsTask());
            AssertUntouched(buffer);
            Assert.Empty(fixture.Http.Requests);
        }
    }

    [Theory]
    [InlineData("identity", ErrorCode.ProtocolMismatch)]
    [InlineData("length", ErrorCode.SourceChanged)]
    [InlineData("version", ErrorCode.CapabilityMissing)]
    [InlineData("resource-version", ErrorCode.CapabilityMissing)]
    [InlineData("resume", ErrorCode.CapabilityMissing)]
    public async Task LeaseEvidenceIsValidatedAndReleasedBeforeHttp(string field, ErrorCode expected)
    {
        await using var fixture = new Fixture(17);
        fixture.Sources.MutateLease = lease =>
        {
            switch (field)
            {
                case "identity": lease.Resource = lease.Resource with { WirePath = "/private/other" }; break;
                case "length": lease.Length++; break;
                case "version": lease.SourceVersion = "private-version"; break;
                case "resource-version": lease.Resource = lease.Resource with { SourceVersion = "" }; break;
                case "resume": lease.SupportsResume = true; break;
            }
        };
        await FailureAsync(expected, () => fixture.Reader.ReadAsync(0, Sentinel(), Token).AsTask());
        Assert.Empty(fixture.Http.Requests);
        Assert.Equal(1, Assert.Single(fixture.Sources.Leases).Disposals);
    }

    [Theory]
    [InlineData("https://127.0.0.1:41234/stream/id")]
    [InlineData("http://localhost:41234/stream/id")]
    [InlineData("http://127.1:41234/stream/id")]
    [InlineData("http://2130706433:41234/stream/id")]
    [InlineData("http://[::1]:41234/stream/id")]
    [InlineData("http://127.0.0.1:80/stream/id")]
    [InlineData("http://127.0.0.1:0/stream/id")]
    [InlineData("http://private-user@127.0.0.1:41234/stream/id")]
    [InlineData("http://127.0.0.1:41234/stream/id?private=query")]
    [InlineData("http://127.0.0.1:41234/stream/id#private-fragment")]
    [InlineData("http://127.0.0.1:41234/stream/")]
    [InlineData("http://127.0.0.1:41234/stream/%61")]
    [InlineData("http://127.0.0.1:41234/stream/id/more")]
    [InlineData("http://127.0.0.1:41234/stream/id/../other")]
    [InlineData("http://127.0.0.1:41234/stream/id.thing")]
    [InlineData("http://127.0.0.1:41234/other/id")]
    public async Task CapabilityAddressMustBeExactPrivateLoopbackToken(string address)
    {
        await using var fixture = new Fixture(17);
        fixture.Sources.MutateLease = lease => lease.Address = new(address);
        await FailureAsync(ErrorCode.ProtocolMismatch, () => fixture.Reader.ReadAsync(0, Sentinel(), Token).AsTask());
        Assert.Empty(fixture.Http.Requests);
        Assert.Equal(1, Assert.Single(fixture.Sources.Leases).Disposals);
    }

    [Fact]
    public async Task EofProbeAndPostMetadataBothFinishBeforeBytesArePublished()
    {
        await using var fixture = new Fixture(17);
        await using var body = new ProbeGateStream(Pattern(0, 17));
        var afterEntered = Gate();
        var afterRelease = Gate();
        fixture.Http.Body = (_, _) => body;
        fixture.Sources.Observe = async (call, token) =>
        {
            if (call == 2)
            {
                afterEntered.TrySetResult();
                await afterRelease.Task.WaitAsync(token);
            }
            return fixture.Sources.Metadata;
        };
        byte[] buffer = Sentinel();
        var read = fixture.Reader.ReadAsync(0, buffer, Token).AsTask();
        try
        {
            await body.ProbeEntered.Task.WaitAsync(TestTimeout, Token);
            Assert.False(read.IsCompleted);
            AssertUntouched(buffer);
            Assert.Equal(1, fixture.Sources.Observations);
            body.ReleaseProbe.TrySetResult();
            await afterEntered.Task.WaitAsync(TestTimeout, Token);
            Assert.False(read.IsCompleted);
            AssertUntouched(buffer);
            afterRelease.TrySetResult();
            Assert.Equal(17, await read.WaitAsync(TestTimeout, Token));
            Assert.Equal(Pattern(0, 17), buffer);
        }
        finally { body.ReleaseProbe.TrySetResult(); afterRelease.TrySetResult(); }
    }

    [Fact]
    public async Task PermissionRevocationOnFinalMetadataNeverPublishesOrLeavesUsableCache()
    {
        await using var fixture = new Fixture(17);
        fixture.Sources.Observe = (call, _) =>
        {
            if (call == 2) fixture.AuthorityError = ErrorCode.PermissionDenied;
            return Task.FromResult(fixture.Sources.Metadata);
        };
        byte[] buffer = Sentinel();
        await FailureAsync(ErrorCode.PermissionDenied, () => fixture.Reader.ReadAsync(0, buffer, Token).AsTask());
        fixture.AuthorityError = null;
        await FailureAsync(ErrorCode.PermissionDenied, () => fixture.Reader.ReadAsync(0, buffer, Token).AsTask());
        AssertUntouched(buffer);
        Assert.Single(fixture.Http.Requests);
    }

    [Fact]
    public async Task CallerCancelDuringUnverifiedBodyDoesNotPublishOrPoisonFollowingRead()
    {
        await using var fixture = new Fixture(17);
        await using var body = new ProbeGateStream(Pattern(0, 17));
        using var caller = CancellationTokenSource.CreateLinkedTokenSource(Token);
        fixture.Http.Body = (_, _) => body;
        byte[] buffer = Sentinel();
        var read = fixture.Reader.ReadAsync(0, buffer, caller.Token).AsTask();
        try
        {
            await body.ProbeEntered.Task.WaitAsync(TestTimeout, Token);
            await caller.CancelAsync();
            await FailureAsync(ErrorCode.Canceled, () => read.WaitAsync(TestTimeout, Token));
            AssertUntouched(buffer);
            fixture.Reader.RequireReleased();
            fixture.Http.Body = null;
            Assert.Equal(17, await fixture.Reader.ReadAsync(0, buffer, Token));
            Assert.Equal(Pattern(0, 17), buffer);
            Assert.Equal(2, fixture.Http.Requests.Count);
            Assert.Equal(3, fixture.Sources.Observations);
            Assert.All(fixture.Sources.Leases, lease => Assert.Equal(1, lease.Disposals));
        }
        finally { body.ReleaseProbe.TrySetResult(); }
    }

    [Theory]
    [InlineData("permission", ErrorCode.PermissionDenied)]
    [InlineData("run", ErrorCode.SourceChanged)]
    [InlineData("authority-stop", ErrorCode.Canceled)]
    [InlineData("caller", ErrorCode.Canceled)]
    public async Task CachedBytesDoNotCrossAuthorityOrCallerCancellationFence(string change, ErrorCode expected)
    {
        await using var fixture = new Fixture(17);
        Assert.Equal(1, await fixture.Reader.ReadAsync(0, new byte[1], Token));
        using var caller = CancellationTokenSource.CreateLinkedTokenSource(Token);
        switch (change)
        {
            case "permission": fixture.AuthorityError = ErrorCode.PermissionDenied; break;
            case "run": fixture.AuthorityError = ErrorCode.SourceChanged; break;
            case "authority-stop": await fixture.AuthorityStop.CancelAsync(); break;
            case "caller": await caller.CancelAsync(); break;
        }
        byte[] buffer = Sentinel();
        await FailureAsync(expected, () => fixture.Reader.ReadAsync(0, buffer, caller.Token).AsTask());
        AssertUntouched(buffer);
        Assert.Single(fixture.Http.Requests);
        Assert.Equal(2, fixture.Sources.Observations);
        if (change == "caller") Assert.Equal(17, await fixture.Reader.ReadAsync(0, new byte[17], Token));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task CanceledOrDisposedAcquisitionOwnsLateLeaseUntilReleaseActuallyFinishes(bool dispose)
    {
        await using var fixture = new Fixture(17);
        using var caller = CancellationTokenSource.CreateLinkedTokenSource(Token);
        var acquired = new TaskCompletionSource<IDownloadLease>(TaskCreationOptions.RunContinuationsAsynchronously);
        var acquireEntered = Gate();
        var acquireCanceled = Gate();
        var releaseEntered = Gate();
        var releaseFinished = Gate();
        int acquisitions = 0;
        var late = new Lease(new(fixture.Sources.Source, 17)) { Release = async () =>
        {
            releaseEntered.TrySetResult();
            await releaseFinished.Task;
        } };
        fixture.Sources.Acquire = (request, token) =>
        {
            if (Interlocked.Increment(ref acquisitions) != 1) return Task.FromResult<IDownloadLease>(new Lease(request));
            token.UnsafeRegister(_ => acquireCanceled.TrySetResult(), null);
            acquireEntered.TrySetResult();
            return acquired.Task;
        };
        byte[] buffer = Sentinel();
        var read = fixture.Reader.ReadAsync(0, buffer, caller.Token).AsTask();
        await acquireEntered.Task.WaitAsync(TestTimeout, Token);
        var queued = fixture.Reader.ReadAsync(1, Sentinel(), Token).AsTask();
        Task? disposal = null;
        try
        {
            if (dispose)
            {
                disposal = fixture.Reader.DisposeAsync().AsTask();
                Assert.Same(disposal, fixture.Reader.DisposeAsync().AsTask());
            }
            else await caller.CancelAsync();
            await acquireCanceled.Task.WaitAsync(TestTimeout, Token);
            Assert.False(read.IsCompleted);
            if (dispose) Assert.False(disposal!.IsCompleted);
            acquired.TrySetResult(late);
            await releaseEntered.Task.WaitAsync(TestTimeout, Token);
            Assert.False(read.IsCompleted);
            if (dispose) Assert.False(disposal!.IsCompleted);
            releaseFinished.TrySetResult();
            await FailureAsync([ErrorCode.Canceled, ErrorCode.SourceChanged], () => read.WaitAsync(TestTimeout, Token));
            if (dispose)
            {
                await FailureAsync([ErrorCode.Canceled, ErrorCode.SourceChanged], () => queued.WaitAsync(TestTimeout, Token));
                await disposal!.WaitAsync(TestTimeout, Token);
                Assert.Equal(1, fixture.Http.Disposals);
                await FailureAsync(ErrorCode.SourceChanged, () => fixture.Reader.ReadAsync(0, buffer, Token).AsTask());
            }
            else
            {
                Assert.Equal(16, await queued.WaitAsync(TestTimeout, Token));
                byte[] fresh = new byte[17];
                Assert.Equal(17, await fixture.Reader.ReadAsync(0, fresh, Token));
                Assert.Equal(Pattern(0, 17), fresh);
                Assert.Equal(2, fixture.Http.Requests.Count);
            }
            Assert.Equal(1, late.Disposals);
            AssertUntouched(buffer);
        }
        finally { acquired.TrySetResult(late); releaseFinished.TrySetResult(); }
    }

    [Fact]
    public async Task DisposalDrainsCancellationCallbacksAndLateAcquisitionBeforeTransportDisposal()
    {
        await using var fixture = new Fixture(17);
        var callbackEntered = Gate();
        var callbackRelease = Gate();
        var acquireEntered = Gate();
        var acquired = new TaskCompletionSource<IDownloadLease>(TaskCreationOptions.RunContinuationsAsynchronously);
        var late = new Lease(new(fixture.Sources.Source, 17));
        fixture.Sources.Acquire = (_, token) =>
        {
            token.UnsafeRegister(_ =>
            {
                callbackEntered.TrySetResult();
                callbackRelease.Task.GetAwaiter().GetResult();
            }, null);
            acquireEntered.TrySetResult();
            return acquired.Task;
        };
        var read = fixture.Reader.ReadAsync(0, Sentinel(), Token).AsTask();
        await acquireEntered.Task.WaitAsync(TestTimeout, Token);
        var disposal = fixture.Reader.DisposeAsync().AsTask();
        try
        {
            await callbackEntered.Task.WaitAsync(TestTimeout, Token);
            acquired.TrySetResult(late);
            Assert.False(disposal.IsCompleted);
            Assert.Equal(0, fixture.Http.Disposals);
            callbackRelease.TrySetResult();
            await FailureAsync([ErrorCode.Canceled, ErrorCode.SourceChanged], () => read.WaitAsync(TestTimeout, Token));
            await disposal.WaitAsync(TestTimeout, Token);
            Assert.Equal(1, late.Disposals);
            Assert.Equal(1, fixture.Http.Disposals);
        }
        finally { acquired.TrySetResult(late); callbackRelease.TrySetResult(); }
    }

    [Theory]
    [InlineData("metadata")]
    [InlineData("acquisition")]
    [InlineData("headers")]
    [InlineData("body")]
    public async Task EveryNetworkStageHasBoundedCancellationBudgetAndSafeTimeout(string stage)
    {
        await using var fixture = new Fixture(17, TimeSpan.FromMilliseconds(50));
        if (stage == "metadata") fixture.Sources.Observe = async (_, token) =>
        {
            await Task.Delay(Timeout.InfiniteTimeSpan, token);
            return fixture.Sources.Metadata;
        };
        if (stage == "acquisition") fixture.Sources.Acquire = async (request, token) =>
        {
            await Task.Delay(Timeout.InfiniteTimeSpan, token);
            return new Lease(request);
        };
        if (stage == "headers") fixture.Http.SendOperation = async (_, token) =>
        {
            await Task.Delay(Timeout.InfiniteTimeSpan, token);
            throw new InvalidOperationException("Unreachable fixture continuation.");
        };
        if (stage == "body") fixture.Http.Body = (_, _) => new FailingReadStream(waitForCancellation: true);
        byte[] buffer = Sentinel();
        await FailureAsync(ErrorCode.Timeout, () => fixture.Reader.ReadAsync(0, buffer, Token).AsTask().WaitAsync(TestTimeout, Token));
        AssertUntouched(buffer);
        Assert.All(fixture.Sources.Leases, lease => Assert.Equal(1, lease.Disposals));
    }

    [Fact]
    public async Task ExpiredAcquisitionBudgetStillCapturesAndReleasesNoncooperativeLateLease()
    {
        await using var fixture = new Fixture(17, TimeSpan.FromMilliseconds(50));
        var canceled = Gate();
        var acquired = new TaskCompletionSource<IDownloadLease>(TaskCreationOptions.RunContinuationsAsynchronously);
        var late = new Lease(new(fixture.Sources.Source, 17));
        fixture.Sources.Acquire = (_, token) =>
        {
            token.UnsafeRegister(_ => canceled.TrySetResult(), null);
            return acquired.Task;
        };
        byte[] buffer = Sentinel();
        var read = fixture.Reader.ReadAsync(0, buffer, Token).AsTask();
        try
        {
            await canceled.Task.WaitAsync(TestTimeout, Token);
            Assert.False(read.IsCompleted);
            acquired.TrySetResult(late);
            await FailureAsync(ErrorCode.Timeout, () => read.WaitAsync(TestTimeout, Token));
            Assert.Equal(1, late.Disposals);
            Assert.Empty(fixture.Http.Requests);
            AssertUntouched(buffer);
        }
        finally { acquired.TrySetResult(late); }
    }

    [Theory]
    [InlineData("none")]
    [InlineData("caller")]
    [InlineData("authority")]
    public async Task FailedRemoteLeaseReleaseRemainsMaterialAfterReadDrainAndRepeatedDisposal(string cancellation)
    {
        var fixture = new Fixture(17);
        using var caller = CancellationTokenSource.CreateLinkedTokenSource(Token);
        var lease = new Lease(new(fixture.Sources.Source, 17))
        {
            Release = () => Task.FromException(new IOException("private cleanup " + Lease.Capability)),
        };
        fixture.Sources.Acquire = (_, _) => Task.FromResult<IDownloadLease>(lease);
        fixture.Sources.Observe = async (call, _) =>
        {
            if (call == 2)
            {
                if (cancellation == "caller") await caller.CancelAsync();
                if (cancellation == "authority") await fixture.AuthorityStop.CancelAsync();
            }
            return fixture.Sources.Metadata;
        };
        try
        {
            byte[] buffer = Sentinel();
            await FailureAsync(ErrorCode.OutcomeUnknown, () => fixture.Reader.ReadAsync(0, buffer, caller.Token).AsTask());
            var release = Assert.Throws<FilewayException>(() => fixture.Reader.RequireReleased());
            Assert.Equal(ErrorCode.OutcomeUnknown, release.Error.Code);
            AssertSafe(release);
            await FailureAsync(ErrorCode.OutcomeUnknown, () => fixture.Reader.DisposeAsync().AsTask());
            await FailureAsync(ErrorCode.OutcomeUnknown, () => fixture.Reader.DisposeAsync().AsTask());
            Assert.Equal(1, lease.Disposals);
            Assert.Equal(1, fixture.Http.Disposals);
            AssertUntouched(buffer);
        }
        finally { await FailureAsync(ErrorCode.OutcomeUnknown, () => fixture.DisposeAsync().AsTask()); }
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task BodyReleaseFailureTakesPriorityOverSuccessfulOrShortBodyRead(bool shortBody)
    {
        await using var fixture = new Fixture(17);
        var body = new AsyncDisposeFailureStream(Pattern(0, shortBody ? 16 : 17));
        fixture.Http.Body = (_, _) => body;
        byte[] buffer = Sentinel();

        await FailureAsync(ErrorCode.OutcomeUnknown, () => fixture.Reader.ReadAsync(0, buffer, Token).AsTask());

        AssertUntouched(buffer);
        Assert.Equal(1, body.AsyncDisposals);
        Assert.Equal(1, Assert.Single(fixture.Sources.Leases).Disposals);
        Assert.Equal(1, fixture.Sources.Observations);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task HeaderOrBodyTransportFailureIsSafeNetworkUnavailable(bool body)
    {
        await using var fixture = new Fixture(17);
        if (body) fixture.Http.Body = (_, _) => new FailingReadStream(waitForCancellation: false);
        else fixture.Http.SendOperation = (_, _) => Task.FromException<HttpResponseMessage>(new HttpRequestException("private-token " + Lease.Capability));
        byte[] buffer = Sentinel();
        await FailureAsync(ErrorCode.NetworkUnavailable, () => fixture.Reader.ReadAsync(0, buffer, Token).AsTask());
        AssertUntouched(buffer);
        Assert.Equal(1, Assert.Single(fixture.Sources.Leases).Disposals);
    }

    [Fact]
    public async Task ProviderFailureDetailsAreRedactedAndPermissionFailureIsTerminal()
    {
        await using var fixture = new Fixture(17);
        fixture.Sources.Observe = (_, _) => Task.FromException<RemoteFile>(new FilewayException(
            new(ErrorCode.PermissionDenied, "private-token " + Lease.Capability + fixture.Sources.Source.WirePath, 403)));
        var error = await FailureAsync(ErrorCode.PermissionDenied, () => fixture.Reader.ReadAsync(0, Sentinel(), Token).AsTask());
        Assert.Equal(403, error.Error.HttpStatus);
        await FailureAsync(ErrorCode.PermissionDenied, () => fixture.Reader.ReadAsync(0, Sentinel(), Token).AsTask());
        Assert.Equal(1, fixture.Sources.Observations);
        Assert.Empty(fixture.Http.Requests);
    }

    private static RemoteFile Change(RemoteFile file, string field) => field switch
    {
        "account" => file with { Resource = file.Resource with { Account = file.Resource.Account with { ProfileId = Guid.NewGuid() } } },
        "revision" => file with { Resource = file.Resource with { Account = file.Resource.Account with { SourceRevision = Guid.NewGuid() } } },
        "user" => file with { Resource = file.Resource with { Account = file.Resource.Account with { ServerUserId = "private-other-user" } } },
        "opaque-path" => file with { Resource = file.Resource with { WirePath = "/private/media%20Name.bin" } },
        "length" => file with { Size = file.Size + 1 },
        "full-modified" => file with { Modified = Modified.AddTicks(1) },
        "missing-modified" => file with { Modified = null },
        "directory" => file with { IsDirectory = true },
        "version" => file with { Resource = file.Resource with { SourceVersion = "private-version" } },
        _ => throw new ArgumentOutOfRangeException(nameof(field)),
    };

    private static TaskCompletionSource Gate() => new(TaskCreationOptions.RunContinuationsAsynchronously);
    private static byte[] Sentinel() => Enumerable.Repeat((byte)0xa5, 17).ToArray();
    private static void AssertUntouched(byte[] buffer) => Assert.All(buffer, value => Assert.Equal(0xa5, value));
    private static byte[] Pattern(long offset, int count) => Enumerable.Range(0, count).Select(index => (byte)((offset + index) % 251)).ToArray();
    private static void Raw(HttpHeaders headers, string name, params string[] values)
    {
        headers.Remove(name);
        Assert.True(headers.TryAddWithoutValidation(name, values));
    }

    private static void ReplaceBody(HttpResponseMessage response, byte[] bytes)
    {
        var range = response.Content.Headers.ContentRange;
        var length = response.Content.Headers.ContentLength;
        response.Content.Dispose();
        response.Content = new StreamContent(new MemoryStream(bytes, writable: false));
        response.Content.Headers.ContentRange = range;
        response.Content.Headers.ContentLength = length;
        response.Content.Headers.LastModified = HttpModified;
    }

    private static Task<FilewayException> FailureAsync(ErrorCode code, Func<Task> action) => FailureAsync([code], action);
    private static async Task<FilewayException> FailureAsync(ErrorCode[] codes, Func<Task> action)
    {
        var error = await Assert.ThrowsAsync<FilewayException>(action);
        Assert.Contains(error.Error.Code, codes);
        AssertSafe(error);
        return error;
    }

    private static void AssertSafe(FilewayException error)
    {
        Assert.DoesNotContain("private", error.Message, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("127.0.0.1", error.Message, StringComparison.Ordinal);
        Assert.DoesNotContain("/stream/", error.Message, StringComparison.Ordinal);
        Assert.Null(error.InnerException);
    }

    private sealed class Fixture : IAsyncDisposable
    {
        private int _disposed;
        internal Fixture(long length, TimeSpan? timeout = null, string wire = "/private/media%20name.bin")
        {
            Sources = new(length, wire);
            Http = new(length);
            Reader = new(Sources, Sources.Source, new(length, Modified), () =>
            {
                if (AuthorityError is { } error) throw new FilewayException(new(error, "private authority detail"));
            }, new DownloadPlaybackRangeReaderOptions(timeout ?? TimeSpan.FromSeconds(30)), Http, AuthorityStop.Token);
        }

        internal Sources Sources { get; }
        internal Handler Http { get; }
        internal DownloadPlaybackRangeReader Reader { get; }
        internal CancellationTokenSource AuthorityStop { get; } = new();
        internal ErrorCode? AuthorityError { get; set; }
        public async ValueTask DisposeAsync()
        {
            try { await Reader.DisposeAsync(); }
            finally
            {
                if (Interlocked.Exchange(ref _disposed, 1) == 0) AuthorityStop.Dispose();
            }
        }
    }

    private sealed class Sources(long length, string wire = "/private/media%20name.bin") : IDownloadSourceProvider
    {
        private int _observations;
        private int _checksums;
        internal RemoteResourceRef Source { get; } = new(new(Guid.NewGuid(), Guid.NewGuid(), "fixture-user"),
            wire, "fixture.bin", "/fixture.bin");
        internal RemoteFile Metadata => new(Source, false, length, Modified);
        internal int Observations => Volatile.Read(ref _observations);
        internal int Checksums => Volatile.Read(ref _checksums);
        internal ConcurrentQueue<Lease> Leases { get; } = new();
        internal Func<int, CancellationToken, Task<RemoteFile>>? Observe { get; set; }
        internal Func<DownloadLeaseRequest, CancellationToken, Task<IDownloadLease>>? Acquire { get; set; }
        internal Action<Lease>? MutateLease { get; set; }
        public Task<RemoteFile> PrepareDownloadAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
        {
            Assert.Equal(Source.Account, resource.Account);
            Assert.Equal(Source.WirePath, resource.WirePath);
            int call = Interlocked.Increment(ref _observations);
            return Observe?.Invoke(call, cancellationToken) ?? Task.FromResult(Metadata);
        }

        public Task<IDownloadLease> AcquireDownloadAsync(DownloadLeaseRequest request, CancellationToken cancellationToken)
        {
            Assert.Equal(length, request.Length);
            Assert.Same(Source, request.Source);
            if (Acquire is not null) return Acquire(request, cancellationToken);
            var lease = new Lease(request);
            MutateLease?.Invoke(lease);
            Leases.Enqueue(lease);
            return Task.FromResult<IDownloadLease>(lease);
        }

        public Task<FileChecksumResult> CalculateDownloadChecksumAsync(DownloadLeaseRequest request, CancellationToken cancellationToken)
        {
            Interlocked.Increment(ref _checksums);
            throw new InvalidOperationException("Playback range reads cannot request writer verification.");
        }
    }

    private sealed class Lease(DownloadLeaseRequest request) : IDownloadLease
    {
        internal const string Capability = "http://127.0.0.1:41234/stream/ABCabc012-_";
        private int _disposals;
        public RemoteResourceRef Resource { get; set; } = request.Source;
        public Uri Address { get; set; } = new(Capability);
        public long Length { get; set; } = request.Length;
        public string? SourceVersion { get; set; }
        public bool SupportsResume { get; set; }
        internal int Disposals => Volatile.Read(ref _disposals);
        internal Func<Task>? Release { get; init; }
        public async ValueTask DisposeAsync()
        {
            Assert.Equal(1, Interlocked.Increment(ref _disposals));
            if (Release is not null) await Release();
        }
    }

    private sealed record Request(Uri? Address, string? Range, string? IfRange, string? Encoding);

    private sealed class Handler(long length) : HttpMessageHandler
    {
        private int _disposals;
        internal ConcurrentQueue<Request> Requests { get; } = new();
        internal Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>>? SendOperation { get; set; }
        internal Func<long, int, Stream>? Body { get; set; }
        internal Action<HttpResponseMessage>? Mutate { get; set; }
        internal int Disposals => Volatile.Read(ref _disposals);
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Requests.Enqueue(new(request.RequestUri, request.Headers.Range?.ToString(), request.Headers.IfRange?.ToString(),
                request.Headers.AcceptEncoding.ToString()));
            if (SendOperation is not null) return SendOperation(request, cancellationToken);
            var range = Assert.Single(request.Headers.Range!.Ranges);
            long start = range.From!.Value;
            long end = range.To!.Value;
            int count = checked((int)(end - start + 1));
            var content = new StreamContent(Body?.Invoke(start, count) ?? new MemoryStream(Pattern(start, count), writable: false));
            content.Headers.ContentRange = new(start, end, length);
            content.Headers.ContentLength = count;
            content.Headers.LastModified = HttpModified;
            content.Headers.ContentType = new("application/octet-stream");
            var response = new HttpResponseMessage(HttpStatusCode.PartialContent) { Content = content };
            Mutate?.Invoke(response);
            return Task.FromResult(response);
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing) Interlocked.Increment(ref _disposals);
            base.Dispose(disposing);
        }
    }

    private sealed class ProbeGateStream(byte[] bytes) : MemoryStream(bytes, writable: false)
    {
        internal TaskCompletionSource ProbeEntered { get; } = Gate();
        internal TaskCompletionSource ReleaseProbe { get; } = Gate();
        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            if (Position < Length) return await base.ReadAsync(buffer, cancellationToken);
            ProbeEntered.TrySetResult();
            await ReleaseProbe.Task.WaitAsync(cancellationToken);
            return 0;
        }
    }

    private sealed class FailingReadStream(bool waitForCancellation) : MemoryStream
    {
        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            if (waitForCancellation) await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
            throw new IOException("private-token " + Lease.Capability);
        }
    }

    private sealed class AsyncDisposeFailureStream(byte[] bytes) : MemoryStream(bytes, writable: false)
    {
        private int _asyncDisposals;
        internal int AsyncDisposals => Volatile.Read(ref _asyncDisposals);
        public override async ValueTask DisposeAsync()
        {
            Interlocked.Increment(ref _asyncDisposals);
            await base.DisposeAsync();
            throw new IOException("private cleanup " + Lease.Capability);
        }
    }
}
