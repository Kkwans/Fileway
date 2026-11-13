package medialibrary

// v1 establishes ownership and reference integrity, not authorization policy.
// User IDs refer to the existing account database and are revalidated by the
// service at access time; no cross-database foreign key or transaction is implied.
// Paths/object keys are opaque bytes. They must not become display text or IDs.
// No implicit cascade removes manual corrections or watchable files.
const schemaV1 = `
CREATE TABLE library (
    id TEXT PRIMARY KEY NOT NULL CHECK(length(id) > 0),
    owner_user_id INTEGER NOT NULL CHECK(owner_user_id > 0),
    name TEXT NOT NULL CHECK(length(trim(name)) > 0),
    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0),
    created_at INTEGER NOT NULL CHECK(created_at >= 0),
    updated_at INTEGER NOT NULL CHECK(updated_at >= created_at)
) STRICT;
CREATE INDEX library_owner ON library(owner_user_id, id);

-- A library with no member grants is private to its owner.
CREATE TABLE library_member (
    library_id TEXT NOT NULL REFERENCES library(id),
    user_id INTEGER NOT NULL CHECK(user_id > 0),
    role TEXT NOT NULL CHECK(role IN ('viewer', 'editor')),
    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0),
    created_at INTEGER NOT NULL CHECK(created_at >= 0),
    PRIMARY KEY(library_id, user_id)
) STRICT;
CREATE INDEX library_member_user ON library_member(user_id, library_id);

CREATE TABLE source (
    id TEXT PRIMARY KEY NOT NULL CHECK(length(id) > 0),
    owner_user_id INTEGER NOT NULL CHECK(owner_user_id > 0),
    kind TEXT NOT NULL CHECK(length(kind) > 0),
    configuration_ref TEXT NOT NULL CHECK(length(configuration_ref) > 0),
    authorization_revision INTEGER NOT NULL DEFAULT 1 CHECK(authorization_revision > 0),
    created_at INTEGER NOT NULL CHECK(created_at >= 0)
) STRICT;
CREATE INDEX source_owner ON source(owner_user_id, id);

CREATE TABLE library_source (
    library_id TEXT NOT NULL REFERENCES library(id),
    source_id TEXT NOT NULL REFERENCES source(id),
    root_locator BLOB NOT NULL CHECK(length(root_locator) > 0),
    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0),
    PRIMARY KEY(library_id, source_id, root_locator)
) STRICT;

CREATE TABLE source_file (
    id TEXT PRIMARY KEY NOT NULL CHECK(length(id) > 0),
    source_id TEXT NOT NULL REFERENCES source(id),
    object_key BLOB NOT NULL CHECK(length(object_key) > 0),
    locator BLOB NOT NULL CHECK(length(locator) > 0),
    source_version TEXT NOT NULL CHECK(length(source_version) > 0),
    size_bytes INTEGER CHECK(size_bytes >= 0),
    observed_at INTEGER NOT NULL CHECK(observed_at >= 0),
    missing_since INTEGER CHECK(missing_since >= 0),
    UNIQUE(source_id, object_key)
) STRICT;

CREATE TABLE library_item (
    id TEXT PRIMARY KEY NOT NULL CHECK(length(id) > 0),
    library_id TEXT NOT NULL REFERENCES library(id),
    kind TEXT NOT NULL CHECK(kind IN ('movie', 'series', 'season', 'episode', 'video')),
    parent_id TEXT,
    title TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0),
    created_at INTEGER NOT NULL CHECK(created_at >= 0),
    UNIQUE(library_id, id),
    FOREIGN KEY(library_id, parent_id) REFERENCES library_item(library_id, id),
    CHECK(parent_id IS NULL OR parent_id != id)
) STRICT;
CREATE INDEX library_item_parent ON library_item(library_id, parent_id, id);

CREATE TABLE edition (
    id TEXT PRIMARY KEY NOT NULL CHECK(length(id) > 0),
    library_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    name TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0),
    UNIQUE(library_id, id),
    FOREIGN KEY(library_id, item_id) REFERENCES library_item(library_id, id)
) STRICT;
CREATE INDEX edition_item ON edition(library_id, item_id, id);

CREATE TABLE variant (
    id TEXT PRIMARY KEY NOT NULL CHECK(length(id) > 0),
    library_id TEXT NOT NULL,
    edition_id TEXT NOT NULL,
    source_file_id TEXT NOT NULL REFERENCES source_file(id),
    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0),
    FOREIGN KEY(library_id, edition_id) REFERENCES edition(library_id, id),
    UNIQUE(edition_id, source_file_id)
) STRICT;
CREATE INDEX variant_file ON variant(source_file_id, id);
CREATE INDEX variant_edition ON variant(library_id, edition_id, id);

CREATE TABLE metadata_revision (
    id TEXT PRIMARY KEY NOT NULL CHECK(length(id) > 0),
    library_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    revision INTEGER NOT NULL CHECK(revision > 0),
    actor_user_id INTEGER CHECK(actor_user_id > 0),
    origin TEXT NOT NULL CHECK(length(origin) > 0),
    changes_json TEXT NOT NULL CHECK(json_valid(changes_json)),
    created_at INTEGER NOT NULL CHECK(created_at >= 0),
    FOREIGN KEY(library_id, item_id) REFERENCES library_item(library_id, id),
    UNIQUE(item_id, revision)
) STRICT;

CREATE TABLE job (
    id TEXT PRIMARY KEY NOT NULL CHECK(length(id) > 0),
    library_id TEXT NOT NULL REFERENCES library(id),
    owner_user_id INTEGER NOT NULL CHECK(owner_user_id > 0),
    kind TEXT NOT NULL CHECK(length(kind) > 0),
    status TEXT NOT NULL CHECK(status IN ('queued', 'running', 'completed', 'failed', 'canceled', 'interrupted')),
    generation INTEGER NOT NULL DEFAULT 1 CHECK(generation > 0),
    checkpoint_json TEXT NOT NULL DEFAULT '{}' CHECK(json_valid(checkpoint_json)),
    attempt INTEGER NOT NULL DEFAULT 0 CHECK(attempt >= 0),
    retry_at INTEGER CHECK(retry_at >= 0),
    created_at INTEGER NOT NULL CHECK(created_at >= 0),
    updated_at INTEGER NOT NULL CHECK(updated_at >= created_at)
) STRICT;
CREATE INDEX job_library ON job(library_id, created_at, id);
CREATE INDEX job_pending ON job(status, retry_at, id);

-- Outbox rows live in the same transaction as catalog changes. Sequence gives
-- stable publication order; event_id gives consumers an idempotency key.
CREATE TABLE outbox (
    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id TEXT NOT NULL UNIQUE CHECK(length(event_id) > 0),
    library_id TEXT NOT NULL REFERENCES library(id),
    kind TEXT NOT NULL CHECK(length(kind) > 0),
    payload_json TEXT NOT NULL CHECK(json_valid(payload_json)),
    created_at INTEGER NOT NULL CHECK(created_at >= 0),
    published_at INTEGER CHECK(published_at >= created_at)
) STRICT;
CREATE INDEX outbox_pending ON outbox(sequence) WHERE published_at IS NULL;
`

// Archive/revoke retain the catalog and grant history. A regrant increments the
// existing grant revision rather than recreating revision 1 (lease ABA safety).
const schemaV2 = `
ALTER TABLE library ADD COLUMN archived_at INTEGER CHECK(archived_at >= created_at);
ALTER TABLE library_member ADD COLUMN revoked_at INTEGER CHECK(revoked_at >= created_at);
CREATE INDEX library_active_owner ON library(owner_user_id, id) WHERE archived_at IS NULL;
CREATE INDEX library_member_active ON library_member(user_id, library_id) WHERE revoked_at IS NULL;
`
