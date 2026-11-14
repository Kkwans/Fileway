using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.ProgressivePlaybackHarness;

/// <summary>Measures the production owned reader without replacing reads, waits, or cancellation.</summary>
internal sealed class ObservedReadLease(IDownloadReadLease inner) : IDownloadReadLease
{
    private int _pendingBoundaryReads, _completedBoundaryReads, _canceledBoundaryReads, _disposed;
    private long _lastBoundaryOffset = -1, _bytesRead;
    public TransferRunToken Run => inner.Run;
    public long TotalBytes => inner.TotalBytes;
    public long ReadablePrefixBytes => inner.ReadablePrefixBytes;
    internal int PendingBoundaryReads => Volatile.Read(ref _pendingBoundaryReads);
    internal int CompletedBoundaryReads => Volatile.Read(ref _completedBoundaryReads);
    internal int CanceledBoundaryReads => Volatile.Read(ref _canceledBoundaryReads);
    internal bool IsDisposed => Volatile.Read(ref _disposed) != 0;
    internal long LastBoundaryOffset => Interlocked.Read(ref _lastBoundaryOffset);
    internal long BytesRead => Interlocked.Read(ref _bytesRead);

    public async ValueTask<int> ReadAsync(long offset, Memory<byte> buffer, CancellationToken cancellationToken)
    {
        long prefix = ReadablePrefixBytes;
        ValueTask<int> read = inner.ReadAsync(offset, buffer, cancellationToken);
        bool waiting = !read.IsCompleted && offset >= prefix && offset < TotalBytes && buffer.Length > 0;
        if (waiting)
        {
            Interlocked.Exchange(ref _lastBoundaryOffset, offset);
            Interlocked.Increment(ref _pendingBoundaryReads);
        }
        try
        {
            int count = await read.ConfigureAwait(false);
            Interlocked.Add(ref _bytesRead, count);
            if (waiting) Interlocked.Increment(ref _completedBoundaryReads);
            return count;
        }
        catch (OperationCanceledException)
        {
            if (waiting) Interlocked.Increment(ref _canceledBoundaryReads);
            throw;
        }
        finally { if (waiting) Interlocked.Decrement(ref _pendingBoundaryReads); }
    }

    public async ValueTask DisposeAsync()
    {
        await inner.DisposeAsync().ConfigureAwait(false);
        Volatile.Write(ref _disposed, 1);
    }
}
