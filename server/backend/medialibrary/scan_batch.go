package medialibrary

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"errors"
	"fmt"
	"slices"
	"strings"
)

type ScanBatchSource struct {
	BindingID        string `json:"bindingId"`
	ExpectedRevision int64  `json:"expectedRevision"`
}

const MaxScanBatchSources = 60

// EnqueueScanBatch queues the selected roots atomically. Both the ordinary
// jobs and the batch receipt commit together before any worker can claim them.
func (store *Store) EnqueueScanBatch(ctx context.Context, actor uint, libraryID, operationID string, rulesRevision int64, sources []ScanBatchSource) ([]ScanJob, error) {
	if len(sources) == 0 || len(sources) > MaxScanBatchSources || rulesRevision < 1 {
		return nil, ErrInvalid
	}
	sources = slices.Clone(sources)
	slices.SortFunc(sources, func(a, b ScanBatchSource) int { return strings.Compare(a.BindingID, b.BindingID) })
	for i, source := range sources {
		if source.BindingID == "" || source.ExpectedRevision < 1 || i > 0 && source.BindingID == sources[i-1].BindingID {
			return nil, ErrInvalid
		}
	}
	receipts, err := providerOperation(ctx, store, actor, "scan.enqueue.batch", operationID, struct {
		LibraryID     string
		RulesRevision int64
		Sources       []ScanBatchSource
	}{libraryID, rulesRevision, sources}, func(tx *sql.Tx) error {
		library, err := activeCatalogLibrary(ctx, tx, actor, libraryID)
		if err == nil && library.Role != RoleOwner {
			return ErrForbidden
		}
		return err
	}, func(tx *sql.Tx) ([]scanBatchReceipt, error) {
		result := make([]scanBatchReceipt, 0, len(sources))
		for _, source := range sources {
			binding, err := scanLibrarySource(tx.QueryRowContext(ctx, bindingSelection+" WHERE ls.binding_id=? AND ls.library_id=? AND l.owner_user_id=? AND s.owner_user_id=? AND s.kind='local'", source.BindingID, libraryID, actor, actor))
			if err != nil {
				return nil, err
			}
			if binding.Revision != source.ExpectedRevision {
				return nil, ErrRevisionConflict
			}
			ref := binding.Ref()
			job, err := readScan(tx.QueryRowContext(ctx, scanSelection+" WHERE sj.binding_id=? AND sj.library_id=? AND sj.active=1", binding.ID, libraryID))
			if err == nil {
				if !sameBinding(job.Binding, ref) || job.RulesRevision != rulesRevision || job.OwnerUserID != actor {
					return nil, ErrScanActive
				}
				result = append(result, scanBatchReceipt{source.BindingID, scanReceiptFromJob(&job.ScanJob)})
				continue
			}
			if !errors.Is(err, ErrNotFound) {
				return nil, err
			}
			digest, err := operationDigest([]any{actor, libraryID, operationID, source.BindingID})
			if err != nil {
				return nil, err
			}
			internalID := fmt.Sprintf("batch_%x", digest[:sha256.Size/2])
			queued, err := enqueueScanTx(ctx, tx, actor, ref, internalID, rulesRevision)
			if err != nil {
				return nil, err
			}
			result = append(result, scanBatchReceipt{source.BindingID, scanReceiptFromJob(queued)})
		}
		return result, nil
	})
	if err != nil {
		return nil, err
	}
	jobs := make([]ScanJob, 0, len(receipts))
	for _, receipt := range receipts {
		job := receipt.Scan.job()
		job.Binding.BindingID = receipt.BindingID
		jobs = append(jobs, *job)
	}
	return jobs, nil
}

type scanBatchReceipt struct {
	BindingID string
	Scan      scanReceipt
}
