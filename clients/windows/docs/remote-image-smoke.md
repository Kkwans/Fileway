# Native remote image interaction smoke

`eng/Test-RemoteImageSmoke.ps1` is an independent Windows-only journey. It
requires a built native `Fileway.App.exe` with its actual Go Host, a built
`server/backend` executable, pinned winapp CLI 0.7.1, Python 3.12, and an
interactive Windows desktop. `-AppPath`, `-ServerPath`, `-WinAppPath` and
`-OutputRoot` are absolute paths; `-PreflightOnly` needs only the latter two.
Output must be outside Git and new/empty, or contain only this script's successful
preflight. A preflight failure is an environment blocker, never a native pass.

The workflow runs offline fixture/source/actual PowerShell helper guards before
provisioning. It runs the existing real desktop/UIA/three-color capture preflight
before building the app or starting the real server. No media package is needed:
this journey exercises WIC image decoding, not mpv playback. The dedicated
player workflow continues its own full media-runtime verification.

## Accepted journey

1. Create one unique private temporary server root/profile and a no-clobber,
   deterministic PNG fixture. The 480 × 320 image has red, green and blue vertical
   bands. Its 461,223 bytes have SHA-256
   `5a05232336535f96c4c698007a8e562019af15240b901fc3ecd4908b16f6874c`.
   Stored DEFLATE blocks make the bytes independent of compressor versions.
2. Start the real repository server in that root/database. Bind only
   `127.0.0.1`, select an ephemeral port, use its existing NoAuth mode, disable
   exec/thumbnails/preview resizing and drain both output streams without
   retaining them. Remove inherited `FB_*` overrides. Probe real `GET /health`.
3. Acquire a bounded in-memory synthetic verifier session from the real
   `GET /api/login` route of only the already-owned NoAuth server and allocated
   loopback port with redirects and URL fragments rejected. NoAuth still requires a JWT for `withUser` APIs; it does not
   disable that guard. The session is never printed, written, passed on a
   process command line or read from the app profile. Read the actual
   `GET /api/recent?limit=100` baseline with that session and require it empty.
   Connect the real app through its visible connection UI, with persistence off
   and explicit synthetic loopback HTTP consent. No credentials are involved.
4. Select the exact synthetic file row and send focused Enter through the
   pinned CLI's `send-input` route. Observe a distinct same-process image window
   with its exact synthetic title and the successful WIC-decoded 480 × 320
   notice. Maximize only through its actual own visible caption button and use
   its real Fit button. Require at least 32 captured rows containing ordered,
   substantial pure RGB bands before saving the owned-window screenshot.
5. Poll the actual server's read-only recent endpoint with the same transient
   verifier session. Require exactly one
   matching new display path, canonical wire path, file name, non-directory
   entry, verified identity, server-format ID and fresh millisecond timestamp.
   The script never POSTs a recent record or replaces the product's remote open
   with an HTTP content request. With the unique ASCII-unreserved filename,
   Go's segment escaping leaves the exact `/filename.png` wire path unchanged.
6. Close the actual image caption, observe that owned window disappear, then
   normally close the main app. Both the original app process and its captured,
   path/start-time-verified Host process must exit zero. Timeout, nonzero exit or
   forced teardown fails the journey. Failure remains failure during cleanup.

The maintained common helper imports only a fixed list of top-level function
ASTs from the transfer harness; it does not execute that harness or its journey.
Native capture/process ownership calls reuse the player harness's tested native
helper. The additional pixel policy is managed code with offline regression
checks. Control selection stays fresh, unique, visible and enabled; there are
no coordinate clicks or first-match fallbacks.

## Evidence boundary

Only these artifacts are allowed: `report.json`, `events.jsonl`,
`screenshots/preflight.png`, `screenshots/preflight-smoke.png`, and
`screenshots/remote-image-ready.png`. The screenshot is the guarded owned image
window containing the synthetic fixture. No picker/desktop/foreign window,
raw UIA tree, URL, credential, profile/database, log or local path is exported.
Reports contain fixed step/code strings and numeric/boolean checks. Private
unique fixtures are retained outside Git; no unrelated files are deleted.

A source/helper test pass is not image acceptance. Only a completed Windows
journey can set `remoteImageVerified=true`. This slice does not verify remote
video, the recent-list pane, transfer cancellation or unknown-result UI flows.
