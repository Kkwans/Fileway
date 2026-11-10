# Scoped subtitle resources, version 1

This API lets an authenticated client browse subtitle files within its current
account scope and read a selected file without exposing server filesystem paths.
It is separate from the legacy resource and raw endpoints; existing clients keep
their current contracts.

`GET /api/client-capabilities` advertises `resourceSubtitlePagesVersion: 1` only
when the server can open the account root with the required containment and
directory-change checks. A missing field means the feature is unavailable. Even
when the root is supported, a particular volume can reject safe browsing with
HTTP 501; clients should report the actual error rather than fall back to legacy
raw reads for arbitrary subtitle paths.

## Directory pages

`POST /api/resource-directory-pages` accepts a JSON object (maximum 32 KiB):

| Field | Meaning |
| --- | --- |
| `action` | `page`, `close`, or `verify` |
| `wirePath` | Canonical escaped path inside the authenticated account scope |
| `browserNonce` | Client-generated base64url value, 16–128 characters, for `page` and `close` |
| `cursor` | Empty for the first page; opaque 32-character continuation for later pages |
| `version` | Opaque version returned for a file, required by `verify` |

`page` returns `version: 1`, the canonical directory `wirePath`, `items`, and a
nullable `nextCursor`. Each item contains display `name` and `path`, its exact
`wirePath`, `isDir`, `size`, `modified`, and an opaque `version` for a subtitle
file. Results follow the opened directory's enumeration order; they are not a
sorted snapshot. A page may be empty while `nextCursor` is present. Clients
must continue until it is null and keep the wire path separate from display text.

Cursors are scoped to the user, device session, browser nonce and directory.
Only one read for a cursor may be in flight. Retrying the most recently delivered
page returns the same page after rechecking authority and directory state. A
changed directory, policy, expired cursor or server restart can return HTTP 409;
discard the affected rows and restart from the first page. `close` releases the
scan early; idle scans expire after two minutes. HTTP 429 and 408 are retryable.

`verify` takes `wirePath` and `version`, with no nonce or cursor, and rechecks
the current account's download permission, rule policy and the file identity.
It returns the verified `version` or an error; it does not authorize a later
legacy raw read.

## File content

`GET` or `HEAD /api/resource-subtitle-file{wirePath}?version={version}` checks
the same authority and version again and reads from the verified file handle.
It supports HTTP Range and returns 409 if the file identity or policy changed.
The version is an opaque comparison token, not an authentication credential;
the request still requires the user's active session. Responses are private and
non-cacheable. Clients can use a session-bound same-origin broker lease and must
revoke it with the associated playback or subtitle lifecycle.

The implementation rejects symbolic links and other path-redirecting entries in
this API, including intermediate components. Linux currently requires `openat2`,
`/proc/self/fd`, directory notifications and a supported local filesystem;
Windows requires a supported local NTFS or ReFS volume and handle-based checks.
Unsupported providers fail closed. These restrictions do not change legacy
resource endpoints or their existing link behavior.
