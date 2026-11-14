# Media library API

The instance-local namespace is `/api/media-library/v1`. The library management,
local-source and scan endpoints below are implemented when an optional catalog
and its exclusive scan coordinator are available. They do not provide playback.
The full `mediaLibraryVersion` capability is withheld until the complete access
boundary and client journey are integrated.

## Authentication and visibility

Requests use the existing authenticated account session. Account identity is never
accepted from a query or ownership field. An ordinary file Download permission is
not required merely to list a shared catalog. Membership does not itself authorize
source bytes, source credentials, directory browsing or filesystem modification.

Private or revoked libraries are indistinguishable from missing libraries (404).
An archived library is visible only to its owner; restoration does not restore a
previously revoked member. Responses set `Cache-Control: no-store`. Library
summaries never include roots, native paths or configuration/credential references.
An authorized owner can see an accessible source's workspace wire path in the
separate source management response.

TV device-session routes are separately allowlisted. A TV media credential cannot
call the management or scan endpoints, even when its account owns the library.

Instance operators may set `--disableMediaLibrarySharing` or
`FB_DISABLE_MEDIA_LIBRARY_SHARING=true` at server startup. The default permits
explicit sharing. When disabled, new grants and role changes are refused, and
existing shared libraries are hidden from non-owners before pagination. Owners
can still revoke grants. The grants remain stored and become visible again when
sharing is re-enabled, except for grants explicitly revoked. Configuration changes
take effect on server restart. Future catalog, image and playback routes must
check this same policy as well as the current membership and source authority.

## List libraries

`GET /api/media-library/v1/libraries`

Optional parameters:

| Parameter | Meaning |
| --- | --- |
| `limit` | 1–100, default 50 |
| `cursor` | Previous response's `nextCursor`; at most 128 bytes |
| `archived` | Literal `true` or `false`, default false; true lists owned archives |

The response is `{ "items": [...], "nextCursor": "..." }`. An empty result uses
an empty array. `nextCursor` is absent on the last page. Pagination uses stable
library IDs and filters caller visibility before applying the limit. It is not a
database snapshot: clients must invalidate page state on account/instance changes
and refresh after relevant permission changes.

## Read a library

`GET /api/media-library/v1/libraries/{id}` returns one summary:

| Field | Type | Meaning |
| --- | --- | --- |
| `id` | string | Opaque instance-local library ID |
| `name` | string | Display name; not a source locator |
| `revision` | integer | Catalog configuration revision |
| `role` | string | `owner`, `editor`, or `viewer` for this caller |
| `archived` | boolean | Owner-visible archived state |

The response does not publish a playability flag or a derived source permission.
An editor's role permits future metadata operations, not member management.

## Mutation and idempotency

Management mutations require an `operationId` supplied by the caller. Repeating
the same operation and parameters for the same actor returns the saved original
acknowledgement; using that operation ID for different parameters within the
same operation type returns 409. Update operations also require the current
`expectedRevision`; scan cancellation and retry use `expectedGeneration`.
The receipt is committed in the same transaction as the change. Clients should
read current state after a replay instead of treating an old acknowledgement as
a fresh status. Each endpoint still validates the requesting identity and its
management contract; an acknowledgement never grants permission to read media.

Source creation and attestation retain their logical operation identity across
internal proof-version changes. A matching committed operation returns its saved
acknowledgement without opening the root again or capturing a new proof. The
original availability and revisions describe that operation's result, not current
source health. Refresh the source list after recovery. Changed workspaces or
revoked source permissions still prevent current catalog disclosure, scans and
playback; replay does not restore them. A source-management operation ID cannot
be reused for another business operation or payload.

| Method and path | JSON body | Result |
| --- | --- | --- |
| `POST /libraries` | `operationId`, `name` | Owner library summary; 201 |
| `PATCH /libraries/{id}` | `operationId`, `expectedRevision`, `name` | Updated summary |
| `POST /libraries/{id}/archive` | `operationId`, `expectedRevision` | Archived summary |
| `POST /libraries/{id}/restore` | `operationId`, `expectedRevision` | Restored summary |
| `PUT /libraries/{id}/members/{userId}` | `operationId`, `expectedRevision`, `role` | Member revision and library revision |
| `DELETE /libraries/{id}/members/{userId}` | `operationId`, `expectedRevision` | Revoked member revision and library revision |

Member roles are `viewer` or `editor`. Only the owner can change members; an
editor is not implicitly allowed to share. Archives preserve library data and
do not revive revoked memberships when restored.

## Local sources

`POST /libraries/{id}/sources/local` accepts `operationId`, `expectedRevision`
for the library, and `wireRoot`, the existing owner-workspace path encoded with
the same wire-path rules as the file API. The server resolves the current owner,
applies current rules, and safely opens that directory before creating a source.
It selects the registered local-workspace configuration itself; `ownerId`, a
native path, arbitrary configuration references, and unknown body fields are
not accepted. The response is 201 with an opaque binding `id`, `sourceId`,
`libraryId`, `revision`, `libraryRevision`, `authorizationRevision`, `access`,
and, when accessible to its owner, `wireRoot` and `displayRoot`.

`GET /libraries/{id}/sources` returns owner-only `{items, nextCursor?}` with
`limit` 1–100 (default 50) and an optional `cursor`. Each source is rechecked
against current owner authority and the previously attested workspace/root
identity. `access` is `available`, `offline`, `unverified`, `rebind_required`,
or `unavailable`; unverified, changed or denied mappings omit root paths.
Previously attested roots that are genuinely offline retain their records.
Source lists for
an archived library return 409 until its owner restores it. No source response
includes credentials or a configuration reference.

`POST /libraries/{id}/sources/{bindingId}/attest` is an explicit owner action
with `operationId`, the binding's `expectedRevision`, and a user-selected
`wireRoot` matching that binding. It safely opens the current directory and
records a new proof epoch atomically. An old proof or different workspace is
never silently adopted; old-epoch source observations and NFO projections are
not automatically inherited. A retry with the same operation ID returns the
original acknowledgement without advancing the epoch again.

Schema 8 adds internal v2 mapping proofs and mount-domain state while preserving
the v1 proof contract. Only one proof representation is active. Consumers that
have not implemented domain validation reject v2 rather than treating leftover
v1 fields as authorization. These internal primitives do not yet enable v2 source
creation through HTTP or establish mounted-source scanning/playback support.

The server itself may use Linux `/` or Windows `computer` as its workspace root.
A physical Linux or ordinary scoped filesystem `/` can be selected as a source.
The Windows virtual computer `/` is a chooser, not one physical volume; choose
an actual drive or directory, such as `/C` or `/D/Movies`, as the binding root.

## Scan jobs

`GET /scan-support` returns `localRulesRevision`, `available`, and, if not
available, `reason` (`not_enabled` or `coordinator_unavailable`). This describes
only local scanning, not access to a particular root or the full media library.
Clients should fetch the rule revision rather than hardcode it. Jobs created
under unsupported older rules cannot resume under new rules; create a new scan.

`POST /libraries/{id}/scans` accepts `bindingId`, `operationId`, the binding's
`expectedRevision`, and `rulesRevision`. The current local rules revision is 2.
The binding, owner and root are reloaded from the catalog; the request cannot
supply a source root. A 202 response is the queued acknowledgement, not a
completed scan. The server uses a bounded worker pool and reauthorizes the owner
and safely opens the root before a job is queued, claimed, and resumed.

`GET /libraries/{id}/scans/{scanId}` is owner-only and returns `id`, `libraryId`,
`status`, `generation`, `attempt`, `rulesRevision`, `phase`, `processed`,
`createdAt`, `updatedAt`, and an optional safe `errorCode`. Source paths and
object keys are never returned. States are `queued`, `running`, `completed`,
`failed`, `canceled`, and `interrupted`; phase is `enumerate`, `reconcile`, or
`ready`. Poll the job for its current generation and state.

`POST /libraries/{id}/scans/{scanId}/cancel` takes `operationId` and
`expectedGeneration`. It commits a cancellation fence before signaling the
worker. `POST .../{scanId}/retry` takes a new `operationId` and the previous
job's `expectedGeneration`; it creates a new queued job after checking the
current binding and source access. Both operations use transactionally recorded
receipts and return 409 on stale generations or conflicting operation IDs.

Interrupted work is resumed from the source root under a new generation; a
checkpoint is not a reusable directory cursor. Missing observations are staged
and published only after a complete enumeration and per-path recheck. Offline,
denied, canceled or incomplete runs do not mark unobserved entries missing.
The source files remain read-only. This first scanner supports only filesystems
accepted by the native safe opener; support for a NAS/network mount must be
verified separately.

## Items and local metadata

`GET /libraries/{id}/items` returns `{items, nextCursor?}` with `limit` 1–100
(default 50) and an optional opaque item-ID `cursor`. Library membership and
the instance sharing policy are checked for the actual viewer. The source
owner's current account, source binding and file rules are checked separately;
viewers do not acquire the owner's general file permissions. Filtering occurs
before deciding whether a next visible page exists, and no count of hidden
sources is returned.

Each item summary includes `id`, `libraryId`, `kind`, optional `parentId`,
`title`, `revision`, `createdAt`, `sourceState` and `metadataState`.
`sourceState` is `available`, `offline`, `changed`, or `unverified`;
`unverified` cannot establish source or playback continuity. `metadataState`
is `none`, `available`, `offline`, or `restricted`. A previously attested
binding with unchanged workspace mapping can retain and display offline data;
a changed or unverified mapping cannot authorize old data. If a video remains
accessible but a supporting NFO
is no longer authorized, the item remains visible: that NFO's fields, ratings,
people, artwork and external IDs are removed, independently authorized manual
fields remain, and an unavailable title falls back to the authorized video's
display filename without its parent path. An item with no currently authorized
video source is hidden.

`GET /libraries/{id}/items/{itemId}` returns `{item, metadataRevision, fields,
credits, ratings, artwork, externalIds, issues, candidates}`. Each authorized
candidate contains a safe `code` and `sourceKinds` indicating the NFO root types;
raw evidence and paths remain private. Fields retain their values,
source, lock, explicit-clear state and revision. Ratings retain provider,
metric, scale and count semantics. Artwork contains only an opaque declaration
ID, role and status: its original path or URL is never a client fetch target.
Scanning consumes same-name NFOs, `movie.nfo`, directory `tvshow.nfo` and
`season.nfo` context plus filename evidence. The catalog stores series, seasons,
episodes and binding/epoch-specific file mappings. Duplicate encodings do not
create extra logical episodes; combined files have no invented cut points.
Ambiguous or conflicting identification remains reviewable with an unmatched
playback entry. Damaged context does not by itself replace an established identity.

Series and season details use descendant-source authorization, with separate
checks for each metadata input. See the [catalog API](../server/docs/media-catalog.md)
for the home, catalog, season, episode and count routes. Online provider
configuration and metadata jobs are pending.
Schema 9 preserves existing item IDs for proven first-epoch associations;
historical relationships whose binding epoch cannot be established remain stored
as recovery candidates rather than silently inheriting a new source proof.

Credits, ratings, artwork, external IDs and candidates each contain at most 20
authorized entries in the initial detail. Their respective optional cursors are
`creditsNextCursor`, `ratingsNextCursor`, `artworkNextCursor`,
`externalIdsNextCursor`, and `issuesNextCursor`. Fetch additional pages from
`GET /libraries/{id}/items/{itemId}/{credits|ratings|artwork|external-ids|issues}`
using `limit` 1–100, optional `cursor`, and `revision` equal to the item revision
from the detail. `revision` is required when a cursor is supplied; a changed
revision returns 409. Responses contain
`items`, optional `nextCursor`, and the current `revision`; authorization is
reapplied before pagination and hidden inputs are never counted.

An owner or explicitly authorized editor may call
`PATCH /libraries/{id}/items/{itemId}/metadata` with `operationId`, `expectedRevision`
(the current item revision), and `fields`. A field patch can set `values`,
`clear`, `reset`, and/or `locked` within the documented validation rules.
An existing field whose source is currently restricted cannot be overwritten
blindly; another independently visible manual field can still be edited.
An explicit clear is a locked value, not a missing value; reset removes that
clear and permits a subsequent scan to apply source data. The NFO file is never
modified. A viewer cannot edit. `POST .../metadata/undo` takes `operationId`,
`expectedRevision`, and `targetRevision` from the metadata change to undo.
Later conflicting edits return 409 rather than overwrite the user's work; undo
is unavailable while any metadata source for the item is restricted.
Both mutations return a receipt `{itemId, revision, metadataRevision}`; fetch
the current detail separately. Their receipts and edits commit atomically.

## Local artwork and original-media access

These routes are an incremental local-source capability, not a declaration that
the complete media-library player protocol is ready. All paths below retain the
`/api/media-library/v1` prefix. Ordinary authenticated accounts and explicitly
authorized media device sessions may read; device sessions do not gain library
management or metadata-write permissions.

The current native opener retains the subtitle subsystem's filesystem limits:
Linux permits ext4, Btrfs, XFS and tmpfs; Windows requires a supported local
volume. Mounted SMB/NFS and container overlay paths are not yet supported by
this implementation. Supporting them requires separating safe handle access
from directory notification/snapshot guarantees; this is a remaining first-release
requirement, not an authorization to bypass root or identity checks.

`GET /libraries/{id}/items/{itemId}/variants` accepts `limit` (1–100, default 20)
and an optional opaque `cursor`. It returns `{items, nextCursor?}`. Each candidate
contains `itemId`, `editionId`, `variantId`, `bindingId`, `sourceFileId`,
`editionName`, `isDefault`, `sourceLabel`, `displayName`, `contentType`, `size`,
`fileRevision`, `identityEpoch`, and `weakIdentity`. `durationSeconds` and
`resolution` are currently null; they must not be presented as measured values.
The first currently authorized binding is selected for each variant. Inaccessible
variants are omitted, without disclosing hidden counts or physical paths. A page
may therefore be empty with a next cursor; clients must follow that cursor.

`POST /libraries/{id}/items/{itemId}/playback-resolutions` takes `editionId`,
`variantId`, and `bindingId`. It returns `itemId`, `editionId`, `variantId`,
`sourceFileId`, `fileRevision`, `identityEpoch`, `weakIdentity`, `displayName`,
`contentType`, `size`, nullable `durationSeconds` and `resolution`,
`rangeSupported`, `canResumeAcrossOpen`, `resumeRequiresSameRevision`, `url`,
`endpoint`, `sessionId`, `leaseId`, `leaseGeneration`, `expiresAt`, `renewAfter`,
`resumeMode`, and `timelineToken`. Times are Unix milliseconds. The URL is a same-origin authenticated
`/streams/{lease}/original` resource supporting GET and HEAD, conditional
requests and HTTP byte ranges. It never contains a source credential or an
upstream media URL. A lease is bound to the requesting account and device
session; knowledge of its opaque ID is not authorization.

The session retains its initial verified file handle. The five-minute admission
window applies to new GET/HEAD requests. An admitted stream continues while its
request authorization and source checks succeed. `POST /playback-sessions/{sessionId}/renew`
takes `operationId` and `expectedLeaseGeneration`, validates the current actor,
device and source, and extends admission for the same resource. A retry of the
most recent successful operation returns that receipt. A stale generation
returns 409. `DELETE /playback-sessions/{sessionId}` closes the owner's session.
The service reclaims sessions after ten minutes without readers or renewal.

`resumeMode: same_session` and `timelineToken` describe continuity of the held
resource. `canResumeAcrossOpen` remains false. Reopening after the session is
gone requires independently verified timeline evidence.

`GET|HEAD /libraries/{id}/items/{itemId}/artwork/{artworkId}` reads an authorized
local NFO artwork declaration. This stage accepts only relative JPEG/PNG files
within the attested source root, limits input and output to 8 MiB, limits each
dimension to 8192 and total pixels to 16 Mi, and permits two concurrent decodes.
Images are decoded and re-encoded before delivery. URLs, absolute paths, root
escapes and SVG are rejected. This route currently preserves image dimensions;
display-sized thumbnail variants and their cache are not yet implemented.
Standalone local artwork discovery without an NFO is also pending.

Reads keep the viewer separate from the source owner and check both the current
library grant and current source-owner authority. They use the same verified
root handle for child opens and fence source identity, binding proof, mapping
and authorization revisions. JWT/device validity and authorization are checked
before conditional responses and before each bounded block of media bytes.
Revoked or changed access invalidates the lease; metadata-only edits and library
renaming do not intentionally interrupt playback. Images additionally verify
the NFO digest, artwork declaration and an authorized video source. Existing
file download permission is not granted to shared-library viewers. Subtitles,
fonts, media probes and compatibility transcodes are not exposed by these new
routes yet and must not fall back to impersonating the library owner.

## Failures and compatibility

The endpoints use the existing server HTTP error envelope. Invalid query
parameters return 400, inaccessible libraries 404, and a disabled/unavailable
catalog 503. Internal SQLite details and filesystem paths are not returned. An
empty library list is distinct from a disabled catalog or a failed request.

Clients must not interpret an HTTP failure as a permanently unsupported server,
nor retry a mutation against a legacy file endpoint. Inaccessible local media
and artwork return a deliberately non-disclosing 404. Lease capacity or artwork
decode saturation returns 503. The local read routes above do not imply complete
player, watch-state or handoff compatibility.
