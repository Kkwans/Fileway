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

## Teardown

1. Mark closing, fence new callbacks and preserve the latest confirmed position.
2. Detach XAML/native composition references on their owning UI thread, without blocking that thread on an event pump that needs the dispatcher.
3. Stop media reads, drain the necessary end events, stop callbacks/pump, and destroy the native player using the ordering verified against the pinned client API.
4. Revoke video and subtitle leases, then dispose temporary resources.
5. Destroy the surface/window after its native dependencies are released.

The native engine must remain alive while an App-owned COM reference or callback can still use it. A timeout is a failed teardown requiring recovery; it is not proof that native handles were released. The last known reference must not be freed while native work continues.

## Rendering and diagnosis

The current path uses mpv `gpu-next` / D3D11 with an HWND video surface and an owned, transparent WinUI controls window. The App synchronizes owner, bounds, DPI, fullscreen and teardown. Opening controls must not hide the video. The controls window is not permanently topmost over other applications.

The control window's native region is the union of the measured title and footer
bounds in physical pixels. Window-level SDK acrylic is applied only after that
region exists; the central movie area remains outside the material window.
Layout, DPI and control visibility changes refresh the region. Errors are shown
inside the footer so native clipping cannot hide them. Region failure hides the
HUD instead of applying material across the whole movie. Focus changes preserve
the visible chrome material; Windows transparency/accessibility policy still
controls its supported fallback. No custom Win2D blur graph or WebView is used.

The public libmpv render API provides OpenGL/SW backends, not a ready D3D11 WinUI surface. External swapchain injection is not used or verified by this implementation. Production acceptance requires actual layer, input, resize, DPI, fullscreen and device-recovery evidence. UI and engine must not independently overwrite video color settings; raw pointers never enter Core domain objects.

Playback rate, volume, mute, chapters and buffered ranges come from native events. Missing reader timestamps or contiguous cache ranges leave the buffered range unknown. The UI requests a change and displays engine-confirmed state; it does not infer a successful change from a button click.

Subtitle preferences and temporary control-bar clearance are separate. Text subtitles can rise above visible controls without changing the saved position. Script-preserving ASS keeps author geometry unless the user explicitly enables style overrides. A local subtitle picker is scoped to its playback generation; a late selection cannot attach to a closed or replaced session. Bitmap font/scale controls are disabled until their renderer behavior is implemented and verified.

Diagnostics separately represent source format, decode path, display capabilities and actual output. Unavailable information is null/unknown. HDR input metadata, a 10-bit buffer or a normal-looking SDR screenshot cannot prove HDR output. Backdrop controller attachment and an enabled transparency setting likewise do not prove visible video-background blur; native visual acceptance remains required.

Runtime packages require pinned hashes, actual configuration/nonfree checks, complete import/runtime closure, notices and corresponding source/build materials before distribution. Engine load success alone does not establish rendering, compatibility or redistribution readiness.
