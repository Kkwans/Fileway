# Media provider configuration

These authenticated account routes are under `/api/media-library/v1`. Device
media credentials cannot manage provider configuration. Configuration performs
no provider request and does not grant access to a library's files. The normal
catalog and local NFO paths continue to work when no credential vault exists.

`GET /provider-status` returns `credentialStoreAvailable`,
`candidateSearchAvailable` and `networkJobsAvailable`. A connection with
`status: "configured"` has a sealed credential recorded. Its acceptance by the
provider is established by an authorized request. `networkJobsAvailable` is
false until background online jobs are supported. Without a protected vault
key, credential writes return 503 `credential_store_unavailable`.

`GET /provider-connections?limit=50&cursor=...` lists the caller's personal
connections and instance connections explicitly granted to that user or an
accessible owned/editor library. Instance administrators can see instance
connections, never another user's personal connection. Pages return `items` and
optional `nextCursor`. Values expose only ID, provider, owner kind, optional
owning user ID, status and revision; neither token, ciphertext, fingerprint,
vault key nor provider URL is returned.

`POST /provider-connections` takes `operationId`, `ownerKind` (`user` or
`instance`) and a TMDB API read-access `token`. Personal ownership is always
the authenticated actor; creating an instance connection requires a current
instance administrator. `PUT /provider-connections/{connectionId}` rotates the
token with `operationId`, `expectedRevision` and `token`. `DELETE` on the same
route takes `operationId` and `expectedRevision`, marks the connection missing
and clears its current sealed envelope. Clearing does not promise physical
erasure from SQLite WAL pages or backups. No library silently switches to a
different connection after clear or failure.

Only an instance administrator may `GET`, `PUT` or `DELETE`
`/provider-connections/{connectionId}/grants`. PUT grants and DELETE revokes;
the body takes `operationId`, `expectedRevision` and exactly one of `userId`
or `libraryId`. The target must exist. Revocation is retained as a tombstone
and changes the connection revision, fencing jobs using an older grant.
The GET result is paged using `limit`, `afterKind` and `afterId`; returned
`nextKind` and `nextId` are used together. A connection grant never grants
membership in that library or permission to open its files.

`GET /libraries/{id}/metadata-policy` is available to the owner and authorized
editor. A new library returns `mode: "local_only"`, revision 0, empty locale
and `allowEditorJobs: false`. Only the owner may `PUT` the policy, with
`operationId`, `expectedRevision`, `mode`, `connectionId`, `language`, `region`
and explicit `allowEditorJobs`. `local_first_online` requires an explicitly
chosen, currently granted connection; no fallback is performed. `local_only`
never authorizes provider calls. Language is an optional BCP-47-style tag and
region is an optional two-letter uppercase code. An editor may use an instance
connection with a valid grant or their own explicitly selected personal
connection only when the owner has enabled editor jobs; they cannot use the
owner's personal credential. Source-owner file access remains a separate check.

`POST /libraries/{id}/items/{itemId}/match-candidates` searches for a movie or
series using the library's approved connection. Use `itemRevision` and
`structureRevision` from `GET .../identification`; send `kind` (`movie` or
`series`), `page` (1–500), and optionally `title`, `year` and `connectionId`.
An omitted title uses the parsed title or displayed title; `year` narrows the
provider results only when supplied. Rows contain typed TMDB IDs, titles, years
and match evidence. The lookup leaves the saved match and metadata untouched.
Policy, permissions or source changes during lookup invalidate its result.
The server may respond to a busy or rate-limited request with `Retry-After`.

Mutations use `operationId` and optimistic revisions. An exact retry returns the
original sanitized receipt; a different request with the same operation or a
stale revision returns 409 `revision_conflict`. A 503
`write_outcome_unknown` after a change means authorization may have changed
after commit: preserve the operation ID and explicitly retry or refresh after
access is restored. Other 5xx/transport failures may also have unknown outcome.
Connection ciphertext and its keyed fingerprint are stored separately from the
connection metadata; the independent protected key is not in the catalog or
operation receipts. Responses and logs must never include credentials.
