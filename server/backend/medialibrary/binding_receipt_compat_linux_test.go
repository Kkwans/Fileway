//go:build linux

package medialibrary

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestSourceReceiptReplayRejectsReplacedCatalog(t *testing.T) {
	directory := t.TempDir()
	path := filepath.Join(directory, "catalog.db")
	store, err := Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = store.Close() }()
	library := createLibrary(t, store, 7, "Films")
	if _, err := store.CreateAttestedLocalSourceWithOperation(t.Context(), 7, library.ID, library.Revision, "receipt", "local", []byte("/media"), proofForTest(1)); err != nil {
		t.Fatal(err)
	}
	if err := os.Rename(path, filepath.Join(directory, "previous.db")); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("replacement"), 0o600); err != nil {
		t.Fatal(err)
	}
	captures := 0
	_, err = store.CreateDomainAttestedLocalSourceWithCapture(t.Context(), 7, library.ID, library.Revision, "receipt", "local", []byte("/media"), func(context.Context) (BindingProof, DomainObservation, error) {
		captures++
		return domainProofForTest(1), domainObservationForTest("mount_scoped", 1), nil
	})
	if !errors.Is(err, ErrCatalogIdentityChanged) || captures != 0 {
		t.Fatalf("replaced catalog receipt accepted: captures=%d err=%v", captures, err)
	}
}
