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
	LibraryID       string
	Source          LocalSource
	RootLocator     []byte
	Revision        int64
	LibraryRevision int64
}

// SourceBindingRef is a catalog consistency token, NOT a permission token.
// Before every scan batch, the source adapter must validate the live owner's
// current scope/rules, administrator policy and safe filesystem root. Each file
// must be safely opened under that root. These opaque bytes are never interpreted
// here as a trusted native path, nor is a string-prefix check treated as security.
type SourceBindingRef struct {
	LibraryID             string
	SourceID              string
	RootLocator           []byte
	BindingRevision       int64
	AuthorizationRevision int64
}

func (binding *LibrarySource) Ref() SourceBindingRef {
	return SourceBindingRef{LibraryID: binding.LibraryID, SourceID: binding.Source.ID,
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
	// A legacy or path-only key is not proof of continuity. In particular,
	// playback must not inherit progress from these identity epochs implicitly.
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
	if strings.TrimSpace(configurationRef) == "" || len(root) == 0 {
		return nil, fmt.Errorf("%w: source configuration reference and root are required", ErrInvalid)
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
		id, err := randomID("src_")
		if err != nil {
			return err
		}
		source := LocalSource{ID: id, OwnerUserID: actor, ConfigurationRef: configurationRef, AuthorizationRevision: 1, CreatedAt: time.Now().UnixMilli()}
		if _, err := tx.ExecContext(ctx, `INSERT INTO source(id, owner_user_id, kind, configuration_ref, created_at) VALUES (?, ?, 'local', ?, ?)`, id, actor, configurationRef, source.CreatedAt); err != nil {
			return err
		}
		binding, err = bindLocalSource(ctx, tx, actor, library, source, root)
		return err
	})
	if err != nil {
		return nil, err
	}
	return binding, nil
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
	err := tx.QueryRowContext(ctx, "SELECT revision FROM library_source WHERE library_id = ? AND source_id = ? AND root_locator = ?", library.ID, source.ID, root).Scan(&binding.Revision)
	if err == nil {
		return binding, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return nil, err
	}
	if _, err := tx.ExecContext(ctx, "INSERT INTO library_source(library_id, source_id, root_locator) VALUES (?, ?, ?)", library.ID, source.ID, root); err != nil {
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
	binding := &LibrarySource{LibraryID: libraryID}
	err := store.db.QueryRowContext(ctx, `SELECT s.id, s.owner_user_id, s.configuration_ref, s.authorization_revision, s.created_at, ls.root_locator, ls.revision, l.revision
FROM library_source ls JOIN library l ON l.id = ls.library_id JOIN source s ON s.id = ls.source_id
WHERE ls.library_id = ? AND ls.source_id = ? AND ls.root_locator = ? AND l.owner_user_id = ? AND s.owner_user_id = ? AND s.kind = 'local'`, libraryID, sourceID, root, actor, actor).Scan(&binding.Source.ID, &binding.Source.OwnerUserID, &binding.Source.ConfigurationRef, &binding.Source.AuthorizationRevision, &binding.Source.CreatedAt, &binding.RootLocator, &binding.Revision, &binding.LibraryRevision)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return binding, nil
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
	var revision int64
	if err := reader.QueryRowContext(ctx, "SELECT revision FROM library_source WHERE library_id = ? AND source_id = ? AND root_locator = ?", binding.LibraryID, binding.SourceID, binding.RootLocator).Scan(&revision); errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	} else if err != nil {
		return nil, err
	}
	if binding.BindingRevision != revision || binding.AuthorizationRevision != source.AuthorizationRevision {
		return nil, ErrRevisionConflict
	}
	return &LibrarySource{LibraryID: library.ID, Source: *source, RootLocator: bytes.Clone(binding.RootLocator), Revision: revision, LibraryRevision: library.Revision}, nil
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
		identityChanged := identityKind != "" && file.IdentityKind != identityKind
		if identityChanged {
			file.IdentityKind = identityKind
		}
		if identityChanged || forceNewEpoch {
			file.IdentityEpoch++
		}
		changed = !bytes.Equal(file.Locator, input.Locator) || file.SourceVersion != input.SourceVersion || !sameOptionalSize(file.SizeBytes, input.SizeBytes) || file.MissingSince != nil || identityChanged || forceNewEpoch
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
	err = tx.QueryRowContext(ctx, "SELECT locator, missing_since FROM library_file WHERE library_id = ? AND source_id = ? AND root_locator = ? AND source_file_id = ?", binding.LibraryID, binding.SourceID, binding.RootLocator, file.ID).Scan(&oldLocator, &oldMissing)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return nil, err
	}
	associationChanged := errors.Is(err, sql.ErrNoRows) || !bytes.Equal(oldLocator, input.Locator) || oldMissing != nil
	if _, err := tx.ExecContext(ctx, `INSERT INTO library_file(library_id, source_file_id, source_id, root_locator, locator, observed_at) VALUES (?, ?, ?, ?, ?, ?)
ON CONFLICT(library_id, source_id, root_locator, source_file_id) DO UPDATE SET locator = excluded.locator, observed_at = excluded.observed_at, missing_since = NULL`, binding.LibraryID, file.ID, binding.SourceID, binding.RootLocator, input.Locator, now); err != nil {
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
