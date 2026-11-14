package cmd

import (
	"bytes"
	"database/sql"
	"log"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/spf13/afero"
	"github.com/spf13/cobra"
	"github.com/spf13/viper"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/storage"
)

func TestMediaLibraryDisabledDoesNotCreateStorage(t *testing.T) {
	store := &storage.Storage{}
	close := startMediaLibrary(t.Context(), store, "")
	close()
	if store.MediaLibrary != nil {
		t.Fatal("disabled catalog was opened")
	}
	flag := rootCmd.Flags().Lookup("mediaLibraryDatabase")
	if flag == nil || flag.DefValue != "" || rootCmd.PersistentFlags().Lookup("mediaLibraryDatabase") != nil {
		t.Fatal("catalog flag must be disabled by default and server-only")
	}
}

func TestMediaLibraryLifecycleKeepsPrivateStateAndCloses(t *testing.T) {
	root := t.TempDir()
	database := filepath.Join(root, files.MediaLibraryDirectory, "catalog.db")
	store := &storage.Storage{}
	close := startMediaLibrary(t.Context(), store, database)
	t.Cleanup(close)
	if store.MediaLibrary == nil {
		t.Fatal("catalog did not open on native temporary storage")
	}
	if store.MediaArtworkCache == nil {
		t.Fatal("catalog artwork cache did not open")
	}
	if err := store.MediaArtworkCache.Store(t.Context(), "fixture-backdrop", []byte("cached")); err != nil {
		t.Fatal(err)
	}
	catalog := store.MediaLibrary
	if err := catalog.WithTx(t.Context(), func(tx *sql.Tx) error {
		_, err := tx.ExecContext(t.Context(), "SELECT 1")
		return err
	}); err != nil {
		t.Fatal(err)
	}
	fs := afero.NewBasePathFs(afero.NewOsFs(), root)
	for _, suffix := range []string{"", "-wal", "-shm"} {
		if !files.IsPrivateResourcePath(fs, "/"+files.MediaLibraryDirectory+"/catalog.db"+suffix) {
			t.Fatal("catalog family exposed", suffix)
		}
	}
	close()
	close()
	if store.MediaLibrary != nil {
		t.Fatal("closed catalog remains attached")
	}
	if store.MediaArtworkCache != nil {
		t.Fatal("closed catalog artwork cache remains attached")
	}
	if err := catalog.WithTx(t.Context(), func(*sql.Tx) error { return nil }); err == nil {
		t.Fatal("closed catalog still accepts transactions")
	}
	reopened := &storage.Storage{}
	t.Cleanup(startMediaLibrary(t.Context(), reopened, database))
	if reopened.MediaArtworkCache == nil {
		t.Fatal("artwork cache unavailable after reopen")
	}
	if got, found, err := reopened.MediaArtworkCache.Load(t.Context(), "fixture-backdrop"); err != nil || !found || string(got) != "cached" {
		t.Fatalf("artwork did not survive reopen: %v %v", found, err)
	}
}

func TestMediaLibraryFailureKeepsPrivacyAndLogsOnlySafeError(t *testing.T) {
	root := t.TempDir()
	directory := filepath.Join(root, files.MediaLibraryDirectory)
	if err := os.Mkdir(directory, 0700); err != nil {
		t.Fatal(err)
	}
	database := filepath.Join(directory, "private-runtime-marker.db")
	contents := []byte("foreign database; preserve unchanged")
	if err := os.WriteFile(database, contents, 0600); err != nil {
		t.Fatal(err)
	}
	alias := filepath.Join(root, "public-alias.db")
	if err := os.Link(database, alias); err != nil {
		t.Fatal(err)
	}
	var output bytes.Buffer
	previous := log.Writer()
	log.SetOutput(&output)
	t.Cleanup(func() { log.SetOutput(previous) })
	store := &storage.Storage{}
	close := startMediaLibrary(t.Context(), store, database)
	t.Cleanup(close)
	if store.MediaLibrary != nil {
		t.Fatal("foreign database opened")
	}
	if output.Len() == 0 || strings.Contains(output.String(), "private-runtime-marker") || strings.Contains(output.String(), root) || strings.Contains(output.String(), string(contents)) {
		t.Fatal("failure log missing or discloses private details", output.String())
	}
	if !files.IsPrivateResourcePath(afero.NewBasePathFs(afero.NewOsFs(), root), "/public-alias.db") {
		t.Fatal("initialization failure dropped private-file protection")
	}
	after, err := os.ReadFile(database)
	if err != nil || !bytes.Equal(after, contents) {
		t.Fatal("rejected database changed", err)
	}
}

func TestMediaLibraryRejectsNonDedicatedAndRemotePaths(t *testing.T) {
	root := t.TempDir()
	for _, database := range []string{"relative.db", "file:///catalog.db", `\\server\share\.fileway-media\catalog.db`, filepath.Join(root, "catalog.db")} {
		store := &storage.Storage{}
		startMediaLibrary(t.Context(), store, database)()
		if store.MediaLibrary != nil {
			t.Fatal("unsafe catalog opened", database)
		}
	}
	entries, err := os.ReadDir(root)
	if err != nil || len(entries) != 0 {
		t.Fatal("invalid configuration modified its parent", err)
	}
}

func TestCLIStoreDoesNotInitializeMediaLibrary(t *testing.T) {
	root := t.TempDir()
	cmd := &cobra.Command{Use: "catalog-cli-test"}
	cmd.Flags().String("config", filepath.Join(root, "absent.json"), "")
	cmd.Flags().String("database", filepath.Join(root, "accounts.db"), "")
	cmd.Flags().String("mediaLibraryDatabase", filepath.Join(root, files.MediaLibraryDirectory, "catalog.db"), "")
	called := false
	err := withViperAndStore(func(_ *cobra.Command, _ []string, _ *viper.Viper, store *store) error {
		called = true
		if store.MediaLibrary != nil || store.Users == nil || store.Settings == nil {
			t.Fatal("CLI storage contract changed")
		}
		_, err := store.Users.Gets("")
		return err
	}, storeOptions{allowsNoDatabase: true})(cmd, nil)
	if err != nil || !called {
		t.Fatal("legacy store command failed", err)
	}
	if _, err := os.Stat(filepath.Join(root, files.MediaLibraryDirectory)); !os.IsNotExist(err) {
		t.Fatal("CLI created the optional media directory", err)
	}
}
