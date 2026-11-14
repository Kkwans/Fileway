using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Headers;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Services;

namespace Fileway.Infrastructure.Tests.Services;

public sealed class DownloadPlaybackConditionalRangeReaderTests
{
    private static readonly DateTimeOffset Modified = new(2026, 10, 13, 1, 2, 3, TimeSpan.Zero);
    private const string Version = "v1-conditional-fixture";

    [Fact]
    public async Task ConditionalCapabilityPinsVersionOnMetadataLeaseAndRangeResponse()
    {
        var sources = new Sources(32);
        var handler = new Handler(32, includeVersion: true);
        await using var reader = new DownloadPlaybackRangeReader(sources, sources.Source, new(32, Modified), () => { },
            CancellationToken.None, handler);

        byte[] bytes = new byte[32];
        Assert.Equal(32, await reader.ReadAsync(0, bytes, CancellationToken.None));
        Assert.Equal(Pattern(0, 32), bytes);
        Assert.Equal(Version, sources.Acquired.Single().SourceVersion);
        Assert.Equal("bytes=0-31", Assert.Single(handler.Requests).Range);
        Assert.Equal(2, sources.Observations);
    }

    [Fact]
    public async Task ConditionalRangeWithoutMatchingResponseVersionDoesNotPublishBytes()
    {
        var sources = new Sources(32);
        var handler = new Handler(32, includeVersion: false);
        await using var reader = new DownloadPlaybackRangeReader(sources, sources.Source, new(32, Modified), () => { },
            CancellationToken.None, handler);

        byte[] bytes = Enumerable.Repeat((byte)0xa5, 32).ToArray();
        var error = await Assert.ThrowsAsync<FilewayException>(() => reader.ReadAsync(0, bytes, CancellationToken.None).AsTask());
        Assert.Equal(ErrorCode.ProtocolMismatch, error.Error.Code);
        Assert.All(bytes, value => Assert.Equal(0xa5, value));
    }

    private static byte[] Pattern(long offset, int count) =>
        Enumerable.Range(0, count).Select(index => (byte)((offset + index) % 251)).ToArray();

    private sealed class Sources(long length) : IDownloadSourceProvider, IConditionalDownloadSourceProvider
    {
        internal RemoteResourceRef Source { get; } = new(new(Guid.NewGuid(), Guid.NewGuid(), "fixture-user"),
            "/private/conditional.bin", "conditional.bin", "/conditional.bin");
        internal ConcurrentQueue<ConditionalDownloadLeaseRequest> Acquired { get; } = new();
        private int _observations;
        internal int Observations => Volatile.Read(ref _observations);

        public bool CanUseConditionalDownload(RemoteResourceRef resource) => true;

        public Task<RemoteFile> PrepareConditionalDownloadAsync(RemoteResourceRef resource,
            CancellationToken cancellationToken)
        {
            Assert.Equal(Source, resource);
            Interlocked.Increment(ref _observations);
            return Task.FromResult(new RemoteFile(Source with { SourceVersion = Version }, false, length, Modified));
        }

        public Task<IDownloadLease> AcquireConditionalDownloadAsync(ConditionalDownloadLeaseRequest request,
            CancellationToken cancellationToken)
        {
            Assert.Equal(length, request.Length);
            Assert.Equal(Source.WirePath, request.Source.WirePath);
            Assert.Equal(Version, request.SourceVersion);
            Acquired.Enqueue(request);
            return Task.FromResult<IDownloadLease>(new Lease(request));
        }

        public Task<RemoteFile> PrepareDownloadAsync(RemoteResourceRef resource, CancellationToken cancellationToken) =>
            throw new InvalidOperationException("Conditional fixture must not use fresh metadata.");

        public Task<IDownloadLease> AcquireDownloadAsync(DownloadLeaseRequest request, CancellationToken cancellationToken) =>
            throw new InvalidOperationException("Conditional fixture must not use fresh lease acquisition.");

        public Task<FileChecksumResult> CalculateDownloadChecksumAsync(DownloadLeaseRequest request,
            CancellationToken cancellationToken) => throw new InvalidOperationException("Not used by playback.");
    }

    private sealed class Lease(ConditionalDownloadLeaseRequest request) : IDownloadLease
    {
        public RemoteResourceRef Resource { get; } = request.Source;
        public Uri Address { get; } = new("http://127.0.0.1:41234/stream/conditional");
        public long Length { get; } = request.Length;
        public string? SourceVersion { get; } = request.SourceVersion;
        public bool SupportsResume => false;
        public ValueTask DisposeAsync() => ValueTask.CompletedTask;
    }

    private sealed record Request(string? Range);

    private sealed class Handler(long length, bool includeVersion) : HttpMessageHandler
    {
        internal ConcurrentQueue<Request> Requests { get; } = new();

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            Requests.Enqueue(new(request.Headers.Range?.ToString()));
            var range = Assert.Single(request.Headers.Range!.Ranges);
            long start = range.From!.Value;
            long end = range.To!.Value;
            int count = checked((int)(end - start + 1));
            var content = new ByteArrayContent(Pattern(start, count));
            content.Headers.ContentRange = new ContentRangeHeaderValue(start, end, length);
            content.Headers.ContentLength = count;
            content.Headers.LastModified = Modified;
            content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
            var response = new HttpResponseMessage(HttpStatusCode.PartialContent) { Content = content };
            if (includeVersion) response.Headers.TryAddWithoutValidation("X-Fileway-Source-Version", Version);
            return Task.FromResult(response);
        }
    }
}
