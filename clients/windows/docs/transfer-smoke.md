# Native single-file transfer smoke

`eng/Test-TransferSmoke.ps1` drives the real built Windows App and Go Host against
the repository's real server. It never substitutes a mock API for a transfer or
uses a NAS, personal computer, password, saved login or external server.

## Inputs and isolation

Use a disposable Windows CI runner with an unlocked interactive desktop, the
hash-pinned winapp CLI 0.7.1 and the normal built native application/runtime. Run
the PowerShell parser and offline source guards before provisioning. Run true
preflight before application/runtime downloads; the full journey repeats it.

```powershell
./eng/Test-TransferSmoke.ps1 -PreflightOnly -WinAppPath $winapp -OutputRoot $evidence
# From server/backend, using the repository's isolated Go environment:
go build -o $serverExe .
# From clients/windows, after the normal native App/Host build:
./eng/Test-TransferSmoke.ps1 -AppPath $appExe -ServerPath $serverExe -WinAppPath $winapp -OutputRoot $evidence
```

All executable paths and the evidence directory must be absolute. Evidence must
be new/empty or contain only the successful preflight's fixed allowlist. Private
runtime state is a newly generated temporary directory outside Git. The server
binds only `127.0.0.1`, uses a private database/root/HLS directory, noauth and
disabled execution/previews. Inherited `FB_*` configuration is removed. Quick
setup's unused password and all server output are drained without retention.

`server/backend/frontend/assets.go` embeds the tracked `dist/README.txt`, so the
native-only API server can compile without generated Web assets. The static
index is loaded when requested, not to serve `/health` or the native APIs. This
smoke is not Web packaging validation; normal unified-server CI builds and
embeds the actual Web artifact. Server build, ready probe and APIs must pass on
the actual Windows runner; source inspection alone is not runtime evidence.

The App's existing `FILEWAY_WINDOWS_DATA_ROOT` isolates its database and private
credentials directory. UI connects to the synthetic server using noauth with
keep-signed-in off and explicit HTTP consent. The actual Downloads Known Folder
must already exist and be usable. A unique `fileway-ci-<random>.bin` name is
checked absent before submitting. The App must show that exact default target
in its native confirmation; no setting, safety guard or destination is changed.

## Verified journey

1. True native desktop/UIA/three-color pixel preflight, reused from player smoke.
2. Native single-file picker, upload confirmation to remote root, completed UI
   and exact one-MiB deterministic payload length/SHA256 in the server root.
3. Native row selection and download confirmation, completed UI and matching
   length/SHA256 at the explicitly confirmed local target.
4. Repeated download produces the real local-name conflict and preserves bytes.
5. Same-name upload of different synthetic bytes produces the real remote-name
   conflict and preserves the original bytes.
6. Normal application close and owned Host exit both return zero within budgets.

Only explicit UI actions initiate transfers. HTTP is used solely for `/health`.
UIA selectors are freshly inspected and required to be unique, enabled and
visible; mismatched actual UI fails rather than guessing a coordinate. Stage
polls, CLI commands, startup and shutdown are bounded. An application or Host
nonzero exit fails the journey even if screenshots and byte checks succeeded.
Forced teardown after failure does not satisfy normal shutdown.

Cancel, resume, pause, uncertain publish/cleanup and local-reconcile UI paths are
not exercised here. Reports keep cancellation/unknown UI verification false.
Small loopback payloads can finish before intermediate progress is observed;
this deterministic journey validates real confirmation/terminal/conflict states,
not a fabricated activity phase or a probabilistic large-file cancellation.

## Evidence and cleanup

Upload only `report.json`, `events.jsonl` and the script's explicitly named PNGs
under `screenshots`. Captures are confined to the verified owned App HWND and
use player smoke's tested foreground/foreign-window safety checks. Never capture
the system picker. Do not upload UIA trees, CLI output, exception text, paths,
URLs, databases, logs, Host capabilities or tokens. Reports contain fixed stage
codes, byte counts, hash equality and exit-code checks, not raw runtime data.
Connection actions have fixed individual stage codes. An ambiguous control
records only numeric match/unique-selector counts and control-type counts.
`connect-failure.png` is allowed only when the isolated synthetic App fails on
its connection/browser-ready surface; the same owned-window/foreground guards
apply. It may show the synthetic name and local loopback address, never a picker,
foreign window or a raw UIA tree. Failure capture never turns a failed run into a
pass; a rejected capture records only a numeric saved/not-saved observation.

No file is recursively or wildcard-deleted. Unique synthetic input, private
server/App state and the exact downloaded file are left on the disposable runner
for its normal teardown; unrelated Downloads entries are neither inspected nor
modified. A failed environment preflight is reported as environment-blocked,
not as a passing transfer. A passed offline source test does not establish that
PowerShell parsed or the native journey ran; those are separate CI gates.
