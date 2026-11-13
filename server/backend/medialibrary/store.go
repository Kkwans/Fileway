// Package medialibrary owns the media catalog independently of the existing
// account and file databases. Opening a catalog does not grant access to media;
// callers must authorize both the source owner and the viewing library member.
package medialibrary

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"

	_ "modernc.org/sqlite"
)

const (
	SchemaVersion  = 1
	applicationID  = 0x46574d43 // FWMC; do not migrate an unrelated SQLite database.
	maxConnections = 4
	busyTimeoutMS  = 5000
)

var (
	ErrFutureSchema    = errors.New("media catalog was created by a newer server")
	ErrForeignDatabase = errors.New("database is not a Fileway media catalog")
)

// Store is an instance-local catalog. Its file and WAL must be on reliable local
// storage, never an SMB/NFS share. Media files themselves may be remote.
type Store struct {
	db *sql.DB
}

// Open opens or initializes a catalog at a filesystem path. The parent directory
// must already exist. URI options and in-memory databases are intentionally not
// accepted: the catalog, checkpoints and outbox must have durable storage.
// Existing databases are inspected read-only before any persistent PRAGMA or
// migration, so unsupported versions can be left for a compatible server.
func Open(ctx context.Context, path string) (*Store, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if strings.TrimSpace(path) == "" || path == ":memory:" || strings.HasPrefix(path, "file:") {
		return nil, errors.New("media catalog requires a filesystem path")
	}
	absolute, err := filepath.Abs(path)
	if err != nil {
		return nil, fmt.Errorf("resolve media catalog path: %w", err)
	}
	if strings.HasPrefix(absolute, `\\`) || strings.HasPrefix(absolute, "//") {
		return nil, errors.New("media catalog requires local storage")
	}
	if err := inspectExisting(ctx, absolute); err != nil {
		return nil, err
	}
	// Set restrictive permissions at creation, before SQLite opens the file.
	file, err := os.OpenFile(absolute, os.O_CREATE|os.O_EXCL|os.O_RDWR, 0600)
	if err == nil {
		if err := file.Close(); err != nil {
			return nil, fmt.Errorf("close new media catalog: %w", err)
		}
	} else if !errors.Is(err, os.ErrExist) {
		return nil, fmt.Errorf("create media catalog: %w", err)
	}
	db, err := sql.Open("sqlite", databaseURL(absolute, "rw"))
	if err != nil {
		return nil, fmt.Errorf("open media catalog: %w", err)
	}
	db.SetMaxOpenConns(maxConnections)
	db.SetMaxIdleConns(maxConnections)
	store := &Store{db: db}
	if err := store.migrate(ctx); err != nil {
		_ = db.Close()
		return nil, err
	}
	var journalMode string
	if err := db.QueryRowContext(ctx, "PRAGMA journal_mode = WAL").Scan(&journalMode); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("enable media catalog WAL: %w", err)
	}
	if !strings.EqualFold(journalMode, "wal") {
		_ = db.Close()
		return nil, fmt.Errorf("media catalog WAL unavailable: %s", journalMode)
	}
	return store, nil
}

func (store *Store) Close() error { return store.db.Close() }

// WithTx commits a domain change and its durable outbox/checkpoint together.
// The callback must use this transaction, not re-enter Store, and must not send
// external notifications or perform network I/O. A callback error or panic rolls
// back all changes. Consumers publish committed outbox entries separately.
func (store *Store) WithTx(ctx context.Context, fn func(*sql.Tx) error) error {
	tx, err := store.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	if err := fn(tx); err != nil {
		return err
	}
	return tx.Commit()
}

func databaseURL(path, mode string) string {
	path = filepath.ToSlash(path)
	if !strings.HasPrefix(path, "/") {
		path = "/" + path // Absolute Windows drive paths also use file:/// URLs.
	}
	address := url.URL{Scheme: "file", Path: path}
	query := url.Values{"mode": {mode}, "_txlock": {"immediate"}}
	query.Add("_pragma", fmt.Sprintf("busy_timeout(%d)", busyTimeoutMS))
	query.Add("_pragma", "foreign_keys(1)")
	query.Add("_pragma", "synchronous(FULL)")
	address.RawQuery = query.Encode()
	return address.String()
}

func inspectExisting(ctx context.Context, path string) error {
	info, err := os.Stat(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("inspect media catalog: %w", err)
	}
	if !info.Mode().IsRegular() {
		return errors.New("media catalog path is not a regular file")
	}
	db, err := sql.Open("sqlite", databaseURL(path, "ro"))
	if err != nil {
		return fmt.Errorf("inspect media catalog: %w", err)
	}
	defer func() { _ = db.Close() }()
	db.SetMaxOpenConns(1)
	_, err = inspectSchema(ctx, db)
	return err
}

type schemaReader interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}

func inspectSchema(ctx context.Context, reader schemaReader) (int, error) {
	var version, identity int
	if err := reader.QueryRowContext(ctx, "PRAGMA user_version").Scan(&version); err != nil {
		return 0, fmt.Errorf("read media catalog version: %w", err)
	}
	if version > SchemaVersion {
		return 0, fmt.Errorf("%w: found %d, supported %d", ErrFutureSchema, version, SchemaVersion)
	}
	if err := reader.QueryRowContext(ctx, "PRAGMA application_id").Scan(&identity); err != nil {
		return 0, fmt.Errorf("read media catalog identity: %w", err)
	}
	if version < 0 || (identity != 0 && identity != applicationID) || (version != 0 && identity != applicationID) {
		return 0, ErrForeignDatabase
	}
	if version == 0 {
		var objects int
		if err := reader.QueryRowContext(ctx, "SELECT count(*) FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%'").Scan(&objects); err != nil {
			return 0, fmt.Errorf("inspect unversioned media catalog: %w", err)
		}
		if objects != 0 {
			return 0, ErrForeignDatabase
		}
	}
	return version, nil
}

func (store *Store) migrate(ctx context.Context) error {
	// BEGIN IMMEDIATE serializes concurrent startup/migration attempts. Recheck
	// the version under the write lock; the read-only preflight is not a lock.
	return store.WithTx(ctx, func(tx *sql.Tx) error {
		version, err := inspectSchema(ctx, tx)
		if err != nil {
			return err
		}
		if version == SchemaVersion {
			return nil
		}
		if _, err := tx.ExecContext(ctx, schemaV1); err != nil {
			return fmt.Errorf("initialize media catalog schema: %w", err)
		}
		if _, err := tx.ExecContext(ctx, fmt.Sprintf("PRAGMA application_id = %d; PRAGMA user_version = %d", applicationID, SchemaVersion)); err != nil {
			return fmt.Errorf("record media catalog schema: %w", err)
		}
		return nil
	})
}
