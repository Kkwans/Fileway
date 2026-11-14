package medialibrary

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
)

var (
	ErrScanActive = errors.New("a scan is already active for this source binding")
	ErrScanState  = errors.New("media scan state or lease is no longer current")
)

const (
	ScanErrorOwnerUnavailable     = "owner_unavailable"
	ScanErrorPermissionDenied     = "permission_denied"
	ScanErrorRootUnavailable      = "root_unavailable"
	ScanErrorUnsupportedRules     = "unsupported_rules"
	ScanErrorSchedulerUnavailable = "scheduler_unavailable"
	ScanErrorSourceChanged        = "source_changed"
	ScanErrorScanFailed           = "scan_failed"
)

// SourceBindingRef is a consistency token, not live filesystem authority.
// Every scan, resume, batch and finalization also requires the source adapter
// to resolve the owner's current authority and perform safe opens.
type ScanLease struct {
	JobID      string
	Generation int64
	Binding    SourceBindingRef
}

type ScanCheckpoint struct {
	Version       int    `json:"version"`
	Sequence      int64  `json:"sequence"`
	Processed     int64  `json:"processed"`
	LastObjectKey []byte `json:"lastObjectKey,omitempty"`
	Phase         string `json:"phase"`
}

type ScanJob struct {
	ID            string
	LibraryID     string
	OwnerUserID   uint
	Status        string
	ErrorCode     string
	Generation    int64
	Attempt       int64
	RulesRevision int64
	Binding       SourceBindingRef
	Checkpoint    ScanCheckpoint
	CreatedAt     int64
	UpdatedAt     int64
}

type ScanObservation struct {
	ObjectKey     []byte
	Identity      string // native or path_unverified; neither proves timeline continuity
	Locator       []byte // original full workspace locator
	SourceVersion string // existing v1:size:mtime marker, never a content hash
	SizeBytes     int64
	// Read the existing revision (zero if absent) before the final safe-open
	// verification. This prevents an older observation overwriting a newer one.
	ExpectedRevision        int64
	ExpectedLocatorRevision int64
	InitialTitle            string
	NFOStatus               string
	NFO                     *MetadataObservation
	DirectoryContext        DirectoryContextRef
	ParsedName              *mediametadata.ParsedName
}

// MetadataObservation is source evidence from a safely opened sidecar. Its
// locator and digest are private and never appear in client-facing DTOs.
type MetadataObservation struct {
	SourceKey            []byte
	Digest               []byte
	ParserVersion        int
	AssociationAmbiguous bool
	Document             mediametadata.Document
}

type ScanCommit struct {
	SourceFileID    string
	ItemID          string
	FileRevision    int64
	IdentityEpoch   int64
	LocatorRevision int64
}

type ScanMissingCandidate struct {
	SourceFileID    string
	Locator         []byte
	FileRevision    int64
	LocatorRevision int64
	SeenJobID       string
	SeenGeneration  int64
}

type ScanMissingConfirmation = ScanMissingCandidate

type ScanMissingPage struct {
	Items      []ScanMissingCandidate
	NextCursor string
}

type ScanFinish struct {
	Status           string // completed, failed or interrupted
	ExpectedSequence int64
	ErrorCode        string // optional safe code for failed; never a raw error
}

type ScanPage struct {
	Items      []ScanJob
	NextCursor string
}

// NFO issues are scoped to one scan generation and expose catalog IDs only.
// The source owner must still authorize any subsequent sidecar read.
type ScanNFOIssue struct {
	SourceFileID string
	Status       string
}

type ScanNFOIssuePage struct {
	Items      []ScanNFOIssue
	NextCursor string
}

func (store *Store) ListScanNFOIssues(ctx context.Context, actor uint, jobID string, generation int64, afterFileID string, limit int) (*ScanNFOIssuePage, error) {
	if generation < 1 {
		return nil, fmt.Errorf("%w: scan generation", ErrInvalid)
	}
	if limit == 0 {
		limit = 50
	}
	if limit < 1 || limit > 100 {
		return nil, fmt.Errorf("%w: issue page limit", ErrInvalid)
	}
	if _, err := getScan(ctx, store.db, actor, jobID); err != nil {
		return nil, err
	}
	rows, err := store.db.QueryContext(ctx, `SELECT n.source_file_id, n.status FROM scan_nfo_status n
JOIN job j ON j.id = n.job_id WHERE n.job_id = ? AND j.owner_user_id = ? AND n.generation = ?
AND n.status IN ('invalid', 'unavailable', 'changed') AND n.source_file_id > ? ORDER BY n.source_file_id LIMIT ?`, jobID, actor, generation, afterFileID, limit+1)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	page := &ScanNFOIssuePage{Items: make([]ScanNFOIssue, 0, limit)}
	for rows.Next() {
		var issue ScanNFOIssue
		if err := rows.Scan(&issue.SourceFileID, &issue.Status); err != nil {
			return nil, err
		}
		if len(page.Items) == limit {
			page.NextCursor = page.Items[len(page.Items)-1].SourceFileID
			break
		}
		page.Items = append(page.Items, issue)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return page, nil
}

// UnverifiedPathObjectKey preserves opaque path bytes and separates path-only
// identities from native identities; equality does not prove continuity.
func UnverifiedPathObjectKey(bindingID string, epoch int64, locator []byte) []byte {
	key := append([]byte(nil), []byte("fileway/local/path-unverified/v2\x00")...)
	var length [4]byte
	binary.BigEndian.PutUint32(length[:], uint32(len(bindingID)))
	key = append(key, length[:]...)
	key = append(key, bindingID...)
	var generation [8]byte
	binary.BigEndian.PutUint64(generation[:], uint64(epoch))
	key = append(key, generation[:]...)
	binary.BigEndian.PutUint32(length[:], uint32(len(locator)))
	key = append(key, length[:]...)
	return append(key, locator...)
}

type scanRecord struct {
	ScanJob
	operationID string
	active      bool
	lastDigest  []byte
	lastResult  sql.NullString
}

const scanSelection = `SELECT j.id, j.library_id, j.owner_user_id, j.status, j.generation, j.attempt, j.checkpoint_json, j.created_at, j.updated_at,
sj.source_id, sj.root_locator, sj.binding_revision, sj.authorization_revision, sj.rules_revision, sj.operation_id, sj.active, sj.last_digest, sj.last_result_json, sj.error_code,sj.binding_id,sj.binding_epoch
FROM job j JOIN scan_job sj ON sj.job_id = j.id AND sj.library_id = j.library_id`

func readScan(row rowScanner) (*scanRecord, error) {
	r := &scanRecord{}
	var checkpoint string
	var active int
	err := row.Scan(&r.ID, &r.LibraryID, &r.OwnerUserID, &r.Status, &r.Generation, &r.Attempt, &checkpoint, &r.CreatedAt, &r.UpdatedAt,
		&r.Binding.SourceID, &r.Binding.RootLocator, &r.Binding.BindingRevision, &r.Binding.AuthorizationRevision, &r.RulesRevision, &r.operationID, &active, &r.lastDigest, &r.lastResult, &r.ErrorCode, &r.Binding.BindingID, &r.Binding.ProofEpoch)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	r.Binding.LibraryID, r.active = r.LibraryID, active == 1
	if err := json.Unmarshal([]byte(checkpoint), &r.Checkpoint); err != nil {
		return nil, fmt.Errorf("decode scan checkpoint: %w", err)
	}
	if !validCheckpoint(r.Checkpoint) {
		return nil, fmt.Errorf("%w: persisted scan checkpoint", ErrInvalid)
	}
	return r, nil
}

func getScan(ctx context.Context, reader schemaReader, actor uint, jobID string) (*scanRecord, error) {
	if actor == 0 {
		return nil, ErrForbidden
	}
	return readScan(reader.QueryRowContext(ctx, scanSelection+" WHERE j.id = ? AND j.owner_user_id = ?", jobID, actor))
}

func (store *Store) GetScan(ctx context.Context, actor uint, jobID string) (*ScanJob, error) {
	r, err := getScan(ctx, store.db, actor, jobID)
	if err != nil {
		return nil, err
	}
	return &r.ScanJob, nil
}

func sameBinding(a, b SourceBindingRef) bool {
	return a.BindingID == b.BindingID && a.ProofEpoch == b.ProofEpoch && a.LibraryID == b.LibraryID && a.SourceID == b.SourceID && bytes.Equal(a.RootLocator, b.RootLocator) && a.BindingRevision == b.BindingRevision && a.AuthorizationRevision == b.AuthorizationRevision
}

func validCheckpoint(c ScanCheckpoint) bool {
	return c.Version == 1 && c.Sequence >= 0 && c.Processed >= 0 && len(c.LastObjectKey) <= 16384 && (c.Phase == "enumerate" || c.Phase == "reconcile" || c.Phase == "ready")
}

func initialCheckpoint() ScanCheckpoint { return ScanCheckpoint{Version: 1, Phase: "enumerate"} }

func scanDigest(kind string, value any) ([]byte, error) {
	data, err := json.Marshal(struct {
		Kind  string `json:"kind"`
		Value any    `json:"value"`
	}{kind, value})
	if err != nil {
		return nil, err
	}
	sum := sha256.Sum256(data)
	return sum[:], nil
}

func validOperationID(value string) bool {
	return value != "" && len(value) <= 200 && utf8.ValidString(value) && strings.IndexFunc(value, unicode.IsControl) < 0
}

func publishScanChange(ctx context.Context, tx *sql.Tx, actor uint, job *scanRecord, kind string) error {
	id, err := randomID("evt_")
	if err != nil {
		return err
	}
	payload, err := json.Marshal(struct {
		ActorUserID uint   `json:"actorUserId"`
		LibraryID   string `json:"libraryId"`
		JobID       string `json:"jobId"`
		Generation  int64  `json:"generation"`
		Status      string `json:"status"`
		ErrorCode   string `json:"errorCode,omitempty"`
		Phase       string `json:"phase"`
		Sequence    int64  `json:"sequence"`
		Processed   int64  `json:"processed"`
	}{actor, job.LibraryID, job.ID, job.Generation, job.Status, job.ErrorCode, job.Checkpoint.Phase, job.Checkpoint.Sequence, job.Checkpoint.Processed})
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, "INSERT INTO outbox(event_id, library_id, kind, payload_json, created_at) VALUES (?, ?, ?, ?, ?)", id, job.LibraryID, kind, string(payload), time.Now().UnixMilli())
	return err
}

func saveScanCheckpoint(ctx context.Context, tx *sql.Tx, job *scanRecord, checkpoint ScanCheckpoint, digest []byte, result any) error {
	encoded, err := json.Marshal(checkpoint)
	if err != nil {
		return err
	}
	var resultJSON any
	if result != nil {
		data, err := json.Marshal(result)
		if err != nil {
			return err
		}
		resultJSON = string(data)
	}
	job.Checkpoint = checkpoint
	job.UpdatedAt = max(time.Now().UnixMilli(), job.UpdatedAt)
	if _, err := tx.ExecContext(ctx, "UPDATE job SET checkpoint_json = ?, updated_at = ? WHERE id = ?", string(encoded), job.UpdatedAt, job.ID); err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, "UPDATE scan_job SET last_digest = ?, last_result_json = ? WHERE job_id = ?", digest, resultJSON, job.ID)
	return err
}

func requireScanLease(ctx context.Context, reader schemaReader, actor uint, lease ScanLease, checkBinding bool) (*scanRecord, error) {
	r, err := getScan(ctx, reader, actor, lease.JobID)
	if err != nil {
		return nil, err
	}
	if !r.active || r.Status != "running" || r.Generation != lease.Generation || !sameBinding(r.Binding, lease.Binding) {
		return nil, ErrScanState
	}
	if checkBinding {
		if _, err := validateSourceBinding(ctx, reader, actor, r.Binding); err != nil {
			return nil, err
		}
	}
	return r, nil
}

// ValidateScanLease validates database state, but never replaces live source
// authority checks performed by the caller before any filesystem operation.
func (store *Store) ValidateScanLease(ctx context.Context, actor uint, lease ScanLease) (*LibrarySource, error) {
	if _, err := requireScanLease(ctx, store.db, actor, lease, true); err != nil {
		return nil, err
	}
	return validateSourceBinding(ctx, store.db, actor, lease.Binding)
}

// EnqueueScan is idempotent for an operation ID within a library. A retry with
// different binding or rules is a conflict, not a new operation. A terminal
// failed/canceled job may be retried using a new operation ID.
func (store *Store) EnqueueScan(ctx context.Context, actor uint, binding SourceBindingRef, operationID string, rulesRevision int64) (*ScanJob, error) {
	if !validOperationID(operationID) || rulesRevision < 1 {
		return nil, fmt.Errorf("%w: scan operation or rules revision", ErrInvalid)
	}
	var result *ScanJob
	err := store.WithTx(ctx, func(tx *sql.Tx) error {
		var err error
		result, err = enqueueScanTx(ctx, tx, actor, binding, operationID, rulesRevision)
		return err
	})
	if err != nil {
		return nil, err
	}
	return result, nil
}

func enqueueScanTx(ctx context.Context, tx *sql.Tx, actor uint, binding SourceBindingRef, operationID string, rulesRevision int64) (*ScanJob, error) {
	if !validOperationID(operationID) || rulesRevision < 1 {
		return nil, fmt.Errorf("%w: scan operation or rules revision", ErrInvalid)
	}
	if _, err := validateSourceBinding(ctx, tx, actor, binding); err != nil {
		return nil, err
	}
	existing, err := readScan(tx.QueryRowContext(ctx, scanSelection+" WHERE sj.library_id = ? AND sj.operation_id = ?", binding.LibraryID, operationID))
	if err == nil {
		if existing.OwnerUserID != actor || !sameBinding(existing.Binding, binding) || existing.RulesRevision != rulesRevision {
			return nil, ErrRevisionConflict
		}
		return &existing.ScanJob, nil
	}
	if !errors.Is(err, ErrNotFound) {
		return nil, err
	}
	var active int
	if err := tx.QueryRowContext(ctx, "SELECT count(*) FROM scan_job WHERE library_id = ? AND source_id = ? AND root_locator = ? AND active = 1", binding.LibraryID, binding.SourceID, binding.RootLocator).Scan(&active); err != nil {
		return nil, err
	}
	if active != 0 {
		return nil, ErrScanActive
	}
	id, err := randomID("job_")
	if err != nil {
		return nil, err
	}
	now := time.Now().UnixMilli()
	cp := initialCheckpoint()
	encoded, err := json.Marshal(cp)
	if err != nil {
		return nil, err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO job(id, library_id, owner_user_id, kind, status, generation, checkpoint_json, created_at, updated_at)
VALUES (?, ?, ?, 'library.scan', 'queued', 1, ?, ?, ?)`, id, binding.LibraryID, actor, string(encoded), now, now); err != nil {
		return nil, err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO scan_job(job_id, library_id, source_id, root_locator, binding_revision, authorization_revision, rules_revision, operation_id, active,binding_id,binding_epoch)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1,?,?)`, id, binding.LibraryID, binding.SourceID, binding.RootLocator, binding.BindingRevision, binding.AuthorizationRevision, rulesRevision, operationID, binding.BindingID, binding.ProofEpoch); err != nil {
		return nil, err
	}
	r := &scanRecord{ScanJob: ScanJob{ID: id, LibraryID: binding.LibraryID, OwnerUserID: actor, Status: "queued", Generation: 1, RulesRevision: rulesRevision, Binding: binding, Checkpoint: cp, CreatedAt: now, UpdatedAt: now}, operationID: operationID, active: true}
	if err := publishScanChange(ctx, tx, actor, r, "media.scan.queued"); err != nil {
		return nil, err
	}
	return &r.ScanJob, nil
}

// ClaimScan must be called after live source authorization. Recovery always
// starts at the root with a new generation; a checkpoint is not a directory
// cursor and an old callback can never write into the new generation.
func (store *Store) claimScan(ctx context.Context, actor uint, jobID string, expectedGeneration int64) (*ScanLease, error) {
	var lease *ScanLease
	err := store.WithTx(ctx, func(tx *sql.Tx) error {
		r, err := getScan(ctx, tx, actor, jobID)
		if err != nil {
			return err
		}
		if !r.active || (r.Status != "queued" && r.Status != "interrupted") || expectedGeneration != r.Generation {
			return ErrScanState
		}
		if _, err := validateSourceBinding(ctx, tx, actor, r.Binding); err != nil {
			return err
		}
		r.Generation++
		r.Attempt++
		r.Status = "running"
		r.UpdatedAt = max(time.Now().UnixMilli(), r.UpdatedAt)
		if _, err := tx.ExecContext(ctx, "UPDATE job SET status = ?, generation = ?, attempt = ?, updated_at = ? WHERE id = ?", r.Status, r.Generation, r.Attempt, r.UpdatedAt, r.ID); err != nil {
			return err
		}
		if err := saveScanCheckpoint(ctx, tx, r, initialCheckpoint(), nil, nil); err != nil {
			return err
		}
		if err := publishScanChange(ctx, tx, actor, r, "media.scan.started"); err != nil {
			return err
		}
		lease = &ScanLease{JobID: r.ID, Generation: r.Generation, Binding: r.Binding}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return lease, nil
}

// ListRecoverableScans is owner-only and does not expose source configuration
// to library viewers or editors. The scheduler must handle running jobs only
// after startup recovery has fenced callbacks from the previous process.
func (store *Store) ListRecoverableScans(ctx context.Context, actor uint, afterID string, limit int) (*ScanPage, error) {
	if actor == 0 {
		return nil, ErrForbidden
	}
	if limit == 0 {
		limit = 50
	}
	if limit < 1 || limit > 100 {
		return nil, fmt.Errorf("%w: scan page limit", ErrInvalid)
	}
	rows, err := store.db.QueryContext(ctx, scanSelection+" WHERE j.owner_user_id = ? AND sj.active = 1 AND j.status IN ('queued', 'running', 'interrupted') AND j.id > ? ORDER BY j.id LIMIT ?", actor, afterID, limit+1)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	page := &ScanPage{Items: make([]ScanJob, 0, limit)}
	for rows.Next() {
		r, err := readScan(rows)
		if err != nil {
			return nil, err
		}
		if len(page.Items) == limit {
			page.NextCursor = page.Items[len(page.Items)-1].ID
			break
		}
		page.Items = append(page.Items, r.ScanJob)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return page, nil
}

// InterruptRunningScans is a startup-only recovery step. Call it only after
// excluding any concurrently active scheduler for this catalog. It touches no
// generic jobs and preserves prior checkpoints for inspection until reclaimed.
func (store *Store) interruptRunningScans(ctx context.Context, validate func(context.Context) error) (int, error) {
	count := 0
	for {
		if err := validate(ctx); err != nil {
			return count, err
		}
		batchCount := 0
		err := store.WithTx(ctx, func(tx *sql.Tx) error {
			rows, err := tx.QueryContext(ctx, scanSelection+" WHERE sj.active = 1 AND j.status = 'running' ORDER BY j.id LIMIT 100")
			if err != nil {
				return err
			}
			var jobs []*scanRecord
			for rows.Next() {
				r, err := readScan(rows)
				if err != nil {
					_ = rows.Close()
					return err
				}
				jobs = append(jobs, r)
			}
			if err := rows.Err(); err != nil {
				_ = rows.Close()
				return err
			}
			if err := rows.Close(); err != nil {
				return err
			}
			for _, r := range jobs {
				r.Status, r.Generation = "interrupted", r.Generation+1
				r.UpdatedAt = max(time.Now().UnixMilli(), r.UpdatedAt)
				if _, err := tx.ExecContext(ctx, "UPDATE job SET status = ?, generation = ?, updated_at = ? WHERE id = ?", r.Status, r.Generation, r.UpdatedAt, r.ID); err != nil {
					return err
				}
				if err := publishScanChange(ctx, tx, r.OwnerUserID, r, "media.scan.interrupted"); err != nil {
					return err
				}
				batchCount++
			}
			return nil
		})
		if err != nil {
			return count, err
		}
		count += batchCount
		if batchCount < 100 {
			return count, nil
		}
	}
}

func (store *Store) CancelScan(ctx context.Context, actor uint, jobID string, expectedGeneration int64) (*ScanJob, error) {
	var result *ScanJob
	err := store.WithTx(ctx, func(tx *sql.Tx) error {
		var err error
		result, err = cancelScanTx(ctx, tx, actor, jobID, expectedGeneration)
		return err
	})
	if err != nil {
		return nil, err
	}
	return result, nil
}

func cancelScanTx(ctx context.Context, tx *sql.Tx, actor uint, jobID string, expectedGeneration int64) (*ScanJob, error) {
	r, err := getScan(ctx, tx, actor, jobID)
	if err != nil {
		return nil, err
	}
	if !r.active || r.Generation != expectedGeneration || (r.Status != "queued" && r.Status != "running" && r.Status != "interrupted") {
		return nil, ErrScanState
	}
	r.Status, r.Generation, r.active = "canceled", r.Generation+1, false
	r.UpdatedAt = max(time.Now().UnixMilli(), r.UpdatedAt)
	if _, err := tx.ExecContext(ctx, "UPDATE job SET status = ?, generation = ?, updated_at = ? WHERE id = ?", r.Status, r.Generation, r.UpdatedAt, r.ID); err != nil {
		return nil, err
	}
	if _, err := tx.ExecContext(ctx, "UPDATE scan_job SET active = 0 WHERE job_id = ?", r.ID); err != nil {
		return nil, err
	}
	if err := publishScanChange(ctx, tx, actor, r, "media.scan.canceled"); err != nil {
		return nil, err
	}
	return &r.ScanJob, nil
}

// CommitScanObservation writes file evidence, a first unmatched item when no
// mapping exists, the checkpoint and outbox under one SQLite write lock.
// Only the last identical sequence may be replayed; it returns the same IDs.
func (store *Store) CommitScanObservation(ctx context.Context, actor uint, lease ScanLease, input ScanObservation, checkpoint ScanCheckpoint) (*ScanCommit, error) {
	if len(input.ObjectKey) == 0 || len(input.ObjectKey) > 16384 || len(input.Locator) == 0 || len(input.Locator) > 8192 || input.SourceVersion == "" || len(input.SourceVersion) > 1024 || input.SizeBytes < 0 || input.ExpectedRevision < 0 || input.ExpectedLocatorRevision < 0 || (input.Identity != "native" && input.Identity != "path_unverified") {
		return nil, fmt.Errorf("%w: scan observation", ErrInvalid)
	}
	if input.Identity == "path_unverified" && !bytes.Equal(input.ObjectKey, UnverifiedPathObjectKey(lease.Binding.BindingID, lease.Binding.ProofEpoch, input.Locator)) {
		return nil, fmt.Errorf("%w: unverified identity must contain the full locator", ErrInvalid)
	}
	if input.NFOStatus != "missing" && input.NFOStatus != "parsed" && input.NFOStatus != "invalid" && input.NFOStatus != "unavailable" && input.NFOStatus != "changed" {
		return nil, fmt.Errorf("%w: NFO status", ErrInvalid)
	}
	if (input.NFOStatus == "parsed") != (input.NFO != nil) {
		return nil, fmt.Errorf("%w: NFO status and evidence", ErrInvalid)
	}
	if !validCheckpoint(checkpoint) || checkpoint.Phase != "enumerate" || !bytes.Equal(checkpoint.LastObjectKey, input.ObjectKey) {
		return nil, fmt.Errorf("%w: observation checkpoint", ErrInvalid)
	}
	digest, err := scanDigest("observation", struct {
		Input      ScanObservation
		Checkpoint ScanCheckpoint
	}{input, checkpoint})
	if err != nil {
		return nil, err
	}
	var result *ScanCommit
	err = store.WithTx(ctx, func(tx *sql.Tx) error {
		r, err := requireScanLease(ctx, tx, actor, lease, true)
		if err != nil {
			return err
		}
		if r.Checkpoint.Phase != "enumerate" {
			return ErrScanState
		}
		if checkpoint.Sequence == r.Checkpoint.Sequence {
			if !bytes.Equal(digest, r.lastDigest) || !r.lastResult.Valid {
				return ErrRevisionConflict
			}
			result = &ScanCommit{}
			return json.Unmarshal([]byte(r.lastResult.String), result)
		}
		if checkpoint.Sequence != r.Checkpoint.Sequence+1 || checkpoint.Processed != r.Checkpoint.Processed+1 {
			return ErrRevisionConflict
		}
		var revision int64
		var seenJob sql.NullString
		var seenGeneration sql.NullInt64
		err = tx.QueryRowContext(ctx, `SELECT f.revision, lf.last_seen_job_id, lf.last_seen_generation FROM source_file f
LEFT JOIN library_file lf ON lf.source_file_id = f.id AND lf.library_id = ? AND lf.source_id = ? AND lf.root_locator = ? AND lf.binding_epoch=?
WHERE f.source_id = ? AND f.object_key = ?`, r.LibraryID, r.Binding.SourceID, r.Binding.RootLocator, r.Binding.ProofEpoch, r.Binding.SourceID, input.ObjectKey).Scan(&revision, &seenJob, &seenGeneration)
		exists := err == nil
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		if revision != input.ExpectedRevision {
			return ErrRevisionConflict
		}
		sameGeneration := seenJob.Valid && seenJob.String == r.ID && seenGeneration.Valid && seenGeneration.Int64 == r.Generation
		forceEpoch := exists && input.Identity == "path_unverified" && !sameGeneration
		size := input.SizeBytes
		file, err := observeSourceFileTx(ctx, tx, actor, r.Binding, FileObservation{ObjectKey: input.ObjectKey, Locator: input.Locator, SourceVersion: input.SourceVersion, SizeBytes: &size, ExpectedRevision: input.ExpectedRevision, ExpectedLocatorRevision: &input.ExpectedLocatorRevision}, input.Identity, forceEpoch)
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE library_file SET last_seen_job_id = ?, last_seen_generation = ?
WHERE library_id = ? AND source_id = ? AND root_locator = ? AND source_file_id = ?`, r.ID, r.Generation, r.LibraryID, r.Binding.SourceID, r.Binding.RootLocator, file.ID); err != nil {
			return err
		}
		// Hard links may expose one native object through multiple paths with
		// different sidecars. A later clean observation must not erase an issue
		// found earlier in this attempt; a new generation reevaluates it.
		if _, err := tx.ExecContext(ctx, `INSERT INTO scan_nfo_status(job_id, generation, source_file_id, status) VALUES (?, ?, ?, ?)
ON CONFLICT(job_id, generation, source_file_id) DO UPDATE SET status = CASE
WHEN scan_nfo_status.status IN ('invalid', 'unavailable', 'changed') AND excluded.status IN ('missing', 'parsed') THEN scan_nfo_status.status
ELSE excluded.status END`, r.ID, r.Generation, file.ID, input.NFOStatus); err != nil {
			return err
		}
		decision, err := identifyScanFileTx(ctx, tx, actor, r, file.ID, input, sameGeneration)
		if err != nil {
			return err
		}
		itemID := decision.ItemID
		if !decision.Handled {
			mappings, err := scanFileMappingsTx(ctx, tx, r.Binding, file.ID)
			if err != nil {
				return err
			}
			if decision.Resolution.State == "needs_review" {
				for index := range mappings {
					mapping := &mappings[index]
					if mapping.Kind == "video" && mapping.SupersededBy != "" && !mapping.Manual {
						if _, err := tx.ExecContext(ctx, `UPDATE item_structure SET superseded_by=NULL,structure_revision=structure_revision+1 WHERE library_id=? AND item_id=? AND superseded_by=?`, r.LibraryID, mapping.ItemID, mapping.SupersededBy); err != nil {
							return err
						}
						mapping.SupersededBy = ""
					}
				}
			}
			var visible []scanMapping
			for _, mapping := range mappings {
				if mapping.SupersededBy == "" && (decision.Resolution.State != "needs_review" || mapping.Kind != "episode" || mapping.Manual) {
					visible = append(visible, mapping)
				}
			}
			if len(visible) == 0 && (len(mappings) == 0 || decision.Resolution.State == "needs_review") {
				if len(mappings) == 0 && input.Identity == "native" && decision.Resolution.State != "needs_review" {
					itemID, err = recoverFilenameVideoTx(ctx, tx, r.LibraryID, file.ID)
					if err != nil {
						return err
					}
				}
				if itemID == "" {
					item, err := createCatalogItemTx(ctx, tx, actor, r.LibraryID, NewCatalogItem{Kind: "video", Title: input.InitialTitle, initialSource: "filename", initialBinding: &r.Binding})
					if err != nil {
						return err
					}
					itemID = item.ID
				}
				if err := attachScanMappingTx(ctx, tx, actor, r.Binding, itemID, file.ID, decision.EvidenceID, "single"); err != nil {
					return err
				}
			} else if len(visible) == 1 {
				itemID = visible[0].ItemID
			}
			// Multiple existing episode links are retained without selecting one
			// as the work identity when current evidence is incomplete.
			if itemID != "" {
				if decision.Resolution.State == "needs_review" {
					issue := "structure_conflict"
					if len(decision.Resolution.Issues) > 0 {
						issue = decision.Resolution.Issues[0]
					}
					if _, err := tx.ExecContext(ctx, `UPDATE item_structure SET match_state='needs_review',issue_code=? WHERE library_id=? AND item_id=? AND item_id IN (SELECT id FROM library_item WHERE kind='video')`, issue, r.LibraryID, itemID); err != nil {
						return err
					}
				}
				if err := applyFilenameObservationTx(ctx, tx, actor, r.Binding, itemID, input.InitialTitle); err != nil {
					return err
				}
				if input.NFO != nil {
					observation := *input.NFO
					if decision.Resolution.State == "needs_review" {
						observation.AssociationAmbiguous = true
					}
					if err := applyNFOObservationTx(ctx, tx, actor, r.Binding, itemID, observation); err != nil {
						return err
					}
				}
			}
		}
		result = &ScanCommit{SourceFileID: file.ID, ItemID: itemID, FileRevision: file.Revision, IdentityEpoch: file.IdentityEpoch, LocatorRevision: file.LocatorRevision}
		if err := saveScanCheckpoint(ctx, tx, r, checkpoint, digest, result); err != nil {
			return err
		}
		return publishScanChange(ctx, tx, actor, r, "media.scan.progress")
	})
	if err != nil {
		return nil, err
	}
	return result, nil
}

// CompleteScanEnumeration may be called only after the adapter completes a
// full traversal without cancellation, authority failure or enumeration error.
func (store *Store) CompleteScanEnumeration(ctx context.Context, actor uint, lease ScanLease, expectedSequence int64) error {
	return store.WithTx(ctx, func(tx *sql.Tx) error {
		r, err := requireScanLease(ctx, tx, actor, lease, true)
		if err != nil {
			return err
		}
		if r.Checkpoint.Sequence != expectedSequence {
			return ErrRevisionConflict
		}
		if r.Checkpoint.Phase == "reconcile" {
			return nil
		}
		if r.Checkpoint.Phase != "enumerate" {
			return ErrScanState
		}
		cp := r.Checkpoint
		cp.Phase = "reconcile"
		if err := saveScanCheckpoint(ctx, tx, r, cp, nil, nil); err != nil {
			return err
		}
		return publishScanChange(ctx, tx, actor, r, "media.scan.reconciling")
	})
}

// A valid staged candidate still names the exact file revision, locator and
// previously observed generation that were checked for safe absence.
const unstagedMissing = `lf.library_id = ? AND lf.source_id = ? AND lf.root_locator = ? AND lf.missing_since IS NULL AND lf.binding_epoch>0
AND lf.binding_epoch=(SELECT ls.proof_epoch FROM library_source ls WHERE ls.library_id=lf.library_id AND ls.source_id=lf.source_id AND ls.root_locator=lf.root_locator)
AND NOT (lf.last_seen_job_id IS ? AND lf.last_seen_generation IS ?)
AND NOT EXISTS (SELECT 1 FROM scan_missing_candidate c WHERE c.job_id = ? AND c.generation = ? AND c.source_file_id = lf.source_file_id
 AND c.file_revision = f.revision AND c.locator_revision=lf.locator_revision AND c.locator = lf.locator AND c.seen_job_id IS lf.last_seen_job_id AND c.seen_generation IS lf.last_seen_generation)`

func (store *Store) ListScanMissingCandidates(ctx context.Context, actor uint, lease ScanLease, afterFileID string, limit int) (*ScanMissingPage, error) {
	if limit == 0 {
		limit = 50
	}
	if limit < 1 || limit > 100 {
		return nil, fmt.Errorf("%w: missing candidate page limit", ErrInvalid)
	}
	r, err := requireScanLease(ctx, store.db, actor, lease, true)
	if err != nil {
		return nil, err
	}
	if r.Checkpoint.Phase != "reconcile" {
		return nil, ErrScanState
	}
	rows, err := store.db.QueryContext(ctx, `SELECT lf.source_file_id, lf.locator, f.revision, lf.locator_revision,lf.last_seen_job_id, lf.last_seen_generation
FROM library_file lf JOIN source_file f ON f.id = lf.source_file_id AND f.source_id = lf.source_id WHERE `+unstagedMissing+` AND lf.source_file_id > ? ORDER BY lf.source_file_id LIMIT ?`,
		r.LibraryID, r.Binding.SourceID, r.Binding.RootLocator, r.ID, r.Generation, r.ID, r.Generation, afterFileID, limit+1)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	page := &ScanMissingPage{Items: make([]ScanMissingCandidate, 0, limit)}
	for rows.Next() {
		var c ScanMissingCandidate
		var seenJob sql.NullString
		var seenGeneration sql.NullInt64
		if err := rows.Scan(&c.SourceFileID, &c.Locator, &c.FileRevision, &c.LocatorRevision, &seenJob, &seenGeneration); err != nil {
			return nil, err
		}
		if len(page.Items) == limit {
			page.NextCursor = page.Items[len(page.Items)-1].SourceFileID
			break
		}
		if seenJob.Valid {
			c.SeenJobID = seenJob.String
		}
		if seenGeneration.Valid {
			c.SeenGeneration = seenGeneration.Int64
		}
		page.Items = append(page.Items, c)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return page, nil
}

// CommitScanMissing stages only a verified safe NotExist result. The caller
// must re-resolve live authority and safely check every locator immediately
// before supplying it. Denial, offline, a special object or any ambiguous
// error is not a missing confirmation. No catalog missing flag changes here.
func (store *Store) CommitScanMissing(ctx context.Context, actor uint, lease ScanLease, candidates []ScanMissingConfirmation, checkpoint ScanCheckpoint) error {
	if len(candidates) == 0 || len(candidates) > 100 || !validCheckpoint(checkpoint) || checkpoint.Phase != "reconcile" {
		return fmt.Errorf("%w: missing confirmation batch", ErrInvalid)
	}
	seen := make(map[string]struct{}, len(candidates))
	for _, c := range candidates {
		if c.SourceFileID == "" || len(c.Locator) == 0 || len(c.Locator) > 8192 || c.FileRevision < 1 || c.LocatorRevision < 1 || ((c.SeenJobID == "") != (c.SeenGeneration == 0)) || c.SeenGeneration < 0 {
			return fmt.Errorf("%w: missing confirmation", ErrInvalid)
		}
		if _, duplicate := seen[c.SourceFileID]; duplicate {
			return fmt.Errorf("%w: duplicate missing confirmation", ErrInvalid)
		}
		seen[c.SourceFileID] = struct{}{}
	}
	digest, err := scanDigest("missing", struct {
		Candidates []ScanMissingConfirmation
		Checkpoint ScanCheckpoint
	}{candidates, checkpoint})
	if err != nil {
		return err
	}
	return store.WithTx(ctx, func(tx *sql.Tx) error {
		r, err := requireScanLease(ctx, tx, actor, lease, true)
		if err != nil {
			return err
		}
		if r.Checkpoint.Phase != "reconcile" {
			return ErrScanState
		}
		if checkpoint.Sequence == r.Checkpoint.Sequence {
			if !bytes.Equal(digest, r.lastDigest) {
				return ErrRevisionConflict
			}
			return nil
		}
		if checkpoint.Sequence != r.Checkpoint.Sequence+1 || checkpoint.Processed != r.Checkpoint.Processed || !bytes.Equal(checkpoint.LastObjectKey, r.Checkpoint.LastObjectKey) {
			return ErrRevisionConflict
		}
		for _, c := range candidates {
			var locator []byte
			var revision int64
			var locatorRevision int64
			var seenJob sql.NullString
			var seenGeneration sql.NullInt64
			var missing sql.NullInt64
			err := tx.QueryRowContext(ctx, `SELECT lf.locator, f.revision,lf.locator_revision, lf.last_seen_job_id, lf.last_seen_generation, lf.missing_since
FROM library_file lf JOIN source_file f ON f.id = lf.source_file_id AND f.source_id = lf.source_id
WHERE lf.library_id = ? AND lf.source_id = ? AND lf.root_locator = ? AND lf.source_file_id = ? AND lf.binding_epoch=? AND lf.binding_epoch>0`, r.LibraryID, r.Binding.SourceID, r.Binding.RootLocator, c.SourceFileID, r.Binding.ProofEpoch).Scan(&locator, &revision, &locatorRevision, &seenJob, &seenGeneration, &missing)
			if errors.Is(err, sql.ErrNoRows) {
				return ErrRevisionConflict
			}
			if err != nil {
				return err
			}
			if missing.Valid || revision != c.FileRevision || locatorRevision != c.LocatorRevision || !bytes.Equal(locator, c.Locator) || seenJob.String != c.SeenJobID || seenGeneration.Int64 != c.SeenGeneration || (seenJob.Valid && seenJob.String == r.ID && seenGeneration.Valid && seenGeneration.Int64 == r.Generation) {
				return ErrRevisionConflict
			}
			var seenJobValue, seenGenerationValue any
			if c.SeenJobID != "" {
				seenJobValue, seenGenerationValue = c.SeenJobID, c.SeenGeneration
			}
			if _, err := tx.ExecContext(ctx, `INSERT INTO scan_missing_candidate(job_id, generation, source_file_id, file_revision,locator_revision, locator, seen_job_id, seen_generation)
VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(job_id, generation, source_file_id) DO UPDATE SET
file_revision = excluded.file_revision,locator_revision=excluded.locator_revision, locator = excluded.locator, seen_job_id = excluded.seen_job_id, seen_generation = excluded.seen_generation`, r.ID, r.Generation, c.SourceFileID, c.FileRevision, c.LocatorRevision, c.Locator, seenJobValue, seenGenerationValue); err != nil {
				return err
			}
		}
		if err := saveScanCheckpoint(ctx, tx, r, checkpoint, digest, nil); err != nil {
			return err
		}
		return publishScanChange(ctx, tx, actor, r, "media.scan.reconcile.progress")
	})
}

func countUnstagedMissing(ctx context.Context, tx *sql.Tx, r *scanRecord) (int, error) {
	var count int
	err := tx.QueryRowContext(ctx, `SELECT count(*) FROM library_file lf JOIN source_file f ON f.id = lf.source_file_id AND f.source_id = lf.source_id WHERE `+unstagedMissing,
		r.LibraryID, r.Binding.SourceID, r.Binding.RootLocator, r.ID, r.Generation, r.ID, r.Generation).Scan(&count)
	return count, err
}

// CompleteScanReconciliation persists the EOF gate only when every current
// candidate has a still-valid staged confirmation. It does not mark files.
func (store *Store) CompleteScanReconciliation(ctx context.Context, actor uint, lease ScanLease, expectedSequence int64) error {
	return store.WithTx(ctx, func(tx *sql.Tx) error {
		r, err := requireScanLease(ctx, tx, actor, lease, true)
		if err != nil {
			return err
		}
		if r.Checkpoint.Sequence != expectedSequence {
			return ErrRevisionConflict
		}
		if r.Checkpoint.Phase == "ready" {
			return nil
		}
		if r.Checkpoint.Phase != "reconcile" {
			return ErrScanState
		}
		count, err := countUnstagedMissing(ctx, tx, r)
		if err != nil {
			return err
		}
		if count != 0 {
			return ErrRevisionConflict
		}
		cp := r.Checkpoint
		cp.Phase = "ready"
		if err := saveScanCheckpoint(ctx, tx, r, cp, nil, nil); err != nil {
			return err
		}
		return publishScanChange(ctx, tx, actor, r, "media.scan.reconciled")
	})
}

// FinishScan requires a fresh live authority check before completed. Publishing
// missing bindings, the completed job and the outbox share one transaction.
// Failure/interruption can still be recorded after stored authority is revoked.
func (store *Store) FinishScan(ctx context.Context, actor uint, lease ScanLease, finish ScanFinish) (*ScanJob, error) {
	if finish.Status != "completed" && finish.Status != "failed" && finish.Status != "interrupted" {
		return nil, fmt.Errorf("%w: scan finish status", ErrInvalid)
	}
	if (finish.Status != "failed" && finish.ErrorCode != "") || (finish.ErrorCode != "" && !validScanErrorCode(finish.ErrorCode)) {
		return nil, fmt.Errorf("%w: scan error code", ErrInvalid)
	}
	var result *ScanJob
	err := store.WithTx(ctx, func(tx *sql.Tx) error {
		r, err := requireScanLease(ctx, tx, actor, lease, finish.Status == "completed")
		if err != nil {
			return err
		}
		if r.Checkpoint.Sequence != finish.ExpectedSequence {
			return ErrRevisionConflict
		}
		if finish.Status == "completed" {
			if r.Checkpoint.Phase != "ready" {
				return ErrScanState
			}
			count, err := countUnstagedMissing(ctx, tx, r)
			if err != nil {
				return err
			}
			if count != 0 {
				return ErrRevisionConflict
			}
			now := time.Now().UnixMilli()
			_, err = tx.ExecContext(ctx, `UPDATE library_file AS lf SET missing_since = ?
WHERE lf.library_id = ? AND lf.source_id = ? AND lf.root_locator = ? AND lf.missing_since IS NULL
AND lf.binding_epoch=? AND lf.binding_epoch>0
AND NOT (lf.last_seen_job_id IS ? AND lf.last_seen_generation IS ?)
AND EXISTS (SELECT 1 FROM scan_missing_candidate c JOIN source_file f ON f.id = c.source_file_id AND f.source_id = lf.source_id
WHERE c.job_id = ? AND c.generation = ? AND c.source_file_id = lf.source_file_id AND c.file_revision = f.revision AND c.locator_revision=lf.locator_revision AND c.locator = lf.locator
AND c.seen_job_id IS lf.last_seen_job_id AND c.seen_generation IS lf.last_seen_generation)`, now, r.LibraryID, r.Binding.SourceID, r.Binding.RootLocator, r.Binding.ProofEpoch, r.ID, r.Generation, r.ID, r.Generation)
			if err != nil {
				return err
			}
		}
		r.Status, r.ErrorCode = finish.Status, finish.ErrorCode
		// The library may have been archived while a filesystem error was in
		// flight. Once archived it is the authoritative reason this job cannot
		// publish; record that reason under the same write transaction.
		if r.Status == "failed" {
			library, err := visibleLibrary(ctx, tx, actor, r.LibraryID)
			if err != nil {
				return err
			}
			if library.ArchivedAt != nil {
				r.ErrorCode = ScanErrorPermissionDenied
			}
		}
		if finish.Status == "interrupted" {
			r.Generation++
		} else {
			r.active = false
		}
		r.UpdatedAt = max(time.Now().UnixMilli(), r.UpdatedAt)
		if _, err := tx.ExecContext(ctx, "UPDATE job SET status = ?, generation = ?, updated_at = ? WHERE id = ?", r.Status, r.Generation, r.UpdatedAt, r.ID); err != nil {
			return err
		}
		if !r.active {
			if _, err := tx.ExecContext(ctx, "UPDATE scan_job SET active = 0, error_code = ? WHERE job_id = ?", r.ErrorCode, r.ID); err != nil {
				return err
			}
		}
		if err := publishScanChange(ctx, tx, actor, r, "media.scan."+r.Status); err != nil {
			return err
		}
		if r.Status == "completed" {
			if err := enqueueMetadataJobAfterScan(ctx, tx, r); err != nil {
				return err
			}
		}
		result = &r.ScanJob
		return nil
	})
	if err != nil {
		return nil, err
	}
	return result, nil
}
