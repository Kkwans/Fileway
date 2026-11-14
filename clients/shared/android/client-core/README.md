# Shared Android media catalog contract

`MediaLibraryRepository` is shared by the phone and TV applications. Its
catalog reads use the existing authenticated `NasSession` and return
`MediaReadResult` with the bound `MediaSessionScope`. Controllers must discard
results after their session/owner/generation changes; the repository also
rejects a changed device session. Ordinary token renewal for the same device
session does not invalidate an in-flight catalog read.

The C2 read surface is `home`, `homeRow`, `catalog`, `catalogDetail`,
`seasons`, `episodes` / `episodesById`, `episodeCounts` / `episodeCountsById`, `playbackVariants`
and `resolvePlayback`. Construct deep-link references through `catalogRef`.
Season results retain their parent series reference; a selection from another
account, session or series cannot be reused as the current selection.

`MediaCatalogCursor` is an opaque in-memory value bound to its session and
complete query. Do not serialize, log, decode, edit or move it to another
query. A page may be empty and `PARTIAL` with a continuation; it is not an
empty library. `DEFERRED`, `PREPARING`, `UNSUPPORTED`, `ERROR`, unknown card
states, and network/permission failures must not be rendered as a successful
empty page. The client does not automatically drain continuations. A stale or
expired cursor is a distinct read failure and requires a fresh query; keep any
already displayed content until the controller chooses to refresh.

Card actions are allowed only when the server explicitly grants the named
action and the returned state is recognized. Media-only device credentials
never expose library-management or metadata-edit actions. Unknown actions are
ignored. Unknown availability, kind, match, metadata or display states disable
actions rather than guessing. Season 0, missing totals and deferred counts are
preserved; file variants are not substituted for unique episode counts. A
combined file never gains an invented independent start offset.

Artwork endpoints are checked against their returned library, item and asset
IDs and remain service-relative; retrieve them through `artworkEndpoint` for
the current scope. Row and count requests are constructed from
known typed IDs, not arbitrary server-provided URLs. Playback resolution is an
explicit request for an ephemeral lease and is not a durable write-journal
operation; do not silently retry an unknown outcome. The response retains the
validated service-relative `endpoint` and deliberately discards legacy `url`,
which can already contain the server base path. If an older server omits the
endpoint, it is null and no fallback URL is synthesized. Lease/session IDs,
timeline tokens and endpoints are sensitive: keep them in memory, do not log
or persist them. Resolve the path through `playbackEndpoint(resolution)`, which
checks that the repository/session still owns it; release/renew only through the separately implemented
playback lifecycle. Parsing the optional session fields does not implement
renewal, playback, offline synchronization or a broker capability grant.
For this lease-creating request, `mutationMayHaveCommitted` on a failed read
means the short-lived lease may have been created even though its response was
not received; it does not indicate a durable journal entry.

The server's matching route and wire contract are documented in
[`server/docs/media-catalog.md`](../../../../server/docs/media-catalog.md).
