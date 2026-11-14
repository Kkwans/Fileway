package medialibrary

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"regexp"
	"strconv"
	"time"
)

type ProviderConnection struct {
	ID          string `json:"id"`
	Provider    string `json:"provider"`
	OwnerKind   string `json:"ownerKind"`
	OwnerUserID uint   `json:"ownerUserId,omitempty"`
	Status      string `json:"status"`
	Revision    int64  `json:"revision"`
}

type ProviderGrant struct {
	ConnectionID       string `json:"connectionId"`
	SubjectKind        string `json:"subjectKind"`
	SubjectID          string `json:"subjectId"`
	Allowed            bool   `json:"allowed"`
	Revision           int64  `json:"revision"`
	ConnectionRevision int64  `json:"connectionRevision"`
}

type LibraryMetadataPolicy struct {
	LibraryID       string `json:"libraryId"`
	Mode            string `json:"mode"`
	ConnectionID    string `json:"connectionId,omitempty"`
	Language        string `json:"language"`
	Region          string `json:"region"`
	AllowEditorJobs bool   `json:"allowEditorJobs"`
	Revision        int64  `json:"revision"`
}

const (
	MetadataLocalOnly   = "local_only"
	MetadataLocalFirst  = "local_first_online"
	MetadataOnlineFirst = "online_first"
)

func (policy LibraryMetadataPolicy) OnlineEnabled() bool {
	return policy.Mode == MetadataLocalFirst || policy.Mode == MetadataOnlineFirst
}

// SealedProviderCredential is never a response or operation receipt. The
// fingerprint must be keyed by the external vault, not a hash of the secret.
type SealedProviderCredential struct {
	Ciphertext  []byte `json:"-"`
	Fingerprint []byte `json:"-"`
}

func (SealedProviderCredential) String() string { return "SealedProviderCredential(redacted)" }

// ProviderConnectionID is stable for an actor's create operation. It is an
// opaque reference, not an authorization capability.
func ProviderConnectionID(actor uint, operationID string) (string, error) {
	if actor == 0 || !validOperationID(operationID) {
		return "", ErrInvalid
	}
	encoded, err := json.Marshal(struct {
		Domain    string
		Actor     uint
		Operation string
	}{"fileway/provider-connection/v1", actor, operationID})
	if err != nil {
		return "", err
	}
	digest := sha256.Sum256(encoded)
	return "pc_" + hex.EncodeToString(digest[:16]), nil
}

var providerIDPattern = regexp.MustCompile(`^pc_[0-9a-f]{32}$`)
var providerLanguagePattern = regexp.MustCompile(`^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,2}$`)
var providerRegionPattern = regexp.MustCompile(`^[A-Z]{2}$`)

func ProviderCredentialContext(connectionID string) (string, error) {
	if !providerIDPattern.MatchString(connectionID) {
		return "", ErrInvalid
	}
	return "fileway/provider/tmdb/" + connectionID, nil
}

func scanProviderConnection(row rowScanner) (*ProviderConnection, error) {
	var value ProviderConnection
	var owner sql.NullInt64
	err := row.Scan(&value.ID, &value.Provider, &value.OwnerKind, &owner, &value.Status, &value.Revision)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if owner.Valid {
		value.OwnerUserID = uint(owner.Int64)
	}
	return &value, nil
}

func providerConnection(ctx context.Context, reader schemaReader, id string) (*ProviderConnection, error) {
	return scanProviderConnection(reader.QueryRowContext(ctx, `SELECT id,provider,owner_kind,owner_user_id,status,revision FROM provider_connection WHERE id=?`, id))
}

// providerOperation authorizes before receipt replay; receipts contain only
// sanitized result data and a digest of non-secret input/fingerprint.
func providerOperation[T any](ctx context.Context, store *Store, actor uint, kind, operationID string, input any,
	authorize func(*sql.Tx) error, apply func(*sql.Tx) (T, error)) (T, error) {
	var result T
	if actor == 0 || !validOperationID(operationID) {
		return result, ErrInvalid
	}
	digest, err := operationDigest(input)
	if err != nil {
		return result, err
	}
	err = store.WithTx(ctx, func(tx *sql.Tx) error {
		if err := authorize(tx); err != nil {
			return err
		}
		previous, found, err := lookupOperation[T](ctx, tx, actor, kind, operationID, digest)
		if err != nil {
			return err
		}
		if found {
			result = previous
			return nil
		}
		result, err = apply(tx)
		if err != nil {
			return err
		}
		encoded, err := json.Marshal(result)
		if err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, `INSERT INTO operation_receipt(actor_user_id,kind,operation_id,input_digest,result_json,created_at) VALUES(?,?,?,?,?,?)`,
			actor, kind, operationID, digest, string(encoded), time.Now().UnixMilli())
		return err
	})
	return result, err
}

func providerManager(ctx context.Context, reader schemaReader, actor uint, admin bool, connectionID string) (*ProviderConnection, error) {
	value, err := providerConnection(ctx, reader, connectionID)
	if err != nil {
		return nil, err
	}
	if value.OwnerKind == "user" && value.OwnerUserID == actor || value.OwnerKind == "instance" && admin {
		return value, nil
	}
	return nil, ErrNotFound
}

func (store *Store) GetManagedProviderConnection(ctx context.Context, actor uint, admin bool, connectionID string) (*ProviderConnection, error) {
	if actor == 0 || !providerIDPattern.MatchString(connectionID) {
		return nil, ErrInvalid
	}
	return providerManager(ctx, store.db, actor, admin, connectionID)
}

// PutProviderConnection creates or rotates exactly one TMDB credential.
// ciphertext may change on retry; fingerprint must remain stable for the same
// token and connection context. The caller verifies the current admin role.
func (store *Store) PutProviderConnection(ctx context.Context, actor uint, admin bool, id, ownerKind, operationID string,
	expectedRevision int64, credential SealedProviderCredential) (*ProviderConnection, error) {
	if actor == 0 || !providerIDPattern.MatchString(id) || (ownerKind != "user" && ownerKind != "instance") ||
		expectedRevision < 0 || len(credential.Ciphertext) == 0 || len(credential.Ciphertext) > 8192 || len(credential.Fingerprint) != 32 {
		return nil, ErrInvalid
	}
	if ownerKind == "instance" && !admin {
		return nil, ErrForbidden
	}
	if expectedRevision == 0 {
		want, err := ProviderConnectionID(actor, operationID)
		if err != nil || id != want {
			return nil, ErrInvalid
		}
	}
	input := struct {
		ID, OwnerKind    string
		ExpectedRevision int64
		Fingerprint      []byte
	}{id, ownerKind, expectedRevision, credential.Fingerprint}
	return providerOperation(ctx, store, actor, "provider.connection.put", operationID, input, func(tx *sql.Tx) error {
		if ownerKind == "instance" && !admin {
			return ErrForbidden
		}
		if expectedRevision == 0 {
			return nil
		}
		value, err := providerManager(ctx, tx, actor, admin, id)
		if err != nil {
			return err
		}
		if value.OwnerKind != ownerKind {
			return ErrNotFound
		}
		return nil
	}, func(tx *sql.Tx) (*ProviderConnection, error) {
		now := time.Now().UnixMilli()
		if expectedRevision == 0 {
			if _, err := providerConnection(ctx, tx, id); err == nil {
				return nil, ErrRevisionConflict
			} else if !errors.Is(err, ErrNotFound) {
				return nil, err
			}
			_, err := tx.ExecContext(ctx, `INSERT INTO provider_credential(credential_ref,ciphertext,fingerprint,updated_at) VALUES(?,?,?,?)`, id, credential.Ciphertext, credential.Fingerprint, now)
			if err != nil {
				return nil, err
			}
			var owner any
			if ownerKind == "user" {
				owner = actor
			}
			_, err = tx.ExecContext(ctx, `INSERT INTO provider_connection(id,provider,owner_kind,owner_user_id,credential_ref,status,revision,created_at,updated_at) VALUES(?,'tmdb',?,?,?,'configured',1,?,?)`, id, ownerKind, owner, id, now, now)
			if err != nil {
				return nil, err
			}
			return providerConnection(ctx, tx, id)
		}
		value, err := providerManager(ctx, tx, actor, admin, id)
		if err != nil {
			return nil, err
		}
		if value.Revision != expectedRevision {
			return nil, ErrRevisionConflict
		}
		if _, err = tx.ExecContext(ctx, `UPDATE provider_credential SET ciphertext=?,fingerprint=?,updated_at=? WHERE credential_ref=?`, credential.Ciphertext, credential.Fingerprint, now, id); err != nil {
			return nil, err
		}
		if _, err = tx.ExecContext(ctx, `UPDATE provider_connection SET status='configured',revision=revision+1,updated_at=? WHERE id=?`, now, id); err != nil {
			return nil, err
		}
		return providerConnection(ctx, tx, id)
	})
}

// ClearProviderConnection makes the selected credential unavailable without
// silently switching any library to another connection. Existing encrypted
// pages/backups are not promised to be physically erased.
func (store *Store) ClearProviderConnection(ctx context.Context, actor uint, admin bool, id, operationID string, expectedRevision int64) (*ProviderConnection, error) {
	if actor == 0 || !providerIDPattern.MatchString(id) || expectedRevision < 1 {
		return nil, ErrInvalid
	}
	input := struct {
		ID               string
		ExpectedRevision int64
	}{id, expectedRevision}
	return providerOperation(ctx, store, actor, "provider.connection.clear", operationID, input, func(tx *sql.Tx) error {
		_, err := providerManager(ctx, tx, actor, admin, id)
		return err
	}, func(tx *sql.Tx) (*ProviderConnection, error) {
		value, err := providerManager(ctx, tx, actor, admin, id)
		if err != nil {
			return nil, err
		}
		if value.Revision != expectedRevision {
			return nil, ErrRevisionConflict
		}
		if value.Status == "missing" {
			return value, nil
		}
		now := time.Now().UnixMilli()
		if _, err = tx.ExecContext(ctx, `UPDATE provider_credential SET ciphertext=X'',fingerprint=X'',updated_at=? WHERE credential_ref=?`, now, id); err != nil {
			return nil, err
		}
		if _, err = tx.ExecContext(ctx, `UPDATE provider_connection SET status='missing',revision=revision+1,updated_at=? WHERE id=?`, now, id); err != nil {
			return nil, err
		}
		return providerConnection(ctx, tx, id)
	})
}

type ProviderConnectionPage struct {
	Items      []ProviderConnection `json:"items"`
	NextCursor string               `json:"nextCursor,omitempty"`
}

func (store *Store) ListProviderConnections(ctx context.Context, actor uint, admin, onlyOwned bool, after string, limit int) (*ProviderConnectionPage, error) {
	if actor == 0 {
		return nil, ErrForbidden
	}
	if limit < 1 || limit > 100 || (after != "" && !providerIDPattern.MatchString(after)) {
		return nil, ErrInvalid
	}
	rows, err := store.db.QueryContext(ctx, `SELECT c.id,c.provider,c.owner_kind,c.owner_user_id,c.status,c.revision FROM provider_connection c
WHERE c.id>? AND ((c.owner_kind='user' AND c.owner_user_id=?) OR (c.owner_kind='instance' AND (? OR EXISTS(
SELECT 1 FROM provider_grant g WHERE g.connection_id=c.id AND g.allowed=1 AND
((g.subject_kind='user' AND g.subject_id=?) OR (g.subject_kind='library' AND EXISTS(
SELECT 1 FROM library l LEFT JOIN library_member m ON m.library_id=l.id AND m.user_id=? AND m.revoked_at IS NULL
WHERE l.id=g.subject_id AND l.archived_at IS NULL AND (l.owner_user_id=? OR (?=0 AND m.role='editor'))))))))) ORDER BY c.id LIMIT ?`,
		after, actor, admin, strconv.FormatUint(uint64(actor), 10), actor, actor, onlyOwned, limit+1)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	result := &ProviderConnectionPage{Items: []ProviderConnection{}}
	for rows.Next() {
		value, err := scanProviderConnection(rows)
		if err != nil {
			return nil, err
		}
		if len(result.Items) == limit {
			result.NextCursor = result.Items[len(result.Items)-1].ID
			break
		}
		result.Items = append(result.Items, *value)
	}
	return result, rows.Err()
}

// VerifyProviderConnections rechecks the returned page against live grants and
// revisions before an HTTP response is published.
func (store *Store) VerifyProviderConnections(ctx context.Context, actor uint, admin, onlyOwned bool, values []ProviderConnection) error {
	if actor == 0 || len(values) > 100 {
		return ErrInvalid
	}
	for _, value := range values {
		var revision int64
		err := store.db.QueryRowContext(ctx, `SELECT c.revision FROM provider_connection c WHERE c.id=? AND
((c.owner_kind='user' AND c.owner_user_id=?) OR (c.owner_kind='instance' AND (? OR EXISTS(
SELECT 1 FROM provider_grant g WHERE g.connection_id=c.id AND g.allowed=1 AND
((g.subject_kind='user' AND g.subject_id=?) OR (g.subject_kind='library' AND EXISTS(
SELECT 1 FROM library l LEFT JOIN library_member m ON m.library_id=l.id AND m.user_id=? AND m.revoked_at IS NULL
WHERE l.id=g.subject_id AND l.archived_at IS NULL AND (l.owner_user_id=? OR (?=0 AND m.role='editor')))))))))`,
			value.ID, actor, admin, strconv.FormatUint(uint64(actor), 10), actor, actor, onlyOwned).Scan(&revision)
		if errors.Is(err, sql.ErrNoRows) || err == nil && revision != value.Revision {
			return ErrRevisionConflict
		}
		if err != nil {
			return err
		}
	}
	return nil
}

type ProviderGrantPage struct {
	Items    []ProviderGrant `json:"items"`
	NextKind string          `json:"nextKind,omitempty"`
	NextID   string          `json:"nextId,omitempty"`
}

// ListProviderGrants exposes grant metadata only to a current instance admin.
func (store *Store) ListProviderGrants(ctx context.Context, actor uint, admin bool, connectionID, afterKind, afterID string, limit int) (*ProviderGrantPage, error) {
	if actor == 0 || !admin {
		return nil, ErrForbidden
	}
	if !providerIDPattern.MatchString(connectionID) || limit < 1 || limit > 100 ||
		(afterKind != "" && grantSubject(afterKind, afterID) != nil) || (afterKind == "" && afterID != "") {
		return nil, ErrInvalid
	}
	value, err := providerManager(ctx, store.db, actor, admin, connectionID)
	if err != nil {
		return nil, err
	}
	if value.OwnerKind != "instance" {
		return nil, ErrNotFound
	}
	rows, err := store.db.QueryContext(ctx, `SELECT subject_kind,subject_id,allowed,revision FROM provider_grant WHERE connection_id=? AND (subject_kind>? OR (subject_kind=? AND subject_id>?)) ORDER BY subject_kind,subject_id LIMIT ?`,
		connectionID, afterKind, afterKind, afterID, limit+1)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	result := &ProviderGrantPage{Items: []ProviderGrant{}}
	for rows.Next() {
		var grant ProviderGrant
		var allowed int
		if err := rows.Scan(&grant.SubjectKind, &grant.SubjectID, &allowed, &grant.Revision); err != nil {
			return nil, err
		}
		grant.ConnectionID, grant.ConnectionRevision, grant.Allowed = connectionID, value.Revision, allowed != 0
		if len(result.Items) == limit {
			last := result.Items[len(result.Items)-1]
			result.NextKind, result.NextID = last.SubjectKind, last.SubjectID
			break
		}
		result.Items = append(result.Items, grant)
	}
	return result, rows.Err()
}

func grantSubject(kind, id string) error {
	if kind == "user" {
		n, err := strconv.ParseUint(id, 10, 32)
		if err != nil || n == 0 || strconv.FormatUint(n, 10) != id {
			return ErrInvalid
		}
		return nil
	}
	if kind == "library" && validScanIDLocal(id) {
		return nil
	}
	return ErrInvalid
}

func validScanIDLocal(value string) bool { return validOperationID(value) }

// SetProviderGrant grants or revokes an instance connection for one subject.
// Revocation retains a tombstone and advances the connection revision.
func (store *Store) SetProviderGrant(ctx context.Context, actor uint, admin bool, connectionID, kind, id, operationID string,
	expectedRevision int64, allowed bool) (*ProviderGrant, error) {
	if !admin {
		return nil, ErrForbidden
	}
	if actor == 0 || !providerIDPattern.MatchString(connectionID) || expectedRevision < 1 || grantSubject(kind, id) != nil {
		return nil, ErrInvalid
	}
	input := struct {
		Connection, Kind, ID string
		Expected             int64
		Allowed              bool
	}{connectionID, kind, id, expectedRevision, allowed}
	return providerOperation(ctx, store, actor, "provider.grant.set", operationID, input, func(tx *sql.Tx) error {
		if !admin {
			return ErrForbidden
		}
		value, err := providerManager(ctx, tx, actor, admin, connectionID)
		if err != nil {
			return err
		}
		if value.OwnerKind != "instance" {
			return ErrNotFound
		}
		if kind == "library" {
			var exists int
			if err := tx.QueryRowContext(ctx, `SELECT 1 FROM library WHERE id=? AND archived_at IS NULL`, id).Scan(&exists); errors.Is(err, sql.ErrNoRows) {
				return ErrNotFound
			} else if err != nil {
				return err
			}
		}
		return nil
	}, func(tx *sql.Tx) (*ProviderGrant, error) {
		value, err := providerManager(ctx, tx, actor, admin, connectionID)
		if err != nil {
			return nil, err
		}
		if value.Revision != expectedRevision {
			return nil, ErrRevisionConflict
		}
		var oldAllowed int
		var oldRevision int64
		err = tx.QueryRowContext(ctx, `SELECT allowed,revision FROM provider_grant WHERE connection_id=? AND subject_kind=? AND subject_id=?`, connectionID, kind, id).Scan(&oldAllowed, &oldRevision)
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return nil, err
		}
		if errors.Is(err, sql.ErrNoRows) || (oldAllowed != 0) != allowed {
			oldRevision++
			now := time.Now().UnixMilli()
			_, err = tx.ExecContext(ctx, `INSERT INTO provider_grant(connection_id,subject_kind,subject_id,allowed,revision,updated_at) VALUES(?,?,?,?,?,?)
ON CONFLICT(connection_id,subject_kind,subject_id) DO UPDATE SET allowed=excluded.allowed,revision=excluded.revision,updated_at=excluded.updated_at`, connectionID, kind, id, allowed, oldRevision, now)
			if err != nil {
				return nil, err
			}
			if _, err = tx.ExecContext(ctx, `UPDATE provider_connection SET revision=revision+1,updated_at=? WHERE id=?`, now, connectionID); err != nil {
				return nil, err
			}
			value.Revision++
		}
		return &ProviderGrant{connectionID, kind, id, allowed, oldRevision, value.Revision}, nil
	})
}

func providerAllowed(ctx context.Context, reader schemaReader, actor uint, libraryID, connectionID string) error {
	value, err := providerConnection(ctx, reader, connectionID)
	if err != nil {
		return err
	}
	if value.Status != "configured" {
		return ErrNotFound
	}
	if value.OwnerKind == "user" {
		if value.OwnerUserID == actor {
			return nil
		}
		return ErrNotFound
	}
	var allowed int
	err = reader.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM provider_grant WHERE connection_id=? AND allowed=1 AND
((subject_kind='user' AND subject_id=?) OR (subject_kind='library' AND subject_id=?)))`, connectionID, strconv.FormatUint(uint64(actor), 10), libraryID).Scan(&allowed)
	if err != nil {
		return err
	}
	if allowed == 0 {
		return ErrNotFound
	}
	return nil
}

func readLibraryMetadataPolicy(ctx context.Context, reader schemaReader, actor uint, libraryID string) (*LibraryMetadataPolicy, error) {
	if actor == 0 {
		return nil, ErrForbidden
	}
	value := &LibraryMetadataPolicy{LibraryID: libraryID, Mode: MetadataLocalOnly}
	var mode, connection, language, region sql.NullString
	var editor, revision sql.NullInt64
	err := reader.QueryRowContext(ctx, `SELECT p.mode,p.connection_id,p.language,p.region,p.allow_editor_jobs,p.revision
FROM library l LEFT JOIN library_member m ON m.library_id=l.id AND m.user_id=? AND m.revoked_at IS NULL
LEFT JOIN library_metadata_policy p ON p.library_id=l.id
WHERE l.id=? AND l.archived_at IS NULL AND (l.owner_user_id=? OR m.role='editor')`, actor, libraryID, actor).
		Scan(&mode, &connection, &language, &region, &editor, &revision)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if mode.Valid {
		value.Mode = mode.String
	}
	value.ConnectionID, value.Language, value.Region = connection.String, language.String, region.String
	value.AllowEditorJobs, value.Revision = editor.Int64 != 0, revision.Int64
	return value, nil
}

func (store *Store) GetLibraryMetadataPolicy(ctx context.Context, actor uint, libraryID string) (*LibraryMetadataPolicy, error) {
	return readLibraryMetadataPolicy(ctx, store.db, actor, libraryID)
}

// MetadataPolicyRevision lets authorized catalog snapshots bind the effects
// of precedence changes without exposing a connection or secret to viewers.
func (store *Store) MetadataPolicyRevision(ctx context.Context, libraryID string) (int64, error) {
	var revision int64
	err := store.db.QueryRowContext(ctx, `SELECT COALESCE((SELECT revision FROM library_metadata_policy WHERE library_id=?), 0)`, libraryID).Scan(&revision)
	return revision, err
}

func (store *Store) SetLibraryMetadataPolicy(ctx context.Context, actor uint, libraryID, operationID string, expectedRevision int64,
	policy LibraryMetadataPolicy) (*LibraryMetadataPolicy, error) {
	if actor == 0 || expectedRevision < 0 || (policy.Mode != MetadataLocalOnly && !policy.OnlineEnabled()) ||
		(policy.OnlineEnabled() && policy.ConnectionID == "") ||
		(policy.ConnectionID != "" && !providerIDPattern.MatchString(policy.ConnectionID)) ||
		(policy.Language != "" && !providerLanguagePattern.MatchString(policy.Language)) ||
		(policy.Region != "" && !providerRegionPattern.MatchString(policy.Region)) {
		return nil, ErrInvalid
	}
	policy.LibraryID, policy.Revision = libraryID, 0
	input := struct {
		LibraryID string
		Expected  int64
		Policy    LibraryMetadataPolicy
	}{libraryID, expectedRevision, policy}
	return providerOperation(ctx, store, actor, "provider.policy.set", operationID, input, func(tx *sql.Tx) error {
		library, err := activeCatalogLibrary(ctx, tx, actor, libraryID)
		if err != nil {
			return err
		}
		if library.Role != RoleOwner {
			return ErrForbidden
		}
		if policy.ConnectionID != "" {
			return providerAllowed(ctx, tx, actor, libraryID, policy.ConnectionID)
		}
		return nil
	}, func(tx *sql.Tx) (*LibraryMetadataPolicy, error) {
		current, err := readLibraryMetadataPolicy(ctx, tx, actor, libraryID)
		if err != nil {
			return nil, err
		}
		if current.Revision != expectedRevision {
			return nil, ErrRevisionConflict
		}
		policy.Revision = current.Revision + 1
		var connection any
		if policy.ConnectionID != "" {
			connection = policy.ConnectionID
		}
		_, err = tx.ExecContext(ctx, `INSERT INTO library_metadata_policy(library_id,mode,connection_id,language,region,allow_editor_jobs,revision,updated_at) VALUES(?,?,?,?,?,?,?,?)
ON CONFLICT(library_id) DO UPDATE SET mode=excluded.mode,connection_id=excluded.connection_id,language=excluded.language,region=excluded.region,allow_editor_jobs=excluded.allow_editor_jobs,revision=excluded.revision,updated_at=excluded.updated_at`,
			libraryID, policy.Mode, connection, policy.Language, policy.Region, policy.AllowEditorJobs, policy.Revision, time.Now().UnixMilli())
		if err != nil {
			return nil, err
		}
		return &policy, nil
	})
}

type ProviderUseAuthority struct {
	ConnectionID       string
	ConnectionRevision int64
	PolicyRevision     int64
}

// CheckProviderUse is a read-only fence for a future explicit job. It does not
// resolve credentials, fetch metadata, or grant access to any media source.
func (store *Store) CheckProviderUse(ctx context.Context, actor uint, libraryID, connectionID string) (*ProviderUseAuthority, error) {
	value, _, err := store.GetProviderCredentialForUse(ctx, actor, libraryID, connectionID)
	return value, err
}

// GetProviderCredentialForUse atomically binds a sealed envelope to the
// current policy, connection revision and grant. It is an internal worker API;
// ciphertext must never be serialized into client responses or logs.
func (store *Store) GetProviderCredentialForUse(ctx context.Context, actor uint, libraryID, connectionID string) (*ProviderUseAuthority, []byte, error) {
	return readProviderCredentialForUse(ctx, store.db, actor, libraryID, connectionID)
}

func readProviderCredentialForUse(ctx context.Context, reader schemaReader, actor uint, libraryID, connectionID string) (*ProviderUseAuthority, []byte, error) {
	if actor == 0 || !providerIDPattern.MatchString(connectionID) {
		return nil, nil, ErrInvalid
	}
	var value ProviderUseAuthority
	var ciphertext []byte
	err := reader.QueryRowContext(ctx, `SELECT c.id,c.revision,p.revision,secret.ciphertext FROM library_metadata_policy p
JOIN library l ON l.id=p.library_id AND l.archived_at IS NULL
LEFT JOIN library_member m ON m.library_id=l.id AND m.user_id=? AND m.revoked_at IS NULL
JOIN provider_connection c ON c.id=? AND c.status='configured'
JOIN provider_credential secret ON secret.credential_ref=c.credential_ref
WHERE l.id=? AND p.mode IN ('local_first_online','online_first') AND
((l.owner_user_id=? AND p.connection_id=c.id) OR (m.role='editor' AND p.allow_editor_jobs=1 AND
(p.connection_id=c.id OR (c.owner_kind='user' AND c.owner_user_id=?)))) AND
((c.owner_kind='user' AND c.owner_user_id=?) OR (c.owner_kind='instance' AND EXISTS(
SELECT 1 FROM provider_grant g WHERE g.connection_id=c.id AND g.allowed=1 AND
((g.subject_kind='user' AND g.subject_id=?) OR (g.subject_kind='library' AND g.subject_id=l.id)))))`,
		actor, connectionID, libraryID, actor, actor, actor, strconv.FormatUint(uint64(actor), 10)).Scan(&value.ConnectionID, &value.ConnectionRevision, &value.PolicyRevision, &ciphertext)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil, ErrNotFound
	}
	if err != nil {
		return nil, nil, err
	}
	return &value, ciphertext, nil
}
