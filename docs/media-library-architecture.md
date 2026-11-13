# Media library architecture

The media library is an instance-local domain shared by Android phone and TV.
Linux and Windows use one API and storage implementation. This document defines
the intended contract; individual capabilities are advertised only when integrated.

## Identity and ownership

- Preserve the existing server and user IDs. An address is not a server identity.
- Libraries are private by default. Member grants do not grant filesystem access.
- Source ownership and library membership are separate authorities. Playback must
  validate the actor, current source authority, library grant and source revision.
- Never impersonate the library owner or expand a viewer's Download permission.
- Library-local matches, NFO data, manual fields, locks and artwork remain private
  to that library. Authorized browse groups may aggregate confirmed identical works
  without merging the libraries' editable metadata.
- Stable file IDs, opaque locators and source revisions are distinct. Size/mtime
  is not a content hash. A provider ID includes provider and entity type.
- Editions describe different cuts; variants describe encodings and sources.
  Different cuts do not share an inferred percentage-based resume position.

## Persistence

The catalog uses a separate SQLite database on reliable local storage. Existing
bbolt accounts, settings and file records remain in place. The initial driver is
modernc.org/sqlite v1.49.0. SQLite schema versions are independent of bbolt.

Opening an unsupported newer or unrelated database must fail without migration.
Every connection enables foreign keys, bounded lock waiting and durable commits.
Catalog changes, job checkpoints and outbox events commit in one transaction.
External calls and event publication happen after commit. Task Center is a
recoverable projection, not a second authoritative media job store.

Database constraints establish reference integrity, not user authorization.
Cross-database user references are revalidated by the service. No implicit cascade
may erase manual corrections or viewing history. A failed/offline/incomplete scan
must never mark an entire source as deleted.

## Metadata and user state

Media and sidecars are read-only inputs. Manual changes are stored in Fileway.
Locked/manual values take precedence over local NFO, configured online metadata
and filename fallback. Missing data is different from an explicit clear.

Ratings retain provider, subject scope, metric, scale, count semantics, acquisition
channel and timestamps. Rotten Tomatoes critics and audiences remain distinct.
Unknown ratings/counts are absent, not zero. Imported data is not presented as a
fresh official API observation.

Precise progress belongs to a file revision and original timeline. New watch
sessions carry sequence and reset revisions. Legacy path-only writes cannot
automatically overwrite a conflicting confirmed new-protocol state.

## Compatibility and rollback

The public namespace is /api/media-library/v1 with mediaLibraryVersion capability
negotiation. Do not advertise it before the end-to-end access boundary is ready.
Existing file APIs and remote v1 envelopes remain compatible. New TV handoff
references must be resolved under the TV's own account; no phone token forwarding.

Archive preserves metadata and viewing state until explicit cleanup. Backups
coordinate SQLite, bbolt projection watermarks and user-owned artwork. Rollback
disables the new feature and preserves catalog data; it does not delete the catalog
or replace application signing identities.
