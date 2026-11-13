package cmd

import (
	"context"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/spf13/afero"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/storage"
)

// startMediaLibrary is deliberately called only by the server command. Existing
// CLI operations continue opening just their original account database.
func startMediaLibrary(ctx context.Context, store *storage.Storage, database string) func() {
	if database == "" {
		return func() {}
	}
	// Treat configuration as a filesystem path, never a SQLite URI or DSN.
	// The reserved parent must be dedicated; do not hide arbitrary user folders.
	if !filepath.IsAbs(database) || strings.HasPrefix(database, `\\`) || strings.HasPrefix(database, "//") ||
		!strings.EqualFold(filepath.Base(filepath.Dir(database)), files.MediaLibraryDirectory) {
		log.Print("Media library unavailable: configure an absolute local path inside a dedicated .fileway-media directory")
		return func() {}
	}
	directory := filepath.Dir(database)
	if _, err := os.Lstat(directory); os.IsNotExist(err) && !localMediaDirectory(filepath.Dir(directory)) {
		log.Print("Media library unavailable: private directory requires supported local storage")
		return func() {}
	}
	if err := os.Mkdir(directory, 0700); err != nil && !os.IsExist(err) {
		log.Print("Media library unavailable: cannot prepare private directory")
		return func() {}
	}
	unprotect, err := files.ProtectMediaLibraryDatabase(database)
	if err != nil {
		log.Print("Media library unavailable: cannot protect private directory")
		return func() {}
	}
	var catalog *medialibrary.Store
	var once sync.Once
	close := func() {
		once.Do(func() {
			if catalog != nil {
				if err := catalog.Close(); err != nil {
					log.Print("Media library shutdown: catalog close failed")
				}
				store.MediaLibrary = nil
			}
			if err := unprotect(); err != nil {
				log.Print("Media library shutdown: private directory close failed")
			}
		})
	}
	// Reuse the native handle-based local-filesystem gate. This rejects network
	// filesystems and unsupported local providers before SQLite creates a WAL.
	if !localMediaDirectory(directory) {
		log.Print("Media library unavailable: private directory is not supported local storage")
		return close
	}
	if info, err := os.Lstat(database); err != nil && !os.IsNotExist(err) || err == nil && !info.Mode().IsRegular() {
		log.Print("Media library unavailable: catalog must be a regular local file")
		return close
	}
	catalog, err = medialibrary.Open(ctx, database)
	if err != nil {
		// Paths, driver details and database contents must not enter logs.
		log.Print("Media library unavailable: catalog initialization failed; file service remains available")
		return close
	}
	store.MediaLibrary = catalog
	return close
}

func localMediaDirectory(directory string) bool {
	local, err := files.OpenSubtitleResource(afero.NewBasePathFs(afero.NewOsFs(), directory), directory, "/", true, false)
	if err != nil {
		return false
	}
	return local.Close() == nil
}
