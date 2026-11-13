# Media library API

The instance-local namespace is `/api/media-library/v1`. The initial read APIs
below are implemented; they do not imply that scanning or playback is available.
The full `mediaLibraryVersion` capability is withheld until the complete access
boundary and client journey are integrated.

## Authentication and visibility

Requests use the existing authenticated account session. Account identity is never
accepted from a query or ownership field. An ordinary file Download permission is
not required merely to list a shared catalog. Membership does not itself authorize
source bytes, source credentials, directory browsing or filesystem modification.

Private or revoked libraries are indistinguishable from missing libraries (404).
An archived library is visible only to its owner; restoration does not restore a
previously revoked member. Responses set `Cache-Control: no-store`. Source roots,
native paths and configuration/credential references are never summary fields.

TV device-session routes are separately allowlisted. Adding a namespace does not
authorize a TV media credential to call its management endpoints.

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

## Failures and compatibility

The initial endpoints use the existing server HTTP error envelope. Invalid query
parameters return 400, inaccessible libraries 404, and a disabled/unavailable
catalog 503. Internal SQLite details and filesystem paths are not returned. An
empty library list is distinct from a disabled catalog or a failed request.

Clients must not interpret an HTTP failure as a permanently unsupported server,
nor retry a mutation against a legacy file endpoint. New mutation and playback
contracts will document operation IDs, expected revisions and explicit capability
requirements when those complete paths are integrated.
