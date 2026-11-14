package medialibrary

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"time"
)

var ErrDomainRebindRequired = errors.New("binding domain requires explicit rebind")

// DomainObservation contains only digests of authorized mapping and handle
// observations. They are private evidence, not credentials or a grant. A
// live domain coordinator must derive them from current owner authority and
// the exact safely opened root, and retain the handle behind AnchorDigest.
type DomainObservation struct {
	MappingDigest []byte `json:"-"`
	RootEvidence  []byte `json:"-"`
	MountEvidence []byte `json:"-"`
	AnchorDigest  []byte `json:"-"`
	EvidenceKind  string `json:"-"`
}

// BindingDomain is private catalog state. The selected root is the only domain
// supported by this slice; a mount epoch is distinct from a binding proof epoch.
type BindingDomain struct {
	ID                 string `json:"-"`
	BindingID          string `json:"-"`
	ProofEpoch         int64  `json:"-"`
	RelativePrefix     []byte `json:"-"`
	MappingDigest      []byte `json:"-"`
	RootEvidence       []byte `json:"-"`
	StrongRootEvidence []byte `json:"-"`
	MountEvidence      []byte `json:"-"`
	anchorDigest       []byte
	EvidenceKind       string `json:"-"`
	MountEpoch         int64  `json:"-"`
	Revision           int64  `json:"-"`
	State              string `json:"-"`
	ResumeState        string `json:"-"`
	CreatedAt          int64  `json:"-"`
	UpdatedAt          int64  `json:"-"`
}

type BindingDomainRef struct {
	ID         string
	BindingID  string
	ProofEpoch int64
	MountEpoch int64
	Revision   int64
}

func (domain *BindingDomain) Ref() BindingDomainRef {
	return BindingDomainRef{ID: domain.ID, BindingID: domain.BindingID, ProofEpoch: domain.ProofEpoch, MountEpoch: domain.MountEpoch, Revision: domain.Revision}
}

// DomainMutationResult is the same path-free acknowledgement used by v1.
// Optional domain fields are populated only when the original operation wrote
// v2 evidence; a v1 receipt replay does not imply a v2 proof or a read grant.
type DomainMutationResult = BindingMutationResult

func domainMutationReceipt(ack *DomainMutationResult, sourceCreatedAt int64) sourceReceipt {
	return sourceReceipt{ID: ack.BindingID, LibraryID: ack.LibraryID, SourceID: ack.SourceID, AuthorizationRevision: ack.AuthorizationRevision, SourceCreatedAt: sourceCreatedAt, Revision: ack.BindingRevision, LibraryRevision: ack.LibraryRevision, ProofVersion: ack.ProofVersion, ProofEpoch: ack.ProofEpoch, AttestedAt: ack.AttestedAt, DomainID: ack.DomainID, MountEpoch: ack.MountEpoch, DomainRevision: ack.DomainRevision, DomainState: ack.State}
}

func validateDomainObservation(observation DomainObservation) error {
	zero := make([]byte, 32)
	if len(observation.MappingDigest) != 32 || bytes.Equal(observation.MappingDigest, zero) || len(observation.AnchorDigest) != 32 || bytes.Equal(observation.AnchorDigest, zero) || len(observation.RootEvidence) != 0 && (len(observation.RootEvidence) != 32 || bytes.Equal(observation.RootEvidence, zero)) || len(observation.MountEvidence) != 0 && (len(observation.MountEvidence) != 32 || bytes.Equal(observation.MountEvidence, zero)) {
		return fmt.Errorf("%w: domain evidence", ErrInvalid)
	}
	switch observation.EvidenceKind {
	case "native_generation":
		if len(observation.RootEvidence) != 32 || len(observation.MountEvidence) != 32 {
			return fmt.Errorf("%w: native domain evidence", ErrInvalid)
		}
	case "mount_scoped", "path_observed":
	default:
		return fmt.Errorf("%w: domain evidence kind", ErrInvalid)
	}
	return nil
}

func validateV2BindingProof(proof BindingProof) error {
	if proof.Version != 2 || len(proof.MappingDigest) != 32 || bytes.Equal(proof.MappingDigest, make([]byte, 32)) || proof.RootIdentityKind != "" || len(proof.RootIdentity) != 0 || proof.AttestedAt < 0 {
		return fmt.Errorf("%w: v2 binding proof", ErrInvalid)
	}
	return nil
}

func cloneDomainObservation(value DomainObservation) DomainObservation {
	return DomainObservation{MappingDigest: bytes.Clone(value.MappingDigest), RootEvidence: bytes.Clone(value.RootEvidence), MountEvidence: bytes.Clone(value.MountEvidence), AnchorDigest: bytes.Clone(value.AnchorDigest), EvidenceKind: value.EvidenceKind}
}

// CreateDomainAttestedLocalSourceWithCapture is not routed to HTTP until every
// consumer is domain-aware. A replay returns its receipt without invoking the
// callback; source I/O stays outside the SQLite writer transaction.
func (store *Store) CreateDomainAttestedLocalSourceWithCapture(ctx context.Context, actor uint, libraryID string, expectedLibraryRevision int64, operationID, configurationRef string, root []byte, capture func(context.Context) (BindingProof, DomainObservation, error)) (*DomainMutationResult, error) {
	root = bytes.Clone(root)
	ack, err := sourceMutationOperation(ctx, store, actor, "source.create.local", operationID, createSourceOperationInput{libraryID, expectedLibraryRevision, configurationRef, root}, func() (func(*sql.Tx) (sourceReceipt, error), error) {
		if err := store.validateSourceCreate(ctx, actor, libraryID, expectedLibraryRevision, configurationRef, root); err != nil {
			return nil, err
		}
		if capture == nil {
			return nil, ErrInvalid
		}
		proof, observation, err := capture(ctx)
		if err != nil {
			return nil, err
		}
		proof.MappingDigest, observation = bytes.Clone(proof.MappingDigest), cloneDomainObservation(observation)
		return func(tx *sql.Tx) (sourceReceipt, error) {
			if err := validateV2BindingProof(proof); err != nil {
				return sourceReceipt{}, err
			}
			if err := validateDomainObservation(observation); err != nil {
				return sourceReceipt{}, err
			}
			binding, err := createLocalSourceTx(ctx, tx, actor, libraryID, expectedLibraryRevision, configurationRef, root)
			if err != nil {
				return sourceReceipt{}, err
			}
			result, err := setV2DomainProofTx(ctx, tx, actor, binding, binding.Revision, proof, observation)
			if err != nil {
				return sourceReceipt{}, err
			}
			return domainMutationReceipt(result, binding.Source.CreatedAt), nil
		}, nil
	})
	if err != nil {
		return nil, err
	}
	return bindingMutationFromReceipt(ack), nil
}

func (store *Store) attestDomainSourceWithCapture(ctx context.Context, actor uint, bindingID string, expectedBindingRevision int64, operationID string, capture func(context.Context) (BindingProof, DomainObservation, error)) (*DomainMutationResult, error) {
	ack, err := sourceMutationOperation(ctx, store, actor, "source.binding.attest", operationID, attestSourceOperationInput{bindingID, expectedBindingRevision}, func() (func(*sql.Tx) (sourceReceipt, error), error) {
		if capture == nil {
			return nil, ErrInvalid
		}
		proof, observation, err := capture(ctx)
		if err != nil {
			return nil, err
		}
		proof.MappingDigest, observation = bytes.Clone(proof.MappingDigest), cloneDomainObservation(observation)
		return func(tx *sql.Tx) (sourceReceipt, error) {
			if err := validateV2BindingProof(proof); err != nil {
				return sourceReceipt{}, err
			}
			if err := validateDomainObservation(observation); err != nil {
				return sourceReceipt{}, err
			}
			binding, err := scanLibrarySource(tx.QueryRowContext(ctx, bindingSelection+" WHERE ls.binding_id=? AND l.owner_user_id=? AND s.owner_user_id=? AND s.kind='local'", bindingID, actor, actor))
			if err != nil {
				return sourceReceipt{}, err
			}
			result, err := setV2DomainProofTx(ctx, tx, actor, binding, expectedBindingRevision, proof, observation)
			if err != nil {
				return sourceReceipt{}, err
			}
			return domainMutationReceipt(result, binding.Source.CreatedAt), nil
		}, nil
	})
	if err != nil {
		return nil, err
	}
	return bindingMutationFromReceipt(ack), nil
}

// AttestDomainSourceWithCapture is an explicit owner rebind. Its immutable
// target is validated before receipt lookup or capture.
func (store *Store) AttestDomainSourceWithCapture(ctx context.Context, actor uint, libraryID, bindingID string, root []byte, expectedBindingRevision int64, operationID string, capture func(context.Context, *LibrarySource) (BindingProof, DomainObservation, error)) (*DomainMutationResult, error) {
	binding, err := store.attestTarget(ctx, actor, libraryID, bindingID, root)
	if err != nil {
		return nil, err
	}
	return store.attestDomainSourceWithCapture(ctx, actor, bindingID, expectedBindingRevision, operationID, func(ctx context.Context) (BindingProof, DomainObservation, error) {
		if capture == nil {
			return BindingProof{}, DomainObservation{}, ErrInvalid
		}
		return capture(ctx, binding)
	})
}

func nullableDomainEvidence(value []byte) any {
	if len(value) == 0 {
		return nil
	}
	return value
}

func setV2DomainProofTx(ctx context.Context, tx *sql.Tx, actor uint, binding *LibrarySource, expectedRevision int64, proof BindingProof, observation DomainObservation) (*DomainMutationResult, error) {
	library, err := activeCatalogLibrary(ctx, tx, actor, binding.LibraryID)
	if err != nil {
		return nil, err
	}
	if library.OwnerUserID != actor || binding.Source.OwnerUserID != actor {
		return nil, ErrForbidden
	}
	if expectedRevision < 1 || binding.Revision != expectedRevision {
		return nil, ErrRevisionConflict
	}
	id, err := randomID("domain_")
	if err != nil {
		return nil, err
	}
	now := time.Now().UnixMilli()
	updated, err := tx.ExecContext(ctx, `UPDATE library_source SET active_proof_version=2,proof_version=0,proof_epoch=proof_epoch+1,mapping_digest=NULL,root_identity_kind='',root_identity=NULL,attested_at=NULL,v2_mapping_digest=?,v2_attested_at=?,revision=revision+1 WHERE binding_id=? AND revision=?`, proof.MappingDigest, now, binding.ID, expectedRevision)
	if err != nil {
		return nil, err
	}
	if count, err := updated.RowsAffected(); err != nil || count != 1 {
		if err != nil {
			return nil, err
		}
		return nil, ErrRevisionConflict
	}
	epoch := binding.ProofEpoch + 1
	var strongRoot any
	if observation.EvidenceKind == "native_generation" {
		strongRoot = observation.RootEvidence
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO binding_domain(id,binding_id,proof_epoch,relative_prefix,mapping_digest,root_evidence,strong_root_evidence,evidence_kind,mount_evidence,anchor_digest,mount_epoch,revision,state,created_at,updated_at)
VALUES (?,?,?,X'',?,?,?,?,?,?,1,1,'available',?,?)`, id, binding.ID, epoch, observation.MappingDigest, nullableDomainEvidence(observation.RootEvidence), strongRoot, observation.EvidenceKind, nullableDomainEvidence(observation.MountEvidence), observation.AnchorDigest, now, now); err != nil {
		return nil, err
	}
	if err := updateLibrary(ctx, tx, library); err != nil {
		return nil, err
	}
	if err := publishCatalogChange(ctx, tx, actor, binding.LibraryID, "library.source.attested", catalogChange{SourceID: binding.Source.ID, Revision: library.Revision}); err != nil {
		return nil, err
	}
	return &DomainMutationResult{BindingID: binding.ID, LibraryID: binding.LibraryID, SourceID: binding.Source.ID, BindingRevision: expectedRevision + 1, LibraryRevision: library.Revision, AuthorizationRevision: binding.Source.AuthorizationRevision, ProofVersion: 2, ProofEpoch: epoch, AttestedAt: now, DomainID: id, MountEpoch: 1, DomainRevision: 1, State: "available"}, nil
}

const domainSelection = `SELECT id,binding_id,proof_epoch,relative_prefix,mapping_digest,root_evidence,strong_root_evidence,evidence_kind,mount_evidence,anchor_digest,mount_epoch,revision,state,resume_state,created_at,updated_at FROM binding_domain`

func readBindingDomain(row rowScanner) (*BindingDomain, error) {
	value := &BindingDomain{}
	err := row.Scan(&value.ID, &value.BindingID, &value.ProofEpoch, &value.RelativePrefix, &value.MappingDigest, &value.RootEvidence, &value.StrongRootEvidence, &value.EvidenceKind, &value.MountEvidence, &value.anchorDigest, &value.MountEpoch, &value.Revision, &value.State, &value.ResumeState, &value.CreatedAt, &value.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return value, err
}

// validateDomainBinding does not make a filesystem or user-policy decision.
// Unlike the old v1-only validateSourceBinding, it accepts only current v2.
func validateDomainBinding(ctx context.Context, reader schemaReader, actor uint, ref SourceBindingRef) (*LibrarySource, error) {
	library, err := activeCatalogLibrary(ctx, reader, actor, ref.LibraryID)
	if err != nil {
		return nil, err
	}
	if actor == 0 || library.OwnerUserID != actor {
		return nil, ErrForbidden
	}
	current, err := scanLibrarySource(reader.QueryRowContext(ctx, bindingSelection+" WHERE ls.binding_id=? AND ls.library_id=? AND l.owner_user_id=? AND s.owner_user_id=? AND s.kind='local'", ref.BindingID, ref.LibraryID, actor, actor))
	if err != nil {
		return nil, err
	}
	if !sameBinding(current.Ref(), ref) {
		return nil, ErrRevisionConflict
	}
	if current.Proof == nil || current.Proof.Version != 2 {
		return nil, ErrBindingProofUnsupported
	}
	return current, nil
}

func currentSelectedDomain(ctx context.Context, reader schemaReader, actor uint, binding SourceBindingRef) (*BindingDomain, error) {
	if _, err := validateDomainBinding(ctx, reader, actor, binding); err != nil {
		return nil, err
	}
	return readBindingDomain(reader.QueryRowContext(ctx, domainSelection+" WHERE binding_id=? AND proof_epoch=? AND relative_prefix=X''", binding.BindingID, binding.ProofEpoch))
}

func (store *Store) GetSelectedBindingDomain(ctx context.Context, actor uint, binding SourceBindingRef) (*BindingDomain, error) {
	return currentSelectedDomain(ctx, store.db, actor, binding)
}

func matchDomainRef(domain *BindingDomain, ref BindingDomainRef) bool {
	return domain != nil && domain.ID == ref.ID && domain.BindingID == ref.BindingID && domain.ProofEpoch == ref.ProofEpoch && domain.MountEpoch == ref.MountEpoch && domain.Revision == ref.Revision
}

func publishDomainChange(ctx context.Context, tx *sql.Tx, actor uint, binding SourceBindingRef, domain *BindingDomain) error {
	id, err := randomID("evt_")
	if err != nil {
		return err
	}
	payload, err := json.Marshal(struct {
		ActorUserID uint   `json:"actorUserId"`
		LibraryID   string `json:"libraryId"`
		DomainID    string `json:"domainId"`
		MountEpoch  int64  `json:"mountEpoch"`
		Revision    int64  `json:"revision"`
		State       string `json:"state"`
	}{actor, binding.LibraryID, domain.ID, domain.MountEpoch, domain.Revision, domain.State})
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, "INSERT INTO outbox(event_id,library_id,kind,payload_json,created_at) VALUES (?,?,?,?,?)", id, binding.LibraryID, "library.source.domain.changed", string(payload), time.Now().UnixMilli())
	return err
}

// ObserveSelectedBindingDomain records a fresh, externally authorized safe
// observation with CAS. Same-held-handle offline recovery keeps the mount epoch;
// weak continuity loss or remount increments it and remains revalidating.
// A changed mapping or contradictory strong root permanently requires explicit
// rebind. No v2 consumer is enabled by recording this state.
func (c *ScanCoordinator) ObserveSelectedBindingDomain(ctx context.Context, actor uint, binding SourceBindingRef, expected BindingDomainRef, observation DomainObservation) (*BindingDomain, error) {
	return coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (*BindingDomain, error) {
		return store.observeSelectedBindingDomain(ctx, actor, binding, expected, observation)
	})
}

func (store *Store) observeSelectedBindingDomain(ctx context.Context, actor uint, binding SourceBindingRef, expected BindingDomainRef, observation DomainObservation) (*BindingDomain, error) {
	observation = cloneDomainObservation(observation)
	if err := validateDomainObservation(observation); err != nil {
		return nil, err
	}
	var result *BindingDomain
	err := store.WithTx(ctx, func(tx *sql.Tx) error {
		current, err := currentSelectedDomain(ctx, tx, actor, binding)
		if err != nil {
			return err
		}
		if !matchDomainRef(current, expected) {
			return ErrRevisionConflict
		}
		if current.State == "rebind_required" {
			return ErrDomainRebindRequired
		}
		nextState := current.State
		if nextState == "offline" {
			nextState = current.ResumeState
		}
		nextEpoch := current.MountEpoch
		mappingChanged := !bytes.Equal(current.MappingDigest, observation.MappingDigest)
		strongContradiction := len(current.StrongRootEvidence) != 0 && observation.EvidenceKind == "native_generation" && !bytes.Equal(current.StrongRootEvidence, observation.RootEvidence)
		if mappingChanged || strongContradiction {
			nextState = "rebind_required"
		} else {
			sameAnchor := bytes.Equal(current.anchorDigest, observation.AnchorDigest)
			sameRoot := bytes.Equal(current.RootEvidence, observation.RootEvidence)
			sameMount := bytes.Equal(current.MountEvidence, observation.MountEvidence)
			strongContinuity := len(current.StrongRootEvidence) == 32 && current.EvidenceKind == "native_generation" && observation.EvidenceKind == "native_generation" && sameRoot && sameMount && bytes.Equal(current.StrongRootEvidence, current.RootEvidence)
			if !sameMount || !sameRoot || current.EvidenceKind != observation.EvidenceKind || !sameAnchor && !strongContinuity {
				nextEpoch++
				nextState = "revalidating"
			}
		}
		if nextState == "rebind_required" {
			observation = DomainObservation{MappingDigest: current.MappingDigest, RootEvidence: current.RootEvidence, MountEvidence: current.MountEvidence, AnchorDigest: current.anchorDigest, EvidenceKind: current.EvidenceKind}
		}
		if nextState == current.State && nextEpoch == current.MountEpoch && bytes.Equal(current.RootEvidence, observation.RootEvidence) && bytes.Equal(current.MountEvidence, observation.MountEvidence) && bytes.Equal(current.anchorDigest, observation.AnchorDigest) && current.EvidenceKind == observation.EvidenceKind {
			result = current
			return nil
		}
		now := max(time.Now().UnixMilli(), current.UpdatedAt)
		updated, err := tx.ExecContext(ctx, `UPDATE binding_domain SET root_evidence=?,evidence_kind=?,mount_evidence=?,anchor_digest=?,mount_epoch=?,revision=revision+1,state=?,resume_state='',updated_at=? WHERE id=? AND binding_id=? AND proof_epoch=? AND mount_epoch=? AND revision=?`, nullableDomainEvidence(observation.RootEvidence), observation.EvidenceKind, nullableDomainEvidence(observation.MountEvidence), observation.AnchorDigest, nextEpoch, nextState, now, expected.ID, expected.BindingID, expected.ProofEpoch, expected.MountEpoch, expected.Revision)
		if err != nil {
			return err
		}
		if count, err := updated.RowsAffected(); err != nil || count != 1 {
			if err != nil {
				return err
			}
			return ErrRevisionConflict
		}
		result, err = currentSelectedDomain(ctx, tx, actor, binding)
		if err != nil {
			return err
		}
		return publishDomainChange(ctx, tx, actor, binding, result)
	})
	if err == nil && result != nil && result.State == "rebind_required" {
		return result, ErrDomainRebindRequired
	}
	return result, err
}

// MarkSelectedBindingDomainOffline is only for an already classified transient
// offline condition. Permission denial, cancellation, a changed mapping and
// unknown errors must never be converted to this state by the coordinator.
func (c *ScanCoordinator) MarkSelectedBindingDomainOffline(ctx context.Context, actor uint, binding SourceBindingRef, expected BindingDomainRef, reason string) (*BindingDomain, error) {
	return coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (*BindingDomain, error) {
		return store.markSelectedBindingDomainOffline(ctx, actor, binding, expected, reason)
	})
}

func (store *Store) markSelectedBindingDomainOffline(ctx context.Context, actor uint, binding SourceBindingRef, expected BindingDomainRef, reason string) (*BindingDomain, error) {
	if reason != "unreachable" && reason != "timeout" && reason != "stale_mount" {
		return nil, fmt.Errorf("%w: domain offline reason", ErrInvalid)
	}
	var result *BindingDomain
	err := store.WithTx(ctx, func(tx *sql.Tx) error {
		current, err := currentSelectedDomain(ctx, tx, actor, binding)
		if err != nil {
			return err
		}
		if !matchDomainRef(current, expected) {
			return ErrRevisionConflict
		}
		if current.State == "rebind_required" {
			return ErrDomainRebindRequired
		}
		if current.State == "offline" {
			result = current
			return nil
		}
		now := max(time.Now().UnixMilli(), current.UpdatedAt)
		updated, err := tx.ExecContext(ctx, `UPDATE binding_domain SET state='offline',resume_state=?,revision=revision+1,updated_at=? WHERE id=? AND binding_id=? AND proof_epoch=? AND mount_epoch=? AND revision=?`, current.State, now, expected.ID, expected.BindingID, expected.ProofEpoch, expected.MountEpoch, expected.Revision)
		if err != nil {
			return err
		}
		if count, err := updated.RowsAffected(); err != nil || count != 1 {
			if err != nil {
				return err
			}
			return ErrRevisionConflict
		}
		result, err = currentSelectedDomain(ctx, tx, actor, binding)
		if err != nil {
			return err
		}
		return publishDomainChange(ctx, tx, actor, binding, result)
	})
	return result, err
}

// UnverifiedDomainObjectKey encodes all epochs and original locator bytes. It
// is an isolation key only and never establishes object or timeline identity.
func UnverifiedDomainObjectKey(bindingID string, proofEpoch int64, domainID string, mountEpoch int64, locator []byte) []byte {
	key := append([]byte(nil), []byte("fileway/local/domain-path-unverified/v1\x00")...)
	appendBytes := func(value []byte) {
		var length [4]byte
		binary.BigEndian.PutUint32(length[:], uint32(len(value)))
		key = append(key, length[:]...)
		key = append(key, value...)
	}
	appendInt := func(value int64) {
		var generation [8]byte
		binary.BigEndian.PutUint64(generation[:], uint64(value))
		key = append(key, generation[:]...)
	}
	appendBytes([]byte(bindingID))
	appendInt(proofEpoch)
	appendBytes([]byte(domainID))
	appendInt(mountEpoch)
	appendBytes(locator)
	return key
}
