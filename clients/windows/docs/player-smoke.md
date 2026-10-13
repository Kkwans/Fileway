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
writer and bounded shutdown-evidence reader with an already installed .NET 6+
SDK. It clears all NuGet sources, uses only SDK framework references and never
calls Windows APIs. The reader tests cover field/type/range rejection, duplicate
keys, malformed/incomplete records, file/line bounds and numeric-only output.
If no SDK is found, that test is explicitly skipped; the other tests remain
stdlib-only. To select an existing private SDK, set `FILEWAY_SMOKE_DOTNET` to its
absolute executable. These checks do not execute or parse PowerShell itself.
A separate parser-only test uses an existing `pwsh` when available and otherwise
reports a skip; it does not execute the smoke or validate Windows behavior.

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
the real speed settings three times, following the More menu when the toolbar
is narrow; verifies pause with unchanged decoded
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
frame/exit observations and codes. On abnormal app exit, bounded diagnostic observations
may also include allowlisted compiled type/method names and a matching-process Windows
fault module basename/code/offset. Exception messages, executable paths and raw Windows
event XML are excluded. Numeric responsiveness evidence is saved before application
shutdown, so an exit failure does not discard completed interaction evidence.
Fault-event collection allows up to 20 reads during a five-second retry window
because Windows may publish the event after process exit. Each query is limited
to 16 events since this app's start time; exported faults must match its process
ID and executable basename. No Windows Error Reporting settings are changed.
Command timings include CLI startup and UIA
work; they are not application handler latency. The optional app-owned recorder
retains separately defined numeric UI-dispatch markers.

### Shutdown-stage breadcrumbs

The application shutdown recorder is off by default. The smoke launch opts in
with `FILEWAY_PLAYER_RESPONSIVENESS_DIAGNOSTICS=1`, using its isolated
`FILEWAY_WINDOWS_DATA_ROOT`. The recorder synchronously writes at most 16 records
to that private data root's `diagnostics/shutdown.jsonl`; the file itself is never
copied into the evidence directory or added to the artifact allowlist.

On abnormal app exit, `Record-SafeExitEvidence` reads only that fixed location.
It rejects reparse points, files over 4,096 bytes, more than 16 records, lines over
256 characters, invalid UTF-8, incomplete final lines and any nonconforming row.
All rows must validate before any shutdown-stage observation is exported. A row
must have exactly the case-sensitive keys `schemaVersion`, `stage`, `uiThread`,
with unquoted integer values: version 1, stage 1–13, and UI-thread bit
0 or 1. Duplicate/unknown keys, escaped names, strings, booleans, nulls, decimal or
exponent forms, leading zeroes and additional data are rejected. Key order and horizontal JSON
whitespace may vary; LF and CRLF record endings are accepted. Missing or rejected
optional evidence does not alter the app/Host exit assertions.

Validated records are rebuilt as numeric `schemaVersion`, `stage`, `uiThread`
details of fixed `shutdown-stage` observations in `events.jsonl`. The stage
values identify these reached points in the application-owned shutdown path:

1. Shutdown entered
2. Startup and connection task waits returned
3. Transfer-disposal attempt returned
4. Playback disposal about to begin
5. Playback-disposal attempt returned
6. Service and settings disposal attempts returned
7. Host-disposal attempt returned
8. Main-window close about to begin
9. Main-window close call returned
10. Main-window chrome's `Closed` callback entered
11. Main-window chrome's `Closed` callback returned
12. Main window's `Closed` callback entered
13. Main window's `Closed` callback returned

The four callback markers wrap the existing cleanup without catching its errors.
They distinguish an exception inside application-owned `Closed` callbacks from a
failure later in WinUI/native window teardown. A `Closed` notification itself is
raised inside WinUI's close operation, before native teardown completes. Thus the
normal nested order is 1–8, 10–13, then 9; stage numbers are identifiers rather than
a sort order. Other windows using the shared chrome do not emit these four main
window markers, so they cannot consume the shutdown recorder's bounded capacity.

Several markers follow exception-catching teardown blocks. They establish that
execution reached that point or an attempt returned, not that disposal succeeded.
Stage 7 does not establish Host process exit, and stage 9 does not establish app
process exit. Even all markers cannot replace exit code 0 for both owned
processes. A missing later marker alone cannot identify a native crash's cause.

### Opt-in native shutdown debugger

`-CaptureShutdownStack` is a temporary diagnostic mode, off by default in the
harness and enabled explicitly by the player workflow. It requires the already
installed x64 Windows SDK `cdb.exe` under Program Files (x86), with a valid
Authenticode signature whose publisher organization is Microsoft Corporation.
Missing or invalid tools block the run; this mode downloads no debugger and
changes no global WER, debugger, security or symbol settings. The hosted image's
[SDK debugger provisioning](https://github.com/actions/runner-images/blob/win25-vs2026/20260925.250/images/windows/scripts/build/Install-VisualStudio.ps1)
and Microsoft's [CDB options](https://learn.microsoft.com/en-us/windows-hardware/drivers/debugger/cdb-command-line-options)
describe that tool source and the attach controls.

The debugger attaches only after every interaction and player-surface-close
assertion passes, immediately before closing the main window. The target must
still match this harness's retained process object, PID, launch time and exact
application executable path. Attach has a 10-second deadline and a fixed ready
marker. `-pd` prevents debugger exit from killing the target; `-sins` ignores
ambient symbol paths and `-noshell` disables debugger shell commands. A targeted
`0xC0000409` exception command writes a minimal dump in the fresh private app-data
directory, then uses `gn` (not handled) so the original fault still terminates the
app. The existing 15-second exit deadline and nonzero-exit failure remain intact.

Offline analysis is bounded to 120 seconds, a dump of at most 64 MiB and 24 stack
frames. Symbols are resolved using Microsoft's public symbol server with a cache
inside the same private directory. Raw dumps, command files, debugger output,
module paths, memory, registers and symbol caches never enter the upload root.
The decoder accepts at most 1 MiB of UTF-8, 4,096 lines and 2,048 characters per
parsed line. Only the expected exception code, a bounded numeric fast-fail
subcode, explicitly allowlisted module names and explicitly allowlisted public
symbol names are exported as rebuilt observations. Unknown symbol text is never
exported. It can yield a module-relative offset only when private exception/return
addresses fit that allowed module's validated `lm` range; otherwise that frame
retains its module only. Duplicate exception or module records reject the evidence.
Frame offsets use `offsetKind=0` for unavailable, `1` for module-relative and `2`
for an allowlisted symbol-relative offset. Absolute addresses are never exported.

Reports include `shutdownDebuggerAttached` and `normalShutdownVerified`. An
attached-debugger run cannot establish normal production-path shutdown even when
its observed app and Host exits are zero. Capture/analysis failures are fixed
codes and cannot convert an abnormal application exit into a pass. A subsequent
run without the debugger is required to verify a shutdown fix.

Offline tests/compilation establish file and decoder contracts, not functioning
Windows capture, UIA delivery or native playback. An executed hosted Windows
smoke proves only those explicit assertions on that runner. It does not prove
TX5Pro performance, GPU hardware decode, HDR, video-background blur, visual
acceptance, remote streaming, or redistribution/license readiness.

Protocol references: [AVI RIFF](https://learn.microsoft.com/en-us/windows/win32/directshow/avi-riff-file-reference),
[pinned winapp UI commands](https://github.com/microsoft/winappCli/blob/v0.7.1/docs/ui-automation.md),
[pinned JSON envelopes](https://github.com/microsoft/winappCli/blob/v0.7.1/plugins/winapp/skills/winapp-ui-automation/references/ui-json-envelope.md).
