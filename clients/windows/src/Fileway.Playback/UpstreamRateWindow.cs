using System.Diagnostics;

namespace Fileway.Playback;

/// <summary>Time-weighted upstream byte deltas over at most three monotonic observations.</summary>
internal sealed class UpstreamRateWindow
{
    private readonly Queue<(long Bytes, long Timestamp)> _samples = new();

    internal void Reset() => _samples.Clear();

    internal double? Observe(long upstreamBytes, long timestamp)
    {
        if (upstreamBytes < 0) { Reset(); return null; }
        if (_samples.Count > 0)
        {
            var previous = _samples.Last();
            if (upstreamBytes < previous.Bytes || timestamp <= previous.Timestamp)
            {
                Reset();
                _samples.Enqueue((upstreamBytes, timestamp));
                return null;
            }
        }

        long? previousBytes = _samples.Count > 0 ? _samples.Last().Bytes : null;
        _samples.Enqueue((upstreamBytes, timestamp));
        while (_samples.Count > 3) _samples.Dequeue();
        if (_samples.Count < 2) return null;
        // A cache hit (or idle source) must not retain a stale network speed.
        if (previousBytes == upstreamBytes) return 0;
        var first = _samples.Peek();
        double elapsed = Stopwatch.GetElapsedTime(first.Timestamp, timestamp).TotalSeconds;
        double rate = (upstreamBytes - first.Bytes) / elapsed;
        return double.IsFinite(rate) && rate >= 0 ? rate : null;
    }
}
