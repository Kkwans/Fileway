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

// A source file is not part of every library owned by the same user. Record the
// exact binding under which it was observed before permitting item association.
const schemaV3 = `
ALTER TABLE source_file ADD COLUMN revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0);
CREATE UNIQUE INDEX source_file_source_identity ON source_file(source_id, id);
CREATE TABLE library_file (
    library_id TEXT NOT NULL,
    source_file_id TEXT NOT NULL,
    source_id TEXT NOT NULL,
    root_locator BLOB NOT NULL,
    observed_at INTEGER NOT NULL CHECK(observed_at >= 0),
    PRIMARY KEY(library_id, source_file_id),
    FOREIGN KEY(library_id, source_id, root_locator) REFERENCES library_source(library_id, source_id, root_locator),
    FOREIGN KEY(source_id, source_file_id) REFERENCES source_file(source_id, id)
) STRICT;
CREATE INDEX library_file_source ON library_file(source_file_id, library_id);
ALTER TABLE edition ADD COLUMN is_default INTEGER NOT NULL DEFAULT 0 CHECK(is_default IN (0, 1));
CREATE UNIQUE INDEX edition_default_item ON edition(library_id, item_id) WHERE is_default = 1;
`

// Preserve each root's observation and locator independently. The v3 locator
// copied below is only a historical hint: every future open must revalidate
// the current owner's authority and the exact binding before reading bytes.
const schemaV4 = `
ALTER TABLE source_file ADD COLUMN identity_kind TEXT NOT NULL DEFAULT 'legacy_unverified'
    CHECK(identity_kind IN ('legacy_unverified', 'native', 'path_unverified'));
ALTER TABLE source_file ADD COLUMN identity_epoch INTEGER NOT NULL DEFAULT 1 CHECK(identity_epoch > 0);

CREATE UNIQUE INDEX job_library_identity ON job(id, library_id);
CREATE TABLE scan_job (
    job_id TEXT PRIMARY KEY NOT NULL,
    library_id TEXT NOT NULL,
    source_id TEXT NOT NULL,
    root_locator BLOB NOT NULL CHECK(length(root_locator) > 0),
    binding_revision INTEGER NOT NULL CHECK(binding_revision > 0),
    authorization_revision INTEGER NOT NULL CHECK(authorization_revision > 0),
    rules_revision INTEGER NOT NULL CHECK(rules_revision > 0),
    operation_id TEXT NOT NULL CHECK(length(operation_id) > 0),
    active INTEGER NOT NULL CHECK(active IN (0, 1)),
    last_digest BLOB,
    last_result_json TEXT CHECK(last_result_json IS NULL OR json_valid(last_result_json)),
    UNIQUE(library_id, operation_id),
    FOREIGN KEY(job_id, library_id) REFERENCES job(id, library_id),
    FOREIGN KEY(library_id, source_id, root_locator) REFERENCES library_source(library_id, source_id, root_locator)
) STRICT;
CREATE UNIQUE INDEX scan_job_active_binding ON scan_job(library_id, source_id, root_locator) WHERE active = 1;

CREATE TABLE library_file_v4 (
    library_id TEXT NOT NULL,
    source_file_id TEXT NOT NULL,
    source_id TEXT NOT NULL,
    root_locator BLOB NOT NULL,
    locator BLOB NOT NULL CHECK(length(locator) > 0),
    observed_at INTEGER NOT NULL CHECK(observed_at >= 0),
    last_seen_job_id TEXT REFERENCES job(id),
    last_seen_generation INTEGER CHECK(last_seen_generation > 0),
    missing_since INTEGER CHECK(missing_since >= 0),
    PRIMARY KEY(library_id, source_id, root_locator, source_file_id),
    FOREIGN KEY(library_id, source_id, root_locator) REFERENCES library_source(library_id, source_id, root_locator),
    FOREIGN KEY(source_id, source_file_id) REFERENCES source_file(source_id, id),
    CHECK((last_seen_job_id IS NULL) = (last_seen_generation IS NULL))
) STRICT;
INSERT INTO library_file_v4(library_id, source_file_id, source_id, root_locator, locator, observed_at)
SELECT lf.library_id, lf.source_file_id, lf.source_id, lf.root_locator,
       (SELECT sf.locator FROM source_file sf WHERE sf.id = lf.source_file_id AND sf.source_id = lf.source_id), lf.observed_at
FROM library_file lf;
DROP TABLE library_file;
ALTER TABLE library_file_v4 RENAME TO library_file;
CREATE INDEX library_file_source ON library_file(source_file_id, library_id);
CREATE INDEX library_file_binding_missing ON library_file(library_id, source_id, root_locator, source_file_id) WHERE missing_since IS NULL;

-- A verified absence is staged for exactly one attempt. Nothing becomes
-- missing until all candidates are rechecked and the job completes.
CREATE TABLE scan_missing_candidate (
    job_id TEXT NOT NULL REFERENCES scan_job(job_id),
    generation INTEGER NOT NULL CHECK(generation > 0),
    source_file_id TEXT NOT NULL REFERENCES source_file(id),
    file_revision INTEGER NOT NULL CHECK(file_revision > 0),
    locator BLOB NOT NULL CHECK(length(locator) > 0),
    seen_job_id TEXT,
    seen_generation INTEGER,
    PRIMARY KEY(job_id, generation, source_file_id),
    CHECK((seen_job_id IS NULL) = (seen_generation IS NULL))
) STRICT;
CREATE TABLE scan_nfo_status (
    job_id TEXT NOT NULL REFERENCES scan_job(job_id),
    generation INTEGER NOT NULL CHECK(generation > 0),
    source_file_id TEXT NOT NULL REFERENCES source_file(id),
    status TEXT NOT NULL CHECK(status IN ('missing', 'parsed', 'invalid', 'unavailable', 'changed')),
    PRIMARY KEY(job_id, generation, source_file_id)
) STRICT;
`

// v5 retains the composite binding key and all dependent foreign keys. IDs are
// filled by the Go migration using crypto/rand before the unique index and
// enforcement triggers are installed, so existing bindings keep their roots.
const schemaV5BeforeIDs = `
ALTER TABLE library_source ADD COLUMN binding_id TEXT;
ALTER TABLE scan_job ADD COLUMN error_code TEXT NOT NULL DEFAULT ''
    CHECK(error_code IN ('', 'owner_unavailable', 'permission_denied', 'root_unavailable', 'unsupported_rules', 'scheduler_unavailable', 'source_changed', 'scan_failed'));
`

const schemaV5AfterIDs = `
CREATE UNIQUE INDEX library_source_binding_id ON library_source(binding_id);
CREATE TRIGGER library_source_binding_id_insert BEFORE INSERT ON library_source
WHEN NEW.binding_id IS NULL OR length(NEW.binding_id) = 0
BEGIN SELECT RAISE(ABORT, 'binding ID required'); END;
CREATE TRIGGER library_source_binding_id_immutable BEFORE UPDATE OF binding_id ON library_source
WHEN NEW.binding_id IS NULL OR NEW.binding_id != OLD.binding_id
BEGIN SELECT RAISE(ABORT, 'binding ID is immutable'); END;
CREATE TABLE operation_receipt (
    actor_user_id INTEGER NOT NULL CHECK(actor_user_id > 0),
    kind TEXT NOT NULL CHECK(length(kind) > 0),
    operation_id TEXT NOT NULL CHECK(length(operation_id) > 0),
    input_digest BLOB NOT NULL CHECK(length(input_digest) = 32),
    result_json TEXT NOT NULL CHECK(json_valid(result_json)),
    created_at INTEGER NOT NULL CHECK(created_at >= 0),
    PRIMARY KEY(actor_user_id, kind, operation_id)
) STRICT;
`

// Metadata inputs and revision records are retained as evidence. Active flags
// change the projected view without deleting prior declarations or assets.
const schemaV6 = `
ALTER TABLE library_source ADD COLUMN proof_version INTEGER NOT NULL DEFAULT 0 CHECK(proof_version IN (0,1));
ALTER TABLE library_source ADD COLUMN proof_epoch INTEGER NOT NULL DEFAULT 0 CHECK(proof_epoch >= 0);
ALTER TABLE library_source ADD COLUMN mapping_digest BLOB;
ALTER TABLE library_source ADD COLUMN root_identity_kind TEXT NOT NULL DEFAULT '' CHECK(root_identity_kind IN ('','native'));
ALTER TABLE library_source ADD COLUMN root_identity BLOB;
ALTER TABLE library_source ADD COLUMN attested_at INTEGER CHECK(attested_at >= 0);
ALTER TABLE library_file ADD COLUMN binding_epoch INTEGER NOT NULL DEFAULT 0 CHECK(binding_epoch >= 0);
ALTER TABLE scan_job ADD COLUMN binding_id TEXT NOT NULL DEFAULT '';
ALTER TABLE scan_job ADD COLUMN binding_epoch INTEGER NOT NULL DEFAULT 0 CHECK(binding_epoch >= 0);
UPDATE scan_job SET binding_id=(SELECT binding_id FROM library_source ls WHERE ls.library_id=scan_job.library_id AND ls.source_id=scan_job.source_id AND ls.root_locator=scan_job.root_locator);
CREATE TRIGGER binding_proof_insert BEFORE INSERT ON library_source
WHEN COALESCE(((NEW.proof_version=0 AND NEW.proof_epoch=0 AND NEW.mapping_digest IS NULL AND NEW.root_identity_kind='' AND NEW.root_identity IS NULL AND NEW.attested_at IS NULL)
OR (NEW.proof_version=1 AND NEW.proof_epoch>0 AND length(NEW.mapping_digest)=32 AND NEW.root_identity_kind='native' AND length(NEW.root_identity) BETWEEN 1 AND 1024 AND NEW.attested_at IS NOT NULL)),0)=0
BEGIN SELECT RAISE(ABORT, 'invalid binding proof'); END;
CREATE TRIGGER binding_proof_update BEFORE UPDATE OF proof_version,proof_epoch,mapping_digest,root_identity_kind,root_identity,attested_at ON library_source
WHEN COALESCE(((NEW.proof_version=0 AND NEW.proof_epoch=0 AND NEW.mapping_digest IS NULL AND NEW.root_identity_kind='' AND NEW.root_identity IS NULL AND NEW.attested_at IS NULL)
OR (NEW.proof_version=1 AND NEW.proof_epoch>0 AND length(NEW.mapping_digest)=32 AND NEW.root_identity_kind='native' AND length(NEW.root_identity) BETWEEN 1 AND 1024 AND NEW.attested_at IS NOT NULL)),0)=0
BEGIN SELECT RAISE(ABORT, 'invalid binding proof'); END;
CREATE TRIGGER binding_proof_fence BEFORE UPDATE OF proof_version,proof_epoch,mapping_digest,root_identity_kind,root_identity,attested_at ON library_source
WHEN (OLD.proof_version=1 AND NEW.proof_version!=1) OR (NEW.proof_version=1 AND (NEW.proof_epoch!=OLD.proof_epoch+1 OR NEW.revision!=OLD.revision+1))
BEGIN SELECT RAISE(ABORT, 'binding proof requires a new epoch and revision'); END;
CREATE TABLE metadata_input (
    id TEXT PRIMARY KEY NOT NULL,
    library_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    binding_id TEXT NOT NULL REFERENCES library_source(binding_id),
    binding_epoch INTEGER NOT NULL CHECK(binding_epoch >= 0),
    source_type TEXT NOT NULL CHECK(source_type IN ('nfo', 'online')),
    source_key BLOB NOT NULL CHECK(length(source_key) > 0),
    digest BLOB NOT NULL CHECK(length(digest) = 32),
    parser_version INTEGER NOT NULL CHECK(parser_version > 0),
    association_ambiguous INTEGER NOT NULL CHECK(association_ambiguous IN (0,1)),
    evidence_json TEXT NOT NULL CHECK(json_valid(evidence_json)),
    status TEXT NOT NULL CHECK(status IN ('applied', 'candidate')),
    issue_code TEXT NOT NULL DEFAULT '',
    is_current INTEGER NOT NULL CHECK(is_current IN (0,1)),
    projection_stale INTEGER NOT NULL DEFAULT 0 CHECK(projection_stale IN (0,1)),
    created_at INTEGER NOT NULL CHECK(created_at >= 0),
    FOREIGN KEY(library_id, item_id) REFERENCES library_item(library_id, id)
) STRICT;
CREATE UNIQUE INDEX metadata_input_current ON metadata_input(library_id,item_id,binding_id,binding_epoch,source_type,source_key) WHERE is_current = 1;
CREATE INDEX metadata_input_item ON metadata_input(library_id,item_id,is_current,id);

CREATE TABLE item_field (
    library_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    field TEXT NOT NULL CHECK(length(field) > 0),
    value_json TEXT NOT NULL CHECK(json_valid(value_json)),
    source TEXT NOT NULL CHECK(source IN ('legacy', 'filename', 'online', 'nfo', 'manual')),
    source_key BLOB,
    input_id TEXT REFERENCES metadata_input(id),
    source_binding_id TEXT REFERENCES library_source(binding_id),
    source_binding_epoch INTEGER NOT NULL DEFAULT 0 CHECK(source_binding_epoch >= 0),
    locked INTEGER NOT NULL CHECK(locked IN (0,1)),
    explicit_clear INTEGER NOT NULL CHECK(explicit_clear IN (0,1)),
    revision INTEGER NOT NULL CHECK(revision > 0),
    PRIMARY KEY(library_id,item_id,field),
    FOREIGN KEY(library_id,item_id) REFERENCES library_item(library_id,id)
) STRICT;
INSERT INTO item_field(library_id,item_id,field,value_json,source,locked,explicit_clear,revision)
SELECT library_id,id,'title',json_array(title),'legacy',1,0,revision FROM library_item;

CREATE TABLE library_person (
    id TEXT PRIMARY KEY NOT NULL,
    library_id TEXT NOT NULL REFERENCES library(id),
    name TEXT NOT NULL,
    declaration_source BLOB,
    declaration_hash BLOB,
    UNIQUE(library_id,id),
    UNIQUE(library_id,declaration_source,declaration_hash)
) STRICT;
CREATE TABLE person_external_id (
    library_id TEXT NOT NULL,
    provider TEXT NOT NULL,
    external_id TEXT NOT NULL,
    person_id TEXT NOT NULL,
    PRIMARY KEY(library_id,provider,external_id),
    FOREIGN KEY(library_id,person_id) REFERENCES library_person(library_id,id)
) STRICT;
CREATE TABLE item_credit (
    id TEXT PRIMARY KEY NOT NULL,
    library_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    person_id TEXT NOT NULL,
    display_name TEXT NOT NULL,
    kind TEXT NOT NULL,
    role TEXT NOT NULL,
    roles_json TEXT NOT NULL CHECK(json_valid(roles_json)),
    position INTEGER NOT NULL CHECK(position >= 0),
    source_key BLOB NOT NULL,
    input_id TEXT NOT NULL REFERENCES metadata_input(id),
    active INTEGER NOT NULL CHECK(active IN (0,1)),
    FOREIGN KEY(library_id,item_id) REFERENCES library_item(library_id,id),
    FOREIGN KEY(library_id,person_id) REFERENCES library_person(library_id,id)
) STRICT;
CREATE INDEX item_credit_active ON item_credit(library_id,item_id,active,position,id);

CREATE TABLE rating_observation (
    id TEXT PRIMARY KEY NOT NULL,
    library_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    source_key BLOB NOT NULL,
    input_id TEXT NOT NULL REFERENCES metadata_input(id),
    scope TEXT NOT NULL,
    rating_json TEXT NOT NULL CHECK(json_valid(rating_json)),
    position INTEGER NOT NULL CHECK(position >= 0),
    active INTEGER NOT NULL CHECK(active IN (0,1)),
    FOREIGN KEY(library_id,item_id) REFERENCES library_item(library_id,id)
) STRICT;
CREATE INDEX rating_observation_active ON rating_observation(library_id,item_id,active,position,id);

CREATE TABLE artwork_declaration (
    id TEXT PRIMARY KEY NOT NULL,
    library_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    binding_id TEXT NOT NULL REFERENCES library_source(binding_id),
    source_key BLOB NOT NULL,
    input_id TEXT NOT NULL REFERENCES metadata_input(id),
    private_ref BLOB,
    role TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('declared', 'remote_unavailable', 'invalid')),
    position INTEGER NOT NULL CHECK(position >= 0),
    active INTEGER NOT NULL CHECK(active IN (0,1)),
    FOREIGN KEY(library_id,item_id) REFERENCES library_item(library_id,id)
) STRICT;
CREATE INDEX artwork_declaration_active ON artwork_declaration(library_id,item_id,active,position,id);

CREATE TABLE item_external_id (
    id TEXT PRIMARY KEY NOT NULL,
    library_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    provider TEXT NOT NULL,
    entity_kind TEXT NOT NULL,
    external_id TEXT NOT NULL,
    source_key BLOB NOT NULL,
    input_id TEXT NOT NULL REFERENCES metadata_input(id),
    active INTEGER NOT NULL CHECK(active IN (0,1)),
    FOREIGN KEY(library_id,item_id) REFERENCES library_item(library_id,id)
) STRICT;
CREATE INDEX item_external_id_active ON item_external_id(library_id,item_id,provider,entity_kind,active);
`
