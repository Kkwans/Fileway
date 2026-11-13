package medialibrary

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

var (
	ErrNotFound         = errors.New("media library not found")
	ErrForbidden        = errors.New("media library action is not permitted")
	ErrInvalid          = errors.New("invalid media library argument")
	ErrRevisionConflict = errors.New("media library revision changed")
	ErrArchived         = errors.New("media library is archived")
)

type MemberRole string

const (
	RoleOwner  MemberRole = "owner"
	RoleViewer MemberRole = "viewer"
	RoleEditor MemberRole = "editor"
)

// Library is a caller-visible catalog. Role is the caller's catalog role, not a
// grant to read source bytes. Only the owner can manage the library or members;
// an editor's future metadata-edit permission never grants sharing permission.
type Library struct {
	ID          string
	OwnerUserID uint
	Name        string
	Revision    int64
	CreatedAt   int64
	UpdatedAt   int64
	ArchivedAt  *int64
	Role        MemberRole
}

type MemberGrant struct {
	LibraryID       string
	UserID          uint
	Role            MemberRole
	Revision        int64
	LibraryRevision int64
	CreatedAt       int64
	RevokedAt       *int64
}

// LibraryListOptions uses stable ID keyset pagination. Archived selects only
// the caller's own archived libraries; archived grants never expose a library.
type LibraryListOptions struct {
	Limit    int
	AfterID  string
	Archived bool
}

type LibraryPage struct {
	Items      []Library
	NextCursor string
}

// Actor/recipient IDs must come from live authenticated account resolution, not
// request-body ownership claims. This package has no authority over the account
// database or administrator sharing policy. Library membership alone is never
// sufficient authority for source enumeration, playback, download or writing.
func (store *Store) CreateLibrary(ctx context.Context, actor uint, name string) (*Library, error) {
	if actor == 0 {
		return nil, ErrForbidden
	}
	name, err := libraryName(name)
	if err != nil {
		return nil, err
	}
	id, err := randomID("lib_")
	if err != nil {
		return nil, err
	}
	now := time.Now().UnixMilli()
	library := &Library{ID: id, OwnerUserID: actor, Name: name, Revision: 1, CreatedAt: now, UpdatedAt: now, Role: RoleOwner}
	err = store.WithTx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `INSERT INTO library(id, owner_user_id, name, created_at, updated_at) VALUES (?, ?, ?, ?, ?)`, id, actor, name, now, now); err != nil {
			return err
		}
		return publishLibraryChange(ctx, tx, library, actor, "library.created", nil)
	})
	if err != nil {
		return nil, err
	}
	return library, nil
}

const librarySelection = `SELECT l.id, l.owner_user_id, l.name, l.revision, l.created_at, l.updated_at, l.archived_at,
CASE WHEN l.owner_user_id = ? THEN 'owner' ELSE m.role END
FROM library l LEFT JOIN library_member m ON m.library_id = l.id AND m.user_id = ? AND m.revoked_at IS NULL`

const libraryVisibility = `(l.owner_user_id = ? OR (l.archived_at IS NULL AND m.user_id IS NOT NULL))`

// GetLibrary hides private, revoked and archived-shared libraries as not found.
// The owner can still retrieve an archived library in order to restore it.
func (store *Store) GetLibrary(ctx context.Context, actor uint, id string) (*Library, error) {
	if actor == 0 {
		return nil, ErrForbidden
	}
	return visibleLibrary(ctx, store.db, actor, id)
}

func (store *Store) ListLibraries(ctx context.Context, actor uint, options LibraryListOptions) (*LibraryPage, error) {
	if actor == 0 {
		return nil, ErrForbidden
	}
	if options.Limit == 0 {
		options.Limit = 50
	}
	if options.Limit < 1 || options.Limit > 100 {
		return nil, fmt.Errorf("%w: limit must be between 1 and 100", ErrInvalid)
	}
	archiveFilter := "l.archived_at IS NULL"
	if options.Archived {
		archiveFilter = "l.archived_at IS NOT NULL"
	}
	// Direct read query, not WithTx/BEGIN IMMEDIATE: browsing must not acquire
	// SQLite's writer lock while a scan or another catalog update is running.
	rows, err := store.db.QueryContext(ctx, librarySelection+" WHERE "+libraryVisibility+" AND "+archiveFilter+" AND l.id > ? ORDER BY l.id LIMIT ?", actor, actor, actor, options.AfterID, options.Limit+1)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	page := &LibraryPage{Items: make([]Library, 0, options.Limit)}
	for rows.Next() {
		library, err := scanLibrary(rows)
		if err != nil {
			return nil, err
		}
		if len(page.Items) == options.Limit {
			page.NextCursor = page.Items[len(page.Items)-1].ID
			break
		}
		page.Items = append(page.Items, *library)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return page, nil
}

func (store *Store) UpdateLibrary(ctx context.Context, actor uint, id, name string, expectedRevision int64) (*Library, error) {
	name, err := libraryName(name)
	if err != nil {
		return nil, err
	}
	return store.changeLibrary(ctx, actor, id, expectedRevision, "library.updated", func(library *Library) (bool, error) {
		if library.ArchivedAt != nil {
			return false, ErrArchived
		}
		if library.Name == name {
			return false, nil
		}
		library.Name = name
		return true, nil
	})
}

func (store *Store) ArchiveLibrary(ctx context.Context, actor uint, id string, expectedRevision int64) (*Library, error) {
	return store.changeLibrary(ctx, actor, id, expectedRevision, "library.archived", func(library *Library) (bool, error) {
		if library.ArchivedAt != nil {
			return false, nil
		}
		now := max(time.Now().UnixMilli(), library.UpdatedAt)
		library.ArchivedAt = &now
		return true, nil
	})
}

func (store *Store) RestoreLibrary(ctx context.Context, actor uint, id string, expectedRevision int64) (*Library, error) {
	return store.changeLibrary(ctx, actor, id, expectedRevision, "library.restored", func(library *Library) (bool, error) {
		if library.ArchivedAt == nil {
			return false, nil
		}
		library.ArchivedAt = nil
		return true, nil
	})
}

func (store *Store) changeLibrary(ctx context.Context, actor uint, id string, expectedRevision int64, event string, change func(*Library) (bool, error)) (*Library, error) {
	var library *Library
	err := store.WithTx(ctx, func(tx *sql.Tx) error {
		var err error
		library, err = ownedLibrary(ctx, tx, actor, id, expectedRevision)
		if err != nil {
			return err
		}
		changed, err := change(library)
		if err != nil || !changed {
			return err
		}
		if err := updateLibrary(ctx, tx, library); err != nil {
			return err
		}
		return publishLibraryChange(ctx, tx, library, actor, event, nil)
	})
	if err != nil {
		return nil, err
	}
	return library, nil
}

func (store *Store) GrantMember(ctx context.Context, actor uint, id string, expectedRevision int64, recipient uint, role MemberRole) (*MemberGrant, error) {
	if role != RoleViewer && role != RoleEditor {
		return nil, fmt.Errorf("%w: member role", ErrInvalid)
	}
	return store.changeMember(ctx, actor, id, expectedRevision, recipient, role, false)
}

func (store *Store) RevokeMember(ctx context.Context, actor uint, id string, expectedRevision int64, recipient uint) (*MemberGrant, error) {
	return store.changeMember(ctx, actor, id, expectedRevision, recipient, "", true)
}

func (store *Store) changeMember(ctx context.Context, actor uint, id string, expectedRevision int64, recipient uint, role MemberRole, revoke bool) (*MemberGrant, error) {
	if recipient == 0 || recipient == actor {
		return nil, fmt.Errorf("%w: member account", ErrInvalid)
	}
	var grant *MemberGrant
	err := store.WithTx(ctx, func(tx *sql.Tx) error {
		library, err := ownedLibrary(ctx, tx, actor, id, expectedRevision)
		if err != nil {
			return err
		}
		if library.ArchivedAt != nil && !revoke {
			return ErrArchived
		}
		grant = &MemberGrant{LibraryID: id, UserID: recipient, LibraryRevision: library.Revision}
		err = tx.QueryRowContext(ctx, "SELECT role, revision, created_at, revoked_at FROM library_member WHERE library_id = ? AND user_id = ?", id, recipient).Scan(&grant.Role, &grant.Revision, &grant.CreatedAt, &grant.RevokedAt)
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		if errors.Is(err, sql.ErrNoRows) && revoke {
			return ErrNotFound
		}
		if err == nil && ((revoke && grant.RevokedAt != nil) || (!revoke && grant.RevokedAt == nil && grant.Role == role)) {
			return nil
		}
		now := max(time.Now().UnixMilli(), library.UpdatedAt, grant.CreatedAt)
		if errors.Is(err, sql.ErrNoRows) {
			grant.Role, grant.Revision, grant.CreatedAt = role, 1, now
			_, err = tx.ExecContext(ctx, "INSERT INTO library_member(library_id, user_id, role, created_at) VALUES (?, ?, ?, ?)", id, recipient, role, now)
		} else {
			grant.Revision++
			if revoke {
				grant.RevokedAt = &now
			} else {
				grant.Role, grant.RevokedAt = role, nil
			}
			_, err = tx.ExecContext(ctx, "UPDATE library_member SET role = ?, revision = ?, revoked_at = ? WHERE library_id = ? AND user_id = ?", grant.Role, grant.Revision, grant.RevokedAt, id, recipient)
		}
		if err != nil {
			return err
		}
		if err := updateLibrary(ctx, tx, library); err != nil {
			return err
		}
		grant.LibraryRevision = library.Revision
		event := "library.member.granted"
		if revoke {
			event = "library.member.revoked"
		}
		return publishLibraryChange(ctx, tx, library, actor, event, grant)
	})
	if err != nil {
		return nil, err
	}
	return grant, nil
}

func visibleLibrary(ctx context.Context, reader schemaReader, actor uint, id string) (*Library, error) {
	library, err := scanLibrary(reader.QueryRowContext(ctx, librarySelection+" WHERE l.id = ? AND "+libraryVisibility, actor, actor, id, actor))
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return library, err
}

func ownedLibrary(ctx context.Context, reader schemaReader, actor uint, id string, expectedRevision int64) (*Library, error) {
	if actor == 0 {
		return nil, ErrForbidden
	}
	library, err := visibleLibrary(ctx, reader, actor, id)
	if err != nil {
		return nil, err
	}
	if library.OwnerUserID != actor {
		return nil, ErrForbidden
	}
	if expectedRevision < 1 || library.Revision != expectedRevision {
		return nil, ErrRevisionConflict
	}
	return library, nil
}

type rowScanner interface{ Scan(...any) error }

func scanLibrary(row rowScanner) (*Library, error) {
	library := &Library{}
	err := row.Scan(&library.ID, &library.OwnerUserID, &library.Name, &library.Revision, &library.CreatedAt, &library.UpdatedAt, &library.ArchivedAt, &library.Role)
	if err != nil {
		return nil, err
	}
	return library, nil
}

func updateLibrary(ctx context.Context, tx *sql.Tx, library *Library) error {
	library.UpdatedAt = max(time.Now().UnixMilli(), library.UpdatedAt)
	result, err := tx.ExecContext(ctx, "UPDATE library SET name = ?, revision = revision + 1, updated_at = ?, archived_at = ? WHERE id = ? AND revision = ?", library.Name, library.UpdatedAt, library.ArchivedAt, library.ID, library.Revision)
	if err != nil {
		return err
	}
	changed, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if changed != 1 {
		return ErrRevisionConflict
	}
	library.Revision++
	return nil
}

func publishLibraryChange(ctx context.Context, tx *sql.Tx, library *Library, actor uint, kind string, grant *MemberGrant) error {
	id, err := randomID("evt_")
	if err != nil {
		return err
	}
	event := struct {
		LibraryID      string `json:"libraryId"`
		Revision       int64  `json:"revision"`
		ActorUserID    uint   `json:"actorUserId"`
		MemberUserID   uint   `json:"memberUserId,omitempty"`
		MemberRevision int64  `json:"memberRevision,omitempty"`
	}{LibraryID: library.ID, Revision: library.Revision, ActorUserID: actor}
	if grant != nil {
		event.MemberUserID, event.MemberRevision = grant.UserID, grant.Revision
	}
	payload, err := json.Marshal(event)
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, "INSERT INTO outbox(event_id, library_id, kind, payload_json, created_at) VALUES (?, ?, ?, ?, ?)", id, library.ID, kind, string(payload), library.UpdatedAt)
	return err
}

func libraryName(value string) (string, error) {
	if !utf8.ValidString(value) {
		return "", fmt.Errorf("%w: library name must be valid UTF-8", ErrInvalid)
	}
	value = strings.TrimSpace(value)
	if value == "" || utf8.RuneCountInString(value) > 200 || strings.IndexFunc(value, unicode.IsControl) >= 0 {
		return "", fmt.Errorf("%w: library name must contain 1 to 200 characters without controls", ErrInvalid)
	}
	return value, nil
}

func randomID(prefix string) (string, error) {
	var value [16]byte
	if _, err := rand.Read(value[:]); err != nil {
		return "", fmt.Errorf("generate media catalog ID: %w", err)
	}
	return prefix + hex.EncodeToString(value[:]), nil
}
