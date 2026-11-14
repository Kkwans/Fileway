package cmd

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/storage"
)

func TestMediaProviderRejectsCombinedBackupAndInvalidHosts(t *testing.T) {
	parent := t.TempDir()
	db := filepath.Join(parent, files.MediaLibraryDirectory, "catalog.db")
	store := &storage.Storage{}
	t.Cleanup(startMediaLibrary(t.Context(), store, db))
	if store.MediaLibrary == nil {
		t.Fatal("catalog unavailable")
	}
	key := filepath.Join(filepath.Dir(db), "key.bin")
	if err := os.WriteFile(key, make([]byte, 32), 0o600); err != nil {
		t.Fatal(err)
	}
	startMediaProvider(store, db, key, "")()
	if store.MediaProviderVault != nil || store.TMDBHTTPClient != nil {
		t.Fatal("key shared the catalog backup directory")
	}
	file := filepath.Join(parent, "tmdb-hosts")
	if err := os.WriteFile(file, []byte("127.0.0.1 api.tmdb.org\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := readTMDBHosts(file); err == nil {
		t.Fatal("inappropriate address accepted")
	}
	if err := os.WriteFile(file, []byte("# TMDB only\n8.8.8.8 api.tmdb.org\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := readTMDBHosts(file); err != nil {
		t.Fatal(err)
	}
	if store.MediaLibrary == nil {
		t.Fatal("provider failure took the local library offline")
	}
}
