# Private native player smoke

`eng/New-PlayerSmokeFixture.py` creates deterministic, indexed AVI 1.0 with one
uncompressed bottom-up BGR24 stream. It needs only Python's standard library.
The default is 160×90, 12 fps, 180 seconds (2,160 frames; 93,364,072 bytes).
Four central color fiducials, a 16-bit pixel counter and a separate moving purple
marker are rendered into the video. They are not UI labels or subtitle overlays.
Private fixtures and their `.fixture.json` companions must stay outside Git.
Existing destinations, symlinks and Windows junctions are rejected. Windows path
checks require Python 3.12+; no encoder, decoder or downloaded media is required.

```powershell
python eng/New-PlayerSmokeFixture.py --output "$env:RUNNER_TEMP\fixture\sample.avi" --duration-seconds 180
python -m unittest discover -s eng/tests -p "test_*.py" -v
```

The optional harness contract test compiles the production pixel decoder/PNG
writer with an already installed .NET 6+ SDK. It clears all NuGet sources, uses
only SDK framework references and never calls Windows APIs. If no SDK is found,
that test is explicitly skipped; the other tests remain stdlib-only. To select
an existing private SDK, set `FILEWAY_SMOKE_DOTNET` to its absolute executable.

## Desktop gate and application run

`eng/Test-PlayerSmoke.ps1` requires PowerShell 7.2+ and the separately installed,
hash-pinned winapp CLI 0.7.1. The existing `Install-WindowsTools.ps1` places it at
`<ToolsRoot>\winapp-0.7.1\winapp.exe`. The smoke harness installs nothing and does
not build, restore packages, publish, upload artifacts or change application UI.

```powershell
./eng/Test-PlayerSmoke.ps1 -PreflightOnly -WinAppPath $winapp -OutputRoot $evidence
./eng/Test-PlayerSmoke.ps1 -AppPath $builtExe -MediaPath $syntheticAvi -WinAppPath $winapp -OutputRoot $evidence
```

Paths must be absolute. An output directory must be new/empty, or contain only
this harness's completed preflight evidence when starting the full smoke. The
full smoke pins the default 180-second fixture's byte hash, rather than trusting
a caller-written manifest. A read-share-only media handle fences later writers.
Application data is isolated in a newly created private temporary directory
separate from the evidence directory; no saved account, NAS or credential is used.

Preflight verifies an interactive non-session-zero Default desktop, creates an
ephemeral synthetic three-color window, inspects its actual UIA tree and checks
the actual colors of winapp's screen capture. Missing desktop/capture/UIA is
`environment-blocked`, exit 2. This gate can run before downloading a media
runtime or building the app. Exit 0 here establishes only `scope=preflight`,
`status=environment-ready`, `nativePlaybackVerified=false`.

Full smoke launches the supplied built app with the sole local media argument.
It checks two independently decoded video frames advance; opens and dismisses
the real speed settings three times; verifies pause with unchanged decoded
frames and resume with advancing frames; checks button and F11 fullscreen
against physical monitor bounds; and requires player/HUD/settings HWND teardown.
It then closes the main application normally and verifies app exit code 0 and
exit code 0 for the uniquely identified Host directly created by that app. A
Host process handle, executable path and creation time fence PID reuse. A timeout
or nonzero exit fails before the success report; forced cleanup never counts as
successful shutdown. Finally cleanup is best-effort and touches only owned
processes. Private failed-run state stays outside the evidence upload root.

Settings screenshots use nonactivating screen-DC capture of the player's region,
with the settings window still foreground before/after capture. This preserves
the real screen layering. Foregrounding/screen-capturing the player through
winapp would light-dismiss the menu and is deliberately avoided. Capture refuses
when a foreign process obscures the region or the foreground leaves the test app.

Only a complete full smoke may write `scope=native-smoke`, `status=passed`,
`nativePlaybackVerified=true`. Product assertion failures exit 1; environment
blocks exit 2. Starting full smoke replaces the preflight report with a running
native-smoke report before launch. An earlier preflight report left by a failed
build/restore is explicitly scoped and cannot establish a native playback pass.

## Evidence allowlist and limits

The only exportable files are `report.json`, `events.jsonl`, the optional strictly
numeric `player-responsiveness.json`, and these fixed names under `screenshots/`:

- `preflight.png`, `preflight-smoke.png`
- `moving-1.png`, `moving-2.png`
- `settings-1.png`, `settings-2.png`, `settings-3.png`
- `paused-1.png`, `paused-2.png`, `resumed-1.png`, `resumed-2.png`
- `fullscreen.png`, `windowed.png`

Do not upload application executables/DLLs, AVI/manifest files, package caches,
private app data, databases, raw CLI/UIA/app output or recursive diagnostic folders.
Events retain only fixed stage/action identifiers, elapsed durations, numeric
frame/exit observations and codes. Command timings include CLI startup and UIA
work; they are not application handler latency. The optional app-owned recorder
retains separately defined numeric UI-dispatch markers.

Offline tests/compilation establish file and decoder contracts, not functioning
Windows capture, UIA delivery or native playback. An executed hosted Windows
smoke proves only those explicit assertions on that runner. It does not prove
TX5Pro performance, GPU hardware decode, HDR, video-background blur, visual
acceptance, remote streaming, or redistribution/license readiness.

Protocol references: [AVI RIFF](https://learn.microsoft.com/en-us/windows/win32/directshow/avi-riff-file-reference),
[pinned winapp UI commands](https://github.com/microsoft/winappCli/blob/v0.7.1/docs/ui-automation.md),
[pinned JSON envelopes](https://github.com/microsoft/winappCli/blob/v0.7.1/plugins/winapp/skills/winapp-ui-automation/references/ui-json-envelope.md).
