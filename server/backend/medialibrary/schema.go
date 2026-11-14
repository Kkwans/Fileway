package medialibrary

const schemaV17 = `
ALTER TABLE user_watch_progress ADD COLUMN continue_hidden INTEGER NOT NULL DEFAULT 0 CHECK(continue_hidden IN (0,1));
CREATE INDEX watch_continue_recent ON user_watch_progress(user_id, updated_at DESC, variant_id DESC)
    WHERE continue_hidden=0 AND completed=0 AND position_ms>0 AND updated_at>0;
`

const schemaV16 = `
CREATE TABLE user_watch_progress (
    user_id INTEGER NOT NULL CHECK(user_id>0),
    library_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    source_file_id TEXT NOT NULL REFERENCES source_file(id),
    edition_id TEXT NOT NULL,
    variant_id TEXT NOT NULL,
    file_revision INTEGER NOT NULL,
    identity_epoch INTEGER NOT NULL,
    edition_revision INTEGER NOT NULL,
    mapping_revision INTEGER NOT NULL,
    native_stamp TEXT NOT NULL,
    session_id TEXT NOT NULL,
    reset_revision INTEGER NOT NULL,
    sequence INTEGER NOT NULL DEFAULT 0,
    position_ms INTEGER NOT NULL DEFAULT 0,
    duration_ms INTEGER,
    completed INTEGER NOT NULL DEFAULT 0 CHECK(completed IN (0,1)),
    updated_at INTEGER NOT NULL CHECK(updated_at>=0),
    PRIMARY KEY(user_id,library_id,item_id,edition_id,variant_id),
    FOREIGN KEY(library_id,item_id) REFERENCES library_item(library_id,id)
) STRICT;
`

const schemaV15 = `
CREATE TABLE provider_match_revision (
    library_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    match_revision INTEGER NOT NULL CHECK(match_revision>0),
    structure_revision INTEGER NOT NULL CHECK(structure_revision>0),
    previous_json TEXT CHECK(previous_json IS NULL OR json_valid(previous_json)),
    PRIMARY KEY(library_id,item_id,match_revision),
    FOREIGN KEY(library_id,item_id) REFERENCES library_item(library_id,id)
) STRICT;
CREATE TABLE provider_match_manual_review (
    library_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    PRIMARY KEY(library_id,item_id),
    FOREIGN KEY(library_id,item_id) REFERENCES library_item(library_id,id)
) STRICT;
`

const schemaV14 = `
CREATE TABLE metadata_job (
    job_id TEXT PRIMARY KEY NOT NULL REFERENCES job(id),
    scan_id TEXT NOT NULL REFERENCES scan_job(job_id),
    scan_generation INTEGER NOT NULL CHECK(scan_generation>0),
    connection_id TEXT NOT NULL REFERENCES provider_connection(id),
    connection_revision INTEGER NOT NULL CHECK(connection_revision>0),
    policy_revision INTEGER NOT NULL CHECK(policy_revision>0),
    failure_code TEXT NOT NULL DEFAULT '',
    UNIQUE(scan_id,scan_generation)
) STRICT;
CREATE TABLE metadata_job_target (
    job_id TEXT NOT NULL REFERENCES metadata_job(job_id),
    library_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    outcome TEXT NOT NULL DEFAULT 'pending' CHECK(outcome IN ('pending','matched','already_matched','needs_review','no_match','source_changed','failed')),
    reason TEXT NOT NULL DEFAULT '',
    PRIMARY KEY(job_id,item_id),
    FOREIGN KEY(library_id,item_id) REFERENCES library_item(library_id,id)
) STRICT;
CREATE INDEX metadata_job_target_pending ON metadata_job_target(job_id,item_id) WHERE outcome='pending';
CREATE INDEX metadata_job_target_review ON metadata_job_target(job_id,item_id) WHERE outcome IN ('needs_review','no_match');
CREATE INDEX library_file_scan_observation ON library_file(library_id,last_seen_job_id,last_seen_generation,source_file_id) WHERE last_seen_job_id IS NOT NULL;
`

const schemaV13 = `
CREATE TABLE item_provider_match (
    library_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    connection_id TEXT NOT NULL REFERENCES provider_connection(id),
    provider TEXT NOT NULL CHECK(provider='tmdb'),
    entity_kind TEXT NOT NULL CHECK(entity_kind IN ('movie','series')),
    external_id INTEGER NOT NULL CHECK(external_id>0),
    language TEXT NOT NULL,
    document_json TEXT NOT NULL CHECK(json_valid(document_json) AND length(document_json)<=262144),
    fetched_at INTEGER NOT NULL CHECK(fetched_at>0),
    revision INTEGER NOT NULL CHECK(revision>0),
    PRIMARY KEY(library_id,item_id),
    FOREIGN KEY(library_id,item_id) REFERENCES library_item(library_id,id)
) STRICT;
`

// Rebuild this leaf table to extend its mode constraint while retaining
// connection ownership, revisions, and existing policy receipts.
const schemaV12 = `
CREATE TABLE library_metadata_policy_v12 (
    library_id TEXT PRIMARY KEY NOT NULL REFERENCES library(id),
    mode TEXT NOT NULL CHECK(mode IN ('local_only','local_first_online','online_first')),
    connection_id TEXT REFERENCES provider_connection(id),
    language TEXT NOT NULL DEFAULT '',
    region TEXT NOT NULL DEFAULT '',
    allow_editor_jobs INTEGER NOT NULL DEFAULT 0 CHECK(allow_editor_jobs IN (0,1)),
    revision INTEGER NOT NULL CHECK(revision > 0),
    updated_at INTEGER NOT NULL CHECK(updated_at >= 0),
    CHECK(mode = 'local_only' OR connection_id IS NOT NULL)
) STRICT;
INSERT INTO library_metadata_policy_v12(library_id,mode,connection_id,language,region,allow_editor_jobs,revision,updated_at)
SELECT library_id,mode,connection_id,language,region,allow_editor_jobs,revision,updated_at FROM library_metadata_policy;
DROP TABLE library_metadata_policy;
ALTER TABLE library_metadata_policy_v12 RENAME TO library_metadata_policy;
`

// Provider ciphertext is separate from the connection metadata. The vault key
// is owned by the server runtime and never stored in this database.
const schemaV11 = `
CREATE TABLE provider_credential (
    credential_ref TEXT PRIMARY KEY NOT NULL,
    ciphertext BLOB NOT NULL,
    fingerprint BLOB NOT NULL,
    updated_at INTEGER NOT NULL CHECK(updated_at >= 0),
    CHECK((length(ciphertext)=0 AND length(fingerprint)=0) OR (length(ciphertext)>0 AND length(fingerprint)=32))
) STRICT;
CREATE TABLE provider_connection (
    id TEXT PRIMARY KEY NOT NULL,
    provider TEXT NOT NULL CHECK(provider = 'tmdb'),
    owner_kind TEXT NOT NULL CHECK(owner_kind IN ('user','instance')),
    owner_user_id INTEGER CHECK(owner_user_id > 0),
    credential_ref TEXT NOT NULL UNIQUE REFERENCES provider_credential(credential_ref),
    status TEXT NOT NULL CHECK(status IN ('configured','invalid','missing')),
    revision INTEGER NOT NULL CHECK(revision > 0),
    created_at INTEGER NOT NULL CHECK(created_at >= 0),
    updated_at INTEGER NOT NULL CHECK(updated_at >= created_at),
    CHECK((owner_kind='user' AND owner_user_id IS NOT NULL) OR (owner_kind='instance' AND owner_user_id IS NULL))
) STRICT;
CREATE INDEX provider_connection_owner ON provider_connection(owner_kind,owner_user_id,id);
CREATE TABLE provider_grant (
    connection_id TEXT NOT NULL REFERENCES provider_connection(id),
    subject_kind TEXT NOT NULL CHECK(subject_kind IN ('user','library')),
    subject_id TEXT NOT NULL CHECK(length(subject_id)>0),
    allowed INTEGER NOT NULL CHECK(allowed IN (0,1)),
    revision INTEGER NOT NULL CHECK(revision > 0),
    updated_at INTEGER NOT NULL CHECK(updated_at >= 0),
    PRIMARY KEY(connection_id,subject_kind,subject_id)
) STRICT;
CREATE TABLE library_metadata_policy (
    library_id TEXT PRIMARY KEY NOT NULL REFERENCES library(id),
    mode TEXT NOT NULL CHECK(mode IN ('local_only','local_first_online')),
    connection_id TEXT REFERENCES provider_connection(id),
    language TEXT NOT NULL DEFAULT '',
    region TEXT NOT NULL DEFAULT '',
    allow_editor_jobs INTEGER NOT NULL DEFAULT 0 CHECK(allow_editor_jobs IN (0,1)),
    revision INTEGER NOT NULL CHECK(revision > 0),
    updated_at INTEGER NOT NULL CHECK(updated_at >= 0),
    CHECK(mode != 'local_first_online' OR connection_id IS NOT NULL)
) STRICT;
`

// Manual user state is scoped to one catalog item. A missing row is revision
// zero; explicit unwatched is retained as a reset fence for future events.
// No cross-database foreign key or cross-library identity is implied.
const schemaV10 = `
CREATE TABLE user_item_state (
    user_id INTEGER NOT NULL CHECK(user_id > 0),
    library_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    favorite INTEGER NOT NULL DEFAULT 0 CHECK(favorite IN (0,1)),
    manual_watched INTEGER CHECK(manual_watched IN (0,1)),
    watch_reset_revision INTEGER NOT NULL DEFAULT 0 CHECK(watch_reset_revision >= 0),
    revision INTEGER NOT NULL CHECK(revision > 0),
    updated_at INTEGER NOT NULL CHECK(updated_at >= 0),
    PRIMARY KEY(user_id, library_id, item_id),
    FOREIGN KEY(library_id, item_id) REFERENCES library_item(library_id, id)
) STRICT;
`

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

// Mapping generations track only edition/item and variant/edition/file
// relationships. Metadata edits do not alter them. Artwork generations fence
// deactivation/reactivation and declaration changes, including ABA restores.
const schemaV7 = `
ALTER TABLE library ADD COLUMN access_revision INTEGER NOT NULL DEFAULT 1 CHECK(access_revision>0);
ALTER TABLE library_file ADD COLUMN locator_revision INTEGER NOT NULL DEFAULT 1 CHECK(locator_revision>0);
ALTER TABLE scan_missing_candidate ADD COLUMN locator_revision INTEGER NOT NULL DEFAULT 1 CHECK(locator_revision>0);
ALTER TABLE edition ADD COLUMN mapping_revision INTEGER NOT NULL DEFAULT 1 CHECK(mapping_revision>0);
ALTER TABLE variant ADD COLUMN mapping_revision INTEGER NOT NULL DEFAULT 1 CHECK(mapping_revision>0);
ALTER TABLE artwork_declaration ADD COLUMN declaration_revision INTEGER NOT NULL DEFAULT 1 CHECK(declaration_revision>0);

CREATE TRIGGER library_access_changed AFTER UPDATE OF archived_at,owner_user_id ON library
WHEN NEW.archived_at IS NOT OLD.archived_at OR NEW.owner_user_id IS NOT OLD.owner_user_id
BEGIN UPDATE library SET access_revision=OLD.access_revision+1 WHERE id=NEW.id; END;
CREATE TRIGGER library_file_locator_changed AFTER UPDATE OF locator,missing_since,binding_epoch ON library_file
WHEN NEW.locator IS NOT OLD.locator OR NEW.missing_since IS NOT OLD.missing_since OR NEW.binding_epoch IS NOT OLD.binding_epoch
BEGIN UPDATE library_file SET locator_revision=OLD.locator_revision+1 WHERE library_id=NEW.library_id AND source_id=NEW.source_id AND root_locator=NEW.root_locator AND source_file_id=NEW.source_file_id; END;
CREATE TRIGGER edition_id_immutable BEFORE UPDATE OF id ON edition WHEN NEW.id IS NOT OLD.id
BEGIN SELECT RAISE(ABORT, 'edition ID is immutable'); END;
CREATE TRIGGER variant_id_immutable BEFORE UPDATE OF id ON variant WHEN NEW.id IS NOT OLD.id
BEGIN SELECT RAISE(ABORT, 'variant ID is immutable'); END;
CREATE TRIGGER artwork_id_immutable BEFORE UPDATE OF id ON artwork_declaration WHEN NEW.id IS NOT OLD.id
BEGIN SELECT RAISE(ABORT, 'artwork ID is immutable'); END;
CREATE TRIGGER edition_mapping_changed AFTER UPDATE OF library_id,item_id ON edition
WHEN NEW.library_id IS NOT OLD.library_id OR NEW.item_id IS NOT OLD.item_id
BEGIN UPDATE edition SET mapping_revision=OLD.mapping_revision+1 WHERE id=NEW.id; END;
CREATE TRIGGER variant_mapping_changed AFTER UPDATE OF library_id,edition_id,source_file_id ON variant
WHEN NEW.library_id IS NOT OLD.library_id OR NEW.edition_id IS NOT OLD.edition_id OR NEW.source_file_id IS NOT OLD.source_file_id
BEGIN UPDATE variant SET mapping_revision=OLD.mapping_revision+1 WHERE id=NEW.id; END;
CREATE TRIGGER artwork_declaration_changed AFTER UPDATE OF library_id,item_id,binding_id,source_key,input_id,private_ref,role,status,active ON artwork_declaration
WHEN NEW.library_id IS NOT OLD.library_id OR NEW.item_id IS NOT OLD.item_id OR NEW.binding_id IS NOT OLD.binding_id OR NEW.source_key IS NOT OLD.source_key OR NEW.input_id IS NOT OLD.input_id OR NEW.private_ref IS NOT OLD.private_ref OR NEW.role IS NOT OLD.role OR NEW.status IS NOT OLD.status OR NEW.active IS NOT OLD.active
BEGIN UPDATE artwork_declaration SET declaration_revision=OLD.declaration_revision+1 WHERE id=NEW.id; END;
`

// v8 keeps v1 proof bytes and their native semantics unchanged. A v2 binding
// stores its mapping proof separately and clears all legacy proof fields; the
// active version and shared proof epoch make the two representations exclusive.
// Existing membership/metadata remains at its original binding epoch.
const schemaV8 = `
CREATE INDEX operation_receipt_actor_operation ON operation_receipt(actor_user_id,operation_id);
CREATE TRIGGER library_source_target_immutable BEFORE UPDATE OF library_id,source_id,root_locator ON library_source
WHEN NEW.library_id IS NOT OLD.library_id OR NEW.source_id IS NOT OLD.source_id OR NEW.root_locator IS NOT OLD.root_locator
BEGIN SELECT RAISE(ABORT, 'binding target is immutable'); END;
ALTER TABLE library_source ADD COLUMN active_proof_version INTEGER NOT NULL DEFAULT 0 CHECK(active_proof_version IN (0,1,2));
ALTER TABLE library_source ADD COLUMN v2_mapping_digest BLOB;
ALTER TABLE library_source ADD COLUMN v2_attested_at INTEGER CHECK(v2_attested_at >= 0);
UPDATE library_source SET active_proof_version=proof_version;
DROP TRIGGER binding_proof_insert;
DROP TRIGGER binding_proof_update;
DROP TRIGGER binding_proof_fence;
CREATE TRIGGER binding_proof_insert BEFORE INSERT ON library_source
WHEN COALESCE(((NEW.active_proof_version=0 AND NEW.proof_version=0 AND NEW.proof_epoch=0 AND NEW.mapping_digest IS NULL AND NEW.root_identity_kind='' AND NEW.root_identity IS NULL AND NEW.attested_at IS NULL AND NEW.v2_mapping_digest IS NULL AND NEW.v2_attested_at IS NULL)
OR (NEW.active_proof_version=1 AND NEW.proof_version=1 AND NEW.proof_epoch>0 AND length(NEW.mapping_digest)=32 AND NEW.root_identity_kind='native' AND length(NEW.root_identity) BETWEEN 1 AND 1024 AND NEW.attested_at IS NOT NULL AND NEW.v2_mapping_digest IS NULL AND NEW.v2_attested_at IS NULL)
OR (NEW.active_proof_version=2 AND NEW.proof_version=0 AND NEW.proof_epoch>0 AND NEW.mapping_digest IS NULL AND NEW.root_identity_kind='' AND NEW.root_identity IS NULL AND NEW.attested_at IS NULL AND length(NEW.v2_mapping_digest)=32 AND NEW.v2_mapping_digest!=zeroblob(32) AND NEW.v2_attested_at IS NOT NULL)),0)=0
BEGIN SELECT RAISE(ABORT, 'invalid binding proof'); END;
CREATE TRIGGER binding_proof_update BEFORE UPDATE OF active_proof_version,proof_version,proof_epoch,mapping_digest,root_identity_kind,root_identity,attested_at,v2_mapping_digest,v2_attested_at ON library_source
WHEN COALESCE(((NEW.active_proof_version=0 AND NEW.proof_version=0 AND NEW.proof_epoch=0 AND NEW.mapping_digest IS NULL AND NEW.root_identity_kind='' AND NEW.root_identity IS NULL AND NEW.attested_at IS NULL AND NEW.v2_mapping_digest IS NULL AND NEW.v2_attested_at IS NULL)
OR (NEW.active_proof_version=1 AND NEW.proof_version=1 AND NEW.proof_epoch>0 AND length(NEW.mapping_digest)=32 AND NEW.root_identity_kind='native' AND length(NEW.root_identity) BETWEEN 1 AND 1024 AND NEW.attested_at IS NOT NULL AND NEW.v2_mapping_digest IS NULL AND NEW.v2_attested_at IS NULL)
OR (NEW.active_proof_version=2 AND NEW.proof_version=0 AND NEW.proof_epoch>0 AND NEW.mapping_digest IS NULL AND NEW.root_identity_kind='' AND NEW.root_identity IS NULL AND NEW.attested_at IS NULL AND length(NEW.v2_mapping_digest)=32 AND NEW.v2_mapping_digest!=zeroblob(32) AND NEW.v2_attested_at IS NOT NULL)),0)=0
BEGIN SELECT RAISE(ABORT, 'invalid binding proof'); END;
CREATE TRIGGER binding_proof_fence BEFORE UPDATE OF active_proof_version,proof_version,proof_epoch,mapping_digest,root_identity_kind,root_identity,attested_at,v2_mapping_digest,v2_attested_at ON library_source
WHEN NEW.active_proof_version NOT IN (1,2) OR NEW.proof_epoch!=OLD.proof_epoch+1 OR NEW.revision!=OLD.revision+1
BEGIN SELECT RAISE(ABORT, 'binding proof requires a new epoch and revision'); END;
CREATE TABLE binding_domain (
    id TEXT PRIMARY KEY NOT NULL CHECK(length(id)>0),
    binding_id TEXT NOT NULL REFERENCES library_source(binding_id),
    proof_epoch INTEGER NOT NULL CHECK(proof_epoch>0),
    relative_prefix BLOB NOT NULL CHECK(length(relative_prefix)<=8192),
    mapping_digest BLOB NOT NULL CHECK(length(mapping_digest)=32 AND mapping_digest!=zeroblob(32)),
    root_evidence BLOB CHECK(root_evidence IS NULL OR (length(root_evidence)=32 AND root_evidence!=zeroblob(32))),
    strong_root_evidence BLOB CHECK(strong_root_evidence IS NULL OR (length(strong_root_evidence)=32 AND strong_root_evidence!=zeroblob(32))),
    evidence_kind TEXT NOT NULL CHECK(evidence_kind IN ('native_generation','mount_scoped','path_observed')),
    mount_evidence BLOB CHECK(mount_evidence IS NULL OR (length(mount_evidence)=32 AND mount_evidence!=zeroblob(32))),
    anchor_digest BLOB NOT NULL CHECK(length(anchor_digest)=32 AND anchor_digest!=zeroblob(32)),
    mount_epoch INTEGER NOT NULL CHECK(mount_epoch>0),
    revision INTEGER NOT NULL CHECK(revision>0),
    state TEXT NOT NULL CHECK(state IN ('available','offline','revalidating','rebind_required')),
    resume_state TEXT NOT NULL DEFAULT '' CHECK(resume_state IN ('','available','revalidating')),
    created_at INTEGER NOT NULL CHECK(created_at>=0),
    updated_at INTEGER NOT NULL CHECK(updated_at>=0),
    CHECK((state='offline' AND resume_state IN ('available','revalidating')) OR (state!='offline' AND resume_state='')),
    UNIQUE(binding_id,proof_epoch,relative_prefix)
) STRICT;
CREATE TRIGGER binding_domain_insert BEFORE INSERT ON binding_domain
WHEN NOT EXISTS(SELECT 1 FROM library_source ls WHERE ls.binding_id=NEW.binding_id AND ls.active_proof_version=2 AND ls.proof_epoch=NEW.proof_epoch)
BEGIN SELECT RAISE(ABORT, 'domain requires current v2 binding'); END;
CREATE TRIGGER binding_domain_identity BEFORE UPDATE OF id,binding_id,proof_epoch,relative_prefix,mapping_digest,strong_root_evidence ON binding_domain
WHEN NEW.id IS NOT OLD.id OR NEW.binding_id IS NOT OLD.binding_id OR NEW.proof_epoch IS NOT OLD.proof_epoch OR NEW.relative_prefix IS NOT OLD.relative_prefix OR NEW.mapping_digest IS NOT OLD.mapping_digest OR NEW.strong_root_evidence IS NOT OLD.strong_root_evidence
BEGIN SELECT RAISE(ABORT, 'domain identity and mapping are immutable'); END;
CREATE TRIGGER binding_domain_fence BEFORE UPDATE ON binding_domain
WHEN NEW.revision!=OLD.revision+1 OR NEW.mount_epoch<OLD.mount_epoch OR NEW.mount_epoch>OLD.mount_epoch+1
OR (OLD.state='rebind_required' AND NEW.state!='rebind_required')
OR (NEW.mount_epoch=OLD.mount_epoch+1 AND NEW.state!='revalidating')
OR (NEW.mount_epoch=OLD.mount_epoch AND (NEW.root_evidence IS NOT OLD.root_evidence OR NEW.mount_evidence IS NOT OLD.mount_evidence OR NEW.evidence_kind IS NOT OLD.evidence_kind))
OR (NEW.mount_epoch=OLD.mount_epoch AND NEW.anchor_digest IS NOT OLD.anchor_digest AND NOT (OLD.evidence_kind='native_generation' AND NEW.evidence_kind='native_generation' AND OLD.strong_root_evidence IS NOT NULL AND OLD.strong_root_evidence IS OLD.root_evidence AND NEW.root_evidence IS OLD.root_evidence AND NEW.mount_evidence IS OLD.mount_evidence))
OR (OLD.state='revalidating' AND NEW.state='available')
OR (OLD.state='offline' AND OLD.resume_state='revalidating' AND NEW.state='available')
OR NOT EXISTS(SELECT 1 FROM library_source ls WHERE ls.binding_id=NEW.binding_id AND ls.active_proof_version=2 AND ls.proof_epoch=NEW.proof_epoch)
BEGIN SELECT RAISE(ABORT, 'stale domain update'); END;
`

// v9 adds local scan context, ordering and explicit file-to-item mappings.
const schemaV9 = `
-- Additive C1.1 structure state. Existing item, edition, variant and binding
-- identifiers are preserved; paths remain private opaque bytes.
CREATE TABLE item_structure (
    library_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    series_id TEXT,
    season_id TEXT,
    superseded_by TEXT,
    match_state TEXT NOT NULL DEFAULT 'unmatched' CHECK(match_state IN ('unmatched','local_confirmed','provider_confirmed','needs_review')),
    issue_code TEXT NOT NULL DEFAULT '',
    structure_revision INTEGER NOT NULL DEFAULT 1 CHECK(structure_revision > 0),
    first_added_at INTEGER NOT NULL CHECK(first_added_at >= 0),
    series_updated_at INTEGER CHECK(series_updated_at >= first_added_at),
    PRIMARY KEY(library_id,item_id),
    FOREIGN KEY(library_id,item_id) REFERENCES library_item(library_id,id),
    FOREIGN KEY(library_id,series_id) REFERENCES library_item(library_id,id),
    FOREIGN KEY(library_id,season_id) REFERENCES library_item(library_id,id),
    FOREIGN KEY(library_id,superseded_by) REFERENCES library_item(library_id,id),
    CHECK(superseded_by IS NULL OR superseded_by!=item_id)
) STRICT;
INSERT INTO item_structure(library_id,item_id,first_added_at)
SELECT library_id,id,created_at FROM library_item;
CREATE TRIGGER item_structure_create AFTER INSERT ON library_item
BEGIN
  INSERT INTO item_structure(library_id,item_id,first_added_at) VALUES(NEW.library_id,NEW.id,NEW.created_at);
END;

CREATE TABLE directory_context (
    id TEXT PRIMARY KEY NOT NULL CHECK(length(id)>0),
    library_id TEXT NOT NULL,
    binding_id TEXT NOT NULL REFERENCES library_source(binding_id),
    binding_epoch INTEGER NOT NULL CHECK(binding_epoch>0),
    directory_locator BLOB NOT NULL CHECK(length(directory_locator)>0),
    parent_context_id TEXT REFERENCES directory_context(id),
    parent_revision INTEGER NOT NULL DEFAULT 0 CHECK(parent_revision>=0),
    series_id TEXT,
    season_id TEXT,
    season_number INTEGER CHECK(season_number BETWEEN 0 AND 999),
    series_problem TEXT NOT NULL DEFAULT '' CHECK(series_problem IN ('','unavailable','conflict')),
    season_problem TEXT NOT NULL DEFAULT '' CHECK(season_problem IN ('','unavailable','conflict')),
    local_series_id TEXT,
    local_season_id TEXT,
    tv_status TEXT NOT NULL CHECK(tv_status IN ('missing','parsed','invalid','unavailable','changed','conflict','needs_review')),
    season_status TEXT NOT NULL CHECK(season_status IN ('missing','parsed','invalid','unavailable','changed','conflict','orphan','needs_review')),
    tv_key BLOB, tv_digest BLOB, tv_parser INTEGER NOT NULL DEFAULT 0 CHECK(tv_parser>=0),
    season_key BLOB, season_digest BLOB, season_parser INTEGER NOT NULL DEFAULT 0 CHECK(season_parser>=0),
    tv_evidence_json TEXT CHECK(tv_evidence_json IS NULL OR json_valid(tv_evidence_json)),
    season_evidence_json TEXT CHECK(season_evidence_json IS NULL OR json_valid(season_evidence_json)),
    rules_revision INTEGER NOT NULL CHECK(rules_revision>0),
    revision INTEGER NOT NULL CHECK(revision>0),
    UNIQUE(binding_id,binding_epoch,directory_locator),
    FOREIGN KEY(library_id,series_id) REFERENCES library_item(library_id,id),
    FOREIGN KEY(library_id,season_id) REFERENCES library_item(library_id,id),
    FOREIGN KEY(library_id,local_series_id) REFERENCES library_item(library_id,id),
    FOREIGN KEY(library_id,local_season_id) REFERENCES library_item(library_id,id),
    CHECK((tv_key IS NULL)=(tv_digest IS NULL)),
    CHECK((tv_digest IS NULL AND tv_parser=0) OR (length(tv_digest)=32 AND tv_parser>0)),
    CHECK((season_key IS NULL)=(season_digest IS NULL)),
    CHECK((season_digest IS NULL AND season_parser=0) OR (length(season_digest)=32 AND season_parser>0)),
    CHECK((parent_context_id IS NULL)=(parent_revision=0))
) STRICT;
CREATE INDEX directory_context_parent ON directory_context(parent_context_id);

CREATE TABLE episode_order (
    id TEXT PRIMARY KEY NOT NULL CHECK(length(id)>0),
    library_id TEXT NOT NULL,
    series_id TEXT NOT NULL,
    scheme TEXT NOT NULL CHECK(scheme IN ('local','aired','absolute','dvd','provider_group')),
    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision>0),
    UNIQUE(library_id,id),
    FOREIGN KEY(library_id,series_id) REFERENCES library_item(library_id,id)
) STRICT;
CREATE UNIQUE INDEX episode_order_standard ON episode_order(library_id,series_id,scheme) WHERE scheme IN ('local','aired','absolute','dvd');
CREATE TABLE season_position (
    library_id TEXT NOT NULL,
    order_id TEXT NOT NULL,
    season_id TEXT NOT NULL,
    season_number INTEGER NOT NULL CHECK(season_number BETWEEN 0 AND 999),
    PRIMARY KEY(library_id,order_id,season_number),
    UNIQUE(library_id,order_id,season_id),
    FOREIGN KEY(library_id,order_id) REFERENCES episode_order(library_id,id),
    FOREIGN KEY(library_id,season_id) REFERENCES library_item(library_id,id)
) STRICT;
CREATE TABLE episode_position (
    library_id TEXT NOT NULL,
    order_id TEXT NOT NULL,
    season_number INTEGER NOT NULL CHECK(season_number BETWEEN 0 AND 999),
    episode_number INTEGER NOT NULL CHECK(episode_number BETWEEN 0 AND 9999),
    episode_id TEXT NOT NULL,
    PRIMARY KEY(library_id,order_id,season_number,episode_number),
    UNIQUE(library_id,order_id,episode_id),
    FOREIGN KEY(library_id,order_id,season_number) REFERENCES season_position(library_id,order_id,season_number),
    FOREIGN KEY(library_id,episode_id) REFERENCES library_item(library_id,id)
) STRICT;

CREATE TABLE identification_evidence (
    id TEXT PRIMARY KEY NOT NULL CHECK(length(id)>0),
    library_id TEXT NOT NULL,
    source_file_id TEXT NOT NULL REFERENCES source_file(id),
    binding_id TEXT NOT NULL REFERENCES library_source(binding_id),
    binding_epoch INTEGER NOT NULL CHECK(binding_epoch>0),
    context_id TEXT REFERENCES directory_context(id),
    context_revision INTEGER NOT NULL DEFAULT 0 CHECK(context_revision>=0),
    parser_revision INTEGER NOT NULL CHECK(parser_revision>0),
    evidence_json TEXT NOT NULL CHECK(json_valid(evidence_json) AND length(evidence_json)<=65536),
    match_state TEXT NOT NULL CHECK(match_state IN ('unmatched','local_confirmed','needs_review')),
    issue_json TEXT NOT NULL CHECK(json_valid(issue_json)),
    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision>0),
    UNIQUE(library_id,source_file_id,binding_id,binding_epoch),
    CHECK((context_id IS NULL)=(context_revision=0))
) STRICT;
CREATE TABLE file_item_mapping (
    id TEXT PRIMARY KEY NOT NULL CHECK(length(id)>0),
    library_id TEXT NOT NULL,
    source_file_id TEXT NOT NULL REFERENCES source_file(id),
    item_id TEXT NOT NULL,
    variant_id TEXT NOT NULL REFERENCES variant(id),
    binding_id TEXT REFERENCES library_source(binding_id),
    binding_epoch INTEGER NOT NULL CHECK(binding_epoch>=0),
    role TEXT NOT NULL CHECK(role IN ('single','combined','part')),
    part_position INTEGER CHECK(part_position>0),
    start_ms INTEGER CHECK(start_ms>=0),
    end_ms INTEGER CHECK(end_ms>=0),
    evidence_id TEXT REFERENCES identification_evidence(id),
    confirmed_by TEXT NOT NULL CHECK(confirmed_by IN ('legacy','local','manual')),
    active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1)),
    mapping_revision INTEGER NOT NULL DEFAULT 1 CHECK(mapping_revision>0),
    FOREIGN KEY(library_id,item_id) REFERENCES library_item(library_id,id),
    CHECK(start_ms IS NULL OR end_ms IS NULL OR end_ms>start_ms),
    CHECK(binding_id IS NOT NULL OR binding_epoch=0)
) STRICT;
CREATE UNIQUE INDEX file_item_mapping_active ON file_item_mapping(library_id,variant_id,COALESCE(binding_id,''),binding_epoch) WHERE active=1;
CREATE INDEX file_item_mapping_item ON file_item_mapping(library_id,item_id,active,source_file_id);
CREATE TRIGGER file_item_mapping_validate_insert BEFORE INSERT ON file_item_mapping
WHEN NOT EXISTS(SELECT 1 FROM variant v JOIN edition e ON e.id=v.edition_id AND e.library_id=v.library_id WHERE v.id=NEW.variant_id AND v.library_id=NEW.library_id AND v.source_file_id=NEW.source_file_id AND e.item_id=NEW.item_id)
OR (NEW.binding_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM library_source ls WHERE ls.binding_id=NEW.binding_id AND ls.library_id=NEW.library_id))
OR (NEW.evidence_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM identification_evidence ie WHERE ie.id=NEW.evidence_id AND ie.library_id=NEW.library_id AND ie.source_file_id=NEW.source_file_id AND ie.binding_id=NEW.binding_id AND ie.binding_epoch=NEW.binding_epoch))
BEGIN SELECT RAISE(ABORT,'invalid file item mapping'); END;
CREATE TRIGGER file_item_mapping_validate_update BEFORE UPDATE ON file_item_mapping
WHEN NOT EXISTS(SELECT 1 FROM variant v JOIN edition e ON e.id=v.edition_id AND e.library_id=v.library_id WHERE v.id=NEW.variant_id AND v.library_id=NEW.library_id AND v.source_file_id=NEW.source_file_id AND e.item_id=NEW.item_id)
OR (NEW.binding_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM library_source ls WHERE ls.binding_id=NEW.binding_id AND ls.library_id=NEW.library_id))
OR (NEW.evidence_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM identification_evidence ie WHERE ie.id=NEW.evidence_id AND ie.library_id=NEW.library_id AND ie.source_file_id=NEW.source_file_id AND ie.binding_id=NEW.binding_id AND ie.binding_epoch=NEW.binding_epoch))
BEGIN SELECT RAISE(ABORT,'invalid file item mapping'); END;
-- A first, still-current v1 proof has no prior proven binding epoch. A native
-- or scan-scoped path object already observed in that epoch can keep its
-- existing association; both the LF and current binding must be at epoch 1.
-- Epochs after an explicit rebind cannot prove when an older variant was
-- attached, so do not turn their current LF value into historical evidence.
INSERT INTO file_item_mapping(id,library_id,source_file_id,item_id,variant_id,binding_id,binding_epoch,role,confirmed_by)
SELECT 'map_'||lower(hex(randomblob(16))),v.library_id,v.source_file_id,e.item_id,v.id,ls.binding_id,1,'single','legacy'
FROM variant v JOIN edition e ON e.id=v.edition_id AND e.library_id=v.library_id
JOIN source_file sf ON sf.id=v.source_file_id AND sf.identity_kind IN ('native','path_unverified')
JOIN library_file lf ON lf.library_id=v.library_id AND lf.source_file_id=v.source_file_id AND lf.binding_epoch=1
JOIN library_source ls ON ls.library_id=lf.library_id AND ls.source_id=lf.source_id AND ls.root_locator=lf.root_locator AND ls.proof_epoch=1 AND ls.active_proof_version=1;
-- Every old link remains as unbound evidence, including ambiguous links from
-- later proof epochs and older unverified identities, pending confirmation.
INSERT INTO file_item_mapping(id,library_id,source_file_id,item_id,variant_id,binding_id,binding_epoch,role,confirmed_by)
SELECT 'map_'||lower(hex(randomblob(16))),v.library_id,v.source_file_id,e.item_id,v.id,NULL,0,'single','legacy'
FROM variant v JOIN edition e ON e.id=v.edition_id AND e.library_id=v.library_id;
`
