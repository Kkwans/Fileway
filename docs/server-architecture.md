# One server core, one Web UI

Fileway maintains one backend in `server/backend` and one Web frontend in
`server/frontend`. Linux, Windows and later macOS build from this same source.
API routes, authentication, permissions, file/media operations, persistence and
task logic are shared. Client applications consume the same API contract.
The client-owned Go transport/control module is `clients/shared/core`, separate
from the server. Its portable components have native Windows unit-test coverage;
Android JNI and future Windows DLL/desktop adapters remain platform-specific.

## Operating-system boundary

Go chooses platform implementations inside the owning package:

- `files/identity_linux.go` and `identity_windows.go`: native file identity.
- `files/created_time_unix.go` (Linux build tag) and `created_time_windows.go`:
  birth time.
- `files/drivefs_windows.go`: Windows logical drives and scoped native paths.
- `files/directory_open_windows.go`: shared-delete directory handles.
- `http/volumes_windows.go`: logical drive enumeration; `volumes.go` owns the
  common response schema and ordinary filesystem discovery.

Common callers use the same functions and types. Unsupported native metadata
remains explicitly unavailable; fabricated timestamps or unsafe deletion
recommendations are not substitutes for a missing platform implementation.

macOS uses Go's `darwin` target. Its Web UI and common core do not need another
copy. Native filesystem metadata and packaging will be validated when macOS is
added; build support does not by itself establish native runtime acceptance.

Deployment profiles belong under `deploy/linux`, `deploy/windows` and, when
implemented, `deploy/macos`. RK3588 and vendor shared-folder discovery are
optional Linux capabilities. Ordinary Linux is supported without `/volume*`
directories; mount/usage paths are constrained to the configured server root.

## Compatibility identifiers

The old Go module path, existing `.nas-file-browser-trash` / cache names and
browser preference keys remain readable for upgrades. These names do not select
an operating system or constrain the product to NAS devices. Renaming stored
data would require an explicit migration and is unnecessary for source reuse.

Windows virtual paths `/C/...` and Linux paths `/volume2/...` are data in the
same API fields, not separate APIs. Scope and permissions apply on both systems.
The Web displays drive-specific data through shared components.
The common schema makes native metadata optional: `created` is omitted when
there is no filesystem birth time (including Windows' virtual computer root),
and `driveLetter` / `volumeLabel` are provided by drives that expose them.
Existing per-account branding, sorting, icon sizes and sidebar preferences remain
user data; identical Web source does not require overwriting those preferences.

The CI builds the Web once and embeds that exact artifact into both server
executables. Platform deployment metadata is recorded separately; user databases,
credentials, mounts and signing material are runtime state outside this repository.
Standalone Go builds must first embed the freshly built Web assets into
`server/backend/frontend/dist`; an old embedded build is not the current UI.
The root server workflow and the Linux multi-stage Dockerfile perform this step.
Linux packaging is centralized in `deploy/linux/Dockerfile` and `compose.yml`.
`profiles/rockchip.yml` and `profiles/ugreen.yml` add only optional hardware/vendor
capabilities; there are no separate `custom` or RK3588 server build trees.

## Sliding authenticated sessions

`tokenExpirationTime` belongs to each server instance and remains a Go duration
between 10 minutes and 30 days (`720h`). Android and Web edit the same
`GET/PUT /api/settings` field; neither client maintains a separate expiry policy.
Authenticated activity advertises `X-Renew-Token: true` once its JWT was issued
before the current second. `POST /api/renew` signs from the current server time
and configuration and returns `false` to prevent recursive renewal. Expired
ordinary JWTs require authentication; existing proxy/noauth rules are unchanged.

Clients process renewal at response headers, including Range, upload and long
streams, and merge concurrent renewals by immutable account/generation/token.
Resource activity adapters cover browser-native media without reloading its
source or creating a keepalive loop. Acknowledged business results survive a
renewal or local persistence failure. Recovery never replays a mutation, adopts
another account's token, or changes an existing resource lease owner. Native
clients expose local session health and may recover the same principal in place;
page state and drafts remain intact while the user retries the failed operation.

## Resource identity across clients

`path` and `name` in public responses are display text. `wirePath` encodes the
original filesystem bytes, segment by segment, and is the resource identity
within the authenticated server/account workspace. Equal display names need
not identify the same file. Clients must retain the original wire path through
selection, queues, navigation, media URLs and metadata refreshes.

Decode a wire path exactly once. For example, a filename containing the literal
text `%2F` uses `%252F` in its wire representation; it is not a directory
separator. JSON `path` in the older protocol remains literal UTF-8 text and must
not be URI-decoded. Traversal, encoded separators and NUL are rejected by the
shared request validators. Windows drive/scoped-path validation remains at the
filesystem boundary.

- Favorites expose `wirePath` and `pathVerified`. Creating a favorite accepts
  `wirePath`; an optional display `path` must agree with it. Older UTF-8 callers
  can still send `path`. A client supporting older servers sends both fields
  in its first UTF-8 request, rather than retrying a write after an error.
- Tags retain the compatible `paths` display list and add ordered
  `pathRefs: [{path, wirePath?, pathVerified}]`. References with equal display
  text remain distinct. Adding or removing an association accepts `wirePath`
  with the same UTF-8 compatibility rule. Addition checks current resource
  access/existence; removing an owned association does not require the file
  still to exist, so stale associations can be cleaned up.
- `pathVerified: false` marks a historical reference whose original identity
  cannot be recovered. Its display text must not be used to choose a similarly
  named resource. Clients preserve these rows and explain the unavailable
  action rather than guessing bytes or silently discarding the data.
- `POST /api/resources/batch` is a read-only metadata lookup. It accepts
  `wirePaths`, optionally with position-aligned compatible `paths`; the older
  `paths`-only request remains supported. Requests contain 1–500 paths and at
  most 1 MiB of JSON. Results preserve input order and include `path`,
  `wirePath`, `status`, and either `item` or `error` for each input. An error for
  one resource must not hide usable results for others. The endpoint does not
  read contents, expand directories or generate previews. Clients split
  requests to their transport budgets and match acknowledgements by wire
  identity, including failed entries.

Raw/preview URLs and media query parameters must preserve the encoded bytes
through the server's one URI/query decode. Re-encoding an already encoded wire
path selects a different literal filename. The legacy playback PUT and HLS
POST contracts still require JSON UTF-8 paths; clients must reject an opaque
source for these operations until an explicit wire contract is supported,
rather than submitting its display-name sibling.

Metadata synchronization after rename, move, deletion or restore resolves each
known owner's relative references in that owner's current filesystem workspace.
Different workspaces' equal relative paths are independent; references to one
shared resource are translated to each workspace's own relative path. Moving
out removes existing references without creating new references in another
workspace. Unknown owners or unverifiable historical bytes are not inferred.
Public display DTOs are separate from byte-preserving persistence records.


### Durable file operations

Authenticated `client-capabilities.resourceWireOperations` advertises three
additional identity-preserving mutation boundaries: transfer items accept
`fromWirePath`/`toWirePath`, pending resource deletions accept ordered `wirePaths`,
and resource PATCH accepts `destinationWirePath`. Ordinary UTF-8 callers retain
their compatible literal fields; transfer also accepts its legacy `/files` route
form. A simultaneously supplied literal and wire identity must agree. Opaque
clients must verify the capability before submitting, and never retry a failed
write against a display-name sibling.

Successful PATCH responses include `X-Resource-Destination-WirePath` for the
actual target, including automatic conflict suffixes. The older rename header
remains a display/UTF-8 compatibility field. A queued transfer response only
acknowledges task creation; it does not confirm that a requested target exists.

Private task arguments, results and checkpoints use `pathEncoding: "wire-v1"`
and encode original path bytes before JSON serialization. Replay restores wire
identity exactly once. Strict legacy UTF-8 task paths remain compatible; a lost
legacy identity containing replacement characters requires recreating the task.
New explicitly identified UTF-8 filenames containing U+FFFD remain supported.
Task replay arguments and results remain private to the backend API model.

A binary predating this codec cannot safely replay newly persisted opaque tasks.
After these writes, recovery requires a compatible forward fix. An older binary
may be considered only after proving no task, result or checkpoint depends on
the new codec; reverting the executable never authorizes discarding runtime data.
