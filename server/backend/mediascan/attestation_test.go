package mediascan

import (
	"bytes"
	"context"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"testing"

	"github.com/spf13/afero"
)

func TestBindingProofDistinguishesOfflineScopeChangeAndReplacement(t *testing.T) {
	f := newScanFixture(t)
	authority, err := f.resolve(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	root := []byte("/media")
	proof, err := CaptureLocalBindingProof(t.Context(), 7, "fixture", root, authority)
	if err != nil {
		t.Fatal(err)
	}
	state, err := VerifyLocalBindingProof(t.Context(), 7, "fixture", root, authority, proof)
	if err != nil || state != BindingAvailable || len(proof.MappingDigest) != 32 || proof.RootIdentityKind != "native" {
		t.Fatalf("initial proof: %+v %q %v", proof, state, err)
	}
	other := filepath.Join(f.root, "other-workspace")
	if err := os.MkdirAll(filepath.Join(other, "media"), 0o700); err != nil {
		t.Fatal(err)
	}
	changed := authority
	changed.FS = afero.NewBasePathFs(afero.NewOsFs(), other)
	if _, err := VerifyLocalBindingProof(t.Context(), 7, "fixture", root, changed, proof); !errors.Is(err, ErrBindingChanged) {
		t.Fatalf("workspace remapping accepted: %v", err)
	}
	changed = authority
	changed.WorkspaceScope = []byte("/new-scope")
	if _, err := VerifyLocalBindingProof(t.Context(), 7, "fixture", root, changed, proof); !errors.Is(err, ErrBindingChanged) {
		t.Fatalf("owner scope change accepted: %v", err)
	}
	if err := os.Rename(filepath.Join(f.root, "media"), filepath.Join(f.root, "offline")); err != nil {
		t.Fatal(err)
	}
	state, err = VerifyLocalBindingProof(t.Context(), 7, "fixture", root, authority, proof)
	if err != nil || state != BindingOffline {
		t.Fatalf("proven root offline: %q %v", state, err)
	}
	if err := os.Mkdir(filepath.Join(f.root, "media"), 0o700); err != nil {
		t.Fatal(err)
	}
	if _, err := VerifyLocalBindingProof(t.Context(), 7, "fixture", root, authority, proof); !errors.Is(err, ErrBindingChanged) {
		t.Fatalf("replacement root accepted: %v", err)
	}
	denied := authority
	denied.Check = func(string) bool { return false }
	if _, err := VerifyLocalBindingProof(t.Context(), 7, "fixture", root, denied, proof); !errors.Is(err, fs.ErrPermission) {
		t.Fatalf("revoked root accepted: %v", err)
	}
	if _, err := VerifyLocalBindingProof(t.Context(), 7, "fixture", root, authority, nil); !errors.Is(err, ErrBindingUnverified) {
		t.Fatalf("legacy proof accepted: %v", err)
	}
}

func TestBindingProofAllowsWorkspaceRoot(t *testing.T) {
	f := newScanFixture(t)
	authority, err := f.resolve(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	proof, err := CaptureLocalBindingProof(t.Context(), 7, "fixture", []byte("/"), authority)
	if err != nil || proof == nil || len(proof.RootIdentity) == 0 || bytes.Equal(proof.MappingDigest, make([]byte, 32)) {
		t.Fatalf("workspace root cannot be attested: %+v %v", proof, err)
	}
	if _, err := VerifyLocalBindingProof(t.Context(), 7, "fixture", []byte("/"), authority, proof); err != nil {
		t.Fatal(err)
	}
}

func TestUnverifiedObjectKeyIncludesBindingAndEpoch(t *testing.T) {
	locator := []byte{'/', 'm', '/', 0xff, '.', 'm', 'k', 'v'}
	first, kind := objectKey(nil, "binding-a", 1, locator)
	nextEpoch, _ := objectKey(nil, "binding-a", 2, locator)
	otherBinding, _ := objectKey(nil, "binding-b", 1, locator)
	if kind != IdentityPathUnverified || bytes.Equal(first, nextEpoch) || bytes.Equal(first, otherBinding) {
		t.Fatal("unverified object identity crossed a binding or proof epoch")
	}
}
