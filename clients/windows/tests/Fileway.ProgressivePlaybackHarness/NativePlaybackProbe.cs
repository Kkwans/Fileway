using System.Diagnostics;
using System.Text.Json;
using Fileway.Playback.Native;

namespace Fileway.ProgressivePlaybackHarness;

/// <summary>Owns real libmpv, its event thread and the production protocol bridge in native lifetime order.</summary>
internal sealed class NativePlaybackProbe : IAsyncDisposable
{
    private readonly MpvNative _native;
    private readonly MpvDownloadStreamBridge _bridge;
    private readonly CancellationTokenSource _eventsStop = new();
    private readonly object _gate = new();
    private nint _handle;
    private Task _events = Task.CompletedTask;
    private Task<TimeSpan>? _destroy;
    private double _position = -1;
    private int _width, _height, _videoReconfigurations, _positionChanges, _commandError, _endReason = -1;
    private bool _pause, _fileLoaded, _nativeDestroyed;
    private ulong _commandId;

    internal NativePlaybackProbe(string runtime, ObservedReadLease reader)
    {
        _native = new MpvNative(runtime);
        _bridge = new MpvDownloadStreamBridge(reader);
    }

    internal void Start()
    {
        _handle = _native.Create();
        HarnessInputs.Require(_handle != 0, "native-create-failed");
        // These are ordinary per-instance playback options. No CFG, process
        // mitigation, system policy, or loader fallback is changed by this test.
        foreach (var (name, value) in new (string, string)[]
        {
            ("config", "no"), ("load-scripts", "no"), ("terminal", "no"), ("msg-level", "all=no"),
            ("input-default-bindings", "no"), ("vo", "null"), ("ao", "null"), ("audio", "no"),
            ("hwdec", "no"), ("idle", "yes"), ("keep-open", "yes"), ("pause", "no"),
            ("speed", "2"), ("cache", "no"), ("demuxer-readahead-secs", "0.25"),
            ("demuxer-max-bytes", "1048576"), ("demuxer-lavf-format", "yuv4mpegpipe"),
        }) _native.Option(_handle, name, value);
        _bridge.Register(_native, _handle);
        _native.Initialize(_handle);
        _native.Observe(_handle, 1, "time-pos", 5);
        _native.Observe(_handle, 2, "pause", 3);
        _native.Observe(_handle, 3, "video-params", 6);
        _events = Task.Run(PumpEvents);
        _native.Command(_handle, ++_commandId, ["loadfile", _bridge.Uri, "replace"]);
    }

    internal NativeSnapshot Snapshot()
    {
        lock (_gate) return new NativeSnapshot(_position, _width, _height, _videoReconfigurations,
            _positionChanges, _pause, _fileLoaded, _commandError, _endReason);
    }

    internal void Pause(bool paused) => _native.Command(_handle, ++_commandId, ["set", "pause", paused ? "yes" : "no"]);
    internal void CancelReaders() => _bridge.CancelBeforeDestroy();

    private void PumpEvents()
    {
        while (!_eventsStop.IsCancellationRequested)
        {
            var item = _native.Wait(_handle);
            lock (_gate)
            {
                if (item.Id == 5 && item.Error < 0) _commandError = item.Error;
                if (item.Id == 7) _endReason = item.EndReason;
                if (item.Id == 8) _fileLoaded = true;
                if (item.Id == 17) _videoReconfigurations++;
                if (item.Property is { Name: "time-pos", Value: double position })
                {
                    if (position > _position) _positionChanges++;
                    _position = position;
                }
                if (item.Property is { Name: "pause", Value: bool pause }) _pause = pause;
                if (item.Property is { Name: "video-params", Value: JsonElement video } && video.ValueKind == JsonValueKind.Object)
                {
                    if (video.TryGetProperty("w", out var width)) _width = width.GetInt32();
                    if (video.TryGetProperty("h", out var height)) _height = height.GetInt32();
                }
            }
        }
    }

    internal Task<TimeSpan> DestroyAsync() => _destroy ??= DestroyCoreAsync();

    private async Task<TimeSpan> DestroyCoreAsync()
    {
        if (_nativeDestroyed) return TimeSpan.Zero;
        var watch = Stopwatch.StartNew();
        _bridge.CancelBeforeDestroy();
        await _eventsStop.CancelAsync().ConfigureAwait(false);
        if (_handle != 0) _native.Wake(_handle);
        await _events.WaitAsync(TimeSpan.FromSeconds(5)).ConfigureAwait(false);
        if (_handle != 0)
        {
            // Never free rooted callbacks or unload the DLL until the REAL
            // terminate_destroy has returned. The outer process deadline bounds
            // a stuck native call; timeout is failure, never a successful detach.
            await Task.Run(() => _native.Destroy(_handle)).WaitAsync(TimeSpan.FromSeconds(15)).ConfigureAwait(false);
            _handle = 0;
        }
        _nativeDestroyed = true;
        await _bridge.ReleaseAfterDestroyAsync().WaitAsync(TimeSpan.FromSeconds(5)).ConfigureAwait(false);
        return watch.Elapsed;
    }

    public async ValueTask DisposeAsync()
    {
        await DestroyAsync().ConfigureAwait(false);
        if (_nativeDestroyed)
        {
            _eventsStop.Dispose();
            _native.Dispose();
        }
    }
}

internal sealed record NativeSnapshot(double Position, int Width, int Height, int VideoReconfigurations,
    int PositionChanges, bool Paused, bool FileLoaded, int CommandError, int EndReason);
