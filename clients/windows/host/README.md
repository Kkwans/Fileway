# Fileway Windows Host

This independent Go module adapts the unchanged shared
`github.com/Kkwans/nas-file-browser-client/core` Engine to the frozen private
[IPC v1](../docs/ipc-v1.md). It does not expose a control TCP listener. The WinUI
parent starts the co-located executable with private inherited pipes and owns its
kill-on-close Job Object.

Use the isolated environment from a PowerShell whose working directory is this
module. Keep every executable, temporary file, cache, and evidence file outside Git:

```powershell
. '..\eng\Use-WindowsEnvironment.ps1' -ArtifactsRoot 'C:\FilewayArtifacts\local'
$env:CGO_ENABLED = '0'
go test ./...
go vet ./...
New-Item -ItemType Directory -Force -Path (Join-Path $env:FilewayArtifactsRoot 'host') | Out-Null
go build -trimpath -tags=ts_omit_logtail -ldflags '-X main.hostVersion=0.1.0 -X main.buildCommit=unknown' -o (Join-Path $env:FilewayArtifactsRoot 'host\Fileway.Host.exe') ./cmd/fileway-host
```

The Windows build uses `ts_omit_logtail` to keep embedded-network authorization links out of Tailscale's disk log buffer and uploader.

The build entry point is `./cmd/fileway-host`. `main.hostVersion` and
`main.buildCommit` are the exact linker injection symbols. When no commit is
injected, the Host uses actual Go VCS metadata if present and otherwise reports
`unknown`. `init` reports Go, module/dependency, safe build settings, and explicit
capacity/cancellation capabilities. Potentially sensitive linker flags are not
returned. Normal successful responses always include `result`, including null.

Eight business calls run with 64 additional queued calls. Two control workers
have a separate 16-item queue. The reader never waits for a busy worker or a
response write. Terminal shutdown has an independent single slot so blocked
control calls cannot delay the cleanup deadline. Once a session close is
accepted, cancellation cannot discard its core cleanup obligation. All operation
parameters must use canonical core field names;
reserved identity fields, duplicate JSON keys, and the raw `bodyBase64` exception
are rejected. Actual encoded core commands remain limited to 1 MiB. Media and
file bytes use core-issued resource leases rather than the IPC channel.

Request identities remain outstanding until stdout finishes their frame. Each
accepted request uses a separate, never reused internal core cancellation ID.
Queued cancellation and session closure prevent dispatch. A confirmed core
success survives late cancellation. Untyped core failures retain the existing
`error` text and receive `CoreRejected`; no English-text classification occurs.

EOF, malformed input, output backpressure, or `shutdown` initiates terminal
cleanup. The Host allows up to three seconds total for cancellation, draining,
core shutdown, and serialized output. It then closes the pipes and exits even if
the core or parent stopped responding. Accepted calls whose results could not be
observed remain subject to the client's unknown-outcome reconciliation rule.
Output buffers are limited to 128 frames and 32 MiB total including a blocked
write. stderr has only up to eight fixed, short diagnostic lines and contains no
request or exception contents.

`go test ./...` includes fake-boundary scheduling tests and real subprocess tests
against local HTTP fixtures. Tests build the executable in Go's temporary
directory, which the environment script places in the external artifacts root.
They do not use NAS addresses, personal credentials, or production services.
