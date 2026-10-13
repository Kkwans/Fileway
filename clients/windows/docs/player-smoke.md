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

In the default Full journey, the debugger attaches only after every interaction
and player-surface-close assertion passes, immediately before closing the main
window. The two explicitly scoped diagnostics attach after their own preceding
assertions; MainWithOpenPlayer intentionally still has its player open. The target must
still match this harness's retained process object, PID, launch time and exact
application executable path. Attach has a 10-second deadline and a fixed ready
marker. The existing attach command records one private, delimited `lm o`
loaded-module snapshot before that marker; no additional debugger attachment,
detachment or symbol download is introduced. Immediately after readiness, the
harness rechecks that the retained process is alive with the same start time and
executable path before it can request main-window close. The bounded ready reader permits concurrent debugger writes and accepts
only a complete marker line, not the echoed command. Attach failures retain only
a fixed numeric location and HResult. `-pd` prevents debugger exit from killing the target; `-sins` ignores
ambient symbol paths and `-noshell` disables debugger shell commands. A targeted
`0xC0000409` exception command writes a minimal `/muF` dump (unloaded-module and basic memory-layout metadata; uppercase `F` adds no memory pages) in the fresh private app-data
directory, then uses `gn` (not handled) so the original fault still terminates the
app. The existing 15-second exit deadline and nonzero-exit failure remain intact.

Offline analysis has a cumulative 120-second process-wait budget, a dump of at
most 64 MiB and 24 stack frames. A conditional second analysis gets at most
30 seconds of that remaining budget. Symbols are resolved using Microsoft's public symbol server with a cache
inside the same private directory. Raw dumps, command files, debugger output,
module paths, memory, registers and symbol caches never enter the upload root.
The decoder accepts at most 1 MiB of UTF-8, 4,096 lines and 2,048 characters per
parsed line. Only the expected exception code, a bounded numeric fast-fail
subcode, explicitly allowlisted module names and explicitly allowlisted public
symbol names are exported as rebuilt observations. At most 15 exception parameters
are accepted. Parameter 0 is the numeric fast-fail code; other parameters are
never exported as addresses. Each yields only its index and a unique allowed
module-relative offset, with mapping 0 (unavailable/ambiguous), 1 (loaded), or 2
(unloaded). A CFG exception may have only parameter 0; absence of another
parameter does not identify the invalid call target. Legacy logs without a
`NumberParameters` line retain `parameterCount=-1` and export no parameter
mappings. Only fixed basename aliases, including the shipped `libmpv-2.dll`,
are accepted in unloaded listings; path-bearing names remain rejected. Unknown
loaded/unloaded module ranges participate anonymously in overlap rejection, so
an unknown image reusing an unloaded known image's address cannot be mislabeled
as that known image. At most 512 anonymous ranges and 64 total unloaded ranges
are admitted; no unknown name or address is retained in the output. Current
logs delimit `lm` output explicitly to prevent disassembly from looking like a
module row. Legacy unmarked logs admit only module-list-shaped unknown rows.
Unknown symbol text is never
exported. It can yield a module-relative offset only when private exception/return
addresses fit that allowed module's validated `lm` range; otherwise that frame
retains its module only. Duplicate exception or module records reject the evidence.
Frame offsets use `offsetKind=0` for unavailable, `1` for module-relative and `2`
for an allowlisted symbol-relative offset. Absolute addresses are never exported.

The same offline analysis reads at most 24 instructions starting at the exact
public symbol `ntdll!RtlpHandleInvalidUserCallTarget`, using the bounded
[unassemble command](https://learn.microsoft.com/en-us/windows-hardware/drivers/debuggercmds/u--unassemble-).
The live process's ntdll image must match the Microsoft-signed system image.
This code-only diagnostic establishes the helper's actual calling convention
before any proposed single-value capture; it does not identify a runtime target.
Raw disassembly stays private. `shutdown-cfg-helper` uses status 0 (absent), 1
(validated), or 2 (rejected). Only contiguous code inside the loaded ntdll range
is accepted. Rebuilt instruction records contain a helper-relative offset,
fixed opcode/register names, bounded stack-slot displacements, and allowlisted
ntdll call/branch symbols with relative offsets. Operand kinds are 0 (absent or
unknown), 1 (register), 2 (stack slot), 3 (immediate with its value discarded),
or 4 (allowlisted symbol). An unknown opcode or operand has `decoded=0` and no
operand data. Instruction bytes, immediate values, absolute addresses, arbitrary
operands and annotations are never exported. Invalid boundaries, missing markers,
oversized records or more than 24 instructions reject the entire helper section
without losing the separate exception/stack evidence.

An additional four-instruction tail is read immediately before the observed
helper return offset `+0x5d`. It must remain in the same loaded ntdll image,
contain exactly four contiguous instructions and end at that offset. The same
enum-only decoder and privacy rules apply. A candidate `helperArgument` mapping
is enabled only when this run's final three instructions are exactly
`mov rdx,rbx` at `+0x50`, `mov ecx,0x0a` at `+0x53`, and a direct call to the
allowlisted `ntdll!RtlFailFast2` at `+0x58`, with the matching two top stack frames
and CFG subcode 10. Only then can the unique RDX value from the existing bounded,
explicitly delimited `.ecxr` context produce a loaded/unloaded module-relative
mapping. No extra register-read command or live breakpoint is added. Missing,
duplicate or malformed contexts and mismatched data flow produce no candidate.
This is the helper's candidate argument, not proof of the invalid call target
or its cause; entry-argument semantics still need independent caller evidence.
Its fixed `classification` distinguishes 0 (overlapping/ambiguous), 1 (zero),
2 (unique known loaded image), 3 (unique known unloaded image), 4 (unique anonymous
loaded image), 5 (unique anonymous unloaded image), and 6 (outside the observed
module ranges). This classification uses only the already parsed private value
and range metadata, without a new memory read. Classes other than 2/3 always
retain mapping 0, empty module and zero offset; none reveals a raw address or an
unknown module name. Class 6 does not establish whether memory is valid, freed,
managed or executable.

Only classification 6 enables one additional address-qualified
[`!vprot @rdx`](https://learn.microsoft.com/en-us/windows-hardware/drivers/debuggercmds/-vprot)
query in that same private dump. This command reports basic protection metadata
and supports dump debugging. It never invokes `!address` classification, omits
the address argument, reads memory pages, or scans registers. The second
pass repeats the exact bounded exception/context/frames/helper-tail validation;
its unique candidate must equal the first pass's private candidate before any
region evidence is accepted. The dump's uppercase
[`/mF`](https://learn.microsoft.com/en-us/windows-hardware/drivers/debuggercmds/-dump--create-dump-file-)
option includes basic memory layout, not lowercase `/mf` full memory.

The single region section admits at most 64 lines within the existing 1 MiB /
4,096-line limits. Only these seven unique field names are accepted: BaseAddress, AllocationBase,
AllocationProtect, RegionSize, State, Protect and Type. BaseAddress, RegionSize
and a validated State are always required. COMMIT requires all seven; FREE may
omit undefined allocation/protection/type fields; RESERVE may omit only Protect. BaseAddress plus nonzero
RegionSize must not overflow and must privately contain the candidate in a
half-open range. Allocation fields are validated but never exported. Only rebuilt numeric `shutdown-cfg-region` fields leave the
private directory: State (`0x1000` committed, `0x2000` reserved, `0x10000` free),
Type (`0x20000` private, `0x40000` mapped, `0x1000000` image), and Protection.
Protection requires one known PAGE base bit (`0x01` through `0x80`) and only valid
combinations of GUARD (`0x100`), NOCACHE (`0x200`) or WRITECOMBINE (`0x400`). Numeric
values must match the fixed symbolic tokens; unknown bits, duplicate fields,
malformed/unsupported output or insufficient dump metadata reject the section.
CFG-operation flag aliases are not accepted as protection values. Free regions
export Type=0 and Protection=0, and reserved regions export Protection=0, because
these fields are
[undefined](https://learn.microsoft.com/en-us/windows/win32/api/minidumpapiset/ns-minidumpapiset-minidump_memory_info)
in those states. Printed undefined values are discarded, never interpreted.
Present undefined fields must still use the fixed numeric/token grammar, but
their absence is permitted only after State validates. They are never used to
infer an allocation or protection state. This supports the observed five-field
FREE output shape without treating a missing required field as a free region. No region address, size, allocation detail, name or raw
line is exported. Failure emits only `shutdown-cfg-region-result` status 2
(rejected/unavailable) or 3 (timeout). Status 2 includes a fixed numeric `reason`:
0 other unavailable error, 1 debugger process failure, 2 original candidate
validation, 3 repeated fault/data-flow validation, 4 context mismatch, 5 bounded
section/field shape, 6 address/interval validation, or 7 fixed enum validation.
These reasons never carry exception messages or raw values.
Timeouts may also include `phase`, derived
only from complete, unique, ordered existing marker lines: 0 unavailable,
1 context begin, 2 context end, 3 tail begin, 4 tail end, 5 region begin,
6 region end. An echoed command is not a marker; duplicates or reordered markers
produce phase 0. The timeout remains a failure even if a later marker was seen.
A free or executable result describes the
candidate region at capture time; it does not establish the target's semantic
identity, ownership, CFG validity, or who released it.

Only a successfully revalidated MEM_FREE candidate enables comparison with that
same session's pre-close snapshot. Microsoft documents
[`lm o`](https://learn.microsoft.com/en-us/windows-hardware/drivers/debuggercmds/lm--list-loaded-modules-)
as listing only currently loaded images. The reader requires the stack, region
and snapshot files to share the fresh private directory, revalidates the original
fault/context and the second-pass region, and accepts one exact bounded module
section. All ranges participate in overlap rejection, including anonymous unknown
images. Unknown module names are never retained or exported. Malformed rows,
duplicate known modules, multiple sections, ranges larger than UInt32, or more
than 512 anonymous ranges reject the snapshot. The existing log/line bounds and
attach/analysis deadlines remain unchanged.

`shutdown-cfg-pre-close-module` exports only mapping 1 with an existing allowlisted
module and a validated relative offset, or mapping 0 with an empty module and zero
offset. Mapping 1 means the free-region candidate lay inside that image's range
at the pre-close observation; it does not establish the argument's semantic role
or which code freed it. Snapshot rejection emits only fixed status 2. This capture
does not retain a DLL, change window/engine disposal order, or suppress the fault.
The raw snapshot stays in the already private debugger log, outside the artifact
allowlist.

Reports include `shutdownDebuggerAttached` and `normalShutdownVerified`. An
attached-debugger run cannot establish normal production-path shutdown even when
its observed app and Host exits are zero. Capture/analysis failures are fixed
codes and cannot convert an abnormal application exit into a pass. A subsequent
run without the debugger is required to verify a shutdown fix.

### Existing internal responsiveness intervals

When the bounded numeric responsiveness report is copied, the harness emits
`player-internal-timing` records once, without adding product instrumentation.
Metric 1 pairs SettingsInput with SettingsActivateEnd; metric 2 pairs a
FullscreenInput/NativeFullscreenInput through an intervening FullscreenUiEntered
with the matching FullscreenPresenterEnd; metric 3 pairs FullscreenUiEntered
with its matching FullscreenPresenterEnd. Each numeric record retains the start
event ID, settings action or fullscreen state, endpoint sequence numbers and
elapsed microseconds. Input is limited to 512 validated entries. Missing entries,
overlapping starts, mismatched states, absent endpoints and negative intervals
produce no sample. A malformed report suffix rejects the whole derived set.
`player-internal-timing-result` status 1 means parsing finished (possibly with
zero usable samples); status 2 means unavailable. Neither grants latency
acceptance or changes any interaction/exit gate.

These are paired internal event intervals, not operation-ID correlation or
physical input-to-visible-pixel measurements. CLI command durations include
process startup, discovery and automation overhead; whole-step deltas also
include deliberate waits and screenshots. Neither should be relabeled as UI
response latency. Presenter/activation completion does not establish when the
compositor first presented the new pixels.

### Separate shutdown diagnostic scenarios

`-ShutdownScenario` defaults to `Full`, preserving the complete interaction and
exit gates. Two explicitly selected diagnostic scenarios isolate shutdown history:

- `MinimalPlayback`: prove advancing synthetic video, then use the normal player
  close control and close the main application. Settings, pause/resume and
  fullscreen cycles are outside this diagnostic's scope.
- `MainWithOpenPlayer`: perform the complete interaction sequence up to player
  close, leave that player open, and request normal main-window `WM_CLOSE` so the
  application's existing shutdown path owns disposal of the live session.

The latter identifies exactly one visible, unowned main window of the retained
app PID using fixed product titles and absence of the synthetic video child. It
revalidates that identity before posting `WM_CLOSE`, rather than allowing
`Process.CloseMainWindow` to select the still-open player. Missing or ambiguous
identity fails the diagnostic. Neither scenario calls disposal directly or
changes the application. Each must still observe app and owned Host zero exits.
Final reports use `scope=shutdown-diagnostic`, name the scenario, and always keep
`fullJourneyVerified=false` and `nativePlaybackVerified=false`; a diagnostic pass
cannot substitute for the default full journey or an unattached-debugger shutdown.
Run each scenario with a fresh isolated app process and output root. The separate
diagnostic workflow builds once and runs both scenarios even if the first fails,
then fails the job if either exit gate failed. Its artifacts retain the same
JSON/synthetic-PNG allowlist, never private debugger files.

Offline tests/compilation establish file and decoder contracts, not functioning
Windows capture, UIA delivery or native playback. An executed hosted Windows
smoke proves only those explicit assertions on that runner. It does not prove
TX5Pro performance, GPU hardware decode, HDR, video-background blur, visual
acceptance, remote streaming, or redistribution/license readiness.

Protocol references: [AVI RIFF](https://learn.microsoft.com/en-us/windows/win32/directshow/avi-riff-file-reference),
[pinned winapp UI commands](https://github.com/microsoft/winappCli/blob/v0.7.1/docs/ui-automation.md),
[pinned JSON envelopes](https://github.com/microsoft/winappCli/blob/v0.7.1/plugins/winapp/skills/winapp-ui-automation/references/ui-json-envelope.md).
