# Native media lifecycle

`Fileway.Core.Contracts.Media` describes application behavior. It contains no WinUI types, HWNDs or COM pointers. Platform surfaces and libmpv bindings belong in `Fileway.Playback` and the App adapter. A swapchain pointer never crosses the Go IPC boundary.

## Ownership and sources

Remote input carries an immutable `RemoteResourceRef`, including account/source revision. The use case acquires a resource lease through `IResourceLeaseProvider`; media bytes flow through the core's loopback transport. The lease URL is a temporary capability and must not appear in a snapshot, playback history, diagnostic export or ordinary log.

Local input is a distinct type with an absolute local path. Its optional origin preserves the original account and version for completed downloads. It must never be rebound to the currently selected account. Local paths and remote wire paths use different validation and normalization rules.

The playback service owns sessions; a session owns its native engine, event pump, surface attachments, video/subtitle leases and temporary resources. Closing an account stops its sessions before credentials or leases are removed. A Host exit invalidates remote sources; local complete files do not acquire a remote lease merely for playback.

## State and progress

Each opened media item has a new generation. Native event data is copied before its documented lifetime ends. Posted UI callbacks verify both session and generation, and cannot attach an old surface after stop or close. Snapshots contain immutable track collections and are published away from native callback threads.

`Position` is presentation state. `ConfirmedPosition` is null until the engine has confirmed usable playback progress and retains the last confirmed value while a seek is pending. History writes use confirmed progress; a requested seek or resume target never becomes history merely because the command was accepted. Track and subtitle changes operate on the current engine without reopening the source.

Commands are serialized per session. Rapid seek/resize requests may be coalesced, but stop/close and resource release are not discarded. `StopAsync` is idempotent and terminates the current source; `DisposeAsync` also releases the engine. Native failure and cancellation are explicit states rather than a silent reopen loop.

### UI responsiveness

Video rendering and HUD input use different execution paths. Smooth video does
not establish a responsive UI. Diagnose delayed menus/fullscreen separately from
network buffering or dropped video frames; record UI dispatch latency, snapshot
application time and whether the input handler was reached before changing the
media transport.

Each surface coalesces its snapshot stream into one pending UI callback and the
newest applicable snapshot. Cosmetic dispatch uses low priority so ordinary input
and deferred menus can run; callbacks yield rather than draining indefinitely.
Session/generation checks and teardown fences reject stale work, while pending
error/close states are retained. Explicit user close starts immediately on the
UI thread. Native visibility and bounds calls run on transitions instead of on
every playback-position notification.

The chapter buffer overlay reuses its existing columns and fills as observed
range endpoints move. Disjoint cached intervals remain disjoint; only overlapping
or touching intervals are merged. A duration/chapter structure change rebuilds
the segments. Hidden controls retain the latest snapshot and refresh their
timeline synchronously before becoming visible again. These changes preserve the
accepted Screenbox design rather than substituting another player interface.

Pure layout and scheduling tests do not establish native interaction latency.
Regression acceptance still includes repeated settings opens, button and F11
fullscreen changes, seek/resize, hide/show, close during updates, and comparison
of visible buffered gaps on a real Windows player.

### Opt-in local responsiveness evidence

`FILEWAY_PLAYER_RESPONSIVENESS_DIAGNOSTICS=1` enables a two-minute local capture
for the first player opened while no other capture is active. Without this exact
value, no diagnostic worker, queue probe or file write is started. Captures use
the existing `FILEWAY_WINDOWS_DATA_ROOT` (or the normal local preview data root)
and replace `diagnostics/player-responsiveness.json`; there is no automatic upload.

The recorder retains at most 512 numeric events, one outstanding Normal-priority
UI probe and one background writer. The report and temporary file are each
limited to 256 KiB. Closing/detaching the player cancels capture without waiting
on the UI thread. Unavailable storage or rejected dispatch disables evidence
collection without changing playback. UI producers use one nonblocking atomic
slot update; they never write files or wait for the writer.

Evidence includes settings/fullscreen handler arrival, UI queue latency/pending
age, paired menu creation/measurement/layout/show/activation markers, fullscreen
presenter markers, activation/visibility state, and sampled hit-test, bounds and
snapshot-application duration. Ordinary hot-path durations are sampled at most
once per second; calls taking at least 50 ms are retained. Settings action values
are fixed identifiers: 1 audio, 2 subtitles, 3 speed, 4 volume, 5 subtitle style,
6 overflow, 7 chapters and 8 source resolution. Event identifiers are defined by
the application-owned `PlaybackDiagnosticEvent` enum; timestamps/durations use
relative monotonic microseconds. An unfinished begin marker and an aging queue
probe can remain visible while the UI is blocked.

The schema accepts only fixed event identifiers, sequences, numeric durations
and counters/booleans. It does not retain media titles or paths, resource URLs,
accounts, text input, pointer coordinates, HWNDs or exception messages. Enabling
diagnostics does not change Screenbox styling, window hit testing, engine commands
or media transport. Passing recorder tests establishes this diagnostic capability;
it does not establish a fix for native interaction stalls.

## Teardown

Active-download playback prefers the same retained file object's readable prefix.
Missing bytes may use fresh authenticated loopback ranges when source length and
modification time can be observed before and after each fill. Strict range validation
and account/task/run/permission fences apply before bytes are returned. This observed
identity is not an immutable version or resume guarantee. At most four readers each
retain one 1 MiB read-ahead buffer; remote bytes never advance persisted download
progress or establish an offline playable time range. Without modification evidence,
reads retain the local prefix wait behavior.

Transfer cancellation, permission withdrawal and account retirement drain actual
reader I/O and remote lease release, without awaiting native destruction, final reader
disposal or window close. The playback session still owns its callbacks and retained
reader until native destruction actually returns. A failed capability release remains
an explicit unknown outcome. Completed-history reopen and real MP4/MKV tail-index
acceptance are separate unfinished work.

1. Mark closing, fence new callbacks and preserve the latest confirmed position.
2. Detach XAML/native composition references on their owning UI thread, without blocking that thread on an event pump that needs the dispatcher.
3. Stop media reads, drain the necessary end events, stop callbacks/pump, and destroy the native player using the ordering verified against the pinned client API.
4. Revoke video and subtitle leases, then dispose temporary resources.
5. Destroy the surface/window after its native dependencies are released.

The native engine must remain alive while an App-owned COM reference or callback can still use it. A timeout is a failed teardown requiring recovery; it is not proof that native handles were released. The last known reference must not be freed while native work continues.

## Rendering and diagnosis

The current path uses mpv `gpu-next` / D3D11 with an HWND video surface and an owned, transparent WinUI controls window. The App synchronizes owner, bounds, DPI, fullscreen and teardown. Opening controls must not hide the video. The controls window is not permanently topmost over other applications.

The complete control window stays transparent. WinUIEx's transparent backdrop enables a clear HUD;
Screenbox-style translucent black gradients fade into the video at both edges.
Desktop acrylic belongs to transient flyouts, not the title/footer surfaces.
Settings reuse the Screenbox control content inside a small owned WinUI window.
Its complete surface is an SDK acrylic target, with keyboard/IME support and
event-driven light dismissal; it does not depend on the transparent HUD popup
bridge. Native menus retain the SDK MenuFlyoutPresenter and original items.
Native window regions must not clip this HUD: a popup extends beyond the toolbar.
Errors and resume offers remain inside the footer. Windows accessibility policy controls
the high-contrast fallback. Native DWM owns window corners and shadow. No custom
Win2D blur graph, solid acrylic toolbar, or WebView is used.

The public libmpv render API provides OpenGL/SW backends, not a ready D3D11 WinUI surface. External swapchain injection is not used or verified by this implementation. Production acceptance requires actual layer, input, resize, DPI, fullscreen and device-recovery evidence. UI and engine must not independently overwrite video color settings; raw pointers never enter Core domain objects.

Playback rate, volume, mute, chapters and buffered ranges come from native events. Missing reader timestamps or contiguous cache ranges leave the buffered range unknown. The UI requests a change and displays engine-confirmed state; it does not infer a successful change from a button click.

Picture input shares one gesture policy across the video host and its owned WinUI
input surface. A left single click toggles pause after the system double-click
interval; a double click cancels that pending single click and toggles fullscreen.
Title/caption, sliders, buttons, menus and other interactive elements do not become
picture gestures. Their input, keyboard commands, external deactivation, DPI changes
and teardown cancel pending actions. Closing uses the existing asynchronous native
teardown; gesture support does not establish that the open CFG exit defect is fixed.

Seeking and buffering after a frame has already played show a transient loading
ring in the accepted Screenbox HUD. The old video surface is hidden until the engine
reports a usable confirmed state, including paused playback after a seek; subtitle
decoding/flush remains the engine's responsibility. First-open buffering retains
the existing opening state instead of drawing two loaders. Repeated progress snapshots
retain the ring's state rather than restarting its animation. Real large-file,
subtitle/track and seek responsiveness still require native acceptance.

Display dimensions come from native video-output parameters. The first normal
window can fit the observed display aspect; a user-adjusted, maximized or
fullscreen window is retained. Original resolution is factual metadata; HLS
quality switching is not implied by that label.

Remote playback reads the existing account-bound server bookmark and user resume
preferences. The presented session decorates native snapshots with a real resume
offer, and saves only engine-confirmed positions. Writes are serialized per
session; an unknown result is reconciled before another write. Repository-created
resource references retain their original connection lifetime after reconnection.
Offline local files do not query or write remote playback history.

Subtitle preferences and temporary control-bar clearance are separate. Text subtitles can rise above visible controls without changing the saved position. Script-preserving ASS keeps author geometry unless the user explicitly enables style overrides. A local subtitle picker is scoped to its playback generation; a late selection cannot attach to a closed or replaced session. Bitmap font/scale controls are disabled until their renderer behavior is implemented and verified.

Diagnostics separately represent source format, decode path, display capabilities and actual output. Unavailable information is null/unknown. HDR input metadata, a 10-bit buffer or a normal-looking SDR screenshot cannot prove HDR output. Backdrop controller attachment and an enabled transparency setting likewise do not prove visible video-background blur; native visual acceptance remains required.

Runtime packages require pinned hashes, actual configuration/nonfree checks, complete import/runtime closure, notices and corresponding source/build materials before distribution. Engine load success alone does not establish rendering, compatibility or redistribution readiness.
