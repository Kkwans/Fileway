package medialibrary

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"time"
)

// operationTx records only a digest and a sanitized acknowledgement, in the
// same transaction as the domain write. The original request, roots and config
// references are never persisted in the receipt.
func operationTx[T any](ctx context.Context, store *Store, actor uint, kind, operationID string, input any, action func(*sql.Tx) (T, error)) (T, error) {
	var result T
	if actor == 0 {
		return result, ErrForbidden
	}
	if !validOperationID(operationID) {
		return result, fmt.Errorf("%w: operation ID", ErrInvalid)
	}
	encoded, err := json.Marshal(input)
	if err != nil {
		return result, err
	}
	digest := sha256.Sum256(encoded)
	err = store.WithTx(ctx, func(tx *sql.Tx) error {
		var previousDigest []byte
		var previousResult string
		err := tx.QueryRowContext(ctx, `SELECT input_digest, result_json FROM operation_receipt WHERE actor_user_id = ? AND kind = ? AND operation_id = ?`, actor, kind, operationID).Scan(&previousDigest, &previousResult)
		if err == nil {
			if !bytes.Equal(previousDigest, digest[:]) {
				return ErrRevisionConflict
			}
			return json.Unmarshal([]byte(previousResult), &result)
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		result, err = action(tx)
		if err != nil {
			return err
		}
		ack, err := json.Marshal(result)
		if err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, `INSERT INTO operation_receipt(actor_user_id, kind, operation_id, input_digest, result_json, created_at) VALUES (?, ?, ?, ?, ?, ?)`, actor, kind, operationID, digest[:], string(ack), time.Now().UnixMilli())
		return err
	})
	if err != nil {
		var zero T
		return zero, err
	}
	return result, nil
}

func (store *Store) CreateLibraryWithOperation(ctx context.Context, actor uint, operationID, name string) (*Library, error) {
	return operationTx(ctx, store, actor, "library.create", operationID, struct{ Name string }{name}, func(tx *sql.Tx) (*Library, error) {
		return createLibraryTx(ctx, tx, actor, name)
	})
}

type sourceReceipt struct {
	ID                    string
	LibraryID             string
	SourceID              string
	AuthorizationRevision int64
	SourceCreatedAt       int64
	Revision              int64
	LibraryRevision       int64
	ProofVersion          int
	ProofEpoch            int64
	AttestedAt            int64
}

func (store *Store) CreateLocalSourceWithOperation(ctx context.Context, actor uint, libraryID string, expectedRevision int64, operationID, configurationRef string, root []byte) (*LibrarySource, error) {
	root = bytes.Clone(root)
	ack, err := operationTx(ctx, store, actor, "source.create.local", operationID, struct {
		LibraryID        string
		ExpectedRevision int64
		ConfigurationRef string
		Root             []byte
	}{libraryID, expectedRevision, configurationRef, root}, func(tx *sql.Tx) (sourceReceipt, error) {
		binding, err := createLocalSourceTx(ctx, tx, actor, libraryID, expectedRevision, configurationRef, root)
		if err != nil {
			return sourceReceipt{}, err
		}
		return sourceReceipt{ID: binding.ID, LibraryID: binding.LibraryID, SourceID: binding.Source.ID, AuthorizationRevision: binding.Source.AuthorizationRevision, SourceCreatedAt: binding.Source.CreatedAt, Revision: binding.Revision, LibraryRevision: binding.LibraryRevision}, nil
	})
	if err != nil {
		return nil, err
	}
	return &LibrarySource{ID: ack.ID, LibraryID: ack.LibraryID, Source: LocalSource{ID: ack.SourceID, OwnerUserID: actor, ConfigurationRef: configurationRef, AuthorizationRevision: ack.AuthorizationRevision, CreatedAt: ack.SourceCreatedAt}, RootLocator: bytes.Clone(root), Revision: ack.Revision, LibraryRevision: ack.LibraryRevision}, nil
}

func (store *Store) UpdateLibraryWithOperation(ctx context.Context, actor uint, id string, expectedRevision int64, operationID, name string) (*Library, error) {
	normalized, err := libraryName(name)
	if err != nil {
		return nil, err
	}
	return operationTx(ctx, store, actor, "library.update", operationID, struct {
		ID       string
		Revision int64
		Name     string
	}{id, expectedRevision, name}, func(tx *sql.Tx) (*Library, error) {
		return changeLibraryTx(ctx, tx, actor, id, expectedRevision, "library.updated", updateLibraryAction(normalized))
	})
}

func (store *Store) ArchiveLibraryWithOperation(ctx context.Context, actor uint, id string, expectedRevision int64, operationID string) (*Library, error) {
	return operationTx(ctx, store, actor, "library.archive", operationID, struct {
		ID       string
		Revision int64
	}{id, expectedRevision}, func(tx *sql.Tx) (*Library, error) {
		return changeLibraryTx(ctx, tx, actor, id, expectedRevision, "library.archived", archiveLibraryAction)
	})
}

func (store *Store) RestoreLibraryWithOperation(ctx context.Context, actor uint, id string, expectedRevision int64, operationID string) (*Library, error) {
	return operationTx(ctx, store, actor, "library.restore", operationID, struct {
		ID       string
		Revision int64
	}{id, expectedRevision}, func(tx *sql.Tx) (*Library, error) {
		return changeLibraryTx(ctx, tx, actor, id, expectedRevision, "library.restored", restoreLibraryAction)
	})
}

func (store *Store) GrantMemberWithOperation(ctx context.Context, actor uint, id string, expectedRevision int64, operationID string, recipient uint, role MemberRole) (*MemberGrant, error) {
	return operationTx(ctx, store, actor, "member.grant", operationID, struct {
		ID        string
		Revision  int64
		Recipient uint
		Role      MemberRole
	}{id, expectedRevision, recipient, role}, func(tx *sql.Tx) (*MemberGrant, error) {
		return changeMemberTx(ctx, tx, actor, id, expectedRevision, recipient, role, false)
	})
}

func (store *Store) RevokeMemberWithOperation(ctx context.Context, actor uint, id string, expectedRevision int64, operationID string, recipient uint) (*MemberGrant, error) {
	return operationTx(ctx, store, actor, "member.revoke", operationID, struct {
		ID        string
		Revision  int64
		Recipient uint
	}{id, expectedRevision, recipient}, func(tx *sql.Tx) (*MemberGrant, error) {
		return changeMemberTx(ctx, tx, actor, id, expectedRevision, recipient, "", true)
	})
}

// scanReceipt deliberately omits source paths, configuration and object keys.
// A scheduler reloads authoritative binding state before claiming a job.
type scanReceipt struct {
	ID            string
	LibraryID     string
	OwnerUserID   uint
	Status        string
	ErrorCode     string
	Generation    int64
	Attempt       int64
	RulesRevision int64
	Version       int
	Sequence      int64
	Processed     int64
	Phase         string
	CreatedAt     int64
	UpdatedAt     int64
}

func scanReceiptFromJob(job *ScanJob) scanReceipt {
	return scanReceipt{job.ID, job.LibraryID, job.OwnerUserID, job.Status, job.ErrorCode, job.Generation, job.Attempt, job.RulesRevision,
		job.Checkpoint.Version, job.Checkpoint.Sequence, job.Checkpoint.Processed, job.Checkpoint.Phase, job.CreatedAt, job.UpdatedAt}
}

func (ack scanReceipt) job() *ScanJob {
	return &ScanJob{ID: ack.ID, LibraryID: ack.LibraryID, OwnerUserID: ack.OwnerUserID, Status: ack.Status, ErrorCode: ack.ErrorCode,
		Generation: ack.Generation, Attempt: ack.Attempt, RulesRevision: ack.RulesRevision,
		Checkpoint: ScanCheckpoint{Version: ack.Version, Sequence: ack.Sequence, Processed: ack.Processed, Phase: ack.Phase}, CreatedAt: ack.CreatedAt, UpdatedAt: ack.UpdatedAt}
}

func (store *Store) EnqueueScanByBindingID(ctx context.Context, actor uint, libraryID, bindingID string, expectedBindingRevision int64, operationID string, rulesRevision int64) (*ScanJob, error) {
	ack, err := operationTx(ctx, store, actor, "scan.enqueue", operationID, struct {
		LibraryID, BindingID string
		BindingRevision      int64
		RulesRevision        int64
	}{libraryID, bindingID, expectedBindingRevision, rulesRevision}, func(tx *sql.Tx) (scanReceipt, error) {
		binding, err := scanLibrarySource(tx.QueryRowContext(ctx, bindingSelection+" WHERE ls.binding_id = ? AND ls.library_id = ? AND l.owner_user_id = ? AND s.owner_user_id = ? AND s.kind = 'local'", bindingID, libraryID, actor, actor))
		if err != nil {
			return scanReceipt{}, err
		}
		if binding.Revision != expectedBindingRevision || expectedBindingRevision < 1 {
			return scanReceipt{}, ErrRevisionConflict
		}
		job, err := enqueueScanTx(ctx, tx, actor, binding.Ref(), operationID, rulesRevision)
		if err != nil {
			return scanReceipt{}, err
		}
		return scanReceiptFromJob(job), nil
	})
	if err != nil {
		return nil, err
	}
	return ack.job(), nil
}

func (store *Store) CancelScanWithOperation(ctx context.Context, actor uint, jobID string, expectedGeneration int64, operationID string) (*ScanJob, error) {
	ack, err := operationTx(ctx, store, actor, "scan.cancel", operationID, struct {
		ID         string
		Generation int64
	}{jobID, expectedGeneration}, func(tx *sql.Tx) (scanReceipt, error) {
		job, err := cancelScanTx(ctx, tx, actor, jobID, expectedGeneration)
		if err != nil {
			return scanReceipt{}, err
		}
		return scanReceiptFromJob(job), nil
	})
	if err != nil {
		return nil, err
	}
	return ack.job(), nil
}

// Retry creates a fresh queued attempt; an interrupted predecessor gives up
// its active binding slot atomically. The caller must first reauthorize and
// safely open the current source root. Receipt replay returns the original ack.
func (store *Store) RetryScanWithOperation(ctx context.Context, actor uint, jobID string, expectedGeneration int64, operationID string) (*ScanJob, error) {
	ack, err := operationTx(ctx, store, actor, "scan.retry", operationID, struct {
		ID         string
		Generation int64
	}{jobID, expectedGeneration}, func(tx *sql.Tx) (scanReceipt, error) {
		previous, err := getScan(ctx, tx, actor, jobID)
		if err != nil {
			return scanReceipt{}, err
		}
		if previous.Generation != expectedGeneration || (previous.Status != "failed" && previous.Status != "canceled" && previous.Status != "interrupted") {
			return scanReceipt{}, ErrScanState
		}
		binding, err := scanLibrarySource(tx.QueryRowContext(ctx, bindingSelection+" WHERE ls.library_id = ? AND ls.source_id = ? AND ls.root_locator = ? AND l.owner_user_id = ? AND s.owner_user_id = ? AND s.kind = 'local'", previous.LibraryID, previous.Binding.SourceID, previous.Binding.RootLocator, actor, actor))
		if err != nil {
			return scanReceipt{}, err
		}
		if previous.Status == "interrupted" {
			if !previous.active {
				return scanReceipt{}, ErrScanState
			}
			if _, err := cancelScanTx(ctx, tx, actor, jobID, previous.Generation); err != nil {
				return scanReceipt{}, err
			}
		}
		internalID, err := randomID("retry_")
		if err != nil {
			return scanReceipt{}, err
		}
		job, err := enqueueScanTx(ctx, tx, actor, binding.Ref(), internalID, previous.RulesRevision)
		if err != nil {
			return scanReceipt{}, err
		}
		return scanReceiptFromJob(job), nil
	})
	if err != nil {
		return nil, err
	}
	return ack.job(), nil
}
