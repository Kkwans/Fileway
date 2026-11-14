package medialibrary

import (
	"bytes"
	"context"
	"database/sql"
	"fmt"
	"strings"
	"time"
)

// BindingMutationResult is a safe receipt, not proof or source authority.
type BindingMutationResult struct {
	BindingID             string `json:"bindingId"`
	LibraryID             string `json:"libraryId"`
	SourceID              string `json:"sourceId"`
	BindingRevision       int64  `json:"bindingRevision"`
	LibraryRevision       int64  `json:"libraryRevision"`
	AuthorizationRevision int64  `json:"authorizationRevision"`
	ProofVersion          int    `json:"proofVersion"`
	ProofEpoch            int64  `json:"proofEpoch"`
	AttestedAt            int64  `json:"attestedAt"`
	DomainID              string `json:"domainId,omitempty"`
	MountEpoch            int64  `json:"mountEpoch,omitempty"`
	DomainRevision        int64  `json:"domainRevision,omitempty"`
	State                 string `json:"state,omitempty"`
}

func bindingMutationFromReceipt(ack sourceReceipt) *BindingMutationResult {
	return &BindingMutationResult{BindingID: ack.ID, LibraryID: ack.LibraryID, SourceID: ack.SourceID, BindingRevision: ack.Revision, LibraryRevision: ack.LibraryRevision, AuthorizationRevision: ack.AuthorizationRevision, ProofVersion: ack.ProofVersion, ProofEpoch: ack.ProofEpoch, AttestedAt: ack.AttestedAt, DomainID: ack.DomainID, MountEpoch: ack.MountEpoch, DomainRevision: ack.DomainRevision, State: ack.DomainState}
}

func bindingReceipt(binding *LibrarySource) sourceReceipt {
	ack := sourceReceipt{ID: binding.ID, LibraryID: binding.LibraryID, SourceID: binding.Source.ID, AuthorizationRevision: binding.Source.AuthorizationRevision, SourceCreatedAt: binding.Source.CreatedAt, Revision: binding.Revision, LibraryRevision: binding.LibraryRevision, ProofEpoch: binding.ProofEpoch}
	if binding.Proof != nil {
		ack.ProofVersion = binding.Proof.Version
		ack.AttestedAt = binding.Proof.AttestedAt
	}
	return ack
}

// CreateAttestedLocalSourceWithOperation expects proof captured from the live,
// safely opened root before this call. Proof is intentionally excluded from
// the request digest: replay returns the original acknowledgement without
// sampling or replacing the persisted proof.
func (store *Store) CreateAttestedLocalSourceWithOperation(ctx context.Context, actor uint, libraryID string, expectedLibraryRevision int64, operationID, configurationRef string, root []byte, proof BindingProof) (*BindingMutationResult, error) {
	return store.CreateAttestedLocalSourceWithCapture(ctx, actor, libraryID, expectedLibraryRevision, operationID, configurationRef, root, func(context.Context) (BindingProof, error) { return proof, nil })
}

// The capture callback runs only for a genuinely new operation, outside the
// SQLite transaction. A committed receipt is returned before touching source.
func (store *Store) CreateAttestedLocalSourceWithCapture(ctx context.Context, actor uint, libraryID string, expectedLibraryRevision int64, operationID, configurationRef string, root []byte, capture func(context.Context) (BindingProof, error)) (*BindingMutationResult, error) {
	root = bytes.Clone(root)
	ack, err := sourceMutationOperation(ctx, store, actor, "source.create.local", operationID, createSourceOperationInput{libraryID, expectedLibraryRevision, configurationRef, root}, func() (func(*sql.Tx) (sourceReceipt, error), error) {
		if err := store.validateSourceCreate(ctx, actor, libraryID, expectedLibraryRevision, configurationRef, root); err != nil {
			return nil, err
		}
		if capture == nil {
			return nil, ErrInvalid
		}
		proof, err := capture(ctx)
		if err != nil {
			return nil, err
		}
		proof.MappingDigest, proof.RootIdentity = bytes.Clone(proof.MappingDigest), bytes.Clone(proof.RootIdentity)
		return func(tx *sql.Tx) (sourceReceipt, error) {
			binding, err := createLocalSourceTx(ctx, tx, actor, libraryID, expectedLibraryRevision, configurationRef, root)
			if err != nil {
				return sourceReceipt{}, err
			}
			binding, err = setBindingProofTx(ctx, tx, actor, binding, binding.Revision, proof)
			if err != nil {
				return sourceReceipt{}, err
			}
			return bindingReceipt(binding), nil
		}, nil
	})
	if err != nil {
		return nil, err
	}
	return bindingMutationFromReceipt(ack), nil
}

func (store *Store) validateSourceCreate(ctx context.Context, actor uint, libraryID string, expectedRevision int64, configurationRef string, root []byte) error {
	if strings.TrimSpace(configurationRef) == "" || len(root) == 0 {
		return fmt.Errorf("%w: source configuration reference and root are required", ErrInvalid)
	}
	library, err := ownedLibrary(ctx, store.db, actor, libraryID, expectedRevision)
	if err != nil {
		return err
	}
	if library.ArchivedAt != nil {
		return ErrArchived
	}
	return nil
}

// AttestSourceBindingWithOperation is an explicit confirmation/rebind. Every
// successful new operation advances the proof epoch and binding revision;
// previous membership and metadata evidence remain at their original epoch.
func (store *Store) AttestSourceBindingWithOperation(ctx context.Context, actor uint, bindingID string, expectedBindingRevision int64, operationID string, proof BindingProof) (*BindingMutationResult, error) {
	return store.attestSourceBindingWithCapture(ctx, actor, bindingID, expectedBindingRevision, operationID, func(context.Context) (BindingProof, error) { return proof, nil })
}

func (store *Store) attestSourceBindingWithCapture(ctx context.Context, actor uint, bindingID string, expectedBindingRevision int64, operationID string, capture func(context.Context) (BindingProof, error)) (*BindingMutationResult, error) {
	ack, err := sourceMutationOperation(ctx, store, actor, "source.binding.attest", operationID, attestSourceOperationInput{bindingID, expectedBindingRevision}, func() (func(*sql.Tx) (sourceReceipt, error), error) {
		if capture == nil {
			return nil, ErrInvalid
		}
		proof, err := capture(ctx)
		if err != nil {
			return nil, err
		}
		proof.MappingDigest, proof.RootIdentity = bytes.Clone(proof.MappingDigest), bytes.Clone(proof.RootIdentity)
		return func(tx *sql.Tx) (sourceReceipt, error) {
			binding, err := scanLibrarySource(tx.QueryRowContext(ctx, bindingSelection+" WHERE ls.binding_id=? AND l.owner_user_id=? AND s.owner_user_id=? AND s.kind='local'", bindingID, actor, actor))
			if err != nil {
				return sourceReceipt{}, err
			}
			binding, err = setBindingProofTx(ctx, tx, actor, binding, expectedBindingRevision, proof)
			if err != nil {
				return sourceReceipt{}, err
			}
			return bindingReceipt(binding), nil
		}, nil
	})
	if err != nil {
		return nil, err
	}
	return bindingMutationFromReceipt(ack), nil
}

// The original v1 digest identifies attestation by binding ID and revision;
// its root is fixed by that binding. Validate the client's normalized root and
// library before consulting the shared receipt or sampling any new proof.
func (store *Store) AttestSourceBindingWithCapture(ctx context.Context, actor uint, libraryID, bindingID string, root []byte, expectedBindingRevision int64, operationID string, capture func(context.Context, *LibrarySource) (BindingProof, error)) (*BindingMutationResult, error) {
	binding, err := store.attestTarget(ctx, actor, libraryID, bindingID, root)
	if err != nil {
		return nil, err
	}
	return store.attestSourceBindingWithCapture(ctx, actor, bindingID, expectedBindingRevision, operationID, func(ctx context.Context) (BindingProof, error) {
		if capture == nil {
			return BindingProof{}, ErrInvalid
		}
		return capture(ctx, binding)
	})
}

func (store *Store) attestTarget(ctx context.Context, actor uint, libraryID, bindingID string, root []byte) (*LibrarySource, error) {
	binding, err := store.GetLibrarySourceByID(ctx, actor, bindingID)
	if err != nil {
		return nil, err
	}
	if binding.LibraryID != libraryID {
		return nil, ErrNotFound
	}
	if !bytes.Equal(binding.RootLocator, root) {
		return nil, ErrRevisionConflict
	}
	return binding, nil
}

func setBindingProofTx(ctx context.Context, tx *sql.Tx, actor uint, binding *LibrarySource, expectedRevision int64, proof BindingProof) (*LibrarySource, error) {
	if err := validateBindingProof(proof); err != nil {
		return nil, err
	}
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
	now := time.Now().UnixMilli()
	result, err := tx.ExecContext(ctx, `UPDATE library_source SET active_proof_version=1,proof_version=?,proof_epoch=proof_epoch+1,mapping_digest=?,root_identity_kind=?,root_identity=?,attested_at=?,v2_mapping_digest=NULL,v2_attested_at=NULL,revision=revision+1 WHERE binding_id=? AND revision=?`, proof.Version, proof.MappingDigest, proof.RootIdentityKind, proof.RootIdentity, now, binding.ID, expectedRevision)
	if err != nil {
		return nil, err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return nil, err
	}
	if count != 1 {
		return nil, ErrRevisionConflict
	}
	if err := updateLibrary(ctx, tx, library); err != nil {
		return nil, err
	}
	proof.AttestedAt = now
	binding.Revision++
	binding.ProofEpoch++
	binding.LibraryRevision = library.Revision
	binding.Proof = &proof
	if err := publishCatalogChange(ctx, tx, actor, binding.LibraryID, "library.source.attested", catalogChange{SourceID: binding.Source.ID, Revision: library.Revision}); err != nil {
		return nil, err
	}
	return binding, nil
}
