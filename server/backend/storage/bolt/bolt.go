package bolt

import (
	"errors"

	"github.com/asdine/storm/v3"

	"github.com/Kkwans/nas-file-browser/backend/auth"
	"github.com/Kkwans/nas-file-browser/backend/deviceauth"
	"github.com/Kkwans/nas-file-browser/backend/favorites"
	"github.com/Kkwans/nas-file-browser/backend/history"
	"github.com/Kkwans/nas-file-browser/backend/playback"
	"github.com/Kkwans/nas-file-browser/backend/recent"
	"github.com/Kkwans/nas-file-browser/backend/settings"
	"github.com/Kkwans/nas-file-browser/backend/share"
	"github.com/Kkwans/nas-file-browser/backend/storage"
	"github.com/Kkwans/nas-file-browser/backend/tags"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
	"github.com/Kkwans/nas-file-browser/backend/transfers"
	"github.com/Kkwans/nas-file-browser/backend/trash"
	"github.com/Kkwans/nas-file-browser/backend/uploads"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

// NewStorage creates a storage.Storage based on Bolt DB.
func NewStorage(db *storm.DB) (*storage.Storage, error) {
	userStore := users.NewStorage(usersBackend{db: db})
	if err := migrateUserListingPreferences(db); err != nil {
		return nil, err
	}
	shareStore := share.NewStorage(shareBackend{db: db})
	settingsStore := settings.NewStorage(settingsBackend{db: db})
	authStore := auth.NewStorage(authBackend{db: db}, userStore)
	favoriteBackend := favoritesBackend{db: db}
	tagBackend := tagsBackend{db: db}
	if err := migrateMetadataOwners(db, favoriteBackend, tagBackend); err != nil {
		return nil, err
	}
	favoriteStore := favorites.NewStorage(favoriteBackend)
	historyStore := history.NewStorage(historyBackend{db: db})
	playbackStore := playback.NewStorage(playbackBackend{db: db})
	recentStore := recent.NewStorage(recentBackend{db: db})
	tagStore := tags.NewStorage(tagBackend)
	taskStore := tasks.NewStorage(taskBackend{db: db})
	transferStore := transfers.NewStorage(transferBackend{db: db})
	trashStore := trash.NewStorage(trashBackend{db: db})
	uploadStore := uploads.NewStorage(uploadBackend{db: db})
	deviceStore, err := deviceauth.New(deviceAuthorizationBackend{db: db})
	if err != nil {
		return nil, err
	}

	err = save(db, "version", 9)
	if err != nil {
		return nil, err
	}

	return &storage.Storage{
		Devices:   deviceStore,
		Auth:      authStore,
		Users:     userStore,
		Share:     shareStore,
		Settings:  settingsStore,
		Favorites: favoriteStore,
		History:   historyStore,
		Playback:  playbackStore,
		Recent:    recentStore,
		Tags:      tagStore,
		Tasks:     taskStore,
		Transfers: transferStore,
		Trash:     trashStore,
		Uploads:   uploadStore,
	}, nil
}

func migrateMetadataOwners(db *storm.DB, favoriteBackend favoritesBackend, tagBackend tagsBackend) error {
	var allUsers []*users.User
	if err := db.All(&allUsers); err != nil {
		if errors.Is(err, storm.ErrNotFound) {
			return nil
		}
		return err
	}
	userIDs := make([]uint, 0, len(allUsers))
	for _, user := range allUsers {
		userIDs = append(userIDs, user.ID)
	}
	if err := favoriteBackend.migrateOwners(userIDs); err != nil {
		return err
	}
	return tagBackend.migrateOwners(userIDs)
}
