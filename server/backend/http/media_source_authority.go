package fbhttp

import (
	"context"
	"io/fs"

	"github.com/Kkwans/nas-file-browser/backend/mediasource"
	"github.com/Kkwans/nas-file-browser/backend/settings"
	"github.com/Kkwans/nas-file-browser/backend/storage"
)

// This connection refers to the account's live Fileway workspace, not a native
// root or caller-supplied filesystem. Other source configurations need their own
// explicitly registered resolver; never silently interpret them as local access.
const localMediaWorkspaceConfiguration = "fileway-user-workspace-v1"

func resolveLocalMediaConnection(ctx context.Context, store *storage.Storage, server *settings.Server, ownerID uint, configurationRef string) (mediasource.LocalAuthority, error) {
	if configurationRef != localMediaWorkspaceConfiguration {
		return mediasource.LocalAuthority{}, fs.ErrPermission
	}
	return localMediaAuthority(store, server, ownerID)(ctx)
}

// localMediaAuthority resolves the SOURCE owner's current filesystem and rules.
// The authenticated viewer/request data is never replaced with the owner. The
// caller must separately validate library membership, binding and sharing policy.
// This resolver is deliberately reusable by scan batches and playback opens;
// keeping a previously resolved LocalAuthority is not a revocation strategy.
func localMediaAuthority(store *storage.Storage, server *settings.Server, ownerID uint) mediasource.ResolveLocalAuthority {
	return func(ctx context.Context) (mediasource.LocalAuthority, error) {
		if err := ctx.Err(); err != nil {
			return mediasource.LocalAuthority{}, err
		}
		if ownerID == 0 || store == nil || server == nil || store.Users == nil || store.Settings == nil {
			return mediasource.LocalAuthority{}, fs.ErrPermission
		}
		owner, err := store.Users.Get(server.Root, ownerID)
		if err != nil || owner == nil || owner.ID != ownerID || !owner.Perm.Download || owner.Fs == nil {
			return mediasource.LocalAuthority{}, fs.ErrPermission
		}
		configuration, err := store.Settings.Get()
		if err != nil || configuration == nil {
			return mediasource.LocalAuthority{}, fs.ErrPermission
		}
		if err := ctx.Err(); err != nil {
			return mediasource.LocalAuthority{}, err
		}
		// Reuse the exact existing rule/private-path evaluation without mutating the
		// original HTTP actor or serializing owner credentials into any catalog DTO.
		authority := &data{settings: configuration, server: server, user: owner}
		return mediasource.LocalAuthority{FS: owner.Fs, ServerRoot: server.Root, CanRead: true, Check: authority.Check}, nil
	}
}
