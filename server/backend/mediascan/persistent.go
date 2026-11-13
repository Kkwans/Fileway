package mediascan

import (
	"bytes"
	"context"
	"errors"
	"io/fs"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediasource"
)

// LocalRulesRevision identifies the scanner's extension, naming and sidecar
// rules. Change it whenever those rules change; a claimed job must never be
// resumed under a different rule set while retaining its old revision label.
const LocalRulesRevision int64 = 2

var ErrScanRulesUnavailable = errors.New("media scan rules revision is unavailable")

// ResolveOwnerAuthority must freshly resolve the stored binding's owner,
// configuration, workspace proof and administrator policy on every call.
// Configuration references and credentials must never be logged.
type ResolveOwnerAuthority func(ctx context.Context, binding *medialibrary.LibrarySource) (mediasource.LocalAuthority, error)

// Run executes one already-claimed scan lease. Claiming or recovering a job is
// the caller's responsibility. Every claim starts enumeration from the root;
// LastObjectKey is evidence of progress, not a reusable filesystem cursor.
// NFO parse status is persisted, while importing parsed fields is a later step.
func Run(ctx context.Context, actor uint, store *medialibrary.Store, coord *medialibrary.ScanCoordinator, lease medialibrary.ScanLease, resolve ResolveOwnerAuthority, options Options) (result Result, resultErr error) {
	if store == nil || coord == nil || resolve == nil {
		return result, fs.ErrInvalid
	}
	var checkpoint medialibrary.ScanCheckpoint
	shouldFinish := true
	defer func() {
		if resultErr == nil || !shouldFinish {
			return
		}
		// Persist an interrupted/failed outcome even when the work context was
		// canceled. A separately canceled lease is already fenced by generation.
		finishCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 10*time.Second)
		defer cancel()
		if err := coord.ValidateFor(finishCtx, store); err != nil {
			resultErr = errors.Join(resultErr, err)
			return
		}
		current, getErr := coord.GetScan(finishCtx, actor, lease.JobID)
		if getErr != nil {
			resultErr = errors.Join(resultErr, getErr)
			return
		}
		if current.Status != "running" || current.Generation != lease.Generation {
			return // Another lifecycle action has already fenced this lease.
		}
		status := "failed"
		errorCode := scanErrorCode(resultErr)
		if ctx.Err() != nil {
			status = "interrupted"
			errorCode = ""
		}
		// A canceled/failed commit can have an unknown outcome. Read the durable
		// checkpoint instead of assuming the in-memory sequence was committed or
		// rolled back. A later claim always starts over from the root.
		_, finishErr := coord.FinishScan(finishCtx, actor, lease, medialibrary.ScanFinish{Status: status, ExpectedSequence: current.Checkpoint.Sequence, ErrorCode: errorCode})
		if finishErr == nil {
			return
		}
		current, getErr = coord.GetScan(finishCtx, actor, lease.JobID)
		if getErr == nil {
			if current.Status != "running" || current.Generation != lease.Generation {
				return
			}
			if errors.Is(finishErr, medialibrary.ErrRevisionConflict) {
				_, finishErr = coord.FinishScan(finishCtx, actor, lease, medialibrary.ScanFinish{Status: status, ExpectedSequence: current.Checkpoint.Sequence, ErrorCode: errorCode})
				if finishErr == nil {
					return
				}
			}
		}
		resultErr = errors.Join(resultErr, finishErr)
	}()
	options, err := normalizeOptions(options)
	if err != nil {
		return result, err
	}
	if err := coord.ValidateFor(ctx, store); err != nil {
		return result, err
	}
	job, err := coord.GetScan(ctx, actor, lease.JobID)
	if err != nil {
		return result, err
	}
	if job.Status != "running" || job.Generation != lease.Generation {
		return result, medialibrary.ErrRevisionConflict
	}
	if job.Checkpoint.Phase != "enumerate" {
		// A duplicate invocation must not terminate a lease already reconciling.
		shouldFinish = false
		return result, medialibrary.ErrScanState
	}
	checkpoint = job.Checkpoint
	if job.RulesRevision != LocalRulesRevision {
		return result, ErrScanRulesUnavailable
	}
	// Validation precedes both resolving the account and opening the root. The
	// returned binding supplies the authoritative owner and configuration ref.
	binding, err := coord.ValidateScanLease(ctx, actor, lease)
	if err != nil {
		return result, err
	}
	boundResolve := func(ctx context.Context) (mediasource.LocalAuthority, error) {
		current, err := coord.ValidateScanLease(ctx, actor, lease)
		if err != nil {
			return mediasource.LocalAuthority{}, err
		}
		if current.Source.OwnerUserID != actor || current.Source.ConfigurationRef != binding.Source.ConfigurationRef {
			return mediasource.LocalAuthority{}, medialibrary.ErrRevisionConflict
		}
		return resolve(ctx, current)
	}
	sink := &persistentSink{coord: coord, actor: actor, lease: lease, checkpoint: &checkpoint}
	result, err = ScanLocal(ctx, actor, binding, boundResolve, sink, options)
	if err != nil {
		return result, err
	}
	if err := coord.CompleteScanEnumeration(ctx, actor, lease, checkpoint.Sequence); err != nil {
		return result, err
	}
	checkpoint.Phase = "reconcile"
	source, err := mediasource.NewLocal(string(binding.RootLocator), boundResolve)
	if err != nil {
		return result, err
	}
	w := walker{source: source, binding: lease.Binding, actor: actor, options: options}
	after := ""
	for {
		page, err := coord.ListScanMissingCandidates(ctx, actor, lease, after, 100)
		if err != nil {
			return result, err
		}
		confirmations := make([]medialibrary.ScanMissingConfirmation, 0, len(page.Items))
		for _, candidate := range page.Items {
			missing, err := w.missingCandidate(ctx, candidate.Locator)
			if err != nil {
				return result, err
			}
			if !missing {
				// The object may have moved or been replaced while enumerating. A
				// fresh whole-root scan is safer than asserting absence.
				return result, ErrCandidateUncertain
			}
			confirmations = append(confirmations, medialibrary.ScanMissingConfirmation{
				SourceFileID: candidate.SourceFileID, Locator: append([]byte(nil), candidate.Locator...), FileRevision: candidate.FileRevision,
				SeenJobID: candidate.SeenJobID, SeenGeneration: candidate.SeenGeneration,
			})
		}
		if len(confirmations) != 0 {
			next := checkpoint
			next.Sequence++
			if err := coord.CommitScanMissing(ctx, actor, lease, confirmations, next); err != nil {
				return result, err
			}
			checkpoint = next
		}
		if page.NextCursor == "" {
			break
		}
		if page.NextCursor == after {
			return result, fs.ErrInvalid
		}
		after = page.NextCursor
	}
	if err := coord.CompleteScanReconciliation(ctx, actor, lease, checkpoint.Sequence); err != nil {
		return result, err
	}
	checkpoint.Phase = "ready"
	// Re-resolve the owner and reopen the exact bound root just before the
	// transaction that publishes staged missing observations.
	root, err := source.Open(ctx, "", true)
	if err != nil {
		return result, err
	}
	rootInfo, _, stateErr := root.State()
	closeErr := root.Close()
	if stateErr != nil || closeErr != nil {
		return result, errors.Join(stateErr, closeErr)
	}
	if rootInfo != nil && files.IsPrivateMediaFile(rootInfo) {
		return result, fs.ErrPermission
	}
	if err := ctx.Err(); err != nil {
		return result, err
	}
	if _, err := coord.FinishScan(ctx, actor, lease, medialibrary.ScanFinish{Status: "completed", ExpectedSequence: checkpoint.Sequence}); err != nil {
		return result, err
	}
	return result, nil
}

func scanErrorCode(err error) string {
	switch {
	case errors.Is(err, ErrScanRulesUnavailable):
		return medialibrary.ScanErrorUnsupportedRules
	case errors.Is(err, ErrBindingUnverified), errors.Is(err, ErrBindingChanged):
		return medialibrary.ScanErrorSourceChanged
	case errors.Is(err, fs.ErrPermission), errors.Is(err, medialibrary.ErrForbidden), errors.Is(err, medialibrary.ErrArchived):
		return medialibrary.ScanErrorPermissionDenied
	case errors.Is(err, fs.ErrNotExist), errors.Is(err, medialibrary.ErrNotFound), errors.Is(err, files.ErrSubtitleScopeUnavailable):
		return medialibrary.ScanErrorRootUnavailable
	case errors.Is(err, ErrDirectoryChanged), errors.Is(err, ErrFileChanged), errors.Is(err, ErrCandidateUncertain), errors.Is(err, medialibrary.ErrRevisionConflict):
		return medialibrary.ScanErrorSourceChanged
	default:
		return medialibrary.ScanErrorScanFailed
	}
}

type persistentSink struct {
	coord      *medialibrary.ScanCoordinator
	actor      uint
	lease      medialibrary.ScanLease
	checkpoint *medialibrary.ScanCheckpoint
}

func (s *persistentSink) LookupRevision(ctx context.Context, key []byte) (int64, error) {
	file, err := s.coord.GetSourceFile(ctx, s.actor, s.lease.Binding.SourceID, key)
	if errors.Is(err, medialibrary.ErrNotFound) {
		return 0, nil
	}
	if err != nil {
		return 0, err
	}
	return file.Revision, nil
}

func (s *persistentSink) Observe(ctx context.Context, observation Observation) error {
	if observation.OwnerUserID != s.actor || observation.Binding.BindingID != s.lease.Binding.BindingID || observation.Binding.ProofEpoch != s.lease.Binding.ProofEpoch || observation.Binding.LibraryID != s.lease.Binding.LibraryID || observation.Binding.SourceID != s.lease.Binding.SourceID ||
		observation.Binding.BindingRevision != s.lease.Binding.BindingRevision || observation.Binding.AuthorizationRevision != s.lease.Binding.AuthorizationRevision ||
		!bytes.Equal(observation.Binding.RootLocator, s.lease.Binding.RootLocator) {
		return medialibrary.ErrRevisionConflict
	}
	next := *s.checkpoint
	next.Sequence++
	next.Processed++
	next.LastObjectKey = append([]byte(nil), observation.ObjectKey...)
	next.Phase = "enumerate"
	_, err := s.coord.CommitScanObservation(ctx, s.actor, s.lease, medialibrary.ScanObservation{
		ObjectKey: observation.ObjectKey, Identity: string(observation.Identity), Locator: observation.Locator,
		ExpectedRevision: observation.ExpectedRevision, SourceVersion: observation.SourceVersion, SizeBytes: observation.SizeBytes, InitialTitle: observation.InitialTitle, NFOStatus: string(observation.NFOStatus), NFO: observation.NFO,
	}, next)
	if err == nil {
		*s.checkpoint = next
	}
	return err
}
