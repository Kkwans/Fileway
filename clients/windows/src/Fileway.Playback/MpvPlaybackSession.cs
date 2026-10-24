using System.Collections.Concurrent;
using System.Diagnostics;
using System.Globalization;
using System.Net;
using System.Runtime.CompilerServices;
using System.Text.Json;
using System.Threading.Channels;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Playback.Contracts;
using Fileway.Playback.Native;

namespace Fileway.Playback;

internal sealed class MpvPlaybackSession : IPlaybackSession, IPlaybackResourceSource, ISubtitleViewportControl
{
    RemoteResourceRef? IPlaybackResourceSource.Resource => _originalResource;
    private static readonly TimeSpan NativeDeadline = TimeSpan.FromSeconds(10);
    private readonly object _gate = new();
    private readonly PlaybackOpenRequest _request;
    private readonly MpvNative _native;
    private readonly IResourceLeaseProvider _leases;
    private readonly IPlaybackSurfaceFactory _surfaces;
    private readonly Action<Guid> _released;
    private readonly IRecentAccessService? _recentAccess;
    private readonly RemoteResourceRef? _originalResource;
    private readonly MpvDownloadStreamBridge? _downloadBridge;
    private readonly DownloadPlaybackObservation? _downloadObservation;
    private RecentPlaybackOpenRecording? _recentOpen;
    private readonly List<IResourceLease> _ownedLeases = [];
    private readonly List<Channel<PlaybackSnapshot>> _watchers = [];
    private readonly Channel<PlaybackCommand> _commands = Channel.CreateBounded<PlaybackCommand>(64);
    private readonly ConcurrentDictionary<ulong, TaskCompletionSource> _replies = new();
    private readonly TaskCompletionSource _ready = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly CancellationTokenSource _pumpStop = new();
    private readonly CancellationTokenSource _startStop = new();
    private readonly CancellationTokenSource _statisticsStop = new();
    private readonly Task _commandPump;
    private PlaybackSnapshot _snapshot;
    private IPlaybackSurface? _surface;
    private Task? _start, _stop, _events;
    private Task? _statistics;
    private IResourceLease? _primaryLease;
    private JsonElement? _cacheState;
    private nint _handle;
    private long _nextCommand;
    private bool _closing, _paused, _seeking, _buffering, _usable, _ended;
    private bool _nativeFullscreen, _hostFullscreen;
    private SubtitlePreferences _subtitlePreferences = new();
    private double _subtitleBottomInset;
    private bool _scriptSubtitleSelected;
    private bool _bitmapSubtitleSelected;
    private double _subtitleViewportAspect;
    private double? _lastViewportSubtitlePosition;

    private sealed record PlaybackCommand(string[][] Arguments, TaskCompletionSource Completion, CancellationToken Cancellation);

    internal MpvPlaybackSession(PlaybackOpenRequest request, long generation, MpvNative native, IResourceLeaseProvider leases,
        IPlaybackSurfaceFactory surfaces, Action<Guid> released, IRecentAccessService? recentAccess = null,
        DownloadPlaybackSource? download = null, DownloadPlaybackObservation? downloadObservation = null)
    {
        _request = request;
        _native = native;
        _leases = leases;
        _surfaces = surfaces;
        _released = released;
        _recentAccess = recentAccess;
        _originalResource = (request.Input as RemotePlaybackInput)?.Resource ?? download?.Item.Source;
        _downloadObservation = downloadObservation;
        string displayName = request.Input switch { RemotePlaybackInput remote => remote.Resource.DisplayName, DownloadPlaybackInput when download is not null => download.Item.Source.DisplayName, LocalPlaybackInput local => local.DisplayName, _ => "媒体" };
        _snapshot = new PlaybackSnapshot(Id, generation, displayName, PlaybackStatus.Preparing, TimeSpan.Zero, null, null, false,
            Array.Empty<MediaTrack>(), new SubtitleCapabilities(true, true, true, true, true), new PlaybackDiagnostics(),
            Download: downloadObservation?.Snapshot, Origin: _originalResource);
        // Keep the exact admitted reader; never reopen a partial path or acquire a
        // remote HTTP lease for a download playback input.
        if (download is not null) _downloadBridge = new MpvDownloadStreamBridge(download.Reader);
        _commandPump = Task.Run(CommandPumpAsync);
    }

    public Guid Id { get; } = Guid.NewGuid();
    internal IPlaybackSession? PresentationSession { get; set; }
    internal AccountKey? Account => _originalResource?.Account ?? (_request.Input as LocalPlaybackInput)?.Origin?.Account;
    public PlaybackSnapshot Snapshot { get { lock (_gate) return _snapshot; } }

    internal async Task StartAsync(CancellationToken cancellationToken)
    {
        Task start;
        lock (_gate)
        {
            if (_closing) throw MpvNative.Error(ErrorCode.Canceled, "播放会话已关闭。");
            _start ??= StartCoreAsync(cancellationToken);
            start = _start;
        }
        await start.ConfigureAwait(false);
    }

    private async Task StartCoreAsync(CancellationToken cancellationToken)
    {
        if (_recentAccess is not null && _originalResource is not null)
            _recentOpen = new RecentPlaybackOpenRecording(_recentAccess, _originalResource, Id, _snapshot.Generation,
                cancellationToken, _startStop.Token);
        using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, _startStop.Token);
        cancellationToken = lifetime.Token;
        try
        {
            _downloadObservation?.Start(state => Publish(snapshot => snapshot with { Download = state }));
            string source = await SourceAsync(_request.Input, cancellationToken, primary: true).ConfigureAwait(false);
            _surface = await _surfaces.CreateAsync(PresentationSession ?? this, cancellationToken).ConfigureAwait(false);
            if (_surface.ParentHwnd == 0) throw MpvNative.Error(ErrorCode.CapabilityMissing, "播放器视频容器尚未就绪。");
            if (_surface is IPlaybackWindowControl window) window.FullscreenChanged += HostFullscreenChanged;
            await Task.Run(() =>
            {
                _handle = _native.Create();
                if (_handle == 0) throw MpvNative.Error(ErrorCode.CapabilityMissing, "无法创建播放器引擎。");
                foreach (var option in new (string Name, string Value)[]
                {
                    ("config", "no"), ("terminal", "no"), ("msg-level", "all=no"), ("load-scripts", "no"),
                    // Native-host control mode. Retain uosc assets as a candidate,
                    // but never load a second production HUD or input profile.
                    ("scripts", ""), ("osd-level", "0"), ("osd-bar", "no"),
                    ("ytdl", "no"), ("osc", "no"), ("load-stats-overlay", "no"),
                    ("input-default-bindings", "no"), ("input-builtin-bindings", "no"), ("input-vo-keyboard", "no"),
                    ("input-cursor", "no"), ("access-references", "no"), ("autoload-files", "no"),
                    ("cursor-autohide", "4000"), ("cursor-autohide-fs-only", "no"),
                    ("load-unsafe-playlists", "no"), ("idle", "yes"), ("keep-open", "yes"),
                    ("title", "Fileway video"), ("force-media-title", _snapshot.DisplayName.Replace("\0", "", StringComparison.Ordinal)),
                    ("vo", "gpu-next"), ("gpu-context", "d3d11"), ("d3d11-output-mode", "window"),
                    ("hwdec", "auto-safe"), ("audio-client-name", "Fileway"), ("network-timeout", "15"),
                // 100% equals the system mixer level; 100–200% is engine-side amplification and
                // stays independent of the Windows volume. Boost may clip on loud sources.
                ("softvol-max", "200"),
                    // mpv 0.41 options: bounded packet RAM; the owner lease supplies persistent blocks.
                    ("cache", _request.Input is RemotePlaybackInput or DownloadPlaybackInput ? "yes" : "auto"), ("cache-on-disk", "no"),
                    ("cache-secs", "30"), ("demuxer-readahead-secs", "1"),
                    ("demuxer-max-bytes", "64MiB"), ("demuxer-max-back-bytes", "16MiB"),
                    ("wid", _surface.ParentHwnd.ToInt64().ToString(CultureInfo.InvariantCulture)),
                }) _native.Option(_handle, option.Name, option.Value);
                if (_request.ResumePosition is { } resume && resume > TimeSpan.Zero)
                    _native.Option(_handle, "start", resume.TotalSeconds.ToString("R", CultureInfo.InvariantCulture));
                _native.Initialize(_handle);
                _downloadBridge?.Register(_native, _handle);
                ulong observer = 0;
                foreach (var property in new (string Name, int Format)[]
                {
                    ("time-pos", 5), ("duration", 5), ("pause", 3), ("seeking", 3), ("paused-for-cache", 3), ("cache-buffering-state", 5),
                    ("eof-reached", 3), ("seekable", 3), ("track-list", 6), ("video-format", 1),
                    ("audio-codec-name", 1), ("hwdec-current", 1), ("current-gpu-context", 1), ("video-params/gamma", 1),
                    ("fullscreen", 3), ("speed", 5), ("volume", 5), ("mute", 3),
                    ("chapter-list", 6), ("demuxer-cache-state", 6), ("video-out-params", 6),
                }) _native.Observe(_handle, ++observer, property.Name, property.Format);
            }, cancellationToken).ConfigureAwait(false);
            _events = Task.Factory.StartNew(EventPump, CancellationToken.None, TaskCreationOptions.LongRunning, TaskScheduler.Default);
            _ready.TrySetResult();
            await QueueAsync([["loadfile", source, "replace"]], cancellationToken).ConfigureAwait(false);
            cancellationToken.ThrowIfCancellationRequested();
            _recentOpen?.Accepted();
        }
        catch (Exception error)
        {
            await StopStatisticsAsync().ConfigureAwait(false);
            _ready.TrySetException(MpvNative.Error(ErrorCode.Unknown, "播放器初始化失败。"));
            Publish(snapshot => snapshot with { Status = PlaybackStatus.Failed, DownloadBytesPerSecond = null,
                Error = error is FilewayException fileway ? fileway.Error : new ErrorInfo(ErrorCode.Unknown, "播放器初始化失败。") });
            throw;
        }
    }

    private async Task<string> SourceAsync(PlaybackInput input, CancellationToken cancellationToken, bool primary = false)
    {
        if (input is DownloadPlaybackInput)
        {
            if (primary && _downloadBridge is not null) return _downloadBridge.Uri;
            throw MpvNative.Error(ErrorCode.InvalidRequest, "下载读取能力仅用于当前主播放来源。");
        }
        if (input is LocalPlaybackInput local)
        {
            if (!Path.IsPathFullyQualified(local.FullPath) || local.FullPath.StartsWith(@"\\", StringComparison.Ordinal) || !File.Exists(local.FullPath))
                throw MpvNative.Error(ErrorCode.InvalidRequest, "本地媒体文件不可用。");
            return Path.GetFullPath(local.FullPath);
        }
        if (input is not RemotePlaybackInput remote) throw MpvNative.Error(ErrorCode.InvalidRequest, "媒体输入类型不受支持。");
        var lease = primary && _leases is ICachedResourceLeaseProvider cached
            ? await cached.AcquireCachedAsync(remote.Resource, cancellationToken).ConfigureAwait(false)
            : await _leases.AcquireAsync(remote.Resource, cancellationToken).ConfigureAwait(false);
        Uri address = lease.Address;
        if (address.Scheme != Uri.UriSchemeHttp || !IPAddress.TryParse(address.Host, out var ip) || !IPAddress.IsLoopback(ip) ||
            !string.IsNullOrEmpty(address.UserInfo) || !string.IsNullOrEmpty(address.Fragment))
        {
            await lease.DisposeAsync().ConfigureAwait(false);
            throw MpvNative.Error(ErrorCode.InvalidRequest, "媒体资源能力地址无效。");
        }
        bool closing;
        lock (_gate)
        {
            closing = _closing;
            if (!closing)
            {
                _ownedLeases.Add(lease);
                if (primary)
                {
                    _primaryLease = lease;
                    if (lease is IResourceLeaseStatisticsSource statistics)
                        _statistics = Task.Run(() => SampleStatisticsAsync(lease, statistics, _statisticsStop.Token));
                }
            }
        }
        if (closing)
        {
            await lease.DisposeAsync().ConfigureAwait(false);
            throw MpvNative.Error(ErrorCode.Canceled, "播放会话已关闭。");
        }
        return address.AbsoluteUri;
    }

    private async Task SampleStatisticsAsync(IResourceLease lease, IResourceLeaseStatisticsSource source, CancellationToken cancellationToken)
    {
        var window = new UpstreamRateWindow();
        using var timer = new PeriodicTimer(TimeSpan.FromMilliseconds(500));
        try
        {
            do
            {
                double? rate;
                try
                {
                    // Single flight, away from the native event thread. Canceling a closing session
                    // also bounds waiting for a provider that fails to finish its canceled read.
                    var statistics = await source.ReadStatisticsAsync(cancellationToken).WaitAsync(cancellationToken).ConfigureAwait(false);
                    if (statistics is null) { window.Reset(); rate = null; }
                    else rate = window.Observe(statistics.UpstreamBytes, Stopwatch.GetTimestamp());
                }
                catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { break; }
                catch (Exception error)
                {
                    window.Reset();
                    rate = null;
                    var code = error is FilewayException fileway ? fileway.Error.Code : ErrorCode.Unknown;
                    Trace.TraceWarning("Fileway playback statistics unavailable ({0}).", code);
                }
                lock (_gate)
                {
                    if (_closing || cancellationToken.IsCancellationRequested || !ReferenceEquals(_primaryLease, lease)) break;
                    Publish(snapshot => snapshot with { DownloadBytesPerSecond = rate });
                }
            } while (await timer.WaitForNextTickAsync(cancellationToken).ConfigureAwait(false));
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { }
    }

    private async Task StopStatisticsAsync()
    {
        await _statisticsStop.CancelAsync().ConfigureAwait(false);
        if (_statistics is { } statistics) await statistics.ConfigureAwait(false);
        lock (_gate) _primaryLease = null;
    }

    private Task QueueAsync(string[][] arguments, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var completion = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        lock (_gate)
        {
            if (_closing) throw MpvNative.Error(ErrorCode.Canceled, "播放会话已关闭。");
            if (!_commands.Writer.TryWrite(new PlaybackCommand(arguments, completion, cancellationToken)))
                throw MpvNative.Error(ErrorCode.Busy, "播放器命令队列已满。");
        }
        return completion.Task;
    }

    private async Task CommandPumpAsync()
    {
        try
        {
            await _ready.Task.ConfigureAwait(false);
            await foreach (var command in _commands.Reader.ReadAllAsync().ConfigureAwait(false))
            {
                try
                {
                    lock (_gate) if (_closing) throw MpvNative.Error(ErrorCode.Canceled, "播放会话已关闭。");
                    command.Cancellation.ThrowIfCancellationRequested();
                    if (command.Arguments[0][0] == "seek")
                    {
                        lock (_gate) { _seeking = true; _ended = false; }
                        Publish(snapshot => snapshot with { Status = PlaybackStatus.Seeking });
                    }
                    foreach (string[] arguments in command.Arguments)
                        await NativeCommandAsync(arguments, command.Cancellation).ConfigureAwait(false);
                    command.Completion.TrySetResult();
                }
                catch (Exception error)
                {
                    command.Completion.TrySetException(error is FilewayException or OperationCanceledException ? error : MpvNative.Error(ErrorCode.Unknown, "播放器命令失败。"));
                }
            }
        }
        catch (Exception)
        {
            while (_commands.Reader.TryRead(out var command)) command.Completion.TrySetException(MpvNative.Error(ErrorCode.Canceled, "播放会话已关闭。"));
        }
    }

    private async Task NativeCommandAsync(string[] arguments, CancellationToken cancellationToken)
    {
        ulong id = unchecked((ulong)Interlocked.Increment(ref _nextCommand));
        var reply = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!_replies.TryAdd(id, reply)) throw MpvNative.Error(ErrorCode.Unknown, "播放器命令标识冲突。");
        try
        {
            _native.Command(_handle, id, arguments);
            await reply.Task.WaitAsync(NativeDeadline, cancellationToken).ConfigureAwait(false);
        }
        finally { _replies.TryRemove(id, out _); }
    }

    private void EventPump()
    {
        try
        {
            while (!_pumpStop.IsCancellationRequested)
            {
                var item = _native.Wait(_handle);
                if (item.Id == 5 && _replies.TryRemove(item.User, out var reply))
                {
                    if (item.Error < 0) reply.TrySetException(MpvNative.Error(ErrorCode.Unknown, $"播放器原生命令失败（{item.Error}）。"));
                    else reply.TrySetResult();
                }
                lock (_gate) if (_closing) continue;
                // mpv deliberately disables its wid child. In native-host mode
                // it stays disabled so the UI-owned container receives input.
                if (item.Id == 16 && item.Messages is { Length: 1 } messages)
                {
                    if (messages[0] == "fileway-close") _ = CloseFromNativeAsync();
                    else if (messages[0] == "fileway-controls") _ = ShowControlsAsync();
                }
                if (item.Property is { } property) PropertyChanged(property);
                else if (item.Id == 21)
                {
                    _usable = true;
                    _seeking = false;
                    Publish(snapshot => snapshot with { Status = CurrentStatus(), ConfirmedPosition = snapshot.Position });
                }
                else if (item.Id == 20)
                {
                    _seeking = true;
                    Publish(snapshot => snapshot with { Status = PlaybackStatus.Seeking });
                }
                else if (item.Id == 7)
                {
                    _ended = true;
                    Publish(snapshot => snapshot with { Status = item.EndReason == 4 ? PlaybackStatus.Failed : PlaybackStatus.Ended,
                        Error = item.EndReason == 4 ? new ErrorInfo(ErrorCode.Unknown, "媒体读取或解码失败。") : null });
                }
                else if (item.Id == 24) Publish(snapshot => snapshot with { Status = PlaybackStatus.Failed, Error = new ErrorInfo(ErrorCode.Unknown, "播放器事件队列溢出。") });
            }
        }
        catch (Exception) { Publish(snapshot => snapshot with { Status = PlaybackStatus.Failed, Error = new ErrorInfo(ErrorCode.Unknown, "播放器事件处理失败。") }); }
    }

    private PlaybackStatus CurrentStatus() => _seeking ? PlaybackStatus.Seeking : _ended ? PlaybackStatus.Ended :
        _buffering || !_usable ? PlaybackStatus.Buffering : _paused ? PlaybackStatus.Paused : PlaybackStatus.Playing;

    private void PropertyChanged(MpvProperty property)
    {
        switch (property.Name)
        {
            case "time-pos" when property.Value is double position && double.IsFinite(position) && position >= 0:
                Publish(snapshot => snapshot with { Position = TimeSpan.FromSeconds(position), ConfirmedPosition = _usable && !_seeking ? TimeSpan.FromSeconds(position) : snapshot.ConfirmedPosition });
                break;
            case "duration" when property.Value is double duration && IsMediaTime(duration):
                Publish(snapshot => snapshot with { Duration = TimeSpan.FromSeconds(duration) });
                UpdateCache(_cacheState); break;
            case "pause" when property.Value is bool paused: _paused = paused; Publish(snapshot => snapshot with { Status = CurrentStatus(), IsPaused = paused }); break;
            case "seeking" when property.Value is bool seeking: _seeking = seeking; Publish(snapshot => snapshot with { Status = CurrentStatus() }); break;
            case "paused-for-cache" when property.Value is bool buffering: _buffering = buffering; Publish(snapshot => snapshot with { Status = CurrentStatus() }); break;
            case "cache-buffering-state":
                Publish(snapshot => snapshot with { BufferingPercent = property.Value is double percent && double.IsFinite(percent) ? Math.Clamp(percent, 0, 100) : null }); break;
            case "eof-reached" when property.Value is bool ended: _ended = ended; Publish(snapshot => snapshot with { Status = CurrentStatus() }); break;
            case "seekable" when property.Value is bool seekable: Publish(snapshot => snapshot with { Seekable = seekable }); break;
            case "speed" when property.Value is double rate && double.IsFinite(rate) && rate > 0:
                Publish(snapshot => snapshot with { PlaybackRate = rate }); break;
            case "volume" when property.Value is double volume && double.IsFinite(volume) && volume >= 0:
                Publish(snapshot => snapshot with { Volume = volume }); break;
            case "mute" when property.Value is bool muted:
                Publish(snapshot => snapshot with { IsMuted = muted }); break;
            case "chapter-list":
                UpdateChapters(property.Value as JsonElement?); break;
            case "demuxer-cache-state":
                UpdateCache(property.Value as JsonElement?); break;
            case "video-out-params":
                UpdateVideoShape(property.Value as JsonElement?); break;
            case "fullscreen" when property.Value is bool fullscreen:
                lock (_gate) _nativeFullscreen = fullscreen;
                if (_hostFullscreen != fullscreen) _ = SetHostFullscreenAsync(fullscreen);
                break;
            case "track-list" when property.Value is JsonElement tracks: UpdateTracks(tracks); break;
            case "video-format" when property.Value is string video: Publish(snapshot => snapshot with { Diagnostics = snapshot.Diagnostics with { VideoCodec = video } }); break;
            case "audio-codec-name" when property.Value is string audio: Publish(snapshot => snapshot with { Diagnostics = snapshot.Diagnostics with { AudioCodec = audio } }); break;
            case "hwdec-current" when property.Value is string decoder: Publish(snapshot => snapshot with { Diagnostics = snapshot.Diagnostics with { Decoder = decoder } }); break;
            case "current-gpu-context" when property.Value is string context: Publish(snapshot => snapshot with { Diagnostics = snapshot.Diagnostics with { ActualOutputMode = context == "d3d11" ? "d3d11-hwnd" : context } }); break;
            case "video-params/gamma" when property.Value is string gamma:
                Publish(snapshot => snapshot with { Diagnostics = snapshot.Diagnostics with { SourceDynamicRange = gamma is "pq" or "hlg" ? gamma : "SDR" } }); break;
        }
    }

    private void UpdateVideoShape(JsonElement? value)
    {
        double? aspect = null;
        if (value is { ValueKind: JsonValueKind.Object } video
            && video.TryGetProperty("dw", out var widthValue) && widthValue.ValueKind == JsonValueKind.Number && widthValue.TryGetInt32(out var width)
            && video.TryGetProperty("dh", out var heightValue) && heightValue.ValueKind == JsonValueKind.Number && heightValue.TryGetInt32(out var height)
            && width is > 0 and <= 65536 && height is > 0 and <= 65536)
        {
            var rotate = video.TryGetProperty("rotate", out var rotation) && rotation.ValueKind == JsonValueKind.Number && rotation.TryGetInt32(out var degrees) ? degrees : 0;
            aspect = Math.Abs(rotate % 180) == 90 ? (double)height / width : (double)width / height;
        }
        var pixelWidth = value is { ValueKind: JsonValueKind.Object } source && source.TryGetProperty("w", out var w)
            && w.ValueKind == JsonValueKind.Number && w.TryGetInt32(out var sourceWidth) && sourceWidth > 0 ? (int?)sourceWidth : null;
        var pixelHeight = value is { ValueKind: JsonValueKind.Object } source2 && source2.TryGetProperty("h", out var h)
            && h.ValueKind == JsonValueKind.Number && h.TryGetInt32(out var sourceHeight) && sourceHeight > 0 ? (int?)sourceHeight : null;
        Publish(snapshot => snapshot with
        {
            DisplayAspectRatio = aspect,
            Diagnostics = snapshot.Diagnostics with { VideoWidth = pixelWidth, VideoHeight = pixelHeight },
        });
    }

    private void HostFullscreenChanged(object? sender, bool fullscreen)
    {
        lock (_gate)
        {
            _hostFullscreen = fullscreen;
            if (_closing || _nativeFullscreen == fullscreen) return;
            _nativeFullscreen = fullscreen;
        }
        try { _ = ObserveWindowCommandAsync(QueueAsync([["set", "fullscreen", fullscreen ? "yes" : "no"]], CancellationToken.None)); }
        catch (FilewayException) { }
    }

    private static async Task ObserveWindowCommandAsync(Task command)
    { try { await command.ConfigureAwait(false); } catch (Exception) { } }

    private async Task SetHostFullscreenAsync(bool fullscreen)
    {
        lock (_gate) { if (_closing) return; }
        if (_surface is not IPlaybackWindowControl window) return;
        try
        {
            await window.SetFullscreenAsync(fullscreen, CancellationToken.None).ConfigureAwait(false);
            lock (_gate) _hostFullscreen = fullscreen;
        }
        catch (Exception) { }
    }

    private async Task ShowControlsAsync()
    {
        lock (_gate) { if (_closing) return; }
        if (_surface is IPlaybackWindowControl window)
            try { await window.ShowControlsAsync(CancellationToken.None).ConfigureAwait(false); } catch (Exception) { }
    }

    private async Task CloseFromNativeAsync()
    { try { await StopAsync(CancellationToken.None).ConfigureAwait(false); } catch (Exception) { } }

    private void UpdateTracks(JsonElement tracks)
    {
        if (tracks.ValueKind != JsonValueKind.Array) return;
        var parsed = new List<MediaTrack>();
        foreach (var track in tracks.EnumerateArray())
        {
            if (track.ValueKind != JsonValueKind.Object || !track.TryGetProperty("id", out var id)
                || id.ValueKind != JsonValueKind.Number || !id.TryGetInt64(out var trackId)
                || !track.TryGetProperty("type", out var type) || type.ValueKind != JsonValueKind.String) continue;
            MediaTrackKind? kind = type.GetString() switch { "video" => MediaTrackKind.Video, "audio" => MediaTrackKind.Audio, "sub" => MediaTrackKind.Subtitle, _ => null };
            if (kind is null) continue;
            string? Text(string name) => track.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;
            bool Flag(string name) => track.TryGetProperty(name, out var value) && value.ValueKind is JsonValueKind.True;
            parsed.Add(new MediaTrack(trackId, kind.Value, Text("title"), Text("lang"), Text("codec"), Flag("selected"), Flag("external")));
        }
        bool bitmap = parsed.Any(track => track.Kind == MediaTrackKind.Subtitle && track.Selected && track.Codec is "hdmv_pgs_subtitle" or "dvd_subtitle");
        bool script = parsed.Any(track => track.Kind == MediaTrackKind.Subtitle && track.Selected && track.Codec is "ass" or "ssa");
        lock (_gate) { _scriptSubtitleSelected = script; _bitmapSubtitleSelected = bitmap; _lastViewportSubtitlePosition = null; }
        Publish(snapshot => snapshot with { Tracks = parsed.AsReadOnly(), SubtitleCapabilities = new SubtitleCapabilities(!bitmap, !bitmap, true, true, script) });
        try { _ = ObserveWindowCommandAsync(QueueAsync(CurrentSubtitleCommands(), CancellationToken.None)); }
        catch (FilewayException) { }
    }

    private void UpdateChapters(JsonElement? value)
    {
        if (value is not { ValueKind: JsonValueKind.Array } chapters)
        {
            Publish(snapshot => snapshot with { Chapters = null });
            return;
        }
        var parsed = new List<PlaybackChapter>();
        foreach (var chapter in chapters.EnumerateArray())
        {
            if (chapter.ValueKind != JsonValueKind.Object || !chapter.TryGetProperty("time", out var time) || time.ValueKind != JsonValueKind.Number
                || !time.TryGetDouble(out var seconds) || !IsMediaTime(seconds)) continue;
            var title = chapter.TryGetProperty("title", out var text) && text.ValueKind == JsonValueKind.String
                ? text.GetString() : null;
            parsed.Add(new PlaybackChapter(string.IsNullOrWhiteSpace(title) ? $"章节 {parsed.Count + 1}" : title, TimeSpan.FromSeconds(seconds)));
        }
        Publish(snapshot => snapshot with { Chapters = parsed.OrderBy(chapter => chapter.Position).ToArray() });
    }

    private void UpdateCache(JsonElement? value)
    {
        _cacheState = value;
        Publish(snapshot =>
        {
            var ranges = PlaybackCacheState.ParseRanges(value, snapshot.Duration);
            return snapshot with { BufferedRanges = ranges, BufferedUntil = PlaybackCacheState.ReaderEnd(value, ranges) };
        });
    }

    private static bool IsMediaTime(double seconds)
        => double.IsFinite(seconds) && seconds >= 0 && seconds < TimeSpan.MaxValue.TotalSeconds;

    private void Publish(Func<PlaybackSnapshot, PlaybackSnapshot> change, bool terminal = false)
    {
        lock (_gate)
        {
            if (_closing && !terminal) return;
            _snapshot = change(_snapshot);
            _recentOpen?.Observe(_snapshot);
            foreach (var watcher in _watchers) watcher.Writer.TryWrite(_snapshot);
        }
    }

    public async IAsyncEnumerable<PlaybackSnapshot> WatchAsync([EnumeratorCancellation] CancellationToken cancellationToken)
    {
        var channel = Channel.CreateBounded<PlaybackSnapshot>(new BoundedChannelOptions(1) { FullMode = BoundedChannelFullMode.DropOldest });
        lock (_gate) { _watchers.Add(channel); channel.Writer.TryWrite(_snapshot); if (_stop?.IsCompletedSuccessfully == true) channel.Writer.TryComplete(); }
        try { await foreach (var snapshot in channel.Reader.ReadAllAsync(cancellationToken).ConfigureAwait(false)) yield return snapshot; }
        finally { lock (_gate) _watchers.Remove(channel); }
    }

    public Task SetPausedAsync(bool paused, CancellationToken cancellationToken) => QueueAsync([["set", "pause", paused ? "yes" : "no"]], cancellationToken);
    public Task SetVolumeAsync(double volume, CancellationToken cancellationToken)
    {
        if (!double.IsFinite(volume) || volume is < 0 or > 200) throw MpvNative.Error(ErrorCode.InvalidRequest, "音量必须为 0 到 200。");
        return QueueAsync([["set", "volume", volume.ToString("R", CultureInfo.InvariantCulture)]], cancellationToken);
    }
    public Task SetRateAsync(double rate, CancellationToken cancellationToken)
    {
        if (!double.IsFinite(rate) || rate is < 0.125 or > 5) throw MpvNative.Error(ErrorCode.InvalidRequest, "播放倍速必须为 0.125 到 5。");
        return QueueAsync([["set", "speed", rate.ToString("R", CultureInfo.InvariantCulture)]], cancellationToken);
    }
    public Task SetMutedAsync(bool muted, CancellationToken cancellationToken)
        => QueueAsync([["set", "mute", muted ? "yes" : "no"]], cancellationToken);
    public Task SeekAsync(TimeSpan position, CancellationToken cancellationToken)
    {
        if (position < TimeSpan.Zero) throw MpvNative.Error(ErrorCode.InvalidRequest, "播放位置不能为负数。");
        return QueueAsync([["seek", position.TotalSeconds.ToString("R", CultureInfo.InvariantCulture), "absolute+exact"]], cancellationToken);
    }
    public Task SelectTrackAsync(MediaTrackKind kind, long? trackId, CancellationToken cancellationToken)
    {
        if (trackId is <= 0) throw MpvNative.Error(ErrorCode.InvalidRequest, "媒体轨道标识无效。");
        string name = kind switch { MediaTrackKind.Video => "vid", MediaTrackKind.Audio => "aid", MediaTrackKind.Subtitle => "sid", _ => throw MpvNative.Error(ErrorCode.InvalidRequest, "媒体轨道类型无效。") };
        return QueueAsync([["set", name, trackId?.ToString(CultureInfo.InvariantCulture) ?? "no"]], cancellationToken);
    }
    public async Task SetSubtitlesAsync(SubtitlePreferences preferences, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(preferences);
        if (!double.IsFinite(preferences.Scale) || preferences.Scale is < 0.1 or > 10 || !double.IsFinite(preferences.VerticalPositionPercent) || preferences.VerticalPositionPercent is < 0 or > 150 ||
            !double.IsFinite(preferences.FontSize) || preferences.FontSize is < 8 or > 120 || preferences.FontFamily?.Length > 256)
            throw MpvNative.Error(ErrorCode.InvalidRequest, "字幕显示参数无效。");
        SubtitlePreferences previous;
        lock (_gate) { previous = _subtitlePreferences; _subtitlePreferences = preferences; }
        try { await QueueAsync(CurrentSubtitleCommands(), cancellationToken).ConfigureAwait(false); }
        catch
        {
            lock (_gate) if (ReferenceEquals(_subtitlePreferences, preferences)) _subtitlePreferences = previous;
            throw;
        }
    }

    public Task SetSubtitleSafeAreaAsync(double bottomInsetFraction, CancellationToken cancellationToken)
    {
        if (!double.IsFinite(bottomInsetFraction) || bottomInsetFraction is < 0 or > 0.4)
            throw MpvNative.Error(ErrorCode.InvalidRequest, "字幕安全区域无效。");
        lock (_gate) _subtitleBottomInset = bottomInsetFraction;
        return QueueAsync(CurrentSubtitleCommands(), cancellationToken);
    }

    private string[][] CurrentSubtitleCommands()
    {
        lock (_gate)
        {
            var preferences = _subtitlePreferences;
            var preserve = _scriptSubtitleSelected && !preferences.OverrideScriptStyle;
            // Author-positioned ASS remains at its native geometry. The full-window
            // mode deliberately includes letterboxing instead of clamping to video.
            // Video-only mode retains the temporary control-bar safety inset.
            var position = SubtitlePosition();
            var scale = preserve ? 1 : preferences.Scale;
            return [["set", "sub-scale", scale.ToString("R", CultureInfo.InvariantCulture)],
                ["set", "sub-pos", position.ToString("R", CultureInfo.InvariantCulture)],
                ["set", "sub-delay", preferences.Delay.TotalSeconds.ToString("R", CultureInfo.InvariantCulture)],
                ["set", "sub-ass-override", preserve ? "yes" : "force"],
                ["set", "sub-use-margins", preferences.UseWindowArea ? "yes" : "no"],
                ["set", "sub-ass-force-margins", preferences.UseWindowArea ? "yes" : "no"],
                ["set", "sub-font-size", preferences.FontSize.ToString("R", CultureInfo.InvariantCulture)],
                ["set", "sub-font", string.IsNullOrWhiteSpace(preferences.FontFamily) ? "sans-serif" : preferences.FontFamily]];
        }
    }
    private double SubtitlePosition()
    {
        var prefs = _subtitlePreferences;
        var position = prefs.UseWindowArea ? prefs.VerticalPositionPercent : Math.Min(prefs.VerticalPositionPercent, 100 * (1 - _subtitleBottomInset));
        // Bitmap subtitles ignore text margin options. Move their original
        // canvas down into the observed letterbox, without stretching the bitmap.
        if (_bitmapSubtitleSelected && prefs.UseWindowArea && _subtitleViewportAspect > 0 &&
            _snapshot.DisplayAspectRatio is { } aspect && aspect > _subtitleViewportAspect)
            position += (1 - _subtitleViewportAspect / aspect) / 2 * 60 + 5;
        return Math.Clamp(position, 0, 150);
    }
    public Task SetSubtitleViewportAsync(double width, double height, CancellationToken cancellationToken)
    {
        if (!double.IsFinite(width) || !double.IsFinite(height) || width <= 0 || height <= 0) return Task.CompletedTask;
        double position;
        lock (_gate)
        {
            _subtitleViewportAspect = width / height;
            if (!_bitmapSubtitleSelected || !_subtitlePreferences.UseWindowArea) return Task.CompletedTask;
            position = SubtitlePosition();
            if (_lastViewportSubtitlePosition is { } previous && Math.Abs(position - previous) < 0.25) return Task.CompletedTask;
            _lastViewportSubtitlePosition = position;
        }
        return QueueAsync([["set", "sub-pos", position.ToString("R", CultureInfo.InvariantCulture)]], cancellationToken);
    }
    public async Task PlayAsync(PlaybackInput input, CancellationToken cancellationToken)
    {
        // Episode switching stays within one remote browsing context; download-sourced sessions
        // keep their dedicated reader lifecycle and switch by opening a new session instead.
        if (input is not RemotePlaybackInput remote)
            throw MpvNative.Error(ErrorCode.InvalidRequest, "选集切换仅支持服务器媒体。");
        if (_request.Input is not RemotePlaybackInput)
            throw MpvNative.Error(ErrorCode.CapabilityMissing, "当前播放来源不支持选集切换。");
        if (remote.Resource.Account != Account)
            throw MpvNative.Error(ErrorCode.InvalidRequest, "选集必须属于当前播放账户。");
        cancellationToken.ThrowIfCancellationRequested();
        await _ready.Task.WaitAsync(cancellationToken).ConfigureAwait(false);
        IResourceLease? previous;
        lock (_gate) previous = _primaryLease;
        await StopStatisticsAsync().ConfigureAwait(false);
        string source = await SourceAsync(input, cancellationToken, primary: true).ConfigureAwait(false);
        try
        {
            await QueueAsync([["loadfile", source, "replace"]], cancellationToken).ConfigureAwait(false);
        }
        catch (Exception)
        {
            await StopStatisticsAsync().ConfigureAwait(false);
            throw;
        }
        // The previous source is released only after its replacement was accepted.
        if (previous is not null)
        {
            try { await previous.DisposeAsync().ConfigureAwait(false); }
            catch (Exception) { /* The replaced source is no longer authoritative; session close still owns it. */ }
        }
        lock (_gate)
        {
            _ended = false;
            _seeking = false;
            _bitmapSubtitleSelected = false;
        }
        Publish(snapshot => snapshot with
        {
            Status = PlaybackStatus.Preparing,
            DisplayName = remote.Resource.DisplayName,
            Origin = remote.Resource,
            Position = TimeSpan.Zero,
            ConfirmedPosition = null,
            Duration = null,
            Tracks = Array.Empty<MediaTrack>(),
            Chapters = null,
            BufferedUntil = null,
            BufferedRanges = null,
            BufferingPercent = null,
            DownloadBytesPerSecond = null,
            ResumePrompt = null,
            Error = null,
            IsPaused = null,
        });
    }

    public async Task AddSubtitleAsync(PlaybackInput subtitle, CancellationToken cancellationToken)
    {
        if (subtitle is RemotePlaybackInput remote && remote.Resource.Account != Account)
            throw MpvNative.Error(ErrorCode.InvalidRequest, "字幕必须属于当前播放账户。");
        string source = await SourceAsync(subtitle, cancellationToken).ConfigureAwait(false);
        await QueueAsync([["sub-add", source, "select"]], cancellationToken).ConfigureAwait(false);
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        Task stop;
        lock (_gate)
        {
            if (_stop is null)
            {
                _closing = true;
                // mpv load/stop can wait inside a prefix read. Wake that read
                // before awaiting startup, detach, stop commands or native drains.
                _downloadBridge?.CancelBeforeDestroy();
                _downloadObservation?.Stop();
                _recentOpen?.Dispose();
                _commands.Writer.TryComplete();
                Publish(snapshot => snapshot with { Status = PlaybackStatus.Closing, DownloadBytesPerSecond = null,
                    Download = snapshot.Download is { } download ? download with { ReadablePrefixBytes = null } : null }, terminal: true);
                _stop = Task.Run(StopCoreAsync, CancellationToken.None);
            }
            stop = _stop;
        }
        return stop.WaitAsync(cancellationToken);
    }

    private async Task StopCoreAsync()
    {
        try
        {
            await _startStop.CancelAsync().ConfigureAwait(false);
            if (_start is not null)
            {
                // An actual native startup that has not returned still owns its
                // bridge. A deadline never authorizes early reader/root release.
                try { await _start.WaitAsync(NativeDeadline).ConfigureAwait(false); }
                catch (Exception) when (_start.IsCompleted) { }
            }
            else _ready.TrySetException(MpvNative.Error(ErrorCode.Canceled, "播放会话已关闭。"));
            if (_downloadObservation is not null)
                await _downloadObservation.DisposeAsync().AsTask().WaitAsync(NativeDeadline).ConfigureAwait(false);
            await StopStatisticsAsync().ConfigureAwait(false);
            if (_surface is IPlaybackWindowControl window) window.FullscreenChanged -= HostFullscreenChanged;
            if (_surface is not null) await _surface.DetachAsync(CancellationToken.None).AsTask().WaitAsync(NativeDeadline).ConfigureAwait(false);
            if (_handle != 0 && _ready.Task.IsCompletedSuccessfully)
            {
                try { await NativeCommandAsync(["stop"], CancellationToken.None).ConfigureAwait(false); }
                catch (FilewayException) { /* Destroy still waits for all native reads to stop. */ }
            }
            _pumpStop.Cancel();
            if (_handle != 0) _native.Wake(_handle);
            foreach (var reply in _replies.Values) reply.TrySetException(MpvNative.Error(ErrorCode.Canceled, "播放会话已关闭。"));
            if (_events is not null) await _events.WaitAsync(NativeDeadline).ConfigureAwait(false);
            await _commandPump.WaitAsync(NativeDeadline).ConfigureAwait(false);
            if (_handle != 0)
            {
                await Task.Run(() => _native.Destroy(_handle), CancellationToken.None).WaitAsync(NativeDeadline).ConfigureAwait(false);
                _handle = 0;
            }
            // Reached only after Destroy actually returned, or when no native
            // instance ever existed. Timeout/error paths retain all bridge roots.
            if (_downloadBridge is not null) await _downloadBridge.ReleaseAfterDestroyAsync().ConfigureAwait(false);
            foreach (var lease in _ownedLeases) await lease.DisposeAsync().ConfigureAwait(false);
            _ownedLeases.Clear();
            if (_surface is not null) await _surface.DisposeAsync().ConfigureAwait(false);
            Publish(snapshot => snapshot with { Status = PlaybackStatus.Stopped, DownloadBytesPerSecond = null,
                Download = snapshot.Download is { } download ? download with { ReadablePrefixBytes = null } : null }, terminal: true);
            lock (_gate) foreach (var watcher in _watchers) watcher.Writer.TryComplete();
            _released(Id);
        }
        catch (Exception)
        {
            Publish(snapshot => snapshot with { Status = PlaybackStatus.Failed,
                Download = snapshot.Download is { } download ? download with { ReadablePrefixBytes = null } : null,
                Error = new ErrorInfo(ErrorCode.Timeout, "播放器关闭未完成；原生资源保留待恢复。") }, terminal: true);
            throw MpvNative.Error(ErrorCode.Timeout, "播放器关闭未完成；原生资源保留待恢复。");
        }
    }

    public ValueTask DisposeAsync() => new(StopAsync(CancellationToken.None));
}
