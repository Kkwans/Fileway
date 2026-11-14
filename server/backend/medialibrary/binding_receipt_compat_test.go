package medialibrary

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"testing"
	"time"
)

// Build real v7 receipts in their original JSON/digest format, as if the v1
// server committed both operations before the client lost their responses.
func createVersionSevenReceiptFixture(t *testing.T) (string, sourceReceipt, sourceReceipt, string, string) {
	t.Helper()
	file := filepath.Join(t.TempDir(), "v7-receipts.db")
	db, err := sql.Open("sqlite", databaseURL(file, "rwc"))
	if err != nil {
		t.Fatal(err)
	}
	old := &Store{db: db}
	createAck := sourceReceipt{ID: "bind_v1", LibraryID: "old", SourceID: "source", AuthorizationRevision: 1, SourceCreatedAt: 1, Revision: 2, LibraryRevision: 3, ProofVersion: 1, ProofEpoch: 1, AttestedAt: 2}
	attestAck := createAck
	attestAck.Revision, attestAck.LibraryRevision, attestAck.ProofEpoch, attestAck.AttestedAt = 3, 4, 2, 3
	createJSON := `{"ID":"bind_v1","LibraryID":"old","SourceID":"source","AuthorizationRevision":1,"SourceCreatedAt":1,"Revision":2,"LibraryRevision":3,"ProofVersion":1,"ProofEpoch":1,"AttestedAt":2}`
	attestJSON := `{"ID":"bind_v1","LibraryID":"old","SourceID":"source","AuthorizationRevision":1,"SourceCreatedAt":1,"Revision":3,"LibraryRevision":4,"ProofVersion":1,"ProofEpoch":2,"AttestedAt":3}`
	createInput, _ := json.Marshal(struct {
		LibraryID        string
		ExpectedRevision int64
		ConfigurationRef string
		Root             []byte
	}{"old", 1, "local", []byte("/media")})
	attestInput, _ := json.Marshal(struct {
		BindingID string
		Revision  int64
	}{"bind_v1", 2})
	createDigest, attestDigest := sha256.Sum256(createInput), sha256.Sum256(attestInput)
	err = old.WithTx(context.Background(), func(tx *sql.Tx) error {
		if _, err := tx.Exec(schemaV1 + schemaV2 + schemaV3 + schemaV4 + schemaV5BeforeIDs + schemaV5AfterIDs + schemaV6 + schemaV7); err != nil {
			return err
		}
		if err := addLibrary(context.Background(), tx, "old"); err != nil {
			return err
		}
		if _, err := tx.Exec(`INSERT INTO source(id,owner_user_id,kind,configuration_ref,created_at) VALUES ('source',7,'local','local',1),('other',7,'local','local',1);
INSERT INTO library_source(library_id,source_id,root_locator,binding_id) VALUES ('old','source',X'2F6D65646961','bind_v1'),('old','other',X'2F6F74686572','bind_other');
UPDATE library_source SET proof_version=1,proof_epoch=1,mapping_digest=zeroblob(32),root_identity_kind='native',root_identity=X'01',attested_at=2,revision=revision+1 WHERE binding_id='bind_v1';
UPDATE library_source SET proof_version=1,proof_epoch=2,mapping_digest=zeroblob(32),root_identity_kind='native',root_identity=X'02',attested_at=3,revision=revision+1 WHERE binding_id='bind_v1';
UPDATE library SET revision=4 WHERE id='old';`); err != nil {
			return err
		}
		if _, err := tx.Exec(`INSERT INTO operation_receipt(actor_user_id,kind,operation_id,input_digest,result_json,created_at) VALUES (7,'source.create.local','lost-create',?,?,1),(7,'source.binding.attest','lost-attest',?,?,1)`, createDigest[:], createJSON, attestDigest[:], attestJSON); err != nil {
			return err
		}
		_, err := tx.Exec(fmt.Sprintf("PRAGMA application_id=%d; PRAGMA user_version=7", applicationID))
		return err
	})
	if closeErr := old.Close(); err != nil || closeErr != nil {
		t.Fatal(err, closeErr)
	}
	return file, createAck, attestAck, createJSON, attestJSON
}

func TestVersionSevenLostSourceReceiptsReplayAcrossProofVersions(t *testing.T) {
	file, createAck, attestAck, createJSON, attestJSON := createVersionSevenReceiptFixture(t)
	store, err := Open(t.Context(), file)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = store.Close() }()
	ctx := t.Context()
	captures := 0
	capture := func(context.Context) (BindingProof, DomainObservation, error) {
		captures++
		return BindingProof{}, DomainObservation{}, errors.New("source must not be sampled")
	}
	create, err := store.CreateDomainAttestedLocalSourceWithCapture(ctx, 7, "old", 1, "lost-create", "local", []byte("/media"), capture)
	if err != nil || *create != *bindingMutationFromReceipt(createAck) || create.ProofVersion != 1 || create.DomainID != "" || captures != 0 {
		t.Fatalf("v1 create receipt: %+v captures=%d err=%v", create, captures, err)
	}
	attest, err := store.AttestDomainSourceWithCapture(ctx, 7, "old", "bind_v1", []byte("/media"), 2, "lost-attest", func(context.Context, *LibrarySource) (BindingProof, DomainObservation, error) {
		captures++
		return BindingProof{}, DomainObservation{}, errors.New("source must not be sampled")
	})
	if err != nil || *attest != *bindingMutationFromReceipt(attestAck) || attest.ProofVersion != 1 || attest.DomainID != "" || captures != 0 {
		t.Fatalf("v1 attest receipt: %+v captures=%d err=%v", attest, captures, err)
	}
	if _, err := store.CreateDomainAttestedLocalSourceWithCapture(ctx, 7, "old", 1, "lost-create", "local", []byte("/other"), capture); !errors.Is(err, ErrRevisionConflict) {
		t.Fatalf("different create root: %v", err)
	}
	if _, err := store.CreateDomainAttestedLocalSourceWithCapture(ctx, 7, "old", 2, "lost-create", "local", []byte("/media"), capture); !errors.Is(err, ErrRevisionConflict) {
		t.Fatalf("different create revision: %v", err)
	}
	if _, err := store.CreateDomainAttestedLocalSourceWithCapture(ctx, 7, "old", 1, "lost-create", "different-config", []byte("/media"), capture); !errors.Is(err, ErrRevisionConflict) {
		t.Fatalf("different source configuration: %v", err)
	}
	if _, err := store.AttestDomainSourceWithCapture(ctx, 7, "old", "bind_v1", []byte("/other"), 2, "lost-attest", nil); !errors.Is(err, ErrRevisionConflict) {
		t.Fatalf("different attest root: %v", err)
	}
	if _, err := store.db.Exec(`UPDATE library_source SET root_locator=X'2F6F74686572' WHERE binding_id='bind_v1'`); err == nil {
		t.Fatal("binding root changed behind a v1 attest receipt")
	}
	if _, err := store.AttestDomainSourceWithCapture(ctx, 7, "old", "bind_other", []byte("/other"), 2, "lost-attest", nil); !errors.Is(err, ErrRevisionConflict) {
		t.Fatalf("different binding: %v", err)
	}
	if _, err := store.AttestDomainSourceWithCapture(ctx, 7, "old", "bind_v1", []byte("/media"), 2, "lost-create", nil); !errors.Is(err, ErrRevisionConflict) {
		t.Fatalf("different logical operation: %v", err)
	}
	if _, err := store.CreateDomainAttestedLocalSourceWithCapture(ctx, 8, "old", 1, "lost-create", "local", []byte("/media"), capture); err == nil || captures != 0 {
		t.Fatalf("foreign actor reused receipt or sampled source: captures=%d err=%v", captures, err)
	}
	current, err := store.GetLibrarySourceByID(ctx, 7, "bind_v1")
	if err != nil || current.Proof == nil || current.Proof.Version != 1 || current.ProofEpoch != 2 || !bytes.Equal(current.Proof.RootIdentity, []byte{2}) {
		t.Fatal(current, err)
	}
	if tableCount(t, store, "binding_domain") != 0 || tableCount(t, store, "operation_receipt") != 2 {
		t.Fatal("replay created a domain or receipt")
	}
	var savedCreate, savedAttest string
	if err := store.db.QueryRow(`SELECT result_json FROM operation_receipt WHERE actor_user_id=7 AND kind='source.create.local' AND operation_id='lost-create'`).Scan(&savedCreate); err != nil {
		t.Fatal(err)
	}
	if err := store.db.QueryRow(`SELECT result_json FROM operation_receipt WHERE actor_user_id=7 AND kind='source.binding.attest' AND operation_id='lost-attest'`).Scan(&savedAttest); err != nil {
		t.Fatal(err)
	}
	if savedCreate != createJSON || savedAttest != attestJSON {
		t.Fatal("legacy receipts were rewritten")
	}
}

func TestSourceReceiptsShareBusinessNamespaceInBothDirections(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	ctx := t.Context()
	root := []byte("/videos")
	v2, err := store.CreateDomainAttestedLocalSourceWithCapture(ctx, 7, library.ID, library.Revision, "v2-create", "local", root, func(context.Context) (BindingProof, DomainObservation, error) {
		return domainProofForTest(8), domainObservationForTest("mount_scoped", 11), nil
	})
	if err != nil {
		t.Fatal(err)
	}
	captures := 0
	legacy, err := store.CreateAttestedLocalSourceWithCapture(ctx, 7, library.ID, library.Revision, "v2-create", "local", root, func(context.Context) (BindingProof, error) {
		captures++
		return BindingProof{}, errors.New("must not capture")
	})
	if err != nil || *legacy != *v2 || legacy.ProofVersion != 2 || legacy.DomainID == "" || captures != 0 {
		t.Fatalf("v2 receipt replay via v1: %+v %+v %d %v", v2, legacy, captures, err)
	}
	if _, err := store.AttestDomainSourceWithCapture(ctx, 7, library.ID, v2.BindingID, root, v2.BindingRevision, "v2-create", nil); !errors.Is(err, ErrRevisionConflict) {
		t.Fatalf("create receipt accepted as attest: %v", err)
	}
	if _, err := store.UpdateLibraryWithOperation(ctx, 7, library.ID, v2.LibraryRevision, "v2-create", "Other"); !errors.Is(err, ErrRevisionConflict) {
		t.Fatalf("source ID accepted for other operation: %v", err)
	}
	if count := tableCount(t, store, "binding_domain"); count != 1 {
		t.Fatalf("unexpected domains: %d", count)
	}
	var active, oldVersion, oldFields int
	if err := store.db.QueryRow(`SELECT active_proof_version,proof_version,(mapping_digest IS NOT NULL)+(root_identity IS NOT NULL)+(attested_at IS NOT NULL) FROM library_source WHERE binding_id=?`, v2.BindingID).Scan(&active, &oldVersion, &oldFields); err != nil || active != 2 || oldVersion != 0 || oldFields != 0 {
		t.Fatal(active, oldVersion, oldFields, err)
	}
}

func TestLegacyUnattestedReceiptDoesNotBecomeV2Proof(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Legacy")
	old, err := store.CreateLocalSourceWithOperation(t.Context(), 7, library.ID, library.Revision, "legacy-create", "local", []byte("/videos"))
	if err != nil {
		t.Fatal(err)
	}
	called := false
	replayed, err := store.CreateDomainAttestedLocalSourceWithCapture(t.Context(), 7, library.ID, library.Revision, "legacy-create", "local", []byte("/videos"), func(context.Context) (BindingProof, DomainObservation, error) {
		called = true
		return BindingProof{}, DomainObservation{}, errors.New("must not sample")
	})
	if err != nil || replayed == nil || replayed.BindingID != old.ID || replayed.ProofVersion != 0 || replayed.ProofEpoch != 0 || replayed.DomainID != "" || called {
		t.Fatalf("legacy receipt upgraded: %+v called=%v err=%v", replayed, called, err)
	}
	current, err := store.GetLibrarySourceByID(t.Context(), 7, old.ID)
	if err != nil || current.Proof != nil || current.ProofEpoch != 0 || tableCount(t, store, "binding_domain") != 0 {
		t.Fatal(current, err)
	}
}

func TestSourceReceiptCommittedDuringConcurrentCaptureFailure(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	started, release := make(chan struct{}), make(chan struct{})
	type outcome struct {
		ack *DomainMutationResult
		err error
	}
	done := make(chan outcome, 1)
	go func() {
		ack, err := store.CreateDomainAttestedLocalSourceWithCapture(t.Context(), 7, library.ID, library.Revision, "concurrent-create", "local", []byte("/videos"), func(context.Context) (BindingProof, DomainObservation, error) {
			close(started)
			<-release
			return BindingProof{}, DomainObservation{}, errors.New("transient source failure")
		})
		done <- outcome{ack, err}
	}()
	select {
	case <-started:
	case result := <-done:
		close(release)
		t.Fatalf("first capture did not start: %+v %v", result.ack, result.err)
	case <-time.After(5 * time.Second):
		close(release)
		t.Fatal("first capture timed out")
	}
	winner, err := store.CreateDomainAttestedLocalSourceWithCapture(t.Context(), 7, library.ID, library.Revision, "concurrent-create", "local", []byte("/videos"), func(context.Context) (BindingProof, DomainObservation, error) {
		return domainProofForTest(5), domainObservationForTest("mount_scoped", 6), nil
	})
	close(release)
	if err != nil {
		<-done
		t.Fatal(err)
	}
	result := <-done
	if result.err != nil || result.ack == nil || *result.ack != *winner {
		t.Fatalf("committed concurrent receipt not recovered: %+v %v", result.ack, result.err)
	}
	if tableCount(t, store, "binding_domain") != 1 || tableCount(t, store, "operation_receipt") != 1 {
		t.Fatal("concurrent request duplicated the operation")
	}
}
