using System.Globalization;
using System.Text;
using Fileway.HostTestHarness;

namespace Fileway.Infrastructure.Tests.Ipc;

public sealed class PidReceiptTests
{
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Fact]
    public async Task OpenPrivateWriterDoesNotPublishAnUnreadableOrPartialReceipt()
    {
        using var fixture = new ReceiptFixture();
        var opened = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        Task publish = PidReceipt.PublishAsync(fixture.Path, 12345, async (path, text, token) =>
        {
            await using var writer = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None, 4096, useAsync: true);
            await writer.WriteAsync(Encoding.UTF8.GetBytes(text[..1]), token);
            opened.SetResult();
            await release.Task.WaitAsync(token);
            await writer.WriteAsync(Encoding.UTF8.GetBytes(text[1..]), token);
        }, Token);
        try
        {
            await opened.Task.WaitAsync(TimeSpan.FromSeconds(5), Token);
            Assert.False(File.Exists(fixture.Path));
            Assert.False(publish.IsCompleted);
            Assert.Single(Directory.GetFiles(fixture.Directory));
        }
        finally
        {
            release.TrySetResult();
            await publish.WaitAsync(TimeSpan.FromSeconds(5), Token);
        }
        for (int attempt = 0; attempt < 10; ++attempt)
            Assert.Equal("12345", await File.ReadAllTextAsync(fixture.Path, Token));
        Assert.Equal([fixture.Path], Directory.GetFiles(fixture.Directory));
    }

    [Fact]
    public async Task ActualWriterPublishesImmediatelyReadableInvariantPid()
    {
        using var fixture = new ReceiptFixture();
        await PidReceipt.PublishAsync(fixture.Path, 23456, Token);
        Assert.Equal(23456, int.Parse(await File.ReadAllTextAsync(fixture.Path, Token), CultureInfo.InvariantCulture));
        Assert.Equal([fixture.Path], Directory.GetFiles(fixture.Directory));
    }

    [Fact]
    public async Task ExistingReceiptIsNeverOverwritten()
    {
        using var fixture = new ReceiptFixture();
        await File.WriteAllTextAsync(fixture.Path, "98765", Token);
        await Assert.ThrowsAsync<IOException>(() => PidReceipt.PublishAsync(fixture.Path, 12345, Token));
        Assert.Equal("98765", await File.ReadAllTextAsync(fixture.Path, Token));
        Assert.Equal([fixture.Path], Directory.GetFiles(fixture.Directory));
    }

    [Fact]
    public async Task FailedPrivateWriteNeverPublishesAndItsFailureIsNotSwallowed()
    {
        using var fixture = new ReceiptFixture();
        var failure = new IOException("synthetic writer failure");
        var actual = await Assert.ThrowsAsync<IOException>(() => PidReceipt.PublishAsync(fixture.Path, 12345,
            async (path, text, token) =>
            {
                await File.WriteAllTextAsync(path, text, token);
                throw failure;
            }, Token));
        Assert.Same(failure, actual);
        Assert.False(File.Exists(fixture.Path));
        Assert.Empty(Directory.GetFiles(fixture.Directory));
    }

    [Fact]
    public async Task CancellationBeforeOrAfterPrivateWriteNeverPublishes()
    {
        using var fixture = new ReceiptFixture();
        using var cancellation = CancellationTokenSource.CreateLinkedTokenSource(Token);
        cancellation.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => PidReceipt.PublishAsync(fixture.Path, 12345, cancellation.Token));
        Assert.Empty(Directory.GetFiles(fixture.Directory));
        using var afterWrite = CancellationTokenSource.CreateLinkedTokenSource(Token);
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => PidReceipt.PublishAsync(fixture.Path, 12345,
            async (path, text, token) =>
            {
                await File.WriteAllTextAsync(path, text, token);
                afterWrite.Cancel();
            }, afterWrite.Token));
        Assert.False(File.Exists(fixture.Path));
        Assert.Empty(Directory.GetFiles(fixture.Directory));
    }

    [Fact]
    public async Task ConcurrentPublishHasExactlyOneWinnerAndNoPartialReceipt()
    {
        using var fixture = new ReceiptFixture();
        var ready = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var writers = 0;
        async Task Write(string path, string text, CancellationToken token)
        {
            await File.WriteAllTextAsync(path, text, token);
            if (Interlocked.Increment(ref writers) == 2) ready.SetResult();
            await ready.Task.WaitAsync(token);
        }
        Task first = PidReceipt.PublishAsync(fixture.Path, 12345, Write, Token);
        Task second = PidReceipt.PublishAsync(fixture.Path, 23456, Write, Token);
        await Assert.ThrowsAsync<IOException>(() => Task.WhenAll(first, second).WaitAsync(TimeSpan.FromSeconds(5), Token));
        Assert.Equal(1, new[] { first, second }.Count(task => task.IsCompletedSuccessfully));
        Assert.True(await File.ReadAllTextAsync(fixture.Path, Token) is "12345" or "23456");
        Assert.Equal([fixture.Path], Directory.GetFiles(fixture.Directory));
    }

    private sealed class ReceiptFixture : IDisposable
    {
        internal string Directory { get; } = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "FilewayPidReceiptTests-" + Guid.NewGuid().ToString("N"));
        internal string Path => System.IO.Path.Combine(Directory, "owned.pid");
        internal ReceiptFixture() => System.IO.Directory.CreateDirectory(Directory);
        public void Dispose() => System.IO.Directory.Delete(Directory, recursive: true);
    }
}
