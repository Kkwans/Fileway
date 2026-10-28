package storage

import (
	"github.com/Kkwans/nas-file-browser/backend/auth"
	"github.com/Kkwans/nas-file-browser/backend/deviceauth"
	"github.com/Kkwans/nas-file-browser/backend/favorites"
	"github.com/Kkwans/nas-file-browser/backend/history"
	"github.com/Kkwans/nas-file-browser/backend/playback"
	"github.com/Kkwans/nas-file-browser/backend/recent"
	"github.com/Kkwans/nas-file-browser/backend/settings"
	"github.com/Kkwans/nas-file-browser/backend/share"
	"github.com/Kkwans/nas-file-browser/backend/tags"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
	"github.com/Kkwans/nas-file-browser/backend/transfers"
	"github.com/Kkwans/nas-file-browser/backend/trash"
	"github.com/Kkwans/nas-file-browser/backend/uploads"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

// Storage is a storage powered by a Backend which makes the necessary
// verifications when fetching and saving data to ensure consistency.
type Storage struct {
	Devices   *deviceauth.Service
	Users     users.Store
	Share     *share.Storage
	Auth      *auth.Storage
	Settings  *settings.Storage
	Favorites *favorites.Storage
	History   *history.Storage
	Playback  *playback.Storage
	Recent    *recent.Storage
	Tags      *tags.Storage
	Tasks     *tasks.Storage
	Transfers *transfers.Storage
	Trash     *trash.Storage
	Uploads   *uploads.Storage
}
