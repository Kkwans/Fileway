package medialibrary

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"
)

// LocalSource stores a reference to separately authorized connection settings,
// never credentials or a claim that the owner's filesystem permissions passed.
type LocalSource struct {
	ID                    string
	OwnerUserID           uint
	ConfigurationRef      string
	AuthorizationRevision int64
	CreatedAt             int64
}

type LibrarySource struct {
	ID              string
	LibraryID       string
	Source          LocalSource
	RootLocator     []byte
	Revision        int64
	LibraryRevision int64
	ProofEpoch      int64
	Proof           *BindingProof `json:"-"`
}

// BindingProof is private source-owner evidence, not an authorization grant or
// playback identity. MappingDigest derives from the live owner/root mapping;
// RootIdentity is obtained only from the safely opened bound root.
type BindingProof struct {
	Version          int
	MappingDigest    []byte `json:"-"`
	RootIdentityKind string
	RootIdentity     []byte `json:"-"`
	AttestedAt       int64
}

func validateBindingProof(proof BindingProof) error {
	if proof.Version != 1 || len(proof.MappingDigest) != 32 || proof.RootIdentityKind != "native" || len(proof.RootIdentity) == 0 || len(proof.RootIdentity) > 1024 || proof.AttestedAt < 0 {
		return fmt.Errorf("%w: binding proof", ErrInvalid)
	}
	return nil
}

type LibrarySourcePage struct {
	Items      []LibrarySource
	NextCursor string
}

// SourceBindingRef is a catalog consistency token, NOT a permission token.
// Before every scan batch, the source adapter must validate the live owner's
// current scope/rules, administrator policy and safe filesystem root. Each file
// must be safely opened under that root. These opaque bytes are never interpreted
// here as a trusted native path, nor is a string-prefix check treated as security.
type SourceBindingRef struct {
	BindingID             string
	ProofEpoch            int64
	LibraryID             string
	SourceID              string
	RootLocator           []byte
	BindingRevision       int64
	AuthorizationRevision int64
}

func (binding *LibrarySource) Ref() SourceBindingRef {
	return SourceBindingRef{BindingID: binding.ID, ProofEpoch: binding.ProofEpoch, LibraryID: binding.LibraryID, SourceID: binding.Source.ID,
		RootLocator: bytes.Clone(binding.RootLocator), BindingRevision: binding.Revision,
		AuthorizationRevision: binding.Source.AuthorizationRevision}
}

type SourceFile struct {
	ID            string
	SourceID      string
	ObjectKey     []byte
	Locator       []byte
	SourceVersion string
	SizeBytes     *int64
	Revision      int64
	ObservedAt    int64
	MissingSince  *int64
	// Native file IDs are evidence, not an absolute proof of continuity: file
	// systems can reuse them. Neither a native ID nor v1:size:mtime alone grants
	// automatic recovery of old progress. Legacy and path-only IDs are weaker.
	IdentityKind  string
	IdentityEpoch int64
}

// FileObservation keeps stable object identity separate from a replaceable
// locator and a provider/source version. ExpectedRevision is zero only for a
// new object; a stale observation must not overwrite a newer scan result.
type FileObservation struct {
	ObjectKey        []byte
	Locator          []byte
	SourceVersion    string
	SizeBytes        *int64
	ExpectedRevision int64
}

// CreateLocalSource registers an owner connection and its first library root in
// one transaction. It must be called only AFTER the outer source authority has
// authorized this root for the actor. No media is read or probed by this method.
func (store *Store) CreateLocalSource(ctx context.Context, actor uint, libraryID string, expectedRevision int64, configurationRef string, root []byte) (*LibrarySource, error) {
	var binding *LibrarySource
	err := store.WithTx(ctx, func(tx *sql.Tx) error {
		var err error
		binding, err = createLocalSourceTx(ctx, tx, actor, libraryID, expectedRevision, configurationRef, root)
		return err
	})
	if err != nil {
		return nil, err
	}
	return binding, nil
}

func createLocalSourceTx(ctx context.Context, tx *sql.Tx, actor uint, libraryID string, expectedRevision int64, configurationRef string, root []byte) (*LibrarySource, error) {
	if strings.TrimSpace(configurationRef) == "" || len(root) == 0 {
		return nil, fmt.Errorf("%w: source configuration reference and root are required", ErrInvalid)
	}
	library, err := ownedLibrary(ctx, tx, actor, libraryID, expectedRevision)
	if err != nil {
		return nil, err
	}
	if library.ArchivedAt != nil {
		return nil, ErrArchived
	}
	id, err := randomID("src_")
	if err != nil {
		return nil, err
	}
	source := LocalSource{ID: id, OwnerUserID: actor, ConfigurationRef: configurationRef, AuthorizationRevision: 1, CreatedAt: time.Now().UnixMilli()}
	if _, err := tx.ExecContext(ctx, `INSERT INTO source(id, owner_user_id, kind, configuration_ref, created_at) VALUES (?, ?, 'local', ?, ?)`, id, actor, configurationRef, source.CreatedAt); err != nil {
		return nil, err
	}
	return bindLocalSource(ctx, tx, actor, library, source, root)
}

// BindLocalSource reuses only a connection owned by the library owner. A grant
// to edit metadata cannot bind an editor's or any other account's filesystem.
func (store *Store) BindLocalSource(ctx context.Context, actor uint, libraryID string, expectedRevision int64, sourceID string, root []byte) (*LibrarySource, error) {
	if len(root) == 0 {
		return nil, fmt.Errorf("%w: source root is required", ErrInvalid)
	}
	var binding *LibrarySource
	err := store.WithTx(ctx, func(tx *sql.Tx) error {
		library, err := ownedLibrary(ctx, tx, actor, libraryID, expectedRevision)
		if err != nil {
			return err
		}
		if library.ArchivedAt != nil {
			return ErrArchived
		}
		source, err := ownedLocalSource(ctx, tx, actor, sourceID)
		if err != nil {
			return err
		}
		binding, err = bindLocalSource(ctx, tx, actor, library, *source, root)
		return err
	})
	if err != nil {
		return nil, err
	}
	return binding, nil
}

func bindLocalSource(ctx context.Context, tx *sql.Tx, actor uint, library *Library, source LocalSource, root []byte) (*LibrarySource, error) {
	if source.OwnerUserID != library.OwnerUserID {
		return nil, ErrForbidden
	}
	binding := &LibrarySource{LibraryID: library.ID, Source: source, RootLocator: bytes.Clone(root), LibraryRevision: library.Revision}
	existing, err := scanLibrarySource(tx.QueryRowContext(ctx, bindingSelection+" WHERE ls.library_id=? AND ls.source_id=? AND ls.root_locator=?", library.ID, source.ID, root))
	if err == nil {
		return existing, nil
	}
	if !errors.Is(err, ErrNotFound) {
		return nil, err
	}
	binding.ID, err = randomID("bind_")
	if err != nil {
		return nil, err
	}
	if _, err := tx.ExecContext(ctx, "INSERT INTO library_source(library_id, source_id, root_locator, binding_id) VALUES (?, ?, ?, ?)", library.ID, source.ID, root, binding.ID); err != nil {
		return nil, err
	}
	if err := updateLibrary(ctx, tx, library); err != nil {
		return nil, err
	}
	binding.Revision, binding.LibraryRevision = 1, library.Revision
	if err := publishCatalogChange(ctx, tx, actor, library.ID, "library.source.bound", catalogChange{SourceID: source.ID, Revision: library.Revision}); err != nil {
		return nil, err
	}
	return binding, nil
}

func ownedLocalSource(ctx context.Context, reader schemaReader, actor uint, id string) (*LocalSource, error) {
	if actor == 0 {
		return nil, ErrForbidden
	}
	source := &LocalSource{}
	err := reader.QueryRowContext(ctx, `SELECT id, owner_user_id, configuration_ref, authorization_revision, created_at FROM source WHERE id = ? AND owner_user_id = ? AND kind = 'local'`, id, actor).Scan(&source.ID, &source.OwnerUserID, &source.ConfigurationRef, &source.AuthorizationRevision, &source.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return source, err
}

// GetLibrarySource is owner-only: shared viewers/editors must not receive
// configuration references or source-root locators through catalog APIs.
func (store *Store) GetLibrarySource(ctx context.Context, actor uint, libraryID, sourceID string, root []byte) (*LibrarySource, error) {
	if actor == 0 {
		return nil, ErrForbidden
	}
	return scanLibrarySource(store.db.QueryRowContext(ctx, bindingSelection+` WHERE ls.library_id = ? AND ls.source_id = ? AND ls.root_locator = ? AND l.owner_user_id = ? AND s.owner_user_id = ? AND s.kind = 'local'`, libraryID, sourceID, root, actor, actor))
}

const bindingSelection = `SELECT ls.binding_id, ls.library_id, s.id, s.owner_user_id, s.configuration_ref, s.authorization_revision, s.created_at, ls.root_locator, ls.revision, l.revision,ls.proof_epoch,ls.proof_version,ls.mapping_digest,ls.root_identity_kind,ls.root_identity,ls.attested_at
FROM library_source ls JOIN library l ON l.id = ls.library_id JOIN source s ON s.id = ls.source_id`

func scanLibrarySource(row rowScanner) (*LibrarySource, error) {
	binding := &LibrarySource{}
	var proof BindingProof
	var attested sql.NullInt64
	err := row.Scan(&binding.ID, &binding.LibraryID, &binding.Source.ID, &binding.Source.OwnerUserID, &binding.Source.ConfigurationRef, &binding.Source.AuthorizationRevision, &binding.Source.CreatedAt, &binding.RootLocator, &binding.Revision, &binding.LibraryRevision, &binding.ProofEpoch, &proof.Version, &proof.MappingDigest, &proof.RootIdentityKind, &proof.RootIdentity, &attested)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if proof.Version != 0 {
		if !attested.Valid {
			return nil, fmt.Errorf("%w: incomplete binding proof", ErrInvalid)
		}
		proof.AttestedAt = attested.Int64
		if err := validateBindingProof(proof); err != nil {
			return nil, err
		}
		binding.Proof = &proof
	}
	return binding, nil
}

// GetLibrarySourceByID reloads a binding only for its owning account. The
// opaque ID never grants authority to open the root or exposes it to viewers.
func (store *Store) GetLibrarySourceByID(ctx context.Context, actor uint, bindingID string) (*LibrarySource, error) {
	if actor == 0 {
		return nil, ErrForbidden
	}
	return scanLibrarySource(store.db.QueryRowContext(ctx, bindingSelection+" WHERE ls.binding_id = ? AND l.owner_user_id = ? AND s.owner_user_id = ? AND s.kind = 'local'", bindingID, actor, actor))
}

// ListLibrarySources is owner-only; callers must redact root/configuration
// references from client DTOs unless a separate product contract permits them.
func (store *Store) ListLibrarySources(ctx context.Context, actor uint, libraryID, afterID string, limit int) (*LibrarySourcePage, error) {
	if actor == 0 {
		return nil, ErrForbidden
	}
	if limit == 0 {
		limit = 50
	}
	if limit < 1 || limit > 100 {
		return nil, fmt.Errorf("%w: source page limit", ErrInvalid)
	}
	var owner uint
	err := store.db.QueryRowContext(ctx, "SELECT owner_user_id FROM library WHERE id = ? AND owner_user_id = ?", libraryID, actor).Scan(&owner)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	rows, err := store.db.QueryContext(ctx, bindingSelection+" WHERE ls.library_id = ? AND l.owner_user_id = ? AND s.owner_user_id = ? AND s.kind = 'local' AND ls.binding_id > ? ORDER BY ls.binding_id LIMIT ?", libraryID, actor, actor, afterID, limit+1)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	page := &LibrarySourcePage{Items: make([]LibrarySource, 0, limit)}
	for rows.Next() {
		binding, err := scanLibrarySource(rows)
		if err != nil {
			return nil, err
		}
		if len(page.Items) == limit {
			page.NextCursor = page.Items[len(page.Items)-1].ID
			break
		}
		page.Items = append(page.Items, *binding)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return page, nil
}

// GetSourceFile is an owner-only lookup for optimistic scan updates. It does not
// assert that this object belongs to any particular library or remains readable.
func (store *Store) GetSourceFile(ctx context.Context, actor uint, sourceID string, objectKey []byte) (*SourceFile, error) {
	if actor == 0 {
		return nil, ErrForbidden
	}
	return scanSourceFile(store.db.QueryRowContext(ctx, sourceFileSelection+` JOIN source s ON s.id = f.source_id WHERE f.source_id = ? AND f.object_key = ? AND s.owner_user_id = ? AND s.kind = 'local'`, sourceID, objectKey, actor))
}

const sourceFileSelection = `SELECT f.id, f.source_id, f.object_key, f.locator, f.source_version, f.size_bytes, f.revision, f.observed_at, f.missing_since, f.identity_kind, f.identity_epoch FROM source_file f`

func scanSourceFile(row rowScanner) (*SourceFile, error) {
	file := &SourceFile{}
	err := row.Scan(&file.ID, &file.SourceID, &file.ObjectKey, &file.Locator, &file.SourceVersion, &file.SizeBytes, &file.Revision, &file.ObservedAt, &file.MissingSince, &file.IdentityKind, &file.IdentityEpoch)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return file, nil
}

// ObserveSourceFile consumes an observation made by the externally authorized
// local-source adapter. The live actor must own both library and connection.
// Metadata editors cannot inject file observations or expand a library's roots.
func (store *Store) ObserveSourceFile(ctx context.Context, actor uint, binding SourceBindingRef, input FileObservation) (*SourceFile, error) {
	var file *SourceFile
	err := store.WithTx(ctx, func(tx *sql.Tx) error {
		var err error
		file, err = observeSourceFileTx(ctx, tx, actor, binding, input, "", false)
		return err
	})
	if err != nil {
		return nil, err
	}
	return file, nil
}

// validateSourceBinding checks stored catalog revisions only. The caller must
// separately revalidate the source owner's live authority and safe open rules.
func validateSourceBinding(ctx context.Context, reader schemaReader, actor uint, binding SourceBindingRef) (*LibrarySource, error) {
	library, err := activeCatalogLibrary(ctx, reader, actor, binding.LibraryID)
	if err != nil {
		return nil, err
	}
	if library.OwnerUserID != actor {
		return nil, ErrForbidden
	}
	source, err := ownedLocalSource(ctx, reader, actor, binding.SourceID)
	if err != nil {
		return nil, err
	}
	current, err := scanLibrarySource(reader.QueryRowContext(ctx, bindingSelection+" WHERE ls.library_id=? AND ls.source_id=? AND ls.root_locator=?", binding.LibraryID, binding.SourceID, binding.RootLocator))
	if err != nil {
		return nil, err
	}
	if binding.BindingID != current.ID || binding.ProofEpoch != current.ProofEpoch || binding.BindingRevision != current.Revision || binding.AuthorizationRevision != source.AuthorizationRevision {
		return nil, ErrRevisionConflict
	}
	return current, nil
}

func observeSourceFileTx(ctx context.Context, tx *sql.Tx, actor uint, binding SourceBindingRef, input FileObservation, identityKind string, forceNewEpoch bool) (*SourceFile, error) {
	if len(input.ObjectKey) == 0 || len(input.Locator) == 0 || input.SourceVersion == "" || input.ExpectedRevision < 0 || (input.SizeBytes != nil && *input.SizeBytes < 0) {
		return nil, fmt.Errorf("%w: invalid file observation", ErrInvalid)
	}
	if identityKind != "" && identityKind != "native" && identityKind != "path_unverified" {
		return nil, fmt.Errorf("%w: invalid source identity evidence", ErrInvalid)
	}
	if _, err := validateSourceBinding(ctx, tx, actor, binding); err != nil {
		return nil, err
	}
	file, err := scanSourceFile(tx.QueryRowContext(ctx, sourceFileSelection+" WHERE f.source_id = ? AND f.object_key = ?", binding.SourceID, input.ObjectKey))
	if err != nil && !errors.Is(err, ErrNotFound) {
		return nil, err
	}
	newFile := errors.Is(err, ErrNotFound)
	if (newFile && input.ExpectedRevision != 0) || (!newFile && file.Revision != input.ExpectedRevision) {
		return nil, ErrRevisionConflict
	}
	now := time.Now().UnixMilli()
	changed := newFile
	if newFile {
		id, err := randomID("file_")
		if err != nil {
			return nil, err
		}
		kind := identityKind
		if kind == "" {
			kind = "legacy_unverified"
		}
		file = &SourceFile{ID: id, SourceID: binding.SourceID, Revision: 1, IdentityKind: kind, IdentityEpoch: 1}
	} else {
		now = max(now, file.ObservedAt)
		// A file number can be recycled after an object disappears. Only reset
		// the shared identity epoch when at least one binding was known and all
		// of them were confirmed missing; a live binding may still refer to the
		// same object. A separate resolver must establish stronger evidence for
		// resuming a prior timeline even when the epoch is unchanged.
		nativeRevived := false
		if file.IdentityKind == "native" && (identityKind == "" || identityKind == "native") {
			var known, live int
			if err := tx.QueryRowContext(ctx, `SELECT count(*), COALESCE(sum(CASE WHEN missing_since IS NULL THEN 1 ELSE 0 END), 0)
FROM library_file WHERE source_file_id = ?`, file.ID).Scan(&known, &live); err != nil {
				return nil, err
			}
			nativeRevived = known > 0 && live == 0
		}
		identityChanged := identityKind != "" && file.IdentityKind != identityKind
		if identityChanged {
			file.IdentityKind = identityKind
		}
		if identityChanged || forceNewEpoch || nativeRevived {
			file.IdentityEpoch++
		}
		changed = !bytes.Equal(file.Locator, input.Locator) || file.SourceVersion != input.SourceVersion || !sameOptionalSize(file.SizeBytes, input.SizeBytes) || file.MissingSince != nil || identityChanged || forceNewEpoch || nativeRevived
		if changed {
			file.Revision++
		}
	}
	file.ObjectKey, file.Locator = bytes.Clone(input.ObjectKey), bytes.Clone(input.Locator)
	file.SourceVersion, file.ObservedAt, file.MissingSince = input.SourceVersion, now, nil
	file.SizeBytes = nil
	if input.SizeBytes != nil {
		size := *input.SizeBytes
		file.SizeBytes = &size
	}
	if newFile {
		_, err = tx.ExecContext(ctx, `INSERT INTO source_file(id, source_id, object_key, locator, source_version, size_bytes, observed_at, identity_kind, identity_epoch) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`, file.ID, file.SourceID, file.ObjectKey, file.Locator, file.SourceVersion, file.SizeBytes, now, file.IdentityKind, file.IdentityEpoch)
	} else {
		_, err = tx.ExecContext(ctx, `UPDATE source_file SET locator = ?, source_version = ?, size_bytes = ?, revision = ?, observed_at = ?, missing_since = NULL, identity_kind = ?, identity_epoch = ? WHERE id = ?`, file.Locator, file.SourceVersion, file.SizeBytes, file.Revision, now, file.IdentityKind, file.IdentityEpoch, file.ID)
	}
	if err != nil {
		return nil, err
	}
	var oldLocator []byte
	var oldMissing *int64
	var oldEpoch int64
	err = tx.QueryRowContext(ctx, "SELECT locator, missing_since,binding_epoch FROM library_file WHERE library_id = ? AND source_id = ? AND root_locator = ? AND source_file_id = ?", binding.LibraryID, binding.SourceID, binding.RootLocator, file.ID).Scan(&oldLocator, &oldMissing, &oldEpoch)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return nil, err
	}
	associationChanged := errors.Is(err, sql.ErrNoRows) || !bytes.Equal(oldLocator, input.Locator) || oldMissing != nil || oldEpoch != binding.ProofEpoch
	if _, err := tx.ExecContext(ctx, `INSERT INTO library_file(library_id, source_file_id, source_id, root_locator, locator, observed_at,binding_epoch) VALUES (?, ?, ?, ?, ?, ?,?)
ON CONFLICT(library_id, source_id, root_locator, source_file_id) DO UPDATE SET locator = excluded.locator, observed_at = excluded.observed_at, missing_since = NULL,binding_epoch=excluded.binding_epoch`, binding.LibraryID, file.ID, binding.SourceID, binding.RootLocator, input.Locator, now, binding.ProofEpoch); err != nil {
		return nil, err
	}
	if !changed && !associationChanged {
		return file, nil
	}
	libraries := []string{binding.LibraryID}
	if changed {
		rows, err := tx.QueryContext(ctx, "SELECT DISTINCT library_id FROM library_file WHERE source_file_id = ? ORDER BY library_id", file.ID)
		if err != nil {
			return nil, err
		}
		libraries = nil
		for rows.Next() {
			var id string
			if err := rows.Scan(&id); err != nil {
				_ = rows.Close()
				return nil, err
			}
			libraries = append(libraries, id)
		}
		readErr := rows.Err()
		closeErr := rows.Close()
		if readErr != nil {
			return nil, readErr
		}
		if closeErr != nil {
			return nil, closeErr
		}
	}
	for _, libraryID := range libraries {
		if err := publishCatalogChange(ctx, tx, actor, libraryID, "catalog.file.observed", catalogChange{SourceID: file.SourceID, SourceFileID: file.ID, Revision: file.Revision}); err != nil {
			return nil, err
		}
	}
	return file, nil
}

func sameOptionalSize(left, right *int64) bool {
	return (left == nil && right == nil) || (left != nil && right != nil && *left == *right)
}

func migrateBindingIDs(ctx context.Context, tx *sql.Tx) error {
	if _, err := tx.ExecContext(ctx, schemaV5BeforeIDs); err != nil {
		return err
	}
	type key struct {
		library, source string
		root            []byte
	}
	for {
		rows, err := tx.QueryContext(ctx, "SELECT library_id, source_id, root_locator FROM library_source WHERE binding_id IS NULL ORDER BY library_id, source_id, root_locator LIMIT 100")
		if err != nil {
			return err
		}
		keys := make([]key, 0, 100)
		for rows.Next() {
			var item key
			if err := rows.Scan(&item.library, &item.source, &item.root); err != nil {
				_ = rows.Close()
				return err
			}
			keys = append(keys, item)
		}
		if err := rows.Err(); err != nil {
			_ = rows.Close()
			return err
		}
		if err := rows.Close(); err != nil {
			return err
		}
		for _, item := range keys {
			id, err := randomID("bind_")
			if err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, "UPDATE library_source SET binding_id = ? WHERE library_id = ? AND source_id = ? AND root_locator = ? AND binding_id IS NULL", id, item.library, item.source, item.root); err != nil {
				return err
			}
		}
		if len(keys) < 100 {
			break
		}
	}
	_, err := tx.ExecContext(ctx, schemaV5AfterIDs)
	return err
}
