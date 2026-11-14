package medialibrary

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

type MetadataJob struct {
	ID, LibraryID, ScanID, Status, FailureCode string
	OwnerUserID                                uint
	Generation, ScanGeneration                 int64
	Attempt                                    int64
	Authority                                  ProviderUseAuthority
	Matched, Review, Skipped, Failed, Pending  int64
}

type MetadataJobTarget struct {
	ItemID, Kind                    string
	ItemRevision, StructureRevision int64
}

const metadataJobKind = "library.metadata.fill"

// Enqueue the follow-up in the scan's completion transaction. The manifest
// names the scanned items, not a mutable query over the whole library. Series
// are taken from mapped episodes under the completed binding only.
func enqueueMetadataJobAfterScan(ctx context.Context, tx *sql.Tx, scan *scanRecord) error {
	policy, err := readLibraryMetadataPolicy(ctx, tx, scan.OwnerUserID, scan.LibraryID)
	if errors.Is(err, ErrNotFound) {
		return nil
	}
	if err != nil || !policy.OnlineEnabled() {
		return err
	}
	authority, _, err := readProviderCredentialForUse(ctx, tx, scan.OwnerUserID, scan.LibraryID, policy.ConnectionID)
	if errors.Is(err, ErrNotFound) {
		return nil
	}
	if err != nil {
		return err
	}
	id, err := randomID("job_")
	if err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO job(id,library_id,owner_user_id,kind,status,created_at,updated_at)
VALUES(?,?,?,?,'queued',?,?)`, id, scan.LibraryID, scan.OwnerUserID, metadataJobKind, scan.UpdatedAt, scan.UpdatedAt); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO metadata_job(job_id,scan_id,scan_generation,connection_id,connection_revision,policy_revision)
VALUES(?,?,?,?,?,?)`, id, scan.ID, scan.Generation, authority.ConnectionID, authority.ConnectionRevision, authority.PolicyRevision); err != nil {
		return err
	}
	result, err := tx.ExecContext(ctx, `WITH observed(item_id) AS (
 SELECT DISTINCT m.item_id FROM library_file f JOIN file_item_mapping m ON
 m.library_id=f.library_id AND m.source_file_id=f.source_file_id AND m.binding_id=? AND m.binding_epoch=f.binding_epoch AND m.active=1
 WHERE f.library_id=? AND f.last_seen_job_id=? AND f.last_seen_generation=? AND f.missing_since IS NULL
), targets(item_id) AS (
 SELECT i.id FROM observed o JOIN library_item i ON i.library_id=? AND i.id=o.item_id AND i.kind IN ('video','movie','series')
 UNION
 SELECT series.id FROM observed o JOIN library_item episode ON episode.library_id=? AND episode.id=o.item_id AND episode.kind='episode'
 JOIN library_item season ON season.library_id=episode.library_id AND season.id=episode.parent_id AND season.kind='season'
 JOIN library_item series ON series.library_id=season.library_id AND series.id=season.parent_id AND series.kind='series'
)
INSERT INTO metadata_job_target(job_id,library_id,item_id)
SELECT ?,st.library_id,t.item_id FROM targets t JOIN item_structure st ON st.library_id=? AND st.item_id=t.item_id AND st.superseded_by IS NULL
WHERE NOT EXISTS (SELECT 1 FROM item_provider_match m WHERE m.library_id=st.library_id AND m.item_id=t.item_id)`,
		scan.Binding.BindingID, scan.LibraryID, scan.ID, scan.Generation, scan.LibraryID, scan.LibraryID, id, scan.LibraryID)
	if err != nil {
		return err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if count == 0 {
		if _, err := tx.ExecContext(ctx, `DELETE FROM metadata_job WHERE job_id=?`, id); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `DELETE FROM job WHERE id=?`, id)
		return err
	}
	return nil
}

const metadataJobSelection = `SELECT j.id,j.library_id,m.scan_id,j.status,m.failure_code,j.owner_user_id,j.generation,m.scan_generation,j.attempt,
m.connection_id,m.connection_revision,m.policy_revision FROM job j JOIN metadata_job m ON m.job_id=j.id`

func readMetadataJob(row rowScanner) (MetadataJob, error) {
	var job MetadataJob
	err := row.Scan(&job.ID, &job.LibraryID, &job.ScanID, &job.Status, &job.FailureCode, &job.OwnerUserID, &job.Generation,
		&job.ScanGeneration, &job.Attempt, &job.Authority.ConnectionID, &job.Authority.ConnectionRevision, &job.Authority.PolicyRevision)
	if errors.Is(err, sql.ErrNoRows) {
		return job, ErrNotFound
	}
	return job, err
}

// MetadataJobForScan serves a current library owner's progress view. No source
// paths, credentials, or provider connection IDs need be sent to the client.
func (store *Store) MetadataJobForScan(ctx context.Context, actor uint, libraryID, scanID string) (MetadataJob, error) {
	job, err := readMetadataJob(store.db.QueryRowContext(ctx, metadataJobSelection+` JOIN library l ON l.id=j.library_id
WHERE j.library_id=? AND m.scan_id=? AND j.owner_user_id=? AND l.owner_user_id=? AND l.archived_at IS NULL`, libraryID, scanID, actor, actor))
	if err != nil {
		return job, err
	}
	err = store.db.QueryRowContext(ctx, `SELECT count(*) FILTER (WHERE outcome='matched'),
count(*) FILTER (WHERE outcome IN ('needs_review','no_match')),count(*) FILTER (WHERE outcome IN ('already_matched','source_changed')),
count(*) FILTER (WHERE outcome='failed'),count(*) FILTER (WHERE outcome='pending') FROM metadata_job_target WHERE job_id=?`, job.ID).
		Scan(&job.Matched, &job.Review, &job.Skipped, &job.Failed, &job.Pending)
	return job, err
}

type MetadataReviewTarget struct {
	ItemID string `json:"itemId"`
	Reason string `json:"reason"`
}

func (store *Store) ListMetadataReviewTargets(ctx context.Context, actor uint, job MetadataJob, after string, limit int) ([]MetadataReviewTarget, string, error) {
	if limit < 1 || limit > 100 {
		return nil, "", ErrInvalid
	}
	rows, err := store.db.QueryContext(ctx, `SELECT t.item_id,t.reason FROM metadata_job_target t
JOIN job j ON j.id=t.job_id AND j.owner_user_id=? JOIN library l ON l.id=t.library_id AND l.owner_user_id=? AND l.archived_at IS NULL
JOIN item_structure s ON s.library_id=t.library_id AND s.item_id=t.item_id AND s.superseded_by IS NULL
WHERE t.job_id=? AND t.item_id>? AND t.outcome IN ('needs_review','no_match')
AND NOT EXISTS(SELECT 1 FROM item_provider_match pm WHERE pm.library_id=t.library_id AND pm.item_id=t.item_id)
ORDER BY t.item_id LIMIT ?`, actor, actor, job.ID, after, limit+1)
	if err != nil {
		return nil, "", err
	}
	defer func() { _ = rows.Close() }()
	items := make([]MetadataReviewTarget, 0, limit)
	for rows.Next() {
		var item MetadataReviewTarget
		if err := rows.Scan(&item.ItemID, &item.Reason); err != nil {
			return nil, "", err
		}
		if len(items) == limit {
			return items, items[len(items)-1].ItemID, nil
		}
		items = append(items, item)
	}
	return items, "", rows.Err()
}

func (c *ScanCoordinator) RecoverMetadataJobs(ctx context.Context) error {
	_, err := coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (struct{}, error) {
		return struct{}{}, store.WithTx(ctx, func(tx *sql.Tx) error {
			_, err := tx.ExecContext(ctx, `UPDATE job SET status='interrupted',generation=generation+1,updated_at=max(updated_at,?)
WHERE kind=? AND status='running'`, time.Now().UnixMilli(), metadataJobKind)
			return err
		})
	})
	return err
}

// ClaimNextMetadataJob serializes a single worker under the process-wide scan
// coordinator. A delayed job becomes eligible again at its persisted retry time.
func (c *ScanCoordinator) ClaimNextMetadataJob(ctx context.Context) (MetadataJob, error) {
	return coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (MetadataJob, error) {
		var job MetadataJob
		err := store.WithTx(ctx, func(tx *sql.Tx) error {
			now := time.Now().UnixMilli()
			var err error
			job, err = readMetadataJob(tx.QueryRowContext(ctx, metadataJobSelection+` WHERE j.kind=? AND j.status IN ('queued','interrupted')
AND (j.retry_at IS NULL OR j.retry_at<=?) ORDER BY j.created_at,j.id LIMIT 1`, metadataJobKind, now))
			if err != nil {
				return err
			}
			job.Status = "running"
			job.Generation++
			job.Attempt++
			_, err = tx.ExecContext(ctx, `UPDATE job SET status='running',generation=?,attempt=attempt+1,retry_at=NULL,updated_at=max(updated_at,?) WHERE id=?`,
				job.Generation, now, job.ID)
			return err
		})
		return job, err
	})
}

func (c *ScanCoordinator) NextMetadataJobTarget(ctx context.Context, job MetadataJob) (MetadataJobTarget, error) {
	return coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (MetadataJobTarget, error) {
		var target MetadataJobTarget
		err := store.db.QueryRowContext(ctx, `SELECT i.id,i.kind,i.revision,s.structure_revision FROM metadata_job_target t
JOIN job j ON j.id=t.job_id AND j.status='running' AND j.generation=?
JOIN library_item i ON i.library_id=t.library_id AND i.id=t.item_id
JOIN item_structure s ON s.library_id=i.library_id AND s.item_id=i.id
WHERE t.job_id=? AND t.outcome='pending' ORDER BY t.item_id LIMIT 1`, job.Generation, job.ID).
			Scan(&target.ItemID, &target.Kind, &target.ItemRevision, &target.StructureRevision)
		if errors.Is(err, sql.ErrNoRows) {
			return target, ErrNotFound
		}
		return target, err
	})
}

func acknowledgeMetadataJobTarget(ctx context.Context, tx *sql.Tx, jobID string, generation int64, libraryID, itemID, outcome, reason string) error {
	result, err := tx.ExecContext(ctx, `UPDATE metadata_job_target SET outcome=?,reason=? WHERE job_id=? AND library_id=? AND item_id=? AND outcome='pending'
AND EXISTS (SELECT 1 FROM job WHERE id=? AND generation=? AND status='running')`, outcome, reason, jobID, libraryID, itemID, jobID, generation)
	if err := metadataJobUpdated(result, err); err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, `UPDATE job SET attempt=0,updated_at=max(updated_at,?) WHERE id=?`, time.Now().UnixMilli(), jobID)
	return err
}

func metadataJobUpdated(result sql.Result, err error) error {
	if err != nil {
		return err
	}
	count, err := result.RowsAffected()
	if err == nil && count != 1 {
		return ErrRevisionConflict
	}
	return err
}

func (c *ScanCoordinator) ResolveMetadataJobTarget(ctx context.Context, job MetadataJob, itemID, outcome, reason string) error {
	_, err := coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (struct{}, error) {
		return struct{}{}, store.WithTx(ctx, func(tx *sql.Tx) error {
			return acknowledgeMetadataJobTarget(ctx, tx, job.ID, job.Generation, job.LibraryID, itemID, outcome, reason)
		})
	})
	return err
}

func (c *ScanCoordinator) FinishMetadataJob(ctx context.Context, job MetadataJob, status, reason string, retryAt *time.Time) error {
	if status != "completed" && status != "queued" && status != "failed" || (status == "queued") != (retryAt != nil) {
		return ErrInvalid
	}
	var retry any
	if retryAt != nil {
		retry = retryAt.UnixMilli()
	}
	_, err := coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (struct{}, error) {
		return struct{}{}, store.WithTx(ctx, func(tx *sql.Tx) error {
			result, err := tx.ExecContext(ctx, `UPDATE job SET status=?,retry_at=?,updated_at=max(updated_at,?) WHERE id=? AND kind=? AND generation=? AND status='running'
AND (?!='completed' OR NOT EXISTS(SELECT 1 FROM metadata_job_target WHERE job_id=? AND outcome='pending'))`,
				status, retry, time.Now().UnixMilli(), job.ID, metadataJobKind, job.Generation, status, job.ID)
			if err := metadataJobUpdated(result, err); err != nil {
				return err
			}
			_, err = tx.ExecContext(ctx, `UPDATE metadata_job SET failure_code=? WHERE job_id=?`, reason, job.ID)
			return err
		})
	})
	return err
}

func (c *ScanCoordinator) CommitMetadataJobMatch(ctx context.Context, job MetadataJob, operationID string, request ProviderMatchRequest,
	language string, fetchedAt int64, document ProviderMatchDocument) (*ProviderMatchResult, error) {
	if request.LibraryID != job.LibraryID || !request.Automatic {
		return nil, ErrInvalid
	}
	request.job = &job
	return coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (*ProviderMatchResult, error) {
		return store.ConfirmProviderMatch(ctx, job.OwnerUserID, operationID, request, job.Authority, language, fetchedAt, document)
	})
}

func (c *ScanCoordinator) CheckMetadataJob(ctx context.Context, job MetadataJob) error {
	_, err := coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (struct{}, error) {
		var active bool
		err := store.db.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM job WHERE id=? AND generation=? AND status='running')`, job.ID, job.Generation).Scan(&active)
		if err == nil && !active {
			err = ErrRevisionConflict
		}
		return struct{}{}, err
	})
	return err
}

func (c *ScanCoordinator) CancelMetadataJob(ctx context.Context, actor uint, libraryID, jobID string, generation int64) error {
	_, err := coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (struct{}, error) {
		return struct{}{}, store.WithTx(ctx, func(tx *sql.Tx) error {
			library, err := activeCatalogLibrary(ctx, tx, actor, libraryID)
			if err != nil {
				return err
			}
			if library.Role != RoleOwner {
				return ErrForbidden
			}
			result, err := tx.ExecContext(ctx, `UPDATE job SET status='canceled',generation=generation+1,retry_at=NULL,updated_at=max(updated_at,?)
WHERE id=? AND library_id=? AND owner_user_id=? AND kind=? AND generation=? AND status IN ('queued','running','interrupted')`,
				time.Now().UnixMilli(), jobID, libraryID, actor, metadataJobKind, generation)
			return metadataJobUpdated(result, err)
		})
	})
	return err
}

// Retry retains every recorded outcome and uses the current explicit provider
// configuration. The original scan binding must still be valid.
func (c *ScanCoordinator) RetryMetadataJob(ctx context.Context, actor uint, job MetadataJob, generation int64) error {
	_, err := coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (struct{}, error) {
		return struct{}{}, store.WithTx(ctx, func(tx *sql.Tx) error {
			library, err := activeCatalogLibrary(ctx, tx, actor, job.LibraryID)
			if err != nil {
				return err
			}
			if library.Role != RoleOwner || job.OwnerUserID != actor {
				return ErrForbidden
			}
			scan, err := getScan(ctx, tx, actor, job.ScanID)
			if err != nil {
				return err
			}
			if scan.Status != "completed" || scan.Generation != job.ScanGeneration {
				return ErrRevisionConflict
			}
			if _, err := validateSourceBinding(ctx, tx, actor, scan.Binding); err != nil {
				return err
			}
			policy, err := readLibraryMetadataPolicy(ctx, tx, actor, job.LibraryID)
			if err != nil {
				return err
			}
			if !policy.OnlineEnabled() {
				return ErrRevisionConflict
			}
			authority, _, err := readProviderCredentialForUse(ctx, tx, actor, job.LibraryID, policy.ConnectionID)
			if err != nil {
				return err
			}
			result, err := tx.ExecContext(ctx, `UPDATE job SET status='queued',generation=generation+1,attempt=0,retry_at=NULL,updated_at=max(updated_at,?)
WHERE id=? AND library_id=? AND owner_user_id=? AND kind=? AND generation=? AND status IN ('failed','canceled')`,
				time.Now().UnixMilli(), job.ID, job.LibraryID, actor, metadataJobKind, generation)
			if err := metadataJobUpdated(result, err); err != nil {
				return err
			}
			_, err = tx.ExecContext(ctx, `UPDATE metadata_job SET connection_id=?,connection_revision=?,policy_revision=?,failure_code='' WHERE job_id=?`,
				authority.ConnectionID, authority.ConnectionRevision, authority.PolicyRevision, job.ID)
			return err
		})
	})
	return err
}
