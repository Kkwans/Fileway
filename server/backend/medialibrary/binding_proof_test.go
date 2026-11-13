package medialibrary

import (
	"bytes"
	"errors"
	"testing"
)

func proofForTest(value byte) BindingProof {
	return BindingProof{Version: 1, MappingDigest: bytes.Repeat([]byte{value}, 32), RootIdentityKind: "native", RootIdentity: []byte{value, 1, 2}}
}

func TestBindingProofReceiptDoesNotResampleOrUpgradeLegacy(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	root := []byte("/videos")
	virtual := proofForTest(1)
	virtual.RootIdentityKind = "virtual"
	if _, err := store.CreateAttestedLocalSourceWithOperation(t.Context(), 7, library.ID, 1, "virtual", "config", root, virtual); !errors.Is(err, ErrInvalid) {
		t.Fatal(err)
	}
	if tableCount(t, store, "source") != 0 {
		t.Fatal("virtual proof created source")
	}
	ack, err := store.CreateAttestedLocalSourceWithOperation(t.Context(), 7, library.ID, 1, "create-proof", "config", root, proofForTest(1))
	if err != nil || ack.ProofEpoch != 1 || ack.BindingRevision != 2 || ack.ProofVersion != 1 {
		t.Fatal(ack, err)
	}
	again, err := store.CreateAttestedLocalSourceWithOperation(t.Context(), 7, library.ID, 1, "create-proof", "config", root, proofForTest(2))
	if err != nil || *again != *ack {
		t.Fatal(again, err)
	}
	binding, err := store.GetLibrarySourceByID(t.Context(), 7, ack.BindingID)
	if err != nil || binding.Proof == nil || binding.Proof.MappingDigest[0] != 1 {
		t.Fatal(binding, err)
	}
	rebound, err := store.AttestSourceBindingWithOperation(t.Context(), 7, binding.ID, binding.Revision, "rebind", proofForTest(2))
	if err != nil || rebound.ProofEpoch != 2 || rebound.BindingRevision != 3 {
		t.Fatal(rebound, err)
	}
	replay, err := store.AttestSourceBindingWithOperation(t.Context(), 7, binding.ID, binding.Revision, "rebind", proofForTest(3))
	if err != nil || *replay != *rebound {
		t.Fatal(replay, err)
	}
	binding, err = store.GetLibrarySourceByID(t.Context(), 7, binding.ID)
	if err != nil || binding.Proof.MappingDigest[0] != 2 {
		t.Fatal(binding, err)
	}
	if _, err := store.db.Exec("UPDATE library_source SET mapping_digest=? WHERE binding_id=?", bytes.Repeat([]byte{3}, 32), binding.ID); err == nil {
		t.Fatal("proof changed without fencing epoch")
	}

	other := createLibrary(t, store, 7, "Legacy")
	legacy, err := store.CreateLocalSourceWithOperation(t.Context(), 7, other.ID, 1, "old-create", "config", []byte("/old"))
	if err != nil {
		t.Fatal(err)
	}
	legacyAck, err := store.CreateAttestedLocalSourceWithOperation(t.Context(), 7, other.ID, 1, "old-create", "config", []byte("/old"), proofForTest(4))
	if err != nil || legacyAck.BindingID != legacy.ID || legacyAck.ProofEpoch != 0 {
		t.Fatal(legacyAck, err)
	}
	stillLegacy, err := store.GetLibrarySourceByID(t.Context(), 7, legacy.ID)
	if err != nil || stillLegacy.Proof != nil || stillLegacy.ProofEpoch != 0 {
		t.Fatal(stillLegacy, err)
	}
}

func TestRebindFencesOldWeakFileAndMetadataEpoch(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	_, lease := testScan(t, store, binding, "old-epoch")
	locator := []byte("/videos/one.mkv")
	input := testObservation("unused", string(locator))
	input.Identity = "path_unverified"
	input.ObjectKey = UnverifiedPathObjectKey(binding.ID, binding.ProofEpoch, locator)
	input.NFOStatus = "parsed"
	input.NFO = testNFO(t, `<movie><title>Old workspace</title></movie>`)
	old := scanNFO(t, store, binding, lease, input, 1)
	if _, err := store.AttestSourceBindingWithOperation(t.Context(), 7, binding.ID, binding.Revision, "rebind-epoch", proofForTest(9)); err != nil {
		t.Fatal(err)
	}
	newBinding, err := store.GetLibrarySourceByID(t.Context(), 7, binding.ID)
	if err != nil || newBinding.ProofEpoch != binding.ProofEpoch+1 {
		t.Fatal(newBinding, err)
	}
	if _, err := store.CommitScanObservation(t.Context(), 7, *lease, input, testCheckpoint(input, 2, 2)); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal(err)
	}
	files, err := store.ListItemSourceAccess(t.Context(), 7, library.ID, old.ItemID, "", 100)
	if err != nil || len(files.Items) != 0 {
		t.Fatal(files, err)
	}
	metadata, err := store.ListItemMetadataSourceAccess(t.Context(), 7, library.ID, old.ItemID, "", 100)
	if err != nil || len(metadata.Items) != 0 {
		t.Fatal(metadata, err)
	}
	detail, err := store.GetItemDetail(t.Context(), 7, library.ID, old.ItemID)
	if err != nil || detail.Item.Title != "" || len(detail.Fields) != 0 {
		t.Fatal(detail, err)
	}
	if _, err := store.FinishScan(t.Context(), 7, *lease, ScanFinish{Status: "failed", ExpectedSequence: 1}); err != nil {
		t.Fatal(err)
	}
	_, newLease := testScan(t, store, newBinding, "new-epoch")
	input.ObjectKey = UnverifiedPathObjectKey(newBinding.ID, newBinding.ProofEpoch, locator)
	input.ExpectedRevision = 0
	input.NFO = testNFO(t, `<movie><title>New workspace</title></movie>`)
	current := scanNFO(t, store, newBinding, newLease, input, 1)
	if current.SourceFileID == old.SourceFileID || current.ItemID == old.ItemID {
		t.Fatal(old, current)
	}
	finishEmptyReconcile(t, store, 7, *newLease, 1)
	var oldEpoch int64
	var oldMissing *int64
	if err := store.db.QueryRow("SELECT binding_epoch,missing_since FROM library_file WHERE source_file_id=?", old.SourceFileID).Scan(&oldEpoch, &oldMissing); err != nil || oldEpoch != binding.ProofEpoch || oldMissing != nil {
		t.Fatal(oldEpoch, oldMissing, err)
	}
	if tableCount(t, store, "metadata_input") != 2 || tableCount(t, store, "library_item") != 2 {
		t.Fatal("old evidence was overwritten")
	}
}

func TestNativeRebindRefreshesFilenameOnlyAfterNewObservation(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	_, lease := testScan(t, store, binding, "native-old")
	input := testObservation("birth-verified-native", "/videos/old-name.mkv")
	input.InitialTitle = "Old name"
	old := scanNFO(t, store, binding, lease, input, 1)
	if _, err := store.AttestSourceBindingWithOperation(t.Context(), 7, binding.ID, binding.Revision, "native-rebind", proofForTest(7)); err != nil {
		t.Fatal(err)
	}
	detail, err := store.GetItemDetail(t.Context(), 7, library.ID, old.ItemID)
	if err != nil || detail.Item.Title != "" {
		t.Fatal(detail, err)
	}
	if _, ok := detail.Fields["title"]; ok {
		t.Fatal("stale filename remained visible")
	}
	if _, err := store.FinishScan(t.Context(), 7, *lease, ScanFinish{Status: "failed", ExpectedSequence: 1}); err != nil {
		t.Fatal(err)
	}
	current, err := store.GetLibrarySourceByID(t.Context(), 7, binding.ID)
	if err != nil {
		t.Fatal(err)
	}
	_, lease = testScan(t, store, current, "native-new")
	input.Locator = []byte("/videos/new-name.mkv")
	input.InitialTitle = "New name"
	input.ExpectedRevision = old.FileRevision
	newResult, err := store.CommitScanObservation(t.Context(), 7, *lease, input, testCheckpoint(input, 1, 1))
	if err != nil || newResult.SourceFileID != old.SourceFileID || newResult.ItemID != old.ItemID {
		t.Fatal(newResult, err)
	}
	detail, err = store.GetItemDetail(t.Context(), 7, library.ID, old.ItemID)
	if err != nil || detail.Item.Title != "New name" || detail.Fields["title"].BindingEpoch != current.ProofEpoch {
		t.Fatal(detail, err)
	}
}
