# Media catalog read API

The catalog API is available under `/api/media-library/v1`. Library membership
does not grant access to the source filesystem. Each published file, directory
context, NFO field, rating and artwork declaration is independently checked
against the source owner's current permissions and binding proof. Opaque IDs
are references, not authority to open a file.

## Capability discovery

Authenticated account and device sessions can call `GET /api/client-capabilities`.
On a successful response, `mediaLibraryVersion: 1` means that the server's media
catalog accepted a bounded read for the current user. The field is omitted when
the catalog is disabled or cannot currently be read; an empty catalog still
advertises version 1. A capability is not a library membership or file access
grant. Clients must continue to handle authorization and availability errors
from individual catalog requests, and must not interpret a failed capability
request as a successful response from an older server.

## Home and browse

- `GET /home` returns visible libraries, a hero descriptor and independent
  `recently-added` and `recently-updated-series` rows. It advertises unsupported
  personal state, search and collections in `capabilities`; it does not create
  sample cards. `state` can be `no_libraries`, `empty_library`, `scanning`,
  `ready`, `partial`, `source_offline` or `restricted`.
- `GET /home/rows/{rowId}` continues `hero`, `recently-added`,
  `recently-updated-series` or `unmatched`. `continue` and `favorites` return
  `unsupported` until personal state is connected. A `nextCursor` is only valid
  for its originating row.
- `GET /catalog/items` supports optional `libraryId`, `structureKind`
  (`movie`, `series`, `video`), `matchState`, `sort` (`id`, `newest`,
  `updated`), `limit` (1–100, default 20) and `cursor`. Unsupported filter
  parameters are rejected instead of silently returning unfiltered results.
  `updated` is restricted to series. Categories, search, people, collections
  and cross-library work groups are not implemented by this slice.

Pages return `items`, `pageState`, `hasMore`, an opaque `revision`, and a
`nextCursor` when there is more work. A `partial` page, including an empty
one, is not an empty library: continue with its cursor. A home row can also be
`preparing` when another row exhausted the home request's candidate budget;
fetch its row endpoint separately. A normal `ready` page may have `hasMore`.
The recently updated series order is computed from the earliest stable
collection time of each **currently visible** episode, taking the latest of
those times. Hidden episodes do not affect the returned time or order. If the
bounded authorized sort cannot complete, that row is `deferred` and does not
present an incomplete ranking.

New cursors are authenticated and encrypted, bind the actor, device session,
query, visible library revisions and live owner policy, and expire after 15
minutes. Invalid, stale and expired cursors return `cursor_invalid` (400),
`cursor_stale` (409) and `cursor_expired` (410). Changes to the query or its
limit require a new cursor. Requests recheck current authority before
publication. The legacy library-item list retains its earlier cursor contract.
Ordinary scan progress and unrelated new entries do not invalidate an
ID/newest continuation. Entries newly inserted before its keyset position are
picked up on refresh. A pending item is fenced by its own revisions, season
pages bind their order ID/revision, and update-sorted pages fence the already
returned authorized ordering prefix rather than the global scan watermark.

Cards use stable `{libraryId,itemId}` references. Missing images, ratings,
duration, year and counts are null, never invented. Movie presentation from an
existing video item requires a current, unambiguous movie NFO whose source is
authorized for this request; otherwise it remains a playable unmatched video.
The underlying item and playback identifiers are unchanged. Hero includes
only currently confirmed and visible movies or series. `canActions` and
`actionReasons` reflect the actual role and credential scope: TV media-only
sessions cannot claim metadata or library-management writes. Watch-state and
online matching actions are `feature_unavailable` until implemented.

## Identification evidence

`GET /libraries/{id}/items/{itemId}/identification` returns the current
authorized display title, structure and match state with item and structure
revisions. The first page includes up to 20 visible typed external IDs.
Continue through the existing `external-ids` route using
`externalIdsNextCursor` and `itemRevision`.

For a leaf item, `file` can describe filename evidence from the selected
visible source, including title/year hints, parsed season/episode numbers,
explicit provider ID hints and review reasons. This is local identification
evidence. A series or season never uses a descendant's filename as its own
evidence. A revoked NFO stops contributing IDs and classification even if
the associated video is still visible. The response contains no source
locator, credential or provider request.

## Series and playback

- `GET /libraries/{id}/items/{series}/seasons` returns authorized seasons in
  local order, including season 0 as specials.
- `GET /libraries/{id}/items/{series}/episodes?seasonId=...` returns an
  authorized, numbered episode page. An optional `orderId` must match the
  returned local order. Both routes accept `limit` and their own opaque
  `cursor`. A parent or season that has no authorized descendant or
  independently authorized local context is not found.
- `GET /libraries/{id}/items/{series}/episode-counts` optionally accepts
  `seasonId`. It counts distinct visible episode IDs, never file variants.
  Offline entries with a valid proof remain collected; revoked entries do
  not. Specials are separate from regular episodes. Unknown provider totals
  remain null. `countState=ready` means the bounded authorized traversal
  completed; `deferred` means no background task is running and no partial
  count is presented. Cards perform a smaller bounded pass and provide this
  endpoint so ordinary long series can obtain a separate exact count. Larger
  traversals can remain deferred; the paged episode list remains usable.

For a combined file, the episode response reports `playMode=combined_file`,
`independentStart=false`, viewer-visible `coveredEpisodeIds`, and a label. It
does not invent a start offset. A `coverageState` of `partial` or `deferred`
means the complete visible set has not yet been established.

The existing playback-resolution response retains `url`, an origin-relative
path including the configured server `BaseURL`, for existing Web/direct HTTP
consumers. Its additional `endpoint` is service-relative and always begins
`/api/...`; Native Broker consumers join it with their configured server base
path once. Do not prepend the base path to `url` or use `endpoint` as a host.
Neither catalog cards nor this document contain playback leases.
