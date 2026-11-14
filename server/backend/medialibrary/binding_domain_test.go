package medialibrary

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"strings"
	"testing"
)

func domainProofForTest(value byte) BindingProof {
	return BindingProof{Version: 2, MappingDigest: bytes.Repeat([]byte{value}, 32)}
}

func domainObservationForTest(kind string, value byte) DomainObservation {
	return DomainObservation{MappingDigest: bytes.Repeat([]byte{value}, 32), RootEvidence: bytes.Repeat([]byte{value + 1}, 32), MountEvidence: bytes.Repeat([]byte{value + 2}, 32), AnchorDigest: bytes.Repeat([]byte{value + 3}, 32), EvidenceKind: kind}
}

func createDomainForTest(t *testing.T, kind string) (*Store, *LibrarySource, *BindingDomain, DomainObservation) {
	t.Helper()
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	observation := domainObservationForTest(kind, 21)
	ack, err := store.CreateDomainAttestedLocalSourceWithCapture(t.Context(), 7, library.ID, library.Revision, "create-domain", "test-workspace", []byte("/videos"), func(context.Context) (BindingProof, DomainObservation, error) {
		return domainProofForTest(10), observation, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	binding, err := store.GetLibrarySourceByID(t.Context(), 7, ack.BindingID)
	if err != nil {
		t.Fatal(err)
	}
	domain, err := store.GetSelectedBindingDomain(t.Context(), 7, binding.Ref())
	if err != nil {
		t.Fatal(err)
	}
	return store, binding, domain, observation
}

func coordinatorForDomainTest(t *testing.T, store *Store) *ScanCoordinator {
	t.Helper()
	coordinator, err := store.AcquireScanCoordinator(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := coordinator.Release(); err != nil {
			t.Error(err)
		}
	})
	return coordinator
}

func TestDomainV2RebindReceiptAndLegacyConsumersFailClosed(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	_, lease := testScan(t, store, binding, "before-domain")
	input := testObservation("native-old", "/videos/one.mkv")
	input.NFOStatus = "parsed"
	input.NFO = testNFO(t, `<movie><title>Old private title</title><thumb>poster.jpg</thumb></movie>`)
	old := scanNFO(t, store, binding, lease, input, 1)
	variants, err := store.ListPlaybackVariants(t.Context(), 7, library.ID, old.ItemID, "", 10)
	if err != nil || len(variants.Items) != 1 {
		t.Fatal(variants, err)
	}
	oldVersion := binding.ProofEpoch
	observation := domainObservationForTest("mount_scoped", 1)
	ack, err := store.AttestDomainSourceWithCapture(t.Context(), 7, library.ID, binding.ID, binding.RootLocator, binding.Revision, "switch-v2", func(context.Context, *LibrarySource) (BindingProof, DomainObservation, error) {
		return domainProofForTest(9), observation, nil
	})
	if err != nil || ack.ProofVersion != 2 || ack.ProofEpoch != oldVersion+1 || ack.MountEpoch != 1 {
		t.Fatal(ack, err)
	}
	replay, err := store.AttestDomainSourceWithCapture(t.Context(), 7, library.ID, binding.ID, binding.RootLocator, binding.Revision, "switch-v2", func(context.Context, *LibrarySource) (BindingProof, DomainObservation, error) {
		return domainProofForTest(8), domainObservationForTest("path_observed", 12), nil
	})
	if err != nil || *ack != *replay {
		t.Fatal(ack, replay, err)
	}
	current, err := store.GetLibrarySourceByID(t.Context(), 7, binding.ID)
	if err != nil || current.Proof == nil || current.Proof.Version != 2 || current.Proof.RootIdentityKind != "" || len(current.Proof.RootIdentity) != 0 || current.Proof.MappingDigest[0] != 9 {
		t.Fatal(current, err)
	}
	var active, legacyVersion, oldFields int
	if err := store.db.QueryRow(`SELECT active_proof_version,proof_version,(mapping_digest IS NOT NULL)+(root_identity IS NOT NULL)+(attested_at IS NOT NULL) FROM library_source WHERE binding_id=?`, binding.ID).Scan(&active, &legacyVersion, &oldFields); err != nil || active != 2 || legacyVersion != 0 || oldFields != 0 {
		t.Fatal(active, legacyVersion, oldFields, err)
	}
	if _, err := store.EnqueueScan(t.Context(), 7, current.Ref(), "old-consumer", 1); !errors.Is(err, ErrBindingProofUnsupported) {
		t.Fatalf("legacy scan accepted v2: %v", err)
	}
	if _, err := store.GetObservationRevision(t.Context(), 7, current.Ref(), []byte("native-old")); !errors.Is(err, ErrBindingProofUnsupported) {
		t.Fatalf("legacy observation accepted v2: %v", err)
	}
	if _, err := store.CommitScanObservation(t.Context(), 7, *lease, input, testCheckpoint(input, 2, 2)); !errors.Is(err, ErrRevisionConflict) {
		t.Fatalf("old scan not fenced: %v", err)
	}
	if _, err := store.ResolvePlayback(t.Context(), 7, library.ID, old.ItemID, variants.Items[0].EditionID, variants.Items[0].VariantID, binding.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("old playback survived rebind: %v", err)
	}
	if records, err := store.ListItemSourceAccess(t.Context(), 7, library.ID, old.ItemID, "", 20); err != nil || len(records.Items) != 0 {
		t.Fatalf("old source visible: %+v %v", records, err)
	}
	if records, err := store.ListItemMetadataSourceAccess(t.Context(), 7, library.ID, old.ItemID, "", 20); err != nil || len(records.Items) != 0 {
		t.Fatalf("old metadata visible: %+v %v", records, err)
	}
	// Simulate future/current-epoch rows before legacy readers are upgraded:
	// neither a matching epoch nor stale v1-shaped data is a v2 grant.
	if _, err := store.db.Exec(`UPDATE library_file SET binding_epoch=? WHERE source_file_id=?`, current.ProofEpoch, old.SourceFileID); err != nil {
		t.Fatal(err)
	}
	if _, err := store.db.Exec(`UPDATE metadata_input SET binding_epoch=? WHERE item_id=?`, current.ProofEpoch, old.ItemID); err != nil {
		t.Fatal(err)
	}
	if records, err := store.ListItemSourceAccess(t.Context(), 7, library.ID, old.ItemID, "", 20); err != nil || len(records.Items) != 0 {
		t.Fatalf("v2 source leaked to v1 consumer: %+v %v", records, err)
	}
	if records, err := store.ListItemMetadataSourceAccess(t.Context(), 7, library.ID, old.ItemID, "", 20); err != nil || len(records.Items) != 0 {
		t.Fatalf("v2 metadata leaked to v1 consumer: %+v %v", records, err)
	}
	item, err := store.GetCatalogItem(t.Context(), 7, library.ID, old.ItemID)
	if err != nil || item.Title != "" || item.FileCount != 0 {
		t.Fatalf("v2 catalog evidence leaked to v1 consumer: %+v %v", item, err)
	}
	detail, err := store.GetItemDetail(t.Context(), 7, library.ID, old.ItemID)
	if err != nil || len(detail.Fields) != 0 || len(detail.Artwork) != 0 {
		t.Fatalf("v2 metadata projected to v1 consumer: %+v %v", detail, err)
	}
	if _, err := store.ResolvePlayback(t.Context(), 7, library.ID, old.ItemID, variants.Items[0].EditionID, variants.Items[0].VariantID, binding.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("v2 playback leaked to v1 consumer: %v", err)
	}
	if _, err := store.db.Exec(`UPDATE library_source SET proof_version=1,mapping_digest=?,root_identity_kind='native',root_identity=X'01',attested_at=1 WHERE binding_id=?`, bytes.Repeat([]byte{1}, 32), binding.ID); err == nil {
		t.Fatal("simultaneous v1/v2 proof accepted")
	}
	if _, err := store.db.Exec(`UPDATE library_source SET v2_mapping_digest=? WHERE binding_id=?`, bytes.Repeat([]byte{2}, 32), binding.ID); err == nil {
		t.Fatal("v2 proof changed without epoch")
	}
	domain, err := store.GetSelectedBindingDomain(t.Context(), 7, current.Ref())
	if err != nil || !bytes.Equal(domain.MappingDigest, observation.MappingDigest) || domain.ProofEpoch != current.ProofEpoch {
		t.Fatal(domain, err)
	}
	if _, err := store.GetSelectedBindingDomain(t.Context(), 8, current.Ref()); !errors.Is(err, ErrNotFound) && !errors.Is(err, ErrForbidden) {
		t.Fatalf("foreign domain visible: %v", err)
	}
	encoded, err := json.Marshal(domain)
	if err != nil || strings.Contains(string(encoded), "videos") || len(encoded) != 2 {
		t.Fatalf("domain evidence serialized: %s %v", encoded, err)
	}
	if _, err := store.AttestSourceBindingWithOperation(t.Context(), 7, binding.ID, current.Revision, "back-v1", proofForTest(7)); err != nil {
		t.Fatal(err)
	}
	after, err := store.GetLibrarySourceByID(t.Context(), 7, binding.ID)
	if err != nil || after.Proof == nil || after.Proof.Version != 1 || after.ProofEpoch != current.ProofEpoch+1 {
		t.Fatal(after, err)
	}
	if _, err := store.GetSelectedBindingDomain(t.Context(), 7, current.Ref()); !errors.Is(err, ErrRevisionConflict) {
		t.Fatalf("old domain retained authority: %v", err)
	}
}

func TestDomainWeakContinuityOfflineRemountAndMappingFence(t *testing.T) {
	store, binding, domain, observation := createDomainForTest(t, "mount_scoped")
	coordinator := coordinatorForDomainTest(t, store)
	ctx := t.Context()
	before := tableCount(t, store, "outbox")
	if _, err := coordinator.MarkSelectedBindingDomainOffline(ctx, 7, binding.Ref(), domain.Ref(), "permission_denied"); !errors.Is(err, ErrInvalid) {
		t.Fatalf("permission denial classified offline: %v", err)
	}
	offline, err := coordinator.MarkSelectedBindingDomainOffline(ctx, 7, binding.Ref(), domain.Ref(), "timeout")
	if err != nil || offline.State != "offline" || offline.ResumeState != "available" || offline.MountEpoch != 1 {
		t.Fatal(offline, err)
	}
	if _, err := coordinator.ObserveSelectedBindingDomain(ctx, 7, binding.Ref(), domain.Ref(), observation); !errors.Is(err, ErrRevisionConflict) {
		t.Fatalf("stale domain revision accepted: %v", err)
	}
	restored, err := coordinator.ObserveSelectedBindingDomain(ctx, 7, binding.Ref(), offline.Ref(), observation)
	if err != nil || restored.State != "available" || restored.MountEpoch != 1 {
		t.Fatal(restored, err)
	}
	changed := cloneDomainObservation(observation)
	changed.AnchorDigest[0]++
	newEpoch, err := coordinator.ObserveSelectedBindingDomain(ctx, 7, binding.Ref(), restored.Ref(), changed)
	if err != nil || newEpoch.State != "revalidating" || newEpoch.MountEpoch != 2 {
		t.Fatal(newEpoch, err)
	}
	stable, err := coordinator.ObserveSelectedBindingDomain(ctx, 7, binding.Ref(), newEpoch.Ref(), changed)
	if err != nil || stable.State != "revalidating" || stable.MountEpoch != 2 || stable.Revision != newEpoch.Revision {
		t.Fatal(stable, err)
	}
	offline, err = coordinator.MarkSelectedBindingDomainOffline(ctx, 7, binding.Ref(), stable.Ref(), "unreachable")
	if err != nil || offline.ResumeState != "revalidating" {
		t.Fatal(offline, err)
	}
	restored, err = coordinator.ObserveSelectedBindingDomain(ctx, 7, binding.Ref(), offline.Ref(), changed)
	if err != nil || restored.State != "revalidating" || restored.MountEpoch != 2 {
		t.Fatal(restored, err)
	}
	mapped := cloneDomainObservation(changed)
	mapped.MappingDigest[0]++
	fenced, err := coordinator.ObserveSelectedBindingDomain(ctx, 7, binding.Ref(), restored.Ref(), mapped)
	if !errors.Is(err, ErrDomainRebindRequired) || fenced == nil || fenced.State != "rebind_required" || fenced.MountEpoch != 2 || !bytes.Equal(fenced.MappingDigest, observation.MappingDigest) {
		t.Fatal(fenced, err)
	}
	if _, err := coordinator.ObserveSelectedBindingDomain(ctx, 7, binding.Ref(), fenced.Ref(), observation); !errors.Is(err, ErrDomainRebindRequired) {
		t.Fatalf("automatic retarget accepted: %v", err)
	}
	if tableCount(t, store, "outbox") != before+6 {
		t.Fatal("domain changes were not atomically published")
	}
	if live, err := store.GetSelectedBindingDomain(ctx, 7, binding.Ref()); err != nil || live.State != "rebind_required" {
		t.Fatalf("rebind fence did not commit: %+v %v", live, err)
	}
	if _, err := store.db.Exec("UPDATE binding_domain SET mapping_digest=?,revision=revision+1 WHERE id=?", bytes.Repeat([]byte{9}, 32), domain.ID); err == nil {
		t.Fatal("domain target mapping mutated")
	}
	if _, err := store.db.Exec("UPDATE binding_domain SET state='available',resume_state='',revision=revision+1 WHERE id=?", domain.ID); err == nil {
		t.Fatal("rebind-required cleared without explicit rebind")
	}
}

func TestDomainDatabaseGuardsEvidenceAndCoordinatorFence(t *testing.T) {
	store, binding, domain, observation := createDomainForTest(t, "mount_scoped")
	coordinator := coordinatorForDomainTest(t, store)
	for _, statement := range []string{
		"UPDATE binding_domain SET root_evidence=zeroblob(32),revision=revision+1 WHERE id=?",
		"UPDATE binding_domain SET anchor_digest=zeroblob(32),revision=revision+1 WHERE id=?",
		"UPDATE binding_domain SET mount_epoch=mount_epoch+1,revision=revision+1 WHERE id=?",
	} {
		if _, err := store.db.Exec(statement, domain.ID); err == nil {
			t.Fatalf("unfenced domain update accepted: %s", statement)
		}
	}
	if err := coordinator.Release(); err != nil {
		t.Fatal(err)
	}
	if _, err := coordinator.ObserveSelectedBindingDomain(t.Context(), 7, binding.Ref(), domain.Ref(), observation); !errors.Is(err, ErrCoordinatorUnavailable) {
		t.Fatalf("released coordinator updated domain: %v", err)
	}
}

func TestDomainStrongContradictionAndWeakIsolationKey(t *testing.T) {
	store, binding, domain, observation := createDomainForTest(t, "native_generation")
	coordinator := coordinatorForDomainTest(t, store)
	changed := cloneDomainObservation(observation)
	changed.RootEvidence[0]++
	result, err := coordinator.ObserveSelectedBindingDomain(t.Context(), 7, binding.Ref(), domain.Ref(), changed)
	if !errors.Is(err, ErrDomainRebindRequired) || result == nil || result.State != "rebind_required" || result.MountEpoch != 1 {
		t.Fatal(result, err)
	}
	store2, binding2, original, originalObservation := createDomainForTest(t, "native_generation")
	coordinator2 := coordinatorForDomainTest(t, store2)
	weaker := cloneDomainObservation(originalObservation)
	weaker.EvidenceKind, weaker.RootEvidence, weaker.MountEvidence = "path_observed", nil, nil
	weakened, err := coordinator2.ObserveSelectedBindingDomain(t.Context(), 7, binding2.Ref(), original.Ref(), weaker)
	if err != nil || weakened.State != "revalidating" || weakened.MountEpoch != 2 {
		t.Fatal(weakened, err)
	}
	contradictory := cloneDomainObservation(originalObservation)
	contradictory.RootEvidence[0]++
	fenced, err := coordinator2.ObserveSelectedBindingDomain(t.Context(), 7, binding2.Ref(), weakened.Ref(), contradictory)
	if !errors.Is(err, ErrDomainRebindRequired) || fenced == nil || fenced.State != "rebind_required" {
		t.Fatalf("weaker observation erased strong contradiction: %+v %v", fenced, err)
	}
	locator := []byte{'/', 'v', '/', 0xff, '.', 'm', 'k', 'v'}
	key := UnverifiedDomainObjectKey(binding.ID, binding.ProofEpoch, domain.ID, 1, locator)
	for _, other := range [][]byte{
		UnverifiedDomainObjectKey(binding.ID, binding.ProofEpoch+1, domain.ID, 1, locator),
		UnverifiedDomainObjectKey(binding.ID, binding.ProofEpoch, domain.ID+"x", 1, locator),
		UnverifiedDomainObjectKey(binding.ID, binding.ProofEpoch, domain.ID, 2, locator),
		UnverifiedDomainObjectKey(binding.ID, binding.ProofEpoch, domain.ID, 1, []byte("/v/�.mkv")),
	} {
		if bytes.Equal(key, other) {
			t.Fatal("weak identity crossed a proof, domain, epoch or opaque locator")
		}
	}
}

func TestDomainTransitionRollsBackWhenOutboxFails(t *testing.T) {
	store, binding, domain, observation := createDomainForTest(t, "path_observed")
	coordinator := coordinatorForDomainTest(t, store)
	if _, err := store.db.Exec(`CREATE TRIGGER fail_domain_event BEFORE INSERT ON outbox WHEN NEW.kind='library.source.domain.changed' BEGIN SELECT RAISE(ABORT,'injected'); END`); err != nil {
		t.Fatal(err)
	}
	changed := cloneDomainObservation(observation)
	changed.AnchorDigest[0]++
	if _, err := coordinator.ObserveSelectedBindingDomain(t.Context(), 7, binding.Ref(), domain.Ref(), changed); err == nil {
		t.Fatal("domain transition succeeded without outbox")
	}
	current, err := store.GetSelectedBindingDomain(t.Context(), 7, binding.Ref())
	if err != nil || current.MountEpoch != domain.MountEpoch || current.Revision != domain.Revision || current.State != domain.State {
		t.Fatal(current, err)
	}
}

func TestVersionSevenDomainMigrationPreservesV1AndLegacy(t *testing.T) {
	path := filepath.Join(t.TempDir(), "v7.db")
	db, err := sql.Open("sqlite", databaseURL(path, "rwc"))
	if err != nil {
		t.Fatal(err)
	}
	old := &Store{db: db}
	err = old.WithTx(context.Background(), func(tx *sql.Tx) error {
		if _, err := tx.Exec(schemaV1 + schemaV2 + schemaV3 + schemaV4 + schemaV5BeforeIDs + schemaV5AfterIDs + schemaV6 + schemaV7); err != nil {
			return err
		}
		if err := addLibrary(context.Background(), tx, "old"); err != nil {
			return err
		}
		_, err := tx.Exec(`INSERT INTO source(id,owner_user_id,kind,configuration_ref,created_at) VALUES ('s1',7,'local','config',1),('s2',7,'local','config',1);
INSERT INTO library_source(library_id,source_id,root_locator,binding_id) VALUES ('old','s1',X'2F61','bind_v1'),('old','s2',X'2F62','bind_legacy');
UPDATE library_source SET proof_version=1,proof_epoch=1,mapping_digest=zeroblob(32),root_identity_kind='native',root_identity=X'0102',attested_at=1,revision=revision+1 WHERE binding_id='bind_v1';` + fmt.Sprintf("PRAGMA application_id=%d; PRAGMA user_version=7", applicationID))
		return err
	})
	if closeErr := old.Close(); err != nil || closeErr != nil {
		t.Fatal(err, closeErr)
	}
	store, err := Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = store.Close() }()
	v1, err := store.GetLibrarySourceByID(t.Context(), 7, "bind_v1")
	if err != nil || v1.Proof == nil || v1.Proof.Version != 1 || v1.ProofEpoch != 1 || !bytes.Equal(v1.Proof.RootIdentity, []byte{1, 2}) {
		t.Fatal(v1, err)
	}
	legacy, err := store.GetLibrarySourceByID(t.Context(), 7, "bind_legacy")
	if err != nil || legacy.Proof != nil || legacy.ProofEpoch != 0 {
		t.Fatal(legacy, err)
	}
	if tableCount(t, store, "binding_domain") != 0 {
		t.Fatal("migration silently attested old sources")
	}
	var violations int
	if err := store.db.QueryRow("SELECT count(*) FROM pragma_foreign_key_check").Scan(&violations); err != nil || violations != 0 {
		t.Fatal(violations, err)
	}
}
