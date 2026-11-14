package cmd

import (
	"io"
	"log"
	"os"
	"path/filepath"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/mediaprovider"
	"github.com/Kkwans/nas-file-browser/backend/storage"
)

func startMediaProvider(store *storage.Storage, database, keyFile, hostsFile string) func() {
	close := func() {}
	if store.MediaLibrary == nil || keyFile == "" {
		return close
	}
	keyDir, keyErr := os.Stat(filepath.Dir(keyFile))
	catalogDir, dbErr := os.Stat(filepath.Dir(database))
	if keyErr != nil || dbErr != nil || os.SameFile(keyDir, catalogDir) {
		log.Print("Media provider unavailable: provider key needs a separate private directory")
		return close
	}
	unprotect, err := files.ProtectMediaLibraryDatabase(keyFile)
	if err != nil {
		log.Print("Media provider unavailable: cannot protect provider key directory")
		return close
	}
	close = func() { _ = unprotect() }
	vault, err := mediaprovider.LoadCredentialVault(keyFile)
	if err != nil {
		log.Print("Media provider unavailable: ", err)
		return close
	}
	var hosts mediaprovider.TMDBHosts
	if hostsFile != "" {
		hosts, err = readTMDBHosts(hostsFile)
		if err != nil {
			// The OS error may contain the private configuration path.
			log.Print("Media provider unavailable: cannot read TMDB HOST configuration")
			return close
		}
	}
	client := mediaprovider.NewTMDBHTTPClient(hosts)
	store.MediaProviderVault, store.TMDBHTTPClient = vault, client
	return func() { client.CloseIdleConnections(); _ = unprotect() }
}

func readTMDBHosts(name string) (mediaprovider.TMDBHosts, error) {
	file, err := os.Open(name)
	if err != nil {
		return mediaprovider.TMDBHosts{}, err
	}
	defer func() { _ = file.Close() }()
	body, err := io.ReadAll(io.LimitReader(file, 8193))
	if err != nil {
		return mediaprovider.TMDBHosts{}, err
	}
	return mediaprovider.ParseTMDBHosts(string(body))
}
