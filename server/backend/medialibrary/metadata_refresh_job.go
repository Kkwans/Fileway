package medialibrary

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

const metadataRefreshJobKind = "library.metadata.refresh_unlocked"

var ErrMetadataRefreshActive = errors.New("a metadata refresh is already active for this library")

type MetadataRefreshJob struct {
	ID, LibraryID, Status, Language, Region, Mode, FailureCode string
	OwnerUserID                                                uint
	Generation, Attempt, RetryAt                               int64
	Authority                                                  ProviderUseAuthority
	Total, Refreshed, AlreadyCurrent, Preserved                int64
	SourceChanged, Failed, Pending, Undone                     int64
}

type MetadataRefreshTarget struct {
	LibraryID, ItemID, Kind         string
	ItemRevision, StructureRevision int64
	OperationID                     string
}

const metadataRefreshJobSelection = `SELECT j.id,j.library_id,j.status,m.failure_code,j.owner_user_id,j.generation,j.attempt,
COALESCE(j.retry_at,0),m.connection_id,m.connection_revision,m.policy_revision,m.language,m.region,m.mode
FROM job j JOIN metadata_refresh_job m ON m.job_id=j.id`

func readMetadataRefreshJob(row rowScanner) (MetadataRefreshJob, error) {
	var job MetadataRefreshJob
	err := row.Scan(&job.ID, &job.LibraryID, &job.Status, &job.FailureCode, &job.OwnerUserID, &job.Generation, &job.Attempt,
		&job.RetryAt, &job.Authority.ConnectionID, &job.Authority.ConnectionRevision, &job.Authority.PolicyRevision,
		&job.Language, &job.Region, &job.Mode)
	if errors.Is(err, sql.ErrNoRows) {
		return job, ErrNotFound
	}
	return job, err
}

func readMetadataRefreshProgress(ctx context.Context, tx *sql.Tx, job MetadataRefreshJob) (MetadataRefreshJob, error) {
	err := tx.QueryRowContext(ctx, `SELECT count(*),count(*) FILTER (WHERE outcome='refreshed'),
	count(*) FILTER (WHERE outcome='already_current'),count(*) FILTER (WHERE outcome='preserved'),
	count(*) FILTER (WHERE outcome='source_changed'),count(*) FILTER (WHERE outcome='failed'),
	count(*) FILTER (WHERE outcome='pending'),count(*) FILTER (WHERE outcome='undone')
	FROM metadata_refresh_target WHERE job_id=?`, job.ID).Scan(&job.Total, &job.Refreshed, &job.AlreadyCurrent,
		&job.Preserved, &job.SourceChanged, &job.Failed, &job.Pending, &job.Undone)
	return job, err
}

func (store *Store) EnqueueMetadataRefreshJob(ctx context.Context, actor uint, operationID, libraryID string, expectedPolicyRevision int64) (MetadataRefreshJob, error) {
	input := struct {
		LibraryID      string `json:"libraryId"`
		ExpectedPolicy int64  `json:"expectedPolicyRevision"`
		Mode           string `json:"mode"`
	}{libraryID, expectedPolicyRevision, "refresh_unlocked"}
	return providerOperation(ctx, store, actor, "metadata.refresh.enqueue", operationID, input, func(tx *sql.Tx) error {
		library, err := activeCatalogLibrary(ctx, tx, actor, libraryID)
		if err != nil {
			return err
		}
		if library.Role != RoleOwner {
			return ErrForbidden
		}
		return nil
	}, func(tx *sql.Tx) (MetadataRefreshJob, error) {
		var job MetadataRefreshJob
		policy, err := readLibraryMetadataPolicy(ctx, tx, actor, libraryID)
		if err != nil {
			return job, err
		}
		if !policy.OnlineEnabled() || policy.Revision != expectedPolicyRevision {
			return job, ErrRevisionConflict
		}
		var active bool
		if err := tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM job WHERE library_id=? AND kind=? AND status IN ('queued','running','interrupted'))`,
			libraryID, metadataRefreshJobKind).Scan(&active); err != nil {
			return job, err
		}
		if active {
			return job, ErrMetadataRefreshActive
		}
		authority, _, err := readProviderCredentialForUse(ctx, tx, actor, libraryID, policy.ConnectionID)
		if err != nil {
			return job, err
		}
		id, err := randomID("job_")
		if err != nil {
			return job, err
		}
		now := time.Now().UnixMilli()
		if _, err := tx.ExecContext(ctx, `INSERT INTO job(id,library_id,owner_user_id,kind,status,created_at,updated_at)
VALUES(?,?,?,?,'queued',?,?)`, id, libraryID, actor, metadataRefreshJobKind, now, now); err != nil {
			return job, err
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO metadata_refresh_job(job_id,connection_id,connection_revision,policy_revision,language,region,mode)
VALUES(?,?,?,?,?,?,?)`, id, authority.ConnectionID, authority.ConnectionRevision, authority.PolicyRevision, policy.Language, policy.Region, policy.Mode); err != nil {
			return job, err
		}
		result, err := tx.ExecContext(ctx, `INSERT INTO metadata_refresh_target(job_id,library_id,item_id,item_revision,structure_revision,operation_id)
SELECT ?,pm.library_id,pm.item_id,i.revision,st.structure_revision,? || '_' || pm.item_id
FROM item_provider_match pm
JOIN library_item i ON i.library_id=pm.library_id AND i.id=pm.item_id
JOIN item_structure st ON st.library_id=i.library_id AND st.item_id=i.id AND st.superseded_by IS NULL
WHERE pm.library_id=? AND pm.language<>?`, id, id, libraryID, policy.Language)
		if err != nil {
			return job, err
		}
		count, err := result.RowsAffected()
		if err != nil {
			return job, err
		}
		if count == 0 {
			if _, err := tx.ExecContext(ctx, `UPDATE job SET status='completed',updated_at=? WHERE id=?`, now, id); err != nil {
				return job, err
			}
		}
		job, err = readMetadataRefreshJob(tx.QueryRowContext(ctx, metadataRefreshJobSelection+` WHERE j.id=?`, id))
		if err != nil {
			return job, err
		}
		return readMetadataRefreshProgress(ctx, tx, job)
	})
}

func (store *Store) MetadataRefreshJob(ctx context.Context, actor uint, libraryID, jobID string) (MetadataRefreshJob, error) {
	library, err := activeCatalogLibrary(ctx, store.db, actor, libraryID)
	if err != nil {
		return MetadataRefreshJob{}, err
	}
	if library.Role != RoleOwner {
		return MetadataRefreshJob{}, ErrForbidden
	}
	job, err := readMetadataRefreshJob(store.db.QueryRowContext(ctx, metadataRefreshJobSelection+` WHERE j.id=? AND j.library_id=? AND j.owner_user_id=?`, jobID, libraryID, actor))
	if err != nil {
		return job, err
	}
	tx, err := store.db.BeginTx(ctx, nil)
	if err != nil {
		return job, err
	}
	defer tx.Rollback()
	return readMetadataRefreshProgress(ctx, tx, job)
}

func (c *ScanCoordinator) ClaimNextMetadataRefreshJob(ctx context.Context) (MetadataRefreshJob, error) {
	return coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (MetadataRefreshJob, error) {
		var job MetadataRefreshJob
		err := store.WithTx(ctx, func(tx *sql.Tx) error {
			now := time.Now().UnixMilli()
			var err error
			job, err = readMetadataRefreshJob(tx.QueryRowContext(ctx, metadataRefreshJobSelection+` WHERE j.kind=? AND j.status IN ('queued','interrupted')
AND (j.retry_at IS NULL OR j.retry_at<=?) ORDER BY j.created_at,j.id LIMIT 1`, metadataRefreshJobKind, now))
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

func (c *ScanCoordinator) NextMetadataRefreshJobTarget(ctx context.Context, job MetadataRefreshJob) (MetadataRefreshTarget, error) {
	return coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (MetadataRefreshTarget, error) {
		var target MetadataRefreshTarget
		err := store.db.QueryRowContext(ctx, `SELECT t.library_id,t.item_id,i.kind,t.item_revision,t.structure_revision,t.operation_id
FROM metadata_refresh_target t JOIN job j ON j.id=t.job_id AND j.status='running' AND j.generation=?
JOIN library_item i ON i.library_id=t.library_id AND i.id=t.item_id
WHERE t.job_id=? AND t.outcome='pending' ORDER BY t.item_id LIMIT 1`, job.Generation, job.ID).
			Scan(&target.LibraryID, &target.ItemID, &target.Kind, &target.ItemRevision, &target.StructureRevision, &target.OperationID)
		if errors.Is(err, sql.ErrNoRows) {
			return target, ErrNotFound
		}
		return target, err
	})
}

func acknowledgeMetadataRefreshTargetTx(ctx context.Context, tx *sql.Tx, job *MetadataRefreshJob, request ProviderMatchRequest, revision int64) error {
	result, err := tx.ExecContext(ctx, `UPDATE metadata_refresh_target SET outcome='refreshed',reason='',
applied_match_revision=?,applied_item_revision=?
WHERE job_id=? AND library_id=? AND item_id=? AND operation_id=? AND item_revision=? AND structure_revision=? AND outcome='pending'
AND EXISTS(SELECT 1 FROM job WHERE id=? AND generation=? AND status='running' AND kind=?)`,
		revision, revision, job.ID, request.LibraryID, request.ItemID, request.refreshOperationID,
		request.ItemRevision, request.StructureRevision, job.ID, job.Generation, metadataRefreshJobKind)
	return metadataJobUpdated(result, err)
}

func (c *ScanCoordinator) CommitMetadataRefreshTarget(ctx context.Context, job MetadataRefreshJob, target MetadataRefreshTarget,
	request ProviderMatchRequest, language string, fetchedAt int64, document ProviderMatchDocument, audit ProviderMatchAudit) (*ProviderMatchResult, error) {
	if target.LibraryID != job.LibraryID || target.OperationID == "" || request.LibraryID != target.LibraryID || request.ItemID != target.ItemID ||
		request.ItemRevision != target.ItemRevision || request.StructureRevision != target.StructureRevision {
		return nil, ErrInvalid
	}
	request.refreshJob = &job
	request.refreshOperationID = target.OperationID
	return coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (*ProviderMatchResult, error) {
		return store.ConfirmProviderMatch(ctx, job.OwnerUserID, target.OperationID, request, job.Authority, language, fetchedAt, document, audit)
	})
}

func (c *ScanCoordinator) ResolveMetadataRefreshJobTarget(ctx context.Context, job MetadataRefreshJob, itemID, outcome, reason string) error {
	if outcome != "refreshed" && outcome != "already_current" && outcome != "preserved" && outcome != "source_changed" && outcome != "failed" {
		return ErrInvalid
	}
	_, err := coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (struct{}, error) {
		return struct{}{}, store.WithTx(ctx, func(tx *sql.Tx) error {
			result, err := tx.ExecContext(ctx, `UPDATE metadata_refresh_target SET outcome=?,reason=?
WHERE job_id=? AND library_id=? AND item_id=? AND outcome='pending'
AND EXISTS(SELECT 1 FROM job WHERE id=? AND generation=? AND status='running')`, outcome, reason, job.ID, job.LibraryID, itemID, job.ID, job.Generation)
			if err := metadataJobUpdated(result, err); err != nil {
				return err
			}
			_, err = tx.ExecContext(ctx, `UPDATE job SET attempt=0,updated_at=max(updated_at,?) WHERE id=?`, time.Now().UnixMilli(), job.ID)
			return err
		})
	})
	return err
}

func (c *ScanCoordinator) FinishMetadataRefreshJob(ctx context.Context, job MetadataRefreshJob, status, reason string, retryAt *time.Time) error {
	if status != "completed" && status != "queued" && status != "failed" || (status == "queued") != (retryAt != nil) {
		return ErrInvalid
	}
	var retry any
	if retryAt != nil {
		retry = retryAt.UnixMilli()
	}
	_, err := coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (struct{}, error) {
		return struct{}{}, store.WithTx(ctx, func(tx *sql.Tx) error {
			result, err := tx.ExecContext(ctx, `UPDATE job SET status=?,retry_at=?,updated_at=max(updated_at,?)
WHERE id=? AND kind=? AND generation=? AND status='running'
AND (?!='completed' OR NOT EXISTS(SELECT 1 FROM metadata_refresh_target WHERE job_id=? AND outcome='pending'))`,
				status, retry, time.Now().UnixMilli(), job.ID, metadataRefreshJobKind, job.Generation, status, job.ID)
			if err := metadataJobUpdated(result, err); err != nil {
				return err
			}
			_, err = tx.ExecContext(ctx, `UPDATE metadata_refresh_job SET failure_code=? WHERE job_id=?`, reason, job.ID)
			return err
		})
	})
	return err
}

func (c *ScanCoordinator) CheckMetadataRefreshJob(ctx context.Context, job MetadataRefreshJob) error {
	_, err := coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (struct{}, error) {
		var active bool
		err := store.db.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM job WHERE id=? AND generation=? AND status='running' AND kind=?)`,
			job.ID, job.Generation, metadataRefreshJobKind).Scan(&active)
		if err == nil && !active {
			err = ErrRevisionConflict
		}
		return struct{}{}, err
	})
	return err
}

func (c *ScanCoordinator) CancelMetadataRefreshJob(ctx context.Context, actor uint, libraryID, jobID string, generation int64) error {
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
				time.Now().UnixMilli(), jobID, libraryID, actor, metadataRefreshJobKind, generation)
			return metadataJobUpdated(result, err)
		})
	})
	return err
}

func (c *ScanCoordinator) RetryMetadataRefreshJob(ctx context.Context, actor uint, job MetadataRefreshJob, generation int64) error {
	_, err := coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (struct{}, error) {
		return struct{}{}, store.WithTx(ctx, func(tx *sql.Tx) error {
			library, err := activeCatalogLibrary(ctx, tx, actor, job.LibraryID)
			if err != nil {
				return err
			}
			if library.Role != RoleOwner || job.OwnerUserID != actor {
				return ErrForbidden
			}
			policy, err := readLibraryMetadataPolicy(ctx, tx, actor, job.LibraryID)
			if err != nil {
				return err
			}
			authority, _, err := readProviderCredentialForUse(ctx, tx, actor, job.LibraryID, policy.ConnectionID)
			if err != nil {
				return err
			}
			if !policy.OnlineEnabled() || *authority != job.Authority || policy.Language != job.Language || policy.Region != job.Region || policy.Mode != job.Mode {
				return ErrRevisionConflict
			}
			result, err := tx.ExecContext(ctx, `UPDATE job SET status='queued',generation=generation+1,attempt=0,retry_at=NULL,updated_at=max(updated_at,?)
WHERE id=? AND library_id=? AND owner_user_id=? AND kind=? AND generation=? AND status IN ('failed','canceled','interrupted')`,
				time.Now().UnixMilli(), job.ID, job.LibraryID, actor, metadataRefreshJobKind, generation)
			if err := metadataJobUpdated(result, err); err != nil {
				return err
			}
			_, err = tx.ExecContext(ctx, `UPDATE metadata_refresh_target SET outcome='pending',reason='',applied_match_revision=0,applied_item_revision=0
WHERE job_id=? AND outcome='failed'`, job.ID)
			if err != nil {
				return err
			}
			_, err = tx.ExecContext(ctx, `UPDATE metadata_refresh_job SET failure_code='' WHERE job_id=?`, job.ID)
			return err
		})
	})
	return err
}
