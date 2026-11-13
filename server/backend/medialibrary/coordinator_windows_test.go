//go:build windows

package medialibrary

import (
	"errors"
	"path/filepath"
	"testing"
)

func TestCoordinatorDriveAndVolumeGUIDAliases(t *testing.T) {
	path := filepath.Join(t.TempDir(), "catalog.db")
	first, err := Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := first.Close(); err != nil {
			t.Error(err)
		}
	}()
	second, err := Open(t.Context(), first.identity.canonical)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := second.Close(); err != nil {
			t.Error(err)
		}
	}()
	coordinator, err := first.AcquireScanCoordinator(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := second.AcquireScanCoordinator(t.Context()); !errors.Is(err, ErrCoordinatorUnavailable) {
		t.Fatal(err)
	}
	if err := coordinator.Release(); err != nil {
		t.Fatal(err)
	}
	coordinator, err = second.AcquireScanCoordinator(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if err := coordinator.Release(); err != nil {
		t.Fatal(err)
	}
}
