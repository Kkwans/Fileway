package medialibrary

import (
	"bytes"
	"context"
	"database/sql"
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
}

func bindingMutationFromReceipt(ack sourceReceipt) *BindingMutationResult {
	return &BindingMutationResult{BindingID: ack.ID, LibraryID: ack.LibraryID, SourceID: ack.SourceID, BindingRevision: ack.Revision, LibraryRevision: ack.LibraryRevision, AuthorizationRevision: ack.AuthorizationRevision, ProofVersion: ack.ProofVersion, ProofEpoch: ack.ProofEpoch, AttestedAt: ack.AttestedAt}
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
	root = bytes.Clone(root)
	proof.MappingDigest = bytes.Clone(proof.MappingDigest)
	proof.RootIdentity = bytes.Clone(proof.RootIdentity)
	ack, err := operationTx(ctx, store, actor, "source.create.local", operationID, struct {
		LibraryID        string
		ExpectedRevision int64
		ConfigurationRef string
		Root             []byte
	}{libraryID, expectedLibraryRevision, configurationRef, root}, func(tx *sql.Tx) (sourceReceipt, error) {
		if err := validateBindingProof(proof); err != nil {
			return sourceReceipt{}, err
		}
		binding, err := createLocalSourceTx(ctx, tx, actor, libraryID, expectedLibraryRevision, configurationRef, root)
		if err != nil {
			return sourceReceipt{}, err
		}
		binding, err = setBindingProofTx(ctx, tx, actor, binding, binding.Revision, proof)
		if err != nil {
			return sourceReceipt{}, err
		}
		return bindingReceipt(binding), nil
	})
	if err != nil {
		return nil, err
	}
	return bindingMutationFromReceipt(ack), nil
}

// AttestSourceBindingWithOperation is an explicit confirmation/rebind. Every
// successful new operation advances the proof epoch and binding revision;
// previous membership and metadata evidence remain at their original epoch.
func (store *Store) AttestSourceBindingWithOperation(ctx context.Context, actor uint, bindingID string, expectedBindingRevision int64, operationID string, proof BindingProof) (*BindingMutationResult, error) {
	proof.MappingDigest = bytes.Clone(proof.MappingDigest)
	proof.RootIdentity = bytes.Clone(proof.RootIdentity)
	ack, err := operationTx(ctx, store, actor, "source.binding.attest", operationID, struct {
		BindingID string
		Revision  int64
	}{bindingID, expectedBindingRevision}, func(tx *sql.Tx) (sourceReceipt, error) {
		if err := validateBindingProof(proof); err != nil {
			return sourceReceipt{}, err
		}
		binding, err := scanLibrarySource(tx.QueryRowContext(ctx, bindingSelection+" WHERE ls.binding_id=? AND l.owner_user_id=? AND s.owner_user_id=? AND s.kind='local'", bindingID, actor, actor))
		if err != nil {
			return sourceReceipt{}, err
		}
		binding, err = setBindingProofTx(ctx, tx, actor, binding, expectedBindingRevision, proof)
		if err != nil {
			return sourceReceipt{}, err
		}
		return bindingReceipt(binding), nil
	})
	if err != nil {
		return nil, err
	}
	return bindingMutationFromReceipt(ack), nil
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
	result, err := tx.ExecContext(ctx, `UPDATE library_source SET proof_version=?,proof_epoch=proof_epoch+1,mapping_digest=?,root_identity_kind=?,root_identity=?,attested_at=?,revision=revision+1 WHERE binding_id=? AND revision=?`, proof.Version, proof.MappingDigest, proof.RootIdentityKind, proof.RootIdentity, now, binding.ID, expectedRevision)
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
